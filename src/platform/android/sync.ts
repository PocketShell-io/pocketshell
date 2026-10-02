/**
 * The phone's one settings-sync adapter (issue #3020), shared by the legacy
 * Account screen and the shared app's `api.sync` group. Created on first use
 * so importing a screen never touches browser storage.
 */
import { googleSync } from '@/native/googleSync';
import { AndroidSync } from '@/sync/androidSync';

let instance: AndroidSync | undefined;

export function androidSync(): AndroidSync {
  instance ??= new AndroidSync({ native: googleSync, storage: window.localStorage });
  return instance;
}
