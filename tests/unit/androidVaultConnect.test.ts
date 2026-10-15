import { describe, expect, it, vi } from 'vitest';
import { ConnectionController, type HostEntry, type SshCapability, type SshConnectOptions } from '@pocketshell/core';
import type { GatewayPairingRow } from '@/native/gatewayPairing';
import { createAndroidPlatform } from '@/platform/android/androidApi';
import { AndroidHostStore, ANDROID_HOSTS_STORAGE_KEY } from '@/platform/android/hostStore';
import { matchAccountHost } from '@/platform/android/accountHosts';
import { createLocalTrustStore } from '@/platform/android/trustStore';
import { readFileSync } from 'node:fs';
import { KeyVaultStore, type VaultSlotTransport } from '@pocketshell/core';
import { createAndroidVault, type VaultCore, type UnlockedVault, type VaultNative } from '@/vault/androidVault';
import { createStoreVaultCore } from '@/vault/coreVault';
import { FakeNative, HOST_KEY, MemoryStorage } from './support/androidFakeNative';

/**
 * Key-vault W4 (G10 reproduction first). The maintainer's scenario: the
 * Hetzner key was saved on the web account, the phone is signed in and the
 * vault is unlocked, yet the phone (a) never had the key, so tapping the
 * account host opened the "Choose a key" picker, and (b) a gateway dial
 * prompted for a key too. With the vault, an unlocked user never lands in a
 * key picker: the account host's vault key is dialled from memory, a locked
 * vault asks to unlock, and a host without a key says what to do.
 */

const PEM = '-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\n-----END OPENSSH PRIVATE KEY-----\n';
const GW = { serverUrl: 'wss://gateway.example', deviceId: 'dev-1' };
const PAIRED_KEY = '00000000-0000-4000-8000-0000000000aa';
const PAIRING: GatewayPairingRow = {
  serverUrl: GW.serverUrl,
  deviceId: GW.deviceId,
  fingerprintSha256: 'SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU',
  pinKind: 'host-key',
  keyHandleId: PAIRED_KEY,
};

function accountHost(name: string, hostname: string, extra: Partial<HostEntry> & Record<string, unknown> = {}): HostEntry {
  return {
    name, hostname, port: 22, user: 'alexey', identityFile: null, proxyJump: null, forwardAgent: false,
    localForwards: [], remoteForwards: [], fromConfig: false, ...extra,
  } as HostEntry;
}

const HETZNER = accountHost('hetzner', '135.181.114.209');
const GATEWAY_HOST = accountHost('laptop', 'laptop.gw', { gateway: GW });

type Binding = { keyId: string; gatewayAuth: 'host' | 'managed' | 'both' };

/** What core's KeyVaultStore hands an unlocked client, over a fixed in-memory index. */
function unlockedVault(bindings: Record<string, Binding>, secrets: Record<string, { kind: 'key'; pem: string } | { kind: 'password'; password: string }>): UnlockedVault {
  return {
    vaultId: 'vault-1',
    epoch: 1,
    binding: (alias) => bindings[alias] ?? null,
    syncPassphrase: () => 'sync words',
    decryptForHost: vi.fn(async (alias: string) => {
      const binding = bindings[alias];
      if (!binding) throw new Error('unbound');
      return secrets[binding.keyId]!;
    }),
  };
}

class FakeVaultNative implements VaultNative {
  readonly held: string[] = [];
  readonly released: Array<string[] | null> = [];
  remembered = new Map<string, string>();
  async holdVaultKey({ privateKeyPem }: { privateKeyPem: string }) {
    this.held.push(privateKeyPem);
    return { handleId: `ephemeral-${this.held.length}`, algorithm: 'ssh-ed25519', fingerprintSha256: 'SHA256:x' };
  }
  async releaseVaultKeys(options: { handleIds?: string[] } = {}) {
    this.released.push(options.handleIds ?? null);
    return { released: 0 };
  }
  async remember({ scope, record }: { scope: string; record: string }) { this.remembered.set(scope, record); return { remembered: true }; }
  async recall({ scope }: { scope: string }) { return { record: this.remembered.get(scope) ?? null }; }
  async forget({ scope }: { scope: string }) { this.remembered.delete(scope); return { forgotten: true }; }
  async forgetAll() { this.remembered.clear(); return { forgotten: true }; }
}

