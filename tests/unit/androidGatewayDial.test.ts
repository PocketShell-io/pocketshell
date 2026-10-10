import { describe, expect, it, vi } from 'vitest';
import { ConnectionController, type SshCapability, type SshConnectOptions, type HostEntry } from '@pocketshell/core';
import {
  adaptSshCapabilityPlugin,
  loadNativeTransportCapabilities,
  type NativeSshCapabilityPlugin,
  type NativeTransportCapabilities,
} from '@/native/sshCapability';
import { listGatewayPairings, type GatewayPairingRow, type NativeGatewayPairingPlugin } from '@/native/gatewayPairing';
import { createAndroidPlatform, unsupportedTransportMessage } from '@/platform/android/androidApi';
import { AndroidHostStore, ANDROID_HOSTS_STORAGE_KEY } from '@/platform/android/hostStore';
import { createLocalTrustStore } from '@/platform/android/trustStore';
import { FakeNative, MemoryStorage, settle } from './support/androidFakeNative';

/**
 * #3086 slice 3: Android dials the PocketShell gateway. The one lift point is
 * `unsupportedTransportMessage`: a gateway host is admitted only when the
 * NATIVE SshCapability plugin reported the gateway transport in its own
 * `transportCapabilities` reply, and then dialled through the same core
 * ConnectionController and native `connect()` as any host, with the key of
 * the phone's pairing for that device. Everything else stays refused before
 * any effect, and a gateway host is never dialled as plain SSH (D28).
 */
const GW = { serverUrl: 'wss://gateway.example', deviceId: 'dev-1' };
const LINK = { relayUrl: 'wss://relay.example', hostId: 'h-1' };
const PAIRED_KEY = '00000000-0000-4000-8000-0000000000aa';
const PHONE_KEY = '00000000-0000-4000-8000-000000000001';
const PAIRING: GatewayPairingRow = {
  serverUrl: 'wss://gateway.example',
  deviceId: 'dev-1',
  fingerprintSha256: 'SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU',
  pinKind: 'host-key',
  keyHandleId: PAIRED_KEY,
};
const GATEWAY_UNSUPPORTED = /is reached through the PocketShell gateway, which this device can't connect through yet\. Nothing was dialled\.$/;
const GATEWAY_INVALID = /has a PocketShell gateway setting this version can't read\. Nothing was dialled\.$/;
const LINK_AND_GATEWAY = /is set up for both the PocketShell gateway and a relay link, so it is unclear which to use\. Nothing was dialled\.$/;
const LINK_UNSUPPORTED = /is reached through a PocketShell relay link, which this device can't connect through yet\. Nothing was dialled\.$/;
const INSECURE = /uses an unencrypted PocketShell gateway address \(ws:\/\/\), which PocketShell for Android does not connect to\. Nothing was dialled\.$/;

/** FakeNative, plus what the Java plugin adds for a gateway dial: the pin receipt. */
class GatewayNative extends FakeNative {
  /** Native gateway refusals for the next dials, oldest first. */
  readonly gatewayFailures: unknown[] = [];

  override capability(): SshCapability {
    const base = super.capability();
    return {
      ...base,
      connect: async (options: SshConnectOptions) => {
        if (options.gateway && this.gatewayFailures.length > 0) {
          this.connects.push(options);
          throw this.gatewayFailures.shift();
        }
        const result = await base.connect(options);
        return options.gateway ? { ...result, gatewayHostKeyVerified: true as const } : result;
      },
    } as SshCapability;
  }
}

type TransportReply = (options: { requestId: string }) => Promise<unknown>;
const reportsGateway: TransportReply = async ({ requestId }) => ({ requestId, gatewayTransport: true, linkTransport: false });
const reportsNoGateway: TransportReply = async ({ requestId }) => ({ requestId, gatewayTransport: false, linkTransport: false });
const olderBuild: TransportReply = async () => { throw { code: 'UNIMPLEMENTED' }; };

function pairingPlugin(rows: () => GatewayPairingRow[] | Promise<never>): NativeGatewayPairingPlugin & { list: ReturnType<typeof vi.fn> } {
  return {
    list: vi.fn(async ({ requestId }: { requestId: string }) => ({ requestId, pairings: await rows() })),
  } as unknown as NativeGatewayPairingPlugin & { list: ReturnType<typeof vi.fn> };
}

interface Setup {
  reply?: TransportReply;
  rows?: () => GatewayPairingRow[] | Promise<never>;
  account?: HostEntry[];
  phone?: Array<Record<string, unknown>>;
}

