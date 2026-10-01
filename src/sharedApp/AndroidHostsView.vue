<script setup lang="ts">
// The phone's host source, the Android counterpart of desktop's
// ~/.ssh/config and web's /app hosts page: add a host and the key-vault key
// it authenticates with. Logic lives in hostForm.ts; the shared picker owns
// CONNECTING.
import { onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { useConnectionStore } from '@ui/app/stores/connection';
import { androidHosts, androidKeyManager } from '@/platform/android/hosts';
import { createHostForm } from './hostForm';

const router = useRouter();
const connection = useConnectionStore();
const { state, loadKeys, importKey, save } = createHostForm({ keys: androidKeyManager, hosts: androidHosts });

async function submit(): Promise<void> {
  if (!save()) return;
  await connection.loadHosts();
  void router.push({ name: 'hosts' });
}

onMounted(() => void loadKeys());
</script>

<template>
  <main class="android-hosts" data-testid="android-add-host">
    <header class="android-hosts__head">
      <button class="btn-ghost" type="button" data-testid="android-add-host-back" @click="router.back()">Back</button>
      <h1>Add a host</h1>
    </header>
    <form class="android-hosts__form" @submit.prevent="submit">
      <label>Name<input v-model="state.name" data-testid="host-name" autocomplete="off" placeholder="dev box" /></label>
      <label>Hostname<input v-model="state.hostname" data-testid="host-hostname" autocomplete="off" autocapitalize="off" inputmode="url" /></label>
      <label>Port<input v-model="state.port" data-testid="host-port" inputmode="numeric" /></label>
      <label>User<input v-model="state.user" data-testid="host-user" autocomplete="off" autocapitalize="off" /></label>
      <label>SSH key
        <select v-model="state.keyHandleId" data-testid="host-key">
          <option value="" disabled>Choose a key</option>
          <option v-for="key in state.keys" :key="key.handleId" :value="key.handleId">{{ key.label }} · {{ key.fingerprintSha256 }}</option>
        </select>
      </label>
      <details class="android-hosts__import">
        <summary>Import a key file</summary>
        <label>Key label<input v-model="state.keyLabel" data-testid="host-import-label" autocomplete="off" /></label>
        <label>Key passphrase (if the key has one)<input v-model="state.keyPassphrase" data-testid="host-import-passphrase" type="password" autocomplete="off" /></label>
        <button class="btn-ghost" type="button" data-testid="host-import-key" :disabled="state.importing" @click="importKey">Import key file…</button>
      </details>
      <p v-if="state.error" class="error" data-testid="host-error">{{ state.error }}</p>
      <button class="android-hosts__save" type="submit" data-testid="host-save">Save host</button>
    </form>
  </main>
</template>

<style scoped>
.android-hosts {
  padding: var(--sp-4);
  overflow-y: auto;
}
.android-hosts__head {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}
.android-hosts__head h1 {
  font-size: var(--fs-500, 18px);
  margin: 0;
}
.android-hosts__form {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  margin-top: var(--sp-4);
}
.android-hosts__form label {
  display: flex;
  flex-direction: column;
  gap: var(--sp-1);
  color: var(--fg-secondary);
}
.android-hosts__form input,
.android-hosts__form select {
  min-height: 48px;
  padding: var(--sp-2);
  background: var(--surface-2);
  color: var(--fg);
  border: 1px solid var(--border-strong);
  border-radius: var(--r-md);
  font: inherit;
}
.android-hosts__save {
  min-height: 48px;
  background: var(--accent);
  color: var(--on-accent);
  border: none;
  border-radius: var(--r-md);
  font-weight: var(--fw-medium);
}
</style>
