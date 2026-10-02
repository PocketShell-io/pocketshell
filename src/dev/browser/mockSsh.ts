/**
 * Browser dev mode (#3022), `dev:mock`: the `SshCapability` plugin contract
 * (SshCapabilityPlugin.java: same arguments, results, sequence rules and
 * error codes) served by the in-page {@link MockHost}.
 */
import type { DevPlugin, FakeNativeBridge } from './nativeBridge';
import { DevPluginError } from './nativeBridge';
import type { SeedHost } from './liveBridge';
import { MOCK_SEED_KEY } from './keyVaultPlugin';
import { base64ToBytes, bytesToBase64, randomId } from './bytes';
import { MockHost, MockShell, normalizePath, shellWords } from './mockHost';

export const MOCK_SEED_HOST: SeedHost = {
  name: 'mock-devbox',
  hostname: 'devbox.mock',
  port: 22,
  user: 'dev',
  keyHandleId: MOCK_SEED_KEY.handleId,
};

const MAX_CHANNELS_PER_CONNECTION = 8;
const MAX_PTY_READ_BYTES = 32 * 1024;

/** The mock host's fixed ed25519 public key blob. */
function mockHostKeyBlob(): Uint8Array {
  const type = new TextEncoder().encode('ssh-ed25519');
  const key = new Uint8Array(32).map((_, index) => (index * 37 + 11) & 0xff);
  const blob = new Uint8Array(4 + type.length + 4 + key.length);
  const view = new DataView(blob.buffer);
  view.setUint32(0, type.length);
  blob.set(type, 4);
  view.setUint32(4 + type.length, key.length);
  blob.set(key, 8 + type.length);
  return blob;
}

export async function mockPresentedHostKey(): Promise<{ keyType: string; keyB64: string; fingerprintSha256: string }> {
  const blob = mockHostKeyBlob();
  const digest = new Uint8Array(await globalThis.crypto.subtle.digest('SHA-256', blob));
  return {
    keyType: 'ssh-ed25519',
    keyB64: bytesToBase64(blob),
    fingerprintSha256: `SHA256:${bytesToBase64(digest).replace(/=+$/u, '')}`,
  };
}

function pinTrusts(pin: unknown, presented: { keyType: string; keyB64: string; fingerprintSha256: string }): boolean {
  if (typeof pin !== 'object' || pin === null) return false;
  const candidate = pin as Record<string, unknown>;
  if (candidate.kind === 'sha256-fingerprint') return candidate.fingerprintSha256 === presented.fingerprintSha256;
  if (candidate.kind === 'wire-key') return candidate.keyType === presented.keyType && candidate.keyB64 === presented.keyB64;
  return false;
}

interface MockConnection {
  connectionId: string;
  generationId: string;
  state: 'connected' | 'lost' | 'closed';
  channels: number;
  sftp: boolean;
  graceTimer: ReturnType<typeof setTimeout> | null;
}

interface MockPty {
  channelId: string;
  connection: MockConnection;
  shell: MockShell;
  buffer: Uint8Array[];
  bufferedBytes: number;
  eof: boolean;
  readSequence: number;
  operationSequence: number;
  waiters: Set<() => void>;
}

export interface MockSshOptions {
  bridge: () => FakeNativeBridge;
  host?: MockHost;
  /** Simulated network latency per call; 0 in tests. */
  latencyMs?: number;
}

export interface MockSshPlugin extends DevPlugin {
  host: MockHost;
  /** Drop every live connection, as a network loss would (reconnect UI work). */
  dropConnections(reason?: string): number;
}

function fail(code: string, message: string, data: Record<string, unknown> = {}): never {
  throw new DevPluginError(message, code, data);
}

function requireString(options: Record<string, unknown>, name: string): string {
  const value = options[name];
  if (typeof value !== 'string' || !value) fail('INVALID_ARGUMENT', `${name} is required.`);
  return value;
}

