import { describe, expect, it } from 'vitest';
import {
  BACKGROUND_GRACE_OPTIONS,
  DEFAULT_APP_SETTINGS,
  SETTINGS_STORAGE_KEY,
  SHARED_SETTINGS_STORAGE_KEY,
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
      backgroundGraceMs: BACKGROUND_GRACE_OPTIONS[0]!.milliseconds,
      reconnectOnReturn: false,
    };

    persistAppSettings(settings, storage);

    expect(JSON.parse(storage.getItem(SETTINGS_STORAGE_KEY) ?? '{}')).toEqual({ themeChoice: 'nord', terminalFontSize: 19 });
    expect(JSON.parse(storage.getItem(SHARED_SETTINGS_STORAGE_KEY) ?? '{}')).toEqual({ backgroundGraceMs: 30_000, reconnectOnReturn: false });
    expect(readAppSettings(storage)).toEqual(settings);
  });

  it('degrades malformed or unsupported saved values to safe defaults', () => {
    expect(parseAppSettings({
      themeChoice: 'url(javascript:alert(1))',
      terminalFontSize: 'not-a-number',
      backgroundGraceMs: 999_999_999,
      host: 'secret.example',
      privateKey: 'private data',
    })).toEqual(DEFAULT_APP_SETTINGS);
  });

  it('clamps terminal size using the pinned desktop font-size policy', () => {
    expect(parseAppSettings({ terminalFontSize: 4 }).terminalFontSize).toBe(8);
    expect(parseAppSettings({ terminalFontSize: 400 }).terminalFontSize).toBe(32);
  });

  it('offers every 0.5.x grace window from the shared core policy, including 10 minutes', () => {
    expect(BACKGROUND_GRACE_OPTIONS.map((option) => option.milliseconds)).toEqual([30_000, 60_000, 90_000, 300_000, 600_000]);
    expect(parseAppSettings({ backgroundGraceMs: 600_000 }).backgroundGraceMs).toBe(600_000);
    expect(parseAppSettings({ backgroundGraceMs: 60_000 }).backgroundGraceMs).toBe(60_000);
    expect(parseAppSettings({ backgroundGraceMs: 45_000 }).backgroundGraceMs).toBe(DEFAULT_APP_SETTINGS.backgroundGraceMs);
    expect(DEFAULT_APP_SETTINGS.reconnectOnReturn).toBe(true);
    expect(parseAppSettings({ reconnectOnReturn: false }).reconnectOnReturn).toBe(false);
    expect(parseAppSettings({ reconnectOnReturn: 'no' }).reconnectOnReturn).toBe(true);
  });

  it('keeps grace and reconnect-on-return in the one shared settings store (D42)', () => {
    const storage = new MemoryStorage();
    storage.setItem(SHARED_SETTINGS_STORAGE_KEY, JSON.stringify({ theme: 'nord', usageWarnPercent: 70, backgroundGraceMs: 600_000, reconnectOnReturn: false }));
    storage.setItem(SETTINGS_STORAGE_KEY, JSON.stringify({ terminalFontSize: 18, backgroundGraceMs: 30_000, reconnectOnReturn: true }));
    const read = readAppSettings(storage);
    expect(read).toMatchObject({ terminalFontSize: 18, backgroundGraceMs: 600_000, reconnectOnReturn: false });

    persistAppSettings({ ...read, backgroundGraceMs: 60_000 }, storage);
    expect(JSON.parse(storage.getItem(SHARED_SETTINGS_STORAGE_KEY) ?? '{}')).toEqual({
      theme: 'nord', usageWarnPercent: 70, backgroundGraceMs: 60_000, reconnectOnReturn: false,
    });
    expect(JSON.parse(storage.getItem(SETTINGS_STORAGE_KEY) ?? '{}')).not.toHaveProperty('backgroundGraceMs');

    const legacy = new MemoryStorage();
    legacy.setItem(SETTINGS_STORAGE_KEY, JSON.stringify({ backgroundGraceMs: 300_000, reconnectOnReturn: false }));
    expect(readAppSettings(legacy)).toMatchObject({ backgroundGraceMs: 300_000, reconnectOnReturn: false });
  });
});
