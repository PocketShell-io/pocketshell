package com.pocketshell.uikit.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issue #1772: Roborazzi drains Robolectric's paused main looper before capture.
 * A live Material indeterminate indicator continuously posts animation frames,
 * so the drain cannot reach quiescence. The two design fixtures that explicitly
 * showcase loading states must therefore paint a deterministic test-only frame.
 *
 * This source guard makes restoring either fixture to the production infinite
 * animation a hard failure while also protecting the opposite boundary:
 * production [com.pocketshell.uikit.components.LoadingIndicator] must stay live.
 *
 * It names TWO fixtures, which is why it could not save #2834: a third one
 * (`sessionSurfaceReconnectAffordance`) rendered the live spinner and wedged
 * the drain, because a hand-kept list only covers what someone remembered to
 * add. The structural answer landed with #2834 — every capture now goes
 * through [captureFrozenRender], which drives a test-owned frame clock for a
 * bounded amount of virtual time, so no animation can hold the pre-capture
 * looper drain open; [RenderHarnessPolicyTest] holds that. These per-fixture
 * pins are no longer what keeps record mode alive. They stay because a design
 * PNG should show one deterministic, recognisable in-flight frame rather than
 * whichever phase a capture happened to land on — visual intent, not safety.
 */
class DesignRenderStaticLoadingPolicyTest {

    @Test
    fun loadingIndicatorShowcaseUsesOnlyStaticFixtureFrames() {
        val source = locateTestSource("DesignRenders.kt")
        val body = source.substringBetween(
            start = "fun loadingIndicators()",
            end = "fun sessionConnectingStates()",
        )

        assertTrue(body.contains("StaticLoadingIndicator.Bar()"))
        assertEquals(4, body.countOccurrences("StaticLoadingIndicator.Spinner("))
        assertFalse(LIVE_BAR_CALL.containsMatchIn(body))
        assertFalse(LIVE_SPINNER_CALL.containsMatchIn(body))
    }

    @Test
    fun sessionConnectingShowcaseUsesOnlyStaticFixtureFrames() {
        val source = locateTestSource("TerminalRenderFixtures.kt")
        val body = source.substringBetween(
            start = "internal fun SessionConnectingStatesRender()",
            end = "internal fun SessionDisconnectedStateRender()",
        )

        assertEquals(2, body.countOccurrences("StaticLoadingIndicator.Spinner("))
        assertFalse(LIVE_SPINNER_CALL.containsMatchIn(body))
    }

    /**
     * Issue #2834: the two checks above name their fixtures, and a hand-kept
     * list only covers what someone remembered to add. Two render sources —
     * `SessionSurfaceReconnectAffordanceRender` and the `bannerSlots`
     * leading slot — carried the LIVE spinner for exactly that reason, and
     * one of them wedged record mode for long enough to cost a root-cause
     * investigation. This scan is TOTAL over the render sources instead:
     * zero live indicator calls, no list to keep.
     */
    @Test
    fun noRenderSourceCallsTheLiveIndicator() {
        val sources = renderDir()
            .listFiles { file -> file.isFile && file.name.endsWith(".kt") }
            .orEmpty()
            .sortedBy { it.name }
        assertTrue("no render sources found — path rot in the locator", sources.isNotEmpty())

        for (file in sources) {
            // This policy file has to name the banned calls to check for them.
            if (file.name == "DesignRenderStaticLoadingPolicyTest.kt") continue
            val body = file.readText()
            assertFalse(
                "${file.name}: render fixtures paint StaticLoadingIndicator, never the live " +
                    "LoadingIndicator.Bar — a design PNG should not capture an arbitrary " +
                    "animation phase (#1772, made total by #2834)",
                LIVE_BAR_CALL.containsMatchIn(body),
            )
            assertFalse(
                "${file.name}: render fixtures paint StaticLoadingIndicator, never the live " +
                    "LoadingIndicator.Spinner — a design PNG should not capture an arbitrary " +
                    "animation phase (#1772, made total by #2834)",
                LIVE_SPINNER_CALL.containsMatchIn(body),
            )
        }
    }

    @Test
    fun staticFixtureHasNoAnimationAndProductionIndicatorRemainsLive() {
        val fixture = locateTestSource("StaticLoadingIndicator.kt")
        assertTrue(fixture.contains("drawLine("))
        assertTrue(fixture.contains("drawArc("))
        assertTrue(fixture.contains("StaticSpinnerSweepDegrees = 270f"))
        assertFalse(fixture.contains("rememberInfiniteTransition"))
        assertFalse(fixture.contains("InfiniteTransition"))
        assertFalse(fixture.contains("LoadingIndicator.Bar("))
        assertFalse(fixture.contains("LoadingIndicator.Spinner("))

        val production = locateProductionSource("LoadingIndicator.kt")
        assertTrue(production.contains("LinearProgressIndicator("))
        assertTrue(production.contains("CircularProgressIndicator("))
        assertFalse(production.contains("StaticLoadingIndicator"))
    }

    private fun String.substringBetween(start: String, end: String): String {
        val startIndex = indexOf(start)
        check(startIndex >= 0) { "$start not found" }
        val endIndex = indexOf(end, startIndex)
        check(endIndex >= 0) { "$end not found after $start" }
        return substring(startIndex, endIndex)
    }

    private fun String.countOccurrences(needle: String): Int =
        windowed(size = needle.length, step = 1).count { it == needle }

    private fun renderDir(): File = listOf(
        "shared/ui-kit/src/test/java/com/pocketshell/uikit/render",
        "src/test/java/com/pocketshell/uikit/render",
    )
        .map(::File)
        .firstOrNull { it.isDirectory }
        ?: error("Could not locate the ui-kit render dir from ${File(".").absolutePath}")

    private fun locateTestSource(name: String): String =
        locate(
            "shared/ui-kit/src/test/java/com/pocketshell/uikit/render/$name",
            "src/test/java/com/pocketshell/uikit/render/$name",
        )

    private fun locateProductionSource(name: String): String =
        locate(
            "shared/ui-kit/src/main/java/com/pocketshell/uikit/components/$name",
            "src/main/java/com/pocketshell/uikit/components/$name",
        )

    private fun locate(vararg candidates: String): String {
        val file = candidates
            .asSequence()
            .map(::File)
            .firstOrNull { it.isFile }
            ?: error("Could not locate ${candidates.joinToString()} from ${File(".").absolutePath}")
        return file.readText()
    }

    private companion object {
        val LIVE_BAR_CALL = Regex("""(?<!Static)LoadingIndicator\.Bar\(""")
        val LIVE_SPINNER_CALL = Regex("""(?<!Static)LoadingIndicator\.Spinner\(""")
    }
}
