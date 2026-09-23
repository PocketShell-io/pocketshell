import { defineStore } from 'pinia';

export const SHELL_ROUTES = [
  'home',
  'settings',
  'settings-terminal',
  'settings-voice',
  'settings-connections',
  'settings-advanced',
  'settings-account',
  'diagnostics',
  'diagnostics-report',
  'diagnostics-clear',
  'about',
  'about-update',
] as const;

export type ShellRoute = (typeof SHELL_ROUTES)[number];

export const useNavigationStore = defineStore('navigation', {
  state: () => ({
    stack: ['home'] as ShellRoute[],
    selectedReportId: null as string | null,
  }),
  getters: {
    route: (state): ShellRoute => state.stack.at(-1) ?? 'home',
    canGoBack: (state): boolean => state.stack.length > 1,
  },
  actions: {
    openSettings() {
      this.open('settings');
    },
    open(route: ShellRoute) {
      if (route === 'home') {
        this.home();
        return;
      }
      if (this.route !== route) this.stack.push(route);
    },
    openReport(reportId: string) {
      this.selectedReportId = reportId;
      this.open('diagnostics-report');
    },
    back() {
      if (this.canGoBack) this.stack.pop();
    },
    home() {
      this.stack = ['home'];
      this.selectedReportId = null;
    },
  },
});
