import { describe, expect, it, vi } from 'vitest';
import { warmUpBridge, withTimeout } from '../../src/native/bridgeReady';
import { readInstalledAppInfoOutcome } from '../../src/platform/androidAppInfo';

describe('bridge warm-up and bounded app info', () => {
  it('retries the sacrificial ping when the first reply is lost, as after a page reload', async () => {
    let calls = 0;
    const plugin = {
      ping: vi.fn(({ requestId }: { requestId: string }) => {
        calls += 1;
        // The first reply goes to the previous document and never arrives.
        return calls === 1 ? new Promise<{ requestId: string }>(() => undefined) : Promise.resolve({ requestId });
      }),
    };
    const result = await warmUpBridge({ plugin, native: true, timeoutMs: 10 });
    expect(result).toMatchObject({ answered: true, attempts: 2 });
    expect(plugin.ping).toHaveBeenCalledTimes(2);
  });

  it('reports an unanswered bridge instead of waiting forever', async () => {
    const plugin = { ping: vi.fn(() => new Promise<{ requestId: string }>(() => undefined)) };
    const result = await warmUpBridge({ plugin, native: true, timeoutMs: 5, attempts: 3 });
    expect(result).toMatchObject({ answered: false, attempts: 3 });
  });

  it('asks App.getInfo again once when an answer is lost, and reports a timeout if both are', async () => {
    let calls = 0;
    const flaky = vi.fn(() => {
      calls += 1;
      return calls === 1 ? new Promise<never>(() => undefined) : Promise.resolve({ version: '0.6.0', build: '612', id: 'com.pocketshell.app.i2861' });
    });
    await expect(readInstalledAppInfoOutcome({ getInfo: flaky, waitForBridge: async () => undefined, native: true, timeoutMs: 10 }))
      .resolves.toMatchObject({ state: 'resolved', attempts: 2, info: { versionName: '0.6.0', versionCode: 612, applicationId: 'com.pocketshell.app.i2861' } });

    const hung = vi.fn(() => new Promise<never>(() => undefined));
    await expect(readInstalledAppInfoOutcome({ getInfo: hung, waitForBridge: async () => undefined, native: true, timeoutMs: 5 }))
      .resolves.toMatchObject({ state: 'timeout', attempts: 2, info: { applicationId: '' } });
    expect(hung).toHaveBeenCalledTimes(2);
  });

  it('settles a slow promise as undefined after the bound and passes through answers and errors', async () => {
    await expect(withTimeout(new Promise<never>(() => undefined), 5)).resolves.toBeUndefined();
    await expect(withTimeout(Promise.resolve(3), 50)).resolves.toBe(3);
    await expect(withTimeout(Promise.reject(new Error('no')), 50)).rejects.toThrow('no');
  });
});
