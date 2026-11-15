#!/usr/bin/env node
/** Execute the actual Java helper's JS/order in Chromium, including focus loss during echo. */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright-core';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const sourceFlag = process.argv.indexOf('--journey-source');
const source = readFileSync(sourceFlag < 0
  ? join(root, 'android/app/src/androidTest/java/com/pocketshell/app/smoke/GatewayDockerJourneyTest.java')
  : process.argv[sourceFlag + 1], 'utf8');
function method(signature) {
  const start = source.indexOf(signature);
  assert.ok(start >= 0, `missing actual helper ${signature}`);
  return source.slice(start, source.indexOf('\n    }', start) + 6);
}
const constants = ['VISIBLE_TERMINAL', 'VISIBLE_TEXTAREA'].map(name => {
  const match = source.match(new RegExp(`    private static final String ${name} =[\\s\\S]*?;\\n`));
  assert.ok(match, `missing ${name}`); return match[0];
}).join('\n');
const scratch = mkdtempSync(join(tmpdir(), 'gateway-enter-regression-'));
let browser;
try {
  writeFileSync(join(scratch, 'GatewayProbe.java'), `
class GatewayProbe {
${constants}
${method('    void typeCommand(String command)')}
${source.includes('    private static String terminalEnterScript()') ? method('    private static String terminalEnterScript()') : ''}
static Scenario scenario = new Scenario();
static String evalString(String s) { record("EVAL",s); return s.startsWith("JSON.stringify") ? "{\\\"focused\\\":true}" : "focused"; }
static void awaitJsTrue(String s) { record("WAIT",s); }
static void assertTrue(String s, boolean b) {}
static void assertEquals(String s, String a, String b) {}
static void record(String kind, String s) { System.out.println(kind+":"+java.util.Base64.getEncoder().encodeToString(s.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
public static void main(String[] args) throws Exception { new GatewayProbe().typeCommand("echo gateway_focus_42"); }
}
class Scenario { void onActivity(java.util.function.Consumer<Activity> f) { f.accept(new Activity()); } }
class Activity { Activity getBridge() { return this; } Activity getWebView() { return this; } void requestFocus() {} }
class JSONObject { JSONObject(String s) {} boolean getBoolean(String s) { return true; } static String quote(String s) { return "\\\""+s+"\\\""; } }
class KeyEvent { static int KEYCODE_ENTER=66; }
class InstrumentationRegistry { static InstrumentationRegistry getInstrumentation() { return new InstrumentationRegistry(); } void sendKeyDownUpSync(int key) { GatewayProbe.record("ENTER", "Enter"); } }
`);
  execFileSync('javac', [join(scratch, 'GatewayProbe.java')]);
  const plan = execFileSync('java', ['-cp', scratch, 'GatewayProbe'], { encoding: 'utf8' }).trim().split('\n').map(line => {
    const colon = line.indexOf(':'); return { kind: line.slice(0, colon), script: Buffer.from(line.slice(colon + 1), 'base64').toString() };
  });
  const cache = resolve(process.env.HOME, '.cache/ms-playwright');
  const candidates = [process.env.CHROME_BIN, '/usr/bin/google-chrome', '/usr/bin/chromium'];
  if (existsSync(cache)) for (const dir of readdirSync(cache).sort().reverse()) {
    if (/^chromium-\d+$/.test(dir)) candidates.push(join(cache, dir, 'chrome-linux64/chrome'), join(cache, dir, 'chrome-linux/chrome'));
  }
  const executablePath = candidates.find(p => p && existsSync(p));
  assert.ok(executablePath, 'a real Chromium executable is required');
  browser = await chromium.launch({ executablePath, headless: true, args: ['--no-sandbox'] });
  const scenarioFlag = process.argv.indexOf('--scenario');
  const scenarios = [{ name: 'normal', loseFocus: false, unrelatedKeyUp: false },
    { name: 'focus-transfer', loseFocus: true, unrelatedKeyUp: false },
    { name: 'unrelated-keyup', loseFocus: false, unrelatedKeyUp: true },
    { name: 'both', loseFocus: true, unrelatedKeyUp: true }];
  for (const scenario of scenarios.filter(s => scenarioFlag < 0 || s.name === process.argv[scenarioFlag + 1])) {
    const { loseFocus, unrelatedKeyUp } = scenario;
    const page = await browser.newPage();
    await page.setContent('<div class="xterm"><div class="xterm-rows"></div><textarea class="xterm-helper-textarea"></textarea></div><textarea id="composer"></textarea>');
    await page.evaluate(() => {
      window.terminalEnters = 0; window.composerEnters = 0;
      const terminal = document.querySelector('.xterm-helper-textarea');
      terminal.addEventListener('paste', event => { document.querySelector('.xterm-rows').textContent = event.clipboardData.getData('text/plain'); });
      terminal.addEventListener('keydown', event => { if (event.key === 'Enter') window.terminalEnters++; });
      document.querySelector('#composer').addEventListener('keydown', event => { if (event.key === 'Enter') window.composerEnters++; });
    });
    let echoObserved = false;
    for (const action of plan) {
      if (action.kind === 'EVAL') await page.evaluate(action.script);
      else if (action.kind === 'ENTER') {
        // Deliver after receipt arming, so filtering itself is load-bearing.
        if (unrelatedKeyUp) await page.evaluate(() => document.querySelector('.xterm-helper-textarea')
          .dispatchEvent(new KeyboardEvent('keyup', { key: 'Unidentified', bubbles: true })));
        await page.keyboard.press('Enter');
      }
      else {
        assert.equal(await page.evaluate(action.script), true, `actual helper condition failed after focus transfer=${loseFocus}: ${action.script}`);
        if (!echoObserved) {
          echoObserved = true;
          await page.evaluate(({ loseFocus }) => {
            if (loseFocus) document.querySelector('#composer').focus();
          }, { loseFocus, unrelatedKeyUp });
        }
      }
    }
    const result = await page.evaluate(() => ({ terminal: window.terminalEnters, composer: window.composerEnters }));
    assert.deepEqual(result, { terminal: 1, composer: 0 }, 'one actual keyboard Enter must reach the visible terminal');
    await page.close();
  }
  console.log('PASS: actual Gateway Java helper restores terminal focus after echo and ignores unrelated keyup before one Enter');
} finally {
  if (browser) await browser.close();
  rmSync(scratch, { recursive: true, force: true });
}
