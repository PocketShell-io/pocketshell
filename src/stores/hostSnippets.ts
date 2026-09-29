import { reactive } from 'vue';
import {
  commandTemplateInsertion,
  commandTemplatesForHost,
  parseLegacyCommandTemplates,
  parseLegacySnippets,
  reorderHostItems,
  snippetInsertion,
  snippetsForHost,
  type HostCommandTemplate,
  type HostSnippet,
  type LiteralDraftInsertion,
} from '@pocketshell/core';
import type { NativeLegacySnapshot } from '../native/installedDataMigration';

export const HOST_SNIPPETS_STORAGE_KEY = 'pocketshell.js.host-snippets.v1';

export interface SnippetStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}

export type ManagedHostItem = {
  key: string;
  collection: 'snippet' | 'template';
  id: string;
  hostId: string;
  label: string | null;
  body: string;
  kind: string;
  sortOrder: number;
};

interface PersistedHostSnippets {
  schemaVersion: 1;
  snippets: HostSnippet[];
  templates: HostCommandTemplate[];
  legacyImportComplete: boolean;
}

function browserStorage(): SnippetStorage {
  try {
    if (typeof globalThis.localStorage !== 'undefined') return globalThis.localStorage;
  } catch {
    // A blocked browser storage area is surfaced by the repository on write.
  }
  return {
    getItem: () => null,
    setItem: () => { throw new Error('Browser storage is unavailable.'); },
  };
}

