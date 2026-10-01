import { beforeAll, describe, expect, it, vi } from 'vitest';

// The real shared singletons (src/platform/android/hosts.ts) over a fake key
// vault and an empty 0.5.x import: this pins that the one key manager both
// shells use includes the hosts saved through the shared app.
const HANDLE = '00000000-0000-4000-8000-00000000000a';

vi.mock('@/native/sshKeyVault', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/native/sshKeyVault')>();
  const vault = {
    listKeys: async () => ({ keys: [{
      handleId: HANDLE, label: 'k', algorithm: 'ssh-ed25519', fingerprintSha256: `SHA256:${'a'.repeat(43)}`,
      passphraseRequired: false, createdAt: 1,
    }] }),
    deleteKey: vi.fn(async () => ({ deleted: true })),
  };
  return { ...actual, sshKeyVault: vault };
});
vi.mock('@/migration/legacySshKeyReferences', () => ({
  createLegacySshKeyReferenceStore: () => ({ list: async () => [], detach: async () => true, restore: async () => true }),
}));
vi.mock('@/migration/installedDataMigration', () => ({ readImportedLegacyHosts: async () => [] }));

let shared: typeof import('@/platform/android/hosts');

beforeAll(async () => {
  const values = new Map<string, string>();
  vi.stubGlobal('window', {
    localStorage: { getItem: (key: string) => values.get(key) ?? null, setItem: (key: string, value: string) => void values.set(key, value) },
  });
  shared = await import('@/platform/android/hosts');
});

describe('shared Android key manager', () => {
  it('reports a host saved through the shared app before deleting its key', async () => {
    shared.androidHosts.save({ name: 'box', hostname: 'box', port: 22, user: 'u', keyHandleId: HANDLE });
    expect(await shared.androidKeyManager.delete(HANDLE)).toEqual({
      status: 'confirmation-required',
      affectedHosts: [{ hostId: 'android-host:box', hostLabel: 'box' }],
    });
  });
});
