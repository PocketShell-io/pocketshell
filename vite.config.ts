import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import vue from '@vitejs/plugin-vue';
import { defineConfig, type Plugin } from 'vite';
import { readPinnedCore, readPinnedDesktop } from './scripts/js-source-integrity.mjs';

const repoRoot = path.dirname(fileURLToPath(import.meta.url));

function sha256(value: string | Uint8Array): string {
  return createHash('sha256').update(value).digest('hex');
}

function assertBrowserOnlyUi(bundle: Record<string, { type: string; code?: string; imports?: string[]; dynamicImports?: string[] }>): void {
  const forbiddenExternal = /^(?:electron|@electron\/|node:)/;
  const forbiddenBridge = /\b(?:ipcRenderer|ipcMain|contextBridge)\b|\b(?:window|globalThis)\.electron\b/;

  for (const output of Object.values(bundle)) {
    if (output.type !== 'chunk') continue;
    const external = [...(output.imports ?? []), ...(output.dynamicImports ?? [])]
      .find((specifier) => forbiddenExternal.test(specifier));
    if (external) throw new Error(`Browser UI bundle contains a forbidden runtime import: ${external}`);
    if (forbiddenBridge.test(output.code ?? '')) {
      throw new Error('Browser UI bundle contains an Electron IPC bridge reference.');
    }
  }
}

function bundledAssetManifest(coreRevision: string, uiRevision: string): Plugin {
  return {
    name: 'pocketshell-bundled-asset-manifest',
    apply: 'build',
    writeBundle(options, bundle) {
      assertBrowserOnlyUi(bundle);
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
            uiSourceRevision: uiRevision,
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
  const desktop = readPinnedDesktop(repoRoot);

  return {
    base: './',
    plugins: [vue(), bundledAssetManifest(core.revision, desktop.revision)],
    esbuild: {
      // Do not inherit the desktop package's authoring tsconfig, which extends
      // @vue/tsconfig for its own workspace. This shell supplies its own
      // compiler settings and consumes the shared source without that package.
      tsconfigRaw: { compilerOptions: { target: 'ES2022', module: 'ESNext' } },
    },
    define: {
      __POCKETSHELL_CORE_REVISION__: JSON.stringify(core.revision),
      __POCKETSHELL_UI_REVISION__: JSON.stringify(desktop.revision),
    },
    resolve: {
      alias: {
        '@pocketshell/core': core.sourceEntry,
        '@pocketshell/ui/styles.css': desktop.stylesEntry,
        '@pocketshell/ui': desktop.sourceEntry,
        '@': path.join(repoRoot, 'src'),
      },
    },
  };
});
