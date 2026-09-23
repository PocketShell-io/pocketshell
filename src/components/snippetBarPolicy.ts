import {
  commandTemplateInsertion,
  commandTemplatesForHost,
  reorderHostItems,
  snippetInsertion,
  snippetsForHost,
  type HostCommandTemplate,
  type HostSnippet,
  type LiteralDraftInsertion,
} from '@pocketshell/core';

export type SnippetBarEntry =
  | { kind: 'snippet'; key: string; label: string; source: HostSnippet }
  | { kind: 'template'; key: string; label: string; source: HostCommandTemplate };

export type SnippetBarSelection =
  | { kind: 'snippet'; hostId: string; item: HostSnippet; insertion: LiteralDraftInsertion }
  | { kind: 'template'; hostId: string; item: HostCommandTemplate; insertion: LiteralDraftInsertion };

export type SnippetBarItemRequest =
  | { kind: 'snippet'; hostId: string; item: HostSnippet }
  | { kind: 'template'; hostId: string; item: HostCommandTemplate };

export type SnippetBarReorderRequest =
  | { kind: 'snippet'; hostId: string; orderedIds: string[]; items: HostSnippet[] }
  | { kind: 'template'; hostId: string; orderedIds: string[]; items: HostCommandTemplate[] };

/**
 * Keep the imported Room-compatible rows intact at the component boundary.
 * This creates display entries without rewriting the durable IDs, host IDs,
 * text, kind, or saved order on either collection.
 */
export function snippetBarEntriesForHost(
  snippets: readonly HostSnippet[],
  templates: readonly HostCommandTemplate[],
  hostId: string,
): SnippetBarEntry[] {
  return [
    ...snippetsForHost(snippets, hostId).map((source): SnippetBarEntry => ({
      kind: 'snippet',
      key: `snippet:${source.id}`,
      label: snippetLabel(source),
      source,
    })),
    ...commandTemplatesForHost(templates, hostId).map((source): SnippetBarEntry => ({
      kind: 'template',
      key: `template:${source.id}`,
      label: source.label.trim() || 'Untitled command',
      source,
    })),
  ];
}

/** Build an explicit draft-only action using the shared core policy. */
export function snippetBarSelection(
  entry: SnippetBarEntry,
): SnippetBarSelection {
  if (entry.kind === 'snippet') {
    return {
      kind: 'snippet',
      hostId: entry.source.hostId,
      item: entry.source,
      insertion: snippetInsertion(entry.source),
    };
  }
  return {
    kind: 'template',
    hostId: entry.source.hostId,
    item: entry.source,
    insertion: commandTemplateInsertion(entry.source),
  };
}

export function snippetBarItemRequest(entry: SnippetBarEntry): SnippetBarItemRequest {
  return entry.kind === 'snippet'
    ? { kind: 'snippet', hostId: entry.source.hostId, item: entry.source }
    : { kind: 'template', hostId: entry.source.hostId, item: entry.source };
}

/** Move one item within its own host and legacy collection, using core ordering. */
export function snippetBarMoveRequest(
  snippets: readonly HostSnippet[],
  templates: readonly HostCommandTemplate[],
  hostId: string,
  kind: SnippetBarEntry['kind'],
  itemId: string,
  offset: -1 | 1,
): SnippetBarReorderRequest | null {
  if (kind === 'snippet') {
    const orderedIds = moveIds(snippetsForHost(snippets, hostId).map((item) => item.id), itemId, offset);
    if (!orderedIds) return null;
    const result = reorderHostItems(snippets, hostId, orderedIds);
    return result.kind === 'ok' ? { kind, hostId, orderedIds, items: result.items } : null;
  }

  const orderedIds = moveIds(commandTemplatesForHost(templates, hostId).map((item) => item.id), itemId, offset);
  if (!orderedIds) return null;
  const result = reorderHostItems(templates, hostId, orderedIds);
  return result.kind === 'ok' ? { kind, hostId, orderedIds, items: result.items } : null;
}

export function snippetBarCanMove(
  entries: readonly SnippetBarEntry[],
  entry: SnippetBarEntry,
  offset: -1 | 1,
): boolean {
  const group = entries.filter((candidate) => candidate.kind === entry.kind);
  const index = group.findIndex((candidate) => candidate.key === entry.key);
  const destination = index + offset;
  return index >= 0 && destination >= 0 && destination < group.length;
}

function moveIds(ids: readonly string[], itemId: string, offset: -1 | 1): string[] | null {
  const index = ids.indexOf(itemId);
  const destination = index + offset;
  if (index < 0 || destination < 0 || destination >= ids.length) return null;
  const reordered = [...ids];
  [reordered[index], reordered[destination]] = [reordered[destination]!, reordered[index]!];
  return reordered;
}

function snippetLabel(snippet: HostSnippet): string {
  if (snippet.label?.trim()) return snippet.label.trim();
  const firstLine = snippet.body.split(/\r?\n/, 1)[0]?.trim();
  return firstLine || 'Untitled snippet';
}
