import { readFileSync } from 'node:fs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { BACKGROUND_GRACE_OPTIONS, DEFAULT_RECONNECT_ON_RETURN } from '@pocketshell/core';
import {
  DEFAULT_APP_SETTINGS,
  SETTINGS_STORAGE_KEY,
  parseAppSettings,
  persistAppSettings,
  readAppSettings,
  type SettingsStorage,
} from '../../src/stores/appSettings';

const SHARED_SETTINGS_STORAGE_KEY = 'pocketshell.settings.v1';

class MemoryStorage implements SettingsStorage {
  values = new Map<string, string>();
  getItem(key: string) { return this.values.get(key) ?? null; }
  setItem(key: string, value: string) { this.values.set(key, value); }
}

const source = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8');

describe('mobile app settings', () => {
  afterEach(() => { vi.unstubAllGlobals(); });

  it('reads and persists only the phone theme, terminal text size and Android dictation settings', () => {
    const storage = new MemoryStorage();
    const settings = { themeChoice: 'nord', terminalFontSize: 19, voiceLanguage: 'ru', voiceSilenceSeconds: 8 } as const;
    persistAppSettings(settings, storage);
    expect(JSON.parse(storage.getItem(SETTINGS_STORAGE_KEY) ?? '{}')).toEqual(settings);
    expect(storage.getItem(SHARED_SETTINGS_STORAGE_KEY)).toBeNull();
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

  it('ignores the unreleased branch-only dictation keys (D22) and maps a BCP-47 tag to its base language', () => {
    expect(parseAppSettings({
      dictationLanguageTag: 'de-DE',
      dictationSilenceWindowMs: 9_000,
    })).toMatchObject({
      voiceLanguage: 'auto',
      voiceSilenceSeconds: 4,
    });
    expect(parseAppSettings({ voiceLanguage: 'de-DE' }).voiceLanguage).toBe('de');
  });

  it('uses the Kotlin-aligned automatic language and four-second silence defaults', () => {
    expect(DEFAULT_APP_SETTINGS.voiceLanguage).toBe('auto');
    expect(DEFAULT_APP_SETTINGS.voiceSilenceSeconds).toBe(4);
  });

  it('offers every 0.5.x grace window from the shared core policy, including 10 minutes', () => {
    expect(BACKGROUND_GRACE_OPTIONS.map((option) => option.milliseconds)).toEqual([30_000, 60_000, 90_000, 300_000, 600_000]);
    expect(DEFAULT_RECONNECT_ON_RETURN).toBe(true);
  });

  it('keeps grace and reconnect-on-return in the one shared settings store (D42)', async () => {
    const storage = new MemoryStorage();
    storage.setItem(SHARED_SETTINGS_STORAGE_KEY, JSON.stringify({ usageWarnPercent: 70, backgroundGraceMs: 600_000, reconnectOnReturn: false }));
    storage.setItem(SETTINGS_STORAGE_KEY, JSON.stringify({ terminalFontSize: 18 }));
    vi.stubGlobal('localStorage', storage);
    setActivePinia(createPinia());
    const { useSettingsStore } = await import('@ui/app/stores/settings');
    const shared = useSettingsStore();
    expect(shared.backgroundGraceMs).toBe(600_000);
    expect(shared.reconnectOnReturn).toBe(false);

    // What the shared Connections switch and grace select do on a tap.
    shared.set('reconnectOnReturn', true);
    shared.set('backgroundGraceMs', 60_000);
    expect(JSON.parse(storage.getItem(SHARED_SETTINGS_STORAGE_KEY) ?? '{}')).toMatchObject({
      usageWarnPercent: 70, backgroundGraceMs: 60_000, reconnectOnReturn: true,
    });
    expect(JSON.parse(storage.getItem(SETTINGS_STORAGE_KEY) ?? '{}')).toEqual({ terminalFontSize: 18 });
    expect(readAppSettings(storage)).not.toHaveProperty('backgroundGraceMs');
    expect(readAppSettings(storage)).not.toHaveProperty('reconnectOnReturn');

    // The phone Connections route renders core's group, not a local copy, and
    // the Android lifecycle reads the same store.
    const screen = source('../../src/components/SettingsScreen.vue');
    expect(screen).toContain("import SettingsConnectionsGroup from '@ui/app/components/settings/SettingsConnectionsGroup.vue';");
    expect(screen).toContain('<SettingsConnectionsGroup :show-title="false" />');
    expect(screen).not.toMatch(/setting-reconnect-on-return|setting-background-grace|role="switch"/);
    expect(source('../../src/styles.css')).not.toContain('settings-switch');
    const app = source('../../src/App.vue');
    expect(app).toContain('getBackgroundGraceMs: () => sharedSettings.backgroundGraceMs');
    expect(app).toContain('getReconnectOnReturn: () => sharedSettings.reconnectOnReturn');
    const lifecycle = source('../../src/session/appLifecycle.ts');
    expect(lifecycle).toContain('active.returnToForeground({ reconnect: getReconnectOnReturn() })');
    expect(lifecycle).toContain('active.enterBackground(getBackgroundGraceMs())');
  });
});