/** Gateway dials need the pin receipt the Java plugin returns. */
class GatewayNative extends FakeNative {
  override capability(): SshCapability {
    const base = super.capability();
    return {
      ...base,
      gatewayTransport: true,
      connect: async (options: SshConnectOptions) => {
        const result = await base.connect(options);
        return options.gateway ? { ...result, gatewayHostKeyVerified: true as const } : result;
      },
    } as SshCapability;
  }
}

/** Core's store as the dial path sees it: whoever unlocks it (the shared sheet), subscribers hear. */
class FakeCore implements VaultCore {
  opened: UnlockedVault | null = null;
  readonly locks: number[] = [];
  private readonly listeners = new Set<() => void>();
  constructor(private readonly exists: boolean) {}
  async present() { return this.exists; }
  async unlockRemembered() { return { kind: 'stale' as const }; }
  current() { return this.opened; }
  subscribe(listener: () => void) { this.listeners.add(listener); return () => this.listeners.delete(listener); }
  lock() { this.locks.push(1); this.opened = null; this.listeners.forEach((listener) => listener()); }
  open(vault: UnlockedVault) { this.opened = vault; this.listeners.forEach((listener) => listener()); }
}

interface Setup {
  account?: HostEntry[];
  vault?: UnlockedVault | 'absent' | 'locked';
  /** What the unlock sheet does when asked: unlock with this vault, or decline. */
  onUnlockAsked?: 'unlock' | 'decline';
  /** The vault the passphrase opens when the test starts locked. */
  unlocksTo?: UnlockedVault;
}

async function setup(options: Setup = {}) {
  const native = new GatewayNative();
  const storage = new MemoryStorage();
  storage.setItem(ANDROID_HOSTS_STORAGE_KEY, '[]');
  const trust = createLocalTrustStore(storage);
  await trust.record('alexey@135.181.114.209:22', { kind: 'wire-key', ...HOST_KEY });
  const hosts = new AndroidHostStore({ storage, readLegacyHosts: async () => [] });
  const account = options.account ?? [HETZNER, GATEWAY_HOST];
  const adopt = vi.fn(async () => false);
  const vaultNative = new FakeVaultNative();
  const target = typeof options.vault === 'object' ? options.vault : options.unlocksTo ?? unlockedVault({}, {});
  const core = new FakeCore(options.vault !== 'absent');
  const askUnlock = vi.fn(async (_label: string) => {
    if (options.onUnlockAsked !== 'unlock') return false;
    core.open(target);
    return true;
  });
  const vault = createAndroidVault({ core, native: vaultNative, scope: async () => 'phone@example.com', askUnlock });
  await vault.refresh();
  if (typeof options.vault === 'object') core.open(options.vault);
  const created = createAndroidPlatform({
    createController: () => new ConnectionController({ capability: native.capability(), trustStore: trust, retryDelaysMs: [0] }),
    hosts,
    backgroundGraceMs: () => 60_000,
    addHostRoute: '/x',
    accountHosts: {
      // The production matcher (alias first, then address), over the account entries.
      find: async (request) => matchAccountHost(account, request),
      adopt,
    },
    vault,
    gatewayTransport: async () => true,
    gatewayPairings: { list: async () => [PAIRING] },
  });
  return { native, created, adopt, vault, vaultNative, askUnlock, hosts, core };
}

/** The account as the web mirrored it: the gateway host's entry carries `gatewayAuth` (design section 7). */
const mirroredAccount = (policy?: 'host' | 'managed' | 'both') =>
  [HETZNER, accountHost('laptop', 'laptop.gw', { gateway: GW, ...(policy ? { gatewayAuth: policy } : {}) })];

const direct = { host: HETZNER.hostname, port: 22, user: 'alexey', hostAlias: 'hetzner' } as never;
const viaGateway = { host: GATEWAY_HOST.hostname, port: 22, user: 'alexey', hostAlias: 'laptop', gateway: GW } as never;


/** The account's slots as the web wrote them; `readOnly` is the phone's view. */
class AccountSlots implements VaultSlotTransport {
  readonly slots = new Map<string, { version: number; data: string }>();
  readonly writes: string[] = [];
  constructor(private readonly readOnly = false, shared?: Map<string, { version: number; data: string }>) {
    if (shared) this.slots = shared;
  }
  async pull(slot: string) {
    const stored = this.slots.get(slot);
    return stored ? { kind: 'ok' as const, ...stored } : { kind: 'absent' as const };
  }
  async push(slot: string, data: string, baseVersion: number) {
    this.writes.push(slot);
    if (this.readOnly) return { kind: 'error' as const, message: 'read-only' };
    const current = this.slots.get(slot)?.version ?? 0;
    if (baseVersion !== current) return { kind: 'conflict' as const, currentVersion: current };
    this.slots.set(slot, { version: current + 1, data });
    return { kind: 'ok' as const, version: current + 1 };
  }
  async delete(slot: string) {
    this.writes.push(`delete:${slot}`);
    if (!this.readOnly) this.slots.delete(slot);
  }
  async list() {
    return [];
  }
}

