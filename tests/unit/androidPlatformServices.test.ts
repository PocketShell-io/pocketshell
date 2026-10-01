import { describe, expect, it, vi } from 'vitest';
import { createApp } from 'vue';
import type { ReleaseFetch } from '@pocketshell/core';
import {
  HIDDEN_IMPORTED_REPORTS_STORAGE_KEY,
  RUNTIME_REPORTS_STORAGE_KEY,
  createAndroidDiagnostics,
  forgetDiagnosticTermsForTest,
  rememberDiagnosticTerms,
  type DiagnosticsStorage,
  type ImportedReportAsset,
} from '../../src/platform/androidDiagnostics';
import { androidUpdateCapabilityFor, createAndroidUpdateCapability } from '../../src/platform/androidUpdate';
import { installAndroidPlatformServices } from '../../src/platform/androidPlatformServices';
import type { NativeCrashReportFile } from '../../src/native/nativeCrashReports';

class MemoryStorage implements DiagnosticsStorage {
  values = new Map<string, string>();
  getItem(key: string) { return this.values.get(key) ?? null; }
  setItem(key: string, value: string) { this.values.set(key, value); }
  removeItem(key: string) { this.values.delete(key); }
}

const NATIVE_CRASH = [
  'PocketShell crash report',
  'Generated: 2026-09-30T08:00:00.000Z',
  'App version: 0.6.0',
  'Thread: main',
  '',
  'Exception summary: IllegalStateException: ssh to alexey@dev.example.internal failed',
  '',
  'Exception',
  'java.lang.IllegalStateException: ssh to alexey@dev.example.internal failed',
  '\tat com.pocketshell.app.SshCapabilityPlugin.connect(SshCapabilityPlugin.java:10)',
].join('\n');

const LEGACY_CRASH = NATIVE_CRASH.replace('2026-09-30T08:00:00.000Z', '2026-08-01T08:00:00.000Z').replace('0.6.0', '0.5.4')
  .replace('Thread: main', 'Thread: main\n\nContext\nHost: devbox\nHostname: dev.example.internal');

function fakeNative(files: NativeCrashReportFile[]) {
  const reports = [...files];
  return {
    reports,
    list: vi.fn(async () => ({ reports: [...reports] })),
    remove: vi.fn(async ({ id }: { id: string }) => {
      const index = reports.findIndex((report) => report.id === id);
      if (index >= 0) reports.splice(index, 1);
      return { id, removed: index >= 0 };
    }),
    clear: vi.fn(async () => {
      const removed = reports.length;
      reports.splice(0);
      return { removed };
    }),
  };
}

function release(tag: string) {
  return {
    tag_name: tag,
    html_url: `https://github.com/PocketShell-io/pocketshell/releases/tag/${tag}`,
    assets: [
      { name: `pocketshell-${tag}-debug.apk`, browser_download_url: `https://github.com/PocketShell-io/pocketshell/releases/download/${tag}/pocketshell-${tag}-debug.apk` },
      { name: `pocketshell-${tag}-release.apk`, browser_download_url: `https://github.com/PocketShell-io/pocketshell/releases/download/${tag}/pocketshell-${tag}-release.apk` },
    ],
  };
}

