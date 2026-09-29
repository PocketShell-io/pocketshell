import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import type { NativeLegacySnapshot } from '../../src/native/installedDataMigration';
import { createComposerDeliveryController } from '../../src/session/composerDelivery';
import { useComposerDrafts } from '../../src/stores/composerDrafts';
import {
  HOST_SNIPPETS_STORAGE_KEY,
  HostSnippetRepository,
  insertLiteralAtDraftSelection,
  type SnippetStorage,
} from '../../src/stores/hostSnippets';

class MemoryStorage implements SnippetStorage {
  values = new Map<string, string>();
  getItem(key: string) { return this.values.get(key) ?? null; }
  setItem(key: string, value: string) { this.values.set(key, value); }
}

function legacySnapshot(): NativeLegacySnapshot {
  return {
    schemaVersion: 1,
    environment: { applicationId: 'com.pocketshell.app', displayDensity: 1 },
    database: {
      present: true,
      version: 22,
      identityHash: '998a588a2d2f383454698ea11820fca9',
      tables: {
        hosts: [{ id: 7 }, { id: 8 }],
        snippets: [
          { id: 31, hostId: 7, label: null, body: '  echo café\nsecond line  ', kind: 'command' },
          { id: 32, hostId: 8, label: 'Other host', body: 'pwd', kind: 'command' },
        ],
        command_templates: [
          { id: 41, hostId: 7, label: 'Multiline template', commands: 'first\nβeta\n🙂\n' },
        ],
      },
    },
    preferences: {},
    encryptedPreferences: {},
    assets: [],
    nativeFiles: [],
  };
}

