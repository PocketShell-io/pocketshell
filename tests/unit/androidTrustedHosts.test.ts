import { describe, it, expect, vi } from 'vitest';
import { trustedHostSlot, type KeyVaultStore, type TrustedHostRecord } from '@pocketshell/core';
import { AndroidHostStore } from '@/platform/android/hostStore';
import { createAndroidTrustedHosts } from '@/platform/android/trustedHosts';

const host: TrustedHostRecord = { accountId: 'google:subject', vaultId: 'A'.repeat(22), epoch: 2, revision: 4, version: 4,
  serverUrl: 'wss://gateway.pocketshell.io', deviceId: 'trusted-device', hostAlias: 'work', username: 'orin', keyId: 'B'.repeat(22),
  sshHostKey: 'ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAINsI+xMBCARP+feB9XlS5sazCADY9SpfgrwI+d4CNIW+',
  devicePublicKey: 'C'.repeat(43), status: 'active', updatedAt: '2026-11-05T00:00:00.000Z' };
function setup() {
  let account: string | null = host.accountId;
  const values = new Map<string, string>();
  const hosts = new AndroidHostStore({ storage: { getItem: (key) => values.get(key) ?? null, setItem: (key, value) => { values.set(key, value); } }, readLegacyHosts: async () => [] });
  const store = { state: 'unlocked', vaultId: host.vaultId, epoch: host.epoch,
    getTrustedHost: vi.fn(async () => ({ kind: 'ok', host })),
    getHostBinding: vi.fn(() => ({ keyId: host.keyId, gatewayAuth: 'managed' })) };
  const native = { trustedRevisions: vi.fn(async () => ({ floors: {} })), saveTrustedRevision: vi.fn(async () => {}), installTrustedHost: vi.fn(async () => {}) };
  const api = createAndroidTrustedHosts({ store: store as unknown as KeyVaultStore, accountId: async () => account, native, hosts });
  return { api, native, hosts, store, account: (next: string | null) => { account = next; } };
}
describe('Android account vault trusted server installation', () => {
  it('installs a validated vault reference and managed route without a local phone key', async () => {
    const f = setup(); await f.api.install(host);
    expect(f.native.installTrustedHost).toHaveBeenCalledWith({ host });
    expect(f.hosts.savedHosts()[0]).toMatchObject({ gatewayAuth: 'managed', keyHandleId: host.keyId,
      trustedVault: { accountId: host.accountId, vaultId: host.vaultId, epoch: 2, revision: 4 }, gateway: { deviceId: host.deviceId } });
    expect((await f.hosts.list())[0]).toMatchObject({ gatewayAuth: 'managed', gateway: { deviceId: host.deviceId } });
  });
  it('compares authenticated descriptor fields independently of JSON property order', async () => {
    const f = setup();
    const reordered = Object.fromEntries(Object.entries(host).reverse()) as unknown as TrustedHostRecord;
    await f.api.install(reordered);
    expect(f.native.installTrustedHost).toHaveBeenCalledWith({ host: reordered });
  });
  it('refuses a different account before native installation or host save', async () => {
    const f = setup(); f.account('google:other');
    await expect(f.api.install(host)).rejects.toThrow(/account changed/);
    expect(f.native.installTrustedHost).not.toHaveBeenCalled(); expect(f.hosts.savedHosts()).toEqual([]);
  });
  it('refuses locked replaced and stale epoch vaults', async () => {
    for (const change of [{ state: 'locked' }, { vaultId: 'D'.repeat(22) }, { epoch: 3 }]) {
      const f = setup(); Object.assign(f.store, change);
      await expect(f.api.install(host)).rejects.toThrow(/current vault/);
      expect(f.native.installTrustedHost).not.toHaveBeenCalled();
    }
  });
  it('refuses missing invalid rollback or revoked descriptors without phone key fallback', async () => {
    for (const kind of ['absent', 'invalid', 'rollback', 'locked']) {
      const f = setup(); f.store.getTrustedHost.mockResolvedValue({ kind } as never);
      await expect(f.api.install(host)).rejects.toThrow(/trust record changed/);
      expect(f.native.installTrustedHost).not.toHaveBeenCalled(); expect(f.hosts.savedHosts()).toEqual([]);
    }
    await expect(setup().api.install({ ...host, status: 'revoked' })).rejects.toThrow();
  });
  it('refuses unbound mismatched and non-managed vault credentials', async () => {
    for (const binding of [null, { keyId: 'D'.repeat(22), gatewayAuth: 'managed' }, { keyId: host.keyId, gatewayAuth: 'both' }]) {
      const f = setup(); f.store.getHostBinding.mockReturnValue(binding as never);
      await expect(f.api.install(host)).rejects.toThrow(/matching vault key/);
      expect(f.native.installTrustedHost).not.toHaveBeenCalled();
    }
  });
  it('rechecks account after native work and preserves no saved row on an account switch', async () => {
    const f = setup(); f.native.installTrustedHost.mockImplementation(async () => { f.account('google:other'); });
    await expect(f.api.install(host)).rejects.toThrow(/account changed/);
    expect(f.hosts.savedHosts()).toEqual([]);
  });
  it('loads protected revision floors and supplies them to authenticated descriptor verification', async () => {
    const f = setup(); const slot = await trustedHostSlot(host);
    f.native.trustedRevisions.mockResolvedValue({ floors: { [slot]: 5 } });
    f.store.getTrustedHost.mockResolvedValue({ kind: 'rollback' } as never);
    await expect(f.api.install(host)).rejects.toThrow();
    expect(f.store.getTrustedHost).toHaveBeenCalledWith(host.accountId, host, 5);
    expect(f.native.installTrustedHost).not.toHaveBeenCalled();
  });
});
