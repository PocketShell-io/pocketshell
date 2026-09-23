import { Capacitor } from '@capacitor/core';
import { reactive } from 'vue';
import {
  installedDataMigrationNative,
  type NativeAssetChunk,
  type NativeInstalledDataMigrationPlugin,
  type NativeLegacyAsset,
  type NativeLegacySnapshot,
} from '../native/installedDataMigration';

export const SETTINGS_STORAGE_KEY = 'pocketshell.js.settings.v1';
export const IMPORT_DATABASE_NAME = 'pocketshell-installed-data-v1';
export const IMPORT_RECORD_ID = 'legacy-import-v1';
export const IMPORT_STATUS_KEY = 'records';
export const IMPORT_ASSET_STORE = 'assets';
export const SETTINGS_RELOAD_SESSION_KEY = 'pocketshell.installed-data-settings-reload.v1';
export const MAX_ASSET_CHUNK_BYTES = 32 * 1024;
const SUPPORTED_APPLICATION_ID = /^com\.pocketshell\.app(?:\.[A-Za-z0-9_]+)*$/;

const ROOM_IDENTITY_HASHES: Readonly<Record<number, string>> = {
  16: 'afc67c7758ec9cfe23ecb21326abe06d',
  17: '274c6967510073a57e89482cbfd30958',
  18: '05f1073ebb70b0b41033a4e4bbe22082',
  19: 'de42603f8ceee22afa2a074bcc9be30e',
  20: 'be97992383b7a66363efbe2cbb142d0b',
  21: '08d40b0e3508acf5ffa4f2378d79325e',
  22: '998a588a2d2f383454698ea11820fca9',
};

export interface StringStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}

export interface ImportedAsset {
  assetId: string;
  category: string;
  relativePath: string;
  byteLength: number;
  sha256: string;
  content: Blob;
}

export interface ImportRecord {
  id: typeof IMPORT_RECORD_ID;
  status: 'staged' | 'complete' | 'empty';
  importedAt: number;
  snapshot: NativeLegacySnapshot;
  warnings: string[];
}

export interface ImportPersistence {
  readRecord(): Promise<ImportRecord | undefined>;
  stage(record: ImportRecord, assets: ImportedAsset[]): Promise<void>;
  markComplete(): Promise<void>;
}

export interface MigrationDependencies {
  native: NativeInstalledDataMigrationPlugin;
  persistence: ImportPersistence;
  storage: StringStorage;
  nativePlatform: boolean;
  now: () => number;
  pixelRatio: () => number;
}

export const installedDataMigrationState = reactive({
  status: 'pending' as 'pending' | 'complete' | 'failed',
  error: '',
  retrying: false,
});

export class InstalledDataMigrationError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'InstalledDataMigrationError';
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function hasOwn(record: Record<string, unknown>, key: string): boolean {
  return Object.prototype.hasOwnProperty.call(record, key);
}

function typedEntry(
  snapshot: NativeLegacySnapshot,
  storeName: string,
  key: string,
  expectedType: string,
): unknown | undefined {
  const store = snapshot.preferences[storeName];
  const entry = store?.entries?.[key];
  if (!entry) return undefined;
  if (entry.type !== expectedType) {
    throw new InstalledDataMigrationError(`Legacy preference ${storeName}.${key} has an unexpected value type.`);
  }
  return entry.value;
}

function decodeEscapedField(value: string): string {
  let output = '';
  for (let index = 0; index < value.length; index += 1) {
    const character = value[index];
    if (character !== '\\') {
      output += character;
      continue;
    }
    const escape = value[index + 1];
    if (escape === undefined) throw new InstalledDataMigrationError('A saved draft attachment ends with an incomplete escape.');
    if (escape === '\\') output += '\\';
    else if (escape === 't') output += '\t';
    else if (escape === 'n') output += '\n';
    else throw new InstalledDataMigrationError('A saved draft attachment contains an unknown escape.');
    index += 1;
  }
  return output;
}

