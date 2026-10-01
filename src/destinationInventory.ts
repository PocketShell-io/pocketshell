/** Route map from the 0.5.x Destinations graph to the JS-first rewrite. */
export interface DestinationMapping {
  oldDestination: string;
  newRoute: string;
  status: 'partial' | 'available' | 'information-only' | 'planned';
  owner: string;
  note: string;
}

export const DESTINATION_INVENTORY: readonly DestinationMapping[] = [
  { oldDestination: 'Hosts', newRoute: 'home', status: 'partial', owner: '#2924/#2856', note: 'One-off SSH form is available; saved host management is pending.' },
  { oldDestination: 'Settings', newRoute: 'settings', status: 'available', owner: '#2861', note: 'Settings index links to the replacement settings and support routes.' },
  { oldDestination: 'TerminalSettings', newRoute: 'settings-terminal', status: 'available', owner: '#2861', note: 'Shared desktop theme registry and terminal font size are applied live.' },
  { oldDestination: 'VoiceSettings', newRoute: 'settings-voice', status: 'available', owner: '#2857', note: 'Dictation language is persisted and applied to the next Android recognizer start.' },
  { oldDestination: 'ConnectionSettings', newRoute: 'settings-connections', status: 'available', owner: '#2861', note: 'Background grace is persisted and read when a live connection backgrounds.' },
  { oldDestination: 'AdvancedSettings', newRoute: 'settings-advanced', status: 'partial', owner: '#2861/#2936', note: 'Enter-key delay, usage warning threshold and reset live in the shared core SettingsView; the Android route shows them once #2936 mounts the shared app.' },
  { oldDestination: 'AccountSync', newRoute: 'settings-account', status: 'information-only', owner: '#2852/#2861', note: 'Sync sign-in and credential handling remain pending.' },
  { oldDestination: 'Diagnostics', newRoute: 'diagnostics', status: 'available', owner: '#2861', note: 'Local redacted bridge, SSH, build, and lifecycle events can be reviewed and exported.' },
  { oldDestination: 'CrashReports', newRoute: 'diagnostics', status: 'available', owner: '#2861', note: 'Compatibility alias for the Diagnostics destination.' },
  { oldDestination: 'DiagnosticReport', newRoute: 'diagnostics-report', status: 'available', owner: '#2861', note: 'A selected event opens a report preview before export.' },
  { oldDestination: 'About', newRoute: 'about', status: 'available', owner: '#2861', note: 'Shows pinned source and packaged asset identity.' },
  { oldDestination: 'Update', newRoute: 'about-update', status: 'partial', owner: '#2861/#2936', note: 'The Android api.update capability (core release check, ACTION_VIEW handoff) feeds the shared Updates group and banner once #2936 mounts the shared app.' },
  { oldDestination: 'Usage', newRoute: 'usage', status: 'available', owner: '#2859', note: 'The connected host supplies provider usage; core parses quota windows and threshold states.' },
  { oldDestination: 'HostUsage', newRoute: 'usage', status: 'available', owner: '#2859', note: 'Provider usage is scoped to the active SSH host and read over its current generation.' },
  { oldDestination: 'Workspaces', newRoute: 'home', status: 'partial', owner: '#2925/#2856', note: 'The current home screen lists sessions after connection; workspace roots remain pending.' },
  { oldDestination: 'WorkspaceRootAction', newRoute: 'pending-workspaces', status: 'planned', owner: '#2925/#2856', note: 'Workspace root shortcuts are not part of this slice.' },
  { oldDestination: 'Workspace', newRoute: 'home', status: 'partial', owner: '#2925/#2856', note: 'Session list and terminal are available; persistent workspace navigation is pending.' },
  { oldDestination: 'WorkspaceStart', newRoute: 'home', status: 'partial', owner: '#2856/#2939', note: 'Session creation is available from the connected home screen.' },
  { oldDestination: 'ReorderWorkspaces', newRoute: 'pending-workspaces', status: 'planned', owner: '#2925', note: 'Workspace ordering is not part of this slice.' },
  { oldDestination: 'Tree', newRoute: 'home', status: 'partial', owner: '#2925/#2856', note: 'The current session list is the interim landing surface.' },
  { oldDestination: 'Session', newRoute: 'home', status: 'partial', owner: '#2856', note: 'The current home screen hosts the live terminal; route-level session state remains pending.' },
  { oldDestination: 'Files', newRoute: 'files', status: 'partial', owner: '#2858', note: 'The JS file workspace and native transfers are implemented; real Android DocumentsUI chooser interaction remains unverified.' },
  { oldDestination: 'FileViewer', newRoute: 'files', status: 'partial', owner: '#2858', note: 'The JS file workspace and native transfers are implemented; real Android DocumentsUI chooser interaction remains unverified.' },
  { oldDestination: 'Ports', newRoute: 'ports', status: 'available', owner: '#2859', note: 'Core discovers listening services and plans bounded automatic and manual local forwards.' },
  { oldDestination: 'TunnelDetail', newRoute: 'ports', status: 'available', owner: '#2859', note: 'Active tunnel endpoints and per-port status are shown in the port table.' },
  { oldDestination: 'AddTunnel', newRoute: 'ports', status: 'available', owner: '#2859', note: 'Discovered remote ports can be manually forwarded through the typed SSH capability.' },
  { oldDestination: 'HostForm', newRoute: 'home', status: 'partial', owner: '#2924/#2860', note: 'Current connection fields are in memory; saved host add/edit and migration remain pending.' },
  { oldDestination: 'SshKeys', newRoute: 'keys', status: 'available', owner: '#2926', note: 'JS-owned key management imports or generates keys in the Android encrypted vault and exposes only opaque handles and public metadata.' },
  { oldDestination: 'WorkspaceRoots', newRoute: 'pending-workspaces', status: 'planned', owner: '#2925', note: 'Workspace root management is not part of this slice.' },
  { oldDestination: 'AddWorkspaceRoot', newRoute: 'pending-workspaces', status: 'planned', owner: '#2925', note: 'Workspace root registration is not part of this slice.' },
  { oldDestination: 'ClearReports', newRoute: 'diagnostics-clear', status: 'available', owner: '#2861', note: 'Local diagnostics have an explicit confirm-and-clear route.' },
  { oldDestination: 'Language', newRoute: 'settings-voice', status: 'available', owner: '#2857', note: 'The persisted language hint is applied to the next Android recognizer start.' },
  { oldDestination: 'Grace', newRoute: 'settings-connections', status: 'available', owner: '#2861', note: 'Grace duration is chosen inline on the connection settings screen.' },
];

export const REQUIRED_OLD_DESTINATIONS = DESTINATION_INVENTORY.map((entry) => entry.oldDestination);
