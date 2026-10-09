<script setup lang="ts">
import type { DictationNoSpeech } from '@pocketshell/core';
import { computed } from 'vue';
import { noSpeechWarningCopy } from '../session/dictationNoSpeech';
import { usePrefersReducedMotion } from './prefersReducedMotion';

const props = withDefaults(defineProps<{
  state: 'starting' | 'recording' | 'transcribing';
  elapsedLabel: string;
  livePreview: string;
  stopDisabled: boolean;
  /** #3062: listening has produced no recognized words for a while. Informational only. */
  noSpeech?: DictationNoSpeech | null;
}>(), { noSpeech: null });
const emit = defineEmits<{ stop: [] }>();
const reducedMotion = usePrefersReducedMotion();
const warning = computed(() => props.noSpeech && props.state !== 'transcribing' ? noSpeechWarningCopy(props.noSpeech) : null);
</script>

<template>
  <section class="recording-mode" :class="{ 'recording-mode--recording': state === 'recording', 'recording-mode--no-speech': warning }"
    role="group" data-testid="composer-recording-mode" :data-recording-state="state"
    :data-no-speech="warning ? 'true' : 'false'"
    :aria-label="state === 'recording' ? 'Prompt dictation recording' : state === 'transcribing' ? 'Transcribing prompt' : 'Starting prompt dictation'">
    <div v-if="state === 'recording'" class="recording-mode__live-row">
      <span class="recording-mode__phase" data-testid="composer-recording-phase">{{ warning ? 'Still listening' : 'Listening' }}</span>
      <time data-testid="composer-recording-timer" aria-label="Dictation duration">
        {{ elapsedLabel }}
      </time>
      <div class="recording-mode__waveform" role="img"
        :class="{
          'recording-mode__waveform--no-speech': warning,
          'recording-mode__waveform--pulse': warning && !reducedMotion,
          'recording-mode__waveform--static': warning && reducedMotion,
        }"
        aria-label="Speech capture is active. The animated bars show recording state, not volume.">
        <span v-for="bar in 30" :key="bar" :style="{ '--bar': bar - 1 }"></span>
      </div>
      <button class="recording-mode__stop" type="button" data-testid="composer-recording-stop"
        aria-label="Stop dictation and keep the recognized text in the editable draft"
        title="Stop dictation" :disabled="stopDisabled" @click="emit('stop')">
        <svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true">
          <rect x="6" y="6" width="12" height="12" rx="1" fill="currentColor" />
        </svg>
      </button>
    </div>

    <div v-else-if="state === 'transcribing'" class="recording-mode__transcribing-row" role="status" aria-live="polite">
      <span class="recording-mode__spinner" aria-hidden="true"></span>
      <span>Transcribing prompt…</span>
      <time data-testid="composer-recording-timer" aria-label="Dictation duration">
        {{ elapsedLabel }}
      </time>
    </div>
    <div v-else class="recording-mode__progress" role="status" aria-live="polite">
      <span class="recording-mode__spinner" aria-hidden="true"></span>
      <span>Requesting microphone access…</span>
    </div>
    <p v-if="warning" class="recording-mode__no-speech"
      :class="{ 'recording-mode__no-speech--static': reducedMotion }"
      data-testid="composer-no-speech-warning" role="status" aria-live="polite"
      :data-no-speech-reason="noSpeech?.reason" :data-no-speech-audio="noSpeech?.audio"
      :data-motion="reducedMotion ? 'static' : 'pulse'">
      <span class="recording-mode__no-speech-dot" aria-hidden="true"></span>
      <span><strong>{{ warning.title }}</strong> {{ warning.detail }}</span>
    </p>

    <!-- #3062: with the warning up and nothing heard yet, the "Listening for speech…" placeholder would contradict it. -->
    <p v-if="state === 'transcribing' || (state === 'recording' && (livePreview.trim() || !warning))" id="composer-recording-preview"
      class="recording-mode__preview" data-testid="composer-recording-preview" aria-live="polite">
      {{ livePreview.trim() ? livePreview : state === 'recording' ? 'Listening for speech…' : 'Waiting for transcript…' }}
    </p>
  </section>
</template>

<style scoped>
.recording-mode {
  display: grid;
  gap: var(--sp-2);
  min-width: 0;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  background: var(--surface-2);
  padding: var(--sp-3);
}

.recording-mode--recording { padding-block: var(--sp-1); }

.recording-mode__live-row {
  display: flex;
  min-width: 0;
  min-height: 48px;
  align-items: center;
  gap: var(--sp-2);
}

.recording-mode__live-row time {
  flex: 0 0 auto;
  color: var(--accent);
  font: 600 var(--fs-300)/1 var(--font-mono);
  font-variant-numeric: tabular-nums;
}

.recording-mode__phase {
  flex: 0 0 auto;
  color: var(--fg);
  font-size: var(--fs-200);
  font-weight: var(--fw-semibold);
}

.recording-mode__stop {
  display: inline-flex;
  width: 48px;
  height: 48px;
  flex: 0 0 48px;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: 50%;
  background: var(--accent);
  padding: 0;
  color: var(--on-accent);
  cursor: pointer;
}
.recording-mode__stop:disabled { opacity: var(--disabled-opacity); cursor: default; }
.recording-mode__stop:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
.recording-mode__stop:active:not(:disabled) { filter: brightness(1.12); }

