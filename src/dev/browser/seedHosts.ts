/**
 * Browser dev mode (#3022): offer the dev hosts as saved hosts on first
 * launch. Mirrors ANDROID_HOSTS_STORAGE_KEY without importing hostStore,
 * because install.ts must not pull app modules in ahead of Capacitor.
 */
import type { SeedHost } from './liveBridge';

/** Must equal ANDROID_HOSTS_STORAGE_KEY (asserted by tests/unit/devBrowserShims.test.ts). */
export const DEV_HOSTS_STORAGE_KEY = 'pocketshell.android.hosts.v1';

/** Add each seed host whose name is not saved yet; returns how many were added. */
export function seedSavedHosts(storage: Pick<Storage, 'getItem' | 'setItem'>, seeds: readonly SeedHost[]): number {
  if (seeds.length === 0) return 0;
  let saved: unknown[] = [];
  try {
    const parsed: unknown = JSON.parse(storage.getItem(DEV_HOSTS_STORAGE_KEY) ?? '[]');
    saved = Array.isArray(parsed) ? parsed : [];
  } catch {
    saved = [];
  }
  const names = new Set(saved.map((host) => (host as { name?: unknown }).name));
  const added = seeds.filter((seed) => seed.keyHandleId && !names.has(seed.name));
  if (added.length === 0) return 0;
  storage.setItem(DEV_HOSTS_STORAGE_KEY, JSON.stringify([...saved, ...added.map((seed) => ({ ...seed }))]));
  return added.length;
}
