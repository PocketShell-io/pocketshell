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
  /** What this build's native `connect()` can dial beyond direct TCP (#3086). */
  transportCapabilities(options: { requestId: string }): Promise<unknown>;
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

/**
 * The transports the NATIVE plugin reported it can dial (#3086). Core reads
 * `SshCapability.gatewayTransport` at dial time; it is answered from here,
 * which only {@link loadNativeTransportCapabilities} sets, from the native
 * plugin's own reply. False until then and after any unreadable reply, so a
 * build or a bridge that does not report the gateway transport can never be
 * treated as having it. (Android still refuses gateway hosts at its platform
 * boundary, `unsupportedTransportMessage`, until #3086 slice 3 lifts that.)
 */
export interface NativeTransportCapabilities {
  gatewayTransport: boolean;
}

/** Adapt the core's string request ID to Capacitor's one-object plugin bridge. */
export function adaptSshCapabilityPlugin(
  plugin: NativeSshCapabilityPlugin,
  transports: NativeTransportCapabilities = { gatewayTransport: false },
): SshCapabilityPlugin {
  return new Proxy(plugin, {
    get(target, property) {
      // Plain values, answered here: Capacitor's plugin proxy would turn any
      // unknown property into a native method call.
      if (property === 'maxChannelsPerConnection') return NATIVE_MAX_CHANNELS_PER_CONNECTION;
      if (property === 'gatewayTransport') return transports.gatewayTransport;
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

function isTransportCapabilitiesReply(value: unknown, requestId: string): value is { gatewayTransport: boolean } {
  if (typeof value !== 'object' || value === null) return false;
  const reply = value as Record<string, unknown>;
  return reply.requestId === requestId && typeof reply.gatewayTransport === 'boolean';
}

let transportProbeSequence = 0;

/**
 * Ask the native plugin which transports it can dial and record the answer
 * in `transports`. Fails closed: a rejected call, a missing method (an older
 * native build, the browser dev bridge) or a malformed reply records false.
 */
export async function loadNativeTransportCapabilities(
  plugin: NativeSshCapabilityPlugin,
  transports: NativeTransportCapabilities,
): Promise<NativeTransportCapabilities> {
  transportProbeSequence += 1;
  const requestId = `transport-capabilities-${transportProbeSequence}`;
  let gatewayTransport = false;
  try {
    const reply = await plugin.transportCapabilities({ requestId });
    gatewayTransport = isTransportCapabilitiesReply(reply, requestId) && reply.gatewayTransport === true;
  } catch {
    gatewayTransport = false;
  }
  transports.gatewayTransport = gatewayTransport;
  return transports;
}

/** sshj performs physical I/O; portable policy remains in pocketshell-core. */
const nativeSshCapability = registerPlugin<NativeSshCapabilityPlugin>('SshCapability');
/** What the installed native plugin reported (see {@link loadNativeTransportCapabilities}). */
export const sshTransportCapabilities: NativeTransportCapabilities = { gatewayTransport: false };
export const sshCapability = adaptSshCapabilityPlugin(nativeSshCapability, sshTransportCapabilities);
/** Read the installed native plugin's transports into {@link sshCapability}. */
export const loadSshTransportCapabilities = () =>
  loadNativeTransportCapabilities(nativeSshCapability, sshTransportCapabilities);

/** Normalize Capacitor's plain bridge error object to the core error type. */
export const readSshError = readSshCapabilityError;
