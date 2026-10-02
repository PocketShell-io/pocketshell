#!/usr/bin/env node
/**
 * Headless smoke for the browser dev mode (#3022). Starts `pnpm dev:mock` or
 * `pnpm dev:live` itself, drives the app in Chromium at a phone viewport and
 * fails unless every journey step is observed.
 *
 *   node scripts/dev-browser-smoke.mjs mock
 *   node scripts/dev-browser-smoke.mjs live --host testuser@127.0.0.1:2222 --identity tests/docker/test_key
 *
 * Options:
 *   --port <n>          Vite port (default: a free one)
 *   --artifacts <dir>   where screenshots and result.json go (default build/dev-browser-smoke/<mode>)
 *   --chrome <path>     Chromium/Chrome executable (default: $CHROME_PATH, then common locations)
 *
 * mock journeys: legacy shell (host form → host-key trust → sessions →
 * terminal echo → composer send → settings/usage → Android back) and the
 * shared app (?shell=shared: host → trust → session → terminal echo), plus a
 * Vite HMR update without a page reload.
 *
 * live journey: the legacy shell against the real host — it creates a fresh
 * session, types a command whose output only a real shell produces, and an
 * independent SSH connection (not the bridge) reads the file that command
 * wrote; then the shared app opens the same session and types into it. The
 * session and file are removed afterwards.
 */
import { spawn } from 'node:child_process';
import { createHash, randomBytes } from 'node:crypto';
import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import ssh2 from 'ssh2';
import { chromium } from 'playwright-core';
import { parseArgs as parseLauncherArgs } from './dev-browser.mjs';

const repoRoot = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const PHONE = { viewport: { width: 412, height: 915 }, deviceScaleFactor: 2, isMobile: false, hasTouch: false };

function parseArgs(argv) {
  const [mode, ...rest] = argv;
  if (mode !== 'mock' && mode !== 'live') throw new Error('usage: dev-browser-smoke.mjs mock|live [options]');
  const options = { mode, port: 0, artifacts: null, chrome: process.env.CHROME_PATH ?? null, launcher: [] };
  for (let index = 0; index < rest.length; index += 1) {
    const arg = rest[index];
    const value = () => {
      index += 1;
      if (rest[index] === undefined) throw new Error(`${arg} needs a value`);
      return rest[index];
    };
    if (arg === '--port') options.port = Number(value());
    else if (arg === '--artifacts') options.artifacts = path.resolve(value());
    else if (arg === '--chrome') options.chrome = value();
    else if (arg === '--host' || arg === '--identity') options.launcher.push(arg, value());
    else throw new Error(`unknown option ${arg}`);
  }
  options.artifacts ??= path.join(repoRoot, 'build', 'dev-browser-smoke', mode);
  const launcher = parseLauncherArgs([mode, ...options.launcher], {});
  if (mode === 'live' && (launcher.hosts.length !== 1 || launcher.identities.length !== 1)) {
    throw new Error('live smoke needs exactly one --host and one --identity');
  }
  options.host = launcher.hosts[0] ?? null;
  options.identity = launcher.identities[0] ?? null;
  return options;
}

function findChrome(explicit) {
  const candidates = [explicit, '/usr/bin/google-chrome', '/usr/bin/google-chrome-stable', '/usr/bin/chromium', '/usr/bin/chromium-browser'];
  const cache = path.join(os.homedir(), '.cache', 'ms-playwright');
  if (existsSync(cache)) {
    for (const dir of readdirSync(cache).filter((name) => /^chromium-\d+$/u.test(name)).sort().reverse()) {
      candidates.push(path.join(cache, dir, 'chrome-linux64', 'chrome'), path.join(cache, dir, 'chrome-linux', 'chrome'));
    }
  }
  const found = candidates.find((candidate) => candidate && existsSync(candidate));
  if (!found) throw new Error('No Chrome/Chromium found; set CHROME_PATH or pass --chrome');
  return found;
}

function freePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address();
      server.close(() => resolve(port));
    });
  });
}

