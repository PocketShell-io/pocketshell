<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue';
import { App as CapacitorApp } from '@capacitor/app';
import type { PluginListenerHandle } from '@capacitor/core';
import AppIcon from '@ui/components/AppIcon.vue';
import ComposerControls from '@ui/components/ComposerControls.vue';
import type {
  ComposerDeliveryIntent,
  ComposerDeliveryResult,
  DictationController,
  DictationInterruptReason,
  DictationNoSpeech,
  DictationSnapshot,
} from '@pocketshell/core';
import { createComposerDeliveryController, type PtyWriteEffect } from '../session/composerDelivery';
import { createSharedDictationController } from '../session/dictationController';
import { useAppSettings, VOICE_LANGUAGE_AUTO } from '../stores/appSettings';
import { useComposerDrafts } from '../stores/composerDrafts';
import { hostSnippets, insertLiteralAtDraftSelection, type ManagedHostItem } from '../stores/hostSnippets';
import ComposerRecordingMode from './ComposerRecordingMode.vue';
import DictationMicIcon from './DictationMicIcon.vue';

const props = withDefaults(defineProps<{
  targetKey: string;
  targetLabel?: string;
  hostId?: string;
  keyboardVisible?: boolean;
  transportState: 'connected' | 'lost' | 'closed';
  writePty: PtyWriteEffect;
  /** Android opens the shared composer in a modal sheet from the terminal dock. */
  mobileSheet?: boolean;
  /** A delivered Android prompt returns to the normal-flow workspace composer. */
  mobileInline?: boolean;
  open?: boolean;
}>(), {
  targetLabel: '',
  hostId: '',
  keyboardVisible: false,
  mobileSheet: false,
  mobileInline: false,
  open: false,
});
const emit = defineEmits<{
  openChange: [open: boolean];
  openKeys: [];
  manage: [];
  submitDelivered: [];
}>();
const mobileComposer = computed(() => props.mobileSheet || props.mobileInline);

type DictationPhase = 'idle' | 'starting' | 'recording' | 'transcribing' | 'review';

interface ActiveDictation {
  targetKey: string;
  baseDraft: string;
  controller: DictationController;
  unsubscribe: (() => void) | null;
  finished: Promise<'stopped' | 'cancelled'>;
  resolveFinished: (result: 'stopped' | 'cancelled') => void;
  cancelled: boolean;
  showCancelStatus: boolean;
  sawListening: boolean;
  stopRequested: boolean;
  deliveryChosen: boolean;
  failureCode: string | null;
}

const drafts = useComposerDrafts();
const appSettings = useAppSettings();
const selectedChipKey = ref('');
const selectionTargetKey = ref('');
const lastSelectionStart = ref(0);
const lastSelectionEnd = ref(0);
const sendingIntent = ref<ComposerDeliveryIntent | null>(null);
const acknowledgedWrites = ref(0);
const statusText = ref('');
const statusTone = ref<'quiet' | 'success' | 'warning' | 'error'>('quiet');
const discardArmed = ref(false);
const dictationPhase = ref<DictationPhase>('idle');
const elapsedMs = ref(0);
const activeDictation = shallowRef<ActiveDictation | null>(null);
const draftInput = ref<HTMLTextAreaElement | null>(null);
const sheetCloseButton = ref<HTMLButtonElement | null>(null);
let recordingStartedAt = 0;
let recordingTimer: ReturnType<typeof setInterval> | null = null;
let appStateListener: PluginListenerHandle | null = null;
let composerUnmounting = false;
let deliveryEpoch = 0;

function createObservedDelivery() {
  const epoch = deliveryEpoch;
  return createComposerDeliveryController(async (bytes, context) => {
    const acknowledgement = await props.writePty(bytes, context);
    if (acknowledgement.ok && epoch === deliveryEpoch) acknowledgedWrites.value += 1;
    return acknowledgement;
  });
}

const delivery = shallowRef(createObservedDelivery());
const draft = computed(() => drafts.draftFor(props.targetKey));
const commandChips = computed(() => hostSnippets.itemsForHost(props.hostId));
const dictationPreview = ref('');
const dictationReviewResult = ref<'ready' | 'empty' | 'error'>('ready');
/** #3062: the shared controller's "listening, but no words recognized" signal. Never touches the draft. */
const dictationNoSpeech = shallowRef<DictationNoSpeech | null>(null);
const failureReviewEdited = ref(false);
const dictationBusy = computed(() => dictationPhase.value === 'starting'
  || dictationPhase.value === 'recording'
  || dictationPhase.value === 'transcribing');
const composerTitle = computed(() => dictationPhase.value === 'review'
  ? 'Review dictation'
  : dictationBusy.value ? 'Prompt dictation' : 'Prompt Composer');
