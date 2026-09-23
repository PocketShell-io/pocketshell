import {
  HostCliCore,
  HostCliFailed,
  verifyHostKeyPin,
  type HostCliTransport,
  type HostKeyPin,
  type SessionRow,
  type SessionsListing,
} from '@pocketshell/core';
import {
  readSshError,
  sshCapability,
  type SshCapabilityPlugin,
  type SshConnectionRef,
  type SshConnectionStateEvent,
  type SshHostTarget,
  type SshPtyRef,
  type SshResourceSnapshot,
} from '@/native/sshCapability';
import type { PluginListenerHandle } from '@capacitor/core';

export type ConnectionPhase =
  | 'idle'
  | 'connecting'
  | 'awaiting-trust'
  | 'connected'
  | 'listing'
  | 'attaching'
  | 'live'
  | 'background'
  | 'reconnecting'
  | 'lost'
  | 'error';

export interface HostKeyTrustStore {
  get(hostId: string): Promise<HostKeyPin | null>;
  record(hostId: string, pin: HostKeyPin): Promise<void>;
}

export interface PendingHostKeyDecision {
  hostId: string;
  hostLabel: string;
  reason: 'unknown' | 'mismatch';
  presented: HostKeyPin & { fingerprintSha256: string };
  previouslyTrusted: HostKeyPin | null;
}

export interface UncertainMutation {
  kind: 'create-session' | 'kill-session';
  target: string;
  state: 'unknown' | 'observed-applied';
  recordedAt: number;
}

export interface ConnectionSnapshot {
  revision: number;
  phase: ConnectionPhase;
  hostId: string | null;
  hostLabel: string | null;
  connectionId: string | null;
  generationId: string | null;
  sessions: SessionRow[];
  selectedSession: SessionRow | null;
  retryAttempt: number;
  error: string | null;
  trustDecision: PendingHostKeyDecision | null;
  uncertainMutation: UncertainMutation | null;
}

export type TerminalOutputHandler = (
  session: SessionRow,
  bytes: Uint8Array,
  generationId: string,
) => void | Promise<void>;

export interface ConnectionControllerOptions {
  capability?: SshCapabilityPlugin;
  trustStore: HostKeyTrustStore;
  now?: () => number;
  delay?: (milliseconds: number) => Promise<void>;
  createId?: () => string;
  retryDelaysMs?: readonly number[];
}

export type ConnectionActionResult<T = undefined> =
  | { ok: true; value: T }
  | { ok: false; reason: 'trust-required' | 'trust-mismatch' | 'not-connected' | 'not-found' | 'failed'; message: string };

const DEFAULT_RETRY_DELAYS_MS = [0, 250, 500, 1_000, 2_000] as const;
const DEFAULT_CONNECT_TIMEOUT_MS = 20_000;
const PTY_READ_WAIT_MS = 250;
const PTY_READ_MAX_BYTES = 32_768;

