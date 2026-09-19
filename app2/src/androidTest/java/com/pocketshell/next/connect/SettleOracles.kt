package com.pocketshell.next.connect

import android.os.SystemClock
import android.view.View
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * Deterministic settle oracles for the two wall-clock settle points the
 * #2776 quarantine was raised over (issue #2781): window FOCUS after a
 * launch/transition, and the TERMINAL VIEWPORT after an IME/rotation change.
 *
 * Both replace "wait a fixed number of milliseconds and hope" with an
 * observable signal, polled inside the suite's standard bounded loop
 * ([awaitIdle] drives the choreographer between polls, so a changed layout is
 * visible on the next read; [SETTLE_POLL_MS] is only the poll rhythm):
 *
 *  - **Focus settle** — [awaitWindowFocus]. Espresso-rooted interactions
 *    (`RootViewPicker`) blind-wait up to 10 s for "the root of the view
 *    hierarchy to have window focus and not request layout" and then throw
 *    `RootViewWithoutFocusException` — exactly how J21's first-connect
 *    journey died on the hosted runner (run 35335062619 attempt 1, quarantined
 *    by 3259ceb0a). The same precondition, observed instead of hoped for:
 *    the journey waits until the activity's decor view reports
 *    [android.view.View.hasWindowFocus] — the settle signal the window focus
 *    path itself exposes — and only then interacts. If the window genuinely
 *    never takes focus, this fails loudly naming the settle point instead of
 *    failing opaquely inside Espresso's root picker.
 *
 *  - **Input focus** — [awaitInputFocus]. The same precondition one level
 *    down, for injected KEY events rather than Espresso view interactions:
 *    `Instrumentation.sendStringSync` / `sendKeyDownUpSync` hand their events
 *    to the platform `InputDispatcher`, which delivers them to whatever the
 *    FOCUSED WINDOW says is focused — and defers them when no window has
 *    focus at all. A `View.requestFocus()` followed by `waitForIdleSync()`
 *    observes neither half: `requestFocus` is a request whose answer is
 *    `hasFocus()`, and main-looper idle is not window focus. The gap drops a
 *    PREFIX of a typed line (the events dispatched before focus arrives) and
 *    leaves the rest, which is why it reads as a corrupt command rather than
 *    as a missing one — issue #2789's `echo po` (7 chars) and #1854's
 *    `printf` → `tf` (4 chars) are the same defect.
 *
 *  - **Size agreement** — [awaitImeViewportAck] / [awaitViewSizeStable]. The
 *    handshake between the IME and the hosted view, observed at the point the
 *    #887/#2533 resize path actually acts on: the IME inset reaches its
 *    expected state AND the hosted view has acknowledged the settled viewport
 *    (laid out non-zero, size unchanged across two consecutive frame-gated
 *    reads). A pending resize cannot hide between "inset looks right" and the
 *    measurement the way it hid behind the old fixed settle sleep.
 *
 * No function here introduces a sleep-based settle: sleeps appear only as the
 * [SETTLE_POLL_MS] gap between observable polls, the same rhythm every
 * journey poll loop in this suite already uses.
 */

/** Gap between two observable polls. The suite's standard 250 ms poll rhythm. */
const val SETTLE_POLL_MS = 250L

/**
 * Waits until the activity's window has focus — the observable focus-settle
 * signal — and throws a loud, settle-point-naming failure if it never does.
 *
 * Call before window-sensitive interactions: Espresso-rooted view
 * interactions (and anything that needs the IME to act on a window) assume a
 * focused window; on a contended emulator the focus can still be in flight
 * seconds after the UI looks ready, which is the J21 red 3259ceb0a
 * quarantined.
 */
fun AndroidComposeTestRule<*, *>.awaitWindowFocus(what: String, timeoutMs: Long) {
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    while (SystemClock.elapsedRealtime() < deadline) {
        awaitIdle("window-focus poll: $what")
        if (runOnUiThread { activity.window.decorView.hasWindowFocus() }) return
        SystemClock.sleep(SETTLE_POLL_MS)
    }
    throw AssertionError(
        "the window never took focus for $what within ${timeoutMs}ms — the " +
            "focus-settle signal stayed false (RootViewPicker's precondition, " +
            "observed instead of hoped for; see issue #2781)" + idleWedgeNote(),
    )
}

