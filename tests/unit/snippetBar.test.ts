import { createRenderer, createSSRApp, h, nextTick } from 'vue';
import { renderToString } from 'vue/server-renderer';
import { describe, expect, it } from 'vitest';
import type { HostCommandTemplate, HostSnippet } from '@pocketshell/core';
import SnippetBar from '../../src/components/SnippetBar.vue';
import {
  snippetBarCanMove,
  snippetBarEntriesForHost,
  snippetBarItemRequest,
  snippetBarMoveRequest,
  snippetBarSelection,
  type SnippetBarItemRequest,
  type SnippetBarReorderRequest,
  type SnippetBarSelection,
} from '../../src/components/snippetBarPolicy';

const snippets: HostSnippet[] = [
  { id: 's2', hostId: 'host-a', label: 'Second', body: 'second\nline', kind: 'prompt', sortOrder: 2 },
  { id: 's1', hostId: 'host-a', label: 'First', body: 'héllo 🐚\r\nsecond\n', kind: 'command', sortOrder: 1 },
  { id: 'other', hostId: 'host-b', label: 'Private host snippet', body: 'other', kind: 'command', sortOrder: 0 },
];

const templates: HostCommandTemplate[] = [
  { id: 't1', hostId: 'host-a', label: 'Deploy', commands: 'echo start\n./deploy\n', sortOrder: 0 },
  { id: 'other-template', hostId: 'host-b', label: 'Other host template', commands: 'private', sortOrder: 0 },
];

type TestNode = {
  kind: 'root' | 'element' | 'text' | 'comment' | 'static';
  tag?: string;
  text?: string;
  props: Record<string, unknown>;
  children: TestNode[];
  parent: TestNode | null;
};

function testNode(kind: TestNode['kind'], text?: string, tag?: string): TestNode {
  return { kind, ...(tag ? { tag } : {}), ...(text === undefined ? {} : { text }), props: {}, children: [], parent: null };
}

function createMountedSnippetBar() {
  const root = testNode('root');
  const events = {
    select: [] as SnippetBarSelection[],
    create: [] as { kind: 'snippet' | 'template'; hostId: string }[],
    edit: [] as SnippetBarItemRequest[],
    delete: [] as SnippetBarItemRequest[],
    reorder: [] as SnippetBarReorderRequest[],
  };

  const renderer = createRenderer<TestNode, TestNode>({
    createElement: (tag) => testNode('element', undefined, tag),
    createText: (text) => testNode('text', text),
    createComment: (text) => testNode('comment', text),
    setText: (node, text) => { node.text = text; },
    setElementText: (node, text) => {
      for (const child of node.children) child.parent = null;
      node.children = [];
      node.text = text;
    },
    patchProp: (node, key, _previous, next) => {
      if (next === null || next === undefined) delete node.props[key];
      else node.props[key] = next;
    },
    insert: (node, parent, anchor = null) => {
      if (node.parent) {
        const oldIndex = node.parent.children.indexOf(node);
        if (oldIndex >= 0) node.parent.children.splice(oldIndex, 1);
      }
      const index = anchor ? parent.children.indexOf(anchor) : -1;
      if (index >= 0) parent.children.splice(index, 0, node);
      else parent.children.push(node);
      node.parent = parent;
    },
    remove: (node) => {
      if (!node.parent) return;
      const index = node.parent.children.indexOf(node);
      if (index >= 0) node.parent.children.splice(index, 1);
      node.parent = null;
    },
    parentNode: (node) => node.parent,
    nextSibling: (node) => {
      if (!node.parent) return null;
      const index = node.parent.children.indexOf(node);
      return node.parent.children[index + 1] ?? null;
    },
    insertStaticContent: (content, parent, anchor) => {
      // Static display-only fragments are opaque here; interactive buttons are
      // dynamic vnodes and remain individually reachable in the test tree.
      const node = testNode('static', content);
      if (node.parent) throw new Error('New static test node unexpectedly has a parent');
      const index = anchor ? parent.children.indexOf(anchor) : -1;
      if (index >= 0) parent.children.splice(index, 0, node);
      else parent.children.push(node);
      node.parent = parent;
      return [node, node];
    },
  });

  const app = renderer.createApp(SnippetBar, {
    hostId: 'host-a',
    hostLabel: 'Dev box',
    snippets,
    templates,
    onSelect: (payload: SnippetBarSelection) => events.select.push(payload),
    onCreate: (payload: { kind: 'snippet' | 'template'; hostId: string }) => events.create.push(payload),
    onEdit: (payload: SnippetBarItemRequest) => events.edit.push(payload),
    onDelete: (payload: SnippetBarItemRequest) => events.delete.push(payload),
    onReorder: (payload: SnippetBarReorderRequest) => events.reorder.push(payload),
  });
  app.mount(root);

  return { root, events, unmount: () => app.unmount() };
}

