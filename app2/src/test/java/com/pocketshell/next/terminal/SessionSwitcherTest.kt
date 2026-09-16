package com.pocketshell.next.terminal

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.hostapi.AgentState
import com.pocketshell.core.hostapi.HostCliClient
import com.pocketshell.core.hostapi.SessionRow
import com.pocketshell.core.transport.ExecResult
import com.pocketshell.next.connect.TestConnectStack
import com.pocketshell.next.hostcli.HostCliClientFactory
import com.pocketshell.next.hostcli.asRemoteExec
import com.pocketshell.next.nav.Destination
import com.pocketshell.next.workspaces.workspaceLabel
import com.pocketshell.uikit.components.SessionTabState
import com.pocketshell.uikit.theme.PocketShellTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #2721 (N2): the tab strip's overflow sheet reaches the WHOLE host.
 * The "Other workspaces" section (name · dot · count) is derived from the
 * listing the switcher already fetches, and a row opens that workspace's
 * ENTRY session — so any session on the host is two taps from a terminal.
 *
 * The sheet body is composed directly ([SessionSwitcherSheetContent]) because
 * Robolectric drops clicks on a `ModalBottomSheet`; the ViewModel test under
 * it drives the real grouping over a scripted host.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SessionSwitcherTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var stack: TestConnectStack
    private lateinit var lastSessions: LastSessionStore

    private val openedSessions = mutableListOf<SessionRow>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        stack = TestConnectStack()
        lastSessions = LastSessionStore(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        stack.close()
    }

    // --- the sheet body ----------------------------------------------------

    @Test
    fun `other workspaces render name, dot and count and open the entry session`() {
        val entry = session("aplexer:yolo", WORKSPACE_APLEXER, activityEpoch = 900)
        composeRule.setContent {
            PocketShellTheme {
                SessionSwitcherSheetContent(
                    currentSessionName = "pocketshell:main",
                    state = SessionSwitcherUiState(
                        sessions = listOf(
                            session("pocketshell:main", WORKSPACE_MAIN, activityEpoch = 800),
                        ),
                        otherWorkspaces = listOf(
                            OtherWorkspaceEntry(
                                path = WORKSPACE_APLEXER,
                                label = workspaceLabel(WORKSPACE_APLEXER),
                                sessionCount = 2,
                                state = SessionTabState.Working,
                                entry = entry,
                            ),
                        ),
                    ),
                    onOpenSession = { openedSessions += it },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SESSION_SWITCHER_OTHER_WORKSPACES_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(sessionSwitcherWorkspaceTag(WORKSPACE_APLEXER))
            .assertIsDisplayed()
            .performClick()

        assertEquals(listOf(entry), openedSessions)
    }

    @Test
    fun `a workspace with no live session anywhere shows the empty state`() {
        composeRule.setContent {
            PocketShellTheme {
                SessionSwitcherSheetContent(
                    currentSessionName = "pocketshell:main",
                    state = SessionSwitcherUiState(),
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SESSION_SWITCHER_EMPTY_TAG).assertIsDisplayed()
        composeRule.onAllNodesWithTag(SESSION_SWITCHER_OTHER_WORKSPACES_TAG)
            .assertCountEquals(0)
    }

    // --- the grouping, over a scripted host ---------------------------------

    /**
     * The workspace on screen is excluded; the other workspace's row opens its
     * most recently ACTIVE session — the same entry-session ladder the
     * workspace rows use (remembered first, then latest activity).
     */
    @Test
    fun `the switcher derives the other host workspace and its entry session`() {
        val hostId = seedHost()
        lastSessions.record(hostId, "aplexer:stale", WORKSPACE_APLEXER)

        val viewModel = SessionSwitcherViewModel(
            savedStateHandle = SavedStateHandle(
                mapOf(
                    Destination.ARG_HOST_ID to hostId,
                    Destination.ARG_WORKSPACE_PATH to WORKSPACE_MAIN,
                    Destination.ARG_SESSION_NAME to "pocketshell:main",
                ),
            ),
            registry = stack.registry,
            clients = HostCliClientFactory { connection -> HostCliClient(connection.asRemoteExec()) },
            hostDao = stack.db.hostDao(),
            lastSessionStore = lastSessions,
        )

        viewModel.refresh()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            viewModel.state.value.otherWorkspaces.isNotEmpty()
        }

        val others = viewModel.state.value.otherWorkspaces
        assertEquals(1, others.size)
        val aplexer = others.single()
        assertEquals(WORKSPACE_APLEXER, aplexer.path)
        assertEquals("aplexer", aplexer.label)
        assertEquals(2, aplexer.sessionCount)
        // `yolo` is the most recently active; the remembered `aplexer:stale`
        // is not in the listing, so the ladder falls through to activity.
        assertEquals("aplexer:yolo", aplexer.entry.name)
        // The dot reflects the busiest agent state in the workspace.
        assertEquals(SessionTabState.Working, aplexer.state)
        // The current workspace's own sessions stay in the primary section.
        assertTrue(viewModel.state.value.sessions.none { it.workspace != WORKSPACE_MAIN })
    }

    /** A remembered session in another workspace beats the activity fallback. */
    @Test
    fun `a remembered session wins the other-workspace entry`() {
        val hostId = seedHost()
        lastSessions.record(hostId, "aplexer:opencode-lab", WORKSPACE_APLEXER)

        val viewModel = SessionSwitcherViewModel(
            savedStateHandle = SavedStateHandle(
                mapOf(
                    Destination.ARG_HOST_ID to hostId,
                    Destination.ARG_WORKSPACE_PATH to WORKSPACE_MAIN,
                    Destination.ARG_SESSION_NAME to "pocketshell:main",
                ),
            ),
            registry = stack.registry,
            clients = HostCliClientFactory { connection -> HostCliClient(connection.asRemoteExec()) },
            hostDao = stack.db.hostDao(),
            lastSessionStore = lastSessions,
        )

        viewModel.refresh()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            viewModel.state.value.otherWorkspaces.isNotEmpty()
        }

        assertEquals("aplexer:opencode-lab", viewModel.state.value.otherWorkspaces.single().entry.name)
    }

    // --- helpers -------------------------------------------------------------

    private fun seedHost(): Long {
        val hostId = stack.seedHost()
        stack.factory.script = { connection ->
            connection.onExecMatching("listing", once = false, { true }) { command ->
                when {
                    command.startsWith("pocketshell sessions list") ->
                        ExecResult(0, TWO_WORKSPACE_SESSIONS, "", false)
                    else -> ExecResult(0, "", "", false)
                }
            }
        }
        return hostId
    }

    private fun session(
        name: String,
        workspace: String,
        activityEpoch: Long? = null,
        agent: String? = null,
        agentState: AgentState? = null,
    ): SessionRow = SessionRow(
        name = name,
        id = null,
        workspace = workspace,
        tag = null,
        engine = "shell",
        profile = null,
        agent = agent,
        agentState = agentState,
        agentStateSource = null,
        attached = false,
        createdEpoch = null,
        activityEpoch = activityEpoch,
    )

    private companion object {
        const val WORKSPACE_MAIN = "/home/testuser/git/pocketshell"
        const val WORKSPACE_APLEXER = "/home/testuser/git/aplexer"

        /** `yolo` is the most recently active aplexer session and runs Claude. */
        val TWO_WORKSPACE_SESSIONS = """
            {"schema":3,"sessions":[
              {"name":"pocketshell:main","workspace":"$WORKSPACE_MAIN","engine":"shell","attached":false,"activity_epoch":800},
              {"name":"aplexer:opencode-lab","workspace":"$WORKSPACE_APLEXER","engine":"shell","attached":false,"activity_epoch":100},
              {"name":"aplexer:yolo","workspace":"$WORKSPACE_APLEXER","engine":"shell","agent":"claude","agent_state":"working","attached":false,"activity_epoch":900}
            ],"errors":[]}
        """.trimIndent()
    }
}
