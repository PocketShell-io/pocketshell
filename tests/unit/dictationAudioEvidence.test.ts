import { afterEach, describe, expect, it, vi } from 'vitest';
import type { DocumentContentPlugin, NativePickedDocument } from '../../src/native/documentContent';
import type { NativeDictationEvent, SpeechRecognitionPlugin } from '../../src/native/speechRecognition';
import { createSharedDictationController, type DictationRecognitionPort } from '../../src/session/dictationController';
import { createPlatformInputService, type DictationEvent } from '../../src/session/platformInput';
import { noSpeechWarningCopy } from '../../src/session/dictationNoSpeech';

/**
 * Issue #3062: Android forwards a coarse, bounded "sound / silence" flag from
 * SpeechRecognizer.onRmsChanged so the no-recognized-text warning can tell
 * "no sound reaching the mic" from "sound, but no words". The flag must travel
 * through the platform adapter without ending the recognizer turn, and reach
 * the shared controller's audio evidence.
 */

function speechPlugin() {
  let listener: ((event: NativeDictationEvent) => void) | undefined;
  const removed = vi.fn(async () => { listener = undefined; });
  const plugin = {
    getCapabilities: vi.fn(async () => ({ speechRecognitionAvailable: true, microphonePermissionGranted: true })),
    startDictation: vi.fn(async ({ requestId }: { requestId: string }) => ({ requestId, started: true })),
    stopDictation: vi.fn(async ({ requestId }: { requestId: string }) => ({ requestId, stopped: true })),
    cancelDictation: vi.fn(async ({ requestId }: { requestId: string }) => ({ requestId, cancelled: true })),
    injectTestDictationEvent: vi.fn(),
    addListener: vi.fn(async (_name: string, next: (event: NativeDictationEvent) => void) => {
      listener = next;
      return { remove: removed };
    }),
    removeAllListeners: vi.fn(async () => {}),
    emit(event: NativeDictationEvent) { listener?.(event); },
    removed,
  };
  return plugin;
}

const noDocuments = {
  pickFiles: vi.fn(async () => ({ cancelled: true, files: [] as NativePickedDocument[] })),
  readPickedFileChunk: vi.fn(),
  releasePickedFile: vi.fn(),
  addListener: vi.fn(),
} as unknown as DocumentContentPlugin;

function fakePort() {
  const sinks = new Map<string, (event: DictationEvent) => void>();
  let latest = '';
  const port: DictationRecognitionPort = {
    startRecognition: async (requestId, onEvent) => {
      sinks.set(requestId, onEvent);
      latest = requestId;
    },
    stopRecognition: async () => {},
    cancelRecognition: async () => {},
  };
  return {
    port,
    emit(event: Omit<DictationEvent, 'requestId'>) { sinks.get(latest)?.({ requestId: latest, ...event }); },
  };
}

async function flush() {
  for (let index = 0; index < 6; index += 1) await Promise.resolve();
}

afterEach(() => {
  vi.useRealTimers();
});

