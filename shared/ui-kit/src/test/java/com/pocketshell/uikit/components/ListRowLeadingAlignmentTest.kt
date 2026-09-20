package com.pocketshell.uikit.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import com.pocketshell.uikit.icons.PocketShellIcons
import com.pocketshell.uikit.model.ConnectionStatus
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Issue #2796 — [ListRow]'s leading slot must not move the title's left edge.
 *
 * ### What broke
 *
 * The comment above the leading `Box` promised "a fixed-width leading box
 * keeps every row's title left edge aligned"; the `Box` had no width modifier,
 * so it took its content's width. Every leading glyph class therefore chose its
 * own title gutter: `screenGutter + <whatever the content measured> + md`. The
 * #2635 audit measured three different left edges in one host-workspaces
 * column — 20.7 dp (no leading), 32.7 dp (a session whose host reported no
 * `agent`, so `SessionKindMark` rendered *nothing*) and ~50.7 dp (an 18 dp
 * mark) — decided by host data rather than by design.
 *
 * ### The contract these tests pin
 *
 * Measured, not eyeballed, and derived from the tokens rather than written
 * down: a row that passes a `leading` lambda starts its title at
 * `screenGutter + icon + md`, whatever that lambda draws; a row that passes no
 * lambda starts at `screenGutter` and gets no phantom gutter
 * ([rowsWithoutALeadingLambdaStayFlushWithTheGutter], the anti-vacuity arm).
 *
 * [leadingWiderThanTheIconBoxKeepsItsOwnWidth] pins the other half of the
 * #2796 decision: the box is a **floor, not a clamp**. Two live call sites
 * hand `ListRow` leading content wider than 24 dp — `ServicesScreen`'s
 * `Icon(modifier = Modifier.padding(xs))` (32 dp) and the session-menu render
 * fixtures' interactive `Checkbox` (48 dp, `minimumInteractiveComponentSize`).
 * A hard `Modifier.width(icon)` would align those too, at the price of
 * squeezing a 24 dp glyph down to 16 dp and dropping an interactive control
 * under the 48 dp hit floor `PocketShellDensity` exists to protect. This test
 * fails if someone later swaps the floor for a clamp.
 */
