package com.pocketshell.next.terminal

import com.pocketshell.core.transport.CloseReason
import com.pocketshell.core.transport.ConnectResult
import com.pocketshell.core.transport.HostConnection
import com.pocketshell.core.transport.PtyChannel
import com.pocketshell.core.transport.TransportState
import com.pocketshell.next.connect.ConnectionsRegistry
import com.pocketshell.next.hostcli.HostCliClientFactory
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How long the end-of-session message waits for the remote's exit
 * status. Short: the two events are effectively simultaneous, and this
 * is only a ceiling on how long a server that sends no status at all
 * can delay the decision to reconnect.
 */
internal const val EXIT_STATUS_GRACE_MS = 2_000L

/** What one pass of [SessionAttacher.attach] can come back with. */
internal sealed interface AttachOutcome {

    /** Attached; [SessionUiState.Live] is on screen. */
    data object Attached : AttachOutcome

    /**
     * The host said no in a way another dial cannot fix (an unconfirmed
     * host key, a session that is not there). Ends the ladder.
     */
    data class Refused(val message: String) : AttachOutcome

    /** Could not reach the host this time. The ladder's business. */
    data class Unreachable(val message: String) : AttachOutcome
}

/**
 * The attach core of the session lifecycle (issue #2684 extraction from
 * [SessionViewModel]): the whole first-attach path, and with it ownership of
 * the live channel — [channel], [bridge], [connection], [watchJob],
 * [pumpScope] — and of the first-wins sequencing ([settleEnd] /
 * [releaseChannel]) that retires it, whichever of the three end events fires
 * first. The screen keeps the emulator, the banners and the ladder wiring;
 * this is the transport-side half of the same invariants.
 */
