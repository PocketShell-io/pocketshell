import {
  MAX_DIAGNOSTIC_REPORTS,
  legacyCrashReport,
  legacyDiagnosticHistoryReport,
  redactDiagnosticText,
  runtimeErrorReport,
  sortDiagnosticReports,
  type DiagnosticReport,
} from '@pocketshell/core';
import { IMPORT_ASSET_STORE, IMPORT_DATABASE_NAME } from '../migration/installedDataMigration';
import { nativeCrashReports, type NativeCrashReportsPlugin } from '../native/nativeCrashReports';

/**
 * Android's `api.diagnostics` capability and `api.diag.log` sink for the
 * shared app (pocketshell-core packages/ui `app/api.ts`).
 *
 * Three report sources, one redacted list:
 *  - runtime errors the shared app's `installDiagCapture` forwards through
 *    `diag.log`, stored in WebView local storage;
 *  - native crashes written by the Java `NativeCrashRecorder`;
 *  - 0.5.x crash reports and diagnostic history staged read-only by the
 *    installed-data import (#2860). Their source files are never modified;
 *    deleting one here hides it from this list.
 * All parsing and redaction is core `diagnosticReports.ts`.
 */

export const RUNTIME_REPORTS_STORAGE_KEY = 'pocketshell.js.diagnostic-reports.v1';
export const HIDDEN_IMPORTED_REPORTS_STORAGE_KEY = 'pocketshell.js.diagnostic-reports-hidden.v1';

export interface ImportedReportAsset {
  assetId: string;
  category: string;
  relativePath: string;
  text: string;
}

export interface DiagnosticsStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

export type DiagnosticsShareOutcome = 'shared' | 'saved' | 'cancelled';

export interface AndroidDiagnosticsDependencies {
  storage?: DiagnosticsStorage | null;
  nativeCrashes?: Pick<NativeCrashReportsPlugin, 'list' | 'remove' | 'clear'>;
  readImportedAssets?: () => Promise<ImportedReportAsset[]>;
  shareFile?: (file: { fileName: string; text: string }) => Promise<DiagnosticsShareOutcome>;
  now?: () => number;
  appVersion?: () => string;
}

export interface DiagLogEntry {
  kind: string;
  message: string;
  stack?: string;
  errorName?: string;
}

export interface AndroidDiagnosticsCapability {
  list(): Promise<DiagnosticReport[]>;
  remove(id: string): Promise<boolean>;
  clear(): Promise<number>;
  share(payload: { fileName: string; text: string }): Promise<DiagnosticsShareOutcome>;
  /** The `api.diag.log` sink: persists a redacted runtime-error report. */
  log(entry: DiagLogEntry): void;
}

const RUNTIME_ID = /^runtime-[a-z0-9-]{1,40}$/;

/**
 * Host names, aliases and user names the app has seen this session (the SSH
 * form and imported 0.5.x hosts). Generic patterns cannot tell a single-label
 * alias such as `devbox` from prose, so these are blanked by name in every
 * report, at record time and again at read time.
 */
const knownTerms = new Set<string>();

export function rememberDiagnosticTerms(terms: readonly (string | null | undefined)[]): void {
  for (const term of terms) {
    const trimmed = term?.trim();
    if (trimmed && trimmed.length >= 3 && trimmed.length <= 253) knownTerms.add(trimmed);
  }
}

export function forgetDiagnosticTermsForTest(): void {
  knownTerms.clear();
}

function redactionOptions() {
  return { knownTerms: [...knownTerms] };
}
const NATIVE_PREFIX = 'native:';
const IMPORTED_PREFIX = 'imported:';

function browserStorage(): DiagnosticsStorage | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage;
  } catch {
    return null;
  }
}

