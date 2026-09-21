package com.pocketshell.next.render

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.next.crash.CrashReportContext
import com.pocketshell.next.crash.CrashReportMetadata
import com.pocketshell.next.crash.CrashReporter
import com.pocketshell.next.crash.CrashReportsViewModel
import com.pocketshell.next.crash.DiagnosticsRoute
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

/**
 * Fresh Quiet renders for the Diagnostics screen (#2635 R5, destination
 * Diagnostics). Run with:
 *
 * ./gradlew :app2:testDebugUnitTest --tests '*DiagnosticsScreenRenders*' --rerun-tasks
 *
 * The screen reads the real [CrashReportStore] through the real
 * [CrashReportsViewModel] — the screen never invents reports — so these
 * captures seed the Robolectric app's store with two real report files and
 * let the production pipeline parse them. The empty capture is the same
 * store before anything was saved.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class DiagnosticsScreenRenders {

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
    fun diagnosticsNoReports() = render("i2762-diagnostics-no-reports") {
        DiagnosticsRoute(
            onBack = {},
            onOpenReport = {},
            viewModel = CrashReportsViewModel(appContext()),
        )
    }

    @Test
    fun diagnosticsSavedReports() = render("i2762-diagnostics-saved-reports") {
        val context = appContext()
        val store = CrashReporter.store(context)
        store.save(
            throwable = IllegalStateException("session channel closed unexpectedly"),
            threadName = "DefaultDispatcher-worker-3",
            metadata = metadata(),
            context = CrashReportContext(
                screen = "Session",
                hostName = "hetzner",
                hostname = "135.181.114.209",
                username = "alexey",
                sessionName = "agent-main",
                startDirectory = "/home/alexey/git/pocketshell",
            ),
        )
        store.save(
            throwable = IllegalArgumentException("host form: port out of range"),
            threadName = "main",
            metadata = metadata(),
            context = CrashReportContext(screen = "HostForm"),
        )
        DiagnosticsRoute(
            onBack = {},
            onOpenReport = {},
            viewModel = CrashReportsViewModel(context),
        )
    }

    private fun appContext(): Context = ApplicationProvider.getApplicationContext()

    private fun metadata() = CrashReportMetadata(
        appVersion = "0.5.9",
        androidRelease = "15",
        sdkInt = 35,
        device = "Pixel 7a",
    )

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
