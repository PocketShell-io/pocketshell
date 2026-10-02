/**
 * Browser dev mode (#3022): `SpeechRecognition` over the Web Speech API when
 * the browser has one, otherwise (or with `?devSpeech=stub`) a scripted
 * recognizer that "hears" a fixed phrase, so the dictation UI is reachable
 * without a microphone.
 */
import type { DevPlugin, FakeNativeBridge } from './nativeBridge';
import { DevPluginError } from './nativeBridge';

export const STUB_DICTATION_PHRASE = 'hello from browser dev mode';

interface WebSpeechResultList {
  length: number;
  [index: number]: { isFinal: boolean; 0: { transcript: string } };
}

interface WebSpeechRecognition {
  lang: string;
  continuous: boolean;
  interimResults: boolean;
  onresult: ((event: { resultIndex: number; results: WebSpeechResultList }) => void) | null;
  onerror: ((event: { error: string }) => void) | null;
  onend: (() => void) | null;
  start(): void;
  stop(): void;
  abort(): void;
}

export type WebSpeechConstructor = new () => WebSpeechRecognition;

export interface SpeechPluginOptions {
  bridge: () => FakeNativeBridge;
  /** The page's Web Speech constructor, or null to always use the stub. */
  webSpeech: WebSpeechConstructor | null;
  schedule?: (callback: () => void, ms: number) => unknown;
  cancel?: (handle: unknown) => void;
}

const WEB_SPEECH_ERRORS: Record<string, string> = {
  'not-allowed': 'permission-denied',
  'service-not-allowed': 'permission-denied',
  network: 'network-error',
  'audio-capture': 'audio-error',
  'language-not-supported': 'language-not-supported',
};

export function createSpeechPlugin(options: SpeechPluginOptions): DevPlugin {
  const schedule = options.schedule ?? ((callback, ms) => setTimeout(callback, ms));
  const cancel = options.cancel ?? ((handle) => clearTimeout(handle as ReturnType<typeof setTimeout>));
  const emit = (event: Record<string, unknown>) => options.bridge().emit('SpeechRecognition', 'dictationEvent', event);
  let active: { requestId: string; stop(): void; abort(): void } | null = null;
  // The stub "hears" its phrase once per dictation session; later turns of
  // the same session are silence, so the controller's restart loop stays calm.
  let stubPhraseSpoken = false;
  let lastLanguageTag: string | undefined;
  let lastSilenceWindowMs: number | undefined;

  function startStub(requestId: string, silenceWindowMs: number) {
    const timers: unknown[] = [];
    let finished = false;
    const finish = (event: Record<string, unknown>) => {
      if (finished) return;
      finished = true;
      timers.forEach(cancel);
      if (active?.requestId === requestId) active = null;
      emit({ requestId, ...event });
    };
    if (!stubPhraseSpoken) {
      stubPhraseSpoken = true;
      timers.push(schedule(() => emit({ requestId, type: 'partial', text: STUB_DICTATION_PHRASE.split(' ').slice(0, 2).join(' ') }), 400));
      timers.push(schedule(() => finish({ type: 'result', text: STUB_DICTATION_PHRASE }), 1_200));
    } else {
      timers.push(schedule(() => finish({ type: 'recoverable', code: 'speech-timeout' }), silenceWindowMs));
    }
    active = {
      requestId,
      stop: () => finish({ type: 'recoverable', code: 'no-match' }),
      abort: () => {
        finished = true;
        timers.forEach(cancel);
      },
    };
  }

  function startWebSpeech(Recognition: WebSpeechConstructor, requestId: string, languageTag: string | undefined) {
    const recognition = new Recognition();
    recognition.continuous = false;
    recognition.interimResults = true;
    if (languageTag && languageTag !== 'auto') recognition.lang = languageTag;
    let finalText = '';
    let failed = false;
    recognition.onresult = (event) => {
      let interim = '';
      for (let index = event.resultIndex; index < event.results.length; index += 1) {
        const result = event.results[index];
        if (result.isFinal) finalText += result[0].transcript;
        else interim += result[0].transcript;
      }
      if (interim) emit({ requestId, type: 'partial', text: finalText + interim });
    };
    recognition.onerror = (event) => {
      failed = true;
      if (event.error === 'no-speech' || event.error === 'aborted') {
        emit({ requestId, type: 'recoverable', code: event.error === 'no-speech' ? 'speech-timeout' : 'no-match' });
        return;
      }
      emit({ requestId, type: 'error', code: WEB_SPEECH_ERRORS[event.error] ?? 'recognizer-error' });
    };
    recognition.onend = () => {
      if (active?.requestId === requestId) active = null;
      if (failed) return;
      if (finalText.trim()) emit({ requestId, type: 'result', text: finalText.trim() });
      else emit({ requestId, type: 'recoverable', code: 'no-match' });
    };
    recognition.start();
    active = { requestId, stop: () => recognition.stop(), abort: () => recognition.abort() };
  }

  return {
    methods: {
      getCapabilities: () => ({ speechRecognitionAvailable: true, microphonePermissionGranted: true }),
      startDictation: (start) => {
        const requestId = String(start.requestId ?? '');
        if (!requestId) throw new DevPluginError('requestId is required', 'INVALID_ARGUMENT');
        active?.abort();
        lastLanguageTag = typeof start.languageTag === 'string' ? start.languageTag : undefined;
        lastSilenceWindowMs = typeof start.silenceWindowMs === 'number' ? start.silenceWindowMs : 4_000;
        if (options.webSpeech && start.testMode !== true) startWebSpeech(options.webSpeech, requestId, lastLanguageTag);
        else if (start.testMode === true) active = { requestId, stop: () => undefined, abort: () => undefined };
        else startStub(requestId, lastSilenceWindowMs);
        return { requestId, started: true };
      },
      stopDictation: (stop) => {
        const stopped = active?.requestId === stop.requestId;
        if (stopped) active?.stop();
        stubPhraseSpoken = false;
        return { requestId: stop.requestId, stopped };
      },
      cancelDictation: (request) => {
        const cancelled = active?.requestId === request.requestId;
        if (cancelled) {
          active?.abort();
          active = null;
        }
        stubPhraseSpoken = false;
        return { requestId: request.requestId, cancelled };
      },
      injectTestDictationEvent: (event) => {
        const requestId = typeof event.requestId === 'string' ? event.requestId : active?.requestId ?? '';
        const emitted = emit({ ...event, requestId }) > 0;
        return { requestId, emitted, languageTag: lastLanguageTag, silenceWindowMs: lastSilenceWindowMs };
      },
    },
  };
}
