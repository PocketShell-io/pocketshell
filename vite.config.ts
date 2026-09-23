import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import vue from '@vitejs/plugin-vue';
import { defineConfig, type Plugin } from 'vite';
import { readPinnedCore } from './scripts/js-source-integrity.mjs';

const repoRoot = path.dirname(fileURLToPath(import.meta.url));

function sha256(value: string | Uint8Array): string {
  return createHash('sha256').update(value).digest('hex');
}

function bundledAssetManifest(coreRevision: string): Plugin {
  return {
    name: 'pocketshell-bundled-asset-manifest',
    apply: 'build',
    writeBundle(options, bundle) {
      const outputDirectory = options.dir ?? path.join(repoRoot, 'dist');
      const assets = Object.values(bundle)
        .filter((output) =>
          output.fileName.startsWith('assets/') &&
          (output.type === 'chunk' || output.type === 'asset'),
        )
        .map((output) => {
          const bytes = readFileSync(path.join(outputDirectory, output.fileName));
          return { file: output.fileName, hash: sha256(bytes) };
        })
        .sort((a, b) => a.file.localeCompare(b.file));

      const aggregate = createHash('sha256');
      for (const asset of assets) aggregate.update(`${asset.file}\0${asset.hash}\n`);
      writeFileSync(
        path.join(outputDirectory, 'build-manifest.json'),
        JSON.stringify(
          {
            schema: 1,
            coreSourceRevision: coreRevision,
            bundleAssetHash: aggregate.digest('hex'),
            assets: assets.map(({ file, hash }) => ({ file, sha256: hash })),
          },
          null,
          2,
        ) + '\n',
      );
    },
  };
}

export default defineConfig(() => {
  const core = readPinnedCore(repoRoot);

  return {
    base: './',
    plugins: [vue(), bundledAssetManifest(core.revision)],
    define: {
      __POCKETSHELL_CORE_REVISION__: JSON.stringify(core.revision),
    },
    resolve: {
      alias: {
        '@pocketshell/core': core.sourceEntry,
        '@': path.join(repoRoot, 'src'),
      },
    },
  };
});
