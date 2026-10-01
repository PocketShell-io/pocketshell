/**
 * The Android connection registry behind the shared app's `ssh`, `shell` and
 * `helper` groups.
 *
 * Every logical connection the shared app sees is one core
 * {@link ConnectionController}. The controller is the single owner of dial,
 * host-key verdicts, session listing, attach, reconnect and background grace
 * (D28); this hub only translates between the shared app's transport
 * vocabulary (connection ids, shell ids, `ConnectionState`) and the
 * controller's snapshot. It makes no reconnect decision of its own:
 *
 *  - the connection id the shared app holds is LOGICAL and stable across the
 *    controller's internal re-dials, so the shared connection store never
 *    sees a new id and never re-keys its surfaces;
 *  - `reconnecting` is reported while the controller walks its ladder, and
 *    `lost` only once the controller has given up — the shared store's own
 *    `ReconnectLoop` starts on `lost` alone, so the two ladders never race
 *    (the remaining overlap after a give-up is stage U1 of the #2936 plan);
 *  - a shell id names one attach of one session; the controller keeps one
 *    PTY per connection, so attaching another session supersedes the
 *    previous shell id, which is reported exited (honest: its PTY closed).
 */
import {
  findSessionRow,
  readSshCapabilityError,
  sessionRowToSummary,
  type ConnectResult,
  type ConnectionController,
  type ConnectionSnapshot,
  type ConnectionState,
  type ExecResult,
  type HostCliExecOutcome,
  type SessionRow,
  type SessionSummary,
  type ShellId,
  type SshHostTarget,
} from '@pocketshell/core';

/** Default timeout for a generic `ssh.exec` from the shared app. */
export const HOST_COMMAND_TIMEOUT_MS = 30_000;

export type ConnectionStateListener = (payload: { connectionId: string; state: ConnectionState }) => void;
export type ShellDataListener = (payload: { shellId: ShellId; data: Uint8Array }) => void;
export type ShellExitListener = (payload: { shellId: ShellId; exitCode: number }) => void;

export type TofuDecision = 'accept-always' | 'accept-once' | 'reject';

export interface AttachRequest {
  connectionId: string;
  sessionName: string;
  cols?: number;
  rows?: number;
  backend?: 'tmux' | 'aplexer';
  workspace?: string;
  tag?: string;
  aplexerId?: string;
}

interface ShellBinding {
  shellId: ShellId;
  row: SessionRow;
  /**
   * Output held for a shell id the shared pane does not know yet; null once
   * the pane has claimed it. See {@link AndroidConnectionHub.claim}.
   */
  held: Uint8Array[] | null;
  claimTimer: ReturnType<typeof setTimeout> | null;
}

/** An attach in flight: its session's output is captured from the first byte. */
interface PendingAttach {
  row: SessionRow;
  buffer: Uint8Array[];
  /** Settles when the attach does, either way. */
  done: Promise<void>;
}

/** How many retired shell ids keep their session for input routing. */
const RETIRED_SHELL_MEMORY = 32;

interface ConnectionRecord {
  id: string;
  controller: ConnectionController;
  lastState: ConnectionState | null;
  lastPhase: ConnectionSnapshot['phase'] | null;
  shell: ShellBinding | null;
  attaching: PendingAttach | null;
  unsubscribe: Array<() => void>;
}

/**
 * How long held output waits for the pane's first call on a new shell id
 * before it is delivered anyway (the pane adopts the id synchronously after
 * `attachSession` resolves, so this only covers a pane that never calls).
 */
export const SHELL_CLAIM_FALLBACK_MS = 1_000;

export interface ConnectionHubOptions {
  /** A fresh controller per logical connection (bound to the native capability). */
  createController: () => ConnectionController;
}

/** Map the controller's phase to the shared app's four-word connection state. */
export function connectionStateFor(snapshot: ConnectionSnapshot): ConnectionState {
  switch (snapshot.phase) {
    case 'idle':
      return 'idle';
    case 'connecting':
      // A dial inside the controller's retry ladder is still the recovery.
      return snapshot.retryAttempt > 0 ? 'reconnecting' : 'connecting';
    case 'awaiting-trust':
      return 'connecting';
    case 'reconnecting':
      return 'reconnecting';
    case 'lost':
      return 'lost';
    case 'error':
      // An operation failed on a live transport (attach, resize): the link is
      // still up. A failed dial leaves no transport behind.
      return snapshot.connectionId ? 'connected' : 'lost';
    default:
      return 'connected';
  }
}

