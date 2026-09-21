package com.pocketshell.next.render

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import com.pocketshell.next.settings.AboutScreen
import com.pocketshell.next.settings.AppBuildInfo
import com.pocketshell.next.settings.ReleaseUpdateDisplay
import com.pocketshell.next.settings.SettingsUpdateCheckState
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
 * Fresh Quiet renders for the About sub-page (#2636 D17, destination About).
 * Run with:
 *
 * ./gradlew :app2:testDebugUnitTest --tests '*AboutScreenRenders*' --rerun-tasks
 *
 * Both captures call the real shared [AboutScreen] with fixture-only state:
 * the idle build-identity page, and the row while a newer release is known.
 * The app-side route (AboutRoute) that reads the package manager and the
 * update-check view model stays unexercised here — those are gate-class and
 * emulator concerns.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class AboutScreenRenders {

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
    fun aboutInstalledBuild() = render("i2636-about-installed-build") {
        AboutScreen(
            buildInfo = AppBuildInfo(versionName = "0.5.9", versionCode = 904),
            updateCheckState = SettingsUpdateCheckState.Idle,
            onBack = {},
            onOpenUpdate = {},
        )
    }

    @Test
    fun aboutUpdateAvailableRow() = render("i2636-about-update-available-row") {
        AboutScreen(
            buildInfo = AppBuildInfo(versionName = "0.5.0", versionCode = 900),
            updateCheckState = SettingsUpdateCheckState.UpdateAvailable(
                ReleaseUpdateDisplay(
                    tagName = "v0.5.1",
                    htmlUrl = "https://github.com/PocketShell-io/pocketshell/releases/tag/v0.5.1",
                    apkUrl = "https://example.com/pocketshell-0.5.1.apk",
                    publishedDateLabel = "5 Sep 2026",
                ),
            ),
            onBack = {},
            onOpenUpdate = {},
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
