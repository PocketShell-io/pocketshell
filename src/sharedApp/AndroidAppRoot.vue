<script setup lang="ts">
// The Android app root: core's shared AppRoot plus what only the phone adds
// around it (AppRoot's own contract: "a client wraps or mounts this component
// and adds those around it"): the account-host key prompt (#3063) and the
// key-vault unlock sheet (key-vault W4), app-wide like core's host-key gate
// because a dial is what raises them.
import { onMounted, watch } from 'vue';
import AppRoot from '@ui/app/AppRoot.vue';
import { useSyncStore } from '@ui/app/stores/sync';
import AccountHostKeyGate from './AccountHostKeyGate.vue';
import VaultUnlockGate from './VaultUnlockGate.vue';
import { androidVault, vaultSync, vaultUnlockPrompt } from './platform';

defineProps<{ monoFallback?: string }>();

const sync = useSyncStore();

// One passphrase (contract #2): an unlocked vault hands the account's sync
// passphrase to the shared Account screen too, so it never asks again.
watch(
  () => androidVault.state.status,
  (status) => {
    if (status !== 'unlocked' || vaultSync.passphrase === '' || sync.passphrase !== '') return;
    sync.passphrase = vaultSync.passphrase;
    void sync.loadAccount().catch(() => undefined);
  },
);

// Each launch: a signed-in account with a vault this phone does not
// remember asks for the passphrase once (Cancel leaves it locked; a dial asks
// again). A remembered vault unlocks silently.
onMounted(async () => {
  const status = await androidVault.refresh().catch(() => 'signed-out' as const);
  if (status === 'locked') void vaultUnlockPrompt.ask('account');
});
</script>

<template>
  <AppRoot :mono-fallback="monoFallback" />
  <AccountHostKeyGate />
  <VaultUnlockGate />
</template>