private typealias Arm = Pair<String, (@Composable () -> Unit)?>

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class ListRowLeadingAlignmentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private companion object {
        /** Device-pixel rounding slack. The relationship is exact in dp here. */
        const val TOLERANCE_DP = 0.5f

        /** One arm of a test tree: a row title and the lambda it leads with. */
        fun arm(label: String, content: @Composable () -> Unit): Arm = label to content

        /** An arm that passes no `leading` lambda at all. */
        fun bare(label: String): Arm = label to null

        /** The leading-glyph width classes that exist across the 72 call sites. */
        val WIDTH_CLASSES: List<Arm> = listOf(
            // `SessionKindMark` before #2796: a lambda that draws nothing at all.
            arm("nothing at all") { Spacer(modifier = Modifier.size(0.dp)) },
            // `StatusDot` / `UsageProviderDot`.
            arm("an 8dp status dot") { StatusDot(status = ConnectionStatus.Connected) },
            // `SessionKindMark` when it does draw.
            arm("an 18dp metadata mark") {
                Box(modifier = Modifier.size(PocketShellDensity.metadataIcon))
            },
            // `ComposerBar` / `ViewerScreen` / `SettingsScreen` sized icons.
            arm("a 20dp sized icon") {
                Icon(
                    imageVector = PocketShellIcons.Terminal,
                    contentDescription = null,
                    tint = PocketShellColors.TextSecondary,
                    modifier = Modifier.size(20.dp),
                )
            },
            // The bare Material3 `Icon` default box — the commonest class.
            arm("a bare 24dp icon") {
                Icon(
                    imageVector = PocketShellIcons.Folder,
                    contentDescription = null,
                    tint = PocketShellColors.TextSecondary,
                )
            },
            // `FileTypeIcon`, which already boxes itself at `icon`.
            arm("a 24dp FileTypeIcon") { FileTypeIcon(iconClass = FileIconClass.FOLDER) },
        )

        const val WIDE_GLYPH_TAG = "wide-leading-glyph"

        /**
         * Tags a Box *wrapping* the control, never the control's own `modifier`.
         * Material3 threads a caller's modifier INSIDE its
         * `minimumInteractiveComponentSize` box, so a `testTag` handed straight
         * to `Checkbox` reports the 24 dp paint and hides the 48 dp footprint
         * this test exists to measure. A wrap-content parent reports the
         * footprint.
         */
        const val CHECKBOX_SLOT_TAG = "interactive-leading-slot"
    }

    /**
     * `setContent` may be called once per test, so every arm lives in the SAME
     * tree: one full-width [ListRow] per width class, titled by that class so
     * a failure names the row that broke rather than an index.
     */
    private fun setRows(arms: List<Arm>) {
        composeRule.setContent {
            PocketShellTheme {
                Column(modifier = Modifier.fillMaxWidth()) {
                    arms.forEach { (title, leading) ->
                        ListRow(
                            title = title,
                            subtitle = "~/proj/agent",
                            leading = leading,
                        )
                    }
                }
            }
        }
    }

    private fun titleLeft(title: String): Dp =
        composeRule.onNodeWithText(title).getUnclippedBoundsInRoot().left

    /** The gutter a row with a leading slot owes its title, straight off the tokens. */
    private val leadingGutter: Dp
        get() = PocketShellDensity.screenGutter + PocketShellDensity.icon + PocketShellSpacing.md

    private fun assertNear(expected: Dp, actual: Dp, what: String) {
        assertTrue(
            "$what measured $actual, expected $expected " +
                "(±$TOLERANCE_DP dp). ListRow's leading slot is sizing itself " +
                "from its content again (#2796).",
            (actual - expected).value in -TOLERANCE_DP..TOLERANCE_DP,
        )
    }

    /**
     * The load-bearing pin (#2796 AC3): every leading width class — including
     * one that draws nothing — puts the title at the same x, and that x is the
     * one the tokens predict.
     */
    @Test
    fun everyLeadingWidthClassSharesOneTitleGutter() {
        setRows(WIDTH_CLASSES)

        val measured = WIDTH_CLASSES.map { (label, _) -> label to titleLeft(label) }
        measured.forEach { (label, left) ->
            assertNear(leadingGutter, left, "The ListRow led by $label put its title at")
        }
        val distinct = measured.map { it.second }.distinct()
        assertTrue(
            "The ${WIDTH_CLASSES.size} leading width classes produced ${distinct.size} " +
                "different title left edges: $measured. One list, one column (#2796).",
            distinct.size == 1,
        )
    }

    /**
     * #2796 AC4 / anti-vacuity. A row that passes no `leading` lambda keeps a
     * flush-left title at the bare screen gutter — no phantom slot, no 24 dp of
     * empty indent — and that x is measurably different from the leading rows',
     * which is what makes [everyLeadingWidthClassSharesOneTitleGutter] mean
     * something. Green before the fix, after it, and under either mutation.
     */
    @Test
    fun rowsWithoutALeadingLambdaStayFlushWithTheGutter() {
        setRows(
            listOf(
                bare("no leading at all"),
                bare("a second bare row"),
                arm("a row that does lead") { FileTypeIcon(iconClass = FileIconClass.FOLDER) },
            ),
        )

        val bareLeft = titleLeft("no leading at all")
        val secondBareLeft = titleLeft("a second bare row")
        val ledLeft = titleLeft("a row that does lead")

        assertNear(
            PocketShellDensity.screenGutter,
            bareLeft,
            "A ListRow with no leading lambda put its title at",
        )
        assertNear(
            PocketShellDensity.screenGutter,
            secondBareLeft,
            "The second leading-less ListRow put its title at",
        )
        assertTrue(
            "A row with a leading slot ($ledLeft) must indent past a row without one " +
                "($bareLeft); if they match, the alignment assertion measures nothing.",
            ledLeft > bareLeft,
        )
    }

    /**
     * The floor-not-clamp decision, as an assertion. A padded 24 dp glyph is
     * 32 dp wide; the leading slot must let it stay 32 dp and keep its glyph at
     * the full `icon` size. Under a hard `Modifier.width(icon)` the padding
     * node would hand the icon only `icon - 2 * xs` and the glyph would render
     * at 16 dp — a shared primitive silently shrinking a caller's artwork.
     */
    @Test
    fun leadingWiderThanTheIconBoxKeepsItsOwnWidth() {
        val padded = PocketShellDensity.icon + PocketShellSpacing.xs * 2
        setRows(
            listOf(
                // Same layout as `ServicesScreen`'s
                // `Icon(modifier = Modifier.padding(xs))`, with the glyph on its
                // own node so it can be measured apart from its padding.
                arm("a padded 32dp glyph") {
                    Box(modifier = Modifier.padding(PocketShellSpacing.xs)) {
                        Icon(
                            imageVector = PocketShellIcons.Ports,
                            contentDescription = null,
                            tint = PocketShellColors.TextSecondary,
                            modifier = Modifier.testTag(WIDE_GLYPH_TAG),
                        )
                    }
                },
            ),
        )

        val glyph = composeRule.onNodeWithTag(WIDE_GLYPH_TAG).getUnclippedBoundsInRoot().width
        assertNear(PocketShellDensity.icon, glyph, "The glyph inside a padded leading slot measured")
        assertNear(
            PocketShellDensity.screenGutter + padded + PocketShellSpacing.md,
            titleLeft("a padded 32dp glyph"),
            "A row led by a padded $padded glyph put its title at",
        )
    }

    /**
     * The a11y half of floor-not-clamp, measured on the slot the row actually
     * gives the control.
     *
     * `SessionMenuRenderFixtures` leads a row with an interactive `Checkbox`,
     * which claims [PocketShellDensity.tapTargetMin] through Material3's
     * `minimumInteractiveComponentSize`. [PocketShellDensity]'s own rule is
     * that shrinking the paint must never shrink the hit area below that
     * floor, so the leading slot must not be allowed to squeeze it.
     *
     * Measured both ways on this tree: under `widthIn(min = icon)` the slot is
     * 48 dp and the title lands at `screenGutter + tapTargetMin + md` = 80 dp;
     * under a hard `Modifier.width(icon)` the same slot collapses to 24 dp and
     * the title to 56 dp — a shared row primitive halving a caller's hit area
     * to buy alignment. That is the trade this test refuses.
     */
    @Test
    fun anInteractiveLeadingKeepsItsTouchFloor() {
        setRows(
            listOf(
                arm("an interactive checkbox") {
                    Box(modifier = Modifier.testTag(CHECKBOX_SLOT_TAG)) {
                        Checkbox(
                            checked = true,
                            onCheckedChange = {},
                            colors = CheckboxDefaults.colors(
                                checkedColor = PocketShellColors.Accent,
                                uncheckedColor = PocketShellColors.TextSecondary,
                            ),
                        )
                    }
                },
            ),
        )

        val slot = composeRule.onNodeWithTag(CHECKBOX_SLOT_TAG).getUnclippedBoundsInRoot().width
        assertTrue(
            "An interactive Checkbox in ListRow's leading slot measured $slot wide, under the " +
                "${PocketShellDensity.tapTargetMin} touch floor. The leading slot is a minimum " +
                "width, never a clamp — a shared row primitive may not shrink a caller's hit " +
                "area (#2796).",
            slot >= PocketShellDensity.tapTargetMin,
        )
        assertNear(
            PocketShellDensity.screenGutter + PocketShellDensity.tapTargetMin +
                PocketShellSpacing.md,
            titleLeft("an interactive checkbox"),
            "A row led by an interactive checkbox put its title at",
        )
    }
}
