import { describe, expect, it } from 'vitest';
import { DESTINATION_INVENTORY, REQUIRED_OLD_DESTINATIONS } from '../../src/destinationInventory';

const PRE_REWRITE_DESTINATIONS = [
  'Hosts', 'Settings', 'TerminalSettings', 'VoiceSettings', 'ConnectionSettings', 'AdvancedSettings',
  'AccountSync', 'Diagnostics', 'DiagnosticReport', 'About', 'Update', 'Usage', 'HostUsage',
  'Workspaces', 'WorkspaceRootAction', 'Workspace', 'WorkspaceStart', 'ReorderWorkspaces', 'Tree',
  'Session', 'Files', 'FileViewer', 'Ports', 'TunnelDetail', 'AddTunnel', 'HostForm', 'SshKeys',
  'WorkspaceRoots', 'AddWorkspaceRoot',
  'CrashReports',
];

describe('pre-rewrite destination inventory', () => {
  it('maps each shipped destination to a replacement route, owning issue, and implementation status', () => {
    const mappings = new Map(DESTINATION_INVENTORY.map((entry) => [entry.oldDestination, entry]));
    for (const destination of PRE_REWRITE_DESTINATIONS) expect(mappings.has(destination), destination).toBe(true);
    expect(mappings.size).toBe(DESTINATION_INVENTORY.length);
    expect(DESTINATION_INVENTORY.map((entry) => entry.oldDestination)).toEqual(expect.arrayContaining(PRE_REWRITE_DESTINATIONS));
    expect(DESTINATION_INVENTORY.every((entry) => entry.newRoute && entry.owner && entry.note)).toBe(true);
  });

  it('states which prior settings/support surfaces have working routes in this slice', () => {
    const currentDestinations = ['Settings', 'TerminalSettings', 'ConnectionSettings', 'Usage', 'HostUsage', 'Ports', 'TunnelDetail', 'AddTunnel', 'Diagnostics', 'DiagnosticReport', 'About', 'ClearReports', 'Grace'];
    for (const destination of currentDestinations) {
      expect(DESTINATION_INVENTORY.find((entry) => entry.oldDestination === destination)?.status).toBe('available');
    }
    expect(DESTINATION_INVENTORY.find((entry) => entry.oldDestination === 'VoiceSettings')?.status).toBe('information-only');
    expect(REQUIRED_OLD_DESTINATIONS.length).toBe(DESTINATION_INVENTORY.length);
  });
});
