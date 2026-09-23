import { afterEach, describe, expect, it, vi } from 'vitest';
import { HostCliFailed, type HostKeyPin, type SessionRow } from '@pocketshell/core';
import {
  ConnectionController,
  type HostKeyTrustStore,
} from '../../src/session/connectionController';
import {
  SshCapabilityError,
  type SshCapabilityPlugin,
  type SshConnectOptions,
  type SshConnectResult,
  type SshConnectionRef,
  type SshConnectionStateEvent,
  type SshExecOptions,
  type SshExecResult,
  type SshHostTarget,
  type SshPortForwardOptions,
  type SshPortForwardRef,
  type SshPtyOpenOptions,
  type SshPtyReadOptions,
  type SshPtyReadResult,
  type SshPtyRef,
  type SshPtyResizeOptions,
  type SshPtyWriteOptions,
  type SshResourceSnapshot,
} from '../../src/native/sshCapability';

const PIN = { keyType: 'ssh-ed25519', keyB64: 'AQIDBA==' } as const;
const HOST_KEY = { ...PIN, fingerprintSha256: 'SHA256:abc123' } as const;

function session(name: string): SessionRow {
  return {
    name,
    id: `${name}-id`,
    workspace: '/work',
    tag: null,
    engine: null,
    profile: null,
    agent: null,
    agentState: null,
    agentStateSource: null,
    attached: false,
    createdEpoch: null,
    activityEpoch: null,
  };
}

