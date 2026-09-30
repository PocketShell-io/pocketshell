import type { SshHostTarget } from '@pocketshell/core';
import type { ImportedLegacyHost } from './installedDataMigration';

export interface LegacyPrivateKeyCredentialReference {
  kind: 'legacy-private-key';
  keyId: number;
  sha256: string;
  passphrase?: string;
}

export type AppSshHostTarget = Omit<SshHostTarget, 'credential'> & {
  credential: SshHostTarget['credential'] | LegacyPrivateKeyCredentialReference;
};

/** Build an app-local opaque reference; old private-key bytes stay native. */
export function makeLegacySshHostTarget(
  host: ImportedLegacyHost,
  passphrase: string,
): AppSshHostTarget {
  return {
    hostId: String(host.id),
    hostname: host.hostname,
    port: host.port,
    username: host.username,
    credential: {
      kind: 'legacy-private-key',
      keyId: host.keyId,
      sha256: host.keySha256,
      ...(host.keyHasPassphrase && passphrase ? { passphrase } : {}),
    },
  };
}
