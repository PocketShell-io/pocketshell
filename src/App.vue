<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch, watchEffect, type CSSProperties } from 'vue';
import { App as CapacitorApp } from '@capacitor/app';
import { Capacitor } from '@capacitor/core';
import {
  fromAndroidTrustedHostKeySha256,
  formatBytes,
  isValidTcpPort,
  type ConnectionSnapshot,
  type HostKeyTrustPin,
  type HostKeyTrustStore,
  type SessionRow,
  type SshConnectionRef,
  type SshHostTarget,
  type SshResourceSnapshot,
  type UsageProviderRecord,
} from '@pocketshell/core';
import { AppIcon, fontCssVariables, resolveTheme } from '@pocketshell/ui';
import { verifyCurrentBuild, type BuildVerification } from './buildDiagnostics';
import { coreSourceRevision } from './coreSourceInfo';
import { uiSourceRevision } from './uiSourceInfo';
import {
  readImportedLegacyHosts,
  installedDataMigrationState,
  SETTINGS_RELOAD_SESSION_KEY,
  retryInstalledDataMigration,
  runInstalledDataMigration,
  shouldReloadForImportedSettings,
  type ImportedLegacyHost,
} from './migration/installedDataMigration';
import { makeLegacySshHostTarget } from './migration/legacySshTarget';
import { useNavigationStore } from './stores/navigation';
import { useAppSettings } from './stores/appSettings';
import { useDiagnosticsStore, type DiagnosticKind } from './diagnostics';
import { ConnectionController } from './session/connectionController';
import { resolveAndroidBackDestination, transitionHomeSurface, type HomeSurface, type HomeSurfaceAction } from './session/homeSurface';
import { readSshError, sshCapability } from './native/sshCapability';
import { keyboardInsets, type KeyboardInsetsState } from './native/keyboardInsets';
import TerminalViewport from './components/TerminalViewport.vue';
import PromptComposer from './components/PromptComposer.vue';
import type { PtyWriteAcknowledgement } from './session/composerDelivery';
import { allocateTerminalResizeRequestId, type TerminalResizeRequest } from './terminalGeometry';
import SettingsScreen from './components/SettingsScreen.vue';
import ProviderUsageScreen from './components/ProviderUsageScreen.vue';
import PortForwardScreen from './components/PortForwardScreen.vue';
import { readHostUsage } from './policy/usage';
import { PortForwardController, type PortForwardControllerSnapshot } from './policy/portForwardController';
import DiagnosticsScreen from './components/DiagnosticsScreen.vue';
import AboutScreen from './components/AboutScreen.vue';

interface TerminalViewportHandle {
  write(bytes: Uint8Array): void;
  clear(): void;
  focus(): void;
  fit(): Promise<TerminalResizeRequest | null>;
  scrollToBottom(): void;
}

type ComposerSmokeEvidenceWindow = Window & {
  __ps2857CaptureTerminalEvidence?: boolean;
  __ps2857AppTerminalDeliveryCount?: number;
  __ps2857AppTerminalLastChunk?: string;
  __ps2857AppTerminalMissingRefCount?: number;
};

