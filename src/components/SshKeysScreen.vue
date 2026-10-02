<script setup lang="ts">
import { computed, ref } from 'vue';
import AppIcon from '@ui/components/AppIcon.vue';
import {
  type CredentialKeyManager,
  type DeleteKeyResult,
} from '@/credentials/keyManagement';
import type { SshKeyMetadata } from '@/native/sshKeyVault';

const props = defineProps<{
  manager: CredentialKeyManager;
  keys: SshKeyMetadata[];
  selectedHandleId: string;
  loadError: string;
}>();

const emit = defineEmits<{
  refresh: [];
  select: [handleId: string];
}>();

const action = ref<'import' | 'generate'>('import');
const label = ref('');
const passphrase = ref('');
const algorithm = ref<'Ed25519' | 'RSA-3072'>('Ed25519');
const busy = ref(false);
const message = ref('');
const error = ref('');
const pendingDelete = ref<{ key: SshKeyMetadata; result: Extract<DeleteKeyResult, { status: 'confirmation-required' }> } | null>(null);
const generateLabel = computed(() => label.value.trim() || `${algorithm.value} key`);

async function importKey() {
  busy.value = true;
  error.value = '';
  message.value = '';
  try {
    const key = await props.manager.pickAndImport({ label: label.value, passphrase: passphrase.value });
    if (key) {
      label.value = '';
      message.value = `${key.label} is stored in Android secure storage.`;
      emit('select', key.handleId);
      emit('refresh');
    }
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'The SSH key could not be imported.';
  } finally {
    passphrase.value = '';
    busy.value = false;
  }
}

async function generateKey() {
  busy.value = true;
  error.value = '';
  message.value = '';
  try {
    const key = await props.manager.generate({ label: generateLabel.value, algorithm: algorithm.value });
    label.value = '';
    message.value = `${key.label} was generated and stored in Android secure storage.`;
    emit('select', key.handleId);
    emit('refresh');
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'The SSH key could not be generated.';
  } finally {
    busy.value = false;
  }
}

async function requestDelete(key: SshKeyMetadata) {
  error.value = '';
  message.value = '';
  try {
    const result = await props.manager.delete(key.handleId);
    if (result.status === 'confirmation-required') {
      pendingDelete.value = { key, result };
      return;
    }
    finishDelete(key.handleId, result);
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'The SSH key could not be removed.';
  }
}

async function confirmDelete() {
  const pending = pendingDelete.value;
  if (!pending) return;
  busy.value = true;
  error.value = '';
  try {
    const result = await props.manager.delete(pending.key.handleId, pending.result.affectedHosts);
    if (result.status === 'confirmation-required') {
      pendingDelete.value = { key: pending.key, result };
      return;
    }
    finishDelete(pending.key.handleId, result);
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'The SSH key could not be removed.';
  } finally {
    busy.value = false;
    pendingDelete.value = null;
  }
}

function finishDelete(handleId: string, result: Extract<DeleteKeyResult, { status: 'deleted' }>) {
  if (props.selectedHandleId === handleId) emit('select', '');
  const hosts = result.affectedHosts.length;
  message.value = hosts > 0
    ? `Key removed. ${hosts} host ${hosts === 1 ? 'association was' : 'associations were'} cleared.`
    : 'Key removed.';
  emit('refresh');
}

function cancelDelete() {
  pendingDelete.value = null;
}
</script>

