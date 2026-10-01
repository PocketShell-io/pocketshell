/**
 * The phone's saved-host store and the key-vault manager that knows which
 * hosts use which key. One instance, shared by the shared app and the legacy
 * screens, so deleting a key from either shell warns about every host that
 * still uses it.
 */
import { CredentialKeyManager } from '@/credentials/keyManagement';
import { sshKeyVault } from '@/native/sshKeyVault';
import { createLegacySshKeyReferenceStore } from '@/migration/legacySshKeyReferences';
import { readImportedLegacyHosts } from '@/migration/installedDataMigration';
import { AndroidHostStore } from './hostStore';
import { combineKeyReferenceStores, createAndroidHostKeyReferenceStore } from './hostKeyReferences';

export const androidHosts = new AndroidHostStore({
  storage: window.localStorage,
  readLegacyHosts: () => readImportedLegacyHosts(),
});

export const androidKeyManager = new CredentialKeyManager(
  sshKeyVault,
  combineKeyReferenceStores(createLegacySshKeyReferenceStore(), createAndroidHostKeyReferenceStore(androidHosts)),
);
