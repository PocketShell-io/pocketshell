import { spawn, type ChildProcess } from 'node:child_process';
import http from 'node:http';
import net from 'node:net';
import path from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import WebSocket from 'ws';

/**
 * `pnpm dev:live` end to end (#3022 review round 1): the real launcher starts
 * the bridge and the Vite dev server, and a DNS-rebinding page or another
 * local user must not be able to obtain the bridge token or drive the bridge.
 *
 *  - the dev server answers only loopback Host headers (page and WebSocket);
 *  - the served HTML carries no bridge token (it travels in the URL fragment
 *    the launcher prints to its own terminal);
 *  - the bridge, reached through Vite's proxy, refuses a foreign Origin even
 *    with the right token.
 */
const repoRoot = path.resolve(__dirname, '../..');
let launcher: ChildProcess;
let port = 0;
let output = '';
let token = '';

function freePort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.on('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const { port: free } = server.address() as net.AddressInfo;
      server.close(() => resolve(free));
    });
  });
}

function get(requestPath: string, host: string): Promise<{ status: number; body: string }> {
  return new Promise((resolve, reject) => {
    const request = http.get({ host: '127.0.0.1', port, path: requestPath, headers: { Host: host, Accept: 'text/html' } }, (response) => {
      let body = '';
      response.setEncoding('utf8');
      response.on('data', (chunk) => { body += chunk; });
      response.on('end', () => resolve({ status: response.statusCode ?? 0, body }));
    });
    request.once('error', reject);
  });
}

/** Open the bridge through Vite's same-origin proxy, as the page does. */
function openBridge(headers: { origin: string; host: string }): Promise<{ opened: true; socket: WebSocket } | { opened: false; status: number }> {
  return new Promise((resolve) => {
    const socket = new WebSocket(
      `ws://127.0.0.1:${port}/__pocketshell-dev-bridge`,
      ['pocketshell-dev-bridge.v1', `token.${token}`],
      { headers: { Origin: headers.origin, Host: headers.host } },
    );
    socket.once('open', () => resolve({ opened: true, socket }));
    socket.once('unexpected-response', (_request, response) => {
      resolve({ opened: false, status: response.statusCode ?? 0 });
      socket.terminate();
    });
    socket.once('error', () => resolve({ opened: false, status: 0 }));
  });
}

function hello(socket: WebSocket): Promise<unknown> {
  return new Promise((resolve) => {
    socket.once('message', (raw) => resolve(JSON.parse(String(raw)).result));
    socket.send(JSON.stringify({ id: 1, method: 'hello', params: {} }));
  });
}

describe('browser dev mode live dev server', () => {
  beforeAll(async () => {
    port = await freePort();
    launcher = spawn(process.execPath, [path.join(repoRoot, 'scripts', 'dev-browser.mjs'), 'live', '--port', String(port)], {
      cwd: repoRoot,
      env: { ...process.env, BROWSER: 'none', POCKETSHELL_DEV_HOSTS: '', POCKETSHELL_DEV_IDENTITY: '' },
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    await new Promise<void>((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`dev:live did not start:\n${output}`)), 60_000);
      const onData = (chunk: Buffer) => {
        output += chunk.toString();
        if (/Local:\s+http:\/\//u.test(output)) {
          clearTimeout(timer);
          resolve();
        }
      };
      launcher.stdout?.on('data', onData);
      launcher.stderr?.on('data', onData);
      launcher.on('exit', (code: number | null) => {
        clearTimeout(timer);
        reject(new Error(`dev:live exited (${code}):\n${output}`));
      });
    });
    token = /#devBridgeToken=([A-Za-z0-9_-]+)/u.exec(output)?.[1] ?? '';
    expect(token.length).toBeGreaterThanOrEqual(16);
  }, 90_000);

  afterAll(async () => {
    if (launcher && launcher.exitCode === null) {
      const exited = new Promise((resolve) => launcher.on('exit', resolve));
      launcher.kill('SIGTERM');
      await exited;
    }
  });

  it('prints the page URL with the bridge token in its fragment to the launching terminal', () => {
    expect(output).toContain(`http://127.0.0.1:${port}/#devBridgeToken=${token}`);
  });

  it('serves the page to loopback Host headers only, and the page carries no bridge token', async () => {
    const page = await get('/', `127.0.0.1:${port}`);
    expect(page.status).toBe(200);
    expect(page.body).toContain('pocketshell-dev-config');
    expect(page.body).not.toContain(token);
    expect(page.body).not.toContain('bridgeToken');
    expect((await get('/', `localhost:${port}`)).status).toBe(200);
    for (const host of [`evil.example:${port}`, 'evil.example', `127.0.0.1.evil.example:${port}`, `localhost:${port + 1}`]) {
      const foreign = await get('/', host);
      expect(foreign.status, `Host: ${host}`).toBe(403);
      expect(foreign.body).not.toContain(token);
    }
    expect((await get('/src/dev/browser/install.ts', `evil.example:${port}`)).status).toBe(403);
  });

  it('refuses the bridge WebSocket from a foreign Host or a foreign Origin even with the right token', async () => {
    const devOrigin = `http://127.0.0.1:${port}`;
    const rebinding = await openBridge({ origin: `http://evil.example:${port}`, host: `evil.example:${port}` });
    expect(rebinding).toMatchObject({ opened: false });
    const foreignOrigin = await openBridge({ origin: 'http://evil.example', host: `127.0.0.1:${port}` });
    expect(foreignOrigin).toEqual({ opened: false, status: 403 });
    const otherLocalSite = await openBridge({ origin: `http://127.0.0.1:${port + 1}`, host: `127.0.0.1:${port}` });
    expect(otherLocalSite).toEqual({ opened: false, status: 403 });

    const page = await openBridge({ origin: devOrigin, host: `127.0.0.1:${port}` });
    expect(page.opened).toBe(true);
    if (page.opened) {
      await expect(hello(page.socket)).resolves.toEqual({ protocol: 'pocketshell-dev-ssh-bridge/1' });
      page.socket.close();
    }
  });
});