/** Start the dev launcher and resolve once Vite is serving. */
function startDevServer(options, port) {
  const args = [path.join(repoRoot, 'scripts', 'dev-browser.mjs'), options.mode, ...options.launcher, '--port', String(port), '--', '--strictPort'];
  const child = spawn(process.execPath, args, { cwd: repoRoot, env: { ...process.env, BROWSER: 'none' }, stdio: ['ignore', 'pipe', 'pipe'] });
  let log = '';
  const ready = new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`dev server did not start:\n${log}`)), 60_000);
    const onData = (chunk) => {
      log += chunk.toString();
      if (/Local:\s+http:\/\//u.test(log)) {
        clearTimeout(timer);
        resolve();
      }
    };
    child.stdout.on('data', onData);
    child.stderr.on('data', onData);
    child.once('exit', (code) => {
      clearTimeout(timer);
      reject(new Error(`dev server exited (${code}):\n${log}`));
    });
  });
  // dev:live prints the page URL with the per-run bridge token in its fragment.
  const token = () => /#devBridgeToken=([A-Za-z0-9_-]+)/u.exec(log)?.[1] ?? '';
  const redactedLog = () => {
    const secret = token();
    return secret ? log.split(secret).join('<bridge-token>') : log;
  };
  return { child, ready, token, log: redactedLog };
}

class Smoke {
  constructor(artifacts) {
    this.artifacts = artifacts;
    this.checks = [];
    this.console = [];
    this.retries = [];
  }

  /** Keep the page's console, errors and reloads for browser-console.log. */
  watch(page, label) {
    const started = Date.now();
    const note = (line) => this.console.push(`+${String(Date.now() - started).padStart(6)}ms [${label}] ${line}`);
    page.on('console', (message) => note(`${message.type()}: ${message.text()}`));
    page.on('pageerror', (error) => note(`pageerror: ${error.message}`));
    page.on('load', () => note('load'));
    page.on('requestfailed', (request) => {
      const reason = request.failure()?.errorText ?? '';
      if (reason.includes('ERR_NETWORK_CHANGED')) page.networkChanged = true;
      note(`requestfailed: ${request.url().split('#')[0]} ${reason}`);
    });
    return page;
  }

  /**
   * Open the app and wait until Vue replaced the boot fallback. Chromium
   * aborts every in-flight request with ERR_NETWORK_CHANGED when the host's
   * interfaces change (Docker containers starting or stopping elsewhere on a
   * shared box), which kills the dev server's module graph mid-load. Only
   * that signature earns one more load, and it is recorded in the check.
   */
  async open(page, url) {
    for (let attempt = 1; ; attempt += 1) {
      page.networkChanged = false;
      // A second goto to the same URL plus a fragment would only be an
      // in-page hash change; reload instead (live mode keeps the token in
      // the tab's sessionStorage).
      if (attempt === 1) await page.goto(url);
      else await page.reload();
      const mounted = await page.waitForFunction(() => !document.querySelector('#app .boot-fallback'), null, { timeout: 30_000 })
        .then(() => true, () => false);
      if (mounted) {
        if (attempt > 1) this.retries.push(`${url.split('#')[0]}: reloaded once after ERR_NETWORK_CHANGED`);
        return;
      }
      if (attempt > 1 || !page.networkChanged) throw new Error(`the app did not mount at ${url.split('#')[0]}`);
    }
  }

  async step(name, run) {
    const started = Date.now();
    try {
      const detail = await run();
      this.checks.push({ name, ok: true, ms: Date.now() - started, ...(detail ? { detail } : {}) });
      process.stdout.write(`PASS ${name}${detail ? ` — ${detail}` : ''}\n`);
    } catch (error) {
      this.checks.push({ name, ok: false, ms: Date.now() - started, error: String(error?.message ?? error) });
      process.stdout.write(`FAIL ${name}: ${error?.message ?? error}\n`);
      throw error;
    }
  }
}

async function terminalText(page) {
  return page.evaluate(() => [...document.querySelectorAll('.xterm-rows > div')].map((row) => row.textContent ?? '').join('\n'));
}

