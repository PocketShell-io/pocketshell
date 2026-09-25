import { describe, expect, it } from 'vitest';
import {
  BACKGROUND_GRACE_OPTIONS,
  DEFAULT_APP_SETTINGS,
  SETTINGS_STORAGE_KEY,
  parseAppSettings,
  persistAppSettings,
  readAppSettings,
  type AppSettings,
  type SettingsStorage,
} from '../../src/stores/appSettings';

class MemoryStorage implements SettingsStorage {
  values = new Map<string, string>();
  getItem(key: string) { return this.values.get(key) ?? null; }
  setItem(key: string, value: string) { this.values.set(key, value); }
}

describe('mobile app settings', () => {
  it('reads and persists shared appearance, connection, and dictation preferences', () => {
    const storage = new MemoryStorage();
    const settings: AppSettings = {
      themeChoice: 'nord',
      terminalFontSize: 19,
      backgroundGraceMs: BACKGROUND_GRACE_OPTIONS[0].milliseconds,
      dictationLanguageTag: 'de-DE',
      dictationSilenceWindowMs: 12_000,
    };

    persistAppSettings(settings, storage);

    expect(storage.getItem(SETTINGS_STORAGE_KEY)).toBe(JSON.stringify(settings));
    expect(readAppSettings(storage)).toEqual(settings);
  });

  it('degrades malformed or unsupported saved values to safe defaults', () => {
    expect(parseAppSettings({
      themeChoice: 'url(javascript:alert(1))',
      terminalFontSize: 'not-a-number',
      backgroundGraceMs: 999_999_999,
      dictationLanguageTag: 'en_US',
      dictationSilenceWindowMs: '12 seconds',
      host: 'secret.example',
      privateKey: 'private data',
    })).toEqual(DEFAULT_APP_SETTINGS);
    expect(readAppSettings({
      getItem: () => '{malformed-json',
      setItem: () => {},
    })).toEqual(DEFAULT_APP_SETTINGS);
  });

  it('clamps terminal size using the pinned desktop font-size policy', () => {
    expect(parseAppSettings({ terminalFontSize: 4 }).terminalFontSize).toBe(8);
    expect(parseAppSettings({ terminalFontSize: 400 }).terminalFontSize).toBe(32);
  });

  it('normalizes valid language hints and applies the adapter silence bounds', () => {
    expect(parseAppSettings({ dictationLanguageTag: ' zh-Hant-TW ' }).dictationLanguageTag).toBe('zh-Hant-TW');
    expect(parseAppSettings({ dictationLanguageTag: 'AUTO' }).dictationLanguageTag).toBe('auto');
    expect(parseAppSettings({ dictationSilenceWindowMs: 1_000 }).dictationSilenceWindowMs).toBe(2_000);
    expect(parseAppSettings({ dictationSilenceWindowMs: 90_000 }).dictationSilenceWindowMs).toBe(60_000);
    expect(parseAppSettings({ dictationSilenceWindowMs: 8_500 }).dictationSilenceWindowMs).toBe(8_500);
  });
});
