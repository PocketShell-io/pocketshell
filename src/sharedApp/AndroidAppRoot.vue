<script setup lang="ts">
// The Android app root: core's shared AppRoot plus what only the phone adds
// around it (AppRoot's own contract: "a client wraps or mounts this component
// and adds those around it"). Today that is the account-host key prompt
// (#3063), app-wide like core's host-key gate because a dial is what raises it.
import AppRoot from '@ui/app/AppRoot.vue';
import AccountHostKeyGate from './AccountHostKeyGate.vue';

defineProps<{ monoFallback?: string }>();
</script>

<template>
  <AppRoot :mono-fallback="monoFallback" />
  <AccountHostKeyGate />
</template>
