import { createRenderer, defineComponent, h, nextTick, ref, type App } from 'vue';
import { describe, expect, it } from 'vitest';
import { HOTKEY_CTRL_PAGE_ROWS, HOTKEY_PALETTE_MAIN_SECTIONS } from '@pocketshell/core';
import MobileHotkeys from '../../src/components/MobileHotkeys.vue';
import { createMobileHotkeysActions, createMobileHotkeysState } from '../../src/components/mobileHotkeysModel';

function makeHarness(enabled = true, holdThresholdMs = 500) {
  const state = createMobileHotkeysState(enabled);
  const sent: Array<{ bytes: number[]; key: string }> = [];
  const paletteChanges: boolean[] = [];
  const actions = createMobileHotkeysActions(state, {
    send: (bytes, key) => sent.push({ bytes: [...bytes], key }),
    paletteChange: (open) => paletteChanges.push(open),
  }, holdThresholdMs);
  return { state, sent, paletteChanges, actions };
}

describe('mobile fast-key behavior', () => {
  it('keeps the controls inert off-live and resumes only after an explicit live state', () => {
    const { state, sent, paletteChanges, actions } = makeHarness(false);

    actions.sendKey('arrow-up');
    actions.togglePalette();
    actions.beginControlPointer('ctrl-c', { button: 0, isPrimary: true, pointerId: 1, timeStamp: 100 });
    actions.finishControlPointer(1, 800);
    expect(state).toMatchObject({ enabled: false, paletteOpen: false });
    expect(sent).toEqual([]);
    expect(paletteChanges).toEqual([]);

    actions.setEnabled(true);
    actions.sendKey('arrow-up');
    expect(sent).toEqual([{ bytes: [0x1b, 0x5b, 0x41], key: 'arrow-up' }]);
  });

  it('emits core catalog bytes and keeps the palette open until toggled or closed', () => {
    const { state, sent, paletteChanges, actions } = makeHarness();

    actions.sendKey('arrow-up');
    actions.sendKey('arrow-down');
    actions.sendKey('enter');
    actions.togglePalette();
    actions.sendKey('escape');
    expect(state).toMatchObject({ paletteOpen: true, page: 'main' });

    actions.showCtrlPage();
    actions.sendKey('ctrl-q');
    expect(state).toMatchObject({ paletteOpen: true, page: 'ctrl' });
    actions.closePalette();
    actions.togglePalette();
    expect(state).toMatchObject({ paletteOpen: true, page: 'main' });
    actions.sendKey('shift-tab');

    expect(sent).toEqual([
      { bytes: [0x1b, 0x5b, 0x41], key: 'arrow-up' },
      { bytes: [0x1b, 0x5b, 0x42], key: 'arrow-down' },
      { bytes: [0x0d], key: 'enter' },
      { bytes: [0x1b], key: 'escape' },
      { bytes: [0x11], key: 'ctrl-q' },
      { bytes: [0x1b, 0x5b, 0x5a], key: 'shift-tab' },
    ]);
    expect(paletteChanges).toEqual([true, false, true]);
  });

  it('renders core key categories and keeps the full QWERTY Ctrl catalog reachable on its page', async () => {
      const mounted = mountMobileHotkeys();
    try {
      expect(findByTestId(mounted.root, 'mobile-hotkeys-sheet')).toBeUndefined();
      const launcher = findButton(mounted.root, { 'data-testid': 'mobile-hotkeys-launcher' });
      expect(launcher.props['aria-label']).toBe('More terminal keys');
      expect(findByTestId(mounted.root, 'mobile-hotkeys-launcher-label')).toBeUndefined();
      expect(findByTestId(mounted.root, 'mobile-hotkeys-enter-divider')).toBeDefined();
      const navigationParts = findAll(findByTestId(mounted.root, 'mobile-hotkeys-navigation')!, (node) =>
        node.props['data-testid'] === 'mobile-hotkeys-enter-divider'
        || (node.tag === 'button' && typeof node.props['data-key-id'] === 'string'));
      expect(navigationParts.map((node) => node.props['data-key-id'] ?? node.props['data-testid']))
        .toEqual(['arrow-up', 'arrow-down', 'mobile-hotkeys-enter-divider', 'enter']);
      click(findButton(mounted.root, { 'data-testid': 'mobile-hotkeys-launcher' }));
      await nextTick();
      expect(findButton(mounted.root, { 'data-testid': 'mobile-hotkeys-launcher' }).props['aria-label'])
        .toBe('Close terminal keys');

      const sheet = findByTestId(mounted.root, 'mobile-hotkeys-sheet');
      if (!sheet) throw new Error('The on-demand key catalog sheet did not mount');
      expect(sheet.props).toMatchObject({ role: 'dialog', 'aria-modal': 'false' });
      expect(sheet.props['aria-labelledby']).toBe('mobile-hotkeys-main-title');
      expect(findByTestId(mounted.root, 'mobile-hotkeys-sheet-title')?.text).toBe('Terminal keys');
      expect(findByTestId(mounted.root, 'mobile-hotkeys-sheet-title')?.props.class).toBe('mobile-hotkeys__sheet-title');
      expect(findAll(mounted.root, (node) => node.tag === 'button' && typeof node.props['data-key-id'] === 'string'))
        .toHaveLength(3 + HOTKEY_PALETTE_MAIN_SECTIONS.reduce((count, section) => count + section.keys.length, 0));

      const mainPage = findByTestId(mounted.root, 'mobile-hotkeys-main-page');
      if (!mainPage) throw new Error('The fast keys main page did not mount');
      expect(mainPage.props).toMatchObject({
        role: 'group',
        'aria-label': 'Common terminal keys',
      });
      expect(mainPage.props.class).toContain('mobile-hotkeys__main-keys');
      const mainKeys = findAll(mainPage, (node) => node.tag === 'button' && typeof node.props['data-key-id'] === 'string');
      expect(mainKeys.map((key) => key.props['data-key-id']))
        .toEqual(HOTKEY_PALETTE_MAIN_SECTIONS.flatMap((section) => section.keys.map((key) => key.id)));
      expect(mainKeys.map((key) => key.props['data-key-section']))
        .toEqual(HOTKEY_PALETTE_MAIN_SECTIONS.flatMap((section) => section.keys.map(() => section.title)));

      click(findButton(mounted.root, { 'data-testid': 'mobile-hotkeys-open-ctrl-page' }));
      await nextTick();

      const ctrlPage = findByTestId(mounted.root, 'mobile-hotkeys-ctrl-page');
      if (!ctrlPage) throw new Error('The Ctrl key page did not mount');
      expect(findByTestId(mounted.root, 'mobile-hotkeys-sheet')?.props['aria-labelledby'])
        .toBe('mobile-hotkeys-ctrl-title');
      expect(findByTestId(mounted.root, 'mobile-hotkeys-sheet-title')?.text).toBe('Ctrl keys');
      expect(findByTestId(mounted.root, 'mobile-hotkeys-sheet-title')?.props.class).toBe('mobile-hotkeys__sheet-title');
      const ctrlIds = findAll(ctrlPage, (node) => node.tag === 'button' && typeof node.props['data-key-id'] === 'string')
        .map((node) => node.props['data-key-id']);
      expect(ctrlIds).toEqual(HOTKEY_CTRL_PAGE_ROWS.flatMap((row) => row.map((key) => key.id)));
      expect(findAll(ctrlPage, (node) => node.props.role === 'group' && typeof node.props['aria-label'] === 'string')
        .map((row) => row.props['aria-label']))
        .toEqual(HOTKEY_CTRL_PAGE_ROWS.map((_, index) => `Ctrl key row ${index + 1}`));
    } finally {
      mounted.app.unmount();
    }
  });

  it('keeps active dictation status above the persistent controls on both catalog pages', async () => {
    const mounted = mountMobileHotkeys(false, true, true);
    try {
      const dock = findByTestId(mounted.root, 'inline-dictation-bar');
      const closedDockChildren = dock?.children.filter((child) => 'tag' in child).map((child) => child.props.class);
      expect(closedDockChildren).toEqual([
        'mobile-hotkeys__dictation-status-row',
        'mobile-hotkeys__bar',
      ]);
      const statusRow = findByTestId(mounted.root, 'inline-dictation-status-row');
      expect(statusRow?.parent).toBe(dock);
      expect(findByTestId(mounted.root, 'inline-dictation-status')?.props).toMatchObject({
        role: 'status', 'aria-live': 'polite',
      });
      expect(findByTestId(mounted.root, 'inline-dictation-mode-selector')).toBeUndefined();

      click(findButton(mounted.root, { 'data-testid': 'mobile-hotkeys-launcher' }));
      await nextTick();
      const dockChildren = dock?.children.filter((child) => 'tag' in child).map((child) => child.props.class);
      expect(dockChildren).toEqual([
        'mobile-hotkeys__dictation-status-row',
        'mobile-hotkeys__bar',
        'mobile-hotkeys__sheet',
      ]);
      const sheetHeader = findByTestId(mounted.root, 'mobile-hotkeys-sheet')?.children
        .find((child) => 'tag' in child && child.props.class === 'mobile-hotkeys__sheet-header');
      if (!sheetHeader || !('tag' in sheetHeader)) throw new Error('Catalog header disappeared');
      expect(sheetHeader?.children).not.toContain(statusRow);
      expect(findByTestId(mounted.root, 'mobile-hotkeys-navigation')).toBeDefined();
      click(findButton(mounted.root, { 'data-testid': 'mobile-hotkeys-open-ctrl-page' }));
      await nextTick();
      expect(findByTestId(mounted.root, 'mobile-hotkeys-ctrl-page')).toBeDefined();
      expect(findByTestId(mounted.root, 'inline-dictation-preview')?.text).toBe('git status');
      expect(mounted.sent).toEqual([]);
    } finally {
      mounted.app.unmount();
    }
  });

  it('resolves Ctrl+C and Ctrl+D tap, hold, keyboard, and cancel without a duplicate tap', () => {
    const { sent, actions } = makeHarness();

    actions.togglePalette();
    actions.beginControlPointer('ctrl-c', { button: 0, isPrimary: true, pointerId: 1, timeStamp: 100 });
    expect(sent).toEqual([]); // No early single byte while a hold is still possible.
    actions.finishControlPointer(1, 280);
    actions.clickKey('ctrl-c', 1); // The browser's pointer-generated click is ignored.
    expect(sent).toEqual([{ bytes: [0x03], key: 'ctrl-c' }]);

    actions.beginControlPointer('ctrl-c', { button: 0, isPrimary: true, pointerId: 2, timeStamp: 400 });
    actions.finishControlPointer(2, 950);
    actions.clickKey('ctrl-c', 1);
    expect(sent.filter((send) => send.key === 'ctrl-c')).toEqual([
      { bytes: [0x03], key: 'ctrl-c' },
      { bytes: [0x03, 0x03], key: 'ctrl-c' },
    ]);

    actions.clickKey('ctrl-d', 0); // Keyboard and assistive-technology tap.
    actions.beginControlPointer('ctrl-d', { button: 0, isPrimary: true, pointerId: 3, timeStamp: 1100 });
    actions.cancelControlPointer(3);
    actions.clickKey('ctrl-d', 1);
    expect(sent.filter((send) => send.key === 'ctrl-d')).toEqual([{ bytes: [0x04], key: 'ctrl-d' }]);

    expect(actions.beginControlPointer('ctrl-c', {
      button: 2, isPrimary: true, pointerId: 5, timeStamp: 2000,
    })).toBe(false);
  });

  it('closes and cancels a pending hold on live-session loss, then starts on the main page again', () => {
    const { state, sent, paletteChanges, actions } = makeHarness();
    actions.togglePalette();
    actions.showCtrlPage();
    actions.beginControlPointer('ctrl-d', { button: 0, isPrimary: true, pointerId: 4, timeStamp: 100 });

    actions.setEnabled(false);
    actions.finishControlPointer(4, 900);
    expect(state).toMatchObject({ enabled: false, paletteOpen: false, page: 'ctrl' });
    expect(sent).toEqual([]);
    expect(paletteChanges).toEqual([true, false]);

    actions.setEnabled(true);
    actions.togglePalette();
    expect(state).toMatchObject({ enabled: true, paletteOpen: true, page: 'main' });
  });

  it('provides a direct close action for the app-level Back route', () => {
    const { state, paletteChanges, actions } = makeHarness();
    // The returned close action is the same method exposed by MobileHotkeys.vue.
    expect(typeof actions.closePalette).toBe('function');
    actions.openPalette();
    actions.closePalette();
    expect(state.paletteOpen).toBe(false);
    expect(paletteChanges).toEqual([true, false]);
  });

  it('keeps the persistent status and control slot layout available on both catalog pages', async () => {
    // Layout-only fixtures for the future #2896 mount; this does not exercise dictation behavior.
    const mounted = mountMobileHotkeys(true);
    try {
      expect(findByTestId(mounted.root, 'mobile-hotkeys-persistent-status')).toBeDefined();
      expect(findByTestId(mounted.root, 'status-slot-fixture')).toBeDefined();
      expect(findByTestId(mounted.root, 'control-slot-fixture')).toBeDefined();

      click(findButton(mounted.root, { 'data-testid': 'mobile-hotkeys-launcher' }));
      await nextTick();
      expect(findByTestId(mounted.root, 'mobile-hotkeys-main-page')).toBeDefined();
      expect(findByTestId(mounted.root, 'status-slot-fixture')).toBeDefined();
      expect(findByTestId(mounted.root, 'control-slot-fixture')).toBeDefined();
      click(findButton(mounted.root, { 'data-testid': 'mobile-hotkeys-open-ctrl-page' }));
      await nextTick();

      expect(findByTestId(mounted.root, 'status-slot-fixture')).toBeDefined();
      expect(findByTestId(mounted.root, 'control-slot-fixture')).toBeDefined();
      expect(findByTestId(mounted.root, 'mobile-hotkeys-ctrl-page')).toBeDefined();
    } finally {
      mounted.app.unmount();
    }
  });

  it('wires the mounted fast-key controls to core bytes and live-state transitions', async () => {
    const mounted = mountMobileHotkeys();
    try {
      click(findButton(mounted.root, { 'data-key-id': 'arrow-up' }));
      click(findButton(mounted.root, { 'data-key-id': 'arrow-down' }));
      click(findButton(mounted.root, { 'data-key-id': 'enter' }));
      click(findButton(mounted.root, { 'data-testid': 'mobile-hotkeys-launcher' }));
      await nextTick();
      expect(findByTestId(mounted.root, 'mobile-hotkeys-main-page')).toBeDefined();
      click(findButton(mounted.root, { 'data-key-id': 'escape' }));
      click(findButton(mounted.root, { 'data-testid': 'mobile-hotkeys-open-ctrl-page' }));
      await nextTick();
      expect(findByTestId(mounted.root, 'mobile-hotkeys-ctrl-page')).toBeDefined();
      click(findButton(mounted.root, { 'data-key-id': 'ctrl-q' }));
      click(findButton(mounted.root, { 'aria-label': 'Back to terminal hotkeys' }));
      await nextTick();
      expect(findByTestId(mounted.root, 'mobile-hotkeys-main-page')).toBeDefined();
      click(findButton(mounted.root, { 'aria-label': 'Close terminal keys' }));
      await nextTick();

      expect(mounted.paletteChanges).toEqual([true, false]);
      expect(mounted.sent.map(({ key, bytes }) => [key, [...bytes]])).toEqual([
        ['arrow-up', [0x1b, 0x5b, 0x41]],
        ['arrow-down', [0x1b, 0x5b, 0x42]],
        ['enter', [0x0d]],
        ['escape', [0x1b]],
        ['ctrl-q', [0x11]],
      ]);
      expect(mounted.sent.every(({ bytes }) => bytes instanceof Uint8Array)).toBe(true);

      click(findButton(mounted.root, { 'data-testid': 'mobile-hotkeys-launcher' }));
      await nextTick();
      const gestures: Array<{ key: string; gesture: 'tap' | 'hold' | 'cancel' }> = [
        { key: 'ctrl-c', gesture: 'tap' },
        { key: 'ctrl-c', gesture: 'hold' },
        { key: 'ctrl-c', gesture: 'cancel' },
        { key: 'ctrl-d', gesture: 'tap' },
        { key: 'ctrl-d', gesture: 'hold' },
        { key: 'ctrl-d', gesture: 'cancel' },
      ];
      for (const [index, { key, gesture }] of gestures.entries()) {
        const control = findButton(mounted.root, { 'data-key-id': key });
        const start = 1000 + index * 1000;
        pointer(control, 'pointerdown', { pointerId: index + 1, timeStamp: start });
        if (gesture === 'tap') {
          pointer(control, 'pointerup', { pointerId: index + 1, timeStamp: start + 120 });
          click(control, 1);
        } else if (gesture === 'hold') {
          pointer(control, 'pointerup', { pointerId: index + 1, timeStamp: start + 600 });
          click(control, 1);
        } else {
          pointer(control, 'pointercancel', { pointerId: index + 1, timeStamp: start + 120 });
          click(control, 1);
        }
      }
      expect(mounted.sent.slice(5).map(({ key, bytes }) => [key, [...bytes]])).toEqual([
        ['ctrl-c', [0x03]],
        ['ctrl-c', [0x03, 0x03]],
        ['ctrl-d', [0x04]],
        ['ctrl-d', [0x04, 0x04]],
      ]);

      expect(findByTestId(mounted.root, 'mobile-hotkeys-main-page')).toBeDefined();
      const pendingCtrlC = findButton(mounted.root, { 'data-key-id': 'ctrl-c' });
      pointer(pendingCtrlC, 'pointerdown', { pointerId: 99, timeStamp: 8000 });
      mounted.setEnabled(false);
      await nextTick();
      pointer(pendingCtrlC, 'pointerup', { pointerId: 99, timeStamp: 9000 });

      const slot = findByTestId(mounted.root, 'mobile-hotkeys');
      expect(slot?.props['data-enabled']).toBe(false);
      expect(slot?.props['data-palette-open']).toBe(false);
      expect(findByTestId(mounted.root, 'mobile-hotkeys-ctrl-page')).toBeUndefined();
      for (const button of findAll(mounted.root, (node) => node.tag === 'button')) {
        expect(button.props.disabled).toBe(true);
      }
      click(findButton(mounted.root, { 'data-key-id': 'arrow-up' }));
      expect(mounted.sent).toHaveLength(9);
      expect(mounted.paletteChanges).toEqual([true, false, true, false]);
    } finally {
      mounted.app.unmount();
    }
  });
});