export function createMockSshPlugin(options: MockSshOptions): MockSshPlugin {
  const host = options.host ?? new MockHost();
  const latency = options.latencyMs ?? 25;
  const connections = new Map<string, MockConnection>();
  const ptys = new Map<string, MockPty>();
  const forwards = new Map<string, { connection: MockConnection; localPort: number }>();
  let nextForwardPort = 40_000;
  const encoder = new TextEncoder();
  const delay = () => (latency > 0 ? new Promise((resolve) => setTimeout(resolve, latency)) : Promise.resolve());

  function requireConnection(request: Record<string, unknown>): MockConnection {
    const connection = connections.get(requireString(request, 'connectionId'));
    if (!connection || connection.generationId !== request.generationId) fail('CONNECTION_NOT_FOUND', 'SSH connection is not open.');
    if (connection.state !== 'connected') fail('CONNECTION_LOST', 'SSH connection was lost.');
    return connection;
  }

  function requirePty(request: Record<string, unknown>): MockPty {
    const pty = ptys.get(requireString(request, 'channelId'));
    if (!pty || pty.connection.connectionId !== request.connectionId || pty.connection.generationId !== request.generationId) {
      fail('CHANNEL_NOT_FOUND', 'PTY channel is not open.');
    }
    return pty;
  }

  function acquireChannel(connection: MockConnection) {
    if (connection.channels >= MAX_CHANNELS_PER_CONNECTION) fail('CHANNEL_LIMIT', 'This SSH connection has no free channels.');
    connection.channels += 1;
  }

  function closePty(pty: MockPty) {
    if (!ptys.delete(pty.channelId)) return;
    pty.connection.channels = Math.max(0, pty.connection.channels - 1);
    pty.eof = true;
    pty.waiters.forEach((wake) => wake());
  }

  function closeConnection(connection: MockConnection, state: 'lost' | 'closed') {
    connection.state = state;
    if (connection.graceTimer) clearTimeout(connection.graceTimer);
    for (const pty of [...ptys.values()]) if (pty.connection === connection) closePty(pty);
    for (const [id, forward] of [...forwards]) if (forward.connection === connection) forwards.delete(id);
    if (state === 'closed') connections.delete(connection.connectionId);
  }

  const ref = (connection: MockConnection) => ({ connectionId: connection.connectionId, generationId: connection.generationId });

  function sftpPath(request: Record<string, unknown>, mustExist: boolean) {
    const path = normalizePath(requireString(request, 'path'));
    if (typeof request.rootPath === 'string') {
      const root = normalizePath(request.rootPath);
      if (path !== root && !path.startsWith(root === '/' ? '/' : `${root}/`)) fail('SFTP_OUTSIDE_ROOT', 'The SFTP path is outside the workspace root.');
    }
    const file = host.files.get(path);
    if (mustExist && !file) fail('SFTP_NOT_FOUND', 'The SFTP path does not exist.');
    return { path, file };
  }

  function parentMustExist(path: string) {
    const parent = path.slice(0, path.lastIndexOf('/')) || '/';
    if (host.files.get(parent)?.kind !== 'directory') fail('SFTP_NOT_FOUND', 'The SFTP parent directory does not exist.');
  }

  const methods: Record<string, (request: Record<string, unknown>) => unknown> = {
    async connect(request) {
      const requestId = requireString(request, 'requestId');
      const generationId = requireString(request, 'generationId');
      const hostname = requireString(request, 'hostname');
      requireString(request, 'username');
      await delay();
      if (/fail|offline/iu.test(hostname)) fail('CONNECTION_FAILED', `connect ECONNREFUSED ${hostname} (mock host name asks for a failure)`);
      if (/denied/iu.test(hostname)) fail('AUTH_FAILED', 'SSH authentication failed.');
      const presented = await mockPresentedHostKey();
      if (!pinTrusts(request.expectedHostKey, presented)) {
        fail('HOST_KEY_REJECTED', 'The SSH host key has not been trusted.', presented);
      }
      const connection: MockConnection = {
        connectionId: randomId(), generationId, state: 'connected', channels: 0, sftp: false, graceTimer: null,
      };
      connections.set(connection.connectionId, connection);
      return { requestId, ...ref(connection), hostKey: presented };
    },
    cancelOperation(request) {
      const requestId = requireString(request, 'requestId');
      const target = request.target as Record<string, unknown> | undefined;
      if (target?.kind === 'connect') return { requestId, cancelled: true };
      if (target?.kind === 'connection') {
        const connection = connections.get(String(target.connectionId));
        const found = Boolean(connection && connection.generationId === target.generationId);
        if (connection && found) closeConnection(connection, 'closed');
        return { requestId, cancelled: found };
      }
      fail('INVALID_ARGUMENT', 'SSH cancellation target kind is not supported.');
    },
    getConnectionState(request) {
      const connection = connections.get(String(request.connectionId));
      return {
        requestId: request.requestId,
        state: connection && connection.generationId === request.generationId ? connection.state : 'closed',
      };
    },
    closeConnection(request) {
      const connection = connections.get(String(request.connectionId));
      if (connection && connection.generationId === request.generationId) closeConnection(connection, 'closed');
      return { requestId: request.requestId };
    },
    scheduleClose(request) {
      const connection = requireConnection(request);
      if (connection.graceTimer) clearTimeout(connection.graceTimer);
      const deadline = Number(request.deadlineEpochMs);
      connection.graceTimer = setTimeout(() => closeConnection(connection, 'closed'), Math.max(0, deadline - Date.now()));
      return { requestId: request.requestId };
    },
    cancelScheduledClose(request) {
      const connection = connections.get(String(request.connectionId));
      const cancelled = Boolean(connection?.graceTimer);
      if (connection?.graceTimer) clearTimeout(connection.graceTimer);
      if (connection) connection.graceTimer = null;
      return { requestId: request.requestId, cancelled };
    },
    async exec(request) {
      const connection = requireConnection(request);
      const command = requireString(request, 'command');
      await delay();
      const result = host.exec(command);
      return { requestId: request.requestId, ...ref(connection), ...result, timedOut: false };
    },
    async openPty(request) {
      const connection = requireConnection(request);
      const command = requireString(request, 'command');
      acquireChannel(connection);
      await delay();
      const words = shellWords(command);
      const attach = words.indexOf('attach');
      const sessionName = attach >= 0 && words[attach + 1] === '--' ? words[attach + 2] : null;
      const session = sessionName ? host.findSession(sessionName) ?? null : null;
      if (sessionName && !session) {
        connection.channels -= 1;
        fail('CHANNEL_OPEN_FAILED', `mock host: no session named ${sessionName}`);
      }
      const pty: MockPty = {
        channelId: randomId(),
        connection,
        shell: null as unknown as MockShell,
        buffer: [],
        bufferedBytes: 0,
        eof: false,
        readSequence: 0,
        operationSequence: 0,
        waiters: new Set(),
      };
      pty.shell = new MockShell(host, session, (text) => {
        const bytes = encoder.encode(text);
        pty.buffer.push(bytes);
        pty.bufferedBytes += bytes.length;
        pty.waiters.forEach((wake) => wake());
        pty.waiters.clear();
      });
      ptys.set(pty.channelId, pty);
      pty.shell.start();
      return { requestId: request.requestId, ...ref(connection), channelId: pty.channelId };
    },
    async readPty(request) {
      const pty = requirePty(request);
      const sequence = Number(request.sequence ?? 0);
      const maxBytes = Math.min(MAX_PTY_READ_BYTES, Number(request.maxBytes ?? MAX_PTY_READ_BYTES));
      const waitMs = Math.min(500, Math.max(0, Number(request.waitMs ?? 0)));
      if (sequence !== pty.readSequence) fail('STALE_SEQUENCE', 'PTY read sequence is stale.');
      if (pty.bufferedBytes === 0 && !pty.eof && waitMs > 0) {
        await new Promise<void>((resolve) => {
          const done = () => {
            clearTimeout(timer);
            resolve();
          };
          const timer = setTimeout(() => {
            pty.waiters.delete(done);
            resolve();
          }, waitMs);
          pty.waiters.add(done);
        });
      }
      if (sequence !== pty.readSequence) fail('STALE_SEQUENCE', 'PTY read sequence is stale.');
      const out = new Uint8Array(Math.min(maxBytes, pty.bufferedBytes));
      let size = 0;
      while (pty.buffer.length > 0 && size < out.length) {
        const head = pty.buffer[0];
        const take = Math.min(head.length, out.length - size);
        out.set(head.subarray(0, take), size);
        size += take;
        if (take === head.length) pty.buffer.shift();
        else pty.buffer[0] = head.subarray(take);
      }
      pty.bufferedBytes -= size;
      if (size > 0) pty.readSequence += 1;
      if (pty.shell.closed && pty.bufferedBytes === 0 && !pty.eof) closePty(pty);
      return {
        requestId: request.requestId,
        ...ref(pty.connection),
        channelId: pty.channelId,
        sequence: pty.readSequence,
        dataBase64: bytesToBase64(out.subarray(0, size)),
        eof: (pty.eof && pty.bufferedBytes === 0) || pty.connection.state !== 'connected',
      };
    },
    writePty(request) {
      const pty = requirePty(request);
      const sequence = Number(request.sequence);
      if (sequence !== pty.operationSequence + 1) fail('STALE_SEQUENCE', 'PTY operation sequence is stale.');
      if (pty.eof) fail('CHANNEL_CLOSED', 'PTY channel is closed.');
      pty.operationSequence = sequence;
      pty.shell.input(new TextDecoder().decode(base64ToBytes(requireString(request, 'dataBase64'))));
      return { requestId: request.requestId, ...ref(pty.connection), channelId: pty.channelId, sequence };
    },
    resizePty(request) {
      const pty = requirePty(request);
      const sequence = Number(request.sequence);
      if (sequence !== pty.operationSequence + 1) fail('STALE_SEQUENCE', 'PTY operation sequence is stale.');
      pty.operationSequence = sequence;
      return { requestId: request.requestId, ...ref(pty.connection), channelId: pty.channelId, sequence };
    },
    closePty(request) {
      const pty = ptys.get(String(request.channelId));
      if (pty && pty.connection.connectionId === request.connectionId) closePty(pty);
      return { requestId: request.requestId };
    },
    async sftpList(request) {
      const connection = requireConnection(request);
      connection.sftp = true;
      await delay();
      const { path, file } = sftpPath(request, true);
      if (file?.kind !== 'directory') fail('SFTP_NOT_DIRECTORY', 'SFTP path is not a directory.');
      return {
        requestId: request.requestId,
        entries: host.childrenOf(path).map(([entryPath, entry]) => ({
          path: entryPath,
          name: entryPath.split('/').pop(),
          type: entry.kind,
          isDirectory: entry.kind === 'directory',
          sizeBytes: entry.content.length,
          modifiedEpochMs: entry.modifiedEpochMs,
        })),
      };
    },
    async sftpRead(request) {
      requireConnection(request).sftp = true;
      await delay();
      const { file } = sftpPath(request, true);
      if (file?.kind !== 'file') fail('SFTP_NOT_FILE', 'SFTP path is not a regular file.');
      return { requestId: request.requestId, dataBase64: bytesToBase64(file.content.subarray(0, Number(request.maxBytes ?? file.content.length))) };
    },
    async sftpWrite(request) {
      const connection = requireConnection(request);
      connection.sftp = true;
      await delay();
      const { path, file } = sftpPath(request, false);
      if (file && request.createOnly === true) fail('SFTP_FILE_EXISTS', 'SFTP write target already exists.');
      if (file?.kind === 'directory') fail('SFTP_NOT_FILE', 'SFTP write target is not a regular file.');
      parentMustExist(path);
      const bytes = base64ToBytes(requireString(request, 'dataBase64'));
      host.files.set(path, { kind: 'file', content: bytes, modifiedEpochMs: Math.floor(Date.now() / 1000) * 1000 });
      if (request.reportProgress === true) {
        options.bridge().emit('SshCapability', 'sftpWriteProgress', {
          requestId: request.requestId, ...ref(connection), path: request.path, bytesWritten: bytes.length, totalBytes: bytes.length,
        });
      }
      return { requestId: request.requestId, bytesWritten: bytes.length };
    },
    async sftpWriteIfUnchanged(request) {
      requireConnection(request).sftp = true;
      await delay();
      const { path, file } = sftpPath(request, false);
      if (!file) return { requestId: request.requestId, status: 'conflict', verdict: 'missing' };
      const expected = request.expectedMetadata as { sizeBytes?: number; modifiedEpochMs?: number } | undefined;
      if (file.kind !== 'file' || file.content.length !== expected?.sizeBytes || file.modifiedEpochMs !== expected?.modifiedEpochMs) {
        return { requestId: request.requestId, status: 'conflict', verdict: 'changed' };
      }
      const bytes = base64ToBytes(requireString(request, 'dataBase64'));
      // Whole seconds, as SFTP reports mtime.
      host.files.set(path, { kind: 'file', content: bytes, modifiedEpochMs: Math.max(file.modifiedEpochMs + 1000, Math.floor(Date.now() / 1000) * 1000) });
      return { requestId: request.requestId, status: 'written', bytesWritten: bytes.length };
    },
    async sftpMkdir(request) {
      requireConnection(request).sftp = true;
      await delay();
      const { path, file } = sftpPath(request, false);
      if (file) fail('SFTP_FILE_EXISTS', 'SFTP directory path already exists.');
      parentMustExist(path);
      host.files.set(path, { kind: 'directory', content: new Uint8Array(), modifiedEpochMs: Date.now() });
      return { requestId: request.requestId };
    },
    async sftpRename(request) {
      requireConnection(request).sftp = true;
      await delay();
      const { path } = sftpPath(request, true);
      const destination = normalizePath(requireString(request, 'destination'));
      if (host.files.has(destination)) fail('SFTP_FILE_EXISTS', 'SFTP destination already exists.');
      parentMustExist(destination);
      for (const [entryPath, entry] of [...host.files]) {
        if (entryPath === path || entryPath.startsWith(`${path}/`)) {
          host.files.delete(entryPath);
          host.files.set(destination + entryPath.slice(path.length), entry);
        }
      }
      return { requestId: request.requestId };
    },
    async sftpDelete(request) {
      requireConnection(request).sftp = true;
      await delay();
      const { path, file } = sftpPath(request, true);
      if (file?.kind === 'directory' && host.childrenOf(path).length > 0) fail('SFTP_FAILURE', 'Directory is not empty.');
      host.files.delete(path);
      return { requestId: request.requestId };
    },
    openPortForward(request) {
      const connection = requireConnection(request);
      acquireChannel(connection);
      const requested = Number(request.localPort ?? 0);
      const localPort = requested > 0 ? requested : nextForwardPort++;
      const forwardId = randomId();
      forwards.set(forwardId, { connection, localPort });
      return { requestId: request.requestId, ...ref(connection), forwardId, localPort };
    },
    closePortForward(request) {
      const forward = forwards.get(String(request.forwardId));
      if (forward && forwards.delete(String(request.forwardId))) {
        forward.connection.channels = Math.max(0, forward.connection.channels - 1);
      }
      return { requestId: request.requestId };
    },
    resourceSnapshot(request) {
      const open = [...connections.values()].filter((connection) => connection.state === 'connected');
      return {
        requestId: request.requestId,
        connections: open.length,
        ptys: ptys.size,
        sftpClients: open.filter((connection) => connection.sftp).length,
        forwards: forwards.size,
      };
    },
  };

  return {
    methods,
    host,
    dropConnections(reason = 'mock network drop') {
      let dropped = 0;
      for (const connection of [...connections.values()]) {
        if (connection.state !== 'connected') continue;
        closeConnection(connection, 'lost');
        dropped += 1;
        options.bridge().emit('SshCapability', 'connectionState', { ...ref(connection), state: 'lost', reason });
      }
      return dropped;
    },
  };
}
