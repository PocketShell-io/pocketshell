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
  assembleSyncSet,
  parseSyncPayloadResult,
  runSyncRound,
  serializeSyncPayload,
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
import { decryptEnvelope, encryptToEnvelope, SYNC_KDF_ITERATIONS } from './syncCrypto';

/**
 * Where builds before #3063 persisted the DECRYPTED account copy in WebView
 * storage. The copy now lives in memory only (like the desktop's session
 * cache, #3026): an adapter deletes this key when it is created, so a phone
 * upgraded from such a build does not keep plaintext host metadata on disk.
 */
export const ACCOUNT_HOSTS_STORAGE_KEY = 'pocketshell.sync.account-hosts.v1';

/**
 * Account aliases this phone has already seen in its sync selection or ticked
 * on its own (aliases only, like the persisted selection itself). An account
 * alias missing from it is new to this phone and is ticked, so a Sync now
 * keeps it; an alias in it but no longer selected is one the user unticked.
 */
export const KNOWN_ALIASES_STORAGE_KEY = 'pocketshell.sync.known-aliases.v1';

/** The sync Lambda rejects `data` over 8 KB. */
export const SYNC_DATA_LIMIT_BYTES = 8 * 1024;

export interface SyncStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

/** The shared host selection (the settings store's `syncSelectedHosts`). */
export interface SyncSelection {
  get(): readonly string[];
  set(aliases: string[]): void;
}

