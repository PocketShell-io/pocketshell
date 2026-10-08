import { describe, expect, it, vi } from 'vitest';
import { createSSRApp } from 'vue';
import { renderToString } from 'vue/server-renderer';
import { createPinia, setActivePinia } from 'pinia';
import { createMemoryHistory, createRouter } from 'vue-router';
import { provideApi } from '@ui/app/ipc';
import { useConnectionStore } from '@ui/app/stores/connection';
import { useSyncStore } from '@ui/app/stores/sync';
import { createAppRoutes } from '@ui/app/routes';
import HostPickerView from '@ui/app/views/HostPickerView.vue';
import { ConnectionController, type HostEntry } from '@pocketshell/core';
import type { GoogleSyncHttpResponse, GoogleSyncNative, GoogleSyncRequest } from '@/native/googleSync';
import type { SshKeyMetadata } from '@/native/sshKeyVault';
import { ACCOUNT_HOSTS_STORAGE_KEY, AndroidSync, keepAccountAliases, KNOWN_ALIASES_STORAGE_KEY } from '@/sync/androidSync';
import { encryptToEnvelope } from '@/sync/syncCrypto';
import { createAndroidPlatform } from '@/platform/android/androidApi';
import { AndroidHostStore, ANDROID_HOSTS_STORAGE_KEY } from '@/platform/android/hostStore';
import { createAccountHostKeys, matchAccountHost } from '@/platform/android/accountHosts';
import { createLocalTrustStore } from '@/platform/android/trustStore';
import { createAccountHostKeyPrompt } from '@/sharedApp/accountHostKey';
import { ACCOUNT_ROUTE, createSharedAppRouter, openAccountRoute } from '@/sharedApp/router';
import { FakeNative, HOST_KEY, settle } from './support/androidFakeNative';
import { createGoogleSyncPlugin, DEV_SYNC_ACCOUNT, DEV_SYNC_PASSPHRASE } from '@/dev/browser/googleSyncPlugin';
import { createGoogleSyncNative } from '@/native/googleSync';

/**
 * Issue #3063 (with #2967 and #3026 point 3): the shared app's sync group on
 * Android, the picker's account button and "From your account" group, and
 * the phone-side key prompt for an account host.
 */

const PASSPHRASE = 'phone and laptop';
const FAST = 1000;
const ACCOUNT = JSON.stringify({
  hosts: [
    { name: 'hetzner', hostname: '135.181.114.209', port: 22, user: 'alexey', identityFile: '~/.ssh/id_ed25519' },
    { name: 'fixture', hostname: 'fixture', port: 2222, user: 'u' },
  ],
});
const KEY: SshKeyMetadata = {
  handleId: '00000000-0000-4000-8000-000000000001',
  label: 'phone key',
  algorithm: 'ssh-ed25519',
  fingerprintSha256: 'SHA256:phonekey',
  passphraseRequired: false,
  createdAt: 1,
};

const OTHER_KEY: SshKeyMetadata = { ...KEY, handleId: '00000000-0000-4000-8000-000000000002', label: 'work key', fingerprintSha256: 'SHA256:work' };

class Storage {
  readonly values = new Map<string, string>();
  getItem(key: string) { return this.values.get(key) ?? null; }
  setItem(key: string, value: string) { this.values.set(key, value); }
  removeItem(key: string) { this.values.delete(key); }
  dump() { return JSON.stringify([...this.values]); }
}

/** The native GoogleSync plugin's shape: signed-in state and the sync API, never a token. */
class FakeGoogleSync implements GoogleSyncNative {
  signedIn = false;
  slot: { version: number; data: string } | null = null;
  readonly requests: GoogleSyncRequest[] = [];
  async status() { return { signedIn: this.signedIn, email: this.signedIn ? 'phone@example.com' : null, packageName: 'p' }; }
  async signIn() { this.signedIn = true; return this.status(); }
  async signOut() { this.signedIn = false; return this.status(); }
  async request(request: GoogleSyncRequest): Promise<GoogleSyncHttpResponse> {
    this.requests.push(request);
    if (request.method === 'GET') {
      return this.slot ? { status: 200, body: JSON.stringify({ version: this.slot.version, data: this.slot.data }) } : { status: 404, body: '{}' };
    }
    const { data, version } = JSON.parse(request.body) as { data: string; version: number };
    this.slot = { version: version + 1, data };
    return { status: 200, body: JSON.stringify({ version: version + 1 }) };
  }
}

