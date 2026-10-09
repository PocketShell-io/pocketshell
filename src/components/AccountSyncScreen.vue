<script setup lang="ts">
/**
 * Settings → Account & sync (issue #3020): Google sign-in, the sync
 * passphrase, the host selection, and "Sync now" through the Android sync
 * adapter (native sign-in/transport, WebView encryption, core runSyncRound).
 *
 * The passphrase lives only in this component's memory; the Google ID token
 * never reaches the WebView at all.
 */
import { computed, onMounted, ref } from 'vue';
import { applyAccountToSelection, type HostEntry, type SyncHostEntry } from '@pocketshell/core';
import AppIcon from '@ui/components/AppIcon.vue';
import { useSettingsStore } from '@ui/app/stores/settings';
import { useSyncStore } from '@ui/app/stores/sync';
import type { GoogleSyncStatus } from '@/native/googleSync';
import { describeSyncError, syncFailureText, type AndroidSync } from '@/sync/androidSync';

const props = defineProps<{
  sync: AndroidSync;
  /** The phone's saved and imported hosts. */
  loadLocalHosts: () => Promise<HostEntry[]>;
}>();

const sharedSettings = useSettingsStore();
const status = ref<GoogleSyncStatus | null>(null);
const statusError = ref('');
const busy = ref<'' | 'sign-in' | 'sign-out' | 'sync' | 'unlock'>('');
const message = ref<{ kind: 'ok' | 'error'; text: string } | null>(null);
const passphrase = ref('');
const localHosts = ref<HostEntry[]>([]);
const accountHosts = ref<SyncHostEntry[] | null>(null);

interface HostRow {
  name: string;
  detail: string;
  where: 'phone' | 'account' | 'both';
}

const rows = computed<HostRow[]>(() => {
  const account = accountHosts.value ?? [];
  const accountNames = new Set(account.map((host) => host.name));
  const phoneNames = new Set(localHosts.value.map((host) => host.name));
  const describe = (host: { hostname: string; port?: number; user?: string }) =>
    `${host.user ? `${host.user}@` : ''}${host.hostname}${host.port && host.port !== 22 ? `:${host.port}` : ''}`;
  return [
    ...localHosts.value.map((host) => ({
      name: host.name,
      detail: describe(host),
      where: accountNames.has(host.name) ? 'both' as const : 'phone' as const,
    })),
    ...account.filter((host) => !phoneNames.has(host.name)).map((host) => ({
      name: host.name,
      detail: describe(host),
      where: 'account' as const,
    })),
  ];
});

const selected = computed(() => new Set(sharedSettings.syncSelectedHosts));

/**
 * A tick or untick is the shared sync store's (core's tick rule, #3072): an
 * untick is saved in `syncUntickedHosts`, so it is the one thing that removes
 * the host from the account on the next Sync now, and ticking again cancels it.
 */
function setSelected(alias: string, on: boolean): void {
  useSyncStore().setSelected(alias, on);
}

/** Write a selection core computed back to the shared settings. */
function persistSelection(next: { checked: string[]; unticked: string[] }): void {
  sharedSettings.syncSelectedHosts = next.checked;
  sharedSettings.syncUntickedHosts = next.unticked;
}

/** Show an account copy with core's tick rule applied: every host the user did not untick is ticked. */
function showAccount(hosts: SyncHostEntry[] | null): void {
  accountHosts.value = hosts;
  if (hosts === null) return;
  persistSelection(applyAccountToSelection(hosts, {
    checked: sharedSettings.syncSelectedHosts,
    unticked: sharedSettings.syncUntickedHosts,
  }));
}

async function refresh(): Promise<void> {
  statusError.value = '';
  try {
    status.value = await props.sync.status();
  } catch (error) {
    status.value = null;
    statusError.value = describeSyncError(error);
  }
  showAccount(status.value?.signedIn ? props.sync.accountHosts() : null);
  localHosts.value = await props.loadLocalHosts().catch(() => []);
}

async function signIn(): Promise<void> {
  if (busy.value) return;
  busy.value = 'sign-in';
  message.value = null;
  try {
    status.value = await props.sync.signIn();
    showAccount(props.sync.accountHosts());
    message.value = { kind: 'ok', text: `Signed in as ${status.value.email ?? 'your Google account'}.` };
  } catch (error) {
    message.value = { kind: 'error', text: describeSyncError(error) };
  } finally {
    busy.value = '';
  }
}

