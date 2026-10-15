import { registerPlugin, type Plugin } from '@capacitor/core';
import type { VaultNative } from '@/vault/androidVault';

/**
 * The native halves of the account key vault (key-vault W4):
 *  - `SshKeyVault.holdVaultKey` / `releaseVaultKeys`: a vault key decrypted
 *    in the WebView is held in native PROCESS MEMORY only
 *    (EphemeralCredentials.java) and dialled by an opaque `ephemeral-` handle;
 *    nothing is written to disk and the key never comes back over the bridge;
 *  - `VaultDeviceKey`: "Remember on this device" — the DEK record sealed by a
 *    non-exportable Android Keystore key (VaultDeviceKeyPlugin.java).
 */
type NativeVaultKeys = Plugin & {
  holdVaultKey(options: { privateKeyPem: string }): Promise<unknown>;
  releaseVaultKeys(options: { handleIds?: string[] }): Promise<unknown>;
};

type NativeDeviceKey = Plugin & {
  remember(options: { scope: string; record: string }): Promise<unknown>;
  recall(options: { scope: string }): Promise<unknown>;
  forget(options: { scope: string }): Promise<unknown>;
  forgetAll(): Promise<unknown>;
};

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

export function createVaultNative(keys: NativeVaultKeys, device: NativeDeviceKey): VaultNative {
  return {
    async holdVaultKey(options) {
      const reply = await keys.holdVaultKey(options);
      if (!isRecord(reply) || typeof reply.handleId !== 'string' || !reply.handleId.startsWith('ephemeral-')
        || typeof reply.algorithm !== 'string' || typeof reply.fingerprintSha256 !== 'string') {
        throw new Error('The Android key vault returned an invalid vault-key handle.');
      }
      return { handleId: reply.handleId, algorithm: reply.algorithm, fingerprintSha256: reply.fingerprintSha256 };
    },
    async releaseVaultKeys(options = {}) {
      const reply = await keys.releaseVaultKeys(options.handleIds ? { handleIds: options.handleIds } : {});
      return { released: isRecord(reply) && typeof reply.released === 'number' ? reply.released : 0 };
    },
    remember: (options) => device.remember(options),
    async recall(options) {
      const reply = await device.recall(options);
      return { record: isRecord(reply) && typeof reply.record === 'string' ? reply.record : null };
    },
    forget: (options) => device.forget(options),
    forgetAll: () => device.forgetAll(),
  };
}

export const vaultNative: VaultNative = createVaultNative(
  registerPlugin<NativeVaultKeys>('SshKeyVault'),
  registerPlugin<NativeDeviceKey>('VaultDeviceKey'),
);
