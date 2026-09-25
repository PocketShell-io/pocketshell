import { describe, expect, it } from 'vitest';
import {
  BACKGROUND_GRACE_OPTIONS,
  DEFAULT_APP_SETTINGS,
  SETTINGS_STORAGE_KEY,
  parseAppSettings,
  persistAppSettings,
  readAppSettings,
  type SettingsStorage,
} from '../../src/stores/appSettings';

class MemoryStorage implements SettingsStorage {
  values = new Map<string, string>();
  getItem(key: string) { return this.values.get(key) ?? null; }
  setItem(key: string, value: string) { this.values.set(key, value); }
}

describe('mobile app settings', () => {
  it('reads and persists only shared theme/font policy and supported background grace values', () => {
    const storage = new MemoryStorage();
    const settings = {
      themeChoice: 'nord',
      terminalFontSize: 19,
      backgroundGraceMs: BACKGROUND_GRACE_OPTIONS[0].milliseconds,
      voiceLanguage: 'ru',
      voiceSilenceSeconds: 8,
    } as const;

    persistAppSettings(settings, storage);

    expect(storage.getItem(SETTINGS_STORAGE_KEY)).toBe(JSON.stringify(settings));
    expect(readAppSettings(storage)).toEqual(settings);
  });

  it('degrades malformed or unsupported saved values to safe defaults', () => {
    expect(parseAppSettings({
      themeChoice: 'url(javascript:alert(1))',
      terminalFontSize: 'not-a-number',
      backgroundGraceMs: 999_999_999,
      voiceLanguage: 'xx',
      voiceSilenceSeconds: Number.NaN,
      host: 'secret.example',
      privateKey: 'private data',
    })).toEqual(DEFAULT_APP_SETTINGS);
  });

  it('clamps terminal size using the pinned desktop font-size policy', () => {
    expect(parseAppSettings({ terminalFontSize: 4 }).terminalFontSize).toBe(8);
    expect(parseAppSettings({ terminalFontSize: 400 }).terminalFontSize).toBe(32);
  });

  it('persists a supported recognizer language and clamps the silence window to the 2–60 second range', () => {
    const storage = new MemoryStorage();
    const settings = parseAppSettings({ voiceLanguage: ' FR ', voiceSilenceSeconds: 78.2 });
    expect(settings.voiceLanguage).toBe('fr');
    expect(settings.voiceSilenceSeconds).toBe(60);
    persistAppSettings(settings, storage);
    expect(readAppSettings(storage)).toEqual(settings);
    expect(parseAppSettings({ voiceLanguage: 'unknown', voiceSilenceSeconds: 0 })).toMatchObject({
      voiceLanguage: 'auto',
      voiceSilenceSeconds: 2,
    });
  });

  it('preserves language and silence settings written by the earlier JS rewrite', () => {
    expect(parseAppSettings({
      dictationLanguageTag: 'de-DE',
      dictationSilenceWindowMs: 9_000,
    })).toMatchObject({
      voiceLanguage: 'de',
      voiceSilenceSeconds: 9,
    });
  });

  it('uses the Kotlin-aligned automatic language and four-second silence defaults', () => {
    expect(DEFAULT_APP_SETTINGS.voiceLanguage).toBe('auto');
    expect(DEFAULT_APP_SETTINGS.voiceSilenceSeconds).toBe(4);
  });
});
