import { describe, expect, it, vi } from 'vitest';
import {
  createInlineDictationController,
  safeInlineTranscriptText,
  type InlineDictationSession,
} from '../../src/session/inlineDictation';
import type { DictationEvent } from '../../src/session/platformInput';

function makeHarness() {
  let emit: ((event: DictationEvent) => void) | undefined;
  const insertText = vi.fn(async () => true);
  const stop = vi.fn(async () => {
    emit?.({ requestId: 'inline-1', type: 'stopped' });
  });
  const session: InlineDictationSession = { requestId: 'inline-1', stop };
  const startDictation = vi.fn(async (onEvent: (event: DictationEvent) => void) => {
    emit = onEvent;
    emit({ requestId: 'inline-1', type: 'started' });
    return session;
  });
  const controller = createInlineDictationController({ startDictation, insertText });
  return { controller, startDictation, insertText, stop, emit: (event: DictationEvent) => emit?.(event) };
}

describe('inline terminal dictation', () => {
  it('previews partials, buffers final phrases until explicit Stop, then inserts settings-backed safe text once', async () => {
    const harness = makeHarness();
    const states: string[] = [];
    harness.controller.subscribe((state) => states.push(`${state.phase}:${state.preview}`));

    await harness.controller.start({
      targetKey: 'host/session-1',
      languageTag: 'de-DE',
      silenceWindowMs: 9_000,
    });

    expect(harness.startDictation).toHaveBeenCalledWith(expect.any(Function), {
      languageTag: 'de-DE',
      silenceWindowMs: 9_000,
    });
    expect(harness.controller.getState().phase).toBe('listening');
    harness.emit({ requestId: 'inline-1', type: 'partial', text: "printf '%s' 'caf" });
    expect(harness.controller.getState().preview).toBe("printf '%s' 'caf");
    expect(harness.insertText).not.toHaveBeenCalled();

    harness.emit({ requestId: 'inline-1', type: 'result', text: "printf '%s' 'café" });
    expect(harness.insertText).not.toHaveBeenCalled();
    expect(harness.controller.getState().phase).toBe('listening');

    await harness.controller.stop();

    await vi.waitFor(() => expect(harness.controller.getState().phase).toBe('idle'));
    expect(harness.stop).toHaveBeenCalledTimes(1);
    expect(harness.insertText).toHaveBeenCalledOnce();
    expect(harness.insertText).toHaveBeenCalledWith('host/session-1', "printf '%s' 'café");
    expect(harness.controller.getState()).toMatchObject({
      phase: 'idle',
      preview: '',
      tone: 'success',
      message: 'Inserted at the cursor. Press Enter to run.',
    });
    expect(states).toContain("listening:printf '%s' 'caf");
  });

  it('inserts multiple final segments but replaces terminal controls so speech cannot press Enter', async () => {
    const harness = makeHarness();
    await harness.controller.start({ targetKey: 'host/session-1', languageTag: 'auto', silenceWindowMs: 4_000 });
    harness.emit({ requestId: 'inline-1', type: 'result', text: 'echo first\nsecond' });
    harness.emit({ requestId: 'inline-1', type: 'result', text: 'café 🧪' });
    expect(harness.insertText).not.toHaveBeenCalled();

    await harness.controller.stop();

    await vi.waitFor(() => expect(harness.insertText).toHaveBeenCalledOnce());
    expect(harness.insertText).toHaveBeenCalledWith('host/session-1', 'echo first second café 🧪');
    expect(safeInlineTranscriptText('a\rb\u001b[31m\u0007c')).toBe('a b [31m c');
  });

  it('cancels on background and ignores final events that arrive after cancellation', async () => {
    const harness = makeHarness();
    await harness.controller.start({ targetKey: 'host/session-1', languageTag: 'auto', silenceWindowMs: 4_000 });
    harness.emit({ requestId: 'inline-1', type: 'partial', text: 'do not run this' });

    await harness.controller.cancel();
    harness.emit({ requestId: 'inline-1', type: 'result', text: 'do not run this' });
    harness.emit({ requestId: 'inline-1', type: 'stopped' });

    expect(harness.insertText).not.toHaveBeenCalled();
    expect(harness.controller.getState()).toMatchObject({ phase: 'idle', preview: '', tone: 'quiet' });
  });

  it('stops a start that resolves after cancellation without forwarding its final text', async () => {
    let resolveStart!: (session: InlineDictationSession) => void;
    let emit: ((event: DictationEvent) => void) | undefined;
    const insertText = vi.fn(async () => true);
    const stop = vi.fn(async () => emit?.({ requestId: 'inline-pending', type: 'stopped' }));
    const session = { requestId: 'inline-pending', stop };
    const controller = createInlineDictationController({
      startDictation: vi.fn((onEvent) => {
        emit = onEvent;
        return new Promise<InlineDictationSession>((resolve) => { resolveStart = resolve; });
      }),
      insertText,
    });

    const starting = controller.start({ targetKey: 'host/session-1', languageTag: 'auto', silenceWindowMs: 4_000 });
    await controller.cancel();
    resolveStart(session);
    emit?.({ requestId: 'inline-pending', type: 'result', text: 'late phrase' });
    await starting;

    expect(stop).toHaveBeenCalledOnce();
    expect(insertText).not.toHaveBeenCalled();
  });

  it('reports denied microphone access and does not write any text', async () => {
    const insertText = vi.fn(async () => true);
    const controller = createInlineDictationController({
      startDictation: vi.fn(async () => {
        throw Object.assign(new Error('Microphone permission was denied.'), { code: 'MICROPHONE_PERMISSION_DENIED' });
      }),
      insertText,
    });

    await controller.start({ targetKey: 'host/session-1', languageTag: 'auto', silenceWindowMs: 4_000 });

    expect(controller.getState()).toMatchObject({
      phase: 'idle',
      tone: 'error',
      message: 'Microphone permission denied. Allow microphone access in Android settings to use dictation.',
    });
    expect(insertText).not.toHaveBeenCalled();
  });

  it('does not turn a partial into a terminal write when Stop receives no final result', async () => {
    const harness = makeHarness();
    await harness.controller.start({ targetKey: 'host/session-1', languageTag: 'auto', silenceWindowMs: 4_000 });
    harness.emit({ requestId: 'inline-1', type: 'partial', text: 'unconfirmed words' });

    await harness.controller.stop();

    expect(harness.insertText).not.toHaveBeenCalled();
    expect(harness.controller.getState()).toMatchObject({
      phase: 'idle',
      message: 'No final transcript was received. Nothing was inserted.',
      tone: 'warning',
    });
  });
});
