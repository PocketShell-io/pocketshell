package com.pocketshell.next.mockapp

import com.pocketshell.next.terminal.NoOpTerminalSessionClient
import com.pocketshell.next.terminal.SessionUiState
import com.termux.terminal.TerminalSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The state→screen seam of the mock app (issue #2636 acceptance 3):
 * `MockAppState.to...UiState()` is what the production screens actually read,
 * so the deterministic loading/error/long-content switches must be proven to
 * SURVIVE the projection, not just exist on the state.
 *
 * Everything here runs on the plain JVM: the vendored `TerminalSession` builds
 * its emulator with no PTY, no thread and no Android call, so a throwaway
 * terminal can stand in for the live one exactly as the shell hands one in at
 * the composition boundary. Same event fold always yields the same projection
 * (determinism is inherited from the reducer and pinned in [MockAppReducerTest]).
 */
class MockAppProjectionsTest {

    /** A JVM-safe stand-in for the terminal the shell builds at composition. */
    private fun terminal(): TerminalSession = TerminalSession(
        /* columns = */ 80,
        /* rows = */ 24,
        /* cellWidthPx = */ 8,
        /* cellHeightPx = */ 16,
        /* transcriptRows = */ 200,
        /* client = */ NoOpTerminalSessionClient(),
    )

    private fun live() = SessionUiState.Live(terminal())

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
        assertSame(SessionUiState.Connecting, state.toSessionUiState(live()))
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
        val ui = state.toSessionUiState(SessionUiState.Live(terminalSession))
        assertTrue("expected Reconnecting, was $ui", ui is SessionUiState.Reconnecting)
        ui as SessionUiState.Reconnecting
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
        assertEquals(SessionUiState.Failed("Could not reach the session. Tap Retry to try again."), ui)
    }

    // ── Services: the enabled/loading gates gate the tunnel rows ──────────────

    @Test
    fun `disabled services project the host identity with no tunnel rows`() {
        val ui = MockAppState.populated().toServicesUiState()
        assertFalse(ui.enabled)
        assertFalse(ui.loading)
        assertTrue(ui.rows.isEmpty())
        assertTrue(ui.discoveredRows.isEmpty())
        assertEquals(MockAppState.HOST_ID, ui.hostId)
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
}
