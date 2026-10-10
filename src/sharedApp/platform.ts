/**
 * The Android platform instance behind the shared app: the native SSH
 * capability bound to core's ConnectionController, the phone's host source,
 * and the Android lifecycle. Created once at startup (main.ts); the shared
 * app reaches it only through `provideApi()`.
 */
import { App as CapacitorApp } from '@capacitor/app';
import { ConnectionController, type HostKeyTrustPin, type HostKeyTrustStore } from '@pocketshell/core';
import { loadSshTransportCapabilities, sshCapability } from '@/native/sshCapability';
import { createAndroidPlatform, type AndroidLifecycle } from '@/platform/android/androidApi';
import { androidHosts, androidKeyManager } from '@/platform/android/hosts';
import { createAccountHostKeys } from '@/platform/android/accountHosts';
import { androidSync } from '@/platform/android/sync';
import type { ConnectionJournalEntry } from '@/platform/android/connectionHub';
import { createLocalTrustStore } from '@/platform/android/trustStore';
import { ADD_HOST_ROUTE, openAccountRoute } from './router';
import { createAccountHostKeyPrompt } from './accountHostKey';

/** Mirrors the legacy screen's default; the shared settings store has no grace field yet (stage L1). */
const BACKGROUND_GRACE_MS = 60_000;

const capacitorLifecycle: AndroidLifecycle = {
  onActiveChange(handler) {
    const pending = CapacitorApp.addListener('appStateChange', ({ isActive }) => handler(isActive));
    return () => {
      void pending.then((listener) => listener.remove());
    };
  },
};

const trustStore: HostKeyTrustStore = createLocalTrustStore(window.localStorage);

/** How many controller snapshots the diagnostics journal keeps. */
const CONNECTION_JOURNAL_LIMIT = 500;

declare global {
  interface Window {
    /**
     * Bounded journal of controller snapshots per logical connection. Read by
     * the packaged shared-app journey to count recovery ladders and dials
     * against the real transport (#2954); nothing in the app reads it.
     */
    __pocketshellConnectionJournal?: ConnectionJournalEntry[];
  }
}

function journalConnection(entry: ConnectionJournalEntry): void {
  const journal = (window.__pocketshellConnectionJournal ??= []);
  journal.push(entry);
  if (journal.length > CONNECTION_JOURNAL_LIMIT) journal.splice(0, journal.length - CONNECTION_JOURNAL_LIMIT);
}

/** The key prompt for account hosts (#3063), rendered by AccountHostKeyGate.vue. */
export const accountHostKeyPrompt = createAccountHostKeyPrompt({ keys: androidKeyManager, hosts: androidHosts });

const syncApi = androidSync().api();

// Core reads `sshCapability.gatewayTransport` from what the native plugin
// reports (#3086). Gateway hosts stay refused at the Android boundary
// (`unsupportedTransportMessage`) until slice 3 lifts that behind this flag.
void loadSshTransportCapabilities();

export const androidPlatform = createAndroidPlatform({
  createController: () => new ConnectionController({ capability: sshCapability, trustStore }),
  hosts: androidHosts,
  lifecycle: capacitorLifecycle,
  backgroundGraceMs: () => BACKGROUND_GRACE_MS,
  addHostRoute: ADD_HOST_ROUTE,
  log: (entry) => console.info(`[pocketshell] ${entry.kind}: ${entry.message}`, entry.detail ?? ''),
  observeConnections: journalConnection,
  sync: syncApi,
  openAccount: openAccountRoute,
  accountHosts: createAccountHostKeys({ accountHosts: () => syncApi.accountHosts(), ask: accountHostKeyPrompt.ask }),
});

export type { HostKeyTrustPin };
export { androidHosts };
