import { reactive } from 'vue';
import {
  commandTemplatesForHost,
  parseLegacyCommandTemplates,
  parseLegacySnippets,
  reorderHostItems,
  snippetsForHost,
  type HostCommandTemplate,
  type HostSnippet,
} from '@pocketshell/core';

export const HOST_SNIPPETS_STORAGE_KEY = 'pocketshell.js.host-snippets.v1';

export interface HostSnippetStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}

export type HostSnippetStorageStatus = 'ready' | 'recovered' | 'unavailable' | 'read-failed' | 'write-failed';

export type HostSnippetMutationResult =
  | { kind: 'ok'; persisted: boolean }
  | { kind: 'invalid'; reason: 'invalid-item' | 'invalid-host-id' | 'duplicate-id' | 'missing-item' | 'invalid-order' };

export type LegacyHostSnippetImportResult =
  | { kind: 'ok'; snippetsAdded: number; templatesAdded: number; persisted: boolean }
  | {
      kind: 'invalid';
      collection: 'snippets' | 'templates';
      reason: string;
      index?: number;
    }
  | { kind: 'invalid'; collection: 'snippets' | 'templates'; reason: 'id-conflict' };

export interface HostSnippetLibraryStore {
  readonly snippets: readonly HostSnippet[];
  readonly templates: readonly HostCommandTemplate[];
  readonly storageStatus: HostSnippetStorageStatus;
  snippetsForHost(hostId: string): HostSnippet[];
  commandTemplatesForHost(hostId: string): HostCommandTemplate[];
  saveSnippet(item: HostSnippet): HostSnippetMutationResult;
  deleteSnippet(hostId: string, id: string): HostSnippetMutationResult;
  reorderSnippets(hostId: string, orderedIds: readonly string[]): HostSnippetMutationResult;
  saveTemplate(item: HostCommandTemplate): HostSnippetMutationResult;
  deleteTemplate(hostId: string, id: string): HostSnippetMutationResult;
  reorderTemplates(hostId: string, orderedIds: readonly string[]): HostSnippetMutationResult;
  importLegacyRows(
    snippets: unknown,
    templates: unknown,
    knownHostIds: readonly number[],
  ): LegacyHostSnippetImportResult;
}

interface ParsedCollection<T> {
  items: T[];
  malformed: boolean;
}

interface StoredLibrary {
  schema: 1;
  snippets: HostSnippet[];
  templates: HostCommandTemplate[];
  recovery?: { source: string };
}

