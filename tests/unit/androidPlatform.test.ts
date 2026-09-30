import { afterEach, describe, expect, it } from 'vitest';
import {
  ConnectionController,
  type HostKeyTrustPin,
  type SshCapability,
  type SshConnectionStateEvent,
  type SshConnectOptions,
  type SshExecOptions,
  type SshPtyOpenOptions,
  type SshPtyReadOptions,
  type SshPtyReadResult,
  type SshPtyWriteOptions,
  type SshPtyResizeOptions,
} from '@pocketshell/core';
import { AndroidConnectionHub, connectionStateFor } from '@/platform/android/connectionHub';
import { createAndroidPlatform, UnsupportedCapability } from '@/platform/android/androidApi';
import { AndroidHostStore, ANDROID_HOSTS_STORAGE_KEY } from '@/platform/android/hostStore';
import { createLocalTrustStore, pinStorageKey } from '@/platform/android/trustStore';
import { selectShell } from '@/shellSelection';

const HOST_KEY = { keyType: 'ssh-ed25519', keyB64: 'AQIDBA==', fingerprintSha256: 'SHA256:abc123' };

function sessionJson(name: string, workspace: string) {
  return { name: `${workspace}:${name}`, id: `${name}-id`, workspace, tag: name, attached: false, created_epoch: 100 };
}

function base64(text: string): string {
  return btoa(text);
}

async function settle(predicate: () => boolean): Promise<void> {
  for (let attempt = 0; attempt < 200; attempt += 1) {
    if (predicate()) return;
    await new Promise((resolve) => setTimeout(resolve, 2));
  }
  throw new Error('condition never held');
}

class MemoryStorage {
  readonly values = new Map<string, string>();
  getItem(key: string) {
    return this.values.get(key) ?? null;
  }
  setItem(key: string, value: string) {
    this.values.set(key, value);
  }
}

/** A physical-effects fake: it records what the controller asks for and moves bytes. */
class FakeNative {
  readonly connects: SshConnectOptions[] = [];
  readonly execs: string[] = [];
  readonly opened: SshPtyOpenOptions[] = [];
  readonly writes: string[] = [];
  readonly resizes: Array<[number, number]> = [];
  readonly closedPtys: string[] = [];
  sessions = [sessionJson('main', '/home/u/git/demo'), sessionJson('tests', '/home/u/git/demo')];
  private listeners = new Set<(event: SshConnectionStateEvent) => void>();
  private live = new Map<string, string>();
  private pendingReads = new Map<string, (result: SshPtyReadResult) => void>();
  private readOptions = new Map<string, SshPtyReadOptions>();
  private ordinal = 0;

  capability(): SshCapability {
    return {
      addListener: async (_event: string, listener: (event: SshConnectionStateEvent) => void) => {
        this.listeners.add(listener);
        return { remove: async () => void this.listeners.delete(listener) };
      },
      connect: async (options: SshConnectOptions) => {
        this.connects.push(options);
        const connectionId = `c-${++this.ordinal}`;
        this.live.set(connectionId, options.generationId);
        return { requestId: options.requestId, connectionId, generationId: options.generationId, hostKey: HOST_KEY };
      },
      getConnectionState: async (ref: { requestId: string; connectionId: string }) => ({
        requestId: ref.requestId,
        state: this.live.has(ref.connectionId) ? 'connected' : 'closed',
      }),
      closeConnection: async (ref: { requestId: string; connectionId: string }) => {
        this.live.delete(ref.connectionId);
        return { requestId: ref.requestId };
      },
      cancelOperation: async (options: { requestId: string }) => ({ requestId: options.requestId, cancelled: true }),
      scheduleClose: async (ref: { requestId: string }) => ({ requestId: ref.requestId }),
      cancelScheduledClose: async (ref: { requestId: string }) => ({ requestId: ref.requestId, cancelled: true }),
      exec: async (options: SshExecOptions) => {
        this.execs.push(options.command);
        const base = { requestId: options.requestId, connectionId: options.connectionId, generationId: options.generationId, stderr: '', timedOut: false };
        if (options.command.includes('sessions list')) {
          return { ...base, exitCode: 0, stdout: JSON.stringify({ schema: 3, sessions: this.sessions }) };
        }
        if (options.command === 'printf %s "$HOME"') return { ...base, exitCode: 0, stdout: '/home/u' };
        if (options.command.includes('usage --json')) {
          return { ...base, exitCode: 0, stdout: '{"provider":"claude","window":"5h","used_pct":12}\n' };
        }
        return { ...base, exitCode: 1, stdout: '' };
      },
      openPty: async (options: SshPtyOpenOptions) => {
        this.opened.push(options);
        return { requestId: options.requestId, connectionId: options.connectionId, generationId: options.generationId, channelId: `pty-${++this.ordinal}` };
      },
      readPty: (options: SshPtyReadOptions) =>
        new Promise<SshPtyReadResult>((resolve) => {
          this.pendingReads.set(options.channelId, resolve);
          this.readOptions.set(options.channelId, options);
        }),
      writePty: async (options: SshPtyWriteOptions) => {
        this.writes.push(atob(options.dataBase64));
        return { ...options };
      },
      resizePty: async (options: SshPtyResizeOptions) => {
        this.resizes.push([options.cols, options.rows]);
        return { ...options };
      },
      closePty: async (options: { requestId: string; channelId: string }) => {
        this.closedPtys.push(options.channelId);
        this.resolveRead(options.channelId, '', true);
        return { requestId: options.requestId };
      },
      sftpList: async () => { throw new Error('unused'); },
      sftpRead: async () => { throw new Error('unused'); },
      sftpWrite: async () => { throw new Error('unused'); },
      sftpMkdir: async () => { throw new Error('unused'); },
      sftpRename: async () => { throw new Error('unused'); },
      sftpDelete: async () => { throw new Error('unused'); },
      openPortForward: async () => { throw new Error('unused'); },
      closePortForward: async () => { throw new Error('unused'); },
      resourceSnapshot: async (requestId: string) => ({ requestId, connections: this.live.size, ptys: 0, sftpClients: 0, forwards: 0 }),
    } as unknown as SshCapability;
  }

