import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { createFakeNativeBridge, installFakeNativeBridge } from '../../src/dev/browser/nativeBridge';
import { createMockSshPlugin, mockPresentedHostKey } from '../../src/dev/browser/mockSsh';
import { createBridgeReadyPlugin } from '../../src/dev/browser/devicePlugins';
import { createKeyVaultPlugin, createMockKeyVaultBackend, MOCK_SEED_KEY } from '../../src/dev/browser/keyVaultPlugin';

/**
 * Browser dev mode end to end through the REAL Capacitor plugin proxy: the
 * fake Android bridge is installed before @capacitor/core first loads (as
 * install.ts does in the page), then the app's own modules register their
 * plugins and the shared ConnectionController drives connect → host-key
 * trust → sessions → attach → type → echo against the mock host.
 *
 * Every app/Capacitor import below is dynamic so it evaluates after the
 * bridge is installed; a static import would hoist above it.
 */
const globals = globalThis as unknown as { androidBridge?: unknown; Capacitor?: Record<string, unknown> };

describe('browser dev mode through Capacitor', () => {
  beforeAll(() => {
    let bridge: ReturnType<typeof createFakeNativeBridge>;
    bridge = createFakeNativeBridge({
      BridgeReady: createBridgeReadyPlugin(),
      SshKeyVault: createKeyVaultPlugin(createMockKeyVaultBackend(), async () => null),
      SshCapability: createMockSshPlugin({ bridge: () => bridge, latencyMs: 0 }),
    });
    installFakeNativeBridge(globals, bridge);
  });

  afterAll(() => {
    delete globals.androidBridge;
    delete globals.Capacitor;
  });

  it('selects the android platform and answers the bridge warm-up and key vault through the plugin proxy', async () => {
    const { Capacitor } = await import('@capacitor/core');
    expect(Capacitor.getPlatform()).toBe('android');
    expect(Capacitor.isNativePlatform()).toBe(true);
    const { warmUpBridge } = await import('../../src/native/bridgeReady');
    await expect(warmUpBridge({ attempts: 1, timeoutMs: 1000 })).resolves.toMatchObject({ answered: true, attempts: 1 });
    const { listSshKeys } = await import('../../src/native/sshKeyVault');
    await expect(listSshKeys()).resolves.toEqual([MOCK_SEED_KEY]);
  });

  it('connects, asks for host-key trust, lists sessions, attaches and echoes typed input via the shared controller', async () => {
    const { ConnectionController } = await import('../../src/session/connectionController');
    const pins = new Map<string, unknown>();
    const controller = new ConnectionController({
      trustStore: {
        get: async (hostId) => (pins.get(hostId) as never) ?? null,
        record: async (hostId, pin) => { pins.set(hostId, pin); },
      },
    });
    const host = {
      hostId: 'dev@devbox.mock:22', hostname: 'devbox.mock', port: 22, username: 'dev',
      credential: { kind: 'key-handle' as const, handleId: MOCK_SEED_KEY.handleId },
    };
    const first = await controller.connect(host);
    expect(first).toMatchObject({ ok: false, reason: 'trust-required' });
    expect(controller.getSnapshot().trustDecision?.presented.fingerprintSha256).toBe((await mockPresentedHostKey()).fingerprintSha256);
    await expect(controller.acceptPresentedHostKey()).resolves.toMatchObject({ ok: true });
    expect(pins.size).toBe(1);

    const listed = await controller.refreshSessions();
    if (!listed.ok) throw new Error(listed.message);
    expect(listed.value.sessions).toHaveLength(5);
    const session = listed.value.sessions.find((row) => row.name === 'pocketshell:shell');
    if (!session) throw new Error('mock session missing');

    let output = '';
    controller.subscribeTerminalOutput((_row, bytes) => { output += new TextDecoder().decode(bytes); });
    await expect(controller.attachSession(session, { cols: 80, rows: 24 })).resolves.toMatchObject({ ok: true });
    const deadline = async (predicate: () => boolean) => {
      const until = Date.now() + 5000;
      while (!predicate() && Date.now() < until) await new Promise((resolve) => setTimeout(resolve, 20));
      return predicate();
    };
    expect(await deadline(() => output.includes('dev@devbox'))).toBe(true);
    await expect(controller.writeTerminalBytes(session, new TextEncoder().encode('echo through-capacitor\r'))).resolves.toMatchObject({ ok: true });
    expect(await deadline(() => output.includes('through-capacitor\r\n'))).toBe(true);
    const resources = await controller.getResourceSnapshot();
    expect(resources).toMatchObject({ connections: 1, ptys: 1 });
    await controller.close();
  });
});
