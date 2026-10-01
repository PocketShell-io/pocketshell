import { Capacitor, registerPlugin } from '@capacitor/core';

export interface BridgeReadyPlugin {
  ping(options: { requestId: string }): Promise<{ requestId: string }>;
}

export const bridgeReady = registerPlugin<BridgeReadyPlugin>('BridgeReady');

export interface BridgeWarmUp {
  /** Pings sent before one was answered (1 = the first ping's reply arrived). */
  attempts: number;
  answered: boolean;
  startedAt: number;
  answeredAt?: number;
}

/** Settle with `undefined` after `ms` instead of waiting forever. */
export function withTimeout<T>(promise: Promise<T>, ms: number): Promise<T | undefined> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => resolve(undefined), ms);
    promise.then((value) => { clearTimeout(timer); resolve(value); }, (error: unknown) => { clearTimeout(timer); reject(error); });
  });
}

/**
 * Make the page's first native call a sacrificial ping, retried until it is
 * answered (see BridgeReadyPlugin.java: Capacitor can deliver the reply to a
 * reloaded page's first call to the previous document). Call it before any
 * other plugin call on the page; later calls use the page's own channel.
 */
export async function warmUpBridge(options: {
  plugin?: BridgeReadyPlugin;
  attempts?: number;
  timeoutMs?: number;
  native?: boolean;
  now?: () => number;
} = {}): Promise<BridgeWarmUp> {
  const now = options.now ?? Date.now;
  const state: BridgeWarmUp = { attempts: 0, answered: false, startedAt: now() };
  if (!(options.native ?? Capacitor.isNativePlatform())) return { ...state, answered: true, answeredAt: state.startedAt };
  const plugin = options.plugin ?? bridgeReady;
  const maxAttempts = options.attempts ?? 4;
  for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
    state.attempts = attempt;
    const requestId = `warmup-${state.startedAt.toString(36)}-${attempt}`;
    try {
      const answer = await withTimeout(plugin.ping({ requestId }), options.timeoutMs ?? 1_500);
      if (answer?.requestId === requestId) {
        state.answered = true;
        state.answeredAt = now();
        return state;
      }
    } catch {
      // A rejected ping still proves the channel; try once more to be sure.
    }
  }
  return state;
}

let warmUp: Promise<BridgeWarmUp> | null = null;

/** Start (once per page) and return the page's bridge warm-up. */
export function bridgeWarmUp(): Promise<BridgeWarmUp> {
  return (warmUp ??= warmUpBridge());
}
