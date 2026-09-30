<script setup lang="ts">
import type { BuildVerification } from '../buildDiagnostics';
import { AppIcon } from '@pocketshell/ui';
import { useNavigationStore } from '../stores/navigation';

defineProps<{
  buildVerification: BuildVerification | { checking: true };
  coreRevision: string;
  uiRevision: string;
  bundleHash: string;
  buildStatus: string;
}>();

const navigation = useNavigationStore();
</script>

<template>
  <main v-if="navigation.route === 'about'" class="screen-content settings-screen" data-testid="about-screen">
    <section class="panel settings-panel" aria-labelledby="about-title">
      <p class="eyebrow">POCKETSHELL · REWRITE PREVIEW</p>
      <h1 id="about-title">About PocketShell</h1>
      <p class="settings-copy">Build identity for this installed JS-first preview.</p>
      <dl class="diagnostic-list about-identity">
        <div><dt>App version</dt><dd>0.6.0 rewrite preview</dd></div>
        <div><dt>Build status</dt><dd data-testid="about-build-status">{{ buildStatus }}</dd></div>
        <div><dt>pocketshell-core revision</dt><dd data-testid="about-core-revision">{{ coreRevision }}</dd></div>
        <div><dt>Shared desktop UI revision</dt><dd data-testid="about-ui-revision">{{ uiRevision }}</dd></div>
        <div><dt>Bundled asset SHA-256</dt><dd data-testid="about-bundle-hash">{{ bundleHash }}</dd></div>
      </dl>
      <p v-if="!('checking' in buildVerification) && !buildVerification.ok" class="integrity-error" role="alert">{{ buildVerification.reason }}</p>
      <button class="settings-link" type="button" data-testid="open-update-status" @click="navigation.open('about-update')">
        <span class="settings-link__icon"><AppIcon name="download" /></span>
        <span><strong>Updates</strong><small>Current update support status</small></span>
        <AppIcon class="settings-link__chevron" name="arrow-right" :size="16" />
      </button>
    </section>
  </main>

  <main v-else class="screen-content settings-screen" data-testid="update-screen">
    <section class="panel settings-panel" aria-labelledby="update-title">
      <p class="eyebrow">ABOUT · UPDATES</p>
      <h1 id="update-title">Updates</h1>
      <div class="settings-empty-state">
        <AppIcon name="download" :size="16" />
        <div><strong>Update check is unavailable in this preview.</strong><p>This screen does not contact a release service or install an APK. Use the validated release process for updates.</p></div>
      </div>
    </section>
  </main>
</template>
