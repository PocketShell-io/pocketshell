import { recordShellBoot, selectShell } from './shellSelection';
import { bridgeWarmUp } from './native/bridgeReady';
import { durableStorageOpenCalls, installAndroidDurableStorage, recordDurableStorageStatus } from './native/durableStorage';

/**
 * The Android entry: bridge warm-up (#3000), then the bounded durable-storage
 * hydrate (#2993), then the shell choice (#2936) and its mount. The pre-#2936
 * phone screens are the default; the shared PocketShell app (core
 * packages/ui) is opt-in until parity (#2941). Each shell is a lazy chunk,
 * so neither's global CSS reaches the other and both read hydrated storage.
 */
async function boot(): Promise<void> {
  // The page's first native call must be the sacrificial bridge ping: after a
  // reload Capacitor can hand the first reply to the previous document (see
  // BridgeReadyPlugin.java, #3000). Every later call, durable storage
  // included, uses this page's own channel.
  const bridge = await bridgeWarmUp();
  document.documentElement.dataset.bridgeWarmUp = JSON.stringify({ attempts: bridge.attempts, answered: bridge.answered });
  // Issue #2993: hydrate and make localStorage durable before any store reads
  // it. The open is bounded, so a lost reply records a failure instead of
  // leaving a blank screen.
  recordDurableStorageStatus(document.documentElement, await installAndroidDurableStorage());
  document.documentElement.dataset.durableStorageOpenCalls = String(durableStorageOpenCalls());
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
