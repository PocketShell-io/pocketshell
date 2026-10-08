import { createRenderer, h, nextTick, ref } from 'vue';
import { createPinia } from 'pinia';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useComposerDrafts } from '../../src/stores/composerDrafts';
import PromptComposer from '../../src/components/PromptComposer.vue';

/**
 * Issue #3060: dictated text must survive screen-off, closing the composer
 * (scrim tap, back, swipe), unmount and target changes. Only an explicit
 * Cancel/Discard may restore or clear the draft. Stop/Send waits for the
 * recognizer's final result and never drops the tail of the last partial.
 */
const mocks = vi.hoisted(() => ({
  appStateListener: undefined as ((state: { isActive: boolean }) => void) | undefined,
  addListener: vi.fn(async (_event: string, _listener: (state: { isActive: boolean }) => void) => ({ remove: vi.fn(async () => {}) })),
  startDictation: vi.fn(),
  cancelDictation: vi.fn(async (_requestId?: string) => {}),
}));

vi.mock('@capacitor/app', () => ({ App: { addListener: mocks.addListener } }));
vi.mock('../../src/session/platformInput', () => ({
  platformInput: {
    startRecognition: vi.fn(),
    stopRecognition: vi.fn(),
    cancelRecognition: vi.fn(),
  },
}));
vi.mock('../../src/session/dictationController', async () => {
  const { DictationController } = await import('@pocketshell/core');
  let requestSequence = 0;
  return {
    createSharedDictationController: (options: object) => {
      const nativeRequestIds = new Map<string, string>();
      const sessions = new Map<string, { stop?: () => Promise<void>; cancel?: () => Promise<void> }>();
      let controller: InstanceType<typeof DictationController>;
      controller = new DictationController({
        createRequestId: () => `composer-test-${++requestSequence}`,
        startRecognition: async (requestId: string) => {
          let nativeRequestId = requestId;
          const session = await mocks.startDictation(
            (event: { type: string; text?: string; code?: string }) => {
              if (event.type === 'partial') controller.onPartial(requestId, event.text ?? '');
              else if (event.type === 'result') controller.onRecognizedSegment(requestId, event.text ?? '');
              else if (event.type === 'error') controller.onError(requestId, {
                code: event.code ?? 'recognizer-error', message: event.code ?? 'Speech recognition failed.',
              });
              else if (event.type === 'recoverable') controller.onRecoverableEnd(requestId, 'no-match');
            },
            options,
            (id: string) => {
              nativeRequestId = id;
              nativeRequestIds.set(requestId, id);
            },
          );
          nativeRequestIds.set(requestId, nativeRequestId);
          sessions.set(requestId, session ?? {});
          if (controller.getSnapshot().phase === 'cancelled') await session?.cancel?.();
        },
        stopRecognition: async (requestId: string) => { await sessions.get(requestId)?.stop?.(); },
        cancelRecognition: async (requestId: string) => {
          await mocks.cancelDictation(nativeRequestIds.get(requestId) ?? requestId);
        },
        schedule: (callback: () => void) => {
          queueMicrotask(callback);
          return undefined;
        },
        cancelScheduled: () => {},
      });
      return controller;
    },
  };
});

interface HostNode {
  type: string;
  props: Record<string, unknown>;
  children: HostNode[];
  parent?: HostNode;
  text?: string;
  focus?: () => void;
  focusCalls: number;
  blur?: () => void;
  style: { display: string };
  value?: string;
  setSelectionRange?: (start: number, end: number) => void;
}

function node(type: string, text = ''): HostNode {
  const host: HostNode = {
    type, props: {}, children: [], text, focusCalls: 0, style: { display: '' }, value: '',
    focus: () => { host.focusCalls += 1; },
    blur: () => {},
    setSelectionRange: () => {},
  };
  return host;
}

let composerTeleportTarget: HostNode | null = null;
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
  querySelector: (selector) => selector === '#prompt-composer-portal' ? composerTeleportTarget : null,
});

function findByTestId(root: HostNode, testId: string): HostNode | undefined {
  if (root.props['data-testid'] === testId) return root;
  for (const child of root.children) {
    const match = findByTestId(child, testId);
    if (match) return match;
  }
  return undefined;
}