function findNode(root: TestNode, predicate: (node: TestNode) => boolean): TestNode {
  const pending = [root];
  while (pending.length > 0) {
    const node = pending.pop();
    if (!node) break;
    if (predicate(node)) return node;
    pending.push(...node.children);
  }
  throw new Error('Could not find the requested mounted Vue node');
}

function textContent(node: TestNode): string {
  return `${node.text ?? ''}${node.children.map(textContent).join('')}`;
}

async function click(root: TestNode, predicate: (node: TestNode) => boolean): Promise<void> {
  const target = findNode(root, predicate);
  const handler = target.props.onClick;
  if (typeof handler !== 'function') throw new Error('Requested mounted Vue node has no click handler');
  handler();
  await nextTick();
}

describe('per-host snippet bar policy and UI', () => {
  it('shows only the active host in saved order and preserves each legacy-compatible row', () => {
    const entries = snippetBarEntriesForHost(snippets, templates, 'host-a');

    expect(entries.map((entry) => entry.key)).toEqual(['snippet:s1', 'snippet:s2', 'template:t1']);
    expect(entries.map((entry) => entry.label)).toEqual(['First', 'Second', 'Deploy']);
    expect(entries[0]?.kind).toBe('snippet');
    if (entries[0]?.kind !== 'snippet') throw new Error('Expected the first entry to be a snippet');
    expect(entries[0].source).toEqual(snippets[1]);
    expect(entries[0].source.body).toBe('héllo 🐚\r\nsecond\n');
    expect(entries.some((entry) => entry.label.includes('Other host'))).toBe(false);
  });

  it('uses core insertion policy and returns literal draft text without submit or newline', () => {
    const [snippetEntry, , templateEntry] = snippetBarEntriesForHost(snippets, templates, 'host-a');
    if (!snippetEntry || !templateEntry) throw new Error('Expected snippet and template entries');

    const snippetSelection = snippetBarSelection(snippetEntry);
    const templateSelection = snippetBarSelection(templateEntry);

    expect(snippetSelection).toMatchObject({
      kind: 'snippet',
      hostId: 'host-a',
      insertion: { kind: 'insert-literal', text: 'héllo 🐚\r\nsecond\n', submit: false },
    });
    expect(templateSelection).toMatchObject({
      kind: 'template',
      hostId: 'host-a',
      insertion: { kind: 'insert-literal', text: 'echo start\n./deploy\n', submit: false },
    });
  });

  it('renders an accessible keyboard-down chip row and host manage toggle', async () => {
    const html = await renderToString(createSSRApp(SnippetBar, {
      hostId: 'host-a',
      hostLabel: 'Dev box',
      snippets,
      templates,
      keyboardVisible: false,
    }));

    expect(html).toContain('Command snippets for Dev box');
    expect(html).toContain('Insert a snippet for Dev box');
    expect(html).toContain('aria-label="Insert First into the draft"');
    expect(html).toContain('aria-pressed="false"');
    expect(html).toContain('snippet-manage-toggle');
    expect(html).toContain('Manage');
    expect(html).not.toContain('Private host snippet');
    expect(html).not.toContain('Other host template');
  });

  it('does not render the chip row while the keyboard is visible', async () => {
    const html = await renderToString(createSSRApp(SnippetBar, {
      hostId: 'host-a',
      hostLabel: 'Dev box',
      snippets,
      templates,
      keyboardVisible: true,
    }));

    expect(html).not.toContain('data-testid="snippet-bar"');
  });

  it('builds host-bound edit/delete requests without reshaping imported rows', () => {
    const [snippetEntry, , templateEntry] = snippetBarEntriesForHost(snippets, templates, 'host-a');
    if (!snippetEntry || !templateEntry) throw new Error('Expected snippet and template entries');

    expect(snippetBarItemRequest(snippetEntry)).toEqual({
      kind: 'snippet', hostId: 'host-a', item: snippets[1],
    });
    expect(snippetBarItemRequest(templateEntry)).toEqual({
      kind: 'template', hostId: 'host-a', item: templates[0],
    });
  });

  it('moves only within the selected host collection and disables moves at group boundaries', () => {
    const entries = snippetBarEntriesForHost(snippets, templates, 'host-a');
    const first = entries[0];
    const second = entries[1];
    if (!first || !second) throw new Error('Expected two host snippets');

    expect(snippetBarCanMove(entries, first, -1)).toBe(false);
    expect(snippetBarCanMove(entries, first, 1)).toBe(true);
    expect(snippetBarCanMove(entries, second, 1)).toBe(false);
    expect(snippetBarMoveRequest(snippets, templates, 'host-a', 'snippet', 's1', 1)).toEqual({
      kind: 'snippet',
      hostId: 'host-a',
      orderedIds: ['s2', 's1'],
      items: [
        { ...snippets[0], sortOrder: 0 },
        { ...snippets[1], sortOrder: 1 },
        snippets[2],
      ],
    });
    expect(snippetBarMoveRequest(snippets, templates, 'host-b', 'snippet', 's1', 1)).toBeNull();
  });

  it('emits host-bound selection and management payloads from mounted controls', async () => {
    const mounted = createMountedSnippetBar();
    const { root, events } = mounted;
    try {
      await click(root, (node) => node.props['data-testid'] === 'snippet-chip-snippet:s1');
      await click(root, (node) => node.props['data-testid'] === 'snippet-chip-template:t1');
      expect(events.select).toEqual([
        {
          kind: 'snippet',
          hostId: 'host-a',
          item: snippets[1],
          insertion: { kind: 'insert-literal', text: 'héllo 🐚\r\nsecond\n', submit: false },
        },
        {
          kind: 'template',
          hostId: 'host-a',
          item: templates[0],
          insertion: { kind: 'insert-literal', text: 'echo start\n./deploy\n', submit: false },
        },
      ]);

      await click(root, (node) => node.props['data-testid'] === 'snippet-manage-toggle');
      await click(root, (node) => node.tag === 'button' && textContent(node).trim() === 'Add snippet');
      await click(root, (node) => node.tag === 'button' && textContent(node).trim() === 'Add command');
      await click(root, (node) => node.props['aria-label'] === 'Edit First');
      await click(root, (node) => node.props['aria-label'] === 'Delete Deploy');
      await click(root, (node) => node.props['aria-label'] === 'Move First down');

      expect(events.create).toEqual([
        { kind: 'snippet', hostId: 'host-a' },
        { kind: 'template', hostId: 'host-a' },
      ]);
      expect(events.edit).toEqual([
        { kind: 'snippet', hostId: 'host-a', item: snippets[1] },
      ]);
      expect(events.delete).toEqual([
        { kind: 'template', hostId: 'host-a', item: templates[0] },
      ]);
      expect(events.reorder).toEqual([
        {
          kind: 'snippet',
          hostId: 'host-a',
          orderedIds: ['s2', 's1'],
          items: [
            { ...snippets[0], sortOrder: 0 },
            { ...snippets[1], sortOrder: 1 },
            snippets[2],
          ],
        },
      ]);
    } finally {
      mounted.unmount();
    }
  });
});
