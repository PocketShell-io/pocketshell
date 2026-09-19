package com.pocketshell.uikit.render

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.github.takahirom.roborazzi.provideRoborazziContext

/**
 * Issue #2834: the ui-kit port of the #2733 frozen-clock capture harness.
 *
 * `captureRoboImage(path) { content }` hosts the content in a plain activity
 * window driven by the PRODUCTION window recomposer, whose frame clock is
 * choreographer-backed (`AndroidUiFrameClock`). Any composable that schedules
 * a frame from a frame callback — `rememberInfiniteTransition`, and the M3
 * indeterminate progress indicators built on it — reposts on every callback,
 * so Robolectric's paused main looper never drains. Roborazzi idles that
 * looper to quiescence before it captures
 * (`captureScreenIfMultipleWindows` -> `ShadowPausedLooper.idle`), so the
 * capture never returns and the test worker spins at ~100% CPU until the heap
 * gives out.
 *
 * That is what #2834 was: `sessionSurfaceReconnectAffordance` rendered the
 * LIVE `LoadingIndicator.Spinner`, wedged the drain, and the eventual
 * `OutOfMemoryError` escaped into a Compose recomposer coroutine — where
 * kotlinx-coroutines-test's process-wide collector picked it up and re-threw
 * it as `UncaughtExceptionsBeforeTest` against the next unrelated class to
 * open a `TestScope` (`QuietThemeTokenTest`). The class named in the failure
 * had nothing to do with it.
 *
 * #1772 answered the same hazard for two named fixtures by hand, with
 * [StaticLoadingIndicator] painters, and #2733 answered it structurally for
 * :app2 — but only for :app2. This closes the port gap: rendering inside the
 * compose-test-rule window means ui-test (`AndroidComposeUiTestEnvironment`)
 * builds its OWN recomposer with a `TestMonotonicFrameClock`, exposed as
 * [ComposeContentTestRule.mainClock]. That clock is driven by the test, not
 * by a choreographer, so no animation can outrun it: the looper drains, the
 * capture completes, and ANY animated composable is safe — not just the ones
 * someone remembered to convert.
 *
 * The three lines after the early return each carry a measured reason.
 *
 * `autoAdvance = false` hands frame pacing to this function. Without it the
 * clock runs itself and the frame an animated fixture is caught on depends on
 * timing, which is how a render harness starts producing diffs nobody
 * changed.
 *
 * `advanceTimeBy(SETTLE_MILLIS)` then pumps a FIXED, bounded amount of
 * virtual time. It is not cosmetic. A Compose overlay that animates in — the
 * `ModalBottomSheet` behind [com.pocketshell.uikit.components.ConfirmDialog]
 * and the ui-kit form dialog — starts fully off-screen, so capturing at t=0
 * writes the bare themed background. During #2834 exactly that shipped for a
 * moment: `confirm-dialog-destructive.png` and `form-dialog.png` came back as
 * two byte-identical empty frames. A bounded advance settles those entry
 * animations while leaving an INFINITE one at a fixed, reproducible phase —
 * so the frame is deterministic without the drain ever being open-ended.
 * Both dialog PNGs are byte-identical to the pre-#2834 harness's output at
 * this value.
 *
 * The capture itself is `captureScreenRoboImage`, which composites every
 * Robolectric window root, NOT `onRoot().captureRoboImage`: a Compose
 * `Dialog`/`Popup` lives in its own window and a root-scoped capture silently
 * drops it. A render harness that quietly renders nothing is worse than one
 * that wedges — the wedge at least announces itself.
 *
 * Plain (non-Roborazzi) unit runs still skip composition entirely: exactly
 * like `captureRoboImage` itself, the helper early-returns unless a Roborazzi
 * task type (record/verify/compare) is enabled, so `testDebugUnitTest` does
 * not pay for render compositions it never looks at. [RenderHarnessPolicyTest]
 * fails if a render class bypasses this helper and re-wedges record mode.
 */
internal fun ComposeContentTestRule.captureFrozenRender(path: String, content: @Composable () -> Unit) {
    if (!provideRoborazziContext().options.taskType.isEnabled()) return
    mainClock.autoAdvance = false
    setContent { content() }
    mainClock.advanceTimeBy(SETTLE_MILLIS)
    captureScreenRoboImage(path)
}

/**
 * Virtual milliseconds pumped before the capture: comfortably past any
 * entry animation the kit uses (the M3 bottom-sheet show spring is the
 * longest), and bounded, so an infinite animation lands on a fixed phase
 * instead of holding the drain open.
 */
private const val SETTLE_MILLIS = 1_000L
