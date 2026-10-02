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
 *  - `reconnecting` (with the ladder's attempt and budget) is reported while
 *    the controller walks its ladder — including between a refused dial and
 *    the next one — and `lost` (with the controller's reason) only once it
 *    has given up;
 *  - the hub advertises `ssh.reconnect` (see `androidApi.ts`), so the shared
 *    store runs no ladder of its own and its Retry asks the controller for
 *    one recovery of the same logical id (#2954, D28: one reconnect owner);
 *  - a shell id names one attach of one session. The controller keeps one
 *    PTY per attached session (#2955), so every visited tab keeps its shell
 *    id, its screen and its live output while another tab is in front;
 *    a shell id is reported exited only when its own session's PTY is gone
 *    (the session ended, or it vanished from the host after a reconnect).
 */
import type { ConnectionStateEvent } from '@ui/app/api';
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
  type HostKeyTrustChoice,
  type HostKeyTrustRequest,
  type PendingHostKeyDecision,
  type SessionRow,
  type SessionSummary,
  type ShellId,
  type SshHostTarget,
} from '@pocketshell/core';

/** Default timeout for a generic `ssh.exec` from the shared app. */
export const HOST_COMMAND_TIMEOUT_MS = 30_000;

export type ConnectionStateListener = (payload: ConnectionStateEvent) => void;
export type ShellDataListener = (payload: { shellId: ShellId; data: Uint8Array }) => void;
export type ShellExitListener = (payload: { shellId: ShellId; exitCode: number }) => void;

export type TofuDecision = HostKeyTrustChoice;

/**
 * Asks the user about a first-contact host key (the shared app's
 * `ssh.onTrustDecision` decider). Resolves with the user's answer.
 */
export type TrustDecider = (request: HostKeyTrustRequest) => Promise<HostKeyTrustChoice>;

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
  lastAttempt: number;
  /** One shell id per attached session, at most one per session. */
  shells: ShellBinding[];
  /** Attaches in flight, at most one per session. */
  attaching: PendingAttach[];
  unsubscribe: Array<() => void>;
}

/**
 * How long held output waits for the pane's first call on a new shell id
 * before it is delivered anyway (the pane adopts the id synchronously after
 * `attachSession` resolves, so this only covers a pane that never calls).
 */
export const SHELL_CLAIM_FALLBACK_MS = 1_000;

/**
 * The most output held for a shell id the pane has not claimed yet. Past it
 * the OLDEST chunks go: the attach snapshot is a full repaint, so what a pane
 * that claims late needs is the newest screen, not a busy session's history.
 */
export const HELD_OUTPUT_LIMIT_BYTES = 256 * 1024;

/** Append to a held queue, dropping its oldest chunks past the byte limit. */
export function holdBounded(queue: Uint8Array[], bytes: Uint8Array, limit = HELD_OUTPUT_LIMIT_BYTES): void {
  queue.push(bytes);
  let total = 0;
  for (const chunk of queue) total += chunk.length;
  while (total > limit && queue.length > 1) total -= queue.shift()!.length;
}

/**
 * One controller snapshot as the hub saw it, keyed by the LOGICAL id — the
 * diagnostics stream a packaged journey reads to count ladders and dials
 * without trusting the UI (#2954).
 */
export interface ConnectionJournalEntry {
  at: number;
  /** The logical id the shared app holds. */
  connectionId: string;
  phase: ConnectionSnapshot['phase'];
  retryAttempt: number;
  /** The native transport id of the current generation (null between dials). */
  transportId: string | null;
  generationId: string | null;
  selectedId: string | null;
  selectedName: string | null;
  selectedTag: string | null;
  error: string | null;
}

export interface ConnectionHubOptions {
  /** A fresh controller per logical connection (bound to the native capability). */
  createController: () => ConnectionController;
  /** Every controller snapshot, for diagnostics; never drives behaviour. */
  observe?: (entry: ConnectionJournalEntry) => void;
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
      // still up. A failed dial leaves no transport behind — inside the
      // retry ladder that is one refused attempt, not the give-up (the
      // controller says `lost` itself when the budget is spent).
      if (snapshot.connectionId) return 'connected';
      return snapshot.retryAttempt > 0 ? 'reconnecting' : 'lost';
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
  private trustDecider: TrustDecider | null = null;
  private nextShell = 1;
  private readonly encoder = new TextEncoder();

  constructor(private readonly options: ConnectionHubOptions) {}

  // --- ssh ------------------------------------------------------------------

  /**
   * Register the shared app's host-key decider (`ssh.onTrustDecision`). One
   * decider at a time; the returned closure unregisters it.
   */
  setTrustDecider(decider: TrustDecider): () => void {
    this.trustDecider = decider;
    return () => {
      if (this.trustDecider === decider) this.trustDecider = null;
    };
  }

  /**
   * Dial `target` on a fresh controller.
   *
   * Host keys, in the controller's terms:
   *  - pinned and matching: connects;
   *  - first contact (`trust-required`): an explicit `tofuDecision` answers it;
   *    without one the registered decider asks the user, and with no decider
   *    the key is refused (fail closed — never a silent pin). `accept-always`
   *    records the pin, `accept-once` trusts it for this controller only;
   *  - CHANGED (`trust-mismatch`): refused outright with a visible message. It
   *    never reaches the prompt and no decision can override it here.
   */
  async connect(
    target: SshHostTarget,
    tofuDecision?: TofuDecision,
    hostLabel: string = target.hostname,
  ): Promise<ConnectResult> {
    const controller = this.options.createController();
    let result = await controller.connect(target);
    if (!result.ok && result.reason === 'trust-required') {
      const pending = controller.getSnapshot().trustDecision;
      const decision = tofuDecision ?? (pending ? await this.askTrust(target, hostLabel, pending) : 'reject');
      if (decision === 'reject') {
        await controller.close().catch(() => undefined);
        return {
          ok: false,
          error: `Host key for ${hostLabel} was not trusted. No connection was opened.`,
        };
      }
      result = await controller.acceptPresentedHostKey({ persist: decision === 'accept-always' });
    } else if (!result.ok && result.reason === 'trust-mismatch') {
      const pending = controller.getSnapshot().trustDecision;
      await controller.close().catch(() => undefined);
      return { ok: false, error: hostKeyChangedMessage(hostLabel, pending) };
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
      lastAttempt: 0,
      shells: [],
      attaching: [],
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
        // Each session's bytes go to that session's own shell id, whichever
        // tab is in front (#2955).
        const attaching = record.attaching.find((pending) => sameRow(pending.row, session));
        if (attaching) {
          holdBounded(attaching.buffer, bytes);
          return;
        }
        const shell = record.shells.find((binding) => sameRow(binding.row, session));
        if (!shell) return;
        if (shell.held) {
          holdBounded(shell.held, bytes);
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
    for (const shell of [...record.shells]) this.retireShell(record, shell);
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

  /**
   * The shared store's Retry: one controller recovery of this logical
   * connection (joining a ladder already running). True once the transport
   * and the attached session are back.
   */
  async reconnect(connectionId: string): Promise<boolean> {
    const result = await this.recordOf(connectionId).controller.reconnect();
    return result.ok;
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

  /**
   * The pane's "did this session outlive its client?" listing (#3039): the
   * controller's side-effect-free probe. Unlike {@link sessionsList} it never
   * starts a reconnect or writes connection state, and it refuses rather
   * than asks while the link is not usable — so a verdict asked as the link
   * dies cannot become a second recovery trigger (#2954's one ladder).
   */
  async sessionsProbe(connectionId: string): Promise<SessionSummary[]> {
    const result = await this.recordOf(connectionId).controller.probeSessions();
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
    const target = row;

    const geometry = request.cols && request.rows ? { cols: request.cols, rows: request.rows } : undefined;
    const current = record.shells.find((binding) => sameRow(binding.row, target));
    const snapshot = controller.getSnapshot();
    const controllerHolds = snapshot.terminals.some((open) => sameRow(open, target));
    if (current && controllerHolds && snapshot.phase === 'live') {
      // The pane asks again for a session it already holds (a re-join of a
      // live tab): its PTY and screen never left. Only the controller's focus
      // moves — no attach, no repaint.
      const focused = await controller.attachSession(target, geometry);
      if (focused.ok) return { shellId: current.shellId, switched: true };
    }
    if (!current && controllerHolds) {
      // A PTY no pane holds (its shell id was retired): open a fresh attach,
      // so the pane that adopts it gets aplexer's repaint.
      await controller.detachSession(target);
    }

    let settle!: () => void;
    const pending: PendingAttach = { row: target, buffer: [], done: new Promise<void>((resolve) => { settle = resolve; }) };
    record.attaching.push(pending);
    let attached;
    try {
      // Geometry-first: the PTY opens at the pane's size, so the attach
      // snapshot is drawn for the screen the user has.
      attached = await controller.attachSession(target, geometry);
    } finally {
      record.attaching = record.attaching.filter((entry) => entry !== pending);
    }
    if (!attached.ok) {
      settle();
      throw new Error(attached.message);
    }
    const previous = record.shells.find((binding) => sameRow(binding.row, target));
    if (previous) this.retireShell(record, previous);
    const shellId = `${record.id}:shell-${this.nextShell++}`;
    const binding: ShellBinding = { shellId, row: attached.value, held: pending.buffer, claimTimer: null };
    binding.claimTimer = setTimeout(() => this.claim(binding), SHELL_CLAIM_FALLBACK_MS);
    record.shells.push(binding);
    settle();
    return { shellId, switched: false };
  }

  /** A bare login shell is not a controller operation yet (stage S2). */
  async open(): Promise<ShellId> {
    throw new Error('Android does not open bare shells yet; attach a session instead.');
  }

  async input(shellId: ShellId, data: string, sessionName?: string, workspace?: string): Promise<boolean> {
    let found = this.shellOf(shellId);
    // Only the pane's own use of the CURRENT id proves it adopted it; keys
    // under a retired id never release the held repaint (#2936 re-review).
    const ownsCurrentId = found !== null;
    if (!found) {
      // Keystrokes addressed to a shell id this hub retired — a pane typing
      // while it re-joins the SAME session (its id is replaced only when the
      // new attach resolves). They are delivered to that session's current
      // shell, in order, rather than dropped. A retired id whose session has
      // no shell (or any other session) gets `false`.
      found = await this.shellForRetired(shellId);
      if (!found) return false;
    }
    const { record, binding } = found;
    if (ownsCurrentId) this.claim(binding);
    const row = binding.row;
    // The shared pane's fence: a caller still holding another tab's shell
    // gets an honest `false` instead of typing into another session.
    if (sessionName && sessionName !== (row.tag ?? row.name) && sessionName !== row.name) return false;
    if (workspace && row.workspace && workspace !== row.workspace) return false;
    const result = await record.controller.writeTerminalBytes(row, this.encoder.encode(data));
    return result.ok;
  }

  async resize(shellId: ShellId, cols: number, rows: number): Promise<boolean> {
    const found = this.shellOf(shellId);
    if (!found) return false;
    this.claim(found.binding);
    return (await found.record.controller.resizeTerminal(found.binding.row, cols, rows)).ok;
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
    const found = this.shellOf(shellId);
    if (!found) return false;
    this.claim(found.binding);
    // A pane redraws when its tab becomes visible: the tab in front is the
    // last one the controller's channel bound may evict (#2955).
    found.record.controller.focusSession(found.binding.row);
    return true;
  }

  /**
   * The pane is done with this shell (tab closed, workspace left): detach
   * that session's PTY — and only that one — so its next attach is a fresh
   * aplexer attach that repaints. While another attach of the same session
   * is already in flight the pane's close is only bookkeeping: that attach
   * owns the PTY, and detaching would supersede it.
   */
  async closeShell(shellId: ShellId): Promise<boolean> {
    const found = this.shellOf(shellId);
    if (!found) return false;
    const { record, binding } = found;
    this.rememberRetired(record, binding);
    this.dropBinding(binding);
    record.shells = record.shells.filter((entry) => entry !== binding);
    if (!record.attaching.some((pending) => sameRow(pending.row, binding.row))) {
      await record.controller.detachSession(binding.row).catch(() => undefined);
    }
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

  private async askTrust(
    target: SshHostTarget,
    hostLabel: string,
    pending: PendingHostKeyDecision,
  ): Promise<HostKeyTrustChoice> {
    const decider = this.trustDecider;
    if (!decider) return 'reject';
    try {
      return await decider({
        hostLabel,
        hostname: target.hostname,
        port: target.port,
        user: target.username,
        keyType: pending.presented.keyType,
        fingerprintSha256: pending.presented.fingerprintSha256,
      });
    } catch {
      return 'reject';
    }
  }

  private onSnapshot(record: ConnectionRecord, snapshot: ConnectionSnapshot): void {
    this.options.observe?.({
      at: Date.now(),
      connectionId: record.id,
      phase: snapshot.phase,
      retryAttempt: snapshot.retryAttempt,
      transportId: snapshot.connectionId,
      generationId: snapshot.generationId,
      selectedId: snapshot.selectedSession?.id ?? null,
      selectedName: snapshot.selectedSession?.name ?? null,
      selectedTag: snapshot.selectedSession?.tag ?? null,
      error: snapshot.error,
    });
    // A shell id is spent once its session's PTY is gone from the
    // controller (the session ended, or it no longer exists after a
    // reconnect). A reconnect in progress — or a give-up awaiting Retry —
    // keeps every terminal, and the controller re-attaches each under its
    // same shell id.
    for (const shell of [...record.shells]) {
      if (snapshot.terminals.some((open) => sameRow(open, shell.row))) continue;
      if (record.attaching.some((pending) => sameRow(pending.row, shell.row))) continue;
      this.retireShell(record, shell);
    }
    const state = connectionStateFor(snapshot);
    const attempt = state === 'reconnecting' ? snapshot.retryAttempt : 0;
    if (state === record.lastState && attempt === record.lastAttempt) return;
    record.lastState = state;
    record.lastAttempt = attempt;
    if (state === 'reconnecting') {
      this.emitState(record.id, state, {
        attempt,
        maxAttempts: record.controller.maxReconnectAttempts,
        ...(snapshot.error ? { error: snapshot.error } : {}),
      });
    } else if (state === 'lost' && snapshot.error) {
      this.emitState(record.id, state, { error: snapshot.error });
    } else {
      this.emitState(record.id, state);
    }
  }

  private retireShell(record: ConnectionRecord, shell: ShellBinding): void {
    record.shells = record.shells.filter((entry) => entry !== shell);
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

  private emitState(
    connectionId: string,
    state: ConnectionState,
    detail: Omit<ConnectionStateEvent, 'connectionId' | 'state'> = {},
  ): void {
    for (const listener of this.stateListeners) listener({ connectionId, state, ...detail });
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

  /** The shell now showing a retired id's session, after any in-flight attach of it. */
  private async shellForRetired(shellId: ShellId): Promise<{ record: ConnectionRecord; binding: ShellBinding } | null> {
    const entry = this.retired.get(shellId);
    if (!entry || !this.records.has(entry.record.id)) return null;
    const { record, row } = entry;
    const pending = record.attaching.find((attach) => sameRow(attach.row, row));
    if (pending) await pending.done;
    const binding = record.shells.find((shell) => sameRow(shell.row, row));
    return binding ? { record, binding } : null;
  }

  private shellOf(shellId: ShellId): { record: ConnectionRecord; binding: ShellBinding } | null {
    for (const record of this.records.values()) {
      const binding = record.shells.find((shell) => shell.shellId === shellId);
      if (binding) return { record, binding };
    }
    return null;
  }
}

/** The refusal a changed host key gets: what changed, and that nothing connected. */
export function hostKeyChangedMessage(hostLabel: string, pending: PendingHostKeyDecision | null): string {
  const presented = pending?.presented.fingerprintSha256;
  const trusted = pending?.previouslyTrusted?.fingerprintSha256;
  const detail = presented
    ? ` It now presents ${presented}${trusted ? ` instead of the trusted ${trusted}` : ''}.`
    : '';
  return `Host key for ${hostLabel} has changed — connection refused.${detail} `
    + 'This can mean the server was reinstalled or that someone is intercepting the connection.';
}

/** Normalize a native bridge failure into the message the shared app shows. */
export function nativeErrorMessage(error: unknown): string {
  return readSshCapabilityError(error).message;
}
