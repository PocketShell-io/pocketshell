/**
 * The Android platform instance behind the shared app: the native SSH
 * capability bound to core's ConnectionController, the phone's host source,
 * and the Android lifecycle. Created once at startup (main.ts); the shared
 * app reaches it only through `provideApi()`.
 */
import { App as CapacitorApp } from '@capacitor/app';
import { ConnectionController, type HostKeyTrustPin, type HostKeyTrustStore } from '@pocketshell/core';
import { loadSshTransportCapabilities, sshCapability } from '@/native/sshCapability';
import { gatewayPairings } from '@/native/gatewayPairing';
import { createAndroidPlatform, type AndroidLifecycle } from '@/platform/android/androidApi';
import { androidHosts, androidKeyManager } from '@/platform/android/hosts';
import { createAccountHostKeys } from '@/platform/android/accountHosts';
import { androidSync } from '@/platform/android/sync';
import type { ConnectionJournalEntry } from '@/platform/android/connectionHub';
import { createLocalTrustStore } from '@/platform/android/trustStore';
import { ADD_HOST_ROUTE, openAccountRoute, openAddHostRoute } from './router';
import { createAccountHostKeyPrompt } from './accountHostKey';
import { googleSync } from '@/native/googleSync';
import { vaultNative } from '@/native/vaultNative';
import { openExternalUrl } from '@/platform/androidAppInfo';
import { createAndroidVault } from '@/vault/androidVault';
import { requestVaultUnlock, type KeyVaultPlatform } from '@ui/app/keyVault';
import {
  createAndroidRememberApi,
  createAndroidVaultStore,
  createStoreVaultCore,
  VAULT_RESET_URL,
} from '@/vault/coreVault';

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

const baseSyncApi = androidSync().api();

/**
 * The account key vault (key-vault W4). One passphrase: unlocking the vault
 * also yields the account's sync passphrase (index `sync.passphrase`,
 * contract #2), which unlocks the account host list with no second prompt.
 */
export const vaultSync = { passphrase: '' };

const vaultScope = async (): Promise<string | null> => {
  const status = await googleSync.status();
  return status.signedIn ? status.email : null;
};

/** The one KeyVaultStore: core's shared unlock sheet and the dial path both drive it. */
const vaultStore = createAndroidVaultStore(googleSync);

/**
 * Core's shared vault UI (packages/ui): the unlock sheet AppRoot mounts,
 * remember on this device (sealed by the Android Keystore key), and "Forgot
 * it?" sending the user to the web's reset. Installed by main.ts next to
 * provideApi.
 */
export const keyVaultPlatform: KeyVaultPlatform = {
  store: vaultStore,
  remember: createAndroidRememberApi(vaultNative, vaultScope),
  resetUrl: VAULT_RESET_URL,
  openUrl: openExternalUrl,
};

export const androidVault = createAndroidVault({
  core: createStoreVaultCore(vaultStore),
  native: vaultNative,
  scope: vaultScope,
  askUnlock: (hostLabel) => requestVaultUnlock('connect', hostLabel),
  onUnlocked: async (syncPassphrase) => {
    if (!syncPassphrase) return;
    vaultSync.passphrase = syncPassphrase;
    await androidSync().unlock(syncPassphrase).catch(() => undefined);
  },
});

const syncApi: typeof baseSyncApi = {
  ...baseSyncApi,
  // Signing out locks the vault, wipes the held keys and forgets this
  // device's remembered vault key for every account.
  logout: async () => {
    vaultSync.passphrase = '';
    await androidVault.signedOut();
    await baseSyncApi.logout();
  },
};

// Core reads `sshCapability.gatewayTransport` from what the native plugin
// reports (#3086), and the Android boundary (`unsupportedTransportMessage`)
// admits a gateway host only on that same report, awaited at dial time.
const nativeTransports = loadSshTransportCapabilities();

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
  openAddHost: openAddHostRoute,
  accountHosts: createAccountHostKeys({ accountHosts: () => syncApi.accountHosts(), ask: accountHostKeyPrompt.ask }),
  gatewayTransport: async () => (await nativeTransports).gatewayTransport,
  gatewayPairings,
  vault: androidVault,
});

export type { HostKeyTrustPin };
export { androidHosts };
