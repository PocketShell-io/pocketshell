package com.pocketshell.uikit.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issue #2834 guard, the ui-kit twin of :app2's #2733 `RenderHarnessPolicyTest`.
 *
 * Every ui-kit render class must capture through [captureFrozenRender] — the
 * compose-test-rule window with `mainClock.autoAdvance = false` — never
 * through the bare `captureRoboImage(path) { … }` harness. That harness
 * composes under the production choreographer frame clock, which any infinite
 * animation (`rememberInfiniteTransition`, and the M3 indeterminate
 * indicators built on it) keeps fed forever: Robolectric's paused main looper
 * never drains, Roborazzi's pre-capture `ShadowPausedLooper.idle()` never
 * returns, and the worker spins at ~100% CPU until the heap gives out. The
 * resulting `OutOfMemoryError` surfaces as `UncaughtExceptionsBeforeTest`
 * against an unrelated class, so a re-wedge would cost another root-cause
 * investigation rather than reading as itself.
 *
 * #2733 fixed that for :app2 alone. Nothing carried the harness across to
 * :shared:ui-kit, so #2834 rediscovered the identical wedge here — on
 * `sessionSurfaceReconnectAffordance`, a fixture that predates the #1772
 * static-painter convention and simply was not on its hand-kept list. This
 * guard is the part that generalises: a NEW render class, or a fixture with
 * an animation nobody anticipated, cannot reach the old path again.
 */
class RenderHarnessPolicyTest {

    @Test
    fun everyRenderClassFreezesTheFrameClock() {
        val renderClasses = renderDir()
            .listFiles { file -> file.isFile && file.name.endsWith(".kt") }
            .orEmpty()
            .sortedBy { it.name }
            .filterNot { it.name in META_SOURCES }
            .filter { CAPTURE_CALL.containsMatchIn(it.readText().withoutComments()) }
        assertTrue(
            "no source under the render dir captures anything — path rot in the locator, " +
                "or this guard is now scanning nothing (#2834)",
            renderClasses.isNotEmpty(),
        )

        for (file in renderClasses) {
            val source = file.readText()
            val classCount = CLASS_DECLARATION.findAll(source).count()
            assertTrue("${file.name}: no test class found", classCount > 0)
            assertEquals(
                "${file.name}: every render class needs a `createComposeRule()` rule " +
                    "so its captures freeze the frame clock (#2834 / #2733)",
                classCount,
                source.countOccurrences("createComposeRule()"),
            )
            assertTrue(
                "${file.name}: the compose rule must carry `@get:Rule` — an unannotated " +
                    "field is never applied, so the capture silently falls back to the " +
                    "production window recomposer (#2834)",
                source.countOccurrences("@get:Rule") >= classCount,
            )
        }
    }

    @Test
    fun noRenderFileCapturesOutsideTheFrozenHarness() {
        val sources = renderDir()
            .listFiles { file -> file.isFile && file.name.endsWith(".kt") }
            .orEmpty()
            .sortedBy { it.name }
        assertTrue("no .kt sources found — path rot in the locator", sources.isNotEmpty())

        for (file in sources) {
            // RenderSupport.kt legitimately calls the banned API inside the
            // frozen helper; this guard names it in its own check strings.
            if (file.name in META_SOURCES) continue
            assertFalse(
                "${file.name}: call captureFrozenRender(...) instead of captureRoboImage — " +
                    "the bare harness wedges record mode on animated states (#2834 / #2733)",
                file.readText().withoutComments().contains("captureRoboImage("),
            )
        }
    }

    @Test
    fun frozenHarnessFreezesTheClockAndKeepsThePlainRunEarlyReturn() {
        val harness = File(renderDir(), "RenderSupport.kt").readText()
        assertTrue(
            "the harness must freeze the test frame clock, or an infinite animation " +
                "reposts frames and the pre-capture looper drain never finishes (#2834)",
            harness.contains("mainClock.autoAdvance = false"),
        )
        assertTrue(
            "the harness must capture with captureScreenRoboImage, which composites every " +
                "window root — a root-scoped capture silently drops Dialog/Popup windows and " +
                "the dialog renders come back empty (#2834)",
            harness.contains("captureScreenRoboImage("),
        )
        assertTrue(
            "the harness must pump a bounded, fixed amount of virtual time before capturing, " +
                "or an overlay that animates in (ModalBottomSheet behind ConfirmDialog) is " +
                "captured fully off-screen and the PNG is a bare background (#2834)",
            harness.contains("mainClock.advanceTimeBy(SETTLE_MILLIS)") &&
                SETTLE_DECLARATION.containsMatchIn(harness),
        )
        assertTrue(
            "the plain-run early return (taskType.isEnabled) must stay, or every ordinary " +
                "`:shared:ui-kit:testDebugUnitTest` starts paying for the render compositions " +
                "it never looks at",
            harness.contains("taskType.isEnabled()"),
        )
    }

    private fun renderDir(): File = listOf(
        "shared/ui-kit/src/test/java/com/pocketshell/uikit/render",
        "src/test/java/com/pocketshell/uikit/render",
    )
        .map(::File)
        .firstOrNull { it.isDirectory }
        ?: error("Could not locate the ui-kit render dir from ${File(".").absolutePath}")

    /**
     * Comment lines are dropped before matching, so KDoc that EXPLAINS the
     * banned call (DesignRenders.kt says why it no longer uses it) cannot trip
     * the guard — and, symmetrically, a commented-out call cannot satisfy one.
     * Same rule as `scripts/check-no-native-build.sh`.
     */
    private fun String.withoutComments(): String =
        lineSequence()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")

    private fun String.countOccurrences(needle: String): Int =
        windowed(size = needle.length, step = 1).count { it == needle }

    private companion object {
        val CLASS_DECLARATION = Regex("""^class \w+ \{""", setOf(RegexOption.MULTILINE))

        /**
         * A source that captures renders. Matched on the call rather than a
         * filename convention: ui-kit's render class is `DesignRenders.kt`,
         * which no `*Renders.kt` suffix rule would cover the same way :app2's
         * does, and a future second class must be caught by what it DOES.
         */
        val CAPTURE_CALL = Regex("""captureFrozenRender\(|captureRoboImage\(|captureScreenRoboImage\(""")

        /**
         * The harness itself and this guard both have to name the banned call
         * to do their jobs, so neither can be scanned as if it were a render
         * class. Every OTHER source under the dir is in scope.
         */
        val META_SOURCES = setOf("RenderSupport.kt", "RenderHarnessPolicyTest.kt")

        /**
         * The settle window must stay a literal constant, not a call that
         * could resolve to zero: capturing at t=0 is precisely the empty-PNG
         * failure this assertion exists for.
         */
        val SETTLE_DECLARATION = Regex("""const val SETTLE_MILLIS\s*=\s*[1-9][\d_]*L""")
    }
}
