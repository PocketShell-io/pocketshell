/**
 * PocketShell browser dev mode — live SSH bridge (#3022).
 *
 * A small local Node process that implements the Android `SshCapability`
 * plugin contract (connect/exec/PTY/SFTP/port-forward, same argument and
 * result shapes and error codes as SshCapabilityPlugin.java) on top of ssh2,
 * and serves it to the browser over a WebSocket. It also holds the dev key
 * vault in memory: a key loaded with `--identity` never reaches the page
 * (a key imported in the app is read by the page once and handed over).
 *
 * Safety rules, all enforced here:
 *  - it binds 127.0.0.1 only and accepts a WebSocket only when its `Host`
 *    is the loopback bridge address, its `Origin` is exactly the dev page's
 *    origin (so neither another site nor a DNS-rebinding page can drive it)
 *    and it offers the per-run token as a `Sec-WebSocket-Protocol` value;
 *  - it never logs credentials, passphrases, key bytes, PTY bytes, command
 *    text or file contents — only method names, durations and error codes;
 *  - host keys are verified before authentication: an unpinned or changed
 *    key fails the dial with HOST_KEY_REJECTED and the presented key, which
 *    the app's own trust prompt then shows (exactly like the Android plugin).
 */
import { createHash, randomUUID, timingSafeEqual } from 'node:crypto';
import { readFileSync } from 'node:fs';
import http from 'node:http';
import net from 'node:net';
import path from 'node:path';
import ssh2 from 'ssh2';
import { WebSocketServer, WebSocket } from 'ws';
import { isAllowedOrigin, isLoopbackHost, refusal } from './loopback.mjs';

const { Client, utils: sshUtils } = ssh2;

export const BRIDGE_PROTOCOL = 'pocketshell-dev-ssh-bridge/1';
export const BRIDGE_BIND_HOST = '127.0.0.1';
/** The WebSocket subprotocol the page offers, next to `token.<per-run token>`. */
export const BRIDGE_SUBPROTOCOL = 'pocketshell-dev-bridge.v1';
const TOKEN_SUBPROTOCOL_PREFIX = 'token.';

/** The per-run token the client offered as a `token.<token>` subprotocol, if any. */
export function offeredToken(protocolHeader) {
  if (typeof protocolHeader !== 'string') return null;
  const offered = protocolHeader.split(',').map((part) => part.trim());
  if (!offered.includes(BRIDGE_SUBPROTOCOL)) return null;
  const entry = offered.find((part) => part.startsWith(TOKEN_SUBPROTOCOL_PREFIX));
  return entry ? entry.slice(TOKEN_SUBPROTOCOL_PREFIX.length) : null;
}

function sameSecret(given, expected) {
  if (typeof given !== 'string') return false;
  const a = Buffer.from(given);
  const b = Buffer.from(expected);
  return a.length === b.length && timingSafeEqual(a, b);
}

const MAX_CHANNELS_PER_CONNECTION = 8;
const MAX_PTY_READ_BYTES = 32 * 1024;
const MAX_PTY_WRITE_BYTES = 32 * 1024;
const MAX_SFTP_TRANSFER_BYTES = 512 * 1024;
const MAX_EXEC_OUTPUT_BYTES = 1024 * 1024;
const MAX_EXEC_TIMEOUT_MS = 120_000;
const MAX_SFTP_ENTRIES = 1000;
const MAX_FORWARDS = 8;

export class BridgeFailure extends Error {
  constructor(code, message, data = undefined) {
    super(message);
    this.code = code;
    this.data = data;
  }
}

function fail(code, message, data) {
  throw new BridgeFailure(code, message, data);
}

function requiredString(options, name) {
  const value = options?.[name];
  if (typeof value !== 'string' || value.length === 0) fail('INVALID_ARGUMENT', `${name} is required.`);
  return value;
}

function boundedInt(options, name, min, max, fallback) {
  const raw = options?.[name];
  const value = raw === undefined || raw === null ? fallback : raw;
  if (!Number.isInteger(value) || value < min || value > max) fail('INVALID_ARGUMENT', `${name} is outside its supported range.`);
  return value;
}

/** `SHA256:<base64, no padding>` — the format the Android plugin presents. */
export function fingerprintOf(blob) {
  return `SHA256:${createHash('sha256').update(blob).digest('base64').replace(/=+$/u, '')}`;
}

export function presentedHostKey(blob) {
  const typeLength = blob.readUInt32BE(0);
  return {
    keyType: blob.subarray(4, 4 + typeLength).toString('utf8'),
    keyB64: blob.toString('base64'),
    fingerprintSha256: fingerprintOf(blob),
  };
}

/** Mirror of core verifyHostKeyTrustPin for the two persisted pin kinds. */
export function hostKeyTrusted(pin, presented) {
  if (!pin || typeof pin !== 'object') return false;
  if (pin.kind === 'sha256-fingerprint') return pin.fingerprintSha256 === presented.fingerprintSha256;
  if (pin.kind === 'wire-key') return pin.keyType === presented.keyType && pin.keyB64 === presented.keyB64;
  return false;
}

