import { describe, expect, it } from 'vitest';
import {
  parseSyncPayloadResult,
  runSyncRound,
  serializeSyncPayload,
  type SyncHostEntry,
  type SyncRoundEffects,
  type SyncRoundPulled,
  type SyncRoundSnapshot,
} from '@pocketshell/core';
import rawVectors from '../../vendor/pocketshell-core/tests/fixtures/settings-sync-vectors.json';

// The sync round (pull -> auto-check -> assemble -> push -> conflict retry)
// lives in @pocketshell/core (syncRound.ts) with its own loop-mechanics suite.
// Per core docs/SYNC.md every client runs the shared vectors through the entry
// point it actually calls; for Android that is core's runSyncRound, which the
// packaged settings-sync probe binds directly.

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
    unticked: string[];
    expected: string[];
    expectedUnticked: string[];
  }>;
  untickRoundCases: Array<{
    id: string;
    local: unknown[];
    remote: unknown[];
    checked: string[];
    unticked: string[];
    expectedUploaded: string[];
    expectedUntickedAfterPush: string[];
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
  snapshot: SyncRoundSnapshot,
  push: SyncRoundEffects['push'] = async () => ({ kind: 'ok', version: 2 }),
): SyncRoundEffects & { uploads: Array<{ baseVersion: number | null; plaintext: string }> } {
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

describe('Android settings sync runs the shared vectors through core runSyncRound', () => {
  it('keeps the existing versionless payload shape', async () => {
    expect(serializeSyncPayload([])).toBe(vectors.wireContract.emptyPayloadPlaintext);
    const effects = effectsFor({ kind: 'absent' });
    const result = await runSyncRound(
      [{ name: 'prod', hostname: 'prod.example.net' }],
      { checked: ['prod'], unticked: [] },
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

      // A merge case's `checked` is the final selection, so every account
      // alias it leaves out is one the user explicitly unticked.
      const unticked = hosts(vector.remote)
        .map((entry) => entry.name)
        .filter((name) => !vector.checked.includes(name));
      const result = await runSyncRound(hosts(vector.local), { checked: vector.checked, unticked }, effects);

      expect(result.kind).toBe('synced');
      expect(effects.uploads).toHaveLength(1);
      expect(effects.uploads[0]?.baseVersion).toBe(7);
      expect(JSON.parse(effects.uploads[0]!.plaintext)).toEqual({ hosts: vector.expected });
      if (result.kind === 'synced') expect(result.hosts).toEqual(vector.expected);
    });
  }

  for (const vector of vectors.autoCheckCases) {
    it(vector.id, async () => {
      const local = vector.checked.map((name) => ({ name, hostname: `${name}.example.net` }));
      const effects = effectsFor({
        kind: 'ok',
        version: 3,
        plaintext: JSON.stringify({ hosts: vector.remote }),
      });

      const observed: SyncRoundPulled[] = [];
      const result = await runSyncRound(
        local,
        { checked: vector.checked, unticked: vector.unticked },
        { ...effects, onPulled: (pulled) => observed.push(pulled) },
      );

      // The vector pins the selection after the pull...
      expect(observed[0]?.selectedAliases).toEqual(vector.expected);
      expect(observed[0]?.untickedAliases).toEqual(vector.expectedUnticked);
      expect(result.kind).toBe('synced');
      // ...and the push then carries every pending untick out (one-shot).
      if (result.kind === 'synced') {
        expect(result.selectedAliases).toEqual(vector.expected);
        expect(result.untickedAliases).toEqual([]);
      }
      expect(JSON.parse(effects.uploads[0]!.plaintext).hosts.map((h: { name: string }) => h.name))
        .toEqual(vector.expected);
    });
  }

  for (const vector of vectors.untickRoundCases) {
    it(vector.id, async () => {
      const effects = effectsFor({ kind: 'ok', version: 2, plaintext: JSON.stringify({ hosts: vector.remote }) });

      const result = await runSyncRound(
        hosts(vector.local),
        { checked: vector.checked, unticked: vector.unticked },
        effects,
      );

      expect(JSON.parse(effects.uploads[0]!.plaintext).hosts.map((h: { name: string }) => h.name))
        .toEqual(vector.expectedUploaded);
      expect(result).toMatchObject({ kind: 'synced', untickedAliases: vector.expectedUntickedAfterPush });
    });
  }

  for (const vector of vectors.payloadCases) {
    it(vector.id, async () => {
      expect(parseSyncPayloadResult(vector.plaintext)).toEqual(vector.expected);
      if (vector.expected.kind === 'ok') return;

      const effects = effectsFor({ kind: 'ok', version: 4, plaintext: vector.plaintext });

      const result = await runSyncRound([], { checked: [], unticked: [] }, effects);

      expect(result).toMatchObject({ kind: 'invalid-payload', reason: vector.expected.reason });
      expect(result).not.toHaveProperty('hosts');
      expect(result).not.toHaveProperty('plaintext');
      expect(effects.uploads).toEqual([]);
    });
  }

  it('accepts an explicit empty payload but never uploads an empty selection', async () => {
    const effects = effectsFor({ kind: 'ok', version: 5, plaintext: '{"hosts":[]}' });

    const result = await runSyncRound([], { checked: [], unticked: [] }, effects);

    expect(parseSyncPayloadResult('{"hosts":[]}')).toEqual({ kind: 'ok', hosts: [] });
    expect(result).toEqual({ kind: 'empty-selection' });
    expect(effects.uploads).toEqual([]);
  });
});
