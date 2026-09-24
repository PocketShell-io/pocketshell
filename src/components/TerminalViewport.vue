<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { FitAddon } from '@xterm/addon-fit';
import { Terminal } from '@xterm/xterm';
import type { ITheme } from '@xterm/xterm';
import { TerminalGeometryReporter, type TerminalResizeRequest } from '../terminalGeometry';

const props = defineProps<{
  enabled: boolean;
  theme: ITheme;
  fontFamily: string;
  fontSize: number;
  resizeFailure?: TerminalResizeRequest | null;
}>();
const emit = defineEmits<{
  input: [data: string];
  resize: [size: TerminalResizeRequest];
}>();

type ComposerSmokeEvidenceWindow = Window & {
  __ps2857CaptureTerminalEvidence?: boolean;
  __ps2857TerminalVisibleText?: string;
  __ps2857TerminalWriteCount?: number;
  __ps2857TerminalLastWriteText?: string;
  __ps2857TerminalWriteCallbackCount?: number;
  __ps2857TerminalWriteParsedCount?: number;
  __ps2857TerminalRenderCount?: number;
  __ps2857TerminalLastRenderRange?: string;
  __ps2857TerminalBufferState?: string;
  __ps2875TerminalRuntimeGeometry?: {
    cols: number;
    rows: number;
    viewportY: number;
    baseY: number;
    bufferLength: number;
    cellHeight: number | null;
  };
};

const terminalHost = ref<HTMLDivElement>();
let terminal: Terminal | undefined;
let fitAddon: FitAddon | undefined;
let resizeObserver: ResizeObserver | undefined;
let renderListener: { dispose(): void } | undefined;
let writeParsedListener: { dispose(): void } | undefined;
const resizeReporter = new TerminalGeometryReporter();
let foregroundRefitFrame = 0;
let foregroundRefitFrameAfterLayout = 0;

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
  const tailStart = Math.max(0, buffer.length - Math.max(terminal.rows, 12));
  const tailRows = Array.from({ length: buffer.length - tailStart }, (_, index) => {
    const line = buffer.getLine(tailStart + index);
    return `${line?.isWrapped ? '' : '\n'}${line?.translateToString(true) ?? ''}`;
  }).join('').slice(-1200);
  const hostRect = terminalHost.value?.getBoundingClientRect();
  evidenceWindow.__ps2857TerminalBufferState = JSON.stringify({
    cols: terminal.cols,
    rows: terminal.rows,
    cursorX: buffer.cursorX,
    cursorY: buffer.cursorY,
    viewportY: buffer.viewportY,
    baseY: buffer.baseY,
    bufferLength: buffer.length,
    bufferType: buffer.type,
    hostWidth: hostRect?.width ?? null,
    hostHeight: hostRect?.height ?? null,
    visibleText: evidenceWindow.__ps2857TerminalVisibleText,
    tailText: tailRows,
  });
}

function captureRequestedTerminalGeometry() {
  const evidenceWindow = window as ComposerSmokeEvidenceWindow;
  if (!evidenceWindow.__ps2857CaptureTerminalEvidence || !terminal) return;
  const buffer = terminal.buffer.active;
  const renderMetrics = (terminal as unknown as {
    _core?: { _renderService?: { dimensions?: { css?: { cell?: { height?: number } } } } };
  })._core?._renderService?.dimensions?.css?.cell;
  evidenceWindow.__ps2875TerminalRuntimeGeometry = {
    cols: terminal.cols,
    rows: terminal.rows,
    viewportY: buffer.viewportY,
    baseY: buffer.baseY,
    bufferLength: buffer.length,
    cellHeight: typeof renderMetrics?.height === 'number' ? renderMetrics.height : null,
  };
}

const terminalGeometryRequestEvent = 'pocketshell:terminal-geometry-request';

function fitTerminal() {
  requestAnimationFrame(() => {
    if (!terminal || !fitAddon) return;
    try {
      fitAddon.fit();
      const geometry = { cols: terminal.cols, rows: terminal.rows };
      if (!props.enabled) {
        // A fit while disconnected can prepare xterm's local grid, but it
        // cannot acknowledge geometry on the next native PTY generation.
        resizeReporter.reset();
        return;
      }
      const request = resizeReporter.request(geometry);
      if (request) emit('resize', request);
    } catch {
      // The terminal host is not measurable until its containing panel is laid out.
    }
  });
}

