import { defineStore } from 'pinia';
import type { AttachmentSource } from '@pocketshell/core';
import type {
  ByteAttachmentSource,
  PendingComposerAttachment,
  StagedComposerAttachment,
} from '../session/composerAttachments';

export interface ComposerAttachmentIssue {
  id: string;
  name: string;
  message: string;
}

function attachmentId(): string {
  const random = globalThis.crypto?.randomUUID?.() ?? Math.random().toString(36).slice(2, 12);
  return `composer-attachment-${random}`;
}

/** Draft text and staged attachment references are isolated by selected PTY identity. */
export const useComposerDrafts = defineStore('composerDrafts', {
  state: () => ({
    byTarget: {} as Record<string, string>,
    pendingByTarget: {} as Record<string, PendingComposerAttachment[]>,
    stagedByTarget: {} as Record<string, StagedComposerAttachment[]>,
    issuesByTarget: {} as Record<string, ComposerAttachmentIssue[]>,
    revisionByTarget: {} as Record<string, number>,
    nextOrderByTarget: {} as Record<string, number>,
  }),
  getters: {
    draftFor: (state) => (targetKey: string): string => state.byTarget[targetKey] ?? '',
    pendingAttachmentsFor: (state) => (targetKey: string): PendingComposerAttachment[] =>
      [...(state.pendingByTarget[targetKey] ?? [])].sort((left, right) => left.order - right.order),
    stagedAttachmentsFor: (state) => (targetKey: string): StagedComposerAttachment[] =>
      [...(state.stagedByTarget[targetKey] ?? [])].sort((left, right) => left.order - right.order),
    attachmentIssuesFor: (state) => (targetKey: string): ComposerAttachmentIssue[] =>
      state.issuesByTarget[targetKey] ?? [],
    revisionFor: (state) => (targetKey: string): number => state.revisionByTarget[targetKey] ?? 0,
  },
  actions: {
    bumpRevision(targetKey: string) {
      if (!targetKey) return;
      this.revisionByTarget[targetKey] = (this.revisionByTarget[targetKey] ?? 0) + 1;
    },
    setDraft(targetKey: string, draft: string) {
      if (!targetKey) return;
      this.byTarget[targetKey] = draft;
      this.bumpRevision(targetKey);
    },
    appendSharedText(targetKey: string, subject?: string, text?: string) {
      if (!targetKey) return;
      const incoming = [subject, text].filter((value): value is string => typeof value === 'string' && value.length > 0).join('\n');
      if (!incoming) return;
      const previous = this.draftFor(targetKey);
      const separator = previous.length === 0 ? '' : previous.endsWith('\n') ? '' : '\n\n';
      this.byTarget[targetKey] = `${previous}${separator}${incoming}`;
      this.bumpRevision(targetKey);
    },
    addPendingAttachments(targetKey: string, sources: readonly AttachmentSource[]): PendingComposerAttachment[] {
      if (!targetKey) return [];
      const pending = this.pendingByTarget[targetKey] ?? (this.pendingByTarget[targetKey] = []);
      const added: PendingComposerAttachment[] = [];
      for (const source of sources) {
        if (source.kind !== 'bytes' || !(source.data instanceof Uint8Array)) continue;
        const byteSource: ByteAttachmentSource = {
          kind: 'bytes',
          data: source.data.slice(),
          name: source.name ?? null,
          mimeType: source.mimeType ?? null,
        };
        const order = this.nextOrderByTarget[targetKey] ?? 0;
        this.nextOrderByTarget[targetKey] = order + 1;
        const attachment = { id: attachmentId(), order, source: byteSource };
        pending.push(attachment);
        added.push(attachment);
      }
      if (added.length > 0) this.bumpRevision(targetKey);
      return added;
    },
    markAttachmentsStaged(targetKey: string, staged: readonly {
      id: string;
      path: string;
      name: string;
      sizeBytes: number;
    }[]) {
      if (!targetKey || staged.length === 0) return;
      const pending = this.pendingByTarget[targetKey] ?? [];
      const stagedIds = new Set(staged.map((attachment) => attachment.id));
      const additions = staged.flatMap((result) => {
        const source = pending.find((item) => item.id === result.id);
        return source ? [{ ...result, order: source.order }] : [];
      });
      if (additions.length === 0) return;
      this.pendingByTarget[targetKey] = pending.filter((item) => !stagedIds.has(item.id));
      this.stagedByTarget[targetKey] = [...(this.stagedByTarget[targetKey] ?? []), ...additions]
        .sort((left, right) => left.order - right.order);
      this.issuesByTarget[targetKey] = (this.issuesByTarget[targetKey] ?? [])
        .filter((issue) => !stagedIds.has(issue.id));
      this.bumpRevision(targetKey);
    },
    removeAttachment(targetKey: string, id: string) {
      if (!targetKey || !id) return;
      const pending = this.pendingByTarget[targetKey] ?? [];
      const staged = this.stagedByTarget[targetKey] ?? [];
      const nextPending = pending.filter((item) => item.id !== id);
      const nextStaged = staged.filter((item) => item.id !== id);
      if (nextPending.length === pending.length && nextStaged.length === staged.length) return;
      this.pendingByTarget[targetKey] = nextPending;
      this.stagedByTarget[targetKey] = nextStaged;
      this.issuesByTarget[targetKey] = (this.issuesByTarget[targetKey] ?? []).filter((issue) => issue.id !== id);
      this.bumpRevision(targetKey);
    },
    addAttachmentIssue(targetKey: string, issue: ComposerAttachmentIssue) {
      if (!targetKey) return;
      const current = this.issuesByTarget[targetKey] ?? [];
      if (current.some((entry) => entry.id === issue.id)) {
        this.issuesByTarget[targetKey] = current.map((entry) => entry.id === issue.id ? issue : entry);
      } else {
        this.issuesByTarget[targetKey] = [...current, issue];
      }
    },
    clearDraftIfRevision(targetKey: string, expectedRevision: number): boolean {
      if (!targetKey || this.revisionFor(targetKey) !== expectedRevision) return false;
      this.clearDraft(targetKey);
      return true;
    },
    clearDraft(targetKey: string) {
      if (!targetKey) return;
      delete this.byTarget[targetKey];
      delete this.pendingByTarget[targetKey];
      delete this.stagedByTarget[targetKey];
      delete this.issuesByTarget[targetKey];
      this.nextOrderByTarget[targetKey] = 0;
      this.bumpRevision(targetKey);
    },
  },
});
