<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue';
import { App as CapacitorApp } from '@capacitor/app';
import type { PluginListenerHandle } from '@capacitor/core';
import { ComposerControls } from '@pocketshell/ui';
import type { AttachmentSource, ComposerDeliveryIntent, ComposerDeliveryResult } from '@pocketshell/core';
import { createComposerDeliveryController, type PtyWriteEffect } from '../session/composerDelivery';
import {
  appendAttachmentPaths,
  type ComposerAttachmentStageResult,
  type PendingComposerAttachment,
} from '../session/composerAttachments';
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
  stageAttachments: (
    targetKey: string,
    pending: readonly PendingComposerAttachment[],
  ) => Promise<ComposerAttachmentStageResult>;
}>();

const drafts = useComposerDrafts();
const sendingIntent = ref<ComposerDeliveryIntent | null>(null);
const deliveryRequested = ref(false);
const pickingAttachments = ref(false);
const stagingAttachments = ref(false);
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
const pendingAttachments = computed(() => drafts.pendingAttachmentsFor(props.targetKey));
const stagedAttachments = computed(() => drafts.stagedAttachmentsFor(props.targetKey));
const attachmentIssues = computed(() => drafts.attachmentIssuesFor(props.targetKey));
const attachmentCount = computed(() => pendingAttachments.value.length + stagedAttachments.value.length);
const attachmentBusy = computed(() => pickingAttachments.value || stagingAttachments.value || deliveryRequested.value);
const canDeliver = computed(() => props.targetKey.length > 0
  && props.transportState === 'connected'
  && (draft.value.length > 0 || attachmentCount.value > 0)
  && !dictationStarting.value
  && !dictationActive.value
  && !attachmentBusy.value
  && sendingIntent.value === null);

watch(
  [() => props.targetKey, () => props.transportState, () => pendingAttachments.value.length, () => deliveryRequested.value],
  ([targetKey, state, count, requested]) => {
    if (targetKey && state === 'connected' && count > 0 && !requested && !attachmentBusy.value) {
      void stagePendingAttachments(targetKey);
    }
  },
  { immediate: true },
);

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
  deliveryRequested.value = false;
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

function attachmentIssueId(prefix: string): string {
  return `${prefix}-${nextOperationId()}`;
}

async function pickAttachments() {
  const targetKey = props.targetKey;
  if (!targetKey || pickingAttachments.value) return;
  pickingAttachments.value = true;
  statusText.value = 'Choose files to attach…';
  statusTone.value = 'quiet';
  try {
    const result = await platformInput.pickAttachments();
    const added = drafts.addPendingAttachments(targetKey, result.sources as AttachmentSource[]);
    for (const [index, failure] of result.failures.entries()) {
      drafts.addAttachmentIssue(targetKey, {
        id: attachmentIssueId(`picker-${index}`),
        name: failure.name,
        message: failure.message,
      });
    }
    if (targetKey === props.targetKey && result.failures.length > 0) {
      statusTone.value = 'warning';
      statusText.value = `Some selected files could not be read. ${added.length} file(s) remain available.`;
    } else if (targetKey === props.targetKey && added.length > 0) {
      statusText.value = 'Files added to this draft.';
    }
  } catch (error) {
    if (targetKey === props.targetKey) {
      statusTone.value = 'error';
      statusText.value = `Files could not be selected: ${errorMessage(error)}`;
    }
  } finally {
    pickingAttachments.value = false;
  }
}