function setup(options: { opened?: string[] } = {}) {
  const google = new FakeGoogleSync();
  const storage = new Storage();
  const sync = new AndroidSync({ native: google, storage, kdfIterations: FAST, selection: memorySelection(), localHosts: async () => [] });
  const syncApi = sync.api();
  const native = new FakeNative();
  const hosts = new AndroidHostStore({ storage, readLegacyHosts: async () => [] });
  const keys = { list: async () => [KEY, OTHER_KEY], pickAndImport: async () => null };
  const prompt = createAccountHostKeyPrompt({ keys, hosts });
  const trust = createLocalTrustStore(storage);
  void trust.record(`u@fixture:2222`, { kind: 'wire-key', ...HOST_KEY });
  const created = createAndroidPlatform({
    createController: () => new ConnectionController({ capability: native.capability(), trustStore: trust, retryDelaysMs: [0] }),
    hosts,
    backgroundGraceMs: () => 60_000,
    addHostRoute: '/android/hosts',
    sync: syncApi,
    openAccount: async () => { options.opened?.push('account'); },
    accountHosts: createAccountHostKeys({ accountHosts: () => syncApi.accountHosts(), ask: prompt.ask }),
  });
  return { google, storage, sync, syncApi, native, hosts, prompt, created };
}

async function renderPicker(): Promise<string> {
  const router = createRouter({ history: createMemoryHistory(), routes: createAppRoutes() });
  await router.push('/');
  await router.isReady();
  const app = createSSRApp(HostPickerView);
  app.use(router);
  // The picker reads the window's launch request (core 2e60b8a,
  // readLaunchRequest); this server render has no window, so give it a plain
  // launch with no query.
  vi.stubGlobal('window', { location: { search: '' } });
  try {
    return await renderToString(app);
  } finally {
    vi.unstubAllGlobals();
  }
}