/** The production wiring of src/sharedApp/platform.ts, over fakes at the native edges. */
async function setup(options: Setup = {}) {
  const native = new GatewayNative();
  const transports: NativeTransportCapabilities = { gatewayTransport: false };
  const plugin = {
    ...native.capability(),
    resourceSnapshot: vi.fn(),
    transportCapabilities: vi.fn(options.reply ?? reportsGateway),
  } as unknown as NativeSshCapabilityPlugin;
  const capability = adaptSshCapabilityPlugin(plugin, transports);
  const probe = loadNativeTransportCapabilities(plugin, transports);
  const pairings = pairingPlugin(options.rows ?? (() => [PAIRING]));
  const storage = new MemoryStorage();
  storage.setItem(ANDROID_HOSTS_STORAGE_KEY, JSON.stringify(options.phone ?? []));
  const hosts = new AndroidHostStore({ storage, readLegacyHosts: async () => [] });
  const adopt = vi.fn(async () => false);
  const created = createAndroidPlatform({
    createController: () => new ConnectionController({ capability, trustStore: createLocalTrustStore(storage), retryDelaysMs: [0, 0] }),
    hosts,
    backgroundGraceMs: () => 60_000,
    addHostRoute: '/x',
    accountHosts: options.account
      ? { find: async (request) => options.account!.find((h) => h.hostname === request.host && h.port === (request.port ?? 22)) ?? null, adopt }
      : undefined,
    gatewayTransport: async () => (await probe).gatewayTransport,
    gatewayPairings: { list: () => listGatewayPairings(pairings) },
  });
  return { native, created, pairings, adopt, hosts, transports };
}

const gatewayRequest = (gateway: unknown = GW, extra: Record<string, unknown> = {}) =>
  ({ host: 'gw-host', port: 22, user: 'alexey', hostAlias: 'gw', gateway, ...extra }) as never;

