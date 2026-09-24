import { createRenderer, defineComponent, h, nextTick, ref, ssrContextKey } from 'vue';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ITheme } from '@xterm/xterm';
import TerminalViewport from '../../src/components/TerminalViewport.vue';
import type { TerminalResizeRequest } from '../../src/terminalGeometry';

const mountableTerminalViewport = TerminalViewport as unknown as { render?: () => ReturnType<typeof h> };
mountableTerminalViewport.render = () => h('div', { ref: 'terminalHost' });

const xtermState = vi.hoisted(() => ({ instances: [] as Array<Record<string, unknown>> }));

vi.mock('@xterm/xterm', () => ({
  Terminal: class {
    cols = 80;
    rows = 24;
    options: Record<string, unknown>;

    constructor(options: Record<string, unknown>) {
      this.options = options;
      xtermState.instances.push(this as unknown as Record<string, unknown>);
    }

    loadAddon() {}
    open() {}
    onRender() { return { dispose() {} }; }
    onWriteParsed() { return { dispose() {} }; }
    onData() { return { dispose() {} }; }
    write(_bytes: Uint8Array, callback?: () => void) { callback?.(); }
    clear() {}
    focus() {}
    scrollToBottom() {}
    dispose() {}
  },
}));

vi.mock('@xterm/addon-fit', () => ({
  FitAddon: class { fit() {} },
}));

interface HostNode {
  type: string;
  text?: string;
  props: Record<string, unknown>;
  children: HostNode[];
  parent: HostNode | null;
}

function hostNode(type: string, text?: string): HostNode {
  return { type, text, props: {}, children: [], parent: null };
}

const renderer = createRenderer<HostNode, HostNode>({
  createElement: (type) => hostNode(type),
  createText: (text) => hostNode('#text', text),
  createComment: (text) => hostNode('#comment', text),
  setText: (node, text) => { node.text = text; },
  setElementText: (node, text) => { node.text = text; node.children = []; },
  patchProp: (node, key, _previous, next) => { node.props[key] = next; },
  insert: (node, parent, anchor) => {
    if (node.parent) {
      const previousIndex = node.parent.children.indexOf(node);
      if (previousIndex >= 0) node.parent.children.splice(previousIndex, 1);
    }
    const anchorIndex = anchor ? parent.children.indexOf(anchor) : -1;
    if (anchorIndex < 0) parent.children.push(node);
    else parent.children.splice(anchorIndex, 0, node);
    node.parent = parent;
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

let nextFrameId = 1;
let pendingFrames: Map<number, FrameRequestCallback>;
let windowListeners: Map<string, Set<EventListener>>;
let mountedApps: Array<ReturnType<typeof renderer.createApp>> = [];

async function flushFrames(): Promise<void> {
  await nextTick();
  for (let round = 0; round < 10 && pendingFrames.size > 0; round += 1) {
    const frames = [...pendingFrames.values()];
    pendingFrames.clear();
    for (const callback of frames) callback(0);
    await nextTick();
  }
  expect(pendingFrames.size).toBe(0);
}

function sendWindowResize(): void {
  for (const listener of [...(windowListeners.get('resize') ?? [])]) listener(new Event('resize'));
}

describe('TerminalViewport resize failure across a route remount', () => {
  beforeEach(() => {
    xtermState.instances.length = 0;
    nextFrameId = 1;
    pendingFrames = new Map();
    windowListeners = new Map();
    const viewportListeners = new Map<string, Set<EventListener>>();
    const fakeWindow = {
      addEventListener: (type: string, listener: EventListener) => {
        const listeners = windowListeners.get(type) ?? new Set<EventListener>();
        listeners.add(listener);
        windowListeners.set(type, listeners);
      },
      removeEventListener: (type: string, listener: EventListener) => windowListeners.get(type)?.delete(listener),
      visualViewport: {
        addEventListener: (type: string, listener: EventListener) => {
          const listeners = viewportListeners.get(type) ?? new Set<EventListener>();
          listeners.add(listener);
          viewportListeners.set(type, listeners);
        },
        removeEventListener: (type: string, listener: EventListener) => viewportListeners.get(type)?.delete(listener),
      },
    };
    const fakeDocument = {
      visibilityState: 'visible',
      addEventListener() {},
      removeEventListener() {},
    };

    vi.stubGlobal('window', fakeWindow);
    vi.stubGlobal('document', fakeDocument);
    vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
      const id = nextFrameId++;
      pendingFrames.set(id, callback);
      return id;
    });
    vi.stubGlobal('cancelAnimationFrame', (id: number) => pendingFrames.delete(id));
    vi.stubGlobal('ResizeObserver', class {
      constructor(_callback: ResizeObserverCallback) {}
      observe() {}
      disconnect() {}
    });
  });

  afterEach(() => {
    for (const app of mountedApps) app.unmount();
    mountedApps = [];
    vi.unstubAllGlobals();
  });

  it('keeps delayed resize failures isolated after home → Settings → home and retries only after the current request fails', async () => {
    const root = hostNode('root');
    const requests: TerminalResizeRequest[] = [];
    const baseProps = {
      enabled: true,
      theme: {} as ITheme,
      fontFamily: 'monospace',
      fontSize: 13,
      onResize: (request: TerminalResizeRequest) => requests.push(request),
    };
    const resizeFailure = ref<TerminalResizeRequest | null>(null);
    const mountHome = () => {
      const home = defineComponent(() => () => h(mountableTerminalViewport as typeof TerminalViewport, {
        ...baseProps,
        resizeFailure: resizeFailure.value,
      }));
      const app = renderer.createApp(home);
      app.provide(ssrContextKey, { modules: new Set<string>() });
      app.mount(root);
      mountedApps.push(app);
      return app;
    };
    const fireOneFit = async () => {
      const countBefore = requests.length;
      sendWindowResize();
      await flushFrames();
      return requests.slice(countBefore);
    };

    // The first home mount starts a native resize that remains in flight while
    // Settings replaces the live home surface and unmounts TerminalViewport.
    const firstHome = mountHome();
    await flushFrames();
    const oldRequest = requests[0];
    expect(oldRequest).toMatchObject({ cols: 80, rows: 24 });
    expect(oldRequest.requestId).toEqual(expect.any(Number));
    firstHome.unmount();
    await nextTick();

    // Returning home mounts a fresh reporter for the same grid. Deliver the
    // old instance's delayed rejection after this replacement request exists.
    const secondHome = mountHome();
    await flushFrames();
    const replacementRequest = requests[1];
    expect(replacementRequest).toMatchObject({ cols: 80, rows: 24 });
    resizeFailure.value = oldRequest;
    await nextTick();
    expect(await fireOneFit()).toEqual([]);
    expect(replacementRequest.requestId).toBeGreaterThan(oldRequest.requestId!);

    // Only the active request's rejection releases an unchanged grid for one
    // later fit. That fit is coalesced again, so no automatic retry loop runs.
    resizeFailure.value = replacementRequest;
    await nextTick();
    const retry = await fireOneFit();
    expect(retry).toHaveLength(1);
    expect(retry[0]).toMatchObject({ cols: 80, rows: 24 });
    expect(retry[0]!.requestId).toBeGreaterThan(replacementRequest.requestId!);
    expect(await fireOneFit()).toEqual([]);
  });
});
