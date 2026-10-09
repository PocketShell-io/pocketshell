import { describe, expect, it } from 'vitest';
import type { HostEntry } from '@pocketshell/core';
import {
  createGoogleSyncNative,
  parseGoogleSyncStatus,
  type GoogleSyncHttpResponse,
  type GoogleSyncNative,
  type GoogleSyncRequest,
} from '../../src/native/googleSync';
import {
  ACCOUNT_HOSTS_STORAGE_KEY,
  AndroidSync,
  phoneOwnedSyncFields,
  syncFailureText,
  type SyncStorage,
} from '../../src/sync/androidSync';
import { decryptEnvelope, encryptToEnvelope } from '../../src/sync/syncCrypto';
import { createAndroidPlatform } from '../../src/platform/android/androidApi';
import { AndroidHostStore } from '../../src/platform/android/hostStore';

const PASSPHRASE = 'phone and laptop';
const FAST = 1000;

class MemoryStorage implements SyncStorage {
  readonly values = new Map<string, string>();
  getItem(key: string) { return this.values.get(key) ?? null; }
  setItem(key: string, value: string) { this.values.set(key, value); }
  removeItem(key: string) { this.values.delete(key); }
}

/**
 * The sync API as the native plugin exposes it: an encrypted slot with an
 * optimistic version, and scripted refusals. The fake has no token at all —
 * the WebView side never handles one.
 */
class FakeSyncBackend implements GoogleSyncNative {
  signedIn = true;
  email: string | null = 'phone@example.com';
  slot: { version: number; data: string } | null = null;
  readonly puts: Array<{ baseVersion: number; data: string }> = [];
  readonly requests: GoogleSyncRequest[] = [];
  /** Statuses to answer the next requests with instead of the slot logic. */
  readonly scripted: GoogleSyncHttpResponse[] = [];
  /** Before the next PUT is applied, another device writes this plaintext. */
  concurrentWrite: string | null = null;
  signOuts = 0;

  async status() { return { signedIn: this.signedIn, email: this.signedIn ? this.email : null, packageName: 'com.pocketshell.app.preview' }; }
  async signIn() { this.signedIn = true; return this.status(); }
  async signOut() { this.signedIn = false; this.signOuts += 1; return this.status(); }

  async request(request: GoogleSyncRequest): Promise<GoogleSyncHttpResponse> {
    this.requests.push(request);
    const scripted = this.scripted.shift();
    if (scripted) return scripted;
    if (request.method === 'GET') {
      if (!this.slot) return { status: 404, body: '{"message":"not found"}' };
      return { status: 200, body: JSON.stringify({ slot: 'main', version: this.slot.version, data: this.slot.data }) };
    }
    const { data, version } = JSON.parse(request.body) as { data: string; version: number };
    if (this.concurrentWrite !== null) {
      this.slot = { version: (this.slot?.version ?? 0) + 1, data: await encryptToEnvelope(this.concurrentWrite, PASSPHRASE, FAST) };
      this.concurrentWrite = null;
    }
    const current = this.slot?.version ?? 0;
    if (version !== current) return { status: 409, body: JSON.stringify({ message: 'version conflict', currentVersion: current }) };
    this.puts.push({ baseVersion: version, data });
    this.slot = { version: current + 1, data };
    return { status: 200, body: JSON.stringify({ slot: 'main', version: current + 1 }) };
  }

  async seed(plaintext: string, version = 3): Promise<void> {
    this.slot = { version, data: await encryptToEnvelope(plaintext, PASSPHRASE, FAST) };
  }

  async uploaded(): Promise<{ hosts: Array<Record<string, unknown>> }> {
    const last = this.puts.at(-1);
    if (!last) throw new Error('nothing uploaded');
    return JSON.parse(await decryptEnvelope(last.data, PASSPHRASE)) as { hosts: Array<Record<string, unknown>> };
  }
}

function phoneHost(name: string, hostname: string, port = 22, user = 'me'): HostEntry {
  return {
    name, hostname, port, user, identityFile: null, proxyJump: null, forwardAgent: false,
    localForwards: [], remoteForwards: [], fromConfig: false,
  };
}

function setup() {
  const backend = new FakeSyncBackend();
  const storage = new MemoryStorage();
  const sync = new AndroidSync({ native: backend, storage, kdfIterations: FAST, localHosts: async () => [] });
  return { backend, storage, sync };
}

const DESKTOP_ACCOUNT = JSON.stringify({
  hosts: [
    {
      name: 'hetzner', hostname: '135.181.114.209', port: 22, user: 'alexey',
      proxyJump: 'bastion', identityFile: '~/.ssh/id_ed25519',
      localForwards: [{ kind: 'local', listenHost: '', listenPort: 8080, destHost: 'localhost', destPort: 80 }],
      futureDirective: { mode: 'opaque', tags: ['desk', 'keep'] },
    },
    { name: 'laptop-only', hostname: 'laptop.lan', port: 2222, user: 'root' },
  ],
});