export function validateLegacyPreferences(snapshot: NativeLegacySnapshot): void {
  const composer = snapshot.preferences.composer_drafts?.entries ?? {};
  for (const [key, entry] of Object.entries(composer)) {
    if (key.startsWith('@migrated/')) {
      if (entry.type !== 'boolean' || typeof entry.value !== 'boolean') {
        throw new InstalledDataMigrationError('A saved draft migration marker has an unexpected type.');
      }
      continue;
    }
    if (entry.type !== 'string' || typeof entry.value !== 'string') {
      throw new InstalledDataMigrationError('A saved composer draft has an unexpected value type.');
    }
    if (!key.startsWith('@att/')) continue;
    if (entry.value.length === 0) continue;
    for (const row of entry.value.split('\n')) {
      const fields = row.split('\t');
      if (fields.length !== 3) {
        throw new InstalledDataMigrationError('A saved draft attachment row is malformed.');
      }
      const [path] = fields.map(decodeEscapedField);
      if (!path) throw new InstalledDataMigrationError('A saved draft attachment has no path.');
    }
  }

  const rawSelection = typedEntry(snapshot, 'next_sync_selection', 'sync_selected_hosts', 'string');
  if (rawSelection !== undefined) {
    if (typeof rawSelection !== 'string') {
      throw new InstalledDataMigrationError('The selected-host sync preference is not text.');
    }
    let decoded: unknown;
    try {
      decoded = JSON.parse(rawSelection) as unknown;
    } catch {
      throw new InstalledDataMigrationError('The selected-host sync preference is malformed JSON.');
    }
    if (!Array.isArray(decoded) || decoded.some((alias) => typeof alias !== 'string')) {
      throw new InstalledDataMigrationError('The selected-host sync preference must be an ordered list of aliases.');
    }
  }

  const settingTypes: Record<string, string> = {
    terminal_text_size_px: 'int',
    voice_language: 'string',
    voice_silence_seconds: 'float',
    usage_warn_threshold_percent: 'int',
    background_grace_millis: 'long',
    agent_submit_enter_delay_ms: 'int',
    default_host_id: 'long',
    last_workspace_path: 'string',
    last_session_id: 'string',
    show_common_keys: 'boolean',
    reconnect_when_return: 'boolean',
  };
  for (const [key, expectedType] of Object.entries(settingTypes)) {
    typedEntry(snapshot, 'next_settings', key, expectedType);
  }
  typedEntry(snapshot, 'app_settings', 'voice_silence_seconds', 'float');

  const workspaceOrder = snapshot.preferences.workspace_order?.entries ?? {};
  for (const [key, entry] of Object.entries(workspaceOrder)) {
    if (key.startsWith('host-root-') && (entry.type !== 'string' || typeof entry.value !== 'string')) {
      throw new InstalledDataMigrationError('A workspace-order preference has an unexpected value type.');
    }
  }
  const showAllPorts = typedEntry(snapshot, 'port_forward_panel', 'show_all_ports', 'boolean');
  if (showAllPorts !== undefined && typeof showAllPorts !== 'boolean') {
    throw new InstalledDataMigrationError('The port-panel preference has an invalid value.');
  }
}

function readLegacySetting(snapshot: NativeLegacySnapshot, key: string): unknown | undefined {
  const current = snapshot.preferences.next_settings?.entries?.[key];
  if (current) return current.value;
  if (key === 'voice_silence_seconds') {
    const legacy = snapshot.preferences.app_settings?.entries?.[key];
    if (legacy) return legacy.value;
  }
  return undefined;
}

export interface PreparedLocalStorageWrites {
  settings?: string;
  trustPins: Array<{ key: string; value: string }>;
  warnings: string[];
}

