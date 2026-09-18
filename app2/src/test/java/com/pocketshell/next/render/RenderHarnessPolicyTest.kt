package com.pocketshell.next.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issue #2733 guard: every app2 render class must capture through
 * [captureFrozenRender] (compose-test-rule window, `mainClock.autoAdvance =
 * false`), never through the bare captureRoboImage path-and-lambda harness.
 * That harness composes under the production choreographer
 * frame clock, which `rememberInfiniteTransition` loops (mic pulse, waveform,
 * status-dot pulse, M3 spinners) keep fed forever — the Robolectric main
 * looper never drains and record mode wedges the test worker in
 * `Sandbox.runOnMainThread`. A new render class using the old harness would
 * reintroduce that hang; this makes it a hard failure instead.
 *
 * Same source-scan shape as ui-kit's `DesignRenderStaticLoadingPolicyTest`
 * (#1772), for the same reason.
 */
class RenderHarnessPolicyTest {

    @Test
    fun everyRenderClassFreezesTheFrameClock() {
        val renderFiles = renderDir()
            .listFiles { file -> file.isFile && file.name.endsWith("Renders.kt") }
            .orEmpty()
            .sortedBy { it.name }
        assertTrue("no *Renders.kt files found — path rot in the locator", renderFiles.isNotEmpty())

        for (file in renderFiles) {
            val source = file.readText()
            val classCount = CLASS_DECLARATION.findAll(source).count()
            assertTrue("${file.name}: no test class found", classCount > 0)
            assertEquals(
                "${file.name}: every render class needs a `createComposeRule()` rule " +
                    "so its captures freeze the frame clock (#2733)",
                classCount,
                source.countOccurrences("createComposeRule()"),
            )
        }
    }

    @Test
    fun noRenderFileCapturesOutsideTheFrozenHarness() {
        for (file in renderDir().listFiles { f -> f.isFile && f.name.endsWith(".kt") }.orEmpty()) {
            // RenderSupport.kt legitimately calls the banned API inside the
            // frozen helper; this guard names it in its own check string.
            if (file.name == "RenderSupport.kt" || file.name == "RenderHarnessPolicyTest.kt") continue
            val source = file.readText()
            assertFalse(
                "${file.name}: call captureFrozenRender(...) instead of captureRoboImage — " +
                    "the bare harness wedges record mode on animated states (#2733)",
                source.contains("captureRoboImage("),
            )
        }
    }

    @Test
    fun frozenHarnessFreezesTheClockAndKeepsThePlainRunEarlyReturn() {
        val harness = locate("RenderSupport.kt").readText()
        assertTrue(harness.contains("mainClock.autoAdvance = false"))
        assertTrue(
            "the plain-run early return (taskType.isEnabled) must stay, or the JVM gate " +
                "starts paying for ~630 render compositions",
            harness.contains("taskType.isEnabled()"),
        )
    }

    /**
     * Issue #2773 guard: render fixtures mirror production types locally
     * (#2636 C1) instead of importing them, so a fixture's visual contract
     * can never silently couple a PNG to a production class's live shape
     * before the shared-module extraction lands. Nothing prevents a future
     * fixture from quietly re-importing them; this makes that a hard failure.
     *
     * "The production TerminalSession type" means the production construction
     * path, [BANNED_PRODUCTION_SYMBOLS]'s `createRemoteTerminalSession`
     * factory: the vendored `com.termux.terminal.TerminalSession` constructed
     * fixture-locally in `SessionScreenRenders` is the sanctioned mirror and
     * stays allowed.
     */
    @Test
    fun noRenderFixtureSourceReferencesProductionTypes() {
        val sources = renderDir()
            .listFiles { file -> file.isFile && file.name.endsWith(".kt") }
            .orEmpty()
            .sortedBy { it.name }
        assertTrue("no .kt sources found — path rot in the locator", sources.isNotEmpty())

        for (file in sources) {
            // This policy file names the banned symbols in its own failure
            // strings; every other source under the dir must be clean.
            if (file.name == "RenderHarnessPolicyTest.kt") continue
            val source = file.readText()
            for ((symbol, mirrorInstead) in BANNED_PRODUCTION_SYMBOLS) {
                assertFalse(
                    "${file.name}: render fixtures must not reference the production symbol " +
                        "`$symbol` — $mirrorInstead (#2636 C1, #2773)",
                    source.contains(symbol),
                )
            }
        }
    }

    private fun renderDir(): File = locateDir(
        "app2/src/test/java/com/pocketshell/next/render",
        "src/test/java/com/pocketshell/next/render",
    )

    private fun locateDir(vararg candidates: String): File =
        candidates
            .map(::File)
            .firstOrNull { it.isDirectory }
            ?: error("Could not locate render dir from ${File(".").absolutePath}")

    private fun locate(name: String): File = File(renderDir(), name)

    private fun String.countOccurrences(needle: String): Int =
        windowed(size = needle.length, step = 1).count { it == needle }

    private companion object {
        val CLASS_DECLARATION = Regex("""^class \w+ \{""", setOf(RegexOption.MULTILINE))

        /**
         * The three production symbols C1 (#2636) de-leaked from these
         * fixtures, each with the local mirror a fix belongs through. The
         * Room entity and the ports controller are banned wholesale; for the
         * terminal only the production PTY-bridge factory is banned — the
         * vendored session type constructed fixture-locally is the mirror.
         */
        val BANNED_PRODUCTION_SYMBOLS = linkedMapOf(
            "SshKeyEntity" to
                "mirror the picker row as hosts.SshKeyRow (see HostScreenRenders.key())",
            "ForwardingController" to
                "mirror the controller's const strings fixture-locally " +
                "(see PortForwardScreenRenders.NEEDS_TRUST_ATTENTION)",
            "createRemoteTerminalSession" to
                "construct the vendored TerminalSession fixture-locally " +
                "(see SessionScreenRenders.fakeTerminalSession())",
        )
    }
}
