<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { App as CapacitorApp } from '@capacitor/app';
import { Capacitor } from '@capacitor/core';
import {
  fromAndroidTrustedHostKeySha256,
  formatBytes,
  type ConnectionSnapshot,
  type HostKeyTrustPin,
  type HostKeyTrustStore,
  type SessionRow,
  type SshHostTarget,
  type SshResourceSnapshot,
} from '@pocketshell/core';
import { AppIcon } from '@pocketshell/ui';
import { verifyCurrentBuild, type BuildVerification } from './buildDiagnostics';
import { coreSourceRevision } from './coreSourceInfo';
import { uiSourceRevision } from './uiSourceInfo';
import { useNavigationStore } from './stores/navigation';
import { ConnectionController } from './session/connectionController';
import { readSshError, sshCapability } from './native/sshCapability';
import TerminalViewport from './components/TerminalViewport.vue';
import PromptComposer from './components/PromptComposer.vue';
import type { PtyWriteAcknowledgement } from './session/composerDelivery';

interface TerminalViewportHandle {
  write(bytes: Uint8Array): void;
  clear(): void;
  focus(): void;
}

const navigation = useNavigationStore();
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
const backButtonReady = ref(!Capacitor.isNativePlatform());
const backButtonEvents = ref(0);
const keyboardVisible = ref(false);
const hostDraft = ref({ hostname: '', port: '22', username: '', privateKeyPem: '' });
const sessionName = ref('mobile-session');
const connectionSnapshot = ref<ConnectionSnapshot | null>(null);
const connectionMessage = ref('');
const resourceSnapshot = ref<SshResourceSnapshot | null>(null);
const resourceSnapshotStatus = ref<'unverified' | 'pending' | 'verified' | 'failed'>('unverified');
const terminalResizeStatus = ref('waiting for a live PTY');
const terminal = ref<TerminalViewportHandle | null>(null);

let controller: ConnectionController | null = null;
let removeBackButton: (() => Promise<void>) | undefined;
let removeAppState: (() => Promise<void>) | undefined;
let removeControllerSnapshot: (() => void) | undefined;
let removeTerminalOutput: (() => void) | undefined;
let removeKeyboardViewportListeners: (() => void) | undefined;
const currentPhase = computed(() => connectionSnapshot.value?.phase ?? 'idle');
const isConnecting = computed(() => ['connecting', 'reconnecting'].includes(currentPhase.value));
const isConnected = computed(() => ['connected', 'listing', 'attaching', 'live', 'background'].includes(currentPhase.value));
const isLive = computed(() => currentPhase.value === 'live');
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

function pinStoreKey(hostId: string): string {
  return `pocketshell.ssh.host-key.${hostId}`;
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
  removeControllerSnapshot = next.subscribe((snapshot) => {
    connectionSnapshot.value = snapshot;
  });
  removeTerminalOutput = next.subscribeTerminalOutput((_session, bytes) => {
    terminal.value?.write(bytes);
  });
}

function makeHostTarget(): SshHostTarget | null {
  const hostname = hostDraft.value.hostname.trim();
  const username = hostDraft.value.username.trim();
  const port = Number(hostDraft.value.port);
  const privateKeyPem = hostDraft.value.privateKeyPem.trim();
  if (!hostname || !username || !privateKeyPem || !Number.isInteger(port) || port < 1 || port > 65535) {
    connectionMessage.value = 'Enter a host, port, user, and private key to connect.';
    return null;
  }
  return {
    hostId: `${username}@${hostname}:${port}`,
    hostname,
    port,
    username,
    credential: { kind: 'private-key', privateKeyPem },
  };
}

async function connectHost() {
  const host = makeHostTarget();
  if (!host) return;
  await closeController();
  resourceSnapshot.value = null;
  resourceSnapshotStatus.value = 'unverified';
  connectionMessage.value = '';
  terminal.value?.clear();
  const next = new ConnectionController({ trustStore });
  bindController(next);
  const result = await next.connect(host);
  if (result.ok) await refreshSessions();
  else if (next.getSnapshot().phase !== 'awaiting-trust') connectionMessage.value = result.message;
}