async function stagePendingAttachments(
  targetKey: string,
  onlyIds?: ReadonlySet<string>,
  duringDelivery = false,
): Promise<void> {
  if (!targetKey || stagingAttachments.value || props.transportState !== 'connected') return;
  if (deliveryRequested.value && !duringDelivery) return;
  const pending = drafts.pendingAttachmentsFor(targetKey)
    .filter((attachment) => onlyIds === undefined || onlyIds.has(attachment.id));
  if (pending.length === 0) return;
  const attemptedIds = new Set(pending.map((attachment) => attachment.id));
  stagingAttachments.value = true;
  statusTone.value = 'quiet';
  statusText.value = `Uploading ${pending.length} attachment(s)…`;
  try {
    const result = await props.stageAttachments(targetKey, pending);
    drafts.markAttachmentsStaged(targetKey, result.staged);
    for (const failure of result.failures) {
      drafts.addAttachmentIssue(targetKey, failure);
    }
    if (targetKey === props.targetKey && result.failures.length > 0) {
      statusTone.value = 'warning';
      statusText.value = 'Some files could not be uploaded. Their bytes and the draft are retained; retry or remove them.';
    } else if (targetKey === props.targetKey && result.staged.length > 0) {
      statusTone.value = 'success';
      statusText.value = `${result.staged.length} attachment(s) ready in the remote workspace.`;
    }
  } catch (error) {
    for (const attachment of pending) {
      drafts.addAttachmentIssue(targetKey, {
        id: attachment.id,
        name: attachment.source.name ?? 'Shared file',
        message: errorMessage(error),
      });
    }
    if (targetKey === props.targetKey) {
      statusTone.value = 'error';
      statusText.value = `Attachments could not be staged: ${errorMessage(error)}. The draft and file bytes are retained.`;
    }
  } finally {
    stagingAttachments.value = false;
    const targetNow = props.targetKey;
    const newlyAdded = drafts.pendingAttachmentsFor(targetKey)
      .some((attachment) => !attemptedIds.has(attachment.id));
    if (!deliveryRequested.value && targetNow && props.transportState === 'connected'
        && (targetNow !== targetKey || newlyAdded)) {
      queueMicrotask(() => void stagePendingAttachments(targetNow));
    }
  }
}