  /** Deliver PTY output on the most recent channel. */
  output(text: string, eof = false): void {
    const channel = [...this.pendingReads.keys()].at(-1);
    if (!channel) throw new Error('no pending PTY read');
    this.resolveRead(channel, text, eof);
  }

  hasPendingRead(): boolean {
    return this.pendingReads.size > 0;
  }

  dropTransport(): void {
    const [connectionId, generationId] = [...this.live.entries()].at(-1)!;
    this.live.delete(connectionId);
    for (const listener of this.listeners) listener({ connectionId, generationId, state: 'lost', reason: 'socket reset' });
  }

  private resolveRead(channel: string, text: string, eof: boolean): void {
    const resolve = this.pendingReads.get(channel);
    const options = this.readOptions.get(channel);
    if (!resolve || !options) return;
    this.pendingReads.delete(channel);
    resolve({
      requestId: options.requestId,
      connectionId: options.connectionId,
      generationId: options.generationId,
      channelId: channel,
      sequence: options.sequence + (text.length > 0 ? 1 : 0),
      dataBase64: base64(text),
      eof,
    });
  }
}

const target = {
  hostId: 'u@fixture:2222',
  hostname: 'fixture',
  port: 2222,
  username: 'u',
  credential: { kind: 'private-key' as const, privateKeyPem: 'KEY' },
};

function harness(pin: HostKeyTrustPin | null = null) {
  const native = new FakeNative();
  const storage = new MemoryStorage();
  const trust = createLocalTrustStore(storage);
  if (pin) void trust.record(target.hostId, pin);
  let ids = 0;
  const controllers: ConnectionController[] = [];
  const hub = new AndroidConnectionHub({
    createController: () => {
      const controller = new ConnectionController({
        capability: native.capability(),
        trustStore: trust,
        createId: () => `r-${++ids}`,
        retryDelaysMs: [0, 0],
      });
      controllers.push(controller);
      return controller;
    },
  });
  return { native, storage, hub, controllers };
}

const open: Array<{ hub: AndroidConnectionHub; id: string }> = [];
afterEach(async () => {
  await Promise.all(open.splice(0).map(({ hub, id }) => hub.close(id)));
});

