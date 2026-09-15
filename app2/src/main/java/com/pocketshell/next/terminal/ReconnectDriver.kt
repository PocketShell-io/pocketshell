package com.pocketshell.next.terminal

import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How often the reconnect countdown is republished. */
private const val COUNTDOWN_TICK_MS = 1_000L

/** Shown when the ladder is exhausted. Names the way out, which is Retry. */
private const val GAVE_UP_MESSAGE =
    "Could not reconnect to the session. Tap Retry to try again."

/**
 * The reconnect ladder's driving half (issue #2684 extraction from
 * [SessionViewModel]): walks the policy ([ReconnectController]) — one dial per
 * [ReconnectController.Decision.RetryAfter] rung, each wait republished as the
 * countdown and gated on the foreground — and owns the one-ladder-at-a-time
 * job. The policy decides how long to wait; this file is the loop that waits,
 * dials and gives up.
 */
internal class ReconnectDriver(
    private val scope: CoroutineScope,
    private val policy: ReconnectController,
    private val foreground: ForegroundSignal,

    /**
     * The screen's one terminal, if it exists. Read once per ladder run: a run
     * with no terminal to keep alive silently ends (its caller only starts a
     * ladder once a terminal exists, so this is the defensive path).
     */
    private val emulatorOrNull: () -> TerminalSession?,

    /** One dial, the same attach path the first attach uses: [SessionAttacher.attach]. */
    private val attachOnce: suspend () -> AttachOutcome,

    /** Republishes the countdown: [SessionUiState.Reconnecting] with what is LEFT of the wait. */
    private val publish: (attempt: Int, retryInMs: Long, emulator: TerminalSession) -> Unit,

    /** Terminal banners: a refused attach's message, or the give-up message. */
    private val fail: (String) -> Unit,
) {

    private var job: Job? = null

    /** True while a ladder job exists — [SessionViewModel]'s auto-restart gate. */
    val hasJob: Boolean get() = job != null

    /**
     * Runs (or re-runs) the ladder from its first rung.
     *
     * The previous run is cancelled AND joined inside the new coroutine rather
     * than fire-and-forget, so a Retry tap or a foreground return can never
     * leave two ladders dialling the same session at once.
     */
    fun restart() {
        val previous = job
        job = scope.launch {
            previous?.cancelAndJoin()
            runLadder()
        }
    }

    /** Cancels the ladder and forgets it, so a later [restart] starts from rung 0. */
    fun cancel() {
        job?.cancel()
        job = null
    }

    private suspend fun runLadder() {
        val emulator = emulatorOrNull() ?: return
        var attempt = 0
        while (true) {
            when (val decision = policy.decide(attempt)) {
                ReconnectController.Decision.GiveUp -> return fail(GAVE_UP_MESSAGE)

                is ReconnectController.Decision.RetryAfter -> {
                    awaitRetryWindow(decision.attempt, decision.delayMs, emulator)
                    when (val outcome = attachOnce()) {
                        AttachOutcome.Attached -> return
                        is AttachOutcome.Refused -> return fail(outcome.message)
                        is AttachOutcome.Unreachable -> attempt = decision.attempt + 1
                    }
                }
            }
        }
    }

    /**
     * Waits out one rung, publishing the countdown as it goes, and returns only
     * with the app in the foreground.
     *
     * The foreground check is the FIRST thing each turn and the last thing
     * before returning, so neither the countdown nor the dial that follows it
     * can happen behind the launcher (D21). A backgrounded app therefore parks
     * here for as long as it takes, showing the reconnect banner it will still
     * be showing when the user comes back.
     */
    private suspend fun awaitRetryWindow(attempt: Int, delayMs: Long, emulator: TerminalSession) {
        var remaining = delayMs
        while (true) {
            foreground.awaitForeground()
            publish(attempt, remaining, emulator)
            if (remaining <= 0) return
            val step = minOf(remaining, COUNTDOWN_TICK_MS)
            delay(step)
            remaining -= step
        }
    }
}
