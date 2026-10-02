import { registerPlugin, type Plugin } from '@capacitor/core';

/**
 * The native Google sign-in and sync transport (GoogleSyncPlugin.java,
 * issue #3020). The bridge never carries the Google ID token: JS sees whether
 * the phone is signed in, which email, and the sync API's HTTP status and
 * body for the fixed `/settings/{slot}` and `/me` routes.
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
  | { method: 'PUT'; slot: string; body: string };

/** The narrow capability the sync adapter needs; tests supply a fake. */
export interface GoogleSyncNative {
  status(): Promise<GoogleSyncStatus>;
  signIn(): Promise<GoogleSyncStatus>;
  signOut(): Promise<GoogleSyncStatus>;
  request(request: GoogleSyncRequest): Promise<GoogleSyncHttpResponse>;
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
  status(): Promise<unknown>;
  signIn(): Promise<unknown>;
  signOut(): Promise<unknown>;
  request(options: { method: string; slot?: string; body?: string }): Promise<unknown>;
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
export function createGoogleSyncNative(plugin: NativeGoogleSyncPlugin): GoogleSyncNative {
  return {
    status: async () => parseGoogleSyncStatus(await plugin.status()),
    signIn: async () => parseGoogleSyncStatus(await plugin.signIn()),
    signOut: async () => parseGoogleSyncStatus(await plugin.signOut()),
    request: async (request) => parseGoogleSyncResponse(await plugin.request({ ...request })),
  };
}

export const googleSync: GoogleSyncNative = createGoogleSyncNative(
  registerPlugin<NativeGoogleSyncPlugin>('GoogleSync'),
);