function emptyRecord(): PersistedHostSnippets {
  return { schemaVersion: 1, snippets: [], templates: [], legacyImportComplete: false };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isHostSnippet(value: unknown): value is HostSnippet {
  return isRecord(value)
    && typeof value.id === 'string' && value.id.length > 0
    && typeof value.hostId === 'string' && value.hostId.trim().length > 0
    && (value.label === null || typeof value.label === 'string')
    && typeof value.body === 'string'
    && typeof value.kind === 'string'
    && Number.isSafeInteger(value.sortOrder) && (value.sortOrder as number) >= 0;
}

function isHostCommandTemplate(value: unknown): value is HostCommandTemplate {
  return isRecord(value)
    && typeof value.id === 'string' && value.id.length > 0
    && typeof value.hostId === 'string' && value.hostId.trim().length > 0
    && typeof value.label === 'string'
    && typeof value.commands === 'string'
    && Number.isSafeInteger(value.sortOrder) && (value.sortOrder as number) >= 0;
}

function parsePersistedRecord(value: unknown): PersistedHostSnippets {
  if (!isRecord(value) || value.schemaVersion !== 1 || !Array.isArray(value.snippets)
    || !Array.isArray(value.templates) || typeof value.legacyImportComplete !== 'boolean'
    || !value.snippets.every(isHostSnippet) || !value.templates.every(isHostCommandTemplate)) {
    throw new Error('Saved command chips have an invalid shape; the stored data was left unchanged.');
  }
  return {
    schemaVersion: 1,
    snippets: value.snippets,
    templates: value.templates,
    legacyImportComplete: value.legacyImportComplete,
  };
}

function storageError(error: unknown): string {
  return error instanceof Error && error.message.length > 0
    ? error.message
    : 'Saved command chips could not be read or written.';
}

function itemKey(collection: ManagedHostItem['collection'], id: string): string {
  return `${collection}:${id}`;
}

function makeItemId(): string {
  if (globalThis.crypto && 'randomUUID' in globalThis.crypto) {
    return `js-${globalThis.crypto.randomUUID()}`;
  }
  return `js-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
}

/**
 * Local durable owner for per-host chips. Legacy Room rows are parsed by the
 * pinned core contract and copied once; the migration envelope remains intact.
 */
export class HostSnippetRepository {
  snippets: HostSnippet[] = [];
  templates: HostCommandTemplate[] = [];
  legacyImportComplete = false;
  error = '';
  private writable = true;

  constructor(private readonly storage: SnippetStorage = browserStorage()) {
    this.load();
  }

  load(): boolean {
    let raw: string | null;
    try {
      raw = this.storage.getItem(HOST_SNIPPETS_STORAGE_KEY);
    } catch (error) {
      this.writable = false;
      this.error = `Saved command chips could not be read: ${storageError(error)}`;
      return false;
    }
    if (raw === null) {
      this.snippets = [];
      this.templates = [];
      this.legacyImportComplete = false;
      this.error = '';
      this.writable = true;
      return true;
    }
    try {
      const record = parsePersistedRecord(JSON.parse(raw) as unknown);
      this.snippets = record.snippets;
      this.templates = record.templates;
      this.legacyImportComplete = record.legacyImportComplete;
      this.error = '';
      this.writable = true;
      return true;
    } catch (error) {
      this.writable = false;
      this.error = `Saved command chips could not be loaded: ${storageError(error)}`;
      return false;
    }
  }

  private commit(next: PersistedHostSnippets): boolean {
    if (!this.writable) return false;
    try {
      this.storage.setItem(HOST_SNIPPETS_STORAGE_KEY, JSON.stringify(next));
    } catch (error) {
      this.error = `Saved command chips could not be written: ${storageError(error)}`;
      return false;
    }
    this.snippets = next.snippets;
    this.templates = next.templates;
    this.legacyImportComplete = next.legacyImportComplete;
    this.error = '';
    return true;
  }

  importLegacySnapshot(snapshot: NativeLegacySnapshot): boolean {
    if (this.legacyImportComplete) return true;
    if (!snapshot.database.present) {
      return this.commit({ ...emptyRecord(), snippets: this.snippets, templates: this.templates, legacyImportComplete: true });
    }
    const hostRows = snapshot.database.tables.hosts;
    const hostIds = Array.isArray(hostRows)
      ? hostRows.flatMap((row) => isRecord(row) && Number.isSafeInteger(row.id) && (row.id as number) > 0
        ? [row.id as number]
        : [])
      : [];
    const parsedSnippets = parseLegacySnippets(snapshot.database.tables.snippets, hostIds);
    if (parsedSnippets.kind !== 'ok') {
      this.error = `Legacy snippets were left in the migration record because their rows are invalid (${parsedSnippets.reason}${parsedSnippets.index === undefined ? '' : ` at row ${parsedSnippets.index + 1}`}).`;
      return false;
    }
    const parsedTemplates = parseLegacyCommandTemplates(snapshot.database.tables.command_templates, hostIds);
    if (parsedTemplates.kind !== 'ok') {
      this.error = `Legacy command templates were left in the migration record because their rows are invalid (${parsedTemplates.reason}${parsedTemplates.index === undefined ? '' : ` at row ${parsedTemplates.index + 1}`}).`;
      return false;
    }

    const nextSnippets = [...this.snippets];
    const nextTemplates = [...this.templates];
    const nextSlotByHost = new Map<string, number>();
    for (const item of [...this.snippets, ...this.templates]) {
      nextSlotByHost.set(item.hostId, Math.max(nextSlotByHost.get(item.hostId) ?? 0, item.sortOrder + 1));
    }
    for (const hostId of hostIds.map(String)) {
      const base = nextSlotByHost.get(hostId) ?? 0;
      const snippets = parsedSnippets.snippets
        .filter((item) => item.hostId === hostId)
        .map((item) => ({ ...item, sortOrder: base + item.sortOrder }));
      const templates = parsedTemplates.templates
        .filter((item) => item.hostId === hostId)
        .map((item) => ({ ...item, sortOrder: base + snippets.length + item.sortOrder }));
      nextSnippets.push(...snippets);
      nextTemplates.push(...templates);
      nextSlotByHost.set(hostId, base + snippets.length + templates.length);
    }
    return this.commit({
      schemaVersion: 1,
      snippets: nextSnippets,
      templates: nextTemplates,
      legacyImportComplete: true,
    });
  }

  itemsForHost(hostId: string): ManagedHostItem[] {
    if (!hostId.trim()) return [];
    const snippets = snippetsForHost(this.snippets, hostId).map((item): ManagedHostItem => ({
      key: itemKey('snippet', item.id),
      collection: 'snippet',
      id: item.id,
      hostId: item.hostId,
      label: item.label,
      body: item.body,
      kind: item.kind,
      sortOrder: item.sortOrder,
    }));
    const templates = commandTemplatesForHost(this.templates, hostId).map((item): ManagedHostItem => ({
      key: itemKey('template', item.id),
      collection: 'template',
      id: item.id,
      hostId: item.hostId,
      label: item.label,
      body: item.commands,
      kind: 'template',
      sortOrder: item.sortOrder,
    }));
    return [...snippets, ...templates]
      .map((item, index) => ({ item, index }))
      .sort((left, right) => left.item.sortOrder - right.item.sortOrder || left.index - right.index)
      .map(({ item }) => item);
  }

  insertionFor(hostId: string, collection: ManagedHostItem['collection'], id: string): LiteralDraftInsertion | null {
    if (collection === 'snippet') {
      const item = snippetsForHost(this.snippets, hostId).find((candidate) => candidate.id === id);
      return item ? snippetInsertion(item) : null;
    }
    const item = commandTemplatesForHost(this.templates, hostId).find((candidate) => candidate.id === id);
    return item ? commandTemplateInsertion(item) : null;
  }

  createSnippet(hostId: string, label: string, body: string, kind: string): boolean {
    if (!hostId.trim() || !label.trim() || body.length === 0 || !kind.trim()) {
      this.error = 'A snippet needs a host, label, text, and type.';
      return false;
    }
    const items = this.itemsForHost(hostId);
    const nextSortOrder = items.reduce((next, item) => Math.max(next, item.sortOrder + 1), 0);
    const next = [...this.snippets, {
      id: makeItemId(),
      hostId,
      label,
      body,
      kind,
      sortOrder: nextSortOrder,
    }];
    return this.commit({ schemaVersion: 1, snippets: next, templates: this.templates, legacyImportComplete: this.legacyImportComplete });
  }

  updateItem(hostId: string, item: Pick<ManagedHostItem, 'collection' | 'id'>, label: string, body: string, kind: string): boolean {
    if (!hostId.trim() || !label.trim() || body.length === 0) {
      this.error = 'A snippet needs a host, label, and text.';
      return false;
    }
    if (item.collection === 'snippet') {
      let found = false;
      const next = this.snippets.map((candidate) => {
        if (candidate.hostId !== hostId || candidate.id !== item.id) return candidate;
        found = true;
        return { ...candidate, label, body, kind: kind.trim() ? kind : candidate.kind };
      });
      return found && this.commit({ schemaVersion: 1, snippets: next, templates: this.templates, legacyImportComplete: this.legacyImportComplete });
    }
    let found = false;
    const next = this.templates.map((candidate) => {
      if (candidate.hostId !== hostId || candidate.id !== item.id) return candidate;
      found = true;
      return { ...candidate, label, commands: body };
    });
    return found && this.commit({ schemaVersion: 1, snippets: this.snippets, templates: next, legacyImportComplete: this.legacyImportComplete });
  }

  deleteItem(hostId: string, item: Pick<ManagedHostItem, 'collection' | 'id'>): boolean {
    if (!hostId.trim()) return false;
    if (item.collection === 'snippet') {
      const next = this.snippets.filter((candidate) => candidate.hostId !== hostId || candidate.id !== item.id);
      if (next.length === this.snippets.length) return false;
      return this.commit({ schemaVersion: 1, snippets: next, templates: this.templates, legacyImportComplete: this.legacyImportComplete });
    }
    const next = this.templates.filter((candidate) => candidate.hostId !== hostId || candidate.id !== item.id);
    if (next.length === this.templates.length) return false;
    return this.commit({ schemaVersion: 1, snippets: this.snippets, templates: next, legacyImportComplete: this.legacyImportComplete });
  }

  reorderHost(hostId: string, orderedKeys: readonly string[]): boolean {
    const items = this.itemsForHost(hostId);
    const records = [
      ...this.snippets.map((item) => ({ id: itemKey('snippet', item.id), hostId: item.hostId, sortOrder: item.sortOrder })),
      ...this.templates.map((item) => ({ id: itemKey('template', item.id), hostId: item.hostId, sortOrder: item.sortOrder })),
    ];
    const result = reorderHostItems(records, hostId, orderedKeys);
    if (result.kind !== 'ok' || items.length !== orderedKeys.length) {
      this.error = 'The requested chip order does not match this host’s saved items.';
      return false;
    }
    const orderByKey = new Map(result.items.map((item) => [item.id, item.sortOrder]));
    const nextSnippets = this.snippets.map((item) => ({ ...item, sortOrder: orderByKey.get(itemKey('snippet', item.id)) ?? item.sortOrder }));
    const nextTemplates = this.templates.map((item) => ({ ...item, sortOrder: orderByKey.get(itemKey('template', item.id)) ?? item.sortOrder }));
    return this.commit({ schemaVersion: 1, snippets: nextSnippets, templates: nextTemplates, legacyImportComplete: this.legacyImportComplete });
  }

  moveItem(hostId: string, itemKeyToMove: string, direction: -1 | 1): boolean {
    const items = this.itemsForHost(hostId);
    const index = items.findIndex((item) => item.key === itemKeyToMove);
    const nextIndex = index + direction;
    if (index < 0 || nextIndex < 0 || nextIndex >= items.length) return false;
    const orderedKeys = items.map((item) => item.key);
    [orderedKeys[index], orderedKeys[nextIndex]] = [orderedKeys[nextIndex], orderedKeys[index]];
    return this.reorderHost(hostId, orderedKeys);
  }
}

export function insertLiteralAtDraftSelection(
  draft: string,
  insertedText: string,
  selectionStart: number,
  selectionEnd: number,
): { draft: string; caret: number } {
  const start = Math.max(0, Math.min(draft.length, Math.trunc(selectionStart)));
  const end = Math.max(start, Math.min(draft.length, Math.trunc(selectionEnd)));
  const next = `${draft.slice(0, start)}${insertedText}${draft.slice(end)}`;
  return { draft: next, caret: start + insertedText.length };
}

export const hostSnippets = reactive(new HostSnippetRepository());