function readJsonArray(storage: DiagnosticsStorage | null, key: string): unknown[] {
  if (!storage) return [];
  try {
    const parsed: unknown = JSON.parse(storage.getItem(key) ?? '[]');
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function writeJson(storage: DiagnosticsStorage | null, key: string, value: unknown): void {
  if (!storage) return;
  try {
    storage.setItem(key, JSON.stringify(value));
  } catch {
    // Diagnostics are best-effort; a full or denied store never affects the app.
  }
}

function isStoredRuntimeReport(value: unknown): value is DiagnosticReport {
  if (typeof value !== 'object' || value === null) return false;
  const report = value as Record<string, unknown>;
  return typeof report.id === 'string' && RUNTIME_ID.test(report.id) && report.source === 'runtime-error'
    && (report.at === null || typeof report.at === 'number') && typeof report.title === 'string' && typeof report.body === 'string';
}

/** Read the 0.5.x crash-report and diagnostic-history assets the import staged in IndexedDB. */
export async function readStagedLegacyReportAssets(factory: IDBFactory | undefined = globalThis.indexedDB): Promise<ImportedReportAsset[]> {
  if (!factory) return [];
  const database = await new Promise<IDBDatabase | null>((resolve) => {
    const request = factory.open(IMPORT_DATABASE_NAME);
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => resolve(null);
    request.onupgradeneeded = () => {
      // No import has run: abort instead of creating an empty database.
      request.transaction?.abort();
    };
  });
  if (!database) return [];
  try {
    if (!database.objectStoreNames.contains(IMPORT_ASSET_STORE)) return [];
    const rows = await new Promise<unknown[]>((resolve, reject) => {
      const request = database.transaction(IMPORT_ASSET_STORE, 'readonly').objectStore(IMPORT_ASSET_STORE).getAll();
      request.onsuccess = () => resolve(request.result as unknown[]);
      request.onerror = () => reject(request.error);
    });
    const assets: ImportedReportAsset[] = [];
    for (const row of rows) {
      if (typeof row !== 'object' || row === null) continue;
      const record = row as Record<string, unknown>;
      if (record.category !== 'crash-report' && record.category !== 'diagnostic-history') continue;
      if (typeof record.assetId !== 'string' || typeof record.relativePath !== 'string' || !(record.content instanceof Blob)) continue;
      assets.push({ assetId: record.assetId, category: record.category, relativePath: record.relativePath, text: await record.content.text() });
    }
    return assets;
  } finally {
    database.close();
  }
}

/** Share through the Android share sheet when the WebView supports files, else download. */
export async function shareDiagnosticsFile(file: { fileName: string; text: string }): Promise<DiagnosticsShareOutcome> {
  const blob = new File([file.text], file.fileName, { type: 'text/plain' });
  try {
    if (typeof navigator.share === 'function' && typeof navigator.canShare === 'function' && navigator.canShare({ files: [blob] })) {
      await navigator.share({ title: 'PocketShell diagnostics', files: [blob] });
      return 'shared';
    }
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') return 'cancelled';
  }
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = file.fileName;
  link.click();
  URL.revokeObjectURL(url);
  return 'saved';
}

export function createAndroidDiagnostics(deps: AndroidDiagnosticsDependencies = {}): AndroidDiagnosticsCapability {
  const storage = deps.storage === undefined ? browserStorage() : deps.storage;
  const native = deps.nativeCrashes ?? nativeCrashReports;
  const readImported = deps.readImportedAssets ?? (() => readStagedLegacyReportAssets());
  const shareFile = deps.shareFile ?? shareDiagnosticsFile;
  const now = deps.now ?? Date.now;
  const appVersion = deps.appVersion ?? (() => 'unknown');

  const runtimeReports = () => readJsonArray(storage, RUNTIME_REPORTS_STORAGE_KEY).filter(isStoredRuntimeReport);
  const hiddenImported = () => new Set(readJsonArray(storage, HIDDEN_IMPORTED_REPORTS_STORAGE_KEY).filter((id): id is string => typeof id === 'string'));

  async function nativeReports(): Promise<DiagnosticReport[]> {
    try {
      const { reports } = await native.list();
      return reports.map((file) => {
        const parsed = legacyCrashReport(`${NATIVE_PREFIX}${file.id}`, file.fileName, file.text, redactionOptions());
        return { ...parsed, source: 'native-crash' as const, title: parsed.title.replace('0.5.x crash report', 'Native crash') };
      });
    } catch {
      return [];
    }
  }

  async function importedReports(): Promise<DiagnosticReport[]> {
    try {
      const hidden = hiddenImported();
      return (await readImported())
        .filter((asset) => !hidden.has(`${IMPORTED_PREFIX}${asset.assetId}`))
        .map((asset) => asset.category === 'diagnostic-history'
          ? legacyDiagnosticHistoryReport(`${IMPORTED_PREFIX}${asset.assetId}`, asset.text, redactionOptions())
          : legacyCrashReport(`${IMPORTED_PREFIX}${asset.assetId}`, asset.relativePath, asset.text, redactionOptions()));
    } catch {
      return [];
    }
  }

  return {
    async list() {
      const [nativeList, importedList] = await Promise.all([nativeReports(), importedReports()]);
      const runtime = runtimeReports().map((report) => ({ ...report, body: redactDiagnosticText(report.body, redactionOptions()) }));
      return sortDiagnosticReports([...runtime, ...nativeList, ...importedList]);
    },
    async remove(id: string) {
      if (id.startsWith(NATIVE_PREFIX)) {
        const result = await native.remove({ id: id.slice(NATIVE_PREFIX.length) });
        return result.removed;
      }
      if (id.startsWith(IMPORTED_PREFIX)) {
        const hidden = hiddenImported();
        if (hidden.has(id) || !(await importedReports()).some((report) => report.id === id)) return false;
        writeJson(storage, HIDDEN_IMPORTED_REPORTS_STORAGE_KEY, [...hidden, id]);
        return true;
      }
      const current = runtimeReports();
      const next = current.filter((report) => report.id !== id);
      if (next.length === current.length) return false;
      writeJson(storage, RUNTIME_REPORTS_STORAGE_KEY, next);
      return true;
    },
    async clear() {
      const runtimeCount = runtimeReports().length;
      storage?.removeItem(RUNTIME_REPORTS_STORAGE_KEY);
      let nativeCount = 0;
      try {
        nativeCount = (await native.clear()).removed;
      } catch {
        nativeCount = 0;
      }
      const imported = await importedReports();
      writeJson(storage, HIDDEN_IMPORTED_REPORTS_STORAGE_KEY, [...hiddenImported(), ...imported.map((report) => report.id)]);
      return runtimeCount + nativeCount + imported.length;
    },
    share(payload) {
      return shareFile(payload);
    },
    log(entry: DiagLogEntry) {
      const at = now();
      const error = Object.assign(new Error(entry.message), { name: entry.errorName ?? 'Error', stack: entry.stack ?? '' });
      const report = runtimeErrorReport({
        id: `runtime-${at.toString(36)}-${Math.random().toString(36).slice(2, 8)}`,
        at,
        kind: entry.kind,
        error,
        appVersion: appVersion(),
        knownTerms: [...knownTerms],
      });
      writeJson(storage, RUNTIME_REPORTS_STORAGE_KEY, [...runtimeReports(), report].slice(-MAX_DIAGNOSTIC_REPORTS));
    },
  };
}
