import {
  isValidTcpPort,
  planPortForwards,
  type ActivePortForward,
  type PortForwardPolicyConfig,
  type PortIntent,
} from '@pocketshell/core';
import type {
  PortScanResult,
  SshCapability,
  SshConnectionRef,
  SshPortForwardRef,
} from '@pocketshell/core';
import { LOOPBACK_HOST } from '@pocketshell/core';
import { scanRemotePorts, type PortScanOptions } from './ports';

let defaultRequestSequence = 0;

interface LiveTunnel extends ActivePortForward {
  ref: SshPortForwardRef;
  localPort: number;
}

export interface PortForwardControllerOptions extends PortScanOptions {
  createRequestId?: () => string;
  now?: () => number;
  config?: Partial<PortForwardPolicyConfig>;
  desiredManualPorts?: readonly number[];
  sshConfigPorts?: readonly number[];
}

export interface PortForwardControllerSnapshot {
  scan: PortScanResult;
  activeForwards: Array<{ remotePort: number; localPort: number; origin: LiveTunnel['origin'] }>;
  deferredPorts: number[];
  errors: Record<number, string>;
}

/**
 * Applies the core's tunnel plan through native SSH effects. It owns no local
 * sockets and keeps explicit user ports across connection-generation swaps.
 */
export class PortForwardController {
  private connection: SshConnectionRef;
  private readonly desiredManualPorts = new Set<number>();
  private readonly intents: Record<number, PortIntent> = {};
  private readonly forwards = new Map<number, LiveTunnel>();
  private readonly priorMissingScans: Record<number, number> = {};
  private readonly failedAt: Record<number, number> = {};
  private readonly errors: Record<number, string> = {};
  private autoEnabled = true;
  private operation: Promise<unknown> = Promise.resolve();
  private readonly createRequestId: () => string;
  private readonly now: () => number;

  constructor(
    private readonly capability: SshCapability,
    connection: SshConnectionRef,
    private readonly options: PortForwardControllerOptions = {},
  ) {
    this.connection = { ...connection };
    for (const port of options.desiredManualPorts ?? []) {
      if (isValidTcpPort(port)) this.desiredManualPorts.add(port);
    }
    this.createRequestId = options.createRequestId ?? (() => {
      defaultRequestSequence += 1;
      return `port-forward-${Date.now().toString(36)}-${defaultRequestSequence.toString(36)}`;
    });
    this.now = options.now ?? Date.now;
  }

  getManualDesiredPorts(): number[] {
    return [...this.desiredManualPorts].sort((a, b) => a - b);
  }

  setAutoEnabled(enabled: boolean): void {
    this.autoEnabled = enabled;
  }

  /** Store absolute manual intent; false explicitly silences the remote port. */
  setManualDesiredPort(remotePort: number, enabled: boolean): void {
    if (!isValidTcpPort(remotePort)) throw new RangeError('remotePort must be between 1 and 65535');
    if (enabled) {
      this.desiredManualPorts.add(remotePort);
      delete this.intents[remotePort];
      delete this.failedAt[remotePort];
    } else {
      this.desiredManualPorts.delete(remotePort);
      this.intents[remotePort] = 'force-off';
    }
  }

  /** Return a port to automatic eligibility after a manual override. */
  followAutomaticPolicy(remotePort: number): void {
    this.desiredManualPorts.delete(remotePort);
    delete this.intents[remotePort];
    delete this.failedAt[remotePort];
  }

  /**
   * On reconnect, release stale generation-scoped native handles and retain
   * user intent. The next scan/reconcile recreates the wanted tunnels.
   */
  setConnection(connection: SshConnectionRef): Promise<void> {
    return this.enqueue(async () => {
      if (connection.connectionId === this.connection.connectionId &&
          connection.generationId === this.connection.generationId) return;
      const oldForwards = [...this.forwards.values()];
      this.connection = { ...connection };
      this.forwards.clear();
      for (const port of Object.keys(this.priorMissingScans)) delete this.priorMissingScans[Number(port)];
      await Promise.allSettled(oldForwards.map((forward) => this.closeNative(forward)));
    });
  }

  scanAndReconcile(): Promise<PortForwardControllerSnapshot> {
    return this.enqueue(async () => {
      const scan = await scanRemotePorts(this.capability, this.connection, {
        ...this.options,
        createRequestId: this.createRequestId,
      });
      return this.applyScan(scan);
    });
  }

