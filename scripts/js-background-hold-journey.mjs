#!/usr/bin/env node
/**
 * Background grace hold journey (#3160), driven from the HOST over raw CDP.
 *
 * Why not an androidTest: an instrumented app process is pinned to a
 * foreground-service process state by ActivityManager for as long as the
 * instrumentation runs, so it is never frozen and its network is never put on
 * Android's background firewall chain. Every instrumented "background" journey
 * (J06, the shared-app suite) therefore keeps its SSH connection whatever the
 * app does, and cannot see the bug the maintainer hits on a phone. This journey
 * launches the packaged APK normally (`am start`, no instrumentation), presses
 * the real HOME key, and drives the shared shell's WebView through its
 * DevTools socket, so Android applies exactly what it applies on a phone.
 *
 * Cases (each writes one <testcase> to the JUnit file; the lane's checker
 * requires all of them, by name, with no failure or skip):
 *   withinGraceHomeKeepsSameConnection     HOME 30 s, back: same transport, same
 *                                          host TCP connection, no dial, typed
 *                                          command echoes on the same PTY; the
 *                                          hold notification exists only while
 *                                          backgrounded.
 *   deviceIdleWithinGraceKeepsConnection   the same under `deviceidle force-idle`.
 *   rapidReturnNeverCrashes                20 HOME→return cycles 300 ms to
 *                                          1.5 s apart (the hold's foreground-service
 *                                          start racing its stop, #2483 class): no
 *                                          crash, same process, same connection.
 *   beyondGraceAutoReattachesSameSession   the Settings grace (30 s) ends the hold
 *                                          and the host connection; return
 *                                          re-attaches the same host session with
 *                                          its scrollback, no banner left.
 *   networkReturnAfterGiveUpReattaches     beyond grace, the network is unusable at
 *                                          the return (airplane mode: every dial
 *                                          fails fast, as a blocked app's DNS does
 *                                          on a phone) until the reconnect ladder
 *                                          gives up; when the network comes back the
 *                                          app re-attaches by itself (no Reconnect
 *                                          tap) with its scrollback.
 *   restoredTabReconnects                  the maintainer's stranded tab via resume:
 *                                          away past grace with the network off,
 *                                          back while it is still off. A Reconnect
 *                                          is visible and fails honestly when
 *                                          tapped offline; once the network returns
 *                                          the pane recovers the same session by
 *                                          itself.
 *
 * Usage:
 *   node scripts/js-background-hold-journey.mjs --serial emulator-5630 \
 *     --package com.pocketshell.app.i3160 --port 2263 \
 *     --container pocketshell-test-agents-2263 --key tests/docker/test_key \
 *     --run-id bg3160-local --out build/js-background-hold/bg3160-local \
 *     [--cases a,b] [--adb PATH]
 */
import WebSocket from 'ws';
import { execFileSync, spawnSync } from 'node:child_process';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:net';
import path from 'node:path';

export const CASES = [
  'withinGraceHomeKeepsSameConnection',
  'deviceIdleWithinGraceKeepsConnection',
  'rapidReturnNeverCrashes',
  'beyondGraceAutoReattachesSameSession',
  'networkReturnAfterGiveUpReattaches',
  'restoredTabReconnects',
];

const args = process.argv.slice(2);
const opt = (name, fallback) => (args.includes(name) ? args[args.indexOf(name) + 1] : fallback);
const ADB = opt('--adb', process.env.ADB ?? '/home/alexey/Android/Sdk/platform-tools/adb');
const SERIAL = opt('--serial', process.env.ANDROID_SERIAL);
const PKG = opt('--package');
const PORT = opt('--port');
const CONTAINER = opt('--container');
const KEY = opt('--key', 'tests/docker/test_key');
const RUN = opt('--run-id', `bg${Date.now().toString(36)}`);
const OUT = opt('--out', `build/js-background-hold/${RUN}`);
const SELECTED = opt('--cases', CASES.join(',')).split(',').filter(Boolean);
const HOST = opt('--ssh-host', '10.0.2.2');
const WITHIN_MS = Number(opt('--within-ms', '30000'));
/** The Settings window case 3 selects (the shortest one offered). */
const SHORT_GRACE_MS = 30_000;
const REATTACH_DEADLINE_MS = 25_000;
/** The network takes a few seconds to come back after airplane mode. */
const NETWORK_RETURN_REATTACH_MS = 45_000;

function validateArgs() {
  for (const [name, value] of Object.entries({ '--serial': SERIAL, '--package': PKG, '--port': PORT, '--container': CONTAINER })) {
    if (!value) throw new Error(`${name} is required`);
  }
  for (const name of SELECTED) if (!CASES.includes(name)) throw new Error(`unknown case ${name}`);
  if (!/^[a-z0-9][a-z0-9-]{4,30}$/.test(RUN)) throw new Error('--run-id must be 5-31 lowercase tag-safe characters');
  mkdirSync(OUT, { recursive: true });
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const log = (...parts) => console.log(`[${new Date().toISOString()}]`, ...parts);

function adb(...command) {
  return execFileSync(ADB, ['-s', SERIAL, ...command], { encoding: 'utf8', maxBuffer: 64 << 20 });
}
function shell(command) {
  return adb('shell', command);
}
function docker(...command) {
  return execFileSync('docker', command, { encoding: 'utf8', maxBuffer: 16 << 20 });
}

/* --- device-side oracles --------------------------------------------------- */

/** Our app's notifications, from NotificationManagerService (not the app). */
export function parseAppNotifications(dump, pkg) {
  const found = [];
  const blocks = dump.split(/\n(?=\s*NotificationRecord\()/);
  for (const block of blocks) {
    if (!block.includes(`pkg=${pkg} `) && !block.includes(`pkg=${pkg}\n`)) continue;
    const title = /android\.title=String \(([^)]*)\)/.exec(block)?.[1] ?? '';
    const text = /android\.text=String \(([^)]*)\)/.exec(block)?.[1] ?? '';
    const channel = /channel=([^\s]+)/.exec(block)?.[1] ?? '';
    const fgs = /flags=0x[0-9a-f]*/i.exec(block)?.[0] ?? '';
    found.push({ title, text, channel, flags: fgs });
  }
  return found;
}

function appNotifications() {
  return parseAppNotifications(shell('dumpsys notification --noredact'), PKG);
}

function holdNotification() {
  return appNotifications().find((n) => /Keeping \d+ connections? alive/.test(n.text)) ?? null;
}

/** The app process's ActivityManager process state (e.g. TOP, FGS, CEM). */
/** Our activity's lifecycle state as ActivityTaskManager sees it (RESUMED, STOPPED, ...). */
function activityState() {
  const out = shell(`dumpsys activity activities | grep -E 'ActivityRecord.*${PKG}/' -A 30 | grep -m1 -E ' state='; true`).trim();
  return /state=(\S+)/.exec(out)?.[1] ?? 'unknown';
}

