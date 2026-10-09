import { createRouter, createMemoryHistory, type Router, type RouteRecordRaw } from 'vue-router';
import { createAppRoutes } from '@ui/app/routes';

/** The Android-only route: adding a host with its key (the phone's ~/.ssh/config). */
export const ADD_HOST_ROUTE = '/android/hosts';

/**
 * Account & sync (#3063): the shared AccountView on its own route — the
 * desktop opens it as a separate window, the web as /account. The picker's
 * account button reaches it through `win.openAccount`.
 */
export const ACCOUNT_ROUTE = '/android/account';

/** Android's own routes, added to the shared map — the web's `/app` equivalent. */
export const androidRoutes: RouteRecordRaw[] = [
  { path: ADD_HOST_ROUTE, name: 'android-hosts', component: () => import('./AndroidHostsView.vue') },
  { path: ACCOUNT_ROUTE, name: 'android-account', component: () => import('./AndroidAccountView.vue') },
];

let current: Router | null = null;

/**
 * The shared route map (core `@ui/app/routes.ts`) plus Android's routes, on
 * memory history: the WebView loads one file and Android Back walks this
 * history. The last router created is the one {@link openAccountRoute} uses.
 */
export function createSharedAppRouter(): Router {
  current = createRouter({ history: createMemoryHistory(), routes: createAppRoutes(androidRoutes) });
  return current;
}

/** `win.openAccount` on Android: open Account & sync in the mounted app. */
export async function openAccountRoute(): Promise<void> {
  if (!current) throw new Error('The PocketShell app is not mounted yet.');
  await current.push(ACCOUNT_ROUTE);
}
