import { defineStore } from 'pinia';

/** Drafts stay in memory for the app lifetime and are isolated by selected PTY identity. */
export const useComposerDrafts = defineStore('composerDrafts', {
  state: () => ({ byTarget: {} as Record<string, string> }),
  getters: {
    draftFor: (state) => (targetKey: string): string => state.byTarget[targetKey] ?? '',
  },
  actions: {
    setDraft(targetKey: string, draft: string) {
      if (!targetKey) return;
      this.byTarget[targetKey] = draft;
    },
    clearDraft(targetKey: string) {
      if (!targetKey) return;
      delete this.byTarget[targetKey];
    },
  },
});