async function signOut(): Promise<void> {
  if (busy.value) return;
  busy.value = 'sign-out';
  message.value = null;
  try {
    await props.sync.signOut();
    passphrase.value = '';
    accountHosts.value = null;
    // The next account starts with every host kept (the shared store's logout
    // does the same): forgetting an untick can only keep a host.
    sharedSettings.syncUntickedHosts = [];
    message.value = { kind: 'ok', text: 'Signed out. The Google sign-in was removed from this phone.' };
  } catch (error) {
    message.value = { kind: 'error', text: describeSyncError(error) };
  } finally {
    busy.value = '';
    await refresh();
  }
}

/**
 * Read the account with the passphrase and upload nothing (#3063). The
 * decrypted copy is kept in memory only, so after a restart this is how the
 * account's hosts come back to the home screen.
 */
async function unlock(): Promise<void> {
  if (busy.value) return;
  if (passphrase.value === '') {
    message.value = { kind: 'error', text: 'Enter your sync passphrase.' };
    return;
  }
  busy.value = 'unlock';
  message.value = null;
  try {
    const hosts = await props.sync.unlock(passphrase.value);
    showAccount(props.sync.accountHosts());
    message.value = {
      kind: 'ok',
      text: `Your account has ${hosts.length} host${hosts.length === 1 ? '' : 's'}; they are listed on the home screen.`,
    };
  } catch (error) {
    message.value = { kind: 'error', text: describeSyncError(error) };
  } finally {
    busy.value = '';
  }
}

async function syncNow(): Promise<void> {
  if (busy.value) return;
  if (!status.value?.signedIn) {
    message.value = { kind: 'error', text: 'Sign in with Google first.' };
    return;
  }
  if (passphrase.value === '') {
    message.value = { kind: 'error', text: 'Enter your sync passphrase.' };
    return;
  }
  busy.value = 'sync';
  message.value = null;
  try {
    localHosts.value = await props.loadLocalHosts().catch(() => localHosts.value);
    const result = await props.sync.syncNow({
      localHosts: localHosts.value,
      selected: sharedSettings.syncSelectedHosts,
      unticked: sharedSettings.syncUntickedHosts,
      passphrase: passphrase.value,
      onSelection: persistSelection,
    });
    accountHosts.value = props.sync.accountHosts();
    if (result.kind === 'synced') {
      const total = result.hosts.length;
      message.value = { kind: 'ok', text: `Synced: ${total} host${total === 1 ? '' : 's'} in your account.` };
    } else {
      message.value = { kind: 'error', text: syncFailureText(result, sharedSettings.syncSelectedHosts.length) };
    }
  } catch (error) {
    message.value = { kind: 'error', text: describeSyncError(error) };
  } finally {
    busy.value = '';
  }
  // An expired sign-in that could not be renewed signed the phone out.
  try {
    status.value = await props.sync.status();
    if (!status.value.signedIn) accountHosts.value = null;
  } catch {
    // Keep the last known state; the message above already explains the failure.
  }
}

onMounted(() => {
  void refresh();
});
</script>

