package com.pocketshell.next.render

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import com.pocketshell.next.settings.AppSettings
import com.pocketshell.next.settings.ConnectionSettingsScreen
import com.pocketshell.next.settings.SettingsHostRow
import com.pocketshell.next.settings.TerminalSettingsScreen
import com.pocketshell.next.settings.VoiceSettingsScreen
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
 * Fresh Quiet renders for the settings sub-pages the #2635 R5 coverage audit
 * (#2635 comment 5700497834 §6) still lists as GAPs after the first batch
 * (#2762) covered Files/FileViewer/Diagnostics/ConnectionSettings and the two
 * sheets. Run with:
 *
 * ./gradlew :app2:testDebugUnitTest --tests '*SettingsPagesRenders*' --rerun-tasks
 *
 * Two states per page, chosen so the state-bearing controls differ: the
 * terminal slider at its floor vs its 16 sp ceiling (MAX_TERMINAL_TEXT_SIZE_PX
 * is 48 px, i.e. 16 sp at this xxhdpi density-3 qualifier; with the common-keys
 * switch on vs off), and the voice language row showing Auto-detect vs an
 * explicit language.
 *
 * Issue #2814 N-3 deleted the two focused picker PAGES those last four cases
 * rendered. The pickers themselves did not go away — they expand in place
 * under their parent row — so the cases follow them: the same selections are
 * now captured on the Voice and Connections pages with the group open. The
 * `initiallyExpanded` seam is what makes that capturable at all, because a
 * frozen-clock render (#2733) cannot click a disclosure row.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class SettingsPagesRenders {

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
    fun terminalSettingsDefault() = render("i2635-terminal-settings-default") {
        TerminalSettingsScreen(
            settings = AppSettings(),
            onBack = {},
            onTerminalTextSizeChange = {},
        )
    }

    @Test
    fun terminalSettingsMaxTextKeysOff() =
        render("i2635-terminal-settings-max-text-keys-off") {
            TerminalSettingsScreen(
                settings = AppSettings(
                    terminalTextSizePx = AppSettings.MAX_TERMINAL_TEXT_SIZE_PX,
                    showCommonKeys = false,
                ),
                onBack = {},
                onTerminalTextSizeChange = {},
            )
        }

    @Test
    fun voiceSettingsAutoLanguage() = render("i2635-voice-settings-auto-language") {
        VoiceSettingsScreen(
            settings = AppSettings(),
            onBack = {},
            onVoiceLanguageChange = {},
        )
    }

    @Test
    fun voiceSettingsExplicitLanguage() =
        render("i2635-voice-settings-explicit-language") {
            VoiceSettingsScreen(
                settings = AppSettings(voiceLanguage = "ru"),
                onBack = {},
                onVoiceLanguageChange = {},
            )
        }

    @Test
    fun voiceLanguageAutoSelected() = render("i2814-voice-language-group-auto-selected") {
        VoiceSettingsScreen(
            settings = AppSettings(),
            onBack = {},
            onVoiceLanguageChange = {},
            initiallyExpanded = true,
        )
    }

    @Test
    fun voiceLanguageMidListSelection() =
        render("i2814-voice-language-group-mid-list-selection") {
            VoiceSettingsScreen(
                settings = AppSettings(voiceLanguage = "de"),
                onBack = {},
                onVoiceLanguageChange = {},
                initiallyExpanded = true,
            )
        }

    @Test
    fun graceSettingsDefaultWindow() = render("i2814-grace-group-default-window") {
        ConnectionSettingsScreen(
            settings = AppSettings(),
            hosts = RENDER_HOSTS,
            onBack = {},
            onBackgroundGraceChange = {},
            onOpenWorkspaceRoots = {},
            initiallyExpanded = true,
        )
    }

    @Test
    fun graceSettingsShortestWindow() =
        render("i2814-grace-group-shortest-window") {
            ConnectionSettingsScreen(
                settings = AppSettings(
                    backgroundGraceMillis = AppSettings.BACKGROUND_GRACE_30_SECONDS_MS,
                ),
                hosts = RENDER_HOSTS,
                onBack = {},
                onBackgroundGraceChange = {},
                onOpenWorkspaceRoots = {},
                initiallyExpanded = true,
            )
        }

    /** One saved host, so the group is captured above real content, not an empty state. */
    private val RENDER_HOSTS = listOf(SettingsHostRow(1L, "hetzner", "alexey@10.0.0.1:22"))

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
