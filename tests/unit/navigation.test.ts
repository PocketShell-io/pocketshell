import { describe, expect, it, beforeEach } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { useNavigationStore } from '../../src/stores/navigation';

describe('mobile route stack', () => {
  beforeEach(() => setActivePinia(createPinia()));

  it('keeps nested settings and support routes reachable through Back', () => {
    const navigation = useNavigationStore();

    navigation.openSettings();
    navigation.open('settings-terminal');
    navigation.open('diagnostics');
    navigation.open('diagnostics-report');

    expect(navigation.route).toBe('diagnostics-report');
    expect(navigation.stack).toEqual(['home', 'settings', 'settings-terminal', 'diagnostics', 'diagnostics-report']);
    navigation.back();
    expect(navigation.route).toBe('diagnostics');
    navigation.back();
    expect(navigation.route).toBe('settings-terminal');
    navigation.back();
    expect(navigation.route).toBe('settings');
    navigation.back();
    expect(navigation.route).toBe('home');
    expect(navigation.canGoBack).toBe(false);
  });

  it('retains a selected report ID and resets the stack when returning home', () => {
    const navigation = useNavigationStore();
    navigation.open('diagnostics');
    navigation.openReport('report-123');

    expect(navigation.selectedReportId).toBe('report-123');
    navigation.home();
    expect(navigation.route).toBe('home');
    expect(navigation.stack).toEqual(['home']);
    expect(navigation.selectedReportId).toBeNull();
  });

  it('clears the selected event when opening the whole-log export preview', () => {
    const navigation = useNavigationStore();
    navigation.open('diagnostics');
    navigation.openReport('event-1');
    navigation.back();
    navigation.openDiagnosticsExport();

    expect(navigation.route).toBe('diagnostics-report');
    expect(navigation.selectedReportId).toBeNull();
  });

  it('opens the SSH file workspace as a back-navigable full-screen destination', () => {
    const navigation = useNavigationStore();

    navigation.open('files');
    expect(navigation.route).toBe('files');
    expect(navigation.stack).toEqual(['home', 'files']);
    navigation.back();
    expect(navigation.route).toBe('home');
  });

  it('does not push the current destination a second time', () => {
    const navigation = useNavigationStore();
    navigation.openSettings();
    navigation.openSettings();
    expect(navigation.stack).toEqual(['home', 'settings']);
  });
});
