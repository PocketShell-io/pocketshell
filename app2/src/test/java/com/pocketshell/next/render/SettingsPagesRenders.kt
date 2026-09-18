package com.pocketshell.next.render

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import com.pocketshell.next.settings.AppSettings
import com.pocketshell.next.settings.GraceSettingsScreen
import com.pocketshell.next.settings.LanguageSettingsScreen
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
 * Fresh Quiet renders for the four settings sub-pages the #2635 R5 coverage
 * audit (#2635 comment 5700497834 §6) still lists as GAPs after the first
 * batch (#2762) covered Files/FileViewer/Diagnostics/ConnectionSettings and
 * the two sheets: Terminal, Voice, Dictation language (VoiceLanguage) and
 * Keep connection (GraceSettings). Run with:
 *
 * ./gradlew :app2:testDebugUnitTest --tests '*SettingsPagesRenders*' --rerun-tasks
 *
 * Two states per page, chosen so the state-bearing controls differ: the
 * terminal slider at its floor vs its 16 sp ceiling (MAX_TERMINAL_TEXT_SIZE_PX
 * is 48 px, i.e. 16 sp at this xxhdpi density-3 qualifier; with the common-keys
 * switch on vs off), the voice language row showing Auto-detect vs an
 * explicit language, the language picker's selection on Auto vs mid-list,
 * and the grace picker on the 90 s default vs the shortest window.
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
            onOpenLanguage = {},
        )
    }

    @Test
    fun voiceSettingsExplicitLanguage() =
        render("i2635-voice-settings-explicit-language") {
            VoiceSettingsScreen(
                settings = AppSettings(voiceLanguage = "ru"),
                onBack = {},
                onOpenLanguage = {},
            )
        }

    @Test
    fun voiceLanguageAutoSelected() = render("i2635-voice-language-auto-selected") {
        LanguageSettingsScreen(
            settings = AppSettings(),
            onBack = {},
            onVoiceLanguageChange = {},
        )
    }

    @Test
    fun voiceLanguageMidListSelection() =
        render("i2635-voice-language-mid-list-selection") {
            LanguageSettingsScreen(
                settings = AppSettings(voiceLanguage = "de"),
                onBack = {},
                onVoiceLanguageChange = {},
            )
        }

    @Test
    fun graceSettingsDefaultWindow() = render("i2635-grace-settings-default-window") {
        GraceSettingsScreen(
            settings = AppSettings(),
            onBack = {},
            onBackgroundGraceChange = {},
        )
    }

    @Test
    fun graceSettingsShortestWindow() =
        render("i2635-grace-settings-shortest-window") {
            GraceSettingsScreen(
                settings = AppSettings(
                    backgroundGraceMillis = AppSettings.BACKGROUND_GRACE_30_SECONDS_MS,
                ),
                onBack = {},
                onBackgroundGraceChange = {},
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