function processState() {
  const out = shell(`dumpsys activity processes ${PKG}`);
  const proc = /\*\s*(?:APP|PERS)?\*?\s*UID\s+\d+\s+ProcessRecord\{[^}]*\}[\s\S]*?(?=\n\s*\*|$)/.exec(out)?.[0] ?? out;
  return {
    procState: /procState=(\S+)/.exec(proc)?.[1] ?? /curProcState=(\S+)/.exec(proc)?.[1] ?? 'unknown',
    frozen: /mFrozen=true|isFrozen=true|frozen=true/.test(proc),
  };
}

/** The app uid's network block reasons, from NetworkPolicyManagerService. */
function networkBlocked(uid) {
  const out = shell('dumpsys netpolicy');
  const line = out.split('\n').find((l) => l.trim().startsWith(`UID=${uid} `)) ?? '';
  const effective = /effective=([A-Z_|]+)/.exec(line)?.[1] ?? 'unknown';
  return { line: line.trim(), effective };
}

function appUid() {
  const out = shell(`dumpsys package ${PKG}`);
  const uid = /(?:userId|appId)=(\d+)/.exec(out)?.[1];
  if (!uid) throw new Error(`no uid for ${PKG}`);
  return uid;
}

/**
 * Established SSH TCP connections inside the Docker fixture, keyed by peer
 * address. A new client TCP connection always arrives on a new peer port, so
 * an unchanged peer port is the host's own proof of "same connection".
 */
function hostSshConnections() {
  const out = docker('exec', CONTAINER, 'sh', '-c', "ss -Htn state established '( sport = :22 )' 2>/dev/null || netstat -tn 2>/dev/null");
  return out.split('\n').map((l) => l.trim().split(/\s+/)).filter((f) => f.length >= 4)
    .map((f) => f[f.length - 1]).filter((peer) => /:\d+$/.test(peer))
    // The fixture's own health checks dial loopback; the app arrives from the bridge.
    .filter((peer) => !/^(\[::1\]|127\.)/.test(peer)).sort();
}

/** Peers already connected before this run's app dialled: not ours. */
let foreignPeers = new Set();

/** Only the SSH connections this run's app opened. */
function appSshConnections() {
  return hostSshConnections().filter((peer) => !foreignPeers.has(peer));
}

function hostSessionAlive(tag) {
  const out = docker('exec', '-u', 'testuser', CONTAINER, 'sh', '-lc',
    'PATH="$HOME/.local/bin:$PATH" pocketshell sessions list --json');
  const rows = JSON.parse(out).sessions ?? [];
  return rows.find((row) => row.tag === tag || row.name === tag) ?? null;
}

/* --- CDP ------------------------------------------------------------------ */

async function freePort() {
  return new Promise((resolve, reject) => {
    const server = createServer();
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address();
      server.close(() => resolve(port));
    });
    server.on('error', reject);
  });
}

class Page {
  constructor(ws) {
    this.ws = ws;
    this.nextId = 0;
    this.pending = new Map();
    ws.on('message', (raw) => {
      const msg = JSON.parse(raw);
      if (msg.id && this.pending.has(msg.id)) {
        this.pending.get(msg.id)(msg);
        this.pending.delete(msg.id);
      }
    });
  }

  static async attach(timeoutMs = 45_000) {
    const deadline = Date.now() + timeoutMs;
    let lastError = '';
    while (Date.now() < deadline) {
      try {
        const pid = shell(`pidof ${PKG}`).trim().split(/\s+/)[0];
        if (!pid) throw new Error('app process not running');
        const port = await freePort();
        adb('forward', `tcp:${port}`, `localabstract:webview_devtools_remote_${pid}`);
        const pages = await (await fetch(`http://127.0.0.1:${port}/json`)).json();
        const target = pages.find((p) => p.type === 'page' && /localhost/.test(p.url));
        if (!target) throw new Error(`no app page yet: ${JSON.stringify(pages.map((p) => p.url))}`);
        const ws = new WebSocket(target.webSocketDebuggerUrl);
        await new Promise((resolve, reject) => { ws.once('open', resolve); ws.once('error', reject); });
        const page = new Page(ws);
        page.pid = pid;
        page.port = port;
        return page;
      } catch (error) {
        lastError = String(error?.message ?? error);
        await sleep(1_000);
      }
    }
    throw new Error(`could not attach to the app WebView: ${lastError}`);
  }

  send(method, params = {}) {
    return new Promise((resolve, reject) => {
      const id = ++this.nextId;
      const timer = setTimeout(() => { this.pending.delete(id); reject(new Error(`CDP ${method} timed out`)); }, 30_000);
      this.pending.set(id, (msg) => { clearTimeout(timer); resolve(msg); });
      this.ws.send(JSON.stringify({ id, method, params }));
    });
  }

  async eval(expression) {
    const reply = await this.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    if (reply.error) throw new Error(`CDP error: ${JSON.stringify(reply.error)}`);
    if (reply.result?.exceptionDetails) {
      throw new Error(`page threw: ${reply.result.exceptionDetails.exception?.description ?? JSON.stringify(reply.result.exceptionDetails)}`);
    }
    return reply.result?.result?.value;
  }

  async waitFor(expression, label, timeoutMs = 45_000) {
    const deadline = Date.now() + timeoutMs;
    let last;
    while (Date.now() < deadline) {
      try {
        last = await this.eval(`(() => { try { return !!(${expression}); } catch (e) { return false; } })()`);
        if (last) return;
      } catch (error) {
        last = String(error?.message ?? error);
      }
      await sleep(250);
    }
    const body = await this.eval('document.body?.innerText?.slice(0, 1500) ?? ""').catch(() => '<unreadable>');
    throw new Error(`timed out (${timeoutMs} ms) waiting for ${label}; last=${last}; visible page:\n${body}`);
  }

  close() {
    try { this.ws.close(); } catch { /* ignore */ }
    try { adb('forward', '--remove', `tcp:${this.port}`); } catch { /* ignore */ }
  }
}

const VISIBLE_TERMINAL = "([...document.querySelectorAll('.xterm-rows')].find((n)=>n.offsetParent!==null)?.innerText ?? '')";
const VISIBLE_TEXTAREA = "[...document.querySelectorAll('.xterm')].find((n)=>n.offsetParent!==null)?.querySelector('.xterm-helper-textarea')";
const BANNER = "(document.querySelector('.link-lost-text')?.textContent?.trim() ?? '')";

/** The journal's newest live entry: the logical connection, transport and attached session. */
async function liveEntry(page) {
  return page.eval(`JSON.parse(JSON.stringify([...(window.__pocketshellConnectionJournal ?? [])].reverse()
    .find((e)=>e.phase==='live' && e.transportId && e.selectedId) ?? null))`);
}

async function recorder(page) {
  return page.eval('JSON.parse(JSON.stringify(window.__ps3160 ?? null))');
}