export function prepareLocalStorageWrites(
  snapshot: NativeLegacySnapshot,
  storage: StringStorage,
  pixelRatio: number,
): PreparedLocalStorageWrites {
  validateLegacyPreferences(snapshot);
  const warnings: string[] = [];
  const serialized = storage.getItem(SETTINGS_STORAGE_KEY);
  let settings: Record<string, unknown> = {};
  if (serialized !== null) {
    try {
      const parsed: unknown = JSON.parse(serialized);
      if (!isRecord(parsed)) throw new Error('Expected a settings object.');
      settings = { ...parsed };
    } catch {
      throw new InstalledDataMigrationError('Existing JavaScript settings are unreadable; they were left unchanged.');
    }
  }
  const originalSettings = { ...settings };

  const oldFontPx = readLegacySetting(snapshot, 'terminal_text_size_px');
  if (oldFontPx !== undefined && !hasOwn(settings, 'terminalFontSize')) {
    if (!Number.isInteger(oldFontPx) || (oldFontPx as number) < 16 || (oldFontPx as number) > 48) {
      throw new InstalledDataMigrationError('The saved terminal text size is outside the legacy range.');
    }
    const ratio = Number.isFinite(pixelRatio) && pixelRatio > 0 ? pixelRatio : 1;
    const cssPixels = Math.round((oldFontPx as number) / ratio);
    if (cssPixels >= 8 && cssPixels <= 32) settings.terminalFontSize = cssPixels;
    else warnings.push('The legacy terminal text size is preserved in the migration record but cannot be represented by the current settings range.');
  }

  const oldGrace = readLegacySetting(snapshot, 'background_grace_millis');
  if (oldGrace !== undefined && !hasOwn(settings, 'backgroundGraceMs')) {
    const grace = typeof oldGrace === 'string' ? Number(oldGrace) : oldGrace;
    if (!Number.isSafeInteger(grace) || ![30_000, 90_000, 300_000].includes(grace as number)) {
      throw new InstalledDataMigrationError('The saved background grace period is not supported by the current settings.');
    }
    settings.backgroundGraceMs = grace;
  }

  const settingsChanged = settings.terminalFontSize !== originalSettings.terminalFontSize
    || settings.backgroundGraceMs !== originalSettings.backgroundGraceMs;
  const settingsWrite = settingsChanged || (serialized === null && (oldFontPx !== undefined || oldGrace !== undefined))
    ? JSON.stringify(settings)
    : undefined;

  const trustPins: PreparedLocalStorageWrites['trustPins'] = [];
  const hosts = snapshot.database.tables.hosts ?? [];
  for (const candidate of hosts) {
    if (!isRecord(candidate)) throw new InstalledDataMigrationError('A saved host row is malformed.');
    const hostId = candidate.id;
    const algorithm = candidate.trustedHostKeyAlgorithm;
    const fingerprint = candidate.trustedHostKeySha256;
    if (algorithm == null && fingerprint == null) continue;
    if (typeof hostId !== 'number' || !Number.isSafeInteger(hostId) ||
      algorithm !== 'SHA256' || typeof fingerprint !== 'string' ||
      !/^SHA256:[A-Za-z0-9+/]{43}$/.test(fingerprint)) {
      throw new InstalledDataMigrationError('A saved host has an incomplete or unsupported SSH trust pin.');
    }
    const key = `pocketshell.ssh.host-key.${hostId}`;
    const value = JSON.stringify({ kind: 'sha256-fingerprint', fingerprintSha256: fingerprint });
    const existing = storage.getItem(key);
    if (existing !== null) {
      const existingFingerprint = readStoredFingerprint(existing);
      if (existingFingerprint !== fingerprint) {
        throw new InstalledDataMigrationError(`A JavaScript trust pin conflicts with the saved host ${hostId}; neither value was overwritten.`);
      }
      continue;
    }
    trustPins.push({ key, value });
  }

  return { settings: settingsWrite, trustPins, warnings };
}

function readStoredFingerprint(raw: string): string | null {
  try {
    const parsed: unknown = JSON.parse(raw);
    if (typeof parsed === 'string') return parsed;
    if (isRecord(parsed) && typeof parsed.fingerprintSha256 === 'string') return parsed.fingerprintSha256;
    return null;
  } catch {
    return raw.startsWith('SHA256:') ? raw : null;
  }
}

