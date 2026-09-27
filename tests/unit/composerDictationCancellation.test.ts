import { createRenderer, getCurrentInstance, h, ssrContextKey } from 'vue';
import { createPinia } from 'pinia';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useComposerDrafts } from '../../src/stores/composerDrafts';
import { useAppSettings } from '../../src/stores/appSettings';

const mocks = vi.hoisted(() => ({
  appStateListener: undefined as ((state: { isActive: boolean }) => void) | undefined,
  addListener: vi.fn(),
  startDictation: vi.fn(),
  cancelDictation: vi.fn(async () => {}),
}));

vi.mock('@capacitor/app', () => ({ App: { addListener: mocks.addListener } }));
vi.mock('@pocketshell/ui', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@pocketshell/ui')>();
  return { ...actual, ComposerControls: { render: () => null } };
});
vi.mock('../../src/session/platformInput', () => ({
  platformInput: { startDictation: mocks.startDictation, cancelDictation: mocks.cancelDictation },
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
    const livePreview = internalInstance?.setupState?.dictationPreview as string | undefined;
    const phase = internalInstance?.setupState?.dictationPhase as string | undefined;
    const activeDictation = internalInstance?.setupState?.activeDictation as object | null | undefined;
    const stopDictation = internalInstance?.setupState?.stopDictation as ((operation: object) => Promise<void>) | undefined;
    const cancelDictation = internalInstance?.setupState?.cancelDictation as ((operation: object) => void) | undefined;
    const deliver = internalInstance?.setupState?.deliver as ((intent: 'insert' | 'submit') => Promise<void>) | undefined;
    const canDeliver = internalInstance?.setupState?.canDeliver as boolean | undefined;
    return h('section', [
      h('button', {
        'data-testid': 'composer-dictate',
        onClick: toggleDictation,
      }),
      h('p', { 'data-testid': 'composer-dictation-state' }, phase ?? ''),
      ...(phase === 'starting' || phase === 'recording' || phase === 'transcribing' ? [h('div', {
        'data-testid': 'composer-recording-actions',
      }, [
        h('button', {
          'data-testid': 'composer-recording-cancel',
          'aria-label': 'Cancel dictation and restore the original draft',
          onClick: () => activeDictation && cancelDictation?.(activeDictation),
        }, 'Cancel'),
        ...(phase === 'recording' ? [
          h('button', {
            'data-testid': 'composer-insert',
            disabled: !canDeliver,
            onClick: () => deliver?.('insert'),
          }, 'Insert'),
        ] : []),
        ...(phase === 'recording' || phase === 'transcribing' ? [
          h('button', {
            'data-testid': 'composer-dictation-send',
            disabled: !canDeliver,
            onClick: () => deliver?.('submit'),
          }, 'Send'),
        ] : []),
        ...(phase === 'recording' ? [h('button', {
          'data-testid': 'composer-recording-stop',
          'aria-label': 'Stop dictation and keep the recognized text in the editable draft',
          onClick: () => activeDictation && stopDictation?.(activeDictation),
        }, 'Stop')] : []),
      ])] : []),
      h('p', { 'data-testid': 'composer-recording-preview' }, livePreview ?? ''),
    ]);
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

describe('composer dictation cancellation', () => {
  afterEach(() => {
    vi.clearAllMocks();
    mocks.appStateListener = undefined;
  });

  it('labels the recording restore action Cancel and restores the original draft', async () => {
    mocks.addListener.mockImplementation(async (_event: string, listener: (state: { isActive: boolean }) => void) => {
      mocks.appStateListener = listener;
      return { remove: vi.fn(async () => {}) };
    });

    let dictationEvent: ((event: { requestId: string; type: string; text?: string }) => void) | undefined;
    const stop = vi.fn(async () => {});
    const nativeCancel = vi.fn(async () => {});
    mocks.cancelDictation.mockImplementation(nativeCancel);
    mocks.startDictation.mockImplementation(async (
      onEvent: typeof dictationEvent,
      _options: object,
      onRequestId: (id: string) => void,
    ) => {
      dictationEvent = onEvent;
      onRequestId('dictation-cancel-label-1');
      dictationEvent?.({ requestId: 'dictation-cancel-label-1', type: 'started' });
      return { requestId: 'dictation-cancel-label-1', stop, cancel: vi.fn(async () => {}) };
    });

    const targetKey = 'host/cancel-label-session';
    const writePty = vi.fn(async () => ({ ok: true }));
    const pinia = createPinia();
    const root = node('root');
    const app = renderer.createApp(mountedPromptComposer, {
      targetKey,
      targetLabel: 'session',
      transportState: 'connected',
      writePty,
    });
    app.use(pinia);
    app.provide(ssrContextKey, { modules: new Set<string>() });
    app.mount(root);
    await flushPromises();

    const drafts = useComposerDrafts(pinia);
    drafts.setDraft(targetKey, 'original typed draft');
    const dictate = findByTestId(root, 'composer-dictate');
    await (dictate?.props.onClick as () => Promise<void>)();
    dictationEvent?.({ requestId: 'dictation-cancel-label-1', type: 'partial', text: 'visible partial' });
    await flushPromises();

    const cancel = findByTestId(root, 'composer-recording-cancel');
    expect(cancel?.text).toBe('Cancel');
    expect(cancel?.props['aria-label']).toBe('Cancel dictation and restore the original draft');
    expect(drafts.draftFor(targetKey)).toBe('original typed draft visible partial');
    expect(writePty).not.toHaveBeenCalled();

    (cancel?.props.onClick as () => void)();
    await flushPromises();
    expect(findByTestId(root, 'composer-dictation-state')?.text).toBe('idle');
    expect(drafts.draftFor(targetKey)).toBe('original typed draft');
    expect(findByTestId(root, 'composer-recording-preview')?.text).toBe('');
    expect(mocks.cancelDictation).toHaveBeenCalledWith('dictation-cancel-label-1');
    expect(stop).not.toHaveBeenCalled();
    expect(writePty).not.toHaveBeenCalled();

    dictationEvent?.({ requestId: 'dictation-cancel-label-1', type: 'partial', text: 'late text' });
    await flushPromises();
    expect(drafts.draftFor(targetKey)).toBe('original typed draft');
    app.unmount();
  });

  it('cancels a pending start on background, restores the base draft, and rejects late partials', async () => {
    mocks.addListener.mockImplementation(async (_event: string, listener: (state: { isActive: boolean }) => void) => {
      mocks.appStateListener = listener;
      return { remove: vi.fn(async () => {}) };
    });

    let resolveStart!: (session: { requestId: string; stop: () => Promise<void>; cancel: () => Promise<void> }) => void;
    const pendingStart = new Promise<{ requestId: string; stop: () => Promise<void>; cancel: () => Promise<void> }>((resolve) => {
      resolveStart = resolve;
    });
    let dictationEvent: ((event: { requestId: string; type: 'partial' | 'stopped'; text?: string }) => void) | undefined;
    mocks.startDictation.mockImplementation((onEvent: typeof dictationEvent, _options: object, onRequestId: (id: string) => void) => {
      dictationEvent = onEvent;
      onRequestId('dictation-1');
      return pendingStart;
    });

    const stop = vi.fn(async () => {
      dictationEvent?.({ requestId: 'dictation-1', type: 'stopped' });
    });
    const cancel = vi.fn(async () => {});
    const pinia = createPinia();
    const root = node('root');
    const app = renderer.createApp(mountedPromptComposer, {
      targetKey: 'host/session',
      targetLabel: 'session',
      transportState: 'connected',
      writePty: vi.fn(async () => ({ ok: true })),
    });
    app.use(pinia);
    app.provide(ssrContextKey, { modules: new Set<string>() });
    app.mount(root);
    await flushPromises();
    const drafts = useComposerDrafts(pinia);
    drafts.setDraft('host/session', 'keep this typed draft');

    expect(mocks.appStateListener).toBeTypeOf('function');
    const dictate = findByTestId(root, 'composer-dictate');
    expect(dictate).toBeDefined();
    const onClick = dictate?.props.onClick;
    expect(onClick).toBeTypeOf('function');
    const starting = (onClick as () => Promise<void>)();
    expect(mocks.startDictation).toHaveBeenCalledTimes(1);

    dictationEvent?.({ requestId: 'dictation-1', type: 'partial', text: 'partial must be discarded' });
    await flushPromises();
    expect(drafts.draftFor('host/session')).toBe('keep this typed draft partial must be discarded');
    expect(findByTestId(root, 'composer-recording-preview')?.text).toBe('partial must be discarded');
    mocks.appStateListener?.({ isActive: false });
    await flushPromises();
    expect(stop).not.toHaveBeenCalled();
    expect(mocks.cancelDictation).toHaveBeenCalledWith('dictation-1');
    expect(drafts.draftFor('host/session')).toBe('keep this typed draft');
    expect(findByTestId(root, 'composer-recording-preview')?.text).toBe('');
    dictationEvent?.({ requestId: 'dictation-1', type: 'partial', text: 'late result must not return' });
    expect(drafts.draftFor('host/session')).toBe('keep this typed draft');

    resolveStart({ requestId: 'dictation-1', stop, cancel });
    await starting;

    expect(stop).not.toHaveBeenCalled();
    expect(cancel).toHaveBeenCalledTimes(1);
    expect(drafts.draftFor('host/session')).toBe('keep this typed draft');
    app.unmount();
    expect(cancel).toHaveBeenCalledTimes(1);
  });

  it('uses the latest persisted voice settings on the next composer start', async () => {
    mocks.addListener.mockImplementation(async (_event: string, listener: (state: { isActive: boolean }) => void) => {
      mocks.appStateListener = listener;
      return { remove: vi.fn(async () => {}) };
    });
    const optionsByStart: object[] = [];
    let startSequence = 0;
    mocks.startDictation.mockImplementation(async (
      _onEvent: unknown,
      options: object,
      onRequestId: (id: string) => void,
    ) => {
      const requestId = `dictation-settings-${++startSequence}`;
      onRequestId(requestId);
      optionsByStart.push(options);
      return { requestId, stop: vi.fn(async () => {}), cancel: vi.fn(async () => {}) };
    });

    const pinia = createPinia();
    const root = node('root');
    const app = renderer.createApp(mountedPromptComposer, {
      targetKey: 'host/settings-session',
      targetLabel: 'session',
      transportState: 'connected',
      writePty: vi.fn(async () => ({ ok: true })),
    });
    app.use(pinia);
    app.provide(ssrContextKey, { modules: new Set<string>() });
    app.mount(root);
    await flushPromises();
    const settings = useAppSettings(pinia);
    const dictate = findByTestId(root, 'composer-dictate');
    const onClick = dictate?.props.onClick;
    expect(onClick).toBeTypeOf('function');

    await (onClick as () => Promise<void>)();
    expect(optionsByStart[0]).toEqual({ silenceWindowMs: 4_000 });
    mocks.appStateListener?.({ isActive: false });
    await flushPromises();

    settings.setVoiceLanguage('de');
    settings.setVoiceSilenceSeconds(9);
    await (onClick as () => Promise<void>)();
    expect(optionsByStart[1]).toEqual({ languageTag: 'de', silenceWindowMs: 9_000 });

    app.unmount();
  });

  it('keeps recording through a natural endpoint and recognizer restart, then transcribes only on explicit Stop', async () => {
    mocks.addListener.mockImplementation(async (_event: string, listener: (state: { isActive: boolean }) => void) => {
      mocks.appStateListener = listener;
      return { remove: vi.fn(async () => {}) };
    });
    let dictationEvent: ((event: { requestId: string; type: string; text?: string }) => void) | undefined;
    const writePty = vi.fn(async () => ({ ok: true }));
    const stop = vi.fn(async () => {
      dictationEvent?.({ requestId: 'dictation-pause-1', type: 'processing' });
    });
    mocks.startDictation.mockImplementation(async (
      onEvent: typeof dictationEvent,
      _options: object,
      onRequestId: (id: string) => void,
    ) => {
      dictationEvent = onEvent;
      onRequestId('dictation-pause-1');
      dictationEvent?.({ requestId: 'dictation-pause-1', type: 'started' });
      return { requestId: 'dictation-pause-1', stop, cancel: vi.fn(async () => {}) };
    });

    const pinia = createPinia();
    const root = node('root');
    const app = renderer.createApp(mountedPromptComposer, {
      targetKey: 'host/pause-session',
      targetLabel: 'session',
      transportState: 'connected',
      writePty,
    });
    app.use(pinia);
    app.provide(ssrContextKey, { modules: new Set<string>() });
    app.mount(root);
    await flushPromises();
    const drafts = useComposerDrafts(pinia);
    drafts.setDraft('host/pause-session', 'keep typed');

    const dictate = findByTestId(root, 'composer-dictate');
    const onClick = dictate?.props.onClick;
    expect(onClick).toBeTypeOf('function');
    await (onClick as () => Promise<void>)();
    expect(findByTestId(root, 'composer-dictation-state')?.text).toBe('recording');

    dictationEvent?.({ requestId: 'dictation-pause-1', type: 'partial', text: 'recognized phrase' });
    await flushPromises();
    dictationEvent?.({ requestId: 'dictation-pause-1', type: 'processing' });
    await flushPromises();
    expect(findByTestId(root, 'composer-dictation-state')?.text).toBe('recording');
    expect(findByTestId(root, 'composer-recording-stop')).toBeDefined();

    dictationEvent?.({ requestId: 'dictation-pause-1', type: 'result', text: 'recognized phrase' });
    dictationEvent?.({ requestId: 'dictation-pause-1', type: 'ready' });
    dictationEvent?.({ requestId: 'dictation-pause-1', type: 'listening' });
    await flushPromises();
    expect(findByTestId(root, 'composer-dictation-state')?.text).toBe('recording');
    expect(findByTestId(root, 'composer-recording-stop')).toBeDefined();
    expect(findByTestId(root, 'composer-recording-preview')?.text).toBe('recognized phrase');
    expect(drafts.draftFor('host/pause-session')).toBe('keep typed recognized phrase');
    expect(writePty).not.toHaveBeenCalled();

    const stopButton = findByTestId(root, 'composer-recording-stop');
    await (stopButton?.props.onClick as () => Promise<void>)();
    expect(stop).toHaveBeenCalledTimes(1);
    expect(findByTestId(root, 'composer-dictation-state')?.text).toBe('transcribing');
    expect(findByTestId(root, 'composer-recording-stop')).toBeUndefined();
    expect(writePty).not.toHaveBeenCalled();
    dictationEvent?.({ requestId: 'dictation-pause-1', type: 'stopped' });
    await flushPromises();
    expect(findByTestId(root, 'composer-dictation-state')?.text).toBe('review');
    expect(drafts.draftFor('host/pause-session')).toBe('keep typed recognized phrase');
    expect(writePty).not.toHaveBeenCalled();

    app.unmount();
  });

  it.each([
    { phase: 'recording', intent: 'insert' },
    { phase: 'recording', intent: 'submit' },
    { phase: 'transcribing', intent: 'submit' },
  ] as const)('stops $phase capture before the explicit $intent delivery and freezes the visible transcript', async ({ phase, intent }) => {
    mocks.addListener.mockImplementation(async (_event: string, listener: (state: { isActive: boolean }) => void) => {
      mocks.appStateListener = listener;
      return { remove: vi.fn(async () => {}) };
    });

    let dictationEvent: ((event: { requestId: string; type: string; text?: string }) => void) | undefined;
    const writes: Array<{ bytes: Uint8Array; operationId: string; writeIndex: number }> = [];
    const writePty = vi.fn(async (bytes: Uint8Array, context: { operationId: string; writeIndex: number }) => {
      writes.push({ bytes, operationId: context.operationId, writeIndex: context.writeIndex });
      return { ok: true };
    });
    const stop = vi.fn(async () => {});
    mocks.startDictation.mockImplementation(async (
      onEvent: typeof dictationEvent,
      _options: object,
      onRequestId: (id: string) => void,
    ) => {
      dictationEvent = onEvent;
      onRequestId('dictation-delivery-1');
      dictationEvent?.({ requestId: 'dictation-delivery-1', type: 'started' });
      return { requestId: 'dictation-delivery-1', stop, cancel: vi.fn(async () => {}) };
    });

    const targetKey = 'host/' + phase + '-' + intent;
    const pinia = createPinia();
    const root = node('root');
    const app = renderer.createApp(mountedPromptComposer, {
      targetKey,
      targetLabel: 'session',
      transportState: 'connected',
      writePty,
    });
    app.use(pinia);
    app.provide(ssrContextKey, { modules: new Set<string>() });
    app.mount(root);
    await flushPromises();
    const drafts = useComposerDrafts(pinia);
    drafts.setDraft(targetKey, 'typed prefix');

    const dictate = findByTestId(root, 'composer-dictate');
    const start = dictate?.props.onClick;
    expect(start).toBeTypeOf('function');
    await (start as () => Promise<void>)();
    dictationEvent?.({ requestId: 'dictation-delivery-1', type: 'partial', text: 'visible transcript' });
    await flushPromises();
    expect(drafts.draftFor(targetKey)).toBe('typed prefix visible transcript');
    expect(writePty).not.toHaveBeenCalled();
    const actions = findByTestId(root, 'composer-recording-actions');
    expect(actions?.children.map((child) => child.props['data-testid'])).toEqual([
      'composer-recording-cancel', 'composer-insert', 'composer-dictation-send', 'composer-recording-stop',
    ]);
    expect(findByTestId(root, 'composer-recording-cancel')?.text).toBe('Cancel');
    expect(findByTestId(root, 'composer-recording-cancel')?.props['aria-label'])
      .toBe('Cancel dictation and restore the original draft');

    if (phase === 'transcribing') {
      const stopButton = findByTestId(root, 'composer-recording-stop');
      expect(stopButton).toBeDefined();
      await (stopButton?.props.onClick as () => Promise<void>)();
      expect(findByTestId(root, 'composer-dictation-state')?.text).toBe('transcribing');
      expect(findByTestId(root, 'composer-recording-actions')?.children.map((child) => child.props['data-testid'])).toEqual([
        'composer-recording-cancel', 'composer-dictation-send',
      ]);
      expect(findByTestId(root, 'composer-recording-cancel')?.text).toBe('Cancel');
      expect(findByTestId(root, 'composer-recording-cancel')?.props['aria-label'])
        .toBe('Cancel dictation and restore the original draft');
      expect(findByTestId(root, 'composer-insert')).toBeUndefined();
      expect(writePty).not.toHaveBeenCalled();
    }

    const action = findByTestId(root, intent === 'insert' ? 'composer-insert' : 'composer-dictation-send');
    expect(action).toBeDefined();
    expect(action?.props.disabled).toBe(false);
    const pending = (action?.props.onClick as () => Promise<void>)();
    await flushPromises();
    expect(findByTestId(root, 'composer-dictation-state')?.text).toBe('transcribing');
    expect(stop).toHaveBeenCalledTimes(1);
    expect(writePty).not.toHaveBeenCalled();

    dictationEvent?.({ requestId: 'dictation-delivery-1', type: 'result', text: 'late final transcript' });
    await flushPromises();
    expect(drafts.draftFor(targetKey)).toBe('typed prefix visible transcript');
    expect(writePty).not.toHaveBeenCalled();
    dictationEvent?.({ requestId: 'dictation-delivery-1', type: 'stopped' });
    await pending;

    expect(writePty).toHaveBeenCalled();
    expect(new Set(writes.map((write) => write.operationId)).size).toBe(1);
    expect(writes.map((write) => write.writeIndex)).toEqual(writes.map((_write, index) => index + 1));
    const delivered = writes.map(({ bytes }) => new TextDecoder().decode(bytes)).join('');
    expect(delivered).toContain('typed prefix visible transcript');
    expect(delivered).not.toContain('late final transcript');
    expect(delivered.endsWith(String.fromCharCode(13))).toBe(intent === 'submit');
    expect(drafts.draftFor(targetKey)).toBe('');
    app.unmount();
  });
});
