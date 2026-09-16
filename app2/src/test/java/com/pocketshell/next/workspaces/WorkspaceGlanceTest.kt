package com.pocketshell.next.workspaces

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.pocketshell.core.hostapi.SessionRow
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issue #2635 D1 remainder: the dense workspace row's glance — a bare muted
 * count (no filled chip) and the freshest activity, nothing at all when the
 * workspace is empty.
 */
@RunWith(RobolectricTestRunner::class)
class WorkspaceGlanceTest {

    @get:Rule
    val compose = createComposeRule()

    private val path = "/home/alexey/git/pocketshell"

    @Test
    fun `a session count renders as a bare integer`() {
        compose.setContent {
            PocketShellTheme {
                WorkspaceGlance(
                    path = path,
                    sessions = listOf(
                        session("one", activityEpoch = 1_000),
                        session("two", activityEpoch = 2_000),
                    ),
                    nowSec = 2_060,
                )
            }
        }

        compose.onNodeWithTag(workspaceGlanceCountTag(path)).assertIsDisplayed()
        compose.onNodeWithText("2").assertIsDisplayed()
    }

    @Test
    fun `an empty workspace renders no count at all`() {
        compose.setContent {
            PocketShellTheme {
                WorkspaceGlance(path = path, sessions = emptyList(), nowSec = 2_060)
            }
        }

        compose.onNodeWithTag(workspaceGlanceCountTag(path)).assertDoesNotExist()
    }

    @Test
    fun `the freshest activity across sessions is shown once`() {
        compose.setContent {
            PocketShellTheme {
                WorkspaceGlance(
                    path = path,
                    sessions = listOf(
                        session("one", activityEpoch = 1_000),
                        session("two", activityEpoch = 2_000),
                    ),
                    nowSec = 2_060,
                )
            }
        }

        compose.onNodeWithTag(workspaceGlanceActivityTag(path)).assertIsDisplayed()
    }

    @Test
    fun `an unavailable status renders the quiet dash`() {
        compose.setContent {
            PocketShellTheme {
                WorkspaceGlance(
                    path = path,
                    sessions = emptyList(),
                    nowSec = 2_060,
                    unavailable = true,
                )
            }
        }

        compose.onNodeWithText("—").assertIsDisplayed()
    }

    private fun session(name: String, activityEpoch: Long?): SessionRow = SessionRow(
        name = name,
        id = null,
        workspace = null,
        tag = null,
        engine = null,
        profile = null,
        agent = null,
        agentState = null,
        agentStateSource = null,
        attached = true,
        createdEpoch = null,
        activityEpoch = activityEpoch,
    )
}