internal class SessionAttacher(
    private val registry: ConnectionsRegistry,
    private val clients: HostCliClientFactory,

    /**
     * The screen's scope: home of the per-attach watchers and of the hop
     * [onOutputEnded] makes back onto state.
     */
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val mainDispatcher: CoroutineDispatcher,

    /** Publishes [SessionUiState.Live] once the pumps are running. */
    private val onLive: (TerminalSession) -> Unit,

    /**
     * The screen half of a settled end: [SessionViewModel] maps [status] /
     * [finalClose] onto either the failure banner or the reconnect ladder.
     * Called at most once per channel, after the first-wins check in
     * [settleEnd] has retired it.
     */
    private val onEnded: (status: Int?, finalClose: Boolean) -> Unit,
) {

    /**
     * Owns the two bridge pumps. Separate from [scope] because the
     * pumps must not run on the main thread: the output pump collects the SSH
     * channel's frames and hops to [mainDispatcher] only for the bounded slice
     * it applies to the emulator, and the input pump writes to the channel.
     * Both belong on [dispatcher] (an IO pool). Cancelled in [shutdown].
     */
    private val pumpScope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

    var channel: PtyChannel? = null
        private set

    var bridge: TerminalPtyBridge? = null
        private set

    /**
     * The connection [channel] is currently attached through (issue #2477).
     *
     * Kept only so a channel that ends with NO exit status can be told apart
     * from a genuine network drop: a deliberate close tears the PTY down the
     * same way a dead socket does (no clean exit-status), but it also flips
     * THIS field's state to [TransportState.Closed] rather than
     * [TransportState.Lost], carrying the [CloseReason] that says which kind of
     * deliberate it was. See [isFinalClose].
     */
    var connection: HostConnection? = null
        private set

    private var watchJob: Job? = null

    /**
     * One full attach: connection → attach command → PTY → pumps →
     * [Live][SessionUiState.Live].
     *
     * The single attach path, shared by [SessionViewModel.open] and the
     * reconnect ladder. It always asks [ConnectionsRegistry] for the
     * connection rather than holding one, which is what makes a reconnect use
     * a FRESH transport: a spent `HostConnection` never self-heals, and the
     * registry treats a dead-but-stored entry as absent and dials a new one.
     *
     * [resolveEmulator] supplies the one [TerminalSession] to adopt; it is
     * consulted only after the connection and the attach command have
     * succeeded — exactly where the ViewModel's fallback emulator used to be
     * resolved — and the screen stores back whatever it returns.
     *
     * [ptyGeometry] supplies the size the PTY opens at, and is evaluated at
     * PTY-open execution — inside [openPty], after the dial — not at dial
     * entry: the screen's real geometry is reported by its first layout,
     * which on a device lands DURING the dial, and the U-5 contract (see
     * [SessionViewModel.onResized]) is that a size reported mid-dial still
     * opens the PTY at the right size instead of being lost.
     */
    suspend fun attach(
        hostId: Long?,
        sessionId: String?,
        sessionLabel: String?,
        ptyGeometry: () -> TerminalCells,
        resolveEmulator: () -> TerminalSession,
    ): AttachOutcome {
        val host = hostId ?: return AttachOutcome.Refused("No host to attach to.")
        val sessionName = sessionLabel ?: return AttachOutcome.Refused("No session to attach to.")

        val connection = when (val result = registry.getOrConnect(host)) {
            is ConnectResult.Connected -> result.connection

            is ConnectResult.NeedsTrust -> return AttachOutcome.Refused(
                "This host's key still needs to be confirmed. Open it from the " +
                    "host list to review the key.",
            )

            is ConnectResult.Failed -> return AttachOutcome.Unreachable(result.message)
        }

        // Issue #2572: the id is the identity when present — attach resolves a
        // name OR an id prefix, and only the id survives a rename.
        val attachHandle = sessionId ?: sessionName
        val command = runCatching { clients.create(connection).attachCommand(attachHandle) }
            .getOrElse { failure ->
                return AttachOutcome.Refused(
                    "Could not build the attach command: " +
                        SessionEndMessages.describe(failure),
                )
            }

        val emulator = resolveEmulator()

        val pty = try {
            openPty(connection, command, ptyGeometry)
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            return AttachOutcome.Unreachable(
                "Could not attach to \"$sessionName\": " + SessionEndMessages.describe(failure),
            )
        }

        val pump = TerminalPtyBridge(
            pty = pty,
            emulator = emulator,
            scope = pumpScope,
            mainDispatcher = mainDispatcher,
            onOutputEnded = { onOutputEnded(pty) },
        )
        channel = pty
        bridge = pump
        this.connection = connection
        pump.start()
        onLive(emulator)

        // Two more observers, on the other end of the channel. The output
        // stream, the channel close and the transport's own state are three
        // separate events and any of them can be the one that arrives first —
        // a stream torn down without a close, a close whose stream never
        // completed, or a transport that reported the drop before either.
        // [settleEnd] is first-wins on the channel identity, so whichever fires
        // decides and the others are no-ops.
        watchJob = scope.launch {
            launch {
                val status = pty.exit.await()
                settleEnd(pty, status, finalClose = status == null && isFinalClose())
            }
            launch {
                // EVERY terminal transport state, not just [TransportState.Lost]
                // (issue #2487): a connection dropped by the D21 grace window
                // settles to [TransportState.Closed], and a watcher that only
                // ever woke for `Lost` would sit here until the PTY's own exit
                // happened to resolve — or forever, if it never did. What the
                // state MEANS is then the same question everywhere else asks.
                val ended = connection.state.first {
                    it is TransportState.Lost || it is TransportState.Closed
                }
                settleEnd(pty, status = null, finalClose = ended.endsTheSession())
            }
        }
        return AttachOutcome.Attached
    }

    /**
     * True when [connection] was closed because someone ASKED for it to end
     * (issues #2477 and #2487) — the one close that means this screen is over.
     *
     * A PTY that ends with no exit status ordinarily means the link dropped —
     * worth a reconnect. A deliberate close tears the channel down the exact
     * same way (no clean exit-status), so [TransportState] is the only place
     * the difference survives. But "deliberate" is not one thing:
     *
     * - [CloseReason.Requested] — a test's own end-of-test hygiene
     *   (`ConnectionsRegistry.closeAll()`, run while this screen's watcher is
     *   still alive), and so would a future "disconnect" action. Redialling
     *   here does not reconnect anything the user asked for; it opens a BRAND
     *   NEW connection nobody is watching, orphaned in the registry until the
     *   next background/grace cycle finds it "live" and holds it open for a
     *   session that no longer has a screen (exactly what stranded J06's
     *   `backgroundingWithNoOpenSessionShowsNoHoldAndNoNotification` on a shared
     *   full-suite run — issue #2477). So: the screen ends.
     *
     * - [CloseReason.GraceExpired] — the D21 background window elapsing, which
     *   is the app deliberately dropping the LINK, with the remote session
     *   still running on the host. Issue #2477's version of this check read
     *   `Closed` alone and swept this case up with the one above, on the stated
     *   (and wrong) assumption that "nothing in production calls close today":
     *   every 90-second background does, through
     *   [HostConnection.scheduleGraceClose]. That turned the single most common
     *   daily journey — pocket the phone, come back later — into a false
     *   "the connection was closed" error over a live session (issue #2487).
     *   So: reconnect, exactly like a dropped link.
     */
    fun isFinalClose(): Boolean = connection?.state?.value?.endsTheSession() == true

    /**
     * Whether a terminal [TransportState] means the SESSION is over, rather
     * than just this attach. Only a [CloseReason.Requested] close does;
     * [TransportState.Lost] and a [CloseReason.GraceExpired] close are both
     * "the link went away under a session that is still there".
     */
    private fun TransportState.endsTheSession(): Boolean =
        this is TransportState.Closed && reason == CloseReason.Requested

    private suspend fun openPty(
        connection: HostConnection,
        command: String,
        geometry: () -> TerminalCells,
    ): PtyChannel =
        withContext(dispatcher) {
            // Read at PTY-open execution, not dial entry — the exact read
            // point the pre-extraction ViewModel had, and the reason the size
            // comes down as a supplier: a resize the screen reported while
            // the dial above was in flight must be what the remote starts at
            // (the U-5 mid-dial contract).
            val cells = geometry()
            connection.openPty(command = command, cols = cells.cols, rows = cells.rows)
        }

    /**
     * The bridge's output flow completed. Fires off the pump dispatcher, so it
     * hops back onto the ViewModel scope to touch state.
     *
     * The exit status is waited for BRIEFLY rather than skipped: the stream and
     * the channel close land within milliseconds of each other, and the status
     * is what tells an ended session apart from a dropped link. The wait is
     * bounded because a server that never sends one must not hold up the
     * reconnect.
     */
    private fun onOutputEnded(ended: PtyChannel) {
        if (channel !== ended) return
        scope.launch {
            val status = withTimeoutOrNull(EXIT_STATUS_GRACE_MS) { ended.exit.await() }
            settleEnd(ended, status, finalClose = status == null && isFinalClose())
        }
    }

    /**
     * The channel [ended] is over. [status] is the remote's exit status, or
     * null when there was none — which is the whole discriminator between an
     * ended session and a dropped link (see the [SessionViewModel] class doc).
     * [finalClose] (issues #2477/#2487, see [isFinalClose]) is the finer
     * discriminator WITHIN "no status": a connection someone ASKED to close is
     * reported as ended, the same as a clean remote exit, rather than
     * redialled.
     *
     * The spent channel is retired the SAME way in both outcomes, through
     * [releaseChannel]. That is not tidiness: "the screen has failed" is not
     * "this ViewModel is dead" — a [SessionUiState.Failed] screen still offers
     * [SessionViewModel.retryNow], which reattaches onto THIS terminal. Ending
     * the bridge with [TerminalPtyBridge.stop] here (as this did before issue
     * #2487) closed the then-vendored session's byte queues one-way — those
     * queues are gone with issue #2566's replacement session — so that Retry
     * attached successfully onto a permanently unwritable emulator: `Live` on
     * screen, a frozen last frame, and every keystroke and output frame
     * dropped. Only [shutdown] — where the ViewModel really is over — stops
     * the bridge for good.
     *
     * First-wins on the channel identity: the two watchers this class launches
     * per attach, the output pump's end callback and a write that failed
     * mid-flight can all observe the same death, and whichever fires first
     * decides; the others are no-ops.
     */
    fun settleEnd(ended: PtyChannel, status: Int?, finalClose: Boolean = false) {
        if (channel !== ended) return
        // Both watchers of `attach`'s watchJob have now been overtaken by
        // events: the one that just fired settled us here, and its sibling has
        // nothing left to report either. [releaseChannel] cancels them — a
        // CLOSED connection's state is terminal and sticky, so a watcher left
        // waiting on it would park forever.
        releaseChannel()
        onEnded(status, finalClose)
    }

    /**
     * Retires the spent channel and its pumps, leaving the [TerminalSession]
     * itself untouched.
     *
     * The single retire path for EVERY way an attach can end (issue #2487):
     * a drop, a clean remote exit and a requested close all leave a screen that
     * can still be reattached from — by the ladder or by
     * [SessionViewModel.retryNow] — so none of them may take the emulator down
     * with them. [TerminalPtyBridge.stop] only releases the session's input
     * sink, so the grid (the last frame the user was reading) survives and the
     * next bridge adopts the same session by starting on it. This is also the
     * ONE place that sequences that hand-off: the spent bridge is always
     * stopped before the next one starts, so the stop cannot clear a sink its
     * successor installed.
     */
    private fun releaseChannel() {
        watchJob?.cancel()
        watchJob = null
        bridge?.stop()
        bridge = null
        val spent = channel
        channel = null
        if (spent != null) {
            pumpScope.launch(NonCancellable) { runCatching { spent.close() } }
        }
    }

    /**
     * The final teardown, run from [SessionViewModel.onCleared]: stops the
     * pumps, closes the PTY, leaves the CONNECTION alone — the registry owns
     * that, and a second screen on the same host must not lose its transport
     * because this one was popped. Unlike [releaseChannel] this is the one
     * place the bridge is stopped with the ViewModel really over, and the
     * pump scope dies with it.
     */
    fun shutdown() {
        watchJob?.cancel()
        watchJob = null
        bridge?.stop()
        bridge = null
        connection = null
        val open = channel
        channel = null
        if (open != null) {
            // The scope this runs on is about to die with the ViewModel, so the
            // close cannot be launched there. NonCancellable on the pump scope,
            // which is cancelled immediately afterwards, keeps the channel
            // teardown from being dropped half-done.
            pumpScope.launch(NonCancellable) { runCatching { open.close() } }
        }
        pumpScope.cancel()
    }
}
