<script setup lang="ts">
import { computed, ref } from 'vue';
import { AppIcon } from '@pocketshell/ui';
import { makeDiagnosticExport, useDiagnosticsStore, type DiagnosticEvent } from '../diagnostics';
import { useNavigationStore } from '../stores/navigation';

const diagnostics = useDiagnosticsStore();
const navigation = useNavigationStore();
const exportMessage = ref('');
const reportText = computed(() => makeDiagnosticExport(diagnostics.events));
const visibleEvents = computed(() => [...diagnostics.events].reverse());
const selectedReport = computed(() => diagnostics.events.find((event) => event.id === navigation.selectedReportId) ?? null);

function eventTitle(event: DiagnosticEvent): string {
  const titles: Record<DiagnosticEvent['kind'], string> = {
    'app-started': 'App started',
    'app-backgrounded': 'App moved to background',
    'app-foregrounded': 'App returned to foreground',
    'build-verified': 'Packaged build verified',
    'build-verification-failed': 'Build verification failed',
    'ssh-connect-failed': 'SSH connection failed',
    'ssh-operation-failed': 'SSH operation failed',
    'ssh-bridge-failed': 'SSH bridge failed',
    'resource-snapshot-failed': 'Native resource snapshot failed',
  };
  return titles[event.kind];
}

function formatTimestamp(at: number): string {
  return new Date(at).toLocaleString();
}

function openReport(event: DiagnosticEvent) {
  navigation.openReport(event.id);
}

function openClearConfirmation() {
  navigation.open('diagnostics-clear');
}

async function exportReport() {
  const file = new File([reportText.value], 'pocketshell-diagnostics.json', { type: 'application/json' });
  try {
    if (typeof navigator.share === 'function' && typeof navigator.canShare === 'function' && navigator.canShare({ files: [file] })) {
      await navigator.share({ title: 'PocketShell diagnostics', files: [file] });
      exportMessage.value = 'The reviewed report was shared.';
      return;
    }
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') return;
  }

  const url = URL.createObjectURL(file);
  const link = document.createElement('a');
  link.href = url;
  link.download = file.name;
  link.click();
  URL.revokeObjectURL(url);
  exportMessage.value = 'The reviewed report was downloaded.';
}

function clearEvents() {
  diagnostics.clear();
  navigation.back();
}
</script>

<template>
  <main v-if="navigation.route === 'diagnostics'" class="screen-content settings-screen" data-testid="diagnostics-screen">
    <section class="panel settings-panel" aria-labelledby="diagnostics-page-title">
      <p class="eyebrow">LOCAL SUPPORT</p>
      <h1 id="diagnostics-page-title">Diagnostics</h1>
      <p class="settings-copy">Review recent build, SSH bridge, connection, and lifecycle events before exporting a report.</p>

      <div v-if="!diagnostics.events.length" class="settings-empty-state" data-testid="diagnostics-empty">
        <AppIcon name="bar-chart-2" :size="16" />
        <div><strong>No diagnostic events yet.</strong><p>Future connection and bridge failures are recorded here automatically.</p></div>
      </div>
      <ol v-else class="event-list" data-testid="diagnostics-events">
        <li v-for="event in visibleEvents" :key="event.id">
          <button class="event-row" type="button" :data-event-id="event.id" @click="openReport(event)">
            <span class="event-row__icon"><AppIcon name="bar-chart-2" :size="16" /></span>
            <span class="event-row__copy">
              <strong>{{ eventTitle(event) }}</strong>
              <small>{{ formatTimestamp(event.at) }} · {{ event.operation }} · {{ event.code }}</small>
            </span>
            <AppIcon class="settings-link__chevron" name="arrow-right" :size="16" />
          </button>
        </li>
      </ol>

      <button class="action-button" type="button" data-testid="review-diagnostics-export" @click="navigation.openDiagnosticsExport()">Review export contents</button>
      <button class="action-button action-button--secondary" type="button" data-testid="export-diagnostics" @click="exportReport">Share or download report</button>
      <p v-if="exportMessage" class="settings-note" role="status" data-testid="diagnostics-export-status">{{ exportMessage }}</p>
      <button class="text-action" type="button" data-testid="clear-diagnostics" @click="openClearConfirmation">Clear local events</button>
      <p class="privacy-note">The report contains event times, failure categories, operation names, and normalized error codes. It never includes host names, private keys, command text, or raw bridge messages. Nothing is sent until you choose Share or download.</p>
    </section>
  </main>

  <main v-else-if="navigation.route === 'diagnostics-report'" class="screen-content settings-screen" data-testid="diagnostics-report-screen">
    <section class="panel settings-panel" aria-labelledby="diagnostics-report-title">
      <p class="eyebrow">LOCAL SUPPORT · REPORT PREVIEW</p>
      <h1 id="diagnostics-report-title">{{ selectedReport ? eventTitle(selectedReport) : 'Connection report' }}</h1>
      <p class="settings-copy">{{ selectedReport ? `Selected event from ${formatTimestamp(selectedReport.at)}; the export below contains the local diagnostic timeline.` : 'This is the exact JSON that will be shared or downloaded.' }}</p>
      <dl v-if="selectedReport" class="diagnostic-list report-event" data-testid="selected-diagnostic-event">
        <div><dt>Recorded</dt><dd>{{ formatTimestamp(selectedReport.at) }}</dd></div>
        <div><dt>Operation</dt><dd>{{ selectedReport.operation }}</dd></div>
        <div><dt>Normalized error code</dt><dd>{{ selectedReport.code }}</dd></div>
      </dl>
      <pre class="report-preview" data-testid="diagnostics-report-preview">{{ reportText }}</pre>
      <button class="action-button" type="button" data-testid="export-reviewed-report" @click="exportReport">Share or download reviewed report</button>
    </section>
  </main>

  <main v-else class="screen-content settings-screen" data-testid="diagnostics-clear-screen">
    <section class="panel settings-panel" aria-labelledby="diagnostics-clear-title">
      <p class="eyebrow">LOCAL SUPPORT</p>
      <h1 id="diagnostics-clear-title">Clear local events?</h1>
      <p class="settings-copy">This removes {{ diagnostics.events.length }} locally stored event{{ diagnostics.events.length === 1 ? '' : 's' }} from this device. It does not change an SSH connection.</p>
      <button class="action-button" type="button" data-testid="confirm-clear-diagnostics" @click="clearEvents">Clear events</button>
      <button class="action-button action-button--secondary" type="button" data-testid="cancel-clear-diagnostics" @click="navigation.back()">Keep events</button>
    </section>
  </main>
</template>