function sameRow(left: SessionRow, right: SessionRow): boolean {
  if (left.id && right.id) return left.id === right.id;
  return left.name === right.name && left.workspace === right.workspace;
}

export class AndroidConnectionHub {
  private readonly records = new Map<string, ConnectionRecord>();
  private readonly stateListeners = new Set<ConnectionStateListener>();
  private readonly dataListeners = new Set<ShellDataListener>();
  private readonly exitListeners = new Set<ShellExitListener>();
  private nextConnection = 1;
  private nextShell = 1;
  private readonly encoder = new TextEncoder();

  constructor(private readonly options: ConnectionHubOptions) {}

  // --- ssh ------------------------------------------------------------------

  async connect(target: SshHostTarget, tofuDecision: TofuDecision = 'accept-always'): Promise<ConnectResult> {
    const controller = this.options.createController();
    let result = await controller.connect(target);
    if (!result.ok && result.reason === 'trust-required' && tofuDecision !== 'reject') {
      // First contact (no pin yet): the shared store dials with the desktop's
      // trust-on-first-use decision, and the controller records the pin. A
      // CHANGED key is never accepted here — see the mismatch branch below.
      result = await controller.acceptPresentedHostKey();
    }
    if (!result.ok) {
      await controller.close().catch(() => undefined);
      return { ok: false, error: result.message };
    }
    const id = `android-${this.nextConnection++}`;
    const record: ConnectionRecord = {
      id,
      controller,
      lastState: null,
      lastPhase: null,
      shell: null,
      attaching: null,
      unsubscribe: [],
    };
    this.records.set(id, record);
    record.unsubscribe.push(controller.subscribe((snapshot) => this.onSnapshot(record, snapshot)));
    record.unsubscribe.push(
      controller.subscribeTerminalOutput((session, bytes) => {
        // aplexer answers an attach with a snapshot of the session's screen —
        // the only repaint a quiet session will ever send. It can arrive
        // before `switchSession` resolves (the pump starts inside it) and
        // before the pane has adopted the new shell id, so it is captured,
        // never dropped (#2936 review).
        const attaching = record.attaching;
        if (attaching && sameRow(attaching.row, session)) {
          attaching.buffer.push(bytes);
          return;
        }
        const shell = record.shell;
        if (!shell || !sameRow(shell.row, session)) return;
        if (shell.held) {
          shell.held.push(bytes);
          return;
        }
        this.emitData(shell.shellId, bytes);
      }),
    );
    return { ok: true, connectionId: id };
  }

  async close(connectionId: string): Promise<boolean> {
    const record = this.records.get(connectionId);
    if (!record) return false;
    this.records.delete(connectionId);
    for (const unsubscribe of record.unsubscribe) unsubscribe();
    this.retireShell(record);
    await record.controller.close().catch(() => undefined);
    this.emitState(connectionId, 'idle');
    return true;
  }

  async exec(connectionId: string, command: string, timeoutMs = HOST_COMMAND_TIMEOUT_MS): Promise<ExecResult> {
    const result = await this.recordOf(connectionId).controller.runHostCommand(command, timeoutMs);
    if (!result.ok) throw new Error(result.message);
    return {
      exitCode: result.value.exitCode ?? -1,
      stdout: result.value.stdout,
      stderr: result.value.stderr,
    };
  }

  /**
   * The host CLI's exec shape (`HostCliExecOutcome`), for core helpers that
   * build a platform API from an exec — e.g. #2925's
   * `workspaceRootsApiFromExec` behind the optional `workspaces` group.
   */
  async hostCommand(connectionId: string, command: string, timeoutMs: number): Promise<HostCliExecOutcome> {
    const result = await this.recordOf(connectionId).controller.runHostCommand(command, timeoutMs);
    if (!result.ok) throw new Error(result.message);
    return result.value;
  }

  onState(listener: ConnectionStateListener): () => void {
    this.stateListeners.add(listener);
    return () => this.stateListeners.delete(listener);
  }

  // --- helper ---------------------------------------------------------------

  async sessionsList(connectionId: string): Promise<SessionSummary[]> {
    const result = await this.recordOf(connectionId).controller.refreshSessions();
    if (!result.ok) throw new Error(result.message);
    return result.value.sessions.map(sessionRowToSummary);
  }

  // --- shell ----------------------------------------------------------------