/**
 * Waits until [view] really holds the keyboard focus INSIDE a focused window —
 * the precondition injected key events silently need — and throws a loud,
 * settle-point-naming failure if it never does.
 *
 * Call before every `Instrumentation.sendStringSync` / `sendKeyDownUpSync`.
 * Both halves are load-bearing and neither is observable from the
 * `requestFocus()` + `waitForIdleSync()` shape this replaces (issue #2789):
 *
 *  1. **The window.** [awaitWindowFocus] first, so a window that never takes
 *     focus fails with #2781's message rather than as a corrupted command
 *     line. With no focused window the platform `InputDispatcher` logs
 *     `Waiting because no window has focus …` and defers the events it was
 *     handed; the ones dispatched during that window are lost, so a typed
 *     line arrives with its LEADING characters missing.
 *  2. **The view.** `requestFocus()` returns a request, not an acknowledgement
 *     — a view that is not yet attached, laid out, or focusable refuses it and
 *     says so only through [android.view.View.hasFocus]. So the request is
 *     re-issued every poll and the platform's own answer is read back, in the
 *     SAME main-thread read as `hasWindowFocus()`: checking them separately
 *     would let a window that lost focus again slip between the two reads.
 *
 * The whole wait is bounded by one [timeoutMs] budget shared with the window
 * half, so a caller's deadline means what it says. On success the caller's
 * very next dispatch happens with both observables true.
 */
fun AndroidComposeTestRule<*, *>.awaitInputFocus(
    what: String,
    timeoutMs: Long,
    view: () -> View?,
) {
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    awaitWindowFocus(what, timeoutMs)

    var state = "no view on screen to focus"
    do {
        awaitIdle("input-focus poll: $what")
        val focused = runOnUiThread {
            val target = view()
            if (target == null) {
                state = "no view on screen to focus"
                false
            } else {
                if (!target.hasFocus()) target.requestFocus()
                val hasFocus = target.hasFocus()
                val hasWindowFocus = target.hasWindowFocus()
                state = "hasFocus=$hasFocus hasWindowFocus=$hasWindowFocus " +
                    "attached=${target.isAttachedToWindow} focusable=${target.isFocusable}"
                hasFocus && hasWindowFocus
            }
        }
        if (focused) return
        SystemClock.sleep(SETTLE_POLL_MS)
    } while (SystemClock.elapsedRealtime() < deadline)

    throw AssertionError(
        "the view never took input focus for $what within ${timeoutMs}ms — last " +
            "observed: $state. Injected key events go to the focused view of the " +
            "focused window, so dispatching here would drop a prefix of the typed " +
            "line instead of failing (see issue #2789)" + idleWedgeNote(),
    )
}

/**
 * The framework's own IME inset, in pixels. 0 when the keyboard is down.
 *
 * The inset — not a screenshot, not `isActive()` — because it is the
 * quantity the resize path acts on, and (under `ADJUST_NOTHING`) the thing
 * that must NOT shrink the terminal.
 */
fun AndroidComposeTestRule<*, *>.imeInsetBottom(): Int {
    awaitIdle("before reading the IME inset")
    return runOnUiThread {
        ViewCompat.getRootWindowInsets(activity.window.decorView)
            ?.getInsets(WindowInsetsCompat.Type.ime())
            ?.bottom
            ?: 0
    }
}

