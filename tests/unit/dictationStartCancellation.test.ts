import { describe, expect, it, vi } from 'vitest';
import { createDictationStartCancellation } from '../../src/session/dictationStartCancellation';

describe('pending dictation start cancellation', () => {
  it('stops the session when the app backgrounds before start resolves', async () => {
    const cancellation = createDictationStartCancellation();
    const stop = vi.fn(async () => {});
    let resolveStart!: (session: { stop: () => Promise<void> }) => void;

    cancellation.begin();
    const startAndHonorStop = new Promise<{ stop: () => Promise<void> }>((resolve) => {
      resolveStart = resolve;
    }).then(async (session) => {
      if (cancellation.takeStopRequest()) await session.stop();
    });

    cancellation.requestStop();
    expect(stop).not.toHaveBeenCalled();

    resolveStart({ stop });
    await startAndHonorStop;

    expect(stop).toHaveBeenCalledTimes(1);
    expect(cancellation.takeStopRequest()).toBe(false);
  });
});
