package com.pocketshell.next.workspaces

import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.core.transport.ExecResult
import com.pocketshell.core.hostapi.HostCliClient
import com.pocketshell.core.hostapi.WarningKind
import com.pocketshell.next.connect.TestConnectStack
import com.pocketshell.next.hostcli.HostCliClientFactory
import com.pocketshell.next.hostcli.asRemoteExec
import com.pocketshell.next.nav.Destination
import com.pocketshell.testsupport.LeakGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class HostWorkspacesViewModelTest {

    @get:Rule
    val leakGuard = LeakGuard()

    companion object {
        @JvmStatic
        @get:ClassRule
        val leakGuardClass = LeakGuard.classGuard()
    }

    private val dispatcher = StandardTestDispatcher()
    private lateinit var stack: TestConnectStack

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        stack = TestConnectStack()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        stack.close()
    }

    @Test
    fun `refresh uses the opaque host identity and preserves a durable empty membership`() =
        runTest(dispatcher) {
            val hostId = stack.seedHost(name = "display-name")
            val hostIdentity = stack.db.hostDao().getById(hostId)!!.treeIdentity
            stack.db.projectRootDao().insert(
                ProjectRootEntity(
                    hostId = hostId,
                    label = "Git",
                    path = "/home/testuser/git",
                    createdAt = 1L,
                ),
            )
            val commands = mutableListOf<String>()
            script(
                workspaces = """{"schema":1,"workspaces":[{"path":"/home/testuser/git/empty","display_path":"~/git/empty"}]}""",
                sessions = emptySessions(),
                seen = commands,
            )

            val viewModel = viewModel(hostId)
            viewModel.refresh()
            advanceUntilIdle()

            val state = viewModel.state.value
            assertTrue(state.loaded)
            assertNull(state.failure)
            assertEquals(1, state.workspaceCount)
            assertEquals("empty", state.roots.single().workspaces.single().label)
            assertTrue(state.roots.single().workspaces.single().durable)
            assertEquals(
                "pocketshell workspaces list --host '${shellIdentity(hostIdentity)}' --json",
                commands.single { it.startsWith("pocketshell workspaces list") },
            )
            assertFalse(commands.any { it.contains("display-name") })
        }

    @Test
    fun `a configured root remains after a healthy empty host listing`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        stack.db.projectRootDao().insert(
            ProjectRootEntity(hostId = hostId, label = "Work", path = "/home/testuser/work", createdAt = 1L),
        )
        script(workspaces = """{"schema":1,"workspaces":[]}""", sessions = emptySessions())

        val viewModel = viewModel(hostId)
        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state.loaded)
        assertEquals(listOf("Work"), state.roots.map { it.label })
        assertEquals(0, state.workspaceCount)
        assertEquals(0, state.sessionCount)
    }

    @Test
    fun `workspace membership failure is a hard unavailable state`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        stack.factory.script = { connection ->
            connection.onExecPrefix(
                "pocketshell workspaces list",
                ExecResult(127, "", "pocketshell: workspaces unavailable\n", false),
            )
        }

        val viewModel = viewModel(hostId)
        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.loaded)
        assertTrue(state.failure!!.contains("exit 127"))
        assertTrue(state.failure!!.contains("workspaces"))
    }

    @Test
    fun `folder creation clears its busy state when the host cannot connect`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        stack.factory.failWith = "connection refused"

        val viewModel = viewModel(hostId)
        viewModel.openCreateFolder("/home/testuser/git")
        viewModel.setCreateFolderName("notes")
        viewModel.createFolder()
        advanceUntilIdle()

        assertFalse(viewModel.state.value.creatingFolder)
        assertTrue(viewModel.state.value.createFolderFailure!!.contains("connect"))
    }

    @Test
    fun `workspace browser replaces the add form and restores it when dismissed`() =
        runTest(dispatcher) {
            val hostId = stack.seedHost()
            val viewModel = viewModel(hostId)

            viewModel.openAddWorkspace("/home/testuser/git")
            assertTrue(viewModel.state.value.addWorkspaceVisible)
            assertFalse(viewModel.state.value.addWorkspaceBrowserVisible)

            viewModel.browseWorkspaceFolder("/home/testuser/git")
            assertFalse(viewModel.state.value.addWorkspaceVisible)
            assertTrue(viewModel.state.value.addWorkspaceBrowserVisible)

            viewModel.dismissWorkspaceBrowser()
            assertTrue(viewModel.state.value.addWorkspaceVisible)
            assertFalse(viewModel.state.value.addWorkspaceBrowserVisible)
        }

    /**
     * Issue #2616, upgrade-shaped: a STORED root may be home-relative
     * (`~/git`), with no session or membership on the host to infer home from.
     * Browsing it must resolve `~` against the host's own home before any
     * SFTP use — and the sheet's root becomes absolute so containment and the
     * persisted path agree with what was browsed.
     */
    @Test
    fun `a home-relative stored root is browsed through the host's home`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        stack.factory.script = { connection ->
            connection.onExec("pwd", ExecResult(0, "/home/testuser\n", "", false))
            connection.sftpFixture().seedDirectory("/home/testuser/git")
            connection.sftpFixture().seedDirectory("/home/testuser/git/app")
        }

        val viewModel = viewModel(hostId)
        viewModel.openAddWorkspace("~/git")
        advanceUntilIdle()

        val state = viewModel.state.value
        assertNull(state.addWorkspaceRootFoldersFailure)
        assertEquals(
            listOf(WorkspaceFolderEntry("app", "/home/testuser/git/app")),
            state.addWorkspaceRootFolders,
        )
        assertEquals("the sheet's root must become absolute", "/home/testuser/git", state.addWorkspaceRootPath)
    }

    /** And a workspace added under it is persisted with its absolute path. */
    @Test
    fun `adding a workspace with a home-relative path persists the absolute path`() =
        runTest(dispatcher) {
            val hostId = stack.seedHost()
            val added = mutableListOf<String>()
            stack.factory.script = { connection ->
                connection.onExec("pwd", ExecResult(0, "/home/testuser\n", "", false))
                connection.onExecMatching("workspaces add", once = false, { "workspaces add" in it }) { command ->
                    added += command
                    ExecResult(0, """{"schema":1,"workspaces":[]}""", "", false)
                }
                connection.sftpFixture().seedDirectory("/home/testuser/git")
                connection.sftpFixture().seedDirectory("/home/testuser/git/app")
            }

            val viewModel = viewModel(hostId)
            viewModel.openAddWorkspace("~/git")
            advanceUntilIdle()
            viewModel.setAddWorkspacePath("~/git/app")
            viewModel.addWorkspace()
            advanceUntilIdle()

            assertNull(viewModel.state.value.addWorkspaceFailure)
            assertTrue("the add must have reached the host CLI", added.isNotEmpty())
            assertTrue(
                "the persisted path must be absolute, got: ${added.last()}",
                added.last().contains("/home/testuser/git/app") && !added.last().contains("~"),
            )
        }

    private fun viewModel(hostId: Long) = HostWorkspacesViewModel(
        savedStateHandle = SavedStateHandle(mapOf(Destination.ARG_HOST_ID to hostId)),
        registry = stack.registry,
        clients = HostCliClientFactory { connection -> HostCliClient(connection.asRemoteExec()) },
        hostDao = stack.db.hostDao(),
        projectRootDao = stack.db.projectRootDao(),
        workspaceOrderStore = WorkspaceOrderStore(ApplicationProvider.getApplicationContext()),
    )

    private fun script(
        workspaces: String,
        sessions: String,
        warnings: String = "[]",
        seen: MutableList<String> = mutableListOf(),
    ) {
        stack.factory.script = { connection ->
            connection.onExecMatching("workspace and sessions listing", once = false, { true }) { command ->
                seen += command
                when {
                    command.startsWith("pocketshell workspaces list") ->
                        ExecResult(0, workspaces, "", false)
                    command.startsWith("pocketshell sessions list") ->
                        ExecResult(0, sessions, "", false)
                    command.startsWith("pocketshell sessions warnings") ->
                        ExecResult(0, warnings, "", false)
                    else -> ExecResult(0, "", "", false)
                }
            }
        }
    }

    @Test
    fun `crash warnings surface beside the tree and ack all clears them`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        val commands = mutableListOf<String>()
        // The host keeps a warning until it is acknowledged, so the scripted
        // store stops reporting it only once the ack verb has run.
        var acked = false
        stack.factory.script = { connection ->
            connection.onExecMatching("workspace and sessions listing", once = false, { true }) { command ->
                commands += command
                when {
                    command.startsWith("pocketshell workspaces list") ->
                        ExecResult(0, """{"schema":1,"workspaces":[]}""", "", false)
                    command.startsWith("pocketshell sessions list") ->
                        ExecResult(0, emptySessions(), "", false)
                    command.startsWith("pocketshell sessions ack") -> {
                        acked = true
                        ExecResult(0, "", "", false)
                    }
                    command.startsWith("pocketshell sessions warnings") ->
                        ExecResult(0, if (acked) "[]" else crashWarnings(), "", false)
                    else -> ExecResult(0, "", "", false)
                }
            }
        }

        val viewModel = viewModel(hostId)
        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state.loaded)
        assertNull(state.failure)
        assertEquals(1, state.warnings.size)
        assertEquals(WarningKind.OOM, state.warnings.single().kind)
        assertEquals("api", state.warnings.single().tag)
        assertTrue(commands.none { it.startsWith("pocketshell sessions ack") })

        viewModel.ackAllWarnings()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.warnings.isEmpty())
        assertEquals(
            "pocketshell sessions ack --json",
            commands.single { it.startsWith("pocketshell sessions ack") },
        )
    }

    @Test
    fun `an old helper without the warnings verb degrades to no warnings`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        stack.factory.script = { connection ->
            connection.onExecMatching("listings", once = false, { true }) { command ->
                when {
                    command.startsWith("pocketshell workspaces list") ->
                        ExecResult(0, """{"schema":1,"workspaces":[]}""", "", false)
                    command.startsWith("pocketshell sessions list") ->
                        ExecResult(0, emptySessions(), "", false)
                    else -> ExecResult(2, "", "Error: no such command: warnings\n", false)
                }
            }
        }

        val viewModel = viewModel(hostId)
        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state.loaded)
        assertNull(state.failure)
        assertTrue(state.warnings.isEmpty())
    }

    private fun emptySessions(): String = """{"schema":3,"sessions":[],"errors":[]}"""

    private fun crashWarnings(): String = """
        [{"session":"0d5a4d1e-6a5c-4a1e-9d2f-5b7a0c3e8f11",
          "workspace":"/home/testuser/git/app","tag":"api","engine":"claude",
          "kind":"oom",
          "detail":"The kernel OOM killer terminated the session's main process.",
          "created_at_ms":1}]
    """.trimIndent()

    private fun shellIdentity(identity: String): String = identity.replace("'", "'\\''")
}
