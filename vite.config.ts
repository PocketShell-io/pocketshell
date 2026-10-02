import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import vue from '@vitejs/plugin-vue';
import type { IncomingMessage, Server as HttpServer } from 'node:http';
import type { Duplex } from 'node:stream';
import { defineConfig, type Connect, type Plugin } from 'vite';
import { readPinnedCore } from './scripts/js-source-integrity.mjs';
import { isLoopbackHost, refusal } from './scripts/dev-ssh-bridge/loopback.mjs';

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

function bundledAssetManifest(coreRevision: string): Plugin {
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
            schema: 2,
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

/** Vite modes that run the Android app in a plain browser (#3022). Dev server only. */
export const DEV_BROWSER_MODES = ['mock', 'live'] as const;
export type DevBrowserMode = (typeof DEV_BROWSER_MODES)[number];

export function isDevBrowserMode(mode: string): mode is DevBrowserMode {
  return (DEV_BROWSER_MODES as readonly string[]).includes(mode);
}

/** Same-origin path the dev server proxies to the live SSH bridge. */
export const DEV_BRIDGE_PATH = '/__pocketshell-dev-bridge';

/**
 * Live mode's loopback gate (#3022): the page and every WebSocket upgrade
 * (the bridge proxy and HMR) answer only `127.0.0.1:<port>`/`localhost:<port>`
 * Host headers, so a DNS-rebinding page cannot read the dev server or open
 * its bridge proxy. Explicit on purpose, independent of Vite's allowedHosts.
 */
export function guardLoopbackHost(httpServer: HttpServer, middlewares: { use(fn: Connect.NextHandleFunction): unknown }): void {
  const listeningPort = () => {
    const address = httpServer.address();
    return typeof address === 'object' && address ? address.port : 0;
  };
  middlewares.use((request, response, next) => {
    if (isLoopbackHost(request.headers.host, listeningPort())) return next();
    response.statusCode = 403;
    response.setHeader('Content-Type', 'text/plain');
    response.end('Browser dev mode (live) answers loopback Host headers only.\n');
  });
  // Vite's HMR socket and its bridge proxy each listen for `upgrade`; gate the
  // event itself so no listener, whenever it was added, sees a foreign Host.
  const emit = httpServer.emit;
  httpServer.emit = function gatedEmit(this: HttpServer, event: string | symbol, ...args: unknown[]): boolean {
    if (event === 'upgrade') {
      const [request, socket] = args as [IncomingMessage, Duplex];
      if (!isLoopbackHost(request.headers.host, listeningPort())) {
        socket.end(refusal(403, 'Forbidden'));
        return true;
      }
    }
    return emit.call(this, event, ...args);
  } as HttpServer['emit'];
}

/**
 * Browser dev mode (#3022): in the `mock`/`live` dev-server modes, inject
 * the fake Android bridge (src/dev/browser/install.ts) ahead of src/main.ts.
 * `apply: 'serve'` keeps it out of every build; the config below also refuses
 * to build in these modes, and scripts/check-no-dev-shims.py checks the APK.
 * The live bridge token is never served: the launcher prints it in the page
 * URL's fragment, which the browser never sends.
 */
export function devBrowserShell(mode: DevBrowserMode, env: NodeJS.ProcessEnv): Plugin {
  const config = {
    mode,
    ...(mode === 'live'
      ? {
          bridgePath: DEV_BRIDGE_PATH,
          seedHosts: JSON.parse(env.POCKETSHELL_DEV_SEED_HOSTS ?? '[]') as unknown[],
        }
      : {}),
  };
  return {
    name: 'pocketshell-dev-browser-shell',
    apply: 'serve',
    configureServer(server) {
      if (mode === 'live' && server.httpServer) guardLoopbackHost(server.httpServer as HttpServer, server.middlewares);
    },
    transformIndexHtml: {
      order: 'pre',
      handler: () => [
        {
          tag: 'script',
          attrs: { type: 'application/json', id: 'pocketshell-dev-config' },
          children: JSON.stringify(config).replace(/</gu, '\\u003c'),
          injectTo: 'head',
        },
        { tag: 'script', attrs: { type: 'module', src: '/src/dev/browser/install.ts' }, injectTo: 'head' },
      ],
    },
  };
}

/** Browser dev mode sources (#3022): served by the dev server only, never bundled. */
const DEV_BROWSER_SOURCE_ROOT = path.join(repoRoot, 'src', 'dev') + path.sep;

/** Module IDs from a build's module graph that are browser dev mode sources. */
export function devBrowserModulesIn(moduleIds: Iterable<string>): string[] {
  return [...moduleIds]
    .map((id) => id.replace(/^\0/u, '').replace(/[?#].*$/u, ''))
    .filter((id) => path.resolve(id).startsWith(DEV_BROWSER_SOURCE_ROOT))
    .map((id) => path.relative(repoRoot, id).split(path.sep).join('/'));
}

/**
 * Fails a production build whose module graph reaches any src/dev module
 * (#3023), whatever the import shape and whether or not the module has a
 * dev-only marker string. Runs before the bundle is written.
 */
export function refuseDevBrowserModules(): Plugin {
  return {
    name: 'pocketshell-refuse-dev-browser-modules',
    apply: 'build',
    buildEnd(error) {
      if (error) return;
      const leaked = devBrowserModulesIn(this.getModuleIds());
      if (leaked.length > 0) {
        throw new Error(`Production build reaches browser dev mode sources (#3022), which must never ship: ${[...new Set(leaked)].sort().join(', ')}`);
      }
    },
  };
}

export default defineConfig(({ command, mode }) => {
  const core = readPinnedCore(repoRoot);
  const devBrowser = isDevBrowserMode(mode) ? mode : null;
  if (devBrowser && command === 'build') {
    throw new Error(`Vite mode "${mode}" is the browser dev server only (#3022); it must never produce a build.`);
  }
  const bridgePort = process.env.POCKETSHELL_DEV_BRIDGE_PORT;
  if (devBrowser === 'live' && !bridgePort) {
    throw new Error('Vite mode "live" needs the dev SSH bridge; start it with `pnpm dev:live`.');
  }

  return {
    base: './',
    plugins: [
      vue(),
      bundledAssetManifest(core.revision),
      refuseDevBrowserModules(),
      ...(devBrowser ? [devBrowserShell(devBrowser, process.env)] : []),
    ],
    ...(devBrowser === 'live'
      ? {
          server: {
            // The bridge carries live SSH sessions: keep the page and its
            // proxy on loopback too (use an SSH tunnel to reach it remotely).
            host: '127.0.0.1',
            strictPort: true,
            proxy: {
              [DEV_BRIDGE_PATH]: { target: `ws://127.0.0.1:${bridgePort}`, ws: true, changeOrigin: true },
            },
          },
        }
      : {}),
    esbuild: {
      // Do not inherit the shared UI package's authoring tsconfig, which extends
      // @vue/tsconfig for its own workspace. This shell supplies its own
      // compiler settings and consumes the shared source without that package.
      tsconfigRaw: { compilerOptions: { target: 'ES2022', module: 'ESNext' } },
    },
    define: {
      __POCKETSHELL_CORE_REVISION__: JSON.stringify(core.revision),
    },
    resolve: {
      alias: {
        // Same alias set as pocketshell-desktop and pocketshell-web: the shared
        // UI package rides inside the core pin. Subpath aliases come first so
        // they win over the bare '@pocketshell/core' entry alias.
        '@ui': core.uiRoot,
        '@pocketshell/core/shared': path.join(core.sourceRoot, 'shared'),
        '@pocketshell/core/attachments': path.join(core.sourceRoot, 'attachments'),
        '@pocketshell/core/preview': path.join(core.sourceRoot, 'preview'),
        '@pocketshell/core': core.sourceEntry,
        '@': path.join(repoRoot, 'src'),
      },
      // The shared app tree lives inside the core submodule; without dedupe
      // its bare imports could resolve a second vue/pinia/xterm instance (#2936).
      dedupe: SHARED_APP_DEDUPE,
    },
  };
});
