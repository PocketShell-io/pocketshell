import { computed, createRenderer, h, nextTick, ref, type Ref } from 'vue';
import { afterEach, describe, expect, it, vi } from 'vitest';

/**
 * #3128: the two contributed components. The launcher (composer.launcher)
 * starts the recognizer and opens the composer in one tap, on the phone layout
 * only; the composer panel (composer.inputSources) stops a live run when the
 * composer switches session or closes, and maps its buttons onto the run.
 */
const mocks = vi.hoisted(() => ({
  layout: { value: 'compact' as 'compact' | 'medium' | 'expanded' },
  calls: [] as string[],
}));

vi.mock('@ui/app/layoutClass', async () => {
  const { computed: c, ref: r } = await import('vue');
  const layout = r(mocks.layout.value);
  return {
    useLayoutClass: () => {
      layout.value = mocks.layout.value;
      return c(() => layout.value);
    },
  };
});

vi.mock('../../src/sharedApp/extensions/voiceComposerRun', async () => {
  const { shallowRef: s } = await import('vue');
  const state = s({ sessionKey: null as string | null, phase: 'idle', reviewing: false, partial: '', error: null as string | null, noSpeech: null });
  return {
    voiceComposer: {
      state,
      start: vi.fn((ctx: { sessionKey: string }) => {
        mocks.calls.push(`start:${ctx.sessionKey}`);
        return true;
      }),
      stop: vi.fn(() => mocks.calls.push('stop')),
      discard: vi.fn(() => mocks.calls.push('discard')),
      deliver: vi.fn(async (ctx: { sessionKey: string }, enter: boolean) => {
        mocks.calls.push(`deliver:${ctx.sessionKey}:${enter ? 'send' : 'insert'}`);
        return true;
      }),
      interrupt: vi.fn((reason: string) => mocks.calls.push(`interrupt:${reason}`)),
      clearReview: vi.fn(),
      isActive: vi.fn(() => false),
    },
  };
});

import { voiceComposer } from '../../src/sharedApp/extensions/voiceComposerRun';
import VoiceComposerLauncher from '../../src/sharedApp/extensions/VoiceComposerLauncher.vue';
import ComposerDictation from '../../src/sharedApp/extensions/ComposerDictation.vue';
import { voiceComposerExtensions } from '../../src/sharedApp/extensions/voiceComposer';
import { extensionsFor, provideExtensions } from '@ui/app/extensions';

interface HostNode {
  type: string;
  props: Record<string, unknown>;
  children: HostNode[];
  parent?: HostNode;
  text?: string;
}
const node = (type: string, text?: string): HostNode => ({ type, props: {}, children: [], text });
const renderer = createRenderer<HostNode, HostNode>({
  patchProp(element, key, _previous, next) {
    if (next == null) delete element.props[key];
    else element.props[key] = next;
  },
  insert(child, parent, anchor) {
    if (child.parent) {
      const at = child.parent.children.indexOf(child);
      if (at >= 0) child.parent.children.splice(at, 1);
    }
    child.parent = parent;
    const index = anchor ? parent.children.indexOf(anchor) : -1;
    if (index < 0) parent.children.push(child);
    else parent.children.splice(index, 0, child);
  },
  remove(child) {
    if (!child.parent) return;
    const at = child.parent.children.indexOf(child);
    if (at >= 0) child.parent.children.splice(at, 1);
    child.parent = undefined;
  },
  createElement: (type) => node(type),
  createText: (text) => node('#text', text),
  createComment: (text) => node('#comment', text),
  setText: (element, text) => {
    element.text = text;
  },
  setElementText: (element, text) => {
    element.children = [];
    element.text = text;
  },
  parentNode: (element) => element.parent ?? null,
  nextSibling: (element) => {
    if (!element.parent) return null;
    return element.parent.children[element.parent.children.indexOf(element) + 1] ?? null;
  },
});

function byTestId(root: HostNode, id: string): HostNode | undefined {
  if (root.props['data-testid'] === id) return root;
  for (const child of root.children) {
    const found = byTestId(child, id);
    if (found) return found;
  }
  return undefined;
}
function click(root: HostNode, id: string): void {
  const target = byTestId(root, id);
  expect(target, id).toBeTruthy();
  (target!.props.onClick as () => void)();
}

/** What the panel asked the run to do, without its action-row claims. */
const actions = () => mocks.calls.filter((c) => !c.startsWith('claim:'));

function context(sessionKey: string) {
  return {
    sessionName: sessionKey,
    sessionKey,
    insertText: vi.fn(),
    attachFiles: vi.fn(),
    open: vi.fn(() => mocks.calls.push(`open:${sessionKey}`)),
    readDraft: vi.fn(() => ''),
    setDraft: vi.fn(),
    submit: vi.fn(async () => true),
    claimActions: vi.fn((claimed: boolean) => mocks.calls.push(`claim:${sessionKey}:${claimed}`)),
  };
}

interface RunState { sessionKey: string | null; phase: string; reviewing: boolean; partial: string; error: string | null; noSpeech: null }
const runState = voiceComposer.state as unknown as Ref<RunState>;
const IDLE_RUN: RunState = { sessionKey: null, phase: 'idle', reviewing: false, partial: '', error: null, noSpeech: null };
function setRun(patch: Partial<RunState>): void {
  runState.value = { ...IDLE_RUN, ...patch };
}
let unmount: (() => void) | null = null;