function refitAfterVisibilityResume() {
  if (document.visibilityState !== 'visible') return;
  void nextTick().then(() => {
    if (document.visibilityState !== 'visible') return;
    cancelAnimationFrame(foregroundRefitFrame);
    cancelAnimationFrame(foregroundRefitFrameAfterLayout);
    foregroundRefitFrame = requestAnimationFrame(() => {
      foregroundRefitFrame = 0;
      foregroundRefitFrameAfterLayout = requestAnimationFrame(() => {
        foregroundRefitFrameAfterLayout = 0;
        if (document.visibilityState === 'visible') fitTerminal();
      });
    });
  });
}

watch(() => props.enabled, async (enabled) => {
  if (!terminal) return;
  terminal.options.disableStdin = !enabled;
  if (!enabled) {
    // Reconnects reuse this xterm instance. Forget its last acknowledged size
    // so the next live fit resizes the new physical PTY even at the same grid.
    resizeReporter.reset();
    return;
  }
  await nextTick();
  fitTerminal();
  terminal.focus();
});

watch(() => props.resizeFailure?.requestId, (requestId) => {
  if (requestId !== undefined) resizeReporter.reject(requestId);
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
  renderListener = terminal.onRender(({ start, end }) => {
    const evidenceWindow = window as ComposerSmokeEvidenceWindow;
    if (!evidenceWindow.__ps2857CaptureTerminalEvidence) return;
    evidenceWindow.__ps2857TerminalRenderCount = (evidenceWindow.__ps2857TerminalRenderCount ?? 0) + 1;
    evidenceWindow.__ps2857TerminalLastRenderRange = `${start}-${end}`;
    captureComposerSmokeTerminalText();
  });
  writeParsedListener = terminal.onWriteParsed(() => {
    const evidenceWindow = window as ComposerSmokeEvidenceWindow;
    if (!evidenceWindow.__ps2857CaptureTerminalEvidence) return;
    evidenceWindow.__ps2857TerminalWriteParsedCount = (evidenceWindow.__ps2857TerminalWriteParsedCount ?? 0) + 1;
    captureComposerSmokeTerminalText();
  });
  window.addEventListener(terminalGeometryRequestEvent, captureRequestedTerminalGeometry);
  terminal.onData((data) => emit('input', data));
  fitTerminal();
  resizeObserver = new ResizeObserver(fitTerminal);
  resizeObserver.observe(terminalHost.value);
  window.visualViewport?.addEventListener('resize', fitTerminal);
  window.addEventListener('resize', fitTerminal);
  document.addEventListener('visibilitychange', refitAfterVisibilityResume);
});

onBeforeUnmount(() => {
  resizeObserver?.disconnect();
  renderListener?.dispose();
  renderListener = undefined;
  writeParsedListener?.dispose();
  writeParsedListener = undefined;
  window.visualViewport?.removeEventListener('resize', fitTerminal);
  window.removeEventListener('resize', fitTerminal);
  document.removeEventListener('visibilitychange', refitAfterVisibilityResume);
  cancelAnimationFrame(foregroundRefitFrame);
  cancelAnimationFrame(foregroundRefitFrameAfterLayout);
  window.removeEventListener(terminalGeometryRequestEvent, captureRequestedTerminalGeometry);
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
  terminal.write(bytes, () => {
    const evidenceWindow = window as ComposerSmokeEvidenceWindow;
    if (!evidenceWindow.__ps2857CaptureTerminalEvidence) return;
    evidenceWindow.__ps2857TerminalWriteCallbackCount = (evidenceWindow.__ps2857TerminalWriteCallbackCount ?? 0) + 1;
    captureComposerSmokeTerminalText();
  });
}

function clear() {
  terminal?.clear();
}

function focus() {
  terminal?.focus();
}

function fit(): Promise<TerminalResizeRequest | null> {
  return new Promise((resolve) => {
    requestAnimationFrame(() => {
      if (!terminal || !fitAddon) {
        resolve(null);
        return;
      }
      try {
        fitAddon.fit();
        // The caller uses this forced request after each session attach so the
        // new PTY receives its own resize acknowledgement even at the same grid.
        resolve(resizeReporter.request({ cols: terminal.cols, rows: terminal.rows }, true));
      } catch {
        resolve(null);
      }
    });
  });
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

<style scoped>
:global(.live-workspace) .terminal-viewport {
  min-height: 0;
}
</style>
