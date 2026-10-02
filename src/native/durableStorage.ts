import { Capacitor, registerPlugin } from '@capacitor/core';
import { appendDiagnosticEvent, type DiagnosticStorage } from '../diagnostics';

/**
 * Android platform storage seam for user data (issue #2993).
 *
 * Shared core/ui code and this app's stores persist through the standard Web
 * Storage API (`localStorage`), which keeps them platform-neutral (D42): web
 * and desktop keep their own localStorage, unchanged. Android WebView, however,
 * holds localStorage writes in memory and commits them to disk later, so a
 * swipe-away, force-stop or low-memory kill shortly after a write loses it.
 *
 * On Android this module makes that same API durable before anything else
 * runs:
 *  - `open` (an origin-restricted Capacitor call) returns the committed entries
 *    and a per-page write token;
 *  - the first time, the WebView's existing localStorage is imported once and
 *    the native store becomes authoritative;
 *  - afterwards localStorage is reconciled to the committed entries at launch,
 *    so a write or removal the WebView lost to a kill is restored;
 *  - `setItem`/`removeItem`/`clear` on `localStorage` (not `sessionStorage`)
 *    then also commit synchronously to native storage before returning.
 *    A failed commit rolls the localStorage change back and throws, so a write
 *    is never acknowledged without being durable.
 * Reads stay plain, synchronous localStorage reads of the hydrated cache.
 */

export const DURABLE_STORAGE_INTERFACE = 'PocketShellDurableStorage';

/** Synchronous native writer; each call returns '' once durable, else an error. */
export interface DurableStorageWriter {
  setItem(token: string, key: string, value: string): string;
  removeItem(token: string, key: string): string;
  clear(token: string): string;
  importAll(token: string, entriesJson: string): string;
}

export interface DurableStorageOpenResult {
  token: string;
  initialized: boolean;
  entries: Record<string, string>;
}

interface DurableStoragePlugin {
  open(): Promise<DurableStorageOpenResult>;
}

export type DurableStorageStatus =
  | { state: 'native-durable'; imported: number; restored: number; removed: number }
  | { state: 'browser-only'; reason: string }
  | { state: 'failed'; reason: string };

type StorageMethods = Pick<Storage, 'getItem' | 'setItem' | 'removeItem' | 'clear' | 'key' | 'length'>;

interface StoragePrototype {
  getItem(this: StorageMethods, key: string): string | null;
  setItem(this: StorageMethods, key: string, value: string): void;
  removeItem(this: StorageMethods, key: string): void;
  clear(this: StorageMethods): void;
}

export interface DurableStorageDependencies {
  /** The storage area to make durable (the WebView's localStorage). */
  storage: StorageMethods;
  /** Where storage methods live (Storage.prototype); patched for `storage` only. */
  prototype: StoragePrototype;
  writer: DurableStorageWriter | undefined;
  open: () => Promise<DurableStorageOpenResult>;
  /** Give up on `open` after this long (default DURABLE_STORAGE_OPEN_TIMEOUT_MS). */
  openTimeoutMs?: number;
}

/**
 * Upper bound for the launch-time `open`. Startup waits on it, so an
 * unanswered native call must become a recorded failure, not a blank screen.
 */
export const DURABLE_STORAGE_OPEN_TIMEOUT_MS = 8_000;

function bounded<T>(promise: Promise<T>, ms: number): Promise<T> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`The phone's app storage did not answer within ${ms} ms.`)), ms);
    promise.then(
      (value) => { clearTimeout(timer); resolve(value); },
      (error: unknown) => { clearTimeout(timer); reject(error); },
    );
  });
}

export class DurableStorageError extends Error {
  constructor(operation: string, detail: string) {
    super(`Saved data could not be written durably (${operation}): ${detail}`);
    this.name = 'DurableStorageError';
  }
}

const DurableStorage = registerPlugin<DurableStoragePlugin>('DurableStorage');

function snapshot(storage: StorageMethods, original: StoragePrototype): Record<string, string> {
  const entries: Record<string, string> = {};
  for (let index = 0; index < storage.length; index += 1) {
    const key = storage.key(index);
    if (key === null) continue;
    const value = original.getItem.call(storage, key);
    if (value !== null) entries[key] = value;
  }
  return entries;
}

function isEntries(value: unknown): value is Record<string, string> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    && Object.values(value).every((entry) => typeof entry === 'string');
}

/**
 * Hydrate `storage` from the durable store and route its writes through it.
 * Must complete before any module reads user data from localStorage.
 */
