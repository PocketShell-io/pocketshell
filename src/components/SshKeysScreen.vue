<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref } from 'vue';
import AppIcon from '@ui/components/AppIcon.vue';
import KeyIcon from './KeyIcon.vue';
import {
  type CredentialKeyManager,
  type DeleteKeyResult,
} from '@/credentials/keyManagement';
import type { AuthorizedKeyInstallResult } from '@/credentials/authorizedKeys';
import type { SshKeyMetadata } from '@/native/sshKeyVault';

export interface SshKeyHostInstallTarget {
  /** `user@host` of the live connection the key would be installed on. */
  hostLabel: string;
  install(publicKey: string): Promise<AuthorizedKeyInstallResult>;
}

const props = defineProps<{
  manager: CredentialKeyManager;
  keys: SshKeyMetadata[];
  selectedHandleId: string;
  loadError: string;
  hostInstall?: SshKeyHostInstallTarget | null;
}>();

const emit = defineEmits<{
  refresh: [];
  /** `stay` keeps the key screen open (a freshly generated key shows its public half). */
  select: [handleId: string, stay?: boolean];
}>();

const action = ref<'paste' | 'import' | 'generate'>('paste');
const label = ref('');
const passphrase = ref('');
const algorithm = ref<'Ed25519' | 'RSA-3072'>('Ed25519');
const busy = ref(false);
const message = ref('');
const error = ref('');
const pendingDelete = ref<{ key: SshKeyMetadata; result: Extract<DeleteKeyResult, { status: 'confirmation-required' }> } | null>(null);
const generateLabel = computed(() => label.value.trim() || `${algorithm.value} key`);

// The pasted private key lives only in the uncontrolled <textarea>: no
// reactive copy, no store. It is read once on Import and the field is
// cleared straight after, whatever the outcome (#3021).
const pasteInput = ref<HTMLTextAreaElement | null>(null);
const pasteHasText = ref(false);

// Public-key panel (#3021): public data only.
const expandedHandleId = ref('');
const publicLines = ref<Record<string, string>>({});
const publicBusy = ref(false);
const publicMessage = ref('');
const publicError = ref('');
const publicPassphraseNeeded = ref('');
const publicPassphrase = ref('');
const pendingInstallHandleId = ref('');

function clearPastedKey() {
  if (pasteInput.value) pasteInput.value.value = '';
  pasteHasText.value = false;
}

function notePasteInput() {
  pasteHasText.value = Boolean(pasteInput.value?.value.trim());
}

function chooseAction(next: 'paste' | 'import' | 'generate') {
  clearPastedKey();
  action.value = next;
  error.value = '';
  message.value = '';
}

function keepFieldVisible(event: FocusEvent) {
  // Let the IME finish resizing the viewport before centring the field.
  const target = event.target as HTMLElement | null;
  window.setTimeout(() => target?.scrollIntoView?.({ block: 'center', behavior: 'smooth' }), 300);
}

onBeforeUnmount(clearPastedKey);

function failureMessage(cause: unknown, fallback: string): string {
  return cause instanceof Error && cause.message ? cause.message : fallback;
}

async function importPastedKey() {
  const text = pasteInput.value?.value ?? '';
  clearPastedKey();
  busy.value = true;
  error.value = '';
  message.value = '';
  try {
    const key = await props.manager.importText({ text, label: label.value, passphrase: passphrase.value });
    label.value = '';
    message.value = `${key.label} is stored in Android secure storage.`;
    emit('select', key.handleId);
    emit('refresh');
  } catch (cause) {
    error.value = `${failureMessage(cause, 'The SSH key could not be imported.')} The pasted text was cleared; paste it again to retry.`;
  } finally {
    passphrase.value = '';
    busy.value = false;
  }
}

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
    error.value = failureMessage(cause, 'The SSH key could not be imported.');
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
    message.value = `${key.label} was generated. Add its public key to your server to use it.`;
    emit('select', key.handleId, true);
    emit('refresh');
    await showPublicKey(key);
  } catch (cause) {
    error.value = failureMessage(cause, 'The SSH key could not be generated.');
  } finally {
    busy.value = false;
  }
}

function resetPublicState() {
  publicMessage.value = '';
  publicError.value = '';
  publicPassphraseNeeded.value = '';
  publicPassphrase.value = '';
  pendingInstallHandleId.value = '';
}

