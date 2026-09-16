package com.pocketshell.uikit.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issue #2635 2a — the ListRow 4dp rule. The vertical air belongs to the TEXT
 * column, not the row, so a row whose trailing slot carries a 48dp control
 * lands on the same height as every text-only row: the 56dp
 * [PocketShellDensity.rowMinHeight] floor. Padding the row instead (the
 * pre-#2635 layout) makes the control row 48 + 2×pad tall while its
 * neighbours stay short, and this test goes red — which is the point.
 */
@RunWith(RobolectricTestRunner::class)
class ListRowHeightTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a text-only row and a row with a 48dp control share one height`() {
        compose.setContent {
            PocketShellTheme {
                Column {
                    ListRow(
                        title = "text only",
                        onClick = {},
                        modifier = Modifier.testTag("row-under-test"),
                    )
                    ListRow(
                        title = "with 48dp control",
                        trailing = {
                            Box(modifier = Modifier.size(48.dp))
                        },
                        onClick = {},
                        modifier = Modifier.testTag("row-under-test"),
                    )
                }
            }
        }
        compose.waitForIdle()

        val density = compose.density.density
        val heights = compose.onAllNodesWithTag("row-under-test")
            .fetchSemanticsNodes()
            .map { it.size.height / density }
        val floor = with(compose.density) { PocketShellDensity.rowMinHeight.toPx() / density }

        assertEquals(
            "every row lands on the same height (the rowMinHeight floor)",
            listOf(floor, floor),
            heights,
        )
    }
}
