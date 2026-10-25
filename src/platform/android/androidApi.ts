import { createAndroidAttachments } from './attachments';
/**
 * The Android half of the shared app's transport seam: one `PocketShellApi`
 * over the Capacitor native plugins, provided to the shared app tree
 * (`@ui/app`) through `provideApi()` — the same seam desktop fills with its
 * Electron preload bridge and web with its browser transport.
 *
 * Groups and what backs them:
 *
 *   ssh / shell / helper — {@link AndroidConnectionHub}: one core
 *              ConnectionController per logical connection over the native
 *              `SshCapability` plugin (sshj). The controller owns dial, trust,
 *              listing, attach, reconnect and grace (D28); `ssh.reconnect`
 *              advertises that, so the shared store never re-dials (#2954);
 *   hosts    — {@link AndroidHostStore}: hosts added on the phone plus the
 *              0.5.x import;
 *   sync     — the Android settings-sync adapter (src/sync/androidSync.ts,
 *              #3020) when supplied: native Google sign-in and transport,
 *              WebView encryption, core's sync round. The picker's account
 *              button (`win.openAccount`) opens the Android account route,
 *              and an account host this phone has no key for asks for one
 *              before it is dialled (#3063, `accountHosts`);
 *   app      — Android lifecycle drives the controllers' background grace
 *              directly (see `bindLifecycle`), so the shared store's resume
 *              probe is deliberately NOT fed;
 *   everything else — not wired yet. Reads that the workspace polls on every
 *              mount answer empty (the web client's pattern); anything that
 *              would claim to do work fails with its own name, so nothing can
 *              pretend to succeed. Each is a row of the #2936 staged plan.
 */
import type { PocketShellApi } from '@ui/app/api';
import {
  hasGatewayMarker,
  readHostUsage,
  runHostBootstrap,
  unsupportedTransport,
  type ConnectionController,
  type ConnectResult,
  type HomeResult,
  type HostEntry,
  type SshHostTarget,
} from '@pocketshell/core';
import { AndroidConnectionHub, type ConnectionJournalEntry, type TofuDecision } from './connectionHub';
import { MissingHostCredential, type AndroidHostStore } from './hostStore';
import { declinedAccountHostMessage, type AccountHostKeys } from './accountHosts';
import { insecureGatewayMessage, resolveGatewayTarget, type GatewayConnectRequest, type GatewayPairings } from './gatewayTarget';
import { AndroidForwards } from './forwards';
import { sshCapability } from '@/native/sshCapability';
import type { AndroidVault, GatewayAuthPolicy } from '@/vault/androidVault';

/** The generation the hub's controller-backed exec answers for (the controller owns the real one). */
const CONTROLLER_GENERATION = 'controller';

export class UnsupportedCapability extends Error {
  constructor(method: string) {
    super(`Android does not implement ${method} yet`);
    this.name = 'UnsupportedCapability';
  }
}

const unsupported = (method: string) => (): Promise<never> =>
  Promise.reject(new UnsupportedCapability(method));

/**
 * What this phone's native transport reported it can dial beyond direct SSH
 * (#3086). `gateway` is true only when the native `SshCapability` plugin's
 * own `transportCapabilities` reply said so (src/native/sshCapability.ts);
 * Android has no link transport, so `link` is not a field at all.
 */
export interface AndroidTransports {
  gateway: boolean;
}

/**
 * Why this phone must not dial a host or connect request at all, or null.
 *
 * Core's gateway contract (#3059, core docs/SYNC.md): the shared
 * `unsupportedTransport` decision, made on PRESENCE of a `gateway` or `link`
 * marker. With `transports.gateway` false every gateway marker (valid, null,
 * malformed, or alongside `link`) refuses; with it true, a null, malformed or
 * future-shaped marker and a `gateway` + `link` pair still refuse, and only a
 * valid target is admitted. Android has no link transport, so a `link`
 * marker always refuses: dialling plain SSH would reach the host over a
 * transport it was not configured for. On top of core's decision Android
 * refuses an admitted `ws://` gateway origin: its native transport dials
 * `wss://` only. The check runs before any key prompt, save, credential or
 * socket.
 *
 * This is the ONE place Android decides transport support: `resolveTarget`
 * asks it about both the connect request and the account entry. An admitted
 * gateway marker is dialled through the native gateway transport, never by
 * falling back to plain SSH (D28).
 */