function applyLocalStorageWrites(
  snapshot: NativeLegacySnapshot,
  storage: StringStorage,
  pixelRatio: number,
): PreparedLocalStorageWrites {
  const writes = prepareLocalStorageWrites(snapshot, storage, pixelRatio);
  if (writes.settings !== undefined) storage.setItem(SETTINGS_STORAGE_KEY, writes.settings);
  for (const pin of writes.trustPins) storage.setItem(pin.key, pin.value);
  return writes;
}

function validateSnapshot(snapshot: NativeLegacySnapshot): void {
  if (!isRecord(snapshot) || snapshot.schemaVersion !== 1 ||
    typeof snapshot.environment?.applicationId !== 'string' ||
    !SUPPORTED_APPLICATION_ID.test(snapshot.environment.applicationId)) {
    throw new InstalledDataMigrationError('The installed-data reader returned an unsupported package or payload version.');
  }
  if (snapshot.database?.present) {
    const version = snapshot.database.version;
    const expected = typeof version === 'number' ? ROOM_IDENTITY_HASHES[version] : undefined;
    if (!expected || expected !== snapshot.database.identityHash) {
      throw new InstalledDataMigrationError('The installed Room database schema is unknown or inconsistent.');
    }
  }
  if (!isRecord(snapshot.preferences) || !isRecord(snapshot.encryptedPreferences) ||
    !Array.isArray(snapshot.assets) || !Array.isArray(snapshot.nativeFiles) || !isRecord(snapshot.database.tables)) {
    throw new InstalledDataMigrationError('The installed-data reader returned an incomplete snapshot.');
  }
  const assetIds = new Set<string>();
  for (const asset of snapshot.assets) {
    if (!isRecord(asset) || typeof asset.assetId !== 'string' || assetIds.has(asset.assetId) ||
      typeof asset.category !== 'string' || typeof asset.relativePath !== 'string' ||
      !Number.isSafeInteger(asset.byteLength) || asset.byteLength < 0 ||
      typeof asset.sha256 !== 'string' || !/^[a-f0-9]{64}$/.test(asset.sha256)) {
      throw new InstalledDataMigrationError('A private data file entry is malformed.');
    }
    assetIds.add(asset.assetId);
  }
  for (const store of Object.values(snapshot.encryptedPreferences)) {
    if (!Array.isArray(store.keys) || store.keys.some((key) => typeof key !== 'string')) {
      throw new InstalledDataMigrationError('An encrypted preference store returned invalid key metadata.');
    }
    if (!store.present) {
      if (store.status !== 'absent') {
        throw new InstalledDataMigrationError('An absent encrypted preference store has an inconsistent status.');
      }
      continue;
    }
    if (store.status === 'unavailable') {
      if (typeof store.error !== 'string' || store.error.length === 0) {
        throw new InstalledDataMigrationError('An encrypted preference store could not be verified.');
      }
    } else if (store.status !== 'decrypted-native-retained') {
      throw new InstalledDataMigrationError('An encrypted credential store could not be verified.');
    }
  }
  validateLegacyPreferences(snapshot);
}

function hasLegacyData(snapshot: NativeLegacySnapshot): boolean {
  return snapshot.database.present
    || Object.values(snapshot.preferences).some((store) => store.present)
    || Object.values(snapshot.encryptedPreferences).some((store) => store.present)
    || snapshot.assets.length > 0
    || snapshot.nativeFiles.length > 0;
}

function decodeBase64(value: string): Uint8Array {
  let decoded: string;
  try {
    decoded = atob(value);
  } catch {
    throw new InstalledDataMigrationError('A private data file chunk is not valid base64.');
  }
  return Uint8Array.from(decoded, (character) => character.charCodeAt(0));
}

