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
 *  - core's KeyVaultStore (adapted as `VaultCore` in coreVault.ts): the
 *    format, Argon2id, the check value, the DEK, the index and key blobs, the
 *    wrong-passphrase backoff and the phase-2 server check;
 *  - core's shared vault UI (packages/ui `provideKeyVault`): the unlock sheet,
 *    "Remember on this device" and "Forgot it?" — the same sheet every client
 *    shows. Android supplies only the Keystore-sealed remember record;
 *  - this module: what a dial of a host gets from the vault, the remembered
 *    unlock at launch, the sync passphrase hand-off, and the native handles'
 *    lifetime (wiped whenever the store locks).
 *
 * Hosts are bound by their ACCOUNT alias (the name in the `main` sync slot).
 */
import { reactive } from 'vue';
import type { GatewayAuthPolicy, HostKeyBinding, SshCredential, VaultSecret } from '@pocketshell/core';

export type { GatewayAuthPolicy, HostKeyBinding, VaultSecret };

/** An unlocked vault: the decrypted index and lazy key decryption (core KeyVaultStore session). */
export interface UnlockedVault {
  readonly vaultId: string;
  readonly epoch: number;
  binding(hostAlias: string): HostKeyBinding | null;
  /** The account's settings-sync passphrase from the index (contract #2), or null. */
  syncPassphrase(): string | null;
  decryptForHost(hostAlias: string): Promise<VaultSecret>;
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
  /** Unlock from a remembered DEK record; `stale` when it no longer opens this vault. */
  unlockRemembered(record: string): Promise<VaultUnlockResult | { kind: 'stale' }>;
  /** The unlocked vault, or null while locked. */
  current(): UnlockedVault | null;
  /** Called on every lock-state change, whoever unlocked (the shared sheet, a remembered record). */
  subscribe(listener: () => void): () => void;
  lock(): void;
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
  /** A gateway host that uses the host's own keys (the pairing key), no vault key. */
  | { kind: 'host-keys' }
  | { kind: 'credential'; credential: SshCredential; secret: VaultSecret['kind']; gatewayAuth: GatewayAuthPolicy };

export interface AndroidVaultDeps {
  core: VaultCore;
  native: VaultNative;
  /** The signed-in account (its e-mail), the scope of a remembered record; null when signed out. */
  scope(): Promise<string | null>;
  /** Show the shared unlock sheet; true once the vault is unlocked, false when the user closed it. */
  askUnlock(hostLabel: string): Promise<boolean>;
  /** Called with the index's sync passphrase after every unlock (contract #2: one passphrase). */
  onUnlocked?(syncPassphrase: string | null): void | Promise<void>;
}

export function createAndroidVault(deps: AndroidVaultDeps) {
  const state = reactive({
    status: 'signed-out' as VaultStatus,
    /** Whether a remembered record exists for this account on this device. */
    remembered: false,
  });
  /** keyId → native ephemeral handle, while unlocked. */
  const handles = new Map<string, Promise<string>>();
  /** Bumped on every lock, so a hold that finishes after a lock is released, not kept. */
  let generation = 0;

  async function releaseHandles(): Promise<void> {
    generation += 1;
    handles.clear();
    await deps.native.releaseVaultKeys().catch(() => undefined);
  }

  // Follow the store whoever changes it: the shared sheet unlocking, a
  // remembered record at launch, or a lock.
  deps.core.subscribe(() => {
    const open = deps.core.current();
    if (open && state.status !== 'unlocked') {
      state.status = 'unlocked';
      void Promise.resolve(deps.onUnlocked?.(open.syncPassphrase())).catch(() => undefined);
    } else if (!open && state.status === 'unlocked') {
      state.status = 'locked';
      void releaseHandles();
    }
  });

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
    if (deps.core.current()) return (state.status = 'unlocked');
    if (!(await deps.core.present())) return (state.status = 'absent');
    state.status = 'locked';
    const { record } = await deps.native.recall({ scope }).catch(() => ({ record: null as string | null }));
    state.remembered = record !== null;
    if (record !== null) {
      const result = await deps.core.unlockRemembered(record).catch((error: unknown) => ({
        kind: 'error' as const,
        message: error instanceof Error ? error.message : String(error),
      }));
      if (result.kind === 'stale' || result.kind === 'absent') {
        // An older epoch (reset), another vault, or a passphrase changed
        // elsewhere: the record is useless now. Any other failure (offline,
        // rate limited) keeps it for the next launch and asks this time.
        await deps.native.forget({ scope }).catch(() => undefined);
        state.remembered = false;
      }
    }
    return state.status;
  }

  /** Lock: the decrypted vault and every native vault-key handle go. */
  async function lock(): Promise<void> {
    deps.core.lock();
    if (state.status === 'unlocked') state.status = 'locked';
    await releaseHandles();
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
      const at = generation;
      pending = deps.native.holdVaultKey({ privateKeyPem: pem }).then(async (held) => {
        if (at !== generation) {
          // The vault locked while native was holding it: never keep it.
          await deps.native.releaseVaultKeys({ handleIds: [held.handleId] }).catch(() => undefined);
          throw new Error('Your key vault locked. Unlock it and connect again.');
        }
        return held.handleId;
      });
      handles.set(keyId, pending);
      pending.catch(() => {
        if (handles.get(keyId) === pending) handles.delete(keyId);
      });
    }
    return pending;
  }

  /**
   * What a dial of `alias` gets. A direct host with a locked vault asks to
   * unlock (never for a key). A gateway host follows its `gatewayAuth`:
   * `host` never needs the vault; `both` uses the vault key when the vault is
   * (or gets) unlocked and binds the host, and the host's own keys otherwise,
   * including when the user closes the sheet; only `managed` requires the
   * vault key. With no mirrored policy a gateway host behaves as `both`.
   */
  async function credentialFor(
    alias: string,
    options: { gateway: boolean; mirroredPolicy?: GatewayAuthPolicy | null } = { gateway: false },
  ): Promise<VaultDialDecision> {
    let status = deps.core.current() ? 'unlocked' : await refresh();
    if (status === 'signed-out' || status === 'absent') return { kind: 'no-vault' };
    const mirrored = options.mirroredPolicy ?? null;
    if (status === 'locked') {
      if (options.gateway && mirrored === 'host') return { kind: 'host-keys' };
      if (!(await deps.askUnlock(alias)) || !deps.core.current()) {
        return options.gateway && mirrored !== 'managed' ? { kind: 'host-keys' } : { kind: 'locked' };
      }
      status = 'unlocked';
    }
    const vault = deps.core.current()!;
    const binding = vault.binding(alias);
    if (!binding) {
      if (!options.gateway) return { kind: 'unbound' };
      // A gateway host the vault has no key for: its own keys, unless the
      // host only accepts vault keys.
      return mirrored === 'managed' ? { kind: 'unbound' } : { kind: 'host-keys' };
    }
    if (options.gateway && binding.gatewayAuth === 'host') return { kind: 'host-keys' };
    const secret = await vault.decryptForHost(alias);
    if (secret.kind === 'password') {
      return { kind: 'credential', credential: { kind: 'password', password: secret.password }, secret: 'password', gatewayAuth: binding.gatewayAuth };
    }
    const handleId = await handleFor(binding.keyId, secret.pem);
    return { kind: 'credential', credential: { kind: 'key-handle', handleId }, secret: 'key', gatewayAuth: binding.gatewayAuth };
  }

  return { state, refresh, lock, signedOut, credentialFor };
}

export type AndroidVault = ReturnType<typeof createAndroidVault>;
