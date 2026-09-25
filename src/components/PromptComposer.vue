<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue';
import { App as CapacitorApp } from '@capacitor/app';
import type { PluginListenerHandle } from '@capacitor/core';
import { ComposerControls } from '@pocketshell/ui';
import type { ComposerDeliveryIntent, ComposerDeliveryResult } from '@pocketshell/core';
import { createComposerDeliveryController, type PtyWriteEffect } from '../session/composerDelivery';
import { createDictationStartCancellation } from '../session/dictationStartCancellation';
import { platformInput, type DictationEvent } from '../session/platformInput';
import { useComposerDrafts } from '../stores/composerDrafts';

const props = defineProps<{
  targetKey: string;
  targetLabel: string;
  transportState: 'connected' | 'lost' | 'closed';
  dictationLanguageTag: string;
  dictationSilenceWindowMs: number;
  writePty: PtyWriteEffect;
}>();

const drafts = useComposerDrafts();
const sendingIntent = ref<ComposerDeliveryIntent | null>(null);
const acknowledgedWrites = ref(0);
const statusText = ref('');
const statusTone = ref<'quiet' | 'success' | 'warning' | 'error'>('quiet');
const discardArmed = ref(false);
const dictationStarting = ref(false);
const dictationActive = ref(false);
let dictationSession: { requestId: string; stop: () => Promise<void> } | null = null;
let dictationTargetKey = '';
const dictationStartCancellation = createDictationStartCancellation();
let dictationBaseDraft = '';
let completedTranscript = '';
let partialTranscript = '';
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
const canDeliver = computed(() => props.targetKey.length > 0
  && props.transportState === 'connected'
  && draft.value.length > 0
  && !dictationStarting.value
  && !dictationActive.value
  && sendingIntent.value === null);

watch(() => props.transportState, (state) => delivery.value.setTransportState(state), { immediate: true });
watch(() => props.writePty, () => {
  delivery.value.setTransportState('closed');
  deliveryEpoch += 1;
  delivery.value = createObservedDelivery();
  delivery.value.setTransportState(props.transportState);
});
watch(() => props.targetKey, () => {
  if (dictationTargetKey && dictationTargetKey !== props.targetKey) {
    requestDictationStop();
  }
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
}, { flush: 'sync' });

onBeforeUnmount(() => {
  composerUnmounting = true;
  void appStateListener?.remove();
  requestDictationStop();
});

