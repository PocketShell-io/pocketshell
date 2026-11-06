/** Android's native-owned gateway discovery and pairing behind core's existing picker. */
import { normalizeGatewayServerUrl, normalizeGatewayTarget, parseGatewayDeviceList } from '@pocketshell/core';
import type { PocketShellApi, GatewayClientKey, GatewayPairingSummary } from '@ui/app/api';
import type { GoogleSyncNative } from '@/native/googleSync';
import { listGatewayPairings, parseGatewayPairingRow, type NativeGatewayPairingPlugin, type GatewayPairingRow } from '@/native/gatewayPairing';
import { listSshKeys, parseSshKeyMetadata, parseSshPublicKeyLine, type NativeSshKeyVaultPlugin } from '@/native/sshKeyVault';
import type { AndroidHostStore } from './hostStore';

type DirectoryPlugin = NativeGatewayPairingPlugin & {
  serverUrl(options: { requestId: string }): Promise<unknown>;
  devices(options: { requestId: string; expectedAccountEmail: string }): Promise<unknown>;
  pair(options: { requestId: string; expectedAccountEmail: string; serverUrl: string; deviceId: string; hostKey: string; keyHandleId: string }): Promise<unknown>;
};

function failure(message: string, code: string): Error {
  return Object.assign(new Error(message), { code });
}
function object(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
function summary(row: GatewayPairingRow): GatewayPairingSummary {
  return { serverUrl: row.serverUrl, deviceId: row.deviceId, fingerprint: row.fingerprintSha256,
    pinKind: row.pinKind, keyId: row.keyHandleId };
}

export function createAndroidGatewayDirectory(deps: {
  native: DirectoryPlugin;
  account: Pick<GoogleSyncNative, 'status'>;
  keys: NativeSshKeyVaultPlugin;
  hosts: AndroidHostStore;
}): NonNullable<PocketShellApi['gateway']> {
  let sequence = 0;
  const requestId = () => `gateway-directory-${++sequence}`;
  async function email(): Promise<string> {
    const status = await deps.account.status();
    if (!status.signedIn || !status.email) throw failure('Sign in to list your PocketShell devices.', 'NOT_SIGNED_IN');
    return status.email;
  }
  async function serverUrl(): Promise<string> {
    const id = requestId();
    const result = await deps.native.serverUrl({ requestId: id });
    if (!object(result) || result.requestId !== id || typeof result.serverUrl !== 'string'
      || !result.serverUrl.startsWith('wss://') || normalizeGatewayServerUrl(result.serverUrl) !== result.serverUrl) {
      throw failure('The PocketShell gateway address could not be read.', 'GATEWAY_DEVICES_INVALID_SERVER');
    }
    return result.serverUrl;
  }
  async function requireServer(requested: string): Promise<string> {
    const server = await serverUrl();
    if (normalizeGatewayServerUrl(requested) !== server) {
      throw failure('Choose the PocketShell gateway.', 'GATEWAY_DEVICES_INVALID_SERVER');
    }
    return server;
  }
  async function key(handleId: string): Promise<GatewayClientKey> {
    const metadata = (await listSshKeys(deps.keys)).find((row) => row.handleId === handleId);
    if (!metadata) throw failure('Choose an SSH key from this phone.', 'GATEWAY_PAIRING_KEY_MISSING');
    return { id: metadata.handleId, label: metadata.label, fingerprint: metadata.fingerprintSha256,
      publicKey: parseSshPublicKeyLine(await deps.keys.publicKey({ handleId }), metadata.algorithm) };
  }
  return {
    defaultServerUrl: 'wss://gateway.pocketshell.io',
    serverUrl,
    async devices(requested) {
      await requireServer(requested);
      const expectedAccountEmail = await email();
      const id = requestId();
      const result = await deps.native.devices({ requestId: id, expectedAccountEmail });
      if (!object(result) || result.requestId !== id) throw failure('The gateway device list could not be read.', 'GATEWAY_DEVICES_INVALID_RESPONSE');
      if (await email() !== expectedAccountEmail) throw failure('The signed-in account changed. Reload devices.', 'GATEWAY_PAIRING_ACCOUNT_CHANGED');
      return parseGatewayDeviceList({ devices: result.devices });
    },
    pairings: async () => (await listGatewayPairings(deps.native)).map(summary),
    clientKeys: async () => Promise.all((await listSshKeys(deps.keys)).filter((row) => !row.passphraseRequired).map((row) => key(row.handleId))),
    async createClientKey(label) {
      const metadata = parseSshKeyMetadata(await deps.keys.generateKey({ label, algorithm: 'Ed25519' }));
      return key(metadata.handleId);
    },
    async addDevice(input) {
      const target = normalizeGatewayTarget(input.gateway);
      if (!target) throw failure('Choose a valid PocketShell device.', 'GATEWAY_PAIRING_INVALID');
      const server = await requireServer(target.serverUrl);
      const expectedAccountEmail = await email();
      const host = { name: input.name.trim(), hostname: target.deviceId, port: 22, user: input.username.trim(),
        keyHandleId: input.keyId, gateway: target };
      if (!host.name || !host.user || !input.pin.trim()) throw failure('Enter a name, SSH user and trusted host-key pin.', 'GATEWAY_PAIRING_INVALID');
      if (deps.hosts.savedHosts().some((row) => row.name === host.name)) throw new Error('A saved host already uses this name.');
      await key(input.keyId);
      const id = requestId();
      const result = await deps.native.pair({ requestId: id, expectedAccountEmail, serverUrl: server,
        deviceId: target.deviceId, hostKey: input.pin, keyHandleId: input.keyId });
      if (!object(result) || result.requestId !== id || !object(result.pairing)) {
        throw failure('The device pairing could not be saved.', 'GATEWAY_PAIRING_STORE_FAILED');
      }
      // Validate the public reply through the same parser as every later dial.
      const paired = parseGatewayPairingRow(result.pairing);
      if (paired.serverUrl !== server || paired.deviceId !== target.deviceId || paired.keyHandleId !== input.keyId
        || await email() !== expectedAccountEmail) throw failure('The signed-in account or pairing changed. Try again.', 'GATEWAY_PAIRING_ACCOUNT_CHANGED');
      deps.hosts.save(host);
      return { hostName: host.name, pairing: summary(paired) };
    },
  };
}
