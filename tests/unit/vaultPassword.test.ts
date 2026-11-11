import { describe, expect, it, vi } from 'vitest';
import { createVaultPasswordManager, type NativeVaultPassword } from '@/native/vaultPassword';

// Synthetic account and passphrase only: no real Google account is involved.
const ACCOUNT = 'https://accounts.google.com|synthetic-subject-1';
const PASSPHRASE = 'synthetic-vault-passphrase-1';

function native(over: Partial<Record<'save' | 'retrieve', (o: never) => Promise<unknown>>> = {}) {
  return {
    save: vi.fn(async () => ({ outcome: 'saved' })),
    retrieve: vi.fn(async () => ({ outcome: 'ok', passphrase: PASSPHRASE })),
    ...over,
  } as unknown as NativeVaultPassword & { save: ReturnType<typeof vi.fn>; retrieve: ReturnType<typeof vi.fn> };
}

describe('Google Password Manager bridge for the vault passphrase', () => {
  it('saves under the verified account id and reports each provider outcome', async () => {
    const plugin = native();
    const manager = createVaultPasswordManager(plugin, async () => ACCOUNT);
    expect(manager.label).toBe('Google Password Manager');
    expect(await manager.save(PASSPHRASE)).toBe('saved');
    expect(plugin.save).toHaveBeenCalledWith({ accountId: ACCOUNT, passphrase: PASSPHRASE });
    for (const outcome of ['cancelled', 'unavailable', 'failed'] as const) {
      plugin.save.mockResolvedValueOnce({ outcome });
      expect(await manager.save(PASSPHRASE)).toBe(outcome);
    }
    plugin.save.mockResolvedValueOnce({ outcome: 'surprise' });
    expect(await manager.save(PASSPHRASE)).toBe('failed');
    plugin.save.mockRejectedValueOnce(new Error(`bridge says ${PASSPHRASE}`));
    expect(await manager.save(PASSPHRASE)).toBe('failed');
  });

  it('fails closed when signed out and never calls the provider', async () => {
    const plugin = native();
    const manager = createVaultPasswordManager(plugin, async () => null);
    expect(await manager.save(PASSPHRASE)).toBe('failed');
    expect(await manager.retrieve()).toEqual({ kind: 'failed' });
    expect(plugin.save).not.toHaveBeenCalled();
    expect(plugin.retrieve).not.toHaveBeenCalled();
  });

  it('retrieves only for the current account and types every non-ok outcome', async () => {
    const plugin = native();
    const manager = createVaultPasswordManager(plugin, async () => ACCOUNT);
    expect(await manager.retrieve()).toEqual({ kind: 'ok', passphrase: PASSPHRASE });
    expect(plugin.retrieve).toHaveBeenCalledWith({ accountId: ACCOUNT });
    for (const kind of ['none', 'cancelled', 'unavailable', 'failed', 'otherAccount'] as const) {
      plugin.retrieve.mockResolvedValueOnce({ outcome: kind });
      expect(await manager.retrieve()).toEqual({ kind });
    }
    plugin.retrieve.mockResolvedValueOnce({ outcome: 'ok' });
    expect(await manager.retrieve()).toEqual({ kind: 'failed' });
    plugin.retrieve.mockResolvedValueOnce(null);
    expect(await manager.retrieve()).toEqual({ kind: 'failed' });
    plugin.retrieve.mockRejectedValueOnce(new Error('plugin missing'));
    expect(await manager.retrieve()).toEqual({ kind: 'failed' });
  });

  it('refuses a passphrase fetched while the account switched under the system sheet', async () => {
    const plugin = native();
    const accounts = [ACCOUNT, 'https://accounts.google.com|synthetic-subject-2'];
    const manager = createVaultPasswordManager(plugin, async () => accounts.shift() ?? null);
    expect(await manager.retrieve()).toEqual({ kind: 'otherAccount' });
  });
});
