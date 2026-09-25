<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import {
  type HostCommandTemplate,
  type HostSnippet,
} from '@pocketshell/core';
import {
  snippetBarCanMove,
  snippetBarEntriesForHost,
  snippetBarItemRequest,
  snippetBarMoveRequest,
  snippetBarSelection,
  type SnippetBarEntry,
  type SnippetBarItemRequest,
  type SnippetBarReorderRequest,
  type SnippetBarSelection,
} from './snippetBarPolicy';

const props = withDefaults(defineProps<{
  hostId: string;
  hostLabel: string;
  snippets: readonly HostSnippet[];
  templates: readonly HostCommandTemplate[];
  keyboardVisible?: boolean;
  disabled?: boolean;
}>(), {
  keyboardVisible: false,
  disabled: false,
});

const emit = defineEmits<{
  select: [selection: SnippetBarSelection];
  create: [request: { kind: 'snippet' | 'template'; hostId: string }];
  edit: [request: SnippetBarItemRequest];
  delete: [request: SnippetBarItemRequest];
  reorder: [request: SnippetBarReorderRequest];
}>();

const managementOpen = ref(false);
const selectedKey = ref('');
const entries = computed(() => snippetBarEntriesForHost(
  props.snippets,
  props.templates,
  props.hostId,
));

watch(() => props.hostId, () => {
  managementOpen.value = false;
  selectedKey.value = '';
}, { flush: 'sync' });

function selectEntry(entry: SnippetBarEntry) {
  if (props.disabled) return;
  selectedKey.value = entry.key;
  emit('select', snippetBarSelection(entry));
}

function requestCreate(kind: 'snippet' | 'template') {
  emit('create', { kind, hostId: props.hostId });
}

function requestEdit(entry: SnippetBarEntry) {
  emit('edit', snippetBarItemRequest(entry));
}

function requestDelete(entry: SnippetBarEntry) {
  emit('delete', snippetBarItemRequest(entry));
}

function move(entry: SnippetBarEntry, offset: -1 | 1) {
  const request = snippetBarMoveRequest(
    props.snippets,
    props.templates,
    props.hostId,
    entry.kind,
    entry.source.id,
    offset,
  );
  if (request) emit('reorder', request);
}

function canMove(entry: SnippetBarEntry, offset: -1 | 1): boolean {
  return !props.disabled && snippetBarCanMove(entries.value, entry, offset);
}
</script>

<template>
  <section
    v-if="!keyboardVisible && hostId.length > 0"
    class="snippet-bar"
    :aria-label="`Command snippets for ${hostLabel}`"
    :data-host-id="hostId"
    data-testid="snippet-bar"
  >
    <div class="snippet-bar__heading">
      <span class="snippet-bar__title">Snippets</span>
      <button
        class="snippet-bar__control"
        type="button"
        :aria-expanded="managementOpen"
        :disabled="disabled"
        data-testid="snippet-manage-toggle"
        @click="managementOpen = !managementOpen"
      >{{ managementOpen ? 'Done' : 'Manage' }}</button>
    </div>

    <div class="snippet-bar__scroller" role="group" :aria-label="`Insert a snippet for ${hostLabel}`">
      <button
        v-for="entry in entries"
        :key="entry.key"
        class="snippet-bar__chip"
        type="button"
        :disabled="disabled"
        :aria-label="`Insert ${entry.label} into the draft`"
        :aria-pressed="selectedKey === entry.key"
        :data-testid="`snippet-chip-${entry.key}`"
        @click="selectEntry(entry)"
      >{{ entry.label }}</button>
      <span v-if="entries.length === 0" class="snippet-bar__empty">No snippets saved for this host.</span>
    </div>

    <div v-if="managementOpen" class="snippet-bar__manager" data-testid="snippet-manager">
      <div class="snippet-bar__manager-heading">
        <span>Manage for {{ hostLabel }}</span>
        <div class="snippet-bar__add-actions">
          <button class="snippet-bar__control" type="button" :disabled="disabled" @click="requestCreate('snippet')">Add snippet</button>
          <button class="snippet-bar__control" type="button" :disabled="disabled" @click="requestCreate('template')">Add command</button>
        </div>
      </div>
      <ul v-if="entries.length > 0" class="snippet-bar__list">
        <li v-for="(entry, index) in entries" :key="`manage-${entry.key}`" class="snippet-bar__item">
          <span class="snippet-bar__item-label">{{ entry.label }}</span>
          <div class="snippet-bar__item-actions">
            <button
              class="snippet-bar__icon-control"
              type="button"
              :disabled="!canMove(entry, -1)"
              :aria-label="`Move ${entry.label} up`"
              @click="move(entry, -1)"
            >↑</button>
            <button
              class="snippet-bar__icon-control"
              type="button"
              :disabled="!canMove(entry, 1)"
              :aria-label="`Move ${entry.label} down`"
              @click="move(entry, 1)"
            >↓</button>
            <button class="snippet-bar__control" type="button" :disabled="disabled" :aria-label="`Edit ${entry.label}`" @click="requestEdit(entry)">Edit</button>
            <button class="snippet-bar__control snippet-bar__control--danger" type="button" :disabled="disabled" :aria-label="`Delete ${entry.label}`" @click="requestDelete(entry)">Delete</button>
          </div>
          <span class="sr-only">{{ index + 1 }} of {{ entries.length }}</span>
        </li>
      </ul>
    </div>
  </section>
