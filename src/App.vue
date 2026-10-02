<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch, watchEffect, type CSSProperties } from 'vue';
import { App as CapacitorApp } from '@capacitor/app';
import { Capacitor } from '@capacitor/core';
import {
  fromAndroidTrustedHostKeySha256,
  isValidTcpPort,
  joinRemoteChildPath,
  PortForwardController,
  readHostUsage,
  runSyncRound,
  type ConnectionSnapshot,
  type HostKeyTrustChoice,
  type HostKeyTrustPin,
  type HostKeyTrustStore,
  type PortForwardControllerSnapshot,
  type SessionRow,
  type SshConnectionRef,
  type SshHostTarget,
  type SshResourceSnapshot,
  type UsageRow,
  type TerminalKeyId,
} from '@pocketshell/core';
import AppIcon from '@ui/components/AppIcon.vue';
import { fontCssVariables } from '@ui/fonts';
import { resolveTheme } from '@ui/themes';
import HostKeyTrustPrompt from '@ui/app/components/HostKeyTrustPrompt.vue';
import { hostKeyAnswer, hostKeyCard, hostKeyRefusalMessage } from './session/hostKeyCard';
import { verifyCurrentBuild, type BuildVerification } from './buildDiagnostics';
import { coreSourceRevision } from './coreSourceInfo';
import { connectionStateLabel, terminalStateLabel } from './session/releaseLabels';
import {
  readImportedLegacyHosts,
  installedDataMigrationState,
  SETTINGS_RELOAD_SESSION_KEY,
  createImportPersistence,
  retryInstalledDataMigration,
  runInstalledDataMigration,
  shouldReloadForImportedSettings,
  type ImportedLegacyHost,
} from './migration/installedDataMigration';
import { makeLegacySshHostTarget } from './migration/legacySshTarget';
import { androidKeyManager } from './platform/android/hosts';
import { androidSync } from './platform/android/sync';
import SshKeysScreen, { type SshKeyHostInstallTarget } from './components/SshKeysScreen.vue';
import KeyIcon from './components/KeyIcon.vue';
import { installPublicKeyOnHost, liveAuthorizedKeyInstallHost, type DialedHost } from './credentials/authorizedKeys';
import { sshKeyVault, type SshKeyMetadata } from './native/sshKeyVault';
import { useNavigationStore } from './stores/navigation';
import { useAppSettings } from './stores/appSettings';
import { useSettingsStore } from '@ui/app/stores/settings';
import { useDiagnosticsStore, type DiagnosticKind } from './diagnostics';
import { rememberDiagnosticTerms } from './platform/androidDiagnostics';
import { ConnectionController } from './session/connectionController';
import { createAppLifecycleHandler } from './session/appLifecycle';
import { waitForAttachAutofocusTestGate } from './session/attachAutofocusTestGate';
import { resolveAndroidBackDestination, transitionHomeSurface, type HomeSurface, type HomeSurfaceAction } from './session/homeSurface';
import { readSshError, sshCapability } from './native/sshCapability';
import { keyboardInsets, type KeyboardInsetsState } from './native/keyboardInsets';
import { createKeyboardInsetsStateSync } from './native/keyboardInsetsState';
import TerminalViewport from './components/TerminalViewport.vue';
import BuildIntegrityAlert from './components/BuildIntegrityAlert.vue';
import MobileHotkeys from './components/MobileHotkeys.vue';
import TerminalDictationBar from './components/TerminalDictationBar.vue';
import PromptComposer from './components/PromptComposer.vue';
import type { PtyWriteAcknowledgement } from './session/composerDelivery';
import type { InlineDictationState } from './session/inlineDictation';
import { allocateTerminalResizeRequestId, type TerminalResizeRequest } from './terminalGeometry';
import SettingsScreen from './components/SettingsScreen.vue';
import ProviderUsageScreen from './components/ProviderUsageScreen.vue';
import PortForwardScreen from './components/PortForwardScreen.vue';
import DiagnosticsScreen from './components/DiagnosticsScreen.vue';
import AboutScreen from './components/AboutScreen.vue';
import FileWorkspaceScreen from './components/FileWorkspaceScreen.vue';
import { hostSnippets } from './stores/hostSnippets';
import SessionAgentMetadata from './components/SessionAgentMetadata.vue';
import { projectSessionAgentPresentation, resolveSelectedSessionRow } from './session/agentMetadata';

interface TerminalViewportHandle {
  write(bytes: Uint8Array): void;
  clear(): void;
  focus(): void;
  fit(): Promise<TerminalResizeRequest | null>;
  scrollToBottom(): void;
}

interface MobileHotkeysHandle {
  closePalette(): void;
  openPalette(): void;
}

type ComposerSmokeEvidenceWindow = Window & {
  __ps2857CaptureTerminalEvidence?: boolean;
  __ps2857AppTerminalInputChunks?: Array<{
    text: string;
    sessionName: string;
    sessionId: string;
    sessionTag: string;
    attachEpoch: number;
    phase: string;
  }>;
  __ps2857AppTerminalInputChars?: number;
  __ps2857AppTerminalInputDroppedChunks?: number;
  __ps2857AppTerminalDeliveryCount?: number;
  __ps2857AppTerminalLastChunk?: string;
  __ps2857AppTerminalMissingRefCount?: number;
  __ps2884HotkeyWrites?: Array<{ key: TerminalKeyId; bytes: number[] }>;
  __ps2884CaptureResizeFitEvidence?: boolean;
  __ps2884ResizeFitMarker?: string;
  __ps2884ResizeAckEvents?: Array<{
    atMs: number;
    marker: string;
    requestId: number;
    cols: number;
    rows: number;
    attachEpoch: number;
    result: 'accepted' | 'failed' | 'missing';
  }>;
};

const MAX_APP_TERMINAL_INPUT_EVIDENCE_CHUNKS = 128;
const MAX_APP_TERMINAL_INPUT_EVIDENCE_CHARS = 2_048;

type SettingsSyncProbeWindow = Window & {
  __ps2852RunSettingsSync?: typeof runSyncRound;
};
const SETTINGS_SYNC_PROBE_STORAGE_KEY = 'pocketshell.settings-sync-test-probe';

type SnippetEvidenceWindow = Window & {
  __ps2885CaptureSnippetEvidence?: boolean;
  __ps2885ComposerWriteCount?: number;
  __ps2885ComposerLastWriteHex?: string;
};

const navigation = useNavigationStore();
const appSettings = useAppSettings();
// Background grace and reconnect-on-return live in the shared settings store (D42).
const sharedSettings = useSettingsStore();
const diagnostics = useDiagnosticsStore();
// Shared with the shared app (#2936) so key deletes warn about every host.
const keyManager = androidKeyManager;
const buildVerification = ref<BuildVerification | { checking: true }>({ checking: true });
const buildStatusTone = computed(() => {
  if ('checking' in buildVerification.value) return 'checking';
  return buildVerification.value.ok ? 'verified' : 'error';
});
/** Why the build check failed, kept for support (About and the hidden hook), never on home (#3023). */
const buildFailureReason = computed(() =>
  !('checking' in buildVerification.value) && !buildVerification.value.ok ? buildVerification.value.reason : '',
);
const bundleHash = computed(() =>
  !('checking' in buildVerification.value) && buildVerification.value.ok
    ? buildVerification.value.bundleAssetHash
    : '',
);
const activeTheme = computed(() => resolveTheme(appSettings.themeChoice));
const terminalFontFamily = computed(() => fontCssVariables({
  monospaceFontFamily: null,
  terminalFontSize: appSettings.terminalFontSize,
  editorFontSize: 13,
}, 'ui-monospace, monospace')['--font-mono']);
const backButtonReady = ref(!Capacitor.isNativePlatform());
const backButtonEvents = ref(0);
const keyboardVisible = ref(false);
const mobilePromptComposerOpen = ref(false);
const mobilePromptComposerInline = ref(false);
const promptComposerHasFocus = ref(false);
const mobileHotkeysHasFocus = ref(false);
const terminalViewportHasFocus = ref(false);
const terminalViewportDockCapPx = ref<number | null>(null);
const terminalViewportDockBaseCapPx = ref<number | null>(null);
const mobileHotkeysPaletteOpen = ref(false);
const mobileHotkeysPage = ref<'main' | 'ctrl'>('main');
const inlineDictationStatusRowHeightPx = 32;
const inlineDictationListeningStatusRowHeightPx = 40;
const inlineDictationRecoveryStatusRowHeightPx = 64;
const terminalViewportDockPreferredCapPx = 144;
const homeSurface = ref<HomeSurface>('connection');
const hostDraft = ref({ hostname: '', port: '22', username: '' });
const importedLegacyHosts = ref<ImportedLegacyHost[]>([]);
const selectedLegacyHostId = ref('');
/** Hosts from the signed-in Google account's last sync (#3020); metadata only, no keys. */
const syncedAccountHosts = ref<Array<{ name: string; hostname: string; port: number; user: string }>>([]);
const selectedSyncedHostName = ref('');
const sshKeys = ref<SshKeyMetadata[]>([]);
const selectedKeyHandleId = ref('');
const sshKeyLoadError = ref('');
const legacyKeyPassphrase = ref('');
const sessionName = ref('mobile-session');
const connectionSnapshot = ref<ConnectionSnapshot | null>(null);
const connectionMessage = ref('');
const resourceSnapshot = ref<SshResourceSnapshot | null>(null);
const resourceSnapshotStatus = ref<'unverified' | 'pending' | 'verified' | 'failed'>('unverified');
const usageRecords = ref<UsageRow[]>([]);
const usageLoading = ref(false);
const usageError = ref('');
const usageLastReadAt = ref<number | null>(null);
const portSnapshot = ref<PortForwardControllerSnapshot>(emptyPortSnapshot());
const portLoading = ref(false);
const portError = ref('');
const portAutoEnabled = ref(true);
const portManualDesiredPorts = ref<number[]>([]);
const portDisabledPorts = ref<number[]>([]);
const portScanCount = ref(0);
const retainedHomeScreenStyle = ref<CSSProperties>();
const hiddenHomeScreenStyle: CSSProperties = { display: 'none' };
const terminalResizeStatus = ref('waiting for a live PTY');
const terminal = ref<TerminalViewportHandle | null>(null);
const mobileHotkeys = ref<MobileHotkeysHandle | null>(null);
const terminalInputPending = ref(0);
const terminalInputAckCount = ref(0);
const terminalInputFailureCount = ref(0);
const terminalResizePending = ref(0);
const terminalResizeAckCount = ref(0);
const terminalResizeFailureCount = ref(0);
const terminalResizeFailure = ref<TerminalResizeRequest | null>(null);
const inlineDictationState = ref<InlineDictationState>({
  phase: 'idle',
  preview: '',
  message: 'Tap Dictate to speak at the terminal cursor.',
  tone: 'quiet',
});
// Resize callbacks can finish after the user has selected a different PTY.
const terminalAttachEpoch = ref(0);
const terminalAttachFocusWindowEpoch = ref(0);
const terminalAttachPromptFocusEpoch = ref(0);
const terminalAttachResizeAckEpoch = ref(0);

