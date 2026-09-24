<script setup lang="ts">
import {
  usageDisplayName,
  usageThresholdState,
  usageWindowPercent,
  usageWindowDisplayLabel,
  type UsageProviderRecord,
} from '@pocketshell/core';
import { AppIcon } from '@pocketshell/ui';

const props = defineProps<{
  connected: boolean;
  records: UsageProviderRecord[];
  loading: boolean;
  error: string;
  lastReadAt: number | null;
}>();

const emit = defineEmits<{ refresh: [] }>();

function stateFor(record: UsageProviderRecord): string {
  const threshold = usageThresholdState(record);
  return record.status === 'ok' ? threshold : record.status;
}

function stateLabel(record: UsageProviderRecord): string | null {
  const state = stateFor(record);
  return state === 'ok' ? null : state;
}

function remainingPercent(record: UsageProviderRecord, index: number): number {
  const window = record.windows[index];
  return window ? Math.max(0, Math.min(100, 100 - usageWindowPercent(window))) : 0;
}

function meterTone(record: UsageProviderRecord): string {
  const threshold = usageThresholdState(record);
  if (threshold === 'exceeded' || threshold === 'critical' || record.status === 'blocked') return 'error';
  if (threshold === 'approaching' || record.status === 'warn') return 'warning';
  return 'success';
}

function formattedReset(value: string | null): string {
  if (!value) return '';
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) return '';
  return date.toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}

function capturedLabel(): string {
  if (props.lastReadAt === null) return '';
  return `Updated ${new Date(props.lastReadAt).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })}`;
}
</script>

