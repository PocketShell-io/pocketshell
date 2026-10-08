import { describe, expect, it, vi } from 'vitest';
import { validatedAndroidGateway, resolveAndroidGatewayTarget } from '@/platform/android/gatewayTarget';
import { AndroidHostStore } from '@/platform/android/hostStore';

const gateway = { serverUrl: 'wss://gateway.pocketshell.io', deviceId: 'win35-device' };
const request = { host: 'loopback-label', port: 22, user: 'alexey', gateway };
function pairings() {
  return {
    currentAccount: vi.fn(async () => ({ signedIn: true, accountSubject: 'account-a' })),
    list: vi.fn(async () => [{ ...gateway, keyHandleId: 'vault-a', fingerprintSha256: `SHA256:${'A'.repeat(43)}`, pairedAtEpochMs: 1 }]),
  };
}

describe('Android gateway target integration', () => {
  it('resolves only the current native pairing key and carries the gateway marker', async () => {
    const native = pairings();
    expect(await resolveAndroidGatewayTarget(request, native, 'vault-a')).toEqual({
      hostId: 'gateway:wss://gateway.pocketshell.io/win35-device', hostname: 'loopback-label', port: 22,
      username: 'alexey', credential: { kind: 'key-handle', handleId: 'vault-a' }, gateway,
    });
    expect(native.currentAccount).toHaveBeenCalledOnce();
    expect(native.list).toHaveBeenCalledOnce();
  });
  it('rejects malformed insecure credential-bearing and conflicting markers before native reads', async () => {
    const native = pairings();
    for (const marker of [null, undefined, {}, { ...gateway, serverUrl: 'ws://gateway.pocketshell.io' },
      { ...gateway, serverUrl: 'wss://user:password@gateway.pocketshell.io' }, { ...gateway, serverUrl: 'wss://gateway.pocketshell.io?token=secret' },
      { ...gateway, serverUrl: 'wss://gateway.pocketshell.io/secret/..' },
      { ...gateway, serverUrl: 'wss://gate\tway.pocketshell.io' },
      { ...gateway, serverUrl: 'wss://gate\nway.pocketshell.io' },
      { ...gateway, serverUrl: 'wss://%67ateway.pocketshell.io' }]) {
      await expect(resolveAndroidGatewayTarget({ ...request, gateway: marker }, native)).rejects.toThrow();
    }
    await expect(resolveAndroidGatewayTarget({ ...request, link: null }, native)).rejects.toThrow('both');
    expect(native.currentAccount).not.toHaveBeenCalled();
    expect(native.list).not.toHaveBeenCalled();
  });
  it('refuses signed-out accounts before pairing lookup', async () => {
    const native = pairings(); native.currentAccount.mockResolvedValue({ signedIn: false, accountSubject: '' });
    await expect(resolveAndroidGatewayTarget(request, native)).rejects.toThrow('Sign in');
    expect(native.list).not.toHaveBeenCalled();
  });
  it('refuses missing sibling-device and sibling-origin pairings without direct fallback', async () => {
    const native = pairings();
    for (const target of [{ ...gateway, deviceId: 'other-device' }, { ...gateway, serverUrl: 'wss://other.pocketshell.io' }]) {
      await expect(resolveAndroidGatewayTarget({ ...request, gateway: target }, native)).rejects.toThrow('Pair this device');
    }
  });
  it('refuses key changes and rereads pairings for every dial', async () => {
    const native = pairings();
    await expect(resolveAndroidGatewayTarget(request, native, 'vault-other')).rejects.toThrow('does not match');
    await resolveAndroidGatewayTarget(request, native, 'vault-a');
    native.list.mockResolvedValue([]);
    await expect(resolveAndroidGatewayTarget(request, native, 'vault-a')).rejects.toThrow('Pair this device');
    expect(native.list).toHaveBeenCalledTimes(3);
  });
  it('carries explicit gateway requests through the host store without saved-host or legacy lookup', async () => {
    const native = pairings();
    const legacy = vi.fn(async () => []);
    const storage = { getItem: vi.fn(() => null), setItem: vi.fn() };
    const store = new AndroidHostStore({ storage, readLegacyHosts: legacy, gatewayPairings: native });
    expect(await store.resolve(request)).toHaveProperty('gateway', gateway);
    expect(storage.getItem).not.toHaveBeenCalled();
    expect(legacy).not.toHaveBeenCalled();
  });
  it('normalizes secure origins but never accepts invalid device identifiers or nonobjects', () => {
    expect(validatedAndroidGateway({ ...gateway, serverUrl: 'wss://GATEWAY.pocketshell.io/' })).toEqual(gateway);
    for (const marker of ['gateway', [], { ...gateway, deviceId: '/bad' }]) expect(() => validatedAndroidGateway(marker)).toThrow('malformed');
  });
});
