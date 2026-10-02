/**
 * Browser dev mode (#3022), `dev:live`: the SshCapability and key vault are
 * served by the local Node bridge (scripts/dev-ssh-bridge) over a WebSocket
 * proxied through the Vite dev server, so the page talks to real hosts.
 */
import type { DevPlugin, FakeNativeBridge } from './nativeBridge';
import { DevPluginError } from './nativeBridge';
import type { DevKeyMetadata, DevKeyVaultBackend } from './keyVaultPlugin';

/** The capability methods the Android plugin exposes (SshCapabilityPlugin.java). */
export const SSH_CAPABILITY_METHODS = [
  'connect', 'cancelOperation', 'getConnectionState', 'closeConnection', 'scheduleClose',
  'cancelScheduledClose', 'exec', 'openPty', 'readPty', 'writePty', 'resizePty', 'closePty',
  'sftpList', 'sftpRead', 'sftpWrite', 'sftpWriteIfUnchanged', 'sftpMkdir', 'sftpRename',
  'sftpDelete', 'openPortForward', 'closePortForward', 'resourceSnapshot',
] as const;

export interface SeedHost {
  name: string;
  hostname: string;
  port: number;
  user: string;
  keyHandleId: string;
}

interface Pending {
  resolve(value: unknown): void;
  reject(error: unknown): void;
}

type SocketLike = Pick<WebSocket, 'send' | 'close' | 'readyState'> & {
  onopen: ((event: unknown) => void) | null;
  onclose: ((event: unknown) => void) | null;
  onerror: ((event: unknown) => void) | null;
  onmessage: ((event: { data: unknown }) => void) | null;
};

export interface BridgeClient {
  call(method: string, params?: Record<string, unknown>): Promise<unknown>;
}

/**
 * A JSON-RPC client over one WebSocket. A dropped socket rejects its pending
 * calls and reports every connection it carried as lost, the way a killed
 * SSH transport surfaces on Android; the next call dials a fresh socket.
 */
export function createBridgeClient(options: {
  url: string;
  /** WebSocket subprotocols to offer; carries the per-run token (bridgeProtocols). */
  protocols?: string[];
  bridge: () => FakeNativeBridge;
  openSocket?: (url: string, protocols: string[]) => SocketLike;
}): BridgeClient {
  const openSocket = options.openSocket ?? ((url, protocols) => new WebSocket(url, protocols) as unknown as SocketLike);
  const protocols = options.protocols ?? [];
  let socket: SocketLike | null = null;
  let opened: Promise<SocketLike> | null = null;
  let nextId = 1;
  const pending = new Map<number, Pending>();
  const liveConnections = new Map<string, string>();

  function connectSocket(): Promise<SocketLike> {
    if (opened) return opened;
    opened = new Promise((resolve, reject) => {
      const next = openSocket(options.url, protocols);
      socket = next;
      next.onopen = () => resolve(next);
      next.onerror = () => reject(new DevPluginError('The dev SSH bridge is not reachable. Is `pnpm dev:live` running?', 'CONNECTION_FAILED'));
      next.onclose = () => {
        if (socket === next) {
          socket = null;
          opened = null;
        }
        for (const [id, call] of pending) {
          pending.delete(id);
          call.reject(new DevPluginError('The dev SSH bridge connection closed.', 'CONNECTION_LOST'));
        }
        for (const [connectionId, generationId] of liveConnections) {
          options.bridge().emit('SshCapability', 'connectionState', {
            connectionId, generationId, state: 'lost', reason: 'dev bridge disconnected',
          });
        }
        liveConnections.clear();
        reject(new DevPluginError('The dev SSH bridge closed the connection.', 'CONNECTION_FAILED'));
      };
      next.onmessage = (event) => {
        let message: { id?: number; result?: unknown; error?: { message: string; code: string; data?: Record<string, unknown> }; event?: string; data?: unknown };
        try {
          message = JSON.parse(String(event.data));
        } catch {
          return;
        }
        if (typeof message.event === 'string') {
          const data = message.data as { connectionId?: string; state?: string } | undefined;
          if (message.event === 'connectionState' && data?.connectionId) liveConnections.delete(data.connectionId);
          options.bridge().emit('SshCapability', message.event, message.data);
          return;
        }
        if (typeof message.id !== 'number') return;
        const call = pending.get(message.id);
        if (!call) return;
        pending.delete(message.id);
        if (message.error) call.reject(new DevPluginError(message.error.message, message.error.code, message.error.data ?? {}));
        else call.resolve(message.result);
      };
    });
    opened.catch(() => {
      opened = null;
    });
    return opened;
  }

  return {
    async call(method, params = {}) {
      const ready = await connectSocket();
      const id = nextId++;
      const result = await new Promise((resolve, reject) => {
        pending.set(id, { resolve, reject });
        ready.send(JSON.stringify({ id, method, params }));
      });
      if (method === 'ssh.connect') {
        const connected = result as { connectionId?: string; generationId?: string };
        if (connected.connectionId && connected.generationId) liveConnections.set(connected.connectionId, connected.generationId);
      } else if (method === 'ssh.closeConnection' && typeof params.connectionId === 'string') {
        liveConnections.delete(params.connectionId);
      }
      return result;
    },
  };
}

