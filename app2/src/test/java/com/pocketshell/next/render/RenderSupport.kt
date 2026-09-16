package com.pocketshell.next.render

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.provideRoborazziContext

/**
 * Issue #2733: the record-mode capture harness used to compose through
 * `captureRoboImage(path) { content }`, which hosts the content in a plain
 * activity window with the PRODUCTION window recomposer. That recomposer's
 * frame clock is choreographer-backed (`AndroidUiFrameClock`), and every
 * `rememberInfiniteTransition` loop (mic pulse, waveform wave phase and idle
 * pulse, elapsed-timer tick, status-dot pulse, M3 spinners) reschedules a
 * frame on every callback — so the Robolectric main looper never drains, the
 * capture teardown task never runs, and the test worker parks forever in
 * `Sandbox.runOnMainThread` (jstack in #2733: main thread ~120% CPU, zero
 * progress 25+ min on `composerRecording`).
 *
 * This helper instead renders inside the compose-test-rule window: ui-test
 * (`AndroidComposeUiTestEnvironment`) builds its OWN recomposer with a
 * `TestMonotonicFrameClock` in the effect context, exposed as
 * [ComposeTestRule.mainClock]. Freezing it with `autoAdvance = false` means
 * infinite transitions suspend on their first `withFrameNanos` and never
 * repost — the looper drains, the capture completes, and the PNG shows the
 * animation's initial frame (full-strength pulse, zero wave phase), which is
 * the deterministic frame the design renders want. ANY animated screen is
 * safe now, not just the four states that wedged.
 *
 * Plain (non-Roborazzi) unit runs still skip composition entirely: exactly
 * like `captureRoboImage` itself, the helper early-returns unless a Roborazzi
 * task type (record/verify/compare) is enabled, so the JVM gate does not pay
 * for ~630 render compositions it never looks at. `RenderHarnessPolicyTest`
 * fails if a render class bypasses this helper and re-wedges record mode.
 */
internal fun ComposeContentTestRule.captureFrozenRender(path: String, content: @Composable () -> Unit) {
    if (!provideRoborazziContext().options.taskType.isEnabled()) return
    mainClock.autoAdvance = false
    setContent { content() }
    onRoot().captureRoboImage(path)
}