describe('Android settings sync adapter', () => {
  it('contributes only the host fields the phone owns, so synthesized defaults never overwrite desktop values', () => {
    expect(phoneOwnedSyncFields(phoneHost('hetzner', '10.0.0.5'))).toEqual({
      name: 'hetzner', hostname: '10.0.0.5', port: 22, user: 'me',
    });
  });

  it('brings desktop hosts onto the phone and keeps desktop-only fields through a phone sync', async () => {
    const { backend, storage, sync } = setup();
    await backend.seed(DESKTOP_ACCOUNT);
    const selections: Array<{ checked: string[]; unticked: string[] }> = [];
    const result = await sync.syncNow({
      localHosts: [phoneHost('hetzner', 'hetzner.phone.example', 2200, 'alexey'), phoneHost('phone-box', '192.168.1.9')],
      selected: ['hetzner'],
      unticked: [],
      passphrase: PASSPHRASE,
      onSelection: (selection) => selections.push(selection),
    });
    expect(result).toMatchObject({ kind: 'synced', version: 4, attempts: 1 });
    // The account-only alias is auto-selected (after the pull, and again after
    // the push); the phone-only one stays unticked.
    expect(selections).toEqual([
      { checked: ['hetzner', 'laptop-only'], unticked: [] },
      { checked: ['hetzner', 'laptop-only'], unticked: [] },
    ]);
    const uploaded = await backend.uploaded();
    expect(uploaded.hosts.map((host) => host.name)).toEqual(['hetzner', 'laptop-only']);
    expect(uploaded.hosts[0]).toEqual({
      name: 'hetzner', hostname: 'hetzner.phone.example', port: 2200, user: 'alexey',
      proxyJump: 'bastion', identityFile: '~/.ssh/id_ed25519',
      localForwards: [{ kind: 'local', listenHost: '', listenPort: 8080, destHost: 'localhost', destPort: 80 }],
      futureDirective: { mode: 'opaque', tags: ['desk', 'keep'] },
    });
    expect(Object.keys(uploaded)).toEqual(['hosts']);
    expect(backend.puts[0].baseVersion).toBe(3);
    // The account copy the phone shows is the uploaded set.
    expect(sync.accountHosts()?.map((host) => host.name)).toEqual(['hetzner', 'laptop-only']);
    expect(sync.accountHosts()?.[1]).toMatchObject({ hostname: 'laptop.lan' });
    // In memory only (#3026): nothing decrypted is written to WebView storage.
    expect(storage.getItem(ACCOUNT_HOSTS_STORAGE_KEY)).toBeNull();
    expect([...storage.values.keys()]).toEqual([]);
    expect(JSON.stringify([...storage.values])).not.toContain('laptop.lan');
  });

  it('creates a fresh account on base version 0 with the selected phone hosts', async () => {
    const { backend, sync } = setup();
    const result = await sync.syncNow({ localHosts: [phoneHost('phone-box', '192.168.1.9')], selected: ['phone-box'], unticked: [], passphrase: PASSPHRASE });
    expect(result).toMatchObject({ kind: 'synced', version: 1 });
    expect(backend.puts[0].baseVersion).toBe(0);
    expect((await backend.uploaded()).hosts).toEqual([{ name: 'phone-box', hostname: '192.168.1.9', port: 22, user: 'me' }]);
  });

  it('re-pulls and re-merges after another device writes first, keeping that device\'s new host', async () => {
    const { backend, sync } = setup();
    await backend.seed(JSON.stringify({ hosts: [{ name: 'hetzner', hostname: 'h', port: 22, user: 'a' }] }));
    backend.concurrentWrite = JSON.stringify({ hosts: [
      { name: 'hetzner', hostname: 'h', port: 22, user: 'a' },
      { name: 'new-from-web', hostname: 'web.example', port: 22, user: 'w' },
    ] });
    const result = await sync.syncNow({ localHosts: [phoneHost('phone-box', 'p')], selected: ['phone-box'], unticked: [], passphrase: PASSPHRASE });
    expect(result).toMatchObject({ kind: 'synced', attempts: 2, version: 5 });
    expect((await backend.uploaded()).hosts.map((host) => host.name)).toEqual(['phone-box', 'hetzner', 'new-from-web']);
  });

  it('never uploads over unreadable account data, a wrong passphrase, or an empty selection', async () => {
    const { backend, sync } = setup();
    await backend.seed('{"schemaVersion":2,"hosts":[]}');
    const invalid = await sync.syncNow({ localHosts: [phoneHost('a', 'a')], selected: ['a'], unticked: [], passphrase: PASSPHRASE });
    expect(invalid).toEqual({ kind: 'invalid-payload', reason: 'unsupported-version' });
    if (invalid.kind !== 'synced') {
      expect(syncFailureText(invalid, 1)).toBe('The account holds sync data this version cannot read, so nothing was uploaded.');
    }

    await backend.seed(DESKTOP_ACCOUNT);
    const wrong = await sync.syncNow({ localHosts: [phoneHost('a', 'a')], selected: ['a'], unticked: [], passphrase: 'not it' });
    expect(wrong).toEqual({ kind: 'error', stage: 'pull', message: 'Wrong sync passphrase, or the account data is corrupted.' });

    const empty = await setup().sync.syncNow({ localHosts: [], selected: [], unticked: [], passphrase: PASSPHRASE });
    expect(empty).toEqual({ kind: 'empty-selection' });
    expect(backend.puts).toHaveLength(0);
  });

  it('turns sync API refusals into plain words and refuses an envelope over the 8 KB limit before sending', async () => {
    const { backend, sync } = setup();
    backend.scripted.push({ status: 401, body: '{"message":"Unauthorized"}' });
    await expect(sync.pull('main', PASSPHRASE)).rejects.toThrow('did not accept this Google sign-in');
    backend.scripted.push({ status: 403, body: '{"message":"forbidden"}' });
    await expect(sync.pull('main', PASSPHRASE)).rejects.toThrow('not allowed to use the sync service');
    backend.scripted.push({ status: 500, body: '{"message":"boom"}' });
    await expect(sync.pull('main', PASSPHRASE)).rejects.toThrow('The sync service refused the request: boom');
    backend.scripted.push({ status: 200, body: '{"version":"x"}' });
    await expect(sync.pull('main', PASSPHRASE)).rejects.toThrow('unreadable response');

    const before = backend.requests.length;
    const big = JSON.stringify({ hosts: Array.from({ length: 200 }, (_, index) => ({ name: `host-${index}`, hostname: `h${index}.example.org` })) });
    expect(await sync.push('main', big, PASSPHRASE, 0)).toEqual({ kind: 'error', message: 'The encrypted host list is larger than the 8 KB sync limit.' });
    expect(backend.requests.length).toBe(before);
  });

  it('signing out deletes the native sign-in and the cached account copy', async () => {
    const { backend, storage, sync } = setup();
    await backend.seed(DESKTOP_ACCOUNT);
    await sync.syncNow({ localHosts: [], selected: ['hetzner'], unticked: [], passphrase: PASSPHRASE });
    expect(sync.accountHosts()).not.toBeNull();
    await sync.signOut();
    expect(backend.signOuts).toBe(1);
    expect(storage.getItem(ACCOUNT_HOSTS_STORAGE_KEY)).toBeNull();
    expect(sync.accountHosts()).toBeNull();
    expect((await sync.status()).signedIn).toBe(false);
  });

  it('serves the shared app\'s api.sync group from the same adapter', async () => {
    const { backend, sync } = setup();
    await backend.seed(DESKTOP_ACCOUNT);
    const platform = createAndroidPlatform({
      createController: () => { throw new Error('not used'); },
      hosts: new AndroidHostStore({ storage: new MemoryStorage(), readLegacyHosts: async () => [] }),
      backgroundGraceMs: () => 60_000,
      addHostRoute: '/hosts/new',
      sync: sync.api(),
    });
    const api = platform.api.sync;
    expect(await api.status()).toEqual({ loggedIn: true, email: 'phone@example.com', keychainAvailable: true });
    expect(await api.login()).toBe('phone@example.com');
    const pulled = await api.pull('main', PASSPHRASE);
    expect(pulled.kind).toBe('ok');
    expect((await api.accountHosts())?.map((host) => host.name)).toEqual(['hetzner', 'laptop-only']);
    expect(await api.push('main', '{"hosts":[{"name":"x","hostname":"y"}]}', PASSPHRASE, 3)).toEqual({ kind: 'ok', version: 4 });
    expect(await api.applyHosts([])).toEqual({ added: [] });
    await api.logout();
    expect((await api.status()).loggedIn).toBe(false);
    platform.dispose();
  });

  it('accepts only signed-in state from the native plugin, never a token field', async () => {
    const leaked = parseGoogleSyncStatus({ signedIn: true, email: 'a@b.c', packageName: 'p', idToken: 'eyJ.secret.sig' });
    expect(leaked).toEqual({ signedIn: true, email: 'a@b.c', packageName: 'p' });
    expect(JSON.stringify(leaked)).not.toContain('secret');
    expect(() => parseGoogleSyncStatus({ signedIn: 'yes', packageName: 'p' })).toThrow('Google sign-in returned an unexpected status.');
    const native = createGoogleSyncNative({
      status: async () => ({ signedIn: false, email: 'stale@example.com', packageName: 'p' }),
      signIn: async () => ({}),
      signOut: async () => ({ signedIn: false, packageName: 'p' }),
      request: async () => ({ status: 'nope' }),
      addListener: async () => ({ remove: async () => undefined }),
      removeAllListeners: async () => undefined,
    } as never);
    expect(await native.status()).toEqual({ signedIn: false, email: null, packageName: 'p' });
    await expect(native.signIn()).rejects.toThrow('Google sign-in returned an unexpected status.');
    await expect(native.request({ method: 'GET', slot: 'main' })).rejects.toThrow('invalid response');
  });
});
