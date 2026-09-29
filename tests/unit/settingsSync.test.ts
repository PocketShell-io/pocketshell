import { describe, expect, it } from 'vitest';
import { serializeSyncPayload, type SyncHostEntry } from '@pocketshell/core';
import rawVectors from '../../vendor/pocketshell-core/tests/fixtures/settings-sync-vectors.json';
import {
  SETTINGS_SYNC_CONFLICT_RETRIES,
  parseSettingsSyncPayload,
  syncSelectedHosts,
  type SettingsSyncEffects,
  type SettingsSyncSnapshot,
} from '../../src/sync/settingsSync';

interface SettingsSyncVectors {
  wireContract: { emptyPayloadPlaintext: string };
  mergeCases: Array<{
    id: string;
    local: unknown[];
    remote: unknown[];
    checked: string[];
    expected: unknown[];
  }>;
  autoCheckCases: Array<{
    id: string;
    remote: unknown[];
    checked: string[];
    localAliases: string[];
    expected: string[];
  }>;
  payloadCases: Array<{
    id: string;
    plaintext: string;
    expected: { kind: string; reason?: string; hosts?: unknown[] };
  }>;
}

const vectors = rawVectors as unknown as SettingsSyncVectors;

function hosts(entries: unknown[]): SyncHostEntry[] {
  return entries as SyncHostEntry[];
}

function effectsFor(
  snapshot: SettingsSyncSnapshot,
  push: SettingsSyncEffects['push'] = async () => ({ kind: 'ok', version: 2 }),
): SettingsSyncEffects & { uploads: Array<{ baseVersion: number | null; plaintext: string }> } {
  const uploads: Array<{ baseVersion: number | null; plaintext: string }> = [];
  return {
    async pull() {
      return snapshot;
    },
    async push(input) {
      uploads.push(input);
      return push(input);
    },
    uploads,
  };
}

