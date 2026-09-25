<script setup lang="ts">
defineProps<{
  state: 'starting' | 'recording' | 'transcribing';
  elapsedLabel: string;
  livePreview: string;
}>();

const emit = defineEmits<{
  cancel: [];
  stop: [];
}>();
</script>

<template>
  <section class="recording-mode" data-testid="composer-recording-mode" :data-recording-state="state"
    :aria-label="state === 'recording' ? 'Prompt dictation recording' : state === 'transcribing' ? 'Transcribing prompt' : 'Starting prompt dictation'">
    <div class="recording-mode__header">
      <span class="recording-mode__indicator" :class="`recording-mode__indicator--${state}`" aria-hidden="true"></span>
      <strong v-if="state === 'starting'">Requesting microphone access…</strong>
      <strong v-else-if="state === 'recording'">Recording prompt</strong>
      <strong v-else>Transcribing…</strong>
      <time v-if="state === 'recording'" data-testid="composer-recording-timer" aria-label="Recording elapsed time">
        {{ elapsedLabel }}
      </time>
    </div>

    <div v-if="state === 'recording'" class="recording-mode__waveform" role="img"
      aria-label="Speech capture is active. The animated bars show recording state, not volume.">
      <span v-for="bar in 25" :key="bar" :style="{ '--bar': bar - 1 }"></span>
    </div>
    <div v-else-if="state === 'transcribing'" class="recording-mode__progress" role="status" aria-live="polite">
      <span class="recording-mode__spinner" aria-hidden="true"></span>
      <span>Preparing your draft</span>
    </div>
    <p v-if="state === 'recording'" id="composer-recording-preview" class="recording-mode__preview" data-testid="composer-recording-preview" aria-live="polite">
      {{ livePreview || 'Speak to build your draft.' }}
    </p>

    <div class="recording-mode__actions">
      <button class="recording-mode__button recording-mode__button--cancel" type="button"
        data-testid="composer-recording-cancel" @click="emit('cancel')">
        Cancel &amp; discard
      </button>
      <button v-if="state === 'recording'" class="recording-mode__button recording-mode__button--stop" type="button"
        data-testid="composer-recording-stop" aria-label="Stop dictation and keep the text in the editable draft"
        @click="emit('stop')">
        Stop &amp; keep text
      </button>
    </div>
  </section>
</template>

<style scoped>
.recording-mode {
  display: grid;
  gap: 8px;
  min-width: 0;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  background: var(--surface-2);
  padding: 10px;
}

.recording-mode__header {
  display: flex;
  min-width: 0;
  min-height: 22px;
  align-items: center;
  gap: 8px;
  color: var(--fg);
  font-size: var(--fs-200);
}

.recording-mode__header strong { min-width: 0; font-weight: 600; }
.recording-mode__header time {
  margin-left: auto;
  color: var(--accent);
  font: 600 var(--fs-300)/1 var(--font-mono);
  font-variant-numeric: tabular-nums;
}

.recording-mode__indicator {
  width: 9px;
  height: 9px;
  flex: 0 0 auto;
  border-radius: 50%;
  background: var(--fg-muted);
}
.recording-mode__indicator--recording {
  background: var(--error);
  box-shadow: 0 0 0 0 color-mix(in srgb, var(--error) 45%, transparent);
  animation: recording-pulse 1.5s ease-out infinite;
}
.recording-mode__indicator--transcribing { background: var(--accent); }

.recording-mode__waveform {
  display: flex;
  height: 27px;
  align-items: center;
  justify-content: space-between;
  overflow: hidden;
  border-radius: var(--r-sm);
  background: var(--bg);
  padding: 0 8px;
}
.recording-mode__waveform span {
  width: 3px;
  height: 22%;
  flex: 0 0 auto;
  border-radius: 2px;
  background: var(--accent);
  opacity: 0.72;
  transform-origin: center;
  animation: recording-wave 900ms ease-in-out infinite alternate;
  animation-delay: calc(var(--bar) * -53ms);
}
.recording-mode__waveform span:nth-child(4n + 1) { --peak: 1.4; }
.recording-mode__waveform span:nth-child(4n + 2) { --peak: 2.4; }
.recording-mode__waveform span:nth-child(4n + 3) { --peak: 3.4; }
.recording-mode__waveform span:nth-child(4n) { --peak: 1.9; }

.recording-mode__progress {
  display: flex;
  min-height: 27px;
  align-items: center;
  gap: 9px;
  color: var(--fg-secondary);
  font-size: var(--fs-100);
}
.recording-mode__spinner {
  width: 15px;
  height: 15px;
  border: 2px solid var(--border-strong);
  border-top-color: var(--accent);
  border-radius: 50%;
  animation: recording-spin 850ms linear infinite;
}

.recording-mode__preview {
  display: -webkit-box;
  min-height: 18px;
  overflow: hidden;
  margin: 0;
  color: var(--fg-secondary);
  font: 12px/1.45 var(--font-mono);
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow-wrap: anywhere;
}

.recording-mode__actions { display: flex; gap: 8px; }
.recording-mode__button {
  min-width: 0;
  min-height: 48px;
  flex: 1 1 0;
  border: 1px solid var(--border-strong);
  border-radius: var(--r-md);
  background: var(--bg);
  padding: 0 8px;
  color: var(--fg);
  font-size: var(--fs-100);
  font-weight: 600;
}
.recording-mode__button--cancel { color: var(--fg-secondary); }
.recording-mode__button--stop {
  border-color: var(--accent);
  background: var(--accent);
  color: var(--bg);
}
.recording-mode__button:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
.recording-mode__button:active { filter: brightness(1.12); }

@keyframes recording-wave {
  from { transform: scaleY(0.35); }
  to { transform: scaleY(var(--peak, 2)); }
}
@keyframes recording-pulse {
  70% { box-shadow: 0 0 0 7px color-mix(in srgb, var(--error) 0%, transparent); }
  100% { box-shadow: 0 0 0 0 color-mix(in srgb, var(--error) 0%, transparent); }
}
@keyframes recording-spin { to { transform: rotate(360deg); } }

@media (prefers-reduced-motion: reduce) {
  .recording-mode__indicator--recording, .recording-mode__waveform span, .recording-mode__spinner { animation: none; }
  .recording-mode__waveform span { height: 45%; }
}
</style>
