package com.pocketshell.next.terminal

import com.termux.terminal.TerminalSession

/**
 * What the session screen can be showing.
 *
 * [Reconnecting] is a first-class state rather than a flavour of [Failed]
 * because the two look nothing alike to a user: one keeps the last frame on
 * screen under a countdown and comes back by itself, the other is over.
 */
sealed interface SessionUiState {

    /** Dialling, resolving the attach command, or opening the PTY. */
    data object Connecting : SessionUiState

    /**
     * Attached. [terminal] is the live vendored emulator front end the screen
     * renders; it is carried on the state rather than exposed as a second
     * ViewModel property so that "there is a terminal to draw" and "we are
     * attached" cannot disagree.
     */
    data class Live(val terminal: TerminalSession) : SessionUiState

    /**
     * The link went away and a fresh attach is on the ladder (task U-7).
     *
     * [attempt] is 0-based, exactly as [ReconnectController] counts.
     * [retryInMs] is what is LEFT of the current wait and ticks down once a
     * second, so the screen can render a live countdown while staying a pure
     * function of this state. (The plan sketched an absolute `nextRetryAtMs`;
     * rendering that needs a clock AND a ticking timer inside a composable, and
     * an unbounded composable timer is the classic never-idle hang under both
     * Robolectric and instrumented Compose tests. The remaining-time form moves
     * the tick to the one place already driven by a virtual clock in tests.)
     *
     * [terminal] is the SAME emulator instance the session was [Live] on: the
     * host repaints on reattach, so there is deliberately no client-side snapshot or
     * reseed — the last frame simply stays on screen, under the banner, until
     * new bytes arrive. Carrying it here rather than letting the screen remember
     * the last live one keeps the screen stateless.
     */
    data class Reconnecting(
        val attempt: Int,
        val retryInMs: Long,
        val terminal: TerminalSession,
    ) : SessionUiState

    /** Never attached, the session ended, or the ladder ran out. [message] is user-facing. */
    data class Failed(val message: String) : SessionUiState
}
