<script setup lang="ts">
// The one-tap voice entry (#3128): on a phone's terminal screen, one tap opens
// the shared prompt composer AND starts Android's recognizer, as the 0.5.x mic
// FAB did. Contributed to core's `composer.launcher` slot, which core renders
// beside the composer's toggle only while the composer is closed.
//
// Kept to this one small component on purpose: the #3122 compact composer bar
// (not signed off yet) puts the mic in that bar, and moving it there must be a
// change of slot, not a rewrite. Phone layout only (D44): on a medium or wide
// window it renders nothing, so desktop-class layouts stay as they are.
import { computed } from 'vue';
import type { ComposerExtensionContext } from '@ui/app/extensions';
import { useLayoutClass } from '@ui/app/layoutClass';
import DictationMicIcon from '@/components/DictationMicIcon.vue';
import { voiceComposer } from './voiceComposerRun';

const props = defineProps<{ context: ComposerExtensionContext }>();
const layout = useLayoutClass();
const compact = computed(() => layout.value === 'compact');

function dictate(): void {
  // Start inside the tap: the first use asks for RECORD_AUDIO from here.
  voiceComposer.start(props.context);
  props.context.open();
}
</script>

<template>
  <button
    v-if="compact"
    class="voice-launcher"
    type="button"
    data-testid="voice-composer-launcher"
    aria-label="Dictate a prompt"
    title="Dictate a prompt"
    @click="dictate"
  >
    <DictationMicIcon :size="24" />
  </button>
</template>

<style scoped>
/* Floats above the composer's toggle in the pane's bottom-right corner (the
   composer root is the containing block), clear of the terminal's text. */
.voice-launcher {
  position: absolute;
  right: var(--sp-3);
  bottom: calc(var(--sp-3) + var(--control-h) + var(--sp-3));
  width: 56px;
  height: 56px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  pointer-events: auto;
  background: var(--accent);
  color: var(--on-accent);
  border: none;
  border-radius: 50%;
  box-shadow: var(--shadow-card);
  cursor: pointer;
}
.voice-launcher:focus-visible {
  outline: var(--focus-ring-width) solid var(--focus-ring);
  outline-offset: var(--focus-ring-offset);
}
</style>
