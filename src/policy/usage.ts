import {
  parseUsageNdjson,
  type UsageProviderRecord,
} from '@pocketshell/core';
import type { SshCapability, SshConnectionRef, SshExecResult } from '@pocketshell/core';

export const USAGE_JSON_COMMAND = 'pocketshell usage --json';
export const USAGE_COMMAND_TIMEOUT_MS = 20_000;

export class UsageSourceError extends Error {
  constructor(message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = 'UsageSourceError';
  }
}

export interface UsageSourceOptions {
  createRequestId?: () => string;
  timeoutMs?: number;
}

let requestSequence = 0;
function defaultRequestId(): string {
  requestSequence += 1;
  return `usage-${Date.now().toString(36)}-${requestSequence.toString(36)}`;
}

/** Fetch usage only from the connected host; PocketShell itself keeps no provider credentials. */
export async function readHostUsage(
  capability: SshCapability,
  connection: SshConnectionRef,
  options: UsageSourceOptions = {},
): Promise<UsageProviderRecord[]> {
  const requestId = (options.createRequestId ?? defaultRequestId)();
  let result: SshExecResult;
  try {
    result = await capability.exec({
      ...connection,
      requestId,
      command: USAGE_JSON_COMMAND,
      timeoutMs: options.timeoutMs ?? USAGE_COMMAND_TIMEOUT_MS,
    });
  } catch (cause) {
    throw new UsageSourceError(
      `Could not read provider usage from the host: ${cause instanceof Error ? cause.message : String(cause)}`,
      { cause },
    );
  }
  if (result.requestId !== requestId || result.connectionId !== connection.connectionId ||
      result.generationId !== connection.generationId) {
    throw new UsageSourceError('The usage response belongs to a different SSH request or connection.');
  }
  if (result.timedOut) throw new UsageSourceError('The host usage command timed out.');
  if (result.exitCode !== 0) {
    const detail = result.stderr.trim().split(/\r?\n/).find(Boolean)?.slice(0, 200);
    throw new UsageSourceError(
      `The host usage command failed${result.exitCode === null ? '' : ` (exit ${result.exitCode})`}` +
      `${detail ? `: ${detail}` : '.'}`,
    );
  }
  return parseUsageNdjson(result.stdout);
}
