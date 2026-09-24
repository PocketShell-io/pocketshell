import { beforeEach, describe, expect, it } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { appendAttachmentPaths, nextAttachmentTimestamp } from '../../src/session/composerAttachments';
import { useComposerDrafts } from '../../src/stores/composerDrafts';

describe('JS composer attachment state', () => {
  beforeEach(() => setActivePinia(createPinia()));

  it('appends staged paths only to the outgoing payload and keeps attachment-only sends valid', () => {
    expect(appendAttachmentPaths('Review café', ['/home/test/a file.bin']))
      .toBe('Review café\n\nAttached files:\n- /home/test/a file.bin');
    expect(appendAttachmentPaths('  ', ['/home/test/only.bin']))
      .toBe('Attached files:\n- /home/test/only.bin');
    expect(appendAttachmentPaths('draft\n', ['/home/test/one.bin']))
      .toBe('draft\n\nAttached files:\n- /home/test/one.bin');
  });

  it('keeps share bytes and text per target, retains failed uploads, and clears only the acknowledged revision', () => {
    const drafts = useComposerDrafts();
    const sourceBytes = Uint8Array.from([0, 0xff, 0x41]);
    const targetA = 'testuser@fixture/session-a';
    const targetB = 'testuser@fixture/session-b';
    drafts.setDraft(targetA, 'Review the share');
    drafts.appendSharedText(targetA, 'Shared subject', 'café 🧪');
    drafts.addPendingAttachments(targetA, [{
      kind: 'bytes',
      data: sourceBytes,
      name: 'notes.bin',
      mimeType: 'application/octet-stream',
    }]);
    sourceBytes[1] = 0;

    const pending = drafts.pendingAttachmentsFor(targetA);
    expect(drafts.draftFor(targetA)).toBe('Review the share\n\nShared subject\ncafé 🧪');
    expect(pending[0]?.source.data).toEqual(Uint8Array.from([0, 0xff, 0x41]));
    expect(drafts.pendingAttachmentsFor(targetB)).toEqual([]);

    drafts.addAttachmentIssue(targetA, { id: pending[0]!.id, name: 'notes.bin', message: 'SFTP unavailable' });
    expect(drafts.pendingAttachmentsFor(targetA)).toHaveLength(1);
    expect(drafts.attachmentIssuesFor(targetA)).toMatchObject([{ id: pending[0]!.id, message: 'SFTP unavailable' }]);
    drafts.markAttachmentsStaged(targetA, [{
      id: pending[0]!.id,
      path: '/home/testuser/.pocketshell/attachments/notes.bin',
      name: 'notes.bin',
      sizeBytes: 3,
    }]);
    expect(drafts.pendingAttachmentsFor(targetA)).toEqual([]);
    expect(drafts.stagedAttachmentsFor(targetA)).toMatchObject([{ path: '/home/testuser/.pocketshell/attachments/notes.bin', sizeBytes: 3 }]);
    expect(drafts.attachmentIssuesFor(targetA)).toEqual([]);

    const sentRevision = drafts.revisionFor(targetA);
    drafts.appendSharedText(targetA, undefined, 'new share after send started');
    expect(drafts.clearDraftIfRevision(targetA, sentRevision)).toBe(false);
    expect(drafts.draftFor(targetA)).toContain('new share after send started');
    expect(drafts.stagedAttachmentsFor(targetA)).toHaveLength(1);
  });

  it('creates unique host-compatible stage prefixes for picks in the same second', () => {
    const instant = new Date('2026-09-24T10:11:12.000Z');
    expect(nextAttachmentTimestamp(instant)).toBe('20260924-101112');
    expect(nextAttachmentTimestamp(instant)).toBe('20260924-101113');
  });
});
