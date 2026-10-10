import { describe, expect, it, vi } from 'vitest';
import {
  adaptSshCapabilityPlugin,
  loadNativeTransportCapabilities,
  type NativeSshCapabilityPlugin,
  type NativeTransportCapabilities,
} from '@/native/sshCapability';
import { FakeNative } from './support/androidFakeNative';

/**
 * #3086 slice 2: the native SshCapability plugin reports the gateway
 * transport, and core's `SshCapability.gatewayTransport` is answered from
 * that report only — never a JS constant. What the Android boundary does
 * with it (slice 3) is androidGatewayDial.test.ts.
 */

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
});
