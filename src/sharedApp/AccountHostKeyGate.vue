<script setup lang="ts">
// The phone's key prompt for an account host (#3063), mounted once beside the
// shared AppRoot (AndroidAppRoot.vue) the way core mounts HostKeyTrustGate:
// a dial of an account host this phone has no key for waits here. Choosing a
// key saves the host on the phone (hostForm.ts) and the dial goes ahead;
// Cancel declines and nothing is dialled. Logic: accountHostKey.ts.
import { useDismissLayer } from '@ui/app/extensions';
import { useConnectionStore } from '@ui/app/stores/connection';
import { accountHostKeyPrompt as prompt } from './platform';

const connection = useConnectionStore();
const { state, form } = prompt;

function connect(): void {
  if (!prompt.confirm()) return;
  // The host is a phone host now: the picker lists it under the phone's group.
  void connection.loadHosts().catch(() => undefined);
}
</script>

<template>
  <div v-if="state.pending" :key="state.seq" class="account-key-scrim" data-testid="account-host-key-gate">
    <AccountHostKeyLayer @close="prompt.cancel()" />
    <form
      class="account-key-card"
      role="dialog"
      aria-labelledby="account-host-key-title"
      data-testid="account-host-key"
      @submit.prevent="connect"
    >
      <h2 id="account-host-key-title" class="account-key-title">Choose a key for {{ state.pending.name }}</h2>
      <p v-if="state.reason === 'missing-key'" class="account-key-body" data-testid="account-host-key-reason" data-reason="missing-key">
        The key this phone used for <code>{{ state.pending.hostname }}:{{ state.pending.port }}</code> is gone.
        Pick another key; the saved host keeps its name and gets the new key.
      </p>
      <p v-else class="account-key-body" data-testid="account-host-key-reason" data-reason="account">
        <code>{{ state.pending.hostname }}:{{ state.pending.port }}</code> is in your account.
        SSH keys never sync, so pick the key this phone uses for it.
      </p>
      <label class="account-key-field">User
        <input v-model="form.state.user" data-testid="account-host-key-user" autocomplete="off" autocapitalize="off" />
      </label>
      <label class="account-key-field">SSH key
        <select v-model="form.state.keyHandleId" data-testid="account-host-key-select">
          <option value="" disabled>{{ form.state.keys.length ? 'Choose a key' : 'No keys on this phone yet' }}</option>
          <option v-for="key in form.state.keys" :key="key.handleId" :value="key.handleId">{{ key.label }} · {{ key.fingerprintSha256 }}</option>
        </select>
      </label>
      <details class="account-key-import">
        <summary>Import a key file</summary>
        <label class="account-key-field">Key passphrase (if the key has one)
          <input v-model="form.state.keyPassphrase" data-testid="account-host-key-import-passphrase" type="password" autocomplete="off" />
        </label>
        <button class="btn-ghost account-key-button" type="button" data-testid="account-host-key-import" :disabled="form.state.importing" @click="form.importKey">
          Import key file…
        </button>
      </details>
      <p v-if="form.state.error" class="error" role="alert" data-testid="account-host-key-error">{{ form.state.error }}</p>
      <div class="account-key-actions">
        <button class="btn-ghost account-key-button" type="button" data-testid="account-host-key-cancel" @click="prompt.cancel()">Cancel</button>
        <button class="account-key-button account-key-primary" type="submit" data-testid="account-host-key-connect">Save and connect</button>
      </div>
    </form>
  </div>
</template>

<script lang="ts">
import { defineComponent } from 'vue';

/** Registers the open prompt as the topmost dismiss layer, so Android Back cancels it. */
const AccountHostKeyLayer = defineComponent({
  emits: ['close'],
  setup(_, { emit }) {
    useDismissLayer(() => emit('close'));
    return () => null;
  },
});
</script>

<style scoped>
.account-key-scrim {
  /* The visible viewport, like core's trust gate: a phone-wide layout must not carry the card off-screen. */
  position: fixed;
  top: 0;
  left: 0;
  width: 100vw;
  height: 100dvh;
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
  padding: var(--sp-4);
  background: var(--scrim);
}
.account-key-card {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  width: min(480px, 100%);
  max-height: 100%;
  overflow-y: auto;
  box-sizing: border-box;
  padding: var(--sp-4);
  background: var(--surface);
  border: 1px solid var(--border-strong);
  border-radius: var(--r-lg);
  color: var(--fg);
  box-shadow: var(--shadow-card);
}
.account-key-title {
  margin: 0;
  font-size: var(--fs-500);
  font-weight: var(--fw-semibold);
}
.account-key-body {
  margin: 0;
  color: var(--fg-secondary);
  font-size: var(--fs-300);
  line-height: 1.5;
}
.account-key-body code {
  font-family: var(--font-mono);
  color: var(--fg);
  overflow-wrap: anywhere;
}
.account-key-field {
  display: flex;
  flex-direction: column;
  gap: var(--sp-1);
  color: var(--fg-secondary);
  font-size: var(--fs-300);
}
.account-key-field input,
.account-key-field select {
  min-height: 48px;
  padding: var(--sp-2);
  background: var(--surface-2);
  color: var(--fg);
  border: 1px solid var(--border-strong);
  border-radius: var(--r-md);
  font: inherit;
}
.account-key-import summary {
  min-height: 48px;
  display: flex;
  align-items: center;
  color: var(--fg-secondary);
  cursor: pointer;
}
.account-key-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--sp-2);
}
.account-key-button {
  min-height: 48px;
  padding: 0 var(--sp-4);
  border-radius: var(--r-md);
  font-family: var(--font-ui);
  font-size: var(--fs-300);
}
.account-key-primary {
  background: var(--accent);
  color: var(--on-accent);
  border: none;
  font-weight: var(--fw-medium);
}
</style>
