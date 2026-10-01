import { recordShellBoot, selectShell } from './shellSelection';
import { installAndroidDurableStorage, recordDurableStorageStatus } from './native/durableStorage';

/**
 * The Android entry. Durable storage is hydrated first (#2993), then the
 * shell is chosen: the pre-#2936 phone screens are the default and the
 * shared PocketShell app (core packages/ui) is opt-in until parity (#2941).
 * Each shell is a lazy chunk, so neither's global CSS reaches the other.
 */
async function boot(): Promise<void> {
  // Issue #2993: hydrate and make localStorage durable before any store reads it.
  recordDurableStorageStatus(document.documentElement, await installAndroidDurableStorage());
  const shell = selectShell(window.location.search);
  recordShellBoot(shell, window.sessionStorage);
  if (shell === 'shared') {
    const { mountSharedApp } = await import('./sharedApp/main');
    mountSharedApp('#app');
    return;
  }
  const { mountLegacyApp } = await import('./legacyMain');
  mountLegacyApp('#app');
}

void boot();
