package com.pocketshell.uikit.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.height
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import com.pocketshell.uikit.theme.PocketShellType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * One row grammar across the shared row primitives (#2804, #2635 audit P-2/P-3).
 *
 * The audit found three treatments for the same job — a title with one
 * supporting line — rendered side by side on one settings sub-page. [ListRow]
 * used the [PocketShellType.metadata] rung on [PocketShellColors.TextMuted],
 * [QuietChoiceRow] the same rung on [PocketShellColors.TextSecondary], and the
 * settings blocks the [PocketShellType.body] rung on `TextSecondary`. These
 * tests read the rung AND the colour back off the text layout Compose actually
 * produced — Material3's `Text` merges its `color` argument into the style it
 * hands `BasicText`, so this is the rendered treatment, not a second copy of
 * the source.
 *
 * The divider cases cover the other half of the grammar: `showDivider` is the
 * escape hatch a deliberately gapped block uses instead of doubling a hairline
 * into a gap, and `RowRhythmGuardTest` (in `:app2`) fails the build on any list
 * that keeps both.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class RowGrammarTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun listRowSupportingLineIsTheMetadataRungOnTextMuted() {
        composeRule.setContent {
            PocketShellTheme {
                ListRow(title = TITLE, subtitle = SUPPORTING)
            }
        }
        assertSupportingLineGrammar(supportingLineStyle())
    }

    @Test
    fun quietChoiceRowSupportingLineMatchesListRow() {
        // The P-2 red arm: before #2804 this line rendered the same 11sp rung
        // on TextSecondary — one of the three treatments the audit counted.
        composeRule.setContent {
            PocketShellTheme {
                QuietChoiceRow(
                    title = "Auto-detect",
                    subtitle = SUPPORTING,
                    selected = false,
                    onClick = {},
                )
            }
        }
        assertSupportingLineGrammar(supportingLineStyle())
    }

    @Test
    fun quietChoiceRowTitleIsTheSharedRowTitleRung() {
        composeRule.setContent {
            PocketShellTheme {
                QuietChoiceRow(title = TITLE, selected = true, onClick = {})
            }
        }
        val style = composeRule.onNodeWithText(TITLE, useUnmergedTree = true).textStyle()
        assertEquals(PocketShellType.body.fontSize, style.fontSize)
        assertEquals(PocketShellColors.Text, style.color)
    }

    @Test
    fun quietChoiceRowKeepsRowLevelRadioSemanticsAndItsTestTag() {
        var clicks = 0
        composeRule.setContent {
            PocketShellTheme {
                Column {
                    QuietChoiceRow(
                        title = "English",
                        subtitle = "EN",
                        selected = true,
                        onClick = {},
                        modifier = Modifier.testTag(SELECTED_TAG),
                    )
                    QuietChoiceRow(
                        title = "German",
                        subtitle = "DE",
                        selected = false,
                        onClick = { clicks++ },
                        modifier = Modifier.testTag(UNSELECTED_TAG),
                    )
                }
            }
        }

        // The caller's tag still lands on the node that carries the selection,
        // and that node still merges the row's text — every existing caller
        // finds its row by tag and reads the title through it.
        composeRule.onNodeWithTag(SELECTED_TAG)
            .assertIsDisplayed()
            .assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assertTextContains("English")
        composeRule.onNodeWithTag(UNSELECTED_TAG)
            .assertIsNotSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .performClick()
        composeRule.runOnIdle { assertEquals(1, clicks) }

        // One announced control per row: the mark stays decorative, so no
        // nested radio target appears beside the row's own.
        assertEquals(
            2,
            composeRule.onAllNodes(
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton),
            ).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun listRowPaintsItsDividerByDefaultAndDropsItOnRequest() {
        composeRule.setContent {
            PocketShellTheme {
                Column {
                    Measured(WITH_DIVIDER_TAG) { ListRow(title = TITLE, subtitle = SUPPORTING) }
                    Measured(WITHOUT_DIVIDER_TAG) {
                        ListRow(title = TITLE, subtitle = SUPPORTING, showDivider = false)
                    }
                }
            }
        }
        assertDividerCostsExactlyOneHairline()
    }

    @Test
    fun quietChoiceRowForwardsTheDividerSwitch() {
        composeRule.setContent {
            PocketShellTheme {
                Column {
                    Measured(WITH_DIVIDER_TAG) {
                        QuietChoiceRow(
                            title = TITLE,
                            subtitle = SUPPORTING,
                            selected = false,
                            onClick = {},
                        )
                    }
                    Measured(WITHOUT_DIVIDER_TAG) {
                        QuietChoiceRow(
                            title = TITLE,
                            subtitle = SUPPORTING,
                            selected = false,
                            onClick = {},
                            showDivider = false,
                        )
                    }
                }
            }
        }
        assertDividerCostsExactlyOneHairline()
    }

    @Test
    fun workspaceRowForwardsTheDividerSwitch() {
        composeRule.setContent {
            PocketShellTheme {
                Column {
                    Measured(WITH_DIVIDER_TAG) {
                        WorkspaceRow(title = TITLE, subtitle = SUPPORTING, onClick = {})
                    }
                    Measured(WITHOUT_DIVIDER_TAG) {
                        WorkspaceRow(
                            title = TITLE,
                            subtitle = SUPPORTING,
                            onClick = {},
                            showDivider = false,
                        )
                    }
                }
            }
        }
        assertDividerCostsExactlyOneHairline()
    }

    /**
     * Wraps a row in a tagged box so the measurement covers the divider too.
     * A row's own `modifier` lands on its clickable Row when it has an
     * `onClick` and on its outer column when it does not, so measuring the row
     * node directly would measure two different things.
     */
    @Composable
    private fun Measured(tag: String, row: @Composable () -> Unit) {
        Box(modifier = Modifier.testTag(tag)) { row() }
    }

    private fun assertSupportingLineGrammar(style: TextStyle) {
        assertEquals(
            "the supporting line must stay on the metadata rung (#2804)",
            PocketShellType.metadata.fontSize,
            style.fontSize,
        )
        assertEquals(
            "the supporting line must stay on the muted text token (#2804)",
            PocketShellColors.TextMuted,
            style.color,
        )
    }

    private fun assertDividerCostsExactlyOneHairline() {
        val withDivider = composeRule.onNodeWithTag(WITH_DIVIDER_TAG)
            .getUnclippedBoundsInRoot().height
        val withoutDivider = composeRule.onNodeWithTag(WITHOUT_DIVIDER_TAG)
            .getUnclippedBoundsInRoot().height
        assertEquals(
            "the default must paint exactly the 1dp hairline and showDivider = false must " +
                "drop it; with=$withDivider without=$withoutDivider",
            1f,
            (withDivider - withoutDivider).value,
            0.01f,
        )
    }

    private fun supportingLineStyle(): TextStyle =
        composeRule.onNodeWithText(SUPPORTING, useUnmergedTree = true).textStyle()

    private fun SemanticsNodeInteraction.textStyle(): TextStyle {
        val results = mutableListOf<TextLayoutResult>()
        val action = checkNotNull(
            fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action,
        ) { "text node exposes no GetTextLayoutResult action" }
        action(results)
        return results.first().layoutInput.style
    }

    private companion object {
        const val TITLE = "agent-main"
        const val SUPPORTING = "Use the device language"
        const val SELECTED_TAG = "row-grammar-selected"
        const val UNSELECTED_TAG = "row-grammar-unselected"
        const val WITH_DIVIDER_TAG = "row-grammar-with-divider"
        const val WITHOUT_DIVIDER_TAG = "row-grammar-without-divider"
    }
}
