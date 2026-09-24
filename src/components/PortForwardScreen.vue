<script setup lang="ts">
import { computed, ref } from 'vue';
import { isValidTcpPort, type RemotePort } from '@pocketshell/core';
import { AppIcon } from '@pocketshell/ui';
import type { PortForwardControllerSnapshot } from '../policy/portForwardController';

const props = defineProps<{
  connected: boolean;
  loading: boolean;
  error: string;
  autoEnabled: boolean;
  manualPorts: number[];
  scanCount: number;
  snapshot: PortForwardControllerSnapshot;
}>();

const emit = defineEmits<{
  refresh: [];
  'set-auto': [enabled: boolean];
  'set-port': [remotePort: number, enabled: boolean];
}>();

const manualPortInput = ref('');
const manualPortError = ref('');

interface PortRow {
  port: number;
  process: string | null;
  cwd: string | null;
  localPort: number | null;
  origin: string | null;
  error: string | null;
  deferred: boolean;
  manuallyDesired: boolean;
}

const rows = computed<PortRow[]>(() => {
  const byPort = new Map<number, PortRow>();
  const discovered = props.snapshot.scan.ok ? props.snapshot.scan.ports : [];
  for (const port of discovered) {
    byPort.set(port.port, fromDiscovered(port));
  }
  for (const forward of props.snapshot.activeForwards) {
    const row = byPort.get(forward.remotePort) ?? emptyRow(forward.remotePort);
    row.localPort = forward.localPort;
    row.origin = forward.origin;
    byPort.set(forward.remotePort, row);
  }
  for (const port of props.manualPorts) {
    const row = byPort.get(port) ?? emptyRow(port);
    row.manuallyDesired = true;
    byPort.set(port, row);
  }
  for (const port of props.snapshot.deferredPorts) {
    const row = byPort.get(port) ?? emptyRow(port);
    row.deferred = true;
    byPort.set(port, row);
  }
  for (const [port, error] of Object.entries(props.snapshot.errors)) {
    const number = Number(port);
    const row = byPort.get(number) ?? emptyRow(number);
    row.error = error;
    byPort.set(number, row);
  }
  return [...byPort.values()].sort((left, right) => left.port - right.port);
});

function fromDiscovered(port: RemotePort): PortRow {
  return { ...emptyRow(port.port), process: port.process, cwd: port.cwd };
}

function emptyRow(port: number): PortRow {
  return { port, process: null, cwd: null, localPort: null, origin: null, error: null, deferred: false, manuallyDesired: false };
}

function stateOf(row: PortRow): string {
  if (row.error) return 'failed';
  if (row.localPort !== null) return 'forwarding';
  if (row.deferred) return 'waiting';
  if (row.manuallyDesired) return 'opening';
  return 'not-forwarded';
}

function pathLabel(path: string | null): string {
  if (!path) return '—';
  const parts = path.split('/').filter(Boolean);
  return parts.length > 2 ? `…/${parts.slice(-2).join('/')}` : path;
}

function addManualPort(): void {
  const port = Number(manualPortInput.value);
  if (!isValidTcpPort(port)) {
    manualPortError.value = 'Enter a TCP port from 1 to 65535.';
    return;
  }
  manualPortError.value = '';
  emit('set-port', port, true);
  manualPortInput.value = '';
}
</script>