async function waitForTerminalLine(page, expected, timeoutMs = 15_000) {
  const until = Date.now() + timeoutMs;
  while (Date.now() < until) {
    const lines = (await terminalText(page)).split('\n').map((line) => line.trimEnd());
    if (lines.some((line) => line === expected)) return;
    await page.waitForTimeout(150);
  }
  throw new Error(`terminal never showed the line ${JSON.stringify(expected)}; last screen:\n${(await terminalText(page)).trim().slice(-800)}`);
}

async function typeInTerminal(page, text) {
  await page.locator('.xterm:visible').first().click();
  await page.keyboard.type(text, { delay: 15 });
  await page.keyboard.press('Enter');
}

async function shot(page, smoke, name) {
  const file = path.join(smoke.artifacts, `${name}.png`);
  await page.screenshot({ path: file });
  return file;
}

/** The mock host's fixed ed25519 key, rebuilt here as an independent oracle. */
function mockHostFingerprint() {
  const type = Buffer.from('ssh-ed25519');
  const key = Buffer.from(Array.from({ length: 32 }, (_, index) => (index * 37 + 11) & 0xff));
  const length = (value) => { const buffer = Buffer.alloc(4); buffer.writeUInt32BE(value.length); return buffer; };
  const blob = Buffer.concat([length(type), type, length(key), key]);
  return `SHA256:${createHash('sha256').update(blob).digest('base64').replace(/=+$/u, '')}`;
}

/** The real host's key fingerprint, read over an independent SSH handshake. */
function liveHostFingerprint(options) {
  return new Promise((resolve, reject) => {
    const client = new ssh2.Client();
    let fingerprint = null;
    client.once('ready', () => { client.end(); resolve(fingerprint); });
    client.once('error', (error) => (fingerprint ? resolve(fingerprint) : reject(error)));
    client.connect({
      host: options.host.hostname,
      port: options.host.port,
      username: options.host.user,
      privateKey: readFileSync(options.identity),
      readyTimeout: 15_000,
      hostVerifier: (key) => {
        fingerprint = `SHA256:${createHash('sha256').update(key).digest('base64').replace(/=+$/u, '')}`;
        return true;
      },
    });
  });
}

async function connectLegacy(page, smoke, expectedHost, expectedFingerprint) {
  await smoke.step('legacy: connect form opens prefilled with the dev host', async () => {
    await page.waitForSelector('[data-testid="ssh-connect"]', { timeout: 30_000 });
    await page.waitForFunction((host) => document.querySelector('[data-testid="ssh-host"]')?.value === host, expectedHost, { timeout: 15_000 });
    await shot(page, smoke, 'legacy-01-connect');
    return expectedHost;
  });
  await smoke.step('legacy: host-key trust prompt shows the presented fingerprint', async () => {
    await page.click('[data-testid="ssh-connect"]');
    await page.waitForSelector('[data-testid="trust-host-key"]', { timeout: 20_000 });
    const text = await page.locator('body').innerText();
    if (!text.includes(expectedFingerprint)) throw new Error(`the trust prompt does not show the host's fingerprint ${expectedFingerprint}`);
    await shot(page, smoke, 'legacy-02-trust');
    await page.click('[data-testid="trust-host-key"]');
    return expectedFingerprint;
  });
  await smoke.step('legacy: sessions list loads after trusting the host', async () => {
    await page.waitForSelector('.session-row', { timeout: 30_000 });
    const count = await page.locator('.session-row').count();
    await shot(page, smoke, 'legacy-03-sessions');
    return `${count} sessions`;
  });
}

