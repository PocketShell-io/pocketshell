/**
 * The account key vault on Android (key-vault W4, design doc sections 2-5).
 *
 * Keys are uploaded on the web only. The phone unlocks the account's vault
 * with the one account passphrase, decrypts a host's key in WebView memory
 * only when that host is dialled, and hands it to native SSH as an EPHEMERAL
 * handle (`SshKeyVault.holdVaultKey`): held in process memory, never written
 * anywhere, wiped when the vault locks. A password entry is dialled as a
 * password credential for that one connect.
 *
 * Split of responsibilities:
 *  - core's KeyVaultStore (W1, `VaultCore` here): the format, Argon2id, the
 *    check value, the DEK, the index and key blobs, read from the account's
 *    `vault` / `vk-*` slots. This module never touches crypto;
 *  - this module: the phone's lock state, wrong-passphrase backoff, the
 *    per-device "Remember on this device" record (sealed natively by an
 *    Android Keystore key, `VaultDeviceKey`), and what a dial gets for a host;
 *  - the unlock sheet (VaultUnlockGate.vue) asks; androidApi decides when.
 *
 * Hosts are bound by their ACCOUNT alias (the name in the `main` sync slot).
 */
import { reactive } from 'vue';
import type { SshCredential } from '@pocketshell/core';

export type GatewayAuthPolicy = 'host' | 'managed' | 'both';

/** What the vault index says a host uses (core `HostKeyBinding`). */
export interface HostKeyBinding {
  keyId: string;
  gatewayAuth: GatewayAuthPolicy;
}

/** A decrypted vault entry (core `VaultSecret`). */
export type VaultSecret = { kind: 'key'; pem: string } | { kind: 'password'; password: string };

/** An unlocked vault: the decrypted index and lazy key decryption (core KeyVaultStore session). */
export interface UnlockedVault {
  readonly vaultId: string;
  readonly epoch: number;
  binding(hostAlias: string): HostKeyBinding | null;
  /** The account's settings-sync passphrase from the index (contract #2), or null. */
  syncPassphrase(): string | null;
  decryptForHost(hostAlias: string): Promise<VaultSecret>;
  /** The DEK record "Remember on this device" seals (DEK + vaultId + epoch; never the passphrase). */
  exportRemembered(): Promise<string>;
  /** Drop the DEK and the decrypted index from memory. */
  close(): void;
}

export type VaultUnlockResult =
  | { kind: 'unlocked'; vault: UnlockedVault }
  | { kind: 'wrong-passphrase'; retryAfterMs?: number }
  | { kind: 'absent' }
  | { kind: 'error'; message: string };

/** The account-vault operations Android needs from core's KeyVaultStore (W1). */
export interface VaultCore {
  /** Whether the signed-in account has a vault at all. */
  present(): Promise<boolean>;
  unlock(passphrase: string): Promise<VaultUnlockResult>;
  /** Unlock from a remembered DEK record; `stale` when its vaultId/epoch no longer match. */
  unlockRemembered(record: string): Promise<VaultUnlockResult | { kind: 'stale' }>;
}

/** The native halves: `SshKeyVault` vault-key handles and `VaultDeviceKey` remember storage. */
export interface VaultNative {
  holdVaultKey(options: { privateKeyPem: string }): Promise<{ handleId: string; algorithm: string; fingerprintSha256: string }>;
  releaseVaultKeys(options?: { handleIds?: string[] }): Promise<{ released: number }>;
  remember(options: { scope: string; record: string }): Promise<unknown>;
  recall(options: { scope: string }): Promise<{ record: string | null }>;
  forget(options: { scope: string }): Promise<unknown>;
  forgetAll(): Promise<unknown>;
}

export type VaultStatus = 'signed-out' | 'absent' | 'locked' | 'unlocked';

/** What a dial of one host gets from the vault. */
export type VaultDialDecision =
  /** The account has no vault (or the phone is signed out): the phone's own key flow applies. */
  | { kind: 'no-vault' }
  /** The user did not unlock. */
  | { kind: 'locked' }
  /** Unlocked, but the vault names no key for this host. */
  | { kind: 'unbound' }
  /** A gateway host whose policy is `host`: the host's own keys (the pairing key), no vault key. */
  | { kind: 'host-keys' }
  | { kind: 'credential'; credential: SshCredential; secret: VaultSecret['kind']; gatewayAuth: GatewayAuthPolicy };

export interface AndroidVaultDeps {
  core: VaultCore;
  native: VaultNative;
  /** The signed-in account (its e-mail), the scope of a remembered record; null when signed out. */
  scope(): Promise<string | null>;
  /** Show the unlock sheet; resolves true once the vault is unlocked, false when the user closed it. */
  askUnlock(): Promise<boolean>;
  /** Called with the index's sync passphrase after every unlock (contract #2: one passphrase). */
  onUnlocked?(syncPassphrase: string | null): void | Promise<void>;
  now?(): number;
}

/** Client-side backoff after three wrong passphrases: 1 s, 2 s, 4 s … capped at 30 s (design section 5). */
export function backoffMs(failures: number): number {
  if (failures < 3) return 0;
  return Math.min(30_000, 1000 * 2 ** (failures - 3));
}

export const WRONG_PASSPHRASE_MESSAGE = 'That passphrase didn\'t unlock your vault.';

