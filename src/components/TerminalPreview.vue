<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue';
import { FitAddon } from '@xterm/addon-fit';
import { Terminal } from '@xterm/xterm';
import { mobileMonoFontFamily, mobileTheme } from '../sharedUiDefaults';

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
    fontFamily: mobileMonoFontFamily,
    fontSize: 13,
    lineHeight: 1.25,
    scrollback: 40,
    theme: mobileTheme.terminal,
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
