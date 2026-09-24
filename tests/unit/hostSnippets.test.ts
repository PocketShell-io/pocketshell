import { describe, expect, it } from 'vitest';
import {
  HOST_SNIPPETS_STORAGE_KEY,
  createHostSnippetLibraryStore,
  type HostSnippetStorage,
} from '../../src/stores/hostSnippets';

class MemoryStorage implements HostSnippetStorage {
  readonly values = new Map<string, string>();
  failReads = false;
  failWrites = false;

  getItem(key: string): string | null {
    if (this.failReads) throw new Error('read unavailable');
    return this.values.get(key) ?? null;
  }

  setItem(key: string, value: string): void {
    if (this.failWrites) throw new Error('quota exceeded');
    this.values.set(key, value);
  }
}

const legacySnippets = [
  { id: 12, hostId: 41, label: 'Status', body: 'git status\r\n', kind: 'command' },
  { id: 13, hostId: 72, label: null, body: 'echo second host', kind: 'command' },
  { id: 14, hostId: 41, label: 'Logs', body: 'tail -n 40 app.log', kind: 'command' },
];

const legacyTemplates = [
  { id: 4, hostId: 41, label: 'Review', commands: 'check this\nthen summarize' },
  { id: 5, hostId: 72, label: 'Build', commands: 'make test' },
];

