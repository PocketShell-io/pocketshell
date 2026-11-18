import { mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { build } from 'vite';
import { describe, expect, it } from 'vitest';
import {
  BUNDLED_MONO_STACK,
  DEFAULT_MONO_STACK,
  resolveMonoStack,
  TERMINAL_FONT_SIZE_COMPACT_DEFAULT,
  TERMINAL_SYMBOL_PROBE,
  monoStackFor,
} from '@ui/fonts';
import { woff2CodePoints } from '../../vendor/pocketshell-core/packages/ui/tests/support/woff2Cmap';
import { DEFAULT_APP_SETTINGS } from '../../src/stores/appSettings';
import { ANDROID_MONO_FALLBACK } from '../../src/sharedApp/androidShell';
import { mobileFontVariables } from '../../src/sharedUiDefaults';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

describe('bundled monospace font (#3138)', () => {
  it('puts JetBrains Mono first in the Android stacks and --font-mono', () => {
    // #3157: every Android layout carries the symbol fallback (a sideways phone is not compact).
    expect(ANDROID_MONO_FALLBACK).toBe("'JetBrains Mono', 'PocketShell Symbols', ui-monospace, monospace");
    expect(mobileFontVariables['--font-mono']).toBe(BUNDLED_MONO_STACK);
    expect(resolveMonoStack(null, ANDROID_MONO_FALLBACK).startsWith("'JetBrains Mono', 'PocketShell Symbols'")).toBe(true);
    // Compact and wide Android layouts resolve the same stack (no duplicate face), and the desktop default has none (D44).
    expect(monoStackFor(null, ANDROID_MONO_FALLBACK, true)).toBe(ANDROID_MONO_FALLBACK);
    expect(monoStackFor(null, ANDROID_MONO_FALLBACK, false)).toBe(ANDROID_MONO_FALLBACK);
    expect(DEFAULT_MONO_STACK).not.toContain('PocketShell Symbols');
    expect(DEFAULT_MONO_STACK.startsWith("'JetBrains Mono'")).toBe(true);
    expect(BUNDLED_MONO_STACK).not.toContain('Consolas');
  });

  it('defaults the shared-app phone terminal to 12px (56 columns across 411dp, 2px gutters) and keeps the legacy shell at 13px (#3157)', () => {
    // The shared app (default shell): core's compact default.
    expect(TERMINAL_FONT_SIZE_COMPACT_DEFAULT).toBe(12);
    expect(Math.floor((411 - 4) / (0.6 * TERMINAL_FONT_SIZE_COMPACT_DEFAULT))).toBe(56);
    // The legacy shell is unchanged by #3157: still 13px (>= 50 columns at 412dp, #3138).
    expect(DEFAULT_APP_SETTINGS.terminalFontSize).toBe(13);
    expect(Math.floor((412 - 16) / (0.6 * DEFAULT_APP_SETTINGS.terminalFontSize))).toBeGreaterThanOrEqual(50);
  });

  it('emits both complete JetBrains Mono woff2 files and the symbol fallback into the built bundle', async () => {
    const out = mkdtempSync(path.join(tmpdir(), 'ps-font-'));
    const entry = mkdtempSync(path.join(tmpdir(), 'ps-font-entry-'));
    try {
      writeFileSync(path.join(entry, 'main.js'), "import '@ui/styles.css';\n");
      await build({
        configFile: false,
        root: entry,
        logLevel: 'silent',
        base: './',
        resolve: { alias: { '@ui': path.join(repoRoot, 'vendor/pocketshell-core/packages/ui/src') } },
        build: { outDir: out, emptyOutDir: true, assetsInlineLimit: 0, rollupOptions: { input: path.join(entry, 'main.js') } },
      });
      const assets = readdirSync(path.join(out, 'assets'));
      const fonts = assets.filter((f) => /^JetBrainsMono-(Regular|Bold)-.*\.woff2$/.test(f));
      expect(fonts.map((f) => f.split('-')[1]).sort()).toEqual(['Bold', 'Regular']);
      for (const f of fonts) {
        const bytes = readFileSync(path.join(out, 'assets', f));
        expect(bytes.subarray(0, 4).toString('latin1')).toBe('wOF2');
        expect(bytes.length).toBeGreaterThan(80_000); // complete face: includes box-drawing
      }
      // #3157: the symbol fallback ships, and together with JetBrains Mono it
      // maps every symbol Claude Code draws to a real glyph (no .notdef/tofu).
      const symbolFiles = assets.filter((f) => /^PocketShellSymbols-Regular-.*\.woff2$/.test(f));
      expect(symbolFiles).toHaveLength(1);
      const mono = woff2CodePoints(readFileSync(path.join(out, 'assets', fonts.find((f) => f.includes('Regular'))!)));
      const symbols = woff2CodePoints(readFileSync(path.join(out, 'assets', symbolFiles[0]!)));
      const tofu = [...TERMINAL_SYMBOL_PROBE].filter((c) => !mono.has(c.codePointAt(0)!) && !symbols.has(c.codePointAt(0)!));
      expect(tofu).toEqual([]);
      expect([...'⏵✻⎿'].every((c) => !mono.has(c.codePointAt(0)!))).toBe(true);
      const css = assets.filter((f) => f.endsWith('.css')).map((f) => readFileSync(path.join(out, 'assets', f), 'utf8')).join('\n');
      expect(css).toContain("font-family:JetBrains Mono");
      expect(css).toMatch(/font-family:\s*["']?PocketShell Symbols/);
      // The default --font-mono is unchanged (D44); the symbol face joins it at runtime on compact only.
      expect(css).toMatch(/--font-mono:\s*["']JetBrains Mono["'],\s*Consolas/);
    } finally {
      rmSync(out, { recursive: true, force: true });
      rmSync(entry, { recursive: true, force: true });
    }
  }, 60_000);
});