export interface AndroidSyncDeps {
  native: GoogleSyncNative;
  /** WebView storage: the known-alias list, and deleting the retired persisted account copy. */
  storage: SyncStorage;
  /**
   * The sync selection. With it, every account alias this phone has not
   * decided about is ticked on each pull, so a Sync now never drops an account
   * host the user did not untick (#3063).
   */
  selection: SyncSelection;
  /**
   * The phone's hosts. A push always carries only the fields the phone owns
   * for them; every other field comes from the account copy (#3020, #3063).
   */
  localHosts: () => Promise<readonly HostEntry[]>;
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

/**
 * The Android selection rule (#3063): every account alias stays selected
 * unless the user unticked it. `known` holds the aliases this phone has
 * decided about (seen selected, or ticked here); an account alias outside it
 * is ticked and becomes known. A known alias that is not selected is an
 * explicit untick and stays off.
 */
export function keepAccountAliases(
  account: readonly string[],
  selected: readonly string[],
  known: readonly string[],
): { selected: string[]; known: string[] } {
  const nextKnown = [...new Set([...known, ...selected])];
  const knownSet = new Set(nextKnown);
  const nextSelected = [...selected];
  for (const alias of account) {
    if (knownSet.has(alias)) continue;
    knownSet.add(alias);
    nextKnown.push(alias);
    if (!nextSelected.includes(alias)) nextSelected.push(alias);
  }
  return { selected: nextSelected, known: nextKnown };
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
  private readonly selection: SyncSelection;
  private readonly localHosts: () => Promise<readonly HostEntry[]>;
  private readonly kdfIterations: number;
  /**
   * The last decrypted account copy, in memory only: it is unlocked with the
   * sync passphrase, which is never stored either, so after a restart the
   * account's hosts reappear once the user unlocks again (desktop behaves the
   * same way).
   */
  private account: SyncHostEntry[] | null = null;

  constructor(deps: AndroidSyncDeps) {
    this.native = deps.native;
    this.storage = deps.storage;
    this.selection = deps.selection;
    this.localHosts = deps.localHosts;
    this.kdfIterations = deps.kdfIterations ?? SYNC_KDF_ITERATIONS;
    deps.storage.removeItem(ACCOUNT_HOSTS_STORAGE_KEY);
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
    this.account = null;
    // The next account starts undecided: its aliases are ticked on unlock.
    // Deliberately, signing out and back in therefore re-ticks a host the user
    // had unticked: forgetting an untick can only keep a host in the account,
    // never drop one (docs/settings-sync.md).
    this.storage.removeItem(KNOWN_ALIASES_STORAGE_KEY);
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
    const hosts = await this.phoneOwnedPayload(plaintext);
    const payload = hosts === null ? plaintext : serializeSyncPayload(hosts);
    const envelope = await encryptToEnvelope(payload, passphrase, this.kdfIterations);
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
    if (hosts !== null) this.account = hosts.map((host) => ({ ...host }));
    return { kind: 'ok', version };
  }

  /**
   * The payload a phone uploads, whoever assembled it (#3063): the selected
   * aliases plus every selected account alias, each with only the fields the
   * phone owns ({@link phoneOwnedSyncFields}) over the account's entry, so a
   * phone sync never clears another client's identityFile, proxyJump or
   * forwards — the #3020 rule, now also for the shared Account screen, whose
   * round assembles from full picker entries. Null leaves unreadable
   * plaintext untouched (the round already refuses to produce it).
   */
  private async phoneOwnedPayload(plaintext: string): Promise<SyncHostEntry[] | null> {
    const parsed = parseSyncPayloadResult(plaintext);
    if (parsed.kind !== 'ok') return null;
    const account = this.account ?? [];
    const accountNames = new Set(account.map((host) => host.name));
    const pushedNames = parsed.hosts.map((host) => host.name);
    const kept = this.selection.get().filter((alias) => accountNames.has(alias) && !pushedNames.includes(alias));
    const phone = await this.localHosts().catch(() => []);
    const local = phone.map(phoneOwnedSyncFields);
    const localNames = new Set(local.map((host) => host.name));
    for (const host of parsed.hosts) {
      if (!localNames.has(host.name)) local.push(phoneOwnedSyncFields(host as Pick<HostEntry, 'name' | 'hostname' | 'port' | 'user'>));
    }
    return assembleSyncSet(local, account, [...pushedNames, ...kept]).map(withoutUndefined);
  }

  /** Every pull: keep the decrypted copy in memory and apply the selection rule. */
  private afterPull(pulled: SyncPullResult): void {
    if (pulled.kind !== 'ok') {
      // A fresh account holds no hosts: unlocked, and empty.
      this.account = [];
      return;
    }
    const parsed = parseSyncPayloadResult(pulled.plaintext);
    if (parsed.kind !== 'ok') return;
    this.rememberAccount(parsed.hosts);
    let known: string[] = [];
    try {
      const raw: unknown = JSON.parse(this.storage.getItem(KNOWN_ALIASES_STORAGE_KEY) ?? '[]');
      if (Array.isArray(raw)) known = raw.filter((alias): alias is string => typeof alias === 'string');
    } catch {
      known = [];
    }
    const current = this.selection.get();
    const next = keepAccountAliases(parsed.hosts.map((host) => host.name), current, known);
    this.storage.setItem(KNOWN_ALIASES_STORAGE_KEY, JSON.stringify(next.known));
    if (next.selected.length !== current.length) this.selection.set(next.selected);
  }

  /**
   * Unlock the account copy with the passphrase without uploading anything:
   * the account's hosts are listed again (both shells) after a restart.
   */
  async unlock(passphrase: string): Promise<SyncHostEntry[]> {
    const pulled = await this.pull(SYNC_SLOT, passphrase);
    this.afterPull(pulled);
    return this.accountHosts() ?? [];
  }

  /** The last decrypted account copy, or null when none was read since the app started. */
  accountHosts(): SyncHostEntry[] | null {
    return this.account === null ? null : this.account.map((host) => ({ ...host }));
  }

  private rememberAccount(hosts: readonly unknown[]): void {
    this.account = hosts.filter((entry): entry is SyncHostEntry => isRecord(entry)
      && typeof entry.name === 'string' && entry.name.length > 0
      && typeof entry.hostname === 'string' && entry.hostname.length > 0);
  }

  /**
   * One shared sync round. The account copy is kept after every clean pull
   * and replaced by the uploaded set on success, so hosts from another client
   * appear on the phone even when the upload then fails.
   */
  async syncNow(request: SyncNowRequest): Promise<SyncRoundResult> {
    let pulls = 0;
    return runSyncRound(request.localHosts.map(phoneOwnedSyncFields), request.selected, {
      pull: async () => {
        pulls += 1;
        const pulled = await this.pull(SYNC_SLOT, request.passphrase);
        this.afterPull(pulled);
        // A re-pull after a conflict that finds no account means it was
        // cleared mid-sync: stop rather than re-create it.
        if (pulls > 1 && pulled.kind !== 'ok') throw new Error('The account changed while syncing. Try again.');
        return pulled;
      },
      push: ({ baseVersion, plaintext }) => this.push(SYNC_SLOT, plaintext, request.passphrase, baseVersion ?? 0),
      onPulled: ({ hosts, selectedAliases }) => {
        this.rememberAccount(hosts);
        // The round's own auto-select plus every alias the selection rule kept.
        const kept = this.selection.get();
        request.onSelection?.([...selectedAliases, ...kept.filter((alias) => !selectedAliases.includes(alias))]);
      },
    }).then((result) => (result.kind === 'synced' ? { ...result, hosts: this.accountHosts() ?? result.hosts } : result));
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
        // Mirror desktop's session cache so the picker can list the account,
        // and keep every undecided account alias selected.
        this.afterPull(pulled);
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

function withoutUndefined(host: SyncHostEntry): SyncHostEntry {
  return Object.fromEntries(Object.entries(host).filter(([, value]) => value !== undefined)) as SyncHostEntry;
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
  };
}
