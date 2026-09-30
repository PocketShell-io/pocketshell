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

/** Libraries the shared app imports from inside the core submodule. */
export const SHARED_APP_DEDUPE = [
  'vue', 'pinia', 'vue-router',
  '@xterm/xterm', '@xterm/addon-fit', '@xterm/addon-web-links', '@xterm/addon-unicode11',
  '@codemirror/commands', '@codemirror/language', '@codemirror/state', '@codemirror/view',
  '@codemirror/legacy-modes', '@codemirror/lang-cpp', '@codemirror/lang-css', '@codemirror/lang-go',
  '@codemirror/lang-html', '@codemirror/lang-java', '@codemirror/lang-javascript', '@codemirror/lang-json',
  '@codemirror/lang-markdown', '@codemirror/lang-php', '@codemirror/lang-python', '@codemirror/lang-rust',
  '@codemirror/lang-sql', '@codemirror/lang-vue', '@codemirror/lang-xml', '@codemirror/lang-yaml',
  '@lezer/highlight', '@lezer/common', '@lezer/lr', 'marked',
];

export default defineConfig(() => {
  const core = readPinnedCore(repoRoot);
  const desktop = readPinnedDesktop(repoRoot);
  const coreSource = path.dirname(core.sourceEntry);
  const coreUiSource = path.join(coreSource, '..', 'packages', 'ui', 'src');

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
      // The shared app (core packages/ui) and its @pocketshell/core subpaths,
      // spelled exactly as desktop and web alias them (#2936), so the same
      // app tree resolves identically on all three platforms.
      alias: [
        { find: /^@ui\//, replacement: `${coreUiSource}/` },
        { find: /^@pocketshell\/ui\/styles\.css$/, replacement: path.join(coreUiSource, 'styles.css') },
        { find: /^@pocketshell\/ui$/, replacement: path.join(coreUiSource, 'index.ts') },
        { find: /^@pocketshell\/core\/(shared|attachments|preview)\//, replacement: `${coreSource}/$1/` },
        { find: /^@pocketshell\/core$/, replacement: core.sourceEntry },
        { find: /^@\//, replacement: `${path.join(repoRoot, 'src')}/` },
      ],
      // The app tree lives inside the core submodule; without dedupe its
      // bare imports could resolve a second vue/pinia/xterm instance.
      dedupe: SHARED_APP_DEDUPE,
    },
  };
});
