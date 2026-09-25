import { describe, expect, it, vi } from 'vitest';
import {
  InstalledDataMigrationError,
  IMPORT_RECORD_ID,
  SETTINGS_STORAGE_KEY,
  installedDataMigrationState,
  prepareLocalStorageWrites,
  readImportedLegacyHosts,
  runInstalledDataMigration,
  shouldReloadForImportedSettings,
  type ImportPersistence,
  type ImportRecord,
  type ImportedAsset,
  type StringStorage,
} from '../../src/migration/installedDataMigration';
import { makeLegacySshHostTarget } from '../../src/migration/legacySshTarget';
import type {
  NativeInstalledDataMigrationPlugin,
  NativeLegacySnapshot,
} from '../../src/native/installedDataMigration';

const TRUST_FINGERPRINT = `SHA256:${'A'.repeat(43)}`;
const ABC_SHA256 = 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad';

class MemoryStorage implements StringStorage {
  readonly values = new Map<string, string>();

  getItem(key: string): string | null {
    return this.values.get(key) ?? null;
  }

  setItem(key: string, value: string): void {
    this.values.set(key, value);
  }
}

function legacySnapshot(): NativeLegacySnapshot {
  return {
    schemaVersion: 1,
    environment: { applicationId: 'com.pocketshell.app', displayDensity: 2 },
    database: {
      present: true,
      version: 22,
      identityHash: '998a588a2d2f383454698ea11820fca9',
      tables: {
        hosts: [{
          id: 41,
          name: 'devbox',
          hostname: 'dev.example.test',
          port: 22,
          username: 'alex',
          keyId: 7,
          trustedHostKeyAlgorithm: 'SHA256',
          trustedHostKeySha256: TRUST_FINGERPRINT,
        }],
        ssh_keys: [{
          id: 7,
          name: 'main key',
          privateKeyPath: '/private/app/files/ssh-keys/main.pem',
          hasPassphrase: false,
        }],
      },
    },
    preferences: {
      composer_drafts: {
        present: true,
        entries: { 'host-41': { type: 'string', value: 'keep this draft' } },
      },
      next_settings: {
        present: true,
        entries: {
          terminal_text_size_px: { type: 'int', value: 32 },
          background_grace_millis: { type: 'long', value: '90000' },
          default_host_id: { type: 'long', value: '41' },
          voice_language: { type: 'string', value: 'de' },
          voice_silence_seconds: { type: 'float', value: 8 },
        },
      },
      app_settings: { present: false, entries: {} },
      next_sync_selection: {
        present: true,
        entries: {
          sync_selected_hosts: { type: 'string', value: '["work-alias"]' },
          sync_future_field: { type: 'string', value: '{"kept":true}' },
        },
      },
      workspace_order: { present: false, entries: {} },
      port_forward_panel: { present: false, entries: {} },
      update_check: { present: false, entries: {} },
    },
    encryptedPreferences: {
      'pocketshell-sync-auth': { present: false, status: 'absent', keys: [] },
      'pocketshell-voice-secrets': { present: false, status: 'absent', keys: [] },
      'pocketshell-assistant-secrets': { present: false, status: 'absent', keys: [] },
    },
    assets: [],
    nativeFiles: [{
      category: 'ssh-private-key',
      relativePath: 'ssh-keys/main.pem',
      byteLength: 1536,
      lastModified: 1_700_000_000_000,
      keyId: 7,
      sha256: 'a'.repeat(64),
    }],
  };
}

