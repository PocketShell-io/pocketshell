/**
 * The shared shell's grace window and reconnect-on-return: the shared
 * settings store's values (Settings → Connections; D42, #3160). The Android
 * platform is created before the app installs Pinia, so a read before then
 * answers core's defaults rather than throwing.
 */
import { DEFAULT_BACKGROUND_GRACE_MS, DEFAULT_RECONNECT_ON_RETURN } from '@pocketshell/core';
import { useSettingsStore } from '@ui/app/stores/settings';

export function sharedBackgroundGraceMs(): number {
  try {
    return useSettingsStore().backgroundGraceMs;
  } catch {
    return DEFAULT_BACKGROUND_GRACE_MS;
  }
}

export function sharedReconnectOnReturn(): boolean {
  try {
    return useSettingsStore().reconnectOnReturn;
  } catch {
    return DEFAULT_RECONNECT_ON_RETURN;
  }
}
