import { defineStore } from 'pinia';
import {
  BACKGROUND_GRACE_OPTIONS as CORE_BACKGROUND_GRACE_OPTIONS,
  DEFAULT_BACKGROUND_GRACE_MS,
  DEFAULT_RECONNECT_ON_RETURN,
  parseBackgroundGraceMs,
  parseBoolean,
} from '@pocketshell/core';
import { parseFontSize } from '@ui/fonts';
import { parseThemeChoice, THEME_CHOICE_DEFAULT } from '@ui/themes';

/** Phone-route preferences not yet owned by the shared settings store (theme, text size). */
export const SETTINGS_STORAGE_KEY = 'pocketshell.js.settings.v1';
/**
 * The shared app's settings store (core packages/ui `stores/settings.ts`).
 * It is the ONE owner of background grace and reconnect-on-return (D42): the
 * Android lifecycle reads them from here, and the shared Settings screen and
 * the phone Connections route both edit them here.
 */
export const SHARED_SETTINGS_STORAGE_KEY = 'pocketshell.settings.v1';

/** Core's shared grace windows (30 s to 10 min), so every client offers the same list. */
export const BACKGROUND_GRACE_OPTIONS = CORE_BACKGROUND_GRACE_OPTIONS.map((option) => ({
  milliseconds: option.milliseconds,
  label: `${option.label} · ${option.detail}`,
}));

export interface AppSettings {
  themeChoice: string;
  terminalFontSize: number;
  backgroundGraceMs: number;
  /** 0.5.x `reconnect_when_return`; read by the foreground lifecycle handler. */
  reconnectOnReturn: boolean;
}

export const DEFAULT_APP_SETTINGS: Readonly<AppSettings> = {
  themeChoice: THEME_CHOICE_DEFAULT,
  terminalFontSize: 16,
  backgroundGraceMs: DEFAULT_BACKGROUND_GRACE_MS,
  reconnectOnReturn: DEFAULT_RECONNECT_ON_RETURN,
};

export interface SettingsStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}

function browserStorage(): SettingsStorage | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage;
  } catch {
    return null;
  }
}

function isGracePeriod(value: unknown): value is AppSettings['backgroundGraceMs'] {
  return typeof value === 'number' && parseBackgroundGraceMs(value) === value;
}

export function parseAppSettings(raw: unknown): AppSettings {
  if (typeof raw !== 'object' || raw === null) return { ...DEFAULT_APP_SETTINGS };
  const input = raw as Record<string, unknown>;
  return {
    themeChoice: parseThemeChoice(input.themeChoice) ?? DEFAULT_APP_SETTINGS.themeChoice,
    terminalFontSize: parseFontSize(input.terminalFontSize) ?? DEFAULT_APP_SETTINGS.terminalFontSize,
    backgroundGraceMs: isGracePeriod(input.backgroundGraceMs)
      ? input.backgroundGraceMs
      : DEFAULT_APP_SETTINGS.backgroundGraceMs,
    reconnectOnReturn: typeof input.reconnectOnReturn === 'boolean'
      ? input.reconnectOnReturn
      : DEFAULT_APP_SETTINGS.reconnectOnReturn,
  };
}

function readJsonObject(storage: SettingsStorage, key: string): Record<string, unknown> | null {
  try {
    const serialized = storage.getItem(key);
    if (serialized === null) return null;
    const parsed: unknown = JSON.parse(serialized);
    return typeof parsed === 'object' && parsed !== null && !Array.isArray(parsed) ? parsed as Record<string, unknown> : null;
  } catch {
    return null;
  }
}

export function readAppSettings(storage: SettingsStorage | null = browserStorage()): AppSettings {
  if (!storage) return { ...DEFAULT_APP_SETTINGS };
  const local = readJsonObject(storage, SETTINGS_STORAGE_KEY) ?? {};
  const shared = readJsonObject(storage, SHARED_SETTINGS_STORAGE_KEY) ?? {};
  const parsed = parseAppSettings(local);
  return {
    ...parsed,
    // Shared store first; a value an earlier build left in the phone blob is
    // carried over until the next write moves it.
    backgroundGraceMs: parseBackgroundGraceMs(shared.backgroundGraceMs) ?? parsed.backgroundGraceMs,
    reconnectOnReturn: parseBoolean(shared.reconnectOnReturn) ?? parsed.reconnectOnReturn,
  };
}

export function persistAppSettings(settings: AppSettings, storage: SettingsStorage | null = browserStorage()): void {
  if (!storage) return;
  const valid = parseAppSettings(settings);
  try {
    storage.setItem(SETTINGS_STORAGE_KEY, JSON.stringify({
      themeChoice: valid.themeChoice,
      terminalFontSize: valid.terminalFontSize,
    }));
    const shared = readJsonObject(storage, SHARED_SETTINGS_STORAGE_KEY) ?? {};
    storage.setItem(SHARED_SETTINGS_STORAGE_KEY, JSON.stringify({
      ...shared,
      backgroundGraceMs: valid.backgroundGraceMs,
      reconnectOnReturn: valid.reconnectOnReturn,
    }));
  } catch {
    // Private-mode or storage-quota failures leave this launch's settings usable.
  }
}

export const useAppSettings = defineStore('appSettings', {
  state: (): AppSettings => readAppSettings(),
  actions: {
    setThemeChoice(choice: unknown) {
      const parsed = parseThemeChoice(choice);
      if (parsed === undefined) return;
      this.themeChoice = parsed;
      this.persist();
    },
    setTerminalFontSize(size: unknown) {
      const parsed = parseFontSize(size);
      if (parsed === undefined) return;
      this.terminalFontSize = parsed;
      this.persist();
    },
    setBackgroundGraceMs(milliseconds: unknown) {
      if (!isGracePeriod(milliseconds)) return;
      this.backgroundGraceMs = milliseconds;
      this.persist();
    },
    setReconnectOnReturn(enabled: unknown) {
      if (typeof enabled !== 'boolean') return;
      this.reconnectOnReturn = enabled;
      this.persist();
    },
    persist() {
      persistAppSettings(this.$state);
    },
  },
});
