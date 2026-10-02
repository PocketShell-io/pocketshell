/**
 * Browser dev mode (#3022): a fake Capacitor Android bridge.
 *
 * The Android app talks to its native plugins through Capacitor's plugin
 * proxy, which on a device routes every call to `Capacitor.nativePromise` /
 * `Capacitor.nativeCallback` (installed by the WebView's native-bridge.js) for
 * each plugin named in `Capacitor.PluginHeaders`. This module installs those
 * same globals in a plain browser, backed by in-page implementations, before
 * `@capacitor/core` loads. The app code is unchanged: it still calls
 * `registerPlugin('SshCapability')` and goes through the real proxy.
 *
 * It is only ever loaded by the `mock`/`live` Vite dev modes (see
 * vite.config.ts `devBrowserShell`); nothing in the production entry imports
 * it, and scripts/check-no-dev-shims.py proves its absence from the APK.
 */

/** Marker string the production-asset check looks for. Keep it unique. */
export const DEV_BROWSER_SHIM_MARKER = 'pocketshell-dev-browser-shim';

export type DevPluginMethod = (options: Record<string, unknown>) => unknown;

export interface DevPlugin {
  /** Promise-returning plugin methods by name. */
  methods: Record<string, DevPluginMethod>;
}

export type DevPluginSet = Record<string, DevPlugin>;

type ListenerCallback = (data: unknown, error?: unknown) => void;

interface Listener {
  plugin: string;
  eventName: string;
  callback: ListenerCallback;
}

/** The error shape Capacitor's native bridge rejects with (CapacitorException). */
export class DevPluginError extends Error {
  readonly code: string;
  readonly data: Record<string, unknown>;

  constructor(message: string, code = 'UNAVAILABLE', data: Record<string, unknown> = {}) {
    super(message);
    this.name = 'DevPluginError';
    this.code = code;
    this.data = data;
  }
}

export interface PluginHeader {
  name: string;
  methods: Array<{ name: string; rtype: 'promise' | 'callback' }>;
}

export interface FakeNativeBridge {
  headers: PluginHeader[];
  nativePromise(pluginName: string, methodName: string, options?: Record<string, unknown>): Promise<unknown>;
  nativeCallback(pluginName: string, methodName: string, options: Record<string, unknown> | undefined, callback: ListenerCallback): string;
  /** Deliver a plugin event to every listener registered for it. Returns the listener count. */
  emit(pluginName: string, eventName: string, data: unknown): number;
  listenerCount(pluginName: string, eventName?: string): number;
}

/** Turn whatever a dev method threw into Capacitor's `{ message, code, data }` rejection. */
export function toBridgeError(error: unknown): DevPluginError {
  if (error instanceof DevPluginError) return error;
  if (typeof error === 'object' && error !== null) {
    const candidate = error as { message?: unknown; code?: unknown; data?: unknown };
    return new DevPluginError(
      typeof candidate.message === 'string' ? candidate.message : 'Dev plugin call failed',
      typeof candidate.code === 'string' ? candidate.code : 'UNAVAILABLE',
      typeof candidate.data === 'object' && candidate.data !== null ? candidate.data as Record<string, unknown> : {},
    );
  }
  return new DevPluginError(String(error));
}

export function createFakeNativeBridge(plugins: DevPluginSet): FakeNativeBridge {
  const listeners = new Map<string, Listener>();
  let nextCallbackId = 1;

  const headers: PluginHeader[] = Object.entries(plugins).map(([name, plugin]) => ({
    name,
    methods: [
      ...Object.keys(plugin.methods).map((method) => ({ name: method, rtype: 'promise' as const })),
      { name: 'addListener', rtype: 'callback' as const },
      { name: 'removeListener', rtype: 'promise' as const },
      { name: 'removeAllListeners', rtype: 'promise' as const },
    ],
  }));

  return {
    headers,
    async nativePromise(pluginName, methodName, options = {}) {
      if (methodName === 'removeListener') {
        const callbackId = String(options.callbackId ?? '');
        listeners.delete(callbackId);
        return undefined;
      }
      if (methodName === 'removeAllListeners') {
        for (const [id, listener] of listeners) if (listener.plugin === pluginName) listeners.delete(id);
        return undefined;
      }
      const plugin = plugins[pluginName];
      const method = plugin?.methods[methodName];
      if (!method) {
        throw new DevPluginError(`"${pluginName}.${methodName}()" is not implemented in browser dev mode`, 'UNIMPLEMENTED');
      }
      try {
        // Structured-clone the arguments like the real bridge serialises them.
        return await method(JSON.parse(JSON.stringify(options ?? {})) as Record<string, unknown>);
      } catch (error) {
        throw toBridgeError(error);
      }
    },
    nativeCallback(pluginName, methodName, options, callback) {
      if (methodName !== 'addListener') {
        throw new DevPluginError(`"${pluginName}.${methodName}()" callbacks are not supported in browser dev mode`, 'UNIMPLEMENTED');
      }
      const eventName = String(options?.eventName ?? '');
      const callbackId = `dev-${nextCallbackId++}`;
      listeners.set(callbackId, { plugin: pluginName, eventName, callback });
      return callbackId;
    },
    emit(pluginName, eventName, data) {
      let delivered = 0;
      for (const listener of [...listeners.values()]) {
        if (listener.plugin !== pluginName || listener.eventName !== eventName) continue;
        delivered += 1;
        try {
          listener.callback(JSON.parse(JSON.stringify(data ?? null)));
        } catch (error) {
          console.error(`[dev] ${pluginName} ${eventName} listener failed`, error);
        }
      }
      return delivered;
    },
    listenerCount(pluginName, eventName) {
      return [...listeners.values()].filter((listener) =>
        listener.plugin === pluginName && (eventName === undefined || listener.eventName === eventName)).length;
    },
  };
}

interface CapacitorGlobalTarget {
  androidBridge?: unknown;
  Capacitor?: Record<string, unknown>;
}

/**
 * Make `@capacitor/core` see an Android WebView: `androidBridge` selects the
 * android platform, and the plugin headers route each named plugin to this
 * bridge. Must run before `@capacitor/core` is first evaluated.
 */
export function installFakeNativeBridge(target: CapacitorGlobalTarget, bridge: FakeNativeBridge): void {
  if (target.Capacitor && typeof target.Capacitor.registerPlugin === 'function') {
    throw new Error('Browser dev mode must install before @capacitor/core loads.');
  }
  target.androidBridge = { postMessage: () => undefined, [DEV_BROWSER_SHIM_MARKER]: true };
  target.Capacitor = {
    ...(target.Capacitor ?? {}),
    PluginHeaders: bridge.headers,
    nativePromise: bridge.nativePromise,
    nativeCallback: bridge.nativeCallback,
    DEBUG: false,
    isLoggingEnabled: false,
  };
}
