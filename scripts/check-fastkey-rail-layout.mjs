#!/usr/bin/env node
/** Render the real Vue rail and replay API 35's fractional endpoint geometry. */
import assert from 'node:assert/strict';
import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright-core';
import { createServer } from 'vite';

const root = fileURLToPath(new URL('..', import.meta.url));
const sourceIndex = process.argv.indexOf('--component-source');
const componentSource = sourceIndex >= 0 ? readFileSync(process.argv[sourceIndex + 1], 'utf8') : undefined;
const artifacts = resolve(root, 'build/dev-browser-smoke/fastkey-rail-layout', componentSource ? 'baseline' : 'fixed');
mkdirSync(artifacts, { recursive: true });
const samples = [];
function chromePath() {
  const cache = resolve(process.env.HOME, '.cache/ms-playwright');
  const paths = [process.env.CHROME_BIN, '/usr/bin/google-chrome', '/usr/bin/chromium'];
  if (existsSync(cache)) for (const dir of readdirSync(cache).sort().reverse()) {
    if (/^chromium-\d+$/.test(dir)) paths.push(resolve(cache, dir, 'chrome-linux64/chrome'), resolve(cache, dir, 'chrome-linux/chrome'));
  }
  const found = paths.find(path => path && existsSync(path));
  assert.ok(found, 'a real Chromium executable is required for fast-key layout validation');
  return found;
}
const entry = '/__fastkey-rail-test.ts';
const html = `<meta name="viewport" content="width=device-width,initial-scale=1"><style>
*{box-sizing:border-box}body{margin:0;--sp-1:4px;--sp-2:8px;--font-ui:sans-serif;--font-mono:monospace}
#host{position:absolute;left:14px;width:384.19049072265625px}.mobile-hotkeys{width:100%}
</style><div id="host"><div id="app"></div></div><script type="module" src="${entry}"></script>`;
let browser;
const server = await createServer({ root, server: { host: '127.0.0.1', port: 0 }, plugins: [{
  name: 'fastkey-rail-regression',
  resolveId(id) { if (id === entry) return id; },
  enforce: 'pre',
  load(id) {
    if (componentSource && id === resolve(root, 'src/components/MobileHotkeys.vue')) return componentSource;
    if (id === entry) return `import {createApp} from 'vue';import MobileHotkeys from '/src/components/MobileHotkeys.vue';
const vm=createApp(MobileHotkeys,{enabled:true,keyboardVisible:true}).mount('#app');vm.openPalette();window.railMounted=true;`; },
  configureServer(vite) { vite.middlewares.use(async (req, res, next) => {
    if (req.url !== '/__fastkey-rail-test.html') return next();
    res.setHeader('Content-Type', 'text/html');res.end(await vite.transformIndexHtml(req.url, html));
  }); },
}] });
try {
  await server.listen();
  const address = server.httpServer.address();
  let measurements = 0;
  for (const dpr of [2.625, 1, 1.5, 2, 2.75, 3, 3.5]) {
    browser = await chromium.launch({ executablePath: chromePath(), headless: true,
      args: ['--no-sandbox', `--force-device-scale-factor=${dpr}`] });
    const page = await browser.newPage({ viewport: { width: 430, height: 800 }, deviceScaleFactor: dpr, isMobile: true, hasTouch: true });
    await page.goto(`http://127.0.0.1:${address.port}/__fastkey-rail-test.html`);
    await page.waitForFunction(() => window.railMounted && document.querySelector('[data-testid=mobile-hotkeys-main-page]'));
    for (const width of [384.19049072265625, 280, 320, 330, 384, 391.99, 392, 412.19]) {
      await page.evaluate(width => { document.querySelector('#host').style.width = `${width}px`; }, width);
      await page.waitForTimeout(50);
      for (const endpoint of ['captured-end-rounding', 'start', 'end']) {
        const result = await page.evaluate(({ endpoint, width }) => {
          const rail = document.querySelector('[data-testid=mobile-hotkeys-main-page]');
          // Replay the old WebView's endpoint rounding (167.619049 rather than
          // 168), in addition to actual CSS snap start/end positions.
          rail.style.scrollSnapType = endpoint === 'captured-end-rounding' ? 'none' : '';
          const max = rail.scrollWidth - rail.clientWidth;
          rail.scrollLeft = endpoint === 'start' ? 0 : endpoint === 'end' ? 10000
            : width === 384.19049072265625 ? 167.61904907226562 : max - (168 - 167.61904907226562);
          return new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(() => {
            const r = rail.getBoundingClientRect();
            resolve({ width: r.width, bounds: {left:r.left,right:r.right,top:r.top,bottom:r.bottom},
              scrollLeft: rail.scrollLeft, keys: [...rail.querySelectorAll('button[data-key-id]')].map(key => {
              const k=key.getBoundingClientRect();
              return { id:key.dataset.keyId,left:k.left,right:k.right,top:k.top,bottom:k.bottom,width:k.width,height:k.height,
                intersects:k.right>r.left&&k.left<r.right&&k.bottom>r.top&&k.top<r.bottom,
                contained:k.left>=r.left&&k.right<=r.right&&k.top>=r.top&&k.bottom<=r.bottom };
            }) });
          })));
        }, { endpoint, width });
        samples.push({ dpr, availableWidth: width, endpoint, ...result });
        assert.equal(result.keys.length, 10, 'all shared-core Main keys must remain present');
        for (const key of result.keys) if (key.intersects) {
          assert.equal(key.contained, true, `clipped ${key.id}: DPR ${dpr}, available ${width}, ${endpoint}: ${JSON.stringify(result)}`);
          assert.ok(Math.abs(key.width - 48) < 0.00001, `target width changed: ${key.width}`);
          assert.ok(Math.abs(key.height - 48) < 0.00001, `target height changed: ${key.height}`);
        }
        if (endpoint !== 'captured-end-rounding') assert.ok(Math.abs(result.scrollLeft - Math.round(result.scrollLeft / 56) * 56) <= .75,
          `off-slot endpoint: DPR ${dpr}, available ${width}, ${endpoint}: ${JSON.stringify(result)}`);
        measurements++;
        if (dpr === 2.625 && width === 384.19049072265625 && endpoint === 'end') {
          await page.screenshot({ path: resolve(artifacts, 'api35-endpoint.png') });
        }
      }
    }
    await page.close();
    await browser.close();
  }
  // Feed the measured endpoints through the unchanged packaged artifact
  // validator too, keeping its exact containment and slot checks load-bearing.
  const extractor = `import importlib.util,json,sys
from pathlib import Path
spec=importlib.util.spec_from_file_location('hotkeys',Path(sys.argv[1])/'scripts/extract-js-hotkeys-artifacts.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
for sample in json.load(sys.stdin):
 journey=module.sample_journey()
 for key,measured in zip(journey['catalogReachability']['mainKeys'],sample['keys'],strict=True):
  assert key['keyId']==measured['id']
  key.update(endpointBounds={edge:measured[edge] for edge in ('left','right','top','bottom')},
   endpointContentBounds=sample['bounds'],endpointIntersectsContent=measured['intersects'],
   endpointInsideContent=measured['contained'],endpointInsideViewport=measured['left']>=0 and measured['right']<=430,
   endpointScrollLeft=sample['scrollLeft'])
 module.validate_journey(journey)
print('PASS: unchanged packaged extractor accepts every measured rail endpoint')`;
  process.stdout.write(execFileSync('python3', ['-c', extractor, root], { input: JSON.stringify(samples), encoding: 'utf8' }));
  console.log(`PASS: ${measurements} real Vue rail layouts retain full 48px targets at start/end across widths and DPR, including the captured API 35 fractional endpoint`);
} finally {
  writeFileSync(resolve(artifacts, 'geometry.json'), JSON.stringify(samples, null, 2));
  await browser?.close();
  await server.close();
}
