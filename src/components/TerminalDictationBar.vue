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
const stopWatching = controller.subscribe((next) => { state.value = next; });
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
  if (state.value.phase === 'starting') return 'Cancel';
  if (state.value.phase === 'listening') return 'Stop';
  if (state.value.phase === 'stopping' || state.value.phase === 'cancelling') return 'Finishing';
  if (state.value.phase === 'inserting') return 'Inserting';
  return 'Dictate';
};

const buttonDisabled = () => !props.enabled
  || !props.targetKey
  || ['stopping', 'cancelling', 'inserting'].includes(state.value.phase);

const buttonLabel = () => {
  if (state.value.phase === 'listening') return 'Stop dictation';
  if (state.value.phase === 'starting') return 'Cancel dictation request';
  if (buttonDisabled()) return 'Dictation unavailable';
  return 'Dictate into the terminal';
};
</script>

<template>
  <div
    class="terminal-dictation-bar"
    data-testid="inline-dictation-bar"
    :data-phase="state.phase"
    :data-dictation-tone="state.tone"
  >
    <div class="terminal-dictation-copy">
      <p class="terminal-dictation-message" data-testid="inline-dictation-status" role="status" aria-live="polite">
        {{ state.message }}
      </p>
      <p v-if="state.preview" class="terminal-dictation-preview" data-testid="inline-dictation-preview" aria-live="off">
        {{ state.preview }}
      </p>
    </div>
    <button
      class="terminal-dictation-button"
      :class="{ 'terminal-dictation-button--active': state.phase === 'listening' || state.phase === 'stopping' }"
      type="button"
      data-testid="inline-dictation-toggle"
      :aria-label="buttonLabel()"
      :aria-pressed="state.phase === 'listening' || state.phase === 'stopping'"
      :disabled="buttonDisabled()"
      @click="toggleDictation"
    >
      <svg v-if="state.phase !== 'listening' && state.phase !== 'stopping'" viewBox="0 0 24 24" aria-hidden="true">
        <path d="M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3z" />
        <path d="M19 10v2a7 7 0 0 1-14 0v-2" />
        <path d="M12 19v3M8 22h8" />
      </svg>
      <svg v-else viewBox="0 0 24 24" aria-hidden="true">
        <rect x="6" y="6" width="12" height="12" rx="2" />
      </svg>
      <span>{{ buttonText() }}</span>
    </button>
  </div>
</template>
