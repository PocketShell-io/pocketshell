package com.pocketshell.next.mockapp

import com.pocketshell.next.hosts.HostFormState
import com.pocketshell.next.settings.AppSettings

/**
 * What the (mock) user just did, in UI terms only.
 *
 * These are the boundary callbacks the issue describes: navigation, typing,
 * toggles and refreshes that mutate ONLY [MockAppState]. Nothing here dials
 * SSH, touches Room, starts a recording or opens a browser — acceptance 3's
 * "without side effects", made structural by the reducer's purity.
 */
sealed interface MockAppEvent {

    /** Navigate forward to a destination (a row tap, a tools entry). */
    data class Navigate(val to: MockDestination) : MockAppEvent

    /** System back. */
    data object Back : MockAppEvent

    /** A host row tap: open that host's workspaces. */
    data class OpenHost(val hostId: Long) : MockAppEvent

    /** Tools → edit host: open the form loaded with that host's values. */
    data class EditHost(val hostId: Long) : MockAppEvent

    /**
     * The host form edited its draft. The production screen edits through a
     * `(HostFormState) -> HostFormState` transform, so the event carries the
     * resulting snapshot: still pure data, still replayable, and no field
     * change (including the key picker) can fall on the floor.
     */
    data class HostFormChange(val next: HostFormState) : MockAppEvent

    /** Composer typing: the draft changes; nothing is sent anywhere. */
    data class ComposerDraftChange(val text: String) : MockAppEvent

    /**
     * Composer send: the draft moves to history and clears. The mock always
     * "delivers" (returns true from the production `onSend` contract); the
     * undelivered notice states are fixture territory, not reducer state.
     */
    data object ComposerSend : MockAppEvent

    /** Composer history: reuse a previously sent message as the draft. */
    data class ComposerHistoryRestore(val id: Long) : MockAppEvent

    /** Workspace screen search typing. */
    data class WorkspaceSearchChange(val query: String) : MockAppEvent

    /** Services & tunnels: the discovery toggle flipped. */
    data class ServicesDiscoveryChange(val enabled: Boolean) : MockAppEvent

    /** Usage pull-to-refresh started; completes when [UsageRefreshComplete] lands. */
    data object UsageRefreshStart : MockAppEvent

    /** The mock fetch finished — instant, deterministic. */
    data object UsageRefreshComplete : MockAppEvent

    /** Session retry from [MockSessionPhase.FAILED]. */
    data object SessionRetry : MockAppEvent

    /** Session reached the terminal (mock attach succeeded). */
    data object SessionAttached : MockAppEvent

    /** The reconnect ladder gave up. */
    data object SessionFailed : MockAppEvent

    /** SSH keys screen dismissed its one-shot message. */
    data object SshKeysMessageDismiss : MockAppEvent

    // ── D18: interactive-state seam for the remaining destinations ──────────

    /** Diagnostics row tap: open that stored report. */
    data class OpenReport(val reportId: String) : MockAppEvent

    /** Services row tap: open that remote port's tunnel detail. */
    data class OpenTunnel(val remotePort: Int) : MockAppEvent

    /** Any settings sub-page committed a field change (shared AppSettings snapshot). */
    data class SettingsChange(val next: AppSettings) : MockAppEvent

    /** Advanced page: reset those values to the fresh-install defaults. */
    data object AdvancedResetDefaults : MockAppEvent

    /** SSH key generation started; the screen shows its busy state. */
    data object SshKeyGenerationStart : MockAppEvent

    /** The mock key generation finished; a row appears with a one-shot message. */
    data class SshKeyGenerationComplete(val keyName: String) : MockAppEvent

    /** Files pull-to-refresh started; completes via [FilesRefreshComplete]. */
    data object FilesRefreshStart : MockAppEvent

    /** The mock directory read finished — instant, deterministic. */
    data object FilesRefreshComplete : MockAppEvent

