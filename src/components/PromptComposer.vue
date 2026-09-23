<script setup lang="ts">
import { computed, ref, shallowRef, watch } from 'vue';
import { ComposerControls } from '@pocketshell/ui';
import type { ComposerDeliveryIntent, ComposerDeliveryResult } from '@pocketshell/core';
import { createComposerDeliveryController, type PtyWriteEffect } from '../session/composerDelivery';
import { useComposerDrafts } from '../stores/composerDrafts';

const props = defineProps<{
  targetKey: string;
  targetLabel: string;
  transportState: 'connected' | 'lost' | 'closed';
  writePty: PtyWriteEffect;
}>();

const drafts = useComposerDrafts();
const sendingIntent = ref<ComposerDeliveryIntent | null>(null);
const acknowledgedWrites = ref(0);
const statusText = ref('');
const statusTone = ref<'quiet' | 'success' | 'warning' | 'error'>('quiet');
const discardArmed = ref(false);
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
  && sendingIntent.value === null);

watch(() => props.transportState, (state) => delivery.value.setTransportState(state), { immediate: true });
watch(() => props.writePty, () => {
  delivery.value.setTransportState('closed');
  deliveryEpoch += 1;
  delivery.value = createObservedDelivery();
  delivery.value.setTransportState(props.transportState);
});
watch(() => props.targetKey, () => {
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
  if (!targetKey || payload.length === 0 || sendingIntent.value !== null) return;
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
      :disabled="targetKey.length === 0 || sendingIntent !== null"
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
      <button class="composer-discard" type="button" data-testid="composer-discard" :disabled="draft.length === 0 || sendingIntent !== null"
        @click="discardDraft">{{ discardArmed ? 'Discard?' : 'Discard' }}</button>
      <span class="composer-action-spacer"></span>
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
