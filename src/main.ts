import { selectShell } from './shellSelection';

/**
 * The Android entry. The shared PocketShell app (core packages/ui) is the
 * app; the phone screens under `App.vue` stay reachable as the `legacy`
 * shell only until each one's shared replacement passes the same packaged
 * journey (#2936 / #2941), then they are deleted (D22).
 */
async function boot(): Promise<void> {
  if (selectShell(window.location.search) === 'legacy') {
    const { mountLegacyApp } = await import('./legacyMain');
    mountLegacyApp('#app');
    return;
  }
  const { mountSharedApp } = await import('./sharedApp/main');
  mountSharedApp('#app');
}

void boot();
