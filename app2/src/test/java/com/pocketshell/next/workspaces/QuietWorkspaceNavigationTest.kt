package com.pocketshell.next.workspaces

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.hostapi.SessionRow
import com.pocketshell.next.AppNavHost
import com.pocketshell.next.connect.TestConnectStack
import com.pocketshell.next.nav.Destination
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #2721: the graph after the workspace page was deleted (D22). A
 * workspace row tap goes STRAIGHT to its entry session's terminal; the only
 * workspace-level route left is [Destination.WorkspaceStart], which hosts the
 * create sheet for a workspace with nothing running. These drive the real
 * graph with screen stand-ins and pin the routes and their arguments — the
 * direct-entry DECISION (which session a tap picks) is
 * [HostWorkspacesDirectEntryTest]'s subject.
 */
@RunWith(AndroidJUnit4::class)
class QuietWorkspaceNavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val stack = TestConnectStack()

    @After
    fun tearDown() {
        stack.close()
    }

    @Test
    fun `a workspace tap opens the session route directly - no workspace page in between`() {
        val workspacePath = "/home/alexey/git/pocketshell"
        val nav = setContent(workspacePath)

        composeRule.runOnUiThread { nav.navigate(Destination.Workspaces.route(7)) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("quiet-open-workspace").performClick()
        composeRule.waitForIdle()

        assertEquals(Destination.Session.pattern, nav.currentBackStackEntry?.destination?.route)
        assertEquals(
            "git-pocketshell",
            nav.currentBackStackEntry?.arguments?.getString(Destination.ARG_SESSION_NAME),
        )
        assertEquals(
            workspacePath,
            nav.currentBackStackEntry?.arguments?.getString(Destination.ARG_WORKSPACE_PATH),
        )
    }

    @Test
    fun `a zero-sessions workspace lands on the create-sheet route carrying its canonical path`() {
        val canonicalPath = "/home/alexey/git/empty"
        val nav = setContent(canonicalPath)

        composeRule.runOnUiThread {
            nav.navigate(Destination.WorkspaceStart.route(7, canonicalPath))
        }
        composeRule.waitForIdle()

        assertEquals(Destination.WorkspaceStart.pattern, nav.currentBackStackEntry?.destination?.route)
        assertEquals(
            canonicalPath,
            nav.currentBackStackEntry?.arguments?.getString(Destination.ARG_WORKSPACE_PATH),
        )
        composeRule.onNodeWithText("WorkspaceStart $canonicalPath").assertIsDisplayed()

        composeRule.onNodeWithTag("quiet-back-to-workspaces").performClick()
        composeRule.waitForIdle()
        // The start screen's Back pops one level — to Hosts when entered
        // directly, to the host workspaces when the workspace tap fell back
        // to it.
        composeRule.onNodeWithText("Hosts").assertIsDisplayed()
    }

    @Test
    fun `new session from a terminal opens the create-sheet route and Back returns to the terminal`() {
        val nav = setContent("/home/alexey/git/app")
        composeRule.runOnUiThread {
            nav.navigate(Destination.Workspaces.route(7))
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("quiet-open-workspace").performClick()
        composeRule.waitForIdle()
        assertEquals(Destination.Session.pattern, nav.currentBackStackEntry?.destination?.route)

        composeRule.onNodeWithTag("quiet-new-session").performClick()
        composeRule.waitForIdle()
        assertEquals(Destination.WorkspaceStart.pattern, nav.currentBackStackEntry?.destination?.route)
        assertEquals(
            "/home/alexey/git/app",
            nav.currentBackStackEntry?.arguments?.getString(Destination.ARG_WORKSPACE_PATH),
        )

        composeRule.onNodeWithTag("quiet-back-to-workspaces").performClick()
        composeRule.waitForIdle()
        assertEquals(Destination.Session.pattern, nav.currentBackStackEntry?.destination?.route)
    }

    private fun setContent(workspacePath: String): NavHostController {
        lateinit var controller: NavHostController
        composeRule.setContent {
            controller = rememberNavController()
            PocketShellTheme {
                AppNavHost(
                    navController = controller,
                    hostsScreen = { Text("Hosts") },
                    connectViewModel = { stack.viewModel },
                    workspacesScreen = { hostId, onOpenSession, _, _, _, onBack, _, _ ->
                        Column {
                            Text("Workspaces $hostId")
                            Button(
                                onClick = {
                                    onOpenSession(
                                        SessionRow(
                                            name = "git-pocketshell",
                                            id = null,
                                            workspace = workspacePath,
                                            tag = null,
                                            engine = null,
                                            profile = null,
                                            agent = null,
                                            agentState = null,
                                            agentStateSource = null,
                                            attached = true,
                                            createdEpoch = null,
                                            activityEpoch = null,
                                        ),
                                    )
                                },
                                modifier = Modifier.testTag("quiet-open-workspace"),
                            ) { Text("Open workspace") }
                            Button(
                                onClick = onBack,
                                modifier = Modifier.testTag("quiet-back-from-workspaces"),
                            ) { Text("Back") }
                        }
                    },
                    workspaceStartScreen = { _, workspaceStartPath, _, onBack ->
                        Column {
                            Text("WorkspaceStart $workspaceStartPath")
                            Button(
                                onClick = onBack,
                                modifier = Modifier.testTag("quiet-back-to-workspaces"),
                            ) { Text("Back to workspaces") }
                        }
                    },
                    sessionScreen = { _, _, _, _, actions ->
                        Column {
                            Button(
                                onClick = actions.onOpenNewSession,
                                modifier = Modifier.testTag("quiet-new-session"),
                            ) { Text("New session") }
                        }
                    },
                )
            }
        }
        composeRule.waitForIdle()
        return controller
    }
}