export async function installDurableStorage(dependencies: DurableStorageDependencies): Promise<DurableStorageStatus> {
  const { storage, prototype, writer } = dependencies;
  if (!writer) return { state: 'failed', reason: 'The phone\'s app storage cannot save changes.' };

  let opened: DurableStorageOpenResult;
  try {
    opened = await bounded(dependencies.open(), dependencies.openTimeoutMs ?? DURABLE_STORAGE_OPEN_TIMEOUT_MS);
  } catch (error) {
    return { state: 'failed', reason: error instanceof Error ? error.message : String(error) };
  }
  if (typeof opened?.token !== 'string' || opened.token.length === 0 || !isEntries(opened.entries)) {
    return { state: 'failed', reason: 'The phone\'s app storage returned unreadable data.' };
  }
  const token = opened.token;
  const original: StoragePrototype = {
    getItem: prototype.getItem,
    setItem: prototype.setItem,
    removeItem: prototype.removeItem,
    clear: prototype.clear,
  };

  let imported = 0;
  let restored = 0;
  let removed = 0;
  if (!opened.initialized) {
    const existing = snapshot(storage, original);
    const error = writer.importAll(token, JSON.stringify(existing));
    if (error) return { state: 'failed', reason: `Existing data could not be imported: ${error}` };
    imported = Object.keys(existing).length;
  } else {
    const durable = opened.entries;
    const current = snapshot(storage, original);
    for (const key of Object.keys(current)) {
      if (!Object.prototype.hasOwnProperty.call(durable, key)) {
        original.removeItem.call(storage, key);
        removed += 1;
      }
    }
    for (const [key, value] of Object.entries(durable)) {
      if (current[key] === value) continue;
      try {
        original.setItem.call(storage, key, value);
        restored += 1;
      } catch (error) {
        return {
          state: 'failed',
          reason: `A saved entry could not be restored: ${error instanceof Error ? error.message : String(error)}`,
        };
      }
    }
  }

  prototype.setItem = function setItem(this: StorageMethods, key: string, value: string): void {
    if (this !== storage) {
      original.setItem.call(this, key, value);
      return;
    }
    const name = String(key);
    const text = String(value);
    const previous = original.getItem.call(this, name);
    original.setItem.call(this, name, text);
    const error = writer.setItem(token, name, text);
    if (error) {
      if (previous === null) original.removeItem.call(this, name);
      else original.setItem.call(this, name, previous);
      throw new DurableStorageError('write', error);
    }
  };
  prototype.removeItem = function removeItem(this: StorageMethods, key: string): void {
    if (this !== storage) {
      original.removeItem.call(this, key);
      return;
    }
    const name = String(key);
    const previous = original.getItem.call(this, name);
    if (previous === null) return;
    original.removeItem.call(this, name);
    const error = writer.removeItem(token, name);
    if (error) {
      original.setItem.call(this, name, previous);
      throw new DurableStorageError('remove', error);
    }
  };
  prototype.clear = function clear(this: StorageMethods): void {
    if (this !== storage) {
      original.clear.call(this);
      return;
    }
    const previous = snapshot(this, original);
    original.clear.call(this);
    const error = writer.clear(token);
    if (error) {
      for (const [key, value] of Object.entries(previous)) original.setItem.call(this, key, value);
      throw new DurableStorageError('clear', error);
    }
  };
  return { state: 'native-durable', imported, restored, removed };
}

let openCalls = 0;

/**
 * Native `open` calls made by this page. Startup asks exactly once and never
 * retries, so a lost reply cannot be masked by a second attempt (#3000).
 */
export function durableStorageOpenCalls(): number {
  return openCalls;
}

/** Android entry point; web/desktop builds of this shell keep plain localStorage. */
export async function installAndroidDurableStorage(): Promise<DurableStorageStatus> {
  if (Capacitor.getPlatform() !== 'android') {
    return { state: 'browser-only', reason: 'Not running inside the Android shell.' };
  }
  const status = await installDurableStorage({
    storage: globalThis.localStorage,
    prototype: Storage.prototype as unknown as StoragePrototype,
    writer: (globalThis as unknown as Record<string, DurableStorageWriter | undefined>)[DURABLE_STORAGE_INTERFACE],
    open: () => {
      openCalls += 1;
      return DurableStorage.open();
    },
  });
  if (status.state !== 'native-durable') {
    console.error(`PocketShell durable storage unavailable: ${status.reason}`);
  }
  return status;
}

/**
 * Expose the outcome to the page and, when Android could not make storage
 * durable, record it in Diagnostics so the failure is visible to the user
 * rather than only a console line.
 */
export function recordDurableStorageStatus(
  root: HTMLElement,
  status: DurableStorageStatus,
  diagnostics: DiagnosticStorage | null = typeof localStorage === 'undefined' ? null : localStorage,
): void {
  root.dataset.durableStorage = status.state;
  if (status.state === 'failed') {
    appendDiagnosticEvent({ kind: 'storage-durability-failed', operation: 'storage', code: 'UNAVAILABLE' }, diagnostics);
  }
  if (status.state === 'native-durable') {
    root.dataset.durableStorageImported = String(status.imported);
    root.dataset.durableStorageRestored = String(status.restored);
    root.dataset.durableStorageRemoved = String(status.removed);
  } else {
    root.dataset.durableStorageReason = status.reason;
  }
}