describe('Android dials account and gateway hosts with the account key vault (key-vault W4)', () => {
  it('dials a web-saved key for an account host from memory, with no key picker and nothing saved', async () => {
    const vault = unlockedVault({ hetzner: { keyId: 'k1', gatewayAuth: 'host' } }, { k1: { kind: 'key', pem: PEM } });
    const { native, created, adopt, vaultNative, hosts } = await setup({ vault });
    const result = await created.api.ssh.connect(direct);
    expect(adopt).not.toHaveBeenCalled();
    expect(result).toEqual({ ok: true, connectionId: expect.any(String) });
    expect(vaultNative.held).toEqual([PEM]);
    expect(native.connects[0]!.credential).toEqual({ kind: 'key-handle', handleId: 'ephemeral-1' });
    // The key never lands in the phone's host store or its key vault.
    expect(hosts.savedHosts()).toEqual([]);
    created.dispose();
  });

  it('dials a vault password entry as a password credential', async () => {
    const vault = unlockedVault({ hetzner: { keyId: 'p1', gatewayAuth: 'host' } }, { p1: { kind: 'password', password: 'pw' } });
    const { native, created, adopt, vaultNative } = await setup({ vault });
    await created.api.ssh.connect(direct);
    expect(adopt).not.toHaveBeenCalled();
    expect(vaultNative.held).toEqual([]);
    expect(native.connects[0]!.credential).toEqual({ kind: 'password', password: 'pw' });
    created.dispose();
  });

  it('a locked vault asks to unlock, never for a key, then dials the vault key', async () => {
    const vault = unlockedVault({ hetzner: { keyId: 'k1', gatewayAuth: 'host' } }, { k1: { kind: 'key', pem: PEM } });
    const { native, created, adopt, askUnlock } = await setup({ vault: 'locked', onUnlockAsked: 'unlock', unlocksTo: vault });
    const result = await created.api.ssh.connect(direct);
    expect(askUnlock).toHaveBeenCalledTimes(1);
    expect(adopt).not.toHaveBeenCalled();
    expect(result.ok).toBe(true);
    expect(native.connects).toHaveLength(1);
    created.dispose();
  });

  it('declining to unlock dials nothing and says to unlock, never offering a key picker', async () => {
    const { native, created, adopt } = await setup({ vault: 'locked', onUnlockAsked: 'decline' });
    const result = await created.api.ssh.connect(direct);
    expect(adopt).not.toHaveBeenCalled();
    expect(result).toEqual({ ok: false, error: 'Unlock your key vault to connect to “hetzner”. Nothing was dialled.' });
    expect(native.connects).toHaveLength(0);
    created.dispose();
  });

  it('an unlocked vault without a key for the host says to assign one on the web, never a picker', async () => {
    const { native, created, adopt } = await setup({ vault: unlockedVault({}, {}) });
    const result = await created.api.ssh.connect(direct);
    expect(adopt).not.toHaveBeenCalled();
    expect(result).toEqual({
      ok: false,
      error: 'No key in your key vault is assigned to “hetzner”. Assign one on the PocketShell web app (Keys), then connect again.',
    });
    expect(native.connects).toHaveLength(0);
    created.dispose();
  });

  it('an account without a vault keeps the phone-key prompt', async () => {
    const { created, adopt } = await setup({ vault: 'absent' });
    await created.api.ssh.connect(direct);
    expect(adopt).toHaveBeenCalledTimes(1);
    created.dispose();
  });
  it.each([
    ['managed', 'the vault key'],
    ['both', 'the vault key first'],
  ] as const)('gatewayAuth %s with an unlocked vault dials %s through the pairing, with no key prompt', async (policy, _what) => {
    const vault = unlockedVault({ laptop: { keyId: 'k1', gatewayAuth: policy } }, { k1: { kind: 'key', pem: PEM } });
    const { native, created, adopt, askUnlock } = await setup({ vault });
    const result = await created.api.ssh.connect(viaGateway);
    expect(result).toEqual({ ok: true, connectionId: expect.any(String) });
    expect(adopt).not.toHaveBeenCalled();
    expect(askUnlock).not.toHaveBeenCalled();
    const dial = native.connects[0]!;
    expect(dial.gateway).toEqual(GW);
    expect(dial.credential).toEqual({ kind: 'key-handle', handleId: 'ephemeral-1' });
    created.dispose();
  });

  it('gatewayAuth host uses the pairing key and needs no vault key', async () => {
    const vault = unlockedVault({ laptop: { keyId: 'k1', gatewayAuth: 'host' } }, { k1: { kind: 'key', pem: PEM } });
    const { native, created, vaultNative } = await setup({ vault });
    await created.api.ssh.connect(viaGateway);
    expect(vaultNative.held).toEqual([]);
    expect(native.connects[0]!.credential).toEqual({ kind: 'key-handle', handleId: PAIRED_KEY });
    created.dispose();
  });

  it('a gateway host mirrored as gatewayAuth host dials the pairing key without asking to unlock', async () => {
    const { native, created, askUnlock } = await setup({ vault: 'locked', onUnlockAsked: 'decline', account: mirroredAccount('host') });
    // Exactly what core's store sends: transport markers, never the policy.
    await created.api.ssh.connect(viaGateway);
    expect(askUnlock).not.toHaveBeenCalled();
    expect(native.connects[0]!.credential).toEqual({ kind: 'key-handle', handleId: PAIRED_KEY });
    created.dispose();
  });

  it('a managed gateway host whose vault entry is a password is refused before any dial', async () => {
    const vault = unlockedVault({ laptop: { keyId: 'p1', gatewayAuth: 'managed' } }, { p1: { kind: 'password', password: 'pw' } });
    const { native, created } = await setup({ vault });
    const result = await created.api.ssh.connect(viaGateway);
    expect(result).toEqual({
      ok: false,
      error: '“laptop” reaches its host through the PocketShell gateway, which needs an SSH key, but its vault entry is a password. Nothing was dialled.',
    });
    expect(native.connects).toHaveLength(0);
    created.dispose();
  });
  it('lock releases every native vault key handle and forgets the decrypted vault', async () => {
    const unlocked = unlockedVault({ hetzner: { keyId: 'k1', gatewayAuth: 'host' } }, { k1: { kind: 'key', pem: PEM } });
    const { created, vault, vaultNative, core } = await setup({ vault: unlocked });
    await created.api.ssh.connect(direct);
    await vault.lock();
    expect(vaultNative.released).toContainEqual(null);
    expect(core.current()).toBeNull();
    expect(vault.state.status).toBe('locked');
    created.dispose();
  });

  it('reuses one native handle per vault key while unlocked', async () => {
    const unlocked = unlockedVault({ hetzner: { keyId: 'k1', gatewayAuth: 'host' } }, { k1: { kind: 'key', pem: PEM } });
    const { created, vaultNative } = await setup({ vault: unlocked });
    await created.api.ssh.connect(direct);
    await created.api.ssh.connect(direct);
    expect(vaultNative.held).toHaveLength(1);
    created.dispose();
  });

  it('end to end with core: a key the web stored in the vault is dialled by the phone after one passphrase, with no writes', async () => {
    // The web: create the vault, upload the Docker fixture key, bind the host.
    const web = new AccountSlots();
    const author = new KeyVaultStore({ transport: web, kdf: { m: 32768, t: 3, p: 1 } });
    expect((await author.createVault('vault words', { syncPassphrase: 'sync words' })).kind).toBe('ok');
    const pem = readFileSync('tests/docker/test_key', 'utf8');
    const added = await author.addKey(pem, 'hetzner key');
    if (added.kind !== 'ok') throw new Error(`addKey: ${added.kind}`);
    expect((await author.bindHost('hetzner', added.entry.keyId, 'host')).kind).toBe('ok');
    author.lock();

    // The phone: core's store over a read-only view of the same account.
    const phone = new AccountSlots(true, web.slots);
    const native = new GatewayNative();
    const storage = new MemoryStorage();
    storage.setItem(ANDROID_HOSTS_STORAGE_KEY, '[]');
    const trust = createLocalTrustStore(storage);
    await trust.record('alexey@135.181.114.209:22', { kind: 'wire-key', ...HOST_KEY });
    const vaultNative = new FakeVaultNative();
    const synced: Array<string | null> = [];
    const phoneStore = new KeyVaultStore({ transport: phone });
    const vault = createAndroidVault({
      core: createStoreVaultCore(phoneStore),
      native: vaultNative,
      scope: async () => 'phone@example.com',
      // The shared sheet unlocks the same store the dial path reads.
      askUnlock: async () => (await phoneStore.unlock('vault words')).kind === 'ok',
      onUnlocked: (syncPassphrase) => { synced.push(syncPassphrase); },
    });
    const adopt = vi.fn(async () => false);
    const created = createAndroidPlatform({
      createController: () => new ConnectionController({ capability: native.capability(), trustStore: trust, retryDelaysMs: [0] }),
      hosts: new AndroidHostStore({ storage, readLegacyHosts: async () => [] }),
      backgroundGraceMs: () => 60_000,
      addHostRoute: '/x',
      accountHosts: { find: async () => HETZNER, adopt },
      vault,
    });
    const result = await created.api.ssh.connect(direct);
    expect(result).toEqual({ ok: true, connectionId: expect.any(String) });
    expect(adopt).not.toHaveBeenCalled();
    expect(synced).toEqual(['sync words']);
    // The decrypted key reached native exactly once, as the web uploaded it.
    expect(vaultNative.held).toHaveLength(1);
    expect(vaultNative.held[0]).toContain(pem.split('\n')[1]);
    expect(native.connects[0]!.credential).toEqual({ kind: 'key-handle', handleId: 'ephemeral-1' });
    expect(phone.writes).toEqual([]);
    await vault.lock();
    expect(vaultNative.released).toContainEqual(null);
    expect(phoneStore.state).toBe('locked');
    created.dispose();
  }, 60_000);

  it.each([
    // [mirrored policy on the host entry, sheet answer, binding the vault holds, expected credential or refusal]
    ['host', 'cancel', 'host', 'pairing', false],
    ['host', 'unlock', 'host', 'pairing', false],
    ['both', 'cancel', 'both', 'pairing', true],
    ['both', 'unlock', 'both', 'vault', true],
    [undefined, 'cancel', 'both', 'pairing', true],
    [undefined, 'unlock', 'both', 'vault', true],
    ['managed', 'cancel', 'managed', 'refused', true],
    ['managed', 'unlock', 'managed', 'vault', true],
  ] as const)('locked vault, gateway host mirrored %s, user answers %s: binding %s dials %s', async (mirrored, answer, bound, expected, asks) => {
    const vault = unlockedVault({ laptop: { keyId: 'k1', gatewayAuth: bound } }, { k1: { kind: 'key', pem: PEM } });
    const { native, created, adopt, askUnlock } = await setup({
      vault: 'locked', onUnlockAsked: answer === 'unlock' ? 'unlock' : 'decline', unlocksTo: vault, account: mirroredAccount(mirrored),
    });
    const result = await created.api.ssh.connect(viaGateway);
    expect(askUnlock).toHaveBeenCalledTimes(asks ? 1 : 0);
    expect(adopt).not.toHaveBeenCalled();
    if (expected === 'refused') {
      expect(result).toEqual({ ok: false, error: 'Unlock your key vault to connect to “laptop”. Nothing was dialled.' });
      expect(native.connects).toHaveLength(0);
    } else {
      expect(result).toEqual({ ok: true, connectionId: expect.any(String) });
      expect(native.connects[0]!.gateway).toEqual(GW);
      expect(native.connects[0]!.credential).toEqual({
        kind: 'key-handle',
        handleId: expected === 'vault' ? 'ephemeral-1' : PAIRED_KEY,
      });
    }
    created.dispose();
  });

  it.each([
    ['managed', 'refused'],
    ['both', 'pairing'],
    [undefined, 'pairing'],
  ] as const)('unlocked vault with no key for a gateway host mirrored %s: %s', async (mirrored, expected) => {
    const { native, created } = await setup({ vault: unlockedVault({}, {}), account: mirroredAccount(mirrored) });
    const result = await created.api.ssh.connect(viaGateway);
    if (expected === 'refused') {
      expect(result).toEqual({
        ok: false,
        error: 'No key in your key vault is assigned to “laptop”. Assign one on the PocketShell web app (Keys), then connect again.',
      });
      expect(native.connects).toHaveLength(0);
    } else {
      expect(native.connects[0]!.credential).toEqual({ kind: 'key-handle', handleId: PAIRED_KEY });
    }
    created.dispose();
  });

  it('an account gateway host carries its mirrored gatewayAuth from the synced entry to the dial', async () => {
    const { native, created, askUnlock } = await setup({ vault: 'locked', onUnlockAsked: 'decline', account: mirroredAccount('host') });
    // Picked from "From your account" without an alias: matched by address.
    await created.api.ssh.connect({ host: 'laptop.gw', port: 22, user: 'alexey', gateway: GW } as never);
    expect(askUnlock).not.toHaveBeenCalled();
    expect(native.connects[0]!.credential).toEqual({ kind: 'key-handle', handleId: PAIRED_KEY });
    created.dispose();
  });

  it('a vault key still being handed to native when the vault locks is released, never kept', async () => {
    const unlocked = unlockedVault({ hetzner: { keyId: 'k1', gatewayAuth: 'host' } }, { k1: { kind: 'key', pem: PEM } });
    const { created, vault, vaultNative } = await setup({ vault: unlocked });
    let finish: () => void = () => undefined;
    const original = vaultNative.holdVaultKey.bind(vaultNative);
    vaultNative.holdVaultKey = (options) => new Promise((resolve) => { finish = () => resolve(original(options)); });
    const dial = created.api.ssh.connect(direct);
    await new Promise((resolve) => setTimeout(resolve, 0));
    await vault.lock();
    finish();
    const result = await dial;
    expect(result.ok).toBe(false);
    expect(vaultNative.released).toContainEqual(['ephemeral-1']);
    created.dispose();
  });

  it('two account hosts at the same address each dial with their own vault key, chosen by the alias core sends', async () => {
    // Same address and user as hetzner (the fixture trusts that host key).
    const box = (name: string) => accountHost(name, HETZNER.hostname);
    const vault = unlockedVault(
      { 'box-a': { keyId: 'ka', gatewayAuth: 'host' }, 'box-b': { keyId: 'kb', gatewayAuth: 'host' } },
      { ka: { kind: 'key', pem: 'PEM-A' }, kb: { kind: 'key', pem: 'PEM-B' } },
    );
    for (const [alias, pem] of [['box-b', 'PEM-B'], ['box-a', 'PEM-A']] as const) {
      const { native, created, vaultNative } = await setup({ vault, account: [box('box-a'), box('box-b')] });
      const result = await created.api.ssh.connect({ host: HETZNER.hostname, port: 22, user: 'alexey', hostAlias: alias } as never);
      expect(result.ok).toBe(true);
      expect(vaultNative.held).toEqual([pem]);
      expect(native.connects).toHaveLength(1);
      created.dispose();
    }
  });

  it('two gateway entries at the same display address keep their own policy: host dials the pairing key, managed refuses', async () => {
    const entries = [
      accountHost('gw-host', 'shared.gw', { gateway: GW, gatewayAuth: 'host' }),
      accountHost('gw-managed', 'shared.gw', { gateway: GW, gatewayAuth: 'managed' }),
    ];
    const payload = (alias: string) => ({ host: 'shared.gw', port: 22, user: 'alexey', hostAlias: alias, gateway: GW }) as never;
    // Locked vault, the user closes the sheet whenever it is shown.
    const hostSide = await setup({ vault: 'locked', onUnlockAsked: 'decline', account: entries });
    expect(await hostSide.created.api.ssh.connect(payload('gw-host'))).toEqual({ ok: true, connectionId: expect.any(String) });
    expect(hostSide.askUnlock).not.toHaveBeenCalled();
    expect(hostSide.native.connects[0]!.credential).toEqual({ kind: 'key-handle', handleId: PAIRED_KEY });
    hostSide.created.dispose();
    const managedSide = await setup({ vault: 'locked', onUnlockAsked: 'decline', account: entries });
    expect(await managedSide.created.api.ssh.connect(payload('gw-managed'))).toEqual({
      ok: false,
      error: 'Unlock your key vault to connect to “gw-managed”. Nothing was dialled.',
    });
    expect(managedSide.native.connects).toHaveLength(0);
    managedSide.created.dispose();
  });

  it('an alias no account entry carries still finds the host by its address', async () => {
    const vault = unlockedVault({ hetzner: { keyId: 'k1', gatewayAuth: 'host' } }, { k1: { kind: 'key', pem: PEM } });
    const { created, vaultNative } = await setup({ vault });
    const result = await created.api.ssh.connect({ host: HETZNER.hostname, port: 22, user: 'alexey', hostAlias: 'renamed-elsewhere' } as never);
    expect(result.ok).toBe(true);
    expect(vaultNative.held).toEqual([PEM]);
    created.dispose();
  });
});
