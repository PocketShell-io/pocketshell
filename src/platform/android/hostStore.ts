/**
 * The Android host source: the phone's answer to desktop's `~/.ssh/config`
 * and web's synced account list, behind the shared picker's
 * `ssh.listConfigHosts` and the adapter's credential resolution.
 *
 * Two kinds of host appear:
 *  - hosts imported from the signed 0.5.x app (`readImportedLegacyHosts`),
 *    whose private keys never leave native storage — they dial with the
 *    opaque `legacy-private-key` reference the native plugin resolves;
 *  - hosts added on this phone. Their metadata persists; the pasted private
 *    key is held for this app session only, because the durable home for
 *    key material is the native key-handle store (#2926), not WebView
 *    storage. A saved host whose key is gone asks for it again.
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
  constructor(hostName: string) {
    super(`No private key for “${hostName}” in this app session — add the host again with its key.`);
    this.name = 'MissingHostCredential';
  }
}

function toEntry(host: SavedHost): HostEntry {
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
    && typeof host.port === 'number' && Number.isInteger(host.port) && host.port > 0 && host.port < 65536;
}

export function validateSavedHost(host: SavedHost): string | null {
  if (!host.name.trim()) return 'Give the host a name.';
  if (!host.hostname.trim()) return 'Enter a hostname or IP address.';
  if (!host.user.trim()) return 'Enter the SSH user.';
  if (!Number.isInteger(host.port) || host.port < 1 || host.port > 65535) return 'Port must be 1–65535.';
  return null;
}

export class AndroidHostStore {
  private readonly sessionKeys = new Map<string, string>();
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

  /** Add or replace a host; the key is kept for this app session only. */
  save(host: SavedHost, privateKeyPem: string): void {
    const problem = validateSavedHost(host);
    if (problem) throw new Error(problem);
    const saved = this.readSaved().filter((existing) => existing.name !== host.name);
    saved.push({ name: host.name.trim(), hostname: host.hostname.trim(), port: host.port, user: host.user.trim() });
    this.options.storage.setItem(ANDROID_HOSTS_STORAGE_KEY, JSON.stringify(saved));
    if (privateKeyPem.trim()) this.sessionKeys.set(host.name.trim(), privateKeyPem.trim());
  }

  remove(name: string): void {
    const saved = this.readSaved().filter((existing) => existing.name !== name);
    this.options.storage.setItem(ANDROID_HOSTS_STORAGE_KEY, JSON.stringify(saved));
    this.sessionKeys.delete(name);
  }

  hasSessionKey(name: string): boolean {
    return this.sessionKeys.has(name);
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
      const key = this.sessionKeys.get(savedMatch.name);
      if (!key) throw new MissingHostCredential(savedMatch.name);
      return {
        hostId: `${savedMatch.user}@${savedMatch.hostname}:${savedMatch.port}`,
        hostname: savedMatch.hostname,
        port: savedMatch.port,
        username: request.user || savedMatch.user,
        credential: { kind: 'private-key', privateKeyPem: key },
      };
    }
    if (this.legacy.length === 0) this.legacy = await this.options.readLegacyHosts().catch(() => []);
    const legacyMatch =
      (request.hostAlias ? this.legacy.find((host) => host.name === request.hostAlias) : undefined) ??
      this.legacy.find((host) => host.hostname === request.host && host.port === port);
    if (legacyMatch) {
      // The native plugin resolves the opaque reference; no key bytes in JS.
      return makeLegacySshHostTarget(legacyMatch, '') as unknown as SshHostTarget;
    }
    throw new Error(`No saved host for ${request.user ? `${request.user}@` : ''}${request.host}:${port}.`);
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