async function installRecorder(page) {
  await page.waitFor("typeof window.Capacitor?.nativePromise === 'function' && typeof window.Capacitor?.Plugins?.SshCapability?.addListener === 'function'", 'the Capacitor bridge');
  await page.eval(`(() => {
    if (window.__ps3160) return 'already';
    const rec = window.__ps3160 = { dials: [], opens: [], closes: [], writes: [], events: [], banners: [], lifecycle: [], ready: false };
    const nativePromise = window.Capacitor.nativePromise.bind(window.Capacitor);
    window.Capacitor.nativePromise = (plugin, method, options) => {
      if (plugin === 'SshCapability' && method === 'connect') rec.dials.push({ at: Date.now() });
      if (plugin === 'SshCapability' && method === 'openPty') rec.opens.push({ at: Date.now() });
      if (plugin === 'SshCapability' && method === 'closePty') rec.closes.push({ at: Date.now() });
      const reply = nativePromise(plugin, method, options);
      if (plugin === 'SshCapability' && method === 'writePty') {
        // What the terminal actually sent, and whether the native write landed.
        const entry = { at: Date.now(), channel: String(options?.channelId ?? '').slice(0, 8), bytes: (() => { try { return atob(options.dataBase64); } catch { return '?'; } })() };
        rec.writes.push(entry);
        if (rec.writes.length > 100) rec.writes.shift();
        reply.then(() => { entry.ok = true; }, (error) => { entry.error = String(error?.message ?? error); });
      }
      return reply;
    };
    const sample = () => {
      const t = document.querySelector('.link-lost-text')?.textContent?.trim();
      if (t && rec.banners.at(-1)?.text !== t) rec.banners.push({ at: Date.now(), text: t });
    };
    new MutationObserver(sample).observe(document.body, { subtree: true, childList: true, characterData: true });
    window.Capacitor.Plugins.App?.addListener?.('appStateChange', (e) => rec.lifecycle.push({ at: Date.now(), active: e.isActive }));
    window.Capacitor.Plugins.SshCapability.addListener('connectionState', (e) => rec.events.push({ ...e, at: Date.now() }))
      .then(() => { rec.ready = true; });
    return 'installed';
  })()`);
  await page.waitFor('window.__ps3160?.ready === true', 'the recorder');
}

async function nativeExec(page, transportId, generationId, command) {
  const key = `exec-${Date.now()}-${Math.random().toString(36).slice(2)}`;
  await page.eval(`(() => {
    const entry = (window.__ps3160execs ??= {})[${JSON.stringify(key)}] = { settled: false };
    window.Capacitor.Plugins.SshCapability.exec({ requestId: ${JSON.stringify(key)}, connectionId: ${JSON.stringify(transportId)},
      generationId: ${JSON.stringify(generationId)}, command: ${JSON.stringify(command)}, timeoutMs: 20000 })
      .then((r) => { entry.result = r; entry.settled = true; }, (e) => { entry.error = String(e?.message ?? e); entry.settled = true; });
    return 'started';
  })()`);
  await page.waitFor(`window.__ps3160execs[${JSON.stringify(key)}].settled`, `native exec ${key}`, 25_000);
  const state = await page.eval(`JSON.parse(JSON.stringify(window.__ps3160execs[${JSON.stringify(key)}]))`);
  if (state.error) throw new Error(`native exec failed: ${state.error}`);
  return state.result;
}

/** Type one line into the visible terminal, the way a keyboard commit reaches xterm. */
/** Every textarea input event, so a lost keystroke says where it was lost. */
async function recordTextareaEvents(page) {
  await page.eval(`(() => { const t = ${VISIBLE_TEXTAREA}; if (!t || t.__ps3160) return 'same';
    t.__ps3160 = true; const log = window.__ps3160input ??= [];
    for (const k of ['keydown', 'keyup', 'beforeinput', 'input', 'compositionstart', 'compositionend', 'focus', 'blur'])
      t.addEventListener(k, (e) => { log.push([Date.now(), k, e.key ?? e.data ?? '', e.inputType ?? '', t.value, document.hasFocus()]);
        if (log.length > 200) log.shift(); }, true);
    return 'installed'; })()`);
}

async function typeLine(page, text) {
  // A keyboard can only type into a window that is on screen and focused;
  // after a return that is the first moment a user's keystroke can land.
  await page.waitFor("document.visibilityState === 'visible' && document.hasFocus()", 'the app window to be visible and focused', 10_000);
  // Recorded, not hidden: an open prompt composer would take the keystrokes.
  const composerClosed = await page.eval(`(() => { const close = [...document.querySelectorAll('[aria-label="Close the prompt panel"]')]
    .find((n) => n.offsetParent !== null); if (!close) return false; close.click(); return true; })()`);
  if (composerClosed) {
    evidence.composerClosedBeforeTyping = (evidence.composerClosedBeforeTyping ?? 0) + 1;
    await sleep(400);
  }
  await page.eval(`(() => { const t = ${VISIBLE_TEXTAREA}; if (!t) throw new Error('no visible terminal'); t.focus(); return 'ok'; })()`);
  await page.waitFor(`document.activeElement === ${VISIBLE_TEXTAREA}`, 'the terminal to own focus', 5_000);
  await recordTextareaEvents(page);
  await page.eval(`(window.__ps3160input ??= []).push([Date.now(), 'type', ${JSON.stringify(text)}, '', ${VISIBLE_TEXTAREA}.value, document.hasFocus()]); 'ok'`);
  await page.send('Input.insertText', { text });
  await page.send('Input.dispatchKeyEvent', { type: 'rawKeyDown', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13, nativeVirtualKeyCode: 13 });
  await page.send('Input.dispatchKeyEvent', { type: 'char', key: 'Enter', code: 'Enter', text: '\r', unmodifiedText: '\r', windowsVirtualKeyCode: 13 });
  await page.send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13, nativeVirtualKeyCode: 13 });
}

const terminalHasLine = (line) => `${VISIBLE_TERMINAL}.split('\\n').some((l) => l.trim() === ${JSON.stringify(line)})`;

function screenshot(name) {
  const file = path.join(OUT, `${name}.png`);
  const png = execFileSync(ADB, ['-s', SERIAL, 'exec-out', 'screencap', '-p'], { maxBuffer: 64 << 20 });
  writeFileSync(file, png);
  return file;
}

/* --- app lifecycle -------------------------------------------------------- */

function launchApp() {
  shell(`am start -W -n ${PKG}/com.pocketshell.app.MainActivity`);
}

function pressHome() {
  shell('input keyevent KEYCODE_HOME');
}

/** What a launcher tap does: bring the existing singleTask activity forward. */
function returnToApp() {
  shell(`am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n ${PKG}/com.pocketshell.app.MainActivity`);
}

async function waitDevice(predicate, label, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  let last;
  while (Date.now() < deadline) {
    last = predicate();
    if (last) return last;
    await sleep(500);
  }
  throw new Error(`timed out (${timeoutMs} ms) waiting for ${label}`);
}

/* --- the journey ---------------------------------------------------------- */

