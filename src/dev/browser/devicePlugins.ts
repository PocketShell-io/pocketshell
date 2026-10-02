/**
 * Browser dev mode (#3022): stand-ins for the Android-only plugins that are
 * not SSH — bridge ping, durable storage, keyboard insets, @capacitor/app,
 * crash reports and the 0.5.x installed-data migration.
 */
import type { DevPlugin, FakeNativeBridge } from './nativeBridge';
import { DevPluginError } from './nativeBridge';

export const DEV_APP_INFO = {
  name: 'PocketShell (browser dev)',
  id: 'com.pocketshell.app.dev',
  build: '0',
  version: '0.6.0-dev',
};

/** `BridgeReady.ping`: answered at once, the page owns its channel. */
export function createBridgeReadyPlugin(): DevPlugin {
  return { methods: { ping: (options) => ({ requestId: options.requestId }) } };
}

type StorageLike = Pick<Storage, 'length' | 'key' | 'getItem'>;

/**
 * `DurableStorage.open`: the browser's localStorage is already durable, so
 * the committed entries are exactly what localStorage holds and every write
 * commits immediately (the writer returns '' = durable).
 */
export function createDurableStoragePlugin(storage: StorageLike): DevPlugin {
  return {
    methods: {
      open: () => {
        const entries: Record<string, string> = {};
        for (let index = 0; index < storage.length; index += 1) {
          const key = storage.key(index);
          if (key === null) continue;
          const value = storage.getItem(key);
          if (value !== null) entries[key] = value;
        }
        return { token: `dev-${Date.now().toString(36)}`, initialized: true, entries };
      },
    },
  };
}

export const durableStorageWriter = {
  setItem: () => '',
  removeItem: () => '',
  clear: () => '',
  importAll: () => '',
};

export interface KeyboardSimulation {
  plugin: DevPlugin;
  /** Force the simulated IME up/down; `null` returns to focus-following. */
  override(visible: boolean | null): void;
  state(): { supported: boolean; imeVisible: boolean; safeBottomDp: number };
}

export function isEditableElement(element: Element | null): boolean {
  if (!element) return false;
  const tag = element.tagName.toLowerCase();
  if (tag === 'textarea') return true;
  if (tag === 'input') {
    const type = (element.getAttribute('type') ?? 'text').toLowerCase();
    return !['button', 'checkbox', 'radio', 'submit', 'reset', 'file', 'range', 'color', 'image', 'hidden'].includes(type);
  }
  return (element as HTMLElement).isContentEditable === true;
}

/**
 * `KeyboardInsets`: a desktop browser has no soft keyboard, so the IME is
 * "up" while an editable element has focus, like a phone keyboard. The
 * dev toolbar can force it either way.
 */
export function createKeyboardSimulation(
  bridge: () => FakeNativeBridge,
  doc: Pick<Document, 'addEventListener' | 'activeElement'> & { activeElement: Element | null },
): KeyboardSimulation {
  let forced: boolean | null = null;
  let lastVisible = false;
  const state = () => ({
    supported: true,
    imeVisible: forced ?? isEditableElement(doc.activeElement),
    safeBottomDp: 0,
  });
  const publish = () => {
    const next = state();
    if (next.imeVisible === lastVisible) return;
    lastVisible = next.imeVisible;
    bridge().emit('KeyboardInsets', 'imeInsetsChanged', next);
  };
  // focusout fires before the next element is focused; settle after the move.
  doc.addEventListener('focusin', () => publish());
  doc.addEventListener('focusout', () => setTimeout(publish, 0));
  return {
    plugin: {
      methods: {
        getState: () => state(),
        hideIme: () => {
          const active = doc.activeElement as HTMLElement | null;
          if (active && isEditableElement(active)) active.blur();
          forced = null;
          setTimeout(publish, 0);
          return undefined;
        },
      },
    },
    override(visible) {
      forced = visible;
      publish();
    },
    state,
  };
}

/** `@capacitor/app` with the Android back button driven from the dev toolbar. */
export function createAppPlugin(
  bridge: () => FakeNativeBridge,
  doc: Pick<Document, 'addEventListener' | 'hidden'>,
): DevPlugin & { back(): number } {
  doc.addEventListener('visibilitychange', () => {
    const isActive = !doc.hidden;
    bridge().emit('App', 'appStateChange', { isActive });
    bridge().emit('App', isActive ? 'resume' : 'pause', {});
  });
  return {
    methods: {
      getInfo: () => ({ ...DEV_APP_INFO }),
      getState: () => ({ isActive: !doc.hidden }),
      getLaunchUrl: () => ({ url: '' }),
      exitApp: () => {
        console.info('[dev] App.exitApp() — ignored in the browser.');
        return undefined;
      },
      minimizeApp: () => undefined,
      toggleBackButtonHandler: () => undefined,
    },
    back: () => bridge().emit('App', 'backButton', { canGoBack: false }),
  };
}

export interface CrashReportStore {
  plugin: DevPlugin;
  add(text: string): string;
}

/** `NativeCrashReports`: an in-memory list (nothing native can crash here). */
export function createCrashReportStore(seed: string[] = []): CrashReportStore {
  const reports: Array<{ id: string; fileName: string; text: string }> = [];
  let counter = 0;
  const add = (text: string) => {
    const stamp = new Date().toISOString().replace(/[-:]/gu, '').replace('T', '-').replace(/\..*/u, '');
    counter += 1;
    const id = `${stamp.slice(0, 8)}-${stamp.slice(9, 15)}-${String(counter).padStart(3, '0')}`;
    reports.push({ id, fileName: `${id}.txt`, text });
    return id;
  };
  for (const text of seed) add(text);
  return {
    add,
    plugin: {
      methods: {
        list: () => ({ reports: reports.map((report) => ({ ...report })) }),
        remove: (options) => {
          const index = reports.findIndex((report) => report.id === options.id);
          if (index >= 0) reports.splice(index, 1);
          return { id: options.id, removed: index >= 0 };
        },
        clear: () => {
          const removed = reports.length;
          reports.splice(0);
          return { removed };
        },
      },
    },
  };
}

/** `InstalledDataMigration`: a fresh install — no 0.5.x data to carry over. */
export function createInstalledDataMigrationPlugin(pixelRatio: () => number): DevPlugin {
  return {
    methods: {
      readLegacyInstalledData: () => ({
        schemaVersion: 1,
        environment: { applicationId: DEV_APP_INFO.id, displayDensity: pixelRatio() },
        database: { present: false, tables: {} },
        preferences: {},
        encryptedPreferences: {},
        assets: [],
        nativeFiles: [],
      }),
      readAssetChunk: () => {
        throw new DevPluginError('Browser dev mode has no 0.5.x assets.', 'NOT_FOUND');
      },
    },
  };
}
