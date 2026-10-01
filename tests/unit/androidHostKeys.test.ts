import { describe, expect, it, vi } from 'vitest';
import { CredentialKeyManager, type SshKeyReferenceStore } from '@/credentials/keyManagement';
import { createLegacySshKeyReferenceStore } from '@/migration/legacySshKeyReferences';
import { IMPORT_RECORD_ID, type ImportPersistence, type ImportRecord } from '@/migration/installedDataMigration';
import type { NativeSshKeyVaultPlugin } from '@/native/sshKeyVault';
import { AndroidHostStore } from '@/platform/android/hostStore';
import { combineKeyReferenceStores, createAndroidHostKeyReferenceStore } from '@/platform/android/hostKeyReferences';
import { createHostForm } from '@/sharedApp/hostForm';

const HANDLE_A = '00000000-0000-4000-8000-00000000000a';
const HANDLE_B = '00000000-0000-4000-8000-00000000000b';
const fingerprint = (letter: string) => `SHA256:${letter.repeat(43)}`;
const meta = (handleId: string, letter: string, label = `key ${letter}`) => ({
  handleId, label, algorithm: 'ssh-ed25519', fingerprintSha256: fingerprint(letter), passphraseRequired: false, createdAt: 1,
});

class MemoryStorage {
  readonly values = new Map<string, string>();
  getItem(key: string) { return this.values.get(key) ?? null; }
  setItem(key: string, value: string) { this.values.set(key, value); }
}

function fakeVault(initial = [meta(HANDLE_A, 'a')]) {
  const keys = [...initial];
  const vault = {
    listKeys: vi.fn(async () => ({ keys: [...keys] })),
    pickKeyDocument: vi.fn(async (): Promise<{ cancelled: boolean; documentId?: string; name?: string }> => ({ cancelled: false, documentId: 'doc-1', name: 'id_ed25519' })),
    importPickedKey: vi.fn(async (options: { documentId: string; label: string; passphrase?: string }) => {
      const key = meta(HANDLE_B, 'b', options.label);
      keys.push(key);
      return key;
    }),
    deleteKey: vi.fn(async ({ handleId }: { handleId: string }) => {
      const index = keys.findIndex((key) => key.handleId === handleId);
      if (index >= 0) keys.splice(index, 1);
      return { deleted: true };
    }),
  };
  return { vault: vault as unknown as NativeSshKeyVaultPlugin & typeof vault, keys };
}

function hostStore() {
  return new AndroidHostStore({ storage: new MemoryStorage(), readLegacyHosts: async () => [] });
}

/** A signed 0.5.x import record: host 41 uses HANDLE_A (key 7), host 42 another key. */
function legacyPersistence(): { persistence: ImportPersistence; read: () => ImportRecord } {
  let current: ImportRecord = {
    id: IMPORT_RECORD_ID,
    status: 'complete',
    importedAt: 1,
    warnings: [],
    credentialHandles: { '7': HANDLE_A, '8': '00000000-0000-4000-8000-000000000008' },
    credentialHandleTombstones: [],
    snapshot: {
      database: { tables: { hosts: [
        { id: 41, name: 'devbox', hostname: 'dev.example', keyId: 7 },
        { id: 42, name: 'old host', hostname: 'old.example', keyId: 8 },
      ] } },
    } as unknown as ImportRecord['snapshot'],
  };
  const persistence = {
    async readRecord() { return current; },
    async compareAndSetCredentialHandles(expected: Record<string, string | null>, next: Record<string, string | null>, tombstones?: { expected: number[]; next: number[] }) {
      if (JSON.stringify(current.credentialHandles ?? {}) !== JSON.stringify(expected)) return false;
      if (tombstones && JSON.stringify(current.credentialHandleTombstones ?? []) !== JSON.stringify(tombstones.expected)) return false;
      current = { ...current, credentialHandles: { ...next }, ...(tombstones ? { credentialHandleTombstones: [...tombstones.next] } : {}) };
      return true;
    },
  } as unknown as ImportPersistence;
  return { persistence, read: () => current };
}