/** Must match scripts/dev-ssh-bridge/bridge.mjs BRIDGE_SUBPROTOCOL. */
export const BRIDGE_SUBPROTOCOL = 'pocketshell-dev-bridge.v1';
/** The URL fragment key `pnpm dev:live` prints the token under. */
export const BRIDGE_TOKEN_FRAGMENT = 'devBridgeToken';
const BRIDGE_TOKEN_STORAGE_KEY = 'pocketshell-dev-bridge-token';

/**
 * The bridge token travels as a subprotocol value (never in the URL, which
 * proxies and logs may record).
 */
export function bridgeProtocols(token: string): string[] {
  return [BRIDGE_SUBPROTOCOL, `token.${token}`];
}

interface TokenLocation {
  hash: string;
  pathname: string;
  search: string;
}

/**
 * Take the per-run bridge token from the page URL's fragment (where the
 * launcher put it; the browser never sends a fragment to the server), keep it
 * in this tab's sessionStorage so reloads work, and drop it from the address
 * bar and history.
 */
export function takeBridgeToken(
  location: TokenLocation,
  storage: Pick<Storage, 'getItem' | 'setItem'>,
  replaceUrl: (url: string) => void,
): string {
  const fragment = new URLSearchParams(location.hash.replace(/^#/u, ''));
  const fromUrl = fragment.get(BRIDGE_TOKEN_FRAGMENT);
  if (fromUrl) {
    storage.setItem(BRIDGE_TOKEN_STORAGE_KEY, fromUrl);
    fragment.delete(BRIDGE_TOKEN_FRAGMENT);
    const rest = fragment.toString();
    replaceUrl(`${location.pathname}${location.search}${rest ? `#${rest}` : ''}`);
    return fromUrl;
  }
  return storage.getItem(BRIDGE_TOKEN_STORAGE_KEY) ?? '';
}

export function createLiveSshPlugin(client: BridgeClient): DevPlugin {
  return {
    methods: Object.fromEntries(SSH_CAPABILITY_METHODS.map((method) => [
      method,
      (options: Record<string, unknown>) => client.call(`ssh.${method}`, options),
    ])),
  };
}

export function createLiveKeyVaultBackend(client: BridgeClient): DevKeyVaultBackend {
  return {
    list: async () => ((await client.call('vault.list')) as { keys: DevKeyMetadata[] }).keys,
    importKey: async (request) => (await client.call('vault.import', { ...request })) as DevKeyMetadata,
    generate: async (request) => (await client.call('vault.generate', { ...request })) as DevKeyMetadata,
    remove: async (handleId, fingerprintSha256) =>
      ((await client.call('vault.delete', { handleId, fingerprintSha256 })) as { deleted: boolean }).deleted,
  };
}
