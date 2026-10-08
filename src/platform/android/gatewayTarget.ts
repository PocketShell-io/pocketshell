import { normalizeGatewayTarget, type GatewayTransportTarget, type SshHostTarget } from '@pocketshell/core';
import type { GatewayPairingNative } from '@/native/gatewayPairing';

export type AndroidGatewayHostTarget = SshHostTarget & { gateway: GatewayTransportTarget };

/** Android never repairs insecure or credential-bearing gateway origins. */
export function validatedAndroidGateway(raw: unknown): GatewayTransportTarget {
  if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) throw new Error('The gateway target is malformed.');
  const value = raw as Record<string, unknown>;
  if (typeof value.serverUrl !== 'string') throw new Error('The gateway target is malformed.');
  const original = value.serverUrl.trim();
  if (!/^wss:\/\/(\[[0-9A-Fa-f:.]+\]|[A-Za-z0-9._-]+)(:[0-9]{1,5})?\/?$/i.test(original)) {
    throw new Error('The gateway requires a secure origin without credentials.');
  }
  let origin: URL;
  try { origin = new URL(value.serverUrl); } catch { throw new Error('The gateway target is malformed.'); }
  if (origin.username || origin.password || origin.protocol !== 'wss:') throw new Error('The gateway requires a secure origin without credentials.');
  const target = normalizeGatewayTarget(raw);
  if (!target) throw new Error('The gateway target is malformed.');
  return target;
}

/** Read current-account native pairing afresh on every dial. Native repeats all checks before effects. */
export async function resolveAndroidGatewayTarget(
  request: { host: string; port?: number; user: string; gateway?: unknown; link?: unknown },
  pairings: Pick<GatewayPairingNative, 'currentAccount' | 'list'>,
  selectedKeyHandleId?: string,
): Promise<AndroidGatewayHostTarget> {
  if (Object.prototype.hasOwnProperty.call(request, 'link')) throw new Error('A connection cannot be both a gateway and a link dial.');
  const gateway = validatedAndroidGateway(request.gateway);
  const account = await pairings.currentAccount();
  if (!account.signedIn || !account.accountSubject) throw new Error('Sign in before connecting through the gateway.');
  const pairing = (await pairings.list()).find((row) => row.serverUrl === gateway.serverUrl && row.deviceId === gateway.deviceId);
  if (!pairing || !pairing.keyHandleId) throw new Error('Pair this device with the host before connecting through the gateway.');
  if (selectedKeyHandleId !== undefined && selectedKeyHandleId !== pairing.keyHandleId) {
    throw new Error('The selected SSH key does not match the saved gateway pairing.');
  }
  if (!request.host.trim() || !request.user.trim()) throw new Error('Enter a host label and SSH user.');
  const port = request.port ?? 22;
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('Port must be 1–65535.');
  return {
    hostId: `gateway:${gateway.serverUrl}/${gateway.deviceId}`,
    hostname: request.host.trim(), port, username: request.user.trim(),
    credential: { kind: 'key-handle', handleId: pairing.keyHandleId }, gateway,
  };
}