.recording-mode__waveform {
  display: flex;
  min-width: 0;
  height: 32px;
  flex: 1 1 auto;
  align-items: center;
  justify-content: space-between;
  overflow: hidden;
  padding-inline: var(--sp-1);
}
.recording-mode__waveform span {
  width: 3px;
  height: 4px;
  flex: 0 0 auto;
  border-radius: 2px;
  background: var(--accent);
  transform-origin: center;
  animation: recording-wave 1.4s linear infinite;
  animation-delay: calc(var(--bar) * -47ms);
}
.recording-mode__waveform span:nth-child(15n + 1) { --peak: 6; }
.recording-mode__waveform span:nth-child(15n + 2) { --peak: 8; }
.recording-mode__waveform span:nth-child(15n + 3) { --peak: 11; }
.recording-mode__waveform span:nth-child(15n + 4) { --peak: 14; }
.recording-mode__waveform span:nth-child(15n + 5) { --peak: 18; }
.recording-mode__waveform span:nth-child(15n + 6) { --peak: 21; }
.recording-mode__waveform span:nth-child(15n + 7) { --peak: 24; }
.recording-mode__waveform span:nth-child(15n + 8) { --peak: 22; }
.recording-mode__waveform span:nth-child(15n + 9) { --peak: 18; }
.recording-mode__waveform span:nth-child(15n + 10) { --peak: 14; }
.recording-mode__waveform span:nth-child(15n + 11) { --peak: 10; }
.recording-mode__waveform span:nth-child(15n + 12) { --peak: 7; }
.recording-mode__waveform span:nth-child(15n + 13) { --peak: 5; }
.recording-mode__waveform span:nth-child(15n + 14) { --peak: 4; }
.recording-mode__waveform span:nth-child(15n) { --peak: 3; }

.recording-mode__progress {
  display: flex;
  min-height: 32px;
  align-items: center;
  gap: var(--sp-2);
  color: var(--fg-secondary);
  font-size: var(--fs-200);
}
.recording-mode__transcribing-row {
  display: flex;
  min-width: 0;
  min-height: 32px;
  align-items: center;
  gap: var(--sp-2);
  color: var(--fg-secondary);
  font-size: var(--fs-200);
}
.recording-mode__transcribing-row > span:nth-child(2) { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.recording-mode__transcribing-row time {
  flex: 0 0 auto;
  margin-left: auto;
  color: var(--accent);
  font: 600 var(--fs-300)/1 var(--font-mono);
  font-variant-numeric: tabular-nums;
}
.recording-mode__spinner {
  width: 16px;
  height: 16px;
  flex: 0 0 auto;
  border: 2px solid var(--border-strong);
  border-top-color: var(--accent);
  border-radius: 50%;
  animation: recording-spin 900ms linear infinite;
}

.recording-mode__preview {
  display: -webkit-box;
  overflow: hidden;
  margin: 0;
  color: var(--fg);
  font: var(--fs-200)/1.45 var(--font-ui);
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
  overflow-wrap: anywhere;
}

@keyframes recording-wave {
  0%, 100% { height: 4px; }
  50% { height: calc(var(--peak, 8) * 1px); }
}
@keyframes recording-spin { to { transform: rotate(360deg); } }

/* #3062: listening without recognized words. The panel, waveform and a dot
   take the warning colour and pulse; the draft and controls are untouched. */
.recording-mode--no-speech {
  border-color: var(--warning);
  box-shadow: inset 3px 0 var(--warning);
}
.recording-mode__waveform--no-speech span { background: var(--warning); }
.recording-mode__waveform--pulse { animation: recording-no-speech-pulse 900ms ease-in-out infinite alternate; }
.recording-mode__no-speech {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: var(--sp-2);
  margin: 0;
  color: var(--fg);
  font: var(--fs-200)/1.4 var(--font-ui);
}
.recording-mode__no-speech strong { color: var(--warning); font-weight: var(--fw-semibold); }
.recording-mode__no-speech-dot {
  width: 8px;
  height: 8px;
  flex: 0 0 8px;
  border-radius: 50%;
  background: var(--warning);
  animation: recording-no-speech-pulse 900ms ease-in-out infinite alternate;
}
/* Reduced motion: a static warning highlight instead of the pulse. */
.recording-mode__waveform--static,
.recording-mode__no-speech--static .recording-mode__no-speech-dot { animation: none; }
.recording-mode__no-speech--static {
  border-radius: var(--r-sm);
  background: var(--warning-soft);
  padding: var(--sp-1) var(--sp-2);
}
@keyframes recording-no-speech-pulse { from { opacity: 1; } to { opacity: 0.35; } }

@media (prefers-reduced-motion: reduce) {
  .recording-mode__waveform--no-speech span, .recording-mode__waveform--pulse, .recording-mode__no-speech-dot { animation: none; }
  .recording-mode__waveform span, .recording-mode__spinner { animation: none; }
  .recording-mode__waveform span { height: 8px; }
  .recording-mode__no-speech { border-radius: var(--r-sm); background: var(--warning-soft); padding: var(--sp-1) var(--sp-2); }
}
</style>
