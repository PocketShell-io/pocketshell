/** Native trust persistence and vault binding for the shared trusted-server UI. */
import { trustedHostSlot, type KeyVaultStore, type TrustedHostRecord } from '@pocketshell/core';
import type { GatewayPairingRow } from '@/native/gatewayPairing';
import type { AndroidHostStore } from './hostStore';

export interface NativeTrustedHosts {
  installTrustedHost(options: { host: TrustedHostRecord }): Promise<void>;
  trustedRevisions(options: { accountId: string; vaultId: string }): Promise<{ floors: Record<string, number> }>;
  saveTrustedRevision(options: { accountId: string; vaultId: string; slot: string; revision: number }): Promise<void>;
}
const descriptorFields: readonly (keyof TrustedHostRecord)[] = [
  'accountId', 'vaultId', 'epoch', 'revision', 'version', 'serverUrl', 'deviceId',
  'hostAlias', 'username', 'keyId', 'sshHostKey', 'devicePublicKey', 'status', 'updatedAt',
];
const sameDescriptor = (left: TrustedHostRecord, right: TrustedHostRecord) =>
  descriptorFields.every((field) => left[field] === right[field]);

export function createAndroidTrustedHosts(deps: {
  store: KeyVaultStore; accountId(): Promise<string | null>; native: NativeTrustedHosts; hosts: AndroidHostStore;
}) {
  const account = async (expected: string) => {
    if (await deps.accountId() !== expected) throw new Error('The signed-in account changed. Reload servers.');
  };
  const revisions = {
    async load(accountId: string, vaultId: string): Promise<Record<string, number>> {
      await account(accountId);
      const { floors } = await deps.native.trustedRevisions({ accountId, vaultId });
      if (!floors || typeof floors !== 'object' || Array.isArray(floors) || Object.entries(floors).some(([slot, rev]) => !/^th-[A-Za-z0-9_-]{22}$/.test(slot) || !Number.isSafeInteger(rev) || rev < 1)) throw new Error('The trusted host revision store is unreadable.');
      await account(accountId);
      return floors;
    },
    async save(accountId: string, vaultId: string, slot: string, revision: number) {
      await account(accountId);
      await deps.native.saveTrustedRevision({ accountId, vaultId, slot, revision });
      await account(accountId);
    },
  };
  async function install(host: TrustedHostRecord): Promise<void> {
    await account(host.accountId);
    const store = deps.store;
    if (store.state !== 'unlocked' || store.vaultId !== host.vaultId || store.epoch !== host.epoch || host.status !== 'active') throw new Error('Unlock the current vault to connect to this server.');
    const floors = await revisions.load(host.accountId, host.vaultId);
    const current = await store.getTrustedHost(host.accountId, host, floors[await trustedHostSlot(host)] ?? 0);
    if (current.kind !== 'ok' || !sameDescriptor(current.host, host)) throw new Error('This server trust record changed. Reload servers.');
    const binding = store.getHostBinding(host.hostAlias);
    if (!binding || binding.gatewayAuth !== 'managed' || binding.keyId !== host.keyId) throw new Error('This server has no matching vault key.');
    await account(host.accountId);
    await deps.native.installTrustedHost({ host });
    await account(host.accountId);
    if (store.state !== 'unlocked' || store.vaultId !== host.vaultId || store.epoch !== host.epoch) throw new Error('The vault changed while connecting.');
    deps.hosts.save({ name: host.hostAlias, hostname: host.deviceId, port: 22, user: host.username,
      keyHandleId: host.keyId, gateway: { serverUrl: host.serverUrl, deviceId: host.deviceId }, gatewayAuth: 'managed',
      trustedVault: { accountId: host.accountId, vaultId: host.vaultId, epoch: host.epoch, keyId: host.keyId, revision: host.revision } });
  }
  async function validatePairing(row: GatewayPairingRow): Promise<void> {
    const accountId = await deps.accountId();
    if (!accountId || deps.store.state !== 'unlocked' || deps.store.vaultId !== row.vaultId || deps.store.epoch !== row.vaultEpoch) throw new Error('Unlock the current account vault to connect to this server.');
    const floors = await revisions.load(accountId, row.vaultId!);
    const current = await deps.store.getTrustedHost(accountId, row, Math.max(row.trustedRevision ?? 0, floors[await trustedHostSlot(row)] ?? 0));
    if (current.kind !== 'ok' || current.host.status !== 'active' || current.host.keyId !== row.vaultKeyId
      || current.host.hostAlias !== row.hostAlias || current.host.username !== row.username) throw new Error('This server trust record is unavailable or changed. Reload servers.');
    await install(current.host);
  }
  return { revisions, install, validatePairing };
}
