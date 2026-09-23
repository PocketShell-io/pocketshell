import { defineStore } from 'pinia';

export type ShellRoute = 'home' | 'settings';

export const useNavigationStore = defineStore('navigation', {
  state: () => ({ route: 'home' as ShellRoute }),
  actions: {
    openSettings() {
      this.route = 'settings';
    },
    back() {
      if (this.route === 'settings') this.route = 'home';
    },
  },
});
