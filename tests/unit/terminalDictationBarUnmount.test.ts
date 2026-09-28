import { compile, createRenderer, getCurrentInstance, ssrContextKey, type App, type VNode } from 'vue';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { DictationEvent, DictationSession } from '../../src/session/platformInput';
import DictationMicIcon from '../../src/components/DictationMicIcon.vue';
import dictationMicIconSource from '../../src/components/DictationMicIcon.vue?raw';
import terminalDictationBarSource from '../../src/components/TerminalDictationBar.vue?raw';

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

const terminalDictationTemplate = terminalDictationBarSource.match(/<template>([\s\S]*)<\/template>/)?.[1];
if (!terminalDictationTemplate) throw new Error('TerminalDictationBar production template is missing');
const compiledTerminalDictationTemplate = compile(terminalDictationTemplate, { hoistStatic: false }) as unknown as (
  context: object,
  cache: unknown[],
  props: object,
  setup: object,
  data: object,
  options: object,
) => VNode;
const dictationMicIconTemplate = dictationMicIconSource.match(/<template>([\s\S]*)<\/template>/)?.[1];
if (!dictationMicIconTemplate) throw new Error('DictationMicIcon production template is missing');
const compiledDictationMicIconTemplate = compile(dictationMicIconTemplate, { hoistStatic: false }) as unknown as (
  context: object,
  cache: unknown[],
  props: object,
  setup: object,
  data: object,
  options: object,
) => VNode;

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
  components: {
    DictationMicIcon: {
      ...DictationMicIcon,
      render() {
        const internal = getCurrentInstance() as unknown as {
          props?: object;
        } | null;
        const props = internal?.props ?? {};
        return compiledDictationMicIconTemplate(props, [], props, props, props, props);
      },
    },
  },
  render() {
    const internal = getCurrentInstance() as unknown as {
      setupState?: object;
      renderCache?: unknown[];
    } | null;
    const setup = internal?.setupState ?? {};
    return compiledTerminalDictationTemplate(
      setup,
      internal?.renderCache ?? [],
      setup,
      setup,
      setup,
      setup,
    );
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

function findAllByType(root: HostNode, type: string): HostNode[] {
  const matches = root.type === type ? [root] : [];
  for (const child of root.children) matches.push(...findAllByType(child, type));
  return matches;
}

async function flushPromises() {
  await Promise.resolve();
  await Promise.resolve();
}

describe('terminal dictation bar lifecycle', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('keeps one Kotlin-sized hit slot with visible Dictate/Stop actions and listening tint', async () => {
    let recognitionEvent: ((event: DictationEvent) => void) | undefined;
    const session: DictationSession = {
      requestId: 'inline-glyph-1',
      stop: vi.fn(async () => {}),
      cancel: vi.fn(async () => {}),
    };
    mocks.addListener.mockResolvedValue({ remove: vi.fn(async () => {}) });
    mocks.startDictation.mockImplementation(async (onEvent: (event: DictationEvent) => void) => {
      recognitionEvent = onEvent;
      onEvent({ requestId: 'inline-glyph-1', type: 'started' });
      return session;
    });

    const root = node('root');
    const app = renderer.createApp(mountedDictationBar, {
      enabled: true,
      targetKey: 'host/session-1',
      languageTag: 'auto',
      silenceWindowMs: 4_000,
      insertText: vi.fn(async () => true),
    }) as App;
    app.provide(ssrContextKey, { modules: new Set<string>() });
    app.mount(root);
    await flushPromises();

    const toggle = findByTestId(root, 'inline-dictation-toggle');
    expect(toggle?.props['aria-label']).toBe('Dictate at terminal cursor');
    expect(findByTestId(root, 'inline-dictation-dock-label')?.text).toBe('Dictate');
    expect(findAllByType(toggle!, 'span').map((span) => span.text)).toEqual(['Dictate']);
    expect(toggle?.props['data-mic-state']).toBe('idle');
    const idleMicSvg = findAllByType(toggle!, 'svg')[0];
    expect(idleMicSvg?.props['aria-hidden']).toBe('true');
    expect(idleMicSvg?.children.filter((child) => child.type === 'path').map((path) => path.props.d))
      .toEqual([
        'M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3z',
        'M19 10v2a7 7 0 0 1-14 0v-2',
        'M12 19v3M8 22h8',
      ]);

    (toggle?.props.onClick as (() => void) | undefined)?.();
    await flushPromises();

    const listeningToggle = findByTestId(root, 'inline-dictation-toggle');
    expect(listeningToggle?.props['data-mic-state']).toBe('listening');
    expect(listeningToggle?.props['aria-label']).toBe('Stop dictation and insert at terminal cursor');
    expect(listeningToggle?.props.title).toBe('Stop dictation and insert at terminal cursor');
    expect(listeningToggle?.props['aria-pressed']).toBe(true);
    expect(findByTestId(root, 'inline-dictation-dock-label')?.text).toBe('Stop');
    expect(findAllByType(listeningToggle!, 'span').map((span) => span.text)).toEqual(['Stop']);
    expect(listeningToggle?.props['data-mic-state']).toBe('listening');
    const listeningMicSvg = findAllByType(listeningToggle!, 'svg')[0];
    expect(listeningMicSvg?.props['aria-hidden']).toBe('true');
    expect(listeningMicSvg?.children.filter((child) => child.type === 'path').map((path) => path.props.d))
      .toEqual(['M7 7h10v10H7z']);
    expect(findByTestId(root, 'inline-dictation-action-label')).toBeUndefined();
    expect(terminalDictationBarSource).toContain("if (state.value.phase === 'listening') return 'listening';");
    expect(terminalDictationBarSource).toContain(':stopped="state.phase === \'listening\'"');

    app.unmount();
    recognitionEvent?.({ requestId: 'inline-glyph-1', type: 'stopped' });
    await flushPromises();
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
      message: 'Tap Dictate to speak at the terminal cursor.',
      tone: 'quiet',
    });
    expect(insertText).not.toHaveBeenCalled();
  });
});
