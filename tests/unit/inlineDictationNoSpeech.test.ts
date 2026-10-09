import { createRenderer, defineComponent, h, ref, type App } from 'vue';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { DictationEvent } from '../../src/session/platformInput';
import { createInlineDictationController, type InlineDictationState } from '../../src/session/inlineDictation';
import { createSharedDictationController } from '../../src/session/dictationController';
import MobileHotkeys from '../../src/components/MobileHotkeys.vue';
import mobileHotkeysSource from '../../src/components/MobileHotkeys.vue?raw';

/**
 * Issue #3062: the inline terminal dictation bar shows the same "not hearing
 * words" warning as the composer: after N seconds of listening with no partial
 * or final, on the listening band and on the Dictate/Stop control, cleared by
 * any recognized text, static under reduced motion. Partials still never reach
 * the PTY.
 */
const mocks = vi.hoisted(() => ({
  sinks: new Map<string, (event: DictationEvent) => void>(),
  latest: '',
}));

vi.mock('@capacitor/app', () => ({ App: { addListener: vi.fn(async () => ({ remove: vi.fn(async () => {}) })) } }));
vi.mock('../../src/session/platformInput', () => ({
  platformInput: {
    startRecognition: vi.fn(async (requestId: string, onEvent: (event: DictationEvent) => void) => {
      mocks.sinks.set(requestId, onEvent);
      mocks.latest = requestId;
    }),
    stopRecognition: vi.fn(async () => {}),
    cancelRecognition: vi.fn(async () => {}),
  },
}));

import TerminalDictationBar from '../../src/components/TerminalDictationBar.vue';

function emit(type: DictationEvent['type'], text?: string, code?: string) {
  mocks.sinks.get(mocks.latest)?.({
    requestId: mocks.latest, type, ...(text === undefined ? {} : { text }), ...(code ? { code } : {}),
  });
}

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

function findByTestId(root: HostNode, testId: string): HostNode | undefined {
  if (root.props['data-testid'] === testId) return root;
  for (const child of root.children) {
    const match = findByTestId(child, testId);
    if (match) return match;
  }
  return undefined;
}

function textContent(root: HostNode): string {
  return `${root.text ?? ''}${root.children.map(textContent).join('')}`.trim();
}

