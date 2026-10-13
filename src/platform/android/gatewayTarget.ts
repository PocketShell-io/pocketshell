/**
 * The dial target for a gateway host on Android (#3086 slice 3).
 *
 * A host whose connect request carries a `gateway` marker the platform
 * admitted (`unsupportedTransportMessage` in ./androidApi.ts) is dialled
 * through the native gateway transport, through the same ConnectionController
 * and the same native `connect()` as any other host: this module only builds
 * the `SshHostTarget`. It never dials, and nothing here can turn a gateway
 * host into an ordinary SSH target (D28: no plain-SSH fallback).
 *
 * The SSH key is the one the phone's saved pairing for that gateway device
 * names: native refuses a dial whose key differs from the pairing, and the
 * pin itself never leaves native code.
 */
import {
  classifyGatewayDialFailure,
  normalizeGatewayServerUrl,
  normalizeGatewayTarget,
  type GatewayTransportTarget,
  type SshHostTarget,
} from '@pocketshell/core';
import type { GatewayPairingRow } from '@/native/gatewayPairing';

export interface GatewayPairings {
  list(): Promise<GatewayPairingRow[]>;
}

export interface GatewayConnectRequest {
  host: string;
  port?: number;
  user: string;
  gateway?: unknown;
}

/**
 * Why Android will not dial this ADMITTED gateway marker, or null. Core
 * accepts a `ws://` origin as a valid target (explicit development), but the
 * phone's native transport only dials `wss://`; refusing here keeps the
 * refusal before any effect instead of after a broker token was minted.
 */
export function insecureGatewayMessage(label: string, gateway: unknown): string | null {
  const target = normalizeGatewayTarget(gateway);
  if (target === null || target.serverUrl.startsWith('wss://')) return null;
  const who = label.trim() !== '' ? `“${label}”` : 'This host';
  return `${who} uses an unencrypted PocketShell gateway address (ws://), which PocketShell for Android does not connect to. Nothing was dialled.`;
}

/** The message for a native pairing refusal, from core's gateway matrix when it knows the code. */
function nativeRefusalMessage(error: unknown): string {
  const code = typeof error === 'object' && error !== null ? (error as { code?: unknown }).code : undefined;
  if (typeof code === 'string') {
    const known = classifyGatewayDialFailure(code, {});
    if (known) return known.userMessage;
  }
  return 'The saved gateway pairings could not be read, so nothing was dialled.';
}

/** Same gateway route: canonical origins (core's grammar on both sides) and device id. */
function samePairing(row: GatewayPairingRow, target: GatewayTransportTarget): boolean {
  return normalizeGatewayServerUrl(row.serverUrl) === target.serverUrl && row.deviceId === target.deviceId;
}

/**
 * Build the gateway dial target for an admitted request. Rejects (nothing
 * dialled) when the phone is signed out, has no pairing for the device, or
 * the request names no SSH user.
 */
export async function resolveGatewayTarget(
  request: GatewayConnectRequest,
  label: string,
  pairings: GatewayPairings,
): Promise<SshHostTarget> {
  const gateway = normalizeGatewayTarget(request.gateway);
  // Admission already refused anything else; this is the type narrowing.
  if (gateway === null) throw new Error(`“${label}” has a PocketShell gateway setting this version can't read. Nothing was dialled.`);
  const insecure = insecureGatewayMessage(label, gateway);
  if (insecure) throw new Error(insecure);
  const user = request.user.trim();
  if (user === '') throw new Error(`“${label}” names no SSH user, so nothing was dialled.`);
  let rows: GatewayPairingRow[];
  try {
    rows = await pairings.list();
  } catch (error) {
    throw new Error(nativeRefusalMessage(error));
  }
  const pairing = rows.find((row) => samePairing(row, gateway));
  if (!pairing) throw new Error(classifyGatewayDialFailure('GATEWAY_UNPAIRED', {})!.userMessage);
  return {
    hostId: `gateway:${gateway.serverUrl}/${gateway.deviceId}`,
    // Display label only: the native transport never resolves or dials it.
    hostname: request.host,
    port: request.port ?? 22,
    username: user,
    credential: { kind: 'key-handle', handleId: pairing.keyHandleId },
    gateway,
  };
}
