import type { SshKeyHandleCredential } from '@pocketshell/core';
import {
  listSshKeys,
  parseSshKeyMetadata,
  parseSshPublicKeyLine,
  sshKeyVault,
  type NativeSshKeyVaultPlugin,
  type SshKeyMetadata,
} from '@/native/sshKeyVault';

export interface SshKeyHostReference {
  hostId: string;
  hostLabel: string;
}

export interface SshKeyReferenceStore {
  list(handleId: string): Promise<SshKeyHostReference[]>;
  /** Atomically removes this handle from every listed host if those references still match. */
  detach(handleId: string, references: SshKeyHostReference[]): Promise<boolean>;
  /** Restores references if a native key deletion failed before it committed. */
  restore(handleId: string, references: SshKeyHostReference[]): Promise<boolean>;
  /** Commits any reference-store deletion journal after native key deletion. */
  commitDelete?(handleId: string): void;
  /** Clears references to missing vault handles so hosts cannot target orphan IDs. */
  reconcileMissing?(availableHandleIds: ReadonlySet<string>): Promise<void>;
}

export type DeleteKeyResult =
  | { status: 'deleted'; affectedHosts: SshKeyHostReference[] }
  | { status: 'confirmation-required'; affectedHosts: SshKeyHostReference[] };

/** JS-owned validation, selection and host-reference policy over native key operations. */
export class CredentialKeyManager {
  constructor(
    private readonly vault: NativeSshKeyVaultPlugin = sshKeyVault,
    private readonly references: SshKeyReferenceStore = emptyReferences,
  ) {}

  async list(): Promise<SshKeyMetadata[]> {
    const keys = await listSshKeys(this.vault);
    await this.references.reconcileMissing?.(new Set(keys.map((key) => key.handleId)));
    return keys;
  }

  async pickAndImport(options: { label: string; passphrase?: string }): Promise<SshKeyMetadata | null> {
    const picked = await this.vault.pickKeyDocument();
    if (picked.cancelled) return null;
    if (!picked.documentId) throw new Error('Android did not return a document reference for the SSH key.');
    const key = parseSshKeyMetadata(await this.vault.importPickedKey({
      documentId: picked.documentId,
      label: options.label.trim() || picked.name || 'Imported SSH key',
      ...(options.passphrase ? { passphrase: options.passphrase } : {}),
    }));
    return key;
  }

  /**
   * Import pasted private-key text (#3021). The text goes straight to the
   * native vault, which validates and seals it; only public metadata returns.
   * The caller owns clearing its input field whatever the outcome.
   */
  async importText(options: { text: string; label: string; passphrase?: string }): Promise<SshKeyMetadata> {
    if (!options.text.trim()) throw new Error('Paste a private key first.');
    return parseSshKeyMetadata(await this.vault.importKeyText({
      text: options.text,
      label: options.label.trim() || 'Pasted SSH key',
      ...(options.passphrase ? { passphrase: options.passphrase } : {}),
    }));
  }

  /** The OpenSSH public line (`ssh-ed25519 AAAA… label`) for a stored key. */
  async publicKey(key: Pick<SshKeyMetadata, 'handleId' | 'algorithm'>, passphrase?: string): Promise<string> {
    return parseSshPublicKeyLine(await this.vault.publicKey({
      handleId: key.handleId,
      ...(passphrase ? { passphrase } : {}),
    }), key.algorithm);
  }

  /** Copy the public line to the Android clipboard natively. */
  async copyPublicKey(handleId: string, passphrase?: string): Promise<void> {
    const result = await this.vault.copyPublicKey({ handleId, ...(passphrase ? { passphrase } : {}) });
    if (result.copied !== true) throw new Error('The public key could not be copied.');
  }

  /** Open the Android share sheet with the public line. */
  async sharePublicKey(handleId: string, passphrase?: string): Promise<void> {
    const result = await this.vault.sharePublicKey({ handleId, ...(passphrase ? { passphrase } : {}) });
    if (result.shared !== true) throw new Error('The public key could not be shared.');
  }

  async generate(options: { label: string; algorithm: 'Ed25519' | 'RSA-3072' }): Promise<SshKeyMetadata> {
    return parseSshKeyMetadata(await this.vault.generateKey({ label: options.label.trim(), algorithm: options.algorithm }));
  }

  async delete(
    handleId: string,
    confirmedHosts?: readonly SshKeyHostReference[],
  ): Promise<DeleteKeyResult> {
    const keys = await this.list();
    const key = keys.find((candidate) => candidate.handleId === handleId);
    if (!key) throw new Error('The selected SSH key is no longer available. Refresh the list and try again.');
    const affectedHosts = await this.references.list(handleId);
    if (affectedHosts.length > 0 && (!confirmedHosts || !sameReferences(affectedHosts, confirmedHosts))) {
      return { status: 'confirmation-required', affectedHosts };
    }
    if (!await this.references.detach(handleId, affectedHosts)) {
      throw new Error('Host key associations changed before deletion. Refresh the hosts and try again.');
    }
    try {
      const result = await this.vault.deleteKey({ handleId, fingerprintSha256: key.fingerprintSha256 });
      if (result.deleted !== true) throw new Error('The Android key vault did not confirm key removal.');
    } catch (error) {
      if (!await this.references.restore(handleId, affectedHosts)) {
        throw new Error('Key removal failed and host associations could not be restored. Refresh saved hosts before connecting.');
      }
      throw error;
    }
    this.references.commitDelete?.(handleId);
    return { status: 'deleted', affectedHosts };
  }

  keyCredential(handleId: string, passphrase?: string | null): SshKeyHandleCredential {
    if (!handleId.trim()) throw new Error('Select an SSH key before connecting.');
    return {
      kind: 'key-handle',
      handleId,
      ...(passphrase == null ? {} : { passphrase }),
    };
  }
}

const emptyReferences: SshKeyReferenceStore = {
  async list() { return []; },
  async detach() { return true; },
  async restore() { return true; },
};

function sameReferences(
  left: readonly SshKeyHostReference[],
  right: readonly SshKeyHostReference[],
): boolean {
  if (left.length !== right.length) return false;
  const identify = (reference: SshKeyHostReference) => `${reference.hostId}\u0000${reference.hostLabel}`;
  const a = left.map(identify).sort();
  const b = right.map(identify).sort();
  return a.every((reference, index) => reference === b[index]);
}
