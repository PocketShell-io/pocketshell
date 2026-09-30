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

export type SpeechRecognitionPlugin = Plugin & {
  getCapabilities(): Promise<NativeSpeechCapabilities>;
  startDictation(options: { requestId: string; languageTag?: string }): Promise<{
    requestId: string;
    started: boolean;
  }>;
  stopDictation(options: { requestId: string }): Promise<{
    requestId: string;
    stopped: boolean;
  }>;
  addListener(
    eventName: 'dictationEvent',
    listener: (event: NativeDictationEvent) => void,
  ): Promise<PluginListenerHandle>;
};

/** Android SpeechRecognizer stays the sole recognition engine for this surface. */
export const speechRecognition = registerPlugin<SpeechRecognitionPlugin>('SpeechRecognition');