function browserStorage(): HostSnippetStorage | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage;
  } catch {
    return null;
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function validIdentity(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0;
}

function parseStoredSnippets(value: unknown): ParsedCollection<HostSnippet> {
  if (!Array.isArray(value)) return { items: [], malformed: true };
  const seen = new Set<string>();
  const items: HostSnippet[] = [];
  let malformed = false;
  for (const candidate of value) {
    if (!isRecord(candidate) || !validIdentity(candidate.id) || !validIdentity(candidate.hostId) ||
      (candidate.label !== null && typeof candidate.label !== 'string') ||
      typeof candidate.body !== 'string' || typeof candidate.kind !== 'string' ||
      typeof candidate.sortOrder !== 'number' || !Number.isSafeInteger(candidate.sortOrder) || candidate.sortOrder < 0 ||
      seen.has(candidate.id)) {
      malformed = true;
      continue;
    }
    seen.add(candidate.id);
    items.push({
      id: candidate.id,
      hostId: candidate.hostId,
      label: candidate.label,
      body: candidate.body,
      kind: candidate.kind,
      sortOrder: candidate.sortOrder,
    });
  }
  return { items, malformed };
}

function parseStoredTemplates(value: unknown): ParsedCollection<HostCommandTemplate> {
  if (!Array.isArray(value)) return { items: [], malformed: true };
  const seen = new Set<string>();
  const items: HostCommandTemplate[] = [];
  let malformed = false;
  for (const candidate of value) {
    if (!isRecord(candidate) || !validIdentity(candidate.id) || !validIdentity(candidate.hostId) ||
      typeof candidate.label !== 'string' || typeof candidate.commands !== 'string' ||
      typeof candidate.sortOrder !== 'number' || !Number.isSafeInteger(candidate.sortOrder) || candidate.sortOrder < 0 ||
      seen.has(candidate.id)) {
      malformed = true;
      continue;
    }
    seen.add(candidate.id);
    items.push({
      id: candidate.id,
      hostId: candidate.hostId,
      label: candidate.label,
      commands: candidate.commands,
      sortOrder: candidate.sortOrder,
    });
  }
  return { items, malformed };
}

function parseStoredLibrary(raw: string): {
  snippets: HostSnippet[];
  templates: HostCommandTemplate[];
  recoverySource: string | null;
  recovered: boolean;
} {
  let decoded: unknown;
  try {
    decoded = JSON.parse(raw) as unknown;
  } catch {
    return { snippets: [], templates: [], recoverySource: raw, recovered: true };
  }
  if (!isRecord(decoded) || decoded.schema !== 1) {
    return { snippets: [], templates: [], recoverySource: raw, recovered: true };
  }

  const parsedSnippets = parseStoredSnippets(decoded.snippets);
  const parsedTemplates = parseStoredTemplates(decoded.templates);
  const validRecovery = isRecord(decoded.recovery) && typeof decoded.recovery.source === 'string'
    ? decoded.recovery.source
    : null;
  const malformedRecovery = decoded.recovery !== undefined && validRecovery === null;
  const malformed = parsedSnippets.malformed || parsedTemplates.malformed || malformedRecovery;
  return {
    snippets: parsedSnippets.items,
    templates: parsedTemplates.items,
    // Keep the complete original document byte-for-byte if any row cannot be
    // represented. A later successful write carries it in the recovery field.
    recoverySource: malformed ? raw : validRecovery,
    recovered: malformed || validRecovery !== null,
  };
}

function maxOrder<T extends { hostId: string; sortOrder: number }>(items: readonly T[], hostId: string): number {
  return items.reduce((maximum, item) => item.hostId === hostId ? Math.max(maximum, item.sortOrder) : maximum, -1);
}

function sameSnippet(left: HostSnippet, right: HostSnippet): boolean {
  return left.id === right.id && left.hostId === right.hostId && left.label === right.label &&
    left.body === right.body && left.kind === right.kind;
}

function sameTemplate(left: HostCommandTemplate, right: HostCommandTemplate): boolean {
  return left.id === right.id && left.hostId === right.hostId && left.label === right.label &&
    left.commands === right.commands;
}

function validSnippet(value: unknown): value is HostSnippet {
  return isRecord(value) && validIdentity(value.id) && validIdentity(value.hostId) &&
    (value.label === null || typeof value.label === 'string') && typeof value.body === 'string' &&
    typeof value.kind === 'string' && typeof value.sortOrder === 'number' &&
    Number.isSafeInteger(value.sortOrder) && value.sortOrder >= 0;
}

function validTemplate(value: unknown): value is HostCommandTemplate {
  return isRecord(value) && validIdentity(value.id) && validIdentity(value.hostId) &&
    typeof value.label === 'string' && typeof value.commands === 'string' &&
    typeof value.sortOrder === 'number' && Number.isSafeInteger(value.sortOrder) && value.sortOrder >= 0;
}

/**
 * Create the JS-owned durable snippet library. Mutations update reactive
 * in-memory state before writing, so a quota/private-mode failure never drops
 * an item from the current session. Corrupt source data is carried forward in
 * an opaque recovery field before a later successful save replaces the key.
 */
export function createHostSnippetLibraryStore(
  storage: HostSnippetStorage | null = browserStorage(),
): HostSnippetLibraryStore {
  let recoverySource: string | null = null;
  let sourceCouldNotBeRead = false;
  let persistencePending = false;
  const state = reactive({
    snippets: [] as HostSnippet[],
    templates: [] as HostCommandTemplate[],
    storageStatus: (storage === null ? 'unavailable' : 'ready') as HostSnippetStorageStatus,
  });

  if (storage) {
    try {
      const raw = storage.getItem(HOST_SNIPPETS_STORAGE_KEY);
      if (raw !== null) {
        const parsed = parseStoredLibrary(raw);
        state.snippets = parsed.snippets;
        state.templates = parsed.templates;
        recoverySource = parsed.recoverySource;
        if (parsed.recovered) state.storageStatus = 'recovered';
      }
    } catch {
      sourceCouldNotBeRead = true;
      state.storageStatus = 'read-failed';
    }
  }

  function persist(): boolean {
    if (!storage) {
      state.storageStatus = 'unavailable';
      persistencePending = true;
      return false;
    }
    // Do not replace a value we could not inspect on startup. That could
    // overwrite a valid library containing items this session has not seen.
    if (sourceCouldNotBeRead) {
      state.storageStatus = 'read-failed';
      persistencePending = true;
      return false;
    }
    const document: StoredLibrary = {
      schema: 1,
      snippets: state.snippets.map((item) => ({ ...item })),
      templates: state.templates.map((item) => ({ ...item })),
      ...(recoverySource === null ? {} : { recovery: { source: recoverySource } }),
    };
    try {
      storage.setItem(HOST_SNIPPETS_STORAGE_KEY, JSON.stringify(document));
      state.storageStatus = recoverySource === null ? 'ready' : 'recovered';
      persistencePending = false;
      return true;
    } catch {
      state.storageStatus = 'write-failed';
      persistencePending = true;
      return false;
    }
  }

  function commit(snippets: HostSnippet[], templates: HostCommandTemplate[]): HostSnippetMutationResult {
    state.snippets = snippets;
    state.templates = templates;
    return { kind: 'ok', persisted: persist() };
  }

  function saveSnippet(item: HostSnippet): HostSnippetMutationResult {
    if (!validSnippet(item)) return { kind: 'invalid', reason: 'invalid-item' };
    if (!validIdentity(item.hostId)) return { kind: 'invalid', reason: 'invalid-host-id' };
    const existingIndex = state.snippets.findIndex((candidate) => candidate.id === item.id);
    if (existingIndex >= 0 && state.snippets[existingIndex]?.hostId !== item.hostId) {
      return { kind: 'invalid', reason: 'duplicate-id' };
    }
    const snippets = [...state.snippets];
    if (existingIndex >= 0) {
      const existing = snippets[existingIndex];
      if (!existing) return { kind: 'invalid', reason: 'missing-item' };
      snippets[existingIndex] = { ...item, sortOrder: existing.sortOrder };
    } else {
      snippets.push({ ...item, sortOrder: maxOrder(snippets, item.hostId) + 1 });
    }
    return commit(snippets, [...state.templates]);
  }

  function deleteSnippet(hostId: string, id: string): HostSnippetMutationResult {
    if (!validIdentity(hostId)) return { kind: 'invalid', reason: 'invalid-host-id' };
    if (!state.snippets.some((item) => item.id === id && item.hostId === hostId)) {
      return { kind: 'invalid', reason: 'missing-item' };
    }
    const remaining = state.snippets.filter((item) => item.id !== id || item.hostId !== hostId);
    const order = reorderHostItems(remaining, hostId, snippetsForHost(remaining, hostId).map((item) => item.id));
    return commit(order.kind === 'ok' ? order.items : remaining, [...state.templates]);
  }

  function reorderSnippets(hostId: string, orderedIds: readonly string[]): HostSnippetMutationResult {
    const result = reorderHostItems(state.snippets, hostId, orderedIds);
    if (result.kind === 'invalid') return { kind: 'invalid', reason: result.reason };
    return commit(result.items, [...state.templates]);
  }

  function saveTemplate(item: HostCommandTemplate): HostSnippetMutationResult {
    if (!validTemplate(item)) return { kind: 'invalid', reason: 'invalid-item' };
    if (!validIdentity(item.hostId)) return { kind: 'invalid', reason: 'invalid-host-id' };
    const existingIndex = state.templates.findIndex((candidate) => candidate.id === item.id);
    if (existingIndex >= 0 && state.templates[existingIndex]?.hostId !== item.hostId) {
      return { kind: 'invalid', reason: 'duplicate-id' };
    }
    const templates = [...state.templates];
    if (existingIndex >= 0) {
      const existing = templates[existingIndex];
      if (!existing) return { kind: 'invalid', reason: 'missing-item' };
      templates[existingIndex] = { ...item, sortOrder: existing.sortOrder };
    } else {
      templates.push({ ...item, sortOrder: maxOrder(templates, item.hostId) + 1 });
    }
    return commit([...state.snippets], templates);
  }

  function deleteTemplate(hostId: string, id: string): HostSnippetMutationResult {
    if (!validIdentity(hostId)) return { kind: 'invalid', reason: 'invalid-host-id' };
    if (!state.templates.some((item) => item.id === id && item.hostId === hostId)) {
      return { kind: 'invalid', reason: 'missing-item' };
    }
    const remaining = state.templates.filter((item) => item.id !== id || item.hostId !== hostId);
    const order = reorderHostItems(remaining, hostId, commandTemplatesForHost(remaining, hostId).map((item) => item.id));
    return commit([...state.snippets], order.kind === 'ok' ? order.items : remaining);
  }

  function reorderTemplates(hostId: string, orderedIds: readonly string[]): HostSnippetMutationResult {
    const result = reorderHostItems(state.templates, hostId, orderedIds);
    if (result.kind === 'invalid') return { kind: 'invalid', reason: result.reason };
    return commit([...state.snippets], result.items);
  }

  function importLegacyRows(
    legacySnippets: unknown,
    legacyTemplates: unknown,
    knownHostIds: readonly number[],
  ): LegacyHostSnippetImportResult {
    const parsedSnippets = parseLegacySnippets(legacySnippets, knownHostIds);
    if (parsedSnippets.kind === 'invalid') {
      return { kind: 'invalid', collection: 'snippets', reason: parsedSnippets.reason, ...('index' in parsedSnippets ? { index: parsedSnippets.index } : {}) };
    }
    const parsedTemplates = parseLegacyCommandTemplates(legacyTemplates, knownHostIds);
    if (parsedTemplates.kind === 'invalid') {
      return { kind: 'invalid', collection: 'templates', reason: parsedTemplates.reason, ...('index' in parsedTemplates ? { index: parsedTemplates.index } : {}) };
    }

    const snippetById = new Map(state.snippets.map((item) => [item.id, item]));
    const templateById = new Map(state.templates.map((item) => [item.id, item]));
    for (const item of parsedSnippets.snippets) {
      const existing = snippetById.get(item.id);
      if (existing && !sameSnippet(existing, item)) return { kind: 'invalid', collection: 'snippets', reason: 'id-conflict' };
    }
    for (const item of parsedTemplates.templates) {
      const existing = templateById.get(item.id);
      if (existing && !sameTemplate(existing, item)) return { kind: 'invalid', collection: 'templates', reason: 'id-conflict' };
    }

    const addedSnippets = parsedSnippets.snippets.filter((item) => !snippetById.has(item.id));
    const addedTemplates = parsedTemplates.templates.filter((item) => !templateById.has(item.id));
    const nextSnippets = appendLegacyInHostOrder(state.snippets, addedSnippets);
    const nextTemplates = appendLegacyInHostOrder(state.templates, addedTemplates);
    const changed = addedSnippets.length > 0 || addedTemplates.length > 0;
    const persisted = changed
      ? persistAfterAssignments(nextSnippets, nextTemplates)
      : persistencePending
        ? persist()
        : true;
    return {
      kind: 'ok',
      snippetsAdded: addedSnippets.length,
      templatesAdded: addedTemplates.length,
      persisted,
    };
  }

  function persistAfterAssignments(snippets: HostSnippet[], templates: HostCommandTemplate[]): boolean {
    state.snippets = snippets;
    state.templates = templates;
    return persist();
  }

  return Object.assign(state, {
    snippetsForHost(hostId: string) { return snippetsForHost(state.snippets, hostId); },
    commandTemplatesForHost(hostId: string) { return commandTemplatesForHost(state.templates, hostId); },
    saveSnippet,
    deleteSnippet,
    reorderSnippets,
    saveTemplate,
    deleteTemplate,
    reorderTemplates,
    importLegacyRows,
  }) as HostSnippetLibraryStore;
}

function appendLegacyInHostOrder<T extends { id: string; hostId: string; sortOrder: number }>(
  current: readonly T[],
  imported: readonly T[],
): T[] {
  const result = [...current];
  const nextOrder = new Map<string, number>();
  for (const item of imported) {
    const position = nextOrder.get(item.hostId) ?? (maxOrder(result, item.hostId) + 1);
    result.push({ ...item, sortOrder: position });
    nextOrder.set(item.hostId, position + 1);
  }
  return result;
}
