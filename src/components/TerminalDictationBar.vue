<script setup lang="ts">
import { App as CapacitorApp } from '@capacitor/app';
import type { PluginListenerHandle } from '@capacitor/core';
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { platformInput } from '../session/platformInput';
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

const state = ref<InlineDictationState>({
  phase: 'idle',
  preview: '',
  message: 'Tap the microphone to dictate at the terminal cursor.',
  tone: 'quiet',
});
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
  stopWatching();
  void controller.cancel();
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

const buttonText = () => {
  if (state.value.phase === 'starting') return 'Cancel terminal dictation';
  if (state.value.phase === 'listening') return 'Stop terminal dictation';
  if (state.value.phase === 'stopping' || state.value.phase === 'cancelling') return 'Transcribing terminal speech';
  if (state.value.phase === 'inserting') return 'Inserting terminal speech';
  return 'Dictate to terminal';
};

const buttonDisabled = () => !props.enabled
  || !props.targetKey
  || ['stopping', 'cancelling', 'inserting'].includes(state.value.phase);

const buttonLabel = () => {
  if (state.value.phase === 'listening') return 'Stop terminal dictation';
  if (state.value.phase === 'starting') return 'Cancel terminal dictation request';
  if (state.value.phase === 'stopping' || state.value.phase === 'cancelling') return 'Transcribing terminal speech';
  if (state.value.phase === 'inserting') return 'Inserting terminal speech';
  if (buttonDisabled()) return 'Terminal dictation unavailable';
  return 'Dictate to terminal';
};
</script>

<template>
  <button
    class="terminal-dictation-button"
    :class="{
      'terminal-dictation-button--listening': state.phase === 'listening',
      'terminal-dictation-button--transcribing': ['stopping', 'inserting'].includes(state.phase),
    }"
    type="button"
    data-testid="inline-dictation-toggle"
    :data-mic-state="state.phase === 'listening' ? 'listening' : ['stopping', 'inserting'].includes(state.phase) ? 'transcribing' : buttonDisabled() ? 'disabled' : 'idle'"
    :aria-label="buttonLabel()"
    :title="buttonLabel()"
    :aria-pressed="state.phase === 'listening'"
    :disabled="buttonDisabled()"
    @click="toggleDictation"
  >
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <path d="M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3z" />
      <path d="M19 10v2a7 7 0 0 1-14 0v-2" />
      <path d="M12 19v3M8 22h8" />
    </svg>
    <span class="sr-only">{{ buttonText() }}</span>
  </button>
</template>
