import { createRenderer, getCurrentInstance, h, ssrContextKey } from 'vue';
import { createPinia } from 'pinia';
import { afterEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  appStateListener: undefined as ((state: { isActive: boolean }) => void) | undefined,
  addListener: vi.fn(),
  startDictation: vi.fn(),
}));

vi.mock('@capacitor/app', () => ({ App: { addListener: mocks.addListener } }));
vi.mock('@pocketshell/ui', () => ({ ComposerControls: { render: () => null } }));
vi.mock('../../src/session/platformInput', () => ({
  platformInput: { startDictation: mocks.startDictation },
}));

import PromptComposer from '../../src/components/PromptComposer.vue';

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

// Vitest's SSR module transform supplies the component setup/lifecycle but not
// a client render function. Keep the actual setup and app-state listener, with
// a tiny host render surface that exposes the real Dictate handler to the test.
const mountedPromptComposer = {
  ...PromptComposer,
  render() {
    const internalInstance = getCurrentInstance() as unknown as { setupState?: Record<string, unknown> } | null;
    const toggleDictation = internalInstance?.setupState?.toggleDictation as (() => Promise<void>) | undefined;
    return h('button', {
      'data-testid': 'composer-dictate',
      onClick: toggleDictation,
    });
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

describe('pending dictation start cancellation', () => {
  afterEach(() => {
    vi.clearAllMocks();
    mocks.appStateListener = undefined;
  });

  it('stops a pending start once when the mounted composer backgrounds', async () => {
    mocks.addListener.mockImplementation(async (_event: string, listener: (state: { isActive: boolean }) => void) => {
      mocks.appStateListener = listener;
      return { remove: vi.fn(async () => {}) };
    });

    let resolveStart!: (session: { requestId: string; stop: () => Promise<void> }) => void;
    const pendingStart = new Promise<{ requestId: string; stop: () => Promise<void> }>((resolve) => {
      resolveStart = resolve;
    });
    let dictationEvent: ((event: { requestId: string; type: 'stopped' }) => void) | undefined;
    mocks.startDictation.mockImplementation((onEvent: typeof dictationEvent) => {
      dictationEvent = onEvent;
      return pendingStart;
    });

    const stop = vi.fn(async () => {
      dictationEvent?.({ requestId: 'dictation-1', type: 'stopped' });
    });
    const root = node('root');
    const app = renderer.createApp(mountedPromptComposer, {
      targetKey: 'host/session',
      targetLabel: 'session',
      transportState: 'connected',
      writePty: vi.fn(async () => ({ ok: true })),
    });
    app.use(createPinia());
    app.provide(ssrContextKey, { modules: new Set<string>() });
    app.mount(root);
    await flushPromises();

    expect(mocks.appStateListener).toBeTypeOf('function');
    const dictate = findByTestId(root, 'composer-dictate');
    expect(dictate).toBeDefined();
    const onClick = dictate?.props.onClick;
    expect(onClick).toBeTypeOf('function');
    const starting = (onClick as () => Promise<void>)();
    expect(mocks.startDictation).toHaveBeenCalledTimes(1);

    mocks.appStateListener?.({ isActive: false });
    expect(stop).not.toHaveBeenCalled();

    resolveStart({ requestId: 'dictation-1', stop });
    await starting;

    expect(stop).toHaveBeenCalledTimes(1);
    app.unmount();
    expect(stop).toHaveBeenCalledTimes(1);
  });
});
