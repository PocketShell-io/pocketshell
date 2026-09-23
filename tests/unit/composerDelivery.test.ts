import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { createComposerDeliveryController } from '../../src/session/composerDelivery';
import { useComposerDrafts } from '../../src/stores/composerDrafts';

describe('composer delivery adapter and per-target drafts', () => {
  beforeEach(() => setActivePinia(createPinia()));

  it('retains an ambiguous draft and does not replay it after reconnect', async () => {
    const drafts = useComposerDrafts();
    const targetKey = 'testuser@fixture/session-a';
    const draft = 'café 🧪\nsecond line';
    drafts.setDraft(targetKey, draft);
    const writePty = vi.fn(async () => ({ ok: false, message: 'PTY write acknowledgement was lost.' }));
    const delivery = createComposerDeliveryController(writePty, async () => undefined);
    delivery.setTransportState('connected');

    const result = await delivery.deliver({ operationId: 'ambiguous-send', payload: drafts.draftFor(targetKey), intent: 'submit' });
    if (result.draftEffect === 'clear') drafts.clearDraft(targetKey);

    expect(result).toMatchObject({ status: 'uncertain', draftEffect: 'retain', stage: 'write' });
    expect(drafts.draftFor(targetKey)).toBe(draft);

    delivery.setTransportState('lost');
    delivery.setTransportState('connected');
    expect(drafts.draftFor(targetKey)).toBe(draft);
    expect(writePty).toHaveBeenCalledTimes(1);
  });

  it('keeps one session draft separate from another and clears only after acknowledged delivery', async () => {
    const drafts = useComposerDrafts();
    const writePty = vi.fn(async () => ({ ok: true }));
    const delivery = createComposerDeliveryController(writePty, async () => undefined);
    delivery.setTransportState('connected');
    drafts.setDraft('host/session-a', 'first draft');
    drafts.setDraft('host/session-b', 'other session');

    const result = await delivery.deliver({ operationId: 'insert-a', payload: drafts.draftFor('host/session-a'), intent: 'insert' });
    if (result.draftEffect === 'clear') drafts.clearDraft('host/session-a');

    expect(result).toMatchObject({ status: 'delivered', intent: 'insert', draftEffect: 'clear' });
    expect(drafts.draftFor('host/session-a')).toBe('');
    expect(drafts.draftFor('host/session-b')).toBe('other session');
  });
});
