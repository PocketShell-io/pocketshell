package com.pocketshell.next.settings

import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.NavigationChevron
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.icons.PocketShellIcons
import com.pocketshell.uikit.theme.PocketShellColors

/**
 * The Voice settings sub-page: dictation language entry point and the
 * review-before-send / recognizer rows.
 *
 * Lives in the shared presentation module (#2636 D6). The route that collects
 * `SettingsViewModel` state stays in app2 (`VoiceSettingsRoute`): this
 * composable only paints the rows and fires the caller's lambdas.
 */
@Composable
fun VoiceSettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onOpenLanguage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val language = AppSettings.VOICE_LANGUAGE_OPTIONS
        .firstOrNull { it.code == settings.voiceLanguage }
        ?.label
        ?: AppSettings.VOICE_LANGUAGE_OPTIONS.first().label
    SettingsPageScaffold(
        title = "Voice",
        pageTag = SETTINGS_VOICE_PAGE_TAG,
        onBack = onBack,
        modifier = modifier,
    ) {
        item { SectionHeader(label = "Dictation") }
        item {
            ListRow(
                title = "Language",
                subtitle = language,
                leading = { androidx.compose.material3.Icon(PocketShellIcons.Mic, null, tint = PocketShellColors.TextSecondary) },
                trailing = { NavigationChevron() },
                onClick = onOpenLanguage,
                modifier = Modifier.testTag("settings-voice-language"),
            )
        }
        item {
            ListRow(
                title = "Review before sending",
                subtitle = "Dictation always stops into an editable draft.",
                leading = {
                    androidx.compose.material3.Icon(
                        PocketShellIcons.Eye,
                        contentDescription = null,
                        tint = PocketShellColors.TextSecondary,
                    )
                },
                modifier = Modifier.testTag(SETTINGS_VOICE_REVIEW_TAG),
            )
        }
        item {
            ListRow(
                title = "Speech recognition",
                subtitle = "System recognizer",
                leading = {
                    androidx.compose.material3.Icon(
                        PocketShellIcons.Mic,
                        contentDescription = null,
                        tint = PocketShellColors.TextSecondary,
                    )
                },
                modifier = Modifier.testTag(SETTINGS_VOICE_RECOGNITION_TAG),
            )
        }
        item {
            SettingsDescription(
                title = "Microphone access",
                description = "PocketShell asks for microphone access when you start dictating.",
            )
        }
    }
}