describe('Android platform services for the shared app', () => {
  it('checks GitHub releases for this install flavour and hands only release links to the browser', async () => {
    const fetcher = vi.fn(async () => ({ ok: true, status: 200, json: async () => release('v0.6.1') })) as unknown as ReleaseFetch;
    const opened: string[] = [];
    const update = createAndroidUpdateCapability({
      readAppInfo: async () => ({ versionName: '0.6.0', versionCode: 120, applicationId: 'com.pocketshell.app.release' }),
      fetcher,
      openUrl: (url) => opened.push(url),
    });
    const result = await update.check();
    expect(result).toMatchObject({ status: 'available', tagName: 'v0.6.1', currentVersion: '0.6.0' });
    expect(result.status === 'available' && result.downloadUrl).toContain('-release.apk');
    await update.open('https://github.com/PocketShell-io/pocketshell/releases/tag/v0.6.1');
    await expect(update.open('https://evil.example/a.apk')).rejects.toThrow(/GitHub release links/);
    expect(opened).toEqual(['https://github.com/PocketShell-io/pocketshell/releases/tag/v0.6.1']);

    const unknownVersion = createAndroidUpdateCapability({
      readAppInfo: async () => ({ versionName: 'unknown', versionCode: null, applicationId: 'com.pocketshell.app' }),
      fetcher,
    });
    await expect(unknownVersion.check()).resolves.toMatchObject({ status: 'failed', reason: 'installed version is unknown' });
  });

  it('omits the update capability for per-worktree test installs', () => {
    expect(androidUpdateCapabilityFor({ versionName: '0.6.0', versionCode: 1, applicationId: 'com.pocketshell.app' })).toBeDefined();
    expect(androidUpdateCapabilityFor({ versionName: '0.6.0', versionCode: 1, applicationId: 'com.pocketshell.app.release' })).toBeDefined();
    expect(androidUpdateCapabilityFor({ versionName: '0.6.0', versionCode: 1, applicationId: 'com.pocketshell.app.i2861' })).toBeUndefined();
  });

  it('lists runtime errors, native crashes and imported 0.5.x reports newest first, all redacted', async () => {
    const storage = new MemoryStorage();
    const native = fakeNative([{ id: '20260930-080000-000', fileName: '20260930-080000-000.txt', text: NATIVE_CRASH }]);
    const imported: ImportedReportAsset[] = [
      { assetId: 'a1', category: 'crash-report', relativePath: 'crash-reports/20260801-080000-000.txt', text: LEGACY_CRASH },
      { assetId: 'a2', category: 'diagnostic-history', relativePath: 'diagnostics/pocketshell-diagnostics.jsonl', text: '{"category":"ssh","wallClock":"2026-07-01T00:00:00Z","host":"10.1.2.3"}\n' },
    ];
    const diagnostics = createAndroidDiagnostics({ storage, nativeCrashes: native, readImportedAssets: async () => imported, now: () => Date.parse('2026-09-30T09:00:00Z'), appVersion: () => '0.6.0' });
    diagnostics.log({ kind: 'unhandledrejection', message: 'fetch ssh://root@prod.example.com failed', errorName: 'TypeError', stack: 'TypeError: x\n  at /home/alexey/app.js:1' });

    const reports = await diagnostics.list();
    expect(reports.map((report) => [report.source, report.title])).toEqual([
      ['runtime-error', 'Unhandled promise rejection: TypeError'],
      ['native-crash', 'Native crash: IllegalStateException'],
      ['imported-crash', '0.5.x crash report: IllegalStateException'],
      ['imported-history', '0.5.x diagnostic history'],
    ]);
    const everything = JSON.stringify(reports);
    for (const secret of ['prod.example.com', 'dev.example.internal', 'devbox', '/home/alexey', '10.1.2.3']) {
      expect(everything, secret).not.toContain(secret);
    }
  });

  it('deletes one report per source, hides imported sources without touching them, and clears all', async () => {
    const storage = new MemoryStorage();
    const native = fakeNative([
      { id: '20260930-080000-000', fileName: '20260930-080000-000.txt', text: NATIVE_CRASH },
      { id: '20260930-080001-000', fileName: '20260930-080001-000.txt', text: NATIVE_CRASH },
    ]);
    const imported: ImportedReportAsset[] = [
      { assetId: 'a1', category: 'crash-report', relativePath: 'crash-reports/20260801-080000-000.txt', text: LEGACY_CRASH },
    ];
    let clock = 1_000;
    const diagnostics = createAndroidDiagnostics({ storage, nativeCrashes: native, readImportedAssets: async () => imported, now: () => clock++ });
    diagnostics.log({ kind: 'error', message: 'one', errorName: 'Error' });
    diagnostics.log({ kind: 'error', message: 'two', errorName: 'RangeError' });
    const runtimeId = (await diagnostics.list()).find((report) => report.title.endsWith('RangeError'))!.id;

    expect(await diagnostics.remove(runtimeId)).toBe(true);
    expect(await diagnostics.remove(runtimeId)).toBe(false);
    expect(await diagnostics.remove('native:20260930-080000-000')).toBe(true);
    expect(native.remove).toHaveBeenCalledWith({ id: '20260930-080000-000' });
    expect(await diagnostics.remove('imported:a1')).toBe(true);
    expect(imported).toHaveLength(1);
    expect(JSON.parse(storage.getItem(HIDDEN_IMPORTED_REPORTS_STORAGE_KEY) ?? '[]')).toEqual(['imported:a1']);
    expect((await diagnostics.list()).map((report) => report.id)).toEqual([
      'native:20260930-080001-000',
      expect.stringMatching(/^runtime-/),
    ]);

    expect(await diagnostics.clear()).toBe(2);
    expect(await diagnostics.list()).toEqual([]);
    expect(storage.getItem(RUNTIME_REPORTS_STORAGE_KEY)).toBeNull();
  });

  it('ignores tampered stored reports and survives a failing native bridge', async () => {
    const storage = new MemoryStorage();
    storage.setItem(RUNTIME_REPORTS_STORAGE_KEY, JSON.stringify([{ id: '../etc', source: 'runtime-error', at: 1, title: 't', body: 'b' }, 'junk']));
    const diagnostics = createAndroidDiagnostics({
      storage,
      nativeCrashes: { list: async () => { throw new Error('bridge down'); }, remove: async () => ({ id: '', removed: false }), clear: async () => { throw new Error('bridge down'); } },
      readImportedAssets: async () => { throw new Error('idb down'); },
    });
    expect(await diagnostics.list()).toEqual([]);
    expect(await diagnostics.clear()).toBe(0);
  });

  it('captures Vue, unhandled-rejection and window errors from app startup', async () => {
    const storage = new MemoryStorage();
    vi.stubGlobal('localStorage', storage);
    const target = Object.assign(new EventTarget(), { localStorage: storage }) as unknown as Window;
    const app = createApp({});
    const services = installAndroidPlatformServices(app, target);
    const quiet = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    app.config.errorHandler?.(new TypeError('render failed'), null, 'render');
    target.dispatchEvent(Object.assign(new Event('unhandledrejection'), { reason: new RangeError('async failed') }));
    target.dispatchEvent(Object.assign(new Event('error'), { error: new SyntaxError('window failed') }));
    target.dispatchEvent(Object.assign(new Event('error'), { error: null }));
    quiet.mockRestore();
    vi.unstubAllGlobals();

    const titles = (await services.diagnostics.list()).filter((report) => report.source === 'runtime-error').map((report) => report.title).sort();
    expect(titles).toEqual(['Render error: TypeError', 'Uncaught error: SyntaxError', 'Unhandled promise rejection: RangeError']);
    expect((target as Window & { __ps2861PlatformServices?: unknown }).__ps2861PlatformServices).toBeUndefined();
  });

  it('blanks remembered host names and seeded secrets in every report source', async () => {
    forgetDiagnosticTermsForTest();
    rememberDiagnosticTerms(['devbox', 'alexey', undefined, 'x']);
    const storage = new MemoryStorage();
    const native = fakeNative([{ id: '20260930-080000-000', fileName: '20260930-080000-000.txt', text: 'Exception summary: IOException\n\nException\njava.io.IOException\n\tat a.B.c(B.java:1) devbox' }]);
    const diagnostics = createAndroidDiagnostics({
      storage,
      nativeCrashes: native,
      readImportedAssets: async () => [{ assetId: 'h', category: 'diagnostic-history', relativePath: 'd.jsonl', text: '{"event":"lost devbox","password":"SEEDEDPW123"}\n' }],
    });
    diagnostics.log({ kind: 'error', errorName: 'Error', message: 'Could not reconnect to devbox as alexey; Authorization: Bearer ghp_SEEDEDabcdef123456' });
    const text = JSON.stringify(await diagnostics.list());
    for (const seed of ['devbox', 'alexey', 'SEEDEDPW123', 'ghp_SEEDED']) expect(text, seed).not.toContain(seed);
    forgetDiagnosticTermsForTest();
  });

  it('exposes the journey probe only for a page loaded with the probe query, and records its lifecycle', async () => {
    const plain = Object.assign(new EventTarget(), { localStorage: new MemoryStorage(), location: { search: '' } }) as unknown as Window;
    installAndroidPlatformServices(createApp({}), plain);
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect((plain as Window & { __ps2861PlatformServices?: unknown }).__ps2861PlatformServices).toBeUndefined();
    expect((plain as Window & { __ps2861ProbeState?: unknown }).__ps2861ProbeState).toBeUndefined();

    const probed = Object.assign(new EventTarget(), { localStorage: new MemoryStorage(), location: { search: '?ps2861Probe=1' } }) as unknown as Window;
    const services = installAndroidPlatformServices(createApp({}), probed);
    await new Promise((resolve) => setTimeout(resolve, 0));
    const probe = probed as Window & { __ps2861PlatformServices?: unknown; __ps2861ProbeState?: { infoState: string; exposedAt?: number } };
    // Off-device the install id is unknown (not a canonical package), as on a test install.
    expect(probe.__ps2861PlatformServices).toBe(services);
    expect(probe.__ps2861ProbeState).toMatchObject({ infoState: 'resolved' });
    expect(typeof probe.__ps2861ProbeState?.exposedAt).toBe('number');
  });
});

