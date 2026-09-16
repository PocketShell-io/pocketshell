package com.pocketshell.next.mockapp

import com.pocketshell.next.hosts.HostFormState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain-JVM coverage of the mock app's pure state machine (issue #2636
 * acceptance 3: fake state changes without side effects).
 *
 * Two properties matter beyond the individual transitions and are pinned
 * here explicitly: the reducer NEVER mutates its input state (purity), and
 * no-op events return the SAME instance so a driver can cheaply distinguish
 * "nothing changed" from "state replaced".
 */
class MockAppReducerTest {

    // ── Purity / determinism ─────────────────────────────────────────────────

    @Test
    fun `reduce never mutates the state it is given`() {
        val before = MockAppState.populated()
        val events = listOf(
            MockAppEvent.Navigate(MockDestination.Session),
            MockAppEvent.OpenHost(1L),
            MockAppEvent.EditHost(1L),
            MockAppEvent.ComposerDraftChange("hello"),
            MockAppEvent.ComposerSend,
            MockAppEvent.SessionFailed,
            MockAppEvent.UsageRefreshStart,
            MockAppEvent.SshKeysMessageDismiss,
        )
        var state = before
        for (event in events) state = reduce(state, event)
        assertEquals("reducer mutated its input", MockAppState.populated(), before)
        assertNotSame(before, state)
    }

    @Test
    fun `the same event sequence always yields the same state`() {
        fun fold(): MockAppState =
            listOf<MockAppEvent>(
                MockAppEvent.ComposerDraftChange(MockData.TYPED_DRAFT),
                MockAppEvent.ComposerSend,
                MockAppEvent.SessionFailed,
                MockAppEvent.SessionRetry,
                MockAppEvent.SessionAttached,
                MockAppEvent.ServicesDiscoveryChange(true),
                MockAppEvent.WorkspaceSearchChange("git"),
            ).fold(MockAppState.populated()) { s, e -> reduce(s, e) }

        assertEquals(fold(), fold())
    }

    @Test
    fun `no-op events return the same instance`() {
        val blankDraft = MockAppState.populated()
        assertSame(
            "blank ComposerSend must be a no-op",
            blankDraft,
            reduce(blankDraft, MockAppEvent.ComposerSend),
        )
        assertSame(
            "Back at a root destination must be a no-op",
            blankDraft,
            reduce(blankDraft, MockAppEvent.Back),
        )
        val atSettings = blankDraft.copy(destination = MockDestination.Settings)
        assertSame(reduce(atSettings, MockAppEvent.Back), atSettings)

        val withHistory = blankDraft.copy(composerHistory = listOf("only entry"))
        val unknownRestore = reduce(
            withHistory,
            MockAppEvent.ComposerHistoryRestore(id = 99L),
        )
        assertSame("unknown history id must be a no-op", withHistory, unknownRestore)
    }

    // ── Navigation ───────────────────────────────────────────────────────────

    @Test
    fun `navigate moves the destination`() {
        val state = reduce(MockAppState.populated(), MockAppEvent.Navigate(MockDestination.Services))
        assertEquals(MockDestination.Services, state.destination)
    }

    @Test
    fun `navigating to the host form resets it to add mode`() {
        val dirty = MockAppState.populated().copy(
            hostForm = HostFormState(name = "old", editing = true, saved = true),
        )
        val state = reduce(dirty, MockAppEvent.Navigate(MockDestination.HostForm))
        assertEquals(MockDestination.HostForm, state.destination)
        assertFalse(state.hostForm.editing)
        assertFalse(state.hostForm.saved)
        assertEquals("old", state.hostForm.name)
    }

    @Test
    fun `navigating to ssh keys marks them loaded and clears the one-shot message`() {
        val withMessage = MockAppState.populated().copy(
            sshKeysLoaded = false,
            sshKeyMessage = "Key added",
        )
        val state = reduce(withMessage, MockAppEvent.Navigate(MockDestination.SshKeys))
        assertEquals(MockDestination.SshKeys, state.destination)
        assertTrue(state.sshKeysLoaded)
        assertNull(state.sshKeyMessage)
    }

    @Test
    fun `navigating to usage cancels an in-flight refresh`() {
        val refreshing = MockAppState.populated().copy(usageRefreshing = true)
        val state = reduce(refreshing, MockAppEvent.Navigate(MockDestination.Usage))
        assertEquals(MockDestination.Usage, state.destination)
        assertFalse(state.usageRefreshing)
    }

    @Test
    fun `back follows the parent map`() {
        val inForm = MockAppState.populated().copy(destination = MockDestination.HostForm)
        assertEquals(MockDestination.Hosts, reduce(inForm, MockAppEvent.Back).destination)
        val inSession = MockAppState.populated().copy(destination = MockDestination.Session)
        assertEquals(MockDestination.Workspaces, reduce(inSession, MockAppEvent.Back).destination)
    }

    // ── Host rows / form ─────────────────────────────────────────────────────

    @Test
    fun `open host opens that host's workspaces`() {
        val state = reduce(MockAppState.populated(), MockAppEvent.OpenHost(1L))
        assertEquals(MockDestination.Workspaces, state.destination)
    }

