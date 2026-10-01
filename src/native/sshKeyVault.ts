import { registerPlugin, type Plugin } from '@capacitor/core';

export interface SshKeyMetadata {
  handleId: string;
  label: string;
  algorithm: 'ssh-ed25519' | 'ssh-rsa';
  fingerprintSha256: string;
  passphraseRequired: boolean;
  createdAt: number;
}

export interface PickedSshKeyDocument {
  cancelled: boolean;
  documentId?: string;
  name?: string;
}

export interface LegacySshKeyCandidate {
  legacyKeyId: number;
  sha256: string;
  label: string;
  passphraseRequired: boolean;
}

export interface LegacySshKeyImportResult {
  keys: Array<{ legacyKeyId: number; key: SshKeyMetadata }>;
  failures: Array<{ legacyKeyId: number; message: string }>;
}

export type NativeSshKeyVaultPlugin = Plugin & {
  listKeys(): Promise<{ keys: unknown[] }>;
  pickKeyDocument(): Promise<PickedSshKeyDocument>;
  importPickedKey(options: { documentId: string; label: string; passphrase?: string }): Promise<unknown>;
  importDocumentUri(options: { uri: string; label: string; passphrase?: string }): Promise<unknown>;
  generateKey(options: { label: string; algorithm: 'Ed25519' | 'RSA-3072' }): Promise<unknown>;
  deleteKey(options: { handleId: string; fingerprintSha256: string }): Promise<{ deleted: boolean }>;
  importLegacyKeys(options: { keys: LegacySshKeyCandidate[] }): Promise<LegacySshKeyImportResult>;
};

const nativeVault = registerPlugin<NativeSshKeyVaultPlugin>('SshKeyVault');

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

export function isSshKeyMetadata(value: unknown): value is SshKeyMetadata {
  if (!isRecord(value)) return false;
  return typeof value.handleId === 'string' && /^[0-9a-f-]{36}$/i.test(value.handleId)
    && typeof value.label === 'string' && value.label.length > 0 && value.label.length <= 80
    && (value.algorithm === 'ssh-ed25519' || value.algorithm === 'ssh-rsa')
    && typeof value.fingerprintSha256 === 'string' && /^SHA256:[A-Za-z0-9+/]{43}$/.test(value.fingerprintSha256)
    && typeof value.passphraseRequired === 'boolean'
    && typeof value.createdAt === 'number' && Number.isSafeInteger(value.createdAt) && value.createdAt >= 0
    && Object.keys(value).every((key) => [
      'handleId', 'label', 'algorithm', 'fingerprintSha256', 'passphraseRequired', 'createdAt',
    ].includes(key));
}

export function parseSshKeyMetadata(value: unknown): SshKeyMetadata {
  if (!isSshKeyMetadata(value)) throw new Error('The Android key vault returned invalid public metadata.');
  return { ...value };
}

/** Validate each native record before any metadata reaches UI or host policy. */
export async function listSshKeys(vault: NativeSshKeyVaultPlugin = nativeVault): Promise<SshKeyMetadata[]> {
  const result = await vault.listKeys();
  if (!Array.isArray(result.keys)) throw new Error('The Android key vault returned an invalid key list.');
  const keys = result.keys.map(parseSshKeyMetadata);
  const handles = new Set<string>();
  const fingerprints = new Set<string>();
  for (const key of keys) {
    if (handles.has(key.handleId) || fingerprints.has(key.fingerprintSha256)) {
      throw new Error('The Android key vault returned duplicate key metadata.');
    }
    handles.add(key.handleId);
    fingerprints.add(key.fingerprintSha256);
  }
  return keys;
}

export const sshKeyVault = nativeVault;