describe('Android account sync group and shared picker account hosts', () => {
  it('maps native sign-in onto the shared status, login and logout', async () => {
    const { google, syncApi } = setup();
    expect(await syncApi.status()).toEqual({ loggedIn: false, email: null, keychainAvailable: true });
    expect(await syncApi.login()).toBe('phone@example.com');
    expect(await syncApi.status()).toEqual({ loggedIn: true, email: 'phone@example.com', keychainAvailable: true });
    await syncApi.logout();
    expect(google.signedIn).toBe(false);
    expect((await syncApi.status()).loggedIn).toBe(false);
  });

  it('unlocks the account copy with the passphrase and serves it as picker rows; a fresh account unlocks empty', async () => {
    const { google, syncApi } = setup();
    google.signedIn = true;
    expect(await syncApi.accountHosts()).toBeNull();
    expect((await syncApi.pull('main', PASSPHRASE)).kind).toBe('absent');
    expect(await syncApi.accountHosts()).toEqual([]);
    google.slot = { version: 2, data: await encryptToEnvelope(ACCOUNT, PASSPHRASE, FAST) };
    await expect(syncApi.pull('main', 'wrong')).rejects.toThrow('Wrong sync passphrase');
    expect((await syncApi.pull('main', PASSPHRASE)).kind).toBe('ok');
    const rows = await syncApi.accountHosts();
    expect(rows?.map((host) => [host.name, host.hostname, host.port, host.user, host.identityFile])).toEqual([
      ['hetzner', '135.181.114.209', 22, 'alexey', null],
      ['fixture', 'fixture', 2222, 'u', null],
    ]);
    await syncApi.logout();
    expect(await syncApi.accountHosts()).toBeNull();
  });

  it('keeps the decrypted account copy in memory only and deletes a copy an older build persisted (#3026)', async () => {
    const google = new FakeGoogleSync();
    google.signedIn = true;
    google.slot = { version: 2, data: await encryptToEnvelope(ACCOUNT, PASSPHRASE, FAST) };
    const storage = new Storage();
    storage.setItem(ACCOUNT_HOSTS_STORAGE_KEY, JSON.stringify([{ name: 'stale', hostname: 'old.example' }]));
    const sync = new AndroidSync({ native: google, storage, kdfIterations: FAST, selection: memorySelection(), localHosts: async () => [] });
    expect(storage.getItem(ACCOUNT_HOSTS_STORAGE_KEY)).toBeNull();
    expect(sync.accountHosts()).toBeNull();

    await sync.api().pull('main', PASSPHRASE);
    const result = await sync.syncNow({ localHosts: [], selected: ['hetzner', 'fixture'], passphrase: PASSPHRASE });
    expect(result.kind).toBe('synced');
    expect(sync.accountHosts()?.map((host) => host.name)).toEqual(['hetzner', 'fixture']);
    // Nothing decrypted reached WebView storage: no host name, no address.
    // Nothing decrypted reached WebView storage: no address, user or desktop
    // field. Only the alias list the selection rule keeps (aliases, like the
    // persisted selection itself) is stored.
    for (const secret of ['135.181.114.209', 'alexey', 'id_ed25519', '2222']) expect(storage.dump()).not.toContain(secret);
    expect([...storage.values.keys()]).toEqual([KNOWN_ALIASES_STORAGE_KEY]);
    expect(JSON.parse(storage.getItem(KNOWN_ALIASES_STORAGE_KEY)!)).toEqual(['hetzner', 'fixture']);
  });

  it('the account button opens Account & sync on the phone instead of refusing', async () => {
    const opened: string[] = [];
    const { created } = setup({ opened });
    await created.api.win.openAccount();
    expect(opened).toEqual(['account']);

    const router = createSharedAppRouter();
    await router.push('/');
    await openAccountRoute();
    expect(router.currentRoute.value.name).toBe('android-account');
    expect(router.currentRoute.value.path).toBe(ACCOUNT_ROUTE);
  });

  it('lists every account host as a picker row once signed in and unlocked, beside the phone hosts', async () => {
    const { google, hosts, created } = setup();
    google.slot = { version: 2, data: await encryptToEnvelope(ACCOUNT, PASSPHRASE, FAST) };
    hosts.save({ name: 'phone-box', hostname: '192.168.1.9', port: 22, user: 'me', keyHandleId: KEY.handleId });
    provideApi(created.api);
    setActivePinia(createPinia());
    const sync = useSyncStore();
    const connection = useConnectionStore();
    await connection.loadHosts();

    // Signed out: one unlabelled list, the account button offers sign-in.
    await sync.refreshStatus();
    let html = await renderPicker();
    expect(html).toContain('Sign in');
    expect(html).toContain('phone-box');
    expect(html).not.toContain('From your account');

    // Signed in, still locked: the picker says how to reveal the account.
    await sync.login();
    html = await renderPicker();
    expect(html).toContain('phone@example.com');
    expect(html).toContain('Open Account &amp; sync');
    expect(html).not.toContain('135.181.114.209');

    // Unlocked with the passphrase: every account host is a row, no sync step.
    sync.passphrase = PASSPHRASE;
    await sync.loadAccount();
    html = await renderPicker();
    expect(html).toContain('From your account');
    expect(html).toContain('On this phone');
    expect(html).toContain('alexey@135.181.114.209:22');
    expect(html).toContain('u@fixture:2222');
    expect(html).toContain('me@192.168.1.9:22');
    expect(html).not.toContain('Open Account &amp; sync');
    expect(html.indexOf('From your account')).toBeLessThan(html.indexOf('On this phone'));
  });

  it('asks for this phone\'s key when an account host is tapped, saves it with the key, then connects', async () => {
    const { google, native, hosts, prompt, created, storage } = setup();
    google.signedIn = true;
    google.slot = { version: 2, data: await encryptToEnvelope(ACCOUNT, PASSPHRASE, FAST) };
    provideApi(created.api);
    setActivePinia(createPinia());
    const sync = useSyncStore();
    const connection = useConnectionStore();
    await sync.refreshStatus();
    sync.passphrase = PASSPHRASE;
    await sync.loadAccount();
    const fixture = sync.accountHosts!.find((host) => host.name === 'fixture')!;

    const dial = connection.connect(fixture);
    await settle(() => prompt.state.pending !== null && prompt.form.state.keys.length === 2);
    expect(prompt.state.pending).toMatchObject({ name: 'fixture', hostname: 'fixture', port: 2222 });
    expect(native.connects).toHaveLength(0);
    // No key chosen: the prompt stays, nothing is saved or dialled.
    expect(prompt.confirm()).toBe(false);
    expect(prompt.form.state.error).toBe('Choose an SSH key from the key vault.');
    expect(storage.getItem(ANDROID_HOSTS_STORAGE_KEY)).toBeNull();

    prompt.form.state.keyHandleId = KEY.handleId;
    expect(prompt.confirm()).toBe(true);
    expect(await dial).toBe(true);
    expect(native.connects[0]).toMatchObject({ hostname: 'fixture', port: 2222, username: 'u', credential: { kind: 'key-handle', handleId: KEY.handleId } });
    expect(hosts.savedHosts()).toEqual([{ name: 'fixture', hostname: 'fixture', port: 2222, user: 'u', keyHandleId: KEY.handleId }]);
    await connection.disconnect();

    // Saved on the phone now: the next tap dials with no question.
    await connection.loadHosts();
    expect(connection.hosts.map((host) => host.name)).toEqual(['fixture']);
    const again = connection.connect(connection.hosts[0]!);
    expect(await again).toBe(true);
    expect(prompt.state.pending).toBeNull();
    await connection.disconnect();
  });

  it('cancelling the key prompt dials nothing and says why', async () => {
    const { google, native, prompt, created } = setup();
    google.signedIn = true;
    google.slot = { version: 2, data: await encryptToEnvelope(ACCOUNT, PASSPHRASE, FAST) };
    await created.api.sync.pull('main', PASSPHRASE);
    const pending = created.api.ssh.connect({ host: '135.181.114.209', port: 22, user: 'alexey' });
    await settle(() => prompt.state.pending !== null);
    prompt.cancel();
    expect(await pending).toEqual({ ok: false, error: 'No SSH key was chosen for “hetzner” on this phone, so nothing was dialled.' });
    expect(native.connects).toHaveLength(0);
  });

  it('only an unlocked account host is adopted; an unknown host keeps its plain refusal', async () => {
    const { prompt, created } = setup();
    expect(await created.api.ssh.connect({ host: 'nowhere', port: 22, user: 'x' }))
      .toEqual({ ok: false, error: 'No saved host for x@nowhere:22.' });
    expect(prompt.state.pending).toBeNull();
    const hosts: HostEntry[] = [
      { name: 'a', hostname: 'h', port: 22, user: 'alice', identityFile: null, proxyJump: null, forwardAgent: false, localForwards: [], remoteForwards: [], fromConfig: false },
      { name: 'b', hostname: 'h', port: 2222, user: '', identityFile: null, proxyJump: null, forwardAgent: false, localForwards: [], remoteForwards: [], fromConfig: false },
    ];
    expect(matchAccountHost(hosts, { host: 'h', user: 'alice' })?.name).toBe('a');
    expect(matchAccountHost(hosts, { host: 'h', user: 'bob' })).toBeNull();
    expect(matchAccountHost(hosts, { host: 'h', port: 2222, user: 'anyone' })?.name).toBe('b');
  });

  it('a phone host whose key is gone gets the key prompt and is updated in place, never duplicated', async () => {
    const { native, hosts, prompt, created, storage } = setup();
    storage.setItem(ANDROID_HOSTS_STORAGE_KEY, JSON.stringify([
      { name: 'first', hostname: 'one.lan', port: 22, user: 'a', keyHandleId: OTHER_KEY.handleId },
      { name: 'box', hostname: 'fixture', port: 2222, user: 'u', keyHandleId: '' },
    ]));
    const pending = created.api.ssh.connect({ host: 'fixture', port: 2222, user: 'u' });
    await settle(() => prompt.state.pending !== null);
    expect(prompt.state.reason).toBe('missing-key');
    expect(prompt.state.pending).toMatchObject({ name: 'box', hostname: 'fixture', port: 2222 });
    prompt.form.state.keyHandleId = KEY.handleId;
    expect(prompt.confirm()).toBe(true);
    const result = await pending;
    expect(result.ok).toBe(true);
    expect(native.connects[0]).toMatchObject({ credential: { kind: 'key-handle', handleId: KEY.handleId } });
    expect(hosts.savedHosts()).toEqual([
      { name: 'first', hostname: 'one.lan', port: 22, user: 'a', keyHandleId: OTHER_KEY.handleId },
      { name: 'box', hostname: 'fixture', port: 2222, user: 'u', keyHandleId: KEY.handleId },
    ]);
    await created.hub.close(result.connectionId!);
  });

  it('an account host at the address of a keyless phone host attaches the key to that phone host', async () => {
    const { google, hosts, prompt, created, storage } = setup();
    google.signedIn = true;
    google.slot = { version: 2, data: await encryptToEnvelope(ACCOUNT, PASSPHRASE, FAST) };
    await created.api.sync.pull('main', PASSPHRASE);
    storage.setItem(ANDROID_HOSTS_STORAGE_KEY, JSON.stringify([{ name: 'box', hostname: 'fixture', port: 2222, user: 'u', keyHandleId: '' }]));
    // Tapped from the account group: the account calls it "fixture".
    const pending = created.api.ssh.connect({ host: 'fixture', port: 2222, user: 'u' });
    await settle(() => prompt.state.pending !== null);
    expect(prompt.state).toMatchObject({ reason: 'missing-key', pending: { name: 'box' } });
    prompt.form.state.keyHandleId = KEY.handleId;
    prompt.confirm();
    const result = await pending;
    expect(result.ok).toBe(true);
    expect(hosts.savedHosts().map((host) => [host.name, host.keyHandleId])).toEqual([['box', KEY.handleId]]);
    await created.hub.close(result.connectionId!);
  });

  it('keeps every account alias selected unless the user unticked it', () => {
    expect(keepAccountAliases(['a', 'b'], [], [])).toEqual({ selected: ['a', 'b'], known: ['a', 'b'] });
    // 'a' was seen selected before and is no longer: an explicit untick.
    expect(keepAccountAliases(['a', 'b', 'c'], ['b'], ['a', 'b'])).toEqual({ selected: ['b', 'c'], known: ['a', 'b', 'c'] });
    expect(keepAccountAliases([], ['x'], [])).toEqual({ selected: ['x'], known: ['x'] });
  });

  it("browser dev mode's GoogleSync stand-in signs in and serves an account the dev passphrase unlocks", async () => {
    const plugin = createGoogleSyncPlugin();
    const call = (name: string) => (options: Record<string, unknown> = {}) => Promise.resolve(plugin.methods[name]!(options));
    const native = createGoogleSyncNative({ status: call('status'), signIn: call('signIn'), signOut: call('signOut'), request: call('request') } as never);
    const sync = new AndroidSync({ native, storage: new Storage(), kdfIterations: FAST, selection: memorySelection(), localHosts: async () => [] }).api();
    expect((await sync.status()).loggedIn).toBe(false);
    expect(await sync.login()).toBe('dev@example.com');
    expect((await sync.pull('main', DEV_SYNC_PASSPHRASE)).kind).toBe('ok');
    expect((await sync.accountHosts())?.map((host) => host.name)).toEqual(DEV_SYNC_ACCOUNT.hosts.map((host) => host.name));
  });
});

/** The sync selection as a plain list (the settings store's `syncSelectedHosts` in the app). */
function memorySelection(initial: string[] = []) {
  let aliases = [...initial];
  return { get: () => aliases, set: (next: string[]) => { aliases = [...next]; } };
}
