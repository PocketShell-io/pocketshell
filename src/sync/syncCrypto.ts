/**
 * Settings-sync envelope encryption, Android WebView edition (issue #3020).
 *
 * The same envelope the desktop (node:crypto) and web (WebCrypto) clients
 * write, so one passphrase opens the account from every client:
 *
 *     { "v": 1, "kdf": "pbkdf2-sha256", "iter": 600000,
 *       "salt": "<b64 16B>", "iv": "<b64 12B>", "ct": "<b64 ct+tag>" }
 *
 * PBKDF2-SHA256 over the passphrase's UTF-8 bytes, AES-256-GCM with the
 * 16-byte tag appended. The salt travels in the header. A wrong passphrase
 * and a corrupted blob fail the same GCM tag check and get the same message.
 *
 * This mirrors pocketshell-web's `src/shared/syncCrypto.ts` (which stays
 * client-local because core has no crypto seam yet); the committed desktop
 * envelope in tests/unit/syncCrypto.test.ts pins byte compatibility.
 */

export const SYNC_KDF_ITERATIONS = 600_000;
const FORMAT_VERSION = 1;
const KDF_NAME = 'pbkdf2-sha256';
const SALT_BYTES = 16;
const IV_BYTES = 12;
const TAG_BYTES = 16;
const KEY_BITS = 256;
const MAX_ITERATIONS = 10_000_000;

export class SyncCryptoError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'SyncCryptoError';
  }
}

function subtle(): SubtleCrypto {
  const value = globalThis.crypto?.subtle;
  if (!value) throw new SyncCryptoError('This device cannot encrypt sync data (WebCrypto is unavailable).');
  return value;
}

function b64encode(bytes: Uint8Array): string {
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

function b64decode(value: string, what: string): Uint8Array {
  let binary: string;
  try {
    binary = atob(value);
  } catch {
    throw new SyncCryptoError(`The account's sync data is damaged (${what} could not be read).`);
  }
  const out = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) out[index] = binary.charCodeAt(index);
  if (b64encode(out).replace(/=+$/, '') !== value.replace(/=+$/, '')) {
    throw new SyncCryptoError(`The account's sync data is damaged (${what} could not be read).`);
  }
  return out;
}

function passphraseBytes(passphrase: string): Uint8Array {
  if (passphrase.length === 0) throw new SyncCryptoError('Enter your sync passphrase.');
  return new TextEncoder().encode(passphrase);
}

async function deriveKey(passphrase: string, salt: Uint8Array, iterations: number): Promise<CryptoKey> {
  const material = await subtle().importKey('raw', passphraseBytes(passphrase) as BufferSource, 'PBKDF2', false, ['deriveKey']);
  return subtle().deriveKey(
    { name: 'PBKDF2', hash: 'SHA-256', salt: salt as BufferSource, iterations },
    material,
    { name: 'AES-GCM', length: KEY_BITS },
    false,
    ['encrypt', 'decrypt'],
  );
}

/**
 * Encrypt the plaintext payload under the passphrase. Fresh salt and IV per
 * call. `iterations` exists for fast unit tests; the app always uses the
 * default, which the full-strength canary test pins.
 */
export async function encryptToEnvelope(
  plaintext: string,
  passphrase: string,
  iterations: number = SYNC_KDF_ITERATIONS,
): Promise<string> {
  passphraseBytes(passphrase);
  const salt = globalThis.crypto.getRandomValues(new Uint8Array(SALT_BYTES));
  const iv = globalThis.crypto.getRandomValues(new Uint8Array(IV_BYTES));
  const key = await deriveKey(passphrase, salt, iterations);
  const ct = await subtle().encrypt({ name: 'AES-GCM', iv: iv as BufferSource }, key, new TextEncoder().encode(plaintext));
  return JSON.stringify({
    v: FORMAT_VERSION,
    kdf: KDF_NAME,
    iter: iterations,
    salt: b64encode(salt),
    iv: b64encode(iv),
    ct: b64encode(new Uint8Array(ct)),
  });
}

/** Reverse {@link encryptToEnvelope}; every failure is a {@link SyncCryptoError}. */
export async function decryptEnvelope(envelope: string, passphrase: string): Promise<string> {
  passphraseBytes(passphrase);
  let fields: unknown;
  try {
    fields = JSON.parse(envelope);
  } catch {
    throw new SyncCryptoError('The account holds data that is not a sync envelope.');
  }
  if (typeof fields !== 'object' || fields === null || Array.isArray(fields)) {
    throw new SyncCryptoError('The account holds data that is not a sync envelope.');
  }
  const f = fields as Record<string, unknown>;
  if (f.v !== FORMAT_VERSION || f.kdf !== KDF_NAME) {
    throw new SyncCryptoError(`The account's sync envelope uses a format this app cannot read (v=${String(f.v)}).`);
  }
  if (typeof f.iter !== 'number' || typeof f.salt !== 'string' || typeof f.iv !== 'string' || typeof f.ct !== 'string') {
    throw new SyncCryptoError('The sync envelope is missing required fields.');
  }
  // The iteration count is blob-controlled and feeds PBKDF2's loop: bound it.
  if (!Number.isInteger(f.iter) || f.iter < 1 || f.iter > MAX_ITERATIONS) {
    throw new SyncCryptoError('The sync envelope iteration count is out of range.');
  }
  const salt = b64decode(f.salt, 'salt');
  const iv = b64decode(f.iv, 'iv');
  const ct = b64decode(f.ct, 'ciphertext');
  if (salt.length !== SALT_BYTES) throw new SyncCryptoError('The sync envelope salt has the wrong length.');
  if (iv.length !== IV_BYTES) throw new SyncCryptoError('The sync envelope IV has the wrong length.');
  if (ct.length <= TAG_BYTES) throw new SyncCryptoError('The sync envelope ciphertext is truncated.');
  const key = await deriveKey(passphrase, salt, f.iter);
  try {
    const plaintext = await subtle().decrypt({ name: 'AES-GCM', iv: iv as BufferSource }, key, ct as BufferSource);
    return new TextDecoder().decode(plaintext);
  } catch {
    throw new SyncCryptoError('Wrong sync passphrase, or the account data is corrupted.');
  }
}
