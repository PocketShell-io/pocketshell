import { createRenderer } from 'vue';
import { createPinia } from 'pinia';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useComposerDrafts } from '../../src/stores/composerDrafts';
import { useAppSettings } from '../../src/stores/appSettings';
import PromptComposer from '../../src/components/PromptComposer.vue';
import composerRecordingModeSource from '../../src/components/ComposerRecordingMode.vue?raw';
import promptComposerSource from '../../src/components/PromptComposer.vue?raw';

const mocks = vi.hoisted(() => ({
  appStateListener: undefined as ((state: { isActive: boolean }) => void) | undefined,
  addListener: vi.fn(),
  startDictation: vi.fn(),
  cancelDictation: vi.fn(async () => {}),
}));

vi.mock('@capacitor/app', () => ({ App: { addListener: mocks.addListener } }));
vi.mock('../../src/session/platformInput', () => ({
  platformInput: { startDictation: mocks.startDictation, cancelDictation: mocks.cancelDictation },
}));

interface HostNode {
  type: string;
  props: Record<string, unknown>;
  children: HostNode[];
  parent?: HostNode;
  text?: string;
  focus?: () => void;
}

function node(type: string, text = ''): HostNode {
  return { type, props: {}, children: [], text, focus: () => {} };
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

function styleSource(componentSource: string, label: string): string {
  const match = componentSource.match(/<style scoped>([\s\S]*?)<\/style>/);
  if (!match) throw new Error(`${label} production scoped styles are missing`);
  return match[1];
}

function cssRule(styles: string, selector: string): string {
  const start = styles.indexOf(`${selector} {`);
  if (start < 0) throw new Error(`Production styles are missing ${selector}`);
  const end = styles.indexOf('}', start);
  return styles.slice(start, end + 1);
}

function isDescendantOf(candidate: HostNode, ancestor: HostNode): boolean {
  let current = candidate.parent;
  while (current) {
    if (current === ancestor) return true;
    current = current.parent;
  }
  return false;
}

async function flushPromises() {
  await Promise.resolve();
  await Promise.resolve();
}

describe('composer dictation cancellation', () => {
  afterEach(() => {
    vi.clearAllMocks();
    mocks.appStateListener = undefined;
    composerTeleportTarget = null;
  });

  it('labels the recording Discard action and restores the original draft', async () => {
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
    const app = renderer.createApp(PromptComposer, {
      targetKey,
      transportState: 'connected',
      writePty,
    });
    app.use(pinia);
    app.mount(root);
    await flushPromises();

    const drafts = useComposerDrafts(pinia);
    drafts.setDraft(targetKey, 'original typed draft');
    const dictate = findByTestId(root, 'composer-dictate');
    await (dictate?.props.onClick as () => Promise<void>)();
    dictationEvent?.({ requestId: 'dictation-cancel-label-1', type: 'partial', text: 'visible partial' });
    await flushPromises();

    const cancel = findByTestId(root, 'composer-recording-cancel');
    expect(textContent(cancel!)).toBe('Discard');
    expect(cancel?.props['aria-label']).toBe('Discard recording without transcribing');
    expect(drafts.draftFor(targetKey)).toBe('original typed draft visible partial');
    expect(writePty).not.toHaveBeenCalled();

    (cancel?.props.onClick as () => void)();
    await flushPromises();
    expect(composerState(root)).toBe('idle');
    expect(drafts.draftFor(targetKey)).toBe('original typed draft');
    expect(findByTestId(root, 'composer-recording-preview')).toBeUndefined();
    expect(mocks.cancelDictation).toHaveBeenCalledWith('dictation-cancel-label-1');
    expect(stop).not.toHaveBeenCalled();
    expect(writePty).not.toHaveBeenCalled();

    dictationEvent?.({ requestId: 'dictation-cancel-label-1', type: 'partial', text: 'late text' });
    await flushPromises();
    expect(drafts.draftFor(targetKey)).toBe('original typed draft');
    app.unmount();
  });

  it('keeps Cancel distinct from Discard while transcription is finishing', async () => {
    let dictationEvent: ((event: { requestId: string; type: string; text?: string }) => void) | undefined;
    const stop = vi.fn(async () => {});
    mocks.startDictation.mockImplementation(async (
      onEvent: typeof dictationEvent,
      _options: object,
      onRequestId: (id: string) => void,
    ) => {
      dictationEvent = onEvent;
      onRequestId('dictation-transcribing-cancel-1');
      dictationEvent?.({ requestId: 'dictation-transcribing-cancel-1', type: 'started' });
      return { requestId: 'dictation-transcribing-cancel-1', stop, cancel: vi.fn(async () => {}) };
    });

    const targetKey = 'host/transcribing-cancel-session';
    const writePty = vi.fn(async () => ({ ok: true }));
    const pinia = createPinia();
    const root = node('root');
    const app = renderer.createApp(PromptComposer, {
      targetKey,
      transportState: 'connected',
      writePty,
    });
    app.use(pinia);
    app.mount(root);
    await flushPromises();

    const drafts = useComposerDrafts(pinia);
    drafts.setDraft(targetKey, 'original draft');
    await (findByTestId(root, 'composer-dictate')?.props.onClick as () => Promise<void>)();
    dictationEvent?.({ requestId: 'dictation-transcribing-cancel-1', type: 'partial', text: 'partial words' });
    await flushPromises();
    await (findByTestId(root, 'composer-recording-stop')?.props.onClick as () => Promise<void>)();

    const cancel = findByTestId(root, 'composer-recording-cancel');
    expect(composerState(root)).toBe('transcribing');
    expect(textContent(cancel!)).toBe('Cancel');
    expect(cancel?.props['aria-label']).toBe('Cancel dictation and restore the original draft');
    (cancel?.props.onClick as () => void)();
    await flushPromises();

    expect(composerState(root)).toBe('idle');
    expect(drafts.draftFor(targetKey)).toBe('original draft');
    expect(writePty).not.toHaveBeenCalled();
    app.unmount();
  });

  it('keeps recording and transcribing preview regions present before speech arrives', async () => {
    let dictationEvent: ((event: { requestId: string; type: string; text?: string }) => void) | undefined;
    mocks.startDictation.mockImplementation(async (
      onEvent: typeof dictationEvent,
      _options: object,
      onRequestId: (id: string) => void,
    ) => {
      dictationEvent = onEvent;
      onRequestId('dictation-empty-preview-1');
      dictationEvent?.({ requestId: 'dictation-empty-preview-1', type: 'started' });
      return {
        requestId: 'dictation-empty-preview-1',
        stop: vi.fn(async () => {}),
        cancel: vi.fn(async () => {}),
      };
    });

    const root = node('root');
    const app = renderer.createApp(PromptComposer, {
      targetKey: 'host/empty-preview-session',
      transportState: 'connected',
      writePty: vi.fn(async () => ({ ok: true })),
    });
    app.use(createPinia());
    app.mount(root);
    await flushPromises();

    await (findByTestId(root, 'composer-dictate')?.props.onClick as () => Promise<void>)();

    const draft = findByTestId(root, 'prompt-draft');
    const describedBy = String(draft?.props['aria-describedby'] ?? '').split(/\s+/);
    const preview = findAll(root, (candidate) => candidate.props.id === 'composer-recording-preview')[0];
    expect(composerState(root)).toBe('recording');
    expect(describedBy).toContain('composer-recording-preview');
    expect(preview).toBeDefined();
    expect(preview?.props['aria-live']).toBe('polite');
    expect(textContent(preview!)).toBe('Listening for speech…');

    await (findByTestId(root, 'composer-recording-stop')?.props.onClick as () => Promise<void>)();
    await flushPromises();
    const transcribingPreview = findByTestId(root, 'composer-recording-preview');
    expect(composerState(root)).toBe('transcribing');
    expect(transcribingPreview?.props.id).toBe('composer-recording-preview');
    expect(transcribingPreview?.props['aria-live']).toBe('polite');
    expect(textContent(transcribingPreview!)).toBe('Waiting for transcript…');

    app.unmount();
  });

  it('places the mobile Dictate prompt microphone in the composer action row', async () => {
    const targetKey = 'host/mobile-composer-actions';
    const writePty = vi.fn(async () => ({ ok: true }));
    const pinia = createPinia();
    const root = node('root');
    const portal = node('portal');
    composerTeleportTarget = portal;
    const app = renderer.createApp(PromptComposer, {
      targetKey,
      transportState: 'connected',
      writePty,
      mobileSheet: true,
      open: true,
    });
    app.use(pinia);
    app.mount(root);
    await flushPromises();

    const composer = findByTestId(portal, 'prompt-composer');
    const draftRow = findByTestId(portal, 'prompt-draft')?.parent;
    const actions = findByTestId(portal, 'composer-actions');
    const mic = findByTestId(portal, 'composer-dictate');
    const send = findAll(portal, (candidate) => candidate.type === 'button'
      && candidate.props.title === 'Send (Enter)')[0];

    expect(composer?.props).toMatchObject({ role: 'dialog', 'aria-modal': 'true' });
    expect(draftRow).toBeDefined();
    expect(findByTestId(draftRow!, 'composer-dictate')).toBeUndefined();
    expect(mic?.parent).toBe(actions);
    expect(mic?.props).toMatchObject({
      class: 'composer-dictate composer-dictate--mic',
      'aria-label': 'Dictate prompt',
      disabled: false,
    });
    expect(findByTestId(portal, 'composer-insert')?.parent).toBe(actions);
    expect(send).toBeDefined();
    expect(isDescendantOf(send!, actions!)).toBe(true);
    expect(findAll(actions!, (candidate) => candidate.type === 'button'
      && (typeof candidate.props['data-testid'] === 'string' || candidate.props.title === 'Send (Enter)'))
      .map((candidate) => candidate.props['data-testid'] ?? candidate.props.title))
      .toEqual(['composer-discard', 'composer-insert', 'Send (Enter)', 'composer-dictate']);

    app.unmount();
    composerTeleportTarget = null;
  });

  it('pins the production composer mic and shared dictation action styles to the Kotlin hierarchy', () => {
    const composerStyles = styleSource(promptComposerSource, 'PromptComposer');
    const mic = cssRule(composerStyles, '.composer-dictate--mic');
    expect(mic).toContain('width: 48px');
    expect(mic).toContain('height: 48px');
    expect(mic).toContain('flex: 0 0 48px');
    expect(mic).toContain('border: 1px solid var(--border-strong)');
    expect(mic).toContain('border-radius: 50%');
    expect(mic).toContain('background: var(--surface-2)');

    const send = cssRule(composerStyles, '.composer-recording-action--send');
    expect(send).toContain('border-color: var(--accent-dim)');
    expect(send).toContain('background: var(--surface-2)');
    expect(send).toContain('color: var(--accent)');

    const stop = cssRule(composerStyles, '.composer-recording-action--stop');
    expect(stop).toContain('width: 48px');
    expect(stop).toContain('height: 48px');
    expect(stop).toContain('flex: 0 0 48px');
    expect(stop).toContain('border-radius: 50%');
    expect(stop).toContain('background: var(--accent)');
    expect(stop).toContain('color: var(--on-accent)');

    const recordingStyles = styleSource(composerRecordingModeSource, 'ComposerRecordingMode');
    expect(cssRule(recordingStyles, '.recording-mode__live-row')).toContain('display: flex');
    expect(cssRule(recordingStyles, '.recording-mode__waveform')).toContain('flex: 1 1 auto');
    expect(composerRecordingModeSource).toContain('v-for="bar in 30"');
    expect(composerRecordingModeSource).not.toContain('data-testid="composer-recording-actions"');
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
    const app = renderer.createApp(PromptComposer, {
      targetKey: 'host/session',
      transportState: 'connected',
      writePty: vi.fn(async () => ({ ok: true })),
    });
    app.use(pinia);
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
    expect(composerState(root)).toBe('starting');
    expect(findByTestId(root, 'composer-recording-preview')).toBeUndefined();
    mocks.appStateListener?.({ isActive: false });
    await flushPromises();
    expect(stop).not.toHaveBeenCalled();
    expect(mocks.cancelDictation).toHaveBeenCalledWith('dictation-1');
    expect(drafts.draftFor('host/session')).toBe('keep this typed draft');
    expect(findByTestId(root, 'composer-recording-preview')).toBeUndefined();
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
    const app = renderer.createApp(PromptComposer, {
      targetKey: 'host/settings-session',
      transportState: 'connected',
      writePty: vi.fn(async () => ({ ok: true })),
    });
    app.use(pinia);
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
    const app = renderer.createApp(PromptComposer, {
      targetKey: 'host/pause-session',
      transportState: 'connected',
      writePty,
    });
    app.use(pinia);
    app.mount(root);
    await flushPromises();
    const drafts = useComposerDrafts(pinia);
    drafts.setDraft('host/pause-session', 'keep typed');

    const dictate = findByTestId(root, 'composer-dictate');
    const onClick = dictate?.props.onClick;
    expect(onClick).toBeTypeOf('function');
    await (onClick as () => Promise<void>)();
    expect(composerState(root)).toBe('recording');

    dictationEvent?.({ requestId: 'dictation-pause-1', type: 'partial', text: 'recognized phrase' });
    await flushPromises();
    dictationEvent?.({ requestId: 'dictation-pause-1', type: 'processing' });
    await flushPromises();
    expect(composerState(root)).toBe('recording');
    expect(findByTestId(root, 'composer-recording-stop')).toBeDefined();

    dictationEvent?.({ requestId: 'dictation-pause-1', type: 'result', text: 'recognized phrase' });
    dictationEvent?.({ requestId: 'dictation-pause-1', type: 'ready' });
    dictationEvent?.({ requestId: 'dictation-pause-1', type: 'listening' });
    await flushPromises();
    expect(composerState(root)).toBe('recording');
    expect(findByTestId(root, 'composer-recording-stop')).toBeDefined();
    expect(textContent(findByTestId(root, 'composer-recording-preview')!)).toBe('recognized phrase');
    expect(drafts.draftFor('host/pause-session')).toBe('keep typed recognized phrase');
    expect(writePty).not.toHaveBeenCalled();

    const stopButton = findByTestId(root, 'composer-recording-stop');
    await (stopButton?.props.onClick as () => Promise<void>)();
    expect(stop).toHaveBeenCalledTimes(1);
    expect(composerState(root)).toBe('transcribing');
    expect(findByTestId(root, 'composer-recording-stop')).toBeUndefined();
    expect(writePty).not.toHaveBeenCalled();
    dictationEvent?.({ requestId: 'dictation-pause-1', type: 'stopped' });
    await flushPromises();
    expect(composerState(root)).toBe('review');
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
    const app = renderer.createApp(PromptComposer, {
      targetKey,
      transportState: 'connected',
      writePty,
    });
    app.use(pinia);
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
    expect(findAll(actions!, (child) => child.type === 'button')
      .map((child) => child.props['data-testid']))
      .toEqual(['composer-recording-cancel', 'composer-insert', 'composer-dictation-send', 'composer-recording-stop']);
    expect(isDescendantOf(actions!, findByTestId(root, 'composer-actions')!)).toBe(true);
    expect(isDescendantOf(actions!, findByTestId(root, 'composer-recording-mode')!)).toBe(false);
    const recordingSend = findByTestId(root, 'composer-dictation-send');
    const stopButton = findByTestId(root, 'composer-recording-stop');
    expect(recordingSend?.props.class)
      .toBe('composer-recording-action composer-recording-action--send');
    expect(stopButton?.props.class)
      .toBe('composer-recording-action composer-recording-action--stop');
    expect(stopButton?.props['aria-label'])
      .toBe('Stop dictation and keep the recognized text in the editable draft');
    expect(stopButton?.children[0]?.type).toBe('svg');
    expect(textContent(findByTestId(root, 'composer-recording-cancel')!)).toBe('Discard');
    expect(findByTestId(root, 'composer-recording-cancel')?.props['aria-label'])
      .toBe('Discard recording without transcribing');

    if (phase === 'transcribing') {
      const stopButton = findByTestId(root, 'composer-recording-stop');
      expect(stopButton).toBeDefined();
      await (stopButton?.props.onClick as () => Promise<void>)();
      expect(composerState(root)).toBe('transcribing');
      expect(findAll(findByTestId(root, 'composer-recording-actions')!, (child) => child.type === 'button')
        .map((child) => child.props['data-testid']))
        .toEqual(['composer-recording-cancel', 'composer-dictation-send']);
      expect(textContent(findByTestId(root, 'composer-recording-cancel')!)).toBe('Cancel');
      expect(findByTestId(root, 'composer-recording-cancel')?.props['aria-label'])
        .toBe('Cancel dictation and restore the original draft');
      expect(findByTestId(root, 'composer-insert')).toBeUndefined();
      expect(writePty).not.toHaveBeenCalled();
    }

    const action = findByTestId(root, intent === 'insert' ? 'composer-insert' : 'composer-dictation-send');
    expect(action).toBeDefined();
    expect(action?.props.disabled).toBe(false);
    (action?.props.onClick as () => void)();
    await flushPromises();
    expect(composerState(root)).toBe('transcribing');
    expect(stop).toHaveBeenCalledTimes(1);
    expect(writePty).not.toHaveBeenCalled();

    dictationEvent?.({ requestId: 'dictation-delivery-1', type: 'result', text: 'late final transcript' });
    await flushPromises();
    expect(drafts.draftFor(targetKey)).toBe('typed prefix visible transcript');
    expect(writePty).not.toHaveBeenCalled();
    dictationEvent?.({ requestId: 'dictation-delivery-1', type: 'stopped' });
    await vi.waitFor(() => expect(drafts.draftFor(targetKey)).toBe(''));

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