describe('AndroidConnectionHub', () => {
  it('dials through the controller, pins a first-contact key, and never accepts a changed one', async () => {
    const first = harness();
    const connected = await first.hub.connect(target);
    expect(connected).toEqual({ ok: true, connectionId: 'android-1' });
    open.push({ hub: first.hub, id: 'android-1' });
    expect(JSON.parse(first.storage.getItem(pinStorageKey(target.hostId))!)).toMatchObject({ fingerprintSha256: 'SHA256:abc123' });

    const changed = harness({ kind: 'sha256-fingerprint', fingerprintSha256: 'SHA256:other' });
    const refused = await changed.hub.connect(target);
    expect(refused.ok).toBe(false);
    expect(refused.error).toMatch(/host key .* changed/i);
    expect(changed.native.opened).toHaveLength(0);

    const rejectFirst = harness();
    expect((await rejectFirst.hub.connect(target, 'reject')).ok).toBe(false);
  });

  it('lists sessions as shared summaries and attaches, types and resizes on the selected PTY', async () => {
    const { native, hub } = harness();
    const { connectionId } = await hub.connect(target);
    open.push({ hub, id: connectionId! });
    const sessions = await hub.sessionsList(connectionId!);
    expect(sessions.map((row) => [row.name, row.workspace, row.aplexerId, row.backend])).toEqual([
      ['main', '/home/u/git/demo', 'main-id', 'aplexer'],
      ['tests', '/home/u/git/demo', 'tests-id', 'aplexer'],
    ]);

    const received: string[] = [];
    hub.onData(({ data }) => received.push(new TextDecoder().decode(data)));
    const attached = await hub.attachSession({
      connectionId: connectionId!, sessionName: 'main', backend: 'aplexer', workspace: '/home/u/git/demo', aplexerId: 'main-id', cols: 50, rows: 30,
    });
    expect(attached.switched).toBe(false);
    expect(native.opened[0]!.command).toContain("sessions attach -- '/home/u/git/demo:main'");
    expect(native.resizes).toContainEqual([50, 30]);

    await settle(() => native.hasPendingRead());
    native.output('$ ');
    await settle(() => received.join('') === '$ ');

    expect(await hub.input(attached.shellId, 'echo hi\r', 'main', '/home/u/git/demo')).toBe(true);
    expect(native.writes).toEqual(['echo hi\r']);
    // The fence: a superseded tab's session name is refused, not typed elsewhere.
    expect(await hub.input(attached.shellId, 'x', 'tests')).toBe(false);
    expect(native.writes).toHaveLength(1);

    const again = await hub.attachSession({ connectionId: connectionId!, sessionName: 'main', aplexerId: 'main-id' });
    expect(again).toEqual({ shellId: attached.shellId, switched: true });
    expect(native.opened).toHaveLength(1);
  });

  it('retires the superseded shell id when another session is attached', async () => {
    const { native, hub } = harness();
    const { connectionId } = await hub.connect(target);
    open.push({ hub, id: connectionId! });
    await hub.sessionsList(connectionId!);
    const exited: string[] = [];
    hub.onExited(({ shellId }) => exited.push(shellId));
    const main = await hub.attachSession({ connectionId: connectionId!, sessionName: 'main', aplexerId: 'main-id' });
    const tests = await hub.attachSession({ connectionId: connectionId!, sessionName: 'tests', aplexerId: 'tests-id' });
    expect(tests.shellId).not.toBe(main.shellId);
    expect(exited).toEqual([main.shellId]);
    expect(native.closedPtys).toHaveLength(1);
    expect(await hub.input(main.shellId, 'late', 'main')).toBe(false);
  });

  it('keeps one logical connection id and reports reconnecting, not lost, while the controller re-dials', async () => {
    const { native, hub, controllers } = harness();
    const { connectionId } = await hub.connect(target);
    expect(connectionId).toBe('android-1');
    open.push({ hub, id: connectionId! });
    await hub.sessionsList(connectionId!);
    const attached = await hub.attachSession({ connectionId: connectionId!, sessionName: 'main', aplexerId: 'main-id' });
    const states: string[] = [];
    hub.onState(({ connectionId: id, state }) => {
      if (id === connectionId) states.push(state);
    });
    const received: string[] = [];
    hub.onData(({ shellId, data }) => {
      if (shellId === attached.shellId) received.push(new TextDecoder().decode(data));
    });

    const dialsBefore = native.connects.length;
    native.dropTransport();
    await settle(() => native.connects.length === dialsBefore + 1 && native.opened.length === 2
      && controllers.at(-1)!.getSnapshot().phase === 'live' && native.hasPendingRead());
    // The controller's re-dial reads as one recovery, never as a drop the
    // shared store would answer with its own reconnect loop.
    expect(states).toEqual(['reconnecting', 'connected']);
    // The same shell id keeps receiving the re-attached session's bytes.
    native.output('redrawn');
    await settle(() => received.includes('redrawn'));
    expect(await hub.input(attached.shellId, 'y', 'main')).toBe(true);
  });

  it('maps controller phases onto the shared connection states', () => {
    const base = { revision: 1, hostId: null, hostLabel: null, generationId: null, sessions: [], selectedSession: null, retryAttempt: 0, error: null, trustDecision: null, uncertainMutation: null };
    expect(connectionStateFor({ ...base, phase: 'live', connectionId: 'c' })).toBe('connected');
    expect(connectionStateFor({ ...base, phase: 'background', connectionId: null })).toBe('connected');
    expect(connectionStateFor({ ...base, phase: 'awaiting-trust', connectionId: null })).toBe('connecting');
    expect(connectionStateFor({ ...base, phase: 'reconnecting', connectionId: null })).toBe('reconnecting');
    expect(connectionStateFor({ ...base, phase: 'connecting', connectionId: null, retryAttempt: 2 })).toBe('reconnecting');
    expect(connectionStateFor({ ...base, phase: 'listing', connectionId: 'c' })).toBe('connected');
    expect(connectionStateFor({ ...base, phase: 'error', connectionId: 'c' })).toBe('connected');
    expect(connectionStateFor({ ...base, phase: 'error', connectionId: null })).toBe('lost');
    expect(connectionStateFor({ ...base, phase: 'lost', connectionId: null })).toBe('lost');
  });
});

