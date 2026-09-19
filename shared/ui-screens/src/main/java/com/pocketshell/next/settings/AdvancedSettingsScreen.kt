package com.pocketshell.next.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.theme.PocketShellDensity
import kotlin.math.roundToInt

/**
 * The Advanced settings sub-page: agent submit delay, silence window and usage
 * warning threshold sliders plus the reset action.
 *
 * Lives in the shared presentation module (#2636 D6). The route that collects
 * `SettingsViewModel` state stays in app2 (`AdvancedSettingsRoute`): this
 * composable only paints the controls and fires the caller's lambdas.
 */
@Composable
fun AdvancedSettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onVoiceSilenceChange: (Float) -> Unit,
    onUsageWarnThresholdChange: (Int) -> Unit,
    onAgentSubmitEnterDelayChange: (Int) -> Unit,
    onResetAdvancedDefaults: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    SettingsPageScaffold(
        title = "Advanced",
        pageTag = SETTINGS_ADVANCED_PAGE_TAG,
        onBack = onBack,
        modifier = modifier,
    ) {
        item { SectionHeader(label = "Timing") }
        item {
            SettingsSlider(
                title = "Enter-key delay",
                description = "Pause after pasted input before sending Enter. Change only if input is left unsubmitted.",
                value = settings.agentSubmitEnterDelayMs.toFloat(),
                valueLabel = "${settings.agentSubmitEnterDelayMs} ms",
                min = AppSettings.MIN_AGENT_SUBMIT_ENTER_DELAY_MS.toFloat(),
                max = AppSettings.MAX_AGENT_SUBMIT_ENTER_DELAY_MS.toFloat(),
                step = AppSettings.AGENT_SUBMIT_ENTER_DELAY_STEP_MS.toFloat(),
                onChange = { onAgentSubmitEnterDelayChange(it.roundToInt()) },
                sliderTestTag = SETTINGS_AGENT_SUBMIT_DELAY_SLIDER_TAG,
                valueTestTag = SETTINGS_AGENT_SUBMIT_DELAY_VALUE_TAG,
            )
        }
        item {
            SettingsSlider(
                title = "Silence window",
                description = "Speech-recognizer pause handling. Recording still ends when you tap Stop.",
                value = settings.voiceSilenceThresholdSeconds,
                valueLabel = "${settings.voiceSilenceThresholdSeconds.roundToInt()} s",
                min = AppSettings.MIN_VOICE_SILENCE_SECONDS,
                max = AppSettings.MAX_VOICE_SILENCE_SECONDS,
                step = AppSettings.VOICE_SILENCE_STEP_SECONDS,
                onChange = onVoiceSilenceChange,
                sliderTestTag = SETTINGS_VOICE_SILENCE_SLIDER_TAG,
                valueTestTag = SETTINGS_VOICE_SILENCE_VALUE_TAG,
            )
        }
        item { SectionHeader(label = "Usage") }
        item {
            SettingsSlider(
                title = "Warn at",
                description = "Start warning when a provider quota reaches this percentage. Critical and exceeded states stay fixed.",
                value = settings.usageWarnThresholdPercent.toFloat(),
                valueLabel = "${settings.usageWarnThresholdPercent}%",
                min = AppSettings.MIN_USAGE_WARN_PERCENT.toFloat(),
                max = AppSettings.MAX_USAGE_WARN_PERCENT.toFloat(),
                step = AppSettings.USAGE_WARN_PERCENT_STEP.toFloat(),
                onChange = { onUsageWarnThresholdChange(it.roundToInt()) },
                sliderTestTag = SETTINGS_USAGE_WARN_SLIDER_TAG,
                valueTestTag = SETTINGS_USAGE_WARN_VALUE_TAG,
            )
        }
        item {
            PocketShellButton(
                text = "Reset advanced defaults",
                onClick = onResetAdvancedDefaults,
                variant = ButtonVariant.Secondary,
                modifier = Modifier
                    .padding(horizontal = PocketShellDensity.screenGutter)
                    .testTag(SETTINGS_RESET_ADVANCED_TAG),
            )
        }
    }
}