const evidence = { run: RUN, package: PKG, serial: SERIAL, cases: {} };
let page;
let uid;
const session = { folder: `ps3160-${RUN}`, tag: `shell-${RUN}` };

async function setup() {
  shell(`pm grant ${PKG} android.permission.POST_NOTIFICATIONS`);
  shell(`am force-stop ${PKG}`);
  foreignPeers = new Set(hostSshConnections());
  evidence.foreignPeers = [...foreignPeers];
  launchApp();
  page = await Page.attach();
  uid = appUid();
  await page.waitFor("!!document.querySelector('.host-list, .empty')", 'the shared host picker');
  const keyText = readFileSync(KEY, 'utf8');
  const handle = await page.eval(`(async () => {
    const vault = window.Capacitor.Plugins.SshKeyVault;
    const listed = await vault.listKeys();
    const existing = listed.keys.find((row) => row.label === 'Docker fixture key');
    if (existing) return existing.handleId;
    const key = await vault.importKeyText({ text: ${JSON.stringify(keyText)}, label: 'Docker fixture key' });
    return key.handleId;
  })()`);
  const hostName = `fixture-${RUN}`;
  const hostRow = `[...document.querySelectorAll('.host-row')].some((n)=>n.textContent.includes(${JSON.stringify(hostName)}))`;
  if (!(await page.eval(hostRow))) {
    await page.eval(`(() => { const b = document.querySelector('button[title="New window"]')
      || [...document.querySelectorAll('button')].find((n) => n.textContent.trim() === 'Add a host');
      if (!b) throw new Error('Add a host action missing'); b.click(); return 'ok'; })()`);
    await page.waitFor("!!document.querySelector('[data-testid=android-add-host]')", 'the add-host form');
    const set = (selector, value, event = 'input') => page.eval(`(() => { const n = document.querySelector(${JSON.stringify(selector)});
      if (!n) throw new Error('missing ${selector}'); n.value = ${JSON.stringify(value)}; n.dispatchEvent(new Event(${JSON.stringify(event)}, { bubbles: true })); return 'ok'; })()`);
    await set('[data-testid=host-name]', hostName);
    await set('[data-testid=host-hostname]', HOST);
    await set('[data-testid=host-port]', String(PORT));
    await set('[data-testid=host-user]', 'testuser');
    await page.waitFor(`[...document.querySelectorAll('[data-testid=host-key] option')].some((o)=>o.value===${JSON.stringify(handle)})`, 'the key option');
    await set('[data-testid=host-key]', handle, 'change');
    await page.eval("document.querySelector('[data-testid=host-save]').click()");
    await page.waitFor(hostRow, 'the saved host row');
  }
  await installRecorder(page);
  await page.eval(`(() => { [...document.querySelectorAll('.host-row')].find((n)=>n.textContent.includes(${JSON.stringify(hostName)})).click(); return 'ok'; })()`);
  const gate = '[data-testid=host-key-trust-gate] [data-testid=host-key-decision]';
  await page.waitFor(`!!document.querySelector(${JSON.stringify(gate)}) || !!document.querySelector('button[title="Back to hosts"]')`, 'the host workspace or trust prompt');
  if (await page.eval(`!!document.querySelector(${JSON.stringify(gate)})`)) {
    await page.eval("document.querySelector('[data-testid=trust-host-key]').click()");
  }
  await page.waitFor("[...(window.__pocketshellConnectionJournal ?? [])].some((e)=>e.transportId && e.generationId)", 'a dialled transport');
  const transport = await page.eval(`JSON.parse(JSON.stringify([...(window.__pocketshellConnectionJournal ?? [])].reverse().find((e)=>e.transportId && e.generationId)))`);
  const created = await nativeExec(page, transport.transportId, transport.generationId,
    `set -eu\nmkdir -p "$HOME/${session.folder}"\nPATH="$HOME/.local/bin:$PATH" pocketshell sessions create --json --cwd "$HOME/${session.folder}" -- '${session.tag}' >/dev/null`);
  if (created.exitCode !== 0) throw new Error(`fixture session create failed: ${JSON.stringify(created)}`);
  await page.eval("document.querySelector('[title=\"Refresh\"], [aria-label=\"Refresh sessions\"]')?.click(); 'ok'").catch(() => undefined);
  const folderRow = `[...document.querySelectorAll('.dir-header')].find((n)=>n.textContent.includes(${JSON.stringify(session.folder)}))`;
  await page.waitFor(`!!${folderRow}`, 'the session folder');
  await page.eval(`(${folderRow}).click(); 'ok'`);
  await page.waitFor(`${VISIBLE_TERMINAL}.includes('$')`, 'the attached prompt');
}

/** Print a marker through the PTY and wait for the shell's own output line. */
async function echoMarker(label) {
  const token = `PS3160_${label}_${RUN}`.replace(/-/g, '_');
  await typeLine(page, `echo ${token}_$((6*7))`);
  try {
    await page.waitFor(terminalHasLine(`${token}_42`), `the ${label} marker echo`, 20_000);
  } catch (error) {
    // Where the keystrokes went: input events, native writes, the pane's state.
    const diagnosis = await page.eval(`JSON.parse(JSON.stringify({
      input: (window.__ps3160input ?? []).slice(-12), writes: (window.__ps3160?.writes ?? []).slice(-12),
      journal: (window.__pocketshellConnectionJournal ?? []).slice(-4).map((e) => [e.phase, e.transportId, e.selectedName]),
      active: document.activeElement?.className ?? null }))`).catch((e) => String(e));
    writeFileSync(path.join(OUT, `echo-failure-${label}.json`), JSON.stringify(diagnosis, null, 2));
    throw error;
  }
  return `${token}_42`;
}

async function snapshotBefore() {
  const live = await liveEntry(page);
  if (!live) throw new Error('no live attach before backgrounding');
  const rec = await recorder(page);
  return {
    live,
    dials: rec.dials.length,
    opens: rec.opens.length,
    closes: rec.closes.length,
    journalLength: await page.eval('(window.__pocketshellConnectionJournal ?? []).length'),
    hostConnections: appSshConnections(),
    pageToken: await page.eval('window.__ps3160pageToken ??= Math.random().toString(36).slice(2)'),
  };
}

async function samePage(before) {
  // The WebView page must be the one we left: a renderer death or a reload
  // would fake a "fresh connect" and is itself a regression.
  const token = await page.eval('window.__ps3160pageToken ?? null');
  if (token !== before.pageToken) throw new Error('the WebView page was reloaded while backgrounded');
}

async function journalSince(before) {
  return page.eval(`JSON.parse(JSON.stringify((window.__pocketshellConnectionJournal ?? []).slice(${before.journalLength})))`);
}

function assert(condition, message, detail) {
  if (!condition) {
    const error = new Error(message);
    error.detail = detail;
    throw error;
  }
}

