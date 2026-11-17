import { mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { build } from 'vite';
import { describe, expect, it } from 'vitest';
import { BUNDLED_MONO_STACK, DEFAULT_MONO_STACK, resolveMonoStack, TERMINAL_FONT_SIZE_COMPACT_DEFAULT } from '@ui/fonts';
import { DEFAULT_APP_SETTINGS } from '../../src/stores/appSettings';
import { ANDROID_MONO_FALLBACK } from '../../src/sharedApp/androidShell';
import { mobileFontVariables } from '../../src/sharedUiDefaults';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

describe('bundled monospace font (#3138)', () => {
  it('puts JetBrains Mono first in the Android stacks and --font-mono', () => {
    expect(ANDROID_MONO_FALLBACK).toBe(BUNDLED_MONO_STACK);
    expect(mobileFontVariables['--font-mono']).toBe(BUNDLED_MONO_STACK);
    expect(resolveMonoStack(null, ANDROID_MONO_FALLBACK).startsWith("'JetBrains Mono'")).toBe(true);
    expect(DEFAULT_MONO_STACK.startsWith("'JetBrains Mono'")).toBe(true);
    expect(BUNDLED_MONO_STACK).not.toContain('Consolas');
  });

  it('defaults the phone terminal to 13px (50 columns across full-width 412dp)', () => {
    expect(DEFAULT_APP_SETTINGS.terminalFontSize).toBe(TERMINAL_FONT_SIZE_COMPACT_DEFAULT);
    expect(DEFAULT_APP_SETTINGS.terminalFontSize).toBe(13);
    expect(Math.floor((412 - 16) / (0.6 * DEFAULT_APP_SETTINGS.terminalFontSize))).toBeGreaterThanOrEqual(50);
  });

  it('emits both complete JetBrains Mono woff2 files into the built bundle', async () => {
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
      const css = assets.filter((f) => f.endsWith('.css')).map((f) => readFileSync(path.join(out, 'assets', f), 'utf8')).join('\n');
      expect(css).toContain("font-family:JetBrains Mono");
      expect(css).toMatch(/--font-mono:\s*["']JetBrains Mono["']/);
    } finally {
      rmSync(out, { recursive: true, force: true });
      rmSync(entry, { recursive: true, force: true });
    }
  }, 60_000);
});
