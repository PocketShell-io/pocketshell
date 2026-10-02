<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import AppIcon from '@ui/components/AppIcon.vue';
import { readInstalledAppInfo } from '../platform/androidAppInfo';
import { aboutBuildLine, type BuildCheckState } from '../session/releaseLabels';
import { useNavigationStore } from '../stores/navigation';

const props = defineProps<{
  buildState: BuildCheckState;
  coreRevision: string;
  bundleHash: string;
  /** Why the file check failed; empty unless `buildState` is `error`. */
  failureReason?: string;
}>();

const navigation = useNavigationStore();
const versionName = ref('');

onMounted(() => {
  void readInstalledAppInfo().then((info) => {
    versionName.value = info.versionName;
  });
});

/** The single plain-language build line (#3023): version, build, and its check. */
const buildLine = computed(() => aboutBuildLine(props.buildState, props.coreRevision, versionName.value));
</script>

<template>
  <main v-if="navigation.route === 'about'" class="screen-content settings-screen" data-testid="about-screen">
    <section class="panel settings-panel" aria-labelledby="about-title">
      <h1 id="about-title">About PocketShell</h1>
      <p class="settings-copy">A voice-first SSH client for working on your own machines from a phone.</p>
      <p
        class="settings-note"
        data-testid="about-build-identity"
        :data-build-state="buildState"
        :data-core-revision="coreRevision"
        :data-bundle-hash="bundleHash"
      >{{ buildLine }}</p>
      <div v-if="buildState === 'error'" class="integrity-error" role="alert" data-testid="about-integrity-error">
        <p>This copy of PocketShell failed its integrity check. Reinstall the app to fix this.</p>
        <small v-if="failureReason" data-testid="about-integrity-reason">Details for support: {{ failureReason }}</small>
      </div>
      <button class="settings-link" type="button" data-testid="open-update-status" @click="navigation.open('about-update')">
        <span class="settings-link__icon"><AppIcon name="download" /></span>
        <span><strong>Updates</strong><small>Check how to update PocketShell</small></span>
        <AppIcon class="settings-link__chevron" name="arrow-right" :size="16" />
      </button>
    </section>
  </main>

  <main v-else class="screen-content settings-screen" data-testid="update-screen">
    <section class="panel settings-panel" aria-labelledby="update-title">
      <h1 id="update-title">Updates</h1>
      <div class="settings-empty-state">
        <AppIcon name="download" :size="16" />
        <div><strong>Automatic updates are not available yet.</strong><p>Install new versions of PocketShell from the official release page.</p></div>
      </div>
    </section>
  </main>
</template>