async function mockJourneys(browser, base, smoke) {
  const context = await browser.newContext(PHONE);
  const page = smoke.watch(await context.newPage(), 'legacy');
  const marker = `DEVSMOKE_${randomBytes(4).toString('hex')}`;
  await smoke.open(page, `${base}/?devSpeech=stub`);
  await connectLegacy(page, smoke, 'devbox.mock', mockHostFingerprint());
  await smoke.step('legacy: the mock host lists its five sessions across three workspaces', async () => {
    const names = await page.locator('.session-row').evaluateAll((rows) => rows.map((row) => row.getAttribute('data-session-name')));
    if (names.length !== 5 || !names.includes('pocketshell:shell')) throw new Error(`unexpected sessions ${names.join(', ')}`);
    return names.join(', ');
  });
  await smoke.step('legacy: attach a session and see typed input echoed by the PTY', async () => {
    await page.click('[data-session-name="pocketshell:shell"]');
    await page.locator('.xterm:visible').first().waitFor({ timeout: 15_000 });
    await waitForTerminalLine(page, 'dev@devbox:~/git/pocketshell$', 10_000).catch(() => undefined);
    await typeInTerminal(page, `echo ${marker}`);
    await waitForTerminalLine(page, marker);
    await shot(page, smoke, 'legacy-04-terminal');
    return marker;
  });
  await smoke.step('legacy: the prompt composer sends a command to the terminal', async () => {
    await page.click('[data-testid="prompt-composer-launcher"]');
    await page.waitForSelector('[data-testid="prompt-draft"]', { timeout: 10_000 });
    await page.fill('[data-testid="prompt-draft"]', `echo composer-${marker}`);
    await shot(page, smoke, 'legacy-05-composer');
    await page.locator('[data-testid="prompt-composer"] button', { hasText: /^Send$/u }).first().click();
    await waitForTerminalLine(page, `composer-${marker}`);
    return `composer-${marker}`;
  });
  await smoke.step('legacy: settings → provider usage shows the mock providers', async () => {
    if (await page.locator('[data-testid="prompt-composer"]').isVisible()) await page.click('[data-testid="composer-close"]').catch(() => undefined);
    await page.click('[aria-label="Settings"]');
    await page.click('[data-testid="open-usage"]');
    await page.getByText('Claude Code').first().waitFor({ timeout: 10_000 });
    await shot(page, smoke, 'legacy-06-usage');
    return 'Claude Code, Codex, Copilot, Gemini';
  });
  await smoke.step('legacy: the dev toolbar Back button acts as the Android back button', async () => {
    await page.click('[data-testid="dev-back"]');
    await page.waitForSelector('[data-testid="open-usage"]', { timeout: 10_000 });
    return 'usage → settings';
  });
  await context.close();

  const shared = await browser.newContext(PHONE);
  const sharedPage = smoke.watch(await shared.newPage(), 'shared');
  await smoke.open(sharedPage, `${base}/?shell=shared`);
  await smoke.step('shared: the seeded mock host is listed and asks for host-key trust', async () => {
    await sharedPage.getByText('mock-devbox').first().click({ timeout: 30_000 });
    await sharedPage.waitForSelector('[data-testid="trust-host-key"]', { timeout: 20_000 });
    if (!(await sharedPage.locator('body').innerText()).includes(mockHostFingerprint())) {
      throw new Error('the shared trust prompt does not show the mock host fingerprint');
    }
    await shot(sharedPage, smoke, 'shared-01-trust');
    await sharedPage.click('[data-testid="trust-host-key"]');
  });
  await smoke.step('shared: the session tree opens a terminal that echoes typed input', async () => {
    await sharedPage.getByText('codex-review').first().click({ timeout: 20_000 });
    await sharedPage.locator('.xterm:visible').first().waitFor({ timeout: 15_000 });
    await typeInTerminal(sharedPage, `echo shared-${marker}`);
    await waitForTerminalLine(sharedPage, `shared-${marker}`);
    await shot(sharedPage, smoke, 'shared-02-terminal');
    return `shared-${marker}`;
  });

  await smoke.step('vite: a hot update applies without reloading the page', async () => {
    const probeDir = path.join(repoRoot, 'build', 'dev-browser-smoke', 'hmr');
    mkdirSync(probeDir, { recursive: true });
    const probe = path.join(probeDir, `probe-${randomBytes(3).toString('hex')}.css`);
    writeFileSync(probe, ':root { --ps-hmr-probe: one; }\n');
    try {
      const url = `/${path.relative(repoRoot, probe).split(path.sep).join('/')}`;
      await sharedPage.evaluate(async (specifier) => {
        window.__psHmrPageMarker = 'still-the-same-page';
        await import(/* @vite-ignore */ specifier);
      }, url);
      const read = () => sharedPage.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue('--ps-hmr-probe').trim());
      await sharedPage.waitForFunction(() => getComputedStyle(document.documentElement).getPropertyValue('--ps-hmr-probe').trim() === 'one', null, { timeout: 10_000 });
      writeFileSync(probe, ':root { --ps-hmr-probe: two; }\n');
      await sharedPage.waitForFunction(() => getComputedStyle(document.documentElement).getPropertyValue('--ps-hmr-probe').trim() === 'two', null, { timeout: 15_000 });
      const sameDocument = await sharedPage.evaluate(() => window.__psHmrPageMarker === 'still-the-same-page');
      if (!sameDocument) throw new Error('the page reloaded instead of hot-updating');
      return `--ps-hmr-probe ${'one'} → ${await read()} in place`;
    } finally {
      rmSync(probe, { force: true });
    }
  });
  await shared.close();
}