function removeAttachment(id: string) {
  drafts.removeAttachment(props.targetKey, id);
  statusTone.value = 'quiet';
  statusText.value = 'Attachment removed from this draft.';
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
  if (!targetKey || (!drafts.draftFor(targetKey) && attachmentCount.value === 0)
      || dictationStarting.value || dictationActive.value || deliveryRequested.value
      || sendingIntent.value !== null || pickingAttachments.value || stagingAttachments.value) return;
  const operationEpoch = deliveryEpoch;
  deliveryRequested.value = true;
  statusTone.value = 'quiet';
  statusText.value = 'Preparing the draft for delivery…';
  try {
    const pending = drafts.pendingAttachmentsFor(targetKey);
    if (pending.length > 0) {
      await stagePendingAttachments(targetKey, undefined, true);
      if (operationEpoch !== deliveryEpoch) return;
      if (drafts.pendingAttachmentsFor(targetKey).length > 0) {
        statusTone.value = 'warning';
        statusText.value = 'Delivery stopped because some attachments are not staged. Their bytes and the draft are retained.';
        return;
      }
    }

    const payload = appendAttachmentPaths(
      drafts.draftFor(targetKey),
      drafts.stagedAttachmentsFor(targetKey).map((attachment) => attachment.path),
    );
    if (payload.length === 0) return;
    const revision = drafts.revisionFor(targetKey);
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
    if (delivery.value !== activeDelivery) return;
    const cleared = result.draftEffect === 'clear' && drafts.clearDraftIfRevision(targetKey, revision);
    showResult(result, intent);
    if (result.draftEffect === 'clear' && !cleared) {
      statusTone.value = 'warning';
      statusText.value = 'Delivered. New content arrived during delivery and remains in this draft.';
    }
  } catch (error) {
    if (operationEpoch === deliveryEpoch) {
      statusTone.value = 'error';
      statusText.value = `Draft could not be prepared: ${errorMessage(error)}. The draft and attachments are retained.`;
    }
  } finally {
    if (operationEpoch === deliveryEpoch) {
      sendingIntent.value = null;
      deliveryRequested.value = false;
    }
  }
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
  statusText.value = 'Draft and attachments cleared.';
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

    <div v-if="attachmentCount > 0" class="composer-attachments" data-testid="composer-attachments">
      <ul class="composer-attachment-list" aria-label="Draft attachments">
        <li v-for="attachment in pendingAttachments" :key="attachment.id" class="composer-attachment"
          data-attachment-state="pending" :data-attachment-id="attachment.id">
          <span class="composer-attachment-name">{{ attachment.source.name || 'Shared file' }}</span>
          <span class="composer-attachment-state">Ready to upload</span>
          <button type="button" class="composer-attachment-remove" :disabled="attachmentBusy || sendingIntent !== null"
            :aria-label="`Remove ${attachment.source.name || 'shared file'}`" @click="removeAttachment(attachment.id)">Remove</button>
        </li>
        <li v-for="attachment in stagedAttachments" :key="attachment.id" class="composer-attachment"
          data-attachment-state="staged" :data-attachment-id="attachment.id" :data-attachment-path="attachment.path">
          <span class="composer-attachment-name">{{ attachment.name }}</span>
          <span class="composer-attachment-state">Uploaded · {{ attachment.path.split('/').at(-1) }}</span>
          <button type="button" class="composer-attachment-remove" :disabled="attachmentBusy || sendingIntent !== null"
            :aria-label="`Remove ${attachment.name}`" @click="removeAttachment(attachment.id)">Remove</button>
        </li>
      </ul>
    </div>
    <ul v-if="attachmentIssues.length > 0" class="composer-attachment-issues" data-testid="composer-attachment-issues" role="alert">
      <li v-for="issue in attachmentIssues" :key="issue.id"><strong>{{ issue.name }}:</strong> {{ issue.message }}</li>
    </ul>

    <p class="composer-status" role="status" aria-live="polite" data-testid="composer-status"
      :data-delivery-state="statusTone" :data-delivery-intent="sendingIntent ?? ''">
      {{ statusText || (transportState === 'connected' ? 'Insert leaves the line at the terminal prompt. Send presses Enter.' : 'Reconnect or attach a live session to send input.') }}
    </p>

    <div class="composer-actions" data-testid="composer-actions">
      <button class="composer-discard" type="button" data-testid="composer-discard" :disabled="(draft.length === 0 && attachmentCount === 0) || sendingIntent !== null || deliveryRequested || dictationStarting || dictationActive"
        @click="discardDraft">{{ discardArmed ? 'Discard?' : 'Discard' }}</button>
      <span class="composer-action-spacer"></span>
      <button class="composer-insert" type="button" data-testid="composer-dictate"
        :disabled="dictationStarting || sendingIntent !== null || deliveryRequested || !targetKey"
        :aria-pressed="dictationActive"
        @click="toggleDictation">{{ dictationStarting ? 'Starting…' : dictationActive ? 'Stop dictation' : 'Dictate' }}</button>
      <button class="composer-insert" type="button" data-testid="composer-insert" :disabled="!canDeliver"
        @click="deliver('insert')">Insert</button>
      <ComposerControls
        class="composer-shared-controls"
        :uploading-count="attachmentBusy ? Math.max(1, pendingAttachments.length) : 0"
        :can-send="canDeliver"
        :send-in-flight="sendingIntent === 'submit' || deliveryRequested"
        :draft-length="draft.length"
        :attachment-count="attachmentCount"
        :discard-armed="false"
        @attach="pickAttachments"
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

<style scoped>
.composer-attachments {
  max-height: 120px;
  overflow: auto;
  padding: 0 var(--sp-3) var(--sp-2);
}
.composer-attachment-list,
.composer-attachment-issues {
  display: grid;
  gap: var(--sp-1);
  margin: 0;
  padding: 0;
  list-style: none;
}
.composer-attachment {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  min-width: 0;
  padding: var(--sp-1) var(--sp-2);
  border: 1px solid var(--border-soft);
  border-radius: var(--r-sm);
  color: var(--fg-secondary);
  font-size: var(--fs-100);
}
.composer-attachment-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--fg);
}
.composer-attachment-state {
  flex: 0 1 auto;
  min-width: 0;
  margin-left: auto;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.composer-attachment-remove {
  flex: 0 0 auto;
  border: 0;
  background: transparent;
  color: var(--fg-secondary);
  font: inherit;
}
.composer-attachment-issues {
  padding: 0 var(--sp-3) var(--sp-2);
  color: var(--error);
  font-size: var(--fs-100);
}
</style>
