/**
 * The Android settings-sync adapter (issue #3020): Google sign-in and the
 * sync transport from the native GoogleSync plugin, envelope encryption in the
 * WebView, and core's shared `runSyncRound` for every merge decision.
 *
 * Split of responsibilities:
 *  - native (GoogleSyncPlugin.java): Credential Manager sign-in, the Google ID
 *    token in encrypted storage, the authenticated HTTPS call. The token never
 *    crosses the bridge.
 *  - this module: the sync API's wire contract (status codes, the 8 KB limit),
 *    passphrase encryption, the cached account copy, and the phone's
 *    contribution to the round (only the host fields the phone owns, so
 *    desktop-only fields survive — #2852 / core docs/SYNC.md).
 *  - core `runSyncRound`: pull → auto-select → merge → push → conflict retry.
 *
 * The same adapter backs the legacy Account screen and the shared app's
 * `api.sync` group, so both shells sync through one implementation.
 */
import {
  runSyncRound,
  SYNC_SLOT,
  type HostEntry,
  type SyncApplyResult,
  type SyncHostEntry,
  type SyncPullResult,
  type SyncPushResult,
  type SyncRoundResult,
  type SyncStatus,
} from '@pocketshell/core';
import type { GoogleSyncNative, GoogleSyncStatus } from '@/native/googleSync';
import { carriedGatewayMarker, hasGatewayMarker } from '@/platform/android/hostStore';
import { decryptEnvelope, encryptToEnvelope, SYNC_KDF_ITERATIONS } from './syncCrypto';

/** The decrypted account copy, kept so synced hosts stay visible after a restart. */
export const ACCOUNT_HOSTS_STORAGE_KEY = 'pocketshell.sync.account-hosts.v1';

/** The sync Lambda rejects `data` over 8 KB. */
export const SYNC_DATA_LIMIT_BYTES = 8 * 1024;

export interface SyncStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

export interface AndroidSyncDeps {
  native: GoogleSyncNative;
  storage: SyncStorage;
  /** PBKDF2 rounds for new writes; only unit tests lower it. */
  kdfIterations?: number;
}

export interface SyncNowRequest {
  /** The phone's hosts (saved and imported). */
  localHosts: readonly HostEntry[];
  /** Selected aliases, in order. */
  selected: readonly string[];
  passphrase: string;
  /** Persist the selection after each pull's auto-select. */
  onSelection?: (aliases: string[]) => void;
}