async function togglePublicKey(key: SshKeyMetadata) {
  if (expandedHandleId.value === key.handleId) {
    expandedHandleId.value = '';
    resetPublicState();
    return;
  }
  await showPublicKey(key);
}

function needsPassphrase(cause: unknown): boolean {
  return typeof cause === 'object' && cause !== null && (cause as { code?: unknown }).code === 'KEY_PASSPHRASE_REQUIRED';
}

async function showPublicKey(key: Pick<SshKeyMetadata, 'handleId' | 'algorithm'>) {
  expandedHandleId.value = key.handleId;
  resetPublicState();
  if (publicLines.value[key.handleId]) return;
  await loadPublicKey(key);
}

async function loadPublicKey(key: Pick<SshKeyMetadata, 'handleId' | 'algorithm'>) {
  publicBusy.value = true;
  publicError.value = '';
  const secret = publicPassphrase.value;
  publicPassphrase.value = '';
  try {
    const line = await props.manager.publicKey(key, secret || undefined);
    publicLines.value = { ...publicLines.value, [key.handleId]: line };
    publicPassphraseNeeded.value = '';
  } catch (cause) {
    if (needsPassphrase(cause)) publicPassphraseNeeded.value = key.handleId;
    publicError.value = failureMessage(cause, 'The public key could not be read.');
  } finally {
    publicBusy.value = false;
  }
}

async function copyPublicKey(key: SshKeyMetadata) {
  publicBusy.value = true;
  publicError.value = '';
  publicMessage.value = '';
  try {
    await props.manager.copyPublicKey(key.handleId);
    publicMessage.value = 'Public key copied. Paste it into ~/.ssh/authorized_keys on your server.';
  } catch (cause) {
    publicError.value = failureMessage(cause, 'The public key could not be copied.');
  } finally {
    publicBusy.value = false;
  }
}

async function sharePublicKey(key: SshKeyMetadata) {
  publicBusy.value = true;
  publicError.value = '';
  publicMessage.value = '';
  try {
    await props.manager.sharePublicKey(key.handleId);
  } catch (cause) {
    publicError.value = failureMessage(cause, 'The public key could not be shared.');
  } finally {
    publicBusy.value = false;
  }
}

function requestInstall(key: SshKeyMetadata) {
  publicError.value = '';
  publicMessage.value = '';
  pendingInstallHandleId.value = key.handleId;
  // The confirmation renders below the fold on a phone; bring it into view.
  void nextTick(() => {
    const dialog = typeof document === 'undefined' ? null : document.querySelector('[data-testid=ssh-key-install-confirmation]');
    dialog?.scrollIntoView?.({ block: 'center' });
  });
}

