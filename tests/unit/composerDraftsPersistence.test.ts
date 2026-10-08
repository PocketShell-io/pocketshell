import { createPinia } from 'pinia';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { composerDraftTargetKey, useComposerDrafts } from '../../src/stores/composerDrafts';

/** Issue #3060: composer drafts survive a WebView/process restart. */
class MemoryStorage {
  readonly values = new Map<string, string>();
  getItem(key: string): string | null { return this.values.get(key) ?? null; }
  setItem(key: string, value: string): void { this.values.set(key, value); }
  removeItem(key: string): void { this.values.delete(key); }
}

let storage: MemoryStorage;

beforeEach(() => {
  storage = new MemoryStorage();
  vi.stubGlobal('localStorage', storage);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('composer draft persistence', () => {
  it('restores each target draft after a restart (fresh store instance)', () => {
    const before = useComposerDrafts(createPinia());
    before.setDraft('alexey@host:22/session-a', 'dictated words for a');
    before.setDraft('alexey@host:22/session-b', 'typed words for b');

    const afterRestart = useComposerDrafts(createPinia());

    expect(afterRestart.draftFor('alexey@host:22/session-a')).toBe('dictated words for a');
    expect(afterRestart.draftFor('alexey@host:22/session-b')).toBe('typed words for b');
  });

  it('forgets a draft that was sent or discarded', () => {
    const before = useComposerDrafts(createPinia());
    before.setDraft('host/sent', 'about to be sent');
    before.setDraft('host/kept', 'still here');
    before.clearDraft('host/sent');
    before.setDraft('host/emptied', 'x');
    before.setDraft('host/emptied', '');

    const afterRestart = useComposerDrafts(createPinia());

    expect(afterRestart.draftFor('host/sent')).toBe('');
    expect(afterRestart.draftFor('host/emptied')).toBe('');
    expect(afterRestart.draftFor('host/kept')).toBe('still here');
    expect(Object.keys(afterRestart.byTarget)).toEqual(['host/kept']);
  });

  it('ignores corrupt or foreign stored values', () => {
    storage.setItem('pocketshell.composerDrafts.v1', '{"host/ok":"fine","host/bad":42,"":"no key"}');
    expect(useComposerDrafts(createPinia()).byTarget).toEqual({ 'host/ok': 'fine' });

    storage.setItem('pocketshell.composerDrafts.v1', 'not json');
    expect(useComposerDrafts(createPinia()).byTarget).toEqual({});
  });

  it('keeps working in memory when storage is unavailable or throws', () => {
    vi.stubGlobal('localStorage', {
      getItem: () => { throw new Error('denied'); },
      setItem: () => { throw new Error('quota'); },
      removeItem: () => { throw new Error('denied'); },
    });
    const drafts = useComposerDrafts(createPinia());
    drafts.setDraft('host/a', 'in memory');
    expect(drafts.draftFor('host/a')).toBe('in memory');
  });

  it('keys drafts by session id so a recreated session with the same name starts empty', () => {
    const host = 'alexey@host:22';
    const drafts = useComposerDrafts(createPinia());
    const original = composerDraftTargetKey(host, { id: 'uuid-1', name: 'work', createdEpoch: 100 });
    drafts.setDraft(original, 'draft for the first work session');

    const recreated = composerDraftTargetKey(host, { id: 'uuid-2', name: 'work', createdEpoch: 200 });
    const recreatedWithoutId = composerDraftTargetKey(host, { id: null, name: 'work', createdEpoch: 200 });
    const firstWithoutId = composerDraftTargetKey(host, { id: null, name: 'work', createdEpoch: 100 });

    expect(original).toBe('alexey@host:22/uuid-1');
    expect(useComposerDrafts(createPinia()).draftFor(recreated)).toBe('');
    expect(recreatedWithoutId).not.toBe(firstWithoutId);
    expect(firstWithoutId).toBe(composerDraftTargetKey(host, { id: null, name: 'work', createdEpoch: 100 }));
  });
});
