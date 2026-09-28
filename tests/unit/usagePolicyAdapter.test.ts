import { readFileSync } from 'node:fs';
import { describe, expect, it, vi } from 'vitest';
import {
  usageThresholdState,
  usageWindowDisplayLabel,
  type SshCapability,
  type SshConnectionRef,
  type SshExecOptions,
  type SshExecResult,
} from '@pocketshell/core';
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
  it('runs the canonical host command on the active generation and parses canonical UsageRows', async () => {
    const { capability, exec } = scriptedCapability((options) => response(options));
    const records = await readHostUsage(capability, connection, { createRequestId: () => 'usage-1' });

    expect(exec).toHaveBeenCalledWith({
      ...connection,
      requestId: 'usage-1',
      command: USAGE_JSON_COMMAND,
      timeoutMs: 20_000,
    });
    expect(records.map((record) => record.provider)).toEqual(['claude', 'codex', 'copilot', 'go', 'grok', 'zai']);
    expect(records.find((record) => record.provider === 'codex')).toMatchObject({
      status: 'ok',
      resets_available: 1,
      resets_expire_at: '2026-09-21T00:13:17Z',
      windows: [{ window: '7d', percent_remaining: 87 }],
    });
  });

  it('reports command, timeout, and cross-generation failures rather than returning empty usage', async () => {
    const nonzero = scriptedCapability((options) => response(options, {
      exitCode: 127,
      stderr: 'pocketshell: command not found\n',
    }));
    await expect(readHostUsage(nonzero.capability, connection, { createRequestId: () => 'usage-2' }))
      .rejects.toThrow(/exit 127.*command not found/);

    const disconnected = scriptedCapability(async () => {
      throw new Error('SSH connection closed');
    });
    await expect(readHostUsage(disconnected.capability, connection, { createRequestId: () => 'usage-disconnected' }))
      .rejects.toThrow(/Could not read provider usage from the host: SSH connection closed/);

    const timeout = scriptedCapability((options) => response(options, { timedOut: true }));
    await expect(readHostUsage(timeout.capability, connection, { createRequestId: () => 'usage-3' }))
      .rejects.toThrow(/timed out/);

    const mismatches: Partial<SshExecResult>[] = [
      { requestId: 'different-request' },
      { connectionId: 'different-connection' },
      { generationId: 'old-generation' },
    ];
    for (const [index, mismatch] of mismatches.entries()) {
      const capability = scriptedCapability((options) => response(options, mismatch));
      await expect(readHostUsage(capability.capability, connection, {
        createRequestId: () => `usage-mismatch-${index}`,
      })).rejects.toBeInstanceOf(UsageSourceError);
    }

    const customTimeout = scriptedCapability((options) => response(options));
    await readHostUsage(customTimeout.capability, connection, {
      createRequestId: () => 'usage-custom-timeout',
      timeoutMs: 4_000,
    });
    expect(customTimeout.exec).toHaveBeenCalledWith(expect.objectContaining({
      requestId: 'usage-custom-timeout',
      timeoutMs: 4_000,
    }));
  });

  it('keeps host-reported provider errors and named custom windows in canonical rows', async () => {
    const providerError = scriptedCapability((options) => response(options, { stdout: dockerFixture }));
    const errorRecord = (await readHostUsage(providerError.capability, connection, {
      createRequestId: () => 'usage-provider-error',
    })).find((record) => record.provider === 'fixture-error');
    expect(errorRecord).toMatchObject({ status: 'error', error: 'fixture provider credentials unavailable' });

    const customWindow = scriptedCapability((options) => response(options, {
      stdout: JSON.stringify({
        provider: 'future-provider',
        status: 'ok',
        windows: {
          weekly: { percent_remaining: 75, reset_at: null },
          per_provider_window: { percent_remaining: 50, reset_at: null },
        },
        details: {},
        error: null,
      }),
    }));
    const [record] = await readHostUsage(customWindow.capability, connection, {
      createRequestId: () => 'usage-custom-window',
    });
    expect(record?.windows.map((window) => window.window)).toEqual(['weekly', 'per_provider_window']);
    expect(usageWindowDisplayLabel(record!.windows[1]!.window)).toBe('Per Provider Window');
  });

  it('parses the packaged Docker fixture states that the Usage screen displays', async () => {
    const { capability } = scriptedCapability((options) => response(options, { stdout: dockerFixture }));
    const records = await readHostUsage(capability, connection, { createRequestId: () => 'usage-docker-fixture' });
    expect(records.find((record) => record.provider === 'claude')?.status).toBe('blocked');
    expect(usageThresholdState(records.find((record) => record.provider === 'copilot')!)).toBe('approaching');
    expect(usageThresholdState(records.find((record) => record.provider === 'fixture-critical')!)).toBe('critical');
    expect(records.find((record) => record.provider === 'codex')?.resets_available).toBe(3);
    expect(records.find((record) => record.provider === 'fixture-error')).toMatchObject({
      status: 'error',
      error: 'fixture provider credentials unavailable',
    });
  });
});
