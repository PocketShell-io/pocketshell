import { createApp } from 'vue';
import { createPinia } from 'pinia';
import App from './App.vue';
import '@pocketshell/ui/styles.css';
import '@xterm/xterm/css/xterm.css';
import './styles.css';
import { applySharedUiDefaults } from './sharedUiDefaults';

applySharedUiDefaults(document.documentElement);
createApp(App).use(createPinia()).mount('#app');