<template>
  <main class="screen-content settings-screen feature-screen" data-testid="ports-screen" :data-scan-count="scanCount">
    <section class="panel settings-panel feature-panel" aria-labelledby="ports-title">
      <div class="panel-heading">
        <div>
          <p class="eyebrow">CONNECTED HOST</p>
          <h1 id="ports-title">Port forwarding</h1>
        </div>
        <button
          class="small-action feature-refresh"
          type="button"
          data-testid="port-scan"
          :disabled="!connected || loading"
          @click="emit('refresh')"
        >
          <AppIcon name="refresh" :class="{ 'feature-refresh__spin': loading }" :size="14" />
          Scan
        </button>
      </div>

      <p class="settings-copy">Listening services are discovered over SSH. Local tunnel sockets are opened by the Android SSH bridge.</p>
      <button
        class="settings-control port-auto-toggle"
        type="button"
        data-testid="port-auto-toggle"
        :aria-pressed="autoEnabled"
        :disabled="!connected || loading"
        @click="emit('set-auto', !autoEnabled)"
      >
        <span class="port-auto-toggle__label">
          <strong>Auto-forward</strong>
          <small>Mirror eligible ports from 1024 to 10000</small>
        </span>
        <span class="port-auto-toggle__state" :class="{ 'port-auto-toggle__state--on': autoEnabled }">{{ autoEnabled ? 'On' : 'Off' }}</span>
      </button>

      <form class="port-manual-form" data-testid="port-manual-form" @submit.prevent="addManualPort">
        <label for="manual-remote-port">Add a port</label>
        <span class="port-manual-form__controls">
          <input
            id="manual-remote-port"
            v-model="manualPortInput"
            data-testid="port-manual-input"
            type="number"
            inputmode="numeric"
            min="1"
            max="65535"
            step="1"
            placeholder="Remote TCP port"
            :disabled="!connected || loading"
          />
          <button class="small-action" data-testid="port-manual-add" type="submit" :disabled="!connected || loading">Forward</button>
        </span>
        <p v-if="manualPortError" class="feature-error port-manual-form__error" role="alert" data-testid="port-manual-error">{{ manualPortError }}</p>
      </form>

      <p v-if="error" class="feature-error" role="alert" data-testid="ports-error">{{ error }}</p>
      <p v-if="!snapshot.scan.ok && snapshot.scan.error" class="feature-error" role="alert" data-testid="port-scan-error">
        The remote scan failed. Existing tunnels are retained. {{ snapshot.scan.error }}
      </p>
      <div v-if="!connected" class="settings-empty-state" data-testid="ports-disconnected">
        <AppIcon name="arrow-right-left" :size="16" />
        <div><strong>Connect to a host to discover services.</strong><p>Automatic forwards are limited by the shared core policy; explicit forwards remain available for any valid TCP port.</p></div>
      </div>
      <p v-else-if="loading && rows.length === 0" class="muted empty" data-testid="ports-loading">Scanning remote listeners…</p>

      <div v-else-if="rows.length" class="port-row-list" data-testid="port-row-list" :data-scan-ok="snapshot.scan.ok">
        <article
          v-for="row in rows"
          :key="row.port"
          class="port-row"
          data-testid="port-row"
          :data-remote-port="row.port"
          :data-forwarded="row.localPort !== null"
          :data-state="stateOf(row)"
        >
          <div class="port-row__details">
            <div class="port-row__heading">
              <strong>Remote {{ row.port }}</strong>
              <span class="port-state" :class="`port-state--${stateOf(row)}`">{{ stateOf(row) }}</span>
            </div>
            <span class="port-row__process">{{ row.process || 'Listening service' }}<template v-if="row.cwd"> · {{ pathLabel(row.cwd) }}</template></span>
            <span v-if="row.localPort !== null" class="port-row__endpoint" data-testid="port-forward-local" :data-local-port="row.localPort">
              127.0.0.1:{{ row.localPort }} → 127.0.0.1:{{ row.port }}<template v-if="row.origin"> · {{ row.origin }}</template>
            </span>
            <span v-else-if="row.error" class="port-row__error">{{ row.error }}</span>
            <span v-else-if="row.deferred" class="port-row__process">Waiting for an available tunnel slot.</span>
            <span v-else-if="row.manuallyDesired" class="port-row__process">Will reopen after a successful scan or reconnect.</span>
          </div>
          <button
            class="small-action port-row__action"
            type="button"
            data-testid="port-forward-toggle"
            :data-remote-port="row.port"
            :disabled="!connected || loading"
            :aria-label="`${row.localPort !== null ? 'Stop forwarding' : row.manuallyDesired ? 'Cancel forwarding' : 'Forward'} port ${row.port}`"
            @click="emit('set-port', row.port, row.localPort === null && !row.manuallyDesired)"
          >{{ row.localPort !== null ? 'Stop' : row.manuallyDesired ? 'Cancel' : 'Forward' }}</button>
        </article>
      </div>

      <p v-else-if="snapshot.scan.ok" class="muted empty" data-testid="ports-empty">No listening TCP ports were reported by the host.</p>
      <p class="settings-note">Auto-forward policy, scan parsing, and cleanup timing come from pocketshell-core. Nothing is forwarded outside this SSH connection.</p>
    </section>
  </main>