function defaultId(): string {
  return globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function sleep(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

function sameSession(left: SessionRow, right: SessionRow): boolean {
  if (left.id && right.id) return left.id === right.id;
  return left.name === right.name && left.workspace === right.workspace;
}

function base64ToBytes(value: string): Uint8Array {
  const binary = atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index);
  return bytes;
}

function bytesToBase64(bytes: Uint8Array): string {
  let binary = '';
  const chunkSize = 0x8000;
  for (let offset = 0; offset < bytes.length; offset += chunkSize) {
    const chunk = bytes.subarray(offset, Math.min(offset + chunkSize, bytes.length));
    for (let index = 0; index < chunk.length; index += 1) binary += String.fromCharCode(chunk[index]!);
  }
  return btoa(binary);
}

function isUncertainMutation(error: unknown): boolean {
  if (error instanceof HostCliFailed) return error.exitCode === null || error.timedOut;
  return ['CONNECTION_LOST', 'CONNECTION_CLOSED', 'CHANNEL_CLOSED', 'SSH_IO', 'EXEC_TIMEOUT']
    .includes(readSshError(error).code);
}

function isRetryableDialError(error: unknown): boolean {
  const nativeError = readSshError(error);
  return !['AUTH_FAILED', 'HOST_KEY_REJECTED', 'INVALID_ARGUMENT'].includes(nativeError.code);
}

/**
 * Owns one active host connection and the selected remote session.
 *
 * The Android plugin reports transport state and moves bytes only. Host CLI
 * operations, host-key verdicts, session selection, reconnect, and grace
 * decisions stay here so Android cannot grow a second policy path.
 */
export class ConnectionController {
  private readonly capability: SshCapabilityPlugin;
  private readonly trustStore: HostKeyTrustStore;
  private readonly now: () => number;
  private readonly delay: (milliseconds: number) => Promise<void>;
  private readonly createId: () => string;
  private readonly retryDelaysMs: readonly number[];
  private readonly listeners = new Set<(snapshot: ConnectionSnapshot) => void>();
  private readonly outputListeners = new Set<TerminalOutputHandler>();
  private readonly listenerReady: Promise<PluginListenerHandle>;

  private snapshot: ConnectionSnapshot = {
    revision: 0,
    phase: 'idle',
    hostId: null,
    hostLabel: null,
    connectionId: null,
    generationId: null,
    sessions: [],
    selectedSession: null,
    retryAttempt: 0,
    error: null,
    trustDecision: null,
    uncertainMutation: null,
  };
  private host: SshHostTarget | null = null;
  private connection: SshConnectionRef | null = null;
  private hostCli: HostCliCore | null = null;
  private pty: SshPtyRef | null = null;
  private ptyReadSequence = 0;
  private ptyOperationSequence = 0;
  private ptyOperationQueue: Promise<void> = Promise.resolve();
  private ptyPumpToken = 0;
  private selectionToken = 0;
  private graceDeadlineEpochMs: number | null = null;
  private reconnectTask: Promise<void> | null = null;
  private disposed = false;
  private lastDialRetryable = true;

  constructor(options: ConnectionControllerOptions) {
    this.capability = options.capability ?? sshCapability;
    this.trustStore = options.trustStore;
    this.now = options.now ?? Date.now;
    this.delay = options.delay ?? sleep;
    this.createId = options.createId ?? defaultId;
    this.retryDelaysMs = options.retryDelaysMs ?? DEFAULT_RETRY_DELAYS_MS;
    this.listenerReady = this.capability.addListener('connectionState', (event) => {
      this.onConnectionState(event);
    });
  }

  getSnapshot(): ConnectionSnapshot {
    return { ...this.snapshot, sessions: [...this.snapshot.sessions] };
  }

  subscribe(listener: (snapshot: ConnectionSnapshot) => void): () => void {
    this.listeners.add(listener);
    listener(this.getSnapshot());
    return () => this.listeners.delete(listener);
  }

  subscribeTerminalOutput(listener: TerminalOutputHandler): () => void {
    this.outputListeners.add(listener);
    return () => this.outputListeners.delete(listener);
  }

  async connect(host: SshHostTarget): Promise<ConnectionActionResult<SshConnectionRef>> {
    this.assertLive();
    await this.listenerReady;
    if (this.connection && this.host?.hostId === host.hostId) {
      try {
        const requestId = this.createId();
        const status = await this.capability.getConnectionState({ ...this.connection, requestId });
        if (status.requestId !== requestId) throw new Error('SSH state returned a stale request.');
        if (status.state === 'connected') return { ok: true, value: this.connection };
      } catch {
        // A missing native handle is the same as a spent transport here.
      }
    }

    await this.closeCurrentTransport();
    this.host = host;
    this.setSnapshot({
      phase: 'connecting',
      hostId: host.hostId,
      hostLabel: host.hostname,
      connectionId: null,
      generationId: null,
      sessions: [],
      selectedSession: null,
      retryAttempt: 0,
      error: null,
      trustDecision: null,
      uncertainMutation: null,
    });
    const result = await this.dial(host);
    if (result.ok) {
      this.setSnapshot({ phase: 'connected', connectionId: result.value.connectionId, generationId: result.value.generationId });
      return result;
    }
    return result;
  }

  async acceptPresentedHostKey(): Promise<ConnectionActionResult<SshConnectionRef>> {
    const pending = this.snapshot.trustDecision;
    const host = this.host;
    if (!pending || !host) return { ok: false, reason: 'failed', message: 'There is no pending host-key decision.' };
    await this.trustStore.record(host.hostId, {
      keyType: pending.presented.keyType,
      keyB64: pending.presented.keyB64,
    });
    this.setSnapshot({ trustDecision: null, phase: 'connecting', error: null });
    return this.connect(host);
  }

  async refreshSessions(): Promise<ConnectionActionResult<SessionsListing>> {
    const cli = this.hostCli;
    if (!cli || !this.connection) {
      return { ok: false, reason: 'not-connected', message: 'Connect to a host before listing sessions.' };
    }
    this.setSnapshot({ phase: this.snapshot.phase === 'live' ? 'live' : 'listing', error: null });
    try {
      const listing = await cli.listSessions();
      this.setSnapshot({ sessions: listing.sessions });
      this.reconcilePendingMutation(listing);
      return { ok: true, value: listing };
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      if (isUncertainMutation(error) || this.isCurrentTransportFailure(error)) {
        this.startReconnect('session list lost its transport');
      }
      const phase = this.snapshot.phase === 'reconnecting'
        ? 'reconnecting'
        : this.snapshot.selectedSession ? 'live' : 'error';
      this.setSnapshot({ error: message, phase });
      return { ok: false, reason: 'failed', message };
    }
  }

  async createSession(
    name: string,
    options: { cwd?: string | null; engine?: string | null; profile?: string | null } = {},
  ): Promise<ConnectionActionResult<{ name: string; id: string | null; created: boolean }>> {
    const cli = this.hostCli;
    if (!cli) return { ok: false, reason: 'not-connected', message: 'Connect to a host before creating a session.' };
    try {
      const created = await cli.createSession(name, options);
      const listing = await cli.listSessions();
      this.setSnapshot({ sessions: listing.sessions });
      return { ok: true, value: created };
    } catch (error) {
      if (isUncertainMutation(error)) {
        this.recordUncertainMutation({ kind: 'create-session', target: name });
        if (this.isCurrentTransportFailure(error)) this.startReconnect('create result was lost');
        else await this.reconcileUncertainMutation();
        return {
          ok: false,
          reason: 'failed',
          message: `The host may have created session “${name}”, but the result was lost. Refresh sessions before deciding what to do next.`,
        };
      }
      const message = error instanceof Error ? error.message : String(error);
      return { ok: false, reason: 'failed', message };
    }
  }

  async killSession(name: string): Promise<ConnectionActionResult> {
    const cli = this.hostCli;
    if (!cli) return { ok: false, reason: 'not-connected', message: 'Connect to a host before stopping a session.' };
    try {
      await cli.killSession(name);
      const listing = await cli.listSessions();
      this.setSnapshot({ sessions: listing.sessions });
      return { ok: true, value: undefined };
    } catch (error) {
      if (isUncertainMutation(error)) {
        this.recordUncertainMutation({ kind: 'kill-session', target: name });
        if (this.isCurrentTransportFailure(error)) this.startReconnect('kill result was lost');
        else await this.reconcileUncertainMutation();
        return {
          ok: false,
          reason: 'failed',
          message: `The host may have stopped session “${name}”, but the result was lost. Refresh sessions before deciding what to do next.`,
        };
      }
      const message = error instanceof Error ? error.message : String(error);
      return { ok: false, reason: 'failed', message };
    }
  }

  async switchSession(session: SessionRow): Promise<ConnectionActionResult<SessionRow>> {
    const hostCli = this.hostCli;
    const connection = this.connection;
    if (!hostCli || !connection || !this.host) {
      return { ok: false, reason: 'not-connected', message: 'Connect to a host before attaching a session.' };
    }
    const selected = this.snapshot.sessions.find((row) => sameSession(row, session));
    if (!selected) return { ok: false, reason: 'not-found', message: `Session “${session.name}” is no longer in the host list.` };
    if (this.pty && this.snapshot.phase === 'live' && this.snapshot.selectedSession && sameSession(this.snapshot.selectedSession, selected)) {
      return { ok: true, value: selected };
    }

    const intent = ++this.selectionToken;
    this.setSnapshot({ phase: 'attaching', error: null, selectedSession: selected });
    await this.closeCurrentPty();
    if (intent !== this.selectionToken || connection !== this.connection) {
      return { ok: false, reason: 'failed', message: 'Session selection was superseded.' };
    }

    try {
      const openRequestId = this.createId();
      const opened = await this.capability.openPty({
        ...connection,
        requestId: openRequestId,
        command: hostCli.buildAttachCommand(selected.name),
        cols: 80,
        rows: 24,
        term: 'xterm-256color',
      });
      if (opened.requestId !== openRequestId || opened.generationId !== connection.generationId || intent !== this.selectionToken) {
        await this.capability.closePty({ ...opened, requestId: this.createId() });
        return { ok: false, reason: 'failed', message: 'A stale session attach completed after a newer selection.' };
      }
      this.pty = { connectionId: opened.connectionId, generationId: opened.generationId, channelId: opened.channelId };
      this.ptyReadSequence = 0;
      this.ptyOperationSequence = 0;
      this.ptyOperationQueue = Promise.resolve();
      this.setSnapshot({ phase: 'live', selectedSession: selected, error: null, retryAttempt: 0 });
      this.startPtyPump(this.pty, selected, intent);
      return { ok: true, value: selected };
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      this.setSnapshot({ phase: 'error', error: message });
      if (this.isCurrentTransportFailure(error)) this.startReconnect('PTY attach failed after transport loss');
      return { ok: false, reason: 'failed', message };
    }
  }

  async enterBackground(graceMs: number): Promise<void> {
    this.assertLive();
    if (!this.connection) return;
    const grace = Math.max(0, Math.min(graceMs, 5 * 60_000));
    const deadlineEpochMs = this.now() + grace;
    this.graceDeadlineEpochMs = deadlineEpochMs;
    const requestId = this.createId();
    const result = await this.capability.scheduleClose({
      ...this.connection,
      requestId,
      deadlineEpochMs,
    });
    if (result.requestId !== requestId) throw new Error('SSH grace schedule returned a stale request.');
    this.setSnapshot({ phase: 'background', error: null });
  }

  async returnToForeground(): Promise<void> {
    this.assertLive();
    const connection = this.connection;
    const host = this.host;
    const deadline = this.graceDeadlineEpochMs;
    this.graceDeadlineEpochMs = null;
    if (!host || deadline === null) return;
    if (!connection || this.now() >= deadline) {
      await this.reconnectAndAttach('background grace expired');
      return;
    }

    try {
      const cancelRequestId = this.createId();
      const cancelled = await this.capability.cancelScheduledClose({ ...connection, requestId: cancelRequestId });
      if (cancelled.requestId !== cancelRequestId) throw new Error('SSH grace cancel returned a stale request.');
      const stateRequestId = this.createId();
      const status = await this.capability.getConnectionState({ ...connection, requestId: stateRequestId });
      if (status.requestId !== stateRequestId) throw new Error('SSH state returned a stale request.');
      if (cancelled.cancelled && status.state === 'connected') {
        this.setSnapshot({ phase: this.pty ? 'live' : 'connected', error: null, retryAttempt: 0 });
        return;
      }
    } catch {
      // A deadline racing resume or a dead connection is handled by the same
      // TypeScript reconnect path below.
    }
    await this.reconnectAndAttach('connection was spent during background grace');
  }

  async close(): Promise<void> {
    if (this.disposed) return;
    this.disposed = true;
    this.selectionToken += 1;
    await this.closeCurrentPty();
    await this.closeCurrentTransport();
    this.host = null;
    this.graceDeadlineEpochMs = null;
    this.setSnapshot({
      phase: 'idle',
      hostId: null,
      hostLabel: null,
      connectionId: null,
      generationId: null,
      sessions: [],
      selectedSession: null,
      retryAttempt: 0,
      error: null,
      trustDecision: null,
      uncertainMutation: null,
    });
    await this.removeNativeListener().catch(() => undefined);
  }

  async writeTerminalBytes(bytes: Uint8Array): Promise<ConnectionActionResult<{ sequence: number }>> {
    return this.withPtyOperation(async () => {
      const pty = this.pty;
      if (!pty || this.snapshot.phase !== 'live') {
        return { ok: false, reason: 'not-connected', message: 'Attach a session before sending terminal input.' };
      }
      const sequence = ++this.ptyOperationSequence;
      const requestId = this.createId();
      try {
        const result = await this.capability.writePty({
          ...pty,
          requestId,
          sequence,
          dataBase64: bytesToBase64(bytes),
        });
        if (result.requestId !== requestId || result.sequence !== sequence || result.channelId !== pty.channelId) {
          throw new Error('PTY write returned a stale operation.');
        }
        return { ok: true, value: { sequence } };
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        await this.abandonPty(pty);
        this.setSnapshot({ phase: 'error', error: message });
        if (this.isCurrentTransportFailure(error) || readSshError(error).code === 'OPERATION_UNCERTAIN') {
          this.startReconnect('terminal input result was uncertain');
        }
        return { ok: false, reason: 'failed', message };
      }
    });
  }

  async resizeTerminal(cols: number, rows: number): Promise<ConnectionActionResult<{ sequence: number }>> {
    return this.withPtyOperation(async () => {
      const pty = this.pty;
      if (!pty || this.snapshot.phase !== 'live') {
        return { ok: false, reason: 'not-connected', message: 'Attach a session before resizing the terminal.' };
      }
      if (!Number.isInteger(cols) || !Number.isInteger(rows) || cols < 1 || rows < 1 || cols > 1000 || rows > 1000) {
        return { ok: false, reason: 'failed', message: 'Terminal dimensions must be between 1 and 1000.' };
      }
      const sequence = ++this.ptyOperationSequence;
      const requestId = this.createId();
      try {
        const result = await this.capability.resizePty({ ...pty, requestId, sequence, cols, rows });
        if (result.requestId !== requestId || result.sequence !== sequence || result.channelId !== pty.channelId) {
          throw new Error('PTY resize returned a stale operation.');
        }
        return { ok: true, value: { sequence } };
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        await this.abandonPty(pty);
        this.setSnapshot({ phase: 'error', error: message });
        if (this.isCurrentTransportFailure(error)) this.startReconnect('terminal resize observed a transport failure');
        return { ok: false, reason: 'failed', message };
      }
    });
  }

  async removeNativeListener(): Promise<void> {
    const handle = await this.listenerReady;
    await handle.remove();
  }

  async getNativeResourceSnapshot(): Promise<SshResourceSnapshot> {
    const requestId = this.createId();
    const result = await this.capability.resourceSnapshot(requestId);
    if (result.requestId !== requestId) throw new Error('SSH resource snapshot returned a stale request.');
    return result;
  }

  clearUncertainMutation(): void {
    this.setSnapshot({ uncertainMutation: null });
  }

  private async dial(host: SshHostTarget): Promise<ConnectionActionResult<SshConnectionRef>> {
    const generationId = this.createId();
    const requestId = this.createId();
    let expectedHostKey: HostKeyPin | null = null;
    this.setSnapshot({ phase: 'connecting', generationId, error: null });
    try {
      expectedHostKey = await this.trustStore.get(host.hostId);
      const connected = await this.capability.connect({
        ...host,
        requestId,
        generationId,
        expectedHostKey,
        connectTimeoutMs: DEFAULT_CONNECT_TIMEOUT_MS,
      });
      if (connected.requestId !== requestId || connected.generationId !== generationId) {
        await this.capability.closeConnection({
          connectionId: connected.connectionId,
          generationId: connected.generationId,
          requestId: this.createId(),
        });
        return { ok: false, reason: 'failed', message: 'SSH connect returned a stale generation.' };
      }
      this.connection = { connectionId: connected.connectionId, generationId };
      this.hostCli = new HostCliCore(this.createHostCliTransport(this.connection));
      this.setSnapshot({
        phase: 'connected',
        connectionId: connected.connectionId,
        generationId,
        trustDecision: null,
        error: null,
      });
      return { ok: true, value: this.connection };
    } catch (error) {
      const sshError = readSshError(error);
      this.lastDialRetryable = isRetryableDialError(error);
      const presented = this.readPresentedKey(sshError.data);
      if (sshError.code === 'HOST_KEY_REJECTED' && presented) {
        const verdict = verifyHostKeyPin(expectedHostKey ?? undefined, presented.keyType, presented.keyB64);
        const reason = verdict === 'mismatch' ? 'mismatch' : 'unknown';
        this.setSnapshot({
          phase: 'awaiting-trust',
          generationId,
          trustDecision: {
            hostId: host.hostId,
            hostLabel: host.hostname,
            reason,
            presented,
            previouslyTrusted: expectedHostKey,
          },
          error: reason === 'unknown'
            ? `First connection to ${host.hostname}; verify ${presented.fingerprintSha256} before trusting it.`
            : `The host key for ${host.hostname} changed. The new fingerprint is ${presented.fingerprintSha256}.`,
        });
        return {
          ok: false,
          reason: reason === 'mismatch' ? 'trust-mismatch' : 'trust-required',
          message: this.snapshot.error ?? 'Host key requires a decision.',
        };
      }
      const message = sshError.message;
      this.setSnapshot({ phase: 'error', error: message, generationId, trustDecision: null });
      return { ok: false, reason: 'failed', message };
    }
  }

  private createHostCliTransport(connection: SshConnectionRef): HostCliTransport {
    return {
      exec: async (command, timeoutMs) => {
        const requestId = this.createId();
        const result = await this.capability.exec({
          ...connection,
          requestId,
          command,
          timeoutMs,
        });
        if (result.requestId !== requestId || result.generationId !== connection.generationId) {
          throw new Error('SSH exec returned a stale request or connection generation.');
        }
        return {
          exitCode: result.exitCode,
          stdout: result.stdout,
          stderr: result.stderr,
          timedOut: result.timedOut,
        };
      },
    };
  }

  private async startPtyPump(pty: SshPtyRef, session: SessionRow, selection: number): Promise<void> {
    const pumpToken = ++this.ptyPumpToken;
    try {
      while (
        !this.disposed &&
        pumpToken === this.ptyPumpToken &&
        selection === this.selectionToken &&
        this.pty?.channelId === pty.channelId
      ) {
        const requestId = this.createId();
        const requestedSequence = this.ptyReadSequence;
        const result = await this.capability.readPty({
          ...pty,
          requestId,
          sequence: requestedSequence,
          maxBytes: PTY_READ_MAX_BYTES,
          waitMs: PTY_READ_WAIT_MS,
        });
        if (pumpToken !== this.ptyPumpToken || selection !== this.selectionToken) return;
        if (result.requestId !== requestId || result.generationId !== pty.generationId || result.channelId !== pty.channelId) {
          throw new Error('PTY read returned a stale request or generation.');
        }
        if (result.sequence < this.ptyReadSequence || result.sequence > this.ptyReadSequence + 1) {
          throw new Error(`PTY output sequence gap: expected ${this.ptyReadSequence} or ${this.ptyReadSequence + 1}, received ${result.sequence}.`);
        }
        if (result.sequence === this.ptyReadSequence && result.dataBase64.length > 0) {
          throw new Error('PTY output changed bytes without advancing its sequence.');
        }
        if (result.sequence === this.ptyReadSequence + 1 && result.dataBase64.length === 0) {
          throw new Error('PTY output advanced its sequence without returning bytes.');
        }
        if (result.sequence === this.ptyReadSequence + 1) {
          const bytes = base64ToBytes(result.dataBase64);
          for (const listener of this.outputListeners) await listener(session, bytes, pty.generationId);
          this.ptyReadSequence = result.sequence;
        }
        if (result.eof) {
          if (selection === this.selectionToken) {
            this.pty = null;
            this.setSnapshot({ phase: 'connected', error: `Session “${session.name}” ended.` });
            await this.capability.closePty({ ...pty, requestId: this.createId() }).catch(() => undefined);
          }
          return;
        }
      }
    } catch (error) {
      if (pumpToken !== this.ptyPumpToken || this.disposed) return;
      const message = error instanceof Error ? error.message : String(error);
      this.setSnapshot({ phase: 'error', error: message });
      if (this.isCurrentTransportFailure(error)) this.startReconnect('PTY output reader observed a transport failure');
    }
  }

  private async reconnectAndAttach(reason: string): Promise<void> {
    const task = this.reconnectTask;
    if (task) return task;
    const host = this.host;
    const selected = this.snapshot.selectedSession;
    if (!host) return;
    this.reconnectTask = this.runReconnect(host, selected, reason).finally(() => {
      this.reconnectTask = null;
    });
    return this.reconnectTask;
  }

  private startReconnect(reason: string): void {
    if (this.snapshot.phase === 'background' || this.disposed) return;
    void this.reconnectAndAttach(reason);
  }

  private async runReconnect(host: SshHostTarget, selected: SessionRow | null, reason: string): Promise<void> {
    const oldPty = this.pty;
    const oldConnection = this.connection;
    this.pty = null;
    this.connection = null;
    this.hostCli = null;
    this.ptyPumpToken += 1;
    this.setSnapshot({ phase: 'reconnecting', retryAttempt: 0, error: reason, connectionId: null });
    if (oldPty) await this.capability.closePty({ ...oldPty, requestId: this.createId() }).catch(() => undefined);
    if (oldConnection) await this.capability.closeConnection({ ...oldConnection, requestId: this.createId() }).catch(() => undefined);

    for (let attempt = 0; attempt < this.retryDelaysMs.length; attempt += 1) {
      if (attempt > 0) await this.delay(this.retryDelaysMs[attempt] ?? 0);
      if (this.disposed) return;
      this.setSnapshot({ phase: 'reconnecting', retryAttempt: attempt + 1, error: reason });
      const connected = await this.dial(host);
      if (!connected.ok) {
        if (connected.reason === 'trust-required' || connected.reason === 'trust-mismatch') return;
        if (!this.lastDialRetryable) break;
        continue;
      }
      const listing = await this.refreshSessions();
      if (!listing.ok) {
        if (this.snapshot.phase === 'reconnecting') continue;
        return;
      }
      if (selected) {
        const current = listing.value.sessions.find((row) => sameSession(row, selected));
        if (!current) {
          this.setSnapshot({ phase: 'lost', error: `Session “${selected.name}” no longer exists on ${host.hostname}.` });
          return;
        }
        const attached = await this.attachListedSession(current);
        if (attached.ok) return;
        if (!this.isCurrentTransportFailure(attached.message)) return;
      } else {
        this.setSnapshot({ phase: 'connected', selectedSession: null, error: null, retryAttempt: 0 });
        return;
      }
    }
    this.setSnapshot({ phase: 'lost', error: `Could not reconnect to ${host.hostname} after ${this.retryDelaysMs.length} attempts.` });
  }

  private async attachListedSession(session: SessionRow): Promise<ConnectionActionResult<SessionRow>> {
    const connection = this.connection;
    const hostCli = this.hostCli;
    if (!connection || !hostCli) return { ok: false, reason: 'not-connected', message: 'SSH transport is not connected.' };
    const intent = ++this.selectionToken;
    this.setSnapshot({ phase: 'attaching', selectedSession: session, error: null });
    try {
      const openRequestId = this.createId();
      const opened = await this.capability.openPty({
        ...connection,
        requestId: openRequestId,
        command: hostCli.buildAttachCommand(session.name),
        cols: 80,
        rows: 24,
        term: 'xterm-256color',
      });
      if (opened.requestId !== openRequestId || intent !== this.selectionToken || opened.generationId !== connection.generationId) {
        await this.capability.closePty({ ...opened, requestId: this.createId() });
        return { ok: false, reason: 'failed', message: 'Attach completed for a stale session generation.' };
      }
      this.pty = { connectionId: opened.connectionId, generationId: opened.generationId, channelId: opened.channelId };
      this.ptyReadSequence = 0;
      this.ptyOperationSequence = 0;
      this.ptyOperationQueue = Promise.resolve();
      this.setSnapshot({ phase: 'live', selectedSession: session, error: null, retryAttempt: 0 });
      this.startPtyPump(this.pty, session, intent);
      return { ok: true, value: session };
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      this.setSnapshot({ phase: 'error', error: message });
      return { ok: false, reason: 'failed', message };
    }
  }

  private async closeCurrentPty(): Promise<void> {
    const pty = this.pty;
    this.pty = null;
    this.ptyPumpToken += 1;
    if (!pty) return;
    const requestId = this.createId();
    await this.capability.closePty({ ...pty, requestId }).then((result) => {
      if (result.requestId !== requestId) throw new Error('PTY close returned a stale request.');
    }).catch(() => undefined);
  }

  private async abandonPty(pty: SshPtyRef): Promise<void> {
    if (this.pty?.channelId !== pty.channelId) return;
    this.pty = null;
    this.ptyPumpToken += 1;
    const requestId = this.createId();
    await this.capability.closePty({ ...pty, requestId }).catch(() => undefined);
  }

  private async closeCurrentTransport(): Promise<void> {
    const connection = this.connection;
    this.connection = null;
    this.hostCli = null;
    if (!connection) return;
    const requestId = this.createId();
    await this.capability.closeConnection({ ...connection, requestId }).then((result) => {
      if (result.requestId !== requestId) throw new Error('SSH close returned a stale request.');
    }).catch(() => undefined);
  }

  private onConnectionState(event: SshConnectionStateEvent): void {
    const current = this.connection;
    if (!current || current.connectionId !== event.connectionId || current.generationId !== event.generationId) return;
    if (event.state === 'closed' && event.reason === 'grace-expired' && this.snapshot.phase === 'background') {
      this.ptyPumpToken += 1;
      this.pty = null;
      this.connection = null;
      this.hostCli = null;
      this.setSnapshot({ phase: 'background', connectionId: null, error: null });
      return;
    }
    if (event.state === 'lost') this.startReconnect(event.reason ?? 'SSH transport lost');
  }

  private async reconcileUncertainMutation(): Promise<void> {
    const refreshed = await this.refreshSessions();
    if (!refreshed.ok) return;
    this.reconcilePendingMutation(refreshed.value);
  }

  private reconcilePendingMutation(listing: SessionsListing): void {
    const mutation = this.snapshot.uncertainMutation;
    if (!mutation || listing.errors.length > 0) return;
    const exists = listing.sessions.some((session) => session.name === mutation.target);
    const applied = mutation.kind === 'create-session' ? exists : !exists;
    if (applied) {
      this.setSnapshot({ uncertainMutation: { ...mutation, state: 'observed-applied' } });
    }
  }

  private recordUncertainMutation(mutation: Pick<UncertainMutation, 'kind' | 'target'>): void {
    this.setSnapshot({
      uncertainMutation: {
        ...mutation,
        state: 'unknown',
        recordedAt: this.now(),
      },
    });
  }

  private readPresentedKey(data: Record<string, unknown>): PendingHostKeyDecision['presented'] | null {
    const keyType = data.keyType;
    const keyB64 = data.keyB64;
    const fingerprintSha256 = data.fingerprintSha256;
    if (typeof keyType !== 'string' || typeof keyB64 !== 'string' || typeof fingerprintSha256 !== 'string') return null;
    return { keyType, keyB64, fingerprintSha256 };
  }

  private isCurrentTransportFailure(error: unknown): boolean {
    if (typeof error === 'string') return /connection|transport|closed|eof|socket|ssh/i.test(error);
    const nativeError = readSshError(error);
    return ['CONNECTION_LOST', 'CONNECTION_CLOSED', 'CHANNEL_CLOSED', 'SSH_IO'].includes(nativeError.code);
  }

  private setSnapshot(patch: Partial<Omit<ConnectionSnapshot, 'revision'>>): void {
    this.snapshot = { ...this.snapshot, ...patch, revision: this.snapshot.revision + 1 };
    const value = this.getSnapshot();
    for (const listener of this.listeners) listener(value);
  }

  private withPtyOperation<T>(operation: () => Promise<T>): Promise<T> {
    const run = this.ptyOperationQueue.then(operation, operation);
    this.ptyOperationQueue = run.then(() => undefined, () => undefined);
    return run;
  }

  private assertLive(): void {
    if (this.disposed) throw new Error('ConnectionController is closed.');
  }
}