    /** The mock directory read failed; the explorer shows its error state. */
    data class FilesRefreshFailed(val message: String) : MockAppEvent

    /** Viewer entered edit mode; the draft starts as the loaded content. */
    data object ViewerEditStart : MockAppEvent

    /** Viewer editor typing; nothing is written anywhere. */
    data class ViewerDraftChange(val text: String) : MockAppEvent

    /** The mock save finished; content becomes the draft, banner text set. */
    data class ViewerSaveComplete(val message: String) : MockAppEvent

    /** The mock save failed; the viewer shows its error state, draft preserved. */
    data class ViewerSaveFailed(val message: String) : MockAppEvent

    /** Workspace screen pull-to-refresh started. */
    data object WorkspaceRefreshStart : MockAppEvent

    /** The mock workspace re-read finished. */
    data object WorkspaceRefreshComplete : MockAppEvent

    /** The mock workspace re-read failed; content stays under an error banner. */
    data class WorkspaceRefreshFailed(val message: String) : MockAppEvent

    /** WorkspaceRootAction's create-folder sheet typing. */
    data class WorkspaceActionNameChange(val text: String) : MockAppEvent

    /** The root-action sheet dismissed; no folder is created. */
    data object WorkspaceActionDismiss : MockAppEvent

    /** Account sync: open the sign-in Custom Tab (mock). */
    data object AccountSyncSignInStart : MockAppEvent

    /** The redirect came back; the code is being exchanged (busy state). */
    data object AccountSyncSignInExchange : MockAppEvent

    /** The exchange succeeded; the account is signed in. */
    data class AccountSyncSignInCompleted(val email: String) : MockAppEvent

    /** Sign-in failed at any rung; the screen shows the failure. */
    data class AccountSyncSignInFailed(val message: String) : MockAppEvent

    /** A sync round started (busy outcome). */
    data object AccountSyncSyncNow : MockAppEvent

    /** The sync round failed; the screen shows the failure. */
    data class AccountSyncSyncFailed(val message: String) : MockAppEvent

    /** Sign out; the account rows go back to signed-out. */
    data object AccountSyncSignOut : MockAppEvent

    /** About/Update: a release check started (busy state). */
    data object UpdateCheckStart : MockAppEvent

    /** The mock check finished: the installed build is current. */
    data object UpdateCheckUpToDate : MockAppEvent

    /** The mock check finished: an update is available. */
    data object UpdateCheckUpdateAvailable : MockAppEvent

    /** The mock check failed; the screens show the reason. */
    data class UpdateCheckFailed(val reason: String) : MockAppEvent

    /** Diagnostics index load failed (its error state, distinct from empty). */
    data class DiagnosticsLoadFailed(val message: String) : MockAppEvent

    /** Usage/HostUsage refresh failed; the host joins [UsageScreenState.failedHosts]. */
    data class UsageRefreshFailed(val reason: String) : MockAppEvent

    /** AddTunnel form typing; the resulting snapshot is carried whole. */
    data class AddTunnelFormChange(val next: MockAddTunnelFormState) : MockAppEvent

    /** AddTunnel submit: valid forms append a mock manual tunnel; invalid is a no-op. */
    data object AddTunnelSubmit : MockAppEvent

    /** AddWorkspaceRoot form typing; the resulting snapshot is carried whole. */
    data class AddWorkspaceRootFormChange(val next: MockAddWorkspaceRootForm) : MockAppEvent

    /** AddWorkspaceRoot submit: valid forms append a root row; invalid is a no-op. */
    data object AddWorkspaceRootSubmit : MockAppEvent

    /** Workspace roots manager load failed (its error state). */
    data class WorkspaceRootsLoadFailed(val message: String) : MockAppEvent

    /** Reorder screen: move the root at [fromIndex] to [toIndex]; out of range is a no-op. */
    data class ReorderMove(val fromIndex: Int, val toIndex: Int) : MockAppEvent
}
