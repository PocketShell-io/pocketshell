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
async function setup(account: object[], phone: Saved[], opts: { storage?: Storage; google?: Google; selected?: string[] } = {}) {
  const google = opts.google ?? new Google();
  if (!opts.google) google.slot = { version: 2, data: await encryptToEnvelope(JSON.stringify({ hosts: account }), PASS, FAST) };
  const storage = opts.storage ?? new Storage();
  if (!opts.storage) storage.setItem(ANDROID_HOSTS_STORAGE_KEY, JSON.stringify(phone));
  const hosts = new AndroidHostStore({ storage, readLegacyHosts: async () => [] });
  setActivePinia(createPinia());
  if (opts.selected) useSettingsStore().syncSelectedHosts = [...opts.selected];
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
 * #3063 review round 4: core 2e60b8a's gateway contract (#3059). Android has
 * no gateway or link transport, so an account host carrying a `gateway`
 * marker (valid, null, malformed, or with `link`) or a `link` marker is
 * refused before any key prompt, save, credential or socket, with a message
 * that says why; the marker survives the account copy and Sync now verbatim.
 * The first case is the reviewer's probe (gateway-probe.test.ts) with its
 * console output turned into assertions.
 */
const GW = { serverUrl: 'wss://gateway.example', deviceId: 'dev-1' };
const LINK = { relayUrl: 'wss://relay.example', hostId: 'h-1' };
const GATEWAY_REFUSAL = /is reached through the PocketShell gateway, which this phone can't connect through yet\. Nothing was dialled\.$/;
const LINK_REFUSAL = /is reached through a PocketShell relay link, which this phone can't connect through yet\. Nothing was dialled\.$/;

const CASES: Array<[string, Record<string, unknown>, RegExp]> = [
  ['a valid gateway marker', { gateway: GW }, GATEWAY_REFUSAL],
  ['a null gateway marker', { gateway: null }, GATEWAY_REFUSAL],
  ['a malformed gateway marker', { gateway: 'not-a-target' }, GATEWAY_REFUSAL],
  ['link and gateway together', { link: LINK, gateway: GW }, GATEWAY_REFUSAL],
  ['a link marker alone (no link transport on Android)', { link: LINK }, LINK_REFUSAL],
];

describe('Android refuses gateway and link account hosts (core #3059)', () => {
  it('reviewer probe: a gateway account host tapped on Android is refused, never prompted, saved or dialled', async () => {
    const gw = { name: 'gw', hostname: '10.0.0.5', port: 22, user: 'root', gateway: GW };
    const { hosts, prompt, created, syncApi, native } = await setup([gw], []);
    await syncApi.pull('main', PASS);
    expect((await syncApi.accountHosts())?.[0]).toMatchObject({ name: 'gw', gateway: GW });
    const result = await created.api.ssh.connect({ host: '10.0.0.5', port: 22, user: 'root', hostAlias: 'gw', gateway: gw.gateway } as never);
    expect(result).toEqual({ ok: false, error: expect.stringMatching(GATEWAY_REFUSAL) });
    expect(result.error).toContain('“gw”');
    expect(prompt.state.pending).toBeNull();
    expect(native.connects).toHaveLength(0);
    expect(hosts.savedHosts()).toEqual([]);
  });

  for (const [label, marker, refusal] of CASES) {
    it(`${label}: the picker's dial is refused with no prompt, save or socket, and the account copy keeps the marker verbatim`, async () => {
      const entry = { name: 'gw', hostname: '10.0.0.5', port: 22, user: 'root', ...marker };
      const { hosts, prompt, store, native } = await setup([entry], []);
      await store.loadAccount();
      const host = store.accountHosts!.find((h) => h.name === 'gw')!;
      for (const key of Object.keys(marker)) expect((host as unknown as Record<string, unknown>)[key]).toEqual(marker[key]);
      // The real picker path: core's connection store carries the raw marker.
      const ok = await useConnectionStore().connect(host);
      expect(ok).toBe(false);
      expect(useConnectionStore().error).toMatch(refusal);
      expect(prompt.state.pending).toBeNull();
      expect(native.connects).toHaveLength(0);
      expect(hosts.savedHosts()).toEqual([]);
    });

    it(`${label}: a dial that omits the marker is still refused from the account entry`, async () => {
      const entry = { name: 'gw', hostname: '10.0.0.5', port: 22, user: 'root', ...marker };
      const { hosts, prompt, syncApi, created, native } = await setup([entry], []);
      await syncApi.pull('main', PASS);
      expect((await syncApi.accountHosts())![0]).toMatchObject(marker);
      const result = await created.api.ssh.connect({ host: '10.0.0.5', port: 22, user: 'root' });
      expect(result).toEqual({ ok: false, error: expect.stringMatching(refusal) });
      expect(prompt.state.pending).toBeNull();
      expect(native.connects).toHaveLength(0);
      expect(hosts.savedHosts()).toEqual([]);
    });
  }

  it('Sync now keeps every account marker byte-identical, including on a host the phone also has', async () => {
    const markedHosts = [
      { name: 'gw', hostname: '10.0.0.5', port: 22, user: 'root', gateway: GW },
      { name: 'gw-null', hostname: '10.0.0.6', port: 22, user: 'root', gateway: null },
      { name: 'gw-bad', hostname: '10.0.0.7', port: 22, user: 'root', gateway: 'not-a-target' },
      { name: 'both', hostname: '10.0.0.8', port: 22, user: 'root', link: LINK, gateway: GW },
      { name: 'linked', hostname: '10.0.0.9', port: 22, user: 'root', link: LINK },
    ];
    // The phone has its own plain 'gw' (same name): its fields win, the marker stays.
    const { google, store } = await setup(markedHosts, [{ name: 'gw', hostname: 'gw.phone.lan', port: 22, user: 'root', keyHandleId: 'k1' }]);
    await store.loadAccount();
    await store.syncNow();
    const account = await google.account();
    expect(account.find((h) => h.name === 'gw')).toEqual({ name: 'gw', hostname: 'gw.phone.lan', port: 22, user: 'root', gateway: GW });
    for (const host of markedHosts.slice(1)) expect(account.find((h) => h.name === host.name)).toEqual(host);
  });
});
