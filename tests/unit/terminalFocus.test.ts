import { describe, expect, it, vi } from 'vitest';
import {
  focusTerminalUnlessComposerFocused,
  getComposerPointerIntentEpoch,
  noteComposerPointerIntent,
} from '../../src/session/terminalFocus';

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

  it('does not steal focus when a composer tap precedes WebView activeElement', async () => {
    const intentAtStart = getComposerPointerIntentEpoch();
    const renderUpdate = deferred<void>();
    let activeElement: 'terminal' | 'composer' = 'terminal';
    const focusTerminal = vi.fn(() => { activeElement = 'terminal'; });
    const pendingFocus = focusTerminalUnlessComposerFocused(
      () => activeElement === 'composer',
      focusTerminal,
      () => renderUpdate.promise,
      () => getComposerPointerIntentEpoch() !== intentAtStart,
    );

    // Android can deliver pointerdown before Chromium changes activeElement.
    noteComposerPointerIntent();
    renderUpdate.resolve(undefined);
    await pendingFocus;

    expect(focusTerminal).not.toHaveBeenCalled();
    activeElement = 'composer';
    expect(activeElement).toBe('composer');
  });

  it('scopes composer pointer intent to the pending focus request', async () => {
    // An interaction completed before this attach started must not suppress
    // the terminal's normal focus for the new request.
    noteComposerPointerIntent();
    const intentAtStart = getComposerPointerIntentEpoch();
    const focusTerminal = vi.fn();

    await focusTerminalUnlessComposerFocused(
      () => false,
      focusTerminal,
      undefined,
      () => getComposerPointerIntentEpoch() !== intentAtStart,
    );

    expect(focusTerminal).toHaveBeenCalledOnce();
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
