import { shallowRef, type ShallowRef } from 'vue';
import type { DictationController, DictationNoSpeech, DictationPhase, DictationSnapshot } from '@pocketshell/core';
import type { ComposerExtensionContext } from '@ui/app/extensions';

/**
 * One-tap voice into the shared prompt composer (#3128, Android only, D42).
 *
 * The run policy - partials, explicit stop only, the recognizer's own
 * endpointing treated as a pause, background and target changes keeping every
 * dictated word - is core's `DictationController`; this module only binds one
 * run to one session's composer draft through the composer extension
 * context, and offers the 0.5.x review actions: Discard (restore the draft
 * the run started from), Insert (deliver without Enter) and Send.
 *
 * It holds no draft of its own. The draft is core's per-session composer
 * record, written through `context.setDraft`, which stays bound to the
 * session the run started in: a late recognizer event after a session switch
 * can only reach that session's draft, never the next one's, and nothing in
 * here ever writes to a terminal except through `context.submit`.
 */

/** The slice of the composer context a run needs. */
export type VoiceDraftTarget = Pick<ComposerExtensionContext, 'sessionKey' | 'readDraft' | 'setDraft'>;

export type VoiceRunPhase = DictationPhase;

/** What the launcher and the composer panel render. */
export interface VoiceComposerState {
  /** The session the current (or last) run is bound to; null when there is none. */
  sessionKey: string | null;
  phase: VoiceRunPhase;
  /** The run ended and its text is in the draft, waiting for Discard, Insert or Send. */
  reviewing: boolean;
  /** The live, unconfirmed partial (also already in the draft). */
  partial: string;
  error: string | null;
  noSpeech: DictationNoSpeech | null;
}

export interface VoiceComposerDeps {
  /** One controller for the app's lifetime; Android's is `createSharedDictationController`. */
  createController: () => DictationController;
  /** Subscribe to app foreground/background; returns nothing (app lifetime). */
  onAppStateChange?: (listener: (isActive: boolean) => void) => void;
}

const IDLE: VoiceComposerState = {
  sessionKey: null,
  phase: 'idle',
  reviewing: false,
  partial: '',
  error: null,
  noSpeech: null,
};

/** The draft the run writes: what was there before, then the dictated words. */
export function joinDictatedDraft(base: string, transcript: string, partial: string): string {
  const spoken = [transcript.trim(), partial.trim()].filter(Boolean).join(' ');
  if (!spoken) return base;
  if (!base || /\s$/u.test(base)) return base + spoken;
  return `${base} ${spoken}`;
}

function isActivePhase(phase: VoiceRunPhase): boolean {
  return phase === 'starting' || phase === 'listening' || phase === 'stopping';
}

export interface VoiceComposerSession {
  readonly state: ShallowRef<VoiceComposerState>;
  /** Start a run for this session's draft. False when one is already running or the recognizer refused. */
  start(target: VoiceDraftTarget): boolean;
  /** The explicit stop tap: wait for the recognizer's final, then review. */
  stop(): void;
  /** Discard what was dictated: the draft goes back to what it was before the run. */
  discard(): void;
  /** End listening now (keeping every word), then deliver: Insert (`enter: false`) or Send. */
  deliver(context: Pick<ComposerExtensionContext, 'sessionKey' | 'submit'>, enter: boolean): Promise<boolean>;
  /** A lifecycle end (session switch, the composer closing): stop now, keep the words, write nothing anywhere else. */
  interrupt(reason: 'target-change' | 'closed'): void;
  /** Forget a finished run's review (the draft was sent, edited or discarded elsewhere). */
  clearReview(): void;
  /** True while the recognizer is starting, listening or finishing. */
  isActive(): boolean;
}

export function createVoiceComposerSession(deps: VoiceComposerDeps): VoiceComposerSession {
  const state = shallowRef<VoiceComposerState>({ ...IDLE });
  let controller: DictationController | null = null;
  let target: VoiceDraftTarget | null = null;
  let base = '';
  let lastWritten = '';
  let lifecycleBound = false;

  function ensureController(): DictationController {
    if (controller) return controller;
    controller = deps.createController();
    controller.subscribe(onSnapshot);
    return controller;
  }

  function bindLifecycle(): void {
    if (lifecycleBound) return;
    lifecycleBound = true;
    // Background (HOME, screen off): core's policy releases the microphone
    // and keeps the words. Nothing is delivered: the words stay in the draft.
    deps.onAppStateChange?.((isActive) => controller?.setForeground(isActive));
  }

  function publish(snapshot: DictationSnapshot, reviewing: boolean): void {
    state.value = {
      sessionKey: target ? target.sessionKey : null,
      phase: snapshot.phase,
      reviewing,
      partial: snapshot.partial,
      error: snapshot.error?.message ?? null,
      noSpeech: snapshot.noSpeech,
    };
  }

  function onSnapshot(snapshot: DictationSnapshot): void {
    const run = target;
    if (!run || snapshot.targetId !== run.sessionKey) return;
    if (snapshot.phase === 'idle') return;
    if (snapshot.phase === 'cancelled') {
      publish(snapshot, false);
      return;
    }
    // The user edited the draft (or core sent it) while the run was live: the
    // draft is theirs now. Stop listening and never write over it.
    if (run.readDraft() !== lastWritten) {
      target = null;
      if (isActivePhase(snapshot.phase)) controller?.cancel();
      state.value = { ...IDLE };
      return;
    }
    const next = joinDictatedDraft(base, snapshot.transcript, snapshot.partial);
    if (next !== lastWritten) {
      run.setDraft(next);
      lastWritten = next;
    }
    const finished = snapshot.phase === 'completed' || snapshot.phase === 'error';
    publish(snapshot, finished && lastWritten !== base);
  }

  function start(next: VoiceDraftTarget): boolean {
    const c = ensureController();
    bindLifecycle();
    if (isActivePhase(c.getSnapshot().phase)) return false;
    target = next;
    base = next.readDraft();
    lastWritten = base;
    c.setTarget(next.sessionKey);
    if (c.start() === null) {
      target = null;
      state.value = { ...IDLE, error: 'Dictation could not start.' };
      return false;
    }
    return true;
  }

  function stop(): void {
    controller?.stop();
  }

  function discard(): void {
    const run = target;
    if (controller && isActivePhase(controller.getSnapshot().phase)) controller.cancel('user');
    if (run && run.readDraft() === lastWritten) run.setDraft(base);
    target = null;
    state.value = { ...IDLE };
  }

  function interrupt(reason: 'target-change' | 'closed'): void {
    if (controller && isActivePhase(controller.getSnapshot().phase)) controller.interrupt(reason);
  }

  async function deliver(context: Pick<ComposerExtensionContext, 'sessionKey' | 'submit'>, enter: boolean): Promise<boolean> {
    if (target && target.sessionKey !== context.sessionKey) return false;
    // Send while a partial is showing delivers the partial too: interrupt
    // promotes it into the transcript, which lands in the draft synchronously.
    interrupt('closed');
    target = null;
    state.value = { ...IDLE };
    return context.submit({ enter });
  }

  function clearReview(): void {
    if (controller && isActivePhase(controller.getSnapshot().phase)) return;
    target = null;
    state.value = { ...IDLE };
  }

  return {
    state,
    start,
    stop,
    discard,
    deliver,
    interrupt,
    clearReview,
    isActive: () => (controller ? isActivePhase(controller.getSnapshot().phase) : false),
  };
}
