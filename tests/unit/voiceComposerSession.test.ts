import { describe, expect, it, vi } from 'vitest';
import { createSharedDictationController, type DictationRecognitionPort } from '../../src/session/dictationController';
import type { DictationEvent } from '../../src/session/platformInput';
import { createVoiceComposerSession, joinDictatedDraft } from '../../src/sharedApp/extensions/voiceComposerSession';

/**
 * #3128: one-tap voice into the shared composer. The real Android adapter
 * (createSharedDictationController) and core's DictationController run here;
 * only the native recognizer is a fake that the test drives turn by turn.
 */
function harness(initialDraft = '') {
  const listeners = new Map<string, (event: DictationEvent) => void>();
  const started: string[] = [];
  const cancelled: string[] = [];
  const recognition: DictationRecognitionPort = {
    startRecognition: vi.fn(async (requestId, onEvent) => {
      listeners.set(requestId, onEvent);
      started.push(requestId);
    }),
    stopRecognition: vi.fn(async () => undefined),
    cancelRecognition: vi.fn(async (requestId) => {
      cancelled.push(requestId);
    }),
  };
  let appState: ((isActive: boolean) => void) | null = null;
  const session = createVoiceComposerSession({
    createController: () => createSharedDictationController({ recognition, restartDelayMs: 0 }),
    onAppStateChange: (listener) => {
      appState = listener;
    },
  });
  const drafts: Record<string, string> = { a: initialDraft, b: '' };
  const submits: Array<{ key: string; enter: boolean; draft: string }> = [];
  const context = (key: 'a' | 'b') => ({
    sessionKey: key,
    readDraft: () => drafts[key]!,
    setDraft: (text: string) => {
      drafts[key] = text;
    },
    submit: vi.fn(async ({ enter }: { enter: boolean }) => {
      submits.push({ key, enter, draft: drafts[key]! });
      drafts[key] = '';
      return true;
    }),
  });
  const current = () => started[started.length - 1]!;
  const emit = (event: Omit<DictationEvent, 'requestId'>) => listeners.get(current())!({ requestId: current(), ...event });
  const settle = () => new Promise<void>((resolve) => setTimeout(resolve, 5));
  return { session, drafts, submits, context, started, cancelled, emit, settle, background: (a: boolean) => appState!(a) };
}

