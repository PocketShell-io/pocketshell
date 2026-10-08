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
  readHostUsage,
  runHostBootstrap,
  type ConnectionController,
  type HomeResult,
} from '@pocketshell/core';
import { AndroidConnectionHub, type ConnectionJournalEntry, type TofuDecision } from './connectionHub';
import { MissingHostCredential, type AndroidHostStore } from './hostStore';
import { declinedAccountHostMessage, type AccountHostKeys } from './accountHosts';

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
  /** Account hosts this phone has no key for yet: find and adopt them (#3063). */
  accountHosts?: AccountHostKeys;
}

export interface AndroidPlatform {
  api: PocketShellApi;
  hub: AndroidConnectionHub;
  dispose(): void;
}

export function createAndroidPlatform(deps: AndroidApiDeps): AndroidPlatform {
  const hub = new AndroidConnectionHub({
    createController: deps.createController,
    observe: deps.observeConnections,
  });
  const unbindLifecycle = deps.lifecycle
    ? deps.lifecycle.onActiveChange((active) => {
        if (active) void hub.returnToForeground();
        else void hub.enterBackground(deps.backgroundGraceMs());
      })
    : () => undefined;

  const exec = (connectionId: string, command: string) => hub.exec(connectionId, command);

  /**
   * The dial target: a phone host; a phone host whose key is gone, once the
   * user attaches a key to it (updated in place); or an account host the
   * phone does not have, once the user gives it this phone's key (saved on
   * the phone). Either way it then resolves like any other phone host.
   */
  async function resolveTarget(payload: Parameters<PocketShellApi['ssh']['connect']>[0]) {
    try {
      return await deps.hosts.resolve(payload);
    } catch (error) {
      const keys = deps.accountHosts;
      if (!keys) throw error;
      const missingKey = error instanceof MissingHostCredential;
      const host = missingKey ? error.host : await keys.find(payload);
      if (!host) throw error;
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
        return hub.connect(target, standing, deps.hosts.labelFor(payload));
      },
      // Android asks before trusting a first-contact key: the shared store
      // registers its prompt here and stops dialling with a standing
      // accept-always (the #2953 regression against the legacy screen).
      onTrustDecision: (decider) => hub.setTrustDecider(decider),
      exec,
      close: (connectionId) => hub.close(connectionId),
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
      killSession: unsupported('projects.killSession'),
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

    // The workspace polls list/isAutoEnabled on every mount: empty is the
    // honest answer until the native forward engine is wired (stage F2).
    forwards: {
      scan: unsupported('forwards.scan'),
      startAuto: unsupported('forwards.startAuto'),
      stopAuto: unsupported('forwards.stopAuto'),
      addManual: unsupported('forwards.addManual'),
      remove: unsupported('forwards.remove'),
      list: async () => [],
      refresh: async () => false,
      discovered: async () => [],
      status: async () => null,
      setName: unsupported('forwards.setName'),
      setRemap: unsupported('forwards.setRemap'),
      clearRemap: unsupported('forwards.clearRemap'),
      setIntent: unsupported('forwards.setIntent'),
      togglePort: unsupported('forwards.togglePort'),
      isAutoEnabled: async () => false,
      onStates: () => () => undefined,
    },

    attachments: {
      stage: unsupported('attachments.stage'),
      pickFiles: unsupported('attachments.pickFiles'),
      readLocal: unsupported('attachments.readLocal'),
    },

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

  return {
    api,
    hub,
    dispose() {
      unbindLifecycle();
    },
  };
}
