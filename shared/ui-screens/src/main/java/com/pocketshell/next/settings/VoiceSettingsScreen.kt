package com.pocketshell.next.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.pocketshell.uikit.components.DisclosureIcon
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.QuietChoiceRow
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.icons.PocketShellIcons
import com.pocketshell.uikit.theme.PocketShellColors

/**
 * The Voice settings sub-page: the dictation-language choice group and the
 * review-before-send / recognizer rows.
 *
 * Lives in the shared presentation module (#2636 D6). The route that collects
 * `SettingsViewModel` state stays in app2 (`VoiceSettingsRoute`): this
 * composable only paints the rows and fires the caller's lambdas.
 *
 * ## The language group expands in place (#2814 N-3)
 *
 * It used to be `Destination.VoiceLanguage` — a whole route, scaffold and
 * header whose entire payload was one six-option [QuietChoiceRow] group plus a
 * "Done" button. That cost four taps from launch to change a dictation
 * language and carried a destination for a choice the parent row already
 * summarises. The group now opens under its own row behind the canonical
 * [DisclosureIcon], and the route is deleted (D22 hard cut — no flag, no
 * "old page still reachable").
 *
 * Picking an option IS the "Done" affordance's replacement: the choice is
 * written and the group collapses, so the row's subtitle — the one summary the
 * user came to change — is what they are left looking at.
 *
 * @param initiallyExpanded render/test seam: start with the language group
 *   open. Production always starts collapsed; the design renders need the open
 *   state without a click, because their frame clock is frozen (#2733).
 */
@Composable
fun VoiceSettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onVoiceLanguageChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
) {
    val language = AppSettings.VOICE_LANGUAGE_OPTIONS
        .firstOrNull { it.code == settings.voiceLanguage }
        ?.label
        ?: AppSettings.VOICE_LANGUAGE_OPTIONS.first().label
    var languageExpanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
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
                trailing = { DisclosureIcon(expanded = languageExpanded) },
                onClick = { languageExpanded = !languageExpanded },
                modifier = Modifier.testTag(SETTINGS_VOICE_LANGUAGE_TAG),
            )
        }
        item {
            AnimatedVisibility(visible = languageExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SETTINGS_VOICE_LANGUAGE_GROUP_TAG),
                ) {
                    AppSettings.VOICE_LANGUAGE_OPTIONS.forEach { option ->
                        QuietChoiceRow(
                            title = option.label,
                            subtitle = if (option.code == AppSettings.VOICE_LANGUAGE_AUTO) {
                                "Use the device language"
                            } else {
                                option.code.uppercase()
                            },
                            selected = settings.voiceLanguage == option.code,
                            onClick = {
                                onVoiceLanguageChange(option.code)
                                languageExpanded = false
                            },
                            modifier = Modifier.testTag(voiceLanguageOptionTag(option.code)),
                        )
                    }
                }
            }
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
