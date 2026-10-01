import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import {
  readHostUsage,
  usageThresholdState,
  type SshCapability,
  type SshConnectionRef,
  type SshExecOptions,
} from '@pocketshell/core';

// The usage controller itself lives in @pocketshell/core (usageSource.ts) with
// its own contract suite. This file pins only the Android-owned Docker
// fixture that the packaged Usage journey serves, read through that core API.
const connection: SshConnectionRef = { connectionId: 'conn-1', generationId: 'gen-4' };
const dockerFixture = readFileSync(
  new URL('../../tests/docker/agent-fixtures/pocketshell-usage.ndjson', import.meta.url),
  'utf8',
);

const capability = {
  async exec(options: SshExecOptions) {
    return {
      requestId: options.requestId,
      connectionId: options.connectionId,
      generationId: options.generationId,
      exitCode: 0,
      stdout: dockerFixture,
      stderr: '',
      timedOut: false,
    };
  },
} as unknown as SshCapability;

describe('Android Usage Docker fixture through core readHostUsage', () => {
  it('parses the packaged Docker fixture states that the Usage screen displays', async () => {
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
