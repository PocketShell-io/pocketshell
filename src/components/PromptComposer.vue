<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue';
import { App as CapacitorApp } from '@capacitor/app';
import type { PluginListenerHandle } from '@capacitor/core';
import { AppIcon, ComposerControls } from '@pocketshell/ui';
import type { ComposerDeliveryIntent, ComposerDeliveryResult } from '@pocketshell/core';
import { createComposerDeliveryController, type PtyWriteEffect } from '../session/composerDelivery';
import { platformInput, type DictationEvent, type DictationSession } from '../session/platformInput';
import { useAppSettings, VOICE_LANGUAGE_AUTO } from '../stores/appSettings';
import { useComposerDrafts } from '../stores/composerDrafts';
import ComposerRecordingMode from './ComposerRecordingMode.vue';

const props = withDefaults(defineProps<{
  targetKey: string;
  transportState: 'connected' | 'lost' | 'closed';
  writePty: PtyWriteEffect;
  /** Android opens the shared composer in a modal sheet from the terminal dock. */
  mobileSheet?: boolean;
  open?: boolean;
}>(), {
  mobileSheet: false,
  open: false,
});
const emit = defineEmits<{ openChange: [open: boolean] }>();

type DictationPhase = 'idle' | 'starting' | 'recording' | 'transcribing' | 'review';

interface ActiveDictation {
  targetKey: string;
  baseDraft: string;
  requestId: string | null;
  session: DictationSession | null;
  finished: Promise<'stopped' | 'cancelled'>;
  resolveFinished: (result: 'stopped' | 'cancelled') => void;
  cancelled: boolean;
  sawStarted: boolean;
  stopRequested: boolean;
  deliveryChosen: boolean;
  completedTranscript: string;
  partialTranscript: string;
}

const drafts = useComposerDrafts();
const appSettings = useAppSettings();
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
const dictationPreview = ref('');
const dictationBusy = computed(() => dictationPhase.value === 'starting'
  || dictationPhase.value === 'recording'
  || dictationPhase.value === 'transcribing');
const elapsedLabel = computed(() => {
  const totalSeconds = Math.floor(elapsedMs.value / 1_000);
  const minutes = Math.floor(totalSeconds / 60).toString().padStart(2, '0');
  const seconds = (totalSeconds % 60).toString().padStart(2, '0');
  return `${minutes}:${seconds}`;
});
const canDeliver = computed(() => props.targetKey.length > 0
  && props.transportState === 'connected'
  && draft.value.length > 0
  && dictationPhase.value !== 'starting'
  && sendingIntent.value === null);

watch(() => props.transportState, (state) => delivery.value.setTransportState(state), { immediate: true });
watch(() => props.writePty, () => {
  delivery.value.setTransportState('closed');
  deliveryEpoch += 1;
  delivery.value = createObservedDelivery();
  delivery.value.setTransportState(props.transportState);
});
watch(() => props.targetKey, () => {
  const operation = activeDictation.value;
  if (operation && operation.targetKey !== props.targetKey) cancelDictation(operation, false);
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
  if (props.mobileSheet) emit('openChange', false);
}, { flush: 'sync' });

watch(() => props.open, (open, previousOpen) => {
  if (!props.mobileSheet) return;
  if (open) {
    void nextTick(() => sheetCloseButton.value?.focus({ preventScroll: true }));
  } else if (previousOpen) {
    const operation = activeDictation.value;
    if (operation) cancelDictation(operation, false);
    draftInput.value?.blur();
  }
});

onBeforeUnmount(() => {
  composerUnmounting = true;
  void appStateListener?.remove();
  const operation = activeDictation.value;
  if (operation) cancelDictation(operation, false);
  stopRecordingTimer();
});

