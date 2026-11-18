import { createRenderer, defineComponent, h, nextTick, type App } from 'vue';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { HOTKEY_CTRL_PAGE_ROWS } from '@pocketshell/core';
import FastKeysDock from '../../src/sharedApp/extensions/FastKeysDock.vue';
import fastKeysDockSource from '../../src/sharedApp/extensions/FastKeysDock.vue?raw';
import sharedMainSource from '../../src/sharedApp/main.ts?raw';
import {
  FAST_KEYS_DOCK_CELLS,
  FAST_KEYS_DOCK_HEIGHT_PX,
  FAST_KEYS_DOCK_PAGES,
  FAST_KEYS_MIN_CAP_PX,
  FAST_KEYS_MIN_TARGET_PX,
  fastKeysDockPage,
  softKeyboardUp,
  terminalKeyInput,
  type FastKeysDockPage,
} from '../../src/sharedApp/extensions/fastKeysDockModel';

// CSS ?raw imports are empty under vitest's CSS handling; read the file itself.
const phoneCss = readFileSync(path.resolve(process.cwd(), 'src/sharedApp/phone.css'), 'utf8');

const hex = (text: string): string => [...text].map((c) => c.charCodeAt(0).toString(16).padStart(2, '0')).join(' ');

/**
 * #3158: the shared shell's fast-key dock. Approval prompts ("1. Yes 2. No ·
 * Esc to cancel · Tab to amend") must be answerable from the phone with no
 * hardware keyboard, so every key's exact bytes, the digits, the Ctrl
 * catalog, focus retention and the dock's registration are pinned here; the
 * packaged J30 journey proves the same bytes reach a real host PTY.
 */
/**
 * The window the dock sees: a width that core's layout seam classifies through
 * matchMedia (its real `max-width` queries), and a viewport height that the
 * keyboard shrinks. Listeners are kept so a test can resize across a breakpoint.
 */
function stubWindow({ width = 411, keyboardUp = false }: { width?: number; keyboardUp?: boolean } = {}) {
  const lists: Array<{ query: string; list: { matches: boolean }; listeners: Array<(event: { matches: boolean }) => void> }> = [];
  const matches = (query: string, w: number): boolean => {
    const max = /max-width:\s*(\d+)px/.exec(query);
    return max ? w <= Number(max[1]) : false;
  };
  const win = {
    screen: { height: 914 },
    innerHeight: keyboardUp ? 695 : 914,
    visualViewport: undefined,
    matchMedia: (query: string) => {
      const listeners: Array<(event: { matches: boolean }) => void> = [];
      const list = {
        matches: matches(query, width),
        addEventListener: (_type: string, listener: (event: { matches: boolean }) => void) => listeners.push(listener),
        removeEventListener: () => undefined,
      };
      lists.push({ query, list, listeners });
      return list;
    },
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
  };
  vi.stubGlobal('window', win);
  return {
    resize(next: number): void {
      for (const entry of lists) {
        entry.list.matches = matches(entry.query, next);
        for (const listener of entry.listeners) listener({ matches: entry.list.matches });
      }
    },
  };
}

