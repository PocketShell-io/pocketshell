export interface BundledAsset {
  file: string;
  sha256: string;
}

export interface BuildManifest {
  schema: 1;
  coreSourceRevision: string;
  bundleAssetHash: string;
  assets: BundledAsset[];
}

export type BuildVerification =
  | { ok: true; coreRevision: string; bundleAssetHash: string }
  | { ok: false; reason: string };

export type FetchAsset = (file: string) => Promise<Uint8Array | null>;

async function sha256(bytes: BufferSource): Promise<string> {
  const digest = await globalThis.crypto.subtle.digest('SHA-256', bytes);
  return Array.from(new Uint8Array(digest), (part) => part.toString(16).padStart(2, '0')).join('');
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function isSha256(value: unknown): value is string {
  return typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);
}

/** Verify the pinned core identity and the bytes of each web asset in an APK. */
export async function verifyBuildManifest(
  value: unknown,
  expectedCoreRevision: string,
  fetchAsset: FetchAsset,
): Promise<BuildVerification> {
  if (!isRecord(value) || value.schema !== 1) {
    return { ok: false, reason: 'Build manifest is missing or has an unsupported schema.' };
  }
  if (value.coreSourceRevision !== expectedCoreRevision) {
    return {
      ok: false,
      reason: `Core source revision mismatch: bundle says ${String(value.coreSourceRevision)}, ` +
        `but this shell was built for ${expectedCoreRevision}.`,
    };
  }
  if (!Array.isArray(value.assets) || value.assets.length === 0 || !isSha256(value.bundleAssetHash)) {
    return { ok: false, reason: 'Build manifest has no valid bundled assets or aggregate hash.' };
  }

  const assets: BundledAsset[] = [];
  for (const candidate of value.assets) {
    if (!isRecord(candidate) || typeof candidate.file !== 'string' || !isSha256(candidate.sha256)) {
      return { ok: false, reason: 'Build manifest contains a malformed asset entry.' };
    }
    if (!candidate.file.startsWith('assets/') || candidate.file.split('/').includes('..')) {
      return { ok: false, reason: `Build manifest contains an invalid asset path: ${candidate.file}` };
    }
    assets.push({ file: candidate.file, sha256: candidate.sha256 });
  }

  assets.sort((left, right) => left.file.localeCompare(right.file));
  for (const asset of assets) {
    let bytes: Uint8Array | null;
    try {
      bytes = await fetchAsset(asset.file);
    } catch (error) {
      const detail = error instanceof Error ? error.message : String(error);
      return { ok: false, reason: `Could not read bundled asset ${asset.file}: ${detail}` };
    }
    if (!bytes) return { ok: false, reason: `Bundled asset is missing: ${asset.file}` };
    if ((await sha256(bytes)) !== asset.sha256) {
      return { ok: false, reason: `Bundled asset hash mismatch: ${asset.file}` };
    }
  }

  const canonical = new TextEncoder().encode(
    assets.map((asset) => `${asset.file}\0${asset.sha256}\n`).join(''),
  );
  const bundleAssetHash = await sha256(canonical);
  if (bundleAssetHash !== value.bundleAssetHash) {
    return { ok: false, reason: 'Aggregate bundled asset hash mismatch.' };
  }

  return {
    ok: true,
    coreRevision: expectedCoreRevision,
    bundleAssetHash,
  };
}

/** Load and verify this packaged shell's generated manifest and assets. */
export async function verifyCurrentBuild(expectedCoreRevision: string): Promise<BuildVerification> {
  try {
    const manifestUrl = new URL('build-manifest.json', document.baseURI);
    const manifestResponse = await fetch(manifestUrl, { cache: 'no-store' });
    if (!manifestResponse.ok) {
      return { ok: false, reason: `Build manifest fetch failed (${manifestResponse.status}).` };
    }
    const manifest: unknown = await manifestResponse.json();
    return verifyBuildManifest(manifest, expectedCoreRevision, async (file) => {
      const response = await fetch(new URL(file, document.baseURI), { cache: 'no-store' });
      if (!response.ok) return null;
      return new Uint8Array(await response.arrayBuffer());
    });
  } catch (error) {
    const detail = error instanceof Error ? error.message : String(error);
    return { ok: false, reason: `Build verification could not run: ${detail}` };
  }
}