async function acceptHostKey() {
  const active = controller;
  if (!active) return;
  connectionMessage.value = '';
  const result = await active.acceptPresentedHostKey();
  if (result.ok) await refreshSessions();
  else if (active.getSnapshot().phase !== 'awaiting-trust') connectionMessage.value = result.message;
}

async function refreshSessions() {
  const active = controller;
  if (!active) return;
  connectionMessage.value = '';
  const result = await active.refreshSessions();
  if (!result.ok) connectionMessage.value = result.message;
}

async function createSession() {
  const active = controller;
  const name = sessionName.value.trim();
  if (!active || !name) return;
  connectionMessage.value = '';
  const result = await active.createSession(name);
  if (result.ok) await refreshSessions();
  else connectionMessage.value = result.message;
}

async function attachSession(session: SessionRow) {
  const active = controller;
  if (!active) return;
  terminal.value?.clear();
  const result = await active.switchSession(session);
  if (!result.ok) connectionMessage.value = result.message;
  else terminal.value?.focus();
}

async function sendTerminalInput(data: string) {
  if (!controller || !isLive.value) return;
  const result = await controller.writeTerminalBytes(new TextEncoder().encode(data));
  if (!result.ok) connectionMessage.value = result.message;
}

async function writeComposerPty(bytes: Uint8Array): Promise<PtyWriteAcknowledgement> {
  const active = controller;
  if (!active) return { ok: false, message: 'No active PTY.' };
  const result = await active.writeTerminalBytes(bytes);
  return result.ok ? { ok: true } : { ok: false, message: result.message };
}

async function resizeTerminal(size: { cols: number; rows: number }) {
  terminalResizeStatus.value = `${size.cols} × ${size.rows} (local fit)`;
  if (!controller || !isLive.value) return;
  const result = await controller.resizeTerminal(size.cols, size.rows);
  terminalResizeStatus.value = result.ok
    ? `${size.cols} × ${size.rows} accepted by SSH`
    : `resize failed: ${result.message}`;
}

async function closeController() {
  const active = controller;
  removeControllerSnapshot?.();
  removeControllerSnapshot = undefined;
  removeTerminalOutput?.();
  removeTerminalOutput = undefined;
  controller = null;
  if (active) await active.close().catch(() => undefined);
  connectionSnapshot.value = null;
}

async function disconnectHost() {
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
    connectionMessage.value = `Native resource snapshot failed (${sshError.code}): ${sshError.message}`;
  }
}

async function rejectHostKey() {
  connectionMessage.value = 'Host key was not trusted. No SSH connection remains open.';
  await disconnectHost();
}

