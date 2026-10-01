import { defineStore } from 'pinia';
import { parseFontSize } from '@ui/fonts';
import { parseThemeChoice, THEME_CHOICE_DEFAULT } from '@ui/themes';

/**
 * Phone-route preferences not owned by the shared settings store (theme,
 * text size, and the Android-only dictation language and silence window).
 * Background grace and reconnect-on-return are NOT here: their one
 * owner is the shared settings store (core packages/ui `stores/settings.ts`,
 * key `pocketshell.settings.v1`, D42), which the Connections group edits and
 * the Android lifecycle reads.
 */
export const SETTINGS_STORAGE_KEY = 'pocketshell.js.settings.v1';

export const VOICE_LANGUAGE_AUTO = 'auto' as const;
export const VOICE_LANGUAGE_OPTIONS = [
  { code: VOICE_LANGUAGE_AUTO, label: 'Auto-detect' },
  { code: 'en', label: 'English' },
  { code: 'ru', label: 'Russian' },
  { code: 'de', label: 'German' },
  { code: 'fr', label: 'French' },
  { code: 'es', label: 'Spanish' },
] as const;
export type VoiceLanguageCode = typeof VOICE_LANGUAGE_OPTIONS[number]['code'];

export const VOICE_SILENCE_DEFAULT_SECONDS = 4;
export const VOICE_SILENCE_MIN_SECONDS = 2;
export const VOICE_SILENCE_MAX_SECONDS = 60;

export interface AppSettings {
  themeChoice: string;
  terminalFontSize: number;
  voiceLanguage: VoiceLanguageCode;
  voiceSilenceSeconds: number;
}

export const DEFAULT_APP_SETTINGS: Readonly<AppSettings> = {
  themeChoice: THEME_CHOICE_DEFAULT,
  terminalFontSize: 16,
  voiceLanguage: VOICE_LANGUAGE_AUTO,
  voiceSilenceSeconds: VOICE_SILENCE_DEFAULT_SECONDS,
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

export function normalizeVoiceLanguage(value: unknown): VoiceLanguageCode {
  if (typeof value !== 'string') return VOICE_LANGUAGE_AUTO;
  const language = value.trim().toLowerCase();
  const direct = VOICE_LANGUAGE_OPTIONS.find((option) => option.code === language)?.code;
  if (direct) return direct;
  // Preserve supported BCP-47 settings saved by the earlier JS rewrite when
  // moving to the compact language picker (for example, de-DE becomes de).
  const baseLanguage = language.split('-', 1)[0];
  return VOICE_LANGUAGE_OPTIONS.find((option) => option.code === baseLanguage)?.code ?? VOICE_LANGUAGE_AUTO;
}

export function normalizeVoiceSilenceSeconds(value: unknown): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) return VOICE_SILENCE_DEFAULT_SECONDS;
  return Math.min(VOICE_SILENCE_MAX_SECONDS,
    Math.max(VOICE_SILENCE_MIN_SECONDS, Math.round(value)));
}

export function parseAppSettings(raw: unknown): AppSettings {
  if (typeof raw !== 'object' || raw === null) return { ...DEFAULT_APP_SETTINGS };
  const input = raw as Record<string, unknown>;
  return {
    themeChoice: parseThemeChoice(input.themeChoice) ?? DEFAULT_APP_SETTINGS.themeChoice,
    terminalFontSize: parseFontSize(input.terminalFontSize) ?? DEFAULT_APP_SETTINGS.terminalFontSize,
    voiceLanguage: normalizeVoiceLanguage(input.voiceLanguage ?? input.dictationLanguageTag),
    voiceSilenceSeconds: normalizeVoiceSilenceSeconds(
      input.voiceSilenceSeconds
        ?? (typeof input.dictationSilenceWindowMs === 'number' ? input.dictationSilenceWindowMs / 1_000 : undefined),
    ),
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
    setVoiceLanguage(language: unknown) {
      this.voiceLanguage = normalizeVoiceLanguage(language);
      this.persist();
    },
    setVoiceSilenceSeconds(seconds: unknown) {
      this.voiceSilenceSeconds = normalizeVoiceSilenceSeconds(seconds);
      this.persist();
    },
    persist() {
      persistAppSettings(this.$state);
    },
  },
});