let controller: ConnectionController | null = null;
let pendingTrustPassphrase: string | undefined;
let portForwardController: PortForwardController | null = null;
let portForwardHostId: string | null = null;
let portForwardConnectionKey: string | null = null;
let usageRequestEpoch = 0;
let portRequestEpoch = 0;
let removeBackButton: (() => Promise<void>) | undefined;
let removeAppState: (() => Promise<void>) | undefined;
let removeKeyboardInsetsListener: (() => Promise<void>) | undefined;
let removeControllerSnapshot: (() => void) | undefined;
let removeTerminalOutput: (() => void) | undefined;
let removeKeyboardViewportListeners: (() => void) | undefined;
let disposeKeyboardInsetsStateSync: (() => void) | undefined;
let nativeKeyboardInsetsSupported = false;
const currentPhase = computed(() => connectionSnapshot.value?.phase ?? 'idle');
const isConnecting = computed(() => ['connecting', 'reconnecting'].includes(currentPhase.value));
const isConnected = computed(() => ['connected', 'listing', 'attaching', 'live', 'background'].includes(currentPhase.value));
const hasActiveConnection = computed(() => isConnected.value
  && Boolean(connectionSnapshot.value?.connectionId && connectionSnapshot.value.generationId));
// A document that will reload to apply imported settings keeps presenting the
// migration as pending, so nothing can act on a page the reload discards (#3005).
const publishedMigrationStatus = computed(() => installedDataMigrationState.reloadPending
  ? 'pending'
  : installedDataMigrationState.status);
const migrationBlocksConnection = computed(() => installedDataMigrationState.retrying
  || publishedMigrationStatus.value === 'pending');
const isLive = computed(() => currentPhase.value === 'live');
const connectionStateText = computed(() => connectionStateLabel(currentPhase.value));
const terminalStateText = computed(() => terminalStateLabel(currentPhase.value));
const terminalAutofocusAllowed = computed(() => (terminalAttachPromptFocusEpoch.value === 0
  || terminalAttachPromptFocusEpoch.value !== terminalAttachEpoch.value)
  && !promptComposerHasFocus.value);
const mobileHotkeysEnabled = computed(() => isLive.value
  && homeSurface.value === 'live'
  && navigation.route === 'home');
const keyboardComposerMode = computed(() =>
  keyboardVisible.value && isLive.value
    && (promptComposerHasFocus.value || mobileHotkeysHasFocus.value || terminalViewportHasFocus.value),
);
const composerTargetKey = computed(() => {
  const session = connectionSnapshot.value?.selectedSession;
  const hostname = hostDraft.value.hostname.trim();
  const username = hostDraft.value.username.trim();
  if (!session || !hostname || !username) return '';
  return `${username}@${hostname}:${hostDraft.value.port}/${session.id ?? session.name}`;
});
watch(composerTargetKey, (targetKey, previousTargetKey) => {
  if (targetKey === previousTargetKey) return;
  mobilePromptComposerInline.value = false;
  mobilePromptComposerOpen.value = false;
});
const inlineDictationTargetKey = computed(() => composerTargetKey.value
  ? `${composerTargetKey.value}/attach-${terminalAttachEpoch.value}`
  : '');
const inlineDictationStatusVisible = computed(() => Capacitor.getPlatform() === 'android' && (
  inlineDictationState.value.phase !== 'idle'
  || inlineDictationState.value.tone === 'error'
  || inlineDictationState.value.tone === 'warning'
));
const inlineDictationRecoveryVisible = computed(() => inlineDictationStatusVisible.value
  && inlineDictationState.value.phase === 'idle'
  && inlineDictationState.value.tone === 'warning'
  && inlineDictationState.value.preview.length > 0);
const mobileHotkeysDockHeight = computed(() => {
  const dictationStatusRowHeight = inlineDictationStatusVisible.value
    ? inlineDictationState.value.phase === 'listening'
      ? inlineDictationListeningStatusRowHeightPx
      : inlineDictationRecoveryVisible.value
        ? inlineDictationRecoveryStatusRowHeightPx
        : inlineDictationStatusRowHeightPx
    : 0;
  const catalogHeight = mobileHotkeysPaletteOpen.value ? 96 : 0;
  const dockInset = Capacitor.getPlatform() === 'android' ? 1 : 0;
  return 48 + dictationStatusRowHeight + dockInset + catalogHeight;
});
watch(
  () => [keyboardVisible.value, keyboardComposerMode.value, mobileHotkeysPaletteOpen.value,
    inlineDictationStatusVisible.value, inlineDictationRecoveryVisible.value, inlineDictationState.value.phase] as const,
  ([imeOpen, keyboardMode, paletteOpen, dictationStatusOpen]) => {
    const androidKeyboardUp = Capacitor.getPlatform() === 'android' && imeOpen && keyboardMode;
    if (!paletteOpen && !dictationStatusOpen && !androidKeyboardUp) {
      terminalViewportDockCapPx.value = null;
      terminalViewportDockBaseCapPx.value = null;
      return;
    }
    if (!imeOpen) return;
    // Establish the accepted Android terminal grid as soon as the keyboard-up
    // composer is active, before a fast-key catalog or dictation status opens.
    // A smaller measured viewport wins; larger API 35 viewports stay capped at
    // the approved 144px/38×6 budget across dock, status, and reattach states.
    if (terminalViewportDockBaseCapPx.value === null) {
      const viewport = document.querySelector<HTMLElement>('.terminal-slot > .terminal-viewport');
      const height = viewport?.getBoundingClientRect().height ?? 0;
      if (height > 0) {
        terminalViewportDockBaseCapPx.value = Math.min(terminalViewportDockPreferredCapPx, Math.floor(height));
      }
    }
    if (terminalViewportDockBaseCapPx.value !== null) {
      terminalViewportDockCapPx.value = terminalViewportDockBaseCapPx.value;
    }
  },
  { flush: 'sync' },
);
const composerTransportState = computed<'connected' | 'lost' | 'closed'>(() => {
  if (!connectionSnapshot.value?.selectedSession) return 'closed';
  if (currentPhase.value === 'live') return 'connected';
  if (['connecting', 'reconnecting', 'attaching', 'background', 'error'].includes(currentPhase.value)) return 'lost';
  return 'closed';
});
const trustDecision = computed(() => connectionSnapshot.value?.trustDecision ?? null);
const legacyHostKeyCard = computed(() => (
  trustDecision.value ? hostKeyCard(trustDecision.value, hostDraft.value) : null
));
const sessions = computed(() => connectionSnapshot.value?.sessions ?? []);
const selectedSessionRow = computed(() => resolveSelectedSessionRow(
  connectionSnapshot.value?.selectedSession,
  sessions.value,
));
const selectedSessionAgentPresentation = computed(() => projectSessionAgentPresentation(selectedSessionRow.value));
const fileConnection = computed(() => {
  const snapshot = connectionSnapshot.value;
  return snapshot?.connectionId && snapshot.generationId
    ? { connectionId: snapshot.connectionId, generationId: snapshot.generationId }
    : null;
});
const fileRootDirectory = computed(() => {
  const username = hostDraft.value.username.trim();
  const home = joinRemoteChildPath('/home', username);
  return home.ok ? home.path : '';
});
const selectedLegacyHost = computed(() => importedLegacyHosts.value.find(
  (host) => String(host.id) === selectedLegacyHostId.value,
) ?? null);
const selectedSshKey = computed(() => sshKeys.value.find(
  (key) => key.handleId === selectedKeyHandleId.value,
) ?? null);
const snippetHostId = computed(() => {
  if (connectionSnapshot.value?.hostId) return connectionSnapshot.value.hostId;
  if (selectedLegacyHost.value) return String(selectedLegacyHost.value.id);
  const hostname = hostDraft.value.hostname.trim();
  const username = hostDraft.value.username.trim();
  const port = Number(hostDraft.value.port);
  return hostname && username && Number.isInteger(port) && port >= 1 && port <= 65_535
    ? `${username}@${hostname}:${port}`
    : '';
});
const snippetHostLabel = computed(() => connectionSnapshot.value?.hostLabel
  || (snippetHostId.value ? `${hostDraft.value.username.trim()}@${hostDraft.value.hostname.trim()}:${Number(hostDraft.value.port)}` : 'No host selected'));

function navigateHomeSurface(action: HomeSurfaceAction) {
  mobilePromptComposerOpen.value = false;
  if (action !== 'session-attached' && document.activeElement instanceof HTMLElement) {
    document.activeElement.blur();
  }
  homeSurface.value = transitionHomeSurface(homeSurface.value, action);
}

/** The controller's session identity: id when both rows have one, else name + workspace. */
function sameSessionRow(left: SessionRow, right: SessionRow): boolean {
  if (left.id && right.id) return left.id === right.id;
  return left.name === right.name && left.workspace === right.workspace;
}

function isSelectedSession(session: SessionRow): boolean {
  const selected = selectedSessionRow.value;
  if (!selected?.id || session.id !== selected.id) return false;
  return sessions.value.filter((candidate) => candidate.id === selected.id).length === 1;
}

function openSettings(): void {
  if (connectionSnapshot.value) {
    const homeScreen = document.querySelector<HTMLElement>('.home-screen');
    const bounds = homeScreen?.getBoundingClientRect();
    if (bounds && bounds.width > 0 && bounds.height > 0) {
      retainedHomeScreenStyle.value = {
        position: 'fixed',
        left: `${bounds.left}px`,
        top: `${bounds.top}px`,
        width: `${bounds.width}px`,
        height: `${bounds.height}px`,
        visibility: 'hidden',
        pointerEvents: 'none',
      };
    }
  }
  if (document.activeElement instanceof HTMLElement) document.activeElement.blur();
  navigation.openSettings();
}

function openSnippetSettings(): void {
  openSettings();
  navigation.open('settings-snippets');
}

interface StoredPortPreferences {
  autoEnabled: boolean;
  desiredManualPorts: number[];
  disabledPorts: number[];
}

function emptyPortSnapshot(): PortForwardControllerSnapshot {
  return {
    scan: { ok: false, ports: [], error: null },
    activeForwards: [],
    deferredPorts: [],
    errors: {},
  };
}

function portPreferencesKey(hostId: string): string {
  return `pocketshell.js.port-preferences.v1.${hostId}`;
}

function readPortPreferences(hostId: string): StoredPortPreferences {
  try {
    const stored = localStorage.getItem(portPreferencesKey(hostId));
    if (!stored) return { autoEnabled: true, desiredManualPorts: [], disabledPorts: [] };
    const value: unknown = JSON.parse(stored);
    if (typeof value !== 'object' || value === null) {
      return { autoEnabled: true, desiredManualPorts: [], disabledPorts: [] };
    }
    const preferences = value as Record<string, unknown>;
    return {
      autoEnabled: typeof preferences.autoEnabled === 'boolean' ? preferences.autoEnabled : true,
      desiredManualPorts: Array.isArray(preferences.desiredManualPorts)
        ? [...new Set(preferences.desiredManualPorts.filter(
          (port): port is number => typeof port === 'number' && isValidTcpPort(port),
        ))].sort((left, right) => left - right)
        : [],
      disabledPorts: Array.isArray(preferences.disabledPorts)
        ? [...new Set(preferences.disabledPorts.filter(
          (port): port is number => typeof port === 'number' && isValidTcpPort(port),
        ))].sort((left, right) => left - right)
        : [],
    };
  } catch {
    return { autoEnabled: true, desiredManualPorts: [], disabledPorts: [] };
  }
}

