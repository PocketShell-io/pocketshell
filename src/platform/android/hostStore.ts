/**
 * The Android host source: the phone's answer to desktop's `~/.ssh/config`
 * and web's synced account list, behind the shared picker's
 * `ssh.listConfigHosts` and the adapter's credential resolution.
 *
 * Two kinds of host appear:
 *  - hosts imported from the signed 0.5.x app (`readImportedLegacyHosts`),
 *    dialled with the native key handle their key was imported under;
 *  - hosts added on this phone, saved with the handle of a key in the
 *    Android key vault (#2926). Key bytes never enter the WebView: a host
 *    only names its handle, and the native plugin resolves it at dial time.
 */
import type { HostEntry, SshHostTarget } from '@pocketshell/core';
import { makeLegacySshHostTarget } from '@/migration/legacySshTarget';
import type { ImportedLegacyHost } from '@/migration/installedDataMigration';

export const ANDROID_HOSTS_STORAGE_KEY = 'pocketshell.android.hosts.v1';

export interface SavedHost {
  name: string;
  hostname: string;
  port: number;
  user: string;
  /** The Android key vault handle this host authenticates with (#2926). */
  keyHandleId: string;
}

export interface StringStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}

export interface AndroidHostStoreOptions {
  storage: StringStorage;
  /** Hosts carried over from the 0.5.x app; empty off-device or before import. */
  readLegacyHosts: () => Promise<ImportedLegacyHost[]>;
}

/** Why a host cannot be dialled right now, in words the picker can show. */
export class MissingHostCredential extends Error {
  /** The saved host with no key, so a prompt can attach one to it in place. */
  readonly host: HostEntry;

  constructor(host: Omit<SavedHost, 'keyHandleId'>) {
    super(`No SSH key is chosen for “${host.name}” — add the host again with a key from the key vault.`);
    this.name = 'MissingHostCredential';
    this.host = toEntry(host);
  }
}

function toEntry(host: Omit<SavedHost, 'keyHandleId'>): HostEntry {
  return {
    name: host.name,
    hostname: host.hostname,
    port: host.port,
    user: host.user,
    identityFile: null,
    proxyJump: null,
    forwardAgent: false,
    localForwards: [],
    remoteForwards: [],
    fromConfig: false,
  };
}

function isSavedHost(value: unknown): value is SavedHost {
  if (typeof value !== 'object' || value === null) return false;
  const host = value as Record<string, unknown>;
  return typeof host.name === 'string' && host.name.length > 0
    && typeof host.hostname === 'string' && host.hostname.length > 0
    && typeof host.user === 'string'
    && typeof host.port === 'number' && Number.isInteger(host.port) && host.port > 0 && host.port < 65536
    && (host.keyHandleId === undefined || typeof host.keyHandleId === 'string');
}

export function validateSavedHost(host: SavedHost): string | null {
  if (!host.name.trim()) return 'Give the host a name.';
  if (!host.hostname.trim()) return 'Enter a hostname or IP address.';
  if (!host.user.trim()) return 'Enter the SSH user.';
  if (!Number.isInteger(host.port) || host.port < 1 || host.port > 65535) return 'Port must be 1–65535.';
  if (!host.keyHandleId) return 'Choose an SSH key from the key vault.';
  return null;
}

export class AndroidHostStore {
  private legacy: ImportedLegacyHost[] = [];

  constructor(private readonly options: AndroidHostStoreOptions) {}

  /** Saved hosts first, then 0.5.x imports whose names are not already taken. */
  async list(): Promise<HostEntry[]> {
    const saved = this.readSaved();
    this.legacy = await this.options.readLegacyHosts().catch(() => []);
    const names = new Set(saved.map((host) => host.name));
    const imported = this.legacy
      .filter((host) => !names.has(host.name))
      .map((host) => toEntry({ name: host.name, hostname: host.hostname, port: host.port, user: host.username }));
    return [...saved.map(toEntry), ...imported];
  }

