import { createRenderer, h } from 'vue';
import { createPinia } from 'pinia';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useComposerDrafts } from '../../src/stores/composerDrafts';
import PromptComposer from '../../src/components/PromptComposer.vue';
import composerRecordingModeSource from '../../src/components/ComposerRecordingMode.vue?raw';

/**
 * Issue #3062: the maintainer dictated for two minutes while nothing was
 * recognized and nothing said so. While the composer listens and no partial or
 * final arrives for N seconds, it must show a visible, non-blocking warning
 * near the waveform (role=status, pulsing, static under reduced motion) that
 * clears when text arrives and never touches the draft. Stop/Send with nothing
 * captured says "No speech was recognized" instead of sending.
 *
 * This drives the REAL shared dictation adapter and core controller; only the
 * Android recognizer port is faked.
 */
const recognizer = vi.hoisted(() => {
  type Sink = (event: { requestId: string; type: string; text?: string; code?: string }) => void;
  const state = {
    sinks: new Map<string, Sink>(),
    latest: '',
    starts: 0,
    emit(type: string, text?: string, code?: string) {
      state.sinks.get(state.latest)?.({
        requestId: state.latest, type, ...(text === undefined ? {} : { text }), ...(code ? { code } : {}),
      });
    },
    reset() {
      state.sinks.clear();
      state.latest = '';
      state.starts = 0;
    },
  };
  return state;
});

vi.mock('@capacitor/app', () => ({ App: { addListener: vi.fn(async () => ({ remove: vi.fn(async () => {}) })) } }));
vi.mock('../../src/session/platformInput', () => ({
  platformInput: {
    startRecognition: vi.fn(async (requestId: string, onEvent: (event: never) => void) => {
      recognizer.sinks.set(requestId, onEvent as never);
      recognizer.latest = requestId;
      recognizer.starts += 1;
    }),
    // Android answers Stop with the turn's final outcome; tests emit it explicitly.
    stopRecognition: vi.fn(async () => {}),
    cancelRecognition: vi.fn(async () => {}),
  },
}));

interface HostNode {
  type: string;
  props: Record<string, unknown>;
  children: HostNode[];
  parent?: HostNode;
  text?: string;
  focus?: () => void;
  blur?: () => void;
  style: { display: string };
  value?: string;
  setSelectionRange?: (start: number, end: number) => void;
}

function node(type: string, text = ''): HostNode {
  return {
    type, props: {}, children: [], text, style: { display: '' }, value: '',
    focus: () => {}, blur: () => {}, setSelectionRange: () => {},
  };
}

let portalTarget: HostNode | null = null;
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
  querySelector: (selector) => selector === '#prompt-composer-portal' ? portalTarget : null,
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

function classList(element: HostNode | undefined): string {
  const value = element?.props.class;
  if (typeof value === 'string') return value;
  if (Array.isArray(value)) return value.join(' ');
  if (value && typeof value === 'object') {
    return Object.entries(value as Record<string, boolean>).filter(([, on]) => on).map(([name]) => name).join(' ');
  }
  return '';
}

async function flush() {
  for (let index = 0; index < 10; index += 1) await Promise.resolve();
}

function stubReducedMotion(reduce: boolean) {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: reduce && query.includes('prefers-reduced-motion: reduce'),
    media: query,
    addEventListener: () => {},
    removeEventListener: () => {},
  }));
}

async function mountComposer(options: { mobileSheet?: boolean; baseDraft?: string } = {}) {
  const targetKey = 'host/no-speech-session';
  const writePty = vi.fn(async () => ({ ok: true }));
  const pinia = createPinia();
  const root = node('root');
  const portal = node('portal');
  portalTarget = portal;
  const app = renderer.createApp({
    setup: () => () => h(PromptComposer, {
      targetKey,
      transportState: 'connected',
      writePty,
      ...(options.mobileSheet ? { mobileSheet: true, open: true } : {}),
    }),
  });
  app.use(pinia);
  app.mount(root);
  await flush();
  const drafts = useComposerDrafts(pinia);
  if (options.baseDraft !== undefined) drafts.setDraft(targetKey, options.baseDraft);
  await flush();
  const surface = options.mobileSheet ? portal : root;
  return { app, surface, drafts, targetKey, writePty };
}

