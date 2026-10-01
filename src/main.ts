import { createApp } from 'vue';
import { createPinia } from 'pinia';
import App from './App.vue';
import '@ui/styles.css';
import '@xterm/xterm/css/xterm.css';
import './styles.css';
import { applySharedUiDefaults } from './sharedUiDefaults';
import { installAndroidPlatformServices } from './platform/androidPlatformServices';

applySharedUiDefaults(document.documentElement);
const app = createApp(App);
installAndroidPlatformServices(app, window);
app.use(createPinia()).mount('#app');