<template>
  <main class="screen-content settings-screen feature-screen" data-testid="usage-screen">
    <section class="panel settings-panel feature-panel" aria-labelledby="usage-title">
      <div class="panel-heading">
        <div>
          <p class="eyebrow">CONNECTED HOST</p>
          <h1 id="usage-title">Provider usage</h1>
        </div>
        <button
          class="small-action feature-refresh"
          type="button"
          data-testid="usage-refresh"
          :disabled="!connected || loading"
          @click="emit('refresh')"
        >
          <AppIcon name="refresh" :class="{ 'feature-refresh__spin': loading }" :size="14" />
          Refresh
        </button>
      </div>

      <p class="settings-copy">Quota and login status are read over SSH from this host. Provider credentials stay on the host.</p>
      <p v-if="lastReadAt !== null" class="feature-updated" data-testid="usage-updated">{{ capturedLabel() }}</p>
      <p v-if="error" class="feature-error" role="alert" data-testid="usage-error">{{ error }}</p>

      <div v-if="!connected" class="settings-empty-state" data-testid="usage-disconnected">
        <AppIcon name="bar-chart-2" :size="16" />
        <div><strong>Connect to a host to read provider usage.</strong><p>Usage is never fetched with credentials stored on this device.</p></div>
      </div>

      <p v-else-if="loading && records.length === 0" class="muted empty" data-testid="usage-loading">Reading usage from the host…</p>

      <div v-else-if="records.length" class="usage-provider-list" data-testid="usage-provider-list">
        <article
          v-for="record in records"
          :key="record.provider"
          class="usage-provider"
          data-testid="usage-provider"
          :data-provider="record.provider"
          :data-state="stateFor(record)"
        >
          <div class="usage-provider__heading">
            <strong>{{ usageDisplayName(record.provider) }}</strong>
            <span v-if="stateLabel(record)" class="usage-state" :class="`usage-state--${stateFor(record)}`">{{ stateLabel(record) }}</span>
          </div>

          <div v-if="record.windows.length" class="usage-window-list">
            <div v-for="(window, index) in record.windows" :key="window.name" class="usage-window">
              <span class="usage-window__label">{{ usageWindowDisplayLabel(window.name) }}</span>
              <span
                class="usage-meter"
                role="meter"
                :aria-label="`${usageWindowDisplayLabel(window.name)} quota remaining`"
                :aria-valuemin="0"
                :aria-valuemax="100"
                :aria-valuenow="Math.round(remainingPercent(record, index))"
              >
                <span class="usage-meter__fill" :class="`usage-meter__fill--${meterTone(record)}`" :style="{ width: `${remainingPercent(record, index)}%` }" />
              </span>
              <span class="usage-window__remaining">{{ Math.round(remainingPercent(record, index)) }}% left</span>
              <time v-if="window.resetAt" class="usage-window__reset" :datetime="window.resetAt" :title="formattedReset(window.resetAt)">
                {{ formattedReset(window.resetAt) }}
              </time>
            </div>
          </div>
          <p v-else class="usage-note">{{ record.lastError || 'No quota windows reported.' }}</p>

          <p v-if="record.blockReason" class="usage-note">{{ record.blockReason }}</p>
          <p v-if="record.lastError && record.windows.length" class="usage-note usage-note--error">{{ record.lastError }}</p>
          <div v-if="record.resetCredits" class="usage-credits" data-testid="usage-reset-credits">
            <strong v-if="record.resetCredits.availableCount !== null">
              {{ record.resetCredits.availableCount }} reset{{ record.resetCredits.availableCount === 1 ? '' : 's' }} available
            </strong>
            <strong v-else>Reset credits unavailable</strong>
            <span v-for="(credit, index) in record.resetCredits.credits" :key="`${credit.title}-${index}`" class="usage-credits__item">
              {{ credit.title }}<template v-if="credit.expiresAt"> · expires {{ formattedReset(credit.expiresAt) }}</template>
            </span>
          </div>
        </article>
      </div>

      <p v-else-if="!error" class="muted empty" data-testid="usage-empty">No usage data was reported by this host.</p>
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
.feature-updated { margin: -8px 0 12px; color: var(--fg-muted); font-size: var(--fs-100); }
.feature-error { margin: 0 0 12px; border-left: 3px solid var(--error); background: var(--error-soft); padding: 9px 11px; color: var(--error); font-size: var(--fs-200); line-height: 1.45; overflow-wrap: anywhere; }
.usage-provider-list { display: grid; gap: 0; }
.usage-provider { display: grid; gap: 8px; border-top: 1px solid var(--border-soft); padding: 13px 0; }
.usage-provider__heading { display: flex; min-height: 28px; align-items: center; justify-content: space-between; gap: 10px; }
.usage-provider__heading > strong { min-width: 0; font-size: var(--fs-300); font-weight: 600; }
.usage-state { border: 1px solid var(--border); border-radius: 999px; background: var(--surface-2); padding: 3px 8px; color: var(--fg-secondary); font-size: var(--fs-100); line-height: 1.2; text-transform: capitalize; }
.usage-state--approaching, .usage-state--warn { border-color: var(--warning); color: var(--warning); }
.usage-state--critical, .usage-state--exceeded, .usage-state--blocked, .usage-state--error { border-color: var(--error); color: var(--error); }
.usage-state--unsupported, .usage-state--unknown { color: var(--fg-muted); }
.usage-window-list { display: grid; gap: 9px; }
.usage-window { display: grid; grid-template-columns: 78px minmax(50px, 1fr) 62px; align-items: center; column-gap: 8px; row-gap: 3px; min-width: 0; }
.usage-window__label { color: var(--fg-secondary); font-size: var(--fs-100); }
.usage-meter { display: block; height: 7px; overflow: hidden; border-radius: 99px; background: var(--surface-3, var(--border-soft)); }
.usage-meter__fill { display: block; height: 100%; min-width: 2px; border-radius: inherit; }
.usage-meter__fill--success { background: var(--success); }
.usage-meter__fill--warning { background: var(--warning); }
.usage-meter__fill--error { background: var(--error); }
.usage-window__remaining { color: var(--fg-secondary); font: 11px/1.3 var(--font-mono); text-align: right; }
.usage-window__reset { grid-column: 2 / -1; color: var(--fg-muted); font-size: 10px; text-align: right; }
.usage-note { margin: 0; color: var(--fg-secondary); font-size: var(--fs-100); line-height: 1.4; overflow-wrap: anywhere; }
.usage-note--error { color: var(--error); }
.usage-credits { display: grid; gap: 4px; border-left: 2px solid var(--border-strong); padding-left: 9px; color: var(--fg-secondary); font-size: var(--fs-100); }
.usage-credits strong { color: var(--fg); font-weight: 600; }
.usage-credits__item { color: var(--fg-muted); }
@keyframes feature-spin { to { transform: rotate(360deg); } }
</style>
