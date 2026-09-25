import {
  parsePortScanResult,
  parseProcCwds,
  PORT_LISTENER_SCAN_COMMAND,
  procCwdCommand,
  type PortScanResult,
  type RemotePort,
  type SshExecResult,
} from '@pocketshell/core';
import type { SshCapability, SshConnectionRef } from '@pocketshell/core';

export const PORT_SCAN_TIMEOUT_MS = 15_000;

export interface PortScanOptions {
  createRequestId?: () => string;
  timeoutMs?: number;
}

let requestSequence = 0;
function defaultRequestId(): string {
  requestSequence += 1;
  return `ports-${Date.now().toString(36)}-${requestSequence.toString(36)}`;
}

function validResponse(
  result: { requestId: string; connectionId: string; generationId: string },
  requestId: string,
  connection: SshConnectionRef,
): boolean {
  return result.requestId === requestId && result.connectionId === connection.connectionId &&
    result.generationId === connection.generationId;
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

/** Run the shared remote port scan using the native SSH exec capability. */
export async function scanRemotePorts(
  capability: SshCapability,
  connection: SshConnectionRef,
  options: PortScanOptions = {},
): Promise<PortScanResult> {
  const createRequestId = options.createRequestId ?? defaultRequestId;
  const requestId = createRequestId();
  let raw: SshExecResult;
  try {
    raw = await capability.exec({
      ...connection,
      requestId,
      command: PORT_LISTENER_SCAN_COMMAND,
      timeoutMs: options.timeoutMs ?? PORT_SCAN_TIMEOUT_MS,
    });
  } catch (error) {
    return { ok: false, ports: [], error: errorMessage(error) };
  }
  if (!validResponse(raw, requestId, connection)) {
    return { ok: false, ports: [], error: 'port scan response belonged to a different SSH request or connection' };
  }
  const result = parsePortScanResult(raw.stdout, raw);
  if (!result.ok) return result;
  const pids = [...new Set(result.ports.map((port) => port.pid))]
    .filter((pid): pid is number => Number.isInteger(pid) && (pid as number) > 0);
  const cwdCommand = procCwdCommand(pids);
  if (!cwdCommand) return result;

  const cwdRequestId = createRequestId();
  try {
    const cwdResult = await capability.exec({
      ...connection,
      requestId: cwdRequestId,
      command: cwdCommand,
      timeoutMs: options.timeoutMs ?? PORT_SCAN_TIMEOUT_MS,
    });
    if (!validResponse(cwdResult, cwdRequestId, connection)) return result;
    const cwdByPid = parseProcCwds(cwdResult.stdout);
    const ports: RemotePort[] = result.ports.map((port) => ({
      ...port,
      cwd: port.pid === null ? null : cwdByPid.get(port.pid) ?? null,
    }));
    return { ...result, ports };
  } catch {
    // Process working directories are useful labels, but never determine
    // whether the port scan succeeded.
    return result;
  }
}
