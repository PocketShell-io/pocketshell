import { registerPlugin, type Plugin, type PluginListenerHandle } from '@capacitor/core';

export type DictationEventType =
  | 'started'
  | 'ready'
  | 'listening'
  | 'processing'
  | 'partial'
  | 'result'
  | 'error'
  | 'stopped';

export interface NativeDictationEvent {
  requestId: string;
  type: DictationEventType;
  text?: string;
  code?: string;
}

export interface NativeSpeechCapabilities {
  speechRecognitionAvailable: boolean;
  microphonePermissionGranted: boolean;
}

export interface StartDictationOptions {
  requestId: string;
  /** BCP-47 language hint from Voice settings; omit it for device auto-detect. */
  languageTag?: string;
  silenceWindowMs?: number;
  testMode?: boolean;
}

export const DEFAULT_SPEECH_SILENCE_WINDOW_MS = 4_000;
export const MIN_SPEECH_SILENCE_WINDOW_MS = 2_000;
export const MAX_SPEECH_SILENCE_WINDOW_MS = 60_000;

export type SpeechRecognitionPlugin = Plugin & {
  getCapabilities(): Promise<NativeSpeechCapabilities>;
  startDictation(options: StartDictationOptions): Promise<{ requestId: string; started: boolean }>;
  stopDictation(options: { requestId: string }): Promise<{ requestId: string; stopped: boolean }>;
  cancelDictation(options: { requestId: string }): Promise<{ requestId: string; cancelled: boolean }>;
  injectTestDictationEvent(options: {
    requestId?: string;
    type: 'partial' | 'processing' | 'result' | 'ready' | 'listening' | 'finish';
    text?: string;
  }): Promise<{
    requestId: string;
    emitted: boolean;
    languageTag?: string;
    silenceWindowMs?: number;
  }>;
  addListener(
    eventName: 'dictationEvent',
    listener: (event: NativeDictationEvent) => void,
  ): Promise<PluginListenerHandle>;
};

export function sanitizeSilenceWindowMs(value: unknown): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) return DEFAULT_SPEECH_SILENCE_WINDOW_MS;
  return Math.min(MAX_SPEECH_SILENCE_WINDOW_MS, Math.max(MIN_SPEECH_SILENCE_WINDOW_MS, Math.round(value)));
}

export function sanitizeLanguageTag(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined;
  const languageTag = value.trim();
  if (languageTag.toLowerCase() === 'auto') return 'auto';
  if (
    languageTag.length === 0
    || languageTag.length > 64
    || !/^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*$/.test(languageTag)
  ) return undefined;
  return languageTag;
}

/** Bound WebView values before they reach Android's speech recognizer. */
export function sanitizeStartDictationOptions(options: StartDictationOptions): StartDictationOptions {
  const languageTag = sanitizeLanguageTag(options.languageTag);
  return {
    requestId: options.requestId,
    ...(languageTag ? { languageTag } : {}),
    silenceWindowMs: sanitizeSilenceWindowMs(options.silenceWindowMs),
    ...(options.testMode === true ? { testMode: true } : {}),
  };
}

type RegisteredSpeechRecognitionPlugin = Plugin & SpeechRecognitionPlugin;
const nativeSpeechRecognition = registerPlugin<RegisteredSpeechRecognitionPlugin>('SpeechRecognition');

/** Android SpeechRecognizer stays the sole recognition engine for this surface. */
export const speechRecognition: SpeechRecognitionPlugin = {
  getCapabilities: () => nativeSpeechRecognition.getCapabilities(),
  startDictation: (options) => nativeSpeechRecognition.startDictation(sanitizeStartDictationOptions(options)),
  stopDictation: (options) => nativeSpeechRecognition.stopDictation(options),
  cancelDictation: (options) => nativeSpeechRecognition.cancelDictation(options),
  injectTestDictationEvent: (options) => nativeSpeechRecognition.injectTestDictationEvent(options),
  addListener: (eventName, listener) => nativeSpeechRecognition.addListener(eventName, listener),
  removeAllListeners: () => nativeSpeechRecognition.removeAllListeners(),
};
