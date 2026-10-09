import { describe, expect, it } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { provideApi } from '@ui/app/ipc';
import { useConnectionStore } from '@ui/app/stores/connection';
import { useSettingsStore } from '@ui/app/stores/settings';
import { useSyncStore } from '@ui/app/stores/sync';
import { ConnectionController } from '@pocketshell/core';
import type { GoogleSyncHttpResponse, GoogleSyncNative, GoogleSyncRequest } from '@/native/googleSync';
import type { SshKeyMetadata } from '@/native/sshKeyVault';
import { decryptEnvelope, encryptToEnvelope } from '@/sync/syncCrypto';
import { createAndroidPlatform } from '@/platform/android/androidApi';
import { AndroidHostStore, ANDROID_HOSTS_STORAGE_KEY } from '@/platform/android/hostStore';
import { createAndroidSync } from '@/platform/android/sync';
import { createAccountHostKeys } from '@/platform/android/accountHosts';
import { createLocalTrustStore } from '@/platform/android/trustStore';
import { createAccountHostKeyPrompt } from '@/sharedApp/accountHostKey';
import { FakeNative, HOST_KEY, settle } from './support/androidFakeNative';

const PASS = 'phone and laptop';
const FAST = 1000;
const KEY: SshKeyMetadata = { handleId: '00000000-0000-4000-8000-000000000001', label: 'k', algorithm: 'ssh-ed25519', fingerprintSha256: 'SHA256:k', passphraseRequired: false, createdAt: 1 };

class Storage { readonly v = new Map<string, string>(); getItem(k: string) { return this.v.get(k) ?? null; } setItem(k: string, x: string) { this.v.set(k, x); } removeItem(k: string) { this.v.delete(k); } }
class Google implements GoogleSyncNative {
  signedIn = true; slot: { version: number; data: string } | null = null; puts = 0;
  async status() { return { signedIn: this.signedIn, email: 'p@example.com', packageName: 'p' }; }
  async signIn() { this.signedIn = true; return this.status(); }
  async signOut() { this.signedIn = false; return this.status(); }
  async request(r: GoogleSyncRequest): Promise<GoogleSyncHttpResponse> {
    if (r.method === 'GET') return this.slot ? { status: 200, body: JSON.stringify(this.slot) } : { status: 404, body: '{}' };
    this.puts += 1;
    const { data, version } = JSON.parse(r.body) as { data: string; version: number };
    this.slot = { version: version + 1, data };
    return { status: 200, body: JSON.stringify({ version: version + 1 }) };
  }
  async account() { return (JSON.parse(await decryptEnvelope(this.slot!.data, PASS)) as { hosts: Array<Record<string, unknown>> }).hosts; }
}

type Saved = { name: string; hostname: string; port: number; user: string; keyHandleId: string };
async function setup(account: object[], phone: Saved[], opts: { storage?: Storage; google?: Google; selected?: string[]; unticked?: string[] } = {}) {
  const google = opts.google ?? new Google();
  if (!opts.google) google.slot = { version: 2, data: await encryptToEnvelope(JSON.stringify({ hosts: account }), PASS, FAST) };
  const storage = opts.storage ?? new Storage();
  if (!opts.storage) storage.setItem(ANDROID_HOSTS_STORAGE_KEY, JSON.stringify(phone));
  const hosts = new AndroidHostStore({ storage, readLegacyHosts: async () => [] });
  setActivePinia(createPinia());
  if (opts.selected) useSettingsStore().syncSelectedHosts = [...opts.selected];
  if (opts.unticked) useSettingsStore().syncUntickedHosts = [...opts.unticked];
  const sync = createAndroidSync({ native: google, storage, localHosts: () => hosts.list(), kdfIterations: FAST });
  const syncApi = sync.api();
  const native = new FakeNative();
  const prompt = createAccountHostKeyPrompt({ keys: { list: async () => [KEY], pickAndImport: async () => null }, hosts });
  const trust = createLocalTrustStore(storage);
  for (const id of ['root@10.0.0.5:22', 'alexey@10.0.0.5:22', 'root@10.0.0.5:2222']) void trust.record(id, { kind: 'wire-key', ...HOST_KEY });
  const created = createAndroidPlatform({
    createController: () => new ConnectionController({ capability: native.capability(), trustStore: trust, retryDelaysMs: [0] }),
    hosts, backgroundGraceMs: () => 60_000, addHostRoute: '/x', sync: syncApi,
    accountHosts: createAccountHostKeys({ accountHosts: () => syncApi.accountHosts(), ask: prompt.ask }),
  });
  provideApi(created.api);
  await useConnectionStore().loadHosts();
  const store = useSyncStore();
  await store.refreshStatus();
  store.passphrase = PASS;
  return { google, storage, hosts, sync, syncApi, store, settings: useSettingsStore(), prompt, created, native };
}