function storePortPreferences(): void {
  if (!portForwardHostId || !portForwardController) return;
  try {
    localStorage.setItem(portPreferencesKey(portForwardHostId), JSON.stringify({
      autoEnabled: portAutoEnabled.value,
      desiredManualPorts: portForwardController.getManualDesiredPorts(),
      disabledPorts: portDisabledPorts.value,
    }));
  } catch {
    // Port policy remains usable for this connection if browser storage is unavailable.
  }
}

function currentConnectionRef(): SshConnectionRef | null {
  const snapshot = connectionSnapshot.value;
  if (!snapshot?.connectionId || !snapshot.generationId) return null;
  return { connectionId: snapshot.connectionId, generationId: snapshot.generationId };
}

function sameConnection(left: SshConnectionRef | null, right: SshConnectionRef | null): boolean {
  return left !== null && right !== null && left.connectionId === right.connectionId &&
    left.generationId === right.generationId;
}

function syncPortForwardController(snapshot: ConnectionSnapshot): void {
  if (!snapshot.hostId || !snapshot.connectionId || !snapshot.generationId) return;
  const connection = { connectionId: snapshot.connectionId, generationId: snapshot.generationId };
  const connectionKey = `${connection.connectionId}\u0000${connection.generationId}`;
  const existing = portForwardController;
  if (existing && portForwardHostId === snapshot.hostId) {
    if (portForwardConnectionKey === connectionKey) return;
    portForwardConnectionKey = connectionKey;
    portRequestEpoch += 1;
    usageRequestEpoch += 1;
    usageLoading.value = false;
    portLoading.value = true;
    portError.value = '';
    void existing.setConnection(connection).then(async () => {
      if (existing !== portForwardController || portForwardConnectionKey !== connectionKey) return;
      portSnapshot.value = existing.snapshot();
      await refreshPorts();
      if (navigation.route === 'usage') void refreshUsage();
    }).catch((error: unknown) => {
      if (existing !== portForwardController || portForwardConnectionKey !== connectionKey) return;
      portError.value = error instanceof Error ? error.message : String(error);
      portLoading.value = false;
    });
    return;
  }

  const preferences = readPortPreferences(snapshot.hostId);
  const next = new PortForwardController(sshCapability, connection, {
    desiredManualPorts: preferences.desiredManualPorts,
  });
  for (const port of preferences.disabledPorts) next.setManualDesiredPort(port, false);
  next.setAutoEnabled(preferences.autoEnabled);
  portForwardController = next;
  portForwardHostId = snapshot.hostId;
  portForwardConnectionKey = connectionKey;
  portAutoEnabled.value = preferences.autoEnabled;
  portManualDesiredPorts.value = next.getManualDesiredPorts();
  portDisabledPorts.value = preferences.disabledPorts;
  portSnapshot.value = next.snapshot();
  portError.value = '';
  portLoading.value = true;
  usageRequestEpoch += 1;
  if (navigation.route === 'usage') void refreshUsage();
  void refreshPorts();
}

async function refreshUsage(): Promise<void> {
  const connection = currentConnectionRef();
  if (!hasActiveConnection.value || !connection) return;
  const requestEpoch = ++usageRequestEpoch;
  usageLoading.value = true;
  usageError.value = '';
  try {
    const records = await readHostUsage(sshCapability, connection);
    if (requestEpoch !== usageRequestEpoch || !sameConnection(connection, currentConnectionRef())) return;
    usageRecords.value = records;
    usageLastReadAt.value = Date.now();
  } catch (error: unknown) {
    if (requestEpoch !== usageRequestEpoch || !sameConnection(connection, currentConnectionRef())) return;
    usageError.value = error instanceof Error ? error.message : String(error);
  } finally {
    if (requestEpoch === usageRequestEpoch) usageLoading.value = false;
  }
}

async function refreshPorts(): Promise<void> {
  const active = portForwardController;
  if (!active || !hasActiveConnection.value || !currentConnectionRef()) return;
  const requestEpoch = ++portRequestEpoch;
  portLoading.value = true;
  portError.value = '';
  try {
    const snapshot = await active.scanAndReconcile();
    if (requestEpoch === portRequestEpoch && active === portForwardController) {
      portSnapshot.value = snapshot;
    }
  } catch (error: unknown) {
    if (requestEpoch === portRequestEpoch && active === portForwardController) {
      portError.value = error instanceof Error ? error.message : String(error);
    }
  } finally {
    if (requestEpoch === portRequestEpoch && active === portForwardController) portLoading.value = false;
    if (requestEpoch === portRequestEpoch && active === portForwardController) portScanCount.value += 1;
  }
}

function setAutomaticPortForwarding(enabled: boolean): void {
  const active = portForwardController;
  if (!active) return;
  portAutoEnabled.value = enabled;
  active.setAutoEnabled(enabled);
  storePortPreferences();
  void refreshPorts();
}

function setManualPortForwarding(remotePort: number, enabled: boolean): void {
  const active = portForwardController;
  if (!active || !isValidTcpPort(remotePort)) return;
  try {
    active.setManualDesiredPort(remotePort, enabled);
    portManualDesiredPorts.value = active.getManualDesiredPorts();
    portDisabledPorts.value = enabled
      ? portDisabledPorts.value.filter((port) => port !== remotePort)
      : [...new Set([...portDisabledPorts.value, remotePort])].sort((left, right) => left - right);
    storePortPreferences();
    void refreshPorts();
  } catch (error: unknown) {
    portError.value = error instanceof Error ? error.message : String(error);
  }
}

watch(() => navigation.route, (route) => {
  if (route === 'home') refreshSyncedAccountHosts();
  if (route === 'usage') void refreshUsage();
  if (route === 'ports') void refreshPorts();
  if (route === 'keys') void refreshSshKeys();
});

function openPromptComposer() {
  // Inline terminal recognition owns the dock until it reaches idle. Leaving
  // Prompt closed keeps its Stop/Cancel action physically reachable.
  if (inlineDictationState.value.phase !== 'idle' || mobilePromptComposerInline.value) return;
  mobileHotkeys.value?.closePalette();
  mobilePromptComposerOpen.value = true;
}

function showInlinePromptComposerAfterSend() {
  if (Capacitor.getPlatform() !== 'android') return;
  // Keep the connected workspace's terminal-first layout after an acknowledged
  // Send. The existing normal-flow composer leaves the fresh PTY output above
  // the input surface instead of dimming it behind the mobile sheet.
  setMobilePromptComposerOpen(false);
  mobilePromptComposerInline.value = true;
}

async function openTerminalKeysFromComposer() {
  if ((!mobilePromptComposerOpen.value && !mobilePromptComposerInline.value) || !isLive.value) return;
  // The composer and catalog are alternate input surfaces. Keep the draft in
  // its per-PTY store, close the modal, then transfer focus to xterm so the
  // palette can stay open above Android's keyboard without a second composer.
  mobilePromptComposerOpen.value = false;
  mobilePromptComposerInline.value = false;
  await nextTick();
  mobileHotkeys.value?.openPalette();
  await nextTick();
  terminal.value?.focus();
}

function setMobilePromptComposerOpen(open: boolean) {
  if (!open) mobilePromptComposerInline.value = false;
  if (!open && Capacitor.getPlatform() === 'android') {
    // Blurring a WebView editor does not reliably dismiss Android's IME on
    // API 35. Request the native inset transition while the Prompt sheet is
    // closing so the dock and terminal regain their full viewport.
    void keyboardInsets.hideIme().catch((error: unknown) => {
      console.error('Could not dismiss the Android IME after closing Prompt.', error);
    });
  }
  mobilePromptComposerOpen.value = open;
  if (!open) {
    void nextTick(() => {
      document.querySelector<HTMLButtonElement>('[data-testid="prompt-composer-launcher"]')
        ?.focus({ preventScroll: true });
    });
  }
}

function pinStoreKey(hostId: string): string {
  return `pocketshell.ssh.host-key.${hostId}`;
}

function isPromptComposerElement(target: Element | null): boolean {
  return target !== null && target.closest('[data-testid="prompt-composer"]') !== null;
}

function isMobileHotkeysElement(target: Element | null): boolean {
  return target !== null && target.closest('[data-testid="mobile-hotkeys"]') !== null;
}

function isTerminalViewportElement(target: Element | null): boolean {
  return target !== null && target.closest('.terminal-viewport') !== null;
}

function recordFocusedElement(event: FocusEvent) {
  const target = event.target instanceof Element ? event.target : null;
  promptComposerHasFocus.value = isPromptComposerElement(target);
  mobileHotkeysHasFocus.value = isMobileHotkeysElement(target);
  terminalViewportHasFocus.value = isTerminalViewportElement(target);
  if (event.type === 'focusin') recordAttachComposerInteraction(target);
}

function recordAttachComposerPointer(event: PointerEvent) {
  recordAttachComposerInteraction(event.target instanceof Element ? event.target : null);
}

function recordAttachComposerInteraction(target: Element | null) {
  // Keep composer intent for this PTY even if the tap lands just after the
  // attach focus window closes. TerminalViewport's enabled watcher can still
  // finish its async autofocus after attachSession has returned to the caller.
  // A later attach gets a new epoch, so it naturally clears this intent.
  if (homeSurface.value !== 'live' || !isLive.value || !isPromptComposerElement(target)) return;
  terminalAttachPromptFocusEpoch.value = terminalAttachEpoch.value;
}

function recordFocusAfterBlur() {
  queueMicrotask(() => {
    const activeElement = document.activeElement instanceof Element ? document.activeElement : null;
    promptComposerHasFocus.value = isPromptComposerElement(activeElement);
    mobileHotkeysHasFocus.value = isMobileHotkeysElement(activeElement);
    terminalViewportHasFocus.value = isTerminalViewportElement(activeElement);
  });
}

function readStoredPin(hostId: string): HostKeyTrustPin | null {
  const stored = localStorage.getItem(pinStoreKey(hostId));
  if (stored == null) return null;
  try {
    const parsed: unknown = JSON.parse(stored);
    if (typeof parsed === 'string') return fromAndroidTrustedHostKeySha256(parsed);
    if (typeof parsed !== 'object' || parsed === null) return null;
    const pin = parsed as Record<string, unknown>;
    if (pin.kind === 'sha256-fingerprint' && typeof pin.fingerprintSha256 === 'string') {
      return { kind: 'sha256-fingerprint', fingerprintSha256: pin.fingerprintSha256 };
    }
    if (pin.kind === 'wire-key'
      && typeof pin.fingerprintSha256 === 'string'
      && typeof pin.keyType === 'string'
      && typeof pin.keyB64 === 'string') {
      return {
        kind: 'wire-key',
        fingerprintSha256: pin.fingerprintSha256,
        keyType: pin.keyType,
        keyB64: pin.keyB64,
      };
    }
    return null;
  } catch {
    // Older Android host rows store the SHA256 fingerprint as a bare string.
    return fromAndroidTrustedHostKeySha256(stored);
  }
}

const trustStore: HostKeyTrustStore = {
  async get(hostId) {
    return readStoredPin(hostId);
  },
  async record(hostId, pin) {
    localStorage.setItem(pinStoreKey(hostId), JSON.stringify(pin));
  },
};