const navigation = useNavigationStore();
const appSettings = useAppSettings();
const diagnostics = useDiagnosticsStore();
const buildVerification = ref<BuildVerification | { checking: true }>({ checking: true });
const coreSample = formatBytes(1536);
const coreShort = coreSourceRevision.slice(0, 12);
const uiShort = uiSourceRevision.slice(0, 12);
const buildStatus = computed(() => {
  if ('checking' in buildVerification.value) return 'Checking bundled assets';
  return buildVerification.value.ok ? 'Build verified' : 'Build verification failed';
});
const buildStatusTone = computed(() => {
  if ('checking' in buildVerification.value) return 'checking';
  return buildVerification.value.ok ? 'verified' : 'error';
});
const bundleShort = computed(() =>
  !('checking' in buildVerification.value) && buildVerification.value.ok
    ? buildVerification.value.bundleAssetHash.slice(0, 12)
    : 'not verified',
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
const promptComposerHasFocus = ref(false);
const homeSurface = ref<HomeSurface>('connection');
const hostDraft = ref({ hostname: '', port: '22', username: '', privateKeyPem: '' });
const importedLegacyHosts = ref<ImportedLegacyHost[]>([]);
const selectedLegacyHostId = ref('');
const legacyKeyPassphrase = ref('');
const sessionName = ref('mobile-session');
const connectionSnapshot = ref<ConnectionSnapshot | null>(null);
const connectionMessage = ref('');
const settingsReloading = ref(false);
const resourceSnapshot = ref<SshResourceSnapshot | null>(null);
const resourceSnapshotStatus = ref<'unverified' | 'pending' | 'verified' | 'failed'>('unverified');
const usageRecords = ref<UsageProviderRecord[]>([]);
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
const terminalResizeStatus = ref('waiting for a live PTY');
const terminal = ref<TerminalViewportHandle | null>(null);
const terminalInputPending = ref(0);
const terminalInputAckCount = ref(0);
const terminalInputFailureCount = ref(0);
const terminalResizePending = ref(0);
const terminalResizeAckCount = ref(0);
const terminalResizeFailureCount = ref(0);
const terminalResizeFailure = ref<TerminalResizeRequest | null>(null);
// Resize callbacks can finish after the user has selected a different PTY.
let terminalAttachEpoch = 0;

let controller: ConnectionController | null = null;
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
let keyboardInsetsEvents = 0;
let nativeKeyboardInsetsSupported = false;
const currentPhase = computed(() => connectionSnapshot.value?.phase ?? 'idle');
const isConnecting = computed(() => ['connecting', 'reconnecting'].includes(currentPhase.value));
const isConnected = computed(() => ['connected', 'listing', 'attaching', 'live', 'background'].includes(currentPhase.value));
const hasActiveConnection = computed(() => isConnected.value
  && Boolean(connectionSnapshot.value?.connectionId && connectionSnapshot.value.generationId));
const migrationBlocksConnection = computed(() => installedDataMigrationState.retrying
  || installedDataMigrationState.status === 'pending'
  || settingsReloading.value);
const isLive = computed(() => currentPhase.value === 'live');
const keyboardComposerMode = computed(() =>
  keyboardVisible.value && isLive.value && promptComposerHasFocus.value,
);
const composerTargetKey = computed(() => {
  const session = connectionSnapshot.value?.selectedSession;
  const hostname = hostDraft.value.hostname.trim();
  const username = hostDraft.value.username.trim();
  if (!session || !hostname || !username) return '';
  return `${username}@${hostname}:${hostDraft.value.port}/${session.id ?? session.name}`;
});
const composerTransportState = computed<'connected' | 'lost' | 'closed'>(() => {
  if (!connectionSnapshot.value?.selectedSession) return 'closed';
  if (currentPhase.value === 'live') return 'connected';
  if (['connecting', 'reconnecting', 'attaching', 'background', 'error'].includes(currentPhase.value)) return 'lost';
  return 'closed';
});
const trustDecision = computed(() => connectionSnapshot.value?.trustDecision ?? null);
const sessions = computed(() => connectionSnapshot.value?.sessions ?? []);
const selectedLegacyHost = computed(() => importedLegacyHosts.value.find(
  (host) => String(host.id) === selectedLegacyHostId.value,
) ?? null);

function navigateHomeSurface(action: HomeSurfaceAction) {
  if (action !== 'session-attached' && document.activeElement instanceof HTMLElement) {
    document.activeElement.blur();
  }
  homeSurface.value = transitionHomeSurface(homeSurface.value, action);
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
  if (route === 'usage') void refreshUsage();
  if (route === 'ports') void refreshPorts();
});

function pinStoreKey(hostId: string): string {
  return `pocketshell.ssh.host-key.${hostId}`;
}

function isPromptComposerElement(target: Element | null): boolean {
  return target !== null && target.closest('[data-testid="prompt-composer"]') !== null;
}

function recordFocusedElement(event: FocusEvent) {
  promptComposerHasFocus.value = event.target instanceof Element
    && isPromptComposerElement(event.target);
}