describe('Android dials the gateway behind the native capability (#3086 slice 3)', () => {
  it('admits a valid gateway host once the native plugin reports the transport, and dials it through native connect with the pairing key', async () => {
    const { native, created, pairings } = await setup();
    const result = await created.api.ssh.connect(gatewayRequest());
    expect(result).toEqual({ ok: true, connectionId: expect.any(String) });
    expect(pairings.list).toHaveBeenCalledTimes(1);
    expect(native.connects).toHaveLength(1);
    const dial = native.connects[0]!;
    expect(dial.gateway).toEqual(GW);
    expect(dial.credential).toEqual({ kind: 'key-handle', handleId: PAIRED_KEY });
    expect(dial.username).toBe('alexey');
    expect(dial.hostId).toBe('gateway:wss://gateway.example/dev-1');
    // The pin is native-only: core never sends one for a gateway dial.
    expect(dial.expectedHostKey).toBeNull();
    expect(dial).not.toHaveProperty('link');
    created.dispose();
  });

  it.each([
    ['the native plugin reports no gateway transport', reportsNoGateway],
    ['an older native build without the capability method', olderBuild],
  ])('refuses every gateway host when %s, before any pairing read or socket', async (_label, reply) => {
    const { native, created, pairings } = await setup({ reply });
    const result = await created.api.ssh.connect(gatewayRequest());
    expect(result).toEqual({ ok: false, error: expect.stringMatching(GATEWAY_UNSUPPORTED) });
    expect(pairings.list).not.toHaveBeenCalled();
    expect(native.connects).toHaveLength(0);
    created.dispose();
  });

  it('decides on the native reply, never a constant: the lift point refuses with the transport off and admits with it on', () => {
    expect(unsupportedTransportMessage('gw', { gateway: GW }, { gateway: false })).toMatch(GATEWAY_UNSUPPORTED);
    expect(unsupportedTransportMessage('gw', { gateway: GW }, { gateway: true })).toBeNull();
  });

  it.each([
    ['a null gateway marker', { gateway: null }, GATEWAY_INVALID],
    ['a malformed gateway marker', { gateway: 'not-a-target' }, GATEWAY_INVALID],
    ['a gateway marker with a bad device id', { gateway: { serverUrl: 'wss://gateway.example', deviceId: '!' } }, GATEWAY_INVALID],
    ['a gateway marker with a path', { gateway: { serverUrl: 'wss://gateway.example/x', deviceId: 'dev-1' } }, GATEWAY_INVALID],
    ['link and gateway together', { gateway: GW, link: LINK }, LINK_AND_GATEWAY],
    ['a link marker alone (no link transport on Android)', { link: LINK }, LINK_UNSUPPORTED],
    ['an unencrypted ws:// gateway', { gateway: { serverUrl: 'ws://gateway.example', deviceId: 'dev-1' } }, INSECURE],
  ])('with the transport on, still refuses %s before any pairing read, prompt or socket', async (_label, marker, refusal) => {
    const { native, created, pairings, adopt } = await setup({ account: [] });
    const result = await created.api.ssh.connect({ host: 'gw-host', port: 22, user: 'alexey', hostAlias: 'gw', ...marker } as never);
    expect(result).toEqual({ ok: false, error: expect.stringMatching(refusal) });
    expect(pairings.list).not.toHaveBeenCalled();
    expect(adopt).not.toHaveBeenCalled();
    expect(native.connects).toHaveLength(0);
    created.dispose();
  });

  it('refuses a signed-out phone with the sign-in advice, and an unpaired device with the pairing advice, nothing dialled', async () => {
    const signedOut = await setup({ rows: () => Promise.reject(Object.assign(new Error('Sign in with Google first.'), { code: 'NOT_SIGNED_IN' })) });
    expect(await signedOut.created.api.ssh.connect(gatewayRequest()))
      .toEqual({ ok: false, error: 'Sign in to your PocketShell account to connect through the gateway.' });
    expect(signedOut.native.connects).toHaveLength(0);
    signedOut.created.dispose();

    const unpaired = await setup({ rows: () => [{ ...PAIRING, deviceId: 'another-device' }] });
    expect(await unpaired.created.api.ssh.connect(gatewayRequest()))
      .toEqual({ ok: false, error: 'This device is not paired with that host for this SSH key — pair it again, then reconnect.' });
    expect(unpaired.native.connects).toHaveLength(0);
    unpaired.created.dispose();
  });

  it('never falls back to plain SSH: a gateway refusal reaches the user and no direct dial follows, even for a phone host at the same address', async () => {
    const { native, created } = await setup({
      phone: [{ name: 'gw', hostname: 'gw-host', port: 22, user: 'alexey', keyHandleId: PHONE_KEY }],
    });
    native.gatewayFailures.push(Object.assign(new Error('closed'), { code: 'GATEWAY_CLOSED', data: { gatewayCloseCode: 4404 } }));
    const result = await created.api.ssh.connect(gatewayRequest());
    expect(result).toEqual({ ok: false, error: 'That host is not registered on the gateway.' });
    expect(native.connects).toHaveLength(1);
    expect(native.connects.every((dial) => dial.gateway !== undefined)).toBe(true);
    created.dispose();
  });

  it('an account host carrying a gateway marker is dialled through the gateway, never adopted as a plain SSH phone host', async () => {
    const account: HostEntry = {
      name: 'gw-account', hostname: 'gw-host', port: 22, user: 'alexey', identityFile: null, proxyJump: null,
      forwardAgent: false, localForwards: [], remoteForwards: [], fromConfig: false, gateway: GW,
    };
    const { native, created, adopt, hosts } = await setup({ account: [account] });
    // A request that lost the marker on the way still dials the account entry's gateway.
    const result = await created.api.ssh.connect({ host: 'gw-host', port: 22, user: 'alexey' });
    expect(result).toEqual({ ok: true, connectionId: expect.any(String) });
    expect(adopt).not.toHaveBeenCalled();
    expect(hosts.savedHosts()).toEqual([]);
    expect(native.connects).toHaveLength(1);
    expect(native.connects[0]!.gateway).toEqual(GW);
    created.dispose();
  });

  it('reconnect stays on the gateway: the controller re-dials native connect with the same gateway target and pairing key', async () => {
    const { native, created } = await setup();
    const result = await created.api.ssh.connect(gatewayRequest());
    if (!result.ok) throw new Error(result.error);
    await created.hub.sessionsList(result.connectionId!);
    // The transport drops; the controller (the one reconnect owner) re-dials.
    native.dropTransport();
    await settle(() => native.connects.length === 2);
    for (const dial of native.connects) {
      expect(dial.gateway).toEqual(GW);
      expect(dial.credential).toEqual({ kind: 'key-handle', handleId: PAIRED_KEY });
    }
    created.dispose();
  });

  it('reads public pairing rows and fails closed on any other reply', async () => {
    expect(await listGatewayPairings(pairingPlugin(() => [PAIRING]))).toEqual([PAIRING]);
    const bad: NativeGatewayPairingPlugin = { list: async () => ({ requestId: 'other', pairings: [PAIRING] }) } as never;
    await expect(listGatewayPairings(bad)).rejects.toThrow('could not be read');
    const malformed = pairingPlugin(() => [{ ...PAIRING, keyHandleId: '' }]);
    await expect(listGatewayPairings(malformed)).rejects.toThrow('could not be read');
  });
});
