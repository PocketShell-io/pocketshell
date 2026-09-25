import { readFileSync } from 'node:fs';
import { describe, expect, it, vi } from 'vitest';
import { usageThresholdState, type SshCapability, type SshConnectionRef, type SshExecOptions, type SshExecResult } from '@pocketshell/core';
import { readHostUsage, USAGE_JSON_COMMAND, UsageSourceError } from '../../src/policy/usage';

const connection: SshConnectionRef = { connectionId: 'conn-1', generationId: 'gen-4' };
const fixture = readFileSync(
  new URL('../../vendor/pocketshell-core/tests/fixtures/usage/quse-0.0.15-usage.ndjson', import.meta.url),
  'utf8',
);
const dockerFixture = readFileSync(
  new URL('../../tests/docker/agent-fixtures/pocketshell-usage.ndjson', import.meta.url),
  'utf8',
);

function scriptedCapability(
  handler: (options: SshExecOptions) => SshExecResult | Promise<SshExecResult>,
): { capability: SshCapability; exec: ReturnType<typeof vi.fn> } {
  const exec = vi.fn(handler);
  return { capability: { exec } as unknown as SshCapability, exec };
}

function response(options: SshExecOptions, overrides: Partial<SshExecResult> = {}): SshExecResult {
  return {
    requestId: options.requestId,
    connectionId: options.connectionId,
    generationId: options.generationId,
    exitCode: 0,
    stdout: fixture,
    stderr: '',
    timedOut: false,
    ...overrides,
  };
}

describe('host usage adapter', () => {
  it('runs the canonical host command on the active generation and parses provider quota states', async () => {
    const { capability, exec } = scriptedCapability((options) => response(options));
    const records = await readHostUsage(capability, connection, { createRequestId: () => 'usage-1' });

    expect(exec).toHaveBeenCalledWith({
      ...connection,
      requestId: 'usage-1',
      command: USAGE_JSON_COMMAND,
      timeoutMs: 20_000,
    });
    expect(records.map((record) => record.provider)).toEqual(['claude', 'codex', 'copilot', 'go', 'grok', 'zai']);
  });

  it('reports command, timeout, and cross-generation failures rather than returning empty usage', async () => {
    const nonzero = scriptedCapability((options) => response(options, {
      exitCode: 127,
      stderr: 'pocketshell: command not found\n',
    }));
    await expect(readHostUsage(nonzero.capability, connection, { createRequestId: () => 'usage-2' }))
      .rejects.toThrow(/exit 127.*command not found/);

    const timeout = scriptedCapability((options) => response(options, { timedOut: true }));
    await expect(readHostUsage(timeout.capability, connection, { createRequestId: () => 'usage-3' }))
      .rejects.toThrow(/timed out/);

    const mismatch = scriptedCapability((options) => response(options, { generationId: 'old-generation' }));
    await expect(readHostUsage(mismatch.capability, connection, { createRequestId: () => 'usage-4' }))
      .rejects.toBeInstanceOf(UsageSourceError);
  });

  it('keeps provider schema failures distinct from an unavailable command', async () => {
    const malformed = scriptedCapability((options) => response(options, { stdout: '{bad json}\n' }));
    await expect(readHostUsage(malformed.capability, connection, { createRequestId: () => 'usage-5' }))
      .rejects.toThrow(/invalid usage JSON/);
  });

  it('parses the packaged Docker fixture states that the Usage screen displays', async () => {
    const { capability } = scriptedCapability((options) => response(options, { stdout: dockerFixture }));
    const records = await readHostUsage(capability, connection, { createRequestId: () => 'usage-docker-fixture' });
    expect(records.find((record) => record.provider === 'claude')?.status).toBe('blocked');
    expect(usageThresholdState(records.find((record) => record.provider === 'copilot')!)).toBe('approaching');
    expect(usageThresholdState(records.find((record) => record.provider === 'fixture-critical')!)).toBe('critical');
    expect(records.find((record) => record.provider === 'codex')?.resetCredits?.availableCount).toBe(3);
    expect(records.find((record) => record.provider === 'fixture-error')).toMatchObject({
      status: 'error',
      lastError: 'fixture provider credentials unavailable',
    });
  });
});