describe('dictation audio evidence (#3062)', () => {
  it('passes sound/silence events through the platform adapter without ending the recognizer turn', async () => {
    const speech = speechPlugin();
    const service = createPlatformInputService(noDocuments, speech as unknown as SpeechRecognitionPlugin);
    const events: DictationEvent[] = [];
    await service.startRecognition('turn-1', (event) => events.push(event));

    speech.emit({ requestId: 'turn-1', type: 'audio', code: 'sound' });
    speech.emit({ requestId: 'turn-1', type: 'audio', code: 'silence' });
    // Malformed or foreign audio events are dropped at the boundary.
    speech.emit({ requestId: 'turn-1', type: 'audio', code: 'loud' } as NativeDictationEvent);
    speech.emit({ requestId: 'turn-1', type: 'audio', text: 'secret words', code: 'sound' } as NativeDictationEvent);
    speech.emit({ requestId: 'other', type: 'audio', code: 'sound' });
    speech.emit({ requestId: 'turn-1', type: 'partial', text: 'still listening' });

    expect(events).toEqual([
      { requestId: 'turn-1', type: 'audio', code: 'sound' },
      { requestId: 'turn-1', type: 'audio', code: 'silence' },
      { requestId: 'turn-1', type: 'partial', text: 'still listening' },
    ]);
    expect(speech.removed).not.toHaveBeenCalled();
  });

  it('feeds the adapter flag into the shared controller so the warning says sound vs silence', async () => {
    vi.useFakeTimers();
    const recognizer = fakePort();
    const controller = createSharedDictationController({ recognition: recognizer.port, createRequestId: (() => {
      let id = 0;
      return () => `audio-${++id}`;
    })() });
    controller.setTarget('composer:a');
    controller.start();
    await flush();
    expect(controller.getSnapshot().phase).toBe('listening');

    recognizer.emit({ type: 'audio', code: 'silence' });
    vi.advanceTimersByTime(8_000);
    expect(controller.getSnapshot().noSpeech).toMatchObject({ reason: 'no-text-timeout', audio: 'silent' });
    expect(noSpeechWarningCopy(controller.getSnapshot().noSpeech!).detail).toMatch(/No sound/u);

    recognizer.emit({ type: 'audio', code: 'sound' });
    expect(controller.getSnapshot().noSpeech?.audio).toBe('sound');
    expect(noSpeechWarningCopy(controller.getSnapshot().noSpeech!).detail).toMatch(/Sound, but no words/u);

    recognizer.emit({ type: 'partial', text: 'there you are' });
    expect(controller.getSnapshot().noSpeech).toBeNull();
    controller.stop();
  });

  it('words the warning for every audio state and keeps the issue wording for unknown audio', () => {
    expect(noSpeechWarningCopy({ reason: 'no-text-timeout', audio: 'unknown', emptyTurns: 0, afterText: false }))
      .toEqual({ title: 'Not hearing words', detail: 'Check the mic or try again.', shortDetail: 'Check the mic' });
    expect(noSpeechWarningCopy({ reason: 'empty-turns', audio: 'silent', emptyTurns: 2, afterText: false }).detail)
      .toBe('No sound from the mic. Check it or try again.');
    expect(noSpeechWarningCopy({ reason: 'empty-turns', audio: 'sound', emptyTurns: 3, afterText: false }).detail)
      .toBe('Sound, but no words. Check the language or speak closer.');
  });

  it('never says "no sound from the mic" after words were already recognized in this dictation', async () => {
    vi.useFakeTimers();
    const recognizer = fakePort();
    const controller = createSharedDictationController({ recognition: recognizer.port });
    controller.setTarget('composer:a');
    controller.start();
    await flush();
    recognizer.emit({ type: 'partial', text: 'first thought' });
    recognizer.emit({ type: 'audio', code: 'silence' });
    vi.advanceTimersByTime(8_000);
    const noSpeech = controller.getSnapshot().noSpeech!;
    expect(noSpeech).toMatchObject({ audio: 'silent', afterText: true });
    const copy = noSpeechWarningCopy(noSpeech);
    expect(copy.detail).toBe('Paused? Keep speaking or tap Stop.');
    expect(copy.detail).not.toMatch(/No sound/u);
    for (const audio of ['unknown', 'silent', 'sound'] as const) {
      expect(noSpeechWarningCopy({ reason: 'no-text-timeout', audio, emptyTurns: 0, afterText: true }).detail)
        .not.toMatch(/No sound/u);
    }
    controller.stop();
  });

  it('keeps the compact inline detail short enough for the 40px terminal band', () => {
    for (const afterText of [false, true]) {
      for (const audio of ['unknown', 'silent', 'sound'] as const) {
        expect(noSpeechWarningCopy({ reason: 'no-text-timeout', audio, emptyTurns: 0, afterText }).shortDetail.length)
          .toBeLessThanOrEqual(21);
      }
    }
  });
});