function findAll(root: HostNode, predicate: (candidate: HostNode) => boolean): HostNode[] {
  const matches = predicate(root) ? [root] : [];
  for (const child of root.children) matches.push(...findAll(child, predicate));
  return matches;
}

function textContent(root: HostNode): string {
  return `${root.text ?? ''}${root.children.map(textContent).join('')}`.trim();
}

function composerState(root: HostNode): unknown {
  return findByTestId(root, 'prompt-composer')?.props['data-dictation-state'];
}




async function flushPromises() {
  for (let index = 0; index < 8; index += 1) await Promise.resolve();
}

type DictationEventSink = (event: { requestId: string; type: string; text?: string; code?: string }) => void;

function captureAppState() {
  mocks.addListener.mockImplementation(async (_event: string, listener: (state: { isActive: boolean }) => void) => {
    mocks.appStateListener = listener;
    return { remove: vi.fn(async () => {}) };
  });
}

/** Each native start gets a fresh request id; `emit` targets the latest turn. */
function nativeRecognizer(prefix: string) {
  let sink: DictationEventSink | undefined;
  let current = '';
  let turn = 0;
  const stop = vi.fn(async () => {});
  mocks.startDictation.mockImplementation(async (
    onEvent: DictationEventSink,
    _options: object,
    onRequestId: (id: string) => void,
  ) => {
    sink = onEvent;
    current = `${prefix}-${++turn}`;
    onRequestId(current);
    return { requestId: current, stop, cancel: vi.fn(async () => {}) };
  });
  return {
    stop,
    emit(type: string, text?: string, code?: string) {
      sink?.({ requestId: current, type, ...(text === undefined ? {} : { text }), ...(code ? { code } : {}) });
    },
    get requestId() { return current; },
  };
}

function decode(writes: Uint8Array[]): string {
  return writes.map((bytes) => new TextDecoder().decode(bytes)).join('');
}

