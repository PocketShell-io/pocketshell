package com.pocketshell.next.render

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import com.pocketshell.next.settings.ReleaseUpdateDisplay
import com.pocketshell.next.settings.SettingsUpdateCheckState
import com.pocketshell.next.settings.UpdateScreen
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
 * Fresh Quiet renders for the Updates sub-page (#2636 D17, destination
 * Update). Run with:
 *
 * ./gradlew :app2:testDebugUnitTest --tests '*UpdateScreenRenders*' --rerun-tasks
 *
 * These captures call the real shared [UpdateScreen] at the two states with
 * the most content to look at: a newer release available (release-notes row
 * plus the open-release primary action) and a failed check (error banner plus
 * retry). The route that drives the real GitHub check stays app-side.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class UpdateScreenRenders {

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
    fun updateReleaseAvailable() = render("i2636-update-release-available") {
        UpdateScreen(
            state = SettingsUpdateCheckState.UpdateAvailable(
                ReleaseUpdateDisplay(
                    tagName = "v0.5.1",
                    htmlUrl = "https://github.com/PocketShell-io/pocketshell/releases/tag/v0.5.1",
                    apkUrl = "https://example.com/pocketshell-0.5.1.apk",
                    publishedDateLabel = "5 Sep 2026",
                ),
            ),
            onBack = {},
            onCheckForUpdates = {},
            onOpenUrl = {},
        )
    }

    @Test
    fun updateCheckFailed() = render("i2636-update-check-failed") {
        UpdateScreen(
            state = SettingsUpdateCheckState.Failed("GitHub unreachable (offline)"),
            onBack = {},
            onCheckForUpdates = {},
            onOpenUrl = {},
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
