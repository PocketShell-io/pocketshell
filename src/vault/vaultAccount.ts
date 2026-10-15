/**
 * The account-side IO core's KeyVaultStore (key-vault W1) needs, for Android
 * (W4), over the native GoogleSync transport (the Google token never crosses
 * the bridge). Shapes are core's, 1:1: `VaultSlotTransport` and
 * `VaultServerVerifier` (the W2 phase-2 contract).
 *
 *  - slots: `vault` (the plaintext header JSON) and `vk-<keyId>` (one sealed
 *    blob each) are read raw: unlike the `main` host list they are not
 *    passphrase envelopes. Android never writes the vault (keys are uploaded on
 *    the web only), so `push` and `delete` refuse and `list` is empty;
 *  - the server check: `GET /vault/status` and `POST /vault/verify` (native
 *    allowlists exactly those two). Not deployed yet, so a missing route reads
 *    as `unavailable` and core falls back to the client-side check value: an
 *    absent server never fails an unlock. Android never sets or resets the
 *    verifier (the web does).
 */
import type {
  VaultServerStatus,
  VaultServerVerifier,
  VaultSlotPullResult,
  VaultSlotTransport,
  VaultVerifyResult,
} from '@pocketshell/core';
import type { GoogleSyncNative } from '@/native/googleSync';

export const ANDROID_VAULT_READ_ONLY = 'PocketShell for Android does not change the key vault. Use the PocketShell web app.';

/**
 * One raw slot read: a missing slot is `absent`; a read the server gates on a
 * recent verify (W2 `vault_verify_required`) is `gated`, which core answers
 * with a silent re-verify or an unlock; any other non-2xx throws.
 */
export async function readVaultSlot(native: GoogleSyncNative, slot: string): Promise<VaultSlotPullResult> {
  const response = await native.request({ method: 'GET', slot });
  if (response.status === 404) return { kind: 'absent' };
  if (response.status === 403 && errorCode(response.body) === 'vault_verify_required') return { kind: 'gated' };
  if (response.status < 200 || response.status > 299) {
    throw new Error(`The sync service returned HTTP ${response.status} for the key vault.`);
  }
  const parsed = parseJson(response.body);
  if (!isRecord(parsed) || typeof parsed.data !== 'string' || !isCount(parsed.version)) {
    throw new Error('The sync service returned an unreadable key-vault slot.');
  }
  return { kind: 'ok', version: parsed.version, data: parsed.data };
}

export function createVaultSlotTransport(native: GoogleSyncNative): VaultSlotTransport {
  return {
    pull: (slot) => readVaultSlot(native, slot),
    push: async () => ({ kind: 'error', message: ANDROID_VAULT_READ_ONLY }),
    delete: async () => {
      throw new Error(ANDROID_VAULT_READ_ONLY);
    },
    list: async () => [],
  };
}

/** AUTH on the wire: base64url without padding of exactly 32 bytes (43 characters). */
export function authToWire(auth: Uint8Array): string {
  if (auth.length !== 32) throw new Error('The vault check value is malformed.');
  let binary = '';
  for (const byte of auth) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function createVaultServerVerifier(native: GoogleSyncNative): VaultServerVerifier {
  return {
    async status(): Promise<VaultServerStatus> {
      const response = await native.request({ method: 'GET', endpoint: 'vault/status' }).catch(() => null);
      if (!response || response.status !== 200) return { kind: 'unavailable' };
      const body = parseJson(response.body);
      if (!isRecord(body) || !isCount(body.epoch) || typeof body.hasVerifier !== 'boolean') return { kind: 'unavailable' };
      const status: VaultServerStatus = { kind: 'ok', epoch: body.epoch, hasVerifier: body.hasVerifier, retryAfterMs: seconds(body.retryAfter) };
      // Gated mode serves the public KDF params here; core validates them.
      return isRecord(body.kdf) ? { ...status, kdf: body.kdf as never } : status;
    },

    async verify(auth): Promise<VaultVerifyResult> {
      const wire = authToWire(auth);
      const response = await native.request({ method: 'POST', endpoint: 'vault/verify', body: JSON.stringify({ auth: wire }) })
        .catch(() => null);
      if (!response) return { kind: 'unavailable' };
      const body = parseJson(response.body);
      const code = errorCode(response.body);
      if (response.status === 200 && isRecord(body) && body.ok === true) return { kind: 'ok' };
      if (response.status === 401 && code === 'wrong_passphrase' && isRecord(body)) {
        return { kind: 'wrong', retryAfterMs: seconds(body.retryAfter) };
      }
      if (response.status === 429 && isRecord(body)) return { kind: 'locked', retryAfterMs: seconds(body.retryAfter) };
      if (response.status === 404 && code === 'no_verifier') return { kind: 'noVerifier' };
      return { kind: 'unavailable' };
    },

    // The web sets the verifier at vault creation and passphrase change, and
    // resets the vault; the phone only verifies.
    setVerifier: async () => ({ kind: 'unavailable' }),
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isCount(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
}

function seconds(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0 ? Math.ceil(value * 1000) : 0;
}

function parseJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

function errorCode(text: string): string | null {
  const body = parseJson(text);
  return isRecord(body) && typeof body.error === 'string' ? body.error : null;
}
