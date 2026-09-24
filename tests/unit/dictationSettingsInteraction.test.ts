import { compile, createRenderer, getCurrentInstance, nextTick, ssrContextKey, type VNode } from 'vue';
import { createPinia } from 'pinia';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AppIcon } from '@pocketshell/ui';
import { SETTINGS_STORAGE_KEY, type SettingsStorage } from '../../src/stores/appSettings';
import { useNavigationStore } from '../../src/stores/navigation';
import SettingsScreen from '../../src/components/SettingsScreen.vue';
import settingsScreenSource from '../../src/components/SettingsScreen.vue?raw';

const settingsTemplate = settingsScreenSource.match(/<template>([\s\S]*)<\/template>/)?.[1];
if (!settingsTemplate) throw new Error('SettingsScreen production template is missing');
const compiledSettingsTemplate = compile(settingsTemplate, { hoistStatic: false }) as unknown as (
  context: object,
  cache: unknown[],
  props: object,
  setup: object,
  data: object,
  options: object,
) => VNode;

class MemoryStorage implements SettingsStorage {
  values = new Map<string, string>();
  getItem(key: string) { return this.values.get(key) ?? null; }
  setItem(key: string, value: string) { this.values.set(key, value); }
}

interface HostNode {
  type: string;
  props: Record<string, unknown>;
  children: HostNode[];
  parent?: HostNode;
  text?: string;
  value?: string;
  listeners: Map<string, EventListener[]>;
  addEventListener(type: string, listener: EventListener): void;
  removeEventListener(type: string, listener: EventListener): void;
  dispatchEvent(type: string, event: Event): void;
}

function node(type: string, text = ''): HostNode {
  return {
    type,
    props: {},
    children: [],
    text,
    listeners: new Map(),
    addEventListener(type, listener) {
      const registered = this.listeners.get(type) ?? [];
      registered.push(listener);
      this.listeners.set(type, registered);
    },
    removeEventListener(type, listener) {
      this.listeners.set(type, (this.listeners.get(type) ?? []).filter((entry) => entry !== listener));
    },
    dispatchEvent(type, event) {
      for (const listener of this.listeners.get(type) ?? []) listener(event);
    },
  };
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

function descendants(root: HostNode): HostNode[] {
  return [root, ...root.children.flatMap(descendants)];
}

function textContent(root: HostNode): string {
  return `${root.text ?? ''}${root.children.map(textContent).join('')}`;
}

const productionSettingsScreen = {
  ...SettingsScreen,
  components: { AppIcon },
  render() {
    const instance = getCurrentInstance() as unknown as { setupState?: object; renderCache?: unknown[] } | null;
    const state = instance?.setupState ?? {};
    const cache = instance?.renderCache ?? [];
    return compiledSettingsTemplate(state, cache, state, state, state, state);
  },
};

describe('mounted dictation settings controls', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('binds the production Voice template to accessible persisted language and silence controls', async () => {
    const storage = new MemoryStorage();
    vi.stubGlobal('localStorage', storage);
    vi.stubGlobal('document', { activeElement: null });
    const pinia = createPinia();
    const navigation = useNavigationStore(pinia);
    const root = node('root');
    const app = renderer.createApp(productionSettingsScreen);
    app.use(pinia);
    app.provide(ssrContextKey, { modules: new Set<string>() });
    app.mount(root);
    await nextTick();

    navigation.open('settings-voice');
    await nextTick();

    const language = findByTestId(root, 'setting-dictation-language');
    const silence = findByTestId(root, 'setting-dictation-silence');
    expect(findByTestId(root, 'voice-settings-screen')).toBeDefined();
    expect(language?.props.type).toBe('text');
    expect(language?.props['aria-labelledby']).toBe('dictation-language-label');
    expect(language?.props['aria-describedby']).toBe('dictation-language-help');
    expect(language?.props['aria-invalid']).toBe('false');
    expect(language?.value).toBe('auto');
    expect(silence?.props.type).toBe('range');
    expect(silence?.props.min).toBe('2');
    expect(silence?.props.max).toBe('60');
    expect(silence?.props.step).toBe('1');
    expect(silence?.props['aria-label']).toBe('Recognition silence window in seconds');
    expect(silence?.props['aria-valuetext']).toBe('4 seconds');
    expect(findByTestId(root, 'dictation-silence-value')).toBeDefined();
    expect(textContent(findByTestId(root, 'dictation-silence-value')!)).toBe('4 seconds');
    expect(settingsScreenSource).toMatch(/\.dictation-language-input\s*\{[^}]*min-height:\s*48px/s);
    expect(settingsScreenSource).toMatch(/\.dictation-silence-range\s*\{[^}]*min-height:\s*48px/s);

    language!.value = 'de-DE';
    language!.dispatchEvent('input', { target: language } as unknown as Event);
    (language?.props.onChange as (event: Event) => void)({ target: { value: 'de-DE' } } as unknown as Event);
    (silence?.props.onInput as (event: Event) => void)({ target: { value: '9' } } as unknown as Event);
    (silence?.props.onChange as (event: Event) => void)({ target: { value: '9' } } as unknown as Event);
    await nextTick();

    expect(JSON.parse(storage.getItem(SETTINGS_STORAGE_KEY) ?? '{}')).toMatchObject({
      dictationLanguageTag: 'de-DE',
      dictationSilenceWindowMs: 9_000,
    });

    const voiceLabel = descendants(root).find((candidate) => candidate.type === 'label'
      && textContent(candidate).includes('Dictation language'));
    expect(voiceLabel).toBeDefined();
    expect(descendants(root).find((candidate) => candidate.props.id === 'dictation-language-help')).toBeDefined();

    language!.value = 'en_US';
    language!.dispatchEvent('input', { target: language } as unknown as Event);
    (language?.props.onChange as (event: Event) => void)({ target: { value: 'en_US' } } as unknown as Event);
    await nextTick();
    const invalidLanguage = findByTestId(root, 'setting-dictation-language');
    expect(invalidLanguage?.props['aria-invalid']).toBe('true');
    expect(invalidLanguage?.props['aria-describedby']).toBe('dictation-language-help dictation-language-error');
    expect(descendants(root).find((candidate) => candidate.props.id === 'dictation-language-error')?.props.role).toBe('alert');
    expect(JSON.parse(storage.getItem(SETTINGS_STORAGE_KEY) ?? '{}')).toMatchObject({
      dictationLanguageTag: 'de-DE',
      dictationSilenceWindowMs: 9_000,
    });
    app.unmount();
  });
});
