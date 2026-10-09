import { describe, expect, it } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { provideApi } from '@ui/app/ipc';
import { useConnectionStore } from '@ui/app/stores/connection';
import { useSettingsStore } from '@ui/app/stores/settings';
import { useSyncStore } from '@ui/app/stores/sync';
import { ConnectionController } from '@pocketshell/core';
import type { GoogleSyncHttpResponse, GoogleSyncNative, GoogleSyncRequest } from '@/native/googleSync';
import { decryptEnvelope, encryptToEnvelope } from '@/sync/syncCrypto';
import { createAndroidPlatform } from '@/platform/android/androidApi';
import { AndroidHostStore } from '@/platform/android/hostStore';
import { createAndroidSync } from '@/platform/android/sync';
import { FakeNative } from './support/androidFakeNative';

/**
 * #3063 review: the shared Account & sync screen is reachable on Android, and
 * its Sync now must not lose account data. Ported from the reviewer's repros
 * (dataloss-repro / fields-repro): the real AndroidSync as both shells build
 * it (src/platform/android/sync.ts), the real Android platform, and core's
 * shared sync store.
 */

const PASS = 'phone and laptop';
const FAST = 1000;
const FORWARD = { kind: 'local', listenHost: '', listenPort: 8080, destHost: 'localhost', destPort: 80 };
const ACCOUNT = JSON.stringify({ hosts: [
  {
    name: 'hetzner', hostname: '135.181.114.209', port: 22, user: 'alexey',
    identityFile: '~/.ssh/id_ed25519', proxyJump: 'bastion', forwardAgent: true,
    localForwards: [FORWARD], remoteForwards: [{ ...FORWARD, kind: 'remote' }],
    futureDirective: { mode: 'opaque' },
  },
  { name: 'fixture', hostname: 'fixture', port: 2222, user: 'u' },
] });

class Storage {
  readonly v = new Map<string, string>();
  getItem(k: string) { return this.v.get(k) ?? null; }
  setItem(k: string, x: string) { this.v.set(k, x); }
  removeItem(k: string) { this.v.delete(k); }
}

class Google implements GoogleSyncNative {
  signedIn = true;
  slot: { version: number; data: string } | null = null;
  /** Refuse the next upload with this HTTP status (a failed push). */
  failNextPut: number | null = null;
  async status() { return { signedIn: this.signedIn, email: 'p@example.com', packageName: 'p' }; }
  async signIn() { this.signedIn = true; return this.status(); }
  async signOut() { this.signedIn = false; return this.status(); }
  async request(r: GoogleSyncRequest): Promise<GoogleSyncHttpResponse> {
    if (r.method === 'GET') return this.slot ? { status: 200, body: JSON.stringify(this.slot) } : { status: 404, body: '{}' };
    if (this.failNextPut !== null) {
      const status = this.failNextPut;
      this.failNextPut = null;
      return { status, body: '{"message":"service unavailable"}' };
    }
    const { data, version } = JSON.parse(r.body) as { data: string; version: number };
    this.slot = { version: version + 1, data };
    return { status: 200, body: JSON.stringify({ version: version + 1 }) };
  }
  async account(): Promise<Array<Record<string, unknown>>> {
    return (JSON.parse(await decryptEnvelope(this.slot!.data, PASS)) as { hosts: Array<Record<string, unknown>> }).hosts;
  }
  /** Another device writes the account (re-adds a host), on the current version. */
  async writeElsewhere(plaintext: string): Promise<void> {
    this.slot = { version: (this.slot?.version ?? 0) + 1, data: await encryptToEnvelope(plaintext, PASS, FAST) };
  }
}

/**
 * The phone has its own `hetzner` (a migrated or added host); the account has
 * hetzner and fixture. `restart` re-launches over an existing phone: the same
 * WebView storage and account, the persisted sync settings, a fresh adapter.
 */
async function setup(restart?: { google: Google; storage: Storage; selected: string[]; unticked: string[] }) {
  const google = restart?.google ?? new Google();
  if (!restart) google.slot = { version: 2, data: await encryptToEnvelope(ACCOUNT, PASS, FAST) };
  const storage = restart?.storage ?? new Storage();
  const hosts = new AndroidHostStore({ storage, readLegacyHosts: async () => [] });
  if (!restart) hosts.save({ name: 'hetzner', hostname: 'hetzner.phone.lan', port: 2200, user: 'alexey', keyHandleId: 'k1' });
  setActivePinia(createPinia());
  if (restart) {
    // The settings store persists these per device (localStorage in the app).
    useSettingsStore().syncSelectedHosts = [...restart.selected];
    useSettingsStore().syncUntickedHosts = [...restart.unticked];
  }
  const sync = createAndroidSync({ native: google, storage, localHosts: () => hosts.list(), kdfIterations: FAST });
  const native = new FakeNative();
  const { api } = createAndroidPlatform({
    createController: () => new ConnectionController({ capability: native.capability(), trustStore: { read: async () => null, record: async () => undefined } as never }),
    hosts,
    backgroundGraceMs: () => 60_000,
    addHostRoute: '/x',
    sync: sync.api(),
  });
  provideApi(api);
  const connection = useConnectionStore();
  await connection.loadHosts();
  const store = useSyncStore();
  await store.refreshStatus();
  store.passphrase = PASS;
  return { google, storage, hosts, sync, store, settings: useSettingsStore() };
}

