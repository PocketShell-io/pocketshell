package com.pocketshell.next.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.next.connect.ConnectionsRegistry
import com.pocketshell.next.di.IoDispatcher
import com.pocketshell.next.di.MainDispatcher
import com.pocketshell.next.hostcli.HostCliClientFactory
import com.termux.terminal.TerminalSession
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One attached session (rewrite tasks U-4 and U-7, journeys J03 and J05) — the
 * point of the app.
 *
 * ## Line budget (plan §C.3)
 *
 * The plan caps this file at 600 lines. Issue #2495 extracted the UI state
 * ([SessionUiState]), the end-of-session wording ([SessionEndMessages]) and
 * the stop flow ([SessionStopper]); issue #2684 extracted the lifecycle core
 * that round deferred — the attach path and the first-wins settle/release
 * sequencing ([SessionAttacher]), the ladder driving ([ReconnectDriver]) and
 * the resize settle loop ([ResizeSettler]). Report, don't grow: do not add
 * here without a cohesion reason for a new collaborator.
 *
 * ## The whole lifecycle, in one place
 *
 * [open] does four things: get the host's live connection from
 * [ConnectionsRegistry], ask [HostCliClientFactory] for the attach command,
 * open a PTY channel running it, and pump that channel into the vendored
 * terminal emulator through a [TerminalPtyBridge]. [SessionAttacher.attach] is
 * that whole sequence, and the reconnect loop re-runs the SAME function —
 * there is no second, subtly different attach path, which is what kept the
 * pre-rewrite client's reconnect and its first connect from ever agreeing.
 *
 * There is no lease, no refcount, no pool and no shadow session tree. The
 * reconnect supervisor is [ReconnectController] — a ladder and a give-up.
 *
 * ## What counts as a drop
 *
 * A resolved exit STATUS means the remote command really ran and exited: the
 * session is over (you typed `exit`, or `sessions attach` said "no such
 * session" with exit 3) and the screen says so. A channel that ends with NO
 * status, or a `TransportState.Lost`, is the link going away underneath a
 * session that is still alive on the host — that is the reconnect case. The
 * distinction is the transport's own: sshj carries `exit-status` on the channel
 * close, and a dropped socket has none to carry.
 *
 * A third case (issue #2477) also ends with no status: the connection this
 * screen is watching being closed deliberately, by something other than a
 * network failure. The channel dies the same way a dropped socket's does, so
 * the discriminator has to come from the transport's own
 * `TransportState.Closed` — and, since issue #2487, from the `CloseReason` it
 * carries, because "closed on purpose" is two opposite cases: a close someone
 * ASKED for ends this screen, while the D21 grace window expiring is the app
 * letting go of a link whose remote session is still alive, and is a reconnect
 * exactly like a dropped one. See [SessionAttacher.isFinalClose].
 *
 * ## Nothing runs while the app is away
 *
 * Every rung of the ladder — the countdown as well as the dial — is gated on
 * [ForegroundSignal]. D21's "no background work" is not a soft target here: a
 * backgrounded app that kept dialling would be the reconnect storm the rewrite
 * exists to delete. Coming back to the foreground resets the ladder and tries
 * at once, as does [retryNow].
 *
 * ## Trust is not answered here
 *
 * A `ConnectResult.NeedsTrust` becomes [SessionUiState.Failed] with a message
 * pointing at the host list, exactly as the session tree does (task U-3), and
 * it is NOT retried: two screens able to write the trust store is two places a
 * host key can be accepted, and the host list is the one that owns that
 * decision.
 *
 * ## Size and budget
 *
 * The PTY opens at [TerminalPtyBridge.DEFAULT_COLS] x
 * [TerminalPtyBridge.DEFAULT_ROWS] — the size a remote shell assumes when
 * nobody has said otherwise — because at `open()` time no view has been laid
 * out and therefore no real geometry exists; [onResized] is the single entry
 * point for a new size once the view knows its font metrics, and
 * [ResizeSettler] the single path from there to `pty.resize`.
 *
 * The rewrite plan caps this file at 600 lines and the public surface at
 * [uiState], [open], [sendBytes], [retryNow], [onResized] and [onCleared].
 * Nothing here is annotated for tests: the seams are the constructor
 * parameters, and the unit suite drives the real class over a scripted
 * `FakeHostConnection` and a fake foreground signal.
 */
@HiltViewModel
class SessionViewModel @Inject constructor(
    private val registry: ConnectionsRegistry,
    private val clients: HostCliClientFactory,
    private val reconnect: ReconnectController,
    private val foreground: ForegroundSignal,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
    @MainDispatcher private val mainDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow<SessionUiState>(SessionUiState.Connecting)

    val uiState: StateFlow<SessionUiState> = _uiState.asStateFlow()

    private val _sendFailures = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val sendFailures = _sendFailures.asSharedFlow()

    /**
     * One-shot: the host accepted the kill, so the screen should pop back to
     * the tree rather than sit on a dead PTY. Cleared by
     * [consumeLeaveAfterStop] before the navigation runs.
     */
    private val _leaveAfterStop = MutableStateFlow(false)
    val leaveAfterStop: StateFlow<Boolean> = _leaveAfterStop.asStateFlow()

    /** Why the last Stop failed, if it did. Null when there is nothing to say. */
    private val _stopFailure = MutableStateFlow<String?>(null)
    val stopFailure: StateFlow<String?> = _stopFailure.asStateFlow()

    /** The stop (kill) flow, extracted verbatim under issue #2495. */
    private val stopper = SessionStopper(registry, clients)

    /**
     * The attach core (issue #2684 extraction): one attach pass, the live
     * channel it produces, and the first-wins end sequencing that retires it.
     * The callbacks are the screen half of the contract: go [Live] once the
     * pumps run, and map a settled end onto the banner or the ladder below.
     */
    private val attacher = SessionAttacher(
        registry = registry,
        clients = clients,
        scope = viewModelScope,
        dispatcher = dispatcher,
        mainDispatcher = mainDispatcher,
        onLive = { emulator -> _uiState.value = SessionUiState.Live(emulator) },
        onEnded = { status, finalClose -> onChannelEnded(status, finalClose) },
    )

    /**
     * The ladder's driving half (issue #2684 extraction): one dial per rung
     * behind the foreground gate, one ladder at a time. It dials [attachOnce]
     * and publishes this screen's [SessionUiState.Reconnecting] and failure
     * banners.
     */
    private val ladder = ReconnectDriver(
        scope = viewModelScope,
        policy = reconnect,
        foreground = foreground,
        emulatorOrNull = { terminal },
        attachOnce = { attachOnce() },
        publish = { attempt, retryInMs, emulator ->
            _uiState.value = SessionUiState.Reconnecting(attempt, retryInMs, emulator)
        },
        fail = { message -> fail(message) },
    )

    /** The resize settle loop (issue #2684 extraction). */
    private val settler = ResizeSettler(liveBridge = { attacher.bridge })

    private var attachJob: Job? = null
    private var stopJob: Job? = null
    private var automaticReconnectEnabled: Boolean = true

    /**
     * The ONE emulator front end for this screen's whole life.
     *
     * Created by [open] and never replaced: a reattach that built a second
     * [TerminalSession] would hand the screen an empty grid, which is exactly
     * the "terminal cleared itself while reconnecting" symptom this task exists
     * to prevent.
     */
    private var terminal: TerminalSession? = null

    private var hostId: Long? = null

    /**
     * The session's stable host id (issue #2572), when the route carried one.
     * This — not the name — is what attach resolves against, so a rename on
     * the host cannot strand this screen or silently retarget it to whichever
     * session took the old name. Null degrades to the pre-#2572 name-keyed
     * behavior for a host that listed no id.
     */
    private var sessionId: String? = null

    /** The session's own name, kept so an end-of-session message can say which. */
    private var sessionLabel: String? = null

    private var cols: Int = TerminalPtyBridge.DEFAULT_COLS
    private var rows: Int = TerminalPtyBridge.DEFAULT_ROWS

    init {
        viewModelScope.launch { settler.run() }

        // Coming back to the app is a reason to try NOW, on a fresh ladder: the
        // wait the loop is parked on was sized for a network blip, not for
        // however long the phone was in a pocket.
        viewModelScope.launch {
            foreground.isForeground.drop(1).filter { it }.collect {
                if (automaticReconnectEnabled && _uiState.value is SessionUiState.Reconnecting) {
                    ladder.restart()
                }
            }
        }
    }

    /**
     * Attaches to [sessionName] on [hostId].
     *
     * [sessionId] (issue #2572) is the identity when present: `sessions
     * attach` resolves a display name OR an id prefix, and the id survives a
     * rename, so the attach goes out with the id and the screen can never be
     * silently retargeted to a different session that took the old name. A
     * session that is gone fails LOUDLY instead.
     *
     * Idempotent by design: the screen calls it from a `LaunchedEffect`, which
     * re-runs on configuration change and on returning to a recomposed route,
     * and a second attach would open a second PTY on the same session.
     * A repeat call after a failure is also ignored — [retryNow] is the retry.
     */
    fun open(hostId: Long, sessionName: String, sessionId: String? = null) {
        if (attachJob != null) return
        this.hostId = hostId
        this.sessionLabel = sessionName
        this.sessionId = sessionId?.takeIf { it.isNotBlank() }
        // Built before the dial so every later state — including a reconnect
        // that starts before the first attach ever landed — has a terminal to
        // show, and so `terminal` is never null once the screen is open.
        this.terminal = createRemoteTerminalSession(cols = cols, rows = rows)
        attachJob = viewModelScope.launch {
            // A FIRST attach that cannot reach the host is a failure, not a
            // reconnect episode: there is nothing to reconnect TO yet, and a
            // ladder here would hide a wrong hostname behind 18 seconds of
            // countdown. The ladder starts only after a session was live.
            when (val outcome = attachOnce()) {
                AttachOutcome.Attached -> Unit
                is AttachOutcome.Refused -> fail(outcome.message)
                is AttachOutcome.Unreachable -> fail(outcome.message)
            }
        }
    }

    /**
     * Sends raw bytes to the remote PTY. Never throws.
     *
     * Used by the screen for anything that is not a keystroke the vendored
     * terminal view already handles itself (that input path goes straight into
     * the session's own queue and out through the bridge), and by the composer
     * and hotkey panel for everything they send.
     *
     * One rule for every input (issue #2578): at the
     * [SessionUiState.Reconnecting] banner there is no channel, and these bytes
     * take the same held-input path a keystroke there takes —
     * [TerminalSession.write] parks them in the emulator's pending buffer and
     * the next attach's sink flushes them, in order, to the new PTY. A held
     * send is NOT a failed send: no [sendFailures] fires, so the composer keeps
     * its cleared draft instead of restoring a duplicate the user could send
     * twice. The one report from this path is a batch with no room to hold
     * WHOLE — reported undelivered rather than truncated to the part that fits.
     *
     * Every other channel-less moment ([SessionUiState.Failed], or the Live
     * race below) still reports [sendFailures]: there is no queued attach to
     * run them.
     */
    fun sendBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val reconnecting = _uiState.value as? SessionUiState.Reconnecting
        if (reconnecting != null) {
            // TerminalSession.write truncates to the room left, which would
            // silently deliver the prefix of a prompt; whole batch or nothing.
            if (reconnecting.terminal.pendingInputRoom() < bytes.size) {
                _sendFailures.tryEmit(Unit)
                return
            }
            reconnecting.terminal.write(bytes, 0, bytes.size)
            return
        }
        val target = attacher.channel ?: run {
            // A caller may observe Live just before the channel is retired by
            // the reconnect watcher. Report that race to the composer instead
            // of silently dropping the write after it cleared its draft.
            _sendFailures.tryEmit(Unit)
            return
        }
        viewModelScope.launch {
            try {
                target.write(bytes)
            } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                // A PTY write only throws on a channel that is already gone, so
                // this is a link-down report like any other and goes through the
                // one place that decides between "ended" and "reconnect". Making
                // it its own failure message would mean a drop noticed by typing
                // ended the screen while a drop noticed by the output pump
                // reconnected.
                _sendFailures.tryEmit(Unit)
                val status = withTimeoutOrNull(EXIT_STATUS_GRACE_MS) { target.exit.await() }
                attacher.settleEnd(
                    target,
                    status,
                    finalClose = status == null && attacher.isFinalClose(),
                )
            }
        }
    }

    /**
     * The user asking for another go — from the reconnect banner or from a
     * failure. Resets the ladder to its first rung and tries immediately.
     *
     * Ignored while [SessionUiState.Live] (nothing to retry) and while
     * [SessionUiState.Connecting] (the first attach is still in flight, and a
     * second one would open a second PTY).
     */
    fun retryNow() {
        when (_uiState.value) {
            is SessionUiState.Reconnecting, is SessionUiState.Failed -> ladder.restart()
            SessionUiState.Connecting, is SessionUiState.Live -> Unit
        }
    }

    /** Applies Settings → Connections without making the setting a dead control. */
    fun setAutomaticReconnectEnabled(enabled: Boolean) {
        automaticReconnectEnabled = enabled
        if (!enabled) {
            ladder.cancel()
        } else if (_uiState.value is SessionUiState.Reconnecting && !ladder.hasJob) {
            ladder.restart()
        }
    }

    /**
     * `pocketshell sessions kill -- NAME` for the session this screen is
     * attached to. On success the route pops back to the tree; on failure a
     * short banner is shown and the terminal stays.
     */
    fun stopSession() {
        if (stopJob?.isActive == true) return
        if (sessionLabel == null) return
        _stopFailure.value = null
        stopJob = viewModelScope.launch { runStop() }
    }

    /** Clears the one-shot leave signal. Called by the route BEFORE it pops. */
    fun consumeLeaveAfterStop() {
        _leaveAfterStop.value = false
    }

    /**
     * Reports the terminal's real size in character cells.
     *
     * Called by the screen whenever the vendored view recomputes its geometry —
     * which, during an IME or rotation animation, is once per frame. The size
     * is remembered immediately (so the next attach opens the PTY at it even
     * mid-dial or mid-reconnect) but the REMOTE is told only once the layout
     * settles; see [ResizeSettler] for why one request per settled layout
     * rather than one per frame.
     */
    fun onResized(cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0) return
        if (cols == this.cols && rows == this.rows) return
        this.cols = cols
        this.rows = rows
        // No bridge yet (dialling, or reconnecting): the remembered size is
        // what the next attach opens the PTY at, so there is nothing to send.
        if (attacher.bridge == null) return
        settler.request(TerminalCells(cols = cols, rows = rows))
    }

    /**
     * Detaches. Stops the pumps, closes the PTY, leaves the CONNECTION alone —
     * the registry owns that, and a second screen on the same host must not
     * lose its transport because this one was popped.
     */
    override fun onCleared() {
        attachJob?.cancel()
        attachJob = null
        ladder.cancel()
        stopJob?.cancel()
        stopJob = null
        attacher.shutdown()
        super.onCleared()
    }

    // --- attach --------------------------------------------------------------

    /**
     * One pass of [SessionAttacher.attach] with this screen's current identity.
     * The identity is read at dial time, so a rename or a Retry between two
     * rungs lands on the next dial's state. The geometry deliberately is NOT:
     * it goes down as a supplier read at PTY-open execution — after the dial —
     * because on a real device the first layout reports its size DURING the
     * dial (the state is still `Connecting`), and that mid-dial report must be
     * what the remote starts at; see [onResized] and [SessionAttacher.attach].
     */
    private suspend fun attachOnce(): AttachOutcome =
        attacher.attach(
            hostId = hostId,
            sessionId = sessionId,
            sessionLabel = sessionLabel,
            ptyGeometry = { TerminalCells(cols = cols, rows = rows) },
            resolveEmulator = { emulatorForAttach() },
        )

    /**
     * The emulator an attach adopts: the screen's one terminal, or — the
     * defensive path [SessionAttacher.attach] used to carry — a fresh one
     * stored back into [terminal] when the screen somehow has none yet.
     */
    private fun emulatorForAttach(): TerminalSession =
        terminal ?: createRemoteTerminalSession(cols = cols, rows = rows).also { terminal = it }

    // --- reconnect -----------------------------------------------------------

    /**
     * The link went away under a live session: keep the last frame, say so, and
     * start the ladder. The spent channel has already been retired by
     * [SessionAttacher.settleEnd], the only path that reaches here.
     */
    private fun beginReconnect() {
        val emulator = terminal ?: return fail(SessionEndMessages.ended(sessionLabel, null))
        // Said immediately, before the first rung, so a user coming back to the
        // screen never sees a stale "attached" over a dead session.
        _uiState.value = SessionUiState.Reconnecting(attempt = 0, retryInMs = 0, terminal = emulator)
        if (automaticReconnectEnabled) ladder.restart()
    }

    /**
     * The screen half of a settled end ([SessionAttacher] owns the first-wins
     * check and the channel retirement): a resolved status or a close someone
     * asked for ends the screen with the matching banner; everything else —
     * the no-status drop — is a reconnect.
     */
    private fun onChannelEnded(status: Int?, finalClose: Boolean) {
        if (status != null || finalClose) {
            fail(
                if (finalClose) SessionEndMessages.closed(sessionLabel)
                else SessionEndMessages.ended(sessionLabel, status),
            )
            return
        }
        beginReconnect()
    }

    private fun fail(message: String) {
        _uiState.value = SessionUiState.Failed(message)
    }

    /**
     * Stops this screen's session, through [SessionStopper] (issue #2495
     * extraction): the resolver-and-kill mechanics live there; this maps the
     * outcome onto this screen's banner and one-shot leave signal.
     */
    private suspend fun runStop() {
        val host = hostId ?: return
        val label = sessionLabel ?: return
        when (val outcome = stopper.stop(host, sessionId, label)) {
            StopOutcome.Stopped -> {
                _stopFailure.value = null
                _leaveAfterStop.value = true
            }
            is StopOutcome.Failed -> _stopFailure.value = outcome.message
        }
    }
}
