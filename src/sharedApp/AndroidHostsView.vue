<script setup lang="ts">
// The phone's host source, the Android counterpart of desktop's
// ~/.ssh/config and web's /app hosts page: add a host with its private key.
// The shared picker owns CONNECTING; this page only prepares hosts for it.
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import { useConnectionStore } from '@ui/app/stores/connection';
import { androidHosts } from './platform';
import { validateSavedHost } from '@/platform/android/hostStore';

const router = useRouter();
const connection = useConnectionStore();
const name = ref('');
const hostname = ref('');
const port = ref('22');
const user = ref('');
const privateKey = ref('');
const error = ref<string | null>(null);

async function save(): Promise<void> {
  const host = { name: name.value.trim() || hostname.value.trim(), hostname: hostname.value, port: Number(port.value), user: user.value };
  const problem = validateSavedHost(host) ?? (privateKey.value.trim() ? null : 'Paste the private key for this host.');
  if (problem) {
    error.value = problem;
    return;
  }
  try {
    androidHosts.save(host, privateKey.value);
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
    return;
  }
  await connection.loadHosts();
  void router.push({ name: 'hosts' });
}
</script>

<template>
  <main class="android-hosts" data-testid="android-add-host">
    <header class="android-hosts__head">
      <button class="btn-ghost" type="button" data-testid="android-add-host-back" @click="router.back()">Back</button>
      <h1>Add a host</h1>
    </header>
    <form class="android-hosts__form" @submit.prevent="save">
      <label>Name<input v-model="name" data-testid="host-name" autocomplete="off" placeholder="dev box" /></label>
      <label>Hostname<input v-model="hostname" data-testid="host-hostname" autocomplete="off" autocapitalize="off" inputmode="url" /></label>
      <label>Port<input v-model="port" data-testid="host-port" inputmode="numeric" /></label>
      <label>User<input v-model="user" data-testid="host-user" autocomplete="off" autocapitalize="off" /></label>
      <label>Private key<textarea v-model="privateKey" data-testid="host-private-key" rows="6" spellcheck="false" autocapitalize="off" placeholder="-----BEGIN OPENSSH PRIVATE KEY-----" /></label>
      <p class="muted">The key is kept for this app session only.</p>
      <p v-if="error" class="error" data-testid="host-error">{{ error }}</p>
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
.android-hosts__form textarea {
  min-height: 48px;
  padding: var(--sp-2);
  background: var(--surface-2);
  color: var(--fg);
  border: 1px solid var(--border-strong);
  border-radius: var(--r-md);
  font: inherit;
}
.android-hosts__form textarea {
  font-family: var(--font-mono);
  font-size: 12px;
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