describe('Android settings sync uses the pinned core contract', () => {
  it('keeps the existing versionless payload shape', async () => {
    expect(serializeSyncPayload([])).toBe(vectors.wireContract.emptyPayloadPlaintext);
    const effects = effectsFor({ kind: 'absent' });
    const result = await syncSelectedHosts(
      [{ name: 'prod', hostname: 'prod.example.net' }],
      ['prod'],
      effects,
    );

    expect(effects.uploads[0]?.baseVersion).toBeNull();
    expect(JSON.parse(effects.uploads[0]!.plaintext)).toEqual({
      hosts: [{ name: 'prod', hostname: 'prod.example.net' }],
    });
    expect(result.kind).toBe('synced');
  });

  for (const vector of vectors.mergeCases) {
    it(vector.id, async () => {
      const effects = effectsFor({
        kind: 'ok',
        version: 7,
        plaintext: JSON.stringify({ hosts: vector.remote }),
      });

      const result = await syncSelectedHosts(hosts(vector.local), vector.checked, effects);

      expect(result.kind).toBe('synced');
      expect(effects.uploads).toHaveLength(1);
      expect(effects.uploads[0]?.baseVersion).toBe(7);
      expect(JSON.parse(effects.uploads[0]!.plaintext)).toEqual({ hosts: vector.expected });
      if (result.kind === 'synced') expect(result.hosts).toEqual(vector.expected);
    });
  }

  for (const vector of vectors.autoCheckCases) {
    it(vector.id, async () => {
      const local = vector.localAliases.map((name) => ({ name, hostname: `${name}.example.net` }));
      const effects = effectsFor({
        kind: 'ok',
        version: 3,
        plaintext: JSON.stringify({ hosts: vector.remote }),
      });

      const result = await syncSelectedHosts(local, vector.checked, effects);

      expect(result.kind).toBe('synced');
      if (result.kind === 'synced') expect(result.selectedAliases).toEqual(vector.expected);
    });
  }

  for (const vector of vectors.payloadCases) {
    it(vector.id, async () => {
      expect(parseSettingsSyncPayload(vector.plaintext)).toEqual(vector.expected);
      if (vector.expected.kind === 'ok') return;

      const effects = effectsFor({ kind: 'ok', version: 4, plaintext: vector.plaintext });

      const result = await syncSelectedHosts([], [], effects);

      expect(result).toMatchObject({ kind: 'invalid-payload', reason: vector.expected.reason });
      expect(result).not.toHaveProperty('hosts');
      expect(result).not.toHaveProperty('plaintext');
      expect(effects.uploads).toEqual([]);
    });
  }

  it('accepts an explicit empty payload but never uploads an empty selection', async () => {
    const effects = effectsFor({ kind: 'ok', version: 5, plaintext: '{"hosts":[]}' });

    const result = await syncSelectedHosts([], [], effects);

    expect(parseSettingsSyncPayload('{"hosts":[]}')).toEqual({ kind: 'ok', hosts: [] });
    expect(result).toEqual({ kind: 'empty-selection' });
    expect(effects.uploads).toEqual([]);
  });

  it('re-pulls and merges fields written by another client before retrying a conflict', async () => {
    const snapshots: SettingsSyncSnapshot[] = [
      {
        kind: 'ok',
        version: 8,
        plaintext: JSON.stringify({
          hosts: [{ name: 'prod', hostname: 'old.example.net', future: { revision: 1 } }],
        }),
      },
      {
        kind: 'ok',
        version: 9,
        plaintext: JSON.stringify({
          hosts: [
            { name: 'prod', hostname: 'desktop.example.net', future: { revision: 2 } },
            { name: 'laptop-only', hostname: 'laptop.example.net', desktopField: true },
          ],
        }),
      },
    ];
    const uploads: Array<{ baseVersion: number | null; plaintext: string }> = [];
    let pullCount = 0;
    const effects: SettingsSyncEffects = {
      async pull() {
        const current = snapshots[Math.min(pullCount, snapshots.length - 1)]!;
        pullCount += 1;
        return current;
      },
      async push(input) {
        uploads.push(input);
        return uploads.length === 1
          ? { kind: 'conflict', currentVersion: 9 }
          : { kind: 'ok', version: 10 };
      },
    };

    const result = await syncSelectedHosts(
      [{ name: 'prod', hostname: 'phone.example.net', port: 2222, user: 'alexey' }],
      ['prod'],
      effects,
    );

    expect(result).toMatchObject({ kind: 'synced', version: 10, attempts: 2 });
    expect(uploads.map((upload) => upload.baseVersion)).toEqual([8, 9]);
    expect(JSON.parse(uploads[1]!.plaintext)).toEqual({
      hosts: [
        {
          name: 'prod',
          hostname: 'phone.example.net',
          port: 2222,
          user: 'alexey',
          future: { revision: 2 },
        },
        { name: 'laptop-only', hostname: 'laptop.example.net', desktopField: true },
      ],
    });
  });

  it('returns visible pull and push failures', async () => {
    const pullFailure = await syncSelectedHosts([], [], {
      async pull() { throw new Error('offline'); },
      async push() { throw new Error('must not push'); },
    });
    expect(pullFailure).toEqual({ kind: 'error', stage: 'pull', message: 'offline' });

    const pushFailure = await syncSelectedHosts(
      [{ name: 'prod', hostname: 'prod.example.net' }],
      ['prod'],
      effectsFor({ kind: 'absent' }, async () => ({ kind: 'error', message: 'service unavailable' })),
    );
    expect(pushFailure).toEqual({ kind: 'error', stage: 'push', message: 'service unavailable' });
  });

  it('rejects invalid platform versions before upload', async () => {
    for (const version of [-1, 1.5, Number.MAX_SAFE_INTEGER + 1]) {
      const effects = effectsFor({ kind: 'ok', version, plaintext: '{"hosts":[]}' });

      expect(await syncSelectedHosts([], [], effects)).toMatchObject({ kind: 'error', stage: 'pull' });
      expect(effects.uploads).toEqual([]);
    }
  });

  it('fails closed when the platform boundary returns an unknown result shape', async () => {
    for (const snapshot of [null, { kind: 'unknown' }, { kind: 'ok', version: 1, plaintext: {} }]) {
      const uploads: Array<{ baseVersion: number | null; plaintext: string }> = [];
      const effects = {
        async pull() { return snapshot; },
        async push(input: { baseVersion: number | null; plaintext: string }) {
          uploads.push(input);
          return { kind: 'ok' as const, version: 3 };
        },
      } as unknown as SettingsSyncEffects;

      expect(await syncSelectedHosts(
        [{ name: 'prod', hostname: 'prod.example.net' }],
        ['prod'],
        effects,
      )).toMatchObject({ kind: 'error', stage: 'pull' });
      expect(uploads).toEqual([]);
    }

    const invalidPush = await syncSelectedHosts(
      [{ name: 'prod', hostname: 'prod.example.net' }],
      ['prod'],
      {
        async pull() { return { kind: 'absent' as const }; },
        async push() { return { kind: 'unknown' }; },
      } as unknown as SettingsSyncEffects,
    );
    expect(invalidPush).toMatchObject({ kind: 'error', stage: 'push' });
  });

  it('stops after the bounded conflict retry policy', async () => {
    let pullCount = 0;
    const result = await syncSelectedHosts([{ name: 'prod', hostname: 'prod.example.net' }], ['prod'], {
      async pull() {
        pullCount += 1;
        return {
          kind: 'ok',
          version: pullCount,
          plaintext: '{"hosts":[{"name":"prod","hostname":"desktop.example.net"}]}',
        };
      },
      async push() {
        return { kind: 'conflict', currentVersion: pullCount + 1 };
      },
    });

    expect(result).toEqual({
      kind: 'conflict-limit',
      version: SETTINGS_SYNC_CONFLICT_RETRIES + 2,
      attempts: SETTINGS_SYNC_CONFLICT_RETRIES + 1,
    });
    expect(pullCount).toBe(SETTINGS_SYNC_CONFLICT_RETRIES + 1);
  });
});
