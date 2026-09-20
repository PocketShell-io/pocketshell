package com.pocketshell.next.settings

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The settings half of the one row grammar (#2804, #2635 audit P-2).
 *
 * `SettingsDescription` and `SettingsSlider` are `internal` to
 * `:shared:ui-screens`, and that module has no Compose test harness, so the
 * reachable way to exercise them is the settings sub-pages app2 already
 * renders. Both blocks used to draw their supporting line at the
 * [PocketShellType.body] rung (14sp) on [PocketShellColors.TextSecondary] —
 * the third and fourth of the treatments the audit counted, sitting on the
 * same page as a `ListRow` and a `QuietChoiceRow` drawing theirs at 11sp.
 *
 * The rung and the colour are read back off the text layout Compose produced
 * (Material3's `Text` merges its `color` into the style it lays out with), so
 * this is the rendered treatment rather than a second copy of the source.
 * `RowGrammarTest` in `:shared:ui-kit` pins the other two components against
 * the same pair.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h1200dp")
class SettingsRowGrammarTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun settingsSliderSupportingLineIsTheMetadataRungOnTextMuted() {
        composeRule.setContent {
            AdvancedSettingsScreen(
                settings = AppSettings(),
                onBack = {},
                onVoiceSilenceChange = {},
                onUsageWarnThresholdChange = {},
                onAgentSubmitEnterDelayChange = {},
            )
        }

        assertSupportingLineGrammar(SLIDER_DESCRIPTION)
    }

    private fun assertSupportingLineGrammar(text: String) {
        val style = renderedStyleOf(text)
        assertEquals(
            "the settings supporting line must sit on the shared metadata rung (#2804)",
            PocketShellType.metadata.fontSize,
            style.fontSize,
        )
        assertEquals(
            "the settings supporting line must sit on the shared muted text token (#2804)",
            PocketShellColors.TextMuted,
            style.color,
        )
    }

    private fun renderedStyleOf(text: String): TextStyle {
        val results = mutableListOf<TextLayoutResult>()
        val layout = checkNotNull(
            composeRule.onNodeWithText(text, useUnmergedTree = true)
                .fetchSemanticsNode()
                .config[SemanticsActions.GetTextLayoutResult]
                .action,
        ) { "no text layout to read for: $text" }
        layout(results)
        return results.first().layoutInput.style
    }

    private companion object {
        const val SLIDER_DESCRIPTION =
            "Pause after pasted input before sending Enter. Change only if input is left unsubmitted."
    }
}