function oracleExec(options, command) {
  return new Promise((resolve, reject) => {
    const client = new ssh2.Client();
    client.once('ready', () => {
      client.exec(command, (error, stream) => {
        if (error) {
          client.end();
          reject(error);
          return;
        }
        let stdout = '';
        let stderr = '';
        stream.on('data', (chunk) => { stdout += chunk; });
        stream.stderr.on('data', (chunk) => { stderr += chunk; });
        stream.on('close', (code) => {
          client.end();
          resolve({ code, stdout, stderr });
        });
      });
    });
    client.once('error', reject);
    client.connect({
      host: options.host.hostname,
      port: options.host.port,
      username: options.host.user,
      privateKey: readFileSync(options.identity),
      readyTimeout: 15_000,
    });
  });
}

async function liveJourney(browser, base, smoke, options, token) {
  if (!token) throw new Error('dev:live did not print the page URL with its bridge token');
  // The token rides in the fragment, exactly as a developer opens the printed URL.
  const entry = (pathAndQuery) => `${base}${pathAndQuery}#devBridgeToken=${token}`;
  const tag = `devsmoke-${randomBytes(4).toString('hex')}`;
  const file = `/tmp/${tag}.txt`;
  const expected = `DEVSMOKE_42_${tag}`;
  const context = await browser.newContext(PHONE);
  const page = smoke.watch(await context.newPage(), 'legacy');
  let sessionName = null;
  try {
    await smoke.open(page, entry('/?devSpeech=stub'));
    await smoke.step('live: the page takes the bridge token from the fragment and clears it from the URL', async () => {
      await page.waitForFunction(() => document.documentElement.dataset.devBrowser === 'live', null, { timeout: 30_000 });
      if (page.url().includes(token)) throw new Error('the bridge token is still in the address bar');
      const html = await (await fetch(`${base}/`)).text();
      if (html.includes(token)) throw new Error('the served page carries the bridge token');
      return page.url();
    });
    await connectLegacy(page, smoke, options.host.hostname, await liveHostFingerprint(options));
    await smoke.step('live: create a fresh session on the real host', async () => {
      await page.fill('[data-testid="new-session-name"]', tag);
      await page.click('[data-testid="create-session"]');
      const row = page.locator(`.session-row[data-session-tag="${tag}"]`);
      await row.first().waitFor({ timeout: 60_000 });
      sessionName = await row.first().getAttribute('data-session-name');
      return sessionName;
    });
    await smoke.step('live: type into the real PTY and see the shell evaluate it', async () => {
      if (!(await page.locator('.xterm:visible').first().isVisible().catch(() => false))) {
        await page.click(`.session-row[data-session-tag="${tag}"]`);
      }
      await page.locator('.xterm:visible').first().waitFor({ timeout: 30_000 });
      await page.waitForTimeout(1_500);
      // Only a real shell turns $((40+2)) into 42; the typed text never contains it.
      await typeInTerminal(page, `echo DEVSMOKE_$((40+2))_${tag} | tee ${file}`);
      await waitForTerminalLine(page, expected, 30_000);
      await shot(page, smoke, 'live-01-terminal');
      return expected;
    });
    await smoke.step('live: an independent SSH connection reads what the PTY command wrote', async () => {
      const result = await oracleExec(options, `cat ${file}`);
      if (result.code !== 0 || result.stdout.trim() !== expected) {
        throw new Error(`host oracle read ${JSON.stringify(result.stdout.trim())} (exit ${result.code}), expected ${expected}`);
      }
      const listing = await oracleExec(options, 'pocketshell sessions list --json');
      if (!listing.stdout.includes(`"${sessionName}"`)) throw new Error(`host listing does not show ${sessionName}`);
      return `${file} = ${result.stdout.trim()}; ${sessionName} listed by the host`;
    });
    await smoke.step('live shared: the shared app opens the same session and types into its real PTY', async () => {
      const shared = await browser.newContext(PHONE);
      try {
        const sharedPage = smoke.watch(await shared.newPage(), 'shared');
        await smoke.open(sharedPage, entry('/?shell=shared'));
        await sharedPage.getByText(options.host.name).first().click({ timeout: 30_000 });
        await sharedPage.waitForSelector('[data-testid="trust-host-key"]', { timeout: 20_000 });
        await sharedPage.click('[data-testid="trust-host-key"]');
        await sharedPage.getByText(tag, { exact: true }).first().click({ timeout: 30_000 });
        await sharedPage.locator('.xterm:visible').first().waitFor({ timeout: 30_000 });
        await sharedPage.waitForTimeout(1_500);
        await typeInTerminal(sharedPage, `echo SHARED_$((40+2))_${tag}`);
        await waitForTerminalLine(sharedPage, `SHARED_42_${tag}`, 30_000);
        await shot(sharedPage, smoke, 'live-02-shared-terminal');
        return `SHARED_42_${tag}`;
      } finally {
        await shared.close();
      }
    });
  } finally {
    await context.close();
    await oracleExec(options, `rm -f ${file}${sessionName ? `; pocketshell sessions kill -- '${sessionName.replace(/'/gu, `'\\''`)}'` : ''}`).catch(() => undefined);
  }
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  mkdirSync(options.artifacts, { recursive: true });
  const smoke = new Smoke(options.artifacts);
  const port = options.port || await freePort();
  const server = startDevServer(options, port);
  let browser = null;
  let failed = false;
  try {
    await server.ready;
    const base = `http://127.0.0.1:${port}`;
    browser = await chromium.launch({ executablePath: findChrome(options.chrome), headless: true, args: ['--no-sandbox'] });
    if (options.mode === 'mock') await mockJourneys(browser, base, smoke);
    else await liveJourney(browser, base, smoke, options, server.token());
  } catch (error) {
    failed = true;
    if (!smoke.checks.some((check) => !check.ok)) {
      smoke.checks.push({ name: 'harness', ok: false, error: String(error?.message ?? error) });
      process.stdout.write(`FAIL harness: ${error?.message ?? error}\n`);
    }
  } finally {
    await browser?.close().catch(() => undefined);
    server.child.kill('SIGTERM');
    writeFileSync(path.join(options.artifacts, 'dev-server.log'), server.log());
    const secret = server.token();
    const consoleLog = smoke.console.join('\n');
    writeFileSync(path.join(options.artifacts, 'browser-console.log'), `${secret ? consoleLog.split(secret).join('<bridge-token>') : consoleLog}\n`);
  }
  const passed = smoke.checks.filter((check) => check.ok).length;
  const result = { mode: options.mode, passed, failed: smoke.checks.length - passed, checks: smoke.checks, infraRetries: smoke.retries };
  writeFileSync(path.join(options.artifacts, 'result.json'), `${JSON.stringify(result, null, 2)}\n`);
  process.stdout.write(`${options.mode} smoke: ${passed}/${smoke.checks.length} checks passed; artifacts in ${options.artifacts}\n`);
  if (failed || passed === 0 || result.failed > 0) process.exit(1);
}

main().catch((error) => {
  process.stderr.write(`dev-browser-smoke: ${error?.message ?? error}\n`);
  process.exit(2);
});
