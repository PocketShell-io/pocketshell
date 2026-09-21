package com.pocketshell.next.mockapp

/**
 * The mock app's navigation state (issue #2636 phase 1).
 *
 * One value per destination the future mock shell can represent in its pure
 * state seam, each naming the production navigation destination it stands in
 * for. Since the D18 interactive-state slice this is the FULL production set
 * (28/28): every absent destination would stay listed in README's checked
 * coverage ledger, never silently claimed. Adding a value here means
 * reducer/state coverage, not runnable-screen coverage.
 */
sealed class MockDestination {

    /** Landing saved-host list — production `Destination.Hosts`. */
    data object Hosts : MockDestination()

    /** Add/edit host form — production `Destination.HostForm`. */
    data object HostForm : MockDestination()

    /** Registered SSH keys — production `Destination.SshKeys`. */
    data object SshKeys : MockDestination()

    /** Host workspace root screen — production `Destination.Workspaces`. */
    data object Workspaces : MockDestination()

    /** New-session start screen — production `Destination.WorkspaceStart`. */
    data object WorkspaceStart : MockDestination()

    /** One persistent workspace — production `Destination.Workspace`. */
    data object Workspace : MockDestination()

    /** Live terminal session — production `Destination.Session`. */
    data object Session : MockDestination()

    /** Remote file browser — production `Destination.Files`. */
    data object Files : MockDestination()

    /** One file open in the viewer/editor — production `Destination.FileViewer`. */
    data object FileViewer : MockDestination()

    /** Services & tunnels — production `Destination.Ports`. */
    data object Services : MockDestination()

    /** Host quota panel — production `Destination.Usage`. */
    data object Usage : MockDestination()

    /** Host-scoped quota panel — production `Destination.HostUsage`. */
    data object HostUsage : MockDestination()

    /** Settings index — production `Destination.Settings`. */
    data object Settings : MockDestination()

    /** Terminal reading settings — production `Destination.TerminalSettings`. */
    data object TerminalSettings : MockDestination()

    /** Dictation settings — production `Destination.VoiceSettings`. */
    data object VoiceSettings : MockDestination()

    /** App-switching/connection-lifetime settings — production `Destination.ConnectionSettings`. */
    data object ConnectionSettings : MockDestination()

    /** Timing and compatibility settings — production `Destination.AdvancedSettings`. */
    data object AdvancedSettings : MockDestination()

    /** Optional account sync settings — production `Destination.AccountSync`. */
    data object AccountSync : MockDestination()

    /** Local diagnostics index — production `Destination.Diagnostics`. */
    data object Diagnostics : MockDestination()

    /** One stored report — production `Destination.DiagnosticReport`. */
    data object DiagnosticReport : MockDestination()

    /** Build identity and update entry point — production `Destination.About`. */
    data object About : MockDestination()

    /** Release-check screen — production `Destination.Update`. */
    data object Update : MockDestination()

    /** One tunnel's detail — production `Destination.TunnelDetail`. */
    data object TunnelDetail : MockDestination()

    /** Manual tunnel form — production `Destination.AddTunnel`. */
    data object AddTunnel : MockDestination()

    /** Per-host workspace-root manager — production `Destination.WorkspaceRoots`. */
    data object WorkspaceRoots : MockDestination()

    /** Focused add-root form — production `Destination.AddWorkspaceRoot`. */
    data object AddWorkspaceRoot : MockDestination()

    /** Host-scoped root/workspace reorder — production `Destination.ReorderWorkspaces`. */
    data object ReorderWorkspaces : MockDestination()

    /** Workspace screen with one root action opened — production `Destination.WorkspaceRootAction`. */
    data object WorkspaceRootAction : MockDestination()

    /**
     * Where system Back lands. A simplification over the production
     * `NavHost` back stack (which records the real entry point per visit);
     * good enough for a mock loop, and it keeps the reducer pure.
     */
    val parent: MockDestination?
        get() = when (this) {
            Hosts, Settings -> null
            HostForm, Workspaces, Services -> Hosts
            SshKeys -> Settings
            Workspace, Files, ReorderWorkspaces, WorkspaceRootAction, HostUsage -> Workspaces
            WorkspaceStart, Session, Usage -> Workspaces
            FileViewer -> Files
            TunnelDetail, AddTunnel -> Services
            TerminalSettings, VoiceSettings, ConnectionSettings, AdvancedSettings,
            AccountSync, Diagnostics, About,
            -> Settings
            DiagnosticReport -> Diagnostics
            Update -> About
            WorkspaceRoots -> ConnectionSettings
            AddWorkspaceRoot -> WorkspaceRoots
        }
}