</template>

<style scoped>
.snippet-bar {
  display: grid;
  min-width: 0;
  gap: var(--sp-1);
  border-top: 1px solid var(--border-soft);
  padding: var(--sp-2) 0;
  color: var(--fg);
  font-family: var(--font-ui);
}

.snippet-bar__heading,
.snippet-bar__manager-heading,
.snippet-bar__item,
.snippet-bar__item-actions,
.snippet-bar__add-actions {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: var(--sp-1);
}

.snippet-bar__heading,
.snippet-bar__manager-heading,
.snippet-bar__item {
  justify-content: space-between;
}

.snippet-bar__title,
.snippet-bar__manager-heading {
  color: var(--fg-secondary);
  font-size: var(--fs-200);
  font-weight: var(--fw-medium);
}

.snippet-bar__scroller {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: var(--sp-2);
  overflow-x: auto;
  overscroll-behavior-inline: contain;
  padding: 1px 0 3px;
  scrollbar-width: thin;
}

.snippet-bar__chip,
.snippet-bar__control,
.snippet-bar__icon-control {
  display: inline-flex;
  min-width: 48px;
  min-height: 48px;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  background: var(--surface-2);
  padding: 0 var(--sp-3);
  color: var(--fg);
  font: 500 var(--fs-200)/var(--lh-200) var(--font-ui);
  text-decoration: none;
  -webkit-tap-highlight-color: transparent;
}

.snippet-bar__chip { scroll-snap-align: start; white-space: nowrap; }
.snippet-bar__chip[aria-pressed="true"] { border-color: var(--accent-dim); background: var(--state-selected); }
.snippet-bar__control { padding-inline: var(--sp-2); }
.snippet-bar__icon-control { width: 48px; padding: 0; font-size: var(--fs-400); }
.snippet-bar__control--danger { color: var(--error); }
.snippet-bar__chip:active:not(:disabled),
.snippet-bar__control:active:not(:disabled),
.snippet-bar__icon-control:active:not(:disabled) { background: var(--state-active); }
.snippet-bar__chip:focus-visible,
.snippet-bar__control:focus-visible,
.snippet-bar__icon-control:focus-visible { outline: 2px solid var(--focus-ring); outline-offset: 2px; }
.snippet-bar__chip:disabled,
.snippet-bar__control:disabled,
.snippet-bar__icon-control:disabled { cursor: not-allowed; opacity: var(--disabled-opacity); }

.snippet-bar__empty { padding: 0 var(--sp-1); color: var(--fg-secondary); font-size: var(--fs-200); }
.snippet-bar__manager { display: grid; gap: var(--sp-2); border-top: 1px solid var(--border-soft); padding-top: var(--sp-2); }
.snippet-bar__manager-heading { flex-wrap: wrap; }
.snippet-bar__add-actions { margin-left: auto; }
.snippet-bar__list { display: grid; gap: var(--sp-1); margin: 0; padding: 0; list-style: none; }
.snippet-bar__item { min-height: 56px; border-bottom: 1px solid var(--border-soft); padding: 0 var(--sp-1); }
.snippet-bar__item-label { min-width: 0; overflow: hidden; color: var(--fg); font-size: var(--fs-200); text-overflow: ellipsis; white-space: nowrap; }
.snippet-bar__item-actions { flex: 0 0 auto; }
.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; clip-path: inset(50%); }

@media (max-width: 520px) {
  .snippet-bar__item { align-items: flex-start; flex-direction: column; padding-block: var(--sp-1); }
  .snippet-bar__item-label { width: 100%; }
  .snippet-bar__item-actions { max-width: 100%; overflow-x: auto; }
}
</style>
