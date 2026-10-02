import { readdirSync, readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import {
  DurableStorageError,
  installDurableStorage,
  recordDurableStorageStatus,
  type DurableStorageDependencies,
  type DurableStorageOpenResult,
  type DurableStorageWriter,
} from '../../src/native/durableStorage';
import { HOST_SNIPPETS_STORAGE_KEY, HostSnippetRepository } from '../../src/stores/hostSnippets';
import { readDiagnosticEvents } from '../../src/diagnostics';
import { DEFAULT_APP_SETTINGS, SETTINGS_STORAGE_KEY, persistAppSettings, readAppSettings } from '../../src/stores/appSettings';

/** A fresh Storage-like class per test, so prototype patching never leaks between tests. */
function storageClass() {
  return class FakeStorage {
    private readonly values = new Map<string, string>();
    get length() { return this.values.size; }
    key(index: number) { return [...this.values.keys()][index] ?? null; }
    getItem(key: string) { return this.values.get(String(key)) ?? null; }
    setItem(key: string, value: string) { this.values.set(String(key), String(value)); }
    removeItem(key: string) { this.values.delete(String(key)); }
    clear() { this.values.clear(); }
  };
}

/** Synchronous fake of the native writer: committed entries are visible the moment a call returns. */
class FakeWriter implements DurableStorageWriter {
  committed = new Map<string, string>();
  calls: string[] = [];
  tokens = new Set<string>();
  failWith = '';
  setItem(token: string, key: string, value: string) {
    this.tokens.add(token);
    this.calls.push(`set:${key}`);
    if (this.failWith) return this.failWith;
    this.committed.set(key, value);
    return '';
  }
  removeItem(token: string, key: string) {
    this.tokens.add(token);
    this.calls.push(`remove:${key}`);
    if (this.failWith) return this.failWith;
    this.committed.delete(key);
    return '';
  }
  clear(token: string) {
    this.tokens.add(token);
    this.calls.push('clear');
    if (this.failWith) return this.failWith;
    this.committed.clear();
    return '';
  }
  importAll(token: string, entriesJson: string) {
    this.tokens.add(token);
    this.calls.push('import');
    if (this.failWith) return this.failWith;
    for (const [key, value] of Object.entries(JSON.parse(entriesJson) as Record<string, string>)) {
      this.committed.set(key, value);
    }
    return '';
  }
}

function harness(open: Partial<DurableStorageOpenResult> = {}) {
  const Storage = storageClass();
  const local = new Storage();
  const session = new Storage();
  const writer = new FakeWriter();
  const opened: DurableStorageOpenResult = { token: 'page-token', initialized: false, entries: {}, ...open };
  const dependencies: DurableStorageDependencies = {
    storage: local as unknown as DurableStorageDependencies['storage'],
    prototype: Storage.prototype as unknown as DurableStorageDependencies['prototype'],
    writer,
    open: async () => opened,
  };
  return { local, session, writer, dependencies };
}

describe('Android durable storage seam', () => {
  it('imports existing localStorage once and makes the native store authoritative', async () => {
    const { local, writer, dependencies } = harness({ initialized: false });
    local.setItem(HOST_SNIPPETS_STORAGE_KEY, '{"saved":true}');
    local.setItem(SETTINGS_STORAGE_KEY, '{"themeChoice":"nord"}');

    const status = await installDurableStorage(dependencies);

    expect(status).toEqual({ state: 'native-durable', imported: 2, restored: 0, removed: 0 });
    expect(writer.calls).toEqual(['import']);
    expect(Object.fromEntries(writer.committed)).toEqual({
      [HOST_SNIPPETS_STORAGE_KEY]: '{"saved":true}',
      [SETTINGS_STORAGE_KEY]: '{"themeChoice":"nord"}',
    });
    expect(local.getItem(SETTINGS_STORAGE_KEY)).toBe('{"themeChoice":"nord"}');
  });

  it('restores writes and removals the WebView lost to a kill from the committed entries', async () => {
    // The WebView's on-disk state lags: the deleted chip is back, a new key is missing.
    const { local, writer, dependencies } = harness({
      initialized: true,
      entries: { [HOST_SNIPPETS_STORAGE_KEY]: '{"chips":["keep"]}', 'shared.ui.width': '320' },
    });
    local.setItem(HOST_SNIPPETS_STORAGE_KEY, '{"chips":["keep","deleted"]}');
    local.setItem('removed.before.kill', 'stale');

    const status = await installDurableStorage(dependencies);

    expect(status).toEqual({ state: 'native-durable', imported: 0, restored: 2, removed: 1 });
    expect(local.getItem(HOST_SNIPPETS_STORAGE_KEY)).toBe('{"chips":["keep"]}');
    expect(local.getItem('shared.ui.width')).toBe('320');
    expect(local.getItem('removed.before.kill')).toBeNull();
    // Hydration itself never writes back to native storage.
    expect(writer.calls).toEqual([]);
  });

  it('commits every localStorage write, removal and clear natively before returning', async () => {
    const { local, writer, dependencies } = harness({ initialized: true, entries: {} });
    await installDurableStorage(dependencies);

    local.setItem('a', '1');
    expect(writer.committed.get('a')).toBe('1');
    local.setItem('b', '🙂 β');
    expect(writer.committed.get('b')).toBe('🙂 β');
    local.removeItem('a');
    expect(writer.committed.has('a')).toBe(false);
    local.removeItem('never-written');
    local.clear();
    expect(writer.committed.size).toBe(0);
    expect(local.length).toBe(0);
    expect(writer.calls).toEqual(['set:a', 'set:b', 'remove:a', 'clear']);
    expect([...writer.tokens]).toEqual(['page-token']);
  });

  it('leaves sessionStorage and other storage areas non-durable', async () => {
    const { session, writer, dependencies } = harness({ initialized: true, entries: {} });
    await installDurableStorage(dependencies);

    session.setItem('reload-once', '1');
    session.removeItem('reload-once');
    session.setItem('x', 'y');
    session.clear();

    expect(writer.calls).toEqual([]);
    expect(session.length).toBe(0);
  });

  it('rolls back and throws when the native commit fails, so no write is acknowledged without being durable', async () => {
    const { local, writer, dependencies } = harness({ initialized: true, entries: { kept: 'old' } });
    await installDurableStorage(dependencies);
    writer.failWith = 'No space left on device';

    expect(() => local.setItem('kept', 'new')).toThrow(DurableStorageError);
    expect(local.getItem('kept')).toBe('old');
    expect(() => local.setItem('fresh', 'value')).toThrow(/No space left on device/);
    expect(local.getItem('fresh')).toBeNull();
    expect(() => local.removeItem('kept')).toThrow(DurableStorageError);
    expect(local.getItem('kept')).toBe('old');
    expect(() => local.clear()).toThrow(DurableStorageError);
    expect(local.getItem('kept')).toBe('old');
  });

  it('keeps plain localStorage and records a Diagnostics event when the native writer or snapshot is unavailable', async () => {
    const missingWriter = harness();
    const status = await installDurableStorage({ ...missingWriter.dependencies, writer: undefined });
    expect(status.state).toBe('failed');
    missingWriter.local.setItem('still', 'works');
    expect(missingWriter.local.getItem('still')).toBe('works');

    const rejected = harness();
    const rejectedStatus = await installDurableStorage({
      ...rejected.dependencies,
      open: async () => { throw new Error('Saved app data could not be read from durable storage.'); },
    });
    expect(rejectedStatus).toEqual({ state: 'failed', reason: 'Saved app data could not be read from durable storage.' });
    rejected.local.setItem('still', 'works');
    expect(rejected.writer.calls).toEqual([]);

    const failedImport = harness({ initialized: false });
    failedImport.writer.failWith = 'disk error';
    expect((await installDurableStorage(failedImport.dependencies)).state).toBe('failed');
    failedImport.writer.failWith = '';
    failedImport.local.setItem('after', 'failed-import');
    expect(failedImport.writer.calls).toEqual(['import']);

    const root = { dataset: {} as Record<string, string> } as unknown as HTMLElement;
    const diagnostics = new (storageClass())();
    recordDurableStorageStatus(root, rejectedStatus, diagnostics);
    expect(root.dataset.durableStorage).toBe('failed');
    expect(readDiagnosticEvents(diagnostics).map((event) => [event.kind, event.operation])).toEqual([
      ['storage-durability-failed', 'storage'],
    ]);
    recordDurableStorageStatus(root, { state: 'native-durable', imported: 0, restored: 0, removed: 0 }, diagnostics);
    expect(readDiagnosticEvents(diagnostics)).toHaveLength(1);
  });

  it('falls back to plain localStorage with a Diagnostics entry when native open never answers, ignoring a late answer', async () => {
    const lost = harness({ initialized: true, entries: { saved: 'durable' } });
    let answerLate: (value: DurableStorageOpenResult) => void = () => undefined;
    const pending = new Promise<DurableStorageOpenResult>((resolve) => { answerLate = resolve; });
    const status = await installDurableStorage({ ...lost.dependencies, open: () => pending, openTimeoutMs: 20 });
    expect(status).toEqual({ state: 'failed', reason: "The phone's app storage did not answer within 20 ms." });

    // The reply that arrives after startup moved on must not hydrate or patch storage.
    answerLate({ token: 'late-token', initialized: true, entries: { saved: 'durable' } });
    await pending;
    await new Promise((resolve) => setTimeout(resolve, 0));
    lost.local.setItem('plain', 'write');
    expect(lost.local.getItem('saved')).toBeNull();
    expect(lost.local.getItem('plain')).toBe('write');
    expect(lost.writer.calls).toEqual([]);

    const root = { dataset: {} as Record<string, string> } as unknown as HTMLElement;
    const diagnostics = new (storageClass())();
    recordDurableStorageStatus(root, status, diagnostics);
    expect(root.dataset.durableStorage).toBe('failed');
    expect(root.dataset.durableStorageReason).toMatch(/did not answer/);
    expect(readDiagnosticEvents(diagnostics).map((event) => [event.kind, event.operation])).toEqual([
      ['storage-durability-failed', 'storage'],
    ]);
  });

  it('warms up the native bridge as the first native call, before durable storage opens', () => {
    const main = readFileSync(new URL('../../src/main.ts', import.meta.url), 'utf8');
    const warmUp = main.indexOf('await bridgeWarmUp()');
    const install = main.indexOf('await installAndroidDurableStorage()');
    expect(warmUp).toBeGreaterThan(-1);
    expect(install).toBeGreaterThan(warmUp);
    // Nothing in boot() before the warm-up may talk to native.
    const bootStart = main.indexOf('async function boot()');
    expect(bootStart).toBeGreaterThan(-1);
    const codeBeforeWarmUp = main.slice(bootStart, warmUp).replace(/^\s*\/\/.*$/gm, '');
    expect(codeBeforeWarmUp).not.toMatch(/\.(open|ping|getInfo)\(|installAndroid|await /);
  });

  it('makes the real snippet and settings stores durable, and refuses to acknowledge a delete that did not commit', async () => {
    const { local, writer, dependencies } = harness({ initialized: true, entries: {} });
    await installDurableStorage(dependencies);
    const repository = new HostSnippetRepository(local);

    expect(repository.createSnippet('me@box:22', 'keep', 'echo keep', 'command')).toBe(true);
    expect(repository.createSnippet('me@box:22', 'drop', 'echo drop', 'command')).toBe(true);
    const drop = repository.itemsForHost('me@box:22').find((item) => item.label === 'drop');
    expect(drop).toBeDefined();
    expect(repository.deleteItem('me@box:22', drop!)).toBe(true);
    const committed = JSON.parse(writer.committed.get(HOST_SNIPPETS_STORAGE_KEY) ?? 'null');
    expect(committed.snippets.map((item: { label: string }) => item.label)).toEqual(['keep']);

    persistAppSettings({ ...DEFAULT_APP_SETTINGS, themeChoice: 'gruvbox-dark', terminalFontSize: 16 }, local);
    expect(JSON.parse(writer.committed.get(SETTINGS_STORAGE_KEY) ?? '{}').themeChoice).toBe('gruvbox-dark');
    expect(readAppSettings(local).themeChoice).toBe('gruvbox-dark');

    writer.failWith = 'disk error';
    const keep = repository.itemsForHost('me@box:22').find((item) => item.label === 'keep');
    expect(repository.deleteItem('me@box:22', keep!)).toBe(false);
    expect(repository.error).toMatch(/could not be written/);
    expect(repository.itemsForHost('me@box:22').map((item) => item.label)).toEqual(['keep']);
    expect(JSON.parse(local.getItem(HOST_SNIPPETS_STORAGE_KEY) ?? 'null').snippets).toHaveLength(1);
  });

  it('boots the app only after durable storage is installed, because stores read saved data at import time', () => {
    const main = readFileSync(new URL('../../src/main.ts', import.meta.url), 'utf8');
    expect(main).not.toMatch(/^import\s[^;]*['"]\.\/App\.vue['"]/m);
    // Both shells (#2936) are lazy chunks imported only after the install;
    // neither may be imported statically, and the legacy chunk owns App.vue.
    expect(main).not.toMatch(/^import\s[^;]*['"]\.\/(legacyMain|sharedApp\/main)['"]/m);
    const install = main.indexOf('await installAndroidDurableStorage()');
    const legacy = main.indexOf("await import('./legacyMain')");
    const shared = main.indexOf("await import('./sharedApp/main')");
    expect(install).toBeGreaterThan(-1);
    expect(legacy).toBeGreaterThan(install);
    expect(shared).toBeGreaterThan(install);
    const legacyMain = readFileSync(new URL('../../src/legacyMain.ts', import.meta.url), 'utf8');
    expect(legacyMain).toMatch(/^import App from '\.\/App\.vue';/m);
  });

  it('finds no property-style localStorage writes that would bypass the durable path in this app or the shared core/ui', () => {
    const roots = ['../../src', '../../vendor/pocketshell-core/src', '../../vendor/pocketshell-core/packages/ui/src'];
    const bypass = /localStorage\[|localStorage\.[A-Za-z_$][\w$]*\s*=[^=]|delete\s+localStorage|Object\.assign\(\s*localStorage/;
    const offenders: string[] = [];
    let scanned = 0;
    const walk = (directory: URL) => {
      for (const entry of readdirSync(directory, { withFileTypes: true })) {
        if (entry.name === 'node_modules') continue;
        const child = new URL(entry.name + (entry.isDirectory() ? '/' : ''), directory);
        if (entry.isDirectory()) walk(child);
        else if (/\.(ts|vue)$/.test(entry.name) && !/\.test\.ts$/.test(entry.name)) {
          scanned += 1;
          readFileSync(child, 'utf8').split('\n').forEach((line, index) => {
            if (bypass.test(line)) offenders.push(`${child.pathname}:${index + 1}`);
          });
        }
      }
    };
    for (const root of roots) walk(new URL(root + '/', import.meta.url));
    expect(scanned).toBeGreaterThan(50);
    expect(offenders).toEqual([]);
  });
});