function bindController(next: ConnectionController) {
  controller = next;
  let lastReportedError = '';
  removeControllerSnapshot = next.subscribe((snapshot) => {
    connectionSnapshot.value = snapshot;
    syncPortForwardController(snapshot);
    if (snapshot.phase === 'error' && snapshot.error && snapshot.error !== lastReportedError) {
      lastReportedError = snapshot.error;
      diagnostics.record('ssh-operation-failed', 'connect', 'CONNECTION_FAILED');
    } else if (!snapshot.error) {
      lastReportedError = '';
    }
  });
  removeTerminalOutput = next.subscribeTerminalOutput((session, bytes) => {
    // This screen shows one terminal: only the selected session's bytes.
    const selected = connectionSnapshot.value?.selectedSession;
    if (selected && !sameSessionRow(selected, session)) return;
    const smokeEvidence = window as ComposerSmokeEvidenceWindow;
    const target = terminal.value;
    // Instrumentation opts in before connection setup; keep terminal output private in normal sessions.
    if (smokeEvidence.__ps2857CaptureTerminalEvidence) {
      smokeEvidence.__ps2857AppTerminalDeliveryCount = (smokeEvidence.__ps2857AppTerminalDeliveryCount ?? 0) + 1;
      smokeEvidence.__ps2857AppTerminalLastChunk = new TextDecoder().decode(bytes).slice(-4000);
      if (!target) smokeEvidence.__ps2857AppTerminalMissingRefCount = (smokeEvidence.__ps2857AppTerminalMissingRefCount ?? 0) + 1;
    }
    target?.write(bytes);
  });
}

function recordFailure(kind: DiagnosticKind, operation: string, error: unknown) {
  const nativeError = readSshError(error);
  diagnostics.record(kind, operation, nativeError.code);
}

function recordOperationFailure(operation: string) {
  diagnostics.record('ssh-operation-failed', operation, 'OPERATION_FAILED');
}

function makeHostTarget(): SshHostTarget | null {
  const hostname = hostDraft.value.hostname.trim();
  const username = hostDraft.value.username.trim();
  const port = Number(hostDraft.value.port);
  const legacyHost = selectedLegacyHost.value;
  if (!hostname || !username || !selectedKeyHandleId.value || !Number.isInteger(port) || port < 1 || port > 65535) {
    connectionMessage.value = 'Enter a host, port, user, and select an SSH key.';
    return null;
  }
  const passphrase = selectedSshKey.value?.passphraseRequired ? legacyKeyPassphrase.value : undefined;
  if (legacyHost && legacyHost.keyHandleId === selectedKeyHandleId.value) {
    return makeLegacySshHostTarget(legacyHost, passphrase ?? '');
  }
  return {
    hostId: legacyHost ? String(legacyHost.id) : `${username}@${hostname}:${port}`,
    hostname,
    port,
    username,
    credential: keyManager.keyCredential(selectedKeyHandleId.value, passphrase),
  };
}

function selectLegacyHost(): void {
  const host = selectedLegacyHost.value;
  legacyKeyPassphrase.value = '';
  if (!host) return;
  hostDraft.value = {
    hostname: host.hostname,
    port: String(host.port),
    username: host.username,
  };
  selectedKeyHandleId.value = host.keyHandleId ?? '';
  if (!host.keyHandleId) {
    connectionMessage.value = 'This saved host key is not available in Android secure storage. Open SSH keys to import it again.';
  }
}

function clearLegacyHostSelection(): void {
  selectedLegacyHostId.value = '';
  selectedSyncedHostName.value = '';
  legacyKeyPassphrase.value = '';
}

function refreshSyncedAccountHosts(): void {
  syncedAccountHosts.value = (androidSync().accountHosts() ?? []).map((host) => ({
    name: host.name,
    hostname: host.hostname,
    port: typeof host.port === 'number' && Number.isInteger(host.port) && host.port > 0 && host.port < 65536 ? host.port : 22,
    user: typeof host.user === 'string' ? host.user : '',
  }));
  if (!syncedAccountHosts.value.some((host) => host.name === selectedSyncedHostName.value)) selectedSyncedHostName.value = '';
}

/** Fill the form from a synced host; its SSH key is chosen on this phone. */
function selectSyncedHost(): void {
  const host = syncedAccountHosts.value.find((candidate) => candidate.name === selectedSyncedHostName.value);
  if (!host) return;
  selectedLegacyHostId.value = '';
  legacyKeyPassphrase.value = '';
  hostDraft.value = { hostname: host.hostname, port: String(host.port), username: host.user };
  connectionMessage.value = selectedKeyHandleId.value ? '' : `Choose an SSH key on this phone to connect to ${host.name}.`;
}

function selectSshKey(handleId: string, stay = false): void {
  selectedKeyHandleId.value = handleId;
  if (selectedLegacyHost.value?.keyHandleId !== handleId) selectedLegacyHostId.value = '';
  legacyKeyPassphrase.value = '';
  connectionMessage.value = '';
  if (navigation.route === 'keys' && !stay) navigation.back();
}

let authorizedKeyRequestSequence = 0;
// #3021: install a stored key's public line on the live host over the
// already-authenticated connection's exec channel.
// The host the live controller dialed, so the confirmation names the
// connection the write goes to, not the editable host form.
const dialedHost = ref<DialedHost | null>(null);
const sshKeyHostInstall = computed<SshKeyHostInstallTarget | null>(() => {
  if (!hasActiveConnection.value) return null;
  const live = liveAuthorizedKeyInstallHost(connectionSnapshot.value, dialedHost.value);
  if (!live) return null;
  return {
    hostLabel: live.hostLabel,
    install: (publicKey: string) => {
      authorizedKeyRequestSequence += 1;
      return installPublicKeyOnHost(sshCapability, live.connection, publicKey,
        `authorized-key-${Date.now()}-${authorizedKeyRequestSequence}`);
    },
  };
});

function openKeyManagement(): void {
  navigation.open('keys');
}

async function refreshSshKeys(): Promise<void> {
  if (Capacitor.getPlatform() !== 'android') {
    sshKeys.value = [];
    sshKeyLoadError.value = 'SSH key storage is available in the Android app.';
    return;
  }
  try {
    sshKeys.value = await keyManager.list();
    sshKeyLoadError.value = '';
    importedLegacyHosts.value = await readImportedLegacyHosts();
    if (selectedLegacyHostId.value) {
      const host = importedLegacyHosts.value.find((candidate) => String(candidate.id) === selectedLegacyHostId.value);
      selectedKeyHandleId.value = host?.keyHandleId ?? '';
      if (!host?.keyHandleId) legacyKeyPassphrase.value = '';
    }
    if (selectedKeyHandleId.value && !sshKeys.value.some((key) => key.handleId === selectedKeyHandleId.value)) {
      selectedKeyHandleId.value = '';
    }
  } catch (error) {
    sshKeyLoadError.value = error instanceof Error ? error.message : 'SSH keys could not be loaded.';
  }
}

async function connectHost() {
  if (migrationBlocksConnection.value) return;
  const host = makeHostTarget();
  if (!host) return;
  rememberDiagnosticTerms([host.hostname, host.username, host.hostId,
    ...importedLegacyHosts.value.flatMap((saved) => [saved.name, saved.hostname, saved.username])]);
  const passphrase = host.credential.kind === 'key-handle' ? host.credential.passphrase ?? undefined : undefined;
  pendingTrustPassphrase = passphrase;
  legacyKeyPassphrase.value = '';
  await closeController();
  resourceSnapshot.value = null;
  resourceSnapshotStatus.value = 'unverified';
  connectionMessage.value = '';
  terminal.value?.clear();
  const next = new ConnectionController({ trustStore });
  bindController(next);
  dialedHost.value = { hostId: host.hostId, username: host.username, hostname: host.hostname, port: host.port };
  let result;
  try {
    result = await next.connect(host);
  } catch (error) {
    pendingTrustPassphrase = undefined;
    recordFailure('ssh-bridge-failed', 'connect', error);
    connectionMessage.value = error instanceof Error ? error.message : String(error);
    return;
  }
  if (result.ok) {
    pendingTrustPassphrase = undefined;
    await refreshSessions();
    navigateHomeSurface('connected');
  }
  else if (next.getSnapshot().phase !== 'awaiting-trust') {
    pendingTrustPassphrase = undefined;
    recordOperationFailure('connect');
    connectionMessage.value = result.message;
  }
}

const reconnectRequested = ref(false);

/** Explicit Reconnect after a lost connection: same host, same selected session (core controller). */
async function reconnectHost() {
  const active = controller;
  if (!active || reconnectRequested.value) return;
  reconnectRequested.value = true;
  try {
    await active.reconnect();
  } catch (error) {
    recordFailure('ssh-bridge-failed', 'connect', error);
    connectionMessage.value = error instanceof Error ? error.message : String(error);
  } finally {
    reconnectRequested.value = false;
  }
}

function decideHostKey(choice: HostKeyTrustChoice) {
  const decision = trustDecision.value;
  if (!decision) return;
  const answer = hostKeyAnswer(decision, choice);
  if (answer === 'reject') void rejectHostKey(hostKeyRefusalMessage(decision));
  else void acceptHostKey(answer === 'accept-always');
}

async function acceptHostKey(persist = true) {
  const active = controller;
  if (!active) return;
  connectionMessage.value = '';
  let result;
  try {
    result = await active.acceptPresentedHostKey({ passphrase: pendingTrustPassphrase, persist });
  } catch (error) {
    pendingTrustPassphrase = undefined;
    recordFailure('ssh-bridge-failed', 'accept-host-key', error);
    connectionMessage.value = error instanceof Error ? error.message : String(error);
    return;
  }
  pendingTrustPassphrase = undefined;
  if (result.ok) {
    await refreshSessions();
    navigateHomeSurface('connected');
  }
  else if (active.getSnapshot().phase !== 'awaiting-trust') {
    recordOperationFailure('accept-host-key');
    connectionMessage.value = result.message;
  }
}

async function refreshSessions() {
  const active = controller;
  if (!active) return;
  connectionMessage.value = '';
  const result = await active.refreshSessions().catch((error: unknown) => {
    recordFailure('ssh-bridge-failed', 'refresh-sessions', error);
    connectionMessage.value = error instanceof Error ? error.message : String(error);
    return null;
  });
  if (result && !result.ok) {
    recordOperationFailure('refresh-sessions');
    connectionMessage.value = result.message;
  }
}

async function createSession() {
  const active = controller;
  const name = sessionName.value.trim();
  if (!active || !name) return;
  connectionMessage.value = '';
  const result = await active.createSession(name).catch((error: unknown) => {
    recordFailure('ssh-bridge-failed', 'create-session', error);
    connectionMessage.value = error instanceof Error ? error.message : String(error);
    return null;
  });
  if (result && result.ok) await refreshSessions();
  else if (result && !result.ok) {
    recordOperationFailure('create-session');
    connectionMessage.value = result.message;
  }
}

