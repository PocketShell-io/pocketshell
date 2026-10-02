import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import ssh2 from 'ssh2';
import WebSocket from 'ws';
import { startBridge, type RunningBridge } from '../../scripts/dev-ssh-bridge/bridge.mjs';

/**
 * The dev:live bridge (#3022) against a real in-process SSH server (ssh2's
 * Server): token gate, loopback bind, host-key verification BEFORE any
 * authentication, key/password auth, exec, PTY echo, and no secrets in logs.
 */
const repoRoot = path.resolve(__dirname, '../..');
const clientKeyFile = path.join(repoRoot, 'tests/docker/test_key');
const clientPublic = ssh2.utils.parseKey(readFileSync(path.join(repoRoot, 'tests/docker/test_key.pub'))) as ssh2.ParsedKey;
const PASSWORD = 'correct horse battery staple 3022';
const TOKEN = 'bridge-test-token-0123456789';
const hostKey = ssh2.utils.generateKeyPairSync('ed25519');
const hostPublicBlob = (ssh2.utils.parseKey(hostKey.private) as ssh2.ParsedKey).getPublicSSH();
const hostFingerprint = `SHA256:${createHash('sha256').update(hostPublicBlob).digest('base64').replace(/=+$/u, '')}`;

let server: ssh2.Server;
let sshPort = 0;
let authAttempts = 0;
let bridge: RunningBridge;
const logs: string[] = [];

function startSshServer(): Promise<void> {
  server = new ssh2.Server({ hostKeys: [hostKey.private] }, (client) => {
    // A refused host key ends the handshake from the client side.
    client.on('error', () => undefined);
    client.on('authentication', (ctx) => {
      authAttempts += 1;
      if (ctx.username !== 'dev') return ctx.reject();
      if (ctx.method === 'password' && ctx.password === PASSWORD) return ctx.accept();
      if (ctx.method === 'publickey' && ctx.key.algo === clientPublic.type
        && Buffer.compare(ctx.key.data, clientPublic.getPublicSSH()) === 0) return ctx.accept();
      return ctx.reject(['password', 'publickey']);
    });
    client.on('ready', () => {
      client.on('session', (acceptSession) => {
        const session = acceptSession();
        let pty = false;
        session.on('pty', (accept) => { pty = true; accept?.(); });
        session.on('window-change', (accept) => accept?.());
        session.on('exec', (accept, _reject, info) => {
          const stream = accept();
          if (pty) {
            stream.write('$ ');
            stream.on('data', (data: Buffer) => {
              const text = data.toString('utf8');
              if (text.includes('exit')) {
                stream.exit(0);
                stream.end();
                return;
              }
              stream.write(text.replace(/\r/gu, '\r\n'));
            });
            return;
          }
          const match = /^echo (.*)$/u.exec(info.command);
          if (match) stream.write(`${match[1]}\n`);
          else stream.stderr.write(`unknown: ${info.command}\n`);
          stream.exit(match ? 0 : 127);
          stream.end();
        });
      });
    });
  });
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => {
    sshPort = (server.address() as { port: number }).port;
    resolve();
  }));
}

/** The exact page origin the launcher allows (the Vite dev server's). */
const DEV_ORIGIN = 'http://127.0.0.1:47391';

function openSocket(token = TOKEN, headers: { origin?: string | null; host?: string } = {}): Promise<WebSocket> {
  return new Promise((resolve, reject) => {
    const origin = headers.origin === undefined ? DEV_ORIGIN : headers.origin;
    const socket = new WebSocket(
      `ws://127.0.0.1:${bridge.address.port}/__pocketshell-dev-bridge`,
      ['pocketshell-dev-bridge.v1', `token.${token}`],
      { headers: { ...(origin === null ? {} : { Origin: origin }), ...(headers.host ? { Host: headers.host } : {}) } },
    );
    socket.once('open', () => resolve(socket));
    socket.once('unexpected-response', (_request, response) => reject(Object.assign(new Error('refused'), { status: response.statusCode })));
    socket.once('error', reject);
  });
}

let nextId = 1;
function rpc(socket: WebSocket, method: string, params: Record<string, unknown> = {}): Promise<any> {
  const id = nextId++;
  return new Promise((resolve, reject) => {
    const onMessage = (raw: WebSocket.RawData) => {
      const message = JSON.parse(String(raw));
      if (message.id !== id) return;
      socket.off('message', onMessage);
      if (message.error) reject(Object.assign(new Error(message.error.message), message.error));
      else resolve(message.result);
    };
    socket.on('message', onMessage);
    socket.send(JSON.stringify({ id, method, params }));
  });
}

