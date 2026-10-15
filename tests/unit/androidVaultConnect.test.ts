import { describe, expect, it, vi } from 'vitest';
import { ConnectionController, type HostEntry, type SshCapability, type SshConnectOptions } from '@pocketshell/core';
import type { GatewayPairingRow } from '@/native/gatewayPairing';
import { createAndroidPlatform } from '@/platform/android/androidApi';
import { AndroidHostStore, ANDROID_HOSTS_STORAGE_KEY } from '@/platform/android/hostStore';
import { createLocalTrustStore } from '@/platform/android/trustStore';
import { createAndroidVault, type VaultCore, type UnlockedVault, type VaultNative } from '@/vault/androidVault';
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
    exportRemembered: async () => '{"vaultId":"vault-1","epoch":1,"dek":"x"}',
    close: vi.fn(),
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
  const core: VaultCore = {
    present: async () => options.vault !== 'absent',
    unlock: async () => ({ kind: 'unlocked', vault: target }),
    unlockRemembered: async () => ({ kind: 'stale' }),
  };
  const askUnlock = vi.fn(async () => {
    if (options.onUnlockAsked !== 'unlock') return false;
    return (await vault.unlock('correct horse', false)).kind === 'unlocked';
  });
  const vault = createAndroidVault({ core, native: vaultNative, scope: async () => 'phone@example.com', askUnlock });
  await vault.refresh();
  if (typeof options.vault === 'object') await vault.unlock('correct horse', false);
  const created = createAndroidPlatform({
    createController: () => new ConnectionController({ capability: native.capability(), trustStore: trust, retryDelaysMs: [0] }),
    hosts,
    backgroundGraceMs: () => 60_000,
    addHostRoute: '/x',
    accountHosts: {
      find: async (request) => account.find((h) => h.hostname === request.host && h.port === (request.port ?? 22)) ?? null,
      adopt,
    },
    vault,
    gatewayTransport: async () => true,
    gatewayPairings: { list: async () => [PAIRING] },
  });
  return { native, created, adopt, vault, vaultNative, askUnlock, hosts };
}

const direct = { host: HETZNER.hostname, port: 22, user: 'alexey', hostAlias: 'hetzner' } as never;
const viaGateway = { host: GATEWAY_HOST.hostname, port: 22, user: 'alexey', hostAlias: 'laptop', gateway: GW } as never;

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
    const { native, created, askUnlock } = await setup({ vault: 'locked', onUnlockAsked: 'decline' });
    await created.api.ssh.connect({ ...(viaGateway as object), gatewayAuth: 'host' } as never);
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
    const { created, vault, vaultNative } = await setup({ vault: unlocked });
    await created.api.ssh.connect(direct);
    await vault.lock();
    expect(vaultNative.released).toEqual([null]);
    expect(unlocked.close).toHaveBeenCalled();
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
});