describe('per-host command chips', () => {
  beforeEach(() => setActivePinia(createPinia()));

  it('imports exact legacy snippets and templates for their host and restores them after restart', () => {
    const storage = new MemoryStorage();
    const repo = new HostSnippetRepository(storage);
    const snapshot = legacySnapshot();

    expect(repo.importLegacySnapshot(snapshot)).toBe(true);
    expect(repo.itemsForHost('7').map(({ collection, label, body }) => ({ collection, label, body }))).toEqual([
      { collection: 'snippet', label: null, body: '  echo café\nsecond line  ' },
      { collection: 'template', label: 'Multiline template', body: 'first\nβeta\n🙂\n' },
    ]);
    expect(repo.itemsForHost('8').map(({ label, body }) => ({ label, body }))).toEqual([
      { label: 'Other host', body: 'pwd' },
    ]);
    expect(repo.itemsForHost('9')).toEqual([]);
    expect(repo.insertionFor('7', 'snippet', '31')).toEqual({
      kind: 'insert-literal', text: '  echo café\nsecond line  ', submit: false,
    });
    expect(repo.insertionFor('7', 'template', '41')).toEqual({
      kind: 'insert-literal', text: 'first\nβeta\n🙂\n', submit: false,
    });
    expect(storage.getItem(HOST_SNIPPETS_STORAGE_KEY)).toContain('legacyImportComplete');

    const afterRestart = new HostSnippetRepository(storage);
    expect(afterRestart.importLegacySnapshot(snapshot)).toBe(true);
    expect(afterRestart.itemsForHost('7')).toEqual(repo.itemsForHost('7'));
    expect(afterRestart.itemsForHost('8')).toEqual(repo.itemsForHost('8'));
  });

  it('creates, edits, deletes, and reorders only the selected host items', () => {
    const repo = new HostSnippetRepository(new MemoryStorage());
    expect(repo.createSnippet('host-a', 'One', 'echo one', 'command')).toBe(true);
    expect(repo.createSnippet('host-a', 'Two', 'echo two', 'command')).toBe(true);
    expect(repo.createSnippet('host-b', 'Other', 'pwd', 'command')).toBe(true);
    const [one, two] = repo.itemsForHost('host-a');

    expect(repo.moveItem('host-a', two.key, -1)).toBe(true);
    expect(repo.itemsForHost('host-a').map(({ label }) => label)).toEqual(['Two', 'One']);
    expect(repo.itemsForHost('host-b').map(({ label }) => label)).toEqual(['Other']);
    expect(repo.updateItem('host-a', one, 'Edited one', 'printf exact', 'prompt')).toBe(true);
    expect(repo.itemsForHost('host-a').find(({ key }) => key === one.key)).toMatchObject({
      label: 'Edited one', body: 'printf exact', kind: 'prompt',
    });
    expect(repo.deleteItem('host-a', two)).toBe(true);
    expect(repo.itemsForHost('host-a').map(({ label }) => label)).toEqual(['Edited one']);
    expect(repo.itemsForHost('host-b').map(({ label }) => label)).toEqual(['Other']);
    expect(repo.reorderHost('host-a', ['snippet:foreign'])).toBe(false);
    expect(repo.itemsForHost('host-a').map(({ label }) => label)).toEqual(['Edited one']);
  });

  it('appends new chips after imported items even when earlier rows were deleted', () => {
    const repo = new HostSnippetRepository(new MemoryStorage());
    expect(repo.importLegacySnapshot(legacySnapshot())).toBe(true);
    expect(repo.deleteItem('7', { collection: 'snippet', id: '31' })).toBe(true);
    expect(repo.createSnippet('7', 'Added later', 'echo later', 'command')).toBe(true);
    expect(repo.itemsForHost('7').map(({ label }) => label)).toEqual([
      'Multiline template',
      'Added later',
    ]);
  });

  it('inserts selected chip text at the draft selection without adding bytes or submitting', () => {
    const insertedText = 'printf \'café\'\nsecond line\n';
    const result = insertLiteralAtDraftSelection('run:replace:end', insertedText, 4, 11);
    expect(result).toEqual({ draft: `run:${insertedText}:end`, caret: 4 + insertedText.length });
  });

  it('retains a selected chip draft after not-sent or uncertain PTY writes', async () => {
    const repo = new HostSnippetRepository(new MemoryStorage());
    expect(repo.createSnippet('host-a', 'Exact command', 'printf exact\nsecond line', 'command')).toBe(true);
    const selected = repo.insertionFor('host-a', 'snippet', repo.itemsForHost('host-a')[0].id);
    expect(selected?.submit).toBe(false);
    const drafts = useComposerDrafts();
    const targetKey = 'host-a/session';
    const exactDraft = selected?.text ?? '';
    drafts.setDraft(targetKey, exactDraft);

    const rejectedWrite = vi.fn(async () => ({ ok: false, message: 'write rejected' }));
    const rejected = createComposerDeliveryController(rejectedWrite, async () => undefined);
    rejected.setTransportState('closed');
    const rejectedResult = await rejected.deliver({ operationId: 'chip-rejected', payload: exactDraft, intent: 'submit' });
    if (rejectedResult.draftEffect === 'clear') drafts.clearDraft(targetKey);
    expect(rejectedResult).toMatchObject({ status: 'not-sent', draftEffect: 'retain', writeCount: 0 });
    expect(rejectedWrite).not.toHaveBeenCalled();
    expect(drafts.draftFor(targetKey)).toBe(exactDraft);

    let resolveWrite: ((value: { ok: boolean }) => void) | undefined;
    let markWriteStarted: (() => void) | undefined;
    const writeStarted = new Promise<void>((resolve) => { markWriteStarted = resolve; });
    const uncertain = createComposerDeliveryController(() => new Promise((resolve) => {
      resolveWrite = resolve;
      markWriteStarted?.();
    }), async () => undefined);
    uncertain.setTransportState('connected');
    const sending = uncertain.deliver({ operationId: 'chip-uncertain', payload: exactDraft, intent: 'submit' });
    await writeStarted;
    uncertain.setTransportState('lost');
    resolveWrite?.({ ok: true });
    const uncertainResult = await sending;
    if (uncertainResult.draftEffect === 'clear') drafts.clearDraft(targetKey);
    expect(uncertainResult).toMatchObject({ status: 'uncertain', draftEffect: 'retain' });
    expect(drafts.draftFor(targetKey)).toBe(exactDraft);
  });
});
