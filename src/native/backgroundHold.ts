/**
 * Android's half of background grace (#3160): the native foreground-service
 * hold that keeps a backgrounded app's process and network alive while core's
 * scheduled grace closes are pending, and the network-return signal that lets
 * a given-up reconnect recover by itself.
 *
 * The grace POLICY (window length, what closes at the end, reconnect on
 * return) stays in the shared core's ConnectionController (D42). Native only
 * needs the user's window ahead of time: it opens the hold on the activity-stop
 * boundary, before the WebView's lifecycle event reaches core.
 */
import type { Plugin, PluginListenerHandle } from '@capacitor/core';
import { nativeSshCapability } from './sshCapability';

export interface BackgroundHoldPlugin extends Plugin {
  setBackgroundGrace(options: { requestId: string; graceMs: number }): Promise<{ requestId: string; graceMs: number }>;
  addListener(
    eventName: 'networkAvailable',
    listener: (event: { reason: string }) => void,
  ): Promise<PluginListenerHandle>;
}

export interface BackgroundHold {
  /** Push the user's grace window (ms) to the native hold; 0 turns it off. */
  setGrace(graceMs: number): Promise<void>;
  /** The network became usable again; returns an unsubscribe. */
  onNetworkAvailable(handler: () => void): () => void;
}

let sequence = 0;

export function createBackgroundHold(plugin: BackgroundHoldPlugin): BackgroundHold {
  return {
    async setGrace(graceMs) {
      const value = Number.isFinite(graceMs) ? Math.max(0, Math.round(graceMs)) : 0;
      const requestId = `background-grace-${++sequence}`;
      const reply = await plugin.setBackgroundGrace({ requestId, graceMs: value });
      if (reply.requestId !== requestId) throw new Error('The background hold answered a stale request.');
    },
    onNetworkAvailable(handler) {
      const pending = plugin.addListener('networkAvailable', () => handler());
      return () => {
        void pending.then((listener) => listener.remove()).catch(() => undefined);
      };
    },
  };
}

/** The SshCapability plugin owns the hold: it knows which connections are live. */
export const backgroundHold = createBackgroundHold(nativeSshCapability as unknown as BackgroundHoldPlugin);
