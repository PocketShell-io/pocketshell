<script setup lang="ts">
// The key-vault unlock sheet (key-vault W4), mounted once beside the shared
// AppRoot like AccountHostKeyGate: a dial of a host whose key is in the locked
// account vault waits here, and so does "Unlock" on the account screen. It
// never offers a key picker. Logic: src/vault/vaultUnlockPrompt.ts.
import { computed } from 'vue';
import { useDismissLayer } from '@ui/app/extensions';
import { FORGOT_PASSPHRASE_TEXT } from '@/vault/vaultUnlockPrompt';
import { vaultUnlockPrompt as prompt } from './platform';

const { state } = prompt;
const title = computed(() => (state.reason === 'connect' ? 'Unlock your key vault to connect' : 'Unlock your key vault'));
</script>

<template>
  <div v-if="state.open" :key="state.seq" class="vault-scrim" data-testid="vault-unlock-gate">
    <VaultUnlockLayer @close="prompt.cancel()" />
    <form
      class="vault-card"
      role="dialog"
      aria-labelledby="vault-unlock-title"
      data-testid="vault-unlock"
      @submit.prevent="prompt.submit()"
    >
      <h2 id="vault-unlock-title" class="vault-title">{{ title }}</h2>
      <p class="vault-body">Your SSH keys are kept encrypted in your PocketShell account. Enter your vault passphrase to use them on this phone.</p>
      <label class="vault-field">Vault passphrase
        <input
          v-model="state.passphrase"
          data-testid="vault-unlock-passphrase"
          type="password"
          autocomplete="current-password"
          autocapitalize="off"
          :disabled="state.busy"
        />
      </label>
      <fieldset class="vault-choice">
        <legend class="vault-choice__legend">On this phone</legend>
        <label class="vault-radio">
          <input v-model="state.remember" type="radio" :value="false" data-testid="vault-unlock-type-every-time" />
          Type it every time
        </label>
        <label class="vault-radio">
          <input v-model="state.remember" type="radio" :value="true" data-testid="vault-unlock-remember" />
          Remember on this device
        </label>
      </fieldset>
      <p v-if="state.error" class="error" role="alert" data-testid="vault-unlock-error">{{ state.error }}</p>
      <div v-if="state.showForgot" class="vault-forgot" data-testid="vault-unlock-forgot-text">
        <p class="vault-body">{{ FORGOT_PASSPHRASE_TEXT }}</p>
        <button class="btn-ghost vault-button" type="button" data-testid="vault-unlock-reset" @click="prompt.openReset()">Reset on the web…</button>
      </div>
      <div class="vault-actions">
        <button v-if="!state.showForgot" class="btn-ghost vault-button vault-forgot-link" type="button" data-testid="vault-unlock-forgot" @click="prompt.forgot()">Forgot it?</button>
        <button class="btn-ghost vault-button" type="button" data-testid="vault-unlock-cancel" @click="prompt.cancel()">Cancel</button>
        <button class="vault-button vault-primary" type="submit" data-testid="vault-unlock-submit" :disabled="state.busy">
          {{ state.busy ? 'Unlocking…' : 'Unlock' }}
        </button>
      </div>
    </form>
  </div>
</template>

<script lang="ts">
import { defineComponent } from 'vue';

/** Registers the open sheet as the topmost dismiss layer, so Android Back cancels it. */
const VaultUnlockLayer = defineComponent({
  emits: ['close'],
  setup(_, { emit }) {
    useDismissLayer(() => emit('close'));
    return () => null;
  },
});
</script>

<style scoped>
.vault-scrim {
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
.vault-card {
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
.vault-title {
  margin: 0;
  font-size: var(--fs-500);
  font-weight: var(--fw-semibold);
}
.vault-body {
  margin: 0;
  color: var(--fg-secondary);
  font-size: var(--fs-300);
  line-height: 1.5;
}
.vault-field {
  display: flex;
  flex-direction: column;
  gap: var(--sp-1);
  color: var(--fg-secondary);
  font-size: var(--fs-300);
}
.vault-field input {
  min-height: 48px;
  padding: var(--sp-2);
  background: var(--surface-2);
  color: var(--fg);
  border: 1px solid var(--border-strong);
  border-radius: var(--r-md);
  font: inherit;
}
.vault-choice {
  display: flex;
  flex-direction: column;
  gap: var(--sp-1);
  margin: 0;
  padding: 0;
  border: none;
}
.vault-choice__legend {
  padding: 0;
  margin-bottom: var(--sp-1);
  color: var(--fg-secondary);
  font-size: var(--fs-300);
}
.vault-radio {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  min-height: 48px;
  color: var(--fg);
  font-size: var(--fs-300);
}
.vault-forgot {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
}
.vault-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: var(--sp-2);
}
.vault-forgot-link {
  margin-right: auto;
}
.vault-button {
  min-height: 48px;
  padding: 0 var(--sp-4);
  border-radius: var(--r-md);
  font-family: var(--font-ui);
  font-size: var(--fs-300);
}
.vault-primary {
  background: var(--accent);
  color: var(--on-accent);
  border: none;
  font-weight: var(--fw-medium);
}
</style>
