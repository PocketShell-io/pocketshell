<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue';
import { FitAddon } from '@xterm/addon-fit';
import { Terminal } from '@xterm/xterm';

const terminalHost = ref<HTMLDivElement>();
let terminal: Terminal | undefined;
let resizeObserver: ResizeObserver | undefined;

function fitTerminal() {
  requestAnimationFrame(() => {
    try {
      fitAddon?.fit();
    } catch {
      // The terminal host is not measurable until its containing panel is laid out.
    }
  });
}

let fitAddon: FitAddon | undefined;

onMounted(() => {
  if (!terminalHost.value) return;
  terminal = new Terminal({
    allowProposedApi: false,
    cursorBlink: false,
    disableStdin: true,
    fontFamily: "Consolas, 'Cascadia Mono', ui-monospace, monospace",
    fontSize: 13,
    lineHeight: 1.25,
    scrollback: 40,
    theme: {
      background: '#0c0c0c',
      foreground: '#cccccc',
      cursor: '#ffffff',
      cursorAccent: '#0c0c0c',
      selectionBackground: 'rgba(255,255,255,0.32)',
      black: '#0c0c0c',
      red: '#c50f1f',
      green: '#13a10e',
      yellow: '#c19c00',
      blue: '#0037da',
      magenta: '#881798',
      cyan: '#3a96dd',
      white: '#cccccc',
      brightBlack: '#767676',
      brightRed: '#e74856',
      brightGreen: '#16c60c',
      brightYellow: '#f9f1a5',
      brightBlue: '#3b78ff',
      brightMagenta: '#b4009e',
      brightCyan: '#61d6d6',
      brightWhite: '#f2f2f2',
    },
  });
  fitAddon = new FitAddon();
  terminal.loadAddon(fitAddon);
  terminal.open(terminalHost.value);
  fitAddon.fit();
  terminal.write(
    '\x1b[1;36mPocketShell\x1b[0m terminal preview\r\n' +
      '\x1b[90mNo SSH connection · demonstration output only\x1b[0m\r\n\r\n' +
      '\x1b[32m$\x1b[0m pocketshell workspace list\r\n' +
      '\x1b[90mNo host selected. Session support is not implemented in this preview.\x1b[0m\r\n',
  );
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
</script>

<template>
  <div ref="terminalHost" class="terminal-preview" role="img" aria-label="Read-only terminal preview with mock output" />
</template>
