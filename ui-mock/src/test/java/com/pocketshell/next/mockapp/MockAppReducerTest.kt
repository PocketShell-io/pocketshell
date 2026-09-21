package com.pocketshell.next.mockapp

import com.pocketshell.next.crash.CrashReportsLoadState
import com.pocketshell.next.hosts.HostFormState
import com.pocketshell.next.settings.AppSettings
import com.pocketshell.next.settings.SettingsUpdateCheckState
import com.pocketshell.next.sync.SyncOutcomeDisplay
import com.pocketshell.next.sync.SyncSignInPhase
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
            // D18 families:
            MockAppEvent.Navigate(MockDestination.Diagnostics),
            MockAppEvent.OpenReport("report-1"),
            MockAppEvent.OpenTunnel(5173),
            MockAppEvent.SettingsChange(MockAppState.populated().settings.copy(terminalTextSizePx = 34)),
            MockAppEvent.SshKeyGenerationStart,
            MockAppEvent.SshKeyGenerationComplete("laptop"),
            MockAppEvent.FilesRefreshStart,
            MockAppEvent.FilesRefreshFailed("host unreachable"),
            MockAppEvent.ViewerEditStart,
            MockAppEvent.ViewerDraftChange("typed"),
            MockAppEvent.ViewerSaveFailed("write failed"),
            MockAppEvent.WorkspaceActionNameChange("new folder"),
            MockAppEvent.WorkspaceActionDismiss,
            MockAppEvent.AccountSyncSignInStart,
            MockAppEvent.AccountSyncSignInFailed("cancelled"),
            MockAppEvent.UpdateCheckStart,
            MockAppEvent.UpdateCheckFailed("offline"),
            MockAppEvent.DiagnosticsLoadFailed("store unreadable"),
            MockAppEvent.UsageRefreshFailed("quota read failed"),
            MockAppEvent.AddTunnelFormChange(MockAddTunnelFormState(name = "web", remotePort = "8443", localPort = "8443")),
            MockAppEvent.AddTunnelSubmit,
            MockAppEvent.AddWorkspaceRootFormChange(MockAddWorkspaceRootForm(label = "Repos", path = "/srv/repos")),
            MockAppEvent.AddWorkspaceRootSubmit,
            MockAppEvent.ReorderMove(0, 1),
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
                MockAppEvent.Navigate(MockDestination.Diagnostics),
                MockAppEvent.OpenReport("report-2026-0920-0814"),
                MockAppEvent.OpenTunnel(8000),
                MockAppEvent.UpdateCheckStart,
                MockAppEvent.UpdateCheckUpdateAvailable,
                MockAppEvent.AccountSyncSignInStart,
                MockAppEvent.AccountSyncSignInExchange,
                MockAppEvent.AccountSyncSignInCompleted(MockData.ACCOUNT_EMAIL),
                MockAppEvent.AccountSyncSyncNow,
                MockAppEvent.AccountSyncSyncFailed("host offline"),
                MockAppEvent.ViewerEditStart,
                MockAppEvent.ViewerDraftChange(MockData.LONG_FILE_CONTENT),
                MockAppEvent.ViewerSaveComplete("Saved"),
                MockAppEvent.ReorderMove(0, 1),
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

        assertSame(
            "invalid AddTunnelSubmit must be a no-op",
            blankDraft,
            reduce(blankDraft, MockAppEvent.AddTunnelSubmit),
        )
        assertSame(
            "invalid AddWorkspaceRootSubmit must be a no-op",
            blankDraft,
            reduce(blankDraft, MockAppEvent.AddWorkspaceRootSubmit),
        )
        assertSame(
            "out-of-range ReorderMove must be a no-op",
            blankDraft,
            reduce(blankDraft, MockAppEvent.ReorderMove(0, 3)),
        )
        assertSame(
            "identity ReorderMove must be a no-op",
            blankDraft,
            reduce(blankDraft, MockAppEvent.ReorderMove(1, 1)),
        )
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

    @Test
    fun `back walks every child destination to its documented parent`() {
        val expectedParents = mapOf(
            MockDestination.HostForm to MockDestination.Hosts,
            MockDestination.Workspaces to MockDestination.Hosts,
            MockDestination.Services to MockDestination.Hosts,
            MockDestination.SshKeys to MockDestination.Settings,
            MockDestination.WorkspaceStart to MockDestination.Workspaces,
            MockDestination.Session to MockDestination.Workspaces,
            MockDestination.Usage to MockDestination.Workspaces,
            // D18 destinations:
            MockDestination.Workspace to MockDestination.Workspaces,
            MockDestination.Files to MockDestination.Workspaces,
            MockDestination.FileViewer to MockDestination.Files,
            MockDestination.TerminalSettings to MockDestination.Settings,
            MockDestination.VoiceSettings to MockDestination.Settings,
            MockDestination.ConnectionSettings to MockDestination.Settings,
            MockDestination.AdvancedSettings to MockDestination.Settings,
            MockDestination.AccountSync to MockDestination.Settings,
            MockDestination.Diagnostics to MockDestination.Settings,
            MockDestination.DiagnosticReport to MockDestination.Diagnostics,
            MockDestination.About to MockDestination.Settings,
            MockDestination.Update to MockDestination.About,
            MockDestination.HostUsage to MockDestination.Workspaces,
            MockDestination.TunnelDetail to MockDestination.Services,
            MockDestination.AddTunnel to MockDestination.Services,
            MockDestination.WorkspaceRoots to MockDestination.ConnectionSettings,
            MockDestination.AddWorkspaceRoot to MockDestination.WorkspaceRoots,
            MockDestination.ReorderWorkspaces to MockDestination.Workspaces,
            MockDestination.WorkspaceRootAction to MockDestination.Workspaces,
        )
        for ((child, parent) in expectedParents) {
            val state = MockAppState.populated().copy(destination = child)
            assertEquals("Back from $child", parent, reduce(state, MockAppEvent.Back).destination)
        }
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

    @Test
    fun `host form field edits accumulate without clobbering earlier fields`() {
        var state = reduce(MockAppState.populated(), MockAppEvent.EditHost(2L))
        state = reduce(state, MockAppEvent.HostFormChange(state.hostForm.copy(port = "2222")))
        assertEquals("root", state.hostForm.username)
        assertEquals("10.0.0.7", state.hostForm.hostname)
        assertEquals("builder", state.hostForm.name)
        assertEquals("2222", state.hostForm.port)
        state = reduce(state, MockAppEvent.HostFormChange(state.hostForm.copy(username = "deploy")))
        assertEquals("deploy", state.hostForm.username)
        assertEquals("earlier edit must survive", "2222", state.hostForm.port)
        assertEquals("10.0.0.7", state.hostForm.hostname)
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

    @Test
    fun `the long typed draft survives send and history restore verbatim`() {
        var state = reduce(MockAppState.populated(), MockAppEvent.ComposerDraftChange(MockData.TYPED_DRAFT))
        assertEquals(MockData.TYPED_DRAFT, state.composerDraft)
        state = reduce(state, MockAppEvent.ComposerSend)
        assertEquals("send clears the long draft", "", state.composerDraft)
        assertEquals(listOf(MockData.TYPED_DRAFT), state.composerHistory)
        state = reduce(state, MockAppEvent.ComposerHistoryRestore(1L))
        assertEquals("restore must not truncate or mangle long content", MockData.TYPED_DRAFT, state.composerDraft)
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

    @Test
    fun `ssh keys one-shot message survives navigation away and clears on return`() {
        val withMessage = MockAppState.populated().copy(
            sshKeysLoaded = true,
            sshKeyMessage = "Key added",
        )
        val elsewhere = reduce(withMessage, MockAppEvent.Navigate(MockDestination.Hosts))
        assertEquals(
            "navigating away must not silently eat the one-shot",
            "Key added",
            elsewhere.sshKeyMessage,
        )
        val returned = reduce(elsewhere, MockAppEvent.Navigate(MockDestination.SshKeys))
        assertEquals(MockDestination.SshKeys, returned.destination)
        assertTrue("returning keeps the screen loaded", returned.sshKeysLoaded)
        assertNull(returned.sshKeyMessage)
    }

    // ── Error recovery ladder ─────────────────────────────────────────────────

    @Test
    fun `failed to retry to attached to live recovers with no residue`() {
        var state = reduce(MockAppState.populated(), MockAppEvent.SessionFailed)
        assertEquals(MockSessionPhase.FAILED, state.sessionPhase)
        assertTrue(state.sessionMessage.isNotBlank())
        state = reduce(state, MockAppEvent.SessionRetry)
        assertEquals(MockSessionPhase.CONNECTING, state.sessionPhase)
        assertEquals("", state.sessionMessage)
        state = reduce(state, MockAppEvent.SessionAttached)
        assertEquals(MockSessionPhase.LIVE, state.sessionPhase)
        assertEquals("", state.sessionMessage)
    }

    // ── D18: navigation into the new destinations ─────────────────────────────

    @Test
    fun `navigating to workspace completes the mock read on the pinned start path`() {
        val state = reduce(MockAppState.populated(), MockAppEvent.Navigate(MockDestination.Workspace))
        assertEquals(MockDestination.Workspace, state.destination)
        val screen = state.workspaceScreen
        assertTrue(screen.loaded)
        assertFalse(screen.loading)
        assertNull(screen.failure)
        assertEquals(MockData.START_WORKSPACE_PATH, screen.workspacePath)
    }

    @Test
    fun `navigating to files completes the directory read and clears a stale failure`() {
        val stale = MockAppState.populated().copy(filesFailure = "old timeout")
        val state = reduce(stale, MockAppEvent.Navigate(MockDestination.Files))
        assertEquals(MockDestination.Files, state.destination)
        assertTrue(state.filesLoaded)
        assertFalse(state.filesLoading)
        assertNull(state.filesFailure)
    }

    @Test
    fun `navigating to the file viewer loads the pinned long content`() {
        val state = reduce(MockAppState.populated(), MockAppEvent.Navigate(MockDestination.FileViewer))
        assertEquals(MockDestination.FileViewer, state.destination)
        assertTrue(state.viewer.loaded)
        assertEquals(MockData.VIEWER_PATH, state.viewer.path)
        assertEquals(MockData.LONG_FILE_CONTENT, state.viewer.content)
        assertTrue("long-content fixture must actually be long", state.viewer.content.length > 1_000)
    }

    @Test
    fun `navigating to diagnostics marks the store ready with the mock reports`() {
        val state = reduce(MockAppState.populated(), MockAppEvent.Navigate(MockDestination.Diagnostics))
        assertEquals(MockDestination.Diagnostics, state.destination)
        assertEquals(CrashReportsLoadState.Ready, state.diagnosticsLoad)
        assertEquals(MockData.crashReports, state.crashReports)
    }

    @Test
    fun `navigating to workspace roots projects the reorderable roots`() {
        val state = reduce(MockAppState.populated(), MockAppEvent.Navigate(MockDestination.WorkspaceRoots))
        assertEquals(MockDestination.WorkspaceRoots, state.destination)
        assertTrue(state.workspaceRoots.loaded)
        assertNull(state.workspaceRoots.failure)
        assertEquals(listOf("Git", "Work"), state.workspaceRoots.roots.map { it.label })
        assertEquals(
            "the Git root must show its workspace count",
            2,
            state.workspaceRoots.roots.first { it.label == "Git" }.workspaceCount,
        )
    }

    @Test
    fun `navigating to the workspace root action opens the create folder sheet`() {
        val state = reduce(MockAppState.populated(), MockAppEvent.Navigate(MockDestination.WorkspaceRootAction))
        assertEquals(MockDestination.WorkspaceRootAction, state.destination)
        assertTrue(state.workspaceAction.visible)
        assertEquals(MockData.START_WORKSPACE_PATH, state.workspaceAction.rootPath)
        assertEquals("", state.workspaceAction.name)
    }

    @Test
    fun `usage and host usage navigation clear both refresh and failure`() {
        val dirty = MockAppState.populated().copy(usageRefreshing = true, usageFailure = "quota read failed")
        val usage = reduce(dirty, MockAppEvent.Navigate(MockDestination.Usage))
        assertEquals(MockDestination.Usage, usage.destination)
        assertFalse(usage.usageRefreshing)
        assertNull(usage.usageFailure)
        val hostUsage = reduce(dirty, MockAppEvent.Navigate(MockDestination.HostUsage))
        assertEquals(MockDestination.HostUsage, hostUsage.destination)
        assertFalse(hostUsage.usageRefreshing)
        assertNull(hostUsage.usageFailure)
    }

    @Test
    fun `open report selects the stored report id`() {
        val ready = reduce(MockAppState.populated(), MockAppEvent.Navigate(MockDestination.Diagnostics))
        val state = reduce(ready, MockAppEvent.OpenReport("report-2026-0920-0814"))
        assertEquals(MockDestination.DiagnosticReport, state.destination)
        assertEquals("report-2026-0920-0814", state.diagnosticReportId)
    }

    @Test
    fun `open tunnel selects the remote port from the discovered rows`() {
        val state = reduce(MockAppState.populated(), MockAppEvent.OpenTunnel(5173))
        assertEquals(MockDestination.TunnelDetail, state.destination)
        assertEquals(5173, state.tunnelDetailPort)
        assertFalse("discovered rows are never manual", state.tunnelDetailManual)
        assertEquals("vite", state.tunnelDetailTunnel()?.process)
    }

    // ── D18: settings pages (shared AppSettings) ──────────────────────────────

    @Test
    fun `settings field edits accumulate across pages without clobbering earlier fields`() {
        var state = reduce(
            MockAppState.populated(),
            MockAppEvent.SettingsChange(MockAppState.populated().settings.copy(terminalTextSizePx = 34)),
        )
        assertEquals(34, state.settings.terminalTextSizePx)
        state = reduce(
            state,
            MockAppEvent.SettingsChange(state.settings.copy(voiceLanguage = "en")),
        )
        assertEquals("earlier edit must survive", 34, state.settings.terminalTextSizePx)
        assertEquals("en", state.settings.voiceLanguage)
        state = reduce(
            state,
            MockAppEvent.SettingsChange(
                state.settings.copy(backgroundGraceMillis = 30_000L, reconnectWhenReturn = true),
            ),
        )
        assertEquals(30_000L, state.settings.backgroundGraceMillis)
        assertTrue(state.settings.reconnectWhenReturn)
        assertEquals("en", state.settings.voiceLanguage)
    }

    @Test
    fun `advanced reset restores the fresh-install defaults`() {
        val tuned = MockAppState.populated().copy(
            settings = AppSettings(terminalTextSizePx = 48, voiceSilenceThresholdSeconds = 9f),
        )
        val state = reduce(tuned, MockAppEvent.AdvancedResetDefaults)
        assertEquals(AppSettings(), state.settings)
    }

    // ── D18: SSH key generation ───────────────────────────────────────────────

    @Test
    fun `ssh key generation appends a row and sets the one-shot message`() {
        var state = reduce(MockAppState.populated(), MockAppEvent.SshKeyGenerationStart)
        assertTrue(state.sshKeysGenerating)
        state = reduce(state, MockAppEvent.SshKeyGenerationComplete("laptop"))
        assertFalse(state.sshKeysGenerating)
        assertEquals(MockData.sshKeys.size + 1, state.sshKeys.size)
        val added = state.sshKeys.last()
        assertEquals("laptop", added.name)
        assertTrue(added.fingerprint.isNotBlank())
        assertEquals("Generated ED25519 key laptop", state.sshKeyMessage)
    }

    @Test
    fun `starting key generation clears a stale one-shot message`() {
        val withMessage = MockAppState.populated().copy(sshKeyMessage = "Key added")
        val state = reduce(withMessage, MockAppEvent.SshKeyGenerationStart)
        assertNull(state.sshKeyMessage)
    }

    // ── D18: files refresh ladder ─────────────────────────────────────────────

    @Test
    fun `files refresh failure shows the error and a later refresh recovers`() {
        var state = reduce(MockAppState.populated(), MockAppEvent.Navigate(MockDestination.Files))
        state = reduce(state, MockAppEvent.FilesRefreshStart)
        assertTrue(state.filesLoading)
        state = reduce(state, MockAppEvent.FilesRefreshFailed("host unreachable"))
        assertFalse(state.filesLoading)
        assertEquals("host unreachable", state.filesFailure)
        state = reduce(state, MockAppEvent.FilesRefreshComplete)
        assertNull(state.filesFailure)
        assertTrue(state.filesLoaded)
    }

    // ── D18: viewer editing ladder (long content) ─────────────────────────────

    @Test
    fun `viewer edit seeds the draft from the loaded content and typing replaces it`() {
        var state = reduce(MockAppState.populated(), MockAppEvent.Navigate(MockDestination.FileViewer))
        state = reduce(state, MockAppEvent.ViewerEditStart)
        assertTrue(state.viewer.editing)
        assertEquals(MockData.LONG_FILE_CONTENT, state.viewer.draft)
        state = reduce(state, MockAppEvent.ViewerDraftChange("short edit"))
        assertEquals("short edit", state.viewer.draft)
        assertEquals("the loaded content must be untouched while typing", MockData.LONG_FILE_CONTENT, state.viewer.content)
    }

    @Test
    fun `viewer save commits the draft and sets the one-shot banner`() {
        var state = reduce(MockAppState.populated(), MockAppEvent.Navigate(MockDestination.FileViewer))
        state = reduce(state, MockAppEvent.ViewerEditStart)
        state = reduce(state, MockAppEvent.ViewerDraftChange("saved body"))
        state = reduce(state, MockAppEvent.ViewerSaveComplete("Saved"))
        assertFalse(state.viewer.editing)
        assertEquals("saved body", state.viewer.content)
        assertEquals("Saved", state.viewer.savedMessage)
        assertNull(state.viewer.failure)
    }

    @Test
    fun `viewer save failure keeps the draft and shows the error`() {
        var state = reduce(MockAppState.populated(), MockAppEvent.Navigate(MockDestination.FileViewer))
        state = reduce(state, MockAppEvent.ViewerEditStart)
        state = reduce(state, MockAppEvent.ViewerDraftChange("unsaved body"))
        state = reduce(state, MockAppEvent.ViewerSaveFailed("write failed"))
        assertTrue(state.viewer.editing)
        assertEquals("unsaved body", state.viewer.draft)
        assertEquals("the committed content must be untouched", MockData.LONG_FILE_CONTENT, state.viewer.content)
        assertEquals("write failed", state.viewer.failure)
    }

    // ── D18: workspace refresh + root action ──────────────────────────────────

    @Test
    fun `workspace refresh failure keeps loaded content under the error banner`() {
        var state = reduce(MockAppState.populated(), MockAppEvent.Navigate(MockDestination.Workspace))
        state = reduce(state, MockAppEvent.WorkspaceRefreshStart)
        assertTrue(state.workspaceScreen.refreshing)
        state = reduce(state, MockAppEvent.WorkspaceRefreshFailed("listing failed"))
        assertFalse(state.workspaceScreen.refreshing)
        assertEquals("listing failed", state.workspaceScreen.failure)
        assertTrue("content stays loaded under the banner", state.workspaceScreen.loaded)
        state = reduce(state, MockAppEvent.WorkspaceRefreshComplete)
        assertNull(state.workspaceScreen.failure)
    }

    @Test
    fun `workspace action typing and dismissal never create anything`() {
        var state = reduce(MockAppState.populated(), MockAppEvent.Navigate(MockDestination.WorkspaceRootAction))
        state = reduce(state, MockAppEvent.WorkspaceActionNameChange("new folder"))
        assertEquals("new folder", state.workspaceAction.name)
        state = reduce(state, MockAppEvent.WorkspaceActionDismiss)
        assertFalse(state.workspaceAction.visible)
        assertEquals("", state.workspaceAction.name)
    }

    // ── D18: account sync ladder (shared AccountSyncUiState) ──────────────────

    @Test
    fun `account sync sign-in ladder walks awaiting exchanging to signed in`() {
        var state = reduce(MockAppState.populated(), MockAppEvent.AccountSyncSignInStart)
        assertEquals(SyncSignInPhase.AwaitingRedirect, state.accountSync.signInPhase)
        state = reduce(state, MockAppEvent.AccountSyncSignInExchange)
        assertEquals(SyncSignInPhase.Exchanging, state.accountSync.signInPhase)
        state = reduce(state, MockAppEvent.AccountSyncSignInCompleted(MockData.ACCOUNT_EMAIL))
        assertTrue(state.accountSync.signedIn)
        assertEquals(MockData.ACCOUNT_EMAIL, state.accountSync.email)
        assertEquals(SyncSignInPhase.SignedIn(MockData.ACCOUNT_EMAIL), state.accountSync.signInPhase)
    }

    @Test
    fun `account sync sign-in failure surfaces the message at any rung`() {
        val awaiting = reduce(MockAppState.populated(), MockAppEvent.AccountSyncSignInStart)
        val state = reduce(awaiting, MockAppEvent.AccountSyncSignInFailed("redirect cancelled"))
        assertEquals(SyncSignInPhase.Failed("redirect cancelled"), state.accountSync.signInPhase)
        assertFalse(state.accountSync.signedIn)
    }

    @Test
    fun `account sync round failure then sign out resets the page`() {
        var state = reduce(MockAppState.populated(), MockAppEvent.AccountSyncSyncNow)
        assertEquals(SyncOutcomeDisplay.Running, state.accountSync.outcome)
        state = reduce(state, MockAppEvent.AccountSyncSyncFailed("host offline"))
        assertEquals(SyncOutcomeDisplay.Failed("host offline"), state.accountSync.outcome)
        val signedIn = state.copy(
            accountSync = state.accountSync.copy(signedIn = true, email = MockData.ACCOUNT_EMAIL),
        )
        val out = reduce(signedIn, MockAppEvent.AccountSyncSignOut)
        assertFalse(out.accountSync.signedIn)
        assertNull(out.accountSync.email)
        assertEquals(SyncSignInPhase.Idle, out.accountSync.signInPhase)
        assertEquals(SyncOutcomeDisplay.None, out.accountSync.outcome)
    }

    // ── D18: update check ladder (shared SettingsUpdateCheckState) ────────────

    @Test
    fun `update check walks checking into each terminal state`() {
        var state = reduce(MockAppState.populated(), MockAppEvent.UpdateCheckStart)
        assertEquals(SettingsUpdateCheckState.Checking, state.updateCheck)
        state = reduce(state, MockAppEvent.UpdateCheckUpToDate)
        assertEquals(SettingsUpdateCheckState.UpToDate, state.updateCheck)

        var state2 = reduce(MockAppState.populated(), MockAppEvent.UpdateCheckStart)
        state2 = reduce(state2, MockAppEvent.UpdateCheckUpdateAvailable)
        assertTrue(
            "expected UpdateAvailable, was ${state2.updateCheck}",
            state2.updateCheck is SettingsUpdateCheckState.UpdateAvailable,
        )
        val available = state2.updateCheck as SettingsUpdateCheckState.UpdateAvailable
        assertEquals(MockData.updateRelease, available.info)

        var state3 = reduce(MockAppState.populated(), MockAppEvent.UpdateCheckStart)
        state3 = reduce(state3, MockAppEvent.UpdateCheckFailed("offline"))
        assertEquals(SettingsUpdateCheckState.Failed("offline"), state3.updateCheck)
    }

    // ── D18: diagnostics error state ──────────────────────────────────────────

    @Test
    fun `diagnostics load failure is distinct from ready and keeps the destination`() {
        val ready = reduce(MockAppState.populated(), MockAppEvent.Navigate(MockDestination.Diagnostics))
        val state = reduce(ready, MockAppEvent.DiagnosticsLoadFailed("store unreadable"))
        assertEquals(MockDestination.Diagnostics, state.destination)
        assertEquals(CrashReportsLoadState.Failed("store unreadable"), state.diagnosticsLoad)
    }

    // ── D18: usage failure ────────────────────────────────────────────────────

    @Test
    fun `usage refresh failure cancels the spinner and records the host reason`() {
        val refreshing = MockAppState.populated().copy(usageRefreshing = true)
        val state = reduce(refreshing, MockAppEvent.UsageRefreshFailed("quota read failed"))
        assertFalse(state.usageRefreshing)
        assertEquals("quota read failed", state.usageFailure)
    }

    // ── D18: add tunnel form ──────────────────────────────────────────────────

    @Test
    fun `add tunnel submit only lands a valid form and clears it`() {
        val invalid = reduce(
            MockAppState.populated(),
            MockAppEvent.AddTunnelFormChange(MockAddTunnelFormState(name = "web", remotePort = "8443")),
        )
        assertFalse(invalid.addTunnelValid)
        assertSame("invalid form must not create a tunnel", invalid, reduce(invalid, MockAppEvent.AddTunnelSubmit))

        val valid = reduce(
            invalid,
            MockAppEvent.AddTunnelFormChange(
                invalid.addTunnelForm.copy(localPort = "18443"),
            ),
        )
        assertTrue(valid.addTunnelValid)
        val submitted = reduce(valid, MockAppEvent.AddTunnelSubmit)
        assertEquals(1, submitted.manualTunnels.size)
        val tunnel = submitted.manualTunnels.single()
        assertEquals(8443, tunnel.remotePort)
        assertEquals(18443, tunnel.localPort)
        assertEquals("web", tunnel.process)
        assertEquals("submit clears the form", MockAddTunnelFormState(), submitted.addTunnelForm)
    }

    @Test
    fun `add tunnel flags a collision with an already-forwarded local port`() {
        val colliding = reduce(
            MockAppState.populated(),
            MockAppEvent.AddTunnelFormChange(
                MockAddTunnelFormState(name = "clash", remotePort = "9999", localPort = "35173"),
            ),
        )
        assertFalse("a colliding port must not be submittable", colliding.addTunnelValid)
        assertEquals(
            "Local port 35173 is already forwarding vite.",
            colliding.addTunnelCollision(),
        )
        assertSame(
            "a colliding submit must be a no-op",
            colliding,
            reduce(colliding, MockAppEvent.AddTunnelSubmit),
        )
    }

    @Test
    fun `a submitted manual tunnel is reachable in the tunnel detail`() {
        var state = reduce(
            MockAppState.populated(),
            MockAppEvent.AddTunnelFormChange(
                MockAddTunnelFormState(name = "grafana", remotePort = "3000", localPort = "13000"),
            ),
        )
        state = reduce(state, MockAppEvent.AddTunnelSubmit)
        state = reduce(state, MockAppEvent.OpenTunnel(3000))
        assertEquals(MockDestination.TunnelDetail, state.destination)
        assertTrue(state.tunnelDetailManual)
        assertEquals("grafana", state.tunnelDetailTunnel()?.process)
    }

    // ── D18: workspace roots + add-root form ──────────────────────────────────

    @Test
    fun `add workspace root submit only lands an absolute path and clears the form`() {
        val relative = reduce(
            MockAppState.populated(),
            MockAppEvent.AddWorkspaceRootFormChange(MockAddWorkspaceRootForm(label = "Repos", path = "srv/repos")),
        )
        assertFalse(relative.addWorkspaceRootForm.valid)
        assertNull(relative.addWorkspaceRootForm.canonicalPath)
        assertSame(
            "a relative path must not create a root",
            relative,
            reduce(relative, MockAppEvent.AddWorkspaceRootSubmit),
        )

        val absolute = reduce(
            relative,
            MockAppEvent.AddWorkspaceRootFormChange(relative.addWorkspaceRootForm.copy(path = "/srv/repos/")),
        )
        assertTrue(absolute.addWorkspaceRootForm.valid)
        assertEquals("/srv/repos", absolute.addWorkspaceRootForm.canonicalPath)
        val submitted = reduce(absolute, MockAppEvent.AddWorkspaceRootSubmit)
        val added = submitted.workspaceRoots.roots.last()
        assertEquals("Repos", added.label)
        assertEquals("/srv/repos", added.path)
        assertEquals(0, added.workspaceCount)
        assertEquals("submit clears the form", MockAddWorkspaceRootForm(), submitted.addWorkspaceRootForm)
    }

    @Test
    fun `workspace roots load failure surfaces the error without dropping loaded roots`() {
        val loaded = reduce(MockAppState.populated(), MockAppEvent.Navigate(MockDestination.WorkspaceRoots))
        val state = reduce(loaded, MockAppEvent.WorkspaceRootsLoadFailed("host unreachable"))
        assertTrue(state.workspaceRoots.roots.isNotEmpty())
        assertEquals("host unreachable", state.workspaceRoots.failure)
    }

    // ── D18: reorder ──────────────────────────────────────────────────────────

    @Test
    fun `reorder move moves the root and the workspaces projection follows the order`() {
        val before = MockAppState.populated()
        val state = reduce(before, MockAppEvent.ReorderMove(0, 1))
        assertEquals(listOf("Work", "Git"), state.reorderRoots.map { it.label })
        assertEquals(
            "the host workspaces projection must follow the new order",
            listOf("Work", "Git"),
            state.toWorkspaceUiState().roots.map { it.label },
        )
    }
}