/** Hold for `ms` in the background, sampling the device-side oracles. */
async function backgroundFor(ms, samples) {
  const start = Date.now();
  pressHome();
  while (Date.now() - start < ms) {
    await sleep(Math.min(5_000, ms - (Date.now() - start)));
    const notification = holdNotification();
    samples.push({
      atMs: Date.now() - start,
      notification,
      process: processState(),
      network: networkBlocked(uid),
      hostConnections: appSshConnections(),
    });
  }
}

async function withinGrace(name, { idle }) {
  const result = { name, samples: [] };
  await samePageAfterAttach();
  const marker = await echoMarker(`${idle ? 'IDLE' : 'HOME'}_BEFORE`);
  const before = await snapshotBefore();
  result.before = before;
  assert(holdNotification() === null, 'no hold notification while the app is in the foreground', appNotifications());
  if (idle) {
    shell('dumpsys battery unplug');
  }
  const backgroundStarted = Date.now();
  pressHome();
  if (idle) {
    await sleep(1_500);
    result.forceIdle = shell('dumpsys deviceidle force-idle').trim();
    result.idleState = shell('dumpsys deviceidle get deep').trim();
  }
  let shadeShot = false;
  while (Date.now() - backgroundStarted < WITHIN_MS) {
    await sleep(Math.min(5_000, WITHIN_MS - (Date.now() - backgroundStarted)));
    if (!shadeShot && !idle && Date.now() - backgroundStarted >= 15_000) {
      // What the user sees in the shade while the hold is up.
      shell('cmd statusbar expand-notifications');
      await sleep(1_200);
      screenshot(`${name}-notification-shade`);
      shell('cmd statusbar collapse');
      shadeShot = true;
    }
    result.samples.push({
      atMs: Date.now() - backgroundStarted,
      notification: holdNotification(),
      process: processState(),
      activity: activityState(),
      network: networkBlocked(uid),
      hostConnections: appSshConnections(),
      ...(idle ? { idle: shell('dumpsys deviceidle get deep').trim() } : {}),
    });
  }
  if (idle) {
    shell('dumpsys deviceidle unforce');
    shell('dumpsys battery reset');
  }
  returnToApp();
  result.backgroundMs = Date.now() - backgroundStarted;
  // Type straight away: the PTY must already be the live one.
  let after = null;
  let echoError = null;
  try {
    after = await echoMarker(`${idle ? 'IDLE' : 'HOME'}_AFTER`);
  } catch (error) {
    echoError = error;
  }
  screenshot(`${name}-after-return`);
  await samePage(before);
  const live = await liveEntry(page);
  const rec = await recorder(page);
  const journal = await journalSince(before);
  result.after = { live, dials: rec.dials.length, opens: rec.opens.length, closes: rec.closes.length,
    hostConnections: appSshConnections(), journalPhases: journal.map((e) => e.phase), notificationAfter: holdNotification() };
  result.markers = [marker, after];
  result.after.banner = await page.eval(BANNER);
  result.after.banners = rec.banners;
  result.after.events = rec.events;
  result.after.terminal = await page.eval(VISIBLE_TERMINAL);
  result.after.inputEvents = await page.eval('JSON.parse(JSON.stringify((window.__ps3160input ?? []).slice(-40)))');
  writeFileSync(path.join(OUT, `${name}.json`), JSON.stringify(result, null, 2));

  const sampled = result.samples.filter((s) => s.atMs >= 4_000);
  assert(sampled.length > 0, 'the background window was sampled', result.samples);
  assert(sampled.every((s) => s.notification && /Keeping 1 connection alive/.test(s.notification.text)),
    'while backgrounded with a live connection the hold notification reads "Keeping 1 connection alive"', sampled.map((s) => s.notification));
  assert(sampled.every((s) => s.network.effective === 'NONE'),
    "while held, Android must not block the app's network", sampled.map((s) => s.network.line));
  assert(sampled.every((s) => JSON.stringify(s.hostConnections) === JSON.stringify(before.hostConnections)),
    'the host keeps the same SSH TCP connection through the background window', sampled.map((s) => s.hostConnections));
  assert(result.after.notificationAfter === null, 'the hold notification is gone once the app is back in the foreground', result.after.notificationAfter);
  assert(live.transportId === before.live.transportId && live.generationId === before.live.generationId,
    'resume within grace keeps the same native SSH connection', { before: before.live, after: live });
  assert(live.selectedId === before.live.selectedId, 'resume within grace keeps the same host session', { before: before.live, after: live });
  assert(rec.dials.length === before.dials, 'resume within grace dials nothing', { before: before.dials, after: rec.dials.length });
  assert(rec.opens.length === before.opens && rec.closes.length === before.closes,
    'resume within grace opens and closes no PTY (the typed command used the same PTY)', result.after);
  assert(!journal.some((e) => e.phase === 'reconnecting' || e.phase === 'lost'),
    'resume within grace never enters reconnecting or lost', result.after.journalPhases);
  assert(JSON.stringify(result.after.hostConnections) === JSON.stringify(before.hostConnections),
    'after return the host still sees the very same SSH TCP connection', { before: before.hostConnections, after: result.after.hostConnections });
  if (echoError) throw echoError;
  return result;
}

async function samePageAfterAttach() {
  if (!(await page.eval(`${VISIBLE_TERMINAL}.includes('$')`))) {
    await page.waitFor(`${VISIBLE_TERMINAL}.includes('$')`, 'an attached terminal');
  }
}

/** Our app's entries in the crash buffer (FATAL blocks for its process, FGS start timeouts). */
export function appCrashes(crashLog, pkgPrefix = 'com.pocketshell.app') {
  const found = [];
  const lines = crashLog.split('\n');
  lines.forEach((line, index) => {
    if (/ForegroundServiceDidNotStartInTimeException|did not then call Service\.startForeground/.test(line)) found.push(line.trim());
    if (/FATAL EXCEPTION/.test(line) && lines.slice(index, index + 3).some((next) => next.includes(`Process: ${pkgPrefix}`))) {
      found.push(lines.slice(index, index + 3).map((next) => next.trim()).join(' | '));
    }
  });
  return found;
}

/** HOME and the return in ONE device shell, so the gap is the real gap, not adb latency. */
const RAPID_GAPS_MS = [300, 600, 700, 800, 900, 1000, 1100, 1200, 1300, 1500, 300, 600, 700, 800, 900, 1000, 1100, 1200, 1300, 1500];

