<script setup lang="ts">
// The Android app root: core's shared AppRoot plus what only the phone adds
// around it (AppRoot's own contract: "a client wraps or mounts this component
// and adds those around it"): the account-host key prompt (#3063). The vault
// unlock sheet is core's own (AppRoot mounts it; main.ts provides the vault).
import { watch } from 'vue';
import AppRoot from '@ui/app/AppRoot.vue';
import AndroidAttachmentBridge from './AndroidAttachmentBridge.vue';
import { useSyncStore } from '@ui/app/stores/sync';
import AccountHostKeyGate from './AccountHostKeyGate.vue';
import { androidVault, vaultSync } from './platform';

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

// Account entry, remembered unlock and first setup have one owner: the shared
// server picker. Android must not independently open a launch-time vault sheet.
</script>

<template>
  <AppRoot :mono-fallback="monoFallback" />
  <AccountHostKeyGate />
  <AndroidAttachmentBridge />
</template>
