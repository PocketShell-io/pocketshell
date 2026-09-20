package com.pocketshell.next.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.QuietChoiceRow
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellSpacing

/**
 * The dictation-language picker sub-page.
 *
 * Lives in the shared presentation module (#2636 D6). The route that collects
 * `SettingsViewModel` state stays in app2 (`LanguageSettingsRoute`): this
 * composable only paints the choices and fires the caller's lambda.
 */
@Composable
fun LanguageSettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onVoiceLanguageChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsPageScaffold(
        title = "Dictation language",
        pageTag = SETTINGS_LANGUAGE_PAGE_TAG,
        onBack = onBack,
        modifier = modifier,
    ) {
        item { SectionHeader(label = "Speech recognition") }
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                AppSettings.VOICE_LANGUAGE_OPTIONS.forEach { option ->
                    QuietChoiceRow(
                        title = option.label,
                        subtitle = if (option.code == AppSettings.VOICE_LANGUAGE_AUTO) {
                            "Use the device language"
                        } else {
                            option.code.uppercase()
                        },
                        selected = settings.voiceLanguage == option.code,
                        onClick = { onVoiceLanguageChange(option.code) },
                        modifier = Modifier.testTag(voiceLanguageOptionTag(option.code)),
                    )
                }
            }
        }
        item {
            PocketShellButton(
                text = "Done",
                onClick = onBack,
                variant = ButtonVariant.Primary,
                // The scaffold no longer spaces its items (#2804), so a block
                // that is not a divider-bearing row carries its own gap.
                modifier = Modifier.padding(
                    horizontal = PocketShellDensity.screenGutter,
                    vertical = PocketShellSpacing.md,
                ),
            )
        }
    }
}