async function rapidReturn(name) {
  const result = { name, iterations: [] };
  await samePageAfterAttach();
  const before = await snapshotBefore();
  const pid = shell(`pidof ${PKG}`).trim();
  shell('logcat -b crash -c');
  for (const gapMs of RAPID_GAPS_MS) {
    shell(`input keyevent KEYCODE_HOME & sleep ${gapMs / 1000}; am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n ${PKG}/com.pocketshell.app.MainActivity >/dev/null 2>&1; wait`);
    await sleep(1_500);
    result.iterations.push({ gapMs, pid: shell(`pidof ${PKG} || true`).trim(), notification: holdNotification()?.text ?? null });
  }
  // Long enough for any pending foreground-service start to time out (10 s on API 35).
  await sleep(12_000);
  result.crashes = appCrashes(shell('logcat -b crash -d'));
  result.pidAfter = shell(`pidof ${PKG} || true`).trim();
  result.notificationAfter = holdNotification();
  result.hostConnectionsAfter = appSshConnections();
  writeFileSync(path.join(OUT, `${name}.json`), JSON.stringify({ ...result, before }, null, 2));
  assert(result.crashes.length === 0, 'no crash from a fast HOME→return', result.crashes);
  assert(result.pidAfter === pid && result.iterations.every((i) => i.pid === pid),
    'the app process survives every fast HOME→return', { pid, iterations: result.iterations, pidAfter: result.pidAfter });
  assert(result.notificationAfter === null, 'no hold notification is left once the app is back', result.notificationAfter);
  await samePage(before);
  const live = await liveEntry(page);
  assert(live.transportId === before.live.transportId, 'the same SSH connection survives the fast cycles', { before: before.live, after: live });
  assert(JSON.stringify(result.hostConnectionsAfter) === JSON.stringify(before.hostConnections),
    'the host keeps the same SSH TCP connection through the fast cycles', { before: before.hostConnections, after: result.hostConnectionsAfter });
  result.markers = [await echoMarker('RAPID_AFTER')];
  writeFileSync(path.join(OUT, `${name}.json`), JSON.stringify({ ...result, before, live }, null, 2));
  return result;
}

async function selectGrace(ms) {
  // The host list has a Settings gear; inside a workspace on a phone the gear
  // is gone (S2, #3121) and Settings is a row of the overflow (⋮) menu.
  const opened = await page.eval(`(() => { const b = [...document.querySelectorAll('button[title="Settings"]')].find((n) => n.offsetParent !== null);
    if (!b) return false; b.click(); return true; })()`);
  if (!opened) {
    // Try each visible ⋮ in turn: the shell can render more than one "More"
    // (compact app bar, session tree), and only the live one opens the sheet.
    const count = await page.eval("[...document.querySelectorAll('button[aria-label=More]')].filter((n) => n.offsetParent).length");
    const row = "!!document.querySelector('[data-testid=host-overflow-settings]')";
    let found = false;
    for (let i = 0; i < count && !found; i += 1) {
      await page.eval(`(() => { const more = [...document.querySelectorAll('button[aria-label=More]')].filter((n) => n.offsetParent)[${i}];
        more.scrollIntoView({ block: 'center' }); more.click(); return 'ok'; })()`);
      const until = Date.now() + 4_000;
      while (!(found = await page.eval(row)) && Date.now() < until) await sleep(250);
    }
    if (!found) {
      const dom = await page.eval(`JSON.stringify({ mores: [...document.querySelectorAll('button[aria-label=More]')].map((n) => ({ visible: !!n.offsetParent, rect: n.getBoundingClientRect().toJSON(), parent: n.parentElement?.className })),
        testids: [...document.querySelectorAll('[data-testid^=host-overflow], [role=dialog], .popup-menu, .sheet')].map((n) => n.getAttribute('data-testid') || n.className) })`);
      throw new Error(`the overflow menu Settings row never appeared (${count} visible More buttons): ${dom}`);
    }
    await page.eval("document.querySelector('[data-testid=host-overflow-settings]').click(); 'ok'");
  }
  // The Connections group lives on one of the Settings tabs.
  const select = "document.querySelector('[data-testid=setting-background-grace]')";
  const deadline = Date.now() + 15_000;
  while (!(await page.eval(`!!${select} && ${select}.offsetParent !== null`)) && Date.now() < deadline) {
    await page.eval(`(() => { for (const tab of document.querySelectorAll('[role=tab]')) { tab.click();
      if (document.querySelector('[data-testid=setting-background-grace]')?.offsetParent) return 'found'; } return 'none'; })()`);
    await sleep(300);
  }
  await page.waitFor(`!!${select}`, 'the Settings "Keep connection after leaving" control (the shared shell must expose the grace)', 1_000);
  await page.eval(`(() => { const n = ${select}; n.value = ${JSON.stringify(String(ms))}; n.dispatchEvent(new Event('change', { bubbles: true })); return 'ok'; })()`);
  await page.waitFor(`JSON.parse(localStorage.getItem('pocketshell.settings.v1') || '{}').backgroundGraceMs === ${ms}`, 'the persisted grace');
  // The control itself must show the chosen window before it is captured.
  await page.waitFor(`${select}.value === ${JSON.stringify(String(ms))} && ${select}.selectedOptions[0]?.textContent.includes('30 seconds')`,
    'the Settings control to show 30 seconds', 5_000);
  await sleep(600);
  screenshot('settings-grace-30s');
  await page.eval(`(() => { const close = [...document.querySelectorAll('button[aria-label="Close"], button[title="Close"]')].find((n) => n.offsetParent !== null); close?.click(); return 'ok'; })()`);
  await sleep(500);
  // Android Back closes the top layer in the shared shell if no Close control did.
  for (let attempt = 0; attempt < 3 && await page.eval("!!document.querySelector('[data-testid=setting-background-grace]')?.offsetParent"); attempt += 1) {
    shell('input keyevent KEYCODE_BACK');
    await sleep(700);
  }
  await page.waitFor("!document.querySelector('[data-testid=setting-background-grace]')?.offsetParent", 'Settings to close', 10_000);
}

