import { describe, expect, it, vi } from 'vitest';
import { ConnectionController } from '@pocketshell/core';
import {
  adaptSshCapabilityPlugin,
  loadNativeTransportCapabilities,
  type NativeSshCapabilityPlugin,
  type NativeTransportCapabilities,
} from '@/native/sshCapability';
import { createAndroidPlatform, unsupportedTransportMessage } from '@/platform/android/androidApi';
import { AndroidHostStore, ANDROID_HOSTS_STORAGE_KEY } from '@/platform/android/hostStore';
import { createLocalTrustStore } from '@/platform/android/trustStore';
import { FakeNative } from './support/androidFakeNative';

/**
 * #3086 slice 2 (landed dark): the native SshCapability plugin reports the
 * gateway transport, and core's `SshCapability.gatewayTransport` is answered
 * from that report only — never a JS constant. Android still refuses every
 * gateway host at its platform boundary until slice 3 lifts the refusal.
 */
const GW = { serverUrl: 'wss://gateway.example', deviceId: 'dev-1' };
const GATEWAY_REFUSAL = /is reached through the PocketShell gateway, which this device can't connect through yet\. Nothing was dialled\.$/;

class Storage {
  readonly v = new Map<string, string>();
  getItem(k: string) { return this.v.get(k) ?? null; }
  setItem(k: string, x: string) { this.v.set(k, x); }
  removeItem(k: string) { this.v.delete(k); }
}

/** The fake native plugin, plus the transport report the Java plugin returns. */
function nativePlugin(native: FakeNative, transportCapabilities: (options: { requestId: string }) => Promise<unknown>) {
  return { ...native.capability(), resourceSnapshot: vi.fn(), transportCapabilities: vi.fn(transportCapabilities) } as unknown as NativeSshCapabilityPlugin;
}

const reportsGateway = async ({ requestId }: { requestId: string }) => ({ requestId, gatewayTransport: true, linkTransport: false });

describe('Android native gateway transport capability (#3086 slice 2)', () => {
  it('reports the gateway transport to core only after the native plugin says so', async () => {
    const transports: NativeTransportCapabilities = { gatewayTransport: false };
    const plugin = nativePlugin(new FakeNative(), reportsGateway);
    const capability = adaptSshCapabilityPlugin(plugin, transports);

    expect(capability.gatewayTransport).toBe(false);
    await loadNativeTransportCapabilities(plugin, transports);
    expect(plugin.transportCapabilities).toHaveBeenCalledTimes(1);
    expect(capability.gatewayTransport).toBe(true);
  });

  it.each([
    ['a native rejection (an older build without the method)', async () => { throw { code: 'UNIMPLEMENTED' }; }],
    ['a reply for another request', async () => ({ requestId: 'other', gatewayTransport: true })],
    ['a non-boolean flag', async ({ requestId }: { requestId: string }) => ({ requestId, gatewayTransport: 'true' })],
    ['an empty reply', async () => undefined],
    ['an explicit false', async ({ requestId }: { requestId: string }) => ({ requestId, gatewayTransport: false })],
  ])('fails closed on %s', async (_label, reply) => {
    const transports: NativeTransportCapabilities = { gatewayTransport: true };
    const plugin = nativePlugin(new FakeNative(), reply as (options: { requestId: string }) => Promise<unknown>);
    const capability = adaptSshCapabilityPlugin(plugin, transports);

    await loadNativeTransportCapabilities(plugin, transports);
    expect(capability.gatewayTransport).toBe(false);
  });

  it('still refuses a valid gateway host while native reports the transport (slice 3 lifts this)', async () => {
    const native = new FakeNative();
    const transports: NativeTransportCapabilities = { gatewayTransport: false };
    const plugin = nativePlugin(native, reportsGateway);
    const capability = adaptSshCapabilityPlugin(plugin, transports);
    await loadNativeTransportCapabilities(plugin, transports);
    expect(capability.gatewayTransport).toBe(true);

    const storage = new Storage();
    storage.setItem(ANDROID_HOSTS_STORAGE_KEY, JSON.stringify([
      { name: 'gw', hostname: '10.0.0.5', port: 22, user: 'root', keyHandleId: '00000000-0000-4000-8000-000000000001' },
    ]));
    const hosts = new AndroidHostStore({ storage, readLegacyHosts: async () => [] });
    const created = createAndroidPlatform({
      createController: () => new ConnectionController({ capability, trustStore: createLocalTrustStore(storage), retryDelaysMs: [0] }),
      hosts,
      backgroundGraceMs: () => 60_000,
      addHostRoute: '/x',
    });

    const result = await created.api.ssh.connect({ host: '10.0.0.5', port: 22, user: 'root', hostAlias: 'gw', gateway: GW } as never);
    expect(result).toEqual({ ok: false, error: expect.stringMatching(GATEWAY_REFUSAL) });
    expect(native.connects).toHaveLength(0);
    // The one lift point is unchanged: no capability argument reaches it yet.
    expect(unsupportedTransportMessage('gw', { gateway: GW })).toMatch(GATEWAY_REFUSAL);
    created.dispose();
  });
});
