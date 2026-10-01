import type { SshHostTarget } from '@pocketshell/core';
import type { ImportedLegacyHost } from './installedDataMigration';

export interface LegacyPrivateKeyCredentialReference {
  kind: 'key-handle';
  handleId: string;
  passphrase?: string;
}

export type AppSshHostTarget = Omit<SshHostTarget, 'credential'> & {
  credential: SshHostTarget['credential'] | LegacyPrivateKeyCredentialReference;
};

/** Build an app-local opaque reference; old private-key bytes stay native. */
export function makeLegacySshHostTarget(
  host: ImportedLegacyHost,
  passphrase: string,
): SshHostTarget {
  if (!host.keyHandleId) throw new Error('This saved host has no imported SSH key. Open SSH keys and import its key before connecting.');
  return {
    hostId: String(host.id),
    hostname: host.hostname,
    port: host.port,
    username: host.username,
    credential: {
      kind: 'key-handle',
      handleId: host.keyHandleId,
      ...(host.keyHasPassphrase && passphrase ? { passphrase } : {}),
    },
  };
}
