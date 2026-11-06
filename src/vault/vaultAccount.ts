/**
 * The account-side IO core's KeyVaultStore (key-vault W1) needs, for Android
 * (W4), over the native GoogleSync transport (the Google token never crosses
 * the bridge). Shapes are core's, 1:1: `VaultSlotTransport` and
 * `VaultServerVerifier` (the W2 phase-2 contract).
 *
 *  - slots: `vault` (the plaintext header JSON) and `vk-<keyId>` (one sealed
 *    blob each) are read raw: unlike the `main` host list they are not
 *    passphrase envelopes. General slot `push` and `delete` still refuse;
 *    `list` reads bounded slot metadata through native GET /settings;
 *  - first setup uses only account-bound POST /vault/initialize, the same
 *    atomic encrypted-header/verifier transaction as CLI enrollment. It never
 *    overwrites an existing vault or falls back to partial initialization;
 *  - existing unlock uses GET /vault/status and POST /vault/verify. Missing
 *    server-check routes remain unavailable for legacy unlock, while first
 *    setup requires a confirmed current server epoch.
 */
import type {
  VaultServerStatus,
  VaultServerVerifier,
  VaultSlotPullResult,
  VaultSlotTransport,
  VaultVerifyResult,
} from '@pocketshell/core';
import { isGoogleAuthLoss, type GoogleSyncNative } from '@/native/googleSync';

export const ANDROID_VAULT_READ_ONLY = 'This app supports first vault setup, but does not change or delete existing vault keys.';

/**
 * One raw slot read: a missing slot is `absent`; a read the server gates on a
 * recent verify (W2 `vault_verify_required`) is `gated`, which core answers
 * with a silent re-verify or an unlock; any other non-2xx throws.
 */
export async function readVaultSlot(native: GoogleSyncNative, slot: string, accountId?: string): Promise<VaultSlotPullResult> {
  const response = await native.request({ method: 'GET', slot, ...(accountId ? {accountId} : {}) });
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

export function createVaultSlotTransport(native: GoogleSyncNative, boundAccount: () => string | null = () => null): VaultSlotTransport {
  return {
    scoped() {const account = boundAccount();return createVaultSlotTransport(native, () => account);},
    pull: (slot) => readVaultSlot(native, slot, boundAccount() ?? undefined),
    push: async () => ({kind:'error',message:ANDROID_VAULT_READ_ONLY}),
    delete: async () => {
      throw new Error(ANDROID_VAULT_READ_ONLY);
    },
    async list() {
      const accountId = boundAccount();
      const response = await native.request({ method: 'GET', endpoint: 'settings', ...(accountId ? {accountId} : {}) });
      if (response.status !== 200) throw new Error(`The sync service returned HTTP ${response.status} for the vault directory.`);
      const body = parseJson(response.body);
      if (!isRecord(body) || !Array.isArray(body.slots) || body.slots.length > 1024) throw new Error('The sync service returned an invalid vault directory.');
      const seen = new Set<string>();
      return body.slots.map((row: unknown) => {
        if (!isRecord(row) || typeof row.slot !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(row.slot)
          || !isCount(row.version) || !isCount(row.size) || typeof row.updatedAt !== 'string'
          || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(row.updatedAt) || !Number.isFinite(Date.parse(row.updatedAt)) || seen.has(row.slot)) throw new Error('The sync service returned an invalid vault directory.');
        seen.add(row.slot);
        return { slot: row.slot, version: row.version };
      });
    },
  };
}

/** AUTH on the wire: base64url without padding of exactly 32 bytes (43 characters). */
export function authToWire(auth: Uint8Array): string {
  if (auth.length !== 32) throw new Error('The vault check value is malformed.');
  let binary = '';
  for (const byte of auth) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function createVaultServerVerifier(native: GoogleSyncNative, boundAccount: () => string | null = () => null): VaultServerVerifier {
  const scope = () => {const accountId=boundAccount();return accountId ? {accountId} : {};};
  return {
    scoped() {const account = boundAccount();return createVaultServerVerifier(native, () => account);},
    async initialize({data,auth,epoch}) {
      const accountId=boundAccount();
      if (!accountId || !isCount(epoch) || epoch < 1) return {kind:'error',message:'Verify your Google account before creating a vault.'};
      const response=await native.request({method:'POST',endpoint:'vault/initialize',accountId,
        body:JSON.stringify({data,auth:authToWire(auth),epoch})});
      const body=parseJson(response.body);
      if(response.status===201 && isRecord(body) && body.ok===true && body.version===1 && body.epoch===epoch) return {kind:'ok',version:1};
      if(response.status===409) return {kind:'conflict',currentVersion:0};
      return {kind:'error',message:`Vault creation was not confirmed (HTTP ${response.status}). Retry with the same passphrase.`};
    },
    async status(): Promise<VaultServerStatus> {
      const response = await native.request({ method: 'GET', endpoint: 'vault/status', ...scope() }).catch((error: unknown) => { if (isGoogleAuthLoss(error)) throw error; return null; });
      if (!response || response.status !== 200) return { kind: 'unavailable' };
      const body = parseJson(response.body);
      if (!isRecord(body) || !isCount(body.epoch) || typeof body.hasVerifier !== 'boolean') return { kind: 'unavailable' };
      const status: VaultServerStatus = { kind: 'ok', epoch: body.epoch, hasVerifier: body.hasVerifier, retryAfterMs: seconds(body.retryAfter) };
      // Gated mode serves the public KDF params here; core validates them.
      return isRecord(body.kdf) ? { ...status, kdf: body.kdf as never } : status;
    },

    async verify(auth): Promise<VaultVerifyResult> {
      const wire = authToWire(auth);
      const response = await native.request({ method: 'POST', endpoint: 'vault/verify', body: JSON.stringify({ auth: wire }), ...scope() })
        .catch((error: unknown) => { if (isGoogleAuthLoss(error)) throw error; return null; });
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

    setVerifier: async () => ({kind:'unavailable'}),
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