async function beyondGrace(name) {
  const result = { name, samples: [] };
  await selectGrace(SHORT_GRACE_MS);
  await samePageAfterAttach();
  // Scrollback the re-attached pane must still show.
  const scrollback = await echoMarker('SCROLLBACK');
  const before = await snapshotBefore();
  result.before = before;
  // Page clock: every banner the recorder sees after this moment is part of the return.
  const pageLeftAt = await page.eval('Date.now()');
  const started = Date.now();
  pressHome();
  const waitMs = SHORT_GRACE_MS + 15_000;
  while (Date.now() - started < waitMs) {
    await sleep(Math.min(5_000, waitMs - (Date.now() - started)));
    result.samples.push({ atMs: Date.now() - started, notification: holdNotification(), process: processState(),
      network: networkBlocked(uid), hostConnections: appSshConnections() });
  }
  const held = result.samples.filter((s) => s.atMs >= 4_000 && s.atMs <= SHORT_GRACE_MS - 3_000);
  const after = result.samples.filter((s) => s.atMs >= SHORT_GRACE_MS + 5_000);
  returnToApp();
  const returned = Date.now();
  await samePage(before);
  // What the user sees while the app reconnects: the last content, not a blank pane.
  result.duringReconnect = await page.eval(`({ phase: [...(window.__pocketshellConnectionJournal ?? [])].at(-1)?.phase ?? null,
    terminal: ${VISIBLE_TERMINAL} })`);
  let reattachError = null;
  try {
    await page.waitFor(`(() => { const e = [...(window.__pocketshellConnectionJournal ?? [])].at(-1);
      return e && e.phase === 'live' && e.transportId && e.transportId !== ${JSON.stringify(before.live.transportId)}; })()`,
    'an automatic re-attach on a new transport', REATTACH_DEADLINE_MS);
  } catch (error) {
    reattachError = error;
  }
  result.reattachMs = Date.now() - returned;
  screenshot(`${name}-after-return`);
  const live = await liveEntry(page);
  const journal = await journalSince(before);
  const rec = await recorder(page);
  result.after = { live, journalPhases: journal.map((e) => e.phase), banner: await page.eval(BANNER),
    banners: rec.banners, terminal: await page.eval(VISIBLE_TERMINAL), hostSession: hostSessionAlive(session.tag) };
  result.bannersSinceLeaving = rec.banners.filter((banner) => banner.at >= pageLeftAt);
  writeFileSync(path.join(OUT, `${name}.json`), JSON.stringify(result, null, 2));

  assert(held.length > 0 && held.every((s) => s.notification), 'the hold notification is up during the selected 30 s window', held);
  assert(after.length > 0 && after.every((s) => !s.notification), 'the hold ends with the selected grace window', after);
  assert(after.every((s) => !s.hostConnections.some((peer) => before.hostConnections.includes(peer))),
    'the host connection is closed after the 30 s grace (the Settings value is honoured)', after.map((s) => s.hostConnections));
  assert(result.duringReconnect.terminal.includes(scrollback),
    'while reconnecting, the pane keeps its last content instead of going blank', result.duringReconnect);
  if (reattachError) throw reattachError;
  assert(live.selectedId === before.live.selectedId, 'the same host session is re-attached', { before: before.live, after: live });
  assert(result.bannersSinceLeaving.length === 0, 'the re-attach after grace is silent: no lost-connection banner at any point',
    result.bannersSinceLeaving);
  await page.waitFor(terminalHasLine(scrollback), 'the scrollback printed before backgrounding', 10_000);
  await page.waitFor(`${BANNER} === ''`, 'no reconnect banner left on screen', 10_000);
  const typed = await echoMarker('BEYOND_AFTER');
  result.markers = [scrollback, typed];
  writeFileSync(path.join(OUT, `${name}.json`), JSON.stringify(result, null, 2));
  return result;
}

function setAirplaneMode(enabled) {
  shell(`cmd connectivity airplane-mode ${enabled ? 'enable' : 'disable'}`);
}

async function networkReturn(name) {
  const result = { name };
  await samePageAfterAttach();
  const scrollback = await echoMarker('NET_SCROLLBACK');
  const before = await snapshotBefore();
  result.before = before;
  pressHome();
  await sleep(SHORT_GRACE_MS + 8_000);
  // The network is not usable for the first seconds after the return (on a
  // phone: radio wake-up, VPN reconnect, Android lifting the app's background
  // block, during which DNS refuses at once). Every dial fails fast, so the
  // short reconnect ladder gives up; then the network comes back.
  setAirplaneMode(true);
  try {
    await waitDevice(() => /unreachable/i.test(shell(`nc -w 2 ${HOST} ${PORT} </dev/null 2>&1; true`)), 'the device network to be unreachable', 20_000);
    returnToApp();
    const returned = Date.now();
    await samePage(before);
    await page.waitFor(`${BANNER}.includes('Could not reconnect')`, 'the reconnect ladder giving up while the network is unusable', 30_000);
    result.gaveUpAfterMs = Date.now() - returned;
    result.gaveUpBanner = await page.eval(BANNER);
    screenshot(`${name}-gave-up`);
  } finally {
    setAirplaneMode(false);
  }
  const restored = Date.now();
  let reattachError = null;
  try {
    await page.waitFor(`(() => { const e = [...(window.__pocketshellConnectionJournal ?? [])].at(-1);
      return e && e.phase === 'live' && e.transportId && e.transportId !== ${JSON.stringify(before.live.transportId)}; })()`,
    'an automatic re-attach once the network is back (no Reconnect tap)', NETWORK_RETURN_REATTACH_MS);
  } catch (error) {
    reattachError = error;
  }
  result.reattachAfterNetworkMs = Date.now() - restored;
  screenshot(`${name}-after-network`);
  const live = await liveEntry(page);
  result.after = { live, banner: await page.eval(BANNER), journalPhases: (await journalSince(before)).map((e) => e.phase) };
  writeFileSync(path.join(OUT, `${name}.json`), JSON.stringify(result, null, 2));
  if (reattachError) throw reattachError;
  assert(live.selectedId === before.live.selectedId, 'the same host session is re-attached', { before: before.live, after: live });
  await page.waitFor(terminalHasLine(scrollback), 'the scrollback printed before backgrounding', 10_000);
  await page.waitFor(`${BANNER} === ''`, 'the give-up banner is cleared by the automatic re-attach', 10_000);
  result.after.terminal = await page.eval(VISIBLE_TERMINAL);
  result.markers = [scrollback, await echoMarker('NET_AFTER')];
  writeFileSync(path.join(OUT, `${name}.json`), JSON.stringify(result, null, 2));
  return result;
}

/** A visible Reconnect: the pane's own (failed join) or the lost-link banner's. */
const VISIBLE_RECONNECT = `([...document.querySelectorAll('[data-testid=pane-reconnect], .link-lost .reconnect-btn')].find((n) => n.offsetParent) ?? null)`;

