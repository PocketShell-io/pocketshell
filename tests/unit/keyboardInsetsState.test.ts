import { afterEach, describe, expect, it, vi } from 'vitest';
import { createKeyboardInsetsStateSync } from '../../src/native/keyboardInsetsState';
import type { KeyboardInsetsState } from '../../src/native/keyboardInsets';

const visible: KeyboardInsetsState = { supported: true, imeVisible: true, safeBottomDp: 0 };
const hidden: KeyboardInsetsState = { supported: true, imeVisible: false, safeBottomDp: 24 };

describe('native keyboard insets state synchronization', () => {
  afterEach(() => vi.useRealTimers());

  it('coalesces plugin notifications and applies a fresh native state read', async () => {
    vi.useFakeTimers();
    const readState = vi.fn(async () => hidden);
    const applyState = vi.fn();
    const sync = createKeyboardInsetsStateSync(readState, applyState, vi.fn(), 120);

    sync.refresh();
    sync.refresh();
    sync.refresh();
    await vi.advanceTimersByTimeAsync(119);
    expect(readState).not.toHaveBeenCalled();

    await vi.advanceTimersByTimeAsync(1);
    expect(readState).toHaveBeenCalledTimes(1);
    expect(applyState).toHaveBeenCalledOnce();
    expect(applyState).toHaveBeenCalledWith(hidden);
    sync.dispose();
  });

  it('ignores an in-flight response after a newer invalidation and serializes the next read', async () => {
    vi.useFakeTimers();
    let resolveFirst!: (state: KeyboardInsetsState) => void;
    const firstRead = new Promise<KeyboardInsetsState>((resolve) => { resolveFirst = resolve; });
    const readState = vi.fn()
      .mockReturnValueOnce(firstRead)
      .mockResolvedValueOnce(hidden);
    const applyState = vi.fn();
    const sync = createKeyboardInsetsStateSync(readState, applyState, vi.fn(), 50);

    sync.refresh();
    await vi.advanceTimersByTimeAsync(50);
    expect(readState).toHaveBeenCalledTimes(1);

    sync.refresh();
    resolveFirst(visible);
    await Promise.resolve();
    await Promise.resolve();
    expect(applyState).not.toHaveBeenCalled();

    await vi.advanceTimersByTimeAsync(50);
    await Promise.resolve();
    expect(readState).toHaveBeenCalledTimes(2);
    expect(applyState).toHaveBeenCalledOnce();
    expect(applyState).toHaveBeenCalledWith(hidden);
    sync.dispose();
  });

  it('does not apply a pending native response after disposal', async () => {
    vi.useFakeTimers();
    const readState = vi.fn(async () => hidden);
    const applyState = vi.fn();
    const sync = createKeyboardInsetsStateSync(readState, applyState, vi.fn(), 25);

    sync.refresh();
    sync.dispose();
    await vi.advanceTimersByTimeAsync(25);

    expect(readState).not.toHaveBeenCalled();
    expect(applyState).not.toHaveBeenCalled();
  });
});