const composerModeStatus = computed(() => {
  if (transportStateIsOffline()) return props.transportState === 'lost' ? 'RECONNECTING' : 'OFFLINE';
  switch (dictationPhase.value) {
    case 'starting': return 'STARTING';
    case 'recording': return dictationNoSpeech.value ? 'NO WORDS' : 'LISTENING';
    case 'transcribing': return 'TRANSCRIBING';
    case 'review': return 'REVIEW';
    case 'idle': return 'READY';
  }
});
const composerModeStatusClass = computed(() => {
  if (props.transportState !== 'connected') return props.transportState === 'lost' ? 'state-tag--warning' : 'state-tag--muted';
  if (dictationPhase.value === 'recording' && dictationNoSpeech.value) return 'state-tag--warning';
  return dictationPhase.value === 'idle' ? 'state-tag--success' : 'state-tag--dictation';
});
const composerModeStatusLabel = computed(() => {
  if (props.transportState !== 'connected') {
    return props.transportState === 'lost' ? 'Terminal reconnecting' : 'No live terminal session';
  }
  if (dictationPhase.value === 'starting') return 'Prompt dictation is starting';
  if (dictationPhase.value === 'recording' && dictationNoSpeech.value) return 'Prompt dictation is listening but not hearing words';
  if (dictationPhase.value === 'recording') return 'Prompt dictation is listening';
  if (dictationPhase.value === 'transcribing') return 'Prompt dictation is transcribing';
  if (dictationPhase.value === 'review' && dictationReviewResult.value === 'empty') return 'No speech was recognized';
  if (dictationPhase.value === 'review' && dictationReviewResult.value === 'error') return 'Dictation ended with a recognition error';
  if (dictationPhase.value === 'review') return 'Dictation transcript is ready to review';
  return 'Terminal ready';
});
const composerReviewText = computed(() => {
  if (dictationReviewResult.value === 'empty') return 'No speech was recognized. Your draft is unchanged. Edit it to continue.';
  if (dictationReviewResult.value === 'error') return 'Recognition stopped. Edit the draft before inserting or sending.';
  return 'Transcript ready. Edit it, then choose Insert or Send.';
});
const composerReviewStatusEmpty = computed(() => dictationPhase.value === 'review'
  && statusTone.value === 'quiet'
  && statusText.value.length === 0);
const composerStatusText = computed(() => {
  if (composerReviewStatusEmpty.value) return '';
  return statusText.value || (props.transportState === 'connected'
    ? 'Insert leaves the line at the terminal prompt. Send presses Enter.'
    : 'Reconnect or attach a live session to send input.');
});
const elapsedLabel = computed(() => {
  const totalSeconds = Math.floor(elapsedMs.value / 1_000);
  const minutes = Math.floor(totalSeconds / 60).toString().padStart(2, '0');
  const seconds = (totalSeconds % 60).toString().padStart(2, '0');
  return `${minutes}:${seconds}`;
});
const canDeliver = computed(() => props.targetKey.length > 0
  && props.transportState === 'connected'
  && (draft.value.length > 0 || (activeDictation.value !== null && dictationPreview.value.trim().length > 0))
  && dictationPhase.value !== 'starting'
  && activeDictation.value?.failureCode == null
  && !(dictationPhase.value === 'review' && dictationReviewResult.value !== 'ready' && !failureReviewEdited.value)
  && sendingIntent.value === null);

function transportStateIsOffline(): boolean {
  return props.transportState !== 'connected';
}

watch(() => props.transportState, (state) => delivery.value.setTransportState(state), { immediate: true });
watch(() => props.writePty, () => {
  delivery.value.setTransportState('closed');
  deliveryEpoch += 1;
  delivery.value = createObservedDelivery();
  delivery.value.setTransportState(props.transportState);
});
watch(() => props.targetKey, () => {
  const operation = activeDictation.value;
  // The shared controller ends the run on a target change but keeps its text;
  // the completed snapshot lands under the original target's draft (#3060).
  if (operation && operation.targetKey !== props.targetKey) operation.controller.setTarget(props.targetKey);
  // Invalidate an in-flight paste before installing a controller for another
  // PTY. Otherwise its next bracketed-paste chunk could land in the new shell.
  delivery.value.setTransportState('closed');
  deliveryEpoch += 1;
  delivery.value = createObservedDelivery();
  delivery.value.setTransportState(props.transportState);
  sendingIntent.value = null;
  acknowledgedWrites.value = 0;
  statusText.value = '';
  statusTone.value = 'quiet';
  discardArmed.value = false;
  dictationPreview.value = '';
  dictationReviewResult.value = 'ready';
  failureReviewEdited.value = false;
  dictationNoSpeech.value = null;
  dictationPhase.value = 'idle';
  if (props.mobileSheet) emit('openChange', false);
  selectionTargetKey.value = props.targetKey;
  lastSelectionStart.value = draft.value.length;
  lastSelectionEnd.value = draft.value.length;
  selectedChipKey.value = '';
}, { flush: 'sync' });

watch(() => props.open || props.mobileInline, (open, previousOpen) => {
  if (!mobileComposer.value) return;
  if (open) {
    void nextTick(() => {
      if (props.mobileSheet && draftInput.value) {
        draftInput.value.focus({ preventScroll: true });
        const cursor = draftInput.value.value.length;
        draftInput.value.setSelectionRange(cursor, cursor);
      } else {
        sheetCloseButton.value?.focus({ preventScroll: true });
      }
    });
  } else if (previousOpen) {
    // Back, swipe or a scrim tap closes the composer: stop and keep the text.
    const operation = activeDictation.value;
    if (operation) interruptDictation(operation, 'closed');
    draftInput.value?.blur();
  }
}, { immediate: true });

onBeforeUnmount(() => {
  composerUnmounting = true;
  void appStateListener?.remove();
  const operation = activeDictation.value;
  if (operation) interruptDictation(operation, 'closed');
  stopRecordingTimer();
});