/** The phone's contribution: only the fields its host model owns. */
export function phoneOwnedSyncFields(host: Pick<HostEntry, 'name' | 'hostname' | 'port' | 'user'>): SyncHostEntry {
  return { name: host.name, hostname: host.hostname, port: host.port, user: host.user };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function httpFailure(status: number, body: string): string {
  if (status === 401) return 'The sync service did not accept this Google sign-in. Sign out and sign in again.';
  if (status === 403) return 'This Google account is not allowed to use the sync service.';
  try {
    const parsed: unknown = JSON.parse(body);
    if (isRecord(parsed) && typeof parsed.message === 'string' && parsed.message.trim() !== '') {
      return `The sync service refused the request: ${parsed.message.trim()}`;
    }
  } catch {
    // Not JSON; fall through to the status.
  }
  return `The sync service returned HTTP ${status}.`;
}

/** Native rejections already carry user-facing words; keep only those. */
export function describeSyncError(error: unknown): string {
  if (error instanceof Error && error.message) return error.message;
  if (isRecord(error) && typeof error.message === 'string' && error.message) return error.message;
  return 'Account sync failed.';
}

export function syncFailureText(result: Exclude<SyncRoundResult, { kind: 'synced' }>, selectedCount: number): string {
  switch (result.kind) {
    case 'empty-selection':
      return selectedCount === 0
        ? 'Select at least one host to sync.'
        : 'None of the selected hosts exists on this phone or in the account.';
    case 'error':
      return result.message;
    case 'conflict-limit':
      return 'The account kept changing while syncing. Try again in a moment.';
    case 'invalid-payload':
      return 'The account holds sync data this version cannot read, so nothing was uploaded.';
  }
}

export class AndroidSync {
  private readonly native: GoogleSyncNative;
  private readonly storage: SyncStorage;
  private readonly kdfIterations: number;

  constructor(deps: AndroidSyncDeps) {
    this.native = deps.native;
    this.storage = deps.storage;
    this.kdfIterations = deps.kdfIterations ?? SYNC_KDF_ITERATIONS;
  }

  status(): Promise<GoogleSyncStatus> {
    return this.native.status();
  }

  async signIn(): Promise<GoogleSyncStatus> {
    const status = await this.native.signIn();
    return status;
  }

  /** Native sign-out deletes the token; the cached account copy goes too. */
  async signOut(): Promise<void> {
    this.storage.removeItem(ACCOUNT_HOSTS_STORAGE_KEY);
    await this.native.signOut();
  }

  /** Fetch and decrypt the slot. A missing slot is a fresh account, not an error. */
  async pull(slot: string, passphrase: string): Promise<SyncPullResult> {
    const response = await this.native.request({ method: 'GET', slot });
    if (response.status === 404) return { kind: 'absent' };
    if (response.status < 200 || response.status > 299) throw new Error(httpFailure(response.status, response.body));
    let parsed: unknown;
    try {
      parsed = JSON.parse(response.body);
    } catch {
      throw new Error('The sync service returned an unreadable response.');
    }
    if (!isRecord(parsed) || typeof parsed.data !== 'string' || typeof parsed.version !== 'number'
      || !Number.isSafeInteger(parsed.version) || parsed.version < 0) {
      throw new Error('The sync service returned an unreadable response.');
    }
    return { kind: 'ok', version: parsed.version, plaintext: await decryptEnvelope(parsed.data, passphrase) };
  }

  /** Encrypt and upload on a base version (0 creates). 409 is a conflict result, not a throw. */
  async push(slot: string, plaintext: string, passphrase: string, baseVersion: number): Promise<SyncPushResult> {
    const envelope = await encryptToEnvelope(plaintext, passphrase, this.kdfIterations);
    if (new TextEncoder().encode(envelope).length > SYNC_DATA_LIMIT_BYTES) {
      return { kind: 'error', message: 'The encrypted host list is larger than the 8 KB sync limit.' };
    }
    const response = await this.native.request({
      method: 'PUT',
      slot,
      body: JSON.stringify({ data: envelope, version: baseVersion }),
    });
    let parsed: unknown = null;
    try {
      parsed = JSON.parse(response.body);
    } catch {
      parsed = null;
    }
    if (response.status === 409) {
      const current = isRecord(parsed) ? parsed.currentVersion : undefined;
      if (typeof current !== 'number' || !Number.isSafeInteger(current) || current < 0) {
        return { kind: 'error', message: 'The sync service reported a conflict without a version.' };
      }
      return { kind: 'conflict', currentVersion: current };
    }
    if (response.status < 200 || response.status > 299) {
      return { kind: 'error', message: httpFailure(response.status, response.body) };
    }
    const version = isRecord(parsed) ? parsed.version : undefined;
    if (typeof version !== 'number' || !Number.isSafeInteger(version) || version < 0) {
      return { kind: 'error', message: 'The sync service returned an unreadable response.' };
    }
    return { kind: 'ok', version };
  }

  /** The last decrypted account copy, or null when none was read on this phone. */
  accountHosts(): SyncHostEntry[] | null {
    const raw = this.storage.getItem(ACCOUNT_HOSTS_STORAGE_KEY);
    if (!raw) return null;
    try {
      const parsed: unknown = JSON.parse(raw);
      if (!Array.isArray(parsed)) return null;
      return parsed.filter((entry): entry is SyncHostEntry => isRecord(entry)
        && typeof entry.name === 'string' && entry.name.length > 0
        && typeof entry.hostname === 'string' && entry.hostname.length > 0);
    } catch {
      return null;
    }
  }

  private rememberAccount(hosts: readonly SyncHostEntry[]): void {
    this.storage.setItem(ACCOUNT_HOSTS_STORAGE_KEY, JSON.stringify(hosts));
  }

  /**
   * One shared sync round. The account copy is cached after every clean pull
   * and replaced by the uploaded set on success, so hosts from another client
   * appear on the phone even when the upload then fails.
   */
  async syncNow(request: SyncNowRequest): Promise<SyncRoundResult> {
    let pulls = 0;
    return runSyncRound(request.localHosts.map(phoneOwnedSyncFields), request.selected, {
      pull: async () => {
        pulls += 1;
        const pulled = await this.pull(SYNC_SLOT, request.passphrase);
        // A re-pull after a conflict that finds no account means it was
        // cleared mid-sync: stop rather than re-create it.
        if (pulls > 1 && pulled.kind !== 'ok') throw new Error('The account changed while syncing. Try again.');
        return pulled;
      },
      push: ({ baseVersion, plaintext }) => this.push(SYNC_SLOT, plaintext, request.passphrase, baseVersion ?? 0),
      onPulled: ({ hosts, selectedAliases }) => {
        this.rememberAccount(hosts);
        request.onSelection?.(selectedAliases);
      },
    }).then((result) => {
      if (result.kind === 'synced') this.rememberAccount(result.hosts);
      return result;
    });
  }

  /** The shared app's `api.sync` group over this adapter. */
  api(): {
    status(): Promise<SyncStatus>;
    login(): Promise<string | null>;
    logout(): Promise<void>;
    pull(slot: string, passphrase: string): Promise<SyncPullResult>;
    push(slot: string, plaintext: string, passphrase: string, baseVersion: number): Promise<SyncPushResult>;
    accountHosts(): Promise<HostEntry[] | null>;
    applyHosts(hosts: HostEntry[]): Promise<SyncApplyResult>;
  } {
    return {
      status: async () => {
        const status = await this.status();
        return { loggedIn: status.signedIn, email: status.email, keychainAvailable: true };
      },
      login: async () => (await this.signIn()).email,
      logout: () => this.signOut(),
      pull: async (slot, passphrase) => {
        const pulled = await this.pull(slot, passphrase);
        if (pulled.kind === 'ok') {
          // Mirror desktop's session cache so the picker can list the account.
          try {
            const parsed: unknown = JSON.parse(pulled.plaintext);
            if (isRecord(parsed) && Array.isArray(parsed.hosts)) this.rememberAccount(parsed.hosts as SyncHostEntry[]);
          } catch {
            // The shared store's strict parser reports unreadable data.
          }
        }
        return pulled;
      },
      push: (slot, plaintext, passphrase, baseVersion) => this.push(slot, plaintext, passphrase, baseVersion),
      accountHosts: async () => this.accountHosts()?.map(accountHostEntry) ?? null,
      // Account hosts stay in the account copy: a phone host needs a key from
      // the vault before it can be saved, which is a separate user step.
      applyHosts: async () => ({ added: [] }),
    };
  }
}

/** A synced entry as a picker row (no key on this phone yet). */
export function accountHostEntry(host: SyncHostEntry): HostEntry {
  return {
    name: host.name,
    hostname: host.hostname,
    port: typeof host.port === 'number' && Number.isInteger(host.port) && host.port > 0 && host.port < 65536 ? host.port : 22,
    user: typeof host.user === 'string' ? host.user : '',
    identityFile: null,
    proxyJump: typeof host.proxyJump === 'string' ? host.proxyJump : null,
    forwardAgent: false,
    localForwards: [],
    remoteForwards: [],
    fromConfig: false,
    // A gateway marker rides along VERBATIM (#3059): stripping it here would
    // present a gateway host as an ordinary one, and the platform boundary
    // refuses on presence — valid, null, or malformed alike.
    ...(hasGatewayMarker(host) ? carriedGatewayMarker(host) : {}),
  };
}
