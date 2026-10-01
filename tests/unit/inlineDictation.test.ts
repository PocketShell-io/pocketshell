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

describe('inline terminal dictation', () => {
  it('previews partials and inserts only sanitized final segments after explicit Stop', async () => {
    const harness = makeHarness();
    const states: string[] = [];
    harness.controller.subscribe((state) => states.push(`${state.phase}:${state.preview}`));

    harness.controller.start({ targetKey: 'host/session-1', languageTag: 'de-DE', silenceWindowMs: 9_000 });
    await vi.waitFor(() => expect(harness.recognition.startRecognition).toHaveBeenCalledTimes(1));
    await vi.waitFor(() => expect(harness.controller.getState().phase).toBe('listening'));
    expect(harness.recognition.startRecognition).toHaveBeenCalledWith('turn-1', expect.any(Function), {
      languageTag: 'de-DE', silenceWindowMs: 9_000,
    });
    harness.emit('partial', "printf '%s' 'caf");
    expect(harness.controller.getState().preview).toBe("printf '%s' 'caf");
    expect(harness.insertText).not.toHaveBeenCalled();

    harness.emit('result', 'first final segment');
    expect(harness.insertText).not.toHaveBeenCalled();
    await vi.waitFor(() => expect(harness.recognition.startRecognition).toHaveBeenCalledTimes(2));
    await vi.waitFor(() => expect(harness.controller.getState().phase).toBe('listening'));

    harness.controller.stop();
    expect(harness.recognition.stopRecognition).toHaveBeenCalledWith('turn-2');
    harness.emit('result', "printf '%s' 'café");
    await vi.waitFor(() => expect(harness.insertText).toHaveBeenCalledOnce());

    expect(harness.insertText).toHaveBeenCalledWith('host/session-1', "first final segment printf '%s' 'café");
    expect(harness.controller.getState()).toMatchObject({
      phase: 'idle', preview: '', tone: 'success', message: 'Inserted at the cursor. Press Enter to run.',
    });
    expect(states).toContain("listening:printf '%s' 'caf");
    expect(harness.recognition.startRecognition).toHaveBeenCalledTimes(2);
  });

  it('keeps the final transcript available when PTY insertion returns false', async () => {
    const harness = makeHarness();
    harness.insertText.mockResolvedValue(false);
    harness.controller.start({ targetKey: 'host/session-1', languageTag: 'auto', silenceWindowMs: 4_000 });
    await vi.waitFor(() => expect(harness.recognition.startRecognition).toHaveBeenCalledTimes(1));
    await vi.waitFor(() => expect(harness.controller.getState().phase).toBe('listening'));

    harness.controller.stop();
    harness.emit('result', 'recover this command', 'turn-1');

    await vi.waitFor(() => expect(harness.controller.getState()).toMatchObject({
      phase: 'idle',
      preview: 'recover this command',
      message: 'Terminal insertion was not confirmed. Check for partial text before copying or trying again.',
      tone: 'warning',
    }));
    expect(harness.insertText).toHaveBeenCalledOnce();
    expect(harness.insertText).toHaveBeenCalledWith('host/session-1', 'recover this command');
    expect(harness.recognition.startRecognition).toHaveBeenCalledTimes(1);
  });

  it('keeps the final transcript available when PTY insertion rejects', async () => {
    const harness = makeHarness();
    harness.insertText.mockRejectedValue(new Error('bridge result unknown'));
    harness.controller.start({ targetKey: 'host/session-1', languageTag: 'auto', silenceWindowMs: 4_000 });
    await vi.waitFor(() => expect(harness.recognition.startRecognition).toHaveBeenCalledTimes(1));
    await vi.waitFor(() => expect(harness.controller.getState().phase).toBe('listening'));

    harness.controller.stop();
    harness.emit('result', 'possibly inserted command', 'turn-1');

    await vi.waitFor(() => expect(harness.controller.getState()).toMatchObject({
      phase: 'idle',
      preview: 'possibly inserted command',
      message: 'Terminal insertion was not confirmed. Check for partial text before copying or trying again.',
      tone: 'warning',
    }));
    expect(harness.insertText).toHaveBeenCalledOnce();
    expect(harness.insertText).toHaveBeenCalledWith('host/session-1', 'possibly inserted command');
    expect(harness.recognition.startRecognition).toHaveBeenCalledTimes(1);
  });

  it('restarts after recoverable turns, isolates request IDs, and does not rearm after Stop', async () => {
    const harness = makeHarness();
    harness.controller.start({ targetKey: 'host/session-1', languageTag: 'auto', silenceWindowMs: 4_000 });
    await vi.waitFor(() => expect(harness.recognition.startRecognition).toHaveBeenCalledTimes(1));
    await vi.waitFor(() => expect(harness.controller.getState().phase).toBe('listening'));

    harness.emit('result', 'foreign segment', 'foreign-request');
    expect(harness.controller.getState().preview).toBe('');
    harness.emit('recoverable', undefined, 'turn-1', 'speech-timeout');
    await vi.waitFor(() => expect(harness.recognition.startRecognition).toHaveBeenCalledTimes(2));
    await vi.waitFor(() => expect(harness.controller.getState().phase).toBe('listening'));
    expect(harness.controller.getState().preview).toBe('');

    harness.emit('recoverable', undefined, 'turn-2', 'no-match');
    await vi.waitFor(() => expect(harness.recognition.startRecognition).toHaveBeenCalledTimes(3));
    await vi.waitFor(() => expect(harness.controller.getState().phase).toBe('listening'));
    harness.controller.stop();
    expect(harness.recognition.stopRecognition).toHaveBeenCalledWith('turn-3');
    harness.emit('result', 'final\nsegment\u001b[31m', 'turn-3');

    await vi.waitFor(() => expect(harness.insertText).toHaveBeenCalledOnce());
    expect(harness.insertText).toHaveBeenCalledWith('host/session-1', 'final segment [31m');
    expect(harness.recognition.startRecognition).toHaveBeenCalledTimes(3);
  });

  it('cancels the active request and ignores a late final callback', async () => {
    const harness = makeHarness();
    harness.controller.start({ targetKey: 'host/session-1', languageTag: 'auto', silenceWindowMs: 4_000 });
    await vi.waitFor(() => expect(harness.recognition.startRecognition).toHaveBeenCalledTimes(1));
    await vi.waitFor(() => expect(harness.controller.getState().phase).toBe('listening'));
    harness.emit('partial', 'do not run this');

    harness.controller.cancel('background');
    harness.emit('result', 'do not run this');

    expect(harness.recognition.cancelRecognition).toHaveBeenCalledWith('turn-1');
    expect(harness.insertText).not.toHaveBeenCalled();
    expect(harness.controller.getState()).toMatchObject({ phase: 'idle', preview: '', tone: 'quiet' });
  });

  it('does not insert when explicit Stop receives no final result', async () => {
    const harness = makeHarness();
    harness.controller.start({ targetKey: 'host/session-1', languageTag: 'auto', silenceWindowMs: 4_000 });
    await vi.waitFor(() => expect(harness.recognition.startRecognition).toHaveBeenCalledTimes(1));
    await vi.waitFor(() => expect(harness.controller.getState().phase).toBe('listening'));
    harness.emit('partial', 'unconfirmed words');

    harness.controller.stop();
    harness.emit('recoverable', undefined, 'turn-1', 'no-match');

    await vi.waitFor(() => expect(harness.controller.getState().phase).toBe('idle'));
    expect(harness.insertText).not.toHaveBeenCalled();
    expect(harness.controller.getState()).toMatchObject({
      phase: 'idle',
      message: 'No final transcript was received. Nothing was inserted.',
      tone: 'warning',
    });
    expect(harness.recognition.startRecognition).toHaveBeenCalledTimes(1);
  });

  it('reports native errors and does not write speech', async () => {
    const harness = makeHarness();
    harness.controller.start({ targetKey: 'host/session-1', languageTag: 'auto', silenceWindowMs: 4_000 });
    await vi.waitFor(() => expect(harness.recognition.startRecognition).toHaveBeenCalledTimes(1));
    await vi.waitFor(() => expect(harness.controller.getState().phase).toBe('listening'));
    harness.emit('error', undefined, 'turn-1', 'permission-denied');

    expect(harness.controller.getState()).toMatchObject({
      phase: 'idle', tone: 'error',
      message: 'Microphone permission denied. Allow microphone access in Android settings to use dictation.',
    });
    expect(harness.insertText).not.toHaveBeenCalled();
  });

  it('sanitizes terminal controls out of final text', () => {
    expect(safeInlineTranscriptText('a\rb\u001b[31m\u0007c')).toBe('a b [31m c');
  });
});