async function copyAsset(
  native: NativeInstalledDataMigrationPlugin,
  asset: NativeLegacyAsset,
): Promise<ImportedAsset> {
  if (!Number.isSafeInteger(asset.byteLength) || asset.byteLength < 0 || asset.byteLength > 20 * 1024 * 1024) {
    throw new InstalledDataMigrationError('A private data file exceeds the supported import size.');
  }
  const bytes = new Uint8Array(asset.byteLength);
  for (let offset = 0; offset < bytes.length;) {
    const maxBytes = Math.min(MAX_ASSET_CHUNK_BYTES, bytes.length - offset);
    let chunk: NativeAssetChunk;
    try {
      chunk = await native.readAssetChunk({ assetId: asset.assetId, offset, maxBytes });
    } catch {
      throw new InstalledDataMigrationError(`Could not read private file ${asset.relativePath}; the source was left in place.`);
    }
    const chunkBytes = decodeBase64(chunk.base64);
    if (chunk.assetId !== asset.assetId || chunk.offset !== offset ||
      chunk.byteLength !== chunkBytes.length || chunkBytes.length !== maxBytes) {
      throw new InstalledDataMigrationError(`Private file ${asset.relativePath} returned an incomplete chunk.`);
    }
    bytes.set(chunkBytes, offset);
    offset += chunkBytes.length;
  }
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
  const hash = Array.from(digest, (value) => value.toString(16).padStart(2, '0')).join('');
  if (hash !== asset.sha256) throw new InstalledDataMigrationError(`Private file ${asset.relativePath} changed during import.`);
  return { ...asset, content: new Blob([bytes]) };
}

function requestResult<T>(request: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error ?? new Error('IndexedDB request failed.'));
  });
}

function transactionDone(transaction: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => {
    transaction.oncomplete = () => resolve();
    transaction.onerror = () => reject(transaction.error ?? new Error('IndexedDB transaction failed.'));
    transaction.onabort = () => reject(transaction.error ?? new Error('IndexedDB transaction was aborted.'));
  });
}

export function createImportPersistence(factory: IDBFactory = indexedDB): ImportPersistence {
  const open = async (): Promise<IDBDatabase> => {
    const request = factory.open(IMPORT_DATABASE_NAME, 1);
    request.onupgradeneeded = () => {
      const database = request.result;
      if (!database.objectStoreNames.contains(IMPORT_STATUS_KEY)) {
        database.createObjectStore(IMPORT_STATUS_KEY, { keyPath: 'id' });
      }
      if (!database.objectStoreNames.contains(IMPORT_ASSET_STORE)) {
        database.createObjectStore(IMPORT_ASSET_STORE, { keyPath: 'assetId' });
      }
    };
    return requestResult(request);
  };
  return {
    async readRecord() {
      const database = await open();
      try {
        const transaction = database.transaction(IMPORT_STATUS_KEY, 'readonly');
        const done = transactionDone(transaction);
        const record = await requestResult(transaction.objectStore(IMPORT_STATUS_KEY).get(IMPORT_RECORD_ID)) as ImportRecord | undefined;
        await done;
        return record;
      } finally {
        database.close();
      }
    },
    async stage(record, assets) {
      const database = await open();
      try {
        const transaction = database.transaction([IMPORT_STATUS_KEY, IMPORT_ASSET_STORE], 'readwrite');
        const recordStore = transaction.objectStore(IMPORT_STATUS_KEY);
        const staged = { ...record, status: 'staged' as const };
        recordStore.put(staged);
        const assetStore = transaction.objectStore(IMPORT_ASSET_STORE);
        assetStore.clear();
        for (const asset of assets) {
          assetStore.put({
            assetId: asset.assetId,
            category: asset.category,
            relativePath: asset.relativePath,
            byteLength: asset.byteLength,
            sha256: asset.sha256,
            content: asset.content,
          });
        }
        await transactionDone(transaction);
      } finally {
        database.close();
      }
    },
    async markComplete() {
      const database = await open();
      try {
        const transaction = database.transaction(IMPORT_STATUS_KEY, 'readwrite');
        const done = transactionDone(transaction);
        const store = transaction.objectStore(IMPORT_STATUS_KEY);
        const record = await requestResult(store.get(IMPORT_RECORD_ID)) as ImportRecord | undefined;
        if (!record || record.status !== 'staged') {
          transaction.abort();
          await done.catch(() => undefined);
          throw new InstalledDataMigrationError('Installed data import could not be committed. Retry the import.');
        }
        store.put({ ...record, status: record.snapshot.database.present || hasLegacyData(record.snapshot) ? 'complete' : 'empty' });
        await done;
      } finally {
        database.close();
      }
    },
  };
}

