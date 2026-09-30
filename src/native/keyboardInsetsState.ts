import type { KeyboardInsetsState } from './keyboardInsets';

/**
 * Coalesces native IME invalidations and applies only the newest native read.
 * Plugin event payloads are intentionally not used as state: an event may be
 * queued while the keyboard is animating or delivered after a newer state.
 */
export function createKeyboardInsetsStateSync(
  readState: () => Promise<KeyboardInsetsState>,
  applyState: (state: KeyboardInsetsState) => void,
  onError: (error: unknown) => void,
  delayMs = 120,
): { refresh: () => void; dispose: () => void } {
  let refreshVersion = 0;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let queuedRead: Promise<void> = Promise.resolve();
  let disposed = false;

  const refresh = () => {
    if (disposed) return;
    const version = ++refreshVersion;
    if (timer !== undefined) clearTimeout(timer);
    timer = setTimeout(() => {
      timer = undefined;
      queuedRead = queuedRead.then(async () => {
        if (disposed || version !== refreshVersion) return;
        try {
          const currentState = await readState();
          if (!disposed && version === refreshVersion) applyState(currentState);
        } catch (error) {
          onError(error);
        }
      });
    }, delayMs);
  };

  const dispose = () => {
    disposed = true;
    refreshVersion += 1;
    if (timer !== undefined) clearTimeout(timer);
    timer = undefined;
  };

  return { refresh, dispose };
}
