<script setup lang="ts">
/**
 * The first-run sign-in affordance (issue #3047). #3020 shipped Google
 * sign-in and host sync, but it lived only under Settings → Account & sync,
 * so a fresh phone's easiest path to its hosts was invisible: the empty home
 * screen only offered manual key entry. This is a secondary action rendered
 * under the "Add an SSH key to connect" card; it only navigates — sign-in
 * itself, the Credential Manager call and sync all stay in the Account &
 * sync screen (#3020), so nothing is duplicated here.
 */
import { computed } from 'vue';
import AppIcon from '@ui/components/AppIcon.vue';

const props = defineProps<{
  /** The phone already shows a host: saved from a previous install or synced from an account. */
  hasHosts: boolean;
  /** Google sign-in state from the sync adapter (#3020); unknown while null, and then hidden. */
  signedIn: boolean | null;
}>();

const emit = defineEmits<{ signIn: [] }>();

/** Only the empty, signed-out phone needs the pointer to sign-in. */
const visible = computed(() => props.signedIn === false && !props.hasHosts);
</script>

<template>
  <button
    v-if="visible"
    class="empty-state-sign-in"
    type="button"
    data-testid="empty-state-sign-in"
    @click="emit('signIn')"
  >
    <AppIcon name="folder" :size="16" />
    <span>
      <strong>Sign in with Google to restore your synced hosts</strong>
      <small>Opens Settings → Account &amp; sync</small>
    </span>
    <AppIcon class="empty-state-sign-in__chevron" name="arrow-right" :size="16" />
  </button>
</template>

<style scoped>
/* A quiet secondary action under the empty state's "Add an SSH key to
   connect" card (#3047): the settings-list row language, not the card's
   accent-filled primary button, so the two actions don't compete. Shared
   tokens only (docs/design-system.md). */
.empty-state-sign-in {
  display: grid;
  width: 100%;
  min-height: 48px;
  grid-template-columns: 16px minmax(0, 1fr) 16px;
  align-items: center;
  gap: 10px;
  margin-top: 10px;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  background: var(--surface-2);
  padding: 8px 12px;
  color: var(--fg);
  text-align: left;
}

.empty-state-sign-in > span {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.empty-state-sign-in strong {
  font-size: var(--fs-300);
  font-weight: 600;
}

.empty-state-sign-in small {
  overflow: hidden;
  color: var(--fg-secondary);
  font-size: var(--fs-200);
  line-height: 1.45;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.empty-state-sign-in > svg:first-child,
.empty-state-sign-in__chevron {
  color: var(--fg-secondary);
}

.empty-state-sign-in:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 2px;
}
</style>
