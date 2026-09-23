<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { FitAddon } from '@xterm/addon-fit';
import { Terminal } from '@xterm/xterm';
import type { ITheme } from '@xterm/xterm';

const props = defineProps<{ enabled: boolean; theme: ITheme; fontFamily: string; fontSize: number }>();
const emit = defineEmits<{
  input: [data: string];
  resize: [size: { cols: number; rows: number }];
}>();

type ComposerSmokeEvidenceWindow = Window & {
  __ps2857CaptureTerminalEvidence?: boolean;
  __ps2857TerminalVisibleText?: string;
  __ps2857TerminalWriteCount?: number;
  __ps2857TerminalLastWriteText?: string;
  __ps2857TerminalRenderCount?: number;
};

const terminalHost = ref<HTMLDivElement>();
let terminal: Terminal | undefined;
let fitAddon: FitAddon | undefined;
let resizeObserver: ResizeObserver | undefined;
let renderListener: { dispose(): void } | undefined;

function captureComposerSmokeTerminalText() {
  const evidenceWindow = window as ComposerSmokeEvidenceWindow;
  // The packaged smoke test explicitly opts in before SSH connects; ordinary app sessions never publish terminal text to the window.
  if (!evidenceWindow.__ps2857CaptureTerminalEvidence || !terminal) return;
  const buffer = terminal.buffer.active;
  const visibleRows = Array.from({ length: terminal.rows }, (_, row) =>
    buffer.getLine(buffer.viewportY + row));
  evidenceWindow.__ps2857TerminalVisibleText = visibleRows
    .map((line, index) => `${index > 0 && !line?.isWrapped ? '\n' : ''}${line?.translateToString(true) ?? ''}`)
    .join('')
    .slice(-4000);
  evidenceWindow.__ps2857TerminalRenderCount = (evidenceWindow.__ps2857TerminalRenderCount ?? 0) + 1;
}

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

watch(() => [props.theme, props.fontFamily, props.fontSize] as const, async () => {
  if (!terminal) return;
  terminal.options.theme = props.theme;
  terminal.options.fontFamily = props.fontFamily;
  terminal.options.fontSize = props.fontSize;
  await nextTick();
  fitTerminal();
});

onMounted(() => {
  if (!terminalHost.value) return;
  terminal = new Terminal({
    allowProposedApi: false,
    cursorBlink: true,
    disableStdin: !props.enabled,
    fontFamily: props.fontFamily,
    fontSize: props.fontSize,
    lineHeight: 1.25,
    scrollback: 1000,
    theme: props.theme,
  });
  fitAddon = new FitAddon();
  terminal.loadAddon(fitAddon);
  terminal.open(terminalHost.value);
  renderListener = terminal.onRender(captureComposerSmokeTerminalText);
  terminal.onData((data) => emit('input', data));
  fitTerminal();
  resizeObserver = new ResizeObserver(fitTerminal);
  resizeObserver.observe(terminalHost.value);
  window.visualViewport?.addEventListener('resize', fitTerminal);
  window.addEventListener('resize', fitTerminal);
});

onBeforeUnmount(() => {
  resizeObserver?.disconnect();
  renderListener?.dispose();
  renderListener = undefined;
  window.visualViewport?.removeEventListener('resize', fitTerminal);
  window.removeEventListener('resize', fitTerminal);
  terminal?.dispose();
  terminal = undefined;
});

function write(bytes: Uint8Array) {
  if (!terminal) return;
  const evidenceWindow = window as ComposerSmokeEvidenceWindow;
  if (evidenceWindow.__ps2857CaptureTerminalEvidence) {
    evidenceWindow.__ps2857TerminalWriteCount = (evidenceWindow.__ps2857TerminalWriteCount ?? 0) + 1;
    evidenceWindow.__ps2857TerminalLastWriteText = new TextDecoder().decode(bytes).slice(-4000);
  }
  terminal.write(bytes, captureComposerSmokeTerminalText);
}

function clear() {
  terminal?.clear();
}

function focus() {
  terminal?.focus();
}

function fit() {
  fitTerminal();
}

function scrollToBottom() {
  terminal?.scrollToBottom();
}

defineExpose({ write, clear, focus, fit, scrollToBottom });
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
