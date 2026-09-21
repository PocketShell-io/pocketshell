package com.pocketshell.next.render

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import com.pocketshell.next.crash.CrashReportDisplay
import com.pocketshell.next.crash.CrashReportsLoadState
import com.pocketshell.next.crash.DiagnosticReportScreen
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import com.pocketshell.testsupport.LeakGuard
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * Fresh Quiet renders for the single crash-report page (#2636 D17, destination
 * DiagnosticReport). Run with:
 *
 * ./gradlew :app2:testDebugUnitTest --tests '*DiagnosticReportScreenRenders*' --rerun-tasks
 *
 * Both captures call the real shared [DiagnosticReportScreen] with the pure
 * [CrashReportDisplay] mirror (the D12 seam): the populated report the user
 * reviews before sharing, and the deleted-report empty state.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class DiagnosticReportScreenRenders {

    // Issue #2724: record-mode captures compose screens a plain unit run never
    // composes, so a leak from that composition fails HERE (the class-guard
    // grace catches post-test stragglers), not whichever runTest class is next.
    @get:Rule
    val leakGuard = LeakGuard()

    // Issue #2733: frozen frame clock for record captures — see
    // [captureFrozenRender]. Without it animated states never let the
    // Robolectric main looper drain and record mode wedges.
    @get:Rule
    val composeRule = createComposeRule()

    companion object {
        @JvmStatic
        @get:ClassRule
        val leakGuardClass = LeakGuard.classGuard()
    }

    @Test
    fun diagnosticReportPopulated() = render("i2636-diagnostic-report-populated") {
        DiagnosticReportScreen(
            report = CrashReportDisplay(
                id = "20260921-055500-000",
                timestamp = Instant.parse("2026-09-21T05:55:00Z"),
                summary = "IllegalStateException: session channel closed unexpectedly",
                contextSummary = "Session · host=hetzner · session=agent-main",
                appVersion = "0.5.9",
                topFrame = "com.pocketshell.next.terminal.SessionScreenKt.render(SessionScreen.kt:540)",
            ),
            body = """
                PocketShell crash report 20260921-055500-000
                app=0.5.9 android=15 sdk=35 device=Pixel 7a

                IllegalStateException: session channel closed unexpectedly
                    at com.pocketshell.next.terminal.SessionScreenKt.render(SessionScreen.kt:540)
                    at com.pocketshell.next.terminal.SessionRoute(SessionRoute.kt:112)
            """.trimIndent(),
            loadState = CrashReportsLoadState.Ready,
            onBack = {},
            onShare = {},
            onDeleteReport = {},
        )
    }

    @Test
    fun diagnosticReportUnavailable() = render("i2636-diagnostic-report-unavailable") {
        DiagnosticReportScreen(
            report = null,
            body = "",
            loadState = CrashReportsLoadState.Ready,
            onBack = {},
            onShare = {},
            onDeleteReport = {},
        )
    }

    private fun render(name: String, content: @Composable () -> Unit) {
        composeRule.captureFrozenRender("build/renders/$name.png") {
            PocketShellTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = PocketShellColors.Background,
                ) {
                    content()
                }
            }
        }
    }
}
