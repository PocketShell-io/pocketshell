import type { DictationController, DictationInterruptReason, DictationNoSpeech, DictationSnapshot } from '@pocketshell/core';
import { createSharedDictationController, type SharedDictationOptions } from './dictationController';
import { noSpeechWarningCopy } from './dictationNoSpeech';

export type InlineDictationPhase =
  | 'idle'
  | 'starting'
  | 'listening'
  | 'stopping'
  | 'cancelling'
  | 'inserting';

export interface InlineDictationState {
  phase: InlineDictationPhase;
  preview: string;
  message: string;
  tone: 'quiet' | 'success' | 'warning' | 'error';
  /**
   * Set while listening has produced no recognized text for a while (#3062).
   * Informational only; the dock shows a warning and keeps listening.
   */
  noSpeech?: DictationNoSpeech | null;
}

export interface InlineDictationOptions {
  targetKey: string;
  languageTag: string;
  silenceWindowMs: number;
}

export interface InlineDictationControllerDependencies {
  createController?: (options: Pick<SharedDictationOptions, 'languageTag' | 'silenceWindowMs'>) => DictationController;
  insertText(targetKey: string, text: string): Promise<boolean>;
}

const INITIAL_STATE: InlineDictationState = {
  phase: 'idle',
  preview: '',
  message: 'Tap Dictate to speak at the terminal cursor.',
  tone: 'quiet',
};

const INSERTION_UNCONFIRMED_MESSAGE =
  'Terminal insertion was not confirmed. Check for partial text before copying or trying again.';

const KEPT_FOR_RECOVERY: Record<DictationInterruptReason, string> = {
  background: 'Dictation stopped when PocketShell went to the background. Nothing was inserted; copy your text below.',
  closed: 'Dictation stopped when the terminal dock closed. Nothing was inserted; copy your text below.',
  'target-change': 'Dictation stopped when the terminal changed. Nothing was inserted; copy your text below.',
};

/**
 * Project the shared recognizer lifecycle into the terminal dock. Only
 * sanitized text completed after explicit Stop can reach the PTY. Text from a
 * run that ended any other way (screen off, target change, closed dock,
 * recognizer error) is kept visible for recovery, never dropped (#3060); only
 * an explicit user cancel discards it.
 */
