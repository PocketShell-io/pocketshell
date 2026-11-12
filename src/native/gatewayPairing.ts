import { registerPlugin, type Plugin } from '@capacitor/core';

/**
 * The native `GatewayPairing` plugin (#3086): the phone's saved gateway
 * pairings for the signed-in account. A pairing binds one gateway device to
 * the host-key pin the user provided out of band and to the vault key this
 * phone authenticates with. JS only ever reads public data from it: no pin
 * is accepted from untrusted discovery metadata; authenticated vault
 * descriptors use a separate account-bound installation method. No token crosses
 * the bridge (see android/app/.../GatewayPairingPlugin.java).
 */
export interface GatewayPairingRow {
  serverUrl: string;
  deviceId: string;
  fingerprintSha256: string;
  pinKind: 'host-key' | 'fingerprint';
  keyHandleId: string;
  credentialSource?: 'local' | 'vault';
  vaultId?: string;
  vaultEpoch?: number;
  vaultKeyId?: string;
  trustedRevision?: number;
  hostAlias?: string;
  username?: string;
}

export type NativeGatewayPairingPlugin = Plugin & {
  list(options: { requestId: string }): Promise<unknown>;
};

function validVaultReference(row: Record<string, unknown>): boolean {
  return row.pinKind === 'host-key'
    && typeof row.vaultId === 'string' && /^[A-Za-z0-9_-]{22}$/.test(row.vaultId)
    && typeof row.vaultEpoch === 'number' && Number.isSafeInteger(row.vaultEpoch) && row.vaultEpoch > 0
    && typeof row.vaultKeyId === 'string' && /^[A-Za-z0-9_-]{22}$/.test(row.vaultKeyId)
    && row.vaultKeyId === row.keyHandleId
    && typeof row.trustedRevision === 'number' && Number.isSafeInteger(row.trustedRevision) && row.trustedRevision > 0
    && typeof row.hostAlias === 'string' && row.hostAlias.length > 0
    && typeof row.username === 'string' && /^[A-Za-z_][A-Za-z0-9_.-]{0,63}$/.test(row.username);
}

function isRow(value: unknown): value is GatewayPairingRow {
  if (typeof value !== 'object' || value === null) return false;
  const row = value as Record<string, unknown>;
  return typeof row.serverUrl === 'string' && row.serverUrl !== ''
    && typeof row.deviceId === 'string' && row.deviceId !== ''
    && typeof row.fingerprintSha256 === 'string' && row.fingerprintSha256.startsWith('SHA256:')
    && (row.pinKind === 'host-key' || row.pinKind === 'fingerprint')
    && (row.credentialSource === undefined || row.credentialSource === 'local' || row.credentialSource === 'vault')
    && (row.credentialSource !== 'vault' || validVaultReference(row))
    && typeof row.keyHandleId === 'string' && row.keyHandleId !== '';
}

export function parseGatewayPairingRow(value: unknown): GatewayPairingRow {
  if (!isRow(value)) throw new Error('The saved gateway pairings could not be read.');
  return { serverUrl: value.serverUrl, deviceId: value.deviceId, fingerprintSha256: value.fingerprintSha256,
    pinKind: value.pinKind, keyHandleId: value.keyHandleId,
    ...(value.credentialSource === 'vault' ? { credentialSource: 'vault', vaultId: value.vaultId, vaultEpoch: value.vaultEpoch, vaultKeyId: value.vaultKeyId, trustedRevision: value.trustedRevision, hostAlias: value.hostAlias, username: value.username } : {}) };
}

let listSequence = 0;

/**
 * The current account's pairings. Fails closed: a reply for another request
 * or one that is not a list of well-formed rows is an error, never an empty
 * list a caller could read as "unpaired" and route around. A native refusal
 * (signed out, unreadable store) rejects with its own code.
 */
export async function listGatewayPairings(plugin: NativeGatewayPairingPlugin): Promise<GatewayPairingRow[]> {
  listSequence += 1;
  const requestId = `gateway-pairings-${listSequence}`;
  const reply = await plugin.list({ requestId });
  if (typeof reply !== 'object' || reply === null) throw new Error('The saved gateway pairings could not be read.');
  const { requestId: answered, pairings } = reply as Record<string, unknown>;
  if (answered !== requestId || !Array.isArray(pairings) || !pairings.every(isRow)) {
    throw new Error('The saved gateway pairings could not be read.');
  }
  return pairings.map(parseGatewayPairingRow);
}

export const nativeGatewayPairing = registerPlugin<NativeGatewayPairingPlugin & {
  installTrustedHost(options: { host: import('@pocketshell/core').TrustedHostRecord }): Promise<void>;
  trustedRevisions(options: { accountId: string; vaultId: string }): Promise<{ floors: Record<string, number> }>;
  saveTrustedRevision(options: { accountId: string; vaultId: string; slot: string; revision: number }): Promise<void>;
  serverUrl(options: { requestId: string }): Promise<unknown>;
  devices(options: { requestId: string; expectedAccountEmail: string }): Promise<unknown>;
  pair(options: { requestId: string; expectedAccountEmail: string; serverUrl: string; deviceId: string; hostKey: string; keyHandleId: string }): Promise<unknown>;
}>('GatewayPairing');

/** The installed native plugin's pairings for the signed-in account. */
export const gatewayPairings = { list: () => listGatewayPairings(nativeGatewayPairing) };
