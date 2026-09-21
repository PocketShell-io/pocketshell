package com.pocketshell.next.mockapp

import com.pocketshell.next.crash.CrashReportsLoadState
import com.pocketshell.next.hosts.SshKeyRow
import com.pocketshell.next.ports.TunnelDisplay
import com.pocketshell.next.ports.TunnelStatusDisplay
import com.pocketshell.next.settings.AppSettings
import com.pocketshell.next.settings.SettingsUpdateCheckState
import com.pocketshell.next.sync.SyncOutcomeDisplay
import com.pocketshell.next.sync.SyncSignInPhase

/**
 * Pure state machine of the mock app: `(MockAppState, MockAppEvent) -> MockAppState`.
 *
 * The single place fake state changes happen. Purity is the point — the same
 * function drives the JVM unit tests, the fixture scenarios (a scenario is
 * just `reduce(populated(), event)` folded onto itself) and, in phase 2+, any
 * interactive driver of the extracted presentation module. No clock, no I/O,
 * no randomness: the same event sequence always yields the same state.
 *
 * Since the D18 interactive-state slice every production destination has at
 * least the loading / error / typing / long-content transitions its real
 * screen has — still without a single side effect.
 */
fun reduce(state: MockAppState, event: MockAppEvent): MockAppState = when (event) {
    is MockAppEvent.Navigate -> when (event.to) {
        MockDestination.HostForm -> state.copy(
            destination = MockDestination.HostForm,
            hostForm = state.hostForm.copy(editing = false, saved = false),
        )
        MockDestination.SshKeys -> state.copy(
            destination = MockDestination.SshKeys,
            sshKeysLoaded = true,
            sshKeyMessage = null,
        )
        MockDestination.Usage, MockDestination.HostUsage -> state.copy(
            destination = event.to,
            usageRefreshing = false,
            usageFailure = null,
        )
        MockDestination.Workspace -> state.copy(
            destination = MockDestination.Workspace,
            workspaceScreen = state.workspaceScreen.copy(
                loaded = true,
                loading = false,
                refreshing = false,
                failure = null,
                workspacePath = state.workspaceScreen.workspacePath ?: MockData.START_WORKSPACE_PATH,
            ),
        )
        MockDestination.WorkspaceRootAction -> state.copy(
            destination = MockDestination.WorkspaceRootAction,
            workspaceAction = MockWorkspaceActionState(rootPath = MockData.START_WORKSPACE_PATH),
        )
        MockDestination.Files -> state.copy(
            destination = MockDestination.Files,
            filesLoaded = true,
            filesLoading = false,
            filesFailure = null,
        )
        MockDestination.FileViewer -> state.copy(
            destination = MockDestination.FileViewer,
            viewer = MockViewerUiState(
                loaded = true,
                content = MockData.LONG_FILE_CONTENT,
                path = MockData.VIEWER_PATH,
            ),
        )
        MockDestination.Diagnostics -> state.copy(
            destination = MockDestination.Diagnostics,
            diagnosticsLoad = CrashReportsLoadState.Ready,
            crashReports = MockData.crashReports,
        )
        MockDestination.WorkspaceRoots -> state.copy(
            destination = MockDestination.WorkspaceRoots,
            workspaceRoots = state.workspaceRoots.copy(
                loaded = true,
                failure = null,
                roots = state.mockWorkspaceRootRows(),
            ),
        )
        else -> state.copy(destination = event.to)
    }

    is MockAppEvent.Back -> state.destination.parent
        ?.let { parent -> state.copy(destination = parent) }
        ?: state

    is MockAppEvent.OpenHost -> state.copy(destination = MockDestination.Workspaces)

    is MockAppEvent.EditHost -> {
        val host = state.hosts.firstOrNull { it.id == event.hostId }
        state.copy(
            destination = MockDestination.HostForm,
            hostForm = if (host == null) {
                state.hostForm.copy(editing = false)
            } else {
                state.hostForm.copy(
                    name = host.name,
                    hostname = host.subtitle.substringAfter('@'),
                    username = host.subtitle.substringBefore('@'),
                    editing = true,
                )
            },
        )
    }

    is MockAppEvent.HostFormChange -> state.copy(hostForm = event.next)

    is MockAppEvent.ComposerDraftChange -> state.copy(composerDraft = event.text)

    is MockAppEvent.ComposerSend -> if (state.composerDraft.isNotBlank()) {
        state.copy(
            composerDraft = "",
            composerHistory = state.composerHistory + state.composerDraft,
        )
    } else {
        state
    }

    is MockAppEvent.ComposerHistoryRestore ->
        state.composerDraftHistoryEntry(event.id)?.let { entry ->
            state.copy(composerDraft = entry)
        } ?: state

    is MockAppEvent.WorkspaceSearchChange -> state.copy(workspaceSearchQuery = event.query)

    is MockAppEvent.ServicesDiscoveryChange -> state.copy(
        servicesEnabled = event.enabled,
        servicesLoading = false,
    )

    is MockAppEvent.UsageRefreshStart -> state.copy(usageRefreshing = true)

    is MockAppEvent.UsageRefreshComplete -> state.copy(usageRefreshing = false)

    is MockAppEvent.UsageRefreshFailed -> state.copy(
        usageRefreshing = false,
        usageFailure = event.reason,
    )

    is MockAppEvent.SessionRetry -> state.copy(
        sessionPhase = MockSessionPhase.CONNECTING,
        sessionMessage = "",
    )

    is MockAppEvent.SessionAttached -> state.copy(
        sessionPhase = MockSessionPhase.LIVE,
        sessionMessage = "",
    )

    is MockAppEvent.SessionFailed -> state.copy(
        sessionPhase = MockSessionPhase.FAILED,
        sessionMessage = "Could not reach the session. Tap Retry to try again.",
    )

    is MockAppEvent.SshKeysMessageDismiss -> state.copy(sshKeyMessage = null)

    // ── D18: interactive-state seam for the remaining destinations ──────────

    is MockAppEvent.OpenReport -> state.copy(
        destination = MockDestination.DiagnosticReport,
        diagnosticReportId = event.reportId,
    )

    is MockAppEvent.OpenTunnel -> state.copy(
        destination = MockDestination.TunnelDetail,
        tunnelDetailPort = event.remotePort,
    )

    is MockAppEvent.SettingsChange -> state.copy(settings = event.next)

    is MockAppEvent.AdvancedResetDefaults -> state.copy(settings = AppSettings())

    is MockAppEvent.SshKeyGenerationStart -> state.copy(
        sshKeysGenerating = true,
        sshKeyMessage = null,
    )

    is MockAppEvent.SshKeyGenerationComplete -> state.copy(
        sshKeysGenerating = false,
        sshKeys = state.sshKeys + SshKeyRow(
            id = (state.sshKeys.maxOfOrNull { it.id } ?: 0L) + 1L,
            name = event.keyName,
            fingerprint = "SHA256:mock-${state.sshKeys.size + 1}",
        ),
        sshKeyMessage = "Generated ED25519 key ${event.keyName}",
    )

    is MockAppEvent.FilesRefreshStart -> state.copy(filesLoading = true)

    is MockAppEvent.FilesRefreshComplete -> state.copy(
        filesLoading = false,
        filesLoaded = true,
        filesFailure = null,
    )

    is MockAppEvent.FilesRefreshFailed -> state.copy(
        filesLoading = false,
        filesFailure = event.message,
    )

    is MockAppEvent.ViewerEditStart -> state.copy(
        viewer = state.viewer.copy(
            editing = true,
            draft = state.viewer.draft.ifEmpty { state.viewer.content },
            savedMessage = null,
        ),
    )

    is MockAppEvent.ViewerDraftChange -> state.copy(
        viewer = state.viewer.copy(draft = event.text),
    )

    is MockAppEvent.ViewerSaveComplete -> state.copy(
        viewer = state.viewer.copy(
            content = state.viewer.draft,
            editing = false,
            savedMessage = event.message,
            failure = null,
        ),
    )

    is MockAppEvent.ViewerSaveFailed -> state.copy(
        viewer = state.viewer.copy(failure = event.message),
    )

    is MockAppEvent.WorkspaceRefreshStart -> state.copy(
        workspaceScreen = state.workspaceScreen.copy(refreshing = true),
    )

    is MockAppEvent.WorkspaceRefreshComplete -> state.copy(
        workspaceScreen = state.workspaceScreen.copy(
            refreshing = false,
            loaded = true,
            failure = null,
        ),
    )

    is MockAppEvent.WorkspaceRefreshFailed -> state.copy(
        workspaceScreen = state.workspaceScreen.copy(
            refreshing = false,
            failure = event.message,
        ),
    )

    is MockAppEvent.WorkspaceActionNameChange -> state.copy(
        workspaceAction = state.workspaceAction.copy(name = event.text),
    )

    is MockAppEvent.WorkspaceActionDismiss -> state.copy(
        workspaceAction = MockWorkspaceActionState(),
    )

    is MockAppEvent.AccountSyncSignInStart -> state.copy(
        accountSync = state.accountSync.copy(signInPhase = SyncSignInPhase.AwaitingRedirect),
    )

    is MockAppEvent.AccountSyncSignInExchange -> state.copy(
        accountSync = state.accountSync.copy(signInPhase = SyncSignInPhase.Exchanging),
    )

    is MockAppEvent.AccountSyncSignInCompleted -> state.copy(
        accountSync = state.accountSync.copy(
            signedIn = true,
            email = event.email,
            signInPhase = SyncSignInPhase.SignedIn(event.email),
        ),
    )

    is MockAppEvent.AccountSyncSignInFailed -> state.copy(
        accountSync = state.accountSync.copy(signInPhase = SyncSignInPhase.Failed(event.message)),
    )

    is MockAppEvent.AccountSyncSyncNow -> state.copy(
        accountSync = state.accountSync.copy(outcome = SyncOutcomeDisplay.Running),
    )

    is MockAppEvent.AccountSyncSyncFailed -> state.copy(
        accountSync = state.accountSync.copy(outcome = SyncOutcomeDisplay.Failed(event.message)),
    )

    is MockAppEvent.AccountSyncSignOut -> state.copy(
        accountSync = state.accountSync.copy(
            signedIn = false,
            email = null,
            signInPhase = SyncSignInPhase.Idle,
            outcome = SyncOutcomeDisplay.None,
        ),
    )

    is MockAppEvent.UpdateCheckStart -> state.copy(
        updateCheck = SettingsUpdateCheckState.Checking,
    )

    is MockAppEvent.UpdateCheckUpToDate -> state.copy(
        updateCheck = SettingsUpdateCheckState.UpToDate,
    )

    is MockAppEvent.UpdateCheckUpdateAvailable -> state.copy(
        updateCheck = SettingsUpdateCheckState.UpdateAvailable(MockData.updateRelease),
    )

    is MockAppEvent.UpdateCheckFailed -> state.copy(
        updateCheck = SettingsUpdateCheckState.Failed(event.reason),
    )

    is MockAppEvent.DiagnosticsLoadFailed -> state.copy(
        diagnosticsLoad = CrashReportsLoadState.Failed(event.message),
    )

    is MockAppEvent.AddTunnelFormChange -> state.copy(addTunnelForm = event.next)

    is MockAppEvent.AddTunnelSubmit -> {
        val form = state.addTunnelForm
        if (!state.addTunnelValid) {
            state
        } else {
            state.copy(
                manualTunnels = state.manualTunnels + TunnelDisplay(
                    remotePort = form.remotePortValue ?: 0,
                    localPort = form.localPortValue ?: 0,
                    process = form.name.trim(),
                    status = TunnelStatusDisplay.AVAILABLE,
                ),
                addTunnelForm = MockAddTunnelFormState(),
            )
        }
    }

    is MockAppEvent.AddWorkspaceRootFormChange -> state.copy(addWorkspaceRootForm = event.next)

    is MockAppEvent.AddWorkspaceRootSubmit -> {
        val form = state.addWorkspaceRootForm
        if (!form.valid) {
            state
        } else {
            state.copy(
                workspaceRoots = state.workspaceRoots.copy(
                    roots = state.workspaceRoots.roots + MockWorkspaceRootRow(
                        id = (state.workspaceRoots.roots.maxOfOrNull { it.id } ?: 0L) + 1L,
                        label = form.label.trim(),
                        path = form.canonicalPath ?: "",
                    ),
                    failure = null,
                ),
                addWorkspaceRootForm = MockAddWorkspaceRootForm(),
            )
        }
    }

    is MockAppEvent.WorkspaceRootsLoadFailed -> state.copy(
        workspaceRoots = state.workspaceRoots.copy(failure = event.message),
    )

    is MockAppEvent.ReorderMove -> {
        val roots = state.reorderRoots
        if (event.fromIndex !in roots.indices ||
            event.toIndex !in roots.indices ||
            event.fromIndex == event.toIndex
        ) {
            state
        } else {
            val moved = roots.toMutableList().apply {
                add(event.toIndex, removeAt(event.fromIndex))
            }
            state.copy(reorderRoots = moved)
        }
    }
}

/** The workspace-roots manager's rows, projected from the (reorderable) root order. */
fun MockAppState.mockWorkspaceRootRows(): List<MockWorkspaceRootRow> =
    reorderRoots.ifEmpty { MockData.registeredRoots }.mapIndexed { index, root ->
        MockWorkspaceRootRow(
            id = index + 1L,
            label = root.label,
            path = root.path,
            workspaceCount = MockData.memberships.count { membership ->
                membership.path == root.path || membership.path.startsWith("${root.path}/")
            },
        )
    }

/** History entry bodies by list position — ids are 1-based list indexes. */
fun MockAppState.composerDraftHistoryEntry(id: Long): String? =
    composerHistory.getOrNull(id.toInt() - 1)