export function createInlineDictationController(
  dependencies: InlineDictationControllerDependencies,
) {
  const createController = dependencies.createController ?? createSharedDictationController;
  let state = { ...INITIAL_STATE };
  let generation = 0;
  let shared: DictationController | null = null;
  let unsubscribe: (() => void) | null = null;
  let configuredTargetKey = '';
  let activeTargetKey = '';
  let foreground = true;
  let explicitStop = false;
  /** The current run already reached listening; later restart turns stay "listening" (#3062). */
  let sawListening = false;
  const listeners = new Set<(next: InlineDictationState) => void>();

  function publish(next: InlineDictationState) {
    state = next;
    for (const listener of listeners) listener({ ...state });
  }

  function disposeShared() {
    unsubscribe?.();
    unsubscribe = null;
    shared = null;
    activeTargetKey = '';
  }

  function finishWithoutInsert(gen: number, message: string, tone: InlineDictationState['tone']) {
    if (generation !== gen) return;
    disposeShared();
    explicitStop = false;
    publish({ phase: 'idle', preview: '', message, tone });
  }

  /** Keep words from a run that cannot be inserted visible and copyable. */
  function finishKeptForRecovery(gen: number, text: string, message: string) {
    if (generation !== gen) return;
    disposeShared();
    explicitStop = false;
    publish({ phase: 'idle', preview: text, message, tone: 'warning' });
  }

  function insertCompleted(gen: number, text: string) {
    const targetKey = activeTargetKey;
    disposeShared();
    explicitStop = false;
    publish({
      phase: 'inserting',
      preview: text,
      message: 'Inserting dictated text at the terminal cursor…',
      tone: 'quiet',
    });
    void dependencies.insertText(targetKey, text).then((inserted) => {
      if (generation !== gen) return;
      if (inserted) {
        publish({ phase: 'idle', preview: '', message: 'Inserted at the cursor. Press Enter to run.', tone: 'success' });
      } else {
        finishInsertUnconfirmed(gen, text);
      }
    }).catch(() => {
      finishInsertUnconfirmed(gen, text);
    });
  }

  function finishInsertUnconfirmed(gen: number, text: string) {
    if (generation !== gen) return;
    publish({
      phase: 'idle',
      preview: text,
      message: INSERTION_UNCONFIRMED_MESSAGE,
      tone: 'warning',
    });
  }

  function finishSnapshot(gen: number, snapshot: DictationSnapshot) {
    if (generation !== gen) return;
    const preview = previewText(snapshot);
    switch (snapshot.phase) {
      case 'idle':
        return;
      case 'starting':
        if (sawListening) {
          // A silent restart between recognizer turns: keep the listening band
          // (and any no-words warning) instead of flashing "Starting".
          finishSnapshot(gen, { ...snapshot, phase: 'listening' });
          return;
        }
        publish({ phase: 'starting', preview, message: 'Requesting microphone access…', tone: 'quiet', noSpeech: snapshot.noSpeech });
        return;
      case 'listening': {
        sawListening = true;
        const warning = snapshot.noSpeech ? noSpeechWarningCopy(snapshot.noSpeech) : null;
        publish({
          phase: 'listening',
          preview,
          message: warning
            ? `${warning.title}. ${warning.detail}`
            : 'Listening. Tap Stop to insert at the terminal cursor.',
          tone: 'quiet',
          noSpeech: snapshot.noSpeech,
        });
        return;
      }
      case 'stopping':
        publish({ phase: 'stopping', preview, message: 'Finishing speech recognition…', tone: 'quiet' });
        return;
      case 'cancelled':
        finishWithoutInsert(gen, 'Dictation cancelled. Nothing was inserted.', 'quiet');
        return;
      case 'error': {
        const text = safeInlineTranscriptText(snapshot.transcript);
        if (text && explicitStop && snapshot.error?.code === 'recognizer-stop-timeout') {
          // Stop was explicit and the shared controller promoted the last
          // partial when the recognizer never confirmed it: insert it all.
          insertCompleted(gen, text);
          return;
        }
        if (text) {
          finishKeptForRecovery(gen, text, `${speechFailureMessage(snapshot.error)} Nothing was inserted; copy your text below.`);
          return;
        }
        finishWithoutInsert(gen, speechFailureMessage(snapshot.error), 'error');
        return;
      }
      case 'completed': {
        const text = safeInlineTranscriptText(snapshot.transcript);
        if (snapshot.interruptReason) {
          if (text) finishKeptForRecovery(gen, text, KEPT_FOR_RECOVERY[snapshot.interruptReason]);
          else finishWithoutInsert(gen, 'Dictation stopped. Nothing was recognized.', 'quiet');
          return;
        }
        if (!explicitStop) {
          if (text) finishKeptForRecovery(gen, text, 'Dictation ended without explicit Stop. Nothing was inserted; copy your text below.');
          else finishWithoutInsert(gen, 'Dictation ended without explicit Stop. Nothing was inserted.', 'warning');
          return;
        }
        if (!text) {
          finishWithoutInsert(gen, 'No speech was recognized. Nothing was inserted.', 'warning');
          return;
        }
        insertCompleted(gen, text);
        return;
      }
    }
  }

  function start(options: InlineDictationOptions): void {
    if (state.phase !== 'idle' || !foreground || options.targetKey.trim() === '') return;
    generation += 1;
    const gen = generation;
    configuredTargetKey = options.targetKey;
    activeTargetKey = options.targetKey;
    explicitStop = false;
    sawListening = false;
    const controller = createController({
      languageTag: options.languageTag,
      silenceWindowMs: options.silenceWindowMs,
    });
    shared = controller;
    controller.setTarget(options.targetKey);
    controller.setForeground(foreground);
    unsubscribe = controller.subscribe((snapshot) => finishSnapshot(gen, snapshot));
    if (controller.start() === null) {
      finishWithoutInsert(gen, 'Speech recognition could not start.', 'warning');
    }
  }

  function stop(): void {
    if (state.phase === 'starting') {
      cancel();
      return;
    }
    if (state.phase !== 'listening' || !shared) return;
    explicitStop = true;
    shared.stop();
  }

  /** Explicit user cancel: the only path that discards dictated words. */
  function cancel(): void {
    if (state.phase === 'idle' || state.phase === 'inserting') return;
    const active = shared;
    if (active) active.cancel('user');
    else {
      generation += 1;
      finishWithoutInsert(generation, 'Dictation cancelled. Nothing was inserted.', 'quiet');
    }
  }

  /** Lifecycle stop (dock disabled/unmounted): end now and keep the words for recovery. */
  function interrupt(): void {
    if (state.phase === 'idle' || state.phase === 'inserting') return;
    const active = shared;
    if (active) active.interrupt('closed');
    else {
      generation += 1;
      finishWithoutInsert(generation, 'Dictation stopped. Nothing was inserted.', 'quiet');
    }
  }

  function setTarget(targetKey: string): void {
    if (configuredTargetKey === targetKey) return;
    configuredTargetKey = targetKey;
    shared?.setTarget(targetKey);
  }

  function setForeground(isForeground: boolean): void {
    foreground = isForeground;
    shared?.setForeground(isForeground);
  }

  return {
    getState(): InlineDictationState {
      return { ...state };
    },
    subscribe(listener: (next: InlineDictationState) => void): () => void {
      listeners.add(listener);
      listener({ ...state });
      return () => listeners.delete(listener);
    },
    start,
    stop,
    cancel,
    interrupt,
    setTarget,
    setForeground,
  };
}

/** Keep raw spoken text editable as a shell line; never let it press Enter or send controls. */
export function safeInlineTranscriptText(value: string): string {
  return value.replace(/[\u0000-\u001f\u007f-\u009f]/gu, ' ').replace(/\s+/gu, ' ').trim();
}

function previewText(snapshot: DictationSnapshot): string {
  return safeInlineTranscriptText([snapshot.transcript, snapshot.partial].filter(Boolean).join(' '));
}

function speechFailureMessage(error: DictationSnapshot['error']): string {
  const code = error?.code ?? '';
  if (code.includes('permission')) {
    return 'Microphone permission denied. Allow microphone access in Android settings to use dictation.';
  }
  if (code.includes('unavailable')) return 'Speech recognition is unavailable on this device.';
  if (code.includes('language')) return 'This language is unavailable in the installed speech service.';
  return error?.message ? `Dictation failed: ${error.message}` : 'Dictation failed. Nothing was inserted.';
}