const HETZNER_ACC = { name: 'hetzner', hostname: '135.181.114.209', port: 22, user: 'alexey', identityFile: '~/.ssh/id_ed25519', proxyJump: 'bastion', localForwards: [{ kind: 'local', listenHost: '', listenPort: 8080, destHost: 'localhost', destPort: 80 }], futureDirective: { x: 1 } };
const FIXTURE_ACC = { name: 'fixture', hostname: 'fixture', port: 2222, user: 'u' };
const HETZNER_PHONE: Saved = { name: 'hetzner', hostname: '135.181.114.209', port: 22, user: 'alexey', keyHandleId: 'k1' };

/**
 * #3063 review round 2, ported verbatim apart from logging: the reviewer's
 * adversarial cases for Sync now (R/C) and for the key prompt's routing (K).
 * K1/K1b failed on 61ece301e: a keyless phone host at the same address under
 * another user was given the account host's key.
 */
describe('Android account sync and key-prompt adversarial cases', () => {
  it('R1 dataloss repro (round-1 scenario, production factory): unlock then Sync now keeps hetzner', async () => {
    const { google, store } = await setup([HETZNER_ACC, FIXTURE_ACC], [HETZNER_PHONE]);
    await store.loadAccount();
    await store.syncNow();
    expect((await google.account()).map((h) => h.name).sort()).toEqual(['fixture', 'hetzner']);
  });

  it('R2 fields repro: ticked hetzner keeps identityFile/proxyJump/forwards/unknown', async () => {
    const { google, store } = await setup([HETZNER_ACC, FIXTURE_ACC], [HETZNER_PHONE]);
    await store.loadAccount();
    store.setSelected('hetzner', true);
    await store.syncNow();
    expect((await google.account()).find((h) => h.name === 'hetzner')).toEqual(HETZNER_ACC);
  });

  it('C1 account-only minimal host (no port/user, unknown field) comes back byte-identical', async () => {
    const mini = { name: 'mini', hostname: 'mini.lan', weird: [1, 2] };
    const { google, store } = await setup([HETZNER_ACC, mini], [HETZNER_PHONE]);
    await store.loadAccount();
    await store.syncNow();
    const acc = await google.account();
    expect(acc.find((h) => h.name === 'mini')).toEqual(mini);
    expect(acc.find((h) => h.name === 'hetzner')).toEqual(HETZNER_ACC);
  });

  it('C2 first Sync now with no prior unlock keeps all hosts and fields; message reports count', async () => {
    const { google, store } = await setup([HETZNER_ACC, FIXTURE_ACC], [HETZNER_PHONE]);
    await store.syncNow();
    const acc = await google.account();
    expect(acc.map((h) => h.name).sort()).toEqual(['fixture', 'hetzner']);
    expect(acc.find((h) => h.name === 'hetzner')).toEqual(HETZNER_ACC);
  });

  it('C3 restart between unlock and Sync now (selection + saved unticks persisted) keeps every host not unticked', async () => {
    const first = await setup([HETZNER_ACC, FIXTURE_ACC], [HETZNER_PHONE]);
    await first.store.loadAccount();
    const persisted = { selected: [...first.settings.syncSelectedHosts], unticked: [...first.settings.syncUntickedHosts] };
    const second = await setup([], [], { storage: first.storage, google: first.google, ...persisted });
    expect(second.sync.accountHosts()).toBeNull();
    await second.store.syncNow();
    const acc = await first.google.account();
    expect(acc.map((h) => h.name).sort()).toEqual(['fixture', 'hetzner']);
    expect(acc.find((h) => h.name === 'hetzner')).toEqual(HETZNER_ACC);
    // An untick saved before a restart is still the user's decision after it:
    // the next Sync now removes that host, and only that host.
    second.store.setSelected('fixture', false);
    const third = await setup([], [], {
      storage: first.storage, google: first.google,
      selected: [...second.settings.syncSelectedHosts], unticked: [...second.settings.syncUntickedHosts],
    });
    await third.store.syncNow();
    expect((await first.google.account()).map((h) => h.name)).toEqual(['hetzner']);
    expect(third.settings.syncUntickedHosts).toEqual([]);
  });

  it('C4 alias case mismatch (phone "Hetzner", account "hetzner") never drops the account entry', async () => {
    const { google, store } = await setup([HETZNER_ACC, FIXTURE_ACC], [{ ...HETZNER_PHONE, name: 'Hetzner' }]);
    await store.loadAccount();
    await store.syncNow();
    const acc = await google.account();
    expect(acc.find((h) => h.name === 'hetzner')).toEqual(HETZNER_ACC);
  });

  it('C5 account with hosts the phone has never seen + legacy screen Sync now keeps all', async () => {
    const { google, hosts, sync, settings } = await setup([HETZNER_ACC, FIXTURE_ACC, { name: 'third', hostname: 't', port: 22, user: 'x', identityFile: '~/k' }], []);
    const r = await sync.syncNow({
      localHosts: await hosts.list(), selected: settings.syncSelectedHosts, unticked: settings.syncUntickedHosts, passphrase: PASS,
      onSelection: (a) => { settings.syncSelectedHosts = a.checked; settings.syncUntickedHosts = a.unticked; },
    });
    expect(r.kind).toBe('synced');
    const acc = await google.account();
    expect(acc.map((h) => h.name).sort()).toEqual(['fixture', 'hetzner', 'third']);
    expect(acc.find((h) => h.name === 'third')).toEqual({ name: 'third', hostname: 't', port: 22, user: 'x', identityFile: '~/k' });
  });

  it('C6 explicit untick of a phone host still removes it', async () => {
    const { google, store } = await setup([HETZNER_ACC, FIXTURE_ACC], [HETZNER_PHONE]);
    await store.loadAccount();
    store.setSelected('hetzner', false);
    await store.syncNow();
    expect((await google.account()).map((h) => h.name)).toEqual(['fixture']);
  });

  it('C7 unlock uploads nothing; storage holds aliases only', async () => {
    const { google, store, storage, syncApi, settings } = await setup([HETZNER_ACC, FIXTURE_ACC], [HETZNER_PHONE]);
    await store.loadAccount();
    await syncApi.pull('main', PASS);
    expect(google.puts).toBe(0);
    // The host store and the test's own trust pins (seeded in setup) are not sync data.
    const dump = JSON.stringify([...storage.v].filter(([k]) => k !== ANDROID_HOSTS_STORAGE_KEY && !k.startsWith('pocketshell.ssh.host-key.')));
    for (const s of ['id_ed25519', 'bastion', 'fixture"', PASS, '2222']) expect(dump).not.toContain(s);
    // The selection is the shared settings' alias list; the adapter keeps no
    // alias list of its own any more (#3072 retired the known-aliases key).
    expect(storage.getItem('pocketshell.sync.known-aliases.v1')).toBeNull();
    expect(settings.syncSelectedHosts).toEqual(['hetzner', 'fixture']);
  });

  it('K1 same hostname+port, DIFFERENT user: account root@ must not rewrite keyless phone alexey@ host', async () => {
    const box: Saved = { name: 'box', hostname: '10.0.0.5', port: 22, user: 'alexey', keyHandleId: '' };
    const { hosts, prompt, created, syncApi } = await setup([{ name: 'fixture', hostname: '10.0.0.5', port: 22, user: 'root' }], [box]);
    await syncApi.pull('main', PASS);
    const pending = created.api.ssh.connect({ host: '10.0.0.5', port: 22, user: 'root', hostAlias: 'fixture' } as never);
    await settle(() => prompt.state.pending !== null);
    prompt.form.state.keyHandleId = KEY.handleId;
    prompt.confirm();
    await pending.catch(() => undefined);
    expect(hosts.savedHosts().find((h) => h.name === 'box')).toEqual(box);
  });

  it('K1b same as K1 but the picker sends no hostAlias', async () => {
    const box: Saved = { name: 'box', hostname: '10.0.0.5', port: 22, user: 'alexey', keyHandleId: '' };
    const { hosts, prompt, created, syncApi } = await setup([{ name: 'fixture', hostname: '10.0.0.5', port: 22, user: 'root' }], [box]);
    await syncApi.pull('main', PASS);
    const pending = created.api.ssh.connect({ host: '10.0.0.5', port: 22, user: 'root' });
    await settle(() => prompt.state.pending !== null);
    prompt.form.state.keyHandleId = KEY.handleId;
    prompt.confirm();
    await pending.catch(() => undefined);
    expect(hosts.savedHosts().find((h) => h.name === 'box')).toEqual(box);
  });

  it('K2 same hostname, DIFFERENT port: account host is adopted as its own host, phone box untouched', async () => {
    const box: Saved = { name: 'box', hostname: '10.0.0.5', port: 22, user: 'root', keyHandleId: '' };
    const { hosts, prompt, created, syncApi } = await setup([{ name: 'fixture', hostname: '10.0.0.5', port: 2222, user: 'root' }], [box]);
    await syncApi.pull('main', PASS);
    const pending = created.api.ssh.connect({ host: '10.0.0.5', port: 2222, user: 'root' });
    await settle(() => prompt.state.pending !== null);
    expect(prompt.state.reason).toBe('account');
    prompt.form.state.keyHandleId = KEY.handleId;
    prompt.confirm();
    await pending.catch(() => undefined);
    expect(hosts.savedHosts().find((h) => h.name === 'box')).toEqual(box);
    expect(hosts.savedHosts().find((h) => h.name === 'fixture')).toMatchObject({ port: 2222, keyHandleId: KEY.handleId });
  });

  it('K3 same hostname, port AND user: the key is attached to the keyless phone host in place, no duplicate', async () => {
    const box: Saved = { name: 'box', hostname: '10.0.0.5', port: 22, user: 'root', keyHandleId: '' };
    const { hosts, prompt, created, syncApi } = await setup([{ name: 'fixture', hostname: '10.0.0.5', port: 22, user: 'root' }], [box]);
    await syncApi.pull('main', PASS);
    const pending = created.api.ssh.connect({ host: '10.0.0.5', port: 22, user: 'root' });
    await settle(() => prompt.state.pending !== null);
    expect(prompt.state).toMatchObject({ reason: 'missing-key', pending: { name: 'box' } });
    prompt.form.state.keyHandleId = KEY.handleId;
    prompt.confirm();
    const result = await pending;
    expect(result.ok).toBe(true);
    expect(hosts.savedHosts()).toEqual([{ ...box, keyHandleId: KEY.handleId }]);
    await created.hub.close(result.connectionId!);
  });

  it('K4 different user on the same address: the account host becomes its own phone host and the next tap dials it without asking', async () => {
    const box: Saved = { name: 'box', hostname: '10.0.0.5', port: 22, user: 'alexey', keyHandleId: '' };
    const { hosts, prompt, created, syncApi, native } = await setup([{ name: 'fixture', hostname: '10.0.0.5', port: 22, user: 'root' }], [box]);
    await syncApi.pull('main', PASS);
    const first = created.api.ssh.connect({ host: '10.0.0.5', port: 22, user: 'root' });
    await settle(() => prompt.state.pending !== null);
    expect(prompt.state).toMatchObject({ reason: 'account', pending: { name: 'fixture', user: 'root' } });
    prompt.form.state.keyHandleId = KEY.handleId;
    prompt.confirm();
    const result = await first;
    expect(result.ok).toBe(true);
    expect(native.connects.at(-1)).toMatchObject({ username: 'root', credential: { kind: 'key-handle', handleId: KEY.handleId } });
    expect(hosts.savedHosts()).toEqual([box, { name: 'fixture', hostname: '10.0.0.5', port: 22, user: 'root', keyHandleId: KEY.handleId }]);
    await created.hub.close(result.connectionId!);
    const again = await created.api.ssh.connect({ host: '10.0.0.5', port: 22, user: 'root' });
    expect(again.ok).toBe(true);
    expect(prompt.state.pending).toBeNull();
    await created.hub.close(again.connectionId!);
  });
});