async function attachSession(session: SessionRow) {
  const active = controller;
  if (!active) return;
  const attachEpoch = ++terminalAttachEpoch.value;
  terminalAttachFocusWindowEpoch.value = attachEpoch;
  try {
    terminal.value?.clear();
    const result = await active.attachSession(session).catch((error: unknown) => {
      recordFailure('ssh-bridge-failed', 'attach-session', error);
      connectionMessage.value = error instanceof Error ? error.message : String(error);
      return null;
    });
    // This screen shows one terminal at a time: the session it left gives up
    // its PTY (the controller would otherwise keep it open, #2955). Detached
    // after the attach so the selection never reads empty in between.
    const left = active.getSnapshot().terminals.filter((row) => !sameSessionRow(row, session));
    for (const row of left) await active.detachSession(row).catch(() => undefined);
    if (attachEpoch !== terminalAttachEpoch.value) return;
    if (result && !result.ok) {
      recordOperationFailure('attach-session');
      connectionMessage.value = result.message;
      return;
    }
    if (result?.ok) {
      navigateHomeSurface('session-attached');
      await nextTick();
      // Reusing a visible terminal can leave ResizeObserver silent when two PTYs
      // have identical geometry. Explicitly resize each newly attached PTY.
      const size = await terminal.value?.fit();
      if (size) {
        const resizeGate = waitForAttachAutofocusTestGate('attach-resize');
        if (resizeGate) await resizeGate;
        await resizeTerminal(size, attachEpoch);
      }
      await nextTick();
      const focusGate = waitForAttachAutofocusTestGate('attach-final-focus');
      if (focusGate) await focusGate;
      if (terminalAutofocusAllowed.value) terminal.value?.focus();
    }
  } finally {
    if (terminalAttachFocusWindowEpoch.value === attachEpoch) terminalAttachFocusWindowEpoch.value = 0;
  }
}

async function sendTerminalBytes(bytes: Uint8Array) {
  const active = controller;
  if (!active || !isLive.value) return;
  const attachEpoch = terminalAttachEpoch.value;
  terminalInputPending.value += 1;
  try {
    const selected = active.getSnapshot().selectedSession;
    if (!selected) return;
    const result = await active.writeTerminalBytes(selected, bytes);
    if (attachEpoch !== terminalAttachEpoch.value || (!result.ok && result.reason === 'superseded')) return;
    if (!result.ok) {
      terminalInputFailureCount.value += 1;
      recordOperationFailure('send-terminal-input');
      connectionMessage.value = result.message;
    } else {
      terminalInputAckCount.value += 1;
    }
  } catch (error: unknown) {
    if (attachEpoch !== terminalAttachEpoch.value) return;
    terminalInputFailureCount.value += 1;
    recordFailure('ssh-bridge-failed', 'send-terminal-input', error);
    connectionMessage.value = error instanceof Error ? error.message : String(error);
  } finally {
    terminalInputPending.value -= 1;
  }
}

function captureAppTerminalInputChunk(data: string, attachEpoch: number) {
  const smokeEvidence = window as ComposerSmokeEvidenceWindow;
  if (!smokeEvidence.__ps2857CaptureTerminalEvidence || data.length === 0) return;

  const chunks = smokeEvidence.__ps2857AppTerminalInputChunks
    ?? (smokeEvidence.__ps2857AppTerminalInputChunks = []);
  const capturedChars = smokeEvidence.__ps2857AppTerminalInputChars ?? 0;
  const remainingChars = MAX_APP_TERMINAL_INPUT_EVIDENCE_CHARS - capturedChars;
  if (chunks.length >= MAX_APP_TERMINAL_INPUT_EVIDENCE_CHUNKS || remainingChars <= 0) {
    smokeEvidence.__ps2857AppTerminalInputDroppedChunks =
      (smokeEvidence.__ps2857AppTerminalInputDroppedChunks ?? 0) + 1;
    return;
  }

  const selected = connectionSnapshot.value?.selectedSession;
  const captured = data.slice(0, remainingChars);
  chunks.push({
    text: captured,
    sessionName: selected?.name ?? '',
    sessionId: selected?.id ?? '',
    sessionTag: selected?.tag ?? '',
    attachEpoch,
    phase: currentPhase.value,
  });
  smokeEvidence.__ps2857AppTerminalInputChars = capturedChars + captured.length;
  if (captured.length < data.length) {
    smokeEvidence.__ps2857AppTerminalInputDroppedChunks =
      (smokeEvidence.__ps2857AppTerminalInputDroppedChunks ?? 0) + 1;
  }
}

async function sendTerminalInput(data: string) {
  captureAppTerminalInputChunk(data, terminalAttachEpoch.value);
  await sendTerminalBytes(new TextEncoder().encode(data));
}

function sendMobileHotkey(bytes: Uint8Array, key: TerminalKeyId) {
  if (!mobileHotkeysEnabled.value) return;
  const evidenceWindow = window as ComposerSmokeEvidenceWindow;
  if (evidenceWindow.__ps2857CaptureTerminalEvidence) {
    evidenceWindow.__ps2884HotkeyWrites = [
      ...(evidenceWindow.__ps2884HotkeyWrites ?? []),
      { key, bytes: Array.from(bytes) },
    ];
  }
  // Keep core-generated key sequences in one PTY write, including Ctrl+C/D holds.
  void sendTerminalBytes(bytes);
}

function keepMobileHotkeysImeOpen() {
  if (!mobileHotkeysEnabled.value) return;
  document.querySelector<HTMLTextAreaElement>('[data-testid="prompt-draft"]')?.focus({ preventScroll: true });
}

async function writeComposerPty(bytes: Uint8Array): Promise<PtyWriteAcknowledgement> {
  const snippetEvidence = window as SnippetEvidenceWindow;
  if (snippetEvidence.__ps2885CaptureSnippetEvidence) {
    snippetEvidence.__ps2885ComposerWriteCount = (snippetEvidence.__ps2885ComposerWriteCount ?? 0) + 1;
    snippetEvidence.__ps2885ComposerLastWriteHex = Array.from(bytes, (value) => value.toString(16).padStart(2, '0')).join('');
  }
  const active = controller;
  if (!active) return { ok: false, message: 'No terminal is open.' };
  const selected = active.getSnapshot().selectedSession;
  if (!selected) return { ok: false, message: 'No terminal is open.' };
  const result = await active.writeTerminalBytes(selected, bytes);
  if (result.ok) terminal.value?.scrollToBottom();
  return result.ok ? { ok: true } : { ok: false, message: result.message };
}

async function insertInlineDictationText(targetKey: string, text: string): Promise<boolean> {
  const active = controller;
  const attachEpoch = terminalAttachEpoch.value;
  if (!active || !isLive.value || targetKey !== inlineDictationTargetKey.value) return false;
  terminalInputPending.value += 1;
  try {
    // Partials stay in the dock. The controller sanitizes control characters,
    // and only its explicit Stop path calls this function with final text.
    const selected = active.getSnapshot().selectedSession;
    if (!selected) return false;
    const result = await active.writeTerminalBytes(selected, new TextEncoder().encode(text));
    if (attachEpoch !== terminalAttachEpoch.value
      || targetKey !== inlineDictationTargetKey.value
      || active !== controller) return false;
    if (!result.ok) {
      terminalInputFailureCount.value += 1;
      recordOperationFailure('insert-inline-dictation');
      connectionMessage.value = result.message;
      return false;
    }
    terminalInputAckCount.value += 1;
    // Return the next physical keyboard input to xterm. Its focus also keeps
    // the compact keyboard layout active while the native IME remains open.
    terminal.value?.focus();
    terminal.value?.scrollToBottom();
    return true;
  } catch (error) {
    if (attachEpoch === terminalAttachEpoch.value && targetKey === inlineDictationTargetKey.value) {
      terminalInputFailureCount.value += 1;
      recordFailure('ssh-bridge-failed', 'insert-inline-dictation', error);
      connectionMessage.value = error instanceof Error ? error.message : String(error);
    }
    return false;
  } finally {
    terminalInputPending.value -= 1;
  }
}

function updateInlineDictationState(next: InlineDictationState) {
  inlineDictationState.value = next;
}

async function resizeTerminal(size: TerminalResizeRequest, attachEpochForAck?: number) {
  if (!controller || !isLive.value) return;
  const requestId = size.requestId ?? allocateTerminalResizeRequestId();
  const attachEpoch = terminalAttachEpoch.value;
  terminalResizeStatus.value = `${size.cols} × ${size.rows} (local fit)`;
  terminalResizePending.value += 1;
  try {
    const selected = controller.getSnapshot().selectedSession;
    const result = selected
      ? await controller.resizeTerminal(selected, size.cols, size.rows).catch((error: unknown) => {
        recordFailure('ssh-bridge-failed', 'resize-terminal', error);
        connectionMessage.value = error instanceof Error ? error.message : String(error);
        return null;
      })
      : null;
    const evidenceWindow = window as ComposerSmokeEvidenceWindow;
    if (evidenceWindow.__ps2884CaptureResizeFitEvidence) {
      const events = evidenceWindow.__ps2884ResizeAckEvents
        ?? (evidenceWindow.__ps2884ResizeAckEvents = []);
      events.push({
        atMs: Math.round(performance.now() * 10) / 10,
        marker: evidenceWindow.__ps2884ResizeFitMarker ?? 'unmarked',
        requestId,
        cols: size.cols,
        rows: size.rows,
        attachEpoch,
        result: result === null ? 'missing' : result.ok ? 'accepted' : 'failed',
      });
      if (events.length > 100) events.shift();
    }
    if (attachEpoch !== terminalAttachEpoch.value || (result && !result.ok && result.reason === 'superseded')) return;
    terminalResizeStatus.value = result?.ok
      ? `${size.cols} × ${size.rows} accepted by SSH`
      : `resize failed: ${result && 'message' in result ? result.message : 'native bridge error'}`;
    if (result?.ok) {
      terminalResizeAckCount.value += 1;
      if (attachEpochForAck === terminalAttachEpoch.value) terminalAttachResizeAckEpoch.value = attachEpochForAck;
    }
    else {
      terminalResizeFailure.value = { ...size, requestId };
      terminalResizeFailureCount.value += 1;
      if (result) recordOperationFailure('resize-terminal');
    }
  } finally {
    terminalResizePending.value -= 1;
  }
}

async function closeController() {
  const active = controller;
  const activePortForwardController = portForwardController;
  portForwardController = null;
  portForwardHostId = null;
  portForwardConnectionKey = null;
  portRequestEpoch += 1;
  usageRequestEpoch += 1;
  portLoading.value = false;
  portError.value = '';
  portSnapshot.value = emptyPortSnapshot();
  portAutoEnabled.value = true;
  portManualDesiredPorts.value = [];
  portDisabledPorts.value = [];
  portScanCount.value = 0;
  usageLoading.value = false;
  usageRecords.value = [];
  usageError.value = '';
  usageLastReadAt.value = null;
  removeControllerSnapshot?.();
  removeControllerSnapshot = undefined;
  removeTerminalOutput?.();
  removeTerminalOutput = undefined;
  controller = null;
  // Tunnels use the active SSH generation. Close them while that connection
  // is still valid, then close the connection itself as a final native guard.
  if (activePortForwardController) await activePortForwardController.closeAll().catch(() => undefined);
  if (active) await active.close().catch(() => undefined);
  connectionSnapshot.value = null;
}

async function disconnectHost() {
  navigateHomeSurface('disconnected');
  await closeController();
  const requestId = `ui-close-${Date.now()}`;
  resourceSnapshot.value = null;
  resourceSnapshotStatus.value = 'pending';
  connectionMessage.value = '';
  try {
    resourceSnapshot.value = await sshCapability.resourceSnapshot(requestId);
    resourceSnapshotStatus.value = 'verified';
  } catch (error) {
    resourceSnapshotStatus.value = 'failed';
    const sshError = readSshError(error);
    diagnostics.record('resource-snapshot-failed', 'resource-snapshot', sshError.code);
    connectionMessage.value = `Could not check the connection (${sshError.code}): ${sshError.message}`;
  }
}

