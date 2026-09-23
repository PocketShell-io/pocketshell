import { registerPlugin, type Plugin, type PluginListenerHandle } from '@capacitor/core';
import type { HostKeyPin } from '@pocketshell/core';

export type SshCredential =
  | { kind: 'private-key'; privateKeyPem: string; passphrase?: string | null }
  | { kind: 'password'; password: string };

export interface SshHostTarget {
  hostId: string;
  hostname: string;
  port: number;
  username: string;
  credential: SshCredential;
}

export interface SshConnectionRef {
  connectionId: string;
  generationId: string;
}

export interface SshConnectionStateEvent extends SshConnectionRef {
  state: 'lost' | 'closed';
  reason: string | null;
}

export interface SshAck {
  requestId: string;
}

export interface SshResourceSnapshot extends SshAck {
  connections: number;
  ptys: number;
  sftpClients: number;
  forwards: number;
}

export interface SshConnectOptions extends SshHostTarget {
  requestId: string;
  generationId: string;
  expectedHostKey: HostKeyPin | null;
  connectTimeoutMs?: number;
}

export interface SshConnectResult extends SshConnectionRef {
  requestId: string;
  hostKey: HostKeyPin & { fingerprintSha256: string };
}

export interface SshExecOptions extends SshConnectionRef {
  requestId: string;
  command: string;
  timeoutMs: number;
}

export interface SshExecResult extends SshConnectionRef {
  requestId: string;
  exitCode: number | null;
  stdout: string;
  stderr: string;
  timedOut: boolean;
}

export interface SshPtyOpenOptions extends SshConnectionRef {
  requestId: string;
  command: string;
  cols: number;
  rows: number;
  term?: string;
}

export interface SshPtyRef extends SshConnectionRef {
  channelId: string;
}

export interface SshPtyReadOptions extends SshPtyRef {
  requestId: string;
  sequence: number;
  maxBytes?: number;
  waitMs?: number;
}

export interface SshPtyReadResult extends SshPtyRef {
  requestId: string;
  sequence: number;
  dataBase64: string;
  eof: boolean;
}

export interface SshPtyOperationOptions extends SshPtyRef {
  requestId: string;
  sequence: number;
}

export interface SshPtyWriteOptions extends SshPtyOperationOptions {
  dataBase64: string;
}

export interface SshPtyResizeOptions extends SshPtyOperationOptions {
  cols: number;
  rows: number;
}

export interface SshSftpEntry {
  path: string;
  name: string;
  isDirectory: boolean;
  sizeBytes: number;
  modifiedEpochMs: number;
}

export interface SshSftpOptions extends SshConnectionRef {
  requestId: string;
  path: string;
}

export interface SshSftpWriteOptions extends SshSftpOptions {
  dataBase64: string;
}

export interface SshPortForwardOptions extends SshConnectionRef {
  requestId: string;
  remoteHost: string;
  remotePort: number;
  localPort?: number;
}

export interface SshPortForwardRef extends SshConnectionRef {
  forwardId: string;
  localPort: number;
}

export interface SshCapabilityPlugin extends Plugin {
  addListener(
    eventName: 'connectionState',
    listenerFunc: (event: SshConnectionStateEvent) => void,
  ): Promise<PluginListenerHandle>;
  connect(options: SshConnectOptions): Promise<SshConnectResult>;
  getConnectionState(ref: SshConnectionRef & { requestId: string }): Promise<SshAck & { state: 'connected' | 'lost' | 'closed' }>;
  closeConnection(ref: SshConnectionRef & { requestId: string }): Promise<SshAck>;
  scheduleClose(ref: SshConnectionRef & { requestId: string; deadlineEpochMs: number }): Promise<SshAck>;
  cancelScheduledClose(ref: SshConnectionRef & { requestId: string }): Promise<SshAck & { cancelled: boolean }>;
  exec(options: SshExecOptions): Promise<SshExecResult>;
  openPty(options: SshPtyOpenOptions): Promise<SshPtyRef & { requestId: string }>;
  readPty(options: SshPtyReadOptions): Promise<SshPtyReadResult>;
  writePty(options: SshPtyWriteOptions): Promise<SshPtyOperationOptions>;
  resizePty(options: SshPtyResizeOptions): Promise<SshPtyOperationOptions>;
  closePty(options: SshPtyRef & { requestId: string }): Promise<SshAck>;
  sftpList(options: SshSftpOptions): Promise<{ requestId: string; entries: SshSftpEntry[] }>;
  sftpRead(options: SshSftpOptions & { maxBytes: number }): Promise<{ requestId: string; dataBase64: string }>;
  sftpWrite(options: SshSftpWriteOptions): Promise<{ requestId: string; bytesWritten: number }>;
  sftpMkdir(options: SshSftpOptions): Promise<{ requestId: string }>;
  sftpRename(options: SshSftpOptions & { destination: string }): Promise<{ requestId: string }>;
  sftpDelete(options: SshSftpOptions): Promise<{ requestId: string }>;
  openPortForward(options: SshPortForwardOptions): Promise<SshPortForwardRef & { requestId: string }>;
  closePortForward(options: SshPortForwardRef & { requestId: string }): Promise<SshAck>;
  resourceSnapshot(requestId: string): Promise<SshResourceSnapshot>;
}

export class SshCapabilityError extends Error {
  readonly code: string;
  readonly data: Record<string, unknown>;

  constructor(message: string, code = 'SSH_ERROR', data: Record<string, unknown> = {}) {
    super(message);
    this.name = 'SshCapabilityError';
    this.code = code;
    this.data = data;
  }
}

/** Native sshj I/O only. Session and retry decisions stay in TypeScript. */
export const sshCapability = registerPlugin<SshCapabilityPlugin>('SshCapability');

export function readSshError(error: unknown): SshCapabilityError {
  if (error instanceof SshCapabilityError) return error;
  if (typeof error === 'object' && error !== null) {
    const candidate = error as {
      message?: unknown;
      code?: unknown;
      data?: unknown;
    };
    const data = typeof candidate.data === 'object' && candidate.data !== null
      ? candidate.data as Record<string, unknown>
      : {};
    return new SshCapabilityError(
      typeof candidate.message === 'string' ? candidate.message : 'SSH operation failed',
      typeof candidate.code === 'string' ? candidate.code : 'SSH_ERROR',
      data,
    );
  }
  return new SshCapabilityError(String(error));
}
