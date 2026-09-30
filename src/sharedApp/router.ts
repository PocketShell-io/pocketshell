import { createRouter, createMemoryHistory, type RouteRecordRaw } from 'vue-router';
import HostPickerView from '@ui/app/views/HostPickerView.vue';
import HostWorkspaceView from '@ui/app/views/HostWorkspaceView.vue';
import FolderWorkspaceView from '@ui/app/views/FolderWorkspaceView.vue';
import SessionPlaceholderView from '@ui/app/views/SessionPlaceholderView.vue';
import SessionRedirectView from '@ui/app/views/SessionRedirectView.vue';

/** The Android-only route: adding a host with its key (the phone's ~/.ssh/config). */
export const ADD_HOST_ROUTE = '/android/hosts';

/**
 * The shared app's route map — identical to desktop's and web's — plus the
 * platform's own host-management route, the web's `/app` equivalent. Memory
 * history: the WebView loads one file and Android Back walks this history.
 */
const routes: RouteRecordRaw[] = [
  { path: '/', name: 'hosts', component: HostPickerView },
  {
    path: '/host/:name',
    component: HostWorkspaceView,
    children: [
      { path: '', name: 'host-sessions', component: SessionPlaceholderView },
      { path: 'folder/:folder', name: 'folder', component: FolderWorkspaceView },
      { path: 'session/:session', name: 'session', component: SessionRedirectView },
    ],
  },
  { path: ADD_HOST_ROUTE, name: 'android-hosts', component: () => import('./AndroidHostsView.vue') },
  { path: '/:pathMatch(.*)*', redirect: '/' },
];

export function createSharedAppRouter() {
  return createRouter({ history: createMemoryHistory(), routes });
}
