/**
 * Android's `VaultCore` over core's KeyVaultStore (key-vault W1): the one
 * implementation of the vault format, Argon2id, the check value, the DEK and
 * the server-check ordering. This module only maps the store's results onto
 * the phone's lock state and serialises the "Remember on this device" record
 * that native seals with the Android Keystore key.
 *
 * `KeyVaultStoreLike` is exactly the slice of core's KeyVaultStore API this
 * module calls (names from W1), so binding the real store is a constructor.
 */
import type { GoogleSyncNative } from '@/native/googleSync';
import type { GatewayAuthPolicy, HostKeyBinding, UnlockedVault, VaultCore, VaultUnlockResult } from './androidVault';
import { createVaultServerVerifier, readVaultSlot, VaultVerifyRequired } from './vaultAccount';

export const VAULT_HEADER_SLOT = 'vault';

/** Core's remember record (decision #6): both secrets sealed by the same device key. */
export interface VaultRememberRecord {
  vaultId: string;
  epoch: number;
  dek: Uint8Array;
  auth?: Uint8Array;
}

type StoreUnlock =
  | { kind: 'ok' }
  | { kind: 'wrongPassphrase'; retryAfterMs: number }
  | { kind: 'backoff'; retryAfterMs: number }
  | { kind: 'rateLimited'; retryAfterMs: number }
  | { kind: 'noVault' }
  | { kind: 'corrupt' }
  | { kind: 'error'; message: string };

type StoreSecret =
  | { kind: 'key'; pem: string }
  | { kind: 'password'; password: string }
  | { kind: 'locked' }
  | { kind: 'unbound' }
  | { kind: 'missingKey'; keyId: string }
  | { kind: 'corrupt' }
  | { kind: 'error'; message: string };

/** The KeyVaultStore methods Android uses (core W1 names). */
export interface KeyVaultStoreLike {
  readonly vaultId: string | null;
  readonly epoch: number | null;
  probe(): Promise<{ kind: 'noVault' } | { kind: 'vault'; vaultId: string; epoch: number } | { kind: 'corrupt' } | { kind: 'error'; message: string }>;
  unlock(passphrase: string): Promise<StoreUnlock>;
  unlockWithRememberedDek(record: VaultRememberRecord): Promise<{ kind: 'ok' } | { kind: 'stale' } | { kind: 'noVault' } | { kind: 'corrupt' } | { kind: 'error'; message: string }>;
  exportRememberRecord(): VaultRememberRecord | null | Promise<VaultRememberRecord | null>;
  getHostBinding(hostAlias: string): { keyId: string; gatewayAuth: GatewayAuthPolicy } | null;
  getSyncPassphrase(): string | null;
  decryptKeyForHost(hostAlias: string): Promise<StoreSecret>;
  lock(): void;
}

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

function session(store: KeyVaultStoreLike): UnlockedVault {
  return {
    vaultId: store.vaultId ?? '',
    epoch: store.epoch ?? 0,
    binding: (alias): HostKeyBinding | null => store.getHostBinding(alias),
    syncPassphrase: () => store.getSyncPassphrase(),
    async decryptForHost(alias) {
      const secret = await store.decryptKeyForHost(alias);
      switch (secret.kind) {
        case 'key':
        case 'password':
          return secret;
        case 'locked':
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
      const record = await store.exportRememberRecord();
      if (!record) throw new Error('Your key vault locked before it could be remembered.');
      return serializeRememberRecord(record);
    },
    close: () => store.lock(),
  };
}

function unlockResult(store: KeyVaultStoreLike, result: StoreUnlock): VaultUnlockResult {
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
        case 'noVault':
          return { kind: 'absent' };
        case 'stale':
        case 'corrupt':
        case 'error':
          // Any record that does not open the current vault is dropped: the
          // user types the passphrase (an older epoch, a passphrase changed
          // elsewhere so its AUTH is wrong, another vault).
          return { kind: 'stale' };
      }
    },
  };
}

/**
 * Whether the account has a vault, before the vendored core carries the
 * store. With phase 2 and the read gate on, the server hides the `vault`
 * header until a verify, so a verifier on `/vault/status` counts as a vault;
 * a gated read is a vault, never "no vault" (that would send the phone back
 * to a key picker).
 */
export async function vaultPresent(native: GoogleSyncNative): Promise<boolean> {
  const status = await createVaultServerVerifier(native).status();
  if (status.kind === 'ok' && status.hasVerifier) return true;
  try {
    return (await readVaultSlot(native, VAULT_HEADER_SLOT)).kind === 'ok';
  } catch (error) {
    if (error instanceof VaultVerifyRequired) return true;
    throw error;
  }
}

const NOT_YET = 'This version of PocketShell cannot open the key vault yet. Update the app.';

/** Until the core bump: knows whether a vault exists, cannot open it. */
export function createCoreVault(native: GoogleSyncNative): VaultCore {
  return {
    present: () => vaultPresent(native),
    unlock: async () => ({ kind: 'error', message: NOT_YET }),
    unlockRemembered: async () => ({ kind: 'stale' }),
  };
}
