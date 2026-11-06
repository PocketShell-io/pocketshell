import assert from 'node:assert/strict';
import { test } from 'node:test';
import { viewportReadiness } from '../../scripts/dev-browser-smoke.mjs';

const proper = () => ({
  viewport: { width: 400, height: 300 },
  safe: { top: 20, bottom: 280, left: 0, right: 400 },
  app: { top: 20, bottom: 280, left: 0, right: 400, height: 260, width: 400 },
  workspace: { top: 20, bottom: 280, left: 0, right: 400, height: 260, width: 400 },
  terminal: { top: 60, bottom: 250, left: 30, right: 370, height: 190, width: 340 },
});
const expected = { width: 400, height: 300, top: 20, bottom: 20 };

test('a stale landscape terminal passes the former vertical-only barrier but is not ready', () => {
  const geometry = proper();
  Object.assign(geometry.terminal, { left: 240, right: 740, width: 500 });
  const formerReady = geometry.app.top >= geometry.safe.top && geometry.app.bottom <= geometry.safe.bottom
    && geometry.terminal.bottom <= geometry.safe.bottom;
  assert.equal(formerReady, true);
  assert.equal(viewportReadiness({ geometry, expected }), false);
});

for (const name of ['app', 'workspace', 'terminal']) {
  for (const [edge, value] of [['top', 19], ['bottom', 281], ['left', -1], ['right', 401]]) {
    test(`${name} outside the ${edge} edge cannot become ready`, () => {
      const geometry = proper();
      geometry[name][edge] = value;
      assert.equal(viewportReadiness({ geometry, expected }), false);
    });
  }
}

test('stale dimensions or insets cannot satisfy the requested viewport', () => {
  for (const changed of [{ width: 900 }, { height: 700 }, { top: 0 }, { bottom: 0 }]) {
    assert.equal(viewportReadiness({ geometry: proper(), expected: { ...expected, ...changed } }), false);
  }
});

test('a hidden, missing or non-finite terminal is not ready', () => {
  for (const terminal of [null, { ...proper().terminal, width: 0 }, { ...proper().terminal, height: 0 },
    { ...proper().terminal, right: Number.NaN }]) {
    assert.equal(viewportReadiness({ geometry: { ...proper(), terminal }, expected }), false);
  }
});

test('a proper sample is returned intact, including when every edge equals its safe edge', () => {
  const geometry = proper();
  assert.strictEqual(viewportReadiness({ geometry, expected }), geometry);
  geometry.terminal = { ...geometry.app };
  assert.strictEqual(viewportReadiness({ geometry, expected }), geometry);
});
