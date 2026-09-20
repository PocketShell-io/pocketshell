package com.pocketshell.uikit.components

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Issue #2806: a kebab silently made a [ListRow] ~17 dp taller than a visually
 * identical row without one.
 *
 * [KebabTrigger] is a hard-sized 48 dp `IconButton`. The row used to apply its
 * 16 dp `rowPadV` to *every* child — so the trailing affordance measured
 * 48 + 2 × 16 = 80 dp and drove the whole row past both the 56 dp floor and the
 * ~64 dp a title-plus-subtitle text block lands on. On the hosts screen that
 * put an unexplained step between the host rows and the "SSH keys" row directly
 * under them, which a user reads as the same kind of thing. The issue quotes
 * 81.0 dp against 64.3 dp because it measured divider-to-divider off a render;
 * the rows themselves measure 80.0 dp against 63.3 dp here, the same numbers
 * plus [androidx.compose.material3.HorizontalDivider]'s 1 dp rule.
 *
 * The fix moves `rowPadV` onto the text column only: the row's height is its
 * text block plus padding, floored at `rowMinHeight`, and a slot affordance is
 * centred inside whatever that comes to instead of inflating it.
 *
 * Every assertion here is written against the density tokens rather than
 * literal dp, so the sizing work in #2800's lane stays free to move the floor.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class ListRowKebabHeightTest {

    @get:Rule
    val composeRule = createComposeRule()

    private companion object {
        const val PLAIN_ROW_TAG = "row-plain"
        const val KEBAB_ROW_TAG = "row-kebab"
        const val TRIGGER_ROW_TAG = "row-kebab-trigger"
        const val COMPACT_ROW_TAG = "row-compact"
        const val COMPACT_KEBAB_ROW_TAG = "row-compact-kebab"
        const val KEBAB_BUTTON = "row-kebab-button"
        const val TRIGGER_BUTTON = "row-kebab-trigger-button"
        const val COMPACT_KEBAB_BUTTON = "row-compact-kebab-button"

        /** The exact content of the two hosts-screen rows issue #2806 measured. */
        const val TITLE = "hetzner"
        const val SUBTITLE = "alexey@135.181.114.209"

        /**
         * Float slack for a dp value that round-trips through device pixels:
         * 48 dp at xxhdpi is 144 px, and 144 px back to dp reads 47.999992.
         * One pixel at this density is 0.333 dp, so 0.05 dp absorbs only the
         * conversion error and never a real short measurement.
         */
        val PIXEL_SLACK = 0.05.dp

        /** A title that cannot fit one 412 dp line, for the growth case. */
        const val LONG_TITLE =
            "a workspace label long enough to need a second line on a 412 dp phone screen"
    }

    /**
     * All three variants in ONE composition so the heights are measured under
     * identical density and font conditions — a number carried over from a
     * separate composition would prove nothing. The title is state-driven so
     * the wrapped case recomposes this same tree rather than building a second
     * one, for the same reason.
     */
    private var title by mutableStateOf(TITLE)
    private var titleMaxLines by mutableStateOf(1)

    private fun setRows() {
        title = TITLE
        titleMaxLines = 1
        composeRule.setContent {
            PocketShellTheme {
                Column {
                    ListRow(
                        title = title,
                        subtitle = SUBTITLE,
                        titleMaxLines = titleMaxLines,
                        onClick = {},
                        modifier = Modifier.testTag(PLAIN_ROW_TAG),
                    )
                    ListRow(
                        title = title,
                        subtitle = SUBTITLE,
                        titleMaxLines = titleMaxLines,
                        onClick = {},
                        trailing = {
                            Kebab(
                                items = listOf(KebabItem(label = "Edit", onClick = {})),
                                triggerTestTag = KEBAB_BUTTON,
                            )
                        },
                        modifier = Modifier.testTag(KEBAB_ROW_TAG),
                    )
                    ListRow(
                        title = title,
                        subtitle = SUBTITLE,
                        titleMaxLines = titleMaxLines,
                        onClick = {},
                        trailing = {
                            KebabTrigger(
                                contentDescription = "More actions",
                                onClick = {},
                                triggerTestTag = TRIGGER_BUTTON,
                            )
                        },
                        modifier = Modifier.testTag(TRIGGER_ROW_TAG),
                    )
                    // The subtitle-less pair: the same oracle at the floor,
                    // and the reference the anti-collapse check leans on.
                    ListRow(
                        title = title,
                        titleMaxLines = titleMaxLines,
                        onClick = {},
                        modifier = Modifier.testTag(COMPACT_ROW_TAG),
                    )
                    ListRow(
                        title = title,
                        titleMaxLines = titleMaxLines,
                        onClick = {},
                        trailing = {
                            KebabTrigger(
                                contentDescription = "More actions",
                                onClick = {},
                                triggerTestTag = COMPACT_KEBAB_BUTTON,
                            )
                        },
                        modifier = Modifier.testTag(COMPACT_KEBAB_ROW_TAG),
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun wrapTheTitle() {
        title = LONG_TITLE
        titleMaxLines = 2
        composeRule.waitForIdle()
    }

    private fun rowHeight(tag: String): Dp =
        composeRule.onNodeWithTag(tag).getUnclippedBoundsInRoot().height

    /**
     * The bug's exact oracle: same text, same everything, one has an overflow
     * affordance. Before the fix this measured 80.0 dp vs 64.3 dp.
     */
    @Test
    fun aKebabDoesNotChangeTheRowHeight() {
        setRows()

        val plain = rowHeight(PLAIN_ROW_TAG)
        val withKebab = rowHeight(KEBAB_ROW_TAG)
        val withTrigger = rowHeight(TRIGGER_ROW_TAG)

        assertEquals(
            "A ListRow with a Kebab measured $withKebab against $plain for the identical " +
                "title/subtitle without one. An overflow affordance must not change the " +
                "row's height (issue #2806).",
            plain.value,
            withKebab.value,
            0.01f,
        )
        assertEquals(
            "A ListRow with a bare KebabTrigger measured $withTrigger against $plain for the " +
                "identical title/subtitle without one. Both trailing shapes are in use across " +
                "the app, so both are pinned (issue #2806).",
            plain.value,
            withTrigger.value,
            0.01f,
        )

        // The same oracle one content-height down: a title-only row sits on the
        // floor with or without the affordance. Before the fix this pair read
        // 80.0 dp against 56.0 dp — the widest version of the step.
        val compact = rowHeight(COMPACT_ROW_TAG)
        val compactWithKebab = rowHeight(COMPACT_KEBAB_ROW_TAG)
        assertEquals(
            "A subtitle-less ListRow with a kebab measured $compactWithKebab against $compact " +
                "without one (issue #2806).",
            compact.value,
            compactWithKebab.value,
            0.01f,
        )

        // Anti-vacuity: equal-and-collapsed would satisfy every assertion above
        // while destroying the rows. Two independent floors pin that.
        assertTrue(
            "The subtitle-less reference row measured $compact; every standard row stands on " +
                "the ${PocketShellDensity.rowMinHeight} floor.",
            compact >= PocketShellDensity.rowMinHeight - PIXEL_SLACK,
        )
        assertTrue(
            "A title-plus-subtitle row measured $plain, no taller than the subtitle-less row " +
                "($compact). The row's ${PocketShellDensity.rowPadV} vertical padding still " +
                "belongs to the text block — equalising the two row kinds by deleting that " +
                "padding would collapse both onto the floor instead (issue #2806).",
            plain > compact,
        )
    }

    /**
     * AC2 — the fix must buy the height back from the layout, not from the
     * accessibility floor. Asserted on the real trigger node, both in the
     * [Kebab] wrapper and standalone.
     */
    @Test
    fun theKebabKeepsItsTouchTarget() {
        setRows()

        listOf(KEBAB_BUTTON, TRIGGER_BUTTON, COMPACT_KEBAB_BUTTON).forEach { tag ->
            val bounds = composeRule.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            assertTrue(
                "The kebab trigger '$tag' measured ${bounds.width} × ${bounds.height}; both " +
                    "sides must stay at or above the ${PocketShellDensity.tapTargetMin} a11y " +
                    "touch floor (issue #2806 must not pay for row height with hit area).",
                bounds.width >= PocketShellDensity.tapTargetMin - PIXEL_SLACK &&
                    bounds.height >= PocketShellDensity.tapTargetMin - PIXEL_SLACK,
            )
        }
    }

    /**
     * The row no longer reserves a 48 dp band for the affordance, so where the
     * trigger *sits* in the row is now load-bearing rather than incidental: it
     * has to be optically centred against the title/subtitle block, which is
     * what `verticalAlignment = Alignment.CenterVertically` on the row buys.
     *
     * Containment is asserted alongside it for completeness, though note it is
     * structurally guaranteed — a `Row`'s height is the max over its children,
     * so a child can only leave the row via an explicit offset or a layout
     * modifier that under-reports. The centre check is the discriminating half.
     */
    @Test
    fun theKebabStaysCentredInTheRow() {
        setRows()

        listOf(
            KEBAB_ROW_TAG to KEBAB_BUTTON,
            TRIGGER_ROW_TAG to TRIGGER_BUTTON,
            COMPACT_KEBAB_ROW_TAG to COMPACT_KEBAB_BUTTON,
        ).forEach { (rowTag, buttonTag) ->
            val row = composeRule.onNodeWithTag(rowTag).getUnclippedBoundsInRoot()
            val button = composeRule.onNodeWithTag(buttonTag).getUnclippedBoundsInRoot()

            val rowCentre = (row.top + row.bottom) / 2f
            val buttonCentre = (button.top + button.bottom) / 2f
            assertEquals(
                "The kebab '$buttonTag' centres on $buttonCentre inside a row ('$rowTag') " +
                    "centred on $rowCentre. With the row no longer sized around the trigger, " +
                    "the trigger has to be centred against the text block rather than pinned " +
                    "to an edge (issue #2806).",
                rowCentre.value,
                buttonCentre.value,
                0.34f, // one xxhdpi pixel
            )
            assertTrue(
                "The kebab '$buttonTag' spans ${button.top}..${button.bottom} but its row " +
                    "'$rowTag' only spans ${row.top}..${row.bottom}.",
                button.top >= row.top - PIXEL_SLACK && button.bottom <= row.bottom + PIXEL_SLACK,
            )
        }
    }

    /**
     * AC3 — the non-goal half. Rows that genuinely need the space still get it:
     * a two-line title grows all three variants, and they grow together.
     */
    @Test
    fun aWrappedTitleStillGrowsTheRow() {
        setRows()
        val singleLine = rowHeight(PLAIN_ROW_TAG)
        val singleLineWithKebab = rowHeight(KEBAB_ROW_TAG)

        wrapTheTitle()
        val wrapped = rowHeight(PLAIN_ROW_TAG)
        val wrappedWithKebab = rowHeight(KEBAB_ROW_TAG)

        assertTrue(
            "A two-line title measured $wrapped, no taller than the single-line row " +
                "($singleLine). Capping the row would have broken every wrapped title " +
                "(issue #2806 non-goal).",
            wrapped > singleLine,
        )
        assertTrue(
            "A two-line title with a kebab measured $wrappedWithKebab, no taller than the " +
                "single-line kebab row ($singleLineWithKebab).",
            wrappedWithKebab > singleLineWithKebab,
        )
        assertEquals(
            "A wrapped row with a kebab ($wrappedWithKebab) must still match the wrapped row " +
                "without one ($wrapped) — the fix has to hold at every content height, not " +
                "only the single-line case.",
            wrapped.value,
            wrappedWithKebab.value,
            0.01f,
        )
    }
}