  async attachSession(request: AttachRequest): Promise<{ shellId: ShellId; switched: boolean }> {
    const record = this.recordOf(request.connectionId);
    const controller = record.controller;
    let row = findSessionRow(controller.getSnapshot().sessions, request);
    if (!row) {
      const refreshed = await controller.refreshSessions();
      if (refreshed.ok) row = findSessionRow(refreshed.value.sessions, request);
    }
    if (!row) throw new Error(`Session “${request.sessionName}” is no longer in the host list.`);

    const current = record.shell;
    const snapshot = controller.getSnapshot();
    if (current && sameRow(current.row, row) && snapshot.phase === 'live') {
      return { shellId: current.shellId, switched: true };
    }

    let settle!: () => void;
    const pending: PendingAttach = { row, buffer: [], done: new Promise<void>((resolve) => { settle = resolve; }) };
    record.attaching = pending;
    let attached;
    try {
      // Geometry-first: the PTY opens at the pane's size, so the attach
      // snapshot is drawn for the screen the user has.
      const geometry = request.cols && request.rows ? { cols: request.cols, rows: request.rows } : undefined;
      attached = await controller.switchSession(row, geometry);
    } finally {
      if (record.attaching === pending) record.attaching = null;
    }
    if (!attached.ok) {
      settle();
      throw new Error(attached.message);
    }
    this.retireShell(record);
    const shellId = `${record.id}:shell-${this.nextShell++}`;
    const binding: ShellBinding = { shellId, row: attached.value, held: pending.buffer, claimTimer: null };
    binding.claimTimer = setTimeout(() => this.claim(binding), SHELL_CLAIM_FALLBACK_MS);
    record.shell = binding;
    settle();
    return { shellId, switched: false };
  }

  /** A bare login shell is not a controller operation yet (stage S2). */
  async open(): Promise<ShellId> {
    throw new Error('Android does not open bare shells yet; attach a session instead.');
  }

  async input(shellId: ShellId, data: string, sessionName?: string, workspace?: string): Promise<boolean> {
    let record = this.recordForShell(shellId);
    // Only the pane's own use of the CURRENT id proves it adopted it; keys
    // under a retired id never release the held repaint (#2936 re-review).
    const ownsCurrentId = record !== null;
    if (!record) {
      // Keystrokes addressed to a shell id this hub retired — a pane typing
      // while it re-joins the SAME session (its id is replaced only when the
      // new attach resolves). The controller's one PTY is, or is about to be,
      // that session, so they are delivered there in order rather than
      // dropped. A retired id of any other session still gets `false`.
      record = await this.recordForRetiredShell(shellId);
      if (!record) return false;
    }
    if (!record.shell) return false;
    if (ownsCurrentId) this.claim(record.shell);
    const row = record.shell.row;
    // The shared pane's fence: a caller still holding a superseded tab's
    // shell gets an honest `false` instead of typing into another session.
    if (sessionName && sessionName !== (row.tag ?? row.name) && sessionName !== row.name) return false;
    if (workspace && row.workspace && workspace !== row.workspace) return false;
    const result = await record.controller.writeTerminalBytes(this.encoder.encode(data));
    return result.ok;
  }

  async resize(shellId: ShellId, cols: number, rows: number): Promise<boolean> {
    const record = this.recordForShell(shellId);
    if (!record?.shell) return false;
    this.claim(record.shell);
    return (await record.controller.resizeTerminal(cols, rows)).ok;
  }

  /**
   * aplexer repaints the session's screen on every attach (its attach
   * snapshot, verified against the fixture's `a` for first and repeated
   * attaches) and follows the PTY size through SIGWINCH, so there is no
   * stale band to refresh — desktop's TmuxClientPool answers an aplexer
   * redraw the same way. What a repaint DOES need is for that snapshot to
   * reach the pane, which the claim below guarantees.
   */
  async redraw(shellId: ShellId): Promise<boolean> {
    const record = this.recordForShell(shellId);
    if (!record?.shell) return false;
    this.claim(record.shell);
    return true;
  }

  /**
   * The pane is done with this shell (tab closed, workspace left): detach the
   * controller's PTY so the next attach of the session — the same one
   * included — is a fresh aplexer attach that repaints. While another attach
   * is already in flight the pane's close is only bookkeeping: that attach
   * replaces the PTY itself, and detaching would supersede it.
   */
  async closeShell(shellId: ShellId): Promise<boolean> {
    const record = this.recordForShell(shellId);
    if (!record?.shell) return false;
    this.rememberRetired(record, record.shell);
    this.dropBinding(record.shell);
    record.shell = null;
    if (!record.attaching) await record.controller.detachSession().catch(() => undefined);
    return true;
  }

  onData(listener: ShellDataListener): () => void {
    this.dataListeners.add(listener);
    return () => this.dataListeners.delete(listener);
  }

  onExited(listener: ShellExitListener): () => void {
    this.exitListeners.add(listener);
    return () => this.exitListeners.delete(listener);
  }

