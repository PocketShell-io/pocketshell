<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { FitAddon } from '@xterm/addon-fit';
import { Terminal } from '@xterm/xterm';
import { mobileMonoFontFamily, mobileTheme } from '../sharedUiDefaults';

const props = defineProps<{ enabled: boolean }>();
const emit = defineEmits<{
  input: [data: string];
  resize: [size: { cols: number; rows: number }];
}>();

const terminalHost = ref<HTMLDivElement>();
let terminal: Terminal | undefined;
let fitAddon: FitAddon | undefined;
let resizeObserver: ResizeObserver | undefined;

function fitTerminal() {
  requestAnimationFrame(() => {
    if (!terminal || !fitAddon) return;
    try {
      fitAddon.fit();
      emit('resize', { cols: terminal.cols, rows: terminal.rows });
    } catch {
      // The terminal host is not measurable until its containing panel is laid out.
    }
  });
}

watch(() => props.enabled, async (enabled) => {
  if (!terminal) return;
  terminal.options.disableStdin = !enabled;
  if (enabled) {
    await nextTick();
    fitTerminal();
    terminal.focus();
  }
});

onMounted(() => {
  if (!terminalHost.value) return;
  terminal = new Terminal({
    allowProposedApi: false,
    cursorBlink: true,
    disableStdin: !props.enabled,
    fontFamily: mobileMonoFontFamily,
    fontSize: 13,
    lineHeight: 1.25,
    scrollback: 1000,
    theme: mobileTheme.terminal,
  });
  fitAddon = new FitAddon();
  terminal.loadAddon(fitAddon);
  terminal.open(terminalHost.value);
  terminal.onData((data) => emit('input', data));
  fitTerminal();
  resizeObserver = new ResizeObserver(fitTerminal);
  resizeObserver.observe(terminalHost.value);
  window.visualViewport?.addEventListener('resize', fitTerminal);
  window.addEventListener('resize', fitTerminal);
});

onBeforeUnmount(() => {
  resizeObserver?.disconnect();
  window.visualViewport?.removeEventListener('resize', fitTerminal);
  window.removeEventListener('resize', fitTerminal);
  terminal?.dispose();
  terminal = undefined;
});

function write(bytes: Uint8Array) {
  terminal?.write(bytes);
}

function clear() {
  terminal?.clear();
}

function focus() {
  terminal?.focus();
}

defineExpose({ write, clear, focus });
</script>

<template>
  <div
    id="terminal-viewport"
    ref="terminalHost"
    class="terminal-viewport"
    role="region"
    aria-label="Live SSH terminal"
    :data-enabled="enabled"
  />
</template>
