<script setup lang="ts">
import { ref, watch } from 'vue';
import type { GatewayTransportTarget } from '@pocketshell/core';
import { gatewayPairing } from '@/native/gatewayPairing';
import { validatedAndroidGateway } from '@/platform/android/gatewayTarget';

const props = defineProps<{ target: unknown; keyHandleId: string }>();
const emit = defineEmits<{ paired: [] }>();
const fingerprint = ref('');
const message = ref('');
const busy = ref(false);
let generation = 0;
watch(() => [JSON.stringify(props.target), props.keyHandleId], () => { generation += 1; fingerprint.value = ''; message.value = ''; }, { flush: 'sync' });
async function pair() {
  if (busy.value) return;
  let target: GatewayTransportTarget;
  try { target = validatedAndroidGateway(props.target); }
  catch (error) { message.value = (error as Error).message; return; }
  if (!props.keyHandleId) { message.value = 'Choose an SSH key before pairing.'; return; }
  if (!/^SHA256:[A-Za-z0-9+/]{43}$/.test(fingerprint.value.trim())) {
    message.value = 'Enter the complete SHA256 host-key fingerprint from the host.'; return;
  }
  const keyHandleId = props.keyHandleId;
  const attemptGeneration = generation;
  const targetSnapshot = JSON.stringify(props.target);
  busy.value = true;
  message.value = '';
  try {
    const account = await gatewayPairing.currentAccount();
    if (!account.signedIn || !account.accountSubject) throw new Error('Sign in before pairing a gateway host.');
    if (attemptGeneration !== generation) return;
    await gatewayPairing.pair({ ...target, expectedAccountSubject: account.accountSubject, keyHandleId, fingerprintSha256: fingerprint.value.trim() });
    const current = await gatewayPairing.currentAccount();
    if (attemptGeneration !== generation || targetSnapshot !== JSON.stringify(props.target)
      || keyHandleId !== props.keyHandleId || current.accountSubject !== account.accountSubject || !current.signedIn) return;
    message.value = 'Host paired with this SSH key.';
    fingerprint.value = '';
    emit('paired');
  } catch (error) { if (attemptGeneration === generation) message.value = error instanceof Error ? error.message : 'The host could not be paired.'; }
  finally { busy.value = false; }
}
</script>

<template>
  <section class="panel" aria-labelledby="gateway-pairing-title" data-testid="gateway-pairing-panel">
    <h2 id="gateway-pairing-title">Pair gateway host</h2>
    <p>Copy the SSH host-key fingerprint from the host through a trusted connection. Pairing stores it on this phone for your signed-in account.</p>
    <label class="form-field">
      <span>SSH host-key fingerprint</span>
      <input v-model="fingerprint" data-testid="gateway-host-fingerprint" autocomplete="off" autocapitalize="none" placeholder="SHA256:…" :disabled="busy" />
    </label>
    <button class="action-button" type="button" data-testid="gateway-pair" :disabled="busy || !keyHandleId" @click="pair">
      {{ busy ? 'Pairing…' : 'Pair with selected SSH key' }}
    </button>
    <p v-if="message" role="status" data-testid="gateway-pairing-message">{{ message }}</p>
  </section>
</template>

<style scoped>
.panel { display: grid; gap: var(--sp-3, 12px); padding: var(--sp-3, 12px); border: 1px solid var(--border-strong, currentColor); border-radius: var(--r-md, 8px); }
h2, p { margin: 0; }
h2 { font-size: var(--fs-500, 18px); }
.form-field { display: grid; gap: var(--sp-1, 4px); }
input { min-height: 48px; padding: var(--sp-2, 8px); background: var(--surface-2); color: var(--fg); border: 1px solid var(--border-strong); border-radius: var(--r-md, 8px); font: inherit; }
.action-button { min-height: 48px; padding: var(--sp-2, 8px); background: var(--accent); color: var(--on-accent); border: 0; border-radius: var(--r-md, 8px); font: inherit; }
</style>
