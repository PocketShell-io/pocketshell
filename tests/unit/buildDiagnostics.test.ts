import { createHash } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import { fontCssVariables } from '../../vendor/pocketshell-desktop/packages/ui/src/fonts';
import { resolveTheme, THEME_CHOICE_DEFAULT } from '../../vendor/pocketshell-desktop/packages/ui/src/themes';
import { verifyBuildManifest } from '../../src/buildDiagnostics';
import { formatBytes } from '../../vendor/pocketshell-core/src/byteSize';

const coreRevision = '7d8899f96888c31b41242332f3b2810ea9133a44';
const uiRevision = 'd10f866f06d2ceccfd8c2a99d30353436dda10b6';
const code = new TextEncoder().encode('export const shell = true;');

function manifestFor(bytes: Uint8Array = code) {
  const sha256 = createHash('sha256').update(bytes).digest('hex');
  const file = 'assets/index-test.js';
  const bundleAssetHash = createHash('sha256').update(`${file}\0${sha256}\n`).digest('hex');
  return {
    schema: 1,
    coreSourceRevision: coreRevision,
    uiSourceRevision: uiRevision,
    bundleAssetHash,
    assets: [{ file, sha256 }],
  };
}

describe('packaged build diagnostics', () => {
  it('imports and executes the real formatter from the pinned core source', () => {
    expect(formatBytes(1536)).toBe('1.5 KB');
  });

  it('imports theme and font policy from the pinned shared UI source', () => {
    const theme = resolveTheme(THEME_CHOICE_DEFAULT);
    const fontVariables = fontCssVariables(
      { monospaceFontFamily: null, terminalFontSize: 13, editorFontSize: 13 },
      'ui-monospace, monospace',
    );

    expect(theme.tokens['--bg']).toBe('#0d1117');
    expect(fontVariables['--font-mono']).toBe('ui-monospace, monospace');
    expect(fontVariables['--term-font-size']).toBe('13px');
  });

  it('accepts a manifest only when both pinned source revisions and exact bundle bytes match', async () => {
    const result = await verifyBuildManifest(
      manifestFor(),
      coreRevision,
      uiRevision,
      async () => code,
    );

    expect(result).toEqual({
      ok: true,
      coreRevision,
      uiRevision,
      bundleAssetHash: manifestFor().bundleAssetHash,
    });
  });

  it('fails closed when the recorded core source revision differs', async () => {
    const result = await verifyBuildManifest(
      manifestFor(),
      '0000000000000000000000000000000000000000',
      uiRevision,
      async () => code,
    );

    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.reason).toContain('Core source revision mismatch');
  });

  it('fails closed when the recorded shared UI source revision differs', async () => {
    const result = await verifyBuildManifest(
      manifestFor(),
      coreRevision,
      '0000000000000000000000000000000000000000',
      async () => code,
    );

    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.reason).toContain('Shared UI source revision mismatch');
  });

  it('fails closed when the packaged asset bytes differ from the manifest', async () => {
    const result = await verifyBuildManifest(
      manifestFor(),
      coreRevision,
      uiRevision,
      async () => new TextEncoder().encode('modified asset'),
    );

    expect(result).toEqual({ ok: false, reason: 'Bundled asset hash mismatch: assets/index-test.js' });
  });

  it('fails closed when a bundle asset is missing', async () => {
    const result = await verifyBuildManifest(
      manifestFor(),
      coreRevision,
      uiRevision,
      async () => null,
    );

    expect(result).toEqual({ ok: false, reason: 'Bundled asset is missing: assets/index-test.js' });
  });
});
