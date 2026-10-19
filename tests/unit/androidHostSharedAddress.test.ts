import { afterEach, describe, expect, it } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { provideApi } from '@ui/app/ipc';
import { useConnectionStore } from '@ui/app/stores/connection';
import { ConnectionController, type HostKeyTrustRequest } from '@pocketshell/core';
import { AndroidHostStore, ANDROID_HOSTS_STORAGE_KEY } from '@/platform/android/hostStore';
import { createAndroidPlatform } from '@/platform/android/androidApi';
import { createLocalTrustStore, pinStorageKey } from '@/platform/android/trustStore';
import { FakeNative, HOST_KEY, MemoryStorage, settle } from './support/androidFakeNative';
import type { ImportedLegacyHost } from '@/migration/installedDataMigration';

function testPlatform(options: { legacyHosts?: ImportedLegacyHost[] } = {}) {
  const native = new FakeNative();
  const storage = new MemoryStorage();
  const trust = createLocalTrustStore(storage);
  const hosts = new AndroidHostStore({
    storage,
    readLegacyHosts: async () => options.legacyHosts ?? [],
  });
  let ids = 0;
  const created = createAndroidPlatform({
    createController: () => new ConnectionController({
      capability: native.capability(),
      trustStore: trust,
      createId: () => `c-${++ids}`,
      retryDelaysMs: [0],
    }),
    hosts,
    backgroundGraceMs: () => 60_000,
    addHostRoute: '/android/hosts',
  });
  return { native, storage, trust, hosts, created };
}

describe('Android hosts sharing an address (#3068)', () => {
  it('labelFor resolves by address and user when two saved hosts share an address', () => {
    const storage = new MemoryStorage();
    const box = { name: 'box', hostname: '10.0.0.5', port: 22, user: 'alexey', keyHandleId: 'k1' };
    const rootbox = { name: 'rootbox', hostname: '10.0.0.5', port: 22, user: 'root', keyHandleId: 'k2' };
    storage.setItem(ANDROID_HOSTS_STORAGE_KEY, JSON.stringify([box, rootbox]));
    const hosts = new AndroidHostStore({ storage, readLegacyHosts: async () => [] });

    // When dialing with user root, labelFor returns rootbox, not box
    expect(hosts.labelFor({ host: '10.0.0.5', port: 22, user: 'root' })).toBe('rootbox');

    // When dialing with user alexey, labelFor returns box
    expect(hosts.labelFor({ host: '10.0.0.5', port: 22, user: 'alexey' })).toBe('box');

    // Explicit hostAlias takes precedence
    expect(hosts.labelFor({ host: '10.0.0.5', port: 22, user: 'root', hostAlias: 'box' })).toBe('box');

    // When dialing with unknown user, falls back to first host at address
    expect(hosts.labelFor({ host: '10.0.0.5', port: 22, user: 'other' })).toBe('box');

    // When dialing a different port, falls back to host
    expect(hosts.labelFor({ host: '10.0.0.5', port: 2222, user: 'root' })).toBe('10.0.0.5');
  });

  it('labelFor and resolve match legacy imported hosts by user when sharing an address', async () => {
    const legacyA: ImportedLegacyHost = {
      id: 101,
      name: 'old-alexey',
      hostname: '10.0.0.5',
      port: 22,
      username: 'alexey',
      keyId: 1,
      keyName: 'k1',
      keyHasPassphrase: false,
      keySha256: 'sha1',
      keyHandleId: 'h1',
    };
    const legacyR: ImportedLegacyHost = {
      id: 102,
      name: 'old-root',
      hostname: '10.0.0.5',
      port: 22,
      username: 'root',
      keyId: 2,
      keyName: 'k2',
      keyHasPassphrase: false,
      keySha256: 'sha2',
      keyHandleId: 'h2',
    };
    const storage = new MemoryStorage();
    const hosts = new AndroidHostStore({ storage, readLegacyHosts: async () => [legacyA, legacyR] });

    // Initialize legacy cache by resolving or listing
    await hosts.list();

    expect(hosts.labelFor({ host: '10.0.0.5', port: 22, user: 'root' })).toBe('old-root');
    expect(hosts.labelFor({ host: '10.0.0.5', port: 22, user: 'alexey' })).toBe('old-alexey');

    const targetRoot = await hosts.resolve({ host: '10.0.0.5', port: 22, user: 'root' });
    expect(targetRoot.hostId).toBe('102');
    expect(targetRoot.username).toBe('root');

    const targetAlexey = await hosts.resolve({ host: '10.0.0.5', port: 22, user: 'alexey' });
    expect(targetAlexey.hostId).toBe('101');
    expect(targetAlexey.username).toBe('alexey');
  });

  it('trust prompt and un-trusted error name the resolved host when two saved hosts share an address', async () => {
    const { hosts, created } = testPlatform();
    hosts.save({ name: 'box', hostname: '10.0.0.5', port: 22, user: 'alexey', keyHandleId: 'k1' });
    hosts.save({ name: 'rootbox', hostname: '10.0.0.5', port: 22, user: 'root', keyHandleId: 'k2' });

    provideApi(created.api);
    setActivePinia(createPinia());
    const connection = useConnectionStore();

    // Dial as root on 10.0.0.5:22 without alias
    const dialRoot = connection.connect({
      name: '',
      hostname: '10.0.0.5',
      port: 22,
      user: 'root',
      identityFile: null,
      proxyJump: null,
      forwardAgent: false,
      localForwards: [],
      remoteForwards: [],
      fromConfig: false,
    });

    await settle(() => connection.pendingTrust !== null);
    // Security surface: trust prompt MUST name rootbox, not box
    expect(connection.pendingTrust?.hostLabel).toBe('rootbox');

    // Rejecting trust must name rootbox in the error
    connection.answerTrust('reject');
    expect(await dialRoot).toBe(false);
    expect(connection.error).toBe('Host key for rootbox was not trusted. No connection was opened.');

    // Now dial as alexey on 10.0.0.5:22
    const dialAlexey = connection.connect({
      name: '',
      hostname: '10.0.0.5',
      port: 22,
      user: 'alexey',
      identityFile: null,
      proxyJump: null,
      forwardAgent: false,
      localForwards: [],
      remoteForwards: [],
      fromConfig: false,
    });

    await settle(() => connection.pendingTrust !== null);
    expect(connection.pendingTrust?.hostLabel).toBe('box');

    connection.answerTrust('reject');
    expect(await dialAlexey).toBe(false);
    expect(connection.error).toBe('Host key for box was not trusted. No connection was opened.');
  });

  it('changed host key error names the resolved host when two saved hosts share an address', async () => {
    const { storage, trust, hosts, created } = testPlatform();
    hosts.save({ name: 'box', hostname: '10.0.0.5', port: 22, user: 'alexey', keyHandleId: 'k1' });
    hosts.save({ name: 'rootbox', hostname: '10.0.0.5', port: 22, user: 'root', keyHandleId: 'k2' });

    // Pin a previous fingerprint for root@10.0.0.5:22
    void trust.record('root@10.0.0.5:22', { kind: 'sha256-fingerprint', fingerprintSha256: 'SHA256:previously-trusted' });

    const result = await created.api.ssh.connect({
      host: '10.0.0.5',
      port: 22,
      user: 'root',
    });

    expect(result.ok).toBe(false);
    expect(result.error).toMatch(/^Host key for rootbox has changed — connection refused\./);
  });
});