/** A stable vault handle (UUID-shaped, as the app validates) for a key fingerprint. */
function handleIdFor(fingerprint) {
  const hex = createHash('sha256').update(`dev-vault:${fingerprint}`).digest('hex');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-4${hex.slice(13, 16)}-8${hex.slice(17, 20)}-${hex.slice(20, 32)}`;
}

/** The in-memory dev key vault. Private keys stay in this process. */
export function createKeyVault() {
  const keys = new Map();

  function add(privateKey, label, passphrase, createdAt = Date.now()) {
    const parsed = sshUtils.parseKey(privateKey, passphrase || undefined);
    if (parsed instanceof Error) {
      const needsPassphrase = /passphrase|encrypted/iu.test(parsed.message);
      fail(needsPassphrase ? 'PASSPHRASE_REQUIRED' : 'INVALID_KEY', needsPassphrase
        ? 'This key is encrypted; enter its passphrase.'
        : 'That file is not a supported SSH private key.');
    }
    const key = Array.isArray(parsed) ? parsed[0] : parsed;
    const algorithm = key.type === 'ssh-ed25519' || key.type === 'ssh-rsa' ? key.type : null;
    if (!algorithm) fail('UNSUPPORTED_KEY', 'Only Ed25519 and RSA keys are supported.');
    const fingerprintSha256 = fingerprintOf(key.getPublicSSH());
    const handleId = handleIdFor(fingerprintSha256);
    if (keys.has(handleId)) fail('KEY_EXISTS', 'This key is already in the vault.');
    // Keep the original text; a passphrase is asked again per dial, like Android.
    const encrypted = Boolean(passphrase) || /ENCRYPTED|bcrypt/u.test(String(privateKey));
    const metadata = { handleId, label, algorithm, fingerprintSha256, passphraseRequired: encrypted, createdAt };
    keys.set(handleId, { metadata, privateKey: Buffer.from(privateKey) });
    return { ...metadata };
  }

  return {
    list: () => [...keys.values()].map((entry) => ({ ...entry.metadata })),
    addFile(file, label) {
      return add(readFileSync(file), label ?? `${path.basename(file)} (dev bridge, in memory)`, undefined, 0);
    },
    importKey(options) {
      const pem = requiredString(options, 'privateKeyPem');
      const label = typeof options.label === 'string' && options.label.trim() ? options.label.trim() : 'Dev key';
      return add(pem, label, typeof options.passphrase === 'string' ? options.passphrase : undefined);
    },
    generate(options) {
      const type = options?.algorithm === 'RSA-3072' ? 'rsa' : 'ed25519';
      const pair = sshUtils.generateKeyPairSync(type, type === 'rsa' ? { bits: 3072 } : undefined);
      return add(pair.private, typeof options?.label === 'string' ? options.label : 'Dev key');
    },
    remove(handleId, fingerprintSha256) {
      const entry = keys.get(handleId);
      if (!entry || entry.metadata.fingerprintSha256 !== fingerprintSha256) return false;
      entry.privateKey.fill(0);
      return keys.delete(handleId);
    },
    privateKey(handleId) {
      const entry = keys.get(handleId);
      if (!entry) fail('KEY_NOT_FOUND', 'The SSH key for this host is not in the dev vault. Import it again.');
      return entry.privateKey;
    },
  };
}

function safeMessage(error) {
  const message = error instanceof Error ? error.message : String(error);
  return message && message.length <= 500 ? message : 'SSH operation failed.';
}

function classify(error) {
  if (error instanceof BridgeFailure) return error;
  const message = safeMessage(error);
  if (error?.level === 'client-authentication' || /authentication/iu.test(message)) {
    return new BridgeFailure('AUTH_FAILED', 'SSH authentication failed.');
  }
  if (error?.code === 'ECONNREFUSED' || error?.code === 'ENOTFOUND' || error?.code === 'EHOSTUNREACH') {
    return new BridgeFailure('CONNECTION_FAILED', message);
  }
  if (/timed out/iu.test(message)) return new BridgeFailure('TIMEOUT', message);
  return new BridgeFailure('CONNECTION_LOST', message);
}

function sftpCall(sftp, method, ...args) {
  return new Promise((resolve, reject) => {
    sftp[method](...args, (error, value) => (error ? reject(error) : resolve(value)));
  });
}

function sftpFailure(error) {
  if (error instanceof BridgeFailure) return error;
  if (error?.code === 2) return new BridgeFailure('SFTP_NOT_FOUND', 'The SFTP path does not exist.');
  if (error?.code === 3) return new BridgeFailure('SFTP_PERMISSION_DENIED', 'Permission denied.');
  if (error?.code === 4 && /exist/iu.test(error.message)) return new BridgeFailure('SFTP_FILE_EXISTS', 'SFTP write target already exists.');
  return classify(error);
}

/**
 * The SshCapability implementation. One instance per bridge process; every
 * WebSocket shares it, like the single Android plugin instance.
 */
export function createSshCapability({ vault, emit, logger }) {
  const connections = new Map();
  const ptys = new Map();
  const forwards = new Map();
  const pendingConnects = new Map();

  function requireConnection(options) {
    const connection = connections.get(requiredString(options, 'connectionId'));
    if (!connection || connection.generationId !== options.generationId) {
      fail('CONNECTION_NOT_FOUND', 'SSH connection is not open.');
    }
    if (connection.state !== 'connected') fail('CONNECTION_LOST', 'SSH connection was lost.');
    return connection;
  }

  function acquireChannel(connection) {
    if (connection.channels >= MAX_CHANNELS_PER_CONNECTION) fail('CHANNEL_LIMIT', 'This SSH connection has no free channels.');
    connection.channels += 1;
  }

  function releaseChannel(connection) {
    connection.channels = Math.max(0, connection.channels - 1);
  }

  function closePty(pty) {
    if (pty.closed) return;
    pty.closed = true;
    ptys.delete(pty.channelId);
    releaseChannel(pty.connection);
    try { pty.stream.close(); } catch { /* already closed */ }
  }

  function closeForward(forward) {
    if (forward.closed) return;
    forward.closed = true;
    forwards.delete(forward.forwardId);
    releaseChannel(forward.connection);
    for (const socket of forward.sockets) socket.destroy();
    forward.server.close();
  }

  function closeChildren(connection) {
    for (const pty of [...ptys.values()]) if (pty.connection === connection) closePty(pty);
    for (const forward of [...forwards.values()]) if (forward.connection === connection) closeForward(forward);
    connection.sftp?.end?.();
    connection.sftp = null;
    if (connection.graceTimer) clearTimeout(connection.graceTimer);
    connection.graceTimer = null;
  }

  function closeConnection(connection, reason) {
    if (connection.state === 'closed') return;
    connection.intentionalClose = true;
    connection.state = 'closed';
    connections.delete(connection.connectionId);
    closeChildren(connection);
    connection.client.end();
    logger(`connection closed (${reason})`);
  }

  async function sftpOf(connection) {
    if (connection.sftp) return connection.sftp;
    connection.sftp = await new Promise((resolve, reject) => {
      connection.client.sftp((error, sftp) => (error ? reject(classify(error)) : resolve(sftp)));
    });
    return connection.sftp;
  }

  function validateAbsolute(value, name) {
    if (typeof value !== 'string' || !value.startsWith('/') || value.includes('\0')) {
      fail('INVALID_ARGUMENT', `${name} must be an absolute path.`);
    }
  }

  /** Mirror of the Android plugin's rootPath containment check (no symlink following). */
  async function resolveWithinRoot(sftp, rootPath, target, allowMissingLeaf) {
    validateAbsolute(rootPath, 'rootPath');
    validateAbsolute(target, 'path');
    const root = await sftpCall(sftp, 'realpath', rootPath).catch((error) => { throw sftpFailure(error); });
    const within = (candidate) => candidate === root || candidate.startsWith(root === '/' ? '/' : `${root}/`);
    let attributes = null;
    try {
      attributes = await sftpCall(sftp, 'lstat', target);
    } catch (error) {
      if (error?.code !== 2) throw sftpFailure(error);
    }
    if (attributes) {
      if (attributes.isSymbolicLink()) fail('SFTP_SYMLINK', 'SFTP file operations do not follow symbolic links.');
      const canonical = await sftpCall(sftp, 'realpath', target);
      if (!within(canonical)) fail('SFTP_OUTSIDE_ROOT', 'The SFTP path is outside the workspace root.');
      return { path: canonical, attributes, exists: true };
    }
    if (!allowMissingLeaf) fail('SFTP_NOT_FOUND', 'The SFTP path does not exist.');
    const parent = path.posix.dirname(target);
    const name = path.posix.basename(target);
    if (!name || name === '.' || name === '..') fail('INVALID_ARGUMENT', 'An SFTP write path must name a child entry.');
    const canonicalParent = await sftpCall(sftp, 'realpath', parent).catch((error) => { throw sftpFailure(error); });
    if (!within(canonicalParent)) fail('SFTP_OUTSIDE_ROOT', 'The SFTP path is outside the workspace root.');
    return { path: path.posix.join(canonicalParent, name), attributes: null, exists: false };
  }

  async function writeFile(sftp, target, bytes, flags) {
    const handle = await sftpCall(sftp, 'open', target, flags).catch((error) => { throw sftpFailure(error); });
    try {
      if (bytes.length > 0) await sftpCall(sftp, 'write', handle, bytes, 0, bytes.length, 0);
    } finally {
      await sftpCall(sftp, 'close', handle).catch(() => undefined);
    }
  }

  function decodeBase64(value, what, limit) {
    if (typeof value !== 'string') fail('INVALID_ARGUMENT', `${what} is required.`);
    const bytes = Buffer.from(value, 'base64');
    if (bytes.length > limit) fail('INVALID_ARGUMENT', `${what} exceeds ${limit / 1024} KiB.`);
    return bytes;
  }

  const methods = {
    async connect(options) {
      const requestId = requiredString(options, 'requestId');
      const generationId = requiredString(options, 'generationId');
      const hostId = requiredString(options, 'hostId');
      const hostname = requiredString(options, 'hostname');
      const port = boundedInt(options, 'port', 1, 65535, 22);
      const username = requiredString(options, 'username');
      const connectTimeout = boundedInt(options, 'connectTimeoutMs', 1000, 30_000, 20_000);
      const credential = options.credential;
      if (!credential || typeof credential !== 'object') fail('INVALID_ARGUMENT', 'SSH credential is missing.');
      const auth = {};
      if (credential.kind === 'key-handle') {
        auth.privateKey = vault.privateKey(requiredString(credential, 'handleId'));
        if (typeof credential.passphrase === 'string' && credential.passphrase) auth.passphrase = credential.passphrase;
      } else if (credential.kind === 'private-key') {
        auth.privateKey = requiredString(credential, 'privateKeyPem');
        if (typeof credential.passphrase === 'string' && credential.passphrase) auth.passphrase = credential.passphrase;
      } else if (credential.kind === 'password') {
        auth.password = requiredString(credential, 'password');
      } else {
        fail('INVALID_ARGUMENT', 'SSH credential kind is not supported.');
      }

      const client = new Client();
      let presented = null;
      let trusted = false;
      const attempt = { cancelled: false, client };
      pendingConnects.set(requestId, attempt);
      try {
        await new Promise((resolve, reject) => {
          client.once('ready', resolve);
          client.once('error', reject);
          client.once('close', () => reject(new BridgeFailure('CONNECTION_LOST', 'SSH connection ended during setup.')));
          client.connect({
            host: hostname,
            port,
            username,
            readyTimeout: connectTimeout,
            keepaliveInterval: 15_000,
            ...auth,
            hostVerifier: (key) => {
              presented = presentedHostKey(Buffer.isBuffer(key) ? key : Buffer.from(key));
              trusted = hostKeyTrusted(options.expectedHostKey, presented);
              return trusted;
            },
          });
        });
      } catch (error) {
        client.end();
        if (attempt.cancelled) fail('CANCELLED', 'SSH connection attempt was cancelled.');
        if (presented && !trusted) fail('HOST_KEY_REJECTED', 'The SSH host key has not been trusted.', presented);
        throw classify(error);
      } finally {
        pendingConnects.delete(requestId);
      }
      if (attempt.cancelled) {
        client.end();
        fail('CANCELLED', 'SSH connection attempt was cancelled.');
      }
      const connection = {
        connectionId: randomUUID(),
        generationId,
        hostId,
        client,
        state: 'connected',
        intentionalClose: false,
        channels: 0,
        sftp: null,
        graceTimer: null,
      };
      client.removeAllListeners('error');
      client.removeAllListeners('close');
      const lost = (reason) => {
        if (connection.intentionalClose || connection.state !== 'connected') return;
        connection.state = 'lost';
        closeChildren(connection);
        emit('connectionState', {
          connectionId: connection.connectionId,
          generationId: connection.generationId,
          state: 'lost',
          reason,
        });
      };
      client.on('error', (error) => lost(classify(error).message));
      client.on('close', () => lost('connection closed'));
      connections.set(connection.connectionId, connection);
      return { requestId, connectionId: connection.connectionId, generationId, hostKey: presented };
    },

    cancelOperation(options) {
      const requestId = requiredString(options, 'requestId');
      const target = options.target;
      if (!target || typeof target !== 'object') fail('INVALID_ARGUMENT', 'SSH cancellation target is missing.');
      if (target.kind === 'connect') {
        const attempt = pendingConnects.get(requiredString(target, 'targetRequestId'));
        if (attempt) {
          attempt.cancelled = true;
          attempt.client.end();
        }
        return { requestId, cancelled: true };
      }
      if (target.kind === 'connection') {
        const connection = connections.get(target.connectionId);
        const found = Boolean(connection && connection.generationId === target.generationId);
        if (found) closeConnection(connection, 'operation-cancelled');
        return { requestId, cancelled: found };
      }
      fail('INVALID_ARGUMENT', 'SSH cancellation target kind is not supported.');
    },

    getConnectionState(options) {
      const requestId = requiredString(options, 'requestId');
      const connection = connections.get(options.connectionId);
      const state = connection && connection.generationId === options.generationId ? connection.state : 'closed';
      return { requestId, state };
    },

    closeConnection(options) {
      const requestId = requiredString(options, 'requestId');
      const connection = connections.get(options.connectionId);
      if (connection && connection.generationId === options.generationId) closeConnection(connection, 'closed');
      return { requestId };
    },

    scheduleClose(options) {
      const requestId = requiredString(options, 'requestId');
      const connection = requireConnection(options);
      const deadline = Number(options.deadlineEpochMs);
      if (!Number.isFinite(deadline)) fail('INVALID_ARGUMENT', 'deadlineEpochMs is required.');
      if (connection.graceTimer) clearTimeout(connection.graceTimer);
      connection.graceTimer = setTimeout(() => closeConnection(connection, 'grace-expired'), Math.max(0, deadline - Date.now()));
      return { requestId };
    },

    cancelScheduledClose(options) {
      const requestId = requiredString(options, 'requestId');
      const connection = connections.get(options.connectionId);
      const cancelled = Boolean(connection?.graceTimer);
      if (connection?.graceTimer) clearTimeout(connection.graceTimer);
      if (connection) connection.graceTimer = null;
      return { requestId, cancelled };
    },

    async exec(options) {
      const requestId = requiredString(options, 'requestId');
      const connection = requireConnection(options);
      const command = requiredString(options, 'command');
      const timeoutMs = boundedInt(options, 'timeoutMs', 1, MAX_EXEC_TIMEOUT_MS, 20_000);
      acquireChannel(connection);
      try {
        return await new Promise((resolve, reject) => {
          connection.client.exec(command, (error, stream) => {
            if (error) {
              reject(classify(error));
              return;
            }
            const stdout = [];
            const stderr = [];
            let outBytes = 0;
            let errBytes = 0;
            let exitCode = null;
            let timedOut = false;
            let overflow = false;
            const timer = setTimeout(() => {
              timedOut = true;
              stream.close();
            }, timeoutMs);
            stream.on('data', (chunk) => {
              outBytes += chunk.length;
              if (outBytes > MAX_EXEC_OUTPUT_BYTES) overflow = true;
              else stdout.push(chunk);
            });
            stream.stderr.on('data', (chunk) => {
              errBytes += chunk.length;
              if (errBytes > MAX_EXEC_OUTPUT_BYTES) overflow = true;
              else stderr.push(chunk);
            });
            stream.on('exit', (code) => {
              if (typeof code === 'number') exitCode = code;
            });
            stream.on('close', (code) => {
              clearTimeout(timer);
              if (exitCode === null && typeof code === 'number') exitCode = code;
              if (overflow) {
                reject(new BridgeFailure('OUTPUT_LIMIT', 'SSH command output exceeded the 1 MiB per-stream limit.'));
                return;
              }
              resolve({
                requestId,
                connectionId: connection.connectionId,
                generationId: connection.generationId,
                exitCode: timedOut ? null : exitCode,
                stdout: Buffer.concat(stdout).toString('utf8'),
                stderr: Buffer.concat(stderr).toString('utf8'),
                timedOut,
              });
            });
          });
        });
      } finally {
        releaseChannel(connection);
      }
    },

    async openPty(options) {
      const requestId = requiredString(options, 'requestId');
      const connection = requireConnection(options);
      const command = requiredString(options, 'command');
      const cols = boundedInt(options, 'cols', 1, 1000, 80);
      const rows = boundedInt(options, 'rows', 1, 1000, 24);
      const term = typeof options.term === 'string' && options.term ? options.term : 'xterm-256color';
      acquireChannel(connection);
      let stream;
      try {
        stream = await new Promise((resolve, reject) => {
          connection.client.exec(command, { pty: { term, cols, rows } }, (error, channel) => (error ? reject(classify(error)) : resolve(channel)));
        });
      } catch (error) {
        releaseChannel(connection);
        throw error;
      }
      const pty = {
        channelId: randomUUID(),
        connection,
        stream,
        buffer: [],
        bufferedBytes: 0,
        eof: false,
        closed: false,
        readSequence: 0,
        operationSequence: 0,
        waiters: new Set(),
      };
      const wake = () => {
        for (const waiter of pty.waiters) waiter();
        pty.waiters.clear();
      };
      const push = (chunk) => {
        pty.buffer.push(chunk);
        pty.bufferedBytes += chunk.length;
        wake();
      };
      stream.on('data', push);
      stream.stderr.on('data', push);
      stream.on('close', () => {
        pty.eof = true;
        wake();
      });
      ptys.set(pty.channelId, pty);
      return { requestId, connectionId: connection.connectionId, generationId: connection.generationId, channelId: pty.channelId };
    },

    async readPty(options) {
      const requestId = requiredString(options, 'requestId');
      const pty = requirePty(options);
      const sequence = boundedInt(options, 'sequence', 0, 2 ** 31 - 1, 0);
      const maxBytes = boundedInt(options, 'maxBytes', 1, MAX_PTY_READ_BYTES, MAX_PTY_READ_BYTES);
      const waitMs = boundedInt(options, 'waitMs', 0, 500, 0);
      if (sequence !== pty.readSequence) fail('STALE_SEQUENCE', 'PTY read sequence is stale.');
      if (pty.bufferedBytes === 0 && !pty.eof && waitMs > 0 && pty.connection.state === 'connected') {
        await new Promise((resolve) => {
          const timer = setTimeout(() => {
            pty.waiters.delete(done);
            resolve();
          }, waitMs);
          const done = () => {
            clearTimeout(timer);
            resolve();
          };
          pty.waiters.add(done);
        });
      }
      if (sequence !== pty.readSequence) fail('STALE_SEQUENCE', 'PTY read sequence is stale.');
      const taken = [];
      let size = 0;
      while (pty.buffer.length > 0 && size < maxBytes) {
        const head = pty.buffer[0];
        const room = maxBytes - size;
        if (head.length <= room) {
          taken.push(pty.buffer.shift());
          size += head.length;
        } else {
          taken.push(head.subarray(0, room));
          pty.buffer[0] = head.subarray(room);
          size += room;
        }
      }
      pty.bufferedBytes -= size;
      if (size > 0) pty.readSequence += 1;
      return {
        requestId,
        connectionId: pty.connection.connectionId,
        generationId: pty.connection.generationId,
        channelId: pty.channelId,
        sequence: pty.readSequence,
        dataBase64: Buffer.concat(taken).toString('base64'),
        eof: (pty.eof && pty.bufferedBytes === 0) || pty.connection.state !== 'connected',
      };
    },

    writePty(options) {
      const requestId = requiredString(options, 'requestId');
      const pty = requirePty(options);
      const sequence = boundedInt(options, 'sequence', 1, 2 ** 31 - 1, 1);
      const bytes = decodeBase64(options.dataBase64, 'PTY input', MAX_PTY_WRITE_BYTES);
      if (sequence !== pty.operationSequence + 1) fail('STALE_SEQUENCE', 'PTY operation sequence is stale.');
      if (pty.eof) fail('CHANNEL_CLOSED', 'PTY channel is closed.');
      pty.stream.write(bytes);
      pty.operationSequence = sequence;
      return operationAck(requestId, pty, sequence);
    },

    resizePty(options) {
      const requestId = requiredString(options, 'requestId');
      const pty = requirePty(options);
      const sequence = boundedInt(options, 'sequence', 1, 2 ** 31 - 1, 1);
      const cols = boundedInt(options, 'cols', 1, 1000, 80);
      const rows = boundedInt(options, 'rows', 1, 1000, 24);
      if (sequence !== pty.operationSequence + 1) fail('STALE_SEQUENCE', 'PTY operation sequence is stale.');
      pty.stream.setWindow(rows, cols, 0, 0);
      pty.operationSequence = sequence;
      return operationAck(requestId, pty, sequence);
    },

    closePty(options) {
      const requestId = requiredString(options, 'requestId');
      const pty = ptys.get(requiredString(options, 'channelId'));
      if (pty && pty.connection.connectionId === options.connectionId) closePty(pty);
      return { requestId };
    },

    async sftpList(options) {
      const requestId = requiredString(options, 'requestId');
      const connection = requireConnection(options);
      const sftp = await sftpOf(connection);
      let listingPath = requiredString(options, 'path');
      if (typeof options.rootPath === 'string') {
        const resolved = await resolveWithinRoot(sftp, options.rootPath, listingPath, false);
        if (!resolved.attributes.isDirectory()) fail('SFTP_NOT_DIRECTORY', 'SFTP path is not a directory.');
        listingPath = resolved.path;
      }
      const listing = await sftpCall(sftp, 'readdir', listingPath).catch((error) => { throw sftpFailure(error); });
      const entries = listing.slice(0, MAX_SFTP_ENTRIES).map((entry) => {
        const attributes = entry.attrs;
        const isDirectory = attributes.isDirectory();
        const type = isDirectory ? 'directory' : attributes.isSymbolicLink() ? 'symlink' : attributes.isFile() ? 'file' : 'other';
        return {
          path: path.posix.join(listingPath, entry.filename),
          name: entry.filename,
          type,
          isDirectory,
          sizeBytes: Number(attributes.size ?? 0),
          modifiedEpochMs: Number(attributes.mtime ?? 0) * 1000,
        };
      });
      return { requestId, entries };
    },

    async sftpRead(options) {
      const requestId = requiredString(options, 'requestId');
      const connection = requireConnection(options);
      const sftp = await sftpOf(connection);
      const maxBytes = boundedInt(options, 'maxBytes', 0, MAX_SFTP_TRANSFER_BYTES, MAX_SFTP_TRANSFER_BYTES);
      let readPath = requiredString(options, 'path');
      if (typeof options.rootPath === 'string') {
        const resolved = await resolveWithinRoot(sftp, options.rootPath, readPath, false);
        if (!resolved.attributes.isFile()) fail('SFTP_NOT_FILE', 'SFTP path is not a regular file.');
        readPath = resolved.path;
      }
      const handle = await sftpCall(sftp, 'open', readPath, 'r').catch((error) => { throw sftpFailure(error); });
      const chunks = [];
      let total = 0;
      try {
        while (total < maxBytes) {
          const block = Buffer.alloc(Math.min(32 * 1024, maxBytes - total));
          const count = await new Promise((resolve, reject) => {
            sftp.read(handle, block, 0, block.length, total, (error, bytesRead) => {
              if (error) reject(error);
              else resolve(bytesRead);
            });
          });
          if (!count) break;
          chunks.push(block.subarray(0, count));
          total += count;
        }
      } finally {
        await sftpCall(sftp, 'close', handle).catch(() => undefined);
      }
      return { requestId, dataBase64: Buffer.concat(chunks).toString('base64') };
    },

    async sftpWrite(options) {
      const requestId = requiredString(options, 'requestId');
      const connection = requireConnection(options);
      const sftp = await sftpOf(connection);
      const createOnly = options.createOnly === true;
      const bytes = decodeBase64(options.dataBase64, 'SFTP content', MAX_SFTP_TRANSFER_BYTES);
      let writePath = requiredString(options, 'path');
      if (typeof options.rootPath === 'string') {
        const resolved = await resolveWithinRoot(sftp, options.rootPath, writePath, true);
        if (resolved.exists) {
          if (!resolved.attributes.isFile()) fail('SFTP_NOT_FILE', 'SFTP write target is not a regular file.');
          if (createOnly) fail('SFTP_FILE_EXISTS', 'SFTP write target already exists.');
        }
        writePath = resolved.path;
      }
      await writeFile(sftp, writePath, bytes, createOnly ? 'wx' : 'w');
      if (options.reportProgress === true) {
        emit('sftpWriteProgress', {
          requestId,
          connectionId: connection.connectionId,
          generationId: connection.generationId,
          path: options.path,
          bytesWritten: bytes.length,
          totalBytes: bytes.length,
        });
      }
      return { requestId, bytesWritten: bytes.length };
    },

    async sftpWriteIfUnchanged(options) {
      const requestId = requiredString(options, 'requestId');
      const connection = requireConnection(options);
      const sftp = await sftpOf(connection);
      const expected = options.expectedMetadata;
      if (!expected || expected.isDirectory !== false) fail('INVALID_ARGUMENT', 'Expected file metadata is missing or invalid.');
      const bytes = decodeBase64(options.dataBase64, 'SFTP content', MAX_SFTP_TRANSFER_BYTES);
      const resolved = await resolveWithinRoot(sftp, requiredString(options, 'rootPath'), requiredString(options, 'path'), true);
      if (!resolved.exists) return { requestId, status: 'conflict', verdict: 'missing' };
      const current = resolved.attributes;
      if (!current.isFile()
        || Number(current.size) !== Number(expected.sizeBytes)
        || Number(current.mtime) * 1000 !== Number(expected.modifiedEpochMs)) {
        return { requestId, status: 'conflict', verdict: 'changed' };
      }
      await writeFile(sftp, resolved.path, bytes, 'w');
      return { requestId, status: 'written', bytesWritten: bytes.length };
    },

    async sftpMkdir(options) {
      const requestId = requiredString(options, 'requestId');
      const connection = requireConnection(options);
      const sftp = await sftpOf(connection);
      let directoryPath = requiredString(options, 'path');
      if (typeof options.rootPath === 'string') {
        const resolved = await resolveWithinRoot(sftp, options.rootPath, directoryPath, true);
        if (resolved.exists) fail('SFTP_FILE_EXISTS', 'SFTP directory path already exists.');
        directoryPath = resolved.path;
      }
      await sftpCall(sftp, 'mkdir', directoryPath).catch((error) => { throw sftpFailure(error); });
      return { requestId };
    },

    async sftpRename(options) {
      const requestId = requiredString(options, 'requestId');
      const connection = requireConnection(options);
      const sftp = await sftpOf(connection);
      await sftpCall(sftp, 'rename', requiredString(options, 'path'), requiredString(options, 'destination'))
        .catch((error) => { throw sftpFailure(error); });
      return { requestId };
    },

    async sftpDelete(options) {
      const requestId = requiredString(options, 'requestId');
      const connection = requireConnection(options);
      const sftp = await sftpOf(connection);
      const target = requiredString(options, 'path');
      const attributes = await sftpCall(sftp, 'lstat', target).catch(() => null);
      if (attributes?.isDirectory()) await sftpCall(sftp, 'rmdir', target).catch((error) => { throw sftpFailure(error); });
      else await sftpCall(sftp, 'unlink', target).catch((error) => { throw sftpFailure(error); });
      return { requestId };
    },

    async openPortForward(options) {
      const requestId = requiredString(options, 'requestId');
      const connection = requireConnection(options);
      const remoteHost = requiredString(options, 'remoteHost');
      const remotePort = boundedInt(options, 'remotePort', 1, 65535, 22);
      const localPort = boundedInt(options, 'localPort', 0, 65535, 0);
      if (forwards.size >= MAX_FORWARDS) fail('FORWARD_LIMIT', 'The app already has eight active local forwards.');
      acquireChannel(connection);
      const forward = { forwardId: randomUUID(), connection, server: null, sockets: new Set(), closed: false, localPort: 0 };
      forward.server = net.createServer((socket) => {
        forward.sockets.add(socket);
        socket.on('close', () => forward.sockets.delete(socket));
        connection.client.forwardOut(BRIDGE_BIND_HOST, socket.remotePort ?? 0, remoteHost, remotePort, (error, stream) => {
          if (error) {
            socket.destroy();
            return;
          }
          socket.pipe(stream).pipe(socket);
          stream.on('error', () => socket.destroy());
          socket.on('error', () => stream.close());
        });
      });
      try {
        await new Promise((resolve, reject) => {
          forward.server.once('error', reject);
          forward.server.listen(localPort, BRIDGE_BIND_HOST, resolve);
        });
      } catch (error) {
        releaseChannel(connection);
        fail('FORWARD_BIND_FAILED', `Local port ${localPort} is not available.`, { cause: safeMessage(error) });
      }
      forward.localPort = forward.server.address().port;
      forwards.set(forward.forwardId, forward);
      return {
        requestId,
        connectionId: connection.connectionId,
        generationId: connection.generationId,
        forwardId: forward.forwardId,
        localPort: forward.localPort,
      };
    },

    closePortForward(options) {
      const requestId = requiredString(options, 'requestId');
      const forward = forwards.get(requiredString(options, 'forwardId'));
      if (forward && forward.connection.connectionId === options.connectionId) closeForward(forward);
      return { requestId };
    },

    resourceSnapshot(options) {
      const requestId = requiredString(options, 'requestId');
      return {
        requestId,
        connections: connections.size,
        ptys: ptys.size,
        sftpClients: [...connections.values()].filter((connection) => connection.sftp).length,
        forwards: forwards.size,
      };
    },
  };

  function requirePty(options) {
    const pty = ptys.get(requiredString(options, 'channelId'));
    if (!pty || pty.connection.connectionId !== options.connectionId || pty.connection.generationId !== options.generationId) {
      fail('CHANNEL_NOT_FOUND', 'PTY channel is not open.');
    }
    return pty;
  }

  function operationAck(requestId, pty, sequence) {
    return {
      requestId,
      connectionId: pty.connection.connectionId,
      generationId: pty.connection.generationId,
      channelId: pty.channelId,
      sequence,
    };
  }

  return {
    methods,
    closeAll() {
      for (const connection of [...connections.values()]) closeConnection(connection, 'bridge-stopped');
    },
  };
}

/**
 * Start the bridge. `token` gates every WebSocket together with the exact
 * `allowedOrigins` (the Vite dev page's origins); `identities` are private
 * key files preloaded into the in-memory vault.
 */
export async function startBridge({
  port = 0,
  token,
  allowedOrigins,
  identities = [],
  logger = (line) => process.stdout.write(`[dev-ssh-bridge] ${line}\n`),
} = {}) {
  if (typeof token !== 'string' || token.length < 16) throw new Error('startBridge needs a token of at least 16 characters.');
  if (!Array.isArray(allowedOrigins) || allowedOrigins.length === 0 || !allowedOrigins.every((origin) => /^http:\/\/(?:127\.0\.0\.1|localhost):\d+$/u.test(origin))) {
    throw new Error('startBridge needs allowedOrigins: the exact loopback origins of the dev page.');
  }
  const vault = createKeyVault();
  const preloaded = identities.map((file) => vault.addFile(file));
  const sockets = new Set();
  const emit = (event, data) => {
    const frame = JSON.stringify({ event, data });
    for (const socket of sockets) if (socket.readyState === WebSocket.OPEN) socket.send(frame);
  };
  const ssh = createSshCapability({ vault, emit, logger });
  const rpc = {
    ...Object.fromEntries(Object.entries(ssh.methods).map(([name, fn]) => [`ssh.${name}`, fn])),
    hello: () => ({ protocol: BRIDGE_PROTOCOL }),
    'vault.list': () => ({ keys: vault.list() }),
    'vault.import': (params) => vault.importKey(params),
    'vault.generate': (params) => vault.generate(params),
    'vault.delete': (params) => ({ deleted: vault.remove(params?.handleId, params?.fingerprintSha256) }),
  };

  const server = http.createServer((_request, response) => {
    response.writeHead(426, { 'content-type': 'text/plain' }).end('PocketShell dev SSH bridge: WebSocket only.\n');
  });
  const wss = new WebSocketServer({
    noServer: true,
    maxPayload: 4 * 1024 * 1024,
    handleProtocols: (protocols) => (protocols.has(BRIDGE_SUBPROTOCOL) ? BRIDGE_SUBPROTOCOL : false),
  });
  server.on('upgrade', (request, socket, head) => {
    const listening = server.address();
    if (!isLoopbackHost(request.headers.host, listening?.port)) {
      logger('refused a WebSocket with a non-loopback Host');
      socket.end(refusal(403, 'Forbidden'));
      return;
    }
    if (!isAllowedOrigin(request.headers.origin, allowedOrigins)) {
      logger('refused a WebSocket from a foreign Origin');
      socket.end(refusal(403, 'Forbidden'));
      return;
    }
    if (!sameSecret(offeredToken(request.headers['sec-websocket-protocol']), token)) {
      logger('refused a WebSocket without the session token');
      socket.end(refusal(401, 'Unauthorized'));
      return;
    }
    wss.handleUpgrade(request, socket, head, (ws) => wss.emit('connection', ws));
  });
  wss.on('connection', (ws) => {
    sockets.add(ws);
    logger(`browser attached (${sockets.size} open)`);
    ws.on('close', () => {
      sockets.delete(ws);
      logger(`browser detached (${sockets.size} open)`);
    });
    ws.on('message', async (raw) => {
      let message;
      try {
        message = JSON.parse(String(raw));
      } catch {
        return;
      }
      const { id, method, params } = message ?? {};
      const handler = typeof method === 'string' ? rpc[method] : undefined;
      const started = Date.now();
      try {
        if (!handler) fail('UNIMPLEMENTED', `${String(method)} is not a bridge method.`);
        const result = await handler(params ?? {});
        if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ id, result }));
        if (method !== 'ssh.readPty') logger(`${method} ok ${Date.now() - started}ms`);
      } catch (error) {
        const failure = error instanceof BridgeFailure ? error : classify(error);
        logger(`${method} failed ${failure.code}`);
        if (ws.readyState === WebSocket.OPEN) {
          ws.send(JSON.stringify({ id, error: { message: failure.message, code: failure.code, data: failure.data ?? {} } }));
        }
      }
    });
  });

  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, BRIDGE_BIND_HOST, resolve);
  });
  const address = server.address();
  logger(`listening on ws://${address.address}:${address.port} (${preloaded.length} key(s) in the in-memory vault)`);
  return {
    address,
    vault,
    async close() {
      ssh.closeAll();
      for (const socket of sockets) socket.terminate();
      wss.close();
      await new Promise((resolve) => server.close(() => resolve()));
    },
  };
}
