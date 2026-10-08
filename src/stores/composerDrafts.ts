import { defineStore } from 'pinia';

/**
 * Drafts are isolated by selected PTY identity and persisted per target, so a
 * dictated or typed draft survives a WebView/process restart (#3060). A sent
 * or discarded draft is removed from storage with `clearDraft`.
 */
export const COMPOSER_DRAFTS_STORAGE_KEY = 'pocketshell.composerDrafts.v1';

/**
 * The draft identity for one PTY on one host. Drafts are persisted, so the
 * key must not let a later session that merely reuses a name inherit an old
 * draft (#3060 review): use the host CLI's session id, and only when an old
 * host reports none, the name plus its creation time.
 */
export function composerDraftTargetKey(
  hostKey: string,
  session: { id: string | null; name: string; createdEpoch: number | null },
): string {
  if (session.id) return `${hostKey}/${session.id}`;
  return `${hostKey}/${session.name}@${session.createdEpoch ?? 'unknown'}`;
}

interface DraftStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

function draftStorage(): DraftStorage | null {
  try {
    const storage = (globalThis as { localStorage?: DraftStorage }).localStorage;
    return storage ?? null;
  } catch {
    return null;
  }
}

function readDrafts(): Record<string, string> {
  let raw: string | null = null;
  try {
    raw = draftStorage()?.getItem(COMPOSER_DRAFTS_STORAGE_KEY) ?? null;
  } catch {
    return {};
  }
  if (!raw) return {};
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return {};
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) return {};
  const drafts: Record<string, string> = {};
  for (const [targetKey, draft] of Object.entries(parsed as Record<string, unknown>)) {
    if (targetKey && typeof draft === 'string' && draft.length > 0) drafts[targetKey] = draft;
  }
  return drafts;
}

function writeDrafts(drafts: Record<string, string>): void {
  try {
    const storage = draftStorage();
    if (!storage) return;
    if (Object.keys(drafts).length === 0) storage.removeItem(COMPOSER_DRAFTS_STORAGE_KEY);
    else storage.setItem(COMPOSER_DRAFTS_STORAGE_KEY, JSON.stringify(drafts));
  } catch {
    // Storage can be unavailable or full; the in-memory draft still stands.
  }
}

export const useComposerDrafts = defineStore('composerDrafts', {
  state: () => ({ byTarget: readDrafts() }),
  getters: {
    draftFor: (state) => (targetKey: string): string => state.byTarget[targetKey] ?? '',
  },
  actions: {
    setDraft(targetKey: string, draft: string) {
      if (!targetKey) return;
      if (draft.length === 0) delete this.byTarget[targetKey];
      else this.byTarget[targetKey] = draft;
      writeDrafts(this.byTarget);
    },
    clearDraft(targetKey: string) {
      if (!targetKey) return;
      delete this.byTarget[targetKey];
      writeDrafts(this.byTarget);
    },
  },
});
