import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { transformWithEsbuild } from 'vite';
import { assertNoFreeIdentifiers, findFreeIdentifiers } from '../../scripts/bundle-free-identifiers.mjs';
import { ANDROID_WEB_BUILD_TARGET } from '../../vite.config';

// #3161: the shipped APK threw `ReferenceError: i is not defined` from xterm's
// DECRQM handler — a minifier bug that exists only in the production bundle.
const require = createRequire(import.meta.url);
const xtermModule = readFileSync(require.resolve('@xterm/xterm/lib/xterm.mjs'), 'utf8');

/** Minify the way `vite build` does (esbuild renderChunk) for a given target. */
async function minifyLikeViteBuild(code: string, target: string): Promise<string> {
  const result = await transformWithEsbuild(code, 'xterm.mjs', {
    minify: true,
    target,
    format: 'esm',
    loader: 'js',
  });
  return result.code;
}

describe('production bundle free-identifier guard (#3161)', () => {
  it('reports a write to an undeclared name, the shape esbuild produced in xterm requestMode', () => {
    const found = findFreeIdentifiers('export class X{requestMode(e,t){(m=>m)(void 0||(i={}));return t}}');
    expect(found).toEqual([{ name: 'i', write: true, line: 1, column: 48 }]);
  });

  it('reports an unguarded read of an undeclared name and allows declared names and browser globals', () => {
    expect(findFreeIdentifiers('let a=1;export const b=a+window.innerWidth+Math.PI+missing;'))
      .toEqual([expect.objectContaining({ name: 'missing', write: false })]);
  });

  it('allows a read the chunk feature-detects with typeof, but never a write to it', () => {
    expect(findFreeIdentifiers('export const g=typeof process<"u"&&process.env.X;')).toEqual([]);
    expect(findFreeIdentifiers('export const g=typeof process<"u"&&(process={});'))
      .toEqual([expect.objectContaining({ name: 'process', write: true })]);
  });

  it('names the chunk and position when a bundle has an undeclared reference', () => {
    expect(() => assertNoFreeIdentifiers({
      'assets/ok.js': { type: 'chunk', fileName: 'assets/ok.js', code: 'export const a=1;' },
      'assets/bad.js': { type: 'chunk', fileName: 'assets/bad.js', code: 'export function f(){void 0||(i={})}' },
      'assets/style.css': { type: 'asset', fileName: 'assets/style.css' },
    })).toThrow(/assets\/bad\.js:1:\d+ assigns undeclared `i`/);
  });

  it('the old es2020 target minifies the real xterm module into an undeclared write (the shipped bug)', async () => {
    const minified = await minifyLikeViteBuild(xtermModule, 'es2020');
    expect(findFreeIdentifiers(minified).filter((found) => found.write)).not.toEqual([]);
  });

  it('the Android build target minifies the real xterm module with no undeclared identifier', async () => {
    expect(ANDROID_WEB_BUILD_TARGET).toBe('es2022');
    const minified = await minifyLikeViteBuild(xtermModule, ANDROID_WEB_BUILD_TARGET);
    expect(findFreeIdentifiers(minified)).toEqual([]);
  });
});
