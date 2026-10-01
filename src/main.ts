import { recordShellBoot, selectShell } from './shellSelection';

/**
 * The Android entry. The pre-#2936 phone screens (`App.vue`) are the default
 * shell; the shared PocketShell app (core packages/ui) is opt-in until each
 * legacy screen's shared replacement passes the same packaged journey and
 * the maintainer signs the shared app off (#2936 / #2941).
 */
async function boot(): Promise<void> {
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