const target = (credential: Record<string, unknown>, expectedHostKey: unknown = null) => ({
  requestId: `r${nextId}`, generationId: `g${nextId}`, hostId: 'dev@127.0.0.1', hostname: '127.0.0.1', port: sshPort, username: 'dev',
  credential, expectedHostKey, connectTimeoutMs: 5000,
});

describe('browser dev mode live SSH bridge', () => {
  beforeAll(async () => {
    await startSshServer();
    bridge = await startBridge({ token: TOKEN, allowedOrigins: [DEV_ORIGIN], identities: [clientKeyFile], logger: (line) => logs.push(line) });
  });

  afterAll(async () => {
    await bridge?.close();
    await new Promise((resolve) => server?.close(resolve));
  });

  it('listens on 127.0.0.1 only and refuses a WebSocket without the session token', async () => {
    expect(bridge.address.address).toBe('127.0.0.1');
    await expect(openSocket('wrong-token')).rejects.toMatchObject({ status: 401 });
    const socket = await openSocket();
    await expect(rpc(socket, 'hello')).resolves.toEqual({ protocol: 'pocketshell-dev-ssh-bridge/1' });
    await expect(rpc(socket, 'ssh.nope')).rejects.toMatchObject({ code: 'UNIMPLEMENTED' });
    socket.close();
  });

  it('refuses a WebSocket with the right token from a foreign or missing Origin', async () => {
    // A DNS-rebinding page (or any other site) holding the token must not drive the bridge.
    await expect(openSocket(TOKEN, { origin: 'http://evil.example' })).rejects.toMatchObject({ status: 403 });
    await expect(openSocket(TOKEN, { origin: 'http://evil.example:47391' })).rejects.toMatchObject({ status: 403 });
    await expect(openSocket(TOKEN, { origin: 'http://127.0.0.1:1' })).rejects.toMatchObject({ status: 403 });
    await expect(openSocket(TOKEN, { origin: null })).rejects.toMatchObject({ status: 403 });
    const socket = await openSocket(TOKEN, { origin: DEV_ORIGIN });
    await expect(rpc(socket, 'hello')).resolves.toEqual({ protocol: 'pocketshell-dev-ssh-bridge/1' });
    socket.close();
  });

  it('refuses a WebSocket whose Host header is not the loopback bridge address', async () => {
    await expect(openSocket(TOKEN, { host: `evil.example:${bridge.address.port}` })).rejects.toMatchObject({ status: 403 });
    await expect(openSocket(TOKEN, { host: 'evil.example' })).rejects.toMatchObject({ status: 403 });
    const socket = await openSocket(TOKEN, { host: `localhost:${bridge.address.port}` });
    socket.close();
  });

  it('verifies the host key before authenticating and presents it for the app trust prompt', async () => {
    const socket = await openSocket();
    const keys = await rpc(socket, 'vault.list');
    expect(keys.keys).toHaveLength(1);
    const credential = { kind: 'key-handle', handleId: keys.keys[0].handleId };
    authAttempts = 0;
    const rejected = await rpc(socket, 'ssh.connect', target(credential)).catch((error) => error);
    expect(rejected).toMatchObject({ code: 'HOST_KEY_REJECTED', data: { keyType: 'ssh-ed25519', fingerprintSha256: hostFingerprint } });
    expect(rejected.data.keyB64).toBe(hostPublicBlob.toString('base64'));
    const changed = await rpc(socket, 'ssh.connect', target(credential, { kind: 'sha256-fingerprint', fingerprintSha256: 'SHA256:changed' })).catch((error) => error);
    expect(changed).toMatchObject({ code: 'HOST_KEY_REJECTED' });
    // No credential ever reached an unverified host.
    expect(authAttempts).toBe(0);
    const pin = { kind: 'wire-key', keyType: 'ssh-ed25519', keyB64: rejected.data.keyB64, fingerprintSha256: hostFingerprint };
    const connected = await rpc(socket, 'ssh.connect', target(credential, pin));
    expect(connected).toMatchObject({ hostKey: { fingerprintSha256: hostFingerprint } });
    await rpc(socket, 'ssh.closeConnection', { requestId: 'x', connectionId: connected.connectionId, generationId: connected.generationId });
    socket.close();
  });

  it('authenticates with vault keys and passwords and runs exec and an echoing PTY', async () => {
    const socket = await openSocket();
    const pin = { kind: 'sha256-fingerprint', fingerprintSha256: hostFingerprint };
    const bad = await rpc(socket, 'ssh.connect', target({ kind: 'password', password: 'not it' }, pin)).catch((error) => error);
    expect(bad).toMatchObject({ code: 'AUTH_FAILED' });
    const connected = await rpc(socket, 'ssh.connect', target({ kind: 'password', password: PASSWORD }, pin));
    const ref = { connectionId: connected.connectionId, generationId: connected.generationId };
    await expect(rpc(socket, 'ssh.exec', { ...ref, requestId: 'e1', command: 'echo bridge-exec', timeoutMs: 5000 }))
      .resolves.toMatchObject({ exitCode: 0, stdout: 'bridge-exec\n', stderr: '', timedOut: false });
    const pty = await rpc(socket, 'ssh.openPty', { ...ref, requestId: 'p1', command: 'exec shell', cols: 80, rows: 24 });
    const read = async (sequence: number) => rpc(socket, 'ssh.readPty', { ...ref, channelId: pty.channelId, requestId: 'rd', sequence, waitMs: 500 });
    let sequence = 0;
    let screen = '';
    const readUntil = async (text: string) => {
      for (let attempt = 0; attempt < 20 && !screen.includes(text); attempt += 1) {
        const chunk = await read(sequence);
        sequence = chunk.sequence;
        screen += Buffer.from(chunk.dataBase64, 'base64').toString('utf8');
      }
      return screen;
    };
    expect(await readUntil('$ ')).toContain('$ ');
    await rpc(socket, 'ssh.writePty', { ...ref, channelId: pty.channelId, requestId: 'w1', sequence: 1, dataBase64: Buffer.from('typed-into-pty\r').toString('base64') });
    await expect(rpc(socket, 'ssh.writePty', { ...ref, channelId: pty.channelId, requestId: 'w2', sequence: 1, dataBase64: 'eA==' }))
      .rejects.toMatchObject({ code: 'STALE_SEQUENCE' });
    expect(await readUntil('typed-into-pty\r\n')).toContain('typed-into-pty\r\n');
    await rpc(socket, 'ssh.resizePty', { ...ref, channelId: pty.channelId, requestId: 'z', sequence: 2, cols: 100, rows: 30 });
    await expect(rpc(socket, 'ssh.resourceSnapshot', { requestId: 's' })).resolves.toMatchObject({ connections: 1, ptys: 1 });
    await rpc(socket, 'ssh.closePty', { ...ref, channelId: pty.channelId, requestId: 'c' });
    await rpc(socket, 'ssh.closeConnection', { ...ref, requestId: 'cc' });
    await expect(rpc(socket, 'ssh.resourceSnapshot', { requestId: 's2' })).resolves.toMatchObject({ connections: 0, ptys: 0 });
    socket.close();
  });

  it('imports, generates and deletes in-memory vault keys over the socket', async () => {
    const socket = await openSocket();
    const duplicate = await rpc(socket, 'vault.import', { privateKeyPem: readFileSync(clientKeyFile, 'utf8'), label: 'again' }).catch((error) => error);
    expect(duplicate).toMatchObject({ code: 'KEY_EXISTS' });
    await expect(rpc(socket, 'vault.import', { privateKeyPem: 'not a key', label: 'junk' })).rejects.toMatchObject({ code: 'INVALID_KEY' });
    const generated = await rpc(socket, 'vault.generate', { label: 'generated', algorithm: 'Ed25519' });
    expect(generated).toMatchObject({ label: 'generated', algorithm: 'ssh-ed25519', passphraseRequired: false });
    expect(generated.handleId).toMatch(/^[0-9a-f-]{36}$/u);
    expect(generated.fingerprintSha256).toMatch(/^SHA256:[A-Za-z0-9+/]{43}$/u);
    await expect(rpc(socket, 'vault.delete', { handleId: generated.handleId, fingerprintSha256: generated.fingerprintSha256 })).resolves.toEqual({ deleted: true });
    socket.close();
  });

  it('never logs credentials, key material, commands or PTY bytes', () => {
    const text = logs.join('\n');
    expect(logs.length).toBeGreaterThan(5);
    expect(text).toContain('ssh.connect failed HOST_KEY_REJECTED');
    const privateKeyBody = readFileSync(clientKeyFile, 'utf8').split('\n')[1];
    for (const secret of [PASSWORD, 'not it', privateKeyBody, 'typed-into-pty', 'echo bridge-exec', 'bridge-exec', TOKEN, 'wrong-token']) {
      expect(text).not.toContain(secret);
    }
  });
});
