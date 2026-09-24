import { describe, expect, it, vi } from 'vitest';
import { focusTerminalUnlessComposerFocused } from '../../src/session/terminalFocus';

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((resolvePromise) => { resolve = resolvePromise; });
  return { promise, resolve };
}

describe('terminal focus after attach', () => {
  it('keeps a composer focus acquired while attach focus is waiting for its update', async () => {
    let focusedView: 'terminal' | 'composer' = 'terminal';
    const renderUpdate = deferred<void>();
    const focusTerminal = vi.fn(() => { focusedView = 'terminal'; });
    const pendingFocus = focusTerminalUnlessComposerFocused(
      () => focusedView === 'composer',
      focusTerminal,
      () => renderUpdate.promise,
    );

    // Model a user tapping the prompt after the attach callback queued its
    // render wait but before that asynchronous focus request resumes.
    focusedView = 'composer';
    renderUpdate.resolve(undefined);
    await pendingFocus;

    expect(focusTerminal).not.toHaveBeenCalled();
    expect(focusedView).toBe('composer');
  });

  it('still gives the terminal focus when no composer has claimed it', async () => {
    const composerHasFocus = false;
    let focusedView: 'none' | 'terminal' = 'none';
    const focusTerminal = vi.fn(() => { focusedView = 'terminal'; });

    await focusTerminalUnlessComposerFocused(
      () => composerHasFocus,
      focusTerminal,
    );

    expect(focusTerminal).toHaveBeenCalledOnce();
    expect(focusedView).toBe('terminal');
  });
});
