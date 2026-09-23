import { createHash } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import { verifyBuildManifest } from '../../src/buildDiagnostics';
import { formatBytes } from '../../vendor/pocketshell-core/src/byteSize';

const coreRevision = '34011f41f858bc8e67ff5da7b12e647086d2f3c8';
const code = new TextEncoder().encode('export const shell = true;');

function manifestFor(bytes: Uint8Array = code) {
  const sha256 = createHash('sha256').update(bytes).digest('hex');
  const file = 'assets/index-test.js';
  const bundleAssetHash = createHash('sha256').update(`${file}\0${sha256}\n`).digest('hex');
  return {
    schema: 1,
    coreSourceRevision: coreRevision,
    bundleAssetHash,
    assets: [{ file, sha256 }],
  };
}

describe('packaged build diagnostics', () => {
  it('imports and executes the real formatter from the pinned core source', () => {
    expect(formatBytes(1536)).toBe('1.5 KB');
  });

  it('accepts a manifest only when its pinned core and exact bundle bytes match', async () => {
    const result = await verifyBuildManifest(
      manifestFor(),
      coreRevision,
      async () => code,
    );

    expect(result).toEqual({
      ok: true,
      coreRevision,
      bundleAssetHash: manifestFor().bundleAssetHash,
    });
  });

  it('fails closed when the recorded core source revision differs', async () => {
    const result = await verifyBuildManifest(
      manifestFor(),
      '0000000000000000000000000000000000000000',
      async () => code,
    );

    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.reason).toContain('Core source revision mismatch');
  });

  it('fails closed when the packaged asset bytes differ from the manifest', async () => {
    const result = await verifyBuildManifest(
      manifestFor(),
      coreRevision,
      async () => new TextEncoder().encode('modified asset'),
    );

    expect(result).toEqual({ ok: false, reason: 'Bundled asset hash mismatch: assets/index-test.js' });
  });

  it('fails closed when a bundle asset is missing', async () => {
    const result = await verifyBuildManifest(manifestFor(), coreRevision, async () => null);

    expect(result).toEqual({ ok: false, reason: 'Bundled asset is missing: assets/index-test.js' });
  });
});