<template>
  <main class="screen-content key-screen" data-testid="ssh-keys-screen">
    <section class="key-screen__intro">
      <h1>SSH keys</h1>
      <p>Private key files stay encrypted in Android storage. PocketShell keeps only their labels and public fingerprints in the interface.</p>
    </section>

    <section class="key-panel panel" aria-labelledby="ssh-key-list-title">
      <div class="panel-heading">
        <div>
          <h2 id="ssh-key-list-title">Stored keys</h2>
        </div>
        <span class="state-tag state-tag--muted" data-testid="ssh-key-count">{{ keys.length }}</span>
      </div>
      <p v-if="loadError" class="connection-message" role="alert" data-testid="ssh-key-load-error">{{ loadError }}</p>

      <div v-if="keys.length === 0" class="key-empty" data-testid="ssh-key-empty">
        <AppIcon name="file" :size="16" />
        <div>
          <strong>No SSH keys yet</strong>
          <p>Import a private key or generate an Ed25519 or RSA key.</p>
        </div>
      </div>
      <ul v-else class="key-list">
        <li v-for="key in keys" :key="key.handleId" class="key-row" :data-testid="`ssh-key-${key.handleId}`">
          <button
            class="key-row__select"
            type="button"
            :aria-pressed="selectedHandleId === key.handleId"
            :data-testid="`select-ssh-key-${key.handleId}`"
            @click="emit('select', key.handleId)"
          >
            <AppIcon name="file" :size="16" />
            <span class="key-row__copy">
              <strong>{{ key.label }}</strong>
              <span class="key-row__details">{{ key.algorithm }}<span v-if="key.passphraseRequired"> · passphrase required</span></span>
              <code>{{ key.fingerprintSha256 }}</code>
            </span>
            <AppIcon v-if="selectedHandleId === key.handleId" name="check" :size="16" />
          </button>
          <button
            class="icon-button key-row__delete"
            type="button"
            :aria-label="`Delete ${key.label}`"
            :data-testid="`delete-ssh-key-${key.handleId}`"
            @click="requestDelete(key)"
          ><AppIcon name="trash-2" /></button>
        </li>
      </ul>

      <section v-if="pendingDelete" class="key-confirm" role="alertdialog" aria-labelledby="key-delete-title" data-testid="ssh-key-delete-confirmation">
        <strong id="key-delete-title">Remove {{ pendingDelete.key.label }}?</strong>
        <p>This key is attached to these saved hosts. Their key association will be cleared before the key is deleted:</p>
        <ul>
          <li v-for="host in pendingDelete.result.affectedHosts" :key="host.hostId">{{ host.hostLabel }}</li>
        </ul>
        <div class="key-actions">
          <button class="small-action" type="button" data-testid="cancel-delete-ssh-key" :disabled="busy" @click="cancelDelete">Cancel</button>
          <button class="small-action key-delete-confirm" type="button" data-testid="confirm-delete-ssh-key" :disabled="busy" @click="confirmDelete">
            {{ busy ? 'Removing…' : 'Remove associations and delete key' }}
          </button>
        </div>
      </section>
    </section>

    <section class="key-panel panel" aria-labelledby="ssh-key-add-title">
      <div class="panel-heading">
        <div>
          <h2 id="ssh-key-add-title">Import or generate</h2>
        </div>
      </div>
      <div class="key-tabs" role="tablist" aria-label="SSH key operation">
        <button type="button" role="tab" :aria-selected="action === 'import'" data-testid="ssh-key-import-tab" @click="action = 'import'">Import</button>
        <button type="button" role="tab" :aria-selected="action === 'generate'" data-testid="ssh-key-generate-tab" @click="action = 'generate'">Generate</button>
      </div>
      <label class="form-field key-field">
        <span>Key label</span>
        <input v-model="label" data-testid="ssh-key-label" autocomplete="off" maxlength="80" placeholder="Personal laptop" />
      </label>
      <template v-if="action === 'import'">
        <label class="form-field key-field">
          <span>Passphrase, if required</span>
          <input v-model="passphrase" data-testid="ssh-key-import-passphrase" type="password" autocomplete="off" />
        </label>
        <p class="key-helper">Supported key algorithms: Ed25519 and RSA. Key files are read directly from the Android document provider.</p>
        <button class="action-button" type="button" data-testid="import-ssh-key" :disabled="busy" @click="importKey">
          {{ busy ? 'Importing…' : 'Choose private key' }}
        </button>
      </template>
      <template v-else>
        <label class="form-field key-field">
          <span>Algorithm</span>
          <select v-model="algorithm" data-testid="ssh-key-algorithm">
            <option value="Ed25519">Ed25519</option>
            <option value="RSA-3072">RSA 3072</option>
          </select>
        </label>
        <p class="key-helper">Generated keys are stored in Android secure storage. Public fingerprints identify the selected key.</p>
        <button class="action-button" type="button" data-testid="generate-ssh-key" :disabled="busy" @click="generateKey">
          {{ busy ? 'Generating…' : `Generate ${algorithm}` }}
        </button>
      </template>
      <p v-if="error" class="connection-message" role="alert" data-testid="ssh-key-error">{{ error }}</p>
      <p v-else-if="message" class="key-success" role="status" data-testid="ssh-key-message">{{ message }}</p>
    </section>
  </main>
</template>

