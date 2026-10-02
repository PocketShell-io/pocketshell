/**
 * Shared fakes for the Android platform tests: a physical-effects stand-in for
 * the native `SshCapability` plugin that a real core `ConnectionController`
 * drives, plus the host target and in-memory storage those tests use.
 */
import type {
  SshCapability,
  SshConnectionStateEvent,
  SshConnectOptions,
  SshExecOptions,
  SshPtyOpenOptions,
  SshPtyReadOptions,
  SshPtyReadResult,
  SshPtyWriteOptions,
  SshPtyResizeOptions,
} from '@pocketshell/core';

export const HOST_KEY = { keyType: 'ssh-ed25519', keyB64: 'AQIDBA==', fingerprintSha256: 'SHA256:abc123' };

export function sessionJson(name: string, workspace: string) {
  return { name: `${workspace}:${name}`, id: `${name}-id`, workspace, tag: name, attached: false, created_epoch: 100 };
}

function base64(text: string): string {
  return btoa(text);
}

export async function settle(predicate: () => boolean): Promise<void> {
  for (let attempt = 0; attempt < 200; attempt += 1) {
    if (predicate()) return;
    await new Promise((resolve) => setTimeout(resolve, 2));
  }
  throw new Error('condition never held');
}

export class MemoryStorage {
  readonly values = new Map<string, string>();
  getItem(key: string) {
    return this.values.get(key) ?? null;
  }
  setItem(key: string, value: string) {
    this.values.set(key, value);
  }
}

/** A physical-effects fake: it records what the controller asks for and moves bytes. */
export class FakeNative {
  readonly connects: SshConnectOptions[] = [];
  readonly execs: string[] = [];
  readonly opened: SshPtyOpenOptions[] = [];
  readonly writes: string[] = [];
  readonly resizes: Array<[number, number]> = [];
  readonly closedPtys: string[] = [];
  /** Every PTY the controller opened, with the attach command it ran. */
  readonly channels: Array<{ channelId: string; command: string }> = [];
  /** Every PTY write, with the channel it was addressed to. */
  readonly channelWrites: Array<[string, string]> = [];
  sessions = [sessionJson('main', '/home/u/git/demo'), sessionJson('tests', '/home/u/git/demo')];
  /** While true every dial fails the way an unreachable host does (retryable). */
  refuseDials = false;
  /**
   * One-shot failures for the next `sessions list` execs, oldest first: the
   * transport already dead under a listing before its native `lost` lands.
   */
  readonly sessionListFailures: unknown[] = [];
  /** aplexer's attach snapshot: the first read of a new channel answers at once with it. */
  attachSnapshot: string | null = null;
  private snapshotServed = new Set<string>();
  private listeners = new Set<(event: SshConnectionStateEvent) => void>();
  private live = new Map<string, string>();
  private pendingReads = new Map<string, (result: SshPtyReadResult) => void>();
  private pendingReadFailures = new Map<string, (error: unknown) => void>();
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
        if (this.refuseDials) {
          throw Object.assign(new Error('Connection refused'), { code: 'SSH_IO' });
        }
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
          if (this.sessionListFailures.length > 0) throw this.sessionListFailures.shift();
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
        const channelId = `pty-${++this.ordinal}`;
        this.channels.push({ channelId, command: options.command });
        return { requestId: options.requestId, connectionId: options.connectionId, generationId: options.generationId, channelId };
      },
      readPty: (options: SshPtyReadOptions) =>
        new Promise<SshPtyReadResult>((resolve, reject) => {
          if (this.attachSnapshot !== null && !this.snapshotServed.has(options.channelId)) {
            this.snapshotServed.add(options.channelId);
            resolve({
              requestId: options.requestId,
              connectionId: options.connectionId,
              generationId: options.generationId,
              channelId: options.channelId,
              sequence: options.sequence + 1,
              dataBase64: base64(this.attachSnapshot),
              eof: false,
            });
            return;
          }
          this.pendingReads.set(options.channelId, resolve);
          this.pendingReadFailures.set(options.channelId, reject);
          this.readOptions.set(options.channelId, options);
        }),
      writePty: async (options: SshPtyWriteOptions) => {
        this.writes.push(atob(options.dataBase64));
        this.channelWrites.push([options.channelId, atob(options.dataBase64)]);
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

  /** The newest channel attached to session tag `name`. */
  channelOf(name: string): string {
    const channel = [...this.channels].reverse().find((entry) => entry.command.includes(`:${name}'`));
    if (!channel) throw new Error(`no PTY was opened for ${name}`);
    return channel.channelId;
  }

  /** Deliver PTY output on one channel (its pending read must exist). */
  outputOn(channel: string, text: string, eof = false): void {
    if (!this.pendingReads.has(channel)) throw new Error(`no pending PTY read on ${channel}`);
    this.resolveRead(channel, text, eof);
  }

  /** Reject the pending read on one channel (a PTY failing on a healthy transport). */
  failReadOn(channel: string, error: unknown): void {
    const reject = this.pendingReadFailures.get(channel);
    if (!reject) throw new Error(`no pending PTY read on ${channel}`);
    this.pendingReads.delete(channel);
    this.pendingReadFailures.delete(channel);
    reject(error);
  }

  hasPendingReadOn(channel: string): boolean {
    return this.pendingReads.has(channel);
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

export const target = {
  hostId: 'u@fixture:2222',
  hostname: 'fixture',
  port: 2222,
  username: 'u',
  credential: { kind: 'key-handle' as const, handleId: '00000000-0000-4000-8000-000000000001' },
};