function classList(element: HostNode | undefined): string {
  const value = element?.props.class;
  if (typeof value === 'string') return value;
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

/** Mount the real dock with the real TerminalDictationBar in its accessory slot, wired like App.vue. */
function mountDock() {
  const root = node('root');
  const insertText = vi.fn(async () => true);
  const states: InlineDictationState[] = [];
  const Host = defineComponent({
    setup: () => {
      const dictationState = ref<InlineDictationState>({
        phase: 'idle', preview: '', message: 'Tap Dictate to speak at the terminal cursor.', tone: 'quiet',
      });
      return () => h(MobileHotkeys, {
        enabled: true,
        dictationAvailable: true,
        dictationState: dictationState.value,
        dictationTargetKey: 'host/inline-session',
      }, {
        'persistent-accessory': () => h(TerminalDictationBar, {
          enabled: true,
          targetKey: 'host/inline-session',
          languageTag: 'auto',
          silenceWindowMs: 4_000,
          insertText,
          onStateChange: (next: InlineDictationState) => {
            states.push(next);
            dictationState.value = next;
          },
        }),
      });
    },
  });
  const app = renderer.createApp(Host) as App;
  app.mount(root);
  return { root, app, insertText, states };
}

describe('inline terminal dictation warns when it hears no words (#3062)', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    mocks.sinks.clear();
    mocks.latest = '';
    stubReducedMotion(false);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.clearAllMocks();
  });

  it('projects the shared no-text signal into the inline listening state and clears it on a partial', async () => {
    const states: InlineDictationState[] = [];
    const controller = createInlineDictationController({
      createController: (settings) => createSharedDictationController(settings),
      insertText: vi.fn(async () => true),
    });
    controller.subscribe((next) => states.push(next));
    controller.start({ targetKey: 'host/a', languageTag: 'auto', silenceWindowMs: 4_000 });
    await flush();
    expect(controller.getState()).toMatchObject({ phase: 'listening', noSpeech: null });

    vi.advanceTimersByTime(8_000);
    expect(controller.getState()).toMatchObject({
      phase: 'listening',
      noSpeech: { reason: 'no-text-timeout', audio: 'unknown' },
      preview: '',
    });

    emit('partial', 'git status');
    expect(controller.getState()).toMatchObject({ phase: 'listening', noSpeech: null, preview: 'git status' });
    controller.cancel();
  });

  it('shows the warning on the listening band and the Stop control, then clears it when text arrives', async () => {
    const { root, app, insertText } = mountDock();
    (findByTestId(root, 'inline-dictation-toggle')?.props.onClick as () => void)();
    await flush();
    expect(findByTestId(root, 'inline-dictation-bar')?.props['data-phase']).toBe('listening');
    expect(findByTestId(root, 'inline-dictation-no-speech-title')).toBeUndefined();

    vi.advanceTimersByTime(8_000);
    await flush();
    const status = findByTestId(root, 'inline-dictation-status')!;
    expect(status.props.role).toBe('status');
    expect(status.props['data-no-speech']).toBe('true');
    expect(status.props['data-motion']).toBe('pulse');
    expect(status.props['aria-label']).toContain('Not hearing words');
    expect(textContent(findByTestId(root, 'inline-dictation-no-speech-title')!)).toBe('Not hearing words');
    expect(textContent(findByTestId(root, 'inline-dictation-no-speech-warning')!)).toBe('Check the mic');
    expect(status.props['aria-label']).toContain('Check the mic or try again.');
    // The 40px band drops the destination label to fit the warning; the label still names the terminal.
    expect(textContent(status)).not.toContain('Terminal');
    expect(status.props['aria-label']).toContain('Terminal listening');
    const toggle = findByTestId(root, 'inline-dictation-toggle')!;
    expect(toggle.props['data-no-speech']).toBe('true');
    expect(toggle.props['data-mic-state']).toBe('listening');
    expect(toggle.props.disabled).toBe(false);
    expect(toggle.props['aria-label']).toContain('Not hearing words');

    emit('audio', undefined, 'silence');
    await flush();
    expect(textContent(findByTestId(root, 'inline-dictation-no-speech-warning')!)).toBe('No sound from mic');
    expect(findByTestId(root, 'inline-dictation-status')?.props['aria-label'])
      .toContain('No sound from the mic. Check it or try again.');

    emit('partial', 'ls -la');
    await flush();
    expect(findByTestId(root, 'inline-dictation-status')?.props['data-no-speech']).toBe('false');
    expect(findByTestId(root, 'inline-dictation-no-speech-title')).toBeUndefined();
    expect(textContent(findByTestId(root, 'inline-dictation-preview')!)).toBe('ls -la');
    expect(findByTestId(root, 'inline-dictation-toggle')?.props['data-no-speech']).toBe('false');
    expect(insertText).not.toHaveBeenCalled();
    app.unmount();
  });

  it('keeps earlier words visible in the band while warning about a later silence', async () => {
    const { root, app } = mountDock();
    (findByTestId(root, 'inline-dictation-toggle')?.props.onClick as () => void)();
    await flush();
    emit('result', 'git');
    await flush();
    vi.advanceTimersByTime(300);
    await flush();
    vi.advanceTimersByTime(8_000);
    await flush();
    expect(textContent(findByTestId(root, 'inline-dictation-no-speech-title')!)).toBe('Not hearing words');
    expect(textContent(findByTestId(root, 'inline-dictation-preview')!)).toBe('git');
    // Words were heard earlier, so this reads as a pause, never "no sound from the mic".
    expect(findByTestId(root, 'inline-dictation-status')?.props['aria-label']).toContain('Paused? Keep speaking or tap Stop.');
    expect(findByTestId(root, 'inline-dictation-status')?.props['aria-label']).not.toContain('No sound');
    app.unmount();
  });

  it('keeps the listening band (and the warning) through silent recognizer restarts instead of flashing Starting', async () => {
    const { root, app, states } = mountDock();
    (findByTestId(root, 'inline-dictation-toggle')?.props.onClick as () => void)();
    await flush();
    const before = states.length;
    for (let turn = 0; turn < 3; turn += 1) {
      emit('recoverable', undefined, 'speech-timeout');
      await flush();
      vi.advanceTimersByTime(300);
      await flush();
    }
    expect(states.slice(before).map((state) => state.phase)).not.toContain('starting');
    expect(findByTestId(root, 'inline-dictation-bar')?.props['data-phase']).toBe('listening');
    expect(textContent(findByTestId(root, 'inline-dictation-no-speech-title')!)).toBe('Not hearing words');
    // Stop still works from a restart turn.
    (findByTestId(root, 'inline-dictation-toggle')?.props.onClick as () => void)();
    await flush();
    emit('recoverable', undefined, 'no-match');
    await flush();
    expect(states.at(-1)).toMatchObject({ phase: 'idle' });
    app.unmount();
  });

  it('uses a static highlight under reduced motion', async () => {
    stubReducedMotion(true);
    const { root, app } = mountDock();
    (findByTestId(root, 'inline-dictation-toggle')?.props.onClick as () => void)();
    await flush();
    vi.advanceTimersByTime(8_000);
    await flush();
    const status = findByTestId(root, 'inline-dictation-status')!;
    expect(status.props['data-motion']).toBe('static');
    expect(classList(status)).toContain('mobile-hotkeys__recording-status--no-speech');
    expect(mobileHotkeysSource).toMatch(
      /@media \(prefers-reduced-motion: reduce\) \{[^}]*\.mobile-hotkeys__recording-status--no-speech[^}]*animation: none/u,
    );
    app.unmount();
  });

  it('says "No speech was recognized" on Stop with nothing captured and inserts nothing', async () => {
    const { root, app, insertText, states } = mountDock();
    (findByTestId(root, 'inline-dictation-toggle')?.props.onClick as () => void)();
    await flush();
    vi.advanceTimersByTime(8_000);
    await flush();
    (findByTestId(root, 'inline-dictation-toggle')?.props.onClick as () => void)();
    await flush();
    emit('recoverable', undefined, 'no-match');
    await flush();
    expect(states.at(-1)).toMatchObject({ phase: 'idle', tone: 'warning' });
    expect(states.at(-1)?.noSpeech ?? null).toBeNull();
    expect(textContent(findByTestId(root, 'inline-dictation-status')!)).toContain('No speech was recognized.');
    expect(insertText).not.toHaveBeenCalled();
    app.unmount();
  });
});
