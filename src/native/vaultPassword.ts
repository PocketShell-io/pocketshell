import { registerPlugin, type Plugin } from '@capacitor/core';
import type {
  KeyVaultPasswordManager,
  PasswordManagerRetrieve,
  PasswordManagerSave,
} from '@ui/app/keyVault';

/**
 * The vault passphrase in Google Password Manager (Credential Manager, via
 * VaultPasswordPlugin.java). The passphrase moves only between the vault UI and
 * the system provider's own sheet: no logging, no server request, no clipboard.
 */
export type NativeVaultPassword = Plugin & {
  save(options: { accountId: string; passphrase: string }): Promise<unknown>;
  retrieve(options: { accountId: string }): Promise<unknown>;
};

const SAVE_OUTCOMES = new Set<PasswordManagerSave>(['saved', 'cancelled', 'unavailable', 'failed']);
const RETRIEVE_FAILURES = new Set(['none', 'cancelled', 'unavailable', 'failed', 'otherAccount']);

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** `accountId` is the verified issuer+subject id; null (signed out) makes both calls fail closed. */
export function createVaultPasswordManager(
  native: NativeVaultPassword,
  accountId: () => Promise<string | null>,
): KeyVaultPasswordManager {
  return {
    label: 'Google Password Manager',
    async save(passphrase): Promise<PasswordManagerSave> {
      const account = await accountId();
      if (!account) return 'failed';
      try {
        const reply = await native.save({ accountId: account, passphrase });
        const outcome = isRecord(reply) ? reply.outcome : undefined;
        return typeof outcome === 'string' && SAVE_OUTCOMES.has(outcome as PasswordManagerSave) ? (outcome as PasswordManagerSave) : 'failed';
      } catch {
        return 'failed';
      }
    },
    async retrieve(): Promise<PasswordManagerRetrieve> {
      const account = await accountId();
      if (!account) return { kind: 'failed' };
      try {
        const reply = await native.retrieve({ accountId: account });
        if (!isRecord(reply) || typeof reply.outcome !== 'string') return { kind: 'failed' };
        if (reply.outcome === 'ok') {
          // The account may have switched while the system sheet was open.
          if (await accountId() !== account) return { kind: 'otherAccount' };
          return typeof reply.passphrase === 'string' && reply.passphrase !== ''
            ? { kind: 'ok', passphrase: reply.passphrase }
            : { kind: 'failed' };
        }
        return RETRIEVE_FAILURES.has(reply.outcome) ? { kind: reply.outcome as 'none' } : { kind: 'failed' };
      } catch {
        return { kind: 'failed' };
      }
    },
  };
}

export const vaultPassword = registerPlugin<NativeVaultPassword>('VaultPassword');