<style scoped>
.key-screen { display: grid; align-content: start; gap: var(--sp-4); }
.key-screen__intro { display: grid; gap: var(--sp-2); padding: var(--sp-1) var(--sp-1) 0; }
.key-screen__intro h1 { margin: 0; font-size: var(--fs-600); font-weight: var(--fw-semibold); line-height: var(--lh-600); }
.key-screen__intro > p:last-child { max-width: 54rem; margin: 0; color: var(--fg-secondary); font-size: var(--fs-300); line-height: 1.5; }
.key-panel { padding: var(--sp-4); }
.key-panel .panel-heading { align-items: center; margin-bottom: var(--sp-3); }
.key-panel .panel-heading h2 { margin: 0; font-size: var(--fs-500); font-weight: var(--fw-medium); }
.key-empty { display: flex; align-items: center; gap: var(--sp-3); border: 1px solid var(--border-soft); border-radius: var(--r-md); padding: var(--sp-4); color: var(--fg-secondary); }
.key-empty strong { color: var(--fg); font-size: var(--fs-300); font-weight: var(--fw-medium); }
.key-empty p { margin: var(--sp-1) 0 0; color: var(--fg-secondary); font-size: var(--fs-200); line-height: 1.45; }
.key-list { display: grid; gap: var(--sp-2); margin: 0; padding: 0; list-style: none; }
.key-row { display: grid; grid-template-columns: minmax(0, 1fr) 48px; align-items: center; gap: var(--sp-2); border: 1px solid var(--border-soft); border-radius: var(--r-lg); background: var(--bg); padding: var(--sp-2); }
.key-row__select { display: flex; min-width: 0; min-height: 56px; align-items: center; gap: var(--sp-3); border: 0; border-radius: var(--r-md); background: transparent; padding: var(--sp-2); color: var(--fg-secondary); text-align: left; }
.key-row__select[aria-pressed="true"] { background: var(--state-selected); color: var(--accent); }
.key-row__copy { display: grid; min-width: 0; flex: 1 1 auto; gap: var(--sp-1); }
.key-row__copy strong { overflow: hidden; color: var(--fg); font-size: var(--fs-300); font-weight: var(--fw-medium); text-overflow: ellipsis; white-space: nowrap; }
.key-row__details { color: var(--fg-secondary); font-size: var(--fs-200); }
.key-row__copy code { overflow: hidden; color: var(--fg-muted); font: var(--fs-100)/var(--lh-100) var(--font-mono); text-overflow: ellipsis; white-space: nowrap; }
.key-row__delete { width: 48px; height: 48px; color: var(--error); }
.key-tabs { display: grid; grid-template-columns: 1fr 1fr; gap: var(--sp-1); border: 1px solid var(--border); border-radius: var(--r-md); background: var(--surface-2); padding: var(--sp-1); margin-bottom: var(--sp-3); }
.key-tabs button { min-height: 44px; border: 0; border-radius: var(--r-sm); background: transparent; color: var(--fg-secondary); font-size: var(--fs-300); font-weight: var(--fw-medium); }
.key-tabs button[aria-selected="true"] { background: var(--surface); color: var(--fg); box-shadow: inset 0 0 0 1px var(--border); }
.key-field { margin-bottom: var(--sp-3); }
.key-field input, .key-field select { min-height: 48px; border: 1px solid var(--border-strong); border-radius: var(--r-md); background: var(--bg); padding: 0 var(--sp-3); color: var(--fg); font: var(--fs-300)/1.4 var(--font-mono); }
.key-helper { margin: 0 0 var(--sp-3); color: var(--fg-secondary); font-size: var(--fs-200); line-height: 1.5; }
.key-actions { display: flex; justify-content: flex-end; gap: var(--sp-2); }
.key-confirm { margin-top: var(--sp-3); border: 1px solid var(--warning); border-radius: var(--r-md); background: var(--warning-soft); padding: var(--sp-3); }
.key-confirm > strong { color: var(--fg); font-size: var(--fs-300); }
.key-confirm p { margin: var(--sp-2) 0; color: var(--fg-secondary); font-size: var(--fs-200); line-height: 1.45; }
.key-confirm ul { display: grid; gap: var(--sp-1); margin: 0; padding-left: var(--sp-4); color: var(--fg); font-size: var(--fs-300); }
.key-delete-confirm { border-color: var(--error); color: var(--error); }
.key-success { margin: var(--sp-3) 0 0; color: var(--success); font-size: var(--fs-200); line-height: 1.45; }
.key-panel > .action-button { min-height: 48px; }
</style>