async function restoredTab(name) {
  // The maintainer's stranded tab, through the resume path (a cold start
  // restores no tabs): away past grace with no network, back with the network
  // still down, then the network returns.
  const result = { name };
  let graceMs = SHORT_GRACE_MS;
  if (await page.eval("JSON.parse(localStorage.getItem('pocketshell.settings.v1') || '{}').backgroundGraceMs") !== SHORT_GRACE_MS) {
    try {
      await selectGrace(SHORT_GRACE_MS);
    } catch (error) {
      // A build without the Settings control (main before #3160) still gets
      // its real assertions: wait out the longest window it could be using.
      result.graceSettingMissing = String(error?.message ?? error).slice(0, 200);
      graceMs = 90_000;
      await page.eval(`(() => { const close = [...document.querySelectorAll('button[aria-label="Close"], button[title="Close"]')].find((n) => n.offsetParent); close?.click(); return 'ok'; })()`);
      await sleep(500);
    }
  }
  await samePageAfterAttach();
  const scrollback = await echoMarker('OFF_SCROLLBACK');
  const before = await snapshotBefore();
  result.before = before;
  pressHome();
  await sleep(3_000);
  setAirplaneMode(true);
  try {
    await sleep(graceMs + 8_000);
    await waitDevice(() => /unreachable/i.test(shell(`nc -w 2 ${HOST} ${PORT} </dev/null 2>&1; true`)), 'the device network to be unreachable', 20_000);
    returnToApp();
    await samePage(before);
    await page.waitFor(`!!${VISIBLE_RECONNECT}`, 'a visible Reconnect while the network is still down', 45_000);
    result.stranded = await page.eval(`({ paneFailed: document.querySelector('[data-testid=pane-open-failed]')?.innerText ?? null,
      banner: ${BANNER}, terminal: ${VISIBLE_TERMINAL} })`);
    screenshot(`${name}-offline-reconnect-visible`);
    // Tapping it while the network is still down fails honestly: the reason
    // and the button stay, nothing is left half-open.
    await page.eval(`${VISIBLE_RECONNECT}.click(); true`);
    await sleep(8_000);
    await page.waitFor(`!!${VISIBLE_RECONNECT} && !${VISIBLE_RECONNECT}.disabled`, 'Reconnect still offered after a failed tap', 30_000);
    result.afterOfflineTap = await page.eval(`({ paneFailed: document.querySelector('[data-testid=pane-open-failed]')?.innerText ?? null,
      banner: ${BANNER}, reconnectText: ${VISIBLE_RECONNECT}?.innerText ?? null })`);
    result.reconnectShown = true;
    screenshot(`${name}-offline-after-tap`);
  } finally {
    setAirplaneMode(false);
    writeFileSync(path.join(OUT, `${name}.json`), JSON.stringify(result, null, 2));
  }
  // The network returns: the pane must recover by itself, with no tap.
  const restored = Date.now();
  let recoverError = null;
  try {
    await page.waitFor(`(() => { const e = [...(window.__pocketshellConnectionJournal ?? [])].at(-1);
      return e && e.phase === 'live' && e.transportId && e.transportId !== ${JSON.stringify(before.live.transportId)}; })()`,
    'an automatic re-attach once the network is back (no tap)', NETWORK_RETURN_REATTACH_MS);
    await page.waitFor("!document.querySelector('[data-testid=pane-open-failed]')", 'the pane to leave its failed state by itself', 20_000);
    await page.waitFor(terminalHasLine(scrollback), 'the session repainted with its scrollback', 20_000);
    await page.waitFor(`${BANNER} === ''`, 'no lost-connection banner left', 15_000);
  } catch (error) {
    recoverError = error;
  }
  result.recoveredAfterNetworkMs = Date.now() - restored;
  screenshot(`${name}-after-network`);
  const live = await liveEntry(page);
  result.after = { live, banner: await page.eval(BANNER), hostConnections: appSshConnections(), hostSession: hostSessionAlive(session.tag),
    terminal: await page.eval(VISIBLE_TERMINAL), paneFailed: await page.eval("document.querySelector('[data-testid=pane-open-failed]')?.innerText ?? null") };
  writeFileSync(path.join(OUT, `${name}.json`), JSON.stringify(result, null, 2));
  if (recoverError) throw recoverError;
  assert(live.selectedId === before.live.selectedId, 'the same session is re-attached', { before: before.live, after: live });
  assert(!!result.after.hostSession, 'the host still has the same session', result.after.hostSession);
  // The old socket may linger on the host: the phone left the network without
  // closing it, so sshd keeps it until its own timeout. What matters is the
  // fresh connection the re-attach opened.
  assert(result.after.hostConnections.some((p) => !before.hostConnections.includes(p)),
    'the host sees the fresh SSH connection', result.after);
  result.markers = [scrollback, await echoMarker('OFF_AFTER')];
  writeFileSync(path.join(OUT, `${name}.json`), JSON.stringify(result, null, 2));
  return result;
}

function xmlEscape(value) {
  return String(value).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

function writeJunit(results) {
  const failures = results.filter((r) => r.error).length;
  const cases = results.map((r) => `  <testcase classname="js.BackgroundHoldJourney" name="${r.name}" time="${(r.ms / 1000).toFixed(1)}">${
    r.error ? `\n    <failure message="${xmlEscape(r.error)}">${xmlEscape(r.detail ?? '')}</failure>\n  ` : ''}</testcase>`);
  const xml = `<?xml version="1.0" encoding="UTF-8"?>\n<testsuite name="js.BackgroundHoldJourney" tests="${results.length}" failures="${failures}" errors="0" skipped="0">\n${cases.join('\n')}\n</testsuite>\n`;
  writeFileSync(path.join(OUT, 'TEST-js.BackgroundHoldJourney.xml'), xml);
}

async function main() {
  validateArgs();
  const results = [];
  try {
    await setup();
  } catch (error) {
    screenshot('setup-failure');
    for (const name of SELECTED) results.push({ name, ms: 0, error: `setup failed: ${error.message}` });
    writeJunit(results);
    throw error;
  }
  const runners = {
    withinGraceHomeKeepsSameConnection: (name) => withinGrace(name, { idle: false }),
    deviceIdleWithinGraceKeepsConnection: (name) => withinGrace(name, { idle: true }),
    rapidReturnNeverCrashes: rapidReturn,
    beyondGraceAutoReattachesSameSession: beyondGrace,
    networkReturnAfterGiveUpReattaches: networkReturn,
    restoredTabReconnects: restoredTab,
  };
  for (const name of SELECTED) {
    const started = Date.now();
    log(`case ${name}: start`);
    try {
      const evidenceForCase = await runners[name](name);
      results.push({ name, ms: Date.now() - started });
      evidence.cases[name] = { ok: true, file: `${name}.json`, markers: evidenceForCase.markers };
      log(`case ${name}: PASS`);
    } catch (error) {
      const detail = JSON.stringify(error.detail ?? null, null, 2);
      results.push({ name, ms: Date.now() - started, error: error.message, detail });
      evidence.cases[name] = { ok: false, error: error.message, detail: error.detail ?? null };
      log(`case ${name}: FAIL ${error.message}\n${detail}`);
      try { screenshot(`${name}-failure`); } catch { /* ignore */ }
      // Recover a known state for the next case: foreground, network on.
      try { setAirplaneMode(false); shell('dumpsys deviceidle unforce'); shell('dumpsys battery reset'); } catch { /* ignore */ }
      try { returnToApp(); } catch { /* ignore */ }
      try {
        await page.waitFor("!document.querySelector('.link-lost-text')", 'recovery between cases', 5_000);
      } catch {
        await page.eval("[...document.querySelectorAll('button')].find((b) => b.textContent.trim() === 'Reconnect')?.click(); 'ok'").catch(() => undefined);
        await page.waitFor(`${VISIBLE_TERMINAL}.includes('$') && !document.querySelector('.link-lost-text')`, 'manual recovery between cases', 30_000).catch(() => undefined);
      }
    }
  }
  writeJunit(results);
  writeFileSync(path.join(OUT, 'evidence.json'), JSON.stringify(evidence, null, 2));
  page?.close();
  const failed = results.filter((r) => r.error);
  log(`${results.length - failed.length}/${results.length} cases passed`);
  if (failed.length) process.exitCode = 1;
}

if (process.argv[1] && import.meta.url === new URL(`file://${path.resolve(process.argv[1])}`).href) {
  main().catch((error) => {
    console.error(error);
    process.exitCode = 1;
  }).finally(() => {
    try { page?.close(); } catch { /* ignore */ }
    process.exit(process.exitCode ?? 0);
  });
}