onMounted(() => {
  const updateKeyboardViewport = () => {
    const viewportHeight = window.visualViewport?.height ?? window.innerHeight;
    const screenHeight = window.screen.height;
    keyboardVisible.value = Capacitor.getPlatform() === 'android'
      && screenHeight - viewportHeight > 150;
  };
  updateKeyboardViewport();
  window.visualViewport?.addEventListener('resize', updateKeyboardViewport);
  window.addEventListener('resize', updateKeyboardViewport);
  removeKeyboardViewportListeners = () => {
    window.visualViewport?.removeEventListener('resize', updateKeyboardViewport);
    window.removeEventListener('resize', updateKeyboardViewport);
  };

  if (Capacitor.isNativePlatform()) {
    void CapacitorApp.addListener('backButton', () => {
      backButtonEvents.value += 1;
      if (navigation.route === 'settings') navigation.back();
      else void disconnectHost();
    }).then((listener) => {
      removeBackButton = () => listener.remove();
      backButtonReady.value = true;
    }).catch((error: unknown) => {
      console.error('Could not register the Android Back handler.', error);
    });
    void CapacitorApp.addListener('appStateChange', ({ isActive }) => {
      const active = controller;
      if (!active) return;
      if (isActive) void active.returnToForeground().catch((error: unknown) => {
        connectionMessage.value = error instanceof Error ? error.message : String(error);
      });
      else void active.enterBackground(30_000).catch((error: unknown) => {
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
  });
});

onBeforeUnmount(() => {
  removeKeyboardViewportListeners?.();
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
    :data-ssh-phase="currentPhase"
  >
    <header class="app-bar">
      <button class="brand-button" type="button" aria-label="PocketShell home" @click="navigation.back()">
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
          @click="navigation.openSettings()"
        >
          <AppIcon name="settings" />
        </button>
        <button
          v-else
          class="icon-button back-button"
          type="button"
          aria-label="Back to hosts"
          title="Back to hosts"
          @click="navigation.back()"
        >
          <AppIcon name="arrow-left" />
        </button>
      </div>
    </header>

    <div class="build-strip" :class="`build-strip--${buildStatusTone}`" data-testid="build-status">
      <AppIcon class="status-dot" name="dot" :size="12" />
      <span>{{ buildStatus }}</span>
      <span class="build-strip__detail">core {{ coreShort }} · ui {{ uiShort }} · assets {{ bundleShort }}</span>
    </div>

    <main v-if="navigation.route === 'home'" class="screen-content home-screen">
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
          <label class="form-field host-field-name">
            <span>Host name or IP</span>
            <input v-model="hostDraft.hostname" data-testid="ssh-host" autocomplete="off" autocapitalize="none" placeholder="dev.example.com" />
          </label>
          <label class="form-field host-field-port">
            <span>Port</span>
            <input v-model="hostDraft.port" data-testid="ssh-port" type="number" inputmode="numeric" min="1" max="65535" />
          </label>
          <label class="form-field host-field-name">
            <span>User</span>
            <input v-model="hostDraft.username" data-testid="ssh-username" autocomplete="username" autocapitalize="none" placeholder="alexey" />
          </label>
          <label class="form-field host-field-key">
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
          <button class="action-button" type="button" data-testid="ssh-connect" :disabled="isConnecting" @click="connectHost">
            {{ isConnecting ? 'Connecting…' : 'Connect' }}
          </button>
          <button v-if="connectionSnapshot" class="action-button action-button--secondary" type="button" data-testid="ssh-disconnect" @click="disconnectHost">
            Disconnect
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
        <p class="panel-footnote">SSH host-key pins are saved locally. The private key is not stored by this preview.</p>
      </section>

      <section class="panel workspace-panel" aria-labelledby="sessions-title">
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
          @input="sendTerminalInput"
          @resize="resizeTerminal"
        />
        <PromptComposer
          v-if="connectionSnapshot?.selectedSession"
          :target-key="composerTargetKey"
          :target-label="connectionSnapshot.selectedSession.name"
          :transport-state="composerTransportState"
          :write-pty="writeComposerPty"
        />
        <p class="panel-footnote" data-testid="terminal-resize-status">{{ terminalResizeStatus }}</p>
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
    </main>

    <main v-else class="screen-content settings-screen">
      <section class="panel settings-panel" aria-labelledby="settings-title">
        <p class="eyebrow">POCKETSHELL</p>
        <h1 id="settings-title">Settings</h1>
        <p class="settings-copy">This is the JS-first Android shell. Product settings will arrive with their replacement issues.</p>
        <div class="settings-row"><span>Theme reference</span><strong>Desktop dark · GitHub palette</strong></div>
        <div class="settings-row"><span>Core formatter</span><strong>{{ coreSample }}</strong></div>
        <button class="action-button action-button--secondary" type="button" @click="navigation.back()">Back to hosts</button>
      </section>
    </main>
  </div>
</template>