</template>

<style scoped>
.feature-panel { min-width: 0; }
.feature-panel .panel-heading { align-items: center; }
.feature-panel .panel-heading h1 { margin: 0; color: var(--fg); font-size: var(--fs-500); font-weight: 600; line-height: 1.25; }
.feature-refresh { display: inline-flex; min-height: 48px; align-items: center; gap: 7px; }
.feature-refresh:disabled { opacity: var(--disabled-opacity); }
.feature-refresh__spin { animation: feature-spin 1s linear infinite; }
.feature-error { margin: 0 0 12px; border-left: 3px solid var(--error); background: var(--error-soft); padding: 9px 11px; color: var(--error); font-size: var(--fs-200); line-height: 1.45; overflow-wrap: anywhere; }
.port-auto-toggle { display: flex; width: 100%; min-height: 56px; align-items: center; justify-content: space-between; text-align: left; background: transparent; }
.port-auto-toggle:disabled { opacity: var(--disabled-opacity); }
.port-auto-toggle__label { display: grid; gap: 3px; }
.port-auto-toggle__label strong { font-weight: 600; }
.port-auto-toggle__label small { color: var(--fg-secondary); font-size: var(--fs-100); }
.port-auto-toggle__state { min-width: 54px; border: 1px solid var(--border); border-radius: 999px; padding: 5px 10px; color: var(--fg-secondary); font-size: var(--fs-100); text-align: center; }
.port-auto-toggle__state--on { border-color: var(--success); color: var(--success); }
.port-manual-form { display: grid; gap: 7px; padding: 3px 0 12px; }
.port-manual-form > label { color: var(--fg-secondary); font-size: var(--fs-100); }
.port-manual-form__controls { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 8px; }
.port-manual-form__controls input { min-width: 0; min-height: 48px; border: 1px solid var(--border-strong); border-radius: var(--r-md); background: var(--bg); padding: 0 11px; color: var(--fg); font: 12px/1.3 var(--font-mono); }
.port-manual-form__controls input::placeholder { color: var(--fg-muted); font-family: var(--font-sans); }
.port-manual-form__controls button { min-width: 76px; min-height: 48px; }
.port-manual-form__controls button:disabled, .port-manual-form__controls input:disabled { opacity: var(--disabled-opacity); }
.port-manual-form__error { margin-bottom: 0; }
.port-row-list { display: grid; }
.port-row { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 12px; border-top: 1px solid var(--border-soft); padding: 12px 0; }
.port-row__details { display: grid; min-width: 0; gap: 4px; }
.port-row__heading { display: flex; min-width: 0; align-items: center; gap: 8px; }
.port-row__heading strong { font: 600 var(--fs-300)/1.25 var(--font-mono); }
.port-state { border: 1px solid var(--border); border-radius: 999px; padding: 2px 7px; color: var(--fg-muted); font-size: var(--fs-100); text-transform: capitalize; white-space: nowrap; }
.port-state--forwarding { border-color: var(--success); color: var(--success); }
.port-state--waiting, .port-state--opening { border-color: var(--warning); color: var(--warning); }
.port-state--failed { border-color: var(--error); color: var(--error); }
.port-row__process { overflow: hidden; color: var(--fg-secondary); font-size: var(--fs-100); text-overflow: ellipsis; white-space: nowrap; }
.port-row__endpoint { color: var(--fg); font: 11px/1.4 var(--font-mono); overflow-wrap: anywhere; }
.port-row__error { color: var(--error); font-size: var(--fs-100); overflow-wrap: anywhere; }
.port-row__action { min-width: 64px; min-height: 48px; }
.port-row__action:disabled { opacity: var(--disabled-opacity); }
@keyframes feature-spin { to { transform: rotate(360deg); } }
</style>
