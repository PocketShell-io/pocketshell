<script setup lang="ts">
// Account & sync on the phone (#3063): the shared AccountView — the same view
// the desktop opens as its Account window and the web serves at /account —
// under a phone app bar with a Back control, because a route on a phone has
// no window chrome to close. Signing in and unlocking with the passphrase
// here is what fills the picker's "From your account" group.
import { useRouter } from 'vue-router';
import AppIcon from '@ui/components/AppIcon.vue';
import AccountView from '@ui/app/views/AccountView.vue';

const router = useRouter();

function back(): void {
  if (router.options.history.state.back) router.back();
  else void router.push({ name: 'hosts' });
}
</script>

<template>
  <div class="android-account" data-testid="android-account">
    <header class="android-account__bar">
      <button class="android-account__back" type="button" aria-label="Back to hosts" data-testid="android-account-back" @click="back">
        <AppIcon name="arrow-left" />
        <span>Hosts</span>
      </button>
    </header>
    <AccountView />
  </div>
</template>

<style scoped>
.android-account__bar {
  display: flex;
  align-items: center;
  padding: var(--sp-1) var(--sp-2) 0;
}
.android-account__back {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-1);
  min-height: 48px;
  padding: 0 var(--sp-3) 0 var(--sp-1);
  border: none;
  border-radius: var(--r-md);
  background: transparent;
  color: var(--fg-secondary);
  font-family: var(--font-ui);
  font-size: var(--fs-300);
  cursor: pointer;
}
.android-account__back:hover {
  background: var(--state-hover);
  color: var(--fg);
}
</style>
