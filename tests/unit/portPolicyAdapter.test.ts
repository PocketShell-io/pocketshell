import { describe, expect, it, vi } from 'vitest';
import type {
  SshCapability,
  SshConnectionRef,
  SshExecOptions,
  SshExecResult,
  SshPortForwardOptions,
  SshPortForwardRef,
} from '@pocketshell/core';
import { PortForwardController } from '../../src/policy/portForwardController';
import { scanRemotePorts } from '../../src/policy/ports';

const connection: SshConnectionRef = { connectionId: 'conn-1', generationId: 'gen-1' };
const listenerOutput = [
  '<<<PS_SS_TLN>>>',
  'State Recv-Q Send-Q Local Address:Port Peer Address:Port',
  'LISTEN 0 128 0.0.0.0:22 0.0.0.0:*',
  'LISTEN 0 128 0.0.0.0:3000 0.0.0.0:*',
  '<<<PS_SS_TLNP>>>',
  'State Recv-Q Send-Q Local Address:Port Peer Address:Port Process',
  'LISTEN 0 128 0.0.0.0:3000 0.0.0.0:* users:(("python3",pid=705,fd=3))',
  '<<<PS_NETSTAT_TLNP>>>',
  '<<<PS_NETSTAT_TLN>>>',
].join('\n');

function execResult(options: SshExecOptions, overrides: Partial<SshExecResult> = {}): SshExecResult {
  return {
    requestId: options.requestId,
    connectionId: options.connectionId,
    generationId: options.generationId,
    exitCode: 0,
    stdout: listenerOutput,
    stderr: '',
    timedOut: false,
    ...overrides,
  };
}

function makeCapability() {
  const exec = vi.fn(async (options: SshExecOptions) => {
    if (/readlink/.test(options.command)) {
      return execResult(options, { stdout: '705\t/home/testuser/project\n' });
    }
    return execResult(options);
  });
  let opened = 0;
  const openPortForward = vi.fn(async (options: SshPortForwardOptions) => {
    opened += 1;
    return {
      requestId: options.requestId,
      connectionId: options.connectionId,
      generationId: options.generationId,
      forwardId: `forward-${opened}`,
      localPort: 40_000 + opened,
    } satisfies SshPortForwardRef & { requestId: string };
  });
  const closePortForward = vi.fn(async (options: SshPortForwardRef & { requestId: string }) => ({
    requestId: options.requestId,
  }));
  return {
    capability: { exec, openPortForward, closePortForward } as unknown as SshCapability,
    exec,
    openPortForward,
    closePortForward,
  };
}

describe('native-backed port discovery and tunnel adapter', () => {
  it('runs the listener and cwd probes through the current SSH generation', async () => {
    const { capability, exec } = makeCapability();
    const ids = ['scan-request', 'cwd-request'];
    const result = await scanRemotePorts(capability, connection, { createRequestId: () => ids.shift()! });

    expect(exec).toHaveBeenCalledTimes(2);
    expect(exec.mock.calls[0]?.[0]).toMatchObject({
      ...connection,
      requestId: 'scan-request',
      timeoutMs: 15_000,
    });
    expect(result).toMatchObject({
      ok: true,
      ports: [
        { port: 22, process: null, pid: null, cwd: null },
        { port: 3000, process: 'python3', pid: 705, cwd: '/home/testuser/project' },
      ],
    });
    expect(exec.mock.calls[1]?.[0].command).toContain('for pid in 705;');
  });

  it('uses JS desired-port policy and native socket effects, then restores manual intent after reconnect', async () => {
    const { capability, openPortForward, closePortForward } = makeCapability();
    const controller = new PortForwardController(capability, connection, {
      createRequestId: (() => {
        let id = 0;
        return () => `request-${++id}`;
      })(),
      desiredManualPorts: [22],
    });

    const first = await controller.reconcile({
      ok: true,
      ports: [
        { port: 22, process: 'sshd', pid: 1, cwd: null },
        { port: 3000, process: 'python3', pid: 705, cwd: null },
      ],
      error: null,
    });
    expect(first.activeForwards).toEqual([
      { remotePort: 22, localPort: 40_001, origin: 'manual' },
      { remotePort: 3000, localPort: 40_002, origin: 'auto' },
    ]);
    expect(openPortForward.mock.calls.map(([call]) => call.remotePort)).toEqual([22, 3000]);
    expect(openPortForward.mock.calls.every(([call]) => call.localPort === undefined)).toBe(true);

    await controller.setConnection({ connectionId: 'conn-1', generationId: 'gen-2' });
    expect(closePortForward).toHaveBeenCalledTimes(2);
    expect(controller.getManualDesiredPorts()).toEqual([22]);
    const afterReconnect = await controller.reconcile({ ok: false, ports: [], error: 'transport closed' });
    expect(afterReconnect.activeForwards).toEqual([
      { remotePort: 22, localPort: 40_003, origin: 'manual' },
    ]);
    expect(openPortForward.mock.calls.at(-1)?.[0].generationId).toBe('gen-2');
  });

  it('closes auto tunnels only after bounded successful misses and closes all owned handles', async () => {
    const { capability, closePortForward } = makeCapability();
    const controller = new PortForwardController(capability, connection, {
      createRequestId: (() => {
        let id = 0;
        return () => `close-${++id}`;
      })(),
    });
    const initial = await controller.reconcile({
      ok: true,
      ports: [{ port: 3000, process: null, pid: null, cwd: null }],
      error: null,
    });
    expect(initial.activeForwards).toHaveLength(1);

    const firstMiss = await controller.reconcile({
      ok: true,
      ports: [{ port: 8080, process: null, pid: null, cwd: null }],
      error: null,
    });
    expect(firstMiss.activeForwards.map((row) => row.remotePort)).toEqual([3000, 8080]);
    const secondMiss = await controller.reconcile({
      ok: true,
      ports: [{ port: 8080, process: null, pid: null, cwd: null }],
      error: null,
    });
    expect(secondMiss.activeForwards.map((row) => row.remotePort)).toEqual([8080]);

    await controller.closeAll();
    expect(controller.snapshot().activeForwards).toEqual([]);
    expect(closePortForward).toHaveBeenCalledTimes(2);
  });
});