function recordFocusAfterBlur() {
  queueMicrotask(() => {
    promptComposerHasFocus.value = document.activeElement instanceof Element
      && isPromptComposerElement(document.activeElement);
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
  removeTerminalOutput = next.subscribeTerminalOutput((_session, bytes) => {
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
  const privateKeyPem = hostDraft.value.privateKeyPem.trim();
  const legacyHost = selectedLegacyHost.value;
  if (!hostname || !username || (!privateKeyPem && !legacyHost) || !Number.isInteger(port) || port < 1 || port > 65535) {
    connectionMessage.value = 'Enter a host, port, user, and private key or choose a saved host.';
    return null;
  }
  if (legacyHost) {
    return makeLegacySshHostTarget(legacyHost, legacyKeyPassphrase.value) as unknown as SshHostTarget;
  }
  return {
    hostId: `${username}@${hostname}:${port}`,
    hostname,
    port,
    username,
    credential: { kind: 'private-key', privateKeyPem },
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
    privateKeyPem: '',
  };
}

function clearLegacyHostSelection(): void {
  selectedLegacyHostId.value = '';
  legacyKeyPassphrase.value = '';
}

async function connectHost() {
  if (migrationBlocksConnection.value) return;
  const host = makeHostTarget();
  if (!host) return;
  await closeController();
  resourceSnapshot.value = null;
  resourceSnapshotStatus.value = 'unverified';
  connectionMessage.value = '';
  terminal.value?.clear();
  const next = new ConnectionController({ trustStore });
  bindController(next);
  let result;
  try {
    result = await next.connect(host);
  } catch (error) {
    recordFailure('ssh-bridge-failed', 'connect', error);
    connectionMessage.value = error instanceof Error ? error.message : String(error);
    return;
  }
  if (result.ok) {
    await refreshSessions();
    navigateHomeSurface('connected');
  }
  else if (next.getSnapshot().phase !== 'awaiting-trust') {
    recordOperationFailure('connect');
    connectionMessage.value = result.message;
  }
}

async function acceptHostKey() {
  const active = controller;
  if (!active) return;
  connectionMessage.value = '';
  let result;
  try {
    result = await active.acceptPresentedHostKey();
  } catch (error) {
    recordFailure('ssh-bridge-failed', 'accept-host-key', error);
    connectionMessage.value = error instanceof Error ? error.message : String(error);
    return;
  }
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
  terminalAttachEpoch += 1;
  terminal.value?.clear();
  const result = await active.switchSession(session).catch((error: unknown) => {
    recordFailure('ssh-bridge-failed', 'attach-session', error);
    connectionMessage.value = error instanceof Error ? error.message : String(error);
    return null;
  });
  if (result && !result.ok) {
    recordOperationFailure('attach-session');
    connectionMessage.value = result.message;
  }
  else if (result?.ok) {
    navigateHomeSurface('session-attached');
    await nextTick();
    // Reusing a visible terminal can leave ResizeObserver silent when two PTYs
    // have identical geometry. Explicitly resize each newly attached PTY.
    const size = await terminal.value?.fit();
    if (size) await resizeTerminal(size);
    terminal.value?.focus();
  }
}

async function sendTerminalInput(data: string) {
  const active = controller;
  if (!active || !isLive.value) return;
  const attachEpoch = terminalAttachEpoch;
  terminalInputPending.value += 1;
  try {
    const result = await active.writeTerminalBytes(new TextEncoder().encode(data));
    if (attachEpoch !== terminalAttachEpoch || (!result.ok && result.reason === 'superseded')) return;
    if (!result.ok) {
      terminalInputFailureCount.value += 1;
      recordOperationFailure('send-terminal-input');
      connectionMessage.value = result.message;
    } else {
      terminalInputAckCount.value += 1;
    }
  } catch (error: unknown) {
    if (attachEpoch !== terminalAttachEpoch) return;
    terminalInputFailureCount.value += 1;
    recordFailure('ssh-bridge-failed', 'send-terminal-input', error);
    connectionMessage.value = error instanceof Error ? error.message : String(error);
  } finally {
    terminalInputPending.value -= 1;
  }
}

async function writeComposerPty(bytes: Uint8Array): Promise<PtyWriteAcknowledgement> {
  const active = controller;
  if (!active) return { ok: false, message: 'No active PTY.' };
  const result = await active.writeTerminalBytes(bytes);
  if (result.ok) terminal.value?.scrollToBottom();
  return result.ok ? { ok: true } : { ok: false, message: result.message };
}

async function resizeTerminal(size: TerminalResizeRequest) {
  if (!controller || !isLive.value) return;
  const requestId = size.requestId ?? allocateTerminalResizeRequestId();
  const attachEpoch = terminalAttachEpoch;
  terminalResizeStatus.value = `${size.cols} × ${size.rows} (local fit)`;
  terminalResizePending.value += 1;
  try {
    const result = await controller.resizeTerminal(size.cols, size.rows).catch((error: unknown) => {
      recordFailure('ssh-bridge-failed', 'resize-terminal', error);
      connectionMessage.value = error instanceof Error ? error.message : String(error);
      return null;
    });
    if (attachEpoch !== terminalAttachEpoch || (result && !result.ok && result.reason === 'superseded')) return;
    terminalResizeStatus.value = result?.ok
      ? `${size.cols} × ${size.rows} accepted by SSH`
      : `resize failed: ${result && 'message' in result ? result.message : 'native bridge error'}`;
    if (result?.ok) terminalResizeAckCount.value += 1;
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
    connectionMessage.value = `Native resource snapshot failed (${sshError.code}): ${sshError.message}`;
  }
}

async function rejectHostKey() {
  connectionMessage.value = 'Host key was not trusted. No SSH connection remains open.';
  await disconnectHost();
}

function retryDataImport() {
  void retryInstalledDataMigration().then((settingsWritten) => {
    reloadAfterSettingsImport(settingsWritten);
    void loadImportedLegacyHosts();
  });
}

async function loadImportedLegacyHosts(): Promise<void> {
  try {
    importedLegacyHosts.value = await readImportedLegacyHosts();
  } catch (error) {
    installedDataMigrationState.status = 'failed';
    installedDataMigrationState.error = error instanceof Error
      ? error.message
      : 'Saved hosts could not be loaded from the migration record.';
  }
}

function reloadAfterSettingsImport(settingsWritten: boolean) {
  if (!settingsWritten) {
    try {
      window.sessionStorage.removeItem(SETTINGS_RELOAD_SESSION_KEY);
    } catch {
      // A later launch can still use the imported value from local storage.
    }
    return;
  }
  if (!shouldReloadForImportedSettings(settingsWritten)) return;
  settingsReloading.value = true;
  window.location.reload();
}

onMounted(() => {
  diagnostics.record('app-started', 'startup', 'OK');
  const updateKeyboardViewport = () => {
    if (nativeKeyboardInsetsSupported || Capacitor.getPlatform() !== 'android') return;
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
  updateKeyboardViewport();
  window.visualViewport?.addEventListener('resize', updateKeyboardViewport);
  window.addEventListener('resize', updateKeyboardViewport);
  removeKeyboardViewportListeners = () => {
    window.visualViewport?.removeEventListener('resize', updateKeyboardViewport);
    window.removeEventListener('resize', updateKeyboardViewport);
  };

  if (Capacitor.getPlatform() === 'android') {
    void keyboardInsets.addListener('imeInsetsChanged', (state) => {
      keyboardInsetsEvents += 1;
      applyKeyboardInsets(state);
    }).then(async (listener) => {
      removeKeyboardInsetsListener = () => listener.remove();
      const eventsBeforeRead = keyboardInsetsEvents;
      const initialState = await keyboardInsets.getState();
      if (eventsBeforeRead === keyboardInsetsEvents) applyKeyboardInsets(initialState);
    }).catch((error: unknown) => {
      console.error('Could not register the Android IME inset listener.', error);
    });
  }

  void runInstalledDataMigration().then((settingsWritten) => {
    reloadAfterSettingsImport(settingsWritten);
    void loadImportedLegacyHosts();
  });
  if (Capacitor.isNativePlatform()) {
    void CapacitorApp.addListener('backButton', () => {
      backButtonEvents.value += 1;
      const activeElement = document.activeElement;
      const inputHasFocus = activeElement instanceof HTMLInputElement
        || activeElement instanceof HTMLTextAreaElement
        || activeElement instanceof HTMLSelectElement
        || (activeElement instanceof HTMLElement && activeElement.isContentEditable);
      const viewportHeight = window.visualViewport?.height ?? window.innerHeight;
      const keyboardIsVisible = window.screen.height - viewportHeight > 120;
      if (inputHasFocus && keyboardIsVisible) {
        (activeElement as HTMLElement).blur();
        return;
      }
      switch (resolveAndroidBackDestination(navigation.canGoBack, homeSurface.value, !!connectionSnapshot.value)) {
        case 'navigation': navigation.back(); break;
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
      const active = controller;
      if (!active) return;
      const phase = active.getSnapshot().phase;
      if (isActive && phase === 'background') void active.returnToForeground().catch((error: unknown) => {
        recordFailure('ssh-bridge-failed', 'lifecycle', error);
        connectionMessage.value = error instanceof Error ? error.message : String(error);
      });
      else if (!isActive && phase === 'live') void active.enterBackground(appSettings.backgroundGraceMs).catch((error: unknown) => {
        recordFailure('ssh-bridge-failed', 'lifecycle', error);
        connectionMessage.value = error instanceof Error ? error.message : String(error);
      });
    }).then((listener) => {
      removeAppState = () => listener.remove();
    }).catch((error: unknown) => {
      console.error('Could not register the app background handler.', error);
    });
  }

  void verifyCurrentBuild(coreSourceRevision, uiSourceRevision).then((verification) => {
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
});


onBeforeUnmount(() => {
  removeKeyboardViewportListeners?.();
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
    :data-ssh-phase="currentPhase"
    :data-home-surface="homeSurface"
    :data-ssh-connection-id="connectionSnapshot?.connectionId ?? ''"
    :data-ssh-generation-id="connectionSnapshot?.generationId ?? ''"
    :data-ssh-selected-session="connectionSnapshot?.selectedSession?.name ?? ''"
    :data-ssh-selected-session-id="connectionSnapshot?.selectedSession?.id ?? ''"
    :data-ssh-selected-workspace="connectionSnapshot?.selectedSession?.workspace ?? ''"
    :data-ssh-selected-tag="connectionSnapshot?.selectedSession?.tag ?? ''"
    :data-ssh-retry-attempt="connectionSnapshot?.retryAttempt ?? 0"
    :data-ssh-terminal-input-pending="terminalInputPending"
    :data-ssh-terminal-input-acks="terminalInputAckCount"
    :data-ssh-terminal-input-failures="terminalInputFailureCount"
    :data-ssh-terminal-resize-pending="terminalResizePending"
    :data-ssh-terminal-resize-acks="terminalResizeAckCount"
    :data-ssh-terminal-resize-failures="terminalResizeFailureCount"
    @focusin="recordFocusedElement"
    @focusout="recordFocusAfterBlur"
    :data-migration-status="installedDataMigrationState.status"
  >
    <header class="app-bar" :class="{ 'app-bar--workspace': !!connectionSnapshot }">
      <template v-if="navigation.route === 'home' && connectionSnapshot">
        <div class="session-context" aria-live="polite">
          <AppIcon class="brand-mark" name="terminal" />
          <div class="session-context__copy">
            <span class="session-context__title">{{ connectionSnapshot.selectedSession?.name || 'PocketShell' }}</span>
            <span class="session-context__host">{{ hostDraft.username }}@{{ hostDraft.hostname }}</span>
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
          <span class="rewrite-chip">0.6.0 · rewrite preview</span>
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

    <div v-if="!connectionSnapshot" class="build-strip" :class="`build-strip--${buildStatusTone}`" data-testid="build-status">
      <AppIcon class="status-dot" name="dot" :size="12" />
      <span>{{ buildStatus }}</span>
      <span class="build-strip__detail">core {{ coreShort }} · ui {{ uiShort }} · assets {{ bundleShort }}</span>
    </div>

    <section
      v-if="installedDataMigrationState.status === 'failed' || installedDataMigrationState.status === 'partial'"
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

    <main
      v-if="connectionSnapshot || navigation.route === 'home'"
      class="screen-content home-screen"
      :class="{ 'home-screen--workspace': !!connectionSnapshot }"
      :style="navigation.route === 'home' ? undefined : retainedHomeScreenStyle"
      :aria-hidden="navigation.route !== 'home'"
      :inert="navigation.route !== 'home'"
    >
      <section v-if="homeSurface === 'connection'" class="connection-stack">
      <section class="panel host-panel" aria-labelledby="hosts-title">
        <div class="panel-heading">
          <div>
            <p class="eyebrow">CONNECTION</p>
            <h1 id="hosts-title">SSH host</h1>
          </div>
          <span class="state-tag" :class="isConnected ? 'state-tag--success' : 'state-tag--muted'">
            {{ isLive ? 'LIVE' : isConnected ? 'CONNECTED' : isConnecting ? 'CONNECTING' : currentPhase.toUpperCase() }}
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
          <label v-if="selectedLegacyHost?.keyHasPassphrase" class="form-field host-field-key">
            <span>Saved SSH key passphrase · kept in memory for this run</span>
            <input v-model="legacyKeyPassphrase" data-testid="legacy-key-passphrase" type="password" autocomplete="off" />
          </label>
          <label v-if="!selectedLegacyHost" class="form-field host-field-key">
            <span>Private key · kept in memory for this run</span>
            <textarea
              v-model="hostDraft.privateKeyPem"
              data-testid="ssh-private-key"
              rows="4"
              autocomplete="off"
              autocapitalize="none"
              spellcheck="false"
              placeholder="Paste an OpenSSH private key"
            />
          </label>
        </div>
        <div class="host-actions">
          <button class="action-button" type="button" data-testid="ssh-connect" :disabled="isConnecting || migrationBlocksConnection" @click="connectHost">
            {{ isConnecting ? 'Connecting…' : 'Connect' }}
          </button>
        </div>

        <div v-if="trustDecision" class="trust-prompt" role="alert" data-testid="host-key-decision">
          <strong>{{ trustDecision.reason === 'mismatch' ? 'Host key changed' : 'Verify this host key' }}</strong>
          <p>{{ trustDecision.hostLabel }} presented:</p>
          <code data-testid="host-key-fingerprint">{{ trustDecision.presented.fingerprintSha256 }}</code>
          <div class="host-actions">
            <button class="action-button" type="button" data-testid="trust-host-key" @click="acceptHostKey">Trust key and connect</button>
            <button class="action-button action-button--secondary" type="button" data-testid="reject-host-key" @click="rejectHostKey">Reject</button>
          </div>
        </div>
        <p v-if="connectionMessage || connectionSnapshot?.error" class="connection-message" role="alert" data-testid="ssh-message">
          {{ connectionMessage || connectionSnapshot?.error }}
        </p>
        <p class="panel-footnote">SSH host-key pins are saved locally. Imported private keys stay in Android private storage and are read by native SSH only.</p>
      </section>

      <section class="panel diagnostics-panel" aria-labelledby="diagnostics-title">
        <div class="panel-heading">
          <div>
            <p class="eyebrow">TRANSPORT</p>
            <h2 id="diagnostics-title">SSH resource status</h2>
          </div>
          <span class="state-tag state-tag--muted">{{ currentPhase.toUpperCase() }}</span>
        </div>
        <dl
          class="resource-list"
          data-testid="ssh-resources"
          :data-snapshot-state="resourceSnapshotStatus"
          :data-snapshot-request-id="resourceSnapshot?.requestId ?? ''"
        >
          <div><dt>Connections</dt><dd data-testid="ssh-resource-connections">{{ resourceSnapshot?.connections ?? 'Unverified' }}</dd></div>
          <div><dt>PTY channels</dt><dd data-testid="ssh-resource-ptys">{{ resourceSnapshot?.ptys ?? 'Unverified' }}</dd></div>
          <div><dt>SFTP clients</dt><dd data-testid="ssh-resource-sftp">{{ resourceSnapshot?.sftpClients ?? 'Unverified' }}</dd></div>
          <div><dt>Port forwards</dt><dd data-testid="ssh-resource-forwards">{{ resourceSnapshot?.forwards ?? 'Unverified' }}</dd></div>
        </dl>
        <p class="panel-footnote">{{ resourceSnapshotStatus === 'verified' ? 'Native close snapshot verified.' : resourceSnapshotStatus === 'failed' ? 'Native close snapshot failed; counts are unverified.' : 'Native close snapshot has not been verified.' }}</p>
      </section>

      <section class="panel diagnostics-panel" aria-labelledby="build-diagnostics-title">
        <div class="panel-heading">
          <div>
            <p class="eyebrow">BUILD</p>
            <h2 id="build-diagnostics-title">Source and asset diagnostics</h2>
          </div>
          <span class="state-tag" :class="buildStatusTone === 'error' ? 'state-tag--error' : 'state-tag--success'">
            {{ buildStatusTone === 'error' ? 'CHECK FAILED' : buildStatusTone === 'checking' ? 'CHECKING' : 'VERIFIED' }}
          </span>
        </div>
        <dl class="diagnostic-list">
          <div><dt>pocketshell-core revision</dt><dd data-testid="core-revision">{{ coreSourceRevision }}</dd></div>
          <div><dt>pocketshell-desktop shared UI revision</dt><dd data-testid="ui-revision">{{ uiSourceRevision }}</dd></div>
          <div>
            <dt>Bundled asset SHA-256</dt>
            <dd data-testid="bundle-asset-hash">{{ !('checking' in buildVerification) && buildVerification.ok ? buildVerification.bundleAssetHash : 'Pending verification' }}</dd>
          </div>
          <div><dt>Core formatter</dt><dd>formatBytes(1536) → {{ coreSample }}</dd></div>
        </dl>
        <p v-if="!('checking' in buildVerification) && !buildVerification.ok" class="integrity-error" role="alert">
          {{ buildVerification.reason }}
        </p>
      </section>
      </section>

      <section v-if="homeSurface === 'sessions'" class="panel workspace-panel" aria-labelledby="sessions-title">
        <div class="panel-heading">
          <div>
            <p class="eyebrow">REMOTE SESSIONS</p>
            <h2 id="sessions-title">Sessions</h2>
          </div>
          <button v-if="isConnected" class="small-action" type="button" data-testid="refresh-sessions" @click="refreshSessions">Refresh</button>
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
                :aria-current="connectionSnapshot?.selectedSession?.name === session.name ? 'true' : undefined"
                @click="attachSession(session)"
              >
                <span class="session-name">{{ session.name }}</span>
                <span class="session-meta">{{ session.workspace || session.engine || 'remote session' }}</span>
                <span class="session-attach">{{ connectionSnapshot?.selectedSession?.name === session.name && isLive ? 'Attached' : 'Attach' }}</span>
              </button>
            </li>
          </ul>
          <p v-else class="empty-sessions" data-testid="empty-sessions">No sessions on this host yet.</p>
        </template>
        <p v-if="connectionSnapshot?.uncertainMutation" class="connection-message" data-testid="uncertain-mutation">
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
            <p class="eyebrow">TERMINAL</p>
            <h2 id="terminal-title">{{ connectionSnapshot?.selectedSession?.name || 'Live terminal' }}</h2>
          </div>
          <span class="state-tag" :class="isLive ? 'state-tag--success' : 'state-tag--muted'">{{ isLive ? 'SSH PTY' : 'NO PTY' }}</span>
        </div>
        <TerminalViewport
          ref="terminal"
          :enabled="isLive"
          :resize-failure="terminalResizeFailure"
          :theme="activeTheme.terminal"
          :font-family="terminalFontFamily"
          :font-size="appSettings.terminalFontSize"
          @input="sendTerminalInput"
          @resize="resizeTerminal"
        />
        <p class="panel-footnote" data-testid="terminal-resize-status">{{ terminalResizeStatus }}</p>
        </section>
        <PromptComposer
          v-if="connectionSnapshot?.selectedSession"
          :target-key="composerTargetKey"
          :target-label="connectionSnapshot.selectedSession.name"
          :transport-state="composerTransportState"
          :write-pty="writeComposerPty"
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
    <SettingsScreen v-if="navigation.route.startsWith('settings')" />
    <DiagnosticsScreen v-if="navigation.route.startsWith('diagnostics')" />
    <AboutScreen
      v-if="navigation.route === 'about' || navigation.route === 'about-update'"
      :build-verification="buildVerification"
      :core-revision="coreSourceRevision"
      :ui-revision="uiSourceRevision"
      :bundle-hash="!('checking' in buildVerification) && buildVerification.ok ? buildVerification.bundleAssetHash : 'Not verified'"
      :build-status="buildStatus"
    />
  </div>
</template>
