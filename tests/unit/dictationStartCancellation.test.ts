import { createRenderer, getCurrentInstance, h, ssrContextKey } from 'vue';
import { createPinia } from 'pinia';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useComposerDrafts } from '../../src/stores/composerDrafts';

const mocks = vi.hoisted(() => ({
  appStateListener: undefined as ((state: { isActive: boolean }) => void) | undefined,
  addListener: vi.fn(),
  pickAttachments: vi.fn(),
  startDictation: vi.fn(),
}));

vi.mock('@capacitor/app', () => ({ App: { addListener: mocks.addListener } }));
vi.mock('@pocketshell/ui', () => ({ ComposerControls: { render: () => null } }));
vi.mock('../../src/session/platformInput', () => ({
  platformInput: { pickAttachments: mocks.pickAttachments, startDictation: mocks.startDictation },
}));

import PromptComposer from '../../src/components/PromptComposer.vue';

let renderedSetupState: Record<string, unknown> | undefined;

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
    renderedSetupState = internalInstance?.setupState;
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

describe('prompt composer dictation', () => {
  afterEach(() => {
    vi.clearAllMocks();
    vi.unstubAllGlobals();
    mocks.appStateListener = undefined;
    renderedSetupState = undefined;
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
      stageAttachments: vi.fn(async () => ({ staged: [], failures: [] })),
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

  it('starts with saved Voice settings, appends recognition to the draft, and leaves it editable after stop', async () => {
    mocks.addListener.mockImplementation(async (_event: string, listener: (state: { isActive: boolean }) => void) => {
      mocks.appStateListener = listener;
      return { remove: vi.fn(async () => {}) };
    });

    const pinia = createPinia();
    const drafts = useComposerDrafts(pinia);
    drafts.setDraft('host/session', 'review the deployment');
    let dictationEvent: ((event: { requestId: string; type: string; text?: string }) => void) | undefined;
    const stop = vi.fn(async () => {
      dictationEvent?.({ requestId: 'dictation-live', type: 'result', text: 'and report failures' });
      dictationEvent?.({ requestId: 'dictation-live', type: 'stopped' });
    });
    mocks.startDictation.mockImplementation(async (onEvent: typeof dictationEvent) => {
      dictationEvent = onEvent;
      return { requestId: 'dictation-live', stop };
    });

    const root = node('root');
    const app = renderer.createApp(mountedPromptComposer, {
      targetKey: 'host/session',
      targetLabel: 'session',
      transportState: 'connected',
      dictationLanguageTag: 'de-DE',
      dictationSilenceWindowMs: 9_000,
      writePty: vi.fn(async () => ({ ok: true })),
      stageAttachments: vi.fn(async () => ({ staged: [], failures: [] })),
    });
    app.use(pinia);
    app.provide(ssrContextKey, { modules: new Set<string>() });
    app.mount(root);
    await flushPromises();

    const dictate = findByTestId(root, 'composer-dictate');
    const onClick = dictate?.props.onClick as (() => Promise<void>) | undefined;
    expect(onClick).toBeTypeOf('function');
    await onClick?.();

    expect(mocks.startDictation).toHaveBeenCalledWith(expect.any(Function), {
      languageTag: 'de-DE',
      silenceWindowMs: 9_000,
    });
    dictationEvent?.({ requestId: 'dictation-live', type: 'partial', text: 'and report' });
    expect(drafts.draftFor('host/session')).toBe('review the deployment and report');
    expect(renderedSetupState?.dictationActive).toBe(true);

    await onClick?.();
    expect(stop).toHaveBeenCalledTimes(1);
    expect(drafts.draftFor('host/session')).toBe('review the deployment and report failures');
    expect(renderedSetupState?.dictationActive).toBe(false);

    class TextAreaStub { value = 'review the deployment and report failures, then summarize'; }
    vi.stubGlobal('HTMLTextAreaElement', TextAreaStub);
    const setDraft = renderedSetupState?.setDraft as ((event: Event) => void) | undefined;
    setDraft?.({ target: new TextAreaStub() } as unknown as Event);
    expect(drafts.draftFor('host/session')).toBe('review the deployment and report failures, then summarize');

    app.unmount();
  });

  it('stops active recognition on background and retains its latest partial in the session draft', async () => {
    mocks.addListener.mockImplementation(async (_event: string, listener: (state: { isActive: boolean }) => void) => {
      mocks.appStateListener = listener;
      return { remove: vi.fn(async () => {}) };
    });
    const pinia = createPinia();
    const drafts = useComposerDrafts(pinia);
    drafts.setDraft('host/session', 'inspect');
    let dictationEvent: ((event: { requestId: string; type: string; text?: string }) => void) | undefined;
    const stop = vi.fn(async () => {
      dictationEvent?.({ requestId: 'dictation-background', type: 'stopped' });
    });
    mocks.startDictation.mockImplementation(async (onEvent: typeof dictationEvent) => {
      dictationEvent = onEvent;
      return { requestId: 'dictation-background', stop };
    });

    const root = node('root');
    const app = renderer.createApp(mountedPromptComposer, {
      targetKey: 'host/session',
      targetLabel: 'session',
      transportState: 'connected',
      dictationLanguageTag: 'auto',
      dictationSilenceWindowMs: 4_000,
      writePty: vi.fn(async () => ({ ok: true })),
      stageAttachments: vi.fn(async () => ({ staged: [], failures: [] })),
    });
    app.use(pinia);
    app.provide(ssrContextKey, { modules: new Set<string>() });
    app.mount(root);
    await flushPromises();

    const dictate = findByTestId(root, 'composer-dictate');
    const onClick = dictate?.props.onClick as (() => Promise<void>) | undefined;
    await onClick?.();
    expect(mocks.startDictation).toHaveBeenCalledWith(expect.any(Function), {
      languageTag: 'auto',
      silenceWindowMs: 4_000,
    });
    dictationEvent?.({ requestId: 'dictation-background', type: 'partial', text: 'the service logs' });
    mocks.appStateListener?.({ isActive: false });
    await flushPromises();

    expect(stop).toHaveBeenCalledTimes(1);
    expect(renderedSetupState?.dictationActive).toBe(false);
    expect(drafts.draftFor('host/session')).toBe('inspect the service logs');
    app.unmount();
    expect(stop).toHaveBeenCalledTimes(1);
  });

  it('stages picker bytes without sending them until the user explicitly delivers the draft', async () => {
    mocks.addListener.mockImplementation(async () => ({ remove: vi.fn(async () => {}) }));
    const pinia = createPinia();
    const drafts = useComposerDrafts(pinia);
    const bytes = Uint8Array.from([0, 0xff, 0x41]);
    mocks.pickAttachments.mockResolvedValue({
      sources: [{ kind: 'bytes', name: 'notes.bin', mimeType: 'application/octet-stream', data: bytes }],
      failures: [],
    });
    const writePty = vi.fn(async (_bytes: Uint8Array) => ({ ok: true }));
    const stageAttachments = vi.fn(async (_targetKey: string, pending: Array<{ id: string; source: { data: Uint8Array; name?: string | null } }>) => ({
      staged: pending.map((attachment) => ({
        id: attachment.id,
        path: '/home/testuser/.pocketshell/attachments/notes.bin',
        name: attachment.source.name ?? 'Shared file',
        sizeBytes: attachment.source.data.byteLength,
      })),
      failures: [],
    }));
    const root = node('root');
    const app = renderer.createApp(mountedPromptComposer, {
      targetKey: 'host/session',
      targetLabel: 'session',
      transportState: 'connected',
      dictationLanguageTag: 'auto',
      dictationSilenceWindowMs: 4_000,
      writePty,
      stageAttachments,
    });
    app.use(pinia);
    app.provide(ssrContextKey, { modules: new Set<string>() });
    app.mount(root);
    await flushPromises();
    drafts.setDraft('host/session', 'Review café');

    await (renderedSetupState?.pickAttachments as () => Promise<void>)();
    await vi.waitFor(() => expect(drafts.stagedAttachmentsFor('host/session')).toHaveLength(1));
    expect(stageAttachments).toHaveBeenCalledTimes(1);
    expect(stageAttachments.mock.calls[0]?.[1][0]?.source.data).toEqual(bytes);
    expect(writePty).not.toHaveBeenCalled();
    expect(drafts.draftFor('host/session')).toBe('Review café');

    await (renderedSetupState?.deliver as (intent: 'submit') => Promise<void>)('submit');

    const written = writePty.mock.calls.map(([chunk]) => new TextDecoder().decode(chunk)).join('');
    expect(written).toContain('Review café\n\nAttached files:\n- /home/testuser/.pocketshell/attachments/notes.bin');
    expect(drafts.draftFor('host/session')).toBe('');
    expect(drafts.stagedAttachmentsFor('host/session')).toEqual([]);
    app.unmount();
  });
});