onMounted(() => {
  void CapacitorApp.addListener('appStateChange', ({ isActive }) => {
    if (!isActive) requestDictationStop();
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
  drafts.setDraft(props.targetKey, target.value);
  statusText.value = '';
  statusTone.value = 'quiet';
  discardArmed.value = false;
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

function renderDictationDraft(targetKey: string) {
  const transcript = appendTranscript(completedTranscript, partialTranscript);
  const separator = dictationBaseDraft && transcript && !/\s$/u.test(dictationBaseDraft) ? ' ' : '';
  drafts.setDraft(targetKey, `${dictationBaseDraft}${separator}${transcript}`);
}

function handleDictationEvent(targetKey: string, event: DictationEvent) {
  const isCurrentTarget = targetKey === props.targetKey;
  if (event.type === 'partial') {
    partialTranscript = event.text ?? '';
    renderDictationDraft(targetKey);
  } else if (event.type === 'result') {
    completedTranscript = appendTranscript(completedTranscript, event.text ?? partialTranscript);
    partialTranscript = '';
    renderDictationDraft(targetKey);
  } else if (event.type === 'error') {
    if (isCurrentTarget) {
      statusTone.value = 'error';
      statusText.value = `Dictation stopped: ${event.code ?? 'speech recognition failed'}. The draft is ready to review.`;
    }
  } else if (event.type === 'stopped') {
    renderDictationDraft(targetKey);
    dictationSession = null;
    dictationActive.value = false;
    dictationStarting.value = false;
    dictationStartCancellation.clear();
    if (isCurrentTarget && statusTone.value !== 'error') {
      statusTone.value = 'quiet';
      statusText.value = 'Dictation stopped. Review the draft before sending.';
    }
    dictationTargetKey = '';
  }
}

async function toggleDictation() {
  if (dictationSession) {
    await stopDictation();
    return;
  }
  if (dictationStarting.value || !props.targetKey) return;

  const targetKey = props.targetKey;
  dictationTargetKey = targetKey;
  dictationBaseDraft = drafts.draftFor(targetKey);
  completedTranscript = '';
  partialTranscript = '';
  dictationStarting.value = true;
  dictationStartCancellation.begin();
  statusTone.value = 'quiet';
  statusText.value = 'Requesting microphone access…';

  try {
    const session = await platformInput.startDictation(
      (event) => handleDictationEvent(targetKey, event),
      {
        languageTag: props.dictationLanguageTag,
        silenceWindowMs: props.dictationSilenceWindowMs,
      },
    );
    dictationSession = session;
    dictationStarting.value = false;
    dictationActive.value = true;
    statusText.value = 'Listening. Tap Stop dictation when you are done.';
    if (dictationStartCancellation.takeStopRequest() || targetKey !== props.targetKey) await stopDictation();
  } catch (error) {
    dictationTargetKey = '';
    dictationStarting.value = false;
    dictationActive.value = false;
    dictationStartCancellation.clear();
    statusTone.value = 'warning';
    statusText.value = `Dictation could not start: ${errorMessage(error)}`;
  }
}

function requestDictationStop() {
  if (dictationSession) void stopDictation();
  else if (dictationStarting.value) dictationStartCancellation.requestStop();
}

async function stopDictation() {
  const session = dictationSession;
  if (!session) return;
  statusTone.value = 'quiet';
  statusText.value = 'Stopping dictation…';
  try {
    await session.stop();
  } catch (error) {
    statusTone.value = 'error';
    statusText.value = `Dictation could not stop: ${errorMessage(error)}`;
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
  if (!targetKey || payload.length === 0 || dictationStarting.value || dictationActive.value || sendingIntent.value !== null) return;
  const activeDelivery = delivery.value;
  sendingIntent.value = intent;
  acknowledgedWrites.value = 0;
  statusTone.value = 'quiet';
  statusText.value = intent === 'insert' ? 'Inserting into the terminal…' : 'Sending to the terminal…';
  const result = await activeDelivery.deliver({
    operationId: nextOperationId(),
    payload,
    intent,
  });
  if (result.draftEffect === 'clear') drafts.clearDraft(targetKey);
  if (delivery.value !== activeDelivery) return;
  showResult(result, intent);
  sendingIntent.value = null;
}

function discardDraft() {
  if (dictationStarting.value || dictationActive.value) return;
  if (!discardArmed.value) {
    discardArmed.value = true;
    statusTone.value = 'warning';
    statusText.value = 'Tap Discard again to clear this draft.';
    return;
  }
  drafts.clearDraft(props.targetKey);
  discardArmed.value = false;
  statusTone.value = 'quiet';
  statusText.value = 'Draft cleared.';
}
</script>

<template>
  <section class="composer-panel" aria-labelledby="composer-title" data-testid="prompt-composer"
    :data-target-key="targetKey" :data-transport-state="transportState"
    :data-acknowledged-writes="acknowledgedWrites">
    <div class="composer-heading">
      <div>
        <p class="eyebrow">PROMPT</p>
        <h3 id="composer-title">Compose for {{ targetLabel }}</h3>
      </div>
      <span class="state-tag" :class="transportState === 'connected' ? 'state-tag--success' : 'state-tag--muted'">
        {{ transportState === 'connected' ? 'READY' : transportState === 'lost' ? 'RECONNECTING' : 'NO PTY' }}
      </span>
    </div>

    <label class="sr-only" for="prompt-draft">Prompt draft</label>
    <textarea
      id="prompt-draft"
      class="composer-draft"
      data-testid="prompt-draft"
      aria-label="Prompt draft"
      :value="draft"
      :disabled="targetKey.length === 0 || sendingIntent !== null || dictationStarting || dictationActive"
      :placeholder="targetKey ? 'Write a prompt for this session…' : 'Attach a session to start a draft.'"
      spellcheck="false"
      autocapitalize="sentences"
      enterkeyhint="enter"
      @input="setDraft"
    />

    <p class="composer-status" role="status" aria-live="polite" data-testid="composer-status"
      :data-delivery-state="statusTone" :data-delivery-intent="sendingIntent ?? ''">
      {{ statusText || (transportState === 'connected' ? 'Insert leaves the line at the terminal prompt. Send presses Enter.' : 'Reconnect or attach a live session to send input.') }}
    </p>

    <div class="composer-actions" data-testid="composer-actions">
      <button class="composer-discard" type="button" data-testid="composer-discard" :disabled="draft.length === 0 || sendingIntent !== null || dictationStarting || dictationActive"
        @click="discardDraft">{{ discardArmed ? 'Discard?' : 'Discard' }}</button>
      <span class="composer-action-spacer"></span>
      <button class="composer-insert" type="button" data-testid="composer-dictate"
        :disabled="dictationStarting || sendingIntent !== null || !targetKey"
        :aria-pressed="dictationActive"
        :aria-label="dictationActive ? 'Stop prompt dictation' : 'Dictate into prompt draft'"
        :title="dictationActive ? 'Stop prompt dictation' : 'Dictate into prompt draft'"
        @click="toggleDictation">{{ dictationStarting ? 'Starting…' : dictationActive ? 'Stop dictation' : 'Dictate' }}</button>
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
    </div>
  </section>
</template>

<script lang="ts">
function errorMessage(error: unknown): string {
  return error instanceof Error && error.message !== '' ? error.message : String(error);
}
</script>