async function rejectHostKey(message = 'Host key was not trusted. No SSH connection remains open.') {
  pendingTrustPassphrase = undefined;
  legacyKeyPassphrase.value = '';
  connectionMessage.value = message;
  await disconnectHost();
}

function retryDataImport() {
  void retryInstalledDataMigration({ requestSettingsReload: requestImportedSettingsReload }).then((settingsWritten) => {
    if (reloadAfterSettingsImport(settingsWritten)) return;
    void loadImportedLegacyHosts();
    void refreshSshKeys();
  });
}

async function loadImportedLegacyHosts(): Promise<void> {
  try {
    importedLegacyHosts.value = await readImportedLegacyHosts();
    await refreshSshKeys();
  } catch (error) {
    installedDataMigrationState.status = 'failed';
    installedDataMigrationState.error = error instanceof Error
      ? error.message
      : 'Saved hosts could not be loaded from the migration record.';
  }
}

async function importLegacySnippets(): Promise<void> {
  if (Capacitor.getPlatform() !== 'android') return;
  if (installedDataMigrationState.status !== 'complete' && installedDataMigrationState.status !== 'partial') return;
  try {
    const record = await createImportPersistence().readRecord();
    if (!record || !['complete', 'partial', 'empty'].includes(record.status)) return;
    hostSnippets.importLegacySnapshot(record.snapshot);
  } catch (error) {
    hostSnippets.error = error instanceof Error
      ? `Legacy command chips could not be loaded: ${error.message}`
      : 'Legacy command chips could not be loaded from the installed-data migration record.';
  }
}

// This shell applies imported settings by reloading once per startup session.
function requestImportedSettingsReload(settingsWritten: boolean): boolean {
  return shouldReloadForImportedSettings(settingsWritten);
}

function reloadAfterSettingsImport(settingsWritten: boolean): boolean {
  if (!settingsWritten) {
    try {
      window.sessionStorage.removeItem(SETTINGS_RELOAD_SESSION_KEY);
    } catch {
      // A later launch can still use the imported value from local storage.
    }
    return false;
  }
  // runInstalledDataMigration decided this together with the settled status.
  if (!installedDataMigrationState.reloadPending) return false;
  window.location.reload();
  return true;
}

onMounted(() => {
  refreshSyncedAccountHosts();
  // Packaged instrumentation opts in through isolated app storage before
  // launch. This exposes the real core-backed policy with fake platform
  // effects; the production settings screen has no sync action or network
  // adapter until native OAuth, encryption, and secure storage are ready.
  try {
    if (Capacitor.isNativePlatform()
      && window.localStorage.getItem(SETTINGS_SYNC_PROBE_STORAGE_KEY) === 'enabled') {
      (window as SettingsSyncProbeWindow).__ps2852RunSettingsSync = runSyncRound;
    }
  } catch {
    // A denied browser-storage read simply leaves the instrumentation probe off.
  }

  diagnostics.record('app-started', 'startup', 'OK');
  let keyboardInsetsStateSync: ReturnType<typeof createKeyboardInsetsStateSync> | undefined;
  const updateKeyboardViewport = () => {
    if (Capacitor.getPlatform() !== 'android') return;
    if (nativeKeyboardInsetsSupported) {
      keyboardInsetsStateSync?.refresh();
      return;
    }
    const viewportHeight = window.visualViewport?.height ?? window.innerHeight;
    const screenHeight = window.screen.height;
    keyboardVisible.value = screenHeight - viewportHeight > 150;
  };
  const applyKeyboardInsets = (state: KeyboardInsetsState) => {
    if (!state.supported || !Number.isFinite(state.safeBottomDp) || state.safeBottomDp < 0) {
      nativeKeyboardInsetsSupported = false;
      updateKeyboardViewport();
      return;
    }
    nativeKeyboardInsetsSupported = true;
    keyboardVisible.value = state.imeVisible;
    document.documentElement.style.setProperty(
      '--safe-area-inset-bottom',
      `${state.imeVisible ? 0 : state.safeBottomDp}px`,
    );
  };
  keyboardInsetsStateSync = createKeyboardInsetsStateSync(
    () => keyboardInsets.getState(),
    applyKeyboardInsets,
    (error) => console.error('Could not refresh the Android IME insets.', error),
  );
  disposeKeyboardInsetsStateSync = keyboardInsetsStateSync.dispose;
  updateKeyboardViewport();
  window.visualViewport?.addEventListener('resize', updateKeyboardViewport);
  window.addEventListener('resize', updateKeyboardViewport);
  removeKeyboardViewportListeners = () => {
    window.visualViewport?.removeEventListener('resize', updateKeyboardViewport);
    window.removeEventListener('resize', updateKeyboardViewport);
  };

  if (Capacitor.getPlatform() === 'android') {
    void keyboardInsets.addListener('imeInsetsChanged', () => {
      // The event is an invalidation signal. A queued event payload may be
      // older than the native IME state by the time JS handles it.
      keyboardInsetsStateSync?.refresh();
    }).then(async (listener) => {
      removeKeyboardInsetsListener = () => listener.remove();
      keyboardInsetsStateSync?.refresh();
    }).catch((error: unknown) => {
      console.error('Could not register the Android IME inset listener.', error);
    });
  }

  void runInstalledDataMigration({ requestSettingsReload: requestImportedSettingsReload }).then((settingsWritten) => {
    if (reloadAfterSettingsImport(settingsWritten)) return;
    void loadImportedLegacyHosts();
    void importLegacySnippets();
  });
  if (Capacitor.isNativePlatform()) {
    const handleAppState = createAppLifecycleHandler({
      getController: () => controller,
      // Grace and reconnect-on-return are owned by the shared settings store (D42, #2861).
      getBackgroundGraceMs: () => sharedSettings.backgroundGraceMs,
      getReconnectOnReturn: () => sharedSettings.reconnectOnReturn,
      onError: (error) => {
        recordFailure('ssh-bridge-failed', 'lifecycle', error);
        connectionMessage.value = error instanceof Error ? error.message : String(error);
      },
    });
    void CapacitorApp.addListener('backButton', () => {
      backButtonEvents.value += 1;
      const activeElement = document.activeElement;
      const inputHasFocus = activeElement instanceof HTMLInputElement
        || activeElement instanceof HTMLTextAreaElement
        || activeElement instanceof HTMLSelectElement
        || (activeElement instanceof HTMLElement && activeElement.isContentEditable);
      const hotkeysHaveFocus = activeElement instanceof Element && isMobileHotkeysElement(activeElement);
      const viewportHeight = window.visualViewport?.height ?? window.innerHeight;
      const keyboardIsVisible = keyboardVisible.value || window.screen.height - viewportHeight > 120;
      if ((inputHasFocus || hotkeysHaveFocus) && keyboardIsVisible) {
        (activeElement as HTMLElement).blur();
        return;
      }
      if (mobilePromptComposerOpen.value || mobilePromptComposerInline.value) {
        setMobilePromptComposerOpen(false);
        return;
      }
      if (mobileHotkeysPaletteOpen.value) {
        mobileHotkeys.value?.closePalette();
        return;
      }
      switch (resolveAndroidBackDestination(
        navigation.canGoBack,
        homeSurface.value,
        !!connectionSnapshot.value,
        navigation.route !== 'home',
      )) {
        case 'navigation':
          if (navigation.canGoBack) navigation.back();
          else navigation.home();
          break;
        case 'workspace': navigateHomeSurface('back'); break;
        case 'minimize':
          void CapacitorApp.minimizeApp().catch((error: unknown) => {
            recordFailure('ssh-bridge-failed', 'lifecycle', error);
          });
          break;
      }
    }).then((listener) => {
      removeBackButton = () => listener.remove();
      backButtonReady.value = true;
    }).catch((error: unknown) => {
      console.error('Could not register the Android Back handler.', error);
    });
    void CapacitorApp.addListener('appStateChange', ({ isActive }) => {
      diagnostics.record(isActive ? 'app-foregrounded' : 'app-backgrounded', 'lifecycle', 'OK');
      handleAppState(isActive);
    }).then((listener) => {
      removeAppState = () => listener.remove();
    }).catch((error: unknown) => {
      console.error('Could not register the app background handler.', error);
    });
  }

  void verifyCurrentBuild(coreSourceRevision).then((verification) => {
    buildVerification.value = verification;
    diagnostics.record(verification.ok ? 'build-verified' : 'build-verification-failed', 'assets', verification.ok ? 'OK' : 'VERIFY_FAILED');
  }).catch((error: unknown) => {
    buildVerification.value = { ok: false, reason: error instanceof Error ? error.message : String(error) };
    recordFailure('build-verification-failed', 'assets', error);
  });
});

watchEffect(() => {
  const theme = activeTheme.value;
  const root = document.documentElement;
  root.dataset['theme'] = theme.id;
  root.style.colorScheme = theme.appearance;
  for (const [name, value] of Object.entries(theme.tokens)) root.style.setProperty(name, value);
  const fontVariables = fontCssVariables({
    monospaceFontFamily: null,
    terminalFontSize: appSettings.terminalFontSize,
    editorFontSize: 13,
  }, 'ui-monospace, monospace');
  for (const [name, value] of Object.entries(fontVariables)) root.style.setProperty(name, value);
  // Keep five measured xterm rows visible with the default 8px viewport inset
  // and a little room for Android/WebView font metric rounding.
  root.style.setProperty('--terminal-min-grid-height', `${Math.ceil(appSettings.terminalFontSize * 7.6 + 22)}px`);
});

watch(() => navigation.route, (route) => {
  if (route !== 'home') {
    // Leaving Home closes Prompt in both forms; the draft stays in its per-PTY
    // store and the returned Home shows the dock's Prompt launcher (#2908).
    mobilePromptComposerOpen.value = false;
    mobilePromptComposerInline.value = false;
  }
});

onBeforeUnmount(() => {
  removeKeyboardViewportListeners?.();
  disposeKeyboardInsetsStateSync?.();
  void removeKeyboardInsetsListener?.();
  void removeBackButton?.();
  void removeAppState?.();
  void closeController();
});
</script>

