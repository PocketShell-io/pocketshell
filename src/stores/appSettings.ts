import { defineStore } from 'pinia';
import { parseFontSize } from '@ui/fonts';
import { parseThemeChoice, THEME_CHOICE_DEFAULT } from '@ui/themes';

/**
 * Phone-route preferences not yet owned by the shared settings store (theme,
 * text size). Background grace and reconnect-on-return are NOT here: their one
 * owner is the shared settings store (core packages/ui `stores/settings.ts`,
 * key `pocketshell.settings.v1`, D42), which the Connections group edits and
 * the Android lifecycle reads.
 */
export const SETTINGS_STORAGE_KEY = 'pocketshell.js.settings.v1';

export interface AppSettings {
  themeChoice: string;
  terminalFontSize: number;
}

export const DEFAULT_APP_SETTINGS: Readonly<AppSettings> = {
  themeChoice: THEME_CHOICE_DEFAULT,
  terminalFontSize: 16,
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

export function parseAppSettings(raw: unknown): AppSettings {
  if (typeof raw !== 'object' || raw === null) return { ...DEFAULT_APP_SETTINGS };
  const input = raw as Record<string, unknown>;
  return {
    themeChoice: parseThemeChoice(input.themeChoice) ?? DEFAULT_APP_SETTINGS.themeChoice,
    terminalFontSize: parseFontSize(input.terminalFontSize) ?? DEFAULT_APP_SETTINGS.terminalFontSize,
  };
}

export function readAppSettings(storage: SettingsStorage | null = browserStorage()): AppSettings {
  if (!storage) return { ...DEFAULT_APP_SETTINGS };
  try {
    const serialized = storage.getItem(SETTINGS_STORAGE_KEY);
    return parseAppSettings(serialized === null ? null : JSON.parse(serialized));
  } catch {
    return { ...DEFAULT_APP_SETTINGS };
  }
}

export function persistAppSettings(settings: AppSettings, storage: SettingsStorage | null = browserStorage()): void {
  if (!storage) return;
  try {
    storage.setItem(SETTINGS_STORAGE_KEY, JSON.stringify(parseAppSettings(settings)));
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
    persist() {
      persistAppSettings(this.$state);
    },
  },
});