describe('Android add-host key flow', () => {
  it('"Import key file…" imports the picked document with its passphrase and selects the new key', async () => {
    const { vault } = fakeVault();
    const hosts = hostStore();
    const form = createHostForm({ keys: new CredentialKeyManager(vault), hosts });
    await form.loadKeys();
    expect(form.state.keyHandleId).toBe(HANDLE_A); // the only key is preselected
    form.state.name = 'box';
    form.state.keyPassphrase = 'secret';

    await form.importKey();
    expect(vault.importPickedKey).toHaveBeenCalledWith({ documentId: 'doc-1', label: 'box', passphrase: 'secret' });
    expect(form.state.keyHandleId).toBe(HANDLE_B);
    expect(form.state.keys.map((key) => key.handleId)).toEqual([HANDLE_A, HANDLE_B]);
    expect(form.state.keyPassphrase).toBe('');
    expect(form.state.error).toBeNull();

    Object.assign(form.state, { hostname: 'box.example', port: '2222', user: 'u' });
    expect(form.save()).toBe(true);
    expect(hosts.savedHosts()).toEqual([{ name: 'box', hostname: 'box.example', port: 2222, user: 'u', keyHandleId: HANDLE_B }]);
  });

  it('a cancelled pick changes nothing, and an import failure is shown, not thrown', async () => {
    const { vault } = fakeVault();
    const form = createHostForm({ keys: new CredentialKeyManager(vault), hosts: hostStore() });
    await form.loadKeys();
    vault.pickKeyDocument.mockResolvedValueOnce({ cancelled: true });
    await form.importKey();
    expect(vault.importPickedKey).not.toHaveBeenCalled();
    expect(form.state.keyHandleId).toBe(HANDLE_A);

    vault.importPickedKey.mockRejectedValueOnce(new Error('wrong passphrase'));
    await form.importKey();
    expect(form.state.error).toBe('wrong passphrase');
    expect(form.state.importing).toBe(false);
  });

  it('refuses to save a host without a key', () => {
    const form = createHostForm({ keys: new CredentialKeyManager(fakeVault([]).vault), hosts: hostStore() });
    Object.assign(form.state, { name: 'box', hostname: 'box', user: 'u' });
    expect(form.save()).toBe(false);
    expect(form.state.error).toBe('Choose an SSH key from the key vault.');
  });

  const legacyNone: SshKeyReferenceStore = {
    list: async () => [], detach: async () => true, restore: async () => true,
  };

  it('asks before deleting a key a shared-app host uses, then detaches that host', async () => {
    const { vault } = fakeVault();
    const hosts = hostStore();
    hosts.save({ name: 'box', hostname: 'box', port: 22, user: 'u', keyHandleId: HANDLE_A });
    const manager = new CredentialKeyManager(vault, combineKeyReferenceStores(legacyNone, createAndroidHostKeyReferenceStore(hosts)));

    const first = await manager.delete(HANDLE_A);
    expect(first).toEqual({ status: 'confirmation-required', affectedHosts: [{ hostId: 'android-host:box', hostLabel: 'box' }] });
    expect(vault.deleteKey).not.toHaveBeenCalled();

    const confirmed = await manager.delete(HANDLE_A, first.affectedHosts);
    expect(confirmed.status).toBe('deleted');
    expect(hosts.savedHosts()[0]!.keyHandleId).toBe('');
  });

  it('restores the host reference when the native delete fails', async () => {
    const { vault } = fakeVault();
    vault.deleteKey.mockRejectedValueOnce(new Error('keystore busy'));
    const hosts = hostStore();
    hosts.save({ name: 'box', hostname: 'box', port: 22, user: 'u', keyHandleId: HANDLE_A });
    const manager = new CredentialKeyManager(vault, combineKeyReferenceStores(legacyNone, createAndroidHostKeyReferenceStore(hosts)));
    const refs = (await manager.delete(HANDLE_A)).affectedHosts;
    await expect(manager.delete(HANDLE_A, refs)).rejects.toThrow();
    expect(hosts.savedHosts()[0]!.keyHandleId).toBe(HANDLE_A);
  });

  it('one key used by a 0.5.x host and a shared-app host: delete confirms, restores on failure, then detaches both', async () => {
    const { vault } = fakeVault();
    vault.deleteKey.mockRejectedValueOnce(new Error('keystore busy'));
    const legacy = legacyPersistence();
    const hosts = hostStore();
    hosts.save({ name: 'box', hostname: 'box', port: 22, user: 'u', keyHandleId: HANDLE_A });
    const manager = new CredentialKeyManager(vault, combineKeyReferenceStores(
      createLegacySshKeyReferenceStore(legacy.persistence), createAndroidHostKeyReferenceStore(hosts),
    ));

    const asked = await manager.delete(HANDLE_A);
    expect(asked.status).toBe('confirmation-required');
    expect(asked.affectedHosts.map((host) => host.hostLabel).sort()).toEqual(['box', 'devbox']);

    // The native delete fails: both stores get their host back.
    await expect(manager.delete(HANDLE_A, asked.affectedHosts)).rejects.toThrow();
    expect(legacy.read().credentialHandles?.['7']).toBe(HANDLE_A);
    expect(hosts.savedHosts()[0]!.keyHandleId).toBe(HANDLE_A);

    // Confirmed again and the delete commits: both hosts are detached.
    expect((await manager.delete(HANDLE_A, asked.affectedHosts)).status).toBe('deleted');
    expect(legacy.read().credentialHandles?.['7']).toBeNull();
    expect(hosts.savedHosts()[0]!.keyHandleId).toBe('');
  });

  it('rolls back the earlier store when a later store refuses the detach', async () => {
    const hosts = hostStore();
    hosts.save({ name: 'box', hostname: 'box', port: 22, user: 'u', keyHandleId: HANDLE_A });
    const refusing: SshKeyReferenceStore = {
      list: async () => [{ hostId: 'other:1', hostLabel: 'other' }],
      detach: async () => false,
      restore: async () => true,
    };
    const { vault } = fakeVault();
    const manager = new CredentialKeyManager(vault, combineKeyReferenceStores(createAndroidHostKeyReferenceStore(hosts), refusing));
    const asked = await manager.delete(HANDLE_A);
    await expect(manager.delete(HANDLE_A, asked.affectedHosts)).rejects.toThrow(/associations changed/);
    expect(vault.deleteKey).not.toHaveBeenCalled();
    expect(hosts.savedHosts()[0]!.keyHandleId).toBe(HANDLE_A);
  });

  it('clears hosts whose key is gone from the vault', async () => {
    const hosts = hostStore();
    hosts.save({ name: 'box', hostname: 'box', port: 22, user: 'u', keyHandleId: HANDLE_B });
    const manager = new CredentialKeyManager(fakeVault().vault, createAndroidHostKeyReferenceStore(hosts));
    await manager.list();
    expect(hosts.savedHosts()[0]!.keyHandleId).toBe('');
  });
});