describe('composer dictation keeps dictated text', () => {
  afterEach(() => {
    vi.clearAllMocks();
    mocks.appStateListener = undefined;
    composerTeleportTarget = null;
  });

  it('keeps finals and the latest partial when the screen turns off mid-dictation', async () => {
    captureAppState();
    const recognizer = nativeRecognizer('screen-off');
    const targetKey = 'host/screen-off-session';
    const writePty = vi.fn(async () => ({ ok: true }));
    const pinia = createPinia();
    const root = node('root');
    const app = renderer.createApp(PromptComposer, { targetKey, transportState: 'connected', writePty });
    app.use(pinia);
    app.mount(root);
    await flushPromises();
    const drafts = useComposerDrafts(pinia);
    drafts.setDraft(targetKey, 'typed first');

    await (findByTestId(root, 'composer-dictate')?.props.onClick as () => Promise<void>)();
    await vi.waitFor(() => expect(composerState(root)).toBe('recording'));
    recognizer.emit('result', 'finalized sentence');
    await vi.waitFor(() => expect(mocks.startDictation).toHaveBeenCalledTimes(2));
    recognizer.emit('partial', 'words still being spoken');
    await flushPromises();

    mocks.appStateListener?.({ isActive: false });
    await flushPromises();

    expect(drafts.draftFor(targetKey)).toBe('typed first finalized sentence words still being spoken');
    expect(composerState(root)).toBe('review');
    expect(mocks.cancelDictation).toHaveBeenCalledWith(recognizer.requestId);
    recognizer.emit('result', 'late callback');
    await flushPromises();
    mocks.appStateListener?.({ isActive: true });
    await flushPromises();
    expect(drafts.draftFor(targetKey)).toBe('typed first finalized sentence words still being spoken');
    expect(findByTestId(root, 'prompt-draft')?.props.value)
      .toBe('typed first finalized sentence words still being spoken');
    expect(writePty).not.toHaveBeenCalled();
    app.unmount();
  });

  it('keeps the dictated text when the sheet scrim is tapped, and shows it again on reopen', async () => {
    const recognizer = nativeRecognizer('scrim');
    const targetKey = 'host/scrim-session';
    const open = ref(true);
    const writePty = vi.fn(async () => ({ ok: true }));
    const pinia = createPinia();
    const root = node('root');
    const portal = node('portal');
    composerTeleportTarget = portal;
    const app = renderer.createApp({
      setup: () => () => h(PromptComposer, {
        targetKey,
        transportState: 'connected',
        writePty,
        mobileSheet: true,
        open: open.value,
        onOpenChange: (next: boolean) => { open.value = next; },
      }),
    });
    app.use(pinia);
    app.mount(root);
    await flushPromises();
    const drafts = useComposerDrafts(pinia);
    drafts.setDraft(targetKey, 'typed');
    await nextTick();

    (findByTestId(portal, 'composer-dictate')?.props.onClick as () => void)();
    await vi.waitFor(() => expect(composerState(portal)).toBe('recording'));
    recognizer.emit('partial', 'spoken before the tap');
    await flushPromises();

    const scrim = findByTestId(portal, 'prompt-composer-scrim')!;
    (scrim.props.onClick as (event: unknown) => void)({ target: scrim, currentTarget: scrim });
    await flushPromises();

    expect(open.value).toBe(false);
    expect(findByTestId(portal, 'prompt-composer')).toBeUndefined();
    expect(drafts.draftFor(targetKey)).toBe('typed spoken before the tap');

    open.value = true;
    await flushPromises();
    expect(findByTestId(portal, 'prompt-draft')?.props.value).toBe('typed spoken before the tap');

    // Tapping the scrim with a non-empty draft and no dictation never discards it.
    const reopenedScrim = findByTestId(portal, 'prompt-composer-scrim')!;
    (reopenedScrim.props.onClick as (event: unknown) => void)({ target: reopenedScrim, currentTarget: reopenedScrim });
    await flushPromises();
    expect(open.value).toBe(false);
    expect(drafts.draftFor(targetKey)).toBe('typed spoken before the tap');
    expect(writePty).not.toHaveBeenCalled();
    app.unmount();
  });

  it.each(['sheet', 'inline'] as const)('keeps the dictated text when the mobile %s composer is dismissed (back/swipe)', async (mode) => {
    const recognizer = nativeRecognizer(`dismiss-${mode}`);
    const targetKey = `host/dismiss-keep-${mode}`;
    const shown = ref(true);
    const pinia = createPinia();
    const root = node('root');
    const portal = node('portal');
    composerTeleportTarget = portal;
    const app = renderer.createApp({
      setup: () => () => h(PromptComposer, {
        targetKey,
        transportState: 'connected',
        writePty: vi.fn(async () => ({ ok: true })),
        mobileSheet: mode === 'sheet' || !shown.value,
        mobileInline: mode === 'inline' && shown.value,
        open: mode === 'sheet' && shown.value,
      }),
    });
    app.use(pinia);
    app.mount(root);
    await flushPromises();
    const drafts = useComposerDrafts(pinia);
    drafts.setDraft(targetKey, 'keep this draft');
    await nextTick();
    const visibleRoot = mode === 'sheet' ? portal : root;
    (findByTestId(visibleRoot, 'composer-dictate')?.props.onClick as () => void)();
    await vi.waitFor(() => expect(composerState(visibleRoot)).toBe('recording'));
    recognizer.emit('partial', 'unsubmitted words');
    await flushPromises();

    shown.value = false;
    await flushPromises();

    expect(drafts.draftFor(targetKey)).toBe('keep this draft unsubmitted words');
    recognizer.emit('result', 'late transcript');
    await flushPromises();
    expect(drafts.draftFor(targetKey)).toBe('keep this draft unsubmitted words');
    app.unmount();
  });

  it('keeps the dictated text when the composer unmounts mid-dictation', async () => {
    const recognizer = nativeRecognizer('unmount');
    const targetKey = 'host/unmount-session';
    const pinia = createPinia();
    const root = node('root');
    const app = renderer.createApp(PromptComposer, {
      targetKey, transportState: 'connected', writePty: vi.fn(async () => ({ ok: true })),
    });
    app.use(pinia);
    app.mount(root);
    await flushPromises();
    const drafts = useComposerDrafts(pinia);
    await (findByTestId(root, 'composer-dictate')?.props.onClick as () => Promise<void>)();
    await vi.waitFor(() => expect(composerState(root)).toBe('recording'));
    recognizer.emit('partial', 'words before unmount');
    await flushPromises();

    app.unmount();

    expect(drafts.draftFor(targetKey)).toBe('words before unmount');
  });

  it('keeps the dictated text under the original target when the target changes', async () => {
    const recognizer = nativeRecognizer('retarget');
    const targetKey = ref('host/session-a');
    const pinia = createPinia();
    const root = node('root');
    const app = renderer.createApp({
      setup: () => () => h(PromptComposer, {
        targetKey: targetKey.value, transportState: 'connected', writePty: vi.fn(async () => ({ ok: true })),
      }),
    });
    app.use(pinia);
    app.mount(root);
    await flushPromises();
    const drafts = useComposerDrafts(pinia);
    drafts.setDraft('host/session-a', 'draft a');
    await (findByTestId(root, 'composer-dictate')?.props.onClick as () => Promise<void>)();
    await vi.waitFor(() => expect(composerState(root)).toBe('recording'));
    recognizer.emit('partial', 'meant for a');
    await flushPromises();

    targetKey.value = 'host/session-b';
    await flushPromises();

    expect(drafts.draftFor('host/session-a')).toBe('draft a meant for a');
    expect(drafts.draftFor('host/session-b')).toBe('');
    app.unmount();
  });

  it('still restores the original draft on an explicit Discard', async () => {
    const recognizer = nativeRecognizer('discard');
    const targetKey = 'host/discard-session';
    const pinia = createPinia();
    const root = node('root');
    const app = renderer.createApp(PromptComposer, {
      targetKey, transportState: 'connected', writePty: vi.fn(async () => ({ ok: true })),
    });
    app.use(pinia);
    app.mount(root);
    await flushPromises();
    const drafts = useComposerDrafts(pinia);
    drafts.setDraft(targetKey, 'original');
    await (findByTestId(root, 'composer-dictate')?.props.onClick as () => Promise<void>)();
    await vi.waitFor(() => expect(composerState(root)).toBe('recording'));
    recognizer.emit('partial', 'throw away');
    await flushPromises();

    (findByTestId(root, 'composer-recording-cancel')?.props.onClick as () => void)();
    await flushPromises();

    expect(composerState(root)).toBe('idle');
    expect(drafts.draftFor(targetKey)).toBe('original');
    app.unmount();
  });

  it.each([
    { name: 'a shorter final', finish: (r: ReturnType<typeof nativeRecognizer>) => r.emit('result', "so let's") },
    { name: 'no final', finish: (r: ReturnType<typeof nativeRecognizer>) => r.emit('recoverable') },
    { name: 'a stop timeout', finish: (r: ReturnType<typeof nativeRecognizer>) => r.emit('error', undefined, 'recognizer-stop-timeout') },
  ])('Send waits for the final result and delivers the whole utterance after $name', async ({ finish }) => {
    const recognizer = nativeRecognizer('send-tail');
    const targetKey = 'host/send-tail-session';
    const writes: Uint8Array[] = [];
    const writePty = vi.fn(async (bytes: Uint8Array) => { writes.push(bytes); return { ok: true }; });
    const pinia = createPinia();
    const root = node('root');
    const app = renderer.createApp(PromptComposer, { targetKey, transportState: 'connected', writePty });
    app.use(pinia);
    app.mount(root);
    await flushPromises();
    const drafts = useComposerDrafts(pinia);
    await (findByTestId(root, 'composer-dictate')?.props.onClick as () => Promise<void>)();
    await vi.waitFor(() => expect(composerState(root)).toBe('recording'));
    recognizer.emit('partial', "so let's solve it");
    await flushPromises();

    const sending = (findByTestId(root, 'composer-dictation-send')?.props.onClick as () => Promise<void>)();
    await flushPromises();
    expect(recognizer.stop).toHaveBeenCalledTimes(1);
    expect(composerState(root)).toBe('transcribing');
    expect(writePty).not.toHaveBeenCalled();

    finish(recognizer);
    await sending;
    await vi.waitFor(() => expect(drafts.draftFor(targetKey)).toBe(''));
    const delivered = decode(writes);
    expect(delivered).toContain("so let's solve it");
    expect(delivered.endsWith(String.fromCharCode(13))).toBe(true);
    app.unmount();
  });
});
