import {
  DictationController,
  type DictationControllerOptions,
  type DictationError,
} from '@pocketshell/core';
import type { DictationEvent, DictationStartOptions } from './platformInput';
import { platformInput } from './platformInput';

export interface DictationRecognitionPort {
  startRecognition(
    requestId: string,
    onEvent: (event: DictationEvent) => void,
    options?: DictationStartOptions,
  ): Promise<void>;
  stopRecognition(requestId: string): Promise<void>;
  cancelRecognition(requestId: string): Promise<void>;
}

export interface SharedDictationOptions extends DictationStartOptions {
  recognition?: DictationRecognitionPort;
  restartDelayMs?: number;
  createRequestId?: () => string;
  /** Override the shared "no recognized text" warning window (#3062); core defaults to 8 s. */
  noTextWarningMs?: number;
}

const DEFAULT_RESTART_DELAY_MS = 300;

/** Adapt one-turn Android recognition calls to the shared dictation policy. */
export function createSharedDictationController(options: SharedDictationOptions = {}) {
  const recognition = options.recognition ?? platformInput;
  const recognitionOptions: DictationStartOptions = {
    ...(options.languageTag ? { languageTag: options.languageTag } : {}),
    ...(options.silenceWindowMs !== undefined ? { silenceWindowMs: options.silenceWindowMs } : {}),
  };
  const restartDelayMs = options.restartDelayMs ?? DEFAULT_RESTART_DELAY_MS;
  let controller: DictationController;

  const controllerOptions: DictationControllerOptions = {
    startRecognition: async (requestId) => {
      try {
        await recognition.startRecognition(
          requestId,
          (event) => handleRecognitionEvent(event),
          recognitionOptions,
        );
      } catch (error) {
        if (!controller.onError(requestId, recognitionError(readErrorCode(error), readErrorMessage(error)))) throw error;
      }
    },
    stopRecognition: (requestId) => recognition.stopRecognition(requestId),
    cancelRecognition: (requestId) => recognition.cancelRecognition(requestId),
    schedule: (callback) => setTimeout(callback, restartDelayMs),
    cancelScheduled: (handle) => clearTimeout(handle as ReturnType<typeof setTimeout>),
    ...(options.createRequestId ? { createRequestId: options.createRequestId } : {}),
    ...(options.noTextWarningMs !== undefined ? { noTextWarningMs: options.noTextWarningMs } : {}),
  };
  controller = new DictationController(controllerOptions);

  function handleRecognitionEvent(event: DictationEvent) {
    switch (event.type) {
      case 'partial':
        controller.onPartial(event.requestId, event.text ?? '');
        break;
      case 'result':
        controller.onRecognizedSegment(event.requestId, event.text ?? '');
        break;
      case 'recoverable':
        controller.onRecoverableEnd(event.requestId, recoverableReason(event.code));
        break;
      case 'error':
        controller.onError(event.requestId, recognitionError(event.code));
        break;
      case 'audio':
        controller.onAudioLevel(event.requestId, event.code === 'sound');
        break;
    }
  }

  return controller;
}

function recoverableReason(code: string | undefined): 'no-match' | 'speech-timeout' | 'recognizer-busy' {
  if (code === 'speech-timeout' || code === 'recognizer-busy') return code;
  return 'no-match';
}

function recognitionError(code: string | undefined, message?: string): DictationError {
  const normalizedCode = code?.trim().toLowerCase().replace(/_/gu, '-') || 'recognizer-error';
  const messages: Record<string, string> = {
    'permission-denied': 'Microphone permission was denied.',
    'speech-recognizer-unavailable': 'Speech recognition is unavailable on this device.',
    'language-not-supported': 'This language is not supported by the installed speech service.',
    'language-unavailable': 'This language is unavailable in the installed speech service.',
    'network-error': 'Speech recognition could not reach its service.',
    'network-timeout': 'Speech recognition timed out while contacting its service.',
    'recognizer-rate-limited': 'Speech recognition is temporarily rate limited.',
    'recognizer-service-disconnected': 'The speech recognition service disconnected.',
    'recognizer-service-error': 'The speech recognition service failed.',
    'audio-error': 'Android could not read microphone audio.',
    'start-failed': 'Android speech recognition could not start.',
    'stop-failed': 'Android speech recognition did not finish.',
  };
  return { code: normalizedCode, message: messages[normalizedCode] ?? message ?? `Speech recognition failed (${normalizedCode}).` };
}

function readErrorCode(error: unknown): string | undefined {
  if (typeof error !== 'object' || error === null || !('code' in error)) return undefined;
  const code = (error as { code?: unknown }).code;
  return typeof code === 'string' && code.trim() ? code : undefined;
}

function readErrorMessage(error: unknown): string {
  if (error instanceof Error && error.message) return error.message;
  if (typeof error === 'string' && error) return error;
  return 'Android speech recognition could not start.';
}
