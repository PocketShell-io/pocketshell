import { createRouter, createMemoryHistory, type RouteRecordRaw } from 'vue-router';
import { createAppRoutes } from '@ui/app/routes';

/** The Android-only route: adding a host with its key (the phone's ~/.ssh/config). */
export const ADD_HOST_ROUTE = '/android/hosts';

/** Android's own routes, added to the shared map — the web's `/app` equivalent. */
export const androidRoutes: RouteRecordRaw[] = [
  { path: ADD_HOST_ROUTE, name: 'android-hosts', component: () => import('./AndroidHostsView.vue') },
];

/**
 * The shared route map (core `@ui/app/routes.ts`) plus Android's routes, on
 * memory history: the WebView loads one file and Android Back walks this
 * history.
 */
export function createSharedAppRouter() {
  return createRouter({ history: createMemoryHistory(), routes: createAppRoutes(androidRoutes) });
}
