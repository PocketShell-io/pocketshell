<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { AppIcon } from '@pocketshell/ui';
import { hostSnippets, type ManagedHostItem } from '../stores/hostSnippets';

const props = defineProps<{ hostId: string; hostLabel: string }>();

const items = computed(() => hostSnippets.itemsForHost(props.hostId));
const form = reactive({ label: '', body: '', kind: 'command' });
const editing = ref<Pick<ManagedHostItem, 'collection' | 'id'> | null>(null);
const deleteArmedKey = ref('');
const statusText = ref('');

function clearForm() {
  form.label = '';
  form.body = '';
  form.kind = 'command';
  editing.value = null;
  statusText.value = '';
}

function beginCreate() {
  clearForm();
  statusText.value = 'Add a reusable text snippet for this host.';
  document.querySelector<HTMLElement>('[data-testid="snippet-label"]')?.focus();
}

function editItem(item: ManagedHostItem) {
  editing.value = { collection: item.collection, id: item.id };
  form.label = item.label ?? labelFor(item);
  form.body = item.body;
  form.kind = item.collection === 'snippet' ? item.kind : 'command';
  deleteArmedKey.value = '';
  statusText.value = `Editing ${labelFor(item)}.`;
  document.querySelector<HTMLElement>('[data-testid="snippet-label"]')?.focus();
}

function saveItem() {
  if (!props.hostId) return;
  const saved = editing.value
    ? hostSnippets.updateItem(props.hostId, editing.value, form.label, form.body, form.kind)
    : hostSnippets.createSnippet(props.hostId, form.label, form.body, form.kind);
  if (saved) {
    statusText.value = editing.value ? 'Command chip updated.' : 'Command chip saved for this host.';
    clearForm();
  }
}

function deleteItem(item: ManagedHostItem) {
  if (deleteArmedKey.value !== item.key) {
    deleteArmedKey.value = item.key;
    statusText.value = `Tap Delete again to remove ${labelFor(item)}.`;
    return;
  }
  if (hostSnippets.deleteItem(props.hostId, item)) {
    statusText.value = `${labelFor(item)} deleted.`;
    if (editing.value?.collection === item.collection && editing.value.id === item.id) clearForm();
  }
  deleteArmedKey.value = '';
}

function moveItem(item: ManagedHostItem, direction: -1 | 1) {
  if (hostSnippets.moveItem(props.hostId, item.key, direction)) {
    deleteArmedKey.value = '';
    statusText.value = `${labelFor(item)} moved ${direction < 0 ? 'up' : 'down'}.`;
  }
}

function labelFor(item: ManagedHostItem): string {
  const savedLabel = item.label?.trim();
  if (savedLabel) return savedLabel;
  return item.body.split(/\r?\n/u, 1)[0]?.trim() || 'Snippet';
}

function handleSubmit(event: Event) {
  event.preventDefault();
  saveItem();
}
</script>

<template>
  <main class="screen-content settings-screen" data-testid="host-snippets-screen" :data-host-id="hostId">
    <section class="panel settings-panel host-snippets-panel" aria-labelledby="host-snippets-title">
      <p class="eyebrow">SETTINGS · INPUT</p>
      <h1 id="host-snippets-title">Command chips</h1>
      <p class="settings-copy">Saved for <strong>{{ hostLabel }}</strong>. Choosing a chip fills the composer draft; Send remains a separate action.</p>

      <p v-if="!hostId" class="settings-note" role="status" data-testid="snippet-no-host">
        Enter a host name, user, and port on the connection screen to manage its chips.
      </p>

      <template v-else>
        <p v-if="hostSnippets.error" class="snippet-store-error" role="alert" data-testid="snippet-store-error">{{ hostSnippets.error }}</p>

        <form class="snippet-editor" data-testid="snippet-editor" @submit="handleSubmit">
          <div class="snippet-editor__heading">
            <h2>{{ editing ? 'Edit command chip' : 'Add command chip' }}</h2>
            <button v-if="!editing" class="small-action" type="button" data-testid="add-snippet" @click="beginCreate">
              <AppIcon name="plus" :size="16" />
              <span>New</span>
            </button>
            <button v-else class="small-action" type="button" data-testid="cancel-snippet-edit" @click="clearForm">Cancel edit</button>
          </div>
          <label class="form-field">
            <span>Label</span>
            <input v-model="form.label" data-testid="snippet-label" autocomplete="off" maxlength="80" placeholder="For example, Git status" />
          </label>
          <label class="form-field">
            <span>Text inserted into the draft</span>
            <textarea v-model="form.body" data-testid="snippet-body" rows="4" spellcheck="false" autocapitalize="none" placeholder="Text is inserted literally. It is never run on selection." />
          </label>
          <label v-if="!editing || editing.collection === 'snippet'" class="form-field snippet-kind-field">
            <span>Kind</span>
            <select v-model="form.kind" data-testid="snippet-kind">
              <option value="command">Command</option>
              <option value="prompt">Prompt</option>
              <option v-if="editing && !['command', 'prompt'].includes(form.kind)" :value="form.kind">{{ form.kind }}</option>
            </select>
          </label>
          <div class="snippet-editor__actions">
            <button class="action-button" type="submit" data-testid="save-snippet" :disabled="!form.label.trim() || form.body.length === 0">
              {{ editing ? 'Save changes' : 'Save chip' }}
            </button>
          </div>
        </form>

        <div class="host-snippets-list" data-testid="host-snippet-list">
          <div class="snippet-list-heading">
            <div>
              <h2>Saved on this host</h2>
              <p>{{ items.length ? `${items.length} reusable ${items.length === 1 ? 'item' : 'items'}` : 'No command chips have been saved yet.' }}</p>
            </div>
          </div>
          <ul v-if="items.length" class="managed-snippet-list">
            <li v-for="(item, index) in items" :key="item.key" class="managed-snippet" :data-chip-key="item.key">
              <div class="managed-snippet__copy">
                <div class="managed-snippet__heading">
                  <strong>{{ labelFor(item) }}</strong>
                  <span class="state-tag state-tag--muted">{{ item.collection === 'template' ? 'TEMPLATE' : item.kind.toUpperCase() }}</span>
                </div>
                <pre>{{ item.body }}</pre>
              </div>
              <div class="managed-snippet__actions" :aria-label="`Actions for ${labelFor(item)}`">
                <button class="snippet-icon-action" type="button" :aria-label="`Move ${labelFor(item)} up`" :disabled="index === 0" @click="moveItem(item, -1)"><AppIcon name="arrow-up" :size="16" /></button>
                <button class="snippet-icon-action" type="button" :aria-label="`Move ${labelFor(item)} down`" :disabled="index === items.length - 1" @click="moveItem(item, 1)"><AppIcon name="chevron-down" :size="16" /></button>
                <button class="snippet-icon-action" type="button" :aria-label="`Edit ${labelFor(item)}`" @click="editItem(item)"><AppIcon name="edit-2" :size="16" /></button>
                <button class="snippet-delete-action" type="button" :aria-label="deleteArmedKey === item.key ? `Confirm delete ${labelFor(item)}` : `Delete ${labelFor(item)}`" @click="deleteItem(item)">
                  {{ deleteArmedKey === item.key ? 'Delete?' : 'Delete' }}
                </button>
              </div>
            </li>
          </ul>
        </div>

        <p class="snippet-editor-status" role="status" aria-live="polite" data-testid="snippet-editor-status">{{ statusText }}</p>
      </template>
    </section>
  </main>
</template>