async function confirmInstall(key: SshKeyMetadata) {
  const target = props.hostInstall;
  const line = publicLines.value[key.handleId];
  if (!target || !line) return;
  publicBusy.value = true;
  publicError.value = '';
  publicMessage.value = '';
  try {
    const result = await target.install(line);
    publicMessage.value = result === 'installed'
      ? `Installed on ${target.hostLabel}. You can now connect with ${key.label}.`
      : `${key.label} is already in ~/.ssh/authorized_keys on ${target.hostLabel}. Nothing was changed.`;
  } catch (cause) {
    publicError.value = failureMessage(cause, 'The key could not be installed on the host.');
  } finally {
    pendingInstallHandleId.value = '';
    publicBusy.value = false;
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
  if (expandedHandleId.value === handleId) {
    expandedHandleId.value = '';
    resetPublicState();
  }
  const { [handleId]: _removed, ...remainingLines } = publicLines.value;
  publicLines.value = remainingLines;
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
      <p>Private keys stay encrypted in Android storage. PocketShell shows only their labels, fingerprints and public keys.</p>
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
        <KeyIcon :size="20" />
        <div>
          <strong>No SSH keys yet</strong>
          <p>Paste a private key, import a key file, or generate a new key below.</p>
        </div>
      </div>
      <ul v-else class="key-list">
        <li v-for="key in keys" :key="key.handleId" class="key-row" :data-testid="`ssh-key-${key.handleId}`">
          <div class="key-row__main">
            <button
              class="key-row__select"
              type="button"
              :aria-pressed="selectedHandleId === key.handleId"
              :data-testid="`select-ssh-key-${key.handleId}`"
              @click="emit('select', key.handleId)"
            >
              <KeyIcon :size="18" />
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
          </div>
          <button
            class="key-row__public-toggle"
            type="button"
            :aria-expanded="expandedHandleId === key.handleId"
            :aria-controls="`ssh-public-key-panel-${key.handleId}`"
            :data-testid="`toggle-ssh-public-key-${key.handleId}`"
            @click="togglePublicKey(key)"
          >
            <span>Public key · copy, share or install</span>
            <AppIcon :name="expandedHandleId === key.handleId ? 'chevron-up' : 'chevron-down'" :size="16" />
          </button>
          <section
            v-if="expandedHandleId === key.handleId"
            :id="`ssh-public-key-panel-${key.handleId}`"
            class="key-public"
            :data-testid="`ssh-public-key-panel-${key.handleId}`"
          >
            <p class="key-public__hint">Add this line to <code>~/.ssh/authorized_keys</code> on each server you want to reach.</p>
            <code v-if="publicLines[key.handleId]" class="key-public__line" :data-testid="`ssh-public-key-${key.handleId}`">{{ publicLines[key.handleId] }}</code>
            <p v-else-if="publicBusy" class="key-public__hint">Reading the public key…</p>
            <template v-if="publicPassphraseNeeded === key.handleId">
              <label class="form-field key-field">
                <span>Key passphrase · used once to read the public key</span>
                <input v-model="publicPassphrase" :data-testid="`ssh-public-key-passphrase-${key.handleId}`" type="password" autocomplete="off" @focus="keepFieldVisible" />
              </label>
              <button class="small-action key-action" type="button" :disabled="publicBusy || !publicPassphrase" @click="loadPublicKey(key)">Show public key</button>
            </template>
            <div v-if="publicLines[key.handleId]" class="key-public__actions">
              <button class="small-action key-action" type="button" :data-testid="`copy-ssh-public-key-${key.handleId}`" :disabled="publicBusy" @click="copyPublicKey(key)">Copy</button>
              <button class="small-action key-action" type="button" :data-testid="`share-ssh-public-key-${key.handleId}`" :disabled="publicBusy" @click="sharePublicKey(key)">Share</button>
              <button
                v-if="hostInstall"
                class="small-action key-action key-action--wide"
                type="button"
                :data-testid="`install-ssh-public-key-${key.handleId}`"
                :disabled="publicBusy"
                @click="requestInstall(key)"
              >Install on {{ hostInstall.hostLabel }}</button>
            </div>
            <p v-if="publicLines[key.handleId] && !hostInstall" class="key-public__hint" :data-testid="`ssh-public-key-install-hint-${key.handleId}`">Connect to a host to install this key there in one step.</p>
            <section v-if="hostInstall && pendingInstallHandleId === key.handleId" class="key-confirm" role="alertdialog" :aria-labelledby="`install-title-${key.handleId}`" data-testid="ssh-key-install-confirmation">
              <strong :id="`install-title-${key.handleId}`">Install {{ key.label }} on {{ hostInstall.hostLabel }}?</strong>
              <p>PocketShell will add this public key to <code>~/.ssh/authorized_keys</code> on the connected host. A key that is already listed is left unchanged.</p>
              <div class="key-actions">
                <button class="small-action key-action" type="button" data-testid="cancel-install-ssh-key" :disabled="publicBusy" @click="pendingInstallHandleId = ''">Cancel</button>
                <button class="small-action key-action key-install-confirm" type="button" data-testid="confirm-install-ssh-key" :disabled="publicBusy" @click="confirmInstall(key)">
                  {{ publicBusy ? 'Installing…' : 'Install key' }}
                </button>
              </div>
            </section>
            <p v-if="publicError" class="connection-message" role="alert" data-testid="ssh-public-key-error">{{ publicError }}</p>
            <p v-else-if="publicMessage" class="key-success" role="status" data-testid="ssh-public-key-message">{{ publicMessage }}</p>
          </section>
        </li>
      </ul>

      <section v-if="pendingDelete" class="key-confirm" role="alertdialog" aria-labelledby="key-delete-title" data-testid="ssh-key-delete-confirmation">
        <strong id="key-delete-title">Remove {{ pendingDelete.key.label }}?</strong>
        <p>This key is attached to these saved hosts. Their key association will be cleared before the key is deleted:</p>
        <ul>
          <li v-for="host in pendingDelete.result.affectedHosts" :key="host.hostId">{{ host.hostLabel }}</li>
        </ul>
        <div class="key-actions">
          <button class="small-action key-action" type="button" data-testid="cancel-delete-ssh-key" :disabled="busy" @click="cancelDelete">Cancel</button>
          <button class="small-action key-action key-delete-confirm" type="button" data-testid="confirm-delete-ssh-key" :disabled="busy" @click="confirmDelete">
            {{ busy ? 'Removing…' : 'Remove associations and delete key' }}
          </button>
        </div>
      </section>
    </section>

    <section class="key-panel panel" :class="{ 'key-panel--first': keys.length === 0 }" aria-labelledby="ssh-key-add-title" data-testid="ssh-key-add-panel">
      <div class="panel-heading">
        <div>
          <h2 id="ssh-key-add-title">Add an SSH key</h2>
        </div>
      </div>
      <div class="key-tabs" role="tablist" aria-label="How to add an SSH key">
        <button type="button" role="tab" :aria-selected="action === 'paste'" data-testid="ssh-key-paste-tab" @click="chooseAction('paste')">Paste</button>
        <button type="button" role="tab" :aria-selected="action === 'import'" data-testid="ssh-key-import-tab" @click="chooseAction('import')">File</button>
        <button type="button" role="tab" :aria-selected="action === 'generate'" data-testid="ssh-key-generate-tab" @click="chooseAction('generate')">Generate</button>
      </div>
      <template v-if="action === 'paste'">
        <label class="form-field key-field">
          <span>Private key</span>
          <textarea
            ref="pasteInput"
            class="key-paste"
            data-testid="ssh-key-paste-text"
            rows="6"
            autocomplete="off"
            autocorrect="off"
            autocapitalize="none"
            spellcheck="false"
            data-gramm="false"
            placeholder="Paste your private key here"
            @input="notePasteInput"
            @focus="keepFieldVisible"
          ></textarea>
        </label>
        <p class="key-helper">Paste the whole private key (Ed25519 or RSA), including its BEGIN and END lines. It is sealed in Android secure storage and this field is cleared right away.</p>
      </template>
      <label class="form-field key-field">
        <span>Key label</span>
        <input v-model="label" data-testid="ssh-key-label" autocomplete="off" maxlength="80" placeholder="Personal laptop" @focus="keepFieldVisible" />
      </label>
      <template v-if="action === 'paste' || action === 'import'">
        <label class="form-field key-field">
          <span>Passphrase, if the key has one</span>
          <input v-model="passphrase" data-testid="ssh-key-import-passphrase" type="password" autocomplete="off" @focus="keepFieldVisible" />
        </label>
      </template>
      <template v-if="action === 'paste'">
        <button class="action-button" type="button" data-testid="import-pasted-ssh-key" :disabled="busy || !pasteHasText" @click="importPastedKey">
          {{ busy ? 'Importing…' : 'Import pasted key' }}
        </button>
      </template>
      <template v-else-if="action === 'import'">
        <p class="key-helper">Supported key algorithms: Ed25519 and RSA. Key files are read directly from the Android document provider.</p>
        <button class="action-button" type="button" data-testid="import-ssh-key" :disabled="busy" @click="importKey">
          {{ busy ? 'Importing…' : 'Choose private key file' }}
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
        <p class="key-helper">The new key stays in Android secure storage. Afterwards, copy or share its public key, or install it on a connected host.</p>
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
.key-screen__intro { order: -2; }
.key-panel--first { order: -1; }
.key-row { display: grid; gap: var(--sp-1); border: 1px solid var(--border-soft); border-radius: var(--r-lg); background: var(--bg); padding: var(--sp-2); }
.key-row__main { display: grid; grid-template-columns: minmax(0, 1fr) 48px; align-items: center; gap: var(--sp-2); }
.key-row__public-toggle { display: flex; min-height: 48px; align-items: center; justify-content: space-between; gap: var(--sp-2); border: 1px solid var(--border); border-radius: var(--r-md); background: var(--surface-2); padding: 0 var(--sp-3); color: var(--fg); font-size: var(--fs-200); font-weight: var(--fw-medium); text-align: left; }
.key-row__public-toggle[aria-expanded="true"] { border-color: var(--accent); color: var(--accent); }
.key-public { display: grid; gap: var(--sp-2); padding: var(--sp-2) var(--sp-1) var(--sp-1); }
.key-public__hint { margin: 0; color: var(--fg-secondary); font-size: var(--fs-200); line-height: 1.45; }
.key-public__hint code, .key-confirm code { font-family: var(--font-mono); color: var(--fg); }
.key-public__line { display: block; max-height: 9.5em; overflow: auto; border: 1px solid var(--border-strong); border-radius: var(--r-md); background: var(--surface-2); padding: var(--sp-2) var(--sp-3); color: var(--fg); font: var(--fs-100)/1.5 var(--font-mono); overflow-wrap: anywhere; user-select: text; }
.key-public__actions { display: grid; grid-template-columns: 1fr 1fr; gap: var(--sp-2); }
.key-action { min-height: 48px; }
.key-action--wide { grid-column: 1 / -1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; border-color: var(--accent); color: var(--accent); }
.key-install-confirm { border-color: var(--accent); background: var(--accent); color: var(--bg); }
.key-row__select { display: flex; min-width: 0; min-height: 56px; align-items: center; gap: var(--sp-3); border: 0; border-radius: var(--r-md); background: transparent; padding: var(--sp-2); color: var(--fg-secondary); text-align: left; }
.key-row__select[aria-pressed="true"] { background: var(--state-selected); color: var(--accent); }
.key-row__copy { display: grid; min-width: 0; flex: 1 1 auto; gap: var(--sp-1); }
.key-row__copy strong { overflow: hidden; color: var(--fg); font-size: var(--fs-300); font-weight: var(--fw-medium); text-overflow: ellipsis; white-space: nowrap; }
.key-row__details { color: var(--fg-secondary); font-size: var(--fs-200); }
.key-row__copy code { overflow: hidden; color: var(--fg-muted); font: var(--fs-100)/var(--lh-100) var(--font-mono); text-overflow: ellipsis; white-space: nowrap; }
.key-row__delete { width: 48px; height: 48px; color: var(--error); }
.key-tabs { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--sp-1); border: 1px solid var(--border); border-radius: var(--r-md); background: var(--surface-2); padding: var(--sp-1); margin-bottom: var(--sp-3); }
.key-tabs button { min-height: 48px; border: 0; border-radius: var(--r-sm); background: transparent; color: var(--fg-secondary); font-size: var(--fs-300); font-weight: var(--fw-medium); }
.key-tabs button[aria-selected="true"] { background: var(--surface); color: var(--fg); box-shadow: inset 0 0 0 1px var(--border); }
.key-field { margin-bottom: var(--sp-3); }
.key-paste { min-height: 9.5em; font: var(--fs-100)/1.45 var(--font-mono) !important; white-space: pre; overflow-wrap: normal; }
.key-field input, .key-field select { min-height: 48px; border: 1px solid var(--border-strong); border-radius: var(--r-md); background: var(--bg); padding: 0 var(--sp-3); color: var(--fg); font: var(--fs-300)/1.4 var(--font-mono); }
.key-helper { margin: 0 0 var(--sp-3); color: var(--fg-secondary); font-size: var(--fs-200); line-height: 1.5; }
.key-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: var(--sp-2); }
.key-confirm { margin-top: var(--sp-3); border: 1px solid var(--warning); border-radius: var(--r-md); background: var(--warning-soft); padding: var(--sp-3); }
.key-confirm > strong { color: var(--fg); font-size: var(--fs-300); }
.key-confirm p { margin: var(--sp-2) 0; color: var(--fg-secondary); font-size: var(--fs-200); line-height: 1.45; }
.key-confirm ul { display: grid; gap: var(--sp-1); margin: 0; padding-left: var(--sp-4); color: var(--fg); font-size: var(--fs-300); }
.key-delete-confirm { border-color: var(--error); color: var(--error); }
.key-success { margin: var(--sp-3) 0 0; color: var(--success); font-size: var(--fs-200); line-height: 1.45; }
.key-panel > .action-button { min-height: 48px; }
.key-empty svg { flex: 0 0 auto; color: var(--accent); }
</style>
