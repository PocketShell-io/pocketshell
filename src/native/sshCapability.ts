import { registerPlugin, type Plugin } from '@capacitor/core';
import {
  readSshCapabilityError,
  type SshCapability,
  type SshResourceSnapshot,
} from '@pocketshell/core';

export type {
  SshAck,
  SshConnectOptions,
  SshConnectResult,
  SshConnectionRef,
  SshConnectionStateEvent,
  SshCancellationOptions,
  SshCancellationResult,
  SshCancellationTarget,
  SshCredential,
  SshExecOptions,
  SshExecResult,
  SshHostTarget,
  SshPtyOpenOptions,
  SshPtyReadOptions,
  SshPtyReadResult,
  SshPtyRef,
  SshPtyResizeOptions,
  SshPtyWriteOptions,
  SshPortForwardOptions,
  SshPortForwardRef,
  SshResourceSnapshot,
  SshSftpEntry,
  SshSftpOptions,
  SshSftpWriteOptions,
  HostKeyTrustPin,
  PresentedHostKey,
} from '@pocketshell/core';
export { SshCapabilityError } from '@pocketshell/core';

export interface NativeSftpExpectedMetadata {
  isDirectory: false;
  sizeBytes: number;
  modifiedEpochMs: number;
}

export interface NativeSftpWriteIfUnchangedOptions {
  requestId: string;
  connectionId: string;
  generationId: string;
  rootPath: string;
  path: string;
  expectedMetadata: NativeSftpExpectedMetadata;
  dataBase64: string;
}

export interface NativeSftpWriteIfUnchangedResult {
  requestId: string;
  status: 'written' | 'conflict';
  verdict?: 'missing' | 'changed';
  bytesWritten?: number;
}

/** Capacitor registration for the platform-neutral core effects contract. */
export type SshCapabilityPlugin = Plugin & SshCapability;

/** Capacitor plugin method arguments must be objects; the core contract uses a request ID. */
export type NativeSshCapabilityPlugin = Plugin & {
  resourceSnapshot(options: { requestId: string }): Promise<unknown>;
  sftpWriteIfUnchanged(
    options: NativeSftpWriteIfUnchangedOptions,
  ): Promise<NativeSftpWriteIfUnchangedResult>;
};

function isResourceSnapshot(value: unknown, requestId: string): value is SshResourceSnapshot {
  if (typeof value !== 'object' || value === null) return false;
  const snapshot = value as Record<string, unknown>;
  if (snapshot.requestId !== requestId) return false;
  return ['connections', 'ptys', 'sftpClients', 'forwards'].every((key) => {
    const count = snapshot[key];
    return typeof count === 'number' && Number.isSafeInteger(count) && count >= 0;
  });
}

/**
 * SshCapabilityPlugin.MAX_CHANNELS_PER_CONNECTION: the plugin refuses a
 * channel past this many per connection (PTYs, execs and forwards share it).
 * Stated to core so the controller bounds its PTYs below it (#2955).
 */
export const NATIVE_MAX_CHANNELS_PER_CONNECTION = 8;

/** Adapt the core's string request ID to Capacitor's one-object plugin bridge. */
export function adaptSshCapabilityPlugin(plugin: NativeSshCapabilityPlugin): SshCapabilityPlugin {
  return new Proxy(plugin, {
    get(target, property) {
      // A plain value, answered here: Capacitor's plugin proxy would turn any
      // unknown property into a native method call.
      if (property === 'maxChannelsPerConnection') return NATIVE_MAX_CHANNELS_PER_CONNECTION;
      if (property === 'resourceSnapshot') {
        return async (requestId: string): Promise<SshResourceSnapshot> => {
          const call = Reflect.get(target, property, target) as NativeSshCapabilityPlugin['resourceSnapshot'];
          const snapshot = await call.call(target, { requestId });
          if (!isResourceSnapshot(snapshot, requestId)) {
            throw new Error('The connection status reply could not be read.');
          }
          return snapshot;
        };
      }
      return Reflect.get(target, property, target);
    },
  }) as unknown as SshCapabilityPlugin;
}

/** sshj performs physical I/O; portable policy remains in pocketshell-core. */
const nativeSshCapability = registerPlugin<NativeSshCapabilityPlugin>('SshCapability');
export const sshCapability = adaptSshCapabilityPlugin(nativeSshCapability);

/** Normalize Capacitor's plain bridge error object to the core error type. */
export const readSshError = readSshCapabilityError;
