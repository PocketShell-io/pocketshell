package com.pocketshell.next.mockapp

/**
 * The mock app's navigation state (issue #2636 phase 1).
 *
 * One value per destination the future mock shell can represent in its pure
 * state seam, each naming the production navigation destination it stands in
 * for. This is deliberately a subset (9/28): every absent destination stays
 * listed in README's checked coverage ledger, never silently claimed. Adding
 * a value here means reducer/state coverage, not runnable-screen coverage.
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

    /** Live terminal session — production `Destination.Session`. */
    data object Session : MockDestination()

    /** Services & tunnels — production `Destination.Ports`. */
    data object Services : MockDestination()

    /** Host quota panel — production `Destination.Usage`. */
    data object Usage : MockDestination()

    /** Settings index — production `Destination.Settings`. */
    data object Settings : MockDestination()

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
            WorkspaceStart, Session, Usage -> Workspaces
        }
}
