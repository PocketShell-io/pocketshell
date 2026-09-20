package com.pocketshell.next.workspaces

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.hostapi.AgentState
import com.pocketshell.core.hostapi.SessionRow
import com.pocketshell.next.tree.SESSION_TREE_FILES_TAG
import com.pocketshell.next.tree.SESSION_TREE_PORTS_TAG
import com.pocketshell.next.tree.SessionTreeUiState
import com.pocketshell.next.tree.sessionRowMenuTag
import com.pocketshell.next.tree.sessionRowTag
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuietWorkspaceScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `root appears once and its whole workspace row opens the canonical path`() {
        val path = "/home/alexey/git/pocketshell"
        val opened = mutableListOf<String>()
        setHostContent(
            state = HostWorkspacesUiState(
                hostId = 7,
                hostLabel = "hetzner",
                loaded = true,
                roots = listOf(
                    WorkspaceRootProjection(
                        key = "/home/alexey/git",
                        label = "Git",
                        displayPath = "~/git",
                        path = "/home/alexey/git",
                        workspaces = listOf(
                            WorkspaceProjection(
                                path = path,
                                label = "pocketshell",
                                displayPath = "~/git/pocketshell",
                                sessions = emptyList(),
                                durable = true,
                            ),
                        ),
                        rootSessions = emptyList(),
                    ),
                ),
            ),
            onOpenWorkspace = { opened += it },
        )

        composeRule.onAllNodesWithTag(workspaceRootTag("/home/alexey/git"))
            .assertCountEquals(1)
        composeRule.onNodeWithText("~/git").assertIsDisplayed()
        composeRule.onNodeWithText("Git").assertDoesNotExist()
        composeRule.onNodeWithTag(workspaceRowTag(path)).assertIsDisplayed().performClick()
        assertEquals(listOf(path), opened)
        composeRule.onNodeWithText("~/git/pocketshell", substring = true).assertDoesNotExist()
        // #2798: the empty row no longer spends a second line saying it is empty.
        composeRule.onNodeWithText("No sessions").assertDoesNotExist()
    }

    @Test
    fun `workspace search matches canonical display paths`() {
        setHostContent(
            state = HostWorkspacesUiState(
                hostLabel = "hetzner",
                loaded = true,
                searchQuery = "mobile",
                roots = listOf(
                    WorkspaceRootProjection(
                        key = "/home/alexey/git",
                        label = "Git",
                        displayPath = "~/git",
                        path = "/home/alexey/git",
                        workspaces = listOf(
                            WorkspaceProjection(
                                path = "/home/alexey/git/pocketshell",
                                label = "pocketshell",
                                displayPath = "~/git/pocketshell",
                                sessions = emptyList(),
                                durable = true,
                            ),
                        ),
                        rootSessions = emptyList(),
                    ),
                    WorkspaceRootProjection(
                        key = "/home/alexey/work",
                        label = "Work",
                        displayPath = "~/work",
                        path = "/home/alexey/work",
                        workspaces = listOf(
                            WorkspaceProjection(
                                path = "/home/alexey/work/mobile",
                                label = "mobile",
                                displayPath = "~/work/mobile",
                                sessions = emptyList(),
                                durable = true,
                            ),
                        ),
                        rootSessions = emptyList(),
                    ),
                ),
            ),
        )

        composeRule.onNodeWithTag(workspaceRowTag("/home/alexey/work/mobile"))
            .assertIsDisplayed()
        composeRule.onNodeWithTag(workspaceRowTag("/home/alexey/git/pocketshell"))
            .assertDoesNotExist()
    }

    @Test
    fun `workspace search never returns root session rows`() {
        setHostContent(
            state = HostWorkspacesUiState(
                hostLabel = "hetzner",
                loaded = true,
                searchQuery = "root-shell",
                roots = listOf(
                    WorkspaceRootProjection(
                        key = "/home/alexey/git",
                        label = "Git",
                        displayPath = "~/git",
                        path = "/home/alexey/git",
                        workspaces = listOf(
                            WorkspaceProjection(
                                path = "/home/alexey/git/pocketshell",
                                label = "pocketshell",
                                displayPath = "~/git/pocketshell",
                                sessions = emptyList(),
                                durable = true,
                            ),
                        ),
                        rootSessions = listOf(session("root-shell", "/home/alexey/git")),
                    ),
                ),
            ),
        )

        composeRule.onNodeWithTag(workspaceSessionRowTag("root-shell"))
            .assertDoesNotExist()
        composeRule.onNodeWithText("No matching workspaces").assertIsDisplayed()
    }

    @Test
    fun `root sessions use In this root and do not create a child workspace`() {
        setHostContent(
            state = HostWorkspacesUiState(
                hostLabel = "hetzner",
                loaded = true,
                roots = listOf(
                    WorkspaceRootProjection(
                        key = "/home/alexey/git",
                        label = "Git",
                        displayPath = "~/git",
                        path = "/home/alexey/git",
                        workspaces = emptyList(),
                        rootSessions = listOf(session("root-shell", "/home/alexey/git")),
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText(HOST_WORKSPACES_IN_ROOT_LABEL).assertIsDisplayed()
        composeRule.onNodeWithTag(workspaceSessionRowTag("root-shell")).assertIsDisplayed()
        composeRule.onNodeWithText("No workspaces yet").assertDoesNotExist()
    }

    @Test
    fun `root session opens with the workspace reported by the host`() {
        val path = "/home/alexey/git"
        val opened = mutableListOf<SessionRow>()
        setHostContent(
            state = HostWorkspacesUiState(
                hostLabel = "hetzner",
                loaded = true,
                roots = listOf(
                    WorkspaceRootProjection(
                        key = path,
                        label = "Git",
                        displayPath = "~/git",
                        path = path,
                        workspaces = emptyList(),
                        rootSessions = listOf(session("root-shell", path)),
                    ),
                ),
            ),
            onOpenSession = { opened += it },
        )

        composeRule.onNodeWithTag(workspaceSessionRowTag("root-shell")).performClick()

        assertEquals(path, opened.single().workspace)
    }

    @Test
    fun `an empty registered root stays visible`() {
        setHostContent(
            state = HostWorkspacesUiState(
                hostLabel = "hetzner",
                loaded = true,
                roots = listOf(
                    WorkspaceRootProjection(
                        key = "/home/alexey/work",
                        label = "Work",
                        displayPath = "~/work",
                        path = "/home/alexey/work",
                        workspaces = emptyList(),
                        rootSessions = emptyList(),
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText("~/work").assertIsDisplayed()
        composeRule.onNodeWithTag(HOST_WORKSPACES_ROOT_EMPTY_TAG).assertIsDisplayed()
    }

    @Test
    fun `root label opens its action sheet`() {
        val rootPath = "/home/alexey/git"
        setHostContent(
            state = HostWorkspacesUiState(
                hostLabel = "hetzner",
                loaded = true,
                roots = listOf(
                    WorkspaceRootProjection(
                        key = rootPath,
                        label = "Git",
                        displayPath = "~/git",
                        path = rootPath,
                        registeredRootId = 12L,
                        workspaces = emptyList(),
                        rootSessions = emptyList(),
                    ),
                ),
            ),
        )

        composeRule.onNodeWithTag(workspaceRootActionsTag(rootPath)).performClick()
        composeRule.onNodeWithText("Start session here").assertIsDisplayed()
    }

    @Test
    fun `root action content starts a session at the root path`() {
        val rootPath = "/home/alexey/git"
        val opened = mutableListOf<String>()
        composeRule.setContent {
            PocketShellTheme {
                RootActionsSheetContent(
                    root = WorkspaceRootProjection(
                        key = rootPath,
                        label = "Git",
                        displayPath = "~/git",
                        path = rootPath,
                        registeredRootId = 12L,
                        workspaces = emptyList(),
                        rootSessions = emptyList(),
                    ),
                    onAddWorkspace = {},
                    onCreateFolder = {},
                    onStartSession = { opened += rootPath },
                    onCopyPath = {},
                    onBrowse = {},
                    onRemove = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HOST_WORKSPACES_ROOT_START_SESSION_TAG).assert(hasClickAction())
        composeRule.onNodeWithTag(HOST_WORKSPACES_ROOT_START_SESSION_TAG).performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(rootPath), opened)
    }

    @Test
    fun `synthetic Other bucket has no add workspace action`() {
        val otherKey = "::quiet-other::"
        setHostContent(
            state = HostWorkspacesUiState(
                hostLabel = "hetzner",
                loaded = true,
                roots = listOf(
                    WorkspaceRootProjection(
                        key = otherKey,
                        label = "Other",
                        displayPath = "Other",
                        path = null,
                        workspaces = listOf(
                            WorkspaceProjection(
                                path = "/tmp/project",
                                label = "project",
                                displayPath = "/tmp/project",
                                sessions = emptyList(),
                                durable = false,
                            ),
                        ),
                        rootSessions = emptyList(),
                        other = true,
                    ),
                ),
            ),
        )

        composeRule.onNodeWithTag(workspaceRootTag(otherKey)).assertIsDisplayed()
        composeRule.onAllNodesWithTag(workspaceRootAddTag(otherKey)).assertCountEquals(0)
    }

    @Test
    fun `offline host reports status unavailable`() {
        setHostContent(
            state = HostWorkspacesUiState(
                hostLabel = "hetzner",
                failure = "connection lost",
            ),
        )
        composeRule.onNodeWithTag(HOST_WORKSPACES_EMPTY_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Offline").assertIsDisplayed()
        composeRule.onNodeWithText("No workspaces").assertDoesNotExist()
    }

    @Test
    fun `stale root sessions use the unavailable status vocabulary`() {
        setHostContent(
            state = HostWorkspacesUiState(
                hostLabel = "hetzner",
                loaded = true,
                failure = "connection lost",
                statusUnavailable = true,
                roots = listOf(
                    WorkspaceRootProjection(
                        key = "/home/alexey/git",
                        label = "Git",
                        displayPath = "~/git",
                        path = "/home/alexey/git",
                        workspaces = emptyList(),
                        rootSessions = listOf(session("root-shell", "/home/alexey/git")),
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText("Offline · Saved list").assertIsDisplayed()
        composeRule.onNodeWithText("Status unavailable").assertIsDisplayed()
        composeRule.onNodeWithText("Terminal").assertDoesNotExist()
    }

    @Test
    fun `workspace screen opens a real session`() {
        val opened = mutableListOf<String>()
        setWorkspaceContent(
            state = SessionTreeUiState(
                hostId = 7,
                workspacePath = "/home/alexey/git/pocketshell",
                loaded = true,
                workspaceSessions = listOf(
                    session("claude-main", "/home/alexey/git/pocketshell")
                        .copy(agent = "claude", agentState = AgentState.WORKING),
                ),
            ),
            onOpenSession = { name, _ -> opened += name },
        )

        composeRule.onNodeWithTag(sessionRowTag("claude-main")).assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Claude Code · Working").assertIsDisplayed()
        composeRule.onAllNodesWithTag(sessionRowMenuTag("claude-main")).assertCountEquals(0)
        assertEquals(listOf("claude-main"), opened)
    }

    @Test
    fun `populated workspace puts new session after the rows`() {
        setWorkspaceContent(
            state = SessionTreeUiState(
                hostId = 7,
                workspacePath = "/home/alexey/git/pocketshell",
                loaded = true,
                workspaceSessions = listOf(
                    session("claude-main", "/home/alexey/git/pocketshell"),
                ),
            ),
        )

        composeRule.onNodeWithTag(sessionRowTag("claude-main")).assertIsDisplayed()
        composeRule.onAllNodesWithTag(WORKSPACE_NEW_SESSION_TAG).assertCountEquals(1)
    }

    @Test
    fun `empty workspace offers new session`() {
        setWorkspaceContent(
            state = SessionTreeUiState(
                hostId = 7,
                workspacePath = "/home/alexey/git/empty",
                loaded = true,
            ),
        )
        composeRule.onNodeWithText("No sessions").assertIsDisplayed()
        composeRule.onAllNodesWithTag(WORKSPACE_NEW_SESSION_TAG).assertCountEquals(1)
        composeRule.onNodeWithText("Workspace").assertIsDisplayed()
        composeRule.onNodeWithText("Browse files").assertIsDisplayed()
        composeRule.onNodeWithText("Services & tunnels").assertDoesNotExist()
    }

    @Test
    fun `workspace actions keep only the catalog actions`() {
        composeRule.setContent {
            PocketShellTheme {
                WorkspaceActionsContent(
                    onNewSession = {},
                    onBrowseFiles = {},
                    onOpenPorts = {},
                    onOpenUsage = {},
                    onCopyPath = {},
                    onReorder = {},
                    onCreateFolder = {},
                    onRemove = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("New session").assertIsDisplayed()
        composeRule.onNodeWithText("Browse files").assertIsDisplayed()
        composeRule.onNodeWithText("Copy folder path").assertIsDisplayed()
        composeRule.onNodeWithText("Reorder workspaces").assertIsDisplayed()
        composeRule.onNodeWithText("Remove from list").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Services & tunnels").assertDoesNotExist()
        composeRule.onNodeWithText("Usage").assertDoesNotExist()
        composeRule.onNodeWithText("Create folder").assertDoesNotExist()
    }

    @Test
    fun `workspace utility rows keep their real navigation callbacks`() {
        var files = 0
        var ports = 0
        setWorkspaceContent(
            state = SessionTreeUiState(
                hostId = 7,
                workspacePath = "/home/alexey/git/pocketshell",
                loaded = true,
                workspaceSessions = listOf(session("shell", "/home/alexey/git/pocketshell")),
            ),
            onOpenFiles = { files += 1 },
            onOpenPorts = { ports += 1 },
        )

        composeRule.onNodeWithTag(SESSION_TREE_FILES_TAG).performClick()
        composeRule.onNodeWithTag(SESSION_TREE_PORTS_TAG).performClick()
        assertEquals(1, files)
        assertEquals(1, ports)
    }

    /**
     * Issue #2758: the host tools sheet uses the same words as the terminal
     * actions sheet for the same action type — exactly "Browse files". The old
     * "Browse host files" wording is retired, not kept as a second variant.
     */
    @Test
    fun `host tools sheet labels the files row Browse files`() {
        setHostContent(
            state = HostWorkspacesUiState(
                hostId = 7,
                hostLabel = "hetzner",
                loaded = true,
            ),
        )

        composeRule.onNodeWithTag(HOST_WORKSPACES_ACTIONS_TAG).performClick()
        composeRule.onNodeWithText("Browse files").assertIsDisplayed()
        composeRule.onNodeWithText("Browse host files").assertDoesNotExist()
    }

    /**
     * Issue #2814 N-2: Settings used to have exactly one entry point, the row
     * on the Hosts landing screen, so a connected user had to back out of the
     * host to change a setting. The host tools sheet now carries it.
     *
     * The tap is asserted, not just the row's presence: a row wired to the
     * wrong lambda (or to nothing, which is what a default `{}` parameter
     * silently gives you) renders identically.
     */
    @Test
    fun `host tools sheet reaches Settings without leaving the host`() {
        var openedSettings = 0
        setHostContent(
            state = HostWorkspacesUiState(
                hostId = 7,
                hostLabel = "hetzner",
                loaded = true,
            ),
            onOpenSettings = { openedSettings += 1 },
        )

        composeRule.onNodeWithTag(HOST_WORKSPACES_ACTIONS_TAG).performClick()
        composeRule.onNodeWithTag(HOST_WORKSPACES_HOST_TOOLS_TAG).assertIsDisplayed()
        // The sheet is a LazyColumn and Settings sits near its end, so the row
        // has to be scrolled INTO composition before it can be tapped.
        composeRule.onNodeWithTag(HOST_WORKSPACES_HOST_TOOLS_TAG)
            .performScrollToNode(hasTestTag(HOST_WORKSPACES_SETTINGS_TAG))
        composeRule.onNodeWithTag(HOST_WORKSPACES_SETTINGS_TAG)
            .assertIsDisplayed()
            .performClick()

        assertEquals(1, openedSettings)
        // The sheet closes behind the navigation; leaving it up would stack a
        // modal over the Settings page the caller is about to push.
        composeRule.onNodeWithTag(HOST_WORKSPACES_HOST_TOOLS_TAG).assertDoesNotExist()
    }

    // Issue #2798: the workspace row's subtitle across all three
    // cardinalities. The empty case is the fix; 1 and 2 are pinned next to it
    // so restoring an empty-case label cannot slip through on the strength of
    // the populated cases still passing. Height is asserted against the token,
    // not a literal dp, so #2800's size work stays free to move the floor.
    @Test
    fun `an empty workspace row drops its subtitle and still meets the touch floor`() {
        setHostContent(state = hostStateWith(sessionCount = 0))

        composeRule.onNodeWithText("No sessions").assertDoesNotExist()
        composeRule.onNodeWithText("Terminal").assertDoesNotExist()
        composeRule.onNodeWithText("Claude Code", substring = true).assertDoesNotExist()
        // The row still honours the navigation touch floor; how much of the
        // reclaimed line it gives back is asserted against a real two-line row
        // in the next test, not against a literal dp here.
        assertTrue(
            "an empty row must still meet the workspace navigation touch floor",
            workspaceRowHeight() >= PocketShellDensity.workspaceRowMinHeight,
        )
    }

    @Test
    fun `a one session workspace row keeps its subtitle and is taller than an empty one`() {
        // One composition, recomposed from 0 to 1 session, so the two heights
        // are measured under identical density/font conditions — comparing a
        // number from a different test run would prove nothing.
        var state by mutableStateOf(hostStateWith(sessionCount = 0))
        composeRule.setContent {
            PocketShellTheme {
                HostWorkspacesScreen(
                    state = state,
                    onRefresh = {},
                    onOpenWorkspace = {},
                    onOpenSession = {},
                )
            }
        }
        composeRule.waitForIdle()
        val emptyHeight = workspaceRowHeight()
        composeRule.onNodeWithText("Claude Code").assertDoesNotExist()

        state = hostStateWith(sessionCount = 1)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Claude Code").assertIsDisplayed()
        val oneHeight = workspaceRowHeight()
        assertTrue(
            "a row with a subtitle must be taller than the collapsed empty row " +
                "(empty=$emptyHeight, one=$oneHeight)",
            oneHeight > emptyHeight,
        )
    }

    @Test
    fun `a two session workspace row counts the repeated kind`() {
        setHostContent(state = hostStateWith(sessionCount = 2))

        composeRule.onNodeWithText("Claude Code \u00d72").assertIsDisplayed()
        composeRule.onNodeWithText("No sessions").assertDoesNotExist()
    }

    @Test
    fun `an empty workspace row keeps its line when the host could not report status`() {
        setHostContent(state = hostStateWith(sessionCount = 0, statusUnavailable = true))

        // "Status unavailable" is real information, not the absence of it, so
        // #2798 leaves that line alone.
        composeRule.onNodeWithText("Status unavailable").assertIsDisplayed()
    }

    private fun workspaceRowHeight(): Dp =
        composeRule.onNodeWithTag(workspaceRowTag(SUBTITLE_WORKSPACE_PATH))
            .getUnclippedBoundsInRoot()
            .height

    private fun hostStateWith(
        sessionCount: Int,
        statusUnavailable: Boolean = false,
    ) = HostWorkspacesUiState(
        hostId = 7,
        hostLabel = "hetzner",
        loaded = true,
        statusUnavailable = statusUnavailable,
        roots = listOf(
            WorkspaceRootProjection(
                key = "/home/alexey/git",
                label = "Git",
                displayPath = "~/git",
                path = "/home/alexey/git",
                workspaces = listOf(
                    WorkspaceProjection(
                        path = SUBTITLE_WORKSPACE_PATH,
                        label = "pocketshell",
                        displayPath = "~/git/pocketshell",
                        sessions = (1..sessionCount).map { index ->
                            session("claude-$index", SUBTITLE_WORKSPACE_PATH)
                                .copy(agent = "claude")
                        },
                        durable = true,
                    ),
                ),
                rootSessions = emptyList(),
            ),
        ),
    )

    // ---------------------------------------------------------------------
    // Issue #2808 (audit D-4): the search field is gated on how many
    // workspaces the host actually has. Below the threshold it is not
    // rendered at all and "Find a workspace" moves into the host-tools
    // sheet; at or above it, the field stays pinned as before.
    //
    // The whole class is pinned, not just the reported four-workspace
    // instance: the boundary on both sides, the case where the list is
    // already filtered, and both halves of the sheet handoff. The counts are
    // written as WORKSPACE_SEARCH_MIN_WORKSPACES arithmetic, so moving the
    // threshold moves the tests with it instead of silently passing.
    // ---------------------------------------------------------------------

    @Test
    fun `a small host does not spend a row on the search field`() {
        setHostContent(state = hostWithWorkspaces(4))

        composeRule.onNodeWithTag(HOST_WORKSPACES_SEARCH_TAG).assertDoesNotExist()
        // The rows it would have filtered are all on screen instead.
        composeRule.onNodeWithTag(workspaceRowTag(searchFixtureWorkspacePath(0)))
            .assertIsDisplayed()
    }

    @Test
    fun `one workspace short of the threshold still hides the field`() {
        setHostContent(state = hostWithWorkspaces(WORKSPACE_SEARCH_MIN_WORKSPACES - 1))

        composeRule.onNodeWithTag(HOST_WORKSPACES_SEARCH_TAG).assertDoesNotExist()
    }

    @Test
    fun `the field appears exactly at the threshold`() {
        setHostContent(state = hostWithWorkspaces(WORKSPACE_SEARCH_MIN_WORKSPACES))

        composeRule.onNodeWithTag(HOST_WORKSPACES_SEARCH_TAG).assertIsDisplayed()
    }

    /**
     * The field's own height comes from the token, not a hand-copied `56.dp`
     * (the second half of D-4). Asserted against [PocketShellDensity.fieldMin]
     * plus the bottom gutter the modifier chain adds after it, so moving the
     * token moves this expectation and a re-introduced literal goes red.
     */
    @Test
    fun `the search field stands on the field-minimum token`() {
        setHostContent(state = hostWithWorkspaces(WORKSPACE_SEARCH_MIN_WORKSPACES))

        assertEquals(
            PocketShellDensity.fieldMin + PocketShellSpacing.md,
            composeRule.onNodeWithTag(HOST_WORKSPACES_SEARCH_TAG)
                .getUnclippedBoundsInRoot()
                .height,
        )
    }

    /**
     * A filtered list always keeps its field. Hiding it here would leave an
     * invisible filter over the rows with no visible control to clear it —
     * the one way a count-based gate can strand the user.
     */
    @Test
    fun `an active query keeps the field even on a small host`() {
        setHostContent(
            state = hostWithWorkspaces(2).copy(searchQuery = "ws-0"),
        )

        composeRule.onNodeWithTag(HOST_WORKSPACES_SEARCH_TAG).assertIsDisplayed()
    }

    @Test
    fun `host tools offers Find a workspace and reveals the field`() {
        setHostContent(state = hostWithWorkspaces(4))

        composeRule.onNodeWithTag(HOST_WORKSPACES_ACTIONS_TAG).performClick()
        composeRule.onNodeWithTag(HOST_WORKSPACES_FIND_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(HOST_WORKSPACES_FIND_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(HOST_WORKSPACES_SEARCH_TAG).assertIsDisplayed()
    }

    @Test
    fun `host tools has no Find row when the field is already on screen`() {
        setHostContent(state = hostWithWorkspaces(WORKSPACE_SEARCH_MIN_WORKSPACES))

        composeRule.onNodeWithTag(HOST_WORKSPACES_ACTIONS_TAG).performClick()
        composeRule.onNodeWithTag(HOST_WORKSPACES_FIND_TAG).assertDoesNotExist()
        composeRule.onAllNodesWithTag(HOST_WORKSPACES_SEARCH_TAG).assertCountEquals(1)
    }

    /** One root holding [count] workspaces — the shape the gate counts. */
    private fun hostWithWorkspaces(count: Int) = HostWorkspacesUiState(
        hostId = 7,
        hostLabel = "hetzner",
        loaded = true,
        roots = listOf(
            WorkspaceRootProjection(
                key = "/home/alexey/git",
                label = "Git",
                displayPath = "~/git",
                path = "/home/alexey/git",
                workspaces = (0 until count).map { index ->
                    WorkspaceProjection(
                        path = searchFixtureWorkspacePath(index),
                        label = "ws-$index",
                        displayPath = "~/git/ws-$index",
                        sessions = emptyList(),
                        durable = true,
                    )
                },
                rootSessions = emptyList(),
            ),
        ),
    )

    private fun searchFixtureWorkspacePath(index: Int) = "/home/alexey/git/ws-$index"

    private fun setHostContent(
        state: HostWorkspacesUiState,
        onOpenWorkspace: (String) -> Unit = {},
        onOpenSession: (SessionRow) -> Unit = {},
        onOpenSettings: () -> Unit = {},
    ) {
        composeRule.setContent {
            PocketShellTheme {
                HostWorkspacesScreen(
                    state = state,
                    onRefresh = {},
                    onOpenWorkspace = onOpenWorkspace,
                    onOpenSession = onOpenSession,
                    onOpenSettings = onOpenSettings,
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun setWorkspaceContent(
        state: SessionTreeUiState,
        onOpenSession: (String, String?) -> Unit = { _, _ -> },
        onOpenCreateFolder: () -> Unit = {},
        onOpenFiles: () -> Unit = {},
        onOpenPorts: () -> Unit = {},
    ) {
        composeRule.setContent {
            PocketShellTheme {
                WorkspaceScreen(
                    state = state,
                    onRefresh = {},
                    onOpenSession = onOpenSession,
                    onOpenFiles = onOpenFiles,
                    onOpenPorts = onOpenPorts,
                    onOpenCreateFolder = onOpenCreateFolder,
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun session(name: String, workspace: String): SessionRow = SessionRow(
        name = name,
        id = null,
        workspace = workspace,
        tag = null,
        engine = null,
        profile = null,
        agent = null,
        agentState = null,
        agentStateSource = null,
        attached = true,
        createdEpoch = 1L,
        activityEpoch = null,
    )

    private companion object {
        /** The one workspace row #2798's subtitle rules are measured on. */
        const val SUBTITLE_WORKSPACE_PATH = "/home/alexey/git/pocketshell"
    }
}