describe('per-host snippet and command-template persistence', () => {
  it('imports strict legacy rows with their identities and order, isolates hosts, and is idempotent', () => {
    const storage = new MemoryStorage();
    const store = createHostSnippetLibraryStore(storage);

    const firstImport = store.importLegacyRows(legacySnippets, legacyTemplates, [41, 72]);

    expect(firstImport).toEqual({ kind: 'ok', snippetsAdded: 3, templatesAdded: 2, persisted: true });
    expect(store.snippetsForHost('41')).toEqual([
      { id: '12', hostId: '41', label: 'Status', body: 'git status\r\n', kind: 'command', sortOrder: 0 },
      { id: '14', hostId: '41', label: 'Logs', body: 'tail -n 40 app.log', kind: 'command', sortOrder: 1 },
    ]);
    expect(store.snippetsForHost('72').map((item) => item.id)).toEqual(['13']);
    expect(store.snippetsForHost('0041')).toEqual([]);
    expect(store.commandTemplatesForHost('41').map((item) => item.id)).toEqual(['4']);

    const secondImport = store.importLegacyRows(legacySnippets, legacyTemplates, [41, 72]);
    expect(secondImport).toEqual({ kind: 'ok', snippetsAdded: 0, templatesAdded: 0, persisted: true });
    expect(store.snippets).toHaveLength(3);
    expect(store.templates).toHaveLength(2);
  });

  it('retries a failed legacy import even when the same rows are already in memory', () => {
    const storage = new MemoryStorage();
    const store = createHostSnippetLibraryStore(storage);
    storage.failWrites = true;

    expect(store.importLegacyRows(legacySnippets, legacyTemplates, [41, 72])).toEqual({
      kind: 'ok', snippetsAdded: 3, templatesAdded: 2, persisted: false,
    });
    expect(storage.getItem(HOST_SNIPPETS_STORAGE_KEY)).toBeNull();

    storage.failWrites = false;
    expect(store.importLegacyRows(legacySnippets, legacyTemplates, [41, 72])).toEqual({
      kind: 'ok', snippetsAdded: 0, templatesAdded: 0, persisted: true,
    });
    expect(storage.getItem(HOST_SNIPPETS_STORAGE_KEY)).not.toBeNull();
    expect(store.storageStatus).toBe('ready');

    const reopened = createHostSnippetLibraryStore(storage);
    expect(reopened.snippetsForHost('41').map((item) => item.id)).toEqual(['12', '14']);
    expect(reopened.snippetsForHost('72').map((item) => item.id)).toEqual(['13']);
    expect(reopened.commandTemplatesForHost('41').map((item) => item.id)).toEqual(['4']);
    expect(reopened.commandTemplatesForHost('72').map((item) => item.id)).toEqual(['5']);
  });

  it('round-trips literal content and saves host-scoped edits and ordering', () => {
    const storage = new MemoryStorage();
    const store = createHostSnippetLibraryStore(storage);
    store.saveSnippet({ id: 'local-1', hostId: 'host-a', label: 'One', body: 'first\nline', kind: 'prompt', sortOrder: 99 });
    store.saveSnippet({ id: 'local-2', hostId: 'host-a', label: 'Two', body: 'second', kind: 'command', sortOrder: 99 });
    store.saveSnippet({ id: 'local-3', hostId: 'host-b', label: 'Other', body: 'do not move', kind: 'command', sortOrder: 0 });
    store.saveTemplate({ id: 'template-1', hostId: 'host-a', label: 'Prompt', commands: 'exact\r\nbody', sortOrder: 0 });
    expect(store.saveSnippet({
      id: 'local-1', hostId: 'host-a', label: 'Edited', body: 'updated\ntext', kind: 'prompt', sortOrder: 99,
    })).toEqual({ kind: 'ok', persisted: true });

    expect(store.reorderSnippets('host-a', ['local-2', 'local-1'])).toEqual({ kind: 'ok', persisted: true });
    expect(store.reorderSnippets('host-a', ['local-2', 'foreign-id'])).toEqual({ kind: 'invalid', reason: 'invalid-order' });
    expect(store.deleteSnippet('host-a', 'local-3')).toEqual({ kind: 'invalid', reason: 'missing-item' });

    const reopened = createHostSnippetLibraryStore(storage);
    expect(reopened.snippetsForHost('host-a').map((item) => [item.id, item.body])).toEqual([
      ['local-2', 'second'],
      ['local-1', 'updated\ntext'],
    ]);
    expect(reopened.snippetsForHost('host-b').map((item) => item.id)).toEqual(['local-3']);
    expect(reopened.commandTemplatesForHost('host-a')[0]?.commands).toBe('exact\r\nbody');
  });

  it('rejects malformed Room rows without partially importing valid rows', () => {
    const storage = new MemoryStorage();
    const store = createHostSnippetLibraryStore(storage);
    store.saveSnippet({ id: 'current', hostId: '41', label: 'Current', body: 'keep', kind: 'command', sortOrder: 0 });
    const before = storage.getItem(HOST_SNIPPETS_STORAGE_KEY);

    const result = store.importLegacyRows(
      [...legacySnippets.slice(0, 1), { id: 15, hostId: 999, label: 'Unknown host', body: 'must not import', kind: 'command' }],
      legacyTemplates,
      [41, 72],
    );

    expect(result).toEqual({ kind: 'invalid', collection: 'snippets', reason: 'unknown-host-id', index: 1 });
    expect(store.snippets).toEqual([
      { id: 'current', hostId: '41', label: 'Current', body: 'keep', kind: 'command', sortOrder: 0 },
    ]);
    expect(store.templates).toEqual([]);
    expect(storage.getItem(HOST_SNIPPETS_STORAGE_KEY)).toBe(before);
  });

  it('keeps malformed stored rows recoverable after a successful write and restart', () => {
    const storage = new MemoryStorage();
    const malformedDocument = JSON.stringify({
      schema: 1,
      snippets: [
        { id: 'valid', hostId: '41', label: 'Keep', body: 'valid', kind: 'command', sortOrder: 0 },
        { id: 8, hostId: 41, label: 'Malformed row', body: 'not a JS string ID', kind: 'command', sortOrder: 1 },
      ],
      templates: [],
    });
    storage.values.set(HOST_SNIPPETS_STORAGE_KEY, malformedDocument);

    const store = createHostSnippetLibraryStore(storage);
    expect(store.storageStatus).toBe('recovered');
    expect(store.snippets.map((item) => item.id)).toEqual(['valid']);
    expect(store.saveSnippet({
      id: 'new-item', hostId: '41', label: 'New', body: 'new literal', kind: 'command', sortOrder: 50,
    })).toEqual({ kind: 'ok', persisted: true });

    const saved = JSON.parse(storage.getItem(HOST_SNIPPETS_STORAGE_KEY) ?? '{}') as {
      recovery?: { source?: string };
    };
    expect(saved.recovery?.source).toBe(malformedDocument);

    const reopened = createHostSnippetLibraryStore(storage);
    expect(reopened.storageStatus).toBe('recovered');
    expect(reopened.snippetsForHost('41').map((item) => item.id)).toEqual(['valid', 'new-item']);
    expect(JSON.parse(storage.getItem(HOST_SNIPPETS_STORAGE_KEY) ?? '{}')).toEqual(saved);
  });

  it('keeps current-session additions when storage writes fail and persists them on a later retry', () => {
    const storage = new MemoryStorage();
    const store = createHostSnippetLibraryStore(storage);
    storage.failWrites = true;

    expect(store.saveSnippet({
      id: 'memory-only', hostId: '41', label: 'First', body: 'retained in memory', kind: 'command', sortOrder: 0,
    })).toEqual({ kind: 'ok', persisted: false });
    expect(store.storageStatus).toBe('write-failed');
    expect(store.snippetsForHost('41').map((item) => item.id)).toEqual(['memory-only']);
    expect(storage.getItem(HOST_SNIPPETS_STORAGE_KEY)).toBeNull();

    storage.failWrites = false;
    expect(store.saveSnippet({
      id: 'later', hostId: '41', label: 'Second', body: 'later write', kind: 'command', sortOrder: 0,
    })).toEqual({ kind: 'ok', persisted: true });
    expect(createHostSnippetLibraryStore(storage).snippetsForHost('41').map((item) => item.id))
      .toEqual(['memory-only', 'later']);
  });
});
