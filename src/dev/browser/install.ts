/**
 * Browser dev mode entry (#3022). Vite injects this script ahead of
 * src/main.ts in the `mock` and `live` dev modes only (vite.config.ts
 * `devBrowserShell`); production builds never reference it.
 *
 * It must run before `@capacitor/core` is evaluated, so it imports nothing
 * that (even transitively) imports Capacitor or app code.
 */
import {
  createFakeNativeBridge,
  installFakeNativeBridge,
  DEV_BROWSER_SHIM_MARKER,
  type DevPlugin,
  type FakeNativeBridge,
} from './nativeBridge';
import {
  createAppPlugin,
  createBridgeReadyPlugin,
  createCrashReportStore,
  createDurableStoragePlugin,
  createInstalledDataMigrationPlugin,
  createKeyboardSimulation,
  durableStorageWriter,
} from './devicePlugins';
import { createSpeechPlugin, type WebSpeechConstructor } from './speechPlugin';
import { browserFilePicker, browserFileSaver, createDocumentPlugin } from './documentPlugin';
import { createKeyVaultPlugin, createMockKeyVaultBackend, MOCK_SEED_KEY } from './keyVaultPlugin';
import {
  bridgeProtocols,
  createBridgeClient,
  createLiveKeyVaultBackend,
  createLiveSshPlugin,
  takeBridgeToken,
  type SeedHost,
} from './liveBridge';
import { createMockSshPlugin, MOCK_SEED_HOST } from './mockSsh';
import { createGoogleSyncPlugin } from './googleSyncPlugin';
import { createDevToolbar } from './toolbar';
import { seedSavedHosts } from './seedHosts';
import { traceSshPlugin } from './trace';

export interface DevBrowserConfig {
  mode: 'mock' | 'live';
  /**
   * Live only: same-origin path Vite proxies to the bridge. Its token is not
   * served; it arrives in the URL fragment `pnpm dev:live` printed.
   */
  bridgePath?: string;
  seedHosts?: SeedHost[];
}

function readConfig(): DevBrowserConfig {
  const element = document.getElementById('pocketshell-dev-config');
  const parsed = JSON.parse(element?.textContent ?? '{}') as Partial<DevBrowserConfig>;
  if (parsed.mode !== 'mock' && parsed.mode !== 'live') throw new Error('Browser dev mode config is missing its mode.');
  return parsed as DevBrowserConfig;
}

function install(): void {
  const config = readConfig();
  const params = new URLSearchParams(window.location.search);
  let bridge: FakeNativeBridge | null = null;
  const bridgeRef = () => {
    if (!bridge) throw new Error('dev bridge not installed');
    return bridge;
  };

  const pick = browserFilePicker(document);
  const keyboard = createKeyboardSimulation(bridgeRef, document);
  const app = createAppPlugin(bridgeRef, document);
  const documents = createDocumentPlugin({ bridge: bridgeRef, pick, save: browserFileSaver(document) });
  const crashes = createCrashReportStore(config.mode === 'mock'
    ? ['java.lang.IllegalStateException: sample crash report from browser dev mode\n\tat com.pocketshell.app.Dev.run(Dev.java:1)']
    : []);
  const speechChoice = params.get('devSpeech');
  const webSpeech = speechChoice === 'stub' ? null
    : ((window as unknown as { SpeechRecognition?: WebSpeechConstructor; webkitSpeechRecognition?: WebSpeechConstructor })
      .SpeechRecognition ?? (window as unknown as { webkitSpeechRecognition?: WebSpeechConstructor }).webkitSpeechRecognition ?? null);

  let sshPlugin: DevPlugin;
  let vaultPlugin: DevPlugin;
  let dropConnections: () => number = () => 0;
  let seeds: SeedHost[];
  if (config.mode === 'live') {
    const url = new URL(config.bridgePath ?? '/__pocketshell-dev-bridge', window.location.href);
    url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
    const token = takeBridgeToken(window.location, window.sessionStorage, (next) => window.history.replaceState(window.history.state, '', next));
    if (!token) {
      console.error('[dev] No dev bridge token: open the URL `pnpm dev:live` printed in its terminal (it ends in #devBridgeToken=…).');
    }
    const client = createBridgeClient({ url: url.toString(), protocols: bridgeProtocols(token), bridge: bridgeRef });
    sshPlugin = createLiveSshPlugin(client);
    vaultPlugin = createKeyVaultPlugin(createLiveKeyVaultBackend(client), pick);
    seeds = config.seedHosts ?? [];
  } else {
    const mock = createMockSshPlugin({ bridge: bridgeRef });
    sshPlugin = mock;
    dropConnections = () => mock.dropConnections();
    vaultPlugin = createKeyVaultPlugin(createMockKeyVaultBackend([MOCK_SEED_KEY]), pick);
    seeds = [MOCK_SEED_HOST];
  }

  if (params.get('devTrace') === '1') sshPlugin = traceSshPlugin(sshPlugin);

  bridge = createFakeNativeBridge({
    BridgeReady: createBridgeReadyPlugin(),
    DurableStorage: createDurableStoragePlugin(window.localStorage),
    KeyboardInsets: keyboard.plugin,
    App: app,
    SpeechRecognition: createSpeechPlugin({ bridge: bridgeRef, webSpeech }),
    DocumentContent: documents,
    InstalledDataMigration: createInstalledDataMigrationPlugin(() => window.devicePixelRatio || 1),
    NativeCrashReports: crashes.plugin,
    SshKeyVault: vaultPlugin,
    SshCapability: sshPlugin,
    GoogleSync: createGoogleSyncPlugin(),
  });
  installFakeNativeBridge(window as unknown as Parameters<typeof installFakeNativeBridge>[0], bridge);
  (window as unknown as Record<string, unknown>).PocketShellDurableStorage = durableStorageWriter;
  seedSavedHosts(window.localStorage, seeds);
  document.documentElement.dataset.devBrowser = config.mode;

  const devApi = {
    marker: DEV_BROWSER_SHIM_MARKER,
    mode: config.mode,
    back: () => app.back(),
    keyboard: (visible: boolean | null) => keyboard.override(visible),
    share: (text: string) => documents.share({ text }),
    crash: (text: string) => crashes.add(text),
    dropConnections: () => dropConnections(),
    seedHosts: seeds,
  };
  (window as unknown as { __pocketshellDev?: typeof devApi }).__pocketshellDev = devApi;
  if (params.get('devToolbar') !== '0') {
    const mount = () => createDevToolbar(document, devApi);
    if (document.body) mount();
    else document.addEventListener('DOMContentLoaded', mount, { once: true });
  }
  console.info(`[dev] PocketShell browser dev mode: ${config.mode}. Keys are held in memory only.`);
}

install();