export function unsupportedTransportMessage(name: string, entry: object, transports: AndroidTransports): string | null {
  const decision = unsupportedTransport(entry, { gateway: transports.gateway, link: false }, name);
  if (decision.refused) return decision.message;
  return hasGatewayMarker(entry) ? insecureGatewayMessage(name, (entry as { gateway?: unknown }).gateway) : null;
}

/** Whether a connect request names this exact phone host (alias, or address and user). */
function isSameHost(host: { name: string; hostname: string; port: number; user: string }, request: { host: string; port?: number; user: string; hostAlias?: string }): boolean {
  if (request.hostAlias) return request.hostAlias === host.name;
  return host.hostname === request.host && host.port === (request.port ?? 22) && (!request.user || host.user === request.user);
}

/** The dial refusals of the account key vault (key-vault W4): never a key picker. */
export function vaultLockedMessage(name: string): string {
  return `Unlock your key vault to connect to “${name}”. Nothing was dialled.`;
}

export function vaultUnboundMessage(name: string): string {
  return `No key in your key vault is assigned to “${name}”. Assign one on the PocketShell web app (Keys), then connect again.`;
}

export function vaultGatewayPasswordMessage(name: string): string {
  return `“${name}” reaches its host through the PocketShell gateway, which needs an SSH key, but its vault entry is a password. Nothing was dialled.`;
}

/** The non-secret `gatewayAuth` mirror a host entry may carry (design section 7). */
function mirroredPolicy(entry: object): GatewayAuthPolicy | null {
  const value = (entry as { gatewayAuth?: unknown }).gatewayAuth;
  return value === 'host' || value === 'managed' || value === 'both' ? value : null;
}

export interface AndroidLifecycle {
  /** Register for foreground/background transitions; returns an unsubscribe. */
  onActiveChange(handler: (active: boolean) => void): () => void;
}

export interface AndroidApiDeps {
  createController: () => ConnectionController;
  hosts: AndroidHostStore;
  lifecycle?: AndroidLifecycle;
  /** The user's background grace, read at the moment the app backgrounds. */
  backgroundGraceMs: () => number;
  /** Route the shared picker's empty state sends the user to. */
  addHostRoute: string;
  log?: (entry: { kind: string; message: string; detail?: Record<string, unknown> }) => void;
  /** Controller snapshots as the hub sees them (diagnostics only). */
  observeConnections?: (entry: ConnectionJournalEntry) => void;
  /** Google sign-in and settings sync (#3020); signed-out stub when absent. */
  sync?: PocketShellApi['sync'];
  /** Open the Account & sync screen: the picker's account button (#3063). */
  openAccount?: () => Promise<void>;
  /** Open the Add host screen: the picker's + button. */
  openAddHost?: () => Promise<void>;
  /** Account hosts this phone has no key for yet: find and adopt them (#3063). */
  accountHosts?: AccountHostKeys;
  /**
   * Whether the native SshCapability plugin reported the gateway transport
   * (#3086), awaited at dial time so a dial never races the probe. Absent or
   * false: every gateway host is refused before any effect.
   */
  gatewayTransport?: () => Promise<boolean>;
  /** The native gateway pairings (#3086); required for a gateway dial. */
  gatewayPairings?: GatewayPairings;
  /**
   * The account key vault (key-vault W4). An account host bound to a vault
   * key is dialled with it from memory; a locked vault asks to unlock, never
   * for a key. Absent, or an account without a vault: the phone's key flow.
   */
  vault?: Pick<AndroidVault, 'credentialFor'>;
}

export interface AndroidPlatform {
  api: PocketShellApi;
  hub: AndroidConnectionHub;
  dispose(): void;
}

export const FORWARD_RESUME_STORAGE_KEY = 'pocketshell.android.forward-resume.v1';
interface ForwardResumeIntent { name: string; hostname: string; port: number; user: string; hostId: string }