/**
 * The insets/viewport-ack handshake: the IME inset at its expected state AND
 * the hosted view settled at the viewport that state implies.
 *
 * Returns only when both observables hold:
 *
 *  1. [imeInsetBottom] is [imeVisible] (the keyboard event itself landed);
 *  2. the view [view] resolves to is laid out with a non-zero size, and that
 *     size is UNCHANGED across two consecutive frame-gated reads
 *     ([awaitIdle] between them — under the Compose test rule the app's frame
 *     clock is driven by the test, so the second read is at least one full
 *     frame after the first and any pending layout has run).
 *
 * On a view that does not resize with the IME (`ADJUST_NOTHING`, the
 * #887/#2533 contract) the size gate is the assertion that nothing is STILL
 * resizing; on one that does, it is the acknowledgement that the resize
 * reached its final frame. Either way the caller measures a settled
 * viewport, not whatever a fixed delay happened to leave behind.
 *
 * [capture], when provided, is called with a failure screenshot name before
 * the assertion is thrown — the journeys' capture-on-failure evidence
 * convention, kept next to the failure that needs it.
 */
fun AndroidComposeTestRule<*, *>.awaitImeViewportAck(
    what: String,
    imeVisible: Boolean,
    timeoutMs: Long,
    view: () -> View?,
    capture: ((String) -> File)? = null,
) {
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    var inset = -1
    var size = "never laid out"
    while (SystemClock.elapsedRealtime() < deadline) {
        awaitIdle("ime viewport-ack poll: $what")
        inset = imeInsetBottom()
        if ((inset > 0) == imeVisible) {
            val stable = stableSize(what, view) { size = it }
            if (stable != null) return
        }
        SystemClock.sleep(SETTLE_POLL_MS)
    }
    capture?.invoke("failure-viewport-ack-${what.replace(' ', '-')}")
    throw AssertionError(
        "the viewport never settled for $what within ${timeoutMs}ms: the IME " +
            "inset bottom=${inset}px (${if (imeVisible) "visible" else "hidden"} " +
            "expected), the hosted view's size was $size and never held stable " +
            "across two consecutive frames. Screenshot evidence is named " +
            "failure-viewport-ack-*" + idleWedgeNote(),
    )
}

/**
 * Waits until [view] resolves to a laid-out, non-zero view whose size is
 * unchanged across two consecutive frame-gated reads — the viewport
 * settled — and throws if it never does.
 *
 * The inset-less half of [awaitImeViewportAck], for settle points that are
 * not IME-driven (rotation recreate) or whose inset state the caller has
 * already gated separately.
 */
fun AndroidComposeTestRule<*, *>.awaitViewSizeStable(
    what: String,
    timeoutMs: Long,
    view: () -> View?,
    capture: ((String) -> File)? = null,
) {
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    var size = "never laid out"
    while (SystemClock.elapsedRealtime() < deadline) {
        awaitIdle("viewport-stability poll: $what")
        if (stableSize(what, view) { size = it } != null) return
        SystemClock.sleep(SETTLE_POLL_MS)
    }
    capture?.invoke("failure-viewport-stable-${what.replace(' ', '-')}")
    throw AssertionError(
        "the viewport never settled for $what within ${timeoutMs}ms: the hosted " +
            "view's size was $size and never held stable across two consecutive " +
            "frames. Screenshot evidence is named failure-viewport-stable-*" +
            idleWedgeNote(),
    )
}

/**
 * One stability probe: the view's laid-out size now, and once more a full
 * frame later. Equal, non-null pair = the viewport has acknowledged whatever
 * drove it; anything else returns null (keep polling) and updates [note]
 * with the last observed shape for the failure message.
 */
private fun AndroidComposeTestRule<*, *>.stableSize(
    what: String,
    view: () -> View?,
    note: (String) -> Unit,
): Pair<Int, Int>? {
    val first = laidOutSize(view)
    if (first != null) {
        awaitIdle("viewport-stability confirm: $what")
        val second = laidOutSize(view)
        if (second != null && second == first) return second
        second?.let { note("${it.first}x${it.second} (was ${first.first}x${first.second})") }
    }
    return null
}

/**
 * The view's laid-out size, read on the main thread, or null while it does
 * not exist or has not had a non-zero layout pass yet.
 */
private fun laidOutSize(view: () -> View?): Pair<Int, Int>? {
    var size: Pair<Int, Int>? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        view()?.takeIf { it.isLaidOut && it.width > 0 && it.height > 0 }
            ?.let { size = it.width to it.height }
    }
    return size
}
