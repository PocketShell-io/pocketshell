import {
  IMPORT_RECORD_ID,
  createImportPersistence,
  type ImportPersistence,
} from './installedDataMigration';
import type {
  SshKeyHostReference,
  SshKeyReferenceStore,
} from '../credentials/keyManagement';

/** Host associations from the signed 0.5.x snapshot are JS policy data. */
export function createLegacySshKeyReferenceStore(
  persistence: ImportPersistence = createImportPersistence(),
): SshKeyReferenceStore {
  const pendingDeletes = new Map<string, {
    handles: Record<string, string>;
    addedTombstones: number[];
  }>();
  return {
    async list(handleId) {
      const record = await persistence.readRecord();
      if (!record || record.id !== IMPORT_RECORD_ID || !['complete', 'partial', 'empty'].includes(record.status)) return [];
      const handles = record.credentialHandles ?? {};
      const result: SshKeyHostReference[] = [];
      for (const row of record.snapshot.database.tables.hosts ?? []) {
        if (!isRecord(row) || typeof row.id !== 'number' || typeof row.keyId !== 'number') continue;
        if (handles[String(row.keyId)] !== handleId) continue;
        result.push({ hostId: String(row.id), hostLabel: typeof row.name === 'string' && row.name ? row.name : String(row.hostname ?? row.id) });
      }
      return result.sort((left, right) => left.hostId.localeCompare(right.hostId));
    },
    async detach(handleId, references) {
      const record = await persistence.readRecord();
      if (!record || record.id !== IMPORT_RECORD_ID) return false;
      const current = { ...(record.credentialHandles ?? {}) };
      const matches = hostRows(record.snapshot.database.tables.hosts)
        .filter((host) => current[String(host.keyId)] === handleId);
      if (!sameReferences(matches, references)) return false;
      const next = { ...current };
      const detachedHandles: Record<string, string> = {};
      for (const [keyId, value] of Object.entries(current)) {
        if (value !== handleId) continue;
        detachedHandles[keyId] = value;
        next[keyId] = null;
      }
      const currentTombstones = [...(record.credentialHandleTombstones ?? [])];
      const nextTombstones = [...new Set([
        ...currentTombstones,
        ...Object.keys(detachedHandles).map(Number).filter((id) => Number.isSafeInteger(id) && id > 0),
      ])].sort((left, right) => left - right);
      const saved = await persistence.compareAndSetCredentialHandles(current, next, {
        expected: currentTombstones,
        next: nextTombstones,
      });
      if (saved) pendingDeletes.set(handleId, {
        handles: detachedHandles,
        addedTombstones: nextTombstones.filter((id) => !currentTombstones.includes(id)),
      });
      return saved;
    },
    async restore(handleId, references) {
      const record = await persistence.readRecord();
      if (!record || record.id !== IMPORT_RECORD_ID) return false;
      const detached = pendingDeletes.get(handleId);
      if (!detached) return false;
      const current = { ...(record.credentialHandles ?? {}) };
      const rows = hostRows(record.snapshot.database.tables.hosts);
      const matches = rows.filter((host) => references.some((reference) => reference.hostId === String(host.id)));
      if (!sameReferences(matches, references)) return false;
      if (Object.keys(detached.handles).some((keyId) => current[keyId] !== null)) return false;
      const next = { ...current };
      for (const [keyId, original] of Object.entries(detached.handles)) next[keyId] = original;
      const currentTombstones = [...(record.credentialHandleTombstones ?? [])];
      const nextTombstones = currentTombstones.filter((id) => !detached.addedTombstones.includes(id));
      const restored = await persistence.compareAndSetCredentialHandles(current, next, {
        expected: currentTombstones,
        next: nextTombstones,
      });
      if (restored) pendingDeletes.delete(handleId);
      return restored;
    },
    commitDelete(handleId) {
      pendingDeletes.delete(handleId);
    },
    async reconcileMissing(availableHandleIds) {
      const record = await persistence.readRecord();
      if (!record || record.id !== IMPORT_RECORD_ID) return;
      const current = { ...(record.credentialHandles ?? {}) };
      const next = { ...current };
      for (const host of hostRows(record.snapshot.database.tables.hosts)) {
        const key = String(host.keyId);
        const handleId = next[key];
        if (typeof handleId === 'string' && !availableHandleIds.has(handleId)) next[key] = null;
      }
      if (JSON.stringify(current) !== JSON.stringify(next)) {
        await persistence.compareAndSetCredentialHandles(current, next);
      }
    },
  };
}

interface LegacyHostRow { id: number; keyId: number; name?: string; hostname?: string; }

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function hostRows(values: unknown[] | undefined): LegacyHostRow[] {
  return (values ?? []).flatMap((value) => isRecord(value) && typeof value.id === 'number' && typeof value.keyId === 'number'
    ? [{
      id: value.id,
      keyId: value.keyId,
      ...(typeof value.name === 'string' ? { name: value.name } : {}),
      ...(typeof value.hostname === 'string' ? { hostname: value.hostname } : {}),
    }]
    : []);
}

function sameReferences(hosts: LegacyHostRow[], references: SshKeyHostReference[]): boolean {
  const expected = hosts.map((host) => String(host.id)).sort();
  const actual = references.map((reference) => reference.hostId).sort();
  return expected.length === actual.length && expected.every((hostId, index) => hostId === actual[index]);
}
