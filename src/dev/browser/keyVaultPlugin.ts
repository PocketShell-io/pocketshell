/**
 * Browser dev mode (#3022): `SshKeyVault`, held IN MEMORY ONLY.
 *
 * On a phone the vault is Android Keystore-backed and key bytes never reach
 * the WebView. Here there is no secure storage, so dev mode keeps keys in
 * memory for the life of the page (mock) or the bridge process (live) and
 * labels them as such. Never import a key you care about into a shared
 * browser profile.
 */
import type { DevPlugin } from './nativeBridge';
import { DevPluginError } from './nativeBridge';
import { bytesToBase64, randomId } from './bytes';
import type { FilePicker, PickedBrowserFile } from './documentPlugin';

export interface DevKeyMetadata {
  handleId: string;
  label: string;
  algorithm: 'ssh-ed25519' | 'ssh-rsa';
  fingerprintSha256: string;
  passphraseRequired: boolean;
  createdAt: number;
}

/** Where keys live: the page (mock) or the local bridge process (live). */
export interface DevKeyVaultBackend {
  list(): Promise<DevKeyMetadata[]>;
  importKey(request: { privateKeyPem: string; label: string; passphrase?: string }): Promise<DevKeyMetadata>;
  generate(request: { label: string; algorithm: 'Ed25519' | 'RSA-3072' }): Promise<DevKeyMetadata>;
  remove(handleId: string, fingerprintSha256: string): Promise<boolean>;
}

function readUint32(bytes: Uint8Array, offset: number): number {
  return ((bytes[offset] << 24) >>> 0) + (bytes[offset + 1] << 16) + (bytes[offset + 2] << 8) + bytes[offset + 3];
}

/**
 * The public-key blob of an OpenSSH-format private key. It sits in the
 * clear header even when the private part is encrypted, so this needs no
 * passphrase. Returns null for anything else.
 */
export function parseOpenSshPrivateKey(pem: string): { publicBlob: Uint8Array; encrypted: boolean } | null {
  const match = /-----BEGIN OPENSSH PRIVATE KEY-----([\s\S]*?)-----END OPENSSH PRIVATE KEY-----/u.exec(pem);
  if (!match) return null;
  let bytes: Uint8Array;
  try {
    const binary = atob(match[1].replace(/\s+/gu, ''));
    bytes = Uint8Array.from(binary, (char) => char.charCodeAt(0));
  } catch {
    return null;
  }
  const magic = 'openssh-key-v1\0';
  if (new TextDecoder().decode(bytes.subarray(0, magic.length)) !== magic) return null;
  let offset = magic.length;
  const skipString = () => {
    const length = readUint32(bytes, offset);
    offset += 4 + length;
  };
  const cipherLength = readUint32(bytes, offset);
  const cipherName = new TextDecoder().decode(bytes.subarray(offset + 4, offset + 4 + cipherLength));
  skipString(); // ciphername
  skipString(); // kdfname
  skipString(); // kdfoptions
  if (readUint32(bytes, offset) < 1) return null;
  offset += 4;
  const length = readUint32(bytes, offset);
  offset += 4;
  if (offset + length > bytes.length) return null;
  return { publicBlob: bytes.slice(offset, offset + length), encrypted: cipherName !== 'none' };
}

export function publicKeyAlgorithm(blob: Uint8Array): string {
  const length = readUint32(blob, 0);
  return new TextDecoder().decode(blob.subarray(4, 4 + length));
}

export async function sha256Fingerprint(bytes: Uint8Array): Promise<string> {
  const digest = new Uint8Array(await globalThis.crypto.subtle.digest('SHA-256', bytes));
  return `SHA256:${bytesToBase64(digest).replace(/=+$/u, '')}`;
}

/** Seeded mock key, so `dev:mock` has a key to pick on first launch. */
export const MOCK_SEED_KEY: DevKeyMetadata = {
  handleId: 'de7de7de-0000-4000-8000-000000000001',
  label: 'Dev mock key (in memory)',
  algorithm: 'ssh-ed25519',
  fingerprintSha256: 'SHA256:ZGV2LW1vY2sta2V5LWZvci1icm93c2VyLWRldi1tb2Q',
  passphraseRequired: false,
  createdAt: 1_759_363_200_000,
};