onMounted(() => {
  void CapacitorApp.addListener('appStateChange', ({ isActive }) => {
    // Screen-off/background stops recognition but keeps the dictated text.
    activeDictation.value?.controller.setForeground(isActive);
  }).then((listener) => {
    if (composerUnmounting) void listener.remove();
    else appStateListener = listener;
  }).catch(() => {
    // Browser previews may not provide the Android lifecycle plugin.
  });
});

function setDraft(event: Event) {
  const target = event.target as HTMLTextAreaElement | null;
  if (!target || typeof target.value !== 'string') return;
  if (dictationBusy.value) {
    // Some Android IMEs still deliver an input after beforeinput was canceled.
    // Keep the browser field synchronized with the JS transcript without
    // letting manual key events replace the active recognition result.
    target.value = drafts.draftFor(props.targetKey);
    return;
  }
  const previousDraft = drafts.draftFor(props.targetKey);
  if (dictationPhase.value === 'review'
    && dictationReviewResult.value !== 'ready'
    && target.value !== previousDraft) {
    failureReviewEdited.value = true;
  }
  drafts.setDraft(props.targetKey, target.value);
  selectionTargetKey.value = props.targetKey;
  lastSelectionStart.value = target.selectionStart;
  lastSelectionEnd.value = target.selectionEnd;
  selectedChipKey.value = '';
  statusText.value = '';
  statusTone.value = 'quiet';
  discardArmed.value = false;
}

function blockDraftEditsDuringDictation(event: Event) {
  if (dictationBusy.value) event.preventDefault();
}

function preserveDraftFocus(event: PointerEvent) {
  if (props.mobileSheet) return;
  const target = event.target;
  if (target instanceof Element && target.closest('button') && document.activeElement === draftInput.value) {
    // Android hides the IME when a tapped control takes focus. Keep the draft
    // focused while dictation controls are used so partials stay visible above
    // the keyboard and the user can review the result in the same composer.
    event.preventDefault();
  }
}

function rememberDraftSelection(event?: Event) {
  const target = event?.target instanceof HTMLTextAreaElement ? event.target : draftInput.value;
  if (!target || target !== document.activeElement) return;
  selectionTargetKey.value = props.targetKey;
  lastSelectionStart.value = target.selectionStart;
  lastSelectionEnd.value = target.selectionEnd;
}

function chipLabel(item: ManagedHostItem): string {
  const savedLabel = item.label?.trim();
  if (savedLabel) return savedLabel;
  const firstLine = item.body.split(/\r?\n/u, 1)[0]?.trim();
  return firstLine || (item.collection === 'template' ? 'Command template' : 'Snippet');
}

function insertChip(item: ManagedHostItem) {
  if (!props.targetKey || dictationBusy.value) return;
  const insertion = hostSnippets.insertionFor(props.hostId, item.collection, item.id);
  if (!insertion || insertion.submit !== false) return;
  const current = drafts.draftFor(props.targetKey);
  let start = current.length;
  let end = current.length;
  if (selectionTargetKey.value === props.targetKey) {
    start = lastSelectionStart.value;
    end = lastSelectionEnd.value;
  }
  if (draftInput.value && document.activeElement === draftInput.value) {
    start = draftInput.value.selectionStart;
    end = draftInput.value.selectionEnd;
  }
  const result = insertLiteralAtDraftSelection(current, insertion.text, start, end);
  drafts.setDraft(props.targetKey, result.draft);
  selectionTargetKey.value = props.targetKey;
  lastSelectionStart.value = result.caret;
  lastSelectionEnd.value = result.caret;
  selectedChipKey.value = item.key;
  statusTone.value = 'quiet';
  statusText.value = `${chipLabel(item)} added to the draft. Send is separate.`;
  discardArmed.value = false;
  void nextTick(() => {
    if (draftInput.value && document.activeElement === draftInput.value) {
      draftInput.value.setSelectionRange(result.caret, result.caret);
    }
  });
}

