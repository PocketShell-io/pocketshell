package com.pocketshell.next.terminal

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay

/**
 * How long the layout has to hold still before the remote is told the
 * new size.
 *
 * Longer than the gap between two frames of an IME inset animation
 * (measured at 13–30 ms on a Pixel-class AVD), so one gesture produces
 * one `window-change`; short enough that the settled size reaches the
 * remote well inside the time it takes a thumb to leave the keyboard
 * and the eye to look at the pane.
 */
private const val RESIZE_SETTLE_MS = 120L

/**
 * The resize settle loop (issue #2684 extraction from [SessionViewModel]):
 * the single owner of `pty.resize`. Collects the stream of sizes a phone
 * viewport reports, waits for the layout to go quiet, and sends exactly one
 * `window-change` per settled layout to the live bridge.
 */
internal class ResizeSettler(
    /** The live bridge, when there is one; consulted at send time only. */
    private val liveBridge: () -> TerminalPtyBridge?,
) {

    /**
     * Sizes the view has reported and the remote has not been told about yet.
     *
     * Conflated, and drained by ONE consumer ([run]), because a
     * viewport change on a phone is not one size — it is a stream of them. A
     * single keyboard open reports a new size on every frame of the IME's
     * inset animation (measured on a Pixel-class AVD: twelve sizes in ~200 ms
     * for one keyboard toggle, and again on the way down). Sending a
     * `window-change` per frame hammers the remote with a resize storm it then
     * has to repaint for, and — the reason this is a defect and not just waste
     * — it puts twelve fire-and-forget requests on the wire in a burst with no
     * acknowledgement of any of them. `window-change` carries `want_reply =
     * FALSE` by protocol (RFC 4254 §6.7), so the app cannot tell which one the
     * remote actually applied; a burst that ends up applied out of order, or
     * whose last request is coalesced away under load, leaves the remote at an
     * INTERMEDIATE size while the screen's `cols`/`rows` say otherwise — and
     * because the reporter skips a size it believes it already sent, nothing
     * ever corrects it. That is a phone-visible stuck-wrong-size terminal: the
     * emulator grid and the remote pty disagree, so the host paints a screen
     * that does not fit the grid it is painted into (observed on this journey
     * as a 63x24 emulator against a 63x49 remote pane).
     *
     * One request per settled layout removes both the storm and the window in
     * which a lost request can go unnoticed.
     */
    private val requests = Channel<TerminalCells>(Channel.CONFLATED)

    /** Remembers a reported size; [run] sends it once the layout settles. */
    fun request(cells: TerminalCells) {
        requests.trySend(cells)
    }

    /**
     * The single owner of `pty.resize`: takes the newest reported size, waits
     * for the layout to go quiet, and sends exactly that one.
     *
     * Being a single consumer is half the point — two resize coroutines racing
     * to the transport can reach it in the opposite order to the one the
     * viewport moved in, which lands the remote on a stale size with the app
     * none the wiser. Waiting for quiet is the other half: it collapses the
     * whole animation into one `window-change`, sent when the remote is not
     * already busy repainting the previous eleven.
     *
     * Runs for the ViewModel's whole life and never for a size the screen has
     * moved past — a size that arrives while a resize is being written is
     * simply the next iteration's input.
     */
    suspend fun run() {
        for (first in requests) {
            var size = first
            while (true) {
                delay(RESIZE_SETTLE_MS)
                // Anything newer means the viewport is still moving; take it
                // and wait again rather than resizing the remote mid-animation.
                size = requests.tryReceive().getOrNull() ?: break
            }
            val live = liveBridge() ?: continue
            try {
                live.resize(size.cols, size.rows)
            } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                // A resize that cannot reach the remote is not worth tearing the
                // screen down for: the emulator half already applied, so the
                // pane still renders and the next output frame will reveal a
                // genuinely dead channel through the output pump instead.
            }
        }
    }
}
