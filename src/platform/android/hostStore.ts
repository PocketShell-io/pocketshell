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
import type { GatewayTransportTarget, HostEntry, SshHostTarget } from '@pocketshell/core';
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
  constructor(hostName: string) {
    super(`No SSH key is chosen for “${hostName}” — add the host again with a key from the key vault.`);
    this.name = 'MissingHostCredential';
  }
}

/**
 * The one refusal a present gateway marker produces on this phone, word for
 * word (issue #3059). The Android transport is the native SSH plugin only;
 * the gateway ships in the browser first, so a gateway host must never be
 * dialled here — and never silently degraded into an ordinary SSH target.
 */
export const GATEWAY_UNSUPPORTED_MESSAGE =
  'This host dials through the PocketShell gateway, which this app does not support.';

export class GatewayHostUnsupported extends Error {
  constructor() {
    super(GATEWAY_UNSUPPORTED_MESSAGE);
    this.name = 'GatewayHostUnsupported';
  }
}

/**
 * Whether a host record carries a PRESENT gateway transport marker — an own
 * `gateway` property, whatever its value: a valid target, null, or a shape
 * this build does not understand. Presence is the whole contract for the
 * guard (#3059): the marker is never inspected, normalized, or repaired, so
 * a malformed or future-shaped marker cannot pass for an ordinary host. An
 * entry carrying BOTH `link` and `gateway` refuses too — the conflict is
 * never resolved by falling back to the link transport.
 */
export function hasGatewayMarker(host: object): boolean {
  return Object.prototype.hasOwnProperty.call(host, 'gateway');
}

/** The marker's raw value; only meaningful when {@link hasGatewayMarker} is true. */
export function gatewayMarkerValue(host: object): unknown {
  return (host as unknown as Record<string, unknown>)['gateway'];
}

/**
 * The raw marker, typed for CARRIAGE in a `HostEntry`'s `gateway` field
 * (#3059). Core's `HostEntry.gateway?: GatewayTransportTarget` types a
 * VALIDATED target — what `normalizeGatewayTarget` returns once the server
 * URL and device id have been checked. What crosses this boundary is
 * untrusted data (JSON out of on-device storage, the sync account copy, or
 * a dial request) that may equally be null, undefined, or a shape this
 * build does not understand, and it must arrive verbatim: dropping it
 * would present a gateway host as an ordinary one, re-shaping it could
 * pass malformed data off as a valid target. The single assertion here is
 * a carriage annotation at that untrusted boundary, not a validity claim —
 * nothing on the Android dial path reads the value (every guard decides on
 * PRESENCE via `hasGatewayMarker` and refuses before any credential,
 * controller or native call), and type validity resumes only where a
 * gateway-dialling client interprets the value, through core's
 * `normalizeGatewayTarget`, which fails closed on everything that is not a
 * usable target.
 */
export function carriedGatewayMarker(host: object): { gateway: GatewayTransportTarget } {
  return { gateway: gatewayMarkerValue(host) as GatewayTransportTarget };
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
    // A stored entry that somehow carries a gateway marker keeps it (#3059):
    // the listed entry must say what it is, so the platform boundary refuses
    // the dial instead of the marker quietly vanishing into an ordinary host.
    ...(hasGatewayMarker(host) ? carriedGatewayMarker(host) : {}),
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
  // Fail closed at the write, too (#3059): a gateway host is never stored as
  // if it were an ordinary one.
  if (hasGatewayMarker(host)) return GATEWAY_UNSUPPORTED_MESSAGE;
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
    const saved = this.readSaved().filter((existing) => existing.name !== host.name);
    saved.push({
      name: host.name.trim(),
      hostname: host.hostname.trim(),
      port: host.port,
      user: host.user.trim(),
      keyHandleId: host.keyHandleId,
    });
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
   *
   * Guards run before anything is resolved or read into a credential
   * (#3059): a request that carries a gateway marker refuses here, and so
   * does a stored record that carries one — before the key handle is named,
   * before the legacy import is consulted, and before any native plugin
   * call a returned target would lead to.
   */
  async resolve(request: { host: string; port?: number; user: string; hostAlias?: string }): Promise<SshHostTarget> {
    if (hasGatewayMarker(request)) throw new GatewayHostUnsupported();
    const port = request.port ?? 22;
    const saved = this.readSaved();
    const savedMatch =
      (request.hostAlias ? saved.find((host) => host.name === request.hostAlias) : undefined) ??
      saved.find((host) => host.hostname === request.host && host.port === port);
    if (savedMatch) {
      if (hasGatewayMarker(savedMatch)) throw new GatewayHostUnsupported();
      if (!savedMatch.keyHandleId) throw new MissingHostCredential(savedMatch.name);
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
