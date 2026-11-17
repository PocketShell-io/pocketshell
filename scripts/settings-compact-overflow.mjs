#!/usr/bin/env node
/**
 * Real-layout check for #3139 against a running PocketShell WebView, over raw
 * CDP (playwright cannot attach to an Android WebView):
 *
 *   adb forward tcp:9333 localabstract:webview_devtools_remote_<pid>
 *   node scripts/settings-compact-overflow.mjs --port 9333 [--widths 360,412]
 *
 * Settings must already be open. For every tab, at the device width and at each
 * emulated --widths dp, it asserts the settings frame and the active panel do
 * not overflow horizontally (scrollWidth <= clientWidth), no row is two-column,
 * and no desktop-only copy is present.
 */
import WebSocket from 'ws';

const args = process.argv.slice(2);
const opt = (name, fallback) => (args.includes(name) ? args[args.indexOf(name) + 1] : fallback);
const port = opt('--port', '9333');
const widths = opt('--widths', '').split(',').filter(Boolean).map(Number);
// --allow <phrase>: a banned phrase another issue still owns (#3138's font-list copy).
const allow = opt('--allow', '');
const BANNED = ['Follow Windows', 'Ctrl', 'Electron', 'installed on this machine'].filter((w) => w !== allow);

const pages = await (await fetch(`http://localhost:${port}/json`)).json();
const ws = new WebSocket(pages.find((p) => p.type === 'page').webSocketDebuggerUrl);
await new Promise((resolve) => ws.once('open', resolve));
let nextId = 0;
const pending = new Map();
ws.on('message', (raw) => {
  const msg = JSON.parse(raw);
  if (msg.id && pending.has(msg.id)) pending.get(msg.id)(msg);
});
const send = (method, params = {}) => new Promise((resolve) => {
  const id = ++nextId;
  pending.set(id, resolve);
  ws.send(JSON.stringify({ id, method, params }));
});
const evalJs = async (expression) => {
  const r = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  if (r.result?.exceptionDetails) throw new Error(JSON.stringify(r.result.exceptionDetails));
  return r.result.result.value;
};
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const MEASURE = (tab) => `(() => {
  document.querySelector('#settings-tab-${tab}').click();
  return new Promise((done) => setTimeout(() => {
    const panel = document.querySelector('#settings-panel-${tab}');
    const frame = document.querySelector('.settings');
    const strip = document.querySelector('[role=tablist]');
    const rows = [...panel.querySelectorAll('.row')].filter((r) => r.offsetParent);
    done({
      innerWidth,
      frame: [frame.scrollWidth, frame.clientWidth],
      panel: [panel.scrollWidth, panel.clientWidth],
      strip: [strip.scrollWidth, strip.clientWidth],
      twoCol: rows.filter((r) => getComputedStyle(r).flexDirection === 'row').length,
      text: panel.innerText,
    });
  }, 250));
})()`;

if (!(await evalJs("!!document.querySelector('.settings')"))) throw new Error('Settings is not open');
const failures = [];
const report = [];
for (const width of widths.length ? widths : [null]) {
  if (width) await send('Emulation.setDeviceMetricsOverride', { width, height: 800, deviceScaleFactor: 1, mobile: true });
  await sleep(400);
  const tabs = await evalJs("[...document.querySelectorAll('[role=tab]')].map((e) => e.id.replace('settings-tab-', ''))");
  for (const id of tabs) {
    const m = await evalJs(MEASURE(id));
    const label = `${width ?? 'device'} tab=${id}`;
    if (m.frame[0] > m.frame[1]) failures.push(`${label}: frame scrollWidth ${m.frame[0]} > ${m.frame[1]}`);
    if (m.panel[0] > m.panel[1]) failures.push(`${label}: panel scrollWidth ${m.panel[0]} > ${m.panel[1]}`);
    if (m.twoCol) failures.push(`${label}: ${m.twoCol} two-column rows`);
    for (const word of BANNED) if (m.text.includes(word)) failures.push(`${label}: desktop copy "${word}"`);
    report.push(`${label} innerWidth=${m.innerWidth} frame=${m.frame} panel=${m.panel} strip=${m.strip} twoColRows=${m.twoCol}`);
  }
}
if (widths.length) await send('Emulation.clearDeviceMetricsOverride');
await evalJs("document.querySelector('#settings-tab-general').click()");
console.log(report.join('\n'));
ws.close();
if (failures.length) {
  console.error(`FAIL\n${failures.join('\n')}`);
  process.exit(1);
}
console.log('PASS: no horizontal overflow, single-column rows, no desktop-only copy');
