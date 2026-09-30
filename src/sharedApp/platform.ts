/**
 * The Android platform instance behind the shared app: the native SSH
 * capability bound to core's ConnectionController, the phone's host source,
 * and the Android lifecycle. Created once at startup (main.ts); the shared
 * app reaches it only through `provideApi()`.
 */
import { App as CapacitorApp } from '@capacitor/app';
import { ConnectionController, type HostKeyTrustPin, type HostKeyTrustStore } from '@pocketshell/core';
import { sshCapability } from '@/native/sshCapability';
import { readImportedLegacyHosts } from '@/migration/installedDataMigration';
import { createAndroidPlatform, type AndroidLifecycle } from '@/platform/android/androidApi';
import { AndroidHostStore } from '@/platform/android/hostStore';
import { createLocalTrustStore } from '@/platform/android/trustStore';
import { ADD_HOST_ROUTE } from './router';

/** Mirrors the legacy screen's default; the shared settings store has no grace field yet (stage L1). */
const BACKGROUND_GRACE_MS = 60_000;

const capacitorLifecycle: AndroidLifecycle = {
  onActiveChange(handler) {
    const pending = CapacitorApp.addListener('appStateChange', ({ isActive }) => handler(isActive));
    return () => {
      void pending.then((listener) => listener.remove());
    };
  },
};

export const androidHosts = new AndroidHostStore({
  storage: window.localStorage,
  readLegacyHosts: () => readImportedLegacyHosts(),
});

const trustStore: HostKeyTrustStore = createLocalTrustStore(window.localStorage);

export const androidPlatform = createAndroidPlatform({
  createController: () => new ConnectionController({ capability: sshCapability, trustStore }),
  hosts: androidHosts,
  lifecycle: capacitorLifecycle,
  backgroundGraceMs: () => BACKGROUND_GRACE_MS,
  addHostRoute: ADD_HOST_ROUTE,
  log: (entry) => console.info(`[pocketshell] ${entry.kind}: ${entry.message}`, entry.detail ?? ''),
});

export type { HostKeyTrustPin };
