package com.pocketshell.uikit.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import com.pocketshell.uikit.model.ConnectionStatus
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issue #2635 D3: the session header's live state is a dot beside the title —
 * colour from [ConnectionStatus], words from the caller's status description —
 * not a "Connected" subtitle line.
 */
@RunWith(RobolectricTestRunner::class)
class ScreenHeaderStatusDotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a status paints the dot and speaks the description`() {
        compose.setContent {
            PocketShellTheme {
                ScreenHeader(
                    title = "agent-main",
                    status = ConnectionStatus.Connected,
                    statusDescription = "Connected to hetzner",
                    statusTestTag = "header-dot",
                )
            }
        }

        compose.onNodeWithTag("header-dot").assertIsDisplayed()
        compose.onNode(
            hasTestTag("header-dot").and(hasContentDescription("Connected to hetzner")),
        ).assertIsDisplayed()
        // The words are a11y-only: no "Connected" subtitle line renders.
        compose.onNodeWithText("Connected to hetzner").assertDoesNotExist()
    }

    @Test
    fun `no status means no dot`() {
        compose.setContent {
            PocketShellTheme {
                ScreenHeader(
                    title = "agent-main",
                    status = null,
                    statusTestTag = "header-dot",
                )
            }
        }

        compose.onNodeWithTag("header-dot").assertDoesNotExist()
    }
}