    @Test
    fun `edit host loads the form from the row subtitle`() {
        val state = reduce(MockAppState.populated(), MockAppEvent.EditHost(1L))
        assertEquals(MockDestination.HostForm, state.destination)
        assertTrue(state.hostForm.editing)
        assertEquals("hetzner", state.hostForm.name)
        assertEquals("alexey", state.hostForm.username)
        assertEquals("135.181.114.209", state.hostForm.hostname)
    }

    @Test
    fun `edit host with an unknown id opens the add form`() {
        val state = reduce(MockAppState.populated(), MockAppEvent.EditHost(999L))
        assertEquals(MockDestination.HostForm, state.destination)
        assertFalse(state.hostForm.editing)
    }

    @Test
    fun `host form change replaces the draft wholesale`() {
        val next = HostFormState(name = "builder", hostname = "10.0.0.7", username = "root", port = "2222")
        val state = reduce(MockAppState.populated(), MockAppEvent.HostFormChange(next))
        assertEquals(next, state.hostForm)
    }

    // ── Composer ─────────────────────────────────────────────────────────────

    @Test
    fun `composer draft change updates the draft`() {
        val state = reduce(MockAppState.populated(), MockAppEvent.ComposerDraftChange("hi"))
        assertEquals("hi", state.composerDraft)
    }

    @Test
    fun `composer send moves the draft to history and clears it`() {
        var state = MockAppState.populated()
        state = reduce(state, MockAppEvent.ComposerDraftChange("first"))
        state = reduce(state, MockAppEvent.ComposerSend)
        state = reduce(state, MockAppEvent.ComposerDraftChange("second"))
        state = reduce(state, MockAppEvent.ComposerSend)
        assertEquals(listOf("first", "second"), state.composerHistory)
        assertEquals("", state.composerDraft)
    }

    @Test
    fun `composer history restore reuses a sent message by one-based id`() {
        var state = MockAppState.populated()
        state = reduce(state, MockAppEvent.ComposerDraftChange("alpha"))
        state = reduce(state, MockAppEvent.ComposerSend)
        state = reduce(state, MockAppEvent.ComposerDraftChange("beta"))
        state = reduce(state, MockAppEvent.ComposerSend)
        state = reduce(state, MockAppEvent.ComposerHistoryRestore(2L))
        assertEquals("beta", state.composerDraft)
    }

    @Test
    fun `composer history entry helper is one-based and null out of range`() {
        val state = MockAppState.populated().copy(composerHistory = listOf("a", "b"))
        assertEquals("a", state.composerDraftHistoryEntry(1L))
        assertEquals("b", state.composerDraftHistoryEntry(2L))
        assertNull(state.composerDraftHistoryEntry(0L))
        assertNull(state.composerDraftHistoryEntry(3L))
    }

    // ── Workspaces / services / usage ────────────────────────────────────────

    @Test
    fun `workspace search change updates the query`() {
        val state = reduce(MockAppState.populated(), MockAppEvent.WorkspaceSearchChange("aplexer"))
        assertEquals("aplexer", state.workspaceSearchQuery)
    }

    @Test
    fun `services discovery toggle sets the intent and clears loading`() {
        val loading = MockAppState.populated().copy(servicesLoading = true)
        val state = reduce(loading, MockAppEvent.ServicesDiscoveryChange(true))
        assertTrue(state.servicesEnabled)
        assertFalse(state.servicesLoading)
    }

    @Test
    fun `usage refresh round trip toggles the flag`() {
        var state = reduce(MockAppState.populated(), MockAppEvent.UsageRefreshStart)
        assertTrue(state.usageRefreshing)
        state = reduce(state, MockAppEvent.UsageRefreshComplete)
        assertFalse(state.usageRefreshing)
    }

    // ── Session phase ────────────────────────────────────────────────────────

    @Test
    fun `session retry from failed returns to connecting with a clear message`() {
        val failed = MockAppState.populated().copy(
            sessionPhase = MockSessionPhase.FAILED,
            sessionMessage = "Could not reach the session.",
        )
        val state = reduce(failed, MockAppEvent.SessionRetry)
        assertEquals(MockSessionPhase.CONNECTING, state.sessionPhase)
        assertEquals("", state.sessionMessage)
    }

    @Test
    fun `session attached reaches live`() {
        val state = reduce(MockAppState.populated(), MockAppEvent.SessionAttached)
        assertEquals(MockSessionPhase.LIVE, state.sessionPhase)
        assertEquals("", state.sessionMessage)
    }

    @Test
    fun `session failed lands in failed with user-facing text`() {
        val state = reduce(MockAppState.populated(), MockAppEvent.SessionFailed)
        assertEquals(MockSessionPhase.FAILED, state.sessionPhase)
        assertTrue(state.sessionMessage.isNotBlank())
    }

    // ── SSH keys ─────────────────────────────────────────────────────────────

    @Test
    fun `ssh keys message dismiss clears the message`() {
        val withMessage = MockAppState.populated().copy(sshKeyMessage = "Generated ed25519 key")
        val state = reduce(withMessage, MockAppEvent.SshKeysMessageDismiss)
        assertNull(state.sshKeyMessage)
    }
}
