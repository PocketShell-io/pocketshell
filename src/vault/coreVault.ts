/**
 * Android's `VaultCore` over core's KeyVaultStore (key-vault W1): the one
 * implementation of the vault format, Argon2id, the check value, the DEK, the
 * backoff and the server-check ordering. This module only maps the store's
 * results onto the phone's lock state and serialises the "Remember on this
 * device" record that native seals with the Android Keystore key.
 */
import {
  KeyVaultStore,
  type VaultRememberRecord,
  type VaultUnlockResult as StoreUnlockResult,
} from '@pocketshell/core';
import type { GoogleSyncNative } from '@/native/googleSync';
import type { UnlockedVault, VaultCore, VaultUnlockResult } from './androidVault';
import { createVaultServerVerifier, createVaultSlotTransport } from './vaultAccount';

const CORRUPT = 'Your key vault could not be read. Open the PocketShell web app to check it.';

function toB64url(bytes: Uint8Array): string {
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function fromB64url(text: unknown): Uint8Array | null {
  if (typeof text !== 'string' || !/^[A-Za-z0-9_-]+$/.test(text)) return null;
  const padded = text.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - (text.length % 4)) % 4);
  try {
    return Uint8Array.from(atob(padded), (char) => char.charCodeAt(0));
  } catch {
    return null;
  }
}

/** The record as native seals it: JSON with base64url secrets. The caller's bytes are wiped. */
export function serializeRememberRecord(record: VaultRememberRecord): string {
  try {
    return JSON.stringify({
      v: 1,
      vaultId: record.vaultId,
      epoch: record.epoch,
      dek: toB64url(record.dek),
      ...(record.auth ? { auth: toB64url(record.auth) } : {}),
    });
  } finally {
    record.dek.fill(0);
    record.auth?.fill(0);
  }
}

export function parseRememberRecord(text: string): VaultRememberRecord | null {
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    return null;
  }
  if (typeof parsed !== 'object' || parsed === null) return null;
  const record = parsed as Record<string, unknown>;
  const dek = fromB64url(record.dek);
  const auth = record.auth === undefined ? undefined : fromB64url(record.auth);
  if (record.v !== 1 || typeof record.vaultId !== 'string' || record.vaultId === ''
    || typeof record.epoch !== 'number' || !Number.isSafeInteger(record.epoch)
    || dek === null || dek.length !== 32 || auth === null || (auth !== undefined && auth.length !== 32)) {
    return null;
  }
  return { vaultId: record.vaultId, epoch: record.epoch, dek, ...(auth ? { auth } : {}) };
}

/** The slice of KeyVaultStore this adapter drives (tests script it). */
export type KeyVaultStoreLike = Pick<KeyVaultStore,
  'vaultId' | 'epoch' | 'probe' | 'unlock' | 'unlockWithRememberedDek' | 'exportRememberRecord'
  | 'getHostBinding' | 'getSyncPassphrase' | 'decryptKeyForHost' | 'lock'>;

function session(store: KeyVaultStoreLike): UnlockedVault {
  return {
    vaultId: store.vaultId ?? '',
    epoch: store.epoch ?? 0,
    binding: (alias) => store.getHostBinding(alias),
    syncPassphrase: () => store.getSyncPassphrase(),
    async decryptForHost(alias) {
      const secret = await store.decryptKeyForHost(alias);
      switch (secret.kind) {
        case 'key':
        case 'password':
          return secret;
        case 'locked':
        case 'gated':
          throw new Error('Your key vault locked. Unlock it and connect again.');
        case 'unbound':
          throw new Error(`No key in your key vault is assigned to “${alias}”.`);
        case 'missingKey':
          throw new Error(`The key assigned to “${alias}” is no longer in your key vault. Upload it again on the PocketShell web app.`);
        case 'corrupt':
          throw new Error(CORRUPT);
        case 'error':
          throw new Error(secret.message);
      }
    },
    async exportRemembered() {
      const record = store.exportRememberRecord();
      if (!record) throw new Error('Your key vault locked before it could be remembered.');
      return serializeRememberRecord(record);
    },
    close: () => store.lock(),
  };
}

function unlockResult(store: KeyVaultStoreLike, result: StoreUnlockResult): VaultUnlockResult {
  switch (result.kind) {
    case 'ok':
      return { kind: 'unlocked', vault: session(store) };
    case 'wrongPassphrase':
    case 'backoff':
    case 'rateLimited':
      return { kind: 'wrong-passphrase', retryAfterMs: result.retryAfterMs };
    case 'noVault':
      return { kind: 'absent' };
    case 'corrupt':
      return { kind: 'error', message: CORRUPT };
    case 'error':
      return { kind: 'error', message: result.message };
  }
}

export function createStoreVaultCore(store: KeyVaultStoreLike): VaultCore {
  return {
    async present() {
      const probe = await store.probe();
      if (probe.kind === 'error') throw new Error(probe.message);
      // `gated`: the server hides the header until a verify — a vault all the
      // same; reading it as "no vault" would send the phone to a key picker.
      return probe.kind !== 'noVault';
    },
    async unlock(passphrase) {
      return unlockResult(store, await store.unlock(passphrase));
    },
    async unlockRemembered(text) {
      const record = parseRememberRecord(text);
      if (!record) return { kind: 'stale' };
      const result = await store.unlockWithRememberedDek(record);
      record.dek.fill(0);
      record.auth?.fill(0);
      switch (result.kind) {
        case 'ok':
          return { kind: 'unlocked', vault: session(store) };
        case 'stale':
          return { kind: 'stale' };
        case 'noVault':
          return { kind: 'absent' };
        case 'rateLimited':
          return { kind: 'error', message: 'The sync service is limiting unlock attempts. Try again shortly.' };
        case 'corrupt':
          return { kind: 'error', message: CORRUPT };
        case 'error':
          return { kind: 'error', message: result.message };
      }
    },
  };
}

/** The phone's vault: core's store over the native sync transport (read-only) and server check. */
export function createAndroidVaultCore(native: GoogleSyncNative): VaultCore {
  return createStoreVaultCore(new KeyVaultStore({
    transport: createVaultSlotTransport(native),
    verifier: createVaultServerVerifier(native),
  }));
}