describe('voice composer run (#3128)', () => {
  it('one start streams partials into the draft after what was already there', async () => {
    const h = harness('Fix the build.');
    expect(h.session.start(h.context('a'))).toBe(true);
    await h.settle();
    expect(h.session.state.value.phase).toBe('listening');
    h.emit({ type: 'partial', text: 'check the' });
    expect(h.drafts.a).toBe('Fix the build. check the');
    h.emit({ type: 'partial', text: 'check the deploy log' });
    expect(h.drafts.a).toBe('Fix the build. check the deploy log');
    expect(h.session.state.value.partial).toBe('check the deploy log');
  });

  it("the recognizer's own endpointing is a pause: listening goes on until the explicit stop", async () => {
    const h = harness();
    h.session.start(h.context('a'));
    await h.settle();
    h.emit({ type: 'partial', text: 'first' });
    h.emit({ type: 'result', text: 'first part' });
    await h.settle();
    expect(h.session.state.value.phase).toBe('listening');
    expect(h.started).toHaveLength(2);
    h.emit({ type: 'recoverable', code: 'speech-timeout' });
    await h.settle();
    expect(h.session.state.value.phase).toBe('listening');
    expect(h.started).toHaveLength(3);
    h.emit({ type: 'partial', text: 'second part' });
    expect(h.drafts.a).toBe('first part second part');
    expect(h.session.isActive()).toBe(true);

    h.session.stop();
    h.emit({ type: 'result', text: 'second part.' });
    await h.settle();
    expect(h.session.state.value.phase).toBe('completed');
    expect(h.session.state.value.reviewing).toBe(true);
    expect(h.drafts.a).toBe('first part second part.');
    expect(h.started).toHaveLength(3);
    expect(h.submits).toEqual([]);
  });

  it('Send delivers with Enter, Insert without; a showing partial is delivered too', async () => {
    const h = harness();
    h.session.start(h.context('a'));
    await h.settle();
    h.emit({ type: 'partial', text: 'echo hello' });
    await expect(h.session.deliver(h.context('a'), false)).resolves.toBe(true);
    expect(h.submits).toEqual([{ key: 'a', enter: false, draft: 'echo hello' }]);
    expect(h.session.isActive()).toBe(false);
    expect(h.cancelled).toHaveLength(1);

    h.session.start(h.context('a'));
    await h.settle();
    h.emit({ type: 'partial', text: 'run the tests' });
    h.session.stop();
    h.emit({ type: 'result', text: 'run the tests' });
    await h.session.deliver(h.context('a'), true);
    expect(h.submits[1]).toEqual({ key: 'a', enter: true, draft: 'run the tests' });
    expect(h.session.state.value.phase).toBe('idle');
  });

  it('Discard restores the draft the run started from and stops the recognizer', async () => {
    const h = harness('keep me');
    h.session.start(h.context('a'));
    await h.settle();
    h.emit({ type: 'partial', text: 'throw this away' });
    expect(h.drafts.a).toBe('keep me throw this away');
    h.session.discard();
    expect(h.drafts.a).toBe('keep me');
    expect(h.session.isActive()).toBe(false);
    expect(h.cancelled).toHaveLength(1);
    expect(h.submits).toEqual([]);
  });

  it('background stops the recognizer, keeps every word in the draft and writes nothing', async () => {
    const h = harness();
    h.session.start(h.context('a'));
    await h.settle();
    h.emit({ type: 'partial', text: 'before home' });
    const turn = h.started[0]!;
    h.background(false);
    expect(h.session.isActive()).toBe(false);
    expect(h.cancelled).toEqual([turn]);
    expect(h.drafts.a).toBe('before home');
    expect(h.submits).toEqual([]);
    // A late event from the abandoned turn changes nothing.
    h.emit({ type: 'partial', text: 'late words' });
    expect(h.drafts.a).toBe('before home');
    h.background(true);
    expect(h.session.isActive()).toBe(false);
    expect(h.session.state.value.reviewing).toBe(true);
  });

  it('a session switch stops the run in its own draft; the next session is untouched', async () => {
    const h = harness();
    h.session.start(h.context('a'));
    await h.settle();
    h.emit({ type: 'partial', text: 'for a' });
    h.session.interrupt('target-change');
    expect(h.session.isActive()).toBe(false);
    expect(h.drafts).toEqual({ a: 'for a', b: '' });
    // Delivering from b cannot send a's review.
    await expect(h.session.deliver(h.context('b'), true)).resolves.toBe(false);
    expect(h.submits).toEqual([]);
  });

  it('editing the draft mid-run hands it back to the user: the run stops and never writes over it', async () => {
    const h = harness();
    h.session.start(h.context('a'));
    await h.settle();
    h.emit({ type: 'partial', text: 'spoken' });
    h.drafts.a = 'spoken, then typed';
    h.emit({ type: 'partial', text: 'spoken more' });
    expect(h.drafts.a).toBe('spoken, then typed');
    expect(h.session.isActive()).toBe(false);
    expect(h.session.state.value.phase).toBe('idle');
  });

  it('a second start while listening is refused', async () => {
    const h = harness();
    expect(h.session.start(h.context('a'))).toBe(true);
    await h.settle();
    expect(h.session.start(h.context('b'))).toBe(false);
    expect(h.started).toHaveLength(1);
  });

  it('joins the spoken words onto the draft with one space', () => {
    expect(joinDictatedDraft('', 'a', '')).toBe('a');
    expect(joinDictatedDraft('x', 'a', 'b')).toBe('x a b');
    expect(joinDictatedDraft('x\n', '', 'b')).toBe('x\nb');
    expect(joinDictatedDraft('x', '', '  ')).toBe('x');
  });
});
