/**
 * The Android platform instance behind the shared app: the native SSH
 * capability bound to core's ConnectionController, the phone's host source,
 * and the Android lifecycle. Created once at startup (main.ts); the shared
 * app reaches it only through `provideApi()`.
 */
import { App as CapacitorApp } from '@capacitor/app';
import { ConnectionController, type HostKeyTrustPin, type HostKeyTrustStore } from '@pocketshell/core';
import { loadSshTransportCapabilities, sshCapability } from '@/native/sshCapability';
import { gatewayPairings, nativeGatewayPairing } from '@/native/gatewayPairing';
import { sshKeyVault } from '@/native/sshKeyVault';
import { createAndroidGatewayDirectory } from '@/platform/android/gatewayDirectory';
import { createAndroidPlatform, type AndroidLifecycle } from '@/platform/android/androidApi';
import { androidHosts, androidKeyManager } from '@/platform/android/hosts';
import { createAccountHostKeys } from '@/platform/android/accountHosts';
import { androidSync } from '@/platform/android/sync';
import type { ConnectionJournalEntry } from '@/platform/android/connectionHub';
import { createLocalTrustStore } from '@/platform/android/trustStore';
import { ADD_HOST_ROUTE, openAccountRoute, openAddHostRoute } from './router';
import { createAccountHostKeyPrompt } from './accountHostKey';
import { createAndroidTrustedHosts } from '@/platform/android/trustedHosts';
import { googleSync, verifiedGoogleAccountId } from '@/native/googleSync';
import { vaultNative } from '@/native/vaultNative';
import { openExternalUrl } from '@/platform/androidAppInfo';
import { createAndroidVault } from '@/vault/androidVault';
import { requestVaultUnlock, useKeyVault, type KeyVaultPlatform } from '@ui/app/keyVault';
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
  if (useKeyVault()?.view.localOnly) return null;
  const status = await googleSync.status();
  return status.signedIn ? status.email : null;
};

/** The one KeyVaultStore: core's shared unlock sheet and the dial path both drive it. */
let vaultAccount: string | null = null;
let vaultPreparation = 0;
const vaultStore = createAndroidVaultStore(googleSync, () => vaultAccount);

/**
 * Core's shared vault UI (packages/ui): the unlock sheet AppRoot mounts,
 * remember on this device (sealed by the Android Keystore key), and "Forgot
 * it?" sending the user to the web's reset. Installed by main.ts next to
 * provideApi.
 */
const trustedHosts = createAndroidTrustedHosts({ store: vaultStore, accountId: verifiedGoogleAccountId,
  native: nativeGatewayPairing, hosts: androidHosts });
export const keyVaultPlatform: KeyVaultPlatform = {
  accountId: verifiedGoogleAccountId,
  canCreate: true,
  cancelAccountPreparation: () => {vaultPreparation++;},
  async prepareAccount() {
    const started = ++vaultPreparation;
    const current = () => {if(started !== vaultPreparation) throw new Error('Account setup was cancelled.');};
    const accountId = await verifiedGoogleAccountId();
    current();
    if (!accountId) throw new Error('Sign in with Google first.');
    if (vaultAccount !== accountId) {await androidVault.lock();current();vaultAccount = accountId;}
    const state = await androidVault.refresh();
    current();
    if (await verifiedGoogleAccountId() !== accountId) throw new Error('Your account changed. Sign in again.');
    current();
    if (state !== 'locked' && state !== 'unlocked' && state !== 'absent') throw new Error('Sign in with Google first.');
    return {accountId,state};
  },
  installTrustedHost: trustedHosts.install,
  trustedHostRevisions: trustedHosts.revisions,
  store: vaultStore,
  remember: createAndroidRememberApi(vaultNative, vaultScope),
  resetUrl: VAULT_RESET_URL,
  openUrl: openExternalUrl,
};

export const androidVault = createAndroidVault({
  core: createStoreVaultCore(vaultStore),
  native: vaultNative,
  scope: vaultScope,
  accountId: verifiedGoogleAccountId,
  askUnlock: (hostLabel) => requestVaultUnlock('connect', hostLabel),
  onUnlocked: async (syncPassphrase) => {
    if (!syncPassphrase) return;
    vaultSync.passphrase = syncPassphrase;
    await androidSync().unlock(syncPassphrase).catch(() => undefined);
  },
});

/** Auth loss locks transient credentials; it must not invoke sign-out's forgetAll. */
export function lockAndroidVaultAfterAuthLoss(): Promise<void> {
  vaultPreparation++;
  vaultAccount = null;
  vaultSync.passphrase = '';
  androidSync().clearAccountCache();
  return androidVault.lock();
}

const syncApi: typeof baseSyncApi = {
  ...baseSyncApi,
  // Signing out locks the vault, wipes the held keys and forgets this
  // device's remembered vault key for every account.
  logout: async () => {
    vaultPreparation++;
    vaultAccount = null;
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
  gatewayPairings: { ...gatewayPairings, validateTrusted: trustedHosts.validatePairing },
  gateway: createAndroidGatewayDirectory({ native: nativeGatewayPairing, account: googleSync, keys: sshKeyVault, hosts: androidHosts }),
  vault: androidVault,
});

export type { HostKeyTrustPin };
export { androidHosts };
