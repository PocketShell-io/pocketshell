/** Route map from the 0.5.x Destinations graph to the JS-first rewrite. */
export interface DestinationMapping {
  oldDestination: string;
  newRoute: string;
  status: 'partial' | 'available' | 'information-only' | 'planned';
  owner: string;
  note: string;
}

export const DESTINATION_INVENTORY: readonly DestinationMapping[] = [
  { oldDestination: 'Hosts', newRoute: 'home', status: 'partial', owner: '#2851/#2856', note: 'One-off SSH form is available; saved host management is pending.' },
  { oldDestination: 'Settings', newRoute: 'settings', status: 'available', owner: '#2861', note: 'Settings index links to the replacement settings and support routes.' },
  { oldDestination: 'TerminalSettings', newRoute: 'settings-terminal', status: 'available', owner: '#2861', note: 'Shared desktop theme registry and terminal font size are applied live.' },
  { oldDestination: 'VoiceSettings', newRoute: 'settings-voice', status: 'information-only', owner: '#2857', note: 'Dictation controls are pending the voice provider implementation.' },
  { oldDestination: 'ConnectionSettings', newRoute: 'settings-connections', status: 'available', owner: '#2861', note: 'Background grace is persisted and read when a live connection backgrounds.' },
  { oldDestination: 'AdvancedSettings', newRoute: 'settings-advanced', status: 'information-only', owner: '#2861', note: 'No compatibility toggle is shown until its runtime behavior is implemented.' },
  { oldDestination: 'AccountSync', newRoute: 'settings-account', status: 'information-only', owner: '#2852/#2861', note: 'Sync sign-in and credential handling remain pending.' },
  { oldDestination: 'Diagnostics', newRoute: 'diagnostics', status: 'available', owner: '#2861', note: 'Local redacted bridge, SSH, build, and lifecycle events can be reviewed and exported.' },
  { oldDestination: 'CrashReports', newRoute: 'diagnostics', status: 'available', owner: '#2861', note: 'Compatibility alias for the Diagnostics destination.' },
  { oldDestination: 'DiagnosticReport', newRoute: 'diagnostics-report', status: 'available', owner: '#2861', note: 'A selected event opens a report preview before export.' },
  { oldDestination: 'About', newRoute: 'about', status: 'available', owner: '#2861', note: 'Shows pinned source and packaged asset identity.' },
  { oldDestination: 'Update', newRoute: 'about-update', status: 'information-only', owner: '#2861', note: 'Update checks and Android install handoff are deliberately unavailable in the rewrite preview.' },
  { oldDestination: 'Usage', newRoute: 'pending-usage', status: 'planned', owner: '#2859', note: 'Provider quota panel is not part of this slice.' },
  { oldDestination: 'HostUsage', newRoute: 'pending-host-usage', status: 'planned', owner: '#2859', note: 'Host-scoped quota panel is not part of this slice.' },
  { oldDestination: 'Workspaces', newRoute: 'home', status: 'partial', owner: '#2851/#2856', note: 'The current home screen lists sessions after connection; workspace roots remain pending.' },
  { oldDestination: 'WorkspaceRootAction', newRoute: 'pending-workspaces', status: 'planned', owner: '#2851/#2856', note: 'Workspace root shortcuts are not part of this slice.' },
  { oldDestination: 'Workspace', newRoute: 'home', status: 'partial', owner: '#2851/#2856', note: 'Session list and terminal are available; persistent workspace navigation is pending.' },
  { oldDestination: 'WorkspaceStart', newRoute: 'home', status: 'partial', owner: '#2856', note: 'Session creation is available from the connected home screen.' },
  { oldDestination: 'ReorderWorkspaces', newRoute: 'pending-workspaces', status: 'planned', owner: '#2851', note: 'Workspace ordering is not part of this slice.' },
  { oldDestination: 'Tree', newRoute: 'home', status: 'partial', owner: '#2851/#2856', note: 'The current session list is the interim landing surface.' },
  { oldDestination: 'Session', newRoute: 'home', status: 'partial', owner: '#2856', note: 'The current home screen hosts the live terminal; route-level session state remains pending.' },
  { oldDestination: 'Files', newRoute: 'pending-files', status: 'planned', owner: '#2858', note: 'Remote file browsing is not part of this slice.' },
  { oldDestination: 'FileViewer', newRoute: 'pending-files', status: 'planned', owner: '#2858', note: 'Remote file viewing and editing are not part of this slice.' },
  { oldDestination: 'Ports', newRoute: 'pending-ports', status: 'planned', owner: '#2859', note: 'Port forwarding is not part of this slice.' },
  { oldDestination: 'TunnelDetail', newRoute: 'pending-ports', status: 'planned', owner: '#2859', note: 'Tunnel details are not part of this slice.' },
  { oldDestination: 'AddTunnel', newRoute: 'pending-ports', status: 'planned', owner: '#2859', note: 'Tunnel creation is not part of this slice.' },
  { oldDestination: 'HostForm', newRoute: 'home', status: 'partial', owner: '#2851/#2860', note: 'Current connection fields are in memory; saved host add/edit and migration remain pending.' },
  { oldDestination: 'SshKeys', newRoute: 'home', status: 'partial', owner: '#2851/#2860', note: 'The current form accepts a temporary private key; key vault management remains pending.' },
  { oldDestination: 'WorkspaceRoots', newRoute: 'pending-workspaces', status: 'planned', owner: '#2851', note: 'Workspace root management is not part of this slice.' },
  { oldDestination: 'AddWorkspaceRoot', newRoute: 'pending-workspaces', status: 'planned', owner: '#2851', note: 'Workspace root registration is not part of this slice.' },
  { oldDestination: 'ClearReports', newRoute: 'diagnostics-clear', status: 'available', owner: '#2861', note: 'Local diagnostics have an explicit confirm-and-clear route.' },
  { oldDestination: 'Language', newRoute: 'settings-voice', status: 'information-only', owner: '#2857', note: 'Language selection follows the supported recognizer configuration and is pending.' },
  { oldDestination: 'Grace', newRoute: 'settings-connections', status: 'available', owner: '#2861', note: 'Grace duration is chosen inline on the connection settings screen.' },
];

export const REQUIRED_OLD_DESTINATIONS = DESTINATION_INVENTORY.map((entry) => entry.oldDestination);
