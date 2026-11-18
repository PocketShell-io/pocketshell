<script setup lang="ts">
// The dictation panel inside the open shared composer (#3128), contributed to
// core's `composer.inputSources`. While a run is live the words stream into the
// draft itself and this panel says it is listening, with Stop (the ONLY thing
// that ends listening: the recognizer's own endpointing is a pause) and
// Discard / Insert / Send. A finished run offers the same three to review.
// Idle, it is a mic that starts a run from inside the composer.
//
// It also owns the two lifecycle ends a mounted composer sees: switching the
// composer to another session, and the composer closing (or the workspace
// going away). Both stop the recognizer and keep the words in the draft they
// were spoken into; nothing is written to any terminal. Background is handled
// app-wide in voiceComposerRun.ts.
//
// While a run is live or in review, this panel's row is THE action row: it
// claims the composer's actions (core hides its own Discard / Send), so there
// is exactly one set. Phone layout only (D44): on a medium or wide window it
// renders nothing and claims nothing.
import { computed, onBeforeUnmount, watch } from 'vue';
import type { ComposerExtensionContext } from '@ui/app/extensions';
import { useLayoutClass } from '@ui/app/layoutClass';
import DictationMicIcon from '@/components/DictationMicIcon.vue';
import { noSpeechWarningCopy } from '@/session/dictationNoSpeech';
import { voiceComposer } from './voiceComposerRun';

const props = defineProps<{ context: ComposerExtensionContext }>();
const run = voiceComposer.state;
const layout = useLayoutClass();
const compact = computed(() => layout.value === 'compact');

/** The run belongs to the session this composer is showing. */
const mine = computed(() => run.value.sessionKey !== null && run.value.sessionKey === props.context.sessionKey);
const active = computed(
  () => mine.value && (run.value.phase === 'starting' || run.value.phase === 'listening' || run.value.phase === 'stopping'),
);
const reviewing = computed(() => mine.value && !active.value && run.value.reviewing);
const panelPhase = computed(() => (active.value ? run.value.phase : reviewing.value ? 'review' : 'idle'));
const status = computed(() => {
  switch (run.value.phase) {
    case 'starting':
      return 'Starting the microphone…';
    case 'stopping':
      return 'Finishing…';
    default:
      return 'Listening… tap Stop when you are done';
  }
});
const warning = computed(() => (active.value && run.value.noSpeech ? noSpeechWarningCopy(run.value.noSpeech) : null));
const error = computed(() => (mine.value && !active.value ? run.value.error : null));

watch(
  () => props.context.sessionKey,
  (next, previous) => {
    if (next === previous) return;
    if (run.value.sessionKey === previous) voiceComposer.interrupt('target-change');
  },
);

// Leaving the phone layout mid-run (a rotation, a resized window) ends the run
// the same way closing the composer does: words kept, nothing written.
watch(compact, (isCompact) => {
  if (!isCompact && active.value) voiceComposer.interrupt('closed');
});

const owning = computed(() => compact.value && (active.value || reviewing.value));
watch(
  [owning, () => props.context],
  ([own], previous) => {
    const before = previous?.[1];
    if (before && before !== props.context) before.claimActions(false);
    props.context.claimActions(own);
  },
  { immediate: true },
);

onBeforeUnmount(() => {
  props.context.claimActions(false);
  if (active.value) voiceComposer.interrupt('closed');
});

function dictate(): void {
  voiceComposer.start(props.context);
}

function deliver(enter: boolean): void {
  void voiceComposer.deliver(props.context, enter);
}
</script>

<template>
  <div
    v-if="compact"
    class="voice-dictation"
    data-testid="composer-dictation"
    :data-phase="panelPhase"
    :data-session-key="context.sessionKey"
  >
    <template v-if="active || reviewing">
      <p class="voice-status" role="status" data-testid="composer-dictation-status">
        <span v-if="active" class="voice-dot" aria-hidden="true"></span>
        {{ active ? status : 'Review the dictated text, then Insert or Send.' }}
      </p>
      <p v-if="warning" class="voice-warning" data-testid="composer-dictation-warning">
        {{ warning.title }}. {{ warning.detail }}
      </p>
      <div class="voice-actions">
        <button
          v-if="active"
          class="btn-ghost voice-stop"
          type="button"
          data-testid="composer-dictation-stop"
          :disabled="run.phase === 'stopping'"
          @click="voiceComposer.stop()"
        >
          <DictationMicIcon :size="18" stopped />
          Stop
        </button>
        <span class="voice-spacer"></span>
        <button class="btn-ghost" type="button" data-testid="composer-dictation-discard" @click="voiceComposer.discard()">
          Discard
        </button>
        <button class="btn-ghost" type="button" data-testid="composer-dictation-insert" @click="deliver(false)">
          Insert
        </button>
        <button class="btn-ghost voice-send" type="button" data-testid="composer-dictation-send" @click="deliver(true)">
          Send
        </button>
      </div>
    </template>
    <div v-else class="voice-actions">
      <button class="btn-ghost" type="button" data-testid="composer-dictation-start" @click="dictate">
        <DictationMicIcon :size="18" />
        Dictate
      </button>
      <span v-if="error" class="error" role="alert" data-testid="composer-dictation-error">{{ error }}</span>
    </div>
  </div>
</template>

<style scoped>
.voice-dictation {
  display: flex;
  flex-direction: column;
  gap: var(--sp-1);
}
.voice-status,
.voice-warning {
  margin: 0;
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  font-size: var(--fs-300);
  color: var(--fg-secondary);
}
.voice-warning {
  color: var(--warning);
}
.voice-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--error);
  flex: none;
}
.voice-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--sp-1);
}
.voice-spacer {
  flex: 1;
}
.voice-send {
  color: var(--accent);
}
</style>
