import { registerPlugin, type Plugin } from '@capacitor/core';
import { isValidGatewayDeviceId, normalizeGatewayServerUrl } from '@pocketshell/core';

/**
 * The native gateway pairing storage API (GatewayPairingPlugin.java,
 * issue #3060) — the seam the shared UI's pairing flow will call once the
 * host-list/resolver integration can use a validated gateway target.
 *
 * Boundaries this adapter keeps:
 *  - Pairings are created ONLY by an explicit `pair()` call carrying an
 *    out-of-band SHA-256 fingerprint (the host's own `ssh-keygen -lf`
 *    output). Nothing here accepts a pairing from synced host metadata, and
 *    nothing here ever returns or accepts a token — Google or routing.
 *  - Every record is scoped to the CURRENT native sign-in account; `list`
 *    answers only that account's records, and a signed-out phone refuses.
 *  - Replies are validated fail-closed at this boundary: a malformed native
 *    answer is an error, never a half-trusted record.
 */

export interface GatewayPairingRecord {
  /** Canonical `wss://host[:port]` gateway origin. */
  serverUrl: string;
  /** The enrolled host-agent id the route reaches. */
  deviceId: string;
  /** The independently provisioned `SHA256:…` host key fingerprint. */
  fingerprintSha256: string;
  /** The local key-vault handle this pairing authenticates with. */
  keyHandleId: string;
  pairedAtEpochMs: number;
}

export interface GatewayPairingAccount {
  signedIn: boolean;
  /** The stable native account subject pairings bind to; empty when signed out. */
  accountSubject: string;
}

export interface GatewayPairRequest {
  serverUrl: string;
  deviceId: string;
  fingerprintSha256: string;
  keyHandleId: string;
}

/** Stable native rejection codes (GatewayPairingPlugin.java / SyncAuthException). */
export const GATEWAY_PAIRING_ERROR_CODES = [
  'GATEWAY_PAIRING_INVALID',
  'GATEWAY_PAIRING_KEY_MISSING',
  'GATEWAY_PAIRING_STORE_FAILED',
  'NOT_SIGNED_IN',
  'SIGN_IN_CANCELLED',
  'SIGN_IN_UNAVAILABLE',
  'SIGN_IN_FAILED',
  'SIGN_IN_STORAGE_FAILED',
  'SYNC_NETWORK_FAILED',
  'SYNC_INVALID_REQUEST',
] as const;

/** The four methods the native plugin carries (tests supply exactly these). */
export type GatewayPairingPluginMethods = {
  currentAccount(options: { requestId: string }): Promise<unknown>;
  list(options: { requestId: string }): Promise<unknown>;
  pair(options: GatewayPairRequest & { requestId: string }): Promise<unknown>;
  remove(options: { serverUrl: string; deviceId: string; requestId: string }): Promise<unknown>;
};

/** The pairing plugin as registered; replies are parsed before use. */
export type NativeGatewayPairingPlugin = Plugin & GatewayPairingPluginMethods;

export interface GatewayPairingNative {
  currentAccount(): Promise<GatewayPairingAccount>;
  list(): Promise<GatewayPairingRecord[]>;
  pair(request: GatewayPairRequest): Promise<GatewayPairingRecord>;
  remove(serverUrl: string, deviceId: string): Promise<boolean>;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

export function parseGatewayPairingAccount(value: unknown): GatewayPairingAccount {
  if (!isRecord(value) || typeof value.signedIn !== 'boolean' || typeof value.accountSubject !== 'string') {
    throw new Error('The gateway pairing account answer could not be read.');
  }
  return { signedIn: value.signedIn, accountSubject: value.signedIn ? value.accountSubject : '' };
}

/** The exact keys a pairing row carries; anything extra (token-shaped above
 * all) makes the answer malformed rather than silently ignored. */
const PAIRING_ROW_KEYS = new Set(['serverUrl', 'deviceId', 'fingerprintSha256', 'keyHandleId', 'pairedAtEpochMs']);

function parsePairingRow(value: unknown): GatewayPairingRecord | null {
  if (!isRecord(value)) return null;
  for (const key of Object.keys(value)) {
    if (!PAIRING_ROW_KEYS.has(key)) return null;
  }
  if (typeof value.serverUrl !== 'string'
    || typeof value.deviceId !== 'string'
    || typeof value.fingerprintSha256 !== 'string'
    || typeof value.keyHandleId !== 'string'
    || typeof value.pairedAtEpochMs !== 'number'
    || !Number.isSafeInteger(value.pairedAtEpochMs)
    || value.pairedAtEpochMs < 0) {
    return null;
  }
  // Type validity resumes at core's normalizers: a stored row that no longer
  // normalizes is dropped rather than shown as a usable pairing.
  if (normalizeGatewayServerUrl(value.serverUrl) !== value.serverUrl) return null;
  if (!isValidGatewayDeviceId(value.deviceId)) return null;
  if (!/^SHA256:[A-Za-z0-9+/]{43}$/.test(value.fingerprintSha256)) return null;
  return {
    serverUrl: value.serverUrl,
    deviceId: value.deviceId,
    fingerprintSha256: value.fingerprintSha256,
    keyHandleId: value.keyHandleId,
    pairedAtEpochMs: value.pairedAtEpochMs,
  };
}

export function parseGatewayPairingList(value: unknown): GatewayPairingRecord[] {
  if (!isRecord(value) || typeof value.pairings !== 'string') {
    throw new Error('The gateway pairing list answer could not be read.');
  }
  let rows: unknown;
  try {
    rows = JSON.parse(value.pairings);
  } catch {
    throw new Error('The gateway pairing list answer could not be read.');
  }
  if (!Array.isArray(rows)) {
    throw new Error('The gateway pairing list answer could not be read.');
  }
  const parsed: GatewayPairingRecord[] = [];
  for (const row of rows) {
    const record = parsePairingRow(row);
    if (record !== null) parsed.push(record);
  }
  return parsed;
}

export function parseGatewayPairingResult(value: unknown): GatewayPairingRecord {
  const record = parsePairingRow(value);
  if (record === null) {
    throw new Error('The gateway pairing answer could not be read.');
  }
  return record;
}

export function parseGatewayPairingRemoval(value: unknown): boolean {
  if (!isRecord(value) || typeof value.removed !== 'boolean') {
    throw new Error('The gateway pairing removal answer could not be read.');
  }
  return value.removed;
}

/** Wrap the registered Capacitor plugin with response validation. */
export function createGatewayPairingNative(plugin: GatewayPairingPluginMethods): GatewayPairingNative {
  return {
    currentAccount: async () => parseGatewayPairingAccount(await plugin.currentAccount({ requestId: crypto.randomUUID() })),
    list: async () => parseGatewayPairingList(await plugin.list({ requestId: crypto.randomUUID() })),
    pair: async (request) =>
      parseGatewayPairingResult(await plugin.pair({
        requestId: crypto.randomUUID(),
        serverUrl: request.serverUrl,
        deviceId: request.deviceId,
        fingerprintSha256: request.fingerprintSha256,
        keyHandleId: request.keyHandleId,
      })),
    remove: async (serverUrl, deviceId) =>
      parseGatewayPairingRemoval(await plugin.remove({ requestId: crypto.randomUUID(), serverUrl, deviceId })),
  };
}

export const gatewayPairing: GatewayPairingNative = createGatewayPairingNative(
  registerPlugin<NativeGatewayPairingPlugin>('GatewayPairing'),
);
