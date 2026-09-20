package com.pocketshell.next.hosts

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.height
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #2808 (audit D-5): where "Add host" lives on each state of the host
 * list.
 *
 * The populated list used to end with a full-width accent button — the same
 * foot button the desktop client deleted — which spent a permanent 56 dp row
 * plus its padding on one action and left ~40% of the screen blank beneath it.
 * It moves into the header's trailing slot as a `+`. The EMPTY state keeps the
 * big accent button, because there it is not chrome: it is the only thing the
 * screen is for.
 *
 * Both states put the action on the same [HOST_LIST_ADD_TAG], and exactly one
 * node carries it at a time, so every existing journey that taps that tag
 * keeps working on both.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class HostListAddActionTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * The D-5 regression oracle. On the pre-#2808 screen the populated list
     * painted a `PocketShellButton` reading "Add host" below the Tools
     * section; the header carried no trailing action at all. Both halves are
     * asserted, because dropping the footer without adding the header action
     * would also satisfy half of them.
     */
    @Test
    fun `the populated list carries add in the header and no footer button`() {
        setContent(populatedState())

        // Nothing on the populated list paints the WORDS "Add host" any more —
        // the footer button was the only node that did. This is the assertion
        // that goes red on the old screen.
        composeRule.onAllNodesWithText("Add host").assertCountEquals(0)

        // The action is still there, once, as the header's `+` glyph, and it
        // is a 48 dp icon affordance rather than a 56 dp full-width button.
        composeRule.onAllNodesWithTag(HOST_LIST_ADD_TAG).assertCountEquals(1)
        composeRule.onNodeWithContentDescription("Add host").assertIsDisplayed()
        assertEquals(
            PocketShellDensity.tapTargetMin,
            composeRule.onNodeWithTag(HOST_LIST_ADD_TAG).getUnclippedBoundsInRoot().height,
        )

        // "In the header" is a position, not a tag: the add action must sit
        // entirely ABOVE the list it used to hang off the bottom of.
        val addBottom = composeRule.onNodeWithTag(HOST_LIST_ADD_TAG)
            .getUnclippedBoundsInRoot()
            .bottom
        val listTop = composeRule.onNodeWithTag(HOST_LIST_TAG)
            .getUnclippedBoundsInRoot()
            .top
        assertTrue("add action at $addBottom is not above the list at $listTop", addBottom <= listTop)
    }

    /** The header `+` opens the same setup sheet the footer button opened. */
    @Test
    fun `the header action opens the add host sheet`() {
        setContent(populatedState())

        composeRule.onNodeWithTag(HOST_LIST_ADD_TAG).performClick()
        composeRule.onNodeWithTag(HOST_LIST_ADD_METHODS_TAG).assertExists()
    }

    /**
     * The empty state is untouched by D-5: the big accent call to action
     * stays, and the header does NOT also grow a `+` (two add affordances on
     * a screen whose only job is adding would be noise, and two nodes on one
     * tag would make `onNodeWithTag(HOST_LIST_ADD_TAG)` ambiguous).
     */
    @Test
    fun `the empty state still leads with the big accent add host button`() {
        setContent(HostListUiState(loaded = true))

        composeRule.onAllNodesWithTag(HOST_LIST_ADD_TAG).assertCountEquals(1)
        composeRule.onNodeWithTag(HOST_LIST_ADD_TAG).assertIsDisplayed()
        composeRule.onAllNodesWithText("Add host").assertCountEquals(1)
        assertTrue(
            "the empty-state action is not a full-height accent button",
            composeRule.onNodeWithTag(HOST_LIST_ADD_TAG)
                .getUnclippedBoundsInRoot()
                .height >= PocketShellDensity.buttonMin,
        )
    }

    /** Before Room's first emission neither state is painted, so neither is the action. */
    @Test
    fun `an unloaded list paints no add action at all`() {
        setContent(HostListUiState(loaded = false))

        composeRule.onAllNodesWithTag(HOST_LIST_ADD_TAG).assertCountEquals(0)
    }

    private fun setContent(state: HostListUiState) {
        composeRule.setContent {
            PocketShellTheme {
                HostListScreen(
                    state = state,
                    onOpenHost = {},
                    onAddHost = {},
                    onEditHost = {},
                    onOpenSettings = {},
                    onDeleteHost = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun populatedState() = HostListUiState(
        loaded = true,
        hosts = listOf(
            HostRow(1, "hetzner", "alexey@135.181.114.209"),
            HostRow(2, "builder", "root@10.0.0.7"),
        ),
    )
}
