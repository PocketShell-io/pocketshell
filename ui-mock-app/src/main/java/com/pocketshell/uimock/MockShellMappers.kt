package com.pocketshell.uimock

import com.pocketshell.next.hosts.SshKeysUiState
import com.pocketshell.next.mockapp.MockAppState
import com.pocketshell.next.mockapp.MockDestination

/**
 * The runnable shell's state→screen seam (#2636 slice D16).
 *
 * Pure mapping from the `:ui-mock` aggregate state onto the REAL shared screen
 * states of `:shared:ui-screens`, for the destinations whose production screen
 * already crossed the presentation boundary. Projections `MockAppState`
 * already carries (`toHostListUiState`, `toServicesUiState`, `toUsageUiState`,
 * …) are used verbatim; the functions here cover only what those lack. No
 * function touches the clock, the network or any Android class, so the whole
 * seam unit-tests on the plain JVM alongside the reducer tests.
 *
 * Deliberately NOT here: any screen copy. Where no shared screen exists
 * (Workspaces, WorkspaceStart, Session) the shell renders a labeled
 * placeholder naming the remaining work — never a lookalike.
 */

/**
 * The real shared SSH-keys screen state, projected from the mock's local
 * `loaded`/`message` switches. The key list stays empty until the
 * "replace SSH-key-local state after shared extraction" ledger row lands:
 * the mock state models no keys yet, and the screen's generate/import/delete
 * actions are no-ops in the shell for the same reason.
 */
fun MockAppState.toSshKeysScreenState(): SshKeysUiState = SshKeysUiState(
    keys = emptyList(),
    loaded = sshKeysLoaded,
    message = sshKeyMessage,
)

/**
 * The destinations the shell renders with a real `:shared:ui-screens` screen.
 * Everything else in [MockDestination] falls to the labeled placeholder.
 */
val wiredDestinations: Set<MockDestination> = setOf(
    MockDestination.Hosts,
    MockDestination.HostForm,
    MockDestination.SshKeys,
    MockDestination.Services,
    MockDestination.Settings,
    MockDestination.Usage,
)

/** Header title for a not-yet-wired destination (its production name). */
fun unwiredDestinationTitle(destination: MockDestination): String = when (destination) {
    MockDestination.Hosts -> "Hosts"
    MockDestination.HostForm -> "HostForm"
    MockDestination.SshKeys -> "SshKeys"
    MockDestination.Services -> "Ports"
    MockDestination.Settings -> "Settings"
    MockDestination.Usage -> "Usage"
    MockDestination.Workspaces -> "Workspaces"
    MockDestination.WorkspaceStart -> "WorkspaceStart"
    MockDestination.Session -> "Session"
    MockDestination.About -> "About"
    MockDestination.AccountSync -> "AccountSync"
    MockDestination.AddTunnel -> "AddTunnel"
    MockDestination.AddWorkspaceRoot -> "AddWorkspaceRoot"
    MockDestination.AdvancedSettings -> "AdvancedSettings"
    MockDestination.ConnectionSettings -> "ConnectionSettings"
    MockDestination.DiagnosticReport -> "DiagnosticReport"
    MockDestination.Diagnostics -> "Diagnostics"
    MockDestination.Files -> "Files"
    MockDestination.FileViewer -> "FileViewer"
    MockDestination.HostUsage -> "HostUsage"
    MockDestination.ReorderWorkspaces -> "ReorderWorkspaces"
    MockDestination.TerminalSettings -> "TerminalSettings"
    MockDestination.TunnelDetail -> "TunnelDetail"
    MockDestination.Update -> "Update"
    MockDestination.VoiceSettings -> "VoiceSettings"
    MockDestination.Workspace -> "Workspace"
    MockDestination.WorkspaceRootAction -> "WorkspaceRootAction"
    MockDestination.WorkspaceRoots -> "WorkspaceRoots"
}

/**
 * Why a destination renders as a placeholder, stated as the remaining work —
 * the ledger's wording, so the shell cannot quietly claim a screen it does
 * not render.
 */
fun unwiredDestinationNote(destination: MockDestination): String = when (destination) {
    MockDestination.Workspaces ->
        "Not wired yet (remaining work): the production screen still lives app-side " +
            "with core-hostapi + Hilt dependencies the mock boundary forbids. " +
            "Next: shared extraction, then shell wiring."
    MockDestination.WorkspaceStart ->
        "Not wired yet (remaining work): no shared fixture or display state. " +
            "Next: fixture, shared display state, shell wiring."
    MockDestination.Session ->
        "Not wired yet (remaining work): the terminal display seam is still app-side. " +
            "Next: shared terminal display seam, then shell wiring."
    MockDestination.Workspace ->
        "Not wired yet (remaining work): shared SessionTreeUiState extraction and shell wiring."
    MockDestination.Files ->
        "Not wired yet (remaining work): shell wiring (state projects the shared " +
            "FileExplorerDisplayState)."
    MockDestination.FileViewer ->
        "Not wired yet (remaining work): shell wiring (mock-local viewer mirror until " +
            "app2's ViewerUiState is shared)."
    MockDestination.TerminalSettings ->
        "Not wired yet (remaining work): shell wiring (state carries the shared AppSettings)."
    MockDestination.VoiceSettings ->
        "Not wired yet (remaining work): shell wiring (state carries the shared AppSettings)."
    MockDestination.ConnectionSettings ->
        "Not wired yet (remaining work): shell wiring (state carries the shared AppSettings)."
    MockDestination.AdvancedSettings ->
        "Not wired yet (remaining work): shell wiring (state carries the shared AppSettings)."
    MockDestination.AccountSync ->
        "Not wired yet (remaining work): shell wiring (state carries the shared AccountSyncUiState)."
    MockDestination.Diagnostics ->
        "Not wired yet (remaining work): shell wiring (state carries the shared crash load state)."
    MockDestination.DiagnosticReport ->
        "Not wired yet (remaining work): shell wiring."
    MockDestination.About ->
        "Not wired yet (remaining work): shell wiring (shared build/update-check state)."
    MockDestination.Update ->
        "Not wired yet (remaining work): shell wiring (shared update-check state)."
    MockDestination.HostUsage ->
        "Not wired yet (remaining work): runnable shell wiring."
    MockDestination.TunnelDetail ->
        "Not wired yet (remaining work): shell wiring."
    MockDestination.AddTunnel ->
        "Not wired yet (remaining work): shell wiring."
    MockDestination.WorkspaceRoots ->
        "Not wired yet (remaining work): shell wiring."
    MockDestination.AddWorkspaceRoot ->
        "Not wired yet (remaining work): shell wiring."
    MockDestination.WorkspaceRootAction ->
        "Not wired yet (remaining work): shell wiring."
    MockDestination.ReorderWorkspaces ->
        "Not wired yet (remaining work): shell wiring."
    // Wired destinations never reach this function; the else keeps the when
    // exhaustive over the full 28-destination graph (D18 union).
    else -> "Not wired yet (remaining work)."
}
