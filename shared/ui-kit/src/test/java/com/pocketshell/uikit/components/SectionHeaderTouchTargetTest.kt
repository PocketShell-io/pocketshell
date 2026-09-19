package com.pocketshell.uikit.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
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
 * Issue #2790: the 48 dp a11y touch floor belongs to *interactive* elements.
 *
 * [SectionHeader] used to apply `defaultMinSize(minHeight = tapTargetMin)` to
 * its label block unconditionally — before the `onLabelClick` branch decided
 * whether the block was clickable at all. A non-interactive heading has no hit
 * area to protect, so an 11 sp muted word reserved 48 dp (+ 2 × `sm` outer
 * padding = 64 dp). On the hosts screen that put 115.6 dp between the screen
 * title and the first real row, with one 8.3 dp word inside it.
 *
 * Both branches are pinned here so the floor cannot silently come back on the
 * non-clickable side, and cannot silently be lost on the clickable side.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class SectionHeaderTouchTargetTest {

    @get:Rule
    val composeRule = createComposeRule()

    private companion object {
        const val HEADER_TAG = "section-header-root"
        const val LABEL_TAG = "section-header-label"
        const val TRAILING_TAG = "section-header-trailing"

        /** The outer [SectionHeader] `Row` pads `sm` above and below the label block. */
        val OUTER_PADDING = PocketShellSpacing.sm * 2
    }

    private fun setHeader(onLabelClick: (() -> Unit)?) {
        composeRule.setContent {
            PocketShellTheme {
                SectionHeader(
                    label = "Hosts",
                    modifier = Modifier.testTag(HEADER_TAG),
                    count = 2,
                    onLabelClick = onLabelClick,
                    labelTestTag = LABEL_TAG,
                )
            }
        }
    }

    private fun labelBlockHeight(): Dp =
        composeRule.onNodeWithTag(LABEL_TAG).getUnclippedBoundsInRoot().height

    private fun headerHeight(): Dp =
        composeRule.onNodeWithTag(HEADER_TAG).getUnclippedBoundsInRoot().height

    @Test
    fun nonClickableLabelDoesNotReserveTheTouchTarget() {
        setHeader(onLabelClick = null)

        val label = labelBlockHeight()
        val header = headerHeight()

        // The load-bearing assertion: a heading nobody can tap must not be
        // floored at the interactive minimum. This is what was red before the
        // #2790 fix (the block measured exactly 48 dp).
        assertTrue(
            "A non-clickable SectionHeader label block measured $label, which still " +
                "clears the ${PocketShellDensity.tapTargetMin} a11y touch floor. That floor is " +
                "for interactive elements; a non-interactive heading has no hit area to " +
                "protect (issue #2790).",
            label < PocketShellDensity.tapTargetMin,
        )

        // Sanity: the label still paints — a zero-height block would satisfy the
        // assertion above vacuously.
        assertTrue(
            "Non-clickable SectionHeader label block collapsed to $label; it should be its " +
                "natural text line box, not nothing.",
            label > 0.dp,
        )

        // The whole header is that natural line box plus the existing `sm`
        // padding on both sides — no hidden floor reintroduced further out.
        assertTrue(
            "A non-clickable SectionHeader measured $header overall; expected its natural " +
                "label line box ($label) plus $OUTER_PADDING of outer padding, i.e. below " +
                "${PocketShellDensity.tapTargetMin + OUTER_PADDING} (issue #2790).",
            header < PocketShellDensity.tapTargetMin + OUTER_PADDING,
        )
    }

    @Test
    fun clickableLabelStillClearsTheTouchTarget() {
        setHeader(onLabelClick = {})

        val label = labelBlockHeight()
        val header = headerHeight()

        // The non-goal half of #2790: a tappable label is an interactive
        // element and keeps the full 48 dp hit area.
        assertTrue(
            "A clickable SectionHeader label block measured $label, below the " +
                "${PocketShellDensity.tapTargetMin} a11y touch floor every interactive element " +
                "must honour (Spacing.kt `tapTargetMin`).",
            label >= PocketShellDensity.tapTargetMin,
        )
        assertTrue(
            "A clickable SectionHeader measured $header overall; expected at least the " +
                "${PocketShellDensity.tapTargetMin} floor plus $OUTER_PADDING of outer padding.",
            header >= PocketShellDensity.tapTargetMin + OUTER_PADDING,
        )
    }

    /**
     * Anti-vacuity: the two branches must actually differ. If some future change
     * made both measure the same, the per-branch assertions above could both be
     * satisfiable by a single geometry and would stop pinning anything.
     */
    @Test
    fun clickableBranchIsTallerThanNonClickableBranch() {
        setHeader(onLabelClick = null)
        val quietLabel = labelBlockHeight()
        val quietHeader = headerHeight()

        composeRule.runOnIdle { }
        val saved = PocketShellDensity.tapTargetMin - quietLabel

        assertTrue(
            "Dropping the touch floor from a non-clickable SectionHeader saved only $saved; " +
                "#2790 measured ~32 dp per header (48 dp floor minus an ~11 sp line box). A much " +
                "smaller saving means the floor is still effectively applied.",
            saved >= 24.dp,
        )
        assertTrue(
            "Non-clickable header ($quietHeader) should be shorter than the clickable floor " +
                "(${PocketShellDensity.tapTargetMin + OUTER_PADDING}).",
            quietHeader < PocketShellDensity.tapTargetMin + OUTER_PADDING,
        )
    }

    /**
     * The one collateral case the #2790 fix could plausibly have shrunk: a
     * non-clickable header whose `trailing` slot IS interactive
     * (`ReorderWorkspacesScreen`'s move controls, `HostWorkspacesScreen`'s
     * "+ Add"). The label block no longer floors the outer row, so the trailing
     * action's touch target must stand on its own — which it does, because
     * every visible [PocketShellButton] carries its own 56 dp minimum.
     */
    @Test
    fun nonClickableHeaderWithInteractiveTrailingKeepsThatTouchTarget() {
        composeRule.setContent {
            PocketShellTheme {
                SectionHeader(
                    label = "~/git",
                    modifier = Modifier.testTag(HEADER_TAG),
                    count = 3,
                    trailing = {
                        PocketShellButton(
                            text = "+ Add",
                            onClick = {},
                            variant = ButtonVariant.Text,
                            modifier = Modifier.testTag(TRAILING_TAG),
                        )
                    },
                )
            }
        }

        val trailing = composeRule.onNodeWithTag(TRAILING_TAG).getUnclippedBoundsInRoot().height
        assertTrue(
            "The interactive trailing action measured $trailing, below the " +
                "${PocketShellDensity.tapTargetMin} a11y touch floor. Dropping the label " +
                "block's floor (#2790) must not shrink a trailing action's hit area.",
            trailing >= PocketShellDensity.tapTargetMin,
        )
        assertTrue(
            "The header (${headerHeight()}) must still contain its trailing action ($trailing).",
            headerHeight() >= trailing,
        )
    }
}
