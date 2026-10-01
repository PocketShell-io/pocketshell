import { createApp } from 'vue';
import { createPinia } from 'pinia';
import App from './App.vue';
import '@ui/styles.css';
import '@xterm/xterm/css/xterm.css';
import './styles.css';
import { applySharedUiDefaults } from './sharedUiDefaults';
import { installAndroidPlatformServices } from './platform/androidPlatformServices';

/** The pre-#2936 phone screens, kept alive until their journeys move over. */
export function mountLegacyApp(target: string | Element): void {
  applySharedUiDefaults(document.documentElement);
  const app = createApp(App);
  // Settings → Diagnostics/About/Update services and launch-time error capture (#2861).
  installAndroidPlatformServices(app, window);
  app.use(createPinia()).mount(target);
}
