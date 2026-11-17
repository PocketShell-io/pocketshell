import { describe, expect, it } from 'vitest';
import { ConnectionController, type SshCapability } from '@pocketshell/core';
import { createAndroidPlatform } from '@/platform/android/androidApi';
import { AndroidHostStore } from '@/platform/android/hostStore';
import { createLocalTrustStore } from '@/platform/android/trustStore';
import { FakeNative, MemoryStorage } from './support/androidFakeNative';

describe('Android desktop-chrome capability (#3139)', () => {
  it('declares app.desktopChrome === false so the shared Settings hides desktop-only controls and copy', () => {
    const native = new FakeNative();
    const storage = new MemoryStorage();
    const platform = createAndroidPlatform({
      createController: () => new ConnectionController({ capability: native.capability() as SshCapability, trustStore: createLocalTrustStore(storage) }),
      hosts: new AndroidHostStore({ storage, readLegacyHosts: async () => [] }),
      remoteDirectory: async () => '/home/orin',
      backgroundGraceMs: () => 60_000,
      addHostRoute: '/add',
    });
    expect(platform.api.app?.desktopChrome).toBe(false);
  });
});
