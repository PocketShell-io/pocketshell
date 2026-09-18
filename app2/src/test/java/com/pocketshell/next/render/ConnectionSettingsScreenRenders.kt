package com.pocketshell.next.render

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import com.pocketshell.next.settings.AppSettings
import com.pocketshell.next.settings.ConnectionSettingsScreen
import com.pocketshell.next.settings.SettingsHostRow
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
 * Fresh Quiet renders for the Connections settings page (#2635 R5,
 * destination ConnectionSettings). Run with:
 *
 * ./gradlew :app2:testDebugUnitTest --tests '*ConnectionSettingsScreenRenders*' --rerun-tasks
 *
 * The page's two visually distinct states: saved hosts to manage workspace
 * roots for, and the no-hosts empty state.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class ConnectionSettingsScreenRenders {

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
    fun connectionSettingsSavedHosts() = render("i2762-connection-settings-saved-hosts") {
        ConnectionSettingsScreen(
            settings = AppSettings(reconnectWhenReturn = true),
            hosts = listOf(
                SettingsHostRow(1L, "hetzner", "135.181.114.209 · 8 workspaces"),
                SettingsHostRow(2L, "lab-box", "10.0.0.7 · 2 workspaces"),
            ),
            onBack = {},
            onOpenGrace = {},
            onOpenWorkspaceRoots = {},
        )
    }

    @Test
    fun connectionSettingsNoHosts() = render("i2762-connection-settings-no-hosts") {
        ConnectionSettingsScreen(
            settings = AppSettings(),
            hosts = emptyList(),
            onBack = {},
            onOpenGrace = {},
            onOpenWorkspaceRoots = {},
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
