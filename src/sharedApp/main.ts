import { createApp, watch } from 'vue';
import { createPinia } from 'pinia';
import { App as CapacitorApp } from '@capacitor/app';
import '@xterm/xterm/css/xterm.css';
// The shared tokens, primitives and Inter — the desktop's and web's first import.
import '@ui/styles.css';
import { provideApi } from '@ui/app/ipc';
import { provideExtensions } from '@ui/app/extensions';
import { handleAndroidBack } from './androidBack';
import { recordDiagError } from '@ui/app/diag';
import { runInstalledDataMigration } from '@/migration/installedDataMigration';
import AndroidAppRoot from './AndroidAppRoot.vue';
import './phone.css';
import { createSharedAppRouter } from './router';
import { provideKeyVault } from '@ui/app/keyVault';
import { useSyncStore } from '@ui/app/stores/sync';
import { useSettingsStore } from '@ui/app/stores/settings';
import { backgroundHold } from '@/native/backgroundHold';
import { googleSync } from '@/native/googleSync';
import { bindAndroidAuthLoss } from '@/platform/android/authLoss';
import { androidPlatform, keyVaultPlatform, lockAndroidVaultAfterAuthLoss } from './platform';
import { androidTerminalInputAdapter } from '@/platform/android/terminalImeInput';
import { voiceComposerExtensions } from './extensions/voiceComposer';
import { bundledOnlyFontProfile, provideFontProfile } from '@ui/app/fontProfile';
import { ANDROID_MONO_FALLBACK, applyAndroidInsets, bindAndroidBottomInsets } from './androidShell';
import { keyboardInsets } from '@/native/keyboardInsets';
import VaultSettingsSection from '@ui/app/components/vault/VaultSettingsSection.vue';
import AndroidUploadChips from './AndroidUploadChips.vue';
import FastKeysDock from './extensions/FastKeysDock.vue';

/**
 * Mount the shared PocketShell app (core packages/ui) on Android. This is
 * the one place the Android transport binds to the shared app tree.
 */
export function mountSharedApp(target: string | Element): void {
  provideApi(androidPlatform.api);
  // The phone bundles one monospace face and has no installed fonts to offer:
  // the terminal's fallback tail and the Settings picker both follow this (#3138).
  provideFontProfile(bundledOnlyFontProfile(ANDROID_MONO_FALLBACK));
  // The account key vault (key-vault W4): core's shared unlock sheet, which
  // AppRoot mounts, over the phone's store and Keystore-sealed remember.
  const vault = provideKeyVault(keyVaultPlatform)!;
  // Android-only behaviour plugs into the shared app's extension points (D42).
  // The soft keyboard's composition reaches the terminal through this adapter
  // (#2952); desktop and web contribute none and keep xterm's own handling.
  // The fast-key dock is the phone's key row under the terminal (#3158): Esc,
  // Tab, Shift+Tab, Enter, arrows, Ctrl and digits without a hardware keyboard.
  provideExtensions({
    'terminal.dock': [{ id: 'fast-keys', component: FastKeysDock }],
    'terminal.inputAdapter': [androidTerminalInputAdapter],
    'settings.sections': [{ id: 'key-vault', title: 'Key vault', component: VaultSettingsSection }],
    // Per-file upload progress, Cancel and size refusals on the composer (#3191).
    'composer.accessory': [{ id: 'android-upload-chips', component: AndroidUploadChips }],
    ...voiceComposerExtensions, // one-tap voice into the prompt composer (#3128)
  });
  // The 0.5.x import runs before the picker reads hosts; it is idempotent.
  void runInstalledDataMigration();

  applyAndroidInsets();
  // The shared app root (theme and typography watchers, diag strip, outlet),
  // the same component desktop and web mount, plus the phone's own gates.
  const app = createApp(AndroidAppRoot);
  app.onUnmount(bindAndroidBottomInsets(keyboardInsets));
  app.config.errorHandler = (err): void => {
    recordDiagError('render', err);
  };
  window.addEventListener('unhandledrejection', (e) => recordDiagError('unhandledrejection', e.reason));
  window.addEventListener('error', (e) => {
    if (e.error) recordDiagError('error', e.error);
  });

  const router = createSharedAppRouter();
  const pinia = createPinia();
  app.use(pinia).use(router);
  const sync = useSyncStore(pinia);
  const releaseAuthLoss = bindAndroidAuthLoss(googleSync, {
    sync, vault,
    lockAndroidVault: lockAndroidVaultAfterAuthLoss,
  });
  app.onUnmount(releaseAuthLoss);
  // The native grace hold opens when the activity stops, before the WebView
  // hears about it, so it needs the user's window ahead of time (#3160).
  const settings = useSettingsStore(pinia);
  app.onUnmount(watch(() => settings.backgroundGraceMs, (graceMs) => {
    void backgroundHold.setGrace(graceMs).catch((error: unknown) => recordDiagError('error', error));
  }, { immediate: true }));
  app.mount(target);

  // Android Back: the shared app consumes it (top layer, subview, workspace,
  // in that order); at the host list root nothing is left, so the app goes to
  // the background. Minimised, not exited: the SSH session survives.
  void CapacitorApp.addListener('backButton', () => {
    handleAndroidBack(router, () => void CapacitorApp.minimizeApp());
  });
}