type TestElement = {
  tag: string;
  props: Record<string, unknown>;
  children: TestNode[];
  parent: TestElement | null;
  text: string;
  setPointerCapture(pointerId: number): void;
};
type TestText = { text: string; parent: TestElement | null; kind: 'text' | 'comment' };
type TestNode = TestElement | TestText;

const renderer = createRenderer<TestNode, TestElement>({
  createElement: (tag) => ({
    tag,
    props: {},
    children: [],
    parent: null,
    text: '',
    setPointerCapture: () => {},
  }),
  createText: (text) => ({ text, parent: null, kind: 'text' }),
  createComment: (text) => ({ text, parent: null, kind: 'comment' }),
  setText: (node, text) => { node.text = text; },
  setElementText: (node, text) => {
    node.text = text;
    for (const child of node.children) child.parent = null;
    node.children = [];
  },
  patchProp: (node, key, _previous, next) => { node.props[key] = next; },
  insert: (node, parent, anchor) => {
    if (node.parent) {
      const previousIndex = node.parent.children.indexOf(node);
      if (previousIndex >= 0) node.parent.children.splice(previousIndex, 1);
    }
    node.parent = parent;
    const anchorIndex = anchor ? parent.children.indexOf(anchor) : -1;
    parent.children.splice(anchorIndex < 0 ? parent.children.length : anchorIndex, 0, node);
  },
  remove: (node) => {
    if (!node.parent) return;
    const index = node.parent.children.indexOf(node);
    if (index >= 0) node.parent.children.splice(index, 1);
    node.parent = null;
  },
  parentNode: (node) => node.parent,
  nextSibling: (node) => {
    if (!node.parent) return null;
    const index = node.parent.children.indexOf(node);
    return node.parent.children[index + 1] ?? null;
  },
});