  // --- lifecycle --------------------------------------------------------------

  /** Android paused the activity: every live controller starts its grace. */
  async enterBackground(graceMs: number): Promise<void> {
    await Promise.all(
      [...this.records.values()].map(async (record) => {
        const phase = record.controller.getSnapshot().phase;
        if (phase === 'live' || phase === 'connected') {
          await record.controller.enterBackground(graceMs).catch(() => undefined);
        }
      }),
    );
  }

  /** Android resumed: each controller reuses or re-dials within its own policy. */
  async returnToForeground(): Promise<void> {
    await Promise.all(
      [...this.records.values()].map(async (record) => {
        if (record.controller.getSnapshot().phase === 'background') {
          await record.controller.returnToForeground().catch(() => undefined);
        }
      }),
    );
  }

  /** Test and diagnostics view of the registry. */
  controllerOf(connectionId: string): ConnectionController | null {
    return this.records.get(connectionId)?.controller ?? null;
  }

  // --- internals --------------------------------------------------------------

  private onSnapshot(record: ConnectionRecord, snapshot: ConnectionSnapshot): void {
    const previousPhase = record.lastPhase;
    record.lastPhase = snapshot.phase;
    // The attached session's PTY reached EOF (the session ended) or the
    // controller gave up: the shell id is spent. A reconnect in progress
    // keeps it — the controller re-attaches the same session under it.
    if (
      record.shell &&
      previousPhase === 'live' &&
      (snapshot.phase === 'connected' || snapshot.phase === 'lost')
    ) {
      this.retireShell(record);
    }
    const state = connectionStateFor(snapshot);
    if (state === record.lastState) return;
    record.lastState = state;
    this.emitState(record.id, state);
  }

  private retireShell(record: ConnectionRecord): void {
    const shell = record.shell;
    record.shell = null;
    if (!shell) return;
    this.rememberRetired(record, shell);
    this.dropBinding(shell);
    for (const listener of this.exitListeners) listener({ shellId: shell.shellId, exitCode: 0 });
  }

  /**
   * The pane has adopted `binding`'s shell id — its first resize, redraw or
   * input on it proves that, since the shared pane binds its byte stream
   * synchronously when `attachSession` resolves and only then pushes
   * geometry — so release the output held since the attach began.
   */
  private claim(binding: ShellBinding): void {
    if (binding.claimTimer) {
      clearTimeout(binding.claimTimer);
      binding.claimTimer = null;
    }
    const held = binding.held;
    if (!held) return;
    binding.held = null;
    for (const bytes of held) this.emitData(binding.shellId, bytes);
  }

  private dropBinding(binding: ShellBinding): void {
    if (binding.claimTimer) clearTimeout(binding.claimTimer);
    binding.claimTimer = null;
    binding.held = null;
  }

  private emitData(shellId: ShellId, data: Uint8Array): void {
    for (const listener of this.dataListeners) listener({ shellId, data });
  }

  private emitState(connectionId: string, state: ConnectionState): void {
    for (const listener of this.stateListeners) listener({ connectionId, state });
  }

  private recordOf(connectionId: string): ConnectionRecord {
    const record = this.records.get(connectionId);
    if (!record) throw new Error(`unknown connection: ${connectionId}`);
    return record;
  }

  private readonly retired = new Map<ShellId, { record: ConnectionRecord; row: SessionRow }>();

  private rememberRetired(record: ConnectionRecord, shell: ShellBinding): void {
    this.retired.set(shell.shellId, { record, row: shell.row });
    while (this.retired.size > RETIRED_SHELL_MEMORY) {
      const oldest = this.retired.keys().next().value as ShellId;
      this.retired.delete(oldest);
    }
  }

  /** The record now showing a retired id's session, after any in-flight attach of it. */
  private async recordForRetiredShell(shellId: ShellId): Promise<ConnectionRecord | null> {
    const entry = this.retired.get(shellId);
    if (!entry || !this.records.has(entry.record.id)) return null;
    const { record, row } = entry;
    if (record.attaching && sameRow(record.attaching.row, row)) await record.attaching.done;
    return record.shell && sameRow(record.shell.row, row) ? record : null;
  }

  private recordForShell(shellId: ShellId): ConnectionRecord | null {
    for (const record of this.records.values()) {
      if (record.shell?.shellId === shellId) return record;
    }
    return null;
  }
}

/** Normalize a native bridge failure into the message the shared app shows. */
export function nativeErrorMessage(error: unknown): string {
  return readSshCapabilityError(error).message;
}
