/**
 * The phone's one settings-sync adapter (issue #3020), shared by the legacy
 * Account screen and the shared app's `api.sync` group. Created on first use
 * so importing a screen never touches browser storage.
 *
 * It reads the shared settings store's host selection (both shells keep it
 * there) so every undecided account alias stays selected, and the phone's
 * hosts so an upload only carries the fields the phone owns (#3063).
 */
import { getActivePinia } from 'pinia';
import type { HostEntry } from '@pocketshell/core';
import { useSettingsStore } from '@ui/app/stores/settings';
import { googleSync, type GoogleSyncNative } from '@/native/googleSync';
import { AndroidSync, type SyncSelection, type SyncStorage } from '@/sync/androidSync';

let instance: AndroidSync | undefined;

/** The settings store's selection; inert until a shell has installed Pinia. */
export const settingsSelection: SyncSelection = {
  get: () => (getActivePinia() ? useSettingsStore().syncSelectedHosts : []),
  set: (aliases) => {
    if (getActivePinia()) useSettingsStore().syncSelectedHosts = aliases;
  },
};

/** The adapter as both shells get it; unit tests pass fakes for the native edge and the stores. */
export function createAndroidSync(deps: {
  native: GoogleSyncNative;
  storage: SyncStorage;
  localHosts: () => Promise<readonly HostEntry[]>;
  kdfIterations?: number;
}): AndroidSync {
  return new AndroidSync({ ...deps, selection: settingsSelection });
}

export function androidSync(): AndroidSync {
  instance ??= createAndroidSync({
    native: googleSync,
    storage: window.localStorage,
    // Loaded on use, like the legacy Settings screen does, so importing this
    // module never builds the host store.
    localHosts: async () => (await import('./hosts')).androidHosts.list(),
  });
  return instance;
}
