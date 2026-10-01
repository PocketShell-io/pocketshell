import { describe, expect, it, vi } from 'vitest';
import { warmUpBridge, withTimeout } from '../../src/native/bridgeReady';

describe('bridge warm-up', () => {
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

  it('settles a slow promise as undefined after the bound and passes through answers and errors', async () => {
    await expect(withTimeout(new Promise<never>(() => undefined), 5)).resolves.toBeUndefined();
    await expect(withTimeout(Promise.resolve(3), 50)).resolves.toBe(3);
    await expect(withTimeout(Promise.reject(new Error('no')), 50)).rejects.toThrow('no');
  });
});
