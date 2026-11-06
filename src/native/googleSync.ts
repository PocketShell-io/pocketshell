import { registerPlugin, type Plugin } from '@capacitor/core';

/**
 * The native Google sign-in and sync transport (GoogleSyncPlugin.java,
 * issue #3020). The bridge never carries the Google ID token: JS sees whether
 * the phone is signed in, which email, and the sync API's HTTP status and
 * body for the fixed `/settings/{slot}`, `/me` and `/vault/status|verify` routes.
 */
export interface GoogleSyncStatus {
  signedIn: boolean;
  email: string | null;
  /** The installed package; the Android OAuth client must be registered for it. */
  packageName: string;
}

export interface GoogleSyncHttpResponse {
  status: number;
  body: string;
}

export type GoogleSyncRequest =
  | { method: 'GET'; slot?: string }
  | { method: 'PUT'; slot: string; body: string }
  /** The key vault's server check (key-vault W2 phase 2); 404/503 = not deployed. */
  | { method: 'GET'; endpoint: 'settings' | 'vault/status' }
  | { method: 'POST'; endpoint: 'vault/verify'; body: string };

/** The narrow capability the sync adapter needs; tests supply a fake. */
export interface GoogleSyncNative {
  status(): Promise<GoogleSyncStatus>;
  signIn(): Promise<GoogleSyncStatus>;
  signOut(): Promise<GoogleSyncStatus>;
  request(request: GoogleSyncRequest): Promise<GoogleSyncHttpResponse>;
  /** Verified public account identity; no token crosses the boundary. */
  accountId?(): Promise<unknown>;
  /** Confirmed native auth loss; consumer cleanup is awaited before request rejection. */
  onAuthLost?(listener: () => void | Promise<void>): () => void;
}

/** Stable native rejection codes (SyncAuthException.java). */
export const GOOGLE_SYNC_ERROR_CODES = [
  'SIGN_IN_CANCELLED',
  'SIGN_IN_UNAVAILABLE',
  'SIGN_IN_FAILED',
  'NOT_SIGNED_IN',
  'SIGN_IN_STORAGE_FAILED',
  'SYNC_NETWORK_FAILED',
  'SYNC_INVALID_REQUEST',
] as const;

type NativeGoogleSyncPlugin = Plugin & {
  accountId(): Promise<unknown>;
  status(): Promise<unknown>;
  signIn(): Promise<unknown>;
  signOut(): Promise<unknown>;
  request(options: { method: string; slot?: string; endpoint?: string; body?: string }): Promise<unknown>;
};

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

export function parseGoogleSyncStatus(value: unknown): GoogleSyncStatus {
  if (!isRecord(value) || typeof value.signedIn !== 'boolean' || typeof value.packageName !== 'string') {
    throw new Error('Google sign-in returned an unexpected status.');
  }
  const email = typeof value.email === 'string' && value.email.length > 0 ? value.email : null;
  return { signedIn: value.signedIn, email: value.signedIn ? email : null, packageName: value.packageName };
}

export function parseGoogleSyncResponse(value: unknown): GoogleSyncHttpResponse {
  if (!isRecord(value) || typeof value.status !== 'number' || !Number.isInteger(value.status)
    || typeof value.body !== 'string') {
    throw new Error('The Android sync transport returned an invalid response.');
  }
  return { status: value.status, body: value.body };
}

/** Wrap the registered Capacitor plugin with response validation. */
export function isGoogleAuthLoss(error: unknown): boolean {
  return isRecord(error) && error.code === 'NOT_SIGNED_IN';
}

export function createGoogleSyncNative(plugin: NativeGoogleSyncPlugin): GoogleSyncNative {
  const listeners = new Set<() => void | Promise<void>>();
  let generation = 0;
  let loss: Promise<void> | null = null;
  let authLost = false;
  async function guarded<T>(operation: () => Promise<T>): Promise<T> {
    const started = generation;
    try {
      const result = await operation();
      if (started !== generation) throw new Error('Google sign-in changed while the request was running. Try again.');
      return result;
    } catch (error) {
      if (started === generation && !authLost && isGoogleAuthLoss(error)) {
        // Invalidate old in-flight successes before any consumer awaits cleanup.
        generation++;
        authLost = true;
        loss = Promise.allSettled([...listeners].map(async listener => listener())).then(() => undefined);
        await loss;
      } else if (isGoogleAuthLoss(error) && loss) {
        await loss;
      }
      throw error;
    }
  }
  return {
    status: () => guarded(async () => parseGoogleSyncStatus(await plugin.status())),
    signIn: async () => {
      // An explicit new login owns its generation; late old requests cannot sign it out.
      generation++;
      await loss;
      const result = parseGoogleSyncStatus(await plugin.signIn());
      authLost = false;
      loss = null;
      return result;
    },
    signOut: async () => { generation++; loss = null; return parseGoogleSyncStatus(await plugin.signOut()); },
    accountId: () => guarded(() => plugin.accountId()),
    request: (request) => guarded(async () => parseGoogleSyncResponse(await plugin.request({ ...request }))),
    onAuthLost: (listener) => { listeners.add(listener); return () => listeners.delete(listener); },
  };
}

const nativeGoogleSyncPlugin = registerPlugin<NativeGoogleSyncPlugin>('GoogleSync');
export const googleSync: GoogleSyncNative = createGoogleSyncNative(nativeGoogleSyncPlugin);

/** Native verifies the subject through the backend authorizer. No JWT or email-derived identity. */
export async function verifiedGoogleAccountId(): Promise<string | null> {
  if (!(await googleSync.status()).signedIn) return null;
  const reply = await googleSync.accountId!();
  if (!isRecord(reply) || typeof reply.accountId !== 'string' || !/^google:[0-9A-Za-z_-]{1,128}$/.test(reply.accountId)) {
    throw new Error('The signed-in account could not be verified.');
  }
  return reply.accountId;
}