function memoryPersistence(initialRecord?: ImportRecord) {
  let currentRecord = initialRecord;
  let stagedRecord: ImportRecord | undefined = initialRecord;
  let stagedAssets: ImportedAsset[] = [];
  const persistence: ImportPersistence = {
    readRecord: vi.fn(async () => currentRecord),
    stage: vi.fn(async (record, assets) => {
      stagedRecord = { ...record, status: 'staged' };
      currentRecord = stagedRecord;
      stagedAssets = assets;
    }),
    markComplete: vi.fn(async (status = 'complete') => {
      if (!currentRecord) throw new Error('nothing staged');
      currentRecord = { ...currentRecord, status: currentRecord.snapshot.database.present ? status : 'empty' };
    }),
  };
  return {
    persistence,
    get stagedRecord() { return stagedRecord; },
    get stagedAssets() { return stagedAssets; },
    get complete() { return currentRecord?.status === 'complete' || currentRecord?.status === 'empty'; },
  };
}

function nativePlugin(snapshot: NativeLegacySnapshot, base64 = '') {
  const native: NativeInstalledDataMigrationPlugin = {
    readLegacyInstalledData: vi.fn(async () => snapshot),
    readAssetChunk: vi.fn(async ({ assetId, offset, maxBytes }) => ({
      assetId,
      offset,
      byteLength: Math.min(maxBytes, base64.length === 4 ? 3 : 0),
      base64,
    })),
  };
  return native;
}

