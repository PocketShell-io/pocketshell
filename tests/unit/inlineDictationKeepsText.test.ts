import { describe, expect, it, vi } from 'vitest';
import { createSharedDictationController, type DictationRecognitionPort } from '../../src/session/dictationController';
import {
  createInlineDictationController,
  safeInlineTranscriptText,
} from '../../src/session/inlineDictation';
import type { DictationEvent } from '../../src/session/platformInput';

function makeHarness() {
  let activeRequestId = '';
  let sequence = 0;
  const callbacks = new Map<string, (event: DictationEvent) => void>();
  const insertText = vi.fn(async () => true);
  const recognition: DictationRecognitionPort = {
    startRecognition: vi.fn(async (requestId, onEvent) => {
      activeRequestId = requestId;
      callbacks.set(requestId, onEvent);
    }),
    stopRecognition: vi.fn(async () => {}),
    cancelRecognition: vi.fn(async () => {}),
  };
  const controller = createInlineDictationController({
    createController: (options) => createSharedDictationController({
      ...options,
      recognition,
      restartDelayMs: 0,
      createRequestId: () => `turn-${++sequence}`,
    }),
    insertText,
  });
  const emit = (
    type: DictationEvent['type'],
    text?: string,
    requestId = activeRequestId,
    code?: string,
    callbackRequestId = activeRequestId,
  ) => {
    callbacks.get(callbackRequestId)?.({
      requestId,
      type,
      ...(text === undefined ? {} : { text }),
      ...(code === undefined ? {} : { code }),
    });
  };
  return { controller, recognition, insertText, emit, callbacks };
}

/**
 * Issue #3060 for the inline terminal dictation dock: screen-off, a target
 * change, the dock being disabled and recognizer errors keep the dictated
 * words visible for recovery (never written to the PTY without an explicit
 * Stop); an explicit Stop inserts the whole utterance, tail included.
 */
async function listening(harness: ReturnType<typeof makeHarness>) {
  harness.controller.start({ targetKey: 'host/session-1', languageTag: 'auto', silenceWindowMs: 4_000 });
  await vi.waitFor(() => expect(harness.controller.getState().phase).toBe('listening'));
}

describe('inline terminal dictation keeps dictated text', () => {
  it('keeps finals and the latest partial for recovery when the screen turns off', async () => {
    const harness = makeHarness();
    await listening(harness);
    harness.emit('result', 'first part');
    await vi.waitFor(() => expect(harness.recognition.startRecognition).toHaveBeenCalledTimes(2));
    harness.emit('partial', 'and the rest');

    harness.controller.setForeground(false);
    harness.emit('result', 'late callback');

    expect(harness.recognition.cancelRecognition).toHaveBeenCalledWith('turn-2');
    expect(harness.insertText).not.toHaveBeenCalled();
    expect(harness.controller.getState()).toMatchObject({
      phase: 'idle', preview: 'first part and the rest', tone: 'warning',
    });
  });

  it('keeps the text for recovery on a target change and on interrupt (dock closed)', async () => {
    const retarget = makeHarness();
    await listening(retarget);
    retarget.emit('partial', 'for the old pane');
    retarget.controller.setTarget('host/session-2');
    expect(retarget.controller.getState()).toMatchObject({ phase: 'idle', preview: 'for the old pane', tone: 'warning' });
    expect(retarget.insertText).not.toHaveBeenCalled();

    const closed = makeHarness();
    await listening(closed);
    closed.emit('partial', 'dock went away');
    closed.controller.interrupt();
    expect(closed.controller.getState()).toMatchObject({ phase: 'idle', preview: 'dock went away', tone: 'warning' });
    expect(closed.insertText).not.toHaveBeenCalled();
  });

  it('keeps the text for recovery when the recognizer fails mid-utterance', async () => {
    const harness = makeHarness();
    await listening(harness);
    harness.emit('partial', 'network dropped here');
    harness.emit('error', undefined, 'turn-1', 'network-error');

    expect(harness.controller.getState()).toMatchObject({ phase: 'idle', preview: 'network dropped here', tone: 'warning' });
    expect(harness.insertText).not.toHaveBeenCalled();
  });

  it.each([
    { name: 'a shorter final', finish: (h: ReturnType<typeof makeHarness>) => h.emit('result', "so let's", 'turn-1') },
    { name: 'no final', finish: (h: ReturnType<typeof makeHarness>) => h.emit('recoverable', undefined, 'turn-1', 'no-match') },
    { name: 'a stop timeout', finish: (h: ReturnType<typeof makeHarness>) => h.emit('error', undefined, 'turn-1', 'recognizer-stop-timeout') },
  ])('explicit Stop inserts the whole utterance after $name', async ({ finish }) => {
    const harness = makeHarness();
    await listening(harness);
    harness.emit('partial', "so let's solve it");

    harness.controller.stop();
    expect(harness.insertText).not.toHaveBeenCalled();
    finish(harness);

    await vi.waitFor(() => expect(harness.insertText).toHaveBeenCalledWith('host/session-1', "so let's solve it"));
  });

  it('an explicit user cancel still drops the text', async () => {
    const harness = makeHarness();
    await listening(harness);
    harness.emit('partial', 'do not keep');
    harness.controller.cancel();
    expect(harness.controller.getState()).toMatchObject({ phase: 'idle', preview: '', tone: 'quiet' });
  });
});
