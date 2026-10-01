import { createApp } from 'vue';
import { createPinia } from 'pinia';
import App from './App.vue';
import '@ui/styles.css';
import '@xterm/xterm/css/xterm.css';
import './styles.css';
import { applySharedUiDefaults } from './sharedUiDefaults';

/** The pre-#2936 phone screens, kept alive until their journeys move over. */
export function mountLegacyApp(target: string | Element): void {
  applySharedUiDefaults(document.documentElement);
  createApp(App).use(createPinia()).mount(target);
}