onMounted(() => {
  void CapacitorApp.addListener('appStateChange', ({ isActive }) => {
    if (!isActive && activeDictation.value) cancelDictation(activeDictation.value, false);
  }).then((listener) => {
    if (composerUnmounting) void listener.remove();
    else appStateListener = listener;
  }).catch(() => {
    // Browser previews may not provide the Android lifecycle plugin.
  });
});

function setDraft(event: Event) {
  const target = event.target;
  if (!(target instanceof HTMLTextAreaElement)) return;
  if (dictationBusy.value) {
    // Some Android IMEs still deliver an input after beforeinput was canceled.
    // Keep the browser field synchronized with the JS transcript without
    // letting manual key events replace the active recognition result.
    target.value = drafts.draftFor(props.targetKey);
    return;
  }
  drafts.setDraft(props.targetKey, target.value);
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

function renderDictationDraft(operation: ActiveDictation) {
  const transcript = appendTranscript(operation.completedTranscript, operation.partialTranscript);
  dictationPreview.value = transcript;
  const separator = operation.baseDraft && transcript && !/\s$/u.test(operation.baseDraft) ? ' ' : '';
  drafts.setDraft(operation.targetKey, `${operation.baseDraft}${separator}${transcript}`);
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

function handleDictationEvent(operation: ActiveDictation, event: DictationEvent) {
  if (operation.cancelled || activeDictation.value !== operation || event.requestId !== operation.requestId) return;
  if (event.type === 'started' || event.type === 'listening' || event.type === 'ready') {
    if (!operation.sawStarted) {
      operation.sawStarted = true;
      if (dictationPhase.value === 'starting') {
        dictationPhase.value = 'recording';
        startRecordingTimer();
      }
    } else if (!operation.stopRequested) {
      // SpeechRecognizer reports ready/listening again after a natural endpoint.
      // Keep the recording surface and timer active across that pause/restart.
      dictationPhase.value = 'recording';
      if (recordingTimer === null) startRecordingTimer(false);
    }
  } else if (event.type === 'processing') {
    // `processing` also marks a normal speech endpoint before Android restarts
    // recognition. Only an explicit user Stop enters the transcribing surface.
    if (operation.stopRequested) {
      dictationPhase.value = 'transcribing';
      stopRecordingTimer();
    }
  } else if (event.type === 'partial') {
    if (operation.deliveryChosen) return;
    operation.partialTranscript = event.text ?? '';
    renderDictationDraft(operation);
  } else if (event.type === 'result') {
    if (operation.deliveryChosen) return;
    operation.completedTranscript = appendTranscript(operation.completedTranscript, event.text ?? operation.partialTranscript);
    operation.partialTranscript = '';
    renderDictationDraft(operation);
  } else if (event.type === 'error') {
    statusTone.value = 'error';
    statusText.value = `Dictation stopped: ${event.code ?? 'speech recognition failed'}. Review the draft before sending.`;
  } else if (event.type === 'stopped') {
    if (!operation.deliveryChosen) renderDictationDraft(operation);
    stopRecordingTimer();
    dictationPreview.value = '';
    activeDictation.value = null;
    dictationPhase.value = 'review';
    operation.resolveFinished('stopped');
    if (!operation.deliveryChosen && statusTone.value !== 'error') {
      statusTone.value = 'quiet';
      statusText.value = 'Dictation ready for review.';
    }
  }
}

async function toggleDictation() {
  if (activeDictation.value) {
    await stopDictation(activeDictation.value);
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
    requestId: null,
    session: null,
    finished,
    resolveFinished,
    cancelled: false,
    sawStarted: false,
    stopRequested: false,
    deliveryChosen: false,
    completedTranscript: '',
    partialTranscript: '',
  };
  dictationPreview.value = '';
  activeDictation.value = operation;
  dictationPhase.value = 'starting';
  elapsedMs.value = 0;
  statusTone.value = 'quiet';
  statusText.value = 'Your draft stays in the composer until you tap Insert or Send.';
  discardArmed.value = false;

  try {
    const session = await platformInput.startDictation(
      (event) => handleDictationEvent(operation, event),
      {
        ...(appSettings.voiceLanguage === VOICE_LANGUAGE_AUTO ? {} : { languageTag: appSettings.voiceLanguage }),
        silenceWindowMs: appSettings.voiceSilenceSeconds * 1_000,
      },
      (requestId) => { operation.requestId = requestId; },
    );
    operation.session = session;
    if (operation.cancelled || activeDictation.value !== operation) {
      await session.cancel();
      return;
    }
    if (!operation.sawStarted) {
      operation.sawStarted = true;
      dictationPhase.value = 'recording';
      startRecordingTimer();
    }
  } catch (error) {
    if (operation.cancelled || activeDictation.value !== operation) return;
    stopRecordingTimer();
    drafts.setDraft(operation.targetKey, operation.baseDraft);
    dictationPreview.value = '';
    activeDictation.value = null;
    dictationPhase.value = 'idle';
    statusTone.value = 'warning';
    statusText.value = `Dictation could not start: ${errorMessage(error)}`;
  }
}

async function stopDictation(operation: ActiveDictation, updateStatus = true): Promise<boolean> {
  if (operation.cancelled || activeDictation.value !== operation || !operation.session) return false;
  if (operation.stopRequested) return true;
  operation.stopRequested = true;
  dictationPhase.value = 'transcribing';
  stopRecordingTimer();
  if (updateStatus) {
    statusTone.value = 'quiet';
    statusText.value = 'Your text is being prepared for review. It will not be sent automatically.';
  }
  try {
    await operation.session.stop();
    return true;
  } catch (error) {
    if (operation.cancelled || activeDictation.value !== operation) return false;
    statusTone.value = 'error';
    statusText.value = `Dictation could not stop: ${errorMessage(error)}. Cancel to restore the original draft.`;
    return false;
  }
}

function cancelDictation(operation: ActiveDictation, showStatus = true) {
  if (operation.cancelled) return;
  operation.cancelled = true;
  operation.resolveFinished('cancelled');
  if (activeDictation.value === operation) {
    activeDictation.value = null;
    dictationPhase.value = 'idle';
    stopRecordingTimer();
    dictationPreview.value = '';
    drafts.setDraft(operation.targetKey, operation.baseDraft);
    statusTone.value = showStatus ? 'quiet' : statusTone.value;
    if (showStatus) statusText.value = 'Dictation cancelled. Your original draft was restored.';
  }
  if (operation.requestId) {
    void platformInput.cancelDictation(operation.requestId).catch((error: unknown) => {
      if (!showStatus || operation.targetKey !== props.targetKey) return;
      statusTone.value = 'warning';
      statusText.value = `Dictation cancellation could not reach Android: ${errorMessage(error)}. The original draft was restored.`;
    });
  } else if (operation.session) {
    void operation.session.cancel().catch(() => {});
  }
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
  const payload = drafts.draftFor(targetKey);
  if (!targetKey || payload.length === 0 || !canDeliver.value) return;
  const activeDelivery = delivery.value;
  const operation = activeDictation.value;
  if (operation && !operation.session) return;
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
    const stopAccepted = await stopDictation(operation, false);
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
    if (finishResult !== 'stopped' || delivery.value !== activeDelivery || props.targetKey !== targetKey) {
      if (sendingIntent.value === intent) sendingIntent.value = null;
      return;
    }
  }
  const result = await activeDelivery.deliver({
    operationId: nextOperationId(),
    payload,
    intent,
  });
  if (result.draftEffect === 'clear') {
    drafts.clearDraft(targetKey);
    dictationPhase.value = 'idle';
  }
  if (delivery.value !== activeDelivery) return;
  showResult(result, intent);
  sendingIntent.value = null;
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
  discardArmed.value = false;
  statusTone.value = 'quiet';
  statusText.value = 'Draft cleared.';
}

function requestClose() {
  if (!props.mobileSheet || !props.open || sendingIntent.value !== null) return;
  const operation = activeDictation.value;
  if (operation) cancelDictation(operation);
  draftInput.value?.blur();
  emit('openChange', false);
}

function startPromptDictation() {
  // Start native recognition directly from the microphone tap, then dismiss
  // Android's IME so the recording state and its Stop/Cancel actions have room.
  void toggleDictation();
  if (props.mobileSheet) draftInput.value?.blur();
}
</script>

<template>
  <Teleport to="#prompt-composer-portal" :disabled="!mobileSheet">
    <div v-if="mobileSheet && open" class="composer-sheet-scrim" data-testid="prompt-composer-scrim"
      @click.self="requestClose" />
    <section v-if="!mobileSheet || open" class="composer-panel"
      :class="{ 'composer-panel--sheet': mobileSheet, 'composer-panel--dictating': dictationBusy }"
      aria-labelledby="composer-title" data-testid="prompt-composer"
      :role="mobileSheet ? 'dialog' : undefined" :aria-modal="mobileSheet ? 'true' : undefined"
      :data-target-key="targetKey" :data-transport-state="transportState"
      :data-acknowledged-writes="acknowledgedWrites" :data-dictation-state="dictationPhase">
      <div v-if="mobileSheet" class="composer-sheet-handle" aria-hidden="true"><span /></div>
      <div class="composer-heading">
        <div class="composer-heading__copy">
          <h3 id="composer-title">Prompt Composer</h3>
        </div>
        <span class="state-tag" :class="transportState === 'connected' ? 'state-tag--success' : 'state-tag--muted'">
          {{ transportState === 'connected' ? 'READY' : transportState === 'lost' ? 'RECONNECTING' : 'NO PTY' }}
        </span>
        <button v-if="mobileSheet && !dictationBusy" ref="sheetCloseButton" class="composer-sheet-close" type="button"
          data-testid="composer-close" aria-label="Close prompt composer" @click="requestClose">
          <AppIcon name="close" aria-hidden="true" />
        </button>
      </div>

      <div class="composer-draft-row" :class="{ 'composer-draft-row--dictating': dictationBusy }">
        <label class="sr-only" for="prompt-draft">Prompt draft</label>
        <textarea
          id="prompt-draft"
          class="composer-draft"
          :class="{ 'composer-draft--dictation-anchor': dictationBusy }"
          data-testid="prompt-draft"
          :aria-label="dictationBusy ? 'Dictation draft, read only while dictating' : 'Prompt draft'"
          :aria-describedby="dictationBusy ? (dictationPhase === 'recording' ? 'composer-recording-preview composer-status' : 'composer-status') : undefined"
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
        />
        <button v-if="mobileSheet && (dictationPhase === 'idle' || dictationPhase === 'review')"
          class="composer-dictate composer-dictate--sheet" type="button" data-testid="composer-dictate"
          :disabled="sendingIntent !== null || !targetKey" :aria-pressed="dictationPhase === 'review'"
          aria-label="Start prompt dictation" @click="startPromptDictation">
          <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2"
            stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <rect x="9" y="2" width="6" height="12" rx="3" />
            <path d="M5 10v2a7 7 0 0 0 14 0v-2M12 19v3M8 22h8" />
          </svg>
          <span>Dictate</span>
        </button>
      </div>

      <ComposerRecordingMode
        v-if="dictationPhase === 'starting' || dictationPhase === 'recording' || dictationPhase === 'transcribing'"
        :state="dictationPhase"
        :elapsed-label="elapsedLabel"
        :live-preview="dictationPreview"
        :can-deliver="canDeliver"
        :sending="sendingIntent !== null"
        @pointerdown.capture="preserveDraftFocus"
        @cancel="activeDictation && cancelDictation(activeDictation)"
        @stop="activeDictation && stopDictation(activeDictation)"
        @insert="deliver('insert')"
        @send="deliver('submit')"
      />
      <p v-else-if="dictationPhase === 'review'" class="composer-review" data-testid="composer-dictation-review">
        Review and edit your dictated text. Your draft stays in the composer until you tap Insert or Send.
      </p>

      <p id="composer-status" class="composer-status" role="status" aria-live="polite" data-testid="composer-status"
        :data-delivery-state="statusTone" :data-delivery-intent="sendingIntent ?? ''">
        {{ statusText || (transportState === 'connected' ? 'Insert leaves the line at the terminal prompt. Send presses Enter.' : 'Reconnect or attach a live session to send input.') }}
      </p>

      <div class="composer-actions" data-testid="composer-actions" @pointerdown.capture="preserveDraftFocus">
        <template v-if="dictationPhase === 'idle' || dictationPhase === 'review'">
          <button class="composer-discard" type="button" data-testid="composer-discard" :disabled="draft.length === 0 || sendingIntent !== null"
            @click="discardDraft">{{ discardArmed ? 'Discard?' : 'Discard' }}</button>
          <span class="composer-action-spacer"></span>
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
        </template>
      </div>
    </section>
  </Teleport>
</template>

<style scoped>
.composer-sheet-scrim {
  position: fixed;
  z-index: 90;
  inset: 0;
  background: rgb(7 9 13 / 0.62);
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
  box-shadow: 0 -10px 36px rgb(0 0 0 / 0.3);
}

.composer-sheet-handle { display: flex; height: 12px; align-items: flex-start; justify-content: center; }
.composer-sheet-handle span { width: 36px; height: 4px; border-radius: 999px; background: var(--border-strong); }
.composer-panel--sheet .composer-heading { min-height: 48px; gap: 8px; }
.composer-panel--sheet .composer-heading__copy { min-width: 0; flex: 1 1 auto; }
.composer-panel--sheet .composer-heading h3 { overflow: hidden; font-size: var(--fs-300); text-overflow: ellipsis; white-space: nowrap; }
.composer-panel--sheet .composer-sheet-close {
  display: inline-flex;
  width: 44px;
  height: 44px;
  flex: 0 0 44px;
  align-items: center;
  justify-content: center;
  border: 1px solid transparent;
  border-radius: 50%;
  background: transparent;
  color: var(--fg-secondary);
}
.composer-panel--sheet .composer-sheet-close svg { width: 18px; height: 18px; }
.composer-panel--sheet .composer-draft-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 72px;
  align-items: stretch;
  gap: 10px;
}
.composer-panel--sheet .composer-draft {
  min-height: 90px;
  max-height: min(28dvh, 220px);
  resize: vertical;
}
.composer-draft-row--dictating { display: block; height: 1px; overflow: visible; }
.composer-dictate--sheet {
  display: grid;
  min-width: 72px;
  min-height: 72px;
  align-content: center;
  justify-items: center;
  gap: 4px;
  border: 1px solid var(--border-strong);
  border-radius: var(--r-md);
  background: var(--surface-2);
  color: var(--fg);
  font-size: var(--fs-100);
  font-weight: 600;
}
.composer-dictate--sheet:hover:not(:disabled) { border-color: var(--accent); color: var(--accent); }
.composer-dictate--sheet:disabled { opacity: var(--disabled-opacity); }
.composer-panel--sheet .composer-status { min-height: 16px; }
.composer-panel--sheet .composer-actions { min-height: 48px; gap: 6px; }
.composer-panel--sheet .composer-discard,
.composer-panel--sheet .composer-insert,
.composer-panel--sheet .composer-shared-controls .send { min-height: 48px; }
.composer-panel--sheet .composer-action-spacer { flex: 1 1 4px; }
.composer-panel--sheet :is(button, textarea):focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }

.composer-review {
  margin: 0;
  color: var(--fg-secondary);
  font-size: var(--fs-100);
  line-height: 1.4;
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