export function createAndroidVault(deps: AndroidVaultDeps) {
  const now = deps.now ?? (() => Date.now());
  const state = reactive({
    status: 'signed-out' as VaultStatus,
    /** Wrong passphrases in a row on this device. */
    failures: 0,
    /** Epoch ms before which Unlock is disabled (backoff), 0 when none. */
    retryAt: 0,
    /** Whether a remembered record exists for this account on this device. */
    remembered: false,
  });
  let unlocked: UnlockedVault | null = null;
  /** keyId → native ephemeral handle, while unlocked. */
  const handles = new Map<string, Promise<string>>();

  async function releaseHandles(): Promise<void> {
    handles.clear();
    await deps.native.releaseVaultKeys().catch(() => undefined);
  }

  async function adopt(vault: UnlockedVault): Promise<void> {
    unlocked?.close();
    unlocked = vault;
    state.status = 'unlocked';
    state.failures = 0;
    state.retryAt = 0;
    await deps.onUnlocked?.(vault.syncPassphrase());
  }

  /**
   * Re-read where the account stands: signed out, no vault, or a vault that is
   * locked, unless this device remembered its key (then unlocked silently).
   */
  async function refresh(): Promise<VaultStatus> {
    const scope = await deps.scope().catch(() => null);
    if (!scope) {
      await lock();
      state.status = 'signed-out';
      return state.status;
    }
    if (unlocked) return state.status;
    if (!(await deps.core.present())) {
      state.status = 'absent';
      return state.status;
    }
    state.status = 'locked';
    const { record } = await deps.native.recall({ scope }).catch(() => ({ record: null as string | null }));
    state.remembered = record !== null;
    if (record !== null) {
      const result = await deps.core.unlockRemembered(record).catch(() => ({ kind: 'stale' as const }));
      if (result.kind === 'unlocked') {
        await adopt(result.vault);
      } else {
        // An older epoch (reset) or another vault: the record is useless now.
        await deps.native.forget({ scope }).catch(() => undefined);
        state.remembered = false;
      }
    }
    return state.status;
  }

  /** Unlock with the passphrase; `remember` seals the DEK record with this device's Keystore key. */
  async function unlock(passphrase: string, remember: boolean): Promise<VaultUnlockResult | { kind: 'backoff'; retryAt: number }> {
    if (state.retryAt > now()) return { kind: 'backoff', retryAt: state.retryAt };
    const result = await deps.core.unlock(passphrase);
    if (result.kind === 'wrong-passphrase') {
      state.failures += 1;
      const wait = Math.max(backoffMs(state.failures), result.retryAfterMs ?? 0);
      state.retryAt = wait > 0 ? now() + wait : 0;
      return result;
    }
    if (result.kind === 'absent') state.status = 'absent';
    if (result.kind !== 'unlocked') return result;
    await adopt(result.vault);
    const scope = await deps.scope().catch(() => null);
    if (scope) {
      if (remember) {
        await deps.native.remember({ scope, record: await result.vault.exportRemembered() });
        state.remembered = true;
      } else if (state.remembered) {
        await deps.native.forget({ scope }).catch(() => undefined);
        state.remembered = false;
      }
    }
    return result;
  }

  /** Lock: the decrypted vault and every native vault-key handle go. */
  async function lock(): Promise<void> {
    const open = unlocked;
    unlocked = null;
    open?.close();
    if (state.status === 'unlocked') state.status = 'locked';
    await releaseHandles();
  }

  /** "Forget on this device": the remembered record goes; the vault stays unlocked until it locks. */
  async function forgetDevice(): Promise<void> {
    const scope = await deps.scope().catch(() => null);
    if (scope) await deps.native.forget({ scope }).catch(() => undefined);
    state.remembered = false;
  }

  /** Sign-out: lock, and forget every remembered record and the device key. */
  async function signedOut(): Promise<void> {
    await lock();
    await deps.native.forgetAll().catch(() => undefined);
    state.remembered = false;
    state.status = 'signed-out';
  }

  async function handleFor(keyId: string, pem: string): Promise<string> {
    let pending = handles.get(keyId);
    if (!pending) {
      pending = deps.native.holdVaultKey({ privateKeyPem: pem }).then((held) => held.handleId);
      handles.set(keyId, pending);
      pending.catch(() => handles.delete(keyId));
    }
    return pending;
  }

  /**
   * What a dial of `alias` gets. A locked vault asks to unlock (never for a
   * key) unless the host is a gateway host mirrored as `host` policy, which
   * needs no vault key at all.
   */
  async function credentialFor(alias: string, options: { gateway: boolean; mirroredPolicy?: GatewayAuthPolicy | null } = { gateway: false }): Promise<VaultDialDecision> {
    let status = unlocked ? 'unlocked' : await refresh();
    if (status === 'signed-out' || status === 'absent') return { kind: 'no-vault' };
    if (status === 'locked') {
      if (options.gateway && options.mirroredPolicy === 'host') return { kind: 'host-keys' };
      if (!(await deps.askUnlock()) || !unlocked) return { kind: 'locked' };
      status = 'unlocked';
    }
    const vault = unlocked!;
    const binding = vault.binding(alias);
    if (!binding) return options.gateway ? { kind: 'host-keys' } : { kind: 'unbound' };
    if (options.gateway && binding.gatewayAuth === 'host') return { kind: 'host-keys' };
    const secret = await vault.decryptForHost(alias);
    if (secret.kind === 'password') {
      return { kind: 'credential', credential: { kind: 'password', password: secret.password }, secret: 'password', gatewayAuth: binding.gatewayAuth };
    }
    const handleId = await handleFor(binding.keyId, secret.pem);
    return { kind: 'credential', credential: { kind: 'key-handle', handleId }, secret: 'key', gatewayAuth: binding.gatewayAuth };
  }

  return { state, refresh, unlock, lock, forgetDevice, signedOut, credentialFor };
}

export type AndroidVault = ReturnType<typeof createAndroidVault>;