function nextOperationId(): string {
  if (globalThis.crypto && 'randomUUID' in globalThis.crypto) return globalThis.crypto.randomUUID();
  return `composer-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

function appendTranscript(previous: string, next: string): string {
  const left = previous.trimEnd();
  const right = next.trimStart();
  if (!right) return left;
  return left ? `${left} ${right}` : right;
}

function renderDictationDraft(operation: ActiveDictation, completedTranscript: string, partialTranscript: string) {
  const transcript = appendTranscript(completedTranscript, partialTranscript);
  dictationPreview.value = transcript;
  const finalText = completedTranscript.trim();
  if (!finalText) return;
  const separator = operation.baseDraft && !/\s$/u.test(operation.baseDraft) ? ' ' : '';
  drafts.setDraft(operation.targetKey, `${operation.baseDraft}${separator}${finalText}`);
}

function startRecordingTimer(resetElapsed = true) {
  stopRecordingTimer();
  if (resetElapsed) elapsedMs.value = 0;
  recordingStartedAt = performance.now() - elapsedMs.value;
  recordingTimer = setInterval(() => {
    elapsedMs.value = performance.now() - recordingStartedAt;
  }, 200);
}

function stopRecordingTimer() {
  if (recordingTimer !== null) clearInterval(recordingTimer);
  recordingTimer = null;
}

function detachDictation(operation: ActiveDictation) {
  operation.unsubscribe?.();
  operation.unsubscribe = null;
}

function finishDictationCancellation(operation: ActiveDictation) {
  if (operation.cancelled) return;
  operation.cancelled = true;
  detachDictation(operation);
  operation.resolveFinished('cancelled');
  if (activeDictation.value !== operation) return;
  activeDictation.value = null;
  dictationPhase.value = 'idle';
  dictationNoSpeech.value = null;
  stopRecordingTimer();
  dictationPreview.value = '';
  drafts.setDraft(operation.targetKey, operation.baseDraft);
  if (operation.showCancelStatus) {
    statusTone.value = 'quiet';
    statusText.value = 'Dictation cancelled. Your original draft was restored.';
  }
  dictationReviewResult.value = 'ready';
  failureReviewEdited.value = false;
}

const INTERRUPTED_STATUS: Record<DictationInterruptReason, string> = {
  background: 'Dictation stopped when PocketShell went to the background. Your text was kept in the draft.',
  closed: 'Dictation stopped when the composer closed. Your text was kept in the draft.',
  'target-change': 'Dictation stopped when the session changed. Your text was kept in its draft.',
};

function finishDictationReview(operation: ActiveDictation, snapshot: DictationSnapshot, hasError: boolean) {
  renderDictationDraft(operation, snapshot.transcript, '');
  dictationReviewResult.value = hasError ? 'error' : snapshot.transcript.trim() ? 'ready' : 'empty';
  failureReviewEdited.value = false;
  stopRecordingTimer();
  dictationPreview.value = '';
  detachDictation(operation);
  if (activeDictation.value === operation) activeDictation.value = null;
  dictationPhase.value = 'review';
  operation.resolveFinished(hasError ? 'cancelled' : 'stopped');
  if (!operation.deliveryChosen && !hasError) {
    statusTone.value = 'quiet';
    statusText.value = '';
  }
}

function handleDictationSnapshot(operation: ActiveDictation, snapshot: DictationSnapshot) {
  if (operation.cancelled || activeDictation.value !== operation) return;
  dictationNoSpeech.value = snapshot.phase === 'starting' || snapshot.phase === 'listening' ? snapshot.noSpeech : null;
  if (snapshot.phase === 'starting' && operation.sawListening && !operation.stopRequested) {
    // #3062: a silent restart between recognizer turns is still the same
    // recording to the user; do not flash "Requesting microphone access".
    dictationPhase.value = 'recording';
    renderDictationDraft(operation, snapshot.transcript, snapshot.partial);
    return;
  }
  if (snapshot.phase === 'starting') {
    dictationPhase.value = 'starting';
    renderDictationDraft(operation, snapshot.transcript, snapshot.partial);
    return;
  }
  if (snapshot.phase === 'listening') {
    dictationPhase.value = 'recording';
    if (!operation.sawListening) {
      operation.sawListening = true;
      startRecordingTimer();
    }
    renderDictationDraft(operation, snapshot.transcript, snapshot.partial);
    return;
  }
  if (snapshot.phase === 'stopping') {
    dictationPhase.value = 'transcribing';
    stopRecordingTimer();
    renderDictationDraft(operation, snapshot.transcript, snapshot.partial);
    return;
  }
  if (snapshot.phase === 'cancelled') {
    finishDictationCancellation(operation);
    return;
  }
  if (snapshot.phase === 'completed') {
    finishDictationReview(operation, snapshot, false);
    if (snapshot.interruptReason && !operation.deliveryChosen) {
      statusTone.value = 'quiet';
      statusText.value = INTERRUPTED_STATUS[snapshot.interruptReason];
    }
    return;
  }
  if (snapshot.phase === 'error'
    && operation.stopRequested
    && snapshot.error?.code === 'recognizer-stop-timeout'
    && snapshot.transcript.trim()) {
    // The recognizer never confirmed the last turn after Stop. The shared
    // controller already promoted its latest partial, so finish with the whole
    // utterance instead of blocking the Insert/Send the user asked for.
    finishDictationReview(operation, snapshot, false);
    return;
  }
  if (snapshot.phase === 'error') {
    operation.failureCode = snapshot.error?.code ?? 'speech recognition failed';
    if (!operation.sawListening && !operation.stopRequested && !snapshot.transcript.trim()) {
      const message = snapshot.error?.message ?? 'Speech recognition could not start.';
      operation.cancelled = true;
      detachDictation(operation);
      operation.resolveFinished('cancelled');
      if (activeDictation.value === operation) {
        activeDictation.value = null;
        dictationPhase.value = 'idle';
        stopRecordingTimer();
        drafts.setDraft(operation.targetKey, operation.baseDraft);
        dictationPreview.value = '';
        statusTone.value = 'warning';
        statusText.value = `Dictation could not start: ${message}`;
      }
      return;
    }
    statusTone.value = 'error';
    statusText.value = `Dictation stopped: ${snapshot.error?.message ?? 'speech recognition failed'}. Review the draft before sending.`;
    finishDictationReview(operation, snapshot, true);
  }
}

function toggleDictation() {
  if (activeDictation.value) {
    void stopDictation(activeDictation.value);
    return;
  }
  if (dictationPhase.value === 'starting' || !props.targetKey) return;

  let resolveFinished!: (result: 'stopped' | 'cancelled') => void;
  const finished = new Promise<'stopped' | 'cancelled'>((resolve) => {
    resolveFinished = resolve;
  });
  const operation: ActiveDictation = {
    targetKey: props.targetKey,
    baseDraft: drafts.draftFor(props.targetKey),
    controller: createSharedDictationController({
      ...(appSettings.voiceLanguage === VOICE_LANGUAGE_AUTO ? {} : { languageTag: appSettings.voiceLanguage }),
      silenceWindowMs: appSettings.voiceSilenceSeconds * 1_000,
    }),
    unsubscribe: null,
    finished,
    resolveFinished,
    cancelled: false,
    showCancelStatus: false,
    sawListening: false,
    stopRequested: false,
    deliveryChosen: false,
    failureCode: null,
  };
  dictationPreview.value = '';
  dictationReviewResult.value = 'ready';
  failureReviewEdited.value = false;
  dictationNoSpeech.value = null;
  activeDictation.value = operation;
  dictationPhase.value = 'starting';
  elapsedMs.value = 0;
  statusTone.value = 'quiet';
  statusText.value = 'Your draft stays in the composer until you tap Insert or Send.';
  discardArmed.value = false;

  operation.controller.setTarget(operation.targetKey);
  operation.unsubscribe = operation.controller.subscribe((snapshot) => handleDictationSnapshot(operation, snapshot));
  operation.controller.start();
}

function stopDictation(operation: ActiveDictation, updateStatus = true): boolean {
  if (operation.cancelled || activeDictation.value !== operation) return false;
  if (operation.stopRequested) return true;
  operation.stopRequested = true;
  dictationNoSpeech.value = null;
  dictationPhase.value = 'transcribing';
  stopRecordingTimer();
  if (updateStatus) {
    statusTone.value = 'quiet';
    statusText.value = 'Your text is being prepared for review. It will not be sent automatically.';
  }
  operation.controller.stop();
  return true;
}

/** Explicit user Cancel/Discard: the only path that restores the base draft. */
function cancelDictation(operation: ActiveDictation, showStatus = true) {
  if (operation.cancelled) return;
  operation.showCancelStatus = showStatus;
  operation.controller.cancel('user');
  if (!operation.cancelled && operation.controller.getSnapshot().phase === 'cancelled') {
    finishDictationCancellation(operation);
  }
}

/** Lifecycle stop (close/unmount): end recognition now and keep every dictated word. */
function interruptDictation(operation: ActiveDictation, reason: DictationInterruptReason) {
  if (operation.cancelled || activeDictation.value !== operation) return;
  operation.controller.interrupt(reason);
}

function showResult(result: ComposerDeliveryResult, intent: ComposerDeliveryIntent) {
  if (result.status === 'delivered') {
    statusTone.value = 'success';
    statusText.value = intent === 'insert'
      ? 'Inserted into the terminal without pressing Enter.'
      : 'Sent to the terminal.';
  } else if (result.status === 'uncertain') {
    statusTone.value = 'warning';
    statusText.value = `Delivery is uncertain during ${result.stage}. Draft retained; check the terminal before trying again.`;
  } else {
    statusTone.value = 'warning';
    statusText.value = 'The terminal did not accept this action. Draft retained.';
  }
}

async function deliver(intent: ComposerDeliveryIntent) {
  const targetKey = props.targetKey;
  const operation = activeDictation.value;
  if (!targetKey
    || (drafts.draftFor(targetKey).length === 0 && !(operation && dictationPreview.value.trim()))
    || !canDeliver.value) return;
  const activeDelivery = delivery.value;
  sendingIntent.value = intent;
  acknowledgedWrites.value = 0;
  statusTone.value = 'quiet';
  statusText.value = operation
    ? 'Stopping dictation before ' + (intent === 'insert' ? 'Insert' : 'Send') + '.'
    : intent === 'insert' ? 'Inserting into the terminal…' : 'Sending to the terminal…';
  if (operation) {
    operation.deliveryChosen = true;
    stopRecordingTimer();
    dictationPhase.value = 'transcribing';
    const stopAccepted = stopDictation(operation, false);
    if (!stopAccepted) {
      operation.deliveryChosen = false;
      if (sendingIntent.value === intent) sendingIntent.value = null;
      return;
    }
    if (operation.cancelled || delivery.value !== activeDelivery || props.targetKey !== targetKey) {
      if (sendingIntent.value === intent) sendingIntent.value = null;
      return;
    }
    const finishResult = await operation.finished;
    if (finishResult !== 'stopped' || operation.failureCode || delivery.value !== activeDelivery || props.targetKey !== targetKey) {
      if (sendingIntent.value === intent) sendingIntent.value = null;
      return;
    }
    if (dictationReviewResult.value === 'empty') {
      // #3062: the user tapped Send/Insert to deliver what they dictated, and
      // nothing was recognized. Say so instead of silently delivering only the
      // earlier draft; the draft stays for review.
      if (sendingIntent.value === intent) sendingIntent.value = null;
      statusTone.value = 'warning';
      statusText.value = `No speech was recognized. Nothing was ${intent === 'insert' ? 'inserted' : 'sent'}; your draft was kept.`;
      return;
    }
  }
  const payload = drafts.draftFor(targetKey);
  if (payload.length === 0) {
    if (sendingIntent.value === intent) {
      sendingIntent.value = null;
      statusTone.value = 'warning';
      statusText.value = 'No final transcript was received. Nothing was sent.';
    }
    return;
  }
  const result = await activeDelivery.deliver({
    operationId: nextOperationId(),
    payload,
    intent,
  });
  if (result.draftEffect === 'clear') {
    drafts.clearDraft(targetKey);
    dictationPhase.value = 'idle';
    dictationReviewResult.value = 'ready';
    failureReviewEdited.value = false;
  }
  if (delivery.value !== activeDelivery) return;
  showResult(result, intent);
  sendingIntent.value = null;
  if (mobileComposer.value && intent === 'submit' && result.status === 'delivered') {
    draftInput.value?.blur();
    emit('submitDelivered');
  }
}

function discardDraft() {
  if (dictationPhase.value === 'starting' || dictationPhase.value === 'recording' || dictationPhase.value === 'transcribing') return;
  if (!discardArmed.value) {
    discardArmed.value = true;
    statusTone.value = 'warning';
    statusText.value = 'Tap Discard again to clear this draft.';
    return;
  }
  drafts.clearDraft(props.targetKey);
  dictationPhase.value = 'idle';
  dictationReviewResult.value = 'ready';
  failureReviewEdited.value = false;
  discardArmed.value = false;
  statusTone.value = 'quiet';
  statusText.value = 'Draft cleared.';
}

function requestClose() {
  if (!mobileComposer.value || (!props.open && !props.mobileInline) || sendingIntent.value !== null) return;
  const operation = activeDictation.value;
  if (operation) interruptDictation(operation, 'closed');
  draftInput.value?.blur();
  emit('openChange', false);
}

function requestTerminalKeys() {
  if (!mobileComposer.value || (!props.open && !props.mobileInline) || dictationBusy.value || sendingIntent.value !== null) return;
  emit('openKeys');
}

function startPromptDictation() {
  // Start native recognition directly from the microphone tap, then dismiss
  // Android's IME so the recording state and its Stop/Cancel actions have room.
  void toggleDictation();
  if (mobileComposer.value) draftInput.value?.blur();
}
</script>

<template>
  <Teleport to="#prompt-composer-portal" :disabled="!mobileSheet">
    <div v-if="mobileSheet && open" class="composer-sheet-scrim" data-testid="prompt-composer-scrim"
      @click.self="requestClose" />
    <section v-if="!mobileSheet || open" class="composer-panel"
      :class="{ 'composer-panel--sheet': mobileSheet, 'composer-panel--mobile': mobileComposer, 'composer-panel--dictating': dictationBusy }"
      aria-labelledby="composer-title" data-testid="prompt-composer"
      :role="mobileSheet ? 'dialog' : undefined" :aria-modal="mobileSheet ? 'true' : undefined"
      :data-target-key="targetKey" :data-transport-state="transportState"
      :data-acknowledged-writes="acknowledgedWrites" :data-dictation-state="dictationPhase">
      <div v-if="mobileSheet" class="composer-sheet-handle" aria-hidden="true"><span /></div>
      <div class="composer-heading">
        <div class="composer-heading__copy">
          <h3 id="composer-title">{{ composerTitle }}</h3>
          <p v-if="targetLabel" class="composer-target-label">{{ targetLabel }}</p>
        </div>
        <span class="state-tag" :class="composerModeStatusClass" data-testid="composer-mode-status"
          :data-dictation-phase="dictationPhase" :aria-label="composerModeStatusLabel">
          {{ composerModeStatus }}
        </span>
        <button v-if="mobileComposer && !dictationBusy" class="composer-open-keys" type="button"
          data-testid="composer-open-keys" aria-label="More terminal keys" title="More terminal keys"
          :disabled="sendingIntent !== null" @pointerdown.prevent @click="requestTerminalKeys">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-linecap="round"
            stroke-linejoin="round" stroke-width="1.8" aria-hidden="true" focusable="false">
            <rect x="3" y="5" width="18" height="14" rx="2" />
            <path d="M7 9h.01M10.5 9h.01M14 9h.01M17.5 9h.01M7 12h.01M10.5 12h.01M14 12h.01M17.5 12h.01M8.5 15.5h7" />
          </svg>
        </button>
        <button v-if="mobileComposer && !dictationBusy" ref="sheetCloseButton" class="composer-sheet-close" type="button"
          data-testid="composer-close" aria-label="Close prompt composer" @click="requestClose">
          <AppIcon name="close" aria-hidden="true" />
        </button>
      </div>

      <div v-show="!keyboardVisible" class="command-chips" data-testid="command-chips" role="group"
        :aria-label="hostId ? `Command chips for ${targetLabel}` : 'Command chips'">
        <div class="command-chips__rail" data-testid="command-chip-list">
          <button
            v-for="item in commandChips"
            :key="item.key"
            class="command-chip"
            type="button"
            data-testid="snippet-chip"
            :data-chip-key="item.key"
            :aria-label="`Insert ${chipLabel(item)}`"
            :aria-current="selectedChipKey === item.key ? 'true' : undefined"
            :title="chipLabel(item)"
            :disabled="dictationBusy || sendingIntent !== null"
            @click="insertChip(item)"
          >{{ chipLabel(item) }}</button>
          <span v-if="commandChips.length === 0" class="command-chips__empty">No chips saved for this host.</span>
          <button class="command-chip command-chip--manage" type="button" data-testid="manage-snippets"
            aria-label="Manage command chips" :disabled="dictationBusy || sendingIntent !== null"
            @click="emit('manage')">
            <span>Manage</span>
          </button>
        </div>
      </div>

      <div class="composer-draft-row" :class="{ 'composer-draft-row--dictating': dictationBusy }">
        <label class="sr-only" for="prompt-draft">Prompt draft</label>
        <textarea
          id="prompt-draft"
          class="composer-draft"
          :class="{ 'composer-draft--dictation-anchor': dictationBusy }"
          data-testid="prompt-draft"
          :aria-label="dictationPhase === 'review' ? 'Dictation transcript, editable before inserting or sending' : dictationBusy ? 'Prompt dictation draft, read only during capture' : 'Prompt draft'"
          :aria-describedby="dictationBusy ? (dictationPhase === 'starting' ? 'composer-status' : 'composer-recording-preview composer-status') : undefined"
          ref="draftInput"
          :value="draft"
          :disabled="targetKey.length === 0 || sendingIntent !== null"
          :aria-readonly="dictationBusy ? 'true' : 'false'"
          :placeholder="targetKey ? 'Write a prompt for this session…' : 'Attach a session to start a draft.'"
          spellcheck="false"
          autocapitalize="sentences"
          enterkeyhint="enter"
          @beforeinput="blockDraftEditsDuringDictation"
          @input="setDraft"
          @select="rememberDraftSelection"
          @keyup="rememberDraftSelection"
          @click="rememberDraftSelection"
        />
      </div>

      <ComposerRecordingMode
        v-if="dictationPhase === 'starting' || dictationPhase === 'recording' || dictationPhase === 'transcribing'"
        :state="dictationPhase"
        :elapsed-label="elapsedLabel"
        :live-preview="dictationPreview"
        :no-speech="dictationNoSpeech"
        :stop-disabled="sendingIntent !== null"
        @stop="activeDictation && stopDictation(activeDictation)"
      />
      <p v-else-if="dictationPhase === 'review'" class="composer-review" data-testid="composer-dictation-review"
        role="status" aria-live="polite">
        {{ composerReviewText }}
      </p>

      <p id="composer-status" class="composer-status" :class="{
          'composer-status--dictation': dictationBusy && statusTone !== 'error',
          'composer-status--review-empty': composerReviewStatusEmpty,
        }"
        role="status" aria-live="polite" data-testid="composer-status"
        :data-delivery-state="statusTone" :data-delivery-intent="sendingIntent ?? ''">
        {{ composerStatusText }}
      </p>

      <div class="composer-actions" :class="{ 'composer-actions--dictation': dictationBusy }"
        data-testid="composer-actions" @pointerdown.capture="preserveDraftFocus">
        <template v-if="dictationPhase === 'idle' || dictationPhase === 'review'">
          <button v-if="draft.length > 0" class="composer-discard" type="button" data-testid="composer-discard" :disabled="sendingIntent !== null"
            @click="discardDraft">{{ discardArmed ? 'Discard?' : 'Discard' }}</button>
          <span v-if="draft.length === 0" class="composer-action-spacer"></span>
          <button v-if="!mobileSheet" class="composer-insert" type="button" data-testid="composer-dictate"
            :disabled="sendingIntent !== null || !targetKey" :aria-pressed="dictationPhase === 'review'"
            @click="startPromptDictation">Dictate</button>
          <button class="composer-insert" type="button" data-testid="composer-insert" :disabled="!canDeliver"
            @click="deliver('insert')">Insert</button>
          <ComposerControls
            class="composer-shared-controls"
            :uploading-count="0"
            :can-send="canDeliver"
            :send-in-flight="sendingIntent === 'submit'"
            :draft-length="0"
            :attachment-count="0"
            :discard-armed="false"
            @send="deliver('submit')"
          />
          <button v-if="mobileSheet" class="composer-dictate composer-dictate--mic" type="button"
            data-testid="composer-dictate" :disabled="sendingIntent !== null || !targetKey"
            title="Dictate into prompt draft" aria-label="Dictate prompt draft" @pointerdown.prevent @click="startPromptDictation">
            <DictationMicIcon :size="20" />
          </button>
        </template>

        <div v-else class="composer-recording-actions" data-testid="composer-recording-actions"
          role="group" aria-label="Dictation controls">
          <button class="composer-recording-action composer-recording-action--discard" type="button"
            data-testid="composer-recording-cancel"
            :aria-label="dictationPhase === 'recording' ? 'Discard recording without transcribing' : 'Cancel dictation and restore the original draft'"
            :disabled="sendingIntent !== null"
            @click="activeDictation && cancelDictation(activeDictation)">
            {{ dictationPhase === 'recording' ? 'Discard' : 'Cancel' }}
          </button>
          <button v-if="dictationPhase === 'recording'"
            class="composer-recording-action composer-recording-action--insert"
            type="button" data-testid="composer-insert" :disabled="!canDeliver || sendingIntent !== null"
            @click="deliver('insert')">
            Insert
          </button>
          <button v-if="dictationPhase === 'recording' || dictationPhase === 'transcribing'"
            class="composer-recording-action composer-recording-action--send" type="button"
            data-testid="composer-dictation-send" :disabled="!canDeliver || sendingIntent !== null"
            @click="deliver('submit')">
            {{ sendingIntent === 'submit' ? 'Sending…' : 'Send' }}
          </button>
        </div>
      </div>
    </section>
  </Teleport>
</template>

<style scoped>
.composer-sheet-scrim {
  position: fixed;
  z-index: 90;
  inset: 0;
  background: var(--scrim);
  backdrop-filter: blur(2px);
}

.composer-panel--sheet {
  position: fixed;
  z-index: 91;
  inset-inline: 0;
  bottom: var(--safe-area-inset-bottom, env(safe-area-inset-bottom, 0px));
  display: grid;
  width: min(100%, 640px);
  max-width: 640px;
  max-height: min(86dvh, calc(100dvh - 12px));
  min-width: 0;
  grid-template-columns: minmax(0, 1fr);
  align-content: start;
  gap: 12px;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
  margin-inline: auto;
  border: 1px solid var(--border-strong);
  border-bottom: 0;
  border-radius: 22px 22px 0 0;
  background: var(--surface);
  padding: 8px 16px calc(16px + var(--safe-area-inset-bottom, env(safe-area-inset-bottom, 0px)));
  box-shadow: 0 -10px 36px var(--scrim);
}

.composer-sheet-handle { display: flex; height: 12px; align-items: flex-start; justify-content: center; }
.composer-sheet-handle span { width: 36px; height: 4px; border-radius: 999px; background: var(--border-strong); }
.composer-panel--mobile { grid-template-columns: minmax(0, 1fr); }
.composer-panel--mobile .composer-heading { min-height: 48px; gap: 8px; }
.composer-panel--mobile .composer-heading__copy { min-width: 0; flex: 1 1 auto; }
.composer-panel--mobile .composer-heading h3 { overflow: hidden; font-size: var(--fs-300); text-overflow: ellipsis; white-space: nowrap; }
.composer-panel--mobile .composer-target-label { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.composer-panel--mobile .composer-open-keys,
.composer-panel--mobile .composer-sheet-close {
  display: inline-flex;
  width: 48px;
  height: 48px;
  flex: 0 0 48px;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--border-soft);
  border-radius: 50%;
  background: var(--surface-2);
  color: var(--fg-secondary);
}
.composer-panel--mobile .composer-open-keys:hover:not(:disabled),
.composer-panel--mobile .composer-sheet-close:hover:not(:disabled) { border-color: var(--border-strong); color: var(--fg); }
.composer-panel--mobile .composer-open-keys:disabled { opacity: var(--disabled-opacity); }
.composer-panel--mobile .composer-open-keys svg { width: 20px; height: 20px; fill: none; stroke: currentColor; stroke-linecap: round; stroke-linejoin: round; stroke-width: 1.8; }
.composer-panel--mobile .composer-sheet-close svg { width: 18px; height: 18px; }
.composer-panel--sheet .composer-draft-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  align-items: stretch;
  gap: 10px;
}
.composer-panel--sheet .composer-draft {
  min-height: 90px;
  max-height: min(28dvh, 220px);
  resize: vertical;
}
.composer-draft-row--dictating { display: block; height: 1px; overflow: visible; }
.composer-dictate--mic {
  display: inline-flex;
  width: 48px;
  min-width: 48px;
  height: 48px;
  flex: 0 0 48px;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--border-strong);
  border-radius: 50%;
  background: var(--surface-2);
  color: var(--fg);
  padding: 0;
}
.composer-dictate--mic:hover:not(:disabled) { border-color: var(--accent-dim); color: var(--accent); }
.composer-dictate--mic:disabled { opacity: var(--disabled-opacity); }
.composer-panel--sheet .composer-status { min-height: 16px; }
.composer-panel--sheet .composer-actions { min-height: 48px; gap: 6px; }
.composer-panel--sheet .composer-actions--dictation { min-height: 48px; }
.composer-panel--sheet .composer-discard,
.composer-panel--sheet .composer-insert,
.composer-panel--sheet .composer-shared-controls .send { min-height: 48px; }
.composer-panel--sheet .composer-recording-action { min-height: 48px; }
.composer-panel--sheet .composer-action-spacer { flex: 1 1 4px; }
.composer-panel--sheet :is(button, textarea):focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }

.composer-actions--dictation { gap: 6px; }
.composer-recording-actions { display: flex; min-width: 0; flex: 1 1 auto; align-items: center; gap: 6px; }
.composer-recording-action {
  display: inline-flex;
  min-width: 0;
  min-height: 48px;
  flex: 1 1 0;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--border-strong);
  border-radius: var(--r-md);
  background: var(--surface-2);
  padding: 0 var(--sp-3);
  color: var(--fg);
  font-family: var(--font-ui);
  font-size: var(--fs-200);
  font-weight: var(--fw-semibold);
  cursor: pointer;
}
.composer-recording-action--discard { color: var(--fg-secondary); }
.composer-recording-action--send {
  border-color: var(--accent-dim);
  background: var(--surface-2);
  color: var(--accent);
}
.composer-recording-action:disabled { opacity: var(--disabled-opacity); cursor: default; }
.composer-recording-action:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
.composer-recording-action:active:not(:disabled) { filter: brightness(1.12); }

.composer-review {
  margin: 0;
  color: var(--fg-secondary);
  font-size: var(--fs-100);
  line-height: 1.4;
}

.state-tag--dictation { border-color: var(--accent-dim); background: var(--state-selected); color: var(--accent); }

.composer-status--review-empty { display: none; }

.composer-status--dictation {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  clip-path: inset(50%);
}

.composer-draft--dictation-anchor {
  position: fixed !important;
  z-index: -1 !important;
  top: 0 !important;
  left: 0 !important;
  width: 1px !important;
  min-width: 1px !important;
  max-width: 1px !important;
  height: 1px !important;
  min-height: 1px !important;
  max-height: 1px !important;
  overflow: hidden !important;
  padding: 0 !important;
  border: 0 !important;
  opacity: 0 !important;
  clip-path: inset(50%);
  pointer-events: none;
}
</style>

<script lang="ts">
function errorMessage(error: unknown): string {
  return error instanceof Error && error.message !== '' ? error.message : String(error);
}
</script>