  /** Add or replace a host, bound to a key-vault handle. */
  save(host: SavedHost): void {
    const problem = validateSavedHost(host);
    if (problem) throw new Error(problem);
    const record = {
      name: host.name.trim(),
      hostname: host.hostname.trim(),
      port: host.port,
      user: host.user.trim(),
      keyHandleId: host.keyHandleId,
    };
    // A host of the same name is replaced where it stands, never duplicated.
    const saved = this.readSaved();
    const at = saved.findIndex((existing) => existing.name === record.name);
    if (at >= 0) saved[at] = record;
    else saved.push(record);
    this.options.storage.setItem(ANDROID_HOSTS_STORAGE_KEY, JSON.stringify(saved));
  }

  /** Every saved host (metadata and key handle; never key bytes). */
  savedHosts(): SavedHost[] {
    return this.readSaved();
  }

  /** Saved hosts that authenticate with `handleId`. */
  hostsUsingKey(handleId: string): SavedHost[] {
    return this.readSaved().filter((host) => host.keyHandleId === handleId);
  }

  /**
   * Point the named hosts at `nextHandleId` ('' = no key) only if each still
   * uses `expectedHandleId`; all or nothing. The key-vault delete flow uses
   * it to detach and restore references (#2926 CredentialKeyManager).
   */
  replaceKeyHandle(names: readonly string[], expectedHandleId: string, nextHandleId: string): boolean {
    const saved = this.readSaved();
    const targets = saved.filter((host) => names.includes(host.name));
    if (targets.length !== names.length || targets.some((host) => host.keyHandleId !== expectedHandleId)) return false;
    for (const host of targets) host.keyHandleId = nextHandleId;
    this.options.storage.setItem(ANDROID_HOSTS_STORAGE_KEY, JSON.stringify(saved));
    return true;
  }

  remove(name: string): void {
    const saved = this.readSaved().filter((existing) => existing.name !== name);
    this.options.storage.setItem(ANDROID_HOSTS_STORAGE_KEY, JSON.stringify(saved));
  }

  /**
   * The dial target for a connect request, matched the way the shared store
   * names it: by alias first, then hostname + port.
   */
  async resolve(request: { host: string; port?: number; user: string; hostAlias?: string }): Promise<SshHostTarget> {
    const port = request.port ?? 22;
    const saved = this.readSaved();
    const savedMatch =
      (request.hostAlias ? saved.find((host) => host.name === request.hostAlias) : undefined) ??
      saved.find((host) => host.hostname === request.host && host.port === port);
    if (savedMatch) {
      if (!savedMatch.keyHandleId) throw new MissingHostCredential(savedMatch);
      return {
        hostId: `${savedMatch.user}@${savedMatch.hostname}:${savedMatch.port}`,
        hostname: savedMatch.hostname,
        port: savedMatch.port,
        username: request.user || savedMatch.user,
        credential: { kind: 'key-handle', handleId: savedMatch.keyHandleId },
      };
    }
    if (this.legacy.length === 0) this.legacy = await this.options.readLegacyHosts().catch(() => []);
    const legacyMatch =
      (request.hostAlias ? this.legacy.find((host) => host.name === request.hostAlias) : undefined) ??
      this.legacy.find((host) => host.hostname === request.host && host.port === port);
    if (legacyMatch) {
      // The native plugin resolves the imported key's handle; no key bytes in JS.
      return makeLegacySshHostTarget(legacyMatch, '');
    }
    throw new Error(`No saved host for ${request.user ? `${request.user}@` : ''}${request.host}:${port}.`);
  }

  /**
   * The name the user knows a connect request's host by (the saved or
   * imported host's name, matched like {@link resolve}), for prompts such as
   * the host-key decision. Falls back to the hostname.
   */
  labelFor(request: { host: string; port?: number; hostAlias?: string }): string {
    if (request.hostAlias) return request.hostAlias;
    const port = request.port ?? 22;
    const match = (host: { hostname: string; port: number }) => host.hostname === request.host && host.port === port;
    return this.readSaved().find(match)?.name ?? this.legacy.find(match)?.name ?? request.host;
  }

  private readSaved(): SavedHost[] {
    const raw = this.options.storage.getItem(ANDROID_HOSTS_STORAGE_KEY);
    if (!raw) return [];
    try {
      const parsed: unknown = JSON.parse(raw);
      return Array.isArray(parsed) ? parsed.filter(isSavedHost) : [];
    } catch {
      return [];
    }
  }
}