describe('createAndroidPlatform', () => {
  function platform() {
    const { native, storage, controllers } = harness();
    const hosts = new AndroidHostStore({ storage, readLegacyHosts: async () => [] });
    let lifecycle: ((active: boolean) => void) | null = null;
    let ids = 0;
    const created = createAndroidPlatform({
      createController: () => {
        const controller = new ConnectionController({
          capability: native.capability(),
          trustStore: createLocalTrustStore(storage),
          createId: () => `p-${++ids}`,
          retryDelaysMs: [0],
        });
        controllers.push(controller);
        return controller;
      },
      hosts,
      lifecycle: { onActiveChange: (handler) => { lifecycle = handler; return () => { lifecycle = null; }; } },
      backgroundGraceMs: () => 60_000,
      addHostRoute: '/android/hosts',
    });
    return { native, storage, hosts, created, controllers, emitActive: (active: boolean) => lifecycle?.(active) };
  }

  it('connects a saved host with its session key and serves bootstrap, usage and home over the controller', async () => {
    const { native, hosts, created } = platform();
    expect(await created.api.ssh.listConfigHosts()).toEqual([]);
    expect(created.api.hosts?.emptyAction).toEqual({ label: 'Add a host', route: '/android/hosts' });

    hosts.save({ name: 'fixture', hostname: 'fixture', port: 2222, user: 'u' }, 'PEM');
    expect((await created.api.ssh.listConfigHosts()).map((host) => host.name)).toEqual(['fixture']);
    const result = await created.api.ssh.connect({ host: 'fixture', port: 2222, user: 'u', tofuDecision: 'accept-always' });
    expect(result.ok).toBe(true);
    open.push({ hub: created.hub, id: result.connectionId! });
    expect(native.connects[0]).toMatchObject({ hostname: 'fixture', port: 2222, username: 'u', credential: { kind: 'private-key', privateKeyPem: 'PEM' } });

    expect(await created.api.projects.home(result.connectionId!)).toEqual({ ok: true, home: '/home/u', error: null });
    expect((await created.api.helper.usage(result.connectionId!))).toHaveLength(1);
    const bootstrap = await created.api.helper.bootstrap(result.connectionId!);
    expect(bootstrap.pocketshell.installed).toBe(false);
    expect(native.execs.some((command) => command.includes('command -v pocketshell'))).toBe(true);
  });

  it('refuses unimplemented work by name instead of pretending', async () => {
    const { created } = platform();
    await expect(created.api.sftp.list('android-1', '/')).rejects.toBeInstanceOf(UnsupportedCapability);
    await expect(created.api.forwards.addManual('android-1', { kind: 'local', listenHost: '', listenPort: 1, destHost: '', destPort: 1 }))
      .rejects.toThrow('forwards.addManual');
    expect(await created.api.forwards.list('android-1')).toEqual([]);
  });

  it('reports a saved host whose key did not survive the app session as a dial failure', async () => {
    const { storage, created } = platform();
    storage.setItem(ANDROID_HOSTS_STORAGE_KEY, JSON.stringify([{ name: 'box', hostname: 'box', port: 22, user: 'u' }]));
    const result = await created.api.ssh.connect({ host: 'box', port: 22, user: 'u' });
    expect(result).toEqual({ ok: false, error: expect.stringContaining('No private key for “box”') });
  });

  it('drives the controller grace from Android lifecycle and never feeds the shared resume probe', async () => {
    const { hosts, created, controllers, emitActive } = platform();
    hosts.save({ name: 'fixture', hostname: 'fixture', port: 2222, user: 'u' }, 'PEM');
    const result = await created.api.ssh.connect({ host: 'fixture', port: 2222, user: 'u' });
    open.push({ hub: created.hub, id: result.connectionId! });
    let resumed = 0;
    created.api.app.onResumed(() => { resumed += 1; });

    emitActive(false);
    await settle(() => controllers.at(-1)!.getSnapshot().phase === 'background');
    emitActive(true);
    await settle(() => controllers.at(-1)!.getSnapshot().phase === 'connected');
    expect(resumed).toBe(0);
  });
});

describe('selectShell', () => {
  it('mounts the shared app unless the launch asked for the legacy screens', () => {
    expect(selectShell('')).toBe('shared');
    expect(selectShell('?shell=shared')).toBe('shared');
    expect(selectShell('?shell=legacy')).toBe('legacy');
  });
});

