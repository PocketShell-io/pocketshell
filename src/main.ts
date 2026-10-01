import { createApp } from 'vue';
import { createPinia } from 'pinia';
import '@ui/styles.css';
import '@xterm/xterm/css/xterm.css';
import './styles.css';
import { applySharedUiDefaults } from './sharedUiDefaults';
import { bridgeWarmUp } from './native/bridgeReady';
import { durableStorageOpenCalls, installAndroidDurableStorage, recordDurableStorageStatus } from './native/durableStorage';

applySharedUiDefaults(document.documentElement);

async function boot(): Promise<void> {
  // The page's first native call must be the sacrificial bridge ping: after a
  // reload Capacitor can hand the first reply to the previous document (see
  // BridgeReadyPlugin.java, #3000). Every later call, durable storage
  // included, uses this page's own channel.
  const bridge = await bridgeWarmUp();
  document.documentElement.dataset.bridgeWarmUp = JSON.stringify({ attempts: bridge.attempts, answered: bridge.answered });
  // Issue #2993: hydrate and make localStorage durable before any store reads
  // it. The open is bounded, so a lost reply records a failure instead of
  // leaving a blank screen. App.vue's module graph reads saved data at import
  // time, so it is only imported once storage is ready.
  recordDurableStorageStatus(document.documentElement, await installAndroidDurableStorage());
  document.documentElement.dataset.durableStorageOpenCalls = String(durableStorageOpenCalls());
  const { default: App } = await import('./App.vue');
  createApp(App).use(createPinia()).mount('#app');
}

void boot();