export function createAndroidPlatform(deps: AndroidApiDeps): AndroidPlatform {
  let disposed = false;
  let forwards: AndroidForwards | undefined;
  const pendingDials = new Map<string, Promise<ConnectResult>>();
  const hostConnections = new Map<string, string>();
  const connectionHosts = new Map<string, ForwardResumeIntent>();
  function rememberedHosts(): ForwardResumeIntent[] {
    try {
      const rows: unknown = JSON.parse(localStorage.getItem(FORWARD_RESUME_STORAGE_KEY) ?? '[]');
      return Array.isArray(rows) ? rows.filter((row): row is ForwardResumeIntent => !!row && typeof row.name === 'string' && typeof row.hostname === 'string' && typeof row.hostId === 'string' && typeof row.user === 'string' && Number.isInteger(row.port) && row.port > 0 && row.port < 65536) : [];
    } catch { return []; }
  }
  function rememberForwardHost(id: string, enabled: boolean): void {
    const host = connectionHosts.get(id);
    if (!host) throw new Error('This host is no longer connected.');
    const rows = rememberedHosts().filter((row) => row.hostId !== host.hostId);
    if (enabled) rows.push(host);
    localStorage.setItem(FORWARD_RESUME_STORAGE_KEY, JSON.stringify(rows));
  }
  async function resumeEnabledHosts(): Promise<void> {
    const saved = await deps.hosts.list();
    for (const intent of rememberedHosts()) {
      if (disposed) return;
      if (!forwards!.isRememberedAutoEnabled(intent.hostId)) continue;
      // Never let a renamed/replaced saved host silently change the endpoint
      // a user enabled. Credential resolution and trust remain the normal API.
      const host = saved.find((host) => host.name === intent.name && host.hostname === intent.hostname && host.port === intent.port && host.user === intent.user);
      if (!host) continue;
      const outcome = await api.ssh.connect({ host: host.hostname, port: host.port, user: host.user, hostAlias: host.name, ...(host.gateway ? { gateway: host.gateway } : {}) });
      if (!outcome.ok) deps.log?.({ kind: 'forward-resume', message: outcome.error ?? 'Could not resume the enabled forwarding host.' });
    }
  }
  const hub = new AndroidConnectionHub({
    createController: deps.createController,
    observe: (entry) => {
      deps.observeConnections?.(entry);
      const snapshot = hub.controllerOf(entry.connectionId)?.getSnapshot();
      if (snapshot) forwards?.observe(entry.connectionId, snapshot);
    },
  });
  forwards = new AndroidForwards(hub, sshCapability);
  const unbindLifecycle = deps.lifecycle
    ? deps.lifecycle.onActiveChange((active) => {
        if (active) void hub.returnToForeground().then(() => forwards!.resume()).then(resumeEnabledHosts).catch((error) => deps.log?.({ kind: 'forward-resume', message: String(error) }));
        else void hub.enterBackground(deps.backgroundGraceMs(), (id) => forwards!.hasActive(id));
      })
    : () => undefined;

  const exec = (connectionId: string, command: string) => hub.exec(connectionId, command);

  /**
   * A gateway dial: the pairing names the device, the pin and the host's own
   * key (`host` policy). With `managed` / `both` and an unlocked vault that
   * binds the host, the vault key is dialled instead, through the same
   * pairing and pin. `both` (or no mirrored policy) falls back to the
   * pairing key when the vault stays locked or names no key; only `managed`
   * refuses then. The pairing is checked first, so an unpaired device is
   * refused before anyone is asked to unlock.
   */
  async function gatewayTarget(request: GatewayConnectRequest & { gatewayAuth?: unknown; hostAlias?: string }, label: string) {
    const paired = await resolveGatewayTarget(request, label, deps.gatewayPairings!);
    if (!deps.vault) return paired;
    // The host's policy is mirrored on its synced entry (design section 7):
    // core's store sends only the transport markers with a dial, so read it
    // from the account entry this dial names.
    const entry = deps.accountHosts ? await deps.accountHosts.find(request) : null;
    const policy = mirroredPolicy(request) ?? (entry ? mirroredPolicy(entry) : null);
    const decision = await deps.vault.credentialFor(label, { gateway: true, mirroredPolicy: policy });
    switch (decision.kind) {
      case 'no-vault':
      case 'host-keys':
        return paired;
      // Only a `managed` host (vault keys only) gets these: the pairing key
      // would be refused by the host, so say what to do instead.
      case 'unbound':
        throw new Error(vaultUnboundMessage(label));
      case 'locked':
        throw new Error(vaultLockedMessage(label));
      case 'credential':
        if (decision.credential.kind !== 'key-handle') throw new Error(vaultGatewayPasswordMessage(label));
        return { ...paired, credential: decision.credential };
    }
  }

  /**
   * A direct dial of an account host (or a phone host whose key is gone)
   * with its vault entry, or null when the account has no vault. The target
   * is built in memory only: nothing is saved on the phone.
   */
  async function vaultTarget(host: HostEntry, payload: Parameters<PocketShellApi['ssh']['connect']>[0]): Promise<SshHostTarget | null> {
    if (!deps.vault) return null;
    const decision = await deps.vault.credentialFor(host.name, { gateway: false });
    switch (decision.kind) {
      case 'no-vault':
      case 'host-keys':
        return null;
      case 'locked':
        throw new Error(vaultLockedMessage(host.name));
      case 'unbound':
        throw new Error(vaultUnboundMessage(host.name));
      case 'credential': {
        const username = payload.user || host.user;
        return {
          hostId: `${username}@${host.hostname}:${host.port}`,
          hostname: host.hostname,
          port: host.port,
          username,
          credential: decision.credential,
        };
      }
    }
  }

  /**
   * The dial target: a phone host; a phone host whose key is gone, once the
   * user attaches a key to it (updated in place); or an account host the
   * phone does not have, once the user gives it this phone's key (saved on
   * the phone). Either way it then resolves like any other phone host.
   */
  async function resolveTarget(payload: Parameters<PocketShellApi['ssh']['connect']>[0]) {
    // The native report, read at dial time; any failure reads as "no gateway".
    const transports: AndroidTransports = {
      gateway: deps.gatewayPairings !== undefined && (await deps.gatewayTransport?.().catch(() => false)) === true,
    };
    // A gateway or link marker the phone cannot dial is refused before
    // anything else (core #3059): no key prompt, save, credential or socket.
    if (unsupportedTransportMessage('', payload, transports) !== null) {
      const named = deps.accountHosts ? await deps.accountHosts.find(payload) : null;
      throw new Error(unsupportedTransportMessage(named?.name ?? deps.hosts.labelFor(payload), payload, transports)!);
    }
    // An admitted gateway marker is dialled through the gateway, with the key
    // of this phone's pairing for that device; never as an ordinary host.
    if (hasGatewayMarker(payload)) {
      return gatewayTarget(payload, deps.hosts.labelFor(payload));
    }
    try {
      return await deps.hosts.resolve(payload);
    } catch (error) {
      const keys = deps.accountHosts;
      if (!keys) throw error;
      // Attach a key in place only to the phone host this dial IS: named by
      // the request, or at the same address for the same user. A keyless
      // phone host that merely shares the address (another user, #3063 review
      // r2) is left alone, and the account host is saved as its own host.
      const missingKey = error instanceof MissingHostCredential && isSameHost(error.host, payload);
      const account = missingKey ? null : await keys.find(payload);
      // An account host marked for the gateway or a link is never adopted as
      // a plain SSH phone host (core #3059): it is refused, or, when this
      // phone can dial its gateway, dialled through the gateway.
      const refusal = account ? unsupportedTransportMessage(account.name, account, transports) : null;
      if (refusal) throw new Error(refusal);
      if (account && hasGatewayMarker(account)) {
        return gatewayTarget(
          {
            host: account.hostname,
            port: account.port,
            user: payload.user || account.user,
            gateway: account.gateway,
            hostAlias: account.name,
            gatewayAuth: account.gatewayAuth,
          },
          account.name,
        );
      }
      const host = missingKey ? (error as MissingHostCredential).host : account;
      if (!host) throw error;
      // The account's vault decides first (key-vault W4): its key, an unlock
      // request, or a "bind a key on the web" refusal. Only an account
      // without a vault keeps the phone's own key prompt.
      const fromVault = await vaultTarget(host, payload);
      if (fromVault) return fromVault;
      if (!(await keys.adopt(host, missingKey ? 'missing-key' : 'account'))) {
        throw new Error(declinedAccountHostMessage(host.name));
      }
      return deps.hosts.resolve({ ...payload, hostAlias: host.name });
    }
  }

  const api: PocketShellApi = {
    ssh: {
      async listConfigHosts() {
        return deps.hosts.list();
      },
      async connect(payload) {
        let target;
        try {
          target = await resolveTarget(payload);
        } catch (error) {
          return { ok: false, error: error instanceof Error ? error.message : String(error) };
        }
        // Android advertises `onTrustDecision`, so trusting a key is the
        // user's answer on the prompt, never a caller's standing decision: a
        // supplied accept-once/accept-always is ignored; only reject is kept.
        const standing: TofuDecision | undefined = payload.tofuDecision === 'reject' ? 'reject' : undefined;
        const hostLabel = deps.hosts.labelFor({
          ...payload,
          user: payload.user || target.username,
        });
        const existing = hostConnections.get(target.hostId);
        if (existing && hub.controllerOf(existing)) {
          const snapshot = hub.controllerOf(existing)!.getSnapshot();
          if (snapshot.phase !== 'lost' && snapshot.phase !== 'idle' && !(snapshot.phase === 'error' && !snapshot.connectionId)) return { ok: true, connectionId: existing };
          const recovering = pendingDials.get(target.hostId);
          if (recovering) return recovering;
          const recovery = hub.reconnect(existing).then((ok) => ok ? { ok: true, connectionId: existing } : { ok: false, error: 'Could not resume the forwarding host.' });
          pendingDials.set(target.hostId, recovery);
          try { return await recovery; } finally { pendingDials.delete(target.hostId); }
        }
        const pending = pendingDials.get(target.hostId);
        if (pending) return pending;
        if (disposed) return { ok: false, error: 'The Android platform was closed.' };
        const dial = hub.connect(target, standing, hostLabel).then(async (result) => {
          if (result.ok && result.connectionId) {
            if (disposed) { await hub.close(result.connectionId); return { ok: false, error: 'The Android platform was closed.' }; }
            hostConnections.set(target.hostId, result.connectionId);
            connectionHosts.set(result.connectionId, { name: hostLabel, hostname: target.hostname, port: target.port, user: target.username, hostId: target.hostId });
          }
          return result;
        });
        pendingDials.set(target.hostId, dial);
        try { return await dial; } finally { pendingDials.delete(target.hostId); }
      },
      // Android asks before trusting a first-contact key: the shared store
      // registers its prompt here and stops dialling with a standing
      // accept-always (the #2953 regression against the legacy screen).
      onTrustDecision: (decider) => hub.setTrustDecider(decider),
      exec,
      close: async (connectionId) => {
        await forwards!.close(connectionId);
        for (const [host, id] of hostConnections) if (id === connectionId) hostConnections.delete(host);
        connectionHosts.delete(connectionId);
        return hub.close(connectionId);
      },
      onState: (listener) => hub.onState(listener),
      // Its presence tells the shared store the controller owns recovery
      // (#2954): no store ladder, and Retry recovers the same logical id.
      reconnect: (connectionId) => hub.reconnect(connectionId),
    },

    hosts: {
      groupLabel: 'On this phone',
      sourceName: 'saved hosts',
      emptyHint: 'No hosts saved on this phone yet.',
      emptyAction: { label: 'Add a host', route: deps.addHostRoute },
    },

    shell: {
      open: () => hub.open(),
      attachSession: (payload) => hub.attachSession(payload),
      input: (shellId, data, sessionName, workspace) => hub.input(shellId, data, sessionName, workspace),
      resize: (shellId, cols, rows) => hub.resize(shellId, cols, rows),
      redraw: (shellId) => hub.redraw(shellId),
      // One PTY per controller, sized by the pane; no tmux geometry to probe.
      windowSize: async () => ({ kind: 'bare' as const }),
      close: (shellId) => hub.closeShell(shellId),
      onData: (handler) => hub.onData(handler),
      onExited: (handler) => hub.onExited(handler),
    },

    helper: {
      bootstrap: (connectionId) => runHostBootstrap((command) => exec(connectionId, command)),
      sessionsList: (connectionId) => hub.sessionsList(connectionId),
      sessionsProbe: (connectionId) => hub.sessionsProbe(connectionId),
      sessionsCreate: unsupported('helper.sessionsCreate'),
      // Core's usage source (#2937). Its exec goes through the controller,
      // which owns the transport generation; the controller has already
      // checked the request/generation echo the source re-checks here.
      usage: (connectionId) =>
        readHostUsage(
          {
            exec: async (options) => {
              const outcome = await hub.hostCommand(connectionId, options.command, options.timeoutMs);
              return { ...options, exitCode: outcome.exitCode, stdout: outcome.stdout, stderr: outcome.stderr, timedOut: outcome.timedOut ?? false };
            },
          },
          { connectionId, generationId: CONTROLLER_GENERATION },
        ),
      // Crash/OOM warnings ride HostCliCore's warnings verbs (stage S3).
      warnings: async () => [],
      ackWarnings: unsupported('helper.ackWarnings'),
    },

    projects: {
      async home(connectionId): Promise<HomeResult> {
        try {
          const res = await exec(connectionId, 'printf %s "$HOME"');
          const home = res.stdout.trim();
          return res.exitCode === 0 && home.startsWith('/')
            ? { ok: true, home, error: null }
            : { ok: false, home: null, error: res.stderr.trim() || 'The host did not report a home directory.' };
        } catch (error) {
          return { ok: false, home: null, error: error instanceof Error ? error.message : String(error) };
        }
      },
      deriveName: unsupported('projects.deriveName'),
      createFolder: unsupported('projects.createFolder'),
      reposList: unsupported('projects.reposList'),
      reposClone: unsupported('projects.reposClone'),
      startSession: unsupported('projects.startSession'),
      renameSession: unsupported('projects.renameSession'),
      async killSession(connectionId, name, ref) {
        const controller = hub.controllerOf(connectionId);
        if (!controller) return { ok: false, code: 'kill-failed', error: 'The connection is no longer available.' };
        // The shared core owns the host CLI and mutation reconciliation. Its
        // immutable selector remains correct if another client renamed a tab.
        const result = await controller.killSession(ref?.aplexerId || name);
        return result.ok
          ? { ok: true, code: null, error: null }
          : { ok: false, code: 'kill-failed', error: result.message };
      },
      onCloneProgress: () => () => undefined,
    },

    sftp: {
      list: unsupported('sftp.list'),
      stat: unsupported('sftp.stat'),
      readFile: unsupported('sftp.readFile'),
      readBinary: unsupported('sftp.readBinary'),
      writeFile: unsupported('sftp.writeFile'),
      createFile: unsupported('sftp.createFile'),
      mkdir: unsupported('sftp.mkdir'),
      rename: unsupported('sftp.rename'),
      deleteFile: unsupported('sftp.deleteFile'),
      rmdir: unsupported('sftp.rmdir'),
      realPath: unsupported('sftp.realPath'),
      upload: unsupported('sftp.upload'),
      download: unsupported('sftp.download'),
      saveAs: unsupported('sftp.saveAs'),
      onProgress: () => () => undefined,
    },

    preview: {
      openHtml: unsupported('preview.openHtml'),
      openMarkdown: unsupported('preview.openMarkdown'),
      openSvg: unsupported('preview.openSvg'),
      release: () => undefined,
      onStats: () => () => undefined,
    },

    forwards: {
      ...forwards.api,
      startAuto: async (id, config) => { const result = await forwards!.api.startAuto(id, config); rememberForwardHost(id, true); return result; },
      stopAuto: async (id) => { const result = await forwards!.api.stopAuto(id); rememberForwardHost(id, false); return result; },
    },

    attachments: createAndroidAttachments(hub),

    agent: {
      kinds: async () => null,
      binaries: async () => null,
      profiles: async () => [],
      envList: async () => [],
      envGet: async () => ({}),
      envSet: unsupported('agent.envSet'),
    },

    diag: {
      log(entry) {
        deps.log?.(entry);
      },
    },

    sync: deps.sync ?? {
      status: async () => ({ loggedIn: false, email: null, keychainAvailable: false }),
      login: unsupported('sync.login'),
      logout: async () => undefined,
      pull: unsupported('sync.pull'),
      push: unsupported('sync.push'),
      accountHosts: async () => null,
      applyHosts: unsupported('sync.applyHosts'),
    },

    win: {
      setTitle(title) {
        document.title = title;
      },
      openAccount: deps.openAccount ?? unsupported('win.openAccount'),
      openNewWindow: deps.openAddHost,
      setZoom: () => undefined,
      onZoomCommand: () => () => undefined,
    },

    app: {
      // Android's lifecycle drives the controllers' own grace/resume path
      // (bindLifecycle above). Feeding the shared store's resume probe too
      // would start a second reconnect decision on the same event (D28).
      onResumed: () => () => undefined,
    },
  };

  // A fresh WebView creates one fresh platform. Enabled forwarding hosts
  // mount through exactly the same shared connection owner as picker dials.
  void Promise.resolve().then(resumeEnabledHosts).catch((error) => deps.log?.({ kind: 'forward-resume', message: String(error) }));

  return {
    api,
    hub,
    dispose() {
      disposed = true;
      unbindLifecycle();
      forwards?.dispose();
      for (const id of hostConnections.values()) void hub.close(id);
      hostConnections.clear();
    },
  };
}