function mountMobileHotkeys(withPersistentSlots = false, dictationAvailable = false, activeDictationStatus = false) {
  const root: TestElement = {
    tag: 'root', props: {}, children: [], parent: null, text: '', setPointerCapture: () => {},
  };
  const enabled = ref(true);
  const sent: Array<{ bytes: Uint8Array; key: string }> = [];
  const paletteChanges: boolean[] = [];
  const Host = defineComponent({
    setup: () => () => h(MobileHotkeys, {
      enabled: enabled.value,
      dictationAvailable,
      dictationState: activeDictationStatus ? {
        phase: 'listening',
        preview: 'git status',
        message: '',
        tone: 'quiet',
      } : undefined,
      holdThresholdMs: 500,
      onSend: (bytes: Uint8Array, key: string) => sent.push({ bytes, key }),
      onPaletteChange: (open: boolean) => paletteChanges.push(open),
    }, withPersistentSlots ? {
      'persistent-status': () => h('span', { 'data-testid': 'status-slot-fixture' }, 'One line of status'),
      'persistent-controls': () => h('button', { 'data-testid': 'control-slot-fixture', 'aria-label': 'Future control' }, '●'),
    } : {}),
  });
  const app = renderer.createApp(Host) as App;
  app.mount(root as unknown as Element);
  return { root, app, sent, paletteChanges, setEnabled: (value: boolean) => { enabled.value = value; } };
}

