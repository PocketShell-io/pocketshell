#!/usr/bin/env node
/**
 * Run the Android JS app in a plain browser with Vite HMR (#3022).
 *
 *   pnpm dev:mock                      fake host, no Android/adb/server needed
 *   pnpm dev:live -- [options]         real SSH through the local bridge
 *
 * Live options (each may repeat; env fallbacks in brackets):
 *   --host user@hostname[:port][=name]   seed a saved host        [POCKETSHELL_DEV_HOSTS, comma-separated]
 *   --identity <private key file>        preload a key into the bridge's in-memory vault
 *                                                                 [POCKETSHELL_DEV_IDENTITY, comma-separated]
 *   --bridge-port <n>                    bridge port (default: any free loopback port)
 *   --port <n>                           Vite port (default 5173; live mode insists on it)
 *
 * Live mode prints the page URL with the per-run bridge token in its fragment
 * (`#devBridgeToken=…`) to this terminal only; the served page never carries
 * the token, and the bridge accepts only the dev page's exact origin.
 * Anything after a further bare `--` is passed to Vite.
 *
 * Example against the Docker agents fixture:
 *   pnpm dev:live -- --host testuser@127.0.0.1:2222 --identity tests/docker/test_key
 */
import { spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { startBridge } from './dev-ssh-bridge/bridge.mjs';
import { loopbackOrigins } from './dev-ssh-bridge/loopback.mjs';

const repoRoot = path.dirname(path.dirname(fileURLToPath(import.meta.url)));

export function parseHostSpec(spec) {
  const match = /^([^@\s]+)@([^:=\s]+)(?::(\d+))?(?:=(.+))?$/u.exec(spec.trim());
  if (!match) throw new Error(`--host must look like user@hostname[:port][=name], got "${spec}"`);
  const port = match[3] ? Number(match[3]) : 22;
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error(`--host port is out of range: ${spec}`);
  return { name: match[4] ?? `${match[2]}:${port}`, hostname: match[2], port, user: match[1] };
}

export const DEFAULT_VITE_PORT = 5173;
export const BRIDGE_TOKEN_FRAGMENT = 'devBridgeToken';

/** The URL the launcher prints for live mode: the token rides in the fragment, never in a request. */
export function livePageUrl(port, token) {
  return `http://127.0.0.1:${port}/#${BRIDGE_TOKEN_FRAGMENT}=${token}`;
}

export function parseArgs(argv, env = process.env) {
  const [mode, ...given] = argv;
  // `pnpm dev:live -- --host …` forwards pnpm's own `--` separator: drop it.
  const rest = given[0] === '--' ? given.slice(1) : given;
  if (mode !== 'mock' && mode !== 'live') throw new Error('usage: dev-browser.mjs mock|live [options] [-- vite args]');
  const options = { mode, hosts: [], identities: [], bridgePort: 0, vitePort: null, viteArgs: [] };
  for (let index = 0; index < rest.length; index += 1) {
    const arg = rest[index];
    const value = () => {
      const next = rest[index + 1];
      if (next === undefined) throw new Error(`${arg} needs a value`);
      index += 1;
      return next;
    };
    if (arg === '--') {
      options.viteArgs.push(...rest.slice(index + 1));
      break;
    } else if (arg === '--host') options.hosts.push(parseHostSpec(value()));
    else if (arg === '--identity') options.identities.push(path.resolve(value()));
    else if (arg === '--bridge-port') options.bridgePort = Number(value());
    else if (arg === '--port') options.vitePort = Number(value());
    else throw new Error(`unknown option ${arg} (pass Vite options after --)`);
  }
  const fromEnv = (name) => (env[name] ?? '').split(',').map((part) => part.trim()).filter(Boolean);
  if (options.hosts.length === 0) options.hosts = fromEnv('POCKETSHELL_DEV_HOSTS').map(parseHostSpec);
  if (options.identities.length === 0) options.identities = fromEnv('POCKETSHELL_DEV_IDENTITY').map((file) => path.resolve(file));
  for (const [name, port] of [['--port', options.vitePort], ['--bridge-port', options.bridgePort]]) {
    if (port !== null && port !== 0 && (!Number.isInteger(port) || port < 1 || port > 65535)) throw new Error(`${name} is out of range: ${port}`);
  }
  if (mode === 'mock' && (options.hosts.length > 0 || options.identities.length > 0)) {
    throw new Error('--host/--identity are for dev:live; dev:mock has its own fake host.');
  }
  return options;
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  const env = { ...process.env };
  const viteArgs = ['--mode', options.mode];
  let bridge = null;
  let liveUrl = null;
  if (options.mode === 'mock') {
    if (options.vitePort) viteArgs.push('--port', String(options.vitePort));
    // Same reachability as `pnpm dev`: no secrets cross a mock page, so a
    // phone on the LAN can open it too.
    viteArgs.push('--host', '0.0.0.0');
  } else {
    // The bridge admits only the dev page's exact origin, so Vite must not
    // drift to another port when this one is taken.
    const vitePort = options.vitePort || DEFAULT_VITE_PORT;
    viteArgs.push('--port', String(vitePort), '--strictPort');
    const token = randomBytes(24).toString('base64url');
    bridge = await startBridge({
      port: options.bridgePort,
      token,
      allowedOrigins: loopbackOrigins(vitePort),
      identities: options.identities,
    });
    const preloaded = bridge.vault.list();
    const seeds = options.hosts.map((host) => ({ ...host, keyHandleId: preloaded[0]?.handleId ?? '' }));
    env.POCKETSHELL_DEV_BRIDGE_PORT = String(bridge.address.port);
    env.POCKETSHELL_DEV_PAGE_PORT = String(vitePort);
    env.POCKETSHELL_DEV_SEED_HOSTS = JSON.stringify(seeds);
    delete env.POCKETSHELL_DEV_BRIDGE_TOKEN;
    liveUrl = livePageUrl(vitePort, token);
    if (options.hosts.length > 0 && preloaded.length === 0) {
      process.stdout.write('[dev-browser] note: no --identity given; import a key in the app (Manage keys) before connecting.\n');
    }
  }
  viteArgs.push(...options.viteArgs);
  const vite = spawn(path.join(repoRoot, 'node_modules', '.bin', 'vite'), viteArgs, { cwd: repoRoot, env, stdio: 'inherit' });
  if (liveUrl) {
    process.stdout.write(
      '[dev-browser] Open this URL (it carries the per-run bridge token; keep it to this terminal):\n'
      + `[dev-browser]   ${liveUrl}\n`
      + '[dev-browser] The plain http://127.0.0.1 page cannot reach the bridge without it.\n',
    );
  }
  const stop = async (code) => {
    if (bridge) await bridge.close().catch(() => undefined);
    process.exit(code);
  };
  vite.on('exit', (code) => void stop(code ?? 0));
  for (const signal of ['SIGINT', 'SIGTERM']) {
    process.on(signal, () => {
      vite.kill(signal);
    });
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    process.stderr.write(`[dev-browser] ${error instanceof Error ? error.message : String(error)}\n`);
    process.exit(2);
  });
}
