<script setup lang="ts">
import { App as CapacitorApp } from '@capacitor/app';
import type { PluginListenerHandle } from '@capacitor/core';
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { platformInput } from '../session/platformInput';
import DictationMicIcon from './DictationMicIcon.vue';
import {
  createInlineDictationController,
  type InlineDictationState,
} from '../session/inlineDictation';

const props = defineProps<{
  enabled: boolean;
  targetKey: string;
  languageTag: string;
  silenceWindowMs: number;
  insertText: (targetKey: string, text: string) => Promise<boolean>;
}>();
const emit = defineEmits<{
  stateChange: [state: InlineDictationState];
}>();

const initialState: InlineDictationState = {
  phase: 'idle',
  preview: '',
  message: 'Tap the microphone to dictate at the terminal cursor.',
  tone: 'quiet',
};
const state = ref<InlineDictationState>({ ...initialState });
const controller = createInlineDictationController({
  startDictation: (onEvent, settings) => platformInput.startDictation(onEvent, settings),
  insertText: (targetKey, text) => props.insertText(targetKey, text),
});
const stopWatching = controller.subscribe((next) => {
  state.value = next;
  emit('stateChange', next);
});
let appStateListener: PluginListenerHandle | null = null;
let disposed = false;
let previousTargetKey = props.targetKey;

watch(() => props.enabled, (enabled) => {
  if (!enabled) void controller.cancel();
}, { flush: 'sync' });

watch(() => props.targetKey, (targetKey) => {
  if (targetKey !== previousTargetKey) void controller.cancel();
  previousTargetKey = targetKey;
}, { flush: 'sync' });

onMounted(() => {
  void CapacitorApp.addListener('appStateChange', ({ isActive }) => {
    if (!isActive) void controller.cancel();
  }).then((listener) => {
    if (disposed) void listener.remove();
    else appStateListener = listener;
  }).catch(() => {
    // Browser previews do not provide Android app lifecycle events.
  });
});

onBeforeUnmount(() => {
  disposed = true;
  void appStateListener?.remove();
  // Cancellation clears buffered finals and rejects late native events. Start
  // it before detaching the controller listener, then reset App's external
  // state explicitly because the sheet containing this control is going away.
  void controller.cancel();
  emit('stateChange', { ...initialState });
  stopWatching();
});

function toggleDictation() {
  if (!props.enabled || !props.targetKey) return;
  if (state.value.phase === 'idle') {
    void controller.start({
      targetKey: props.targetKey,
      languageTag: props.languageTag,
      silenceWindowMs: props.silenceWindowMs,
    });
  } else if (state.value.phase === 'starting') {
    void controller.cancel();
  } else if (state.value.phase === 'listening') {
    void controller.stop();
  }
}

const buttonDisabled = () => !props.enabled
  || !props.targetKey
  || ['stopping', 'cancelling', 'inserting'].includes(state.value.phase);

const buttonLabel = () => {
  if (state.value.phase === 'listening') return 'Stop terminal dictation';
  if (state.value.phase === 'starting') return 'Cancel terminal dictation request';
  if (state.value.phase === 'cancelling') return 'Cancelling terminal dictation';
  if (state.value.phase === 'stopping') return 'Transcribing terminal speech';
  if (state.value.phase === 'inserting') return 'Inserting terminal speech';
  if (buttonDisabled()) return 'Terminal dictation unavailable';
  return 'Dictate to terminal';
};

const buttonAction = () => {
  if (state.value.phase === 'starting') return 'Cancel';
  if (state.value.phase === 'listening') return 'Stop';
  if (['stopping', 'cancelling', 'inserting'].includes(state.value.phase)) return 'Wait';
  return 'Dictate';
};

const buttonState = () => {
  if (state.value.phase === 'listening') return 'listening';
  if (state.value.phase === 'starting') return 'starting';
  if (['stopping', 'cancelling', 'inserting'].includes(state.value.phase)) return 'transcribing';
  if (state.value.tone === 'error') return 'error';
  return buttonDisabled() ? 'disabled' : 'idle';
};
</script>

<template>
  <button
    class="terminal-dictation-button"
    type="button"
    data-testid="inline-dictation-toggle"
    :data-mic-state="buttonState()"
    :aria-label="buttonLabel()"
    :title="buttonLabel()"
    :aria-pressed="state.phase === 'listening'"
    :aria-busy="['stopping', 'cancelling', 'inserting'].includes(state.phase)"
    :disabled="buttonDisabled()"
    @click="toggleDictation"
  >
    <DictationMicIcon :size="20" :stopped="state.phase === 'listening'" />
    <span class="terminal-dictation-action" data-testid="inline-dictation-action-label" aria-hidden="true">
      {{ buttonAction() }}
    </span>
  </button>
</template>
