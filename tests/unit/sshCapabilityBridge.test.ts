import { describe, expect, it, vi } from 'vitest';
import {
  adaptSshCapabilityPlugin,
  type NativeSshCapabilityPlugin,
} from '../../src/native/sshCapability';

const requestId = 'ui-close-1734567890';

function nativePlugin(result: unknown) {
  const resourceSnapshot = vi.fn(async (_options: { requestId: string }) => result);
  return {
    capability: adaptSshCapabilityPlugin({
      addListener: vi.fn(),
      removeAllListeners: vi.fn(),
      resourceSnapshot,
    } as unknown as NativeSshCapabilityPlugin),
    resourceSnapshot,
  };
}

describe('Capacitor SSH resource snapshot bridge', () => {
  it('wraps the core request ID in Capacitor method options', async () => {
    const snapshot = {
      requestId,
      connections: 0,
      ptys: 0,
      sftpClients: 0,
      forwards: 0,
    };
    const { capability, resourceSnapshot } = nativePlugin(snapshot);

    await expect(capability.resourceSnapshot(requestId)).resolves.toEqual(snapshot);

    expect(resourceSnapshot).toHaveBeenCalledTimes(1);
    expect(resourceSnapshot).toHaveBeenCalledWith({ requestId });
  });

  it.each([
    ['a mismatched request ID', { requestId: 'other-request', connections: 0, ptys: 0, sftpClients: 0, forwards: 0 }],
    ['a missing counter', { requestId, connections: 0, ptys: 0, sftpClients: 0 }],
    ['a negative counter', { requestId, connections: 0, ptys: -1, sftpClients: 0, forwards: 0 }],
  ])('rejects %s instead of accepting an unverified snapshot', async (_description, result) => {
    const { capability } = nativePlugin(result);

    await expect(capability.resourceSnapshot(requestId)).rejects.toThrow(
      'Native resourceSnapshot returned an invalid request ID or resource count.',
    );
  });

  it('propagates a native bridge rejection', async () => {
    const nativeError = { code: 'INVALID_ARGUMENT', message: 'requestId is required' };
    const { capability } = nativePlugin(Promise.reject(nativeError));

    await expect(capability.resourceSnapshot(requestId)).rejects.toEqual(nativeError);
  });
});