<template>
  <div
    class="app-shell"
    :data-route="navigation.route"
    :data-back-button-ready="backButtonReady"
    :data-back-button-events="backButtonEvents"
    :data-native-platform="Capacitor.getPlatform()"
    :data-keyboard-visible="keyboardVisible"
    :data-keyboard-composer-mode="keyboardComposerMode"
    :data-prompt-composer-open="mobilePromptComposerOpen"
    :data-prompt-composer-inline="mobilePromptComposerInline"
    :data-terminal-viewport-focused="terminalViewportHasFocus"
    :data-fast-keys-ctrl="mobileHotkeysPaletteOpen && mobileHotkeysPage === 'ctrl'"
    :data-fast-keys-main="mobileHotkeysPaletteOpen && mobileHotkeysPage === 'main'"
    :data-inline-dictation-status="inlineDictationStatusVisible"
    :data-ssh-phase="currentPhase"
    :data-home-surface="homeSurface"
    :data-ssh-connection-id="connectionSnapshot?.connectionId ?? ''"
    :data-ssh-generation-id="connectionSnapshot?.generationId ?? ''"
    :data-ssh-selected-session="connectionSnapshot?.selectedSession?.name ?? ''"
    :data-ssh-selected-session-id="connectionSnapshot?.selectedSession?.id ?? ''"
    :data-ssh-selected-workspace="connectionSnapshot?.selectedSession?.workspace ?? ''"
    :data-ssh-selected-tag="connectionSnapshot?.selectedSession?.tag ?? ''"
    :data-ssh-selected-agent-kind="selectedSessionRow?.agent ?? ''"
    :data-ssh-selected-agent="selectedSessionAgentPresentation.identity?.label ?? ''"
    :data-ssh-selected-agent-state="selectedSessionAgentPresentation.state ?? ''"
    :data-ssh-selected-agent-state-source="selectedSessionRow?.agentStateSource ?? ''"
    :data-ssh-retry-attempt="connectionSnapshot?.retryAttempt ?? 0"
    :data-ssh-terminal-input-pending="terminalInputPending"
    :data-ssh-terminal-input-acks="terminalInputAckCount"
    :data-ssh-terminal-input-failures="terminalInputFailureCount"
    :data-ssh-terminal-resize-pending="terminalResizePending"
    :data-ssh-terminal-resize-acks="terminalResizeAckCount"
    :data-ssh-terminal-resize-failures="terminalResizeFailureCount"
    :data-ssh-attach-epoch="terminalAttachEpoch"
    :data-ssh-attach-focus-pending="terminalAttachFocusWindowEpoch !== 0"
    :data-ssh-attach-prompt-focus-epoch="terminalAttachPromptFocusEpoch"
    :data-ssh-attach-resize-ack-epoch="terminalAttachResizeAckEpoch"
    :data-ssh-terminal-autofocus-allowed="terminalAutofocusAllowed"
    @focusin="recordFocusedElement"
    @focusout="recordFocusAfterBlur"
    @pointerdown.capture="recordAttachComposerPointer"
    :data-migration-status="publishedMigrationStatus"
  >
    <div id="prompt-composer-portal" aria-live="off"></div>
    <header class="app-bar" :class="{ 'app-bar--workspace': !!connectionSnapshot }">
      <template v-if="navigation.route === 'home' && connectionSnapshot">
        <div class="session-context" aria-live="polite">
          <AppIcon class="brand-mark" name="terminal" />
          <div class="session-context__copy">
            <span class="session-context__title">{{ connectionSnapshot.selectedSession?.name || 'PocketShell' }}</span>
            <span class="session-context__host">{{ hostDraft.username }}@{{ hostDraft.hostname }}</span>
            <SessionAgentMetadata
              v-if="selectedSessionRow"
              class="session-context__agent"
              :session="selectedSessionRow"
            />
          </div>
        </div>
        <nav class="workspace-navigation" aria-label="Session destinations">
          <button
            class="workspace-nav-button"
            type="button"
            aria-label="Host connection"
            title="Host connection"
            data-testid="open-connection"
            :aria-current="homeSurface === 'connection' ? 'page' : undefined"
            @click="navigateHomeSurface('open-connection')"
          ><AppIcon name="home" /></button>
          <button
            class="workspace-nav-button"
            type="button"
            aria-label="Sessions"
            title="Sessions"
            data-testid="open-sessions"
            :aria-current="homeSurface === 'sessions' ? 'page' : undefined"
            :disabled="!isConnected"
            @click="navigateHomeSurface('open-sessions')"
          ><AppIcon name="folder" /></button>
          <button
            v-if="isConnected"
            class="workspace-nav-button workspace-nav-button--disconnect"
            type="button"
            aria-label="Disconnect SSH"
            title="Disconnect"
            data-testid="ssh-disconnect"
            @click="disconnectHost"
          ><AppIcon name="close" /></button>
          <button
            class="workspace-nav-button workspace-nav-button--labelled"
            type="button"
            aria-label="SSH keys"
            title="SSH keys"
            data-testid="open-ssh-keys"
            @click="openKeyManagement"
          ><KeyIcon /><span class="nav-button-label" aria-hidden="true">Keys</span></button>
          <button
            class="workspace-nav-button"
            type="button"
            aria-label="Settings"
            title="Settings"
            @click="openSettings"
          ><AppIcon name="settings" /></button>
        </nav>
      </template>
      <template v-else>
        <button class="brand-button" type="button" aria-label="PocketShell home" @click="navigation.home()">
          <AppIcon class="brand-mark" name="terminal" />
          <span class="wordmark">PocketShell</span>
        </button>
        <div class="app-bar-actions">
          <button
            v-if="navigation.route === 'home'"
            class="icon-button"
            type="button"
            aria-label="Settings"
            title="Settings"
            @click="openSettings"
          >
            <AppIcon name="settings" />
          </button>
          <button
            v-if="navigation.route === 'home'"
            class="icon-button labelled-icon-button"
            type="button"
            aria-label="SSH keys"
            title="SSH keys"
            data-testid="open-ssh-keys"
            @click="openKeyManagement"
          >
            <KeyIcon />
            <span class="nav-button-label" aria-hidden="true">Keys</span>
          </button>
          <button
            v-else
            class="icon-button back-button"
            type="button"
            aria-label="Back"
            title="Back"
            @click="navigation.back()"
          >
            <AppIcon name="arrow-left" />
          </button>
        </div>
      </template>
    </header>

    <!-- Build identity is a test hook here, not chrome (#3023): the user reads
         it in Settings → About. Only a failed integrity check is shown. -->
    <span
      v-if="!connectionSnapshot"
      hidden
      data-testid="build-status"
      :data-state="buildStatusTone"
      :data-core-revision="coreSourceRevision"
      :data-bundle-hash="bundleHash"
      :data-failure-reason="buildFailureReason"
    />
    <BuildIntegrityAlert v-if="buildStatusTone === 'error'" />

    <section
      v-if="publishedMigrationStatus === 'failed' || publishedMigrationStatus === 'partial'"
      class="migration-error"
      role="alert"
      data-testid="installed-data-migration-error"
    >
      <div class="migration-error__copy">
        <strong>{{ installedDataMigrationState.status === 'partial' ? 'Some installed data needs attention' : 'Installed data needs attention' }}</strong>
        <p>{{ installedDataMigrationState.error }} Your original Android data remains in place.</p>
      </div>
      <button
        v-if="installedDataMigrationState.status === 'failed' || installedDataMigrationState.status === 'partial'"
        class="small-action"
        type="button"
        data-testid="retry-installed-data-migration"
        :disabled="installedDataMigrationState.retrying"
        @click="retryDataImport"
      >
        {{ installedDataMigrationState.retrying ? 'Checking…' : installedDataMigrationState.status === 'partial' ? 'Check installed data again' : 'Retry import' }}
      </button>
    </section>

    <section
      v-if="navigation.route === 'home' && currentPhase === 'lost' && connectionSnapshot?.hostId"
      class="migration-error"
      role="alert"
      data-testid="reconnect-banner"
    >
      <div class="migration-error__copy">
        <strong>Disconnected</strong>
        <p>{{ connectionSnapshot.error || 'The connection closed.' }}</p>
      </div>
      <button class="small-action" type="button" data-testid="ssh-reconnect" :disabled="reconnectRequested" @click="reconnectHost">
        {{ reconnectRequested ? 'Reconnecting…' : 'Reconnect' }}
      </button>
    </section>

    <main
      v-if="connectionSnapshot || navigation.route === 'home'"
      class="screen-content home-screen"
      :class="{ 'home-screen--workspace': !!connectionSnapshot }"
      :style="navigation.route === 'home' ? undefined : (retainedHomeScreenStyle ?? hiddenHomeScreenStyle)"
      :aria-hidden="navigation.route !== 'home'"
      :inert="navigation.route !== 'home'"
    >
      <section v-if="homeSurface === 'connection'" class="connection-stack">
      <section class="panel host-panel" aria-labelledby="hosts-title">
        <div class="panel-heading">
          <div>
            <h1 id="hosts-title">SSH host</h1>
          </div>
          <span class="state-tag" :class="isConnected ? 'state-tag--success' : 'state-tag--muted'" data-testid="connection-state" :data-phase="currentPhase">
            {{ connectionStateText }}
          </span>
        </div>

        <div class="host-fields">
          <label v-if="importedLegacyHosts.length > 0" class="form-field host-field-name">
            <span>Saved host from your previous install</span>
            <select v-model="selectedLegacyHostId" data-testid="legacy-host-select" @change="selectLegacyHost">
              <option value="">Choose a saved host</option>
              <option v-for="host in importedLegacyHosts" :key="host.id" :value="String(host.id)">
                {{ host.name || host.hostname }} · {{ host.username }}@{{ host.hostname }}:{{ host.port }} · {{ host.keyName }}
              </option>
            </select>
          </label>
          <label v-if="syncedAccountHosts.length > 0" class="form-field host-field-name">
            <span>Synced host from your account</span>
            <select v-model="selectedSyncedHostName" data-testid="synced-host-select" @change="selectSyncedHost">
              <option value="">Choose a synced host</option>
              <option v-for="host in syncedAccountHosts" :key="host.name" :value="host.name">
                {{ host.name }} · {{ host.user ? `${host.user}@` : '' }}{{ host.hostname }}:{{ host.port }}
              </option>
            </select>
          </label>
          <label class="form-field host-field-name">
            <span>Host name or IP</span>
            <input v-model="hostDraft.hostname" data-testid="ssh-host" autocomplete="off" autocapitalize="none" placeholder="dev.example.com" @input="clearLegacyHostSelection" />
          </label>
          <label class="form-field host-field-port">
            <span>Port</span>
            <input v-model="hostDraft.port" data-testid="ssh-port" type="number" inputmode="numeric" min="1" max="65535" @input="clearLegacyHostSelection" />
          </label>
          <label class="form-field host-field-name">
            <span>User</span>
            <input v-model="hostDraft.username" data-testid="ssh-username" autocomplete="username" autocapitalize="none" placeholder="alexey" @input="clearLegacyHostSelection" />
          </label>
          <label class="form-field host-field-key">
            <span>SSH key</span>
            <select v-model="selectedKeyHandleId" data-testid="ssh-key-selection" :aria-describedby="selectedSshKey ? 'selected-ssh-key-details' : undefined" @change="clearLegacyHostSelection">
              <option value="">Choose a stored SSH key</option>
              <option v-for="key in sshKeys" :key="key.handleId" :value="key.handleId">
                {{ key.label }} · {{ key.fingerprintSha256.slice(7, 15) }}
              </option>
            </select>
            <span v-if="selectedSshKey" id="selected-ssh-key-details" class="host-key-details" data-testid="selected-ssh-key-details">
              <span>{{ selectedSshKey.algorithm }}</span>
              <code data-testid="selected-ssh-key-fingerprint">{{ selectedSshKey.fingerprintSha256 }}</code>
            </span>

            <span v-if="sshKeyLoadError" class="host-field-help" role="alert" data-testid="ssh-key-load-error">{{ sshKeyLoadError }}</span>
          </label>
          <label v-if="selectedSshKey?.passphraseRequired" class="form-field host-field-key">
            <span>SSH key passphrase · used for this connection only</span>
            <input v-model="legacyKeyPassphrase" data-testid="legacy-key-passphrase" type="password" autocomplete="off" />
          </label>
        </div>
        <!-- #3021: with no stored key the form cannot connect, so adding one is
             the primary action here rather than a small text hint. -->
        <div v-if="sshKeys.length === 0 && !sshKeyLoadError" class="host-key-cta" data-testid="ssh-key-cta">
          <div class="host-key-cta__copy">
            <KeyIcon :size="22" />
            <span><strong>Add an SSH key to connect</strong><small>Paste a private key, import a key file, or generate a new key.</small></span>
          </div>
          <button class="action-button" type="button" data-testid="add-ssh-key" @click="openKeyManagement">
            <KeyIcon :size="18" /><span>Add a key</span>
          </button>
        </div>
        <div class="host-actions">
          <button class="small-action" type="button" data-testid="manage-ssh-keys" @click="openKeyManagement">Manage keys</button>
          <button class="action-button" type="button" data-testid="ssh-connect" :disabled="isConnecting || migrationBlocksConnection" @click="connectHost">
            {{ isConnecting ? 'Connecting…' : 'Connect' }}
          </button>
        </div>

        <!-- The shared host-key card (#2953): the first-contact prompt, or the
             changed-key refusal with no trust action. -->
        <HostKeyTrustPrompt
          v-if="legacyHostKeyCard"
          :key="legacyHostKeyCard.request.fingerprintSha256"
          :request="legacyHostKeyCard.request"
          :trusted-fingerprint-sha256="legacyHostKeyCard.trustedFingerprintSha256"
          @decide="decideHostKey"
        />
        <p v-if="connectionMessage || connectionSnapshot?.error" class="connection-message" role="alert" data-testid="ssh-message">
          {{ connectionMessage || connectionSnapshot?.error }}
        </p>
        <p class="panel-footnote">Your private keys and trusted hosts are stored only on this phone.</p>
      </section>

      <!-- Native SSH resource counts after a close: a test hook, not chrome (#3023). -->
      <dl
        hidden
        data-testid="ssh-resources"
        :data-snapshot-state="resourceSnapshotStatus"
        :data-snapshot-request-id="resourceSnapshot?.requestId ?? ''"
      >
        <dd data-testid="ssh-resource-connections">{{ resourceSnapshot?.connections ?? 'Unverified' }}</dd>
        <dd data-testid="ssh-resource-ptys">{{ resourceSnapshot?.ptys ?? 'Unverified' }}</dd>
        <dd data-testid="ssh-resource-sftp">{{ resourceSnapshot?.sftpClients ?? 'Unverified' }}</dd>
        <dd data-testid="ssh-resource-forwards">{{ resourceSnapshot?.forwards ?? 'Unverified' }}</dd>
      </dl>
      </section>

      <section v-if="homeSurface === 'sessions'" class="panel workspace-panel" aria-labelledby="sessions-title">
        <div class="panel-heading">
          <div>
            <h2 id="sessions-title">Sessions</h2>
          </div>
          <div class="workspace-panel-actions">
            <button v-if="isConnected" class="small-action files-open-button" type="button" data-testid="open-files" @click="navigation.open('files')">Files</button>
            <button v-if="isConnected" class="small-action" type="button" data-testid="refresh-sessions" @click="refreshSessions">Refresh</button>
          </div>
        </div>
        <div v-if="!isConnected" class="workspace-placeholder">
          <div class="workspace-placeholder__icon" aria-hidden="true"><AppIcon name="folder" /></div>
          <p>Connect to list or create sessions on the host.</p>
        </div>
        <template v-else>
          <div class="create-session-row">
            <label class="sr-only" for="session-name">New session name</label>
            <input id="session-name" v-model="sessionName" data-testid="new-session-name" placeholder="New session name" />
            <button class="small-action" type="button" data-testid="create-session" :disabled="!sessionName.trim()" @click="createSession">Create</button>
          </div>
          <ul v-if="sessions.length" class="session-list" data-testid="session-list">
            <li v-for="session in sessions" :key="session.id ?? session.name">
              <button
                class="session-row"
                type="button"
                :data-session-name="session.name"
                :data-session-id="session.id ?? ''"
                :data-session-workspace="session.workspace ?? ''"
                :data-session-tag="session.tag ?? ''"
                :data-session-agent="session.agent ?? ''"
                :data-session-agent-state="session.agentState ?? ''"
                :data-session-agent-state-source="session.agentStateSource ?? ''"
                :aria-current="isSelectedSession(session) ? 'true' : undefined"
                @click="attachSession(session)"
              >
                <span class="session-row__title">
                  <span class="session-name">{{ session.name }}</span>
                  <SessionAgentMetadata :session="session" />
                </span>
                <span class="session-meta">{{ session.workspace || session.engine || 'remote session' }}</span>
                <span class="session-attach">{{ isSelectedSession(session) && isLive ? 'Attached' : 'Attach' }}</span>
              </button>
            </li>
          </ul>
          <p v-else class="empty-sessions" data-testid="empty-sessions">No sessions on this host yet.</p>
        </template>
        <p v-if="connectionSnapshot?.uncertainMutation" class="connection-message" data-testid="uncertain-mutation"
          :data-state="connectionSnapshot.uncertainMutation.state">
          {{ connectionSnapshot.uncertainMutation.kind }} “{{ connectionSnapshot.uncertainMutation.target }}” may have completed. Refresh sessions before retrying.
        </p>
      </section>

      <section v-if="homeSurface === 'live' || connectionSnapshot?.selectedSession" v-show="homeSurface === 'live'" class="live-workspace">
        <p v-if="connectionMessage" class="connection-message live-connection-message" role="alert" data-testid="ssh-message">
          {{ connectionMessage }}
        </p>
        <section class="panel terminal-panel" aria-labelledby="terminal-title">
        <div class="panel-heading panel-heading--terminal">
          <div>
            <h2 id="terminal-title">{{ connectionSnapshot?.selectedSession?.name || 'Terminal' }}</h2>
          </div>
          <span v-if="!isLive" class="state-tag" :class="currentPhase === 'lost' ? 'state-tag--warning' : 'state-tag--muted'" data-testid="terminal-state">{{ terminalStateText }}</span>
        </div>
        <div
          class="terminal-slot"
          data-testid="terminal-slot"
          :data-terminal-viewport-dock-cap="terminalViewportDockCapPx ?? ''"
          :data-terminal-hotkeys-dock-height="mobileHotkeysDockHeight"
          :style="{
            '--terminal-viewport-dock-cap': terminalViewportDockCapPx === null ? undefined : `${terminalViewportDockCapPx}px`,
            '--terminal-hotkeys-dock-height': `${mobileHotkeysDockHeight}px`,
          }"
          :class="{
            'terminal-slot--hotkeys': isLive,
            'terminal-slot--fast-keys-main': mobileHotkeysPaletteOpen && mobileHotkeysPage === 'main',
            'terminal-slot--fast-keys-ctrl': mobileHotkeysPaletteOpen && mobileHotkeysPage === 'ctrl',
            'terminal-slot--dictation-status': inlineDictationStatusVisible,
            'terminal-slot--preserve-grid': terminalViewportDockCapPx !== null,
          }"
          :data-keyboard-visible="keyboardVisible"
        >
          <TerminalViewport
            ref="terminal"
            :enabled="isLive"
            :resize-failure="terminalResizeFailure"
            :autofocus-allowed="terminalAutofocusAllowed"
            :theme="activeTheme.terminal"
            :font-family="terminalFontFamily"
            :font-size="appSettings.terminalFontSize"
            @input="sendTerminalInput"
            @resize="resizeTerminal"
          />
          <MobileHotkeys
            v-if="isLive"
            ref="mobileHotkeys"
            :enabled="mobileHotkeysEnabled"
            :keyboard-visible="keyboardVisible"
            :dictation-available="Capacitor.getPlatform() === 'android'"
            :show-inline-dictation-status="inlineDictationStatusVisible"
            :prompt-composer-available="Capacitor.getPlatform() === 'android' && !mobilePromptComposerInline"
            :prompt-composer-enabled="inlineDictationState.phase === 'idle'"
            :dictation-state="inlineDictationState"
            :dictation-target-key="inlineDictationTargetKey"
            @send="sendMobileHotkey"
            @palette-change="mobileHotkeysPaletteOpen = $event"
            @page-change="mobileHotkeysPage = $event"
            @keep-keyboard-open="keepMobileHotkeysImeOpen"
            @open-composer="openPromptComposer"
          >
            <template #persistent-accessory>
              <TerminalDictationBar
                v-if="Capacitor.getPlatform() === 'android'"
                :enabled="mobileHotkeysEnabled"
                :target-key="inlineDictationTargetKey"
                :language-tag="appSettings.voiceLanguage === 'auto' ? '' : appSettings.voiceLanguage"
                :silence-window-ms="appSettings.voiceSilenceSeconds * 1_000"
                :insert-text="insertInlineDictationText"
                @state-change="updateInlineDictationState"
              />
            </template>
          </MobileHotkeys>
        </div>
        <!-- The SSH-accepted terminal grid: a test hook, not chrome (#3023). -->
        <span hidden data-testid="terminal-resize-status" :data-status="terminalResizeStatus" />
        </section>
        <PromptComposer
          v-if="connectionSnapshot?.selectedSession"
          :target-key="composerTargetKey"
          :target-label="connectionSnapshot.selectedSession.name"
          :host-id="snippetHostId"
          :keyboard-visible="keyboardVisible"
          :transport-state="composerTransportState"
          :write-pty="writeComposerPty"
          :mobile-sheet="Capacitor.getPlatform() === 'android' && !mobilePromptComposerInline"
          :mobile-inline="Capacitor.getPlatform() === 'android' && mobilePromptComposerInline"
          :open="mobilePromptComposerOpen"
          @open-change="setMobilePromptComposerOpen"
          @submit-delivered="showInlinePromptComposerAfterSend"
          @open-keys="openTerminalKeysFromComposer"
          @manage="openSnippetSettings"
        />
      </section>
    </main>

    <ProviderUsageScreen
      v-if="navigation.route === 'usage'"
      :connected="hasActiveConnection"
      :records="usageRecords"
      :loading="usageLoading"
      :error="usageError"
      :last-read-at="usageLastReadAt"
      @refresh="refreshUsage"
    />
    <PortForwardScreen
      v-if="navigation.route === 'ports'"
      :connected="hasActiveConnection"
      :loading="portLoading"
      :error="portError"
      :auto-enabled="portAutoEnabled"
      :manual-ports="portManualDesiredPorts"
      :scan-count="portScanCount"
      :snapshot="portSnapshot"
      @refresh="refreshPorts"
      @set-auto="setAutomaticPortForwarding"
      @set-port="setManualPortForwarding"
    />
    <SshKeysScreen
      v-if="navigation.route === 'keys'"
      :manager="keyManager"
      :keys="sshKeys"
      :selected-handle-id="selectedKeyHandleId"
      :load-error="sshKeyLoadError"
      :host-install="sshKeyHostInstall"
      @refresh="refreshSshKeys"
      @select="selectSshKey"
    />
    <SettingsScreen
      v-if="navigation.route.startsWith('settings')"
      :snippet-host-id="snippetHostId"
      :snippet-host-label="snippetHostLabel"
    />
    <DiagnosticsScreen v-if="navigation.route.startsWith('diagnostics')" />
    <FileWorkspaceScreen
      v-if="navigation.route === 'files'"
      :connection="fileConnection"
      :initial-root-directory="fileRootDirectory"
      :capability="sshCapability"
    />
    <AboutScreen
      v-if="navigation.route === 'about' || navigation.route === 'about-update'"
      :build-state="buildStatusTone"
      :core-revision="coreSourceRevision"
      :bundle-hash="bundleHash"
      :failure-reason="buildFailureReason"
    />
  </div>
</template>
