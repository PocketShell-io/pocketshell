import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it, vi } from 'vitest';
import { createFakeNativeBridge, installFakeNativeBridge, DevPluginError } from '../../src/dev/browser/nativeBridge';
import {
  createAppPlugin,
  createBridgeReadyPlugin,
  createCrashReportStore,
  createDurableStoragePlugin,
  createInstalledDataMigrationPlugin,
  createKeyboardSimulation,
  durableStorageWriter,
} from '../../src/dev/browser/devicePlugins';
import { createSpeechPlugin, STUB_DICTATION_PHRASE } from '../../src/dev/browser/speechPlugin';
import { createDocumentPlugin, type PickedBrowserFile } from '../../src/dev/browser/documentPlugin';
import {
  createKeyVaultPlugin,
  createMockKeyVaultBackend,
  MOCK_SEED_KEY,
  parseOpenSshPrivateKey,
  publicKeyAlgorithm,
  sha256Fingerprint,
} from '../../src/dev/browser/keyVaultPlugin';
import { DEV_HOSTS_STORAGE_KEY, seedSavedHosts } from '../../src/dev/browser/seedHosts';
import { traceSshPlugin } from '../../src/dev/browser/trace';
import { bridgeProtocols, createBridgeClient, takeBridgeToken } from '../../src/dev/browser/liveBridge';
import { parseArgs, parseHostSpec } from '../../scripts/dev-browser.mjs';
import { ANDROID_HOSTS_STORAGE_KEY, AndroidHostStore } from '../../src/platform/android/hostStore';
import { isSshKeyMetadata } from '../../src/native/sshKeyVault';
import { DURABLE_STORAGE_INTERFACE, installDurableStorage } from '../../src/native/durableStorage';
import viteConfig, { devBrowserShell, isDevBrowserMode } from '../../vite.config';

const repoRoot = path.resolve(__dirname, '../..');

function memoryStorage(initial: Record<string, string> = {}) {
  const data = new Map(Object.entries(initial));
  return {
    get length() { return data.size; },
    key: (index: number) => [...data.keys()][index] ?? null,
    getItem: (key: string) => data.get(key) ?? null,
    setItem: (key: string, value: string) => { data.set(key, value); },
    removeItem: (key: string) => { data.delete(key); },
    clear: () => data.clear(),
    data,
  };
}

function fakeDocument() {
  const listeners = new Map<string, Array<() => void>>();
  return {
    activeElement: null as Element | null,
    hidden: false,
    addEventListener(type: string, listener: () => void) {
      listeners.set(type, [...(listeners.get(type) ?? []), listener]);
    },
    fire(type: string) {
      for (const listener of listeners.get(type) ?? []) listener();
    },
  };
}

function editable(tag: string, type?: string): Element {
  return {
    tagName: tag.toUpperCase(),
    getAttribute: (name: string) => (name === 'type' ? type ?? null : null),
    isContentEditable: false,
    blur: vi.fn(),
  } as unknown as Element;
}

function pickedFile(name: string, text: string): PickedBrowserFile {
  const bytes = new TextEncoder().encode(text);
  return { name, type: 'text/plain', size: bytes.length, arrayBuffer: async () => bytes.slice().buffer };
}