/** The mock vault: metadata only; the fake host accepts any stored handle. */
export function createMockKeyVaultBackend(seed: DevKeyMetadata[] = [MOCK_SEED_KEY]): DevKeyVaultBackend {
  const keys = new Map(seed.map((key) => [key.handleId, { ...key }]));
  const add = (key: DevKeyMetadata) => {
    if ([...keys.values()].some((existing) => existing.fingerprintSha256 === key.fingerprintSha256)) {
      throw new DevPluginError('This key is already in the vault.', 'KEY_EXISTS');
    }
    keys.set(key.handleId, key);
    return { ...key };
  };
  return {
    list: async () => [...keys.values()].map((key) => ({ ...key })),
    importKey: async ({ privateKeyPem, label }) => {
      const parsed = parseOpenSshPrivateKey(privateKeyPem);
      const blob = parsed?.publicBlob ?? null;
      const algorithm = blob ? publicKeyAlgorithm(blob) : /RSA/u.test(privateKeyPem) ? 'ssh-rsa' : '';
      if (algorithm !== 'ssh-ed25519' && algorithm !== 'ssh-rsa') {
        throw new DevPluginError('Only Ed25519 and RSA private keys are supported.', 'UNSUPPORTED_KEY');
      }
      return add({
        handleId: randomId(),
        label,
        algorithm,
        fingerprintSha256: await sha256Fingerprint(blob ?? new TextEncoder().encode(privateKeyPem)),
        passphraseRequired: parsed ? parsed.encrypted : /ENCRYPTED/u.test(privateKeyPem),
        createdAt: Date.now(),
      });
    },
    generate: async ({ label, algorithm }) => add({
      handleId: randomId(),
      label,
      algorithm: algorithm === 'RSA-3072' ? 'ssh-rsa' : 'ssh-ed25519',
      fingerprintSha256: await sha256Fingerprint(globalThis.crypto.getRandomValues(new Uint8Array(32))),
      passphraseRequired: false,
      createdAt: Date.now(),
    }),
    remove: async (handleId, fingerprintSha256) => {
      const key = keys.get(handleId);
      if (!key || key.fingerprintSha256 !== fingerprintSha256) return false;
      return keys.delete(handleId);
    },
  };
}

const MAX_KEY_FILE_BYTES = 64 * 1024;

export function createKeyVaultPlugin(backend: DevKeyVaultBackend, pick: FilePicker): DevPlugin {
  const documents = new Map<string, PickedBrowserFile>();
  return {
    methods: {
      listKeys: async () => ({ keys: await backend.list() }),
      pickKeyDocument: async () => {
        const files = await pick('*/*', false);
        if (!files || files.length === 0) return { cancelled: true };
        const documentId = randomId();
        documents.set(documentId, files[0]);
        return { cancelled: false, documentId, name: files[0].name };
      },
      importPickedKey: async (request) => {
        const documentId = String(request.documentId ?? '');
        const file = documents.get(documentId);
        if (!file) throw new DevPluginError('The picked key file is no longer available.', 'NOT_FOUND');
        documents.delete(documentId);
        if (file.size > MAX_KEY_FILE_BYTES) throw new DevPluginError('That file is too large to be an SSH key.', 'INVALID_ARGUMENT');
        const privateKeyPem = new TextDecoder().decode(await file.arrayBuffer());
        return backend.importKey({
          privateKeyPem,
          label: String(request.label ?? file.name),
          ...(typeof request.passphrase === 'string' && request.passphrase ? { passphrase: request.passphrase } : {}),
        });
      },
      importDocumentUri: () => {
        throw new DevPluginError('Document URIs exist only on Android; pick the key file instead.', 'UNIMPLEMENTED');
      },
      generateKey: (request) => backend.generate({
        label: String(request.label ?? 'Dev key'),
        algorithm: request.algorithm === 'RSA-3072' ? 'RSA-3072' : 'Ed25519',
      }),
      deleteKey: async (request) => ({
        deleted: await backend.remove(String(request.handleId ?? ''), String(request.fingerprintSha256 ?? '')),
      }),
      importLegacyKeys: (request) => ({
        keys: [],
        failures: (Array.isArray(request.keys) ? request.keys : []).map((key) => ({
          legacyKeyId: (key as { legacyKeyId?: number }).legacyKeyId ?? 0,
          message: 'Browser dev mode has no 0.5.x keys to import.',
        })),
      }),
    },
  };
}
