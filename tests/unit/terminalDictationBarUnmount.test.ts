import { createRenderer, getCurrentInstance, h, ssrContextKey, type App } from 'vue';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { DictationEvent, DictationSession } from '../../src/session/platformInput';

const mocks = vi.hoisted(() => ({
  addListener: vi.fn(),
  startDictation: vi.fn(),
}));

vi.mock('@capacitor/app', () => ({
  App: { addListener: mocks.addListener },
}));
vi.mock('../../src/session/platformInput', () => ({
  platformInput: { startDictation: mocks.startDictation },
}));

import TerminalDictationBar from '../../src/components/TerminalDictationBar.vue';

interface HostNode {
  type: string;
  props: Record<string, unknown>;
  children: HostNode[];
  parent?: HostNode;
  text?: string;
}

function node(type: string, text = ''): HostNode {
  return { type, props: {}, children: [], text };
}

const renderer = createRenderer<HostNode, HostNode>({
  patchProp(element, key, _previous, next) {
    if (next == null) delete element.props[key];
    else element.props[key] = next;
  },
  insert(child, parent, anchor) {
    if (child.parent) {
      const oldIndex = child.parent.children.indexOf(child);
      if (oldIndex >= 0) child.parent.children.splice(oldIndex, 1);
    }
    child.parent = parent;
    const index = anchor ? parent.children.indexOf(anchor) : -1;
    if (index < 0) parent.children.push(child);
    else parent.children.splice(index, 0, child);
  },
  remove(child) {
    if (!child.parent) return;
    const index = child.parent.children.indexOf(child);
    if (index >= 0) child.parent.children.splice(index, 1);
    child.parent = undefined;
  },
  createElement: (type) => node(type),
  createText: (text) => node('#text', text),
  createComment: (text) => node('#comment', text),
  setText: (element, text) => { element.text = text; },
  setElementText: (element, text) => {
    element.children = [];
    element.text = text;
  },
  parentNode: (element) => element.parent ?? null,
  nextSibling: (element) => {
    if (!element.parent) return null;
    const index = element.parent.children.indexOf(element);
    return element.parent.children[index + 1] ?? null;
  },
});

const mountedDictationBar = {
  ...TerminalDictationBar,
  render() {
    const internal = getCurrentInstance() as unknown as { setupState?: Record<string, unknown> } | null;
    const toggleDictation = internal?.setupState?.toggleDictation as (() => void) | undefined;
    return h('button', { 'data-testid': 'inline-dictation-toggle', onClick: toggleDictation });
  },
};

function findByTestId(root: HostNode, testId: string): HostNode | undefined {
  if (root.props['data-testid'] === testId) return root;
  for (const child of root.children) {
    const match = findByTestId(child, testId);
    if (match) return match;
  }
  return undefined;
}

async function flushPromises() {
  await Promise.resolve();
  await Promise.resolve();
}

describe('terminal dictation bar lifecycle', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('cancels on unmount, clears parent state, and ignores late transcript events', async () => {
    let recognitionEvent: ((event: DictationEvent) => void) | undefined;
    const stop = vi.fn(async () => {
      recognitionEvent?.({ requestId: 'inline-unmount-1', type: 'stopped' });
    });
    const session: DictationSession = {
      requestId: 'inline-unmount-1',
      stop,
      cancel: vi.fn(async () => {}),
    };
    mocks.addListener.mockResolvedValue({ remove: vi.fn(async () => {}) });
    mocks.startDictation.mockImplementation(async (onEvent: (event: DictationEvent) => void) => {
      recognitionEvent = onEvent;
      onEvent({ requestId: 'inline-unmount-1', type: 'started' });
      return session;
    });

    const root = node('root');
    const states: Array<{ phase: string; preview: string; tone: string; message: string }> = [];
    const insertText = vi.fn(async () => true);
    const app = renderer.createApp(mountedDictationBar, {
      enabled: true,
      targetKey: 'host/session-1',
      languageTag: 'auto',
      silenceWindowMs: 4_000,
      insertText,
      onStateChange: (state: { phase: string; preview: string; tone: string; message: string }) => states.push(state),
    }) as App;
    app.provide(ssrContextKey, { modules: new Set<string>() });
    app.mount(root);
    await flushPromises();

    const toggle = findByTestId(root, 'inline-dictation-toggle');
    expect(toggle).toBeDefined();
    (toggle?.props.onClick as (() => void) | undefined)?.();
    await flushPromises();
    expect(states.at(-1)?.phase).toBe('listening');
    recognitionEvent?.({ requestId: 'inline-unmount-1', type: 'partial', text: 'discard this phrase' });
    recognitionEvent?.({ requestId: 'inline-unmount-1', type: 'result', text: 'discard this phrase' });
    expect(insertText).not.toHaveBeenCalled();

    app.unmount();
    recognitionEvent?.({ requestId: 'inline-unmount-1', type: 'result', text: 'late phrase must not insert' });
    recognitionEvent?.({ requestId: 'inline-unmount-1', type: 'stopped' });
    await flushPromises();

    expect(stop).toHaveBeenCalledOnce();
    expect(states.at(-1)).toEqual({
      phase: 'idle',
      preview: '',
      message: 'Tap the microphone to dictate at the terminal cursor.',
      tone: 'quiet',
    });
    expect(insertText).not.toHaveBeenCalled();
  });
});