describe('Android shared Account screen sync safety', () => {
  it('unlocking then Sync now keeps a phone host that is also in the account (dataloss repro)', async () => {
    const { google, store, settings } = await setup();
    await store.loadAccount();
    // Nothing is marked "remove on sync": every account alias is selected.
    expect(settings.syncSelectedHosts).toEqual(expect.arrayContaining(['hetzner', 'fixture']));
    await store.syncNow();
    expect(store.message).toEqual({ kind: 'ok', text: 'Synced: 2 hosts in your account.' });
    expect((await google.account()).map((host) => host.name)).toEqual(['hetzner', 'fixture']);
  });

  it('Sync now pressed without unlocking first still keeps every account host', async () => {
    const { google, store } = await setup();
    await store.syncNow();
    expect((await google.account()).map((host) => host.name).sort()).toEqual(['fixture', 'hetzner']);
  });

  it('a ticked phone host keeps every field another client owns (fields repro)', async () => {
    const { google, store } = await setup();
    await store.loadAccount();
    store.setSelected('hetzner', true);
    await store.syncNow();
    const hetzner = (await google.account()).find((host) => host.name === 'hetzner');
    expect(hetzner).toEqual({
      // The phone's own fields win...
      name: 'hetzner', hostname: 'hetzner.phone.lan', port: 2200, user: 'alexey',
      // ...and every desktop-only field survives, unknown ones included.
      identityFile: '~/.ssh/id_ed25519', proxyJump: 'bastion', forwardAgent: true,
      localForwards: [FORWARD], remoteForwards: [{ ...FORWARD, kind: 'remote' }],
      futureDirective: { mode: 'opaque' },
    });
    expect((await google.account()).find((host) => host.name === 'fixture')).toEqual({ name: 'fixture', hostname: 'fixture', port: 2222, user: 'u' });
  });

  it('only an explicit untick removes an account host, and the push spends it: a host another device re-adds is kept', async () => {
    // #3072 ruling: an untick is ONE-SHOT, consumed by the push that removes
    // the host, never a standing per-device ban. The phone presses Sync now
    // again without touching anything (no unlock in between): the host
    // another device added back must stay in the account.
    const { google, store, settings } = await setup();
    await store.loadAccount();
    store.setSelected('hetzner', false);
    await store.syncNow();
    expect((await google.account()).map((host) => host.name)).toEqual(['fixture']);
    await google.writeElsewhere(ACCOUNT);
    await store.syncNow();
    expect(store.message).toEqual({ kind: 'ok', text: 'Synced: 2 hosts in your account.' });
    expect((await google.account()).map((host) => host.name).sort()).toEqual(['fixture', 'hetzner']);
    expect(settings.syncSelectedHosts).toEqual(expect.arrayContaining(['hetzner', 'fixture']));
    expect(settings.syncUntickedHosts).toEqual([]);
  });

  it('a spent untick stays spent across a restart: a host another device re-adds is kept', async () => {
    const first = await setup();
    await first.store.loadAccount();
    first.store.setSelected('hetzner', false);
    await first.store.syncNow();
    expect((await first.google.account()).map((host) => host.name)).toEqual(['fixture']);
    await first.google.writeElsewhere(ACCOUNT);
    const second = await setup({
      google: first.google,
      storage: first.storage,
      selected: [...first.settings.syncSelectedHosts],
      unticked: [...first.settings.syncUntickedHosts],
    });
    await second.store.syncNow();
    expect((await first.google.account()).map((host) => host.name).sort()).toEqual(['fixture', 'hetzner']);
  });

  it('an untick survives a restart until a push carries it out, and a failed push keeps it pending', async () => {
    const first = await setup();
    await first.store.loadAccount();
    first.store.setSelected('hetzner', false);
    expect(first.settings.syncUntickedHosts).toEqual(['hetzner']);
    first.google.failNextPut = 503;
    await first.store.syncNow();
    expect(first.store.message?.kind).toBe('error');
    expect((await first.google.account()).map((host) => host.name)).toEqual(['hetzner', 'fixture']);
    expect(first.settings.syncUntickedHosts).toEqual(['hetzner']);
    // Restart: the pending untick is still the user's decision.
    const second = await setup({
      google: first.google,
      storage: first.storage,
      selected: [...first.settings.syncSelectedHosts],
      unticked: [...first.settings.syncUntickedHosts],
    });
    await second.store.syncNow();
    expect((await first.google.account()).map((host) => host.name)).toEqual(['fixture']);
    expect(second.settings.syncUntickedHosts).toEqual([]);
  });

  it('a phone upgraded with the retired known-aliases list: an untouched Sync now keeps an overlapping host', async () => {
    // A #3063 build kept "account aliases this phone has seen" and treated a
    // seen-but-unselected alias as an untick, so a stale entry deleted the
    // phone's overlapping host on an untouched Sync now. #3072 hard-cuts that
    // list: it is deleted on upgrade, and only a saved untick removes a host.
    const first = await setup();
    first.storage.setItem('pocketshell.sync.known-aliases.v1', JSON.stringify(['hetzner', 'fixture']));
    const upgraded = await setup({ google: first.google, storage: first.storage, selected: ['fixture'], unticked: [] });
    await upgraded.store.syncNow();
    expect((await first.google.account()).map((host) => host.name).sort()).toEqual(['fixture', 'hetzner']);
    expect(upgraded.storage.getItem('pocketshell.sync.known-aliases.v1')).toBeNull();
  });

  it('the legacy Account screen\'s Sync now keeps account hosts and their fields the same way', async () => {
    const { google, hosts, sync, settings } = await setup();
    const result = await sync.syncNow({
      localHosts: await hosts.list(),
      selected: settings.syncSelectedHosts,
      unticked: settings.syncUntickedHosts,
      passphrase: PASS,
      onSelection: (selection) => {
        settings.syncSelectedHosts = selection.checked;
        settings.syncUntickedHosts = selection.unticked;
      },
    });
    expect(result).toMatchObject({ kind: 'synced' });
    // Core's rule ticks every account host in the account's own order.
    if (result.kind === 'synced') expect(result.hosts.map((host) => host.name)).toEqual(['hetzner', 'fixture']);
    const account = await google.account();
    expect(account.map((host) => host.name)).toEqual(['hetzner', 'fixture']);
    expect(account.find((host) => host.name === 'hetzner')).toMatchObject({ hostname: 'hetzner.phone.lan', identityFile: '~/.ssh/id_ed25519', proxyJump: 'bastion' });
    expect(settings.syncSelectedHosts).toEqual(expect.arrayContaining(['hetzner', 'fixture']));
  });

  it('the legacy Account screen\'s untick is one-shot too: its push spends it, and a host another device re-adds is kept', async () => {
    const { google, hosts, sync, settings } = await setup();
    const legacySyncNow = async () => sync.syncNow({
      localHosts: await hosts.list(),
      selected: settings.syncSelectedHosts,
      unticked: settings.syncUntickedHosts,
      passphrase: PASS,
      onSelection: (selection) => {
        settings.syncSelectedHosts = selection.checked;
        settings.syncUntickedHosts = selection.unticked;
      },
    });
    settings.syncUntickedHosts = ['hetzner'];
    expect((await legacySyncNow()).kind).toBe('synced');
    expect((await google.account()).map((host) => host.name)).toEqual(['fixture']);
    expect(settings.syncUntickedHosts).toEqual([]);
    await google.writeElsewhere(ACCOUNT);
    expect((await legacySyncNow()).kind).toBe('synced');
    expect((await google.account()).map((host) => host.name).sort()).toEqual(['fixture', 'hetzner']);
  });

  it('after a restart the account hosts come back once the passphrase unlocks them, with nothing uploaded', async () => {
    const { google, storage, hosts } = await setup();
    const version = google.slot!.version;
    // A restart: a new adapter, no decrypted copy anywhere.
    const restarted = createAndroidSync({ native: google, storage, localHosts: () => hosts.list(), kdfIterations: FAST });
    expect(restarted.accountHosts()).toBeNull();
    const unlocked = await restarted.unlock(PASS);
    expect(unlocked.map((host) => host.name)).toEqual(['hetzner', 'fixture']);
    expect(restarted.accountHosts()?.map((host) => host.name)).toEqual(['hetzner', 'fixture']);
    expect(google.slot!.version).toBe(version);
    expect(JSON.stringify([...storage.v])).not.toContain('135.181.114.209');
    await expect(restarted.unlock('wrong')).rejects.toThrow('Wrong sync passphrase');
  });
});