function findAll(root: TestElement, predicate: (node: TestElement) => boolean): TestElement[] {
  const matches: TestElement[] = [];
  const visit = (node: TestNode): void => {
    if (!('tag' in node)) return;
    if (predicate(node)) matches.push(node);
    for (const child of node.children) visit(child);
  };
  for (const child of root.children) visit(child);
  return matches;
}

function findByTestId(root: TestElement, testId: string): TestElement | undefined {
  return findAll(root, (node) => node.props['data-testid'] === testId)[0];
}

function findButton(root: TestElement, attributes: Record<string, unknown>): TestElement {
  const match = findAll(root, (node) => node.tag === 'button' && Object.entries(attributes)
    .every(([key, value]) => node.props[key] === value))[0];
  if (!match) throw new Error(`Could not find mounted button: ${JSON.stringify(attributes)}`);
  return match;
}

function dispatch(node: TestElement, eventName: string, fields: Record<string, unknown>): void {
  const handler = node.props[`on${eventName[0].toUpperCase()}${eventName.slice(1)}`];
  if (typeof handler !== 'function') throw new Error(`No ${eventName} handler on <${node.tag}>`);
  handler({ currentTarget: node, target: node, preventDefault: () => {}, ...fields });
}

function click(node: TestElement, detail = 0): void {
  dispatch(node, 'Click', { detail });
}

function pointer(node: TestElement, eventName: string, fields: { pointerId: number; timeStamp: number }): void {
  dispatch(node, eventName[0].toUpperCase() + eventName.slice(1), {
    button: 0,
    isPrimary: true,
    ...fields,
  });
}
