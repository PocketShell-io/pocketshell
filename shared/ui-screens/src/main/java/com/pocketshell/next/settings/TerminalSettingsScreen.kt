package com.pocketshell.next.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.icons.PocketShellIcons
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType
import kotlin.math.roundToInt

/**
 * The Terminal settings sub-page: text size, sample text and the common-keys
 * toggle.
 *
 * Lives in the shared presentation module (#2636 D6). The route that collects
 * `SettingsViewModel` state stays in app2 (`TerminalSettingsRoute`): this
 * composable only paints the controls and fires the caller's lambdas.
 */
@Composable
fun TerminalSettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onTerminalTextSizeChange: (Int) -> Unit,
    onShowCommonKeysChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val terminalTextSizeSp = terminalTextSizeSpFromPx(settings.terminalTextSizePx, density)
    SettingsPageScaffold(
        title = "Terminal",
        pageTag = SETTINGS_TERMINAL_PAGE_TAG,
        onBack = onBack,
        modifier = modifier,
    ) {
        item { SectionHeader(label = "Reading") }
        item {
            SettingsSlider(
                title = "Terminal text size",
                description = "Adjust the terminal text size for comfortable reading.",
                value = terminalTextSizeSp,
                valueLabel = "${terminalTextSizeSp.roundToInt()} sp",
                min = AppSettings.MIN_TERMINAL_TEXT_SIZE_SP,
                max = AppSettings.MAX_TERMINAL_TEXT_SIZE_SP,
                step = AppSettings.TERMINAL_TEXT_SIZE_STEP_SP,
                onChange = { onTerminalTextSizeChange(terminalTextSizePxFromSp(it, density)) },
                sliderTestTag = SETTINGS_TERMINAL_SIZE_SLIDER_TAG,
                valueTestTag = SETTINGS_TERMINAL_SIZE_VALUE_TAG,
            )
        }
        item {
            Text(
                text = "\$ git status\nOn branch main\nWorking tree clean",
                color = PocketShellColors.TermText,
                style = PocketShellType.bodyMono.copy(fontSize = terminalTextSizeSp.sp),
                modifier = Modifier
                    .fillMaxWidth()
                    // Own gap: the scaffold no longer spaces its items (#2804).
                    .padding(
                        horizontal = PocketShellDensity.screenGutter,
                        vertical = PocketShellSpacing.md,
                    )
                    .background(PocketShellColors.TermBg, PocketShellShapes.small)
                    .border(1.dp, PocketShellColors.BorderSoft, PocketShellShapes.small)
                    .padding(PocketShellSpacing.md)
                    .testTag(SETTINGS_TERMINAL_SAMPLE_TAG),
            )
        }
        item {
            SettingsDescription(
                title = "Text and input",
                description = "App text follows your Android font-size setting.",
            )
        }
        item { SectionHeader(label = "Input") }
        item {
            ListRow(
                title = "Show common keys",
                subtitle = "Esc, Tab, Ctrl and arrows when typing.",
                leading = {
                    androidx.compose.material3.Icon(
                        PocketShellIcons.Keyboard,
                        contentDescription = null,
                        tint = PocketShellColors.TextSecondary,
                    )
                },
                trailing = {
                    Switch(
                        checked = settings.showCommonKeys,
                        onCheckedChange = onShowCommonKeysChange,
                    )
                },
                modifier = Modifier.testTag(SETTINGS_COMMON_KEYS_TAG),
            )
        }
        item {
            SettingsDescription(
                title = "Input stays separate from the grid",
                description = "The keyboard overlays the terminal without resizing its grid.",
            )
        }
    }
}

/**
 * Converts the renderer's persisted raw pixels into the SP value shown to a
 * user. The terminal view consumes pixels directly, so this conversion belongs
 * at the settings boundary rather than in the terminal package.
 */
fun terminalTextSizeSpFromPx(sizePx: Int, density: Density): Float {
    val pixelsPerSp = pixelsPerSp(density)
    return (sizePx / pixelsPerSp)
        .coerceIn(
            AppSettings.MIN_TERMINAL_TEXT_SIZE_SP,
            AppSettings.MAX_TERMINAL_TEXT_SIZE_SP,
        )
        .roundToInt()
        .toFloat()
}

/** Converts a user-facing SP stop back to the raw pixels stored and rendered. */
fun terminalTextSizePxFromSp(sizeSp: Float, density: Density): Int {
    val pixels = (sizeSp.coerceIn(
        AppSettings.MIN_TERMINAL_TEXT_SIZE_SP,
        AppSettings.MAX_TERMINAL_TEXT_SIZE_SP,
    ) * pixelsPerSp(density)).roundToInt()
    return pixels.coerceIn(
        AppSettings.MIN_TERMINAL_TEXT_SIZE_PX,
        AppSettings.MAX_TERMINAL_TEXT_SIZE_PX,
    )
}

private fun pixelsPerSp(density: Density): Float =
    (density.density * density.fontScale).takeIf { it.isFinite() && it > 0f } ?: 1f