function base64(bytes: Uint8Array): string {
  let binary = '';
  for (const value of bytes) binary += String.fromCharCode(value);
  return btoa(binary);
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

async function waitFor(predicate: () => boolean): Promise<void> {
  for (let attempt = 0; attempt < 100; attempt += 1) {
    if (predicate()) return;
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
  throw new Error('Timed out waiting for the controller condition.');
}

class FakeCapability {
  readonly connectCalls: SshConnectOptions[] = [];
  readonly execCommands: string[] = [];
  readonly openPtyCalls: SshPtyOpenOptions[] = [];
  readonly closePtyCalls: SshPtyRef[] = [];
  readonly writeCalls: SshPtyWriteOptions[] = [];
  readonly resizeCalls: SshPtyResizeOptions[] = [];
  readonly connections = new Map<string, string>();
  readonly ptys = new Map<string, SshPtyRef>();
  readonly forwards = new Map<string, SshPortForwardRef>();
  private readonly listeners = new Set<(event: SshConnectionStateEvent) => void>();
  private readonly pendingReads: Array<{
    options: SshPtyReadOptions;
    resolve: (value: SshPtyReadResult) => void;
  }> = [];
  private readonly queuedOutput = new Map<string, Array<{ bytes: Uint8Array; eof: boolean }>>();
  readonly sessions: SessionRow[] = [session('alpha'), session('beta')];
  createRequests = 0;
  killRequests = 0;
  createAfterApplyFailure = false;
  killAfterApplyFailure = false;
  nextWriteError: unknown = null;
  private connectionOrdinal = 0;
  private ptyOrdinal = 0;
  private readonly graceScheduled = new Set<string>();

  addListener = async (
    _eventName: 'connectionState',
    listener: (event: SshConnectionStateEvent) => void,
  ) => {
    this.listeners.add(listener);
    return { remove: async () => this.listeners.delete(listener) };
  };

  connect = async (options: SshConnectOptions): Promise<SshConnectResult> => {
    this.connectCalls.push(options);
    if (!options.expectedHostKey || options.expectedHostKey.keyType !== PIN.keyType || options.expectedHostKey.keyB64 !== PIN.keyB64) {
      throw new SshCapabilityError('Host key needs a user decision.', 'HOST_KEY_REJECTED', HOST_KEY);
    }
    const connectionId = `connection-${++this.connectionOrdinal}`;
    this.connections.set(connectionId, options.generationId);
    return { requestId: options.requestId, connectionId, generationId: options.generationId, hostKey: HOST_KEY };
  };

  getConnectionState = async (ref: SshConnectionRef & { requestId: string }) => ({
    requestId: ref.requestId,
    state: this.connections.has(ref.connectionId) ? 'connected' as const : 'closed' as const,
  });

  closeConnection = async (ref: SshConnectionRef & { requestId: string }) => {
    this.connections.delete(ref.connectionId);
    for (const [channelId, pty] of this.ptys) {
      if (pty.connectionId === ref.connectionId) this.closePtyRef(pty);
    }
    this.graceScheduled.delete(ref.connectionId);
    return { requestId: ref.requestId };
  };

  scheduleClose = async (ref: SshConnectionRef & { requestId: string }) => {
    this.graceScheduled.add(ref.connectionId);
    return { requestId: ref.requestId };
  };

  cancelScheduledClose = async (ref: SshConnectionRef & { requestId: string }) => {
    const cancelled = this.graceScheduled.delete(ref.connectionId);
    return { requestId: ref.requestId, cancelled };
  };

  exec = async (options: SshExecOptions): Promise<SshExecResult> => {
    this.execCommands.push(options.command);
    if (options.command.includes('sessions list')) {
      return {
        requestId: options.requestId,
        connectionId: options.connectionId,
        generationId: options.generationId,
        exitCode: 0,
        stdout: JSON.stringify({ schema: 3, sessions: this.sessions.map(({ name, id, workspace, attached }) => ({ name, id, workspace, attached })) }),
        stderr: '',
        timedOut: false,
      };
    }
    if (options.command.includes('sessions create')) {
      this.createRequests += 1;
      const name = options.command.split(' -- ').at(-1)?.replace(/^'|'$/g, '') ?? 'created';
      if (this.createAfterApplyFailure) {
        this.createAfterApplyFailure = false;
        this.sessions.push(session(name));
        throw new HostCliFailed(options.command, null, '', false, 'SSH transport ended after the host applied create.');
      }
      this.sessions.push(session(name));
      return {
        requestId: options.requestId,
        connectionId: options.connectionId,
        generationId: options.generationId,
        exitCode: 0,
        stdout: JSON.stringify({ schema: 3, name, id: `${name}-id`, created: true }),
        stderr: '',
        timedOut: false,
      };
    }
    if (options.command.includes('sessions kill')) {
      this.killRequests += 1;
      const name = options.command.split(' -- ').at(-1)?.replace(/^'|'$/g, '') ?? '';
      if (this.killAfterApplyFailure) {
        this.killAfterApplyFailure = false;
        const index = this.sessions.findIndex((row) => row.name === name);
        if (index >= 0) this.sessions.splice(index, 1);
        throw new HostCliFailed(options.command, null, '', false, 'SSH transport ended after the host applied kill.');
      }
      return {
        requestId: options.requestId,
        connectionId: options.connectionId,
        generationId: options.generationId,
        exitCode: 0,
        stdout: '',
        stderr: '',
        timedOut: false,
      };
    }
    throw new Error(`Unexpected HostCliCore command: ${options.command}`);
  };

  openPty = async (options: SshPtyOpenOptions) => {
    this.openPtyCalls.push(options);
    const pty: SshPtyRef = {
      connectionId: options.connectionId,
      generationId: options.generationId,
      channelId: `pty-${++this.ptyOrdinal}`,
    };
    this.ptys.set(pty.channelId, pty);
    return { ...pty, requestId: options.requestId };
  };

  readPty = async (options: SshPtyReadOptions): Promise<SshPtyReadResult> => {
    const queued = this.queuedOutput.get(options.channelId)?.shift();
    if (queued) return this.readResult(options, queued.bytes, queued.eof);
    return new Promise((resolve) => this.pendingReads.push({ options, resolve }));
  };

  writePty = async (options: SshPtyWriteOptions) => {
    this.writeCalls.push(options);
    if (this.nextWriteError) {
      const error = this.nextWriteError;
      this.nextWriteError = null;
      throw error;
    }
    return { ...options };
  };

  resizePty = async (options: SshPtyResizeOptions) => {
    this.resizeCalls.push(options);
    return { ...options };
  };

  closePty = async (options: SshPtyRef & { requestId: string }) => {
    this.closePtyCalls.push(options);
    this.closePtyRef(options);
    return { requestId: options.requestId };
  };

  sftpList = async (options: { requestId: string }) => ({ requestId: options.requestId, entries: [] });
  sftpRead = async (options: { requestId: string }) => ({ requestId: options.requestId, dataBase64: '' });
  sftpWrite = async (options: { requestId: string }) => ({ requestId: options.requestId, bytesWritten: 0 });
  sftpMkdir = async (options: { requestId: string }) => ({ requestId: options.requestId });
  sftpRename = async (options: { requestId: string }) => ({ requestId: options.requestId });
  sftpDelete = async (options: { requestId: string }) => ({ requestId: options.requestId });

  openPortForward = async (options: SshPortForwardOptions): Promise<SshPortForwardRef & { requestId: string }> => {
    const forward = {
      connectionId: options.connectionId,
      generationId: options.generationId,
      forwardId: 'forward-1',
      localPort: options.localPort ?? 4000,
    };
    this.forwards.set(forward.forwardId, forward);
    return { ...forward, requestId: options.requestId };
  };

  closePortForward = async (options: SshPortForwardRef & { requestId: string }) => {
    this.forwards.delete(options.forwardId);
    return { requestId: options.requestId };
  };

  resourceSnapshot = async (requestId: string): Promise<SshResourceSnapshot> => ({
    requestId,
    connections: this.connections.size,
    ptys: this.ptys.size,
    sftpClients: 0,
    forwards: this.forwards.size,
  });

  emitOutput(channelId: string, bytes: Uint8Array, eof = false): void {
    const index = this.pendingReads.findIndex((pending) => pending.options.channelId === channelId);
    if (index < 0) {
      const queue = this.queuedOutput.get(channelId) ?? [];
      queue.push({ bytes, eof });
      this.queuedOutput.set(channelId, queue);
      return;
    }
    const [pending] = this.pendingReads.splice(index, 1);
    pending!.resolve(this.readResult(pending!.options, bytes, eof));
  }

  emitLost(reason = 'socket reset'): void {
    const connection = [...this.connections.entries()].at(-1);
    if (!connection) throw new Error('No connected fake SSH connection to drop.');
    const [connectionId, generationId] = connection;
    const event: SshConnectionStateEvent = { connectionId, generationId, state: 'lost', reason };
    for (const listener of this.listeners) listener(event);
  }

  pendingReadsFor(channelId: string): number {
    return this.pendingReads.filter((pending) => pending.options.channelId === channelId).length;
  }

  private readResult(options: SshPtyReadOptions, bytes: Uint8Array, eof: boolean): SshPtyReadResult {
    return {
      requestId: options.requestId,
      connectionId: options.connectionId,
      generationId: options.generationId,
      channelId: options.channelId,
      sequence: options.sequence + (bytes.length > 0 ? 1 : 0),
      dataBase64: base64(bytes),
      eof,
    };
  }

  private closePtyRef(pty: SshPtyRef): void {
    this.ptys.delete(pty.channelId);
    for (let index = this.pendingReads.length - 1; index >= 0; index -= 1) {
      const pending = this.pendingReads[index]!;
      if (pending.options.channelId === pty.channelId) {
        this.pendingReads.splice(index, 1);
        pending.resolve(this.readResult(pending.options, new Uint8Array(), true));
      }
    }
  }
}

const host: SshHostTarget = {
  hostId: 'fixture-host',
  hostname: '127.0.0.1',
  port: 2222,
  username: 'testuser',
  credential: { kind: 'private-key', privateKeyPem: 'test-private-key' },
};

function trustStore(initial: HostKeyPin | null = null) {
  let pin = initial;
  const store: HostKeyTrustStore = {
    get: vi.fn(async () => pin),
    record: vi.fn(async (_hostId, next) => { pin = next; }),
  };
  return { store, current: () => pin };
}

function controllerFor(
  capability: FakeCapability,
  trusted = trustStore(),
  options: { now?: () => number } = {},
) {
  let id = 0;
  const controller = new ConnectionController({
    capability: capability as unknown as SshCapabilityPlugin,
    trustStore: trusted.store,
    createId: () => `request-${++id}`,
    retryDelaysMs: [0],
    ...options,
  });
  return { controller, trusted };
}

async function connectAndList(controller: ConnectionController) {
  const connected = await controller.connect(host);
  expect(connected.ok).toBe(true);
  const listing = await controller.refreshSessions();
  expect(listing.ok).toBe(true);
  return listing;
}

describe('JS connection and session policy', () => {
  const controllers: ConnectionController[] = [];

  afterEach(async () => {
    await Promise.all(controllers.splice(0).map((controller) => controller.close()));
  });

  it('rejects unknown and changed host keys until the user accepts the presented key', async () => {
    const capability = new FakeCapability();
    const trust = trustStore();
    const { controller } = controllerFor(capability, trust);
    controllers.push(controller);

    const unknown = await controller.connect(host);
    expect(unknown).toMatchObject({ ok: false, reason: 'trust-required' });
    expect(controller.getSnapshot().trustDecision?.reason).toBe('unknown');
    expect(capability.connections.size).toBe(0);
    expect((await controller.acceptPresentedHostKey()).ok).toBe(true);
    expect(trust.current()).toEqual(PIN);

    await controller.close();
    const changedTrust = trustStore({ keyType: 'ssh-rsa', keyB64: 'different' });
    const changedCapability = new FakeCapability();
    const changed = controllerFor(changedCapability, changedTrust);
    controllers.push(changed.controller);
    expect((await changed.controller.connect(host))).toMatchObject({ ok: false, reason: 'trust-mismatch' });
    expect(changed.controller.getSnapshot().trustDecision?.reason).toBe('mismatch');
    expect((await changed.controller.acceptPresentedHostKey()).ok).toBe(true);
    expect(changedTrust.current()).toEqual(PIN);
  });

  it('routes list and attach through HostCliCore and switches sessions on one SSH connection', async () => {
    const capability = new FakeCapability();
    const { controller } = controllerFor(capability, trustStore(PIN));
    controllers.push(controller);
    await connectAndList(controller);

    expect(capability.execCommands[0]).toBe('pocketshell sessions list --json');
    expect(controller.getSnapshot().sessions.map((row) => row.name)).toEqual(['alpha', 'beta']);
    const connectedCount = capability.connectCalls.length;
    expect((await controller.switchSession(session('alpha'))).ok).toBe(true);
    const firstPty = controller.getSnapshot().selectedSession;
    expect(firstPty?.name).toBe('alpha');
    expect(capability.openPtyCalls[0]?.command).toContain('pocketshell sessions attach --');
    expect(capability.openPtyCalls[0]?.command).toContain("'alpha'");

    expect((await controller.switchSession(session('beta'))).ok).toBe(true);
    expect(capability.closePtyCalls).toHaveLength(1);
    expect(controller.getSnapshot().selectedSession?.name).toBe('beta');
    expect(capability.connectCalls).toHaveLength(connectedCount);
    expect(capability.openPtyCalls[1]?.command).toContain("'beta'");
  });

  it('awaits terminal output consumers and serializes PTY input and resize operations', async () => {
    const capability = new FakeCapability();
    const { controller } = controllerFor(capability, trustStore(PIN));
    controllers.push(controller);
    await connectAndList(controller);
    await controller.switchSession(session('alpha'));
    await waitFor(() => capability.openPtyCalls.length === 1 && capability.ptys.size === 1);
    const channelId = [...capability.ptys.keys()][0];
    expect(channelId).toBeTruthy();
    await waitFor(() => capability.pendingReadsFor(channelId!) === 1);

    const gate = deferred<void>();
    const consumerStarted = deferred<void>();
    const received = vi.fn(async () => {
      consumerStarted.resolve();
      await gate.promise;
    });
    controller.subscribeTerminalOutput(async (_selected, bytes) => {
      expect(new TextDecoder().decode(bytes)).toBe('hello');
      await received();
    });
    capability.emitOutput(channelId!, new TextEncoder().encode('hello'));
    await consumerStarted.promise;
    expect(capability.pendingReadsFor(channelId!)).toBe(0);
    gate.resolve();
    await waitFor(() => capability.pendingReadsFor(channelId!) === 1);

    const writes = controller.writeTerminalBytes(new TextEncoder().encode('ls\n'));
    const resize = controller.resizeTerminal(120, 36);
    expect((await writes).ok).toBe(true);
    expect((await resize).ok).toBe(true);
    expect(capability.writeCalls).toHaveLength(1);
    expect(capability.resizeCalls).toHaveLength(1);
    expect(capability.writeCalls[0]?.sequence).toBe(1);
    expect(capability.writeCalls[0]?.dataBase64).toBe(base64(new TextEncoder().encode('ls\n')));
    expect(capability.resizeCalls[0]).toMatchObject({ sequence: 2, cols: 120, rows: 36 });
    expect(received).toHaveBeenCalledOnce();
  });

  it('reconnects after a real transport-state event and reattaches the selected session', async () => {
    const capability = new FakeCapability();
    const { controller } = controllerFor(capability, trustStore(PIN));
    controllers.push(controller);
    await connectAndList(controller);
    await controller.switchSession(session('alpha'));
    const originalConnection = controller.getSnapshot().connectionId;

    capability.emitLost();
    await waitFor(() => capability.connectCalls.length === 2 && controller.getSnapshot().phase === 'live');
    expect(controller.getSnapshot().connectionId).not.toBe(originalConnection);
    expect(controller.getSnapshot().selectedSession?.name).toBe('alpha');
    expect(capability.openPtyCalls).toHaveLength(2);
  });

  it('keeps a connection inside background grace and reconnects after the deadline', async () => {
    const capability = new FakeCapability();
    let now = 1000;
    const { controller } = controllerFor(capability, trustStore(PIN), { now: () => now });
    controllers.push(controller);
    await connectAndList(controller);
    await controller.switchSession(session('alpha'));
    const originalConnection = controller.getSnapshot().connectionId;

    await controller.enterBackground(10_000);
    now += 5000;
    await controller.returnToForeground();
    expect(controller.getSnapshot().connectionId).toBe(originalConnection);
    expect(capability.connectCalls).toHaveLength(1);

    await controller.enterBackground(10_000);
    now += 10_001;
    await controller.returnToForeground();
    expect(capability.connectCalls).toHaveLength(2);
    expect(controller.getSnapshot().phase).toBe('live');
    expect(controller.getSnapshot().selectedSession?.name).toBe('alpha');
  });

  it('does not resend uncertain create or kill operations and reconciles the host listing', async () => {
    const capability = new FakeCapability();
    const { controller } = controllerFor(capability, trustStore(PIN));
    controllers.push(controller);
    await connectAndList(controller);

    capability.createAfterApplyFailure = true;
    expect((await controller.createSession('created-once')).ok).toBe(false);
    expect(capability.createRequests).toBe(1);
    expect(controller.getSnapshot().uncertainMutation).toMatchObject({
      kind: 'create-session', target: 'created-once', state: 'observed-applied',
    });

    controller.clearUncertainMutation();
    capability.killAfterApplyFailure = true;
    expect((await controller.killSession('alpha')).ok).toBe(false);
    expect(capability.killRequests).toBe(1);
    expect(controller.getSnapshot().uncertainMutation).toMatchObject({
      kind: 'kill-session', target: 'alpha', state: 'observed-applied',
    });
    expect(capability.execCommands.filter((command) => command.includes('sessions create'))).toHaveLength(1);
    expect(capability.execCommands.filter((command) => command.includes('sessions kill'))).toHaveLength(1);
  });

  it('does not retry terminal input after an uncertain native send and proves resource closure', async () => {
    const capability = new FakeCapability();
    const { controller } = controllerFor(capability, trustStore(PIN));
    controllers.push(controller);
    await connectAndList(controller);
    await controller.switchSession(session('alpha'));
    capability.nextWriteError = new SshCapabilityError('SSH transport was lost during write.', 'CONNECTION_LOST');

    expect((await controller.writeTerminalBytes(new TextEncoder().encode('command\n'))).ok).toBe(false);
    await waitFor(() => capability.connectCalls.length === 2 && controller.getSnapshot().phase === 'live');
    expect(capability.writeCalls).toHaveLength(1);
    expect((await controller.getNativeResourceSnapshot()).ptys).toBe(1);

    await controller.close();
    const afterClose = await capability.resourceSnapshot('snapshot-after-close');
    expect(afterClose).toMatchObject({ connections: 0, ptys: 0, sftpClients: 0, forwards: 0 });
  });
});