describe('shared-shell fast-key dock', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('lays out six fixed cells, page key last: Esc Tab Up Down Enter More, then Shift+Tab Left Right Ctrl 123 Keys, with exact bytes', () => {
    expect(fastKeysDockPage('keys').map((key) => key.id)).toEqual([
      'escape', 'tab', 'arrow-up', 'arrow-down', 'enter', 'page-more',
    ]);
    expect(fastKeysDockPage('more').map((key) => key.id)).toEqual([
      'shift-tab', 'arrow-left', 'arrow-right', 'page-ctrl', 'page-digits', 'page-keys',
    ]);
    expect(hex(terminalKeyInput('escape'))).toBe('1b');
    expect(hex(terminalKeyInput('tab'))).toBe('09');
    expect(hex(terminalKeyInput('shift-tab'))).toBe('1b 5b 5a');
    expect(hex(terminalKeyInput('enter'))).toBe('0d');
    expect(hex(terminalKeyInput('arrow-up'))).toBe('1b 5b 41');
    expect(hex(terminalKeyInput('arrow-down'))).toBe('1b 5b 42');
    expect(hex(terminalKeyInput('arrow-right'))).toBe('1b 5b 43');
    expect(hex(terminalKeyInput('arrow-left'))).toBe('1b 5b 44');
  });

  it('fits every page in the six cells a 360dp phone has beside the side rail, the page key always last', () => {
    // 360dp minus the shared shell's side rail leaves 303 CSS px (J30/smoke measure it).
    expect(FAST_KEYS_DOCK_CELLS).toBe(6);
    expect(FAST_KEYS_DOCK_CELLS * FAST_KEYS_MIN_TARGET_PX).toBeLessThanOrEqual(303);
    for (const page of FAST_KEYS_DOCK_PAGES) {
      const keys = fastKeysDockPage(page);
      expect(keys.length, page).toBeLessThanOrEqual(FAST_KEYS_DOCK_CELLS);
      expect(keys.at(-1)?.kind, `${page}: last cell is its page key`).toBe('page');
      expect(new Set(keys.map((key) => key.id)).size, `${page}: unique keys`).toBe(keys.length);
    }
  });

  it('reaches every page by taps from the terminal keys, and every page leads back to them', () => {
    const links = (page: FastKeysDockPage): FastKeysDockPage[] =>
      fastKeysDockPage(page).flatMap((key) => (key.kind === 'page' ? [key.page] : []));
    const reach = (from: FastKeysDockPage): Set<FastKeysDockPage> => {
      const seen = new Set<FastKeysDockPage>([from]);
      const queue = [from];
      while (queue.length) for (const next of links(queue.shift()!)) if (!seen.has(next)) { seen.add(next); queue.push(next); }
      return seen;
    };
    expect([...reach('keys')].sort()).toEqual([...FAST_KEYS_DOCK_PAGES].sort());
    for (const page of FAST_KEYS_DOCK_PAGES) expect(reach(page).has('keys'), page).toBe(true);
  });

  it('reaches digits 1-9 and 0 through More, 123', () => {
    expect(fastKeysDockPage('more').find((key) => key.id === 'page-digits')).toMatchObject({ kind: 'page', page: 'digits', label: '123' });
    const digits = [...fastKeysDockPage('digits'), ...fastKeysDockPage('digits-2')].filter((key) => key.kind === 'digit');
    expect(digits.map((key) => key.label)).toEqual(['1', '2', '3', '4', '5', '6', '7', '8', '9', '0']);
    for (const key of digits) {
      if (key.kind === 'digit') expect(hex(key.text)).toBe((0x30 + Number(key.label)).toString(16));
    }
  });

  it('spreads the whole core Ctrl catalog over Ctrl pages of five, Ctrl+C and Ctrl+D first', () => {
    const ctrlPages = FAST_KEYS_DOCK_PAGES.filter((page) => page === 'ctrl' || page.startsWith('ctrl-'));
    const ctrl = ctrlPages.flatMap((page) => fastKeysDockPage(page).filter((key) => key.kind === 'terminal'));
    const catalog = HOTKEY_CTRL_PAGE_ROWS.flat().map((key) => key.id);
    expect(ctrl.map((key) => key.id).sort()).toEqual([...catalog].sort());
    expect(ctrl.slice(0, 2).map((key) => key.id)).toEqual(['ctrl-c', 'ctrl-d']);
    expect(ctrl.filter((key) => key.kind === 'terminal' && key.holdable).map((key) => key.id)).toEqual(['ctrl-c', 'ctrl-d']);
    expect(fastKeysDockPage(ctrlPages.at(-1)!).at(-1)).toMatchObject({ kind: 'page', page: 'keys' });
  });

  it('sends the exact approval-prompt bytes (2, Esc, Shift+Tab) through the dock context and keeps focus', async () => {
    stubWindow({ keyboardUp: true });
    const mounted = mountDock();
    try {
      const dock = findByTestId(mounted.root, 'fast-keys-dock')!;
      expect(dock.props.role).toBe('toolbar');
      expect(dock.props['data-page']).toBe('keys');
      const go = async (pageKey: string, page: string) => {
        tap(findButton(mounted.root, pageKey));
        await nextTick();
        expect(findByTestId(mounted.root, 'fast-keys-dock')!.props['data-page']).toBe(page);
      };
      await go('page-more', 'more');
      await go('page-digits', 'digits');
      tap(findButton(mounted.root, 'digit-2'));
      await go('page-digits-2', 'digits-2');
      await go('page-keys', 'keys');
      tap(findButton(mounted.root, 'escape'));
      await go('page-more', 'more');
      tap(findButton(mounted.root, 'shift-tab'));
      expect(mounted.sent.map(hex)).toEqual(['32', '1b', '1b 5b 5a']);
      // Keyboard up: every pointerdown was cancelled, so no key pulls focus
      // off the terminal and the open soft keyboard stays open.
      expect(mounted.preventedPointerDowns()).toBe(8);
      expect(findButton(mounted.root, 'shift-tab').blurs).toBe(0);
    } finally {
      mounted.app.unmount();
    }
  });

  it('resolves Ctrl+C as one byte on a tap and the atomic double on a hold, like the legacy shell', async () => {
    const mounted = mountDock();
    try {
      tap(findButton(mounted.root, 'page-more'));
      await nextTick();
      tap(findButton(mounted.root, 'page-ctrl'));
      await nextTick();
      const ctrlC = findButton(mounted.root, 'ctrl-c');
      pointer(ctrlC, 'Pointerdown', 1, 1000);
      pointer(ctrlC, 'Pointerup', 1, 1100);
      dispatch(ctrlC, 'Click', { detail: 1 });
      pointer(ctrlC, 'Pointerdown', 2, 2000);
      pointer(ctrlC, 'Pointerup', 2, 2600);
      dispatch(ctrlC, 'Click', { detail: 1 });
      pointer(ctrlC, 'Pointerdown', 3, 3000);
      pointer(ctrlC, 'Pointercancel', 3, 3100);
      tap(findButton(mounted.root, 'ctrl-z'));
      // A keyboard / assistive-technology activation (no pointer press first) is one tap.
      dispatch(ctrlC, 'Click', { detail: 0 });
      expect(mounted.sent.map(hex)).toEqual(['03', '03 03', '1a', '03']);
    } finally {
      mounted.app.unmount();
    }
  });

  it('sends one Ctrl+D for a touch tap whose click arrives with detail 0, as Android WebView delivers it', async () => {
    const mounted = mountDock();
    try {
      tap(findButton(mounted.root, 'page-more'));
      await nextTick();
      tap(findButton(mounted.root, 'page-ctrl'));
      await nextTick();
      const ctrlD = findButton(mounted.root, 'ctrl-d');
      // The emulator's real sequence: pointerdown (cancelled), pointerup, then click with detail 0.
      pointer(ctrlD, 'Pointerdown', 4, 5000);
      pointer(ctrlD, 'Pointerup', 4, 5050);
      pointer(ctrlD, 'Lostpointercapture', 4, 5051);
      dispatch(ctrlD, 'Click', { detail: 0 });
      expect(mounted.sent.map(hex)).toEqual(['04']);
    } finally {
      mounted.app.unmount();
    }
  });

  it('lets a key take focus while the soft keyboard is down, so a tap cannot bring it up', () => {
    stubWindow({ keyboardUp: false });
    const mounted = mountDock();
    try {
      const escape = findButton(mounted.root, 'escape');
      tap(escape);
      expect(mounted.sent.map(hex)).toEqual(['1b']);
      expect(mounted.preventedPointerDowns()).toBe(0);
      // ...and gives focus up again, so a hardware Enter cannot re-fire it.
      expect(escape.blurs).toBe(1);
    } finally {
      mounted.app.unmount();
    }
  });

  it('reads the soft keyboard as up only when the viewport is more than 150px shorter than the screen', () => {
    expect(softKeyboardUp(914, 695)).toBe(true);
    expect(softKeyboardUp(914, 914)).toBe(false);
    expect(softKeyboardUp(914, 764)).toBe(false);
    expect(softKeyboardUp(undefined, 695)).toBe(false);
  });

  it('is contributed to the shared shell terminal.dock slot', () => {
    expect(sharedMainSource).toContain("import FastKeysDock from './extensions/FastKeysDock.vue';");
    expect(sharedMainSource).toContain("'terminal.dock': [{ id: 'fast-keys', component: FastKeysDock }],");
  });

  it('keeps every key a 48px cell with a 44px keycap in a row that cannot scroll, and lifts the prompt layer off the dock only at phone width', () => {
    expect(FAST_KEYS_MIN_TARGET_PX).toBe(48);
    expect(FAST_KEYS_MIN_CAP_PX).toBe(44);
    expect(FAST_KEYS_DOCK_HEIGHT_PX).toBe(48);
    expect(fastKeysDockSource).toContain('.fast-keys-dock {\n  display: grid;\n  grid-template-columns: repeat(6, minmax(48px, 1fr));\n  flex: 0 0 48px;\n  height: 48px;\n  min-width: 0;\n  overflow: hidden;');
    expect(fastKeysDockSource).toContain('.fast-keys-dock__key {\n  display: inline-flex;\n  min-width: 48px;\n  height: 100%;');
    expect(fastKeysDockSource).toContain('.fast-keys-dock__cap {\n  display: inline-flex;\n  width: 100%;\n  min-width: 44px;\n  height: 44px;');
    // #3158 round 6: nothing in the dock scrolls sideways.
    expect(fastKeysDockSource).not.toMatch(/overflow-x:\s*(auto|scroll)|touch-action:\s*pan-x|scrollLeft/);
    expect(phoneCss).toContain("@media (max-width: 640px) {\n  .workspace-body:has(.terminal-area [data-testid='fast-keys-dock']) > .composer-dock {\n    bottom: calc(var(--composer-inset) + 48px);\n  }\n}");
  });

  it('renders nothing outside the compact layout class and follows a resize across the breakpoint (D44)', async () => {
    for (const width of [1280, 900, 641]) {
      vi.unstubAllGlobals();
      stubWindow({ width });
      const wide = mountDock();
      try {
        expect(findByTestId(wide.root, 'fast-keys-dock'), `width ${width}`).toBeUndefined();
        expect(findAll(wide.root, (node) => node.tag === 'button')).toHaveLength(0);
      } finally {
        wide.app.unmount();
      }
    }
    vi.unstubAllGlobals();
    const viewport = stubWindow({ width: 411 });
    const phone = mountDock();
    try {
      expect(findByTestId(phone.root, 'fast-keys-dock')).toBeDefined();
      viewport.resize(1280);
      await nextTick();
      expect(findByTestId(phone.root, 'fast-keys-dock')).toBeUndefined();
      viewport.resize(360);
      await nextTick();
      expect(findByTestId(phone.root, 'fast-keys-dock')).toBeDefined();
    } finally {
      phone.app.unmount();
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
  blurs: number;
  blur(): void;
  getBoundingClientRect(): { left: number; top: number; width: number; height: number };
};
type TestText = { text: string; parent: TestElement | null; kind: 'text' | 'comment' };
type TestNode = TestElement | TestText;

function element(tag: string): TestElement {
  const node: TestElement = {
    tag, props: {}, children: [], parent: null, text: '', blurs: 0,
    setPointerCapture: () => {},
    blur: () => { node.blurs += 1; },
    getBoundingClientRect: () => ({ left: 0, top: 0, width: 0, height: 0 }),
  };
  return node;
}

const renderer = createRenderer<TestNode, TestElement>({
  createElement: (tag) => element(tag),
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

let prevented = 0;

function mountDock() {
  if (typeof window === 'undefined' || !('matchMedia' in (window as object))) stubWindow();
  const root: TestElement = element('root');
  const sent: string[] = [];
  prevented = 0;
  const context = { sessionKey: 's', sessionName: 'main', sendInput: (data: string) => sent.push(data) };
  const Host = defineComponent({ setup: () => () => h(FastKeysDock, { context, holdThresholdMs: 500 }) });
  const app = renderer.createApp(Host) as App;
  app.mount(root as unknown as Element);
  return { root, app, sent, preventedPointerDowns: () => prevented };
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

function findButton(root: TestElement, keyId: string): TestElement {
  const match = findAll(root, (node) => node.tag === 'button' && node.props['data-key-id'] === keyId)[0];
  if (!match) throw new Error(`no dock button ${keyId}`);
  return match;
}

function dispatch(node: TestElement, eventName: string, fields: Record<string, unknown>): void {
  const handler = node.props[`on${eventName}`];
  if (typeof handler !== 'function') return;
  handler({ currentTarget: node, target: node, preventDefault: () => { prevented += 1; }, button: 0, isPrimary: true, ...fields });
}

function pointer(node: TestElement, eventName: string, pointerId: number, timeStamp: number): void {
  dispatch(node, eventName, { pointerId, timeStamp });
}

/** A touch tap: pointerdown, pointerup, then the click it produces (detail 1). */
function tap(node: TestElement): void {
  pointer(node, 'Pointerdown', 7, 0);
  pointer(node, 'Pointerup', 7, 50);
  dispatch(node, 'Click', { detail: 1 });
}