describe('installed Android data migration', () => {
  it('maps legacy settings only when the JS settings are absent and preserves existing fields', () => {
    const snapshot = legacySnapshot();
    const existing = new MemoryStorage();
    existing.setItem('pocketshell.js.settings.v1', JSON.stringify({
      themeChoice: 'maintainer-choice',
      terminalFontSize: 24,
      backgroundGraceMs: 30_000,
      voiceLanguage: 'fr',
      voiceSilenceSeconds: 12,
      futureSetting: 'keep',
    }));

    const writes = prepareLocalStorageWrites(snapshot, existing, 2);

    expect(writes.settings).toBeUndefined();
    expect(writes.trustPins).toEqual([{
      key: 'pocketshell.ssh.host-key.41',
      value: JSON.stringify({ kind: 'sha256-fingerprint', fingerprintSha256: TRUST_FINGERPRINT }),
    }]);

    const empty = new MemoryStorage();
    const mapped = prepareLocalStorageWrites(snapshot, empty, 2);
    expect(JSON.parse(mapped.settings ?? '{}')).toEqual({
      terminalFontSize: 16,
      backgroundGraceMs: 90_000,
      voiceLanguage: 'de',
      voiceSilenceSeconds: 8,
    });
  });

  it('stages complete history, trust and asset bytes before marking the import complete', async () => {
    const snapshot = legacySnapshot();
    snapshot.environment.applicationId = 'com.pocketshell.app.release';
    snapshot.assets = [{
      assetId: 'voice-export-1',
      category: 'voice-export',
      relativePath: 'voice-exports/recording.wav',
      byteLength: 3,
      sha256: ABC_SHA256,
    }];
    const persistence = memoryPersistence();
    const storage = new MemoryStorage();
    const native = nativePlugin(snapshot, 'YWJj');
    installedDataMigrationState.status = 'pending';
    installedDataMigrationState.error = '';

    const settingsWritten = await runInstalledDataMigration({
      native,
      persistence: persistence.persistence,
      storage,
      nativePlatform: true,
      now: () => 123,
      pixelRatio: () => 2,
    });

    expect(settingsWritten).toBe(true);
    expect(installedDataMigrationState.status).toBe('complete');
    expect(persistence.complete).toBe(true);
    expect(persistence.stagedRecord?.id).toBe(IMPORT_RECORD_ID);
    expect(persistence.stagedRecord?.snapshot.preferences.next_sync_selection.entries.sync_future_field)
      .toEqual({ type: 'string', value: '{"kept":true}' });
    expect(persistence.stagedRecord?.snapshot.preferences.composer_drafts.entries['host-41'])
      .toEqual({ type: 'string', value: 'keep this draft' });
    expect(persistence.stagedAssets).toHaveLength(1);
    await expect(persistence.stagedAssets[0].content.text()).resolves.toBe('abc');
    expect(native.readAssetChunk).toHaveBeenCalledWith({
      assetId: 'voice-export-1',
      offset: 0,
      maxBytes: 3,
    });
    expect(storage.getItem('pocketshell.js.settings.v1')).toBe(JSON.stringify({
      terminalFontSize: 16,
      backgroundGraceMs: 90_000,
      voiceLanguage: 'de',
      voiceSilenceSeconds: 8,
    }));
    expect(storage.getItem('pocketshell.ssh.host-key.41')).toContain(TRUST_FINGERPRINT);
  });

  it('repairs missing JS settings and trust pins from a completed import without rereading native data', async () => {
    const snapshot = legacySnapshot();
    const priorRecord: ImportRecord = {
      id: IMPORT_RECORD_ID,
      status: 'complete',
      importedAt: 123,
      snapshot,
      warnings: [],
    };
    const persistence = memoryPersistence(priorRecord);
    const storage = new MemoryStorage();
    const native = nativePlugin(snapshot);
    installedDataMigrationState.status = 'pending';
    installedDataMigrationState.error = '';

    const settingsWritten = await runInstalledDataMigration({
      native,
      persistence: persistence.persistence,
      storage,
      nativePlatform: true,
      now: () => 999,
      pixelRatio: () => 2,
    });

    expect(settingsWritten).toBe(true);
    expect(installedDataMigrationState.status).toBe('complete');
    expect(persistence.persistence.stage).not.toHaveBeenCalled();
    expect(persistence.persistence.markComplete).not.toHaveBeenCalled();
    expect(native.readLegacyInstalledData).not.toHaveBeenCalled();
    expect(storage.getItem('pocketshell.js.settings.v1')).toBe(JSON.stringify({
      terminalFontSize: 16,
      backgroundGraceMs: 90_000,
      voiceLanguage: 'de',
      voiceSilenceSeconds: 8,
    }));
    expect(storage.getItem('pocketshell.ssh.host-key.41')).toContain(TRUST_FINGERPRINT);
  });

  it('preserves newer JS settings and skips the startup reload when no setting was written', async () => {
    const snapshot = legacySnapshot();
    const priorRecord: ImportRecord = {
      id: IMPORT_RECORD_ID,
      status: 'complete',
      importedAt: 123,
      snapshot,
      warnings: [],
    };
    const persistence = memoryPersistence(priorRecord);
    const storage = new MemoryStorage();
    const newerSettings = {
      themeChoice: 'user-theme',
      terminalFontSize: 24,
      backgroundGraceMs: 30_000,
      voiceLanguage: 'fr',
      voiceSilenceSeconds: 12,
      futureField: 'keep',
    };
    storage.setItem('pocketshell.js.settings.v1', JSON.stringify(newerSettings));
    const reloadSession = new MemoryStorage();

    const settingsWritten = await runInstalledDataMigration({
      native: nativePlugin(snapshot),
      persistence: persistence.persistence,
      storage,
      nativePlatform: true,
      now: () => 999,
      pixelRatio: () => 2,
    });

    expect(settingsWritten).toBe(false);
    expect(JSON.parse(storage.getItem('pocketshell.js.settings.v1') ?? '{}')).toEqual(newerSettings);
    expect(shouldReloadForImportedSettings(settingsWritten, reloadSession)).toBe(false);
  });

  it('requests only one reload in the current startup session after writing imported settings', () => {
    const reloadSession = new MemoryStorage();

    expect(shouldReloadForImportedSettings(false, reloadSession)).toBe(false);
    expect(shouldReloadForImportedSettings(true, reloadSession)).toBe(true);
    expect(shouldReloadForImportedSettings(true, reloadSession)).toBe(false);
  });

  it('refuses malformed sync state without staging or marking partial data complete', async () => {
    const snapshot = legacySnapshot();
    snapshot.preferences.next_sync_selection.entries.sync_selected_hosts.value = '{broken';
    const persistence = memoryPersistence();
    const storage = new MemoryStorage();
    installedDataMigrationState.status = 'pending';
    installedDataMigrationState.error = '';

    await runInstalledDataMigration({
      native: nativePlugin(snapshot),
      persistence: persistence.persistence,
      storage,
      nativePlatform: true,
      now: () => 123,
      pixelRatio: () => 2,
    });

    expect(installedDataMigrationState.status).toBe('failed');
    expect(installedDataMigrationState.error).toContain('malformed JSON');
    expect(persistence.persistence.stage).not.toHaveBeenCalled();
    expect(persistence.persistence.markComplete).not.toHaveBeenCalled();
    expect(storage.values.size).toBe(0);
  });

  it('imports unrelated data as partial and visibly retains an unavailable encrypted store', async () => {
    const snapshot = legacySnapshot();
    snapshot.encryptedPreferences['pocketshell-voice-secrets'] = {
      present: true,
      status: 'unavailable',
      keys: [],
      error: 'Encrypted preferences pocketshell-voice-secrets have an unreadable keyset.',
    };
    const persistence = memoryPersistence();
    const storage = new MemoryStorage();
    installedDataMigrationState.status = 'pending';
    installedDataMigrationState.error = '';

    await runInstalledDataMigration({
      native: nativePlugin(snapshot),
      persistence: persistence.persistence,
      storage,
      nativePlatform: true,
      now: () => 123,
      pixelRatio: () => 2,
    });

    expect(installedDataMigrationState.status).toBe('partial');
    expect(installedDataMigrationState.error).toContain('unreadable keyset');
    expect(persistence.persistence.stage).toHaveBeenCalledOnce();
    expect(persistence.stagedRecord?.status).toBe('staged');
    expect(persistence.stagedRecord?.warnings.some((warning) =>
      warning.includes(snapshot.encryptedPreferences['pocketshell-voice-secrets'].error ?? ''))).toBe(true);
    expect(persistence.persistence.markComplete).toHaveBeenCalledWith('partial');
    expect(storage.getItem('pocketshell.js.settings.v1')).toContain('terminalFontSize');
    expect(storage.getItem('pocketshell.ssh.host-key.41')).toContain(TRUST_FINGERPRINT);
  });

  it('shows an unmappable legacy terminal size on first import and completed-record startup', async () => {
    const snapshot = legacySnapshot();
    const persistence = memoryPersistence();
    const storage = new MemoryStorage();
    installedDataMigrationState.status = 'pending';
    installedDataMigrationState.error = '';

    await runInstalledDataMigration({
      native: nativePlugin(snapshot),
      persistence: persistence.persistence,
      storage,
      nativePlatform: true,
      now: () => 123,
      pixelRatio: () => 5,
    });

    expect(installedDataMigrationState.status).toBe('partial');
    expect(installedDataMigrationState.error).toContain('terminal text size');
    expect(persistence.stagedRecord?.warnings).toContain(installedDataMigrationState.error);
    expect(persistence.persistence.markComplete).toHaveBeenCalledWith('partial');
    expect(JSON.parse(storage.getItem(SETTINGS_STORAGE_KEY) ?? '{}')).not.toHaveProperty('terminalFontSize');

    const completedWithOldStatus: ImportRecord = {
      ...persistence.stagedRecord!,
      status: 'complete',
    };
    const replay = memoryPersistence(completedWithOldStatus);
    installedDataMigrationState.status = 'pending';
    installedDataMigrationState.error = '';
    const native = nativePlugin(snapshot);

    await runInstalledDataMigration({
      native,
      persistence: replay.persistence,
      storage,
      nativePlatform: true,
      now: () => 456,
      pixelRatio: () => 5,
    });

    expect(installedDataMigrationState.status).toBe('partial');
    expect(installedDataMigrationState.error).toContain('terminal text size');
    expect(native.readLegacyInstalledData).not.toHaveBeenCalled();
  });

  it('marks decrypted native-only credentials partial and offers only opaque legacy host key references', async () => {
    const snapshot = legacySnapshot();
    snapshot.encryptedPreferences['pocketshell-sync-auth'] = {
      present: true,
      status: 'decrypted-native-retained',
      keys: ['google_sync_auth'],
    };
    const persistence = memoryPersistence();
    const storage = new MemoryStorage();
    installedDataMigrationState.status = 'pending';
    installedDataMigrationState.error = '';

    await runInstalledDataMigration({
      native: nativePlugin(snapshot),
      persistence: persistence.persistence,
      storage,
      nativePlatform: true,
      now: () => 123,
      pixelRatio: () => 2,
    });

    expect(installedDataMigrationState.status).toBe('partial');
    expect(installedDataMigrationState.error).toContain('not available to the JS app yet');
    expect(persistence.persistence.markComplete).toHaveBeenCalledWith('partial');
    const importedHosts = await readImportedLegacyHosts(persistence.persistence);
    expect(importedHosts).toEqual([{
      id: 41,
      name: 'devbox',
      hostname: 'dev.example.test',
      port: 22,
      username: 'alex',
      keyId: 7,
      keyName: 'main key',
      keyHasPassphrase: false,
      keySha256: 'a'.repeat(64),
    }]);
    expect(JSON.stringify(importedHosts)).not.toContain('privateKeyPath');
  });

  it('connects a saved host through an opaque native key reference and keeps passphrases transient', () => {
    const host = {
      id: 41,
      name: 'devbox',
      hostname: 'dev.example.test',
      port: 2222,
      username: 'alex',
      keyId: 7,
      keyName: 'main key',
      keyHasPassphrase: true,
      keySha256: 'a'.repeat(64),
    };

    const target = makeLegacySshHostTarget(host, 'entered-for-this-connection');

    expect(target).toEqual({
      hostId: '41',
      hostname: 'dev.example.test',
      port: 2222,
      username: 'alex',
      credential: {
        kind: 'legacy-private-key',
        keyId: 7,
        sha256: 'a'.repeat(64),
        passphrase: 'entered-for-this-connection',
      },
    });
    expect(JSON.stringify(target)).not.toContain('privateKeyPem');
    expect(JSON.stringify(target)).not.toContain('-----BEGIN');
    expect(makeLegacySshHostTarget({ ...host, keyHasPassphrase: false }, 'ignored'))
      .not.toHaveProperty('credential.passphrase');
  });

  it('does not mark complete or write settings when an imported asset hash differs', async () => {
    const snapshot = legacySnapshot();
    snapshot.assets = [{
      assetId: 'changed-asset',
      category: 'voice-export',
      relativePath: 'voice-exports/recording.wav',
      byteLength: 3,
      sha256: '0'.repeat(64),
    }];
    const persistence = memoryPersistence();
    const storage = new MemoryStorage();
    installedDataMigrationState.status = 'pending';
    installedDataMigrationState.error = '';

    await runInstalledDataMigration({
      native: nativePlugin(snapshot, 'YWJj'),
      persistence: persistence.persistence,
      storage,
      nativePlatform: true,
      now: () => 123,
      pixelRatio: () => 2,
    });

    expect(installedDataMigrationState.status).toBe('failed');
    expect(installedDataMigrationState.error).toContain('changed during import');
    expect(persistence.persistence.stage).not.toHaveBeenCalled();
    expect(persistence.persistence.markComplete).not.toHaveBeenCalled();
    expect(storage.values.size).toBe(0);
  });

  it('refuses conflicting JS trust pins without replacing either fingerprint', () => {
    const snapshot = legacySnapshot();
    const storage = new MemoryStorage();
    storage.setItem('pocketshell.ssh.host-key.41', JSON.stringify({
      kind: 'sha256-fingerprint',
      fingerprintSha256: `SHA256:${'B'.repeat(43)}`,
    }));

    expect(() => prepareLocalStorageWrites(snapshot, storage, 2)).toThrowError(InstalledDataMigrationError);
    expect(storage.getItem('pocketshell.ssh.host-key.41')).toContain(`SHA256:${'B'.repeat(43)}`);
  });
});
