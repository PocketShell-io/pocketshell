import { createApp } from 'vue';
import { createPinia } from 'pinia';
import '@ui/styles.css';
import '@xterm/xterm/css/xterm.css';
import './styles.css';
import { applySharedUiDefaults } from './sharedUiDefaults';
import { installAndroidDurableStorage, recordDurableStorageStatus } from './native/durableStorage';

applySharedUiDefaults(document.documentElement);

async function boot(): Promise<void> {
  // Issue #2993: hydrate and make localStorage durable before any store reads
  // it. App.vue's module graph reads saved data at import time, so it is only
  // imported once storage is ready.
  recordDurableStorageStatus(document.documentElement, await installAndroidDurableStorage());
  const { default: App } = await import('./App.vue');
  createApp(App).use(createPinia()).mount('#app');
}

void boot();
