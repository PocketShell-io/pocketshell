import {
  aliasesToAutoCheck,
  assembleSyncSet,
  parseSyncPayloadResult,
  serializeSyncPayload,
  type SyncHostEntry,
  type SyncPayloadParseResult,
} from '@pocketshell/core';

/** The decrypted state returned by a future platform sync adapter. */
export type SettingsSyncSnapshot =
  | { kind: 'absent' }
  | { kind: 'ok'; version: number; plaintext: string };

/**
 * Platform effects are deliberately injected. The JS-first Android shell has
 * no production implementation until OAuth, encryption, and secure storage
 * are available; this boundary must not grow a browser/network fallback.
 */
export interface SettingsSyncEffects {
  pull(): Promise<SettingsSyncSnapshot>;
  push(input: { baseVersion: number | null; plaintext: string }): Promise<
    | { kind: 'ok'; version: number }
    | { kind: 'conflict'; currentVersion: number }
    | { kind: 'error'; message: string }
  >;
}

export const SETTINGS_SYNC_CONFLICT_RETRIES = 3;

/** Parse through core's strict, write-safe payload contract. */
export function parseSettingsSyncPayload(plaintext: string): SyncPayloadParseResult {
  return parseSyncPayloadResult(plaintext);
}

export type SettingsSyncResult =
  | {
      kind: 'synced';
      version: number;
      selectedAliases: string[];
      hosts: SyncHostEntry[];
      attempts: number;
    }
  | {
      kind: 'invalid-payload';
      reason: 'invalid-json' | 'invalid-shape' | 'invalid-host-entry' | 'unsupported-version';
      index?: number;
    }
  | { kind: 'empty-selection' }
  | { kind: 'error'; stage: 'pull' | 'push'; message: string }
  | { kind: 'conflict-limit'; version: number; attempts: number };

/**
 * Run the Android client's portable sync decisions through @pocketshell/core.
 * Effects receive plaintext only at this injected test/platform boundary;
 * they never choose aliases, parse payloads, or merge host fields.
 */
export async function syncSelectedHosts(
  localHosts: readonly SyncHostEntry[],
  checkedAliases: readonly string[],
  effects: SettingsSyncEffects,
): Promise<SettingsSyncResult> {
  const selected = [...new Set(checkedAliases)];
  const localAliases = localHosts.map((host) => host.name);

  for (let attempts = 1; attempts <= SETTINGS_SYNC_CONFLICT_RETRIES + 1; attempts += 1) {
    let snapshot: SettingsSyncSnapshot;
    try {
      snapshot = await effects.pull();
    } catch (error) {
      return { kind: 'error', stage: 'pull', message: errorMessage(error) };
    }
    if (!isSettingsSyncSnapshot(snapshot)) {
      return { kind: 'error', stage: 'pull', message: 'The sync service returned an invalid snapshot.' };
    }

    let version: number | null = null;
    let remoteHosts: SyncHostEntry[] = [];
    if (snapshot.kind === 'ok') {
      if (!Number.isSafeInteger(snapshot.version) || snapshot.version < 0) {
        return { kind: 'error', stage: 'pull', message: 'The sync service returned an invalid version.' };
      }
      version = snapshot.version;
      const parsed = parseSettingsSyncPayload(snapshot.plaintext);
      if (parsed.kind === 'invalid') {
        return {
          kind: 'invalid-payload',
          reason: parsed.reason,
          ...(parsed.index === undefined ? {} : { index: parsed.index }),
        };
      }
      remoteHosts = parsed.hosts;
      for (const alias of aliasesToAutoCheck(remoteHosts, selected, localAliases)) {
        selected.push(alias);
      }
    }

    const hosts = assembleSyncSet(localHosts, remoteHosts, selected);
    // An empty assembled list can otherwise replace the account with an
    // empty payload. Explicit account clearing needs a separate user action.
    if (hosts.length === 0) return { kind: 'empty-selection' };

    const plaintext = serializeSyncPayload(hosts);
    let pushed: Awaited<ReturnType<SettingsSyncEffects['push']>>;
    try {
      pushed = await effects.push({ baseVersion: version, plaintext });
    } catch (error) {
      return { kind: 'error', stage: 'push', message: errorMessage(error) };
    }
    if (!isSettingsSyncPushResult(pushed)) {
      return { kind: 'error', stage: 'push', message: 'The sync service returned an invalid response.' };
    }

    if (pushed.kind === 'error') {
      return { kind: 'error', stage: 'push', message: pushed.message };
    }
    if (pushed.kind === 'ok') {
      return {
        kind: 'synced',
        version: pushed.version,
        selectedAliases: [...selected],
        hosts,
        attempts,
      };
    }
    if (attempts > SETTINGS_SYNC_CONFLICT_RETRIES) {
      return { kind: 'conflict-limit', version: pushed.currentVersion, attempts };
    }
  }

  // The bounded loop always returns; retain an explicit result for type and
  // runtime safety if its retry bound is changed later.
  return { kind: 'conflict-limit', version: 0, attempts: SETTINGS_SYNC_CONFLICT_RETRIES + 1 };
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function isSettingsSyncSnapshot(value: unknown): value is SettingsSyncSnapshot {
  if (!isObjectRecord(value)) return false;
  if (value.kind === 'absent') return true;
  return value.kind === 'ok'
    && typeof value.version === 'number'
    && Number.isSafeInteger(value.version)
    && value.version >= 0
    && typeof value.plaintext === 'string';
}

function isSettingsSyncPushResult(
  value: unknown,
): value is Awaited<ReturnType<SettingsSyncEffects['push']>> {
  if (!isObjectRecord(value)) return false;
  if (value.kind === 'error') return typeof value.message === 'string';
  if (value.kind === 'ok') {
    return typeof value.version === 'number' && Number.isSafeInteger(value.version) && value.version >= 0;
  }
  return value.kind === 'conflict'
    && typeof value.currentVersion === 'number'
    && Number.isSafeInteger(value.currentVersion)
    && value.currentVersion >= 0;
}

function isObjectRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}