function mount(component: unknown, ctx: () => ReturnType<typeof context>): HostNode {
  const root = node('root');
  const app = renderer.createApp({ render: () => h(component as never, { context: ctx() }) });
  app.mount(root);
  unmount = () => app.unmount();
  return root;
}

afterEach(() => {
  unmount?.();
  unmount = null;
  mocks.calls.length = 0;
  mocks.layout.value = 'compact';
  setRun({});
  provideExtensions({});
});

describe('voice composer launcher and dictation panel (#3128)', () => {
  it('one tap starts the recognizer for this session and opens the composer', () => {
    const root = mount(VoiceComposerLauncher, () => context('ws/a'));
    click(root, 'voice-composer-launcher');
    expect(mocks.calls).toEqual(['start:ws/a', 'open:ws/a']);
  });

  it.each(['medium', 'expanded'] as const)('renders nothing on the %s layout (D44: desktop unchanged)', (layout) => {
    mocks.layout.value = layout;
    const root = mount(VoiceComposerLauncher, () => context('ws/a'));
    expect(byTestId(root, 'voice-composer-launcher')).toBeUndefined();
  });

  it('contributes the launcher and the panel to the composer slots only', () => {
    provideExtensions({ ...voiceComposerExtensions });
    expect(extensionsFor('composer.launcher').map((e) => e.id)).toEqual(['voice-composer']);
    expect(extensionsFor('composer.inputSources').map((e) => e.id)).toEqual(['voice-composer-dictation']);
    expect(extensionsFor('terminal.dock')).toEqual([]);
  });

  it.each(['medium', 'expanded'] as const)('the panel renders nothing and claims nothing on the %s layout (D44)', (layout) => {
    mocks.layout.value = layout;
    setRun({ sessionKey: 'ws/a', phase: 'listening' });
    const root = mount(ComposerDictation, () => context('ws/a'));
    expect(byTestId(root, 'composer-dictation')).toBeUndefined();
    expect(mocks.calls).toEqual(['claim:ws/a:false']);
  });

  it('owns the action row (one set of Discard/Send) only while listening or reviewing', async () => {
    setRun({ sessionKey: 'ws/a', phase: 'listening' });
    mount(ComposerDictation, () => context('ws/a'));
    expect(mocks.calls).toEqual(['claim:ws/a:true']);
    setRun({ sessionKey: 'ws/a', phase: 'completed', reviewing: true });
    await nextTick();
    expect(mocks.calls).toEqual(['claim:ws/a:true']);
    setRun({});
    await nextTick();
    expect(mocks.calls).toEqual(['claim:ws/a:true', 'claim:ws/a:false']);
    unmount!();
    unmount = null;
    expect(mocks.calls.at(-1)).toBe('claim:ws/a:false');
  });

  it('idle: offers Dictate, which starts a run for the composer session', () => {
    const root = mount(ComposerDictation, () => context('ws/a'));
    expect(byTestId(root, 'composer-dictation')!.props['data-phase']).toBe('idle');
    click(root, 'composer-dictation-start');
    expect(actions()).toEqual(['start:ws/a']);
  });

  it('listening: Stop, Discard, Insert (no Enter) and Send map onto the run', async () => {
    setRun({ sessionKey: 'ws/a', phase: 'listening' });
    const root = mount(ComposerDictation, () => context('ws/a'));
    await nextTick();
    expect(byTestId(root, 'composer-dictation')!.props['data-phase']).toBe('listening');
    click(root, 'composer-dictation-stop');
    click(root, 'composer-dictation-insert');
    click(root, 'composer-dictation-send');
    click(root, 'composer-dictation-discard');
    expect(actions()).toEqual(['stop', 'deliver:ws/a:insert', 'deliver:ws/a:send', 'discard']);
  });

  it("another session's run is not shown here", async () => {
    setRun({ sessionKey: 'ws/b', phase: 'listening' });
    const root = mount(ComposerDictation, () => context('ws/a'));
    await nextTick();
    expect(byTestId(root, 'composer-dictation')!.props['data-phase']).toBe('idle');
    expect(byTestId(root, 'composer-dictation-stop')).toBeUndefined();
  });

  it('switching the composer to another session stops the run (target change)', async () => {
    setRun({ sessionKey: 'ws/a', phase: 'listening' });
    const key = ref('ws/a');
    const ctx = computed(() => context(key.value));
    mount(ComposerDictation, () => ctx.value);
    key.value = 'ws/b';
    await nextTick();
    await nextTick();
    expect(mocks.calls).toContain('interrupt:target-change');
    expect(mocks.calls.some((c) => c.startsWith('deliver'))).toBe(false);
  });

  it('the composer closing while listening stops the run and delivers nothing', async () => {
    setRun({ sessionKey: 'ws/a', phase: 'listening' });
    mount(ComposerDictation, () => context('ws/a'));
    await nextTick();
    unmount!();
    unmount = null;
    expect(actions()).toEqual(['interrupt:closed']);
  });

  it('review: a finished run offers Discard, Insert and Send but no Stop', async () => {
    setRun({ sessionKey: 'ws/a', phase: 'completed', reviewing: true });
    const root = mount(ComposerDictation, () => context('ws/a'));
    await nextTick();
    expect(byTestId(root, 'composer-dictation')!.props['data-phase']).toBe('review');
    expect(byTestId(root, 'composer-dictation-stop')).toBeUndefined();
    expect(byTestId(root, 'composer-dictation-send')).toBeTruthy();
  });
});