describe('browser dev mode shims', () => {
  it('routes Capacitor native promises, callbacks and listener removal through the fake bridge', async () => {
    const ping = vi.fn((options: Record<string, unknown>) => ({ requestId: options.requestId }));
    const bridge = createFakeNativeBridge({ BridgeReady: { methods: { ping } } });
    expect(bridge.headers).toEqual([{
      name: 'BridgeReady',
      methods: [
        { name: 'ping', rtype: 'promise' },
        { name: 'addListener', rtype: 'callback' },
        { name: 'removeListener', rtype: 'promise' },
        { name: 'removeAllListeners', rtype: 'promise' },
      ],
    }]);
    await expect(bridge.nativePromise('BridgeReady', 'ping', { requestId: 'r1' })).resolves.toEqual({ requestId: 'r1' });
    const events: unknown[] = [];
    const callbackId = bridge.nativeCallback('BridgeReady', 'addListener', { eventName: 'tick' }, (data) => events.push(data));
    expect(bridge.emit('BridgeReady', 'tick', { n: 1 })).toBe(1);
    await bridge.nativePromise('BridgeReady', 'removeListener', { eventName: 'tick', callbackId });
    expect(bridge.emit('BridgeReady', 'tick', { n: 2 })).toBe(0);
    expect(events).toEqual([{ n: 1 }]);
    await expect(bridge.nativePromise('BridgeReady', 'missing', {})).rejects.toMatchObject({ code: 'UNIMPLEMENTED' });
    const failing = createFakeNativeBridge({ X: { methods: { boom: () => { throw new DevPluginError('nope', 'HOST_KEY_REJECTED', { a: 1 }); } } } });
    await expect(failing.nativePromise('X', 'boom', {})).rejects.toMatchObject({ message: 'nope', code: 'HOST_KEY_REJECTED', data: { a: 1 } });

    const target: { androidBridge?: unknown; Capacitor?: Record<string, unknown> } = {};
    installFakeNativeBridge(target, bridge);
    expect(target.androidBridge).toBeTruthy();
    expect(target.Capacitor?.PluginHeaders).toBe(bridge.headers);
    expect(() => installFakeNativeBridge({ Capacitor: { registerPlugin: () => undefined } }, bridge)).toThrow(/before @capacitor\/core/u);
  });

  it('serves bridge ping, durable storage, crash reports and an empty 0.5.x migration snapshot', async () => {
    expect(createBridgeReadyPlugin().methods.ping({ requestId: 'w1' })).toEqual({ requestId: 'w1' });
    const storage = memoryStorage({ 'pocketshell.js.settings.v1': '{"theme":"dark"}' });
    const opened = createDurableStoragePlugin(storage).methods.open({}) as { token: string; initialized: boolean; entries: Record<string, string> };
    expect(opened.initialized).toBe(true);
    expect(opened.entries).toEqual({ 'pocketshell.js.settings.v1': '{"theme":"dark"}' });
    // The app's real durable-storage install accepts the dev plugin + writer.
    const prototype = { getItem: storage.getItem, setItem: storage.setItem, removeItem: storage.removeItem, clear: storage.clear };
    const status = await installDurableStorage({
      storage, prototype: prototype as never, writer: durableStorageWriter, open: async () => opened,
    });
    expect(status).toMatchObject({ state: 'native-durable' });
    expect(DURABLE_STORAGE_INTERFACE).toBe('PocketShellDurableStorage');

    const crashes = createCrashReportStore(['boom']);
    const listed = crashes.plugin.methods.list({}) as { reports: Array<{ id: string; fileName: string; text: string }> };
    expect(listed.reports).toHaveLength(1);
    expect(listed.reports[0].fileName).toBe(`${listed.reports[0].id}.txt`);
    expect(crashes.plugin.methods.remove({ id: listed.reports[0].id })).toEqual({ id: listed.reports[0].id, removed: true });
    expect(crashes.plugin.methods.clear({})).toEqual({ removed: 0 });

    const snapshot = createInstalledDataMigrationPlugin(() => 2.625).methods.readLegacyInstalledData({}) as Record<string, any>;
    expect(snapshot.schemaVersion).toBe(1);
    expect(snapshot.environment.applicationId).toMatch(/^com\.pocketshell\.app(?:\.[A-Za-z0-9_]+)*$/u);
    expect(snapshot.database).toEqual({ present: false, tables: {} });
    expect([snapshot.assets, snapshot.nativeFiles]).toEqual([[], []]);
  });

  it('simulates the soft keyboard from focus and the Android back button from the toolbar', async () => {
    const doc = fakeDocument();
    const bridge = createFakeNativeBridge({});
    const keyboard = createKeyboardSimulation(() => bridge, doc as never);
    const app = createAppPlugin(() => bridge, doc as never);
    const imeEvents: unknown[] = [];
    bridge.nativeCallback('KeyboardInsets', 'addListener', { eventName: 'imeInsetsChanged' }, (data) => imeEvents.push(data));
    expect(keyboard.plugin.methods.getState({})).toEqual({ supported: true, imeVisible: false, safeBottomDp: 0 });
    const input = editable('textarea');
    doc.activeElement = input;
    doc.fire('focusin');
    expect(imeEvents).toEqual([{ supported: true, imeVisible: true, safeBottomDp: 0 }]);
    keyboard.plugin.methods.hideIme({});
    expect((input as unknown as { blur: ReturnType<typeof vi.fn> }).blur).toHaveBeenCalled();
    doc.activeElement = editable('input', 'checkbox');
    await new Promise((resolve) => setTimeout(resolve, 5));
    expect(imeEvents.at(-1)).toEqual({ supported: true, imeVisible: false, safeBottomDp: 0 });
    keyboard.override(true);
    expect(keyboard.state().imeVisible).toBe(true);

    const backs: unknown[] = [];
    bridge.nativeCallback('App', 'addListener', { eventName: 'backButton' }, (data) => backs.push(data));
    expect(app.back()).toBe(1);
    expect(backs).toEqual([{ canGoBack: false }]);
    expect(app.methods.getInfo({})).toMatchObject({ id: 'com.pocketshell.app.dev' });
    const states: unknown[] = [];
    bridge.nativeCallback('App', 'addListener', { eventName: 'appStateChange' }, (data) => states.push(data));
    doc.hidden = true;
    doc.fire('visibilitychange');
    expect(states).toEqual([{ isActive: false }]);
  });

  it('runs the stub recognizer once per dictation session and supports injected test events', async () => {
    vi.useFakeTimers();
    try {
      const bridge = createFakeNativeBridge({});
      const plugin = createSpeechPlugin({ bridge: () => bridge, webSpeech: null });
      const events: Array<Record<string, unknown>> = [];
      bridge.nativeCallback('SpeechRecognition', 'addListener', { eventName: 'dictationEvent' }, (data) => events.push(data as Record<string, unknown>));
      expect(plugin.methods.getCapabilities({})).toEqual({ speechRecognitionAvailable: true, microphonePermissionGranted: true });
      expect(plugin.methods.startDictation({ requestId: 'd1', silenceWindowMs: 2000 })).toEqual({ requestId: 'd1', started: true });
      await vi.advanceTimersByTimeAsync(1_300);
      expect(events.map((event) => event.type)).toEqual(['partial', 'result']);
      expect(events[1]).toMatchObject({ requestId: 'd1', text: STUB_DICTATION_PHRASE });
      plugin.methods.startDictation({ requestId: 'd2', silenceWindowMs: 2000 });
      await vi.advanceTimersByTimeAsync(2_100);
      expect(events.at(-1)).toMatchObject({ requestId: 'd2', type: 'recoverable', code: 'speech-timeout' });
      expect(plugin.methods.cancelDictation({ requestId: 'd2' })).toMatchObject({ cancelled: false });
      plugin.methods.startDictation({ requestId: 'd3', testMode: true });
      expect(plugin.methods.injectTestDictationEvent({ type: 'result', text: 'injected' })).toMatchObject({ requestId: 'd3', emitted: true });
      expect(events.at(-1)).toMatchObject({ requestId: 'd3', type: 'result', text: 'injected' });
    } finally {
      vi.useRealTimers();
    }
  });

  it('picks files into opaque IDs, reads them in chunks and saves created documents as downloads', async () => {
    const bridge = createFakeNativeBridge({});
    const saved: Array<{ name: string; bytes: Uint8Array }> = [];
    let next: PickedBrowserFile[] | null = [pickedFile('notes.txt', 'hello world')];
    const plugin = createDocumentPlugin({ bridge: () => bridge, pick: async () => next, save: (name, _mime, bytes) => saved.push({ name, bytes }) });
    const picked = await plugin.methods.pickFiles({ mimeType: '*/*', multiple: true }) as { cancelled: boolean; files: Array<{ fileId: string; sizeBytes: number }> };
    expect(picked.cancelled).toBe(false);
    expect(picked.files[0].sizeBytes).toBe(11);
    const first = await plugin.methods.readPickedFileChunk({ fileId: picked.files[0].fileId, offset: 0, maxBytes: 5 }) as Record<string, unknown>;
    expect(first).toMatchObject({ offset: 0, bytesRead: 5, eof: false, base64: btoa('hello') });
    const rest = await plugin.methods.readPickedFileChunk({ fileId: picked.files[0].fileId, offset: 5, maxBytes: 100 }) as Record<string, unknown>;
    expect(rest).toMatchObject({ bytesRead: 6, eof: true, base64: btoa(' world') });
    expect(plugin.methods.releasePickedFile({ fileId: picked.files[0].fileId })).toEqual({ released: true });
    next = null;
    await expect(plugin.methods.pickFiles({ mimeType: '*/*', multiple: false })).resolves.toEqual({ cancelled: true, files: [] });

    const created = plugin.methods.createDocument({ name: 'out.txt', mimeType: 'text/plain' }) as { fileId: string };
    plugin.methods.writeCreatedDocumentChunk({ fileId: created.fileId, offset: 0, base64: btoa('abc') });
    plugin.methods.writeCreatedDocumentChunk({ fileId: created.fileId, offset: 3, base64: btoa('def') });
    expect(plugin.methods.completeCreatedDocument({ fileId: created.fileId, expectedBytes: 6 })).toMatchObject({ bytesWritten: 6, complete: true });
    expect(saved).toHaveLength(1);
    expect(new TextDecoder().decode(saved[0].bytes)).toBe('abcdef');

    const shares: unknown[] = [];
    bridge.nativeCallback('DocumentContent', 'addListener', { eventName: 'shareReceived' }, (data) => shares.push(data));
    expect(plugin.share({ text: 'shared text' })).toBe(1);
    expect(shares[0]).toMatchObject({ action: 'android.intent.action.SEND', text: 'shared text', files: [] });
  });

  it('keeps an in-memory key vault whose metadata passes the app validator and matches ssh-keygen fingerprints', async () => {
    const pem = readFileSync(path.join(repoRoot, 'tests/docker/test_key'), 'utf8');
    const parsed = parseOpenSshPrivateKey(pem);
    expect(parsed).not.toBeNull();
    expect(publicKeyAlgorithm(parsed!.publicBlob)).toBe('ssh-ed25519');
    // `ssh-keygen -lf tests/docker/test_key.pub` → the same SHA256 the bridge and Android present.
    const pub = readFileSync(path.join(repoRoot, 'tests/docker/test_key.pub'), 'utf8').trim().split(/\s+/u)[1];
    const publicBlob = Uint8Array.from(atob(pub), (char) => char.charCodeAt(0));
    expect(await sha256Fingerprint(parsed!.publicBlob)).toBe(await sha256Fingerprint(publicBlob));
    expect(parsed!.encrypted).toBe(false);

    const backend = createMockKeyVaultBackend();
    const vault = createKeyVaultPlugin(backend, async () => [pickedFile('test_key', pem)]);
    expect(isSshKeyMetadata(MOCK_SEED_KEY)).toBe(true);
    const picked = await vault.methods.pickKeyDocument({}) as { cancelled: boolean; documentId: string };
    const imported = await vault.methods.importPickedKey({ documentId: picked.documentId, label: 'fixture' });
    expect(isSshKeyMetadata(imported)).toBe(true);
    expect(imported).toMatchObject({ algorithm: 'ssh-ed25519', passphraseRequired: false, fingerprintSha256: await sha256Fingerprint(publicBlob) });
    const generated = await vault.methods.generateKey({ label: 'gen', algorithm: 'Ed25519' });
    expect(isSshKeyMetadata(generated)).toBe(true);
    const listed = await vault.methods.listKeys({}) as { keys: Array<{ handleId: string; fingerprintSha256: string }> };
    expect(listed.keys).toHaveLength(3);
    const target = listed.keys[1];
    expect(await vault.methods.deleteKey({ handleId: target.handleId, fingerprintSha256: 'SHA256:wrong' })).toEqual({ deleted: false });
    expect(await vault.methods.deleteKey({ handleId: target.handleId, fingerprintSha256: target.fingerprintSha256 })).toEqual({ deleted: true });
    expect(() => vault.methods.importDocumentUri({ uri: 'content://x' })).toThrow(/Android/u);
  });

  it('seeds dev hosts into the Android host store without overwriting saved ones', async () => {
    expect(DEV_HOSTS_STORAGE_KEY).toBe(ANDROID_HOSTS_STORAGE_KEY);
    const storage = memoryStorage();
    const seed = { name: 'fixture', hostname: '127.0.0.1', port: 2222, user: 'testuser', keyHandleId: MOCK_SEED_KEY.handleId };
    expect(seedSavedHosts(storage, [seed])).toBe(1);
    expect(seedSavedHosts(storage, [{ ...seed, hostname: 'changed' }])).toBe(0);
    expect(seedSavedHosts(storage, [{ ...seed, name: 'no-key', keyHandleId: '' }])).toBe(0);
    const hosts = new AndroidHostStore({ storage, readLegacyHosts: async () => [] });
    await expect(hosts.resolve({ host: '127.0.0.1', port: 2222, user: 'testuser', hostAlias: 'fixture' })).resolves.toMatchObject({
      hostId: 'testuser@127.0.0.1:2222',
      credential: { kind: 'key-handle', handleId: MOCK_SEED_KEY.handleId },
    });
  });

  it('traces SSH calls by method and command but never PTY bytes', async () => {
    const lines: string[] = [];
    const traced = traceSshPlugin({ methods: {
      exec: () => ({ exitCode: 0 }),
      writePty: () => ({ ok: true }),
      connect: () => { throw new DevPluginError('x', 'AUTH_FAILED'); },
    } }, (line) => lines.push(line));
    await traced.methods.exec({ command: 'pocketshell sessions list --json' });
    await traced.methods.writePty({ dataBase64: btoa('my secret password') });
    await expect(traced.methods.connect({ credential: { kind: 'password', password: 'hunter2' } })).rejects.toThrow();
    expect(lines).toHaveLength(2);
    expect(lines[0]).toMatch(/exec pocketshell sessions list --json ok exit=0/u);
    expect(lines[1]).toMatch(/connect failed AUTH_FAILED/u);
    expect(lines.join('\n')).not.toMatch(/hunter2|secret|bXkg/u);
  });

  it('reports a dropped bridge socket as lost connections and rejects its pending calls', async () => {
    const bridge = createFakeNativeBridge({});
    const lost: unknown[] = [];
    bridge.nativeCallback('SshCapability', 'addListener', { eventName: 'connectionState' }, (data) => lost.push(data));
    const sockets: Array<Record<string, any>> = [];
    const offered: string[][] = [];
    const client = createBridgeClient({
      url: 'ws://127.0.0.1/x',
      protocols: bridgeProtocols('t0ken'),
      bridge: () => bridge,
      openSocket: (_url, protocols) => {
        offered.push(protocols);
        const socket: Record<string, any> = { readyState: 1, sent: [] as string[], close: vi.fn(), onopen: null, onclose: null, onerror: null, onmessage: null };
        socket.send = (frame: string) => socket.sent.push(frame);
        sockets.push(socket);
        queueMicrotask(() => socket.onopen?.({}));
        return socket as never;
      },
    });
    const connecting = client.call('ssh.connect', { requestId: 'r1' });
    await new Promise((resolve) => setTimeout(resolve, 0));
    const frame = JSON.parse(sockets[0].sent[0]);
    expect(frame).toMatchObject({ method: 'ssh.connect', params: { requestId: 'r1' } });
    expect(offered).toEqual([['pocketshell-dev-bridge.v1', 'token.t0ken']]);
    sockets[0].onmessage({ data: JSON.stringify({ id: frame.id, result: { connectionId: 'c1', generationId: 'g1' } }) });
    await expect(connecting).resolves.toEqual({ connectionId: 'c1', generationId: 'g1' });
    const hanging = client.call('ssh.exec', { requestId: 'r2' });
    await new Promise((resolve) => setTimeout(resolve, 0));
    sockets[0].onclose({});
    await expect(hanging).rejects.toMatchObject({ code: 'CONNECTION_LOST' });
    expect(lost).toEqual([{ connectionId: 'c1', generationId: 'g1', state: 'lost', reason: 'dev bridge disconnected' }]);
  });

  it('takes the live bridge token from the URL fragment, keeps it per tab and clears it from the address bar', () => {
    const stored = new Map<string, string>();
    const storage = { getItem: (key: string) => stored.get(key) ?? null, setItem: (key: string, value: string) => void stored.set(key, value) };
    const replaced: string[] = [];
    const token = takeBridgeToken({ hash: '#devBridgeToken=abc_DEF-123&other=1', pathname: '/', search: '?shell=shared' }, storage, (url) => replaced.push(url));
    expect(token).toBe('abc_DEF-123');
    expect(replaced).toEqual(['/?shell=shared#other=1']);
    // A reload (no fragment any more) still finds it in this tab.
    expect(takeBridgeToken({ hash: '', pathname: '/', search: '' }, storage, (url) => replaced.push(url))).toBe('abc_DEF-123');
    expect(replaced).toHaveLength(1);
    expect(takeBridgeToken({ hash: '', pathname: '/', search: '' }, { getItem: () => null, setItem: () => undefined }, () => undefined)).toBe('');
  });

  it('injects the shim only into the mock/live dev server and refuses to build in those modes', () => {
    expect(isDevBrowserMode('mock')).toBe(true);
    expect(isDevBrowserMode('live')).toBe(true);
    expect(isDevBrowserMode('production')).toBe(false);
    const plugin = devBrowserShell('live', { POCKETSHELL_DEV_BRIDGE_TOKEN: 'tok-must-not-be-served', POCKETSHELL_DEV_SEED_HOSTS: '[]' });
    expect(plugin.apply).toBe('serve');
    const tags = (plugin.transformIndexHtml as unknown as { handler: () => Array<{ attrs: Record<string, string>; children?: string }> }).handler();
    expect(tags.map((tag) => tag.attrs.src ?? tag.attrs.id)).toEqual(['pocketshell-dev-config', '/src/dev/browser/install.ts']);
    expect(JSON.parse(tags[0].children ?? '{}')).toEqual({ mode: 'live', bridgePath: '/__pocketshell-dev-bridge', seedHosts: [] });
    expect(tags[0].children).not.toContain('tok-must-not-be-served');
    const configFn = viteConfig as unknown as (env: { command: string; mode: string }) => { plugins: Array<{ name?: string }> };
    expect(() => configFn({ command: 'build', mode: 'mock' })).toThrow(/never produce a build/u);
    expect(() => configFn({ command: 'build', mode: 'live' })).toThrow(/never produce a build/u);
    const production = configFn({ command: 'build', mode: 'production' });
    expect(production.plugins.flat().map((entry) => entry?.name)).not.toContain('pocketshell-dev-browser-shell');
    const mockServe = configFn({ command: 'serve', mode: 'mock' });
    expect(mockServe.plugins.flat().map((entry) => entry?.name)).toContain('pocketshell-dev-browser-shell');
  });

  it('parses the dev launcher options, including pnpm\'s forwarded separator', () => {
    expect(parseHostSpec('testuser@127.0.0.1:2222=fixture')).toEqual({ name: 'fixture', hostname: '127.0.0.1', port: 2222, user: 'testuser' });
    expect(parseHostSpec('alexey@hetzner')).toEqual({ name: 'hetzner:22', hostname: 'hetzner', port: 22, user: 'alexey' });
    expect(() => parseHostSpec('nohost')).toThrow(/user@hostname/u);
    const live = parseArgs(['live', '--', '--host', 'u@h:2', '--identity', 'k', '--', '--open'], {});
    expect(live).toMatchObject({ mode: 'live', hosts: [{ hostname: 'h', port: 2 }], viteArgs: ['--open'] });
    expect(live.identities[0]).toBe(path.resolve('k'));
    expect(parseArgs(['live'], { POCKETSHELL_DEV_HOSTS: 'a@b:3,c@d', POCKETSHELL_DEV_IDENTITY: 'x' }).hosts).toHaveLength(2);
    expect(() => parseArgs(['mock', '--host', 'u@h'], {})).toThrow(/dev:live/u);
    expect(() => parseArgs(['build'], {})).toThrow(/usage/u);
  });
});
