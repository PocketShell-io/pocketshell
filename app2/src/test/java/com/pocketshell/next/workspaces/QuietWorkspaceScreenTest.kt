package com.pocketshell.next.workspaces

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.hostapi.SessionRow
import com.pocketshell.next.tree.CreateSessionState
import com.pocketshell.next.tree.SessionTreeUiState
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
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
        // Issue #2635 D1: an empty workspace reads as quiet — no count, no
        // "No sessions" subtitle, just the name.
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
    fun `the search field hides behind a header icon on a short list`() {
        // Issue #2635 D2: at or below WORKSPACE_SEARCH_THRESHOLD the field
        // costs nothing until asked for.
        setHostContent(
            state = HostWorkspacesUiState(
                hostLabel = "hetzner",
                loaded = true,
                roots = listOf(oneWorkspaceRoot()),
            ),
        )

        composeRule.onNodeWithTag(HOST_WORKSPACES_SEARCH_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(HOST_WORKSPACES_SEARCH_TOGGLE_TAG).assertIsDisplayed()
    }

    @Test
    fun `the header icon expands the search field in place`() {
        setHostContent(
            state = HostWorkspacesUiState(
                hostLabel = "hetzner",
                loaded = true,
                roots = listOf(oneWorkspaceRoot()),
            ),
        )

        composeRule.onNodeWithTag(HOST_WORKSPACES_SEARCH_TOGGLE_TAG).performClick()

        composeRule.onNodeWithTag(HOST_WORKSPACES_SEARCH_TAG).assertIsDisplayed()
    }

    /**
     * Issue #2635 D2: a live query is state the user typed — the field must
     * not collapse out from under it (mid-filter), even while the list is
     * short enough to hide the field by default.
     */
    @Test
    fun `a live query keeps the search field visible`() {
        setHostContent(
            state = HostWorkspacesUiState(
                hostLabel = "hetzner",
                loaded = true,
                searchQuery = "pocket",
                roots = listOf(oneWorkspaceRoot()),
            ),
        )

        composeRule.onNodeWithTag(HOST_WORKSPACES_SEARCH_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(HOST_WORKSPACES_SEARCH_TOGGLE_TAG).assertDoesNotExist()
    }

    @Test
    fun `the search field is permanent above the threshold`() {
        val workspaces = (1..(WORKSPACE_SEARCH_THRESHOLD + 1)).map { index ->
            WorkspaceProjection(
                path = "/home/alexey/git/w$index",
                label = "w$index",
                displayPath = "~/git/w$index",
                sessions = emptyList(),
                durable = true,
            )
        }
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
                        workspaces = workspaces,
                        rootSessions = emptyList(),
                    ),
                ),
            ),
        )

        composeRule.onNodeWithTag(HOST_WORKSPACES_SEARCH_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(HOST_WORKSPACES_SEARCH_TOGGLE_TAG).assertDoesNotExist()
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
    fun `workspace start screen offers the create entry and nothing else`() {
        setStartContent(
            state = SessionTreeUiState(
                hostId = 7,
                workspacePath = "/home/alexey/git/empty",
                loaded = true,
            ),
        )
        composeRule.onNodeWithText("No sessions here yet").assertIsDisplayed()
        composeRule.onAllNodesWithTag(WORKSPACE_NEW_SESSION_TAG).assertCountEquals(1)
        // The utility rows moved to the host kebab / the terminal's action
        // sheet (issue #2721) — the start screen is ONLY the create surface.
        composeRule.onNodeWithText("Browse files").assertDoesNotExist()
        composeRule.onNodeWithText("Services & tunnels").assertDoesNotExist()
        composeRule.onNodeWithText("Usage").assertDoesNotExist()
    }

    @Test
    fun `workspace start screen surfaces the idempotent create notice`() {
        setStartContent(
            state = SessionTreeUiState(
                hostId = 7,
                workspacePath = "/home/alexey/git/pocketshell",
                loaded = true,
                create = CreateSessionState(
                    visible = false,
                    notice = "Session \"pocketshell:main\" already existed — nothing new was created; opened it.",
                ),
            ),
        )
        composeRule.onNodeWithTag(WORKSPACE_CREATE_NOTICE_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(
            "Session \"pocketshell:main\" already existed — nothing new was created; opened it.",
        ).assertIsDisplayed()
    }

    @Test
    fun `workspace long-press keeps only the catalog actions`() {
        composeRule.setContent {
            PocketShellTheme {
                WorkspaceActionsSheetContent(
                    workspace = WorkspaceProjection(
                        path = "/home/alexey/git/pocketshell",
                        label = "pocketshell",
                        displayPath = "~/git/pocketshell",
                        sessions = emptyList(),
                        durable = true,
                    ),
                    onNewSession = {},
                    onBrowseFiles = {},
                    onCopyPath = {},
                    onReorder = {},
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
    fun `workspace row long-press opens the actions with real callbacks`() {
        val path = "/home/alexey/git/pocketshell"
        var startedSessions = 0
        var browsed = 0
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
            onStartSessionAtPath = { startedSessions += 1 },
            onOpenFilesAtPath = { browsed += 1 },
        )

        composeRule.onNodeWithTag(workspaceRowTag(path))
            .performTouchInput { longClick() }
        composeRule.onNodeWithTag(HOST_WORKSPACES_WS_ACTIONS_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(HOST_WORKSPACES_WS_BROWSE_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(workspaceRowTag(path))
            .performTouchInput { longClick() }
        composeRule.onNodeWithTag(HOST_WORKSPACES_WS_NEW_SESSION_TAG).performClick()
        composeRule.waitForIdle()

        assertEquals(1, browsed)
        assertEquals(1, startedSessions)
    }

    private fun setHostContent(
        state: HostWorkspacesUiState,
        onOpenWorkspace: (String) -> Unit = {},
        onStartSessionAtPath: (String) -> Unit = {},
        onOpenSession: (SessionRow) -> Unit = {},
        onOpenFilesAtPath: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            PocketShellTheme {
                HostWorkspacesScreen(
                    state = state,
                    onRefresh = {},
                    onOpenWorkspace = onOpenWorkspace,
                    onStartSessionAtPath = onStartSessionAtPath,
                    onOpenSession = onOpenSession,
                    onOpenFilesAtPath = onOpenFilesAtPath,
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun setStartContent(
        state: SessionTreeUiState,
        onOpenSession: (String, String?) -> Unit = { _, _ -> },
    ) {
        composeRule.setContent {
            PocketShellTheme {
                WorkspaceStartScreen(
                    state = state,
                    onOpenSession = onOpenSession,
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun oneWorkspaceRoot(): WorkspaceRootProjection =
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
        )


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
}
