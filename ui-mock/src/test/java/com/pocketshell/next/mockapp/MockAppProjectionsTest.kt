package com.pocketshell.next.mockapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The state→screen seam of the mock app (issue #2636 acceptance 3):
 * `MockAppState.to...UiState()` is the future shell's display seam, so the
 * deterministic loading/error/long-content switches must survive projection,
 * not merely exist on the aggregate state. Extracted families use their real
 * shared display types; unextracted families use named mock-local mirrors.
 *
 * Everything here runs on the plain JVM. The mock-local terminal is an opaque
 * display identity, so this module never needs Termux/native code. Same event
 * fold always yields the same projection (determinism is inherited from the
 * reducer and pinned in [MockAppReducerTest]).
 */
class MockAppProjectionsTest {

    private fun terminal(): MockTerminalSession = MockTerminalSession()

    private fun live() = MockSessionUiState.Live(terminal())

    // ── Host list: the loading switch + long-content fixture ─────────────────

    @Test
    fun `cold boot projects an unloaded empty host list`() {
        val ui = MockAppState.boot().toHostListUiState()
        assertFalse("cold launch must still read as unloaded", ui.loaded)
        assertTrue(ui.hosts.isEmpty())
    }

    @Test
    fun `populated state projects the whole mock host list as loaded`() {
        val ui = MockAppState.populated().toHostListUiState()
        assertTrue(ui.loaded)
        assertEquals(MockData.hosts, ui.hosts)
    }

    @Test
    fun `the deliberately long host row reaches the list verbatim`() {
        val row = MockAppState.populated().toHostListUiState().hosts.last()
        assertEquals(3L, row.id)
        assertEquals("relay-eu-central-1-with-a-deliberately-long-name", row.name)
        assertEquals("deploy@relay.example.io", row.subtitle)
    }

    // ── Session: the error/reconnect switches carry the payload ───────────────

    @Test
    fun `connecting projects the production connecting state`() {
        val state = MockAppState.populated().copy(sessionPhase = MockSessionPhase.CONNECTING)
        assertSame(MockSessionUiState.Connecting, state.toSessionUiState(live()))
    }

    @Test
    fun `live hands back the exact terminal state it was given`() {
        val state = MockAppState.populated().copy(sessionPhase = MockSessionPhase.LIVE)
        val terminalState = live()
        assertSame(terminalState, state.toSessionUiState(terminalState))
    }

    @Test
    fun `reconnecting carries attempt retry delay and the SAME terminal`() {
        val state = MockAppState.populated().copy(
            sessionPhase = MockSessionPhase.RECONNECTING,
            reconnectAttempt = 2,
            reconnectRetryInMs = 4500L,
        )
        val terminalSession = terminal()
        val ui = state.toSessionUiState(MockSessionUiState.Live(terminalSession))
        assertTrue("expected Reconnecting, was $ui", ui is MockSessionUiState.Reconnecting)
        ui as MockSessionUiState.Reconnecting
        assertEquals(2, ui.attempt)
        assertEquals(4500L, ui.retryInMs)
        assertSame("reconnect must reuse the live emulator, never reseed", terminalSession, ui.terminal)
    }

    @Test
    fun `failed projects the exact user-facing message`() {
        val state = MockAppState.populated().copy(
            sessionPhase = MockSessionPhase.FAILED,
            sessionMessage = "Could not reach the session. Tap Retry to try again.",
        )
        val ui = state.toSessionUiState(live())
        assertEquals(MockSessionUiState.Failed("Could not reach the session. Tap Retry to try again."), ui)
    }

    // ── Services: the enabled/loading gates gate the tunnel rows ──────────────

    @Test
    fun `disabled services project the host identity with no tunnel rows`() {
        val ui = MockAppState.populated().toServicesUiState()
        assertFalse(ui.enabled)
        assertFalse(ui.loading)
        assertTrue(ui.rows.isEmpty())
        assertTrue(ui.discoveredRows.isEmpty())
        // `hostId` is the one field the D11 display state drops (never
        // painted); the host identity the screens DO render is pinned below.
        assertEquals("hetzner", ui.hostName)
        assertEquals(MockAppState.HOST_ADDRESS, ui.hostSubtitle)
    }

    @Test
    fun `services loading shows the spinner without inventing rows`() {
        val ui = MockAppState.populated().copy(servicesLoading = true).toServicesUiState()
        assertTrue(ui.loading)
        assertTrue("loading must not fabricate rows", ui.rows.isEmpty())
    }

    @Test
    fun `enabled services project every mock tunnel`() {
        val ui = MockAppState.populated().copy(servicesEnabled = true).toServicesUiState()
        assertTrue(ui.enabled)
        assertEquals(MockData.tunnels, ui.rows)
        assertEquals(MockData.tunnels, ui.discoveredRows)
    }

    // ── Usage: the refreshing switch mirrors onto the screen state ────────────

    @Test
    fun `usage projection is idle and loaded with the mock quota snapshot`() {
        val ui = MockAppState.populated().toUsageUiState()
        assertFalse(ui.isRefreshing)
        assertTrue(ui.loaded)
        assertEquals(listOf(MockData.usageHost), ui.hosts)
        assertEquals(MockAppState.HOST_ID, ui.selectedHostId)
        assertEquals("hetzner", ui.selectedHostName)
    }

    @Test
    fun `an in-flight usage refresh projects as refreshing`() {
        val ui = MockAppState.populated().copy(usageRefreshing = true).toUsageUiState()
        assertTrue(ui.isRefreshing)
    }

    // ── Workspaces: typing and the named-arg root fixtures meet the screen ────

    @Test
    fun `workspace search typing flows into the projection`() {
        val typed = reduce(MockAppState.populated(), MockAppEvent.WorkspaceSearchChange("git"))
        assertEquals("git", typed.toWorkspaceUiState().searchQuery)
    }

    @Test
    fun `workspace projection carries the named-arg registered roots`() {
        val ui = MockAppState.populated().toWorkspaceUiState()
        assertTrue(ui.loaded)
        assertEquals(MockAppState.HOST_ID, ui.hostId)
        assertEquals("hetzner", ui.hostLabel)
        val labels = ui.roots.map { it.label }
        assertTrue("Git root missing from $labels", "Git" in labels)
        assertTrue("Work root missing from $labels", "Work" in labels)
        val git = ui.roots.first { it.label == "Git" }
        assertEquals("/home/alexey/git", git.path)
        assertTrue("root session must count toward the Git root", git.sessionCount >= 1)
    }

    @Test
    fun `workspace start projection opens on the pinned start path`() {
        val ui = MockAppState.populated().toWorkspaceStartUiState()
        assertEquals(MockData.START_WORKSPACE_PATH, ui.workspacePath)
        assertEquals(MockAppState.HOST_ID, ui.hostId)
        assertEquals("hetzner", ui.hostLabel)
        assertTrue(ui.loaded)
    }

    @Test
    fun `the session label mirrors the mock data`() {
        assertEquals(MockData.SESSION_NAME, MockAppState.populated().sessionName)
    }

    // ── D18: mirror-vs-shared-reuse seams are pinned by type ──────────────────

    @Test
    fun `ssh keys project the SHARED SshKeysUiState with rows generating and message`() {
        val ui: Any = MockAppState.populated().toSshKeysUiState()
        assertTrue(
            "the D13 shared type must be reused, not a mock mirror",
            ui is com.pocketshell.next.hosts.SshKeysUiState,
        )
        val shared = ui as com.pocketshell.next.hosts.SshKeysUiState
        assertTrue(shared.loaded)
        assertEquals(MockData.sshKeys, shared.keys)
        assertFalse(shared.generating)
        assertNull(shared.message)
        val generating = MockAppState.populated().copy(sshKeysGenerating = true, sshKeyMessage = "Generated key")
        assertTrue(generating.toSshKeysUiState().generating)
        assertEquals("Generated key", generating.toSshKeysUiState().message)
    }

    @Test
    fun `cold ssh keys project unloaded with no rows`() {
        val ui = MockAppState.boot().toSshKeysUiState()
        assertFalse(ui.loaded)
        assertTrue(ui.keys.isEmpty())
    }

    @Test
    fun `files project the SHARED FileExplorerDisplayState with entries crumbs and switches`() {
        val ui: Any = MockAppState.populated()
            .copy(filesLoaded = true, filesPath = MockData.FILES_ROOT_PATH)
            .toFileExplorerUiState()
        assertTrue(
            "the D14 shared type must be reused, not a mock mirror",
            ui is com.pocketshell.next.files.FileExplorerDisplayState,
        )
        val shared = ui as com.pocketshell.next.files.FileExplorerDisplayState
        assertTrue(shared.loaded)
        assertFalse(shared.loading)
        assertNull(shared.failure)
        assertEquals(MockData.FILES_ROOT_PATH, shared.path)
        assertEquals(MockData.fileEntries, shared.entries)
        assertEquals("hetzner", shared.subtitle)
        assertTrue(
            "the long file name must reach the screen verbatim",
            shared.entries.any { it.name.startsWith("a-deliberately-long-file-name") },
        )
    }

    @Test
    fun `files loading and failure switches flow into the shared projection`() {
        val loading = MockAppState.populated().copy(filesLoading = true).toFileExplorerUiState()
        assertTrue(loading.loading)
        val failed = MockAppState.populated()
            .copy(filesLoaded = true, filesFailure = "host unreachable")
            .toFileExplorerUiState()
        assertEquals("host unreachable", failed.failure)
        assertFalse("a failed read is not a healthy empty state", failed.isEmptyAndHealthy)
    }

    @Test
    fun `file crumbs project the host crumb then one crumb per segment below the root`() {
        val crumbs = projectFileCrumbs(
            hostLabel = "hetzner",
            root = "/home/alexey/git",
            path = "/home/alexey/git/pocketshell/docs",
        )
        assertEquals(
            listOf(
                "hetzner" to "/home/alexey/git",
                "pocketshell" to "/home/alexey/git/pocketshell",
                "docs" to "/home/alexey/git/pocketshell/docs",
            ),
            crumbs.map { it.label to it.path },
        )
        assertEquals(
            "the root itself projects exactly the host crumb",
            listOf("hetzner" to "/home/alexey/git"),
            projectFileCrumbs("hetzner", "/home/alexey/git", "/home/alexey/git").map { it.label to it.path },
        )
        assertEquals(
            "a path outside the root degrades to a single self-named crumb",
            listOf("/etc" to "/etc"),
            projectFileCrumbs("hetzner", "/home/alexey/git", "/etc").map { it.label to it.path },
        )
        assertTrue(projectFileCrumbs("hetzner", "/home/alexey/git", "").isEmpty())
    }

    @Test
    fun `viewer projection carries the long content and host identity`() {
        val viewer = MockViewerUiState(loaded = true, content = MockData.LONG_FILE_CONTENT, path = MockData.VIEWER_PATH)
        val ui = MockAppState.populated().copy(viewer = viewer).toViewerUiState()
        assertEquals(MockAppState.HOST_ID, ui.hostId)
        assertEquals("hetzner", ui.hostName)
        assertEquals(MockData.VIEWER_PATH, ui.path)
        assertTrue("long content must survive projection", ui.content.length > 1_000)
        val editing = ui.copy(editing = true, draft = "typed")
        assertEquals("typed", editing.draft)
    }

    @Test
    fun `workspace screen projection fills host identity and default sessions`() {
        var state = reduce(MockAppState.populated(), MockAppEvent.Navigate(MockDestination.Workspace))
        val ui = state.toWorkspaceScreenUiState()
        assertTrue(ui.loaded)
        assertEquals(MockAppState.HOST_ID, ui.hostId)
        assertEquals("hetzner", ui.hostLabel)
        assertEquals(MockData.START_WORKSPACE_PATH, ui.workspacePath)
        assertTrue("sessions default from the mock data", ui.sessions.isNotEmpty())
        state = reduce(state, MockAppEvent.WorkspaceRefreshFailed("listing failed"))
        assertEquals("listing failed", state.toWorkspaceScreenUiState().failure)
    }

    @Test
    fun `workspace roots projection fills host identity over the root rows`() {
        val navigated = reduce(MockAppState.populated(), MockAppEvent.Navigate(MockDestination.WorkspaceRoots))
        val ui = navigated.toWorkspaceRootsUiState()
        assertEquals(MockAppState.HOST_ID, ui.hostId)
        assertEquals("hetzner", ui.hostName)
        assertTrue(ui.loaded)
        assertEquals(listOf("Git", "Work"), ui.roots.map { it.label })
        val failed = reduce(navigated, MockAppEvent.WorkspaceRootsLoadFailed("host unreachable"))
        assertEquals("host unreachable", failed.toWorkspaceRootsUiState().failure)
    }

    @Test
    fun `account sync state IS the shared AccountSyncUiState`() {
        val state = reduce(MockAppState.populated(), MockAppEvent.AccountSyncSignInStart)
        val ui: Any = state.toAccountSyncUiState()
        assertTrue(
            "the D7 shared type must be reused, not a mock mirror",
            ui is com.pocketshell.next.sync.AccountSyncUiState,
        )
        assertTrue(state.accountSync.clientConfigured)
    }

    @Test
    fun `settings update check and build info are the shared types`() {
        val state = reduce(MockAppState.populated(), MockAppEvent.UpdateCheckUpdateAvailable)
        assertTrue(
            "SettingsUpdateCheckState is the shared D3 type",
            state.updateCheck is com.pocketshell.next.settings.SettingsUpdateCheckState.UpdateAvailable,
        )
        assertEquals("1.2.0", state.buildInfo.versionName)
        assertEquals(312L, state.buildInfo.versionCode)
    }

    @Test
    fun `tunnel detail projects the known row and flags manual tunnels`() {
        val discovered = reduce(MockAppState.populated(), MockAppEvent.OpenTunnel(8000))
        assertEquals("python", discovered.tunnelDetailTunnel()?.process)
        assertFalse(discovered.tunnelDetailManual)
        assertEquals(8000, discovered.tunnelDetailPort)
        val unknown = reduce(MockAppState.populated(), MockAppEvent.OpenTunnel(9999))
        assertNull("an unknown port is the detail screen's empty state", unknown.tunnelDetailTunnel())
    }

    @Test
    fun `add tunnel validity and collision derive from the known local ports`() {
        val blank = MockAppState.populated()
        assertFalse(blank.addTunnelValid)
        assertNull(blank.addTunnelCollision())
        val clash = blank.copy(
            addTunnelForm = MockAddTunnelFormState(name = "clash", remotePort = "9999", localPort = "35173"),
        )
        assertFalse(clash.addTunnelValid)
        assertEquals("Local port 35173 is already forwarding vite.", clash.addTunnelCollision())
        val free = clash.copy(addTunnelForm = clash.addTunnelForm.copy(localPort = "45173"))
        assertTrue(free.addTunnelValid)
        assertNull(free.addTunnelCollision())
    }

    @Test
    fun `usage failure projects into the shared failedHosts list`() {
        val failed = MockAppState.populated().copy(usageFailure = "quota read failed")
        val ui = failed.toUsageUiState()
        assertEquals(1, ui.failedHosts.size)
        assertEquals(MockAppState.HOST_ID, ui.failedHosts.single().hostId)
        assertEquals("hetzner", ui.failedHosts.single().hostName)
        assertEquals("quota read failed", ui.failedHosts.single().reason)
        assertTrue("the healthy projection has no failed hosts", MockAppState.populated().toUsageUiState().failedHosts.isEmpty())
    }
}