  /** Exposed for callers that already run the shared scanner. */
  reconcile(scan: PortScanResult): Promise<PortForwardControllerSnapshot> {
    return this.enqueue(() => this.applyScan(scan));
  }

  /** Close all currently owned native handles. Desired state is retained. */
  closeAll(): Promise<void> {
    return this.enqueue(async () => {
      const forwards = [...this.forwards.values()];
      const results = await Promise.allSettled(forwards.map((forward) => this.closeNative(forward)));
      results.forEach((result, index) => {
        if (result.status === 'fulfilled') {
          this.forwards.delete(forwards[index]!.remotePort);
          delete this.errors[forwards[index]!.remotePort];
        }
      });
    });
  }

  snapshot(scan: PortScanResult = { ok: false, ports: [], error: null }, deferredPorts: number[] = []): PortForwardControllerSnapshot {
    return {
      scan,
      activeForwards: [...this.forwards.values()]
        .map(({ remotePort, localPort, origin }) => ({ remotePort, localPort, origin }))
        .sort((a, b) => a.remotePort - b.remotePort),
      deferredPorts: [...deferredPorts],
      errors: { ...this.errors },
    };
  }

  private async applyScan(scan: PortScanResult): Promise<PortForwardControllerSnapshot> {
    const activeForwards: ActivePortForward[] = [...this.forwards.values()]
      .map(({ remotePort, origin }) => ({ remotePort, origin }));
    const plan = planPortForwards({
      scan,
      activeForwards,
      desiredManualPorts: this.getManualDesiredPorts(),
      intents: this.intents,
      autoEnabled: this.autoEnabled,
      priorMissingScans: this.priorMissingScans,
      failedAt: this.failedAt,
      nowMs: this.now(),
      config: this.options.config,
      sshConfigPorts: this.options.sshConfigPorts,
    });

    for (const port of plan.closePorts) {
      const live = this.forwards.get(port);
      if (!live) continue;
      try {
        await this.closeNative(live);
        this.forwards.delete(port);
        delete this.errors[port];
        delete this.failedAt[port];
      } catch (error) {
        this.errors[port] = errorMessage(error);
      }
    }
    for (const port of Object.keys(this.priorMissingScans)) delete this.priorMissingScans[Number(port)];
    Object.assign(this.priorMissingScans, plan.missingScans);

    const deferred = [...plan.deferredPorts];
    for (const port of plan.openPorts) {
      if (this.forwards.size >= Math.min(this.options.config?.maxActiveForwards ?? 8, 8)) {
        deferred.push(port);
        continue;
      }
      const requestId = this.createRequestId();
      try {
        const opened = await this.capability.openPortForward({
          ...this.connection,
          requestId,
          remoteHost: LOOPBACK_HOST,
          remotePort: port,
          // Let the native server socket choose a free local port. JS decides
          // what to forward; Android owns socket binding and allocation.
        });
        if (opened.requestId !== requestId || opened.connectionId !== this.connection.connectionId ||
            opened.generationId !== this.connection.generationId || typeof opened.forwardId !== 'string' ||
            !opened.forwardId || !isValidTcpPort(opened.localPort)) {
          throw new Error('Native port forward returned an invalid request, connection, or local port.');
        }
        const manual = this.desiredManualPorts.has(port) || this.intents[port] === 'force-on';
        this.forwards.set(port, {
          remotePort: port,
          localPort: opened.localPort,
          origin: manual ? 'manual' : 'auto',
          ref: {
            forwardId: opened.forwardId,
            connectionId: opened.connectionId,
            generationId: opened.generationId,
            localPort: opened.localPort,
          },
        });
        delete this.failedAt[port];
        delete this.errors[port];
      } catch (error) {
        this.failedAt[port] = this.now();
        this.errors[port] = errorMessage(error);
      }
    }
    return this.snapshot(scan, deferred);
  }

  private async closeNative(forward: LiveTunnel): Promise<void> {
    const requestId = this.createRequestId();
    const ack = await this.capability.closePortForward({ ...forward.ref, requestId });
    if (ack.requestId !== requestId) throw new Error('Native port-forward close returned a mismatched request ID.');
  }

  private enqueue<T>(work: () => Promise<T>): Promise<T> {
    const result = this.operation.then(work, work);
    this.operation = result.then(() => undefined, () => undefined);
    return result;
  }
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
