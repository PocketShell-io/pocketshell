import { describe, expect, it, vi } from 'vitest';
import { CredentialKeyManager, type SshKeyReferenceStore } from '@/credentials/keyManagement';
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

  it('clears hosts whose key is gone from the vault', async () => {
    const hosts = hostStore();
    hosts.save({ name: 'box', hostname: 'box', port: 22, user: 'u', keyHandleId: HANDLE_B });
    const manager = new CredentialKeyManager(fakeVault().vault, createAndroidHostKeyReferenceStore(hosts));
    await manager.list();
    expect(hosts.savedHosts()[0]!.keyHandleId).toBe('');
  });
});
