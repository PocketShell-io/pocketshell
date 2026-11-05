import { describe, expect, it, vi } from 'vitest';
import { createAndroidGatewayDirectory } from '@/platform/android/gatewayDirectory';
import { AndroidHostStore, ANDROID_HOSTS_STORAGE_KEY } from '@/platform/android/hostStore';
import type { NativeSshKeyVaultPlugin } from '@/native/sshKeyVault';
import type { NativeGatewayPairingPlugin } from '@/native/gatewayPairing';

const server = 'wss://gateway.pocketshell.io';
const handle = '00000000-0000-4000-8000-000000000001';
const fingerprint = `SHA256:${'a'.repeat(43)}`;
const pin = `SHA256:${'b'.repeat(43)}`;
const publicKey = `ssh-ed25519 ${'A'.repeat(64)} phone-fixture`;
const route = { serverUrl: server, deviceId: 'fixture-device' };
const input = { name: 'Test machine', username: 'tester', gateway: route, pin, keyId: handle };

function setup() {
  let currentEmail = 'tester@example.test';
  const values = new Map<string, string>();
  const storage = { getItem: (name: string) => values.get(name) ?? null,
    setItem: (name: string, value: string) => { values.set(name, value); } };
  const hosts = new AndroidHostStore({ storage, readLegacyHosts: async () => [] });
  const metadata = { handleId: handle, label: 'Phone fixture', algorithm: 'ssh-ed25519',
    fingerprintSha256: fingerprint, passphraseRequired: false, createdAt: 1 };
  const row = { ...route, fingerprintSha256: pin, pinKind: 'fingerprint', keyHandleId: handle };
  const native = {
    serverUrl: vi.fn(async ({ requestId }) => ({ requestId, serverUrl: server })),
    devices: vi.fn(async ({ requestId }) => ({ requestId, devices: [{ id: route.deviceId, revoked: false,
      presence: { online: true, observed_at: '2026-01-01T00:00:00Z' }, ssh_host_key: 'advisory-only' }] })),
    list: vi.fn(async ({ requestId }) => ({ requestId, pairings: [] })),
    pair: vi.fn(async ({ requestId }) => ({ requestId, pairing: row })),
  };
  const keys = { listKeys: vi.fn(async () => ({ keys: [metadata] })),
    publicKey: vi.fn(async () => ({ publicKey })), generateKey: vi.fn(async () => metadata) };
  const api = createAndroidGatewayDirectory({ native: native as unknown as Parameters<typeof createAndroidGatewayDirectory>[0]['native'],
    account: { status: async () => ({ signedIn: !!currentEmail, email: currentEmail || null, packageName: 'fixture' }) },
    keys: keys as unknown as NativeSshKeyVaultPlugin, hosts });
  return { api, native, hosts, storage, values, keys, switchAccount: (email: string) => { currentEmail = email; } };
}

describe('Android gateway picker integration', () => {
  it('lists native account devices and keeps their advertised key advisory', async () => {
    const { api, native } = setup();
    const devices = await api.devices(server);
    expect(devices[0]).toMatchObject({ id: route.deviceId, presence: { state: 'online' }, advisoryHostKey: 'advisory-only' });
    expect(native.devices).toHaveBeenCalledWith(expect.objectContaining({ expectedAccountEmail: 'tester@example.test' }));
    expect(native.pair).not.toHaveBeenCalled();
  });
  it('refuses another gateway before requesting account devices', async () => {
    const { api, native } = setup();
    await expect(api.devices('wss://foreign.example')).rejects.toMatchObject({ code: 'GATEWAY_DEVICES_INVALID_SERVER' });
    expect(native.devices).not.toHaveBeenCalled();
  });
  it('drops a list if the account changes during its native request', async () => {
    const f = setup();
    f.native.devices.mockImplementation(async ({ requestId }) => { f.switchAccount('other@example.test'); return { requestId, devices: [] }; });
    await expect(f.api.devices(server)).rejects.toMatchObject({ code: 'GATEWAY_PAIRING_ACCOUNT_CHANGED' });
  });
  it('rejects a reply belonging to another native request', async () => {
    const f = setup();
    f.native.serverUrl.mockResolvedValue({ requestId: 'stale', serverUrl: server });
    await expect(f.api.devices(server)).rejects.toMatchObject({ code: 'GATEWAY_DEVICES_INVALID_SERVER' });
    expect(f.native.devices).not.toHaveBeenCalled();
  });
  it('pairs the typed pin and chosen phone key then preserves the route across restart', async () => {
    const f = setup();
    expect(await f.api.addDevice(input)).toMatchObject({ hostName: input.name, pairing: { ...route, fingerprint: pin, keyId: handle } });
    expect(f.native.pair).toHaveBeenCalledWith(expect.objectContaining({ hostKey: pin, keyHandleId: handle,
      expectedAccountEmail: 'tester@example.test', ...route }));
    const restarted = new AndroidHostStore({ storage: f.storage, readLegacyHosts: async () => [] });
    expect((await restarted.list())[0]).toMatchObject({ name: input.name, gateway: route });
  });
  it('does not save a host if native pairing refuses it', async () => {
    const f = setup();
    f.native.pair.mockRejectedValue({ code: 'GATEWAY_PAIRING_INVALID' });
    await expect(f.api.addDevice(input)).rejects.toMatchObject({ code: 'GATEWAY_PAIRING_INVALID' });
    expect(f.hosts.savedHosts()).toEqual([]);
  });
  it('does not replace an existing host with a gateway device of the same name', async () => {
    const f = setup();
    f.hosts.save({ name: input.name, hostname: 'ordinary.example', user: 'tester', port: 22, keyHandleId: handle });
    await expect(f.api.addDevice(input)).rejects.toThrow('already uses this name');
    expect(f.native.pair).not.toHaveBeenCalled();
    expect(f.hosts.savedHosts()[0]?.hostname).toBe('ordinary.example');
  });
  it('never turns a malformed stored gateway marker into an ordinary host', async () => {
    const f = setup();
    f.values.set(ANDROID_HOSTS_STORAGE_KEY, JSON.stringify([{ name: 'invalid', hostname: 'fixture-device', user: 'tester',
      port: 22, keyHandleId: handle, gateway: { serverUrl: server } }]));
    expect(await f.hosts.list()).toEqual([]);
  });
});
