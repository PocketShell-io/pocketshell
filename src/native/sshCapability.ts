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

/** Capacitor registration for the platform-neutral core effects contract. */
export type SshCapabilityPlugin = Plugin & SshCapability;

/** Capacitor plugin method arguments must be objects; the core contract uses a request ID. */
export type NativeSshCapabilityPlugin = Plugin & {
  resourceSnapshot(options: { requestId: string }): Promise<unknown>;
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

/** Adapt the core's string request ID to Capacitor's one-object plugin bridge. */
export function adaptSshCapabilityPlugin(plugin: NativeSshCapabilityPlugin): SshCapabilityPlugin {
  return new Proxy(plugin, {
    get(target, property) {
      if (property === 'resourceSnapshot') {
        return async (requestId: string): Promise<SshResourceSnapshot> => {
          const call = Reflect.get(target, property, target) as NativeSshCapabilityPlugin['resourceSnapshot'];
          const snapshot = await call.call(target, { requestId });
          if (!isResourceSnapshot(snapshot, requestId)) {
            throw new Error('Native resourceSnapshot returned an invalid request ID or resource count.');
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
