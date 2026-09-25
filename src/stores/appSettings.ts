import { defineStore } from 'pinia';
import { parseFontSize, parseThemeChoice, THEME_CHOICE_DEFAULT } from '@pocketshell/ui';
import {
  DEFAULT_SPEECH_SILENCE_WINDOW_MS,
  sanitizeLanguageTag,
  sanitizeSilenceWindowMs,
} from '../native/speechRecognition';

export const SETTINGS_STORAGE_KEY = 'pocketshell.js.settings.v1';

export const BACKGROUND_GRACE_OPTIONS = [
  { milliseconds: 30_000, label: '30 seconds' },
  { milliseconds: 90_000, label: '90 seconds · recommended' },
  { milliseconds: 300_000, label: '5 minutes' },
] as const;

export interface AppSettings {
  themeChoice: string;
  terminalFontSize: number;
  backgroundGraceMs: number;
  dictationLanguageTag: string;
  dictationSilenceWindowMs: number;
}

export const DEFAULT_APP_SETTINGS: Readonly<AppSettings> = {
  themeChoice: THEME_CHOICE_DEFAULT,
  terminalFontSize: 16,
  backgroundGraceMs: 90_000,
  dictationLanguageTag: 'auto',
  dictationSilenceWindowMs: DEFAULT_SPEECH_SILENCE_WINDOW_MS,
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
  return BACKGROUND_GRACE_OPTIONS.some((option) => option.milliseconds === value);
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
    dictationLanguageTag: sanitizeLanguageTag(input.dictationLanguageTag) ?? DEFAULT_APP_SETTINGS.dictationLanguageTag,
    dictationSilenceWindowMs: sanitizeSilenceWindowMs(input.dictationSilenceWindowMs),
  };
}

export function readAppSettings(storage: SettingsStorage | null = browserStorage()): AppSettings {
  if (!storage) return { ...DEFAULT_APP_SETTINGS };
  try {
    const serialized = storage.getItem(SETTINGS_STORAGE_KEY);
    return serialized === null ? { ...DEFAULT_APP_SETTINGS } : parseAppSettings(JSON.parse(serialized));
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
    setBackgroundGraceMs(milliseconds: unknown) {
      if (!isGracePeriod(milliseconds)) return;
      this.backgroundGraceMs = milliseconds;
      this.persist();
    },
    setDictationLanguageTag(languageTag: unknown) {
      const parsed = sanitizeLanguageTag(languageTag);
      if (parsed === undefined) return;
      this.dictationLanguageTag = parsed;
      this.persist();
    },
    setDictationSilenceWindowMs(milliseconds: unknown) {
      if (typeof milliseconds !== 'number' || !Number.isFinite(milliseconds)) return;
      this.dictationSilenceWindowMs = sanitizeSilenceWindowMs(milliseconds);
      this.persist();
    },
    persist() {
      persistAppSettings(this.$state);
    },
  },
});
