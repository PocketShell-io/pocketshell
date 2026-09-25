import type { DictationEvent } from './platformInput';

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
}

export interface InlineDictationSession {
  requestId: string;
  stop(): Promise<void>;
}

export interface InlineDictationOptions {
  targetKey: string;
  languageTag: string;
  silenceWindowMs: number;
}

export interface InlineDictationControllerDependencies {
  startDictation(
    onEvent: (event: DictationEvent) => void,
    settings: Pick<InlineDictationOptions, 'languageTag' | 'silenceWindowMs'>,
  ): Promise<InlineDictationSession>;
  insertText(targetKey: string, text: string): Promise<boolean>;
  stopTimeoutMs?: number;
}

const DEFAULT_STOP_TIMEOUT_MS = 15_000;

const INITIAL_STATE: InlineDictationState = {
  phase: 'idle',
  preview: '',
  message: 'Tap the microphone to dictate at the terminal cursor.',
  tone: 'quiet',
};

/**
 * Keep recognizer policy in JS: partials are preview-only, final phrases wait
 * for explicit Stop, and only then can their safe text reach the live PTY.
 */
export function createInlineDictationController(
  dependencies: InlineDictationControllerDependencies,
) {
  let state = { ...INITIAL_STATE };
  let generation = 0;
  let session: InlineDictationSession | null = null;
  let targetKey = '';
  let partial = '';
  let finalSegments: string[] = [];
  let failureMessage = '';
  let cancelRequested = false;
  let complete = false;
  let stopTimer: ReturnType<typeof setTimeout> | undefined;
  const listeners = new Set<(next: InlineDictationState) => void>();

  function publish(next: InlineDictationState) {
    state = next;
    for (const listener of listeners) listener({ ...state });
  }

  function clearStopTimer() {
    if (stopTimer !== undefined) clearTimeout(stopTimer);
    stopTimer = undefined;
  }

  function invalidateGeneration() {
    generation += 1;
    complete = true;
    clearStopTimer();
    session = null;
    partial = '';
    finalSegments = [];
    targetKey = '';
    cancelRequested = false;
    failureMessage = '';
  }

  function current(gen: number): boolean {
    return generation === gen && !complete;
  }

  function isStillStarting(): boolean {
    return state.phase === 'starting';
  }

  function armStopTimeout(gen: number) {
    clearStopTimer();
    stopTimer = setTimeout(() => {
      if (!current(gen)) return;
      const active = session;
      invalidateGeneration();
      void active?.stop().catch(() => undefined);
      publish({
        phase: 'idle',
        preview: '',
        message: 'Speech recognition did not finish. Nothing was inserted.',
        tone: 'warning',
      });
    }, dependencies.stopTimeoutMs ?? DEFAULT_STOP_TIMEOUT_MS);
  }

  function completeWithoutInsert(gen: number, message: string, tone: InlineDictationState['tone']) {
    if (!current(gen)) return;
    complete = true;
    clearStopTimer();
    session = null;
    targetKey = '';
    partial = '';
    finalSegments = [];
    cancelRequested = false;
    publish({ phase: 'idle', preview: '', message, tone });
  }

  async function insertFinalText(gen: number, destination: string, text: string) {
    if (!current(gen)) return;
    complete = true;
    clearStopTimer();
    publish({
      phase: 'inserting',
      preview: text,
      message: 'Inserting dictated text at the terminal cursor…',
      tone: 'quiet',
    });
    let inserted = false;
    try {
      inserted = await dependencies.insertText(destination, text);
    } catch {
      inserted = false;
    }
    if (generation !== gen) return;
    session = null;
    targetKey = '';
    partial = '';
    finalSegments = [];
    publish(inserted
      ? { phase: 'idle', preview: '', message: 'Inserted at the cursor. Press Enter to run.', tone: 'success' }
      : {
        phase: 'idle',
        preview: '',
        message: 'Text could not be inserted. Check the terminal before trying again.',
        tone: 'warning',
      });
  }

  function onEvent(gen: number, event: DictationEvent) {
    if (!current(gen)) return;
    switch (event.type) {
      case 'started':
      case 'ready':
      case 'listening':
        if (state.phase === 'starting') {
          publish({ phase: 'listening', preview: '', message: 'Listening. Tap Stop to insert the recognized text.', tone: 'quiet' });
        }
        break;
      case 'processing':
        if (state.phase === 'stopping') {
          publish({ ...state, message: 'Finishing speech recognition…' });
        }
        break;
      case 'partial': {
        if (cancelRequested || failureMessage) break;
        partial = safeInlineTranscriptText(event.text ?? '');
        publish({ ...state, preview: partial });
        break;
      }
      case 'result': {
        if (cancelRequested || failureMessage) break;
        const finalText = safeInlineTranscriptText(event.text ?? '');
        if (finalText) finalSegments.push(finalText);
        partial = '';
        publish({ ...state, preview: finalText || finalSegments.at(-1) || '' });
        break;
      }
      case 'error':
        failureMessage = speechFailureMessage(event.code ?? '');
        finalSegments = [];
        partial = '';
        publish({ phase: 'stopping', preview: '', message: failureMessage, tone: 'error' });
        break;
      case 'stopped': {
        clearStopTimer();
        session = null;
        if (cancelRequested) {
          completeWithoutInsert(gen, '', 'quiet');
          return;
        }
        if (failureMessage) {
          completeWithoutInsert(gen, failureMessage, 'error');
          return;
        }
        const text = finalSegments.join(' ').replace(/\s+/gu, ' ').trim();
        if (!text) {
          completeWithoutInsert(gen, 'No final transcript was received. Nothing was inserted.', 'warning');
          return;
        }
        void insertFinalText(gen, targetKey, text);
        break;
      }
    }
  }

  async function start(options: InlineDictationOptions): Promise<void> {
    if (state.phase !== 'idle' || options.targetKey.trim() === '') return;
    clearStopTimer();
    generation += 1;
    const gen = generation;
    complete = false;
    session = null;
    targetKey = options.targetKey;
    partial = '';
    finalSegments = [];
    failureMessage = '';
    cancelRequested = false;
    publish({ phase: 'starting', preview: '', message: 'Requesting microphone access…', tone: 'quiet' });

    try {
      const started = await dependencies.startDictation(
        (event) => onEvent(gen, event),
        { languageTag: options.languageTag, silenceWindowMs: options.silenceWindowMs },
      );
      if (!current(gen)) {
        // A late permission grant after backgrounding must be stopped without
        // allowing its results to reach the PTY.
        void started.stop().catch(() => undefined);
        return;
      }
      session = started;
      if (cancelRequested) {
        publish({ phase: 'cancelling', preview: '', message: 'Cancelling dictation…', tone: 'quiet' });
        armStopTimeout(gen);
        await started.stop();
        return;
      }
      if (isStillStarting()) {
        publish({ phase: 'listening', preview: '', message: 'Listening. Tap Stop to insert the recognized text.', tone: 'quiet' });
      }
    } catch (error) {
      if (!current(gen)) return;
      completeWithoutInsert(gen, cancelRequested ? '' : speechFailureMessage(error), cancelRequested ? 'quiet' : 'error');
    }
  }

  async function stop(): Promise<void> {
    if (state.phase === 'starting') {
      await cancel();
      return;
    }
    if (state.phase === 'listening' && !session) {
      cancelRequested = true;
      publish({ phase: 'cancelling', preview: '', message: 'Cancelling dictation…', tone: 'quiet' });
      return;
    }
    if (state.phase !== 'listening' || !session) return;
    const gen = generation;
    publish({ ...state, phase: 'stopping', message: 'Finishing speech recognition…' });
    armStopTimeout(gen);
    try {
      await session.stop();
    } catch (error) {
      if (!current(gen)) return;
      failureMessage = speechFailureMessage(error);
      finalSegments = [];
      partial = '';
      publish({ ...state, phase: 'stopping', preview: '', message: failureMessage, tone: 'error' });
    }
  }

  async function cancel(): Promise<void> {
    if (state.phase === 'idle' || state.phase === 'inserting') return;
    cancelRequested = true;
    finalSegments = [];
    partial = '';
    const active = session;
    if (!active) {
      publish({ phase: 'cancelling', preview: '', message: 'Cancelling dictation…', tone: 'quiet' });
      return;
    }
    const gen = generation;
    publish({ phase: 'cancelling', preview: '', message: 'Cancelling dictation…', tone: 'quiet' });
    armStopTimeout(gen);
    try {
      await active.stop();
    } catch {
      // The listener or watchdog will retire the request. Results stay gated.
    }
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
  };
}

/** Keep raw spoken text editable as a shell line; never let it press Enter or send controls. */
export function safeInlineTranscriptText(value: string): string {
  return value.replace(/[\u0000-\u001f\u007f-\u009f]/gu, ' ').replace(/\s+/gu, ' ').trim();
}

function speechFailureMessage(error: unknown): string {
  const candidate = typeof error === 'string'
    ? error
    : error instanceof Error
      ? error.message
      : String(error);
  const code = typeof error === 'object' && error !== null && 'code' in error
    ? String((error as { code?: unknown }).code ?? '')
    : '';
  const identity = `${code} ${candidate}`.toLowerCase();
  if (identity.includes('permission')) {
    return 'Microphone permission denied. Allow microphone access in Android settings to use dictation.';
  }
  if (identity.includes('unavailable') || identity.includes('not available')) {
    return 'Speech recognition is unavailable on this device.';
  }
  if (identity.includes('language')) {
    return 'This language is unavailable in the installed speech service.';
  }
  return candidate.trim() ? `Dictation failed: ${candidate}` : 'Dictation failed. Nothing was inserted.';
}