export function shouldReloadForImportedSettings(
  settingsWritten: boolean,
  storage?: Pick<Storage, 'getItem' | 'setItem'>,
): boolean {
  if (!settingsWritten) return false;
  try {
    const session = storage ?? globalThis.sessionStorage;
    if (session.getItem(SETTINGS_RELOAD_SESSION_KEY) === '1') return false;
    session.setItem(SETTINGS_RELOAD_SESSION_KEY, '1');
    return true;
  } catch {
    return false;
  }
}

export async function runInstalledDataMigration(
  overrides: Partial<MigrationDependencies> = {},
): Promise<boolean> {
  const nativePlatform = overrides.nativePlatform ?? (Capacitor.getPlatform() === 'android');
  if (!nativePlatform) {
    installedDataMigrationState.status = 'complete';
    installedDataMigrationState.error = '';
    return false;
  }
  const dependencies: MigrationDependencies = {
    native: overrides.native ?? installedDataMigrationNative,
    persistence: overrides.persistence ?? createImportPersistence(),
    storage: overrides.storage ?? globalThis.localStorage,
    nativePlatform,
    now: Date.now,
    pixelRatio: () => globalThis.devicePixelRatio || 1,
    ...overrides,
  };
  installedDataMigrationState.retrying = true;
  try {
    const priorRecord = await dependencies.persistence.readRecord();
    if (priorRecord?.status === 'complete' || priorRecord?.status === 'empty') {
      validateSnapshot(priorRecord.snapshot);
      const writes = applyLocalStorageWrites(priorRecord.snapshot, dependencies.storage, dependencies.pixelRatio());
      installedDataMigrationState.status = 'complete';
      installedDataMigrationState.error = '';
      return writes.settings !== undefined;
    }
    const snapshot = await dependencies.native.readLegacyInstalledData();
    validateSnapshot(snapshot);
    let writes = prepareLocalStorageWrites(snapshot, dependencies.storage, dependencies.pixelRatio());
    const assets: ImportedAsset[] = [];
    for (const asset of snapshot.assets) assets.push(await copyAsset(dependencies.native, asset));
    const record: ImportRecord = {
      id: IMPORT_RECORD_ID,
      status: 'staged',
      importedAt: dependencies.now(),
      snapshot,
      warnings: [
        ...writes.warnings,
        ...Object.entries(snapshot.encryptedPreferences)
          .filter(([, store]) => store.present && store.status === 'unavailable')
          .map(([, store]) => store.error ?? 'An encrypted preference store could not be verified.'),
      ],
    };
    await dependencies.persistence.stage(record, assets);
    // Re-read local settings after asynchronous asset staging so a setting
    // created or changed during this migration attempt always wins.
    const unavailableStores = Object.values(snapshot.encryptedPreferences)
      .filter((store) => store.present && store.status === 'unavailable');
    if (unavailableStores.length > 0) {
      installedDataMigrationState.status = 'failed';
      installedDataMigrationState.error = unavailableStores
        .map((store) => store.error ?? 'An encrypted preference store could not be verified.')
        .join(' ');
      return false;
    }
    await dependencies.persistence.markComplete();
    writes = applyLocalStorageWrites(snapshot, dependencies.storage, dependencies.pixelRatio());
    installedDataMigrationState.status = 'complete';
    installedDataMigrationState.error = '';
    return writes.settings !== undefined;
  } catch (error) {
    installedDataMigrationState.status = 'failed';
    installedDataMigrationState.error = error instanceof Error
      ? error.message
      : 'Installed data could not be imported. Original data was left untouched.';
    return false;
  } finally {
    installedDataMigrationState.retrying = false;
  }
}

export function retryInstalledDataMigration(): Promise<boolean> {
  return runInstalledDataMigration();
}
