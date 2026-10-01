import { createApp } from 'vue';
import { createPinia } from 'pinia';
import { App as CapacitorApp } from '@capacitor/app';
import '@xterm/xterm/css/xterm.css';
// The shared tokens, primitives and Inter — the desktop's and web's first import.
import '@ui/styles.css';
import { provideApi } from '@ui/app/ipc';
import { provideExtensions } from '@ui/app/extensions';
import { recordDiagError } from '@ui/app/diag';
import { runInstalledDataMigration } from '@/migration/installedDataMigration';
import SharedAppRoot from './SharedAppRoot.vue';
import { createSharedAppRouter } from './router';
import { androidPlatform } from './platform';
import { androidTerminalInputAdapter } from '@/platform/android/terminalImeInput';

/**
 * Mount the shared PocketShell app (core packages/ui) on Android. This is
 * the one place the Android transport binds to the shared app tree.
 */
export function mountSharedApp(target: string | Element): void {
  provideApi(androidPlatform.api);
  // Android-only behaviour plugs into the shared app's extension points (D42).
  // The soft keyboard's composition reaches the terminal through this adapter
  // (#2952); desktop and web contribute none and keep xterm's own handling.
  provideExtensions({ 'terminal.inputAdapter': [androidTerminalInputAdapter] });
  // The 0.5.x import runs before the picker reads hosts; it is idempotent.
  void runInstalledDataMigration();

  const app = createApp(SharedAppRoot);
  app.config.errorHandler = (err): void => {
    recordDiagError('render', err);
  };
  window.addEventListener('unhandledrejection', (e) => recordDiagError('unhandledrejection', e.reason));
  window.addEventListener('error', (e) => {
    if (e.error) recordDiagError('error', e.error);
  });

  const router = createSharedAppRouter();
  app.use(createPinia()).use(router).mount(target);

  // Android Back walks the shared app's history, then leaves the app.
  void CapacitorApp.addListener('backButton', () => {
    if (router.currentRoute.value.name === 'hosts') {
      void CapacitorApp.exitApp();
    } else if (router.options.history.state.back) {
      router.back();
    } else {
      void router.push({ name: 'hosts' });
    }
  });
}
