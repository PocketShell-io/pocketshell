import { createApp } from 'vue';
import { createPinia } from 'pinia';
import App from './App.vue';
import '@fontsource-variable/inter';
import '@xterm/xterm/css/xterm.css';
import './styles.css';

createApp(App).use(createPinia()).mount('#app');
