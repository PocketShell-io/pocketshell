import { describe, expect, it, vi } from 'vitest';
import { createAppLifecycleHandler } from '../../src/session/appLifecycle';

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((resolvePromise) => { resolve = resolvePromise; });
  return { promise, resolve };
}

describe('native app lifecycle transitions', () => {
  it('returns to foreground when it arrives before background scheduling resolves', async () => {
    const backgroundStarted = deferred<void>();
    const finishBackground = deferred<void>();
    let phase: 'live' | 'background' = 'live';
    const controller = {
      getSnapshot: () => ({ phase }),
      enterBackground: vi.fn(async () => {
        backgroundStarted.resolve(undefined);
        await finishBackground.promise;
        phase = 'background';
      }),
      returnToForeground: vi.fn(async () => {
        phase = 'live';
      }),
    };
    const onError = vi.fn();
    const handleAppState = createAppLifecycleHandler({
      getController: () => controller,
      getBackgroundGraceMs: () => 12_000,
      onError,
    });

    handleAppState(false);
    await backgroundStarted.promise;
    handleAppState(true);
    finishBackground.resolve(undefined);

    await vi.waitFor(() => expect(controller.returnToForeground).toHaveBeenCalledOnce());
    expect(controller.enterBackground).toHaveBeenCalledOnce();
    expect(controller.enterBackground).toHaveBeenCalledWith(12_000);
    expect(phase).toBe('live');
    expect(onError).not.toHaveBeenCalled();
  });
});
