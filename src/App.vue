<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { App as CapacitorApp } from '@capacitor/app';
import { Capacitor } from '@capacitor/core';
import { formatBytes } from '@pocketshell/core';
import { AppIcon, ComposerControls } from '@pocketshell/ui';
import { verifyCurrentBuild, type BuildVerification } from './buildDiagnostics';
import { coreSourceRevision } from './coreSourceInfo';
import { uiSourceRevision } from './uiSourceInfo';
import { useNavigationStore } from './stores/navigation';
import TerminalPreview from './components/TerminalPreview.vue';

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

let removeBackButton: (() => Promise<void>) | undefined;

onMounted(() => {
  if (Capacitor.isNativePlatform()) {
    void CapacitorApp.addListener('backButton', () => {
      backButtonEvents.value += 1;
      if (navigation.route === 'settings') navigation.back();
      else void CapacitorApp.exitApp();
    }).then((listener) => {
      removeBackButton = () => listener.remove();
      backButtonReady.value = true;
    }).catch((error: unknown) => {
      console.error('Could not register the Android Back handler.', error);
    });
  }

  void verifyCurrentBuild(coreSourceRevision, uiSourceRevision).then((verification) => {
    buildVerification.value = verification;
  });
});

onBeforeUnmount(() => {
  void removeBackButton?.();
});
</script>

<template>
  <div
    class="app-shell"
    :data-route="navigation.route"
    :data-back-button-ready="backButtonReady"
    :data-back-button-events="backButtonEvents"
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
            <h1 id="hosts-title">Hosts</h1>
          </div>
          <span class="state-tag state-tag--muted">EMPTY</span>
        </div>
        <div class="empty-state">
          <AppIcon name="terminal" />
          <div>
            <h2>No host configured</h2>
            <p>Host setup and SSH transport are planned for later rewrite slices.</p>
          </div>
        </div>
        <button class="action-button" type="button" disabled>Connect to host</button>
      </section>

      <section class="panel workspace-panel" aria-labelledby="workspace-title">
        <div class="panel-heading">
          <div>
            <p class="eyebrow">WORKSPACE</p>
            <h2 id="workspace-title">No workspace selected</h2>
          </div>
          <span class="state-tag state-tag--muted">NO HOST</span>
        </div>
        <div class="workspace-placeholder">
          <div class="workspace-placeholder__icon" aria-hidden="true">
            <AppIcon name="folder" />
          </div>
          <p>Workspace and session lists will appear here after a host is connected.</p>
        </div>
      </section>

      <section class="panel terminal-panel" aria-labelledby="terminal-title">
        <div class="panel-heading panel-heading--terminal">
          <div>
            <p class="eyebrow">TERMINAL</p>
            <h2 id="terminal-title">Session preview</h2>
          </div>
          <span class="state-tag state-tag--warning">OFFLINE MOCK</span>
        </div>
        <TerminalPreview />
        <p class="panel-footnote">Read-only preview output. No SSH connection is open.</p>
      </section>

      <section class="panel composer-panel" aria-labelledby="composer-title">
        <div class="panel-heading">
          <div>
            <p class="eyebrow">COMPOSER</p>
            <h2 id="composer-title">Input preview</h2>
          </div>
          <span class="state-tag state-tag--muted">LOCAL ONLY</span>
        </div>
        <label class="sr-only" for="preview-input">Rewrite preview input</label>
        <input
          id="preview-input"
          class="preview-input"
          type="text"
          autocomplete="off"
          enterkeyhint="send"
          placeholder="Tap to check the Android keyboard"
        />
        <fieldset class="preview-control-boundary" disabled aria-label="Preview composer controls">
          <ComposerControls
            :uploading-count="0"
            :can-send="false"
            :send-in-flight="false"
            :draft-length="0"
            :attachment-count="0"
            :discard-armed="false"
          />
        </fieldset>
        <p class="panel-footnote">The input and shared controls are a local preview. Text is not sent or saved.</p>
      </section>

      <section class="panel diagnostics-panel" aria-labelledby="diagnostics-title">
        <div class="panel-heading">
          <div>
            <p class="eyebrow">BUILD</p>
            <h2 id="diagnostics-title">Source and asset diagnostics</h2>
          </div>
          <span class="state-tag" :class="buildStatusTone === 'error' ? 'state-tag--error' : 'state-tag--success'">
            {{ buildStatusTone === 'error' ? 'CHECK FAILED' : buildStatusTone === 'checking' ? 'CHECKING' : 'VERIFIED' }}
          </span>
        </div>
        <dl class="diagnostic-list">
          <div>
            <dt>pocketshell-core revision</dt>
            <dd data-testid="core-revision">{{ coreSourceRevision }}</dd>
          </div>
          <div>
            <dt>pocketshell-desktop shared UI revision</dt>
            <dd data-testid="ui-revision">{{ uiSourceRevision }}</dd>
          </div>
          <div>
            <dt>Bundled asset SHA-256</dt>
            <dd data-testid="bundle-asset-hash">
              {{ !('checking' in buildVerification) && buildVerification.ok ? buildVerification.bundleAssetHash : 'Pending verification' }}
            </dd>
          </div>
          <div>
            <dt>Core source function</dt>
            <dd>formatBytes(1536) → {{ coreSample }}</dd>
          </div>
        </dl>
        <p v-if="!('checking' in buildVerification) && !buildVerification.ok" class="integrity-error" role="alert">
          {{ buildVerification.reason }}
        </p>
        <p v-else class="panel-footnote">
          Missing or mismatched source and asset bytes stop the shell from presenting a verified state.
        </p>
      </section>

      <p class="rewrite-note">Unfinished rewrite preview · host, session and input data are mock/empty states.</p>
    </main>

    <main v-else class="screen-content settings-screen">
      <section class="panel settings-panel" aria-labelledby="settings-title">
        <p class="eyebrow">POCKETSHELL</p>
        <h1 id="settings-title">Settings</h1>
        <p class="settings-copy">This is the JS-first Android shell. Product settings will arrive with their replacement issues.</p>
        <div class="settings-row">
          <span>Theme reference</span>
          <strong>Desktop dark · GitHub palette</strong>
        </div>
        <div class="settings-row">
          <span>Core formatter</span>
          <strong>{{ coreSample }}</strong>
        </div>
        <button class="action-button action-button--secondary" type="button" @click="navigation.back()">
          Back to hosts
        </button>
      </section>
      <p class="rewrite-note">Android Back returns to Hosts from this screen.</p>
    </main>
  </div>
</template>
