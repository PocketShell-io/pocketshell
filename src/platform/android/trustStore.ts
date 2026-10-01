/**
 * Host-key pins for the Android platform, keyed by the durable host id the
 * connection controller dials with. Same storage keys and value shapes the
 * phone screens used (`pocketshell.ssh.host-key.<hostId>`), so a pin
 * recorded by either shell is honoured by the other during the migration.
 */
import { fromAndroidTrustedHostKeySha256, type HostKeyTrustPin, type HostKeyTrustStore } from '@pocketshell/core';
import type { StringStorage } from './hostStore';

export function pinStorageKey(hostId: string): string {
  return `pocketshell.ssh.host-key.${hostId}`;
}

export function parseStoredPin(stored: string | null): HostKeyTrustPin | null {
  if (stored == null) return null;
  try {
    const parsed: unknown = JSON.parse(stored);
    if (typeof parsed === 'string') return fromAndroidTrustedHostKeySha256(parsed);
    if (typeof parsed !== 'object' || parsed === null) return null;
    const pin = parsed as Record<string, unknown>;
    if (pin.kind === 'sha256-fingerprint' && typeof pin.fingerprintSha256 === 'string') {
      return { kind: 'sha256-fingerprint', fingerprintSha256: pin.fingerprintSha256 };
    }
    if (pin.kind === 'wire-key' && typeof pin.fingerprintSha256 === 'string'
      && typeof pin.keyType === 'string' && typeof pin.keyB64 === 'string') {
      return { kind: 'wire-key', fingerprintSha256: pin.fingerprintSha256, keyType: pin.keyType, keyB64: pin.keyB64 };
    }
    return null;
  } catch {
    // Older Android host rows store the SHA256 fingerprint as a bare string.
    return fromAndroidTrustedHostKeySha256(stored);
  }
}

export function createLocalTrustStore(storage: StringStorage): HostKeyTrustStore {
  return {
    async get(hostId) {
      return parseStoredPin(storage.getItem(pinStorageKey(hostId)));
    },
    async record(hostId, pin) {
      storage.setItem(pinStorageKey(hostId), JSON.stringify(pin));
    },
  };
}
