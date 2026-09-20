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
 * The "Keep connection" (background grace) picker sub-page.
 *
 * Lives in the shared presentation module (#2636 D6). The route that collects
 * `SettingsViewModel` state stays in app2 (`GraceSettingsRoute`): this
 * composable only paints the choices and fires the caller's lambda.
 */
@Composable
fun GraceSettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onBackgroundGraceChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsPageScaffold(
        title = "Keep connection",
        pageTag = SETTINGS_GRACE_PAGE_TAG,
        onBack = onBack,
        modifier = modifier,
    ) {
        item { SectionHeader(label = "When you leave the app") }
        item {
            SettingsDescription(
                title = "Choose how long a live connection stays available.",
                description = "This controls the phone’s connection. Remote sessions are not ended by this setting.",
            )
        }
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                AppSettings.BACKGROUND_GRACE_OPTIONS.forEach { option ->
                    QuietChoiceRow(
                        title = option.label,
                        subtitle = graceDescription(option.millis),
                        selected = settings.backgroundGraceMillis == option.millis,
                        onClick = { onBackgroundGraceChange(option.millis) },
                        modifier = Modifier.testTag(backgroundGraceOptionTag(option.millis)),
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

private fun graceDescription(millis: Long): String = when (millis) {
    AppSettings.BACKGROUND_GRACE_30_SECONDS_MS -> "Good for switching apps"
    AppSettings.BACKGROUND_GRACE_1_MINUTE_MS -> "More time between app switches"
    AppSettings.BACKGROUND_GRACE_90_SECONDS_MS -> "Default recovery window"
    AppSettings.BACKGROUND_GRACE_5_MINUTES_MS -> "Longer app switches"
    AppSettings.BACKGROUND_GRACE_10_MINUTES_MS -> "Extended recovery window"
    else -> "Custom recovery window"
}