<template>
  <main class="screen-content settings-screen" data-testid="account-settings-screen" :data-sync-busy="busy || 'idle'">
    <section class="panel settings-panel" aria-labelledby="account-settings-title">
      <h1 id="account-settings-title">Account &amp; sync</h1>
      <p class="settings-copy">Sign in with Google to sync your host list with PocketShell on your laptop and the web. Hosts are encrypted on this phone with your sync passphrase before they are uploaded.</p>

      <div class="account-status" data-testid="account-sync-status" :data-signed-in="status?.signedIn ? 'true' : 'false'">
        <AppIcon :name="status?.signedIn ? 'check' : 'circle'" :size="16" />
        <div>
          <strong v-if="status?.signedIn" data-testid="account-sync-email">Signed in as {{ status.email ?? 'your Google account' }}</strong>
          <strong v-else-if="status">Not signed in</strong>
          <strong v-else>Checking sign-in…</strong>
          <p v-if="statusError" role="alert">{{ statusError }}</p>
          <p v-else-if="!status?.signedIn">Sign-in is optional. Without it, PocketShell keeps everything on this phone.</p>
        </div>
      </div>

      <button
        v-if="!status?.signedIn"
        class="action-button"
        type="button"
        data-testid="account-sign-in"
        :disabled="!!busy || !status"
        @click="signIn"
      >
        {{ busy === 'sign-in' ? 'Signing in…' : 'Sign in with Google' }}
      </button>

      <template v-else>
        <label class="form-field account-passphrase">
          <span>Sync passphrase · kept in memory only, never uploaded</span>
          <input
            v-model="passphrase"
            data-testid="account-sync-passphrase"
            type="password"
            autocomplete="off"
            autocapitalize="none"
            spellcheck="false"
          />
        </label>
        <p v-if="accountHosts === null" class="settings-note" data-testid="account-sync-locked">
          Your account's hosts stay locked until you enter the passphrase. PocketShell keeps them in memory only, so after a restart unlock again to list them on the home screen.
        </p>
        <button class="small-action" type="button" data-testid="account-sync-unlock" :disabled="!!busy" @click="unlock">
          {{ busy === 'unlock' ? 'Unlocking…' : 'Show account hosts' }}
        </button>

        <div class="account-hosts" data-testid="account-sync-hosts">
          <h2 class="account-hosts__title">Hosts to sync</h2>
          <p v-if="rows.length === 0" class="settings-note">No hosts yet. Sync once to bring in the hosts saved in your account.</p>
          <label v-for="row in rows" :key="row.name" class="account-host-row" :data-testid="`account-sync-host-${row.name}`" :data-where="row.where">
            <input
              type="checkbox"
              :checked="selected.has(row.name)"
              :data-testid="`account-sync-select-${row.name}`"
              @change="setSelected(row.name, ($event.target as HTMLInputElement).checked)"
            />
            <span>
              <strong>{{ row.name }}</strong>
              <small>{{ row.detail }} · {{ row.where === 'account' ? 'from your account' : row.where === 'both' ? 'on this phone and in your account' : 'on this phone' }}</small>
            </span>
          </label>
        </div>

        <button class="action-button" type="button" data-testid="account-sync-now" :disabled="!!busy" @click="syncNow">
          {{ busy === 'sync' ? 'Syncing…' : 'Sync now' }}
        </button>
        <button class="small-action account-sign-out" type="button" data-testid="account-sign-out" :disabled="!!busy" @click="signOut">
          {{ busy === 'sign-out' ? 'Signing out…' : 'Sign out' }}
        </button>
      </template>

      <p
        v-if="message"
        class="account-message"
        :class="message.kind === 'error' ? 'account-message--error' : 'account-message--ok'"
        :role="message.kind === 'error' ? 'alert' : 'status'"
        data-testid="account-sync-message"
        :data-kind="message.kind"
      >{{ message.text }}</p>

      <p class="settings-note">Hosts from your account appear in the home screen's host list. Choose an SSH key on this phone before connecting. Losing the sync passphrase loses the synced data; it cannot be recovered.</p>
    </section>
  </main>
</template>

<style scoped>
.account-status {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  margin: 16px 0;
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  background: var(--bg);
}

.account-status p {
  margin: 4px 0 0;
  color: var(--fg-secondary);
  font-size: var(--fs-300);
}

.account-passphrase {
  margin: 12px 0;
}

.account-hosts {
  display: grid;
  gap: 8px;
  margin: 12px 0 16px;
}

.account-hosts__title {
  margin: 0;
  color: var(--fg);
  font-size: var(--fs-300);
  font-weight: 600;
}

.account-host-row {
  display: flex;
  gap: 10px;
  align-items: center;
  min-height: 44px;
  padding: 6px 10px;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
}

.account-host-row input {
  width: 20px;
  height: 20px;
  accent-color: var(--accent);
}

.account-host-row span {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.account-host-row small {
  overflow: hidden;
  color: var(--fg-secondary);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.account-sign-out {
  width: 100%;
  min-height: 44px;
  margin-top: 10px;
}

.account-message {
  margin: 12px 0 0;
  font-size: var(--fs-300);
  line-height: 1.45;
}

.account-message--error {
  color: var(--error);
}

.account-message--ok {
  color: var(--success);
}
</style>
