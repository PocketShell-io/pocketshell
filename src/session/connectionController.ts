import {
  ConnectionController as PortableConnectionController,
  type ConnectionControllerOptions,
} from '@pocketshell/core';
import { sshCapability } from '@/native/sshCapability';

export {
  type ConnectionActionResult,
  type ConnectionPhase,
  type ConnectionSnapshot,
  type HostKeyTrustStore,
  type PendingHostKeyDecision,
  type TerminalOutputHandler,
  type UncertainMutation,
} from '@pocketshell/core';

/** Bind the Capacitor SSH plugin to the shared connection/session policy. */
export class ConnectionController extends PortableConnectionController {
  constructor(options: Omit<ConnectionControllerOptions, 'capability'>) {
    super({ ...options, capability: sshCapability });
  }
}