async function startDictating(surface: HostNode) {
  (findByTestId(surface, 'composer-dictate')?.props.onClick as () => void)();
  await flush();
  expect(findByTestId(surface, 'prompt-composer')?.props['data-dictation-state']).toBe('recording');
}

describe('composer warns when listening hears no words (#3062)', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    recognizer.reset();
    stubReducedMotion(false);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.clearAllMocks();
    portalTarget = null;
  });

  it('shows a pulsing role=status warning after 8 s with no text, clears it on a partial, and never changes the draft', async () => {
    const { app, surface, drafts, targetKey, writePty } = await mountComposer({ mobileSheet: true, baseDraft: 'typed before' });
    await startDictating(surface);

    vi.advanceTimersByTime(7_900);
    await flush();
    expect(findByTestId(surface, 'composer-no-speech-warning')).toBeUndefined();
    expect(findByTestId(surface, 'composer-recording-mode')?.props['data-no-speech']).toBe('false');

    vi.advanceTimersByTime(200);
    await flush();
    const warning = findByTestId(surface, 'composer-no-speech-warning');
    expect(warning).toBeDefined();
    expect(warning?.props.role).toBe('status');
    expect(warning?.props['aria-live']).toBe('polite');
    expect(textContent(warning!)).toContain('Not hearing words');
    expect(textContent(warning!)).toContain('Check the mic or try again.');
    expect(warning?.props['data-no-speech-reason']).toBe('no-text-timeout');
    expect(warning?.props['data-motion']).toBe('pulse');
    // Nothing around the warning still claims all is well.
    expect(textContent(findByTestId(surface, 'composer-mode-status')!)).toBe('NO WORDS');
    expect(findByTestId(surface, 'composer-mode-status')?.props['aria-label']).toBe('Prompt dictation is listening but not hearing words');
    expect(textContent(findByTestId(surface, 'composer-recording-phase')!)).toBe('Still listening');
    expect(findByTestId(surface, 'composer-recording-preview')).toBeUndefined();
    const waveform = findAll(surface, (candidate) => classList(candidate).includes('recording-mode__waveform'))[0];
    expect(classList(waveform)).toContain('recording-mode__waveform--no-speech');
    expect(classList(waveform)).toContain('recording-mode__waveform--pulse');
    // Non-blocking: Stop and Discard stay available, and the draft is untouched.
    expect(findByTestId(surface, 'composer-recording-stop')?.props.disabled).toBe(false);
    expect(findByTestId(surface, 'composer-recording-cancel')?.props.disabled).toBe(false);
    expect(drafts.draftFor(targetKey)).toBe('typed before');
    expect(findByTestId(surface, 'prompt-draft')?.props.value).toBe('typed before');

    recognizer.emit('audio', undefined, 'sound');
    await flush();
    expect(textContent(findByTestId(surface, 'composer-no-speech-warning')!))
      .toContain('Sound, but no words. Check the language or speak closer.');
    expect(drafts.draftFor(targetKey)).toBe('typed before');

    recognizer.emit('partial', 'finally some words');
    await flush();
    expect(findByTestId(surface, 'composer-no-speech-warning')).toBeUndefined();
    expect(textContent(findByTestId(surface, 'composer-recording-preview')!)).toContain('finally some words');
    expect(textContent(findByTestId(surface, 'composer-mode-status')!)).toBe('LISTENING');
    expect(textContent(findByTestId(surface, 'composer-recording-phase')!)).toBe('Listening');
    expect(drafts.draftFor(targetKey)).toBe('typed before');
    expect(writePty).not.toHaveBeenCalled();
    app.unmount();
  });

  it('raises the warning early when the recognizer keeps ending turns with no match', async () => {
    const { app, surface } = await mountComposer({ mobileSheet: true });
    await startDictating(surface);
    recognizer.emit('recoverable', undefined, 'no-match');
    await flush();
    vi.advanceTimersByTime(300);
    await flush();
    expect(recognizer.starts).toBe(2);
    expect(findByTestId(surface, 'composer-no-speech-warning')).toBeUndefined();
    recognizer.emit('recoverable', undefined, 'speech-timeout');
    await flush();
    const warning = findByTestId(surface, 'composer-no-speech-warning');
    expect(warning?.props['data-no-speech-reason']).toBe('empty-turns');
    // The silent restart that follows does not flash "Requesting microphone access" or drop the warning.
    vi.advanceTimersByTime(300);
    expect(recognizer.starts).toBe(3);
    expect(findByTestId(surface, 'prompt-composer')?.props['data-dictation-state']).toBe('recording');
    expect(findByTestId(surface, 'composer-no-speech-warning')?.props['data-no-speech-reason']).toBe('empty-turns');
    await flush();
    expect(findByTestId(surface, 'prompt-composer')?.props['data-dictation-state']).toBe('recording');
    app.unmount();
  });

  it('uses a static highlight instead of pulsing when the user prefers reduced motion', async () => {
    stubReducedMotion(true);
    const { app, surface } = await mountComposer({ mobileSheet: true });
    await startDictating(surface);
    vi.advanceTimersByTime(8_000);
    await flush();
    const warning = findByTestId(surface, 'composer-no-speech-warning');
    expect(warning?.props['data-motion']).toBe('static');
    const waveform = findAll(surface, (candidate) => classList(candidate).includes('recording-mode__waveform'))[0];
    expect(classList(waveform)).toContain('recording-mode__waveform--static');
    expect(classList(waveform)).not.toContain('recording-mode__waveform--pulse');
    // The stylesheet also stops every warning animation under the media query.
    expect(composerRecordingModeSource).toMatch(
      /@media \(prefers-reduced-motion: reduce\) \{[^}]*\.recording-mode__waveform--no-speech span[^}]*animation: none/u,
    );
    app.unmount();
  });

  it('says "No speech was recognized" on Stop with nothing captured', async () => {
    const { app, surface, drafts, targetKey } = await mountComposer({ mobileSheet: true, baseDraft: 'typed before' });
    await startDictating(surface);
    vi.advanceTimersByTime(8_000);
    await flush();
    (findByTestId(surface, 'composer-recording-stop')?.props.onClick as () => void)();
    await flush();
    recognizer.emit('recoverable', undefined, 'no-match');
    await flush();
    expect(findByTestId(surface, 'prompt-composer')?.props['data-dictation-state']).toBe('review');
    expect(findByTestId(surface, 'composer-no-speech-warning')).toBeUndefined();
    expect(textContent(findByTestId(surface, 'composer-dictation-review')!)).toContain('No speech was recognized.');
    expect(drafts.draftFor(targetKey)).toBe('typed before');
    app.unmount();
  });

  it('still sends a typed-only draft normally when no dictation was started', async () => {
    const { app, surface, drafts, targetKey, writePty } = await mountComposer({ mobileSheet: true, baseDraft: 'typed only' });
    const send = findAll(surface, (candidate) => candidate.type === 'button' && candidate.props.title === 'Send (Enter)')[0];
    expect(send?.props.disabled).toBe(false);
    (send?.props.onClick as () => void)();
    // Delivery paces Enter after the text; let its timers run.
    for (let index = 0; index < 20; index += 1) {
      vi.advanceTimersByTime(100);
      await flush();
    }
    const written = (writePty.mock.calls as unknown as Array<[Uint8Array]>).map(([bytes]) => new TextDecoder().decode(bytes)).join('');
    expect(written).toContain('typed only');
    expect(written.endsWith(String.fromCharCode(13))).toBe(true);
    expect(drafts.draftFor(targetKey)).toBe('');
    expect(textContent(findByTestId(surface, 'composer-status')!)).not.toContain('No speech');
    app.unmount();
  });

  it('does not send the old draft when Send is tapped mid-dictation with nothing captured', async () => {
    const { app, surface, drafts, targetKey, writePty } = await mountComposer({ mobileSheet: true, baseDraft: 'typed before' });
    await startDictating(surface);
    vi.advanceTimersByTime(8_000);
    await flush();
    (findByTestId(surface, 'composer-dictation-send')?.props.onClick as () => void)();
    await flush();
    recognizer.emit('recoverable', undefined, 'no-match');
    await flush();
    expect(writePty).not.toHaveBeenCalled();
    expect(findByTestId(surface, 'prompt-composer')?.props['data-dictation-state']).toBe('review');
    expect(textContent(findByTestId(surface, 'composer-status')!))
      .toBe('No speech was recognized. Nothing was sent; your draft was kept.');
    expect(drafts.draftFor(targetKey)).toBe('typed before');
    app.unmount();
  });
});
