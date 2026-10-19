import { describe, expect, it } from 'vitest';
import {
  isContentStale,
  isFrameIdentical,
  shouldAcceptFrame,
} from '../../src/platform/android/screenshotGuard';

describe('screenshot frame guard (#3067)', () => {
  const dims = { width: 10, height: 20, topInset: 4 };

  function makeFrame(statusBarVal: number, contentVal: number): number[] {
    const pixels = new Array(dims.width * dims.height);
    const split = dims.topInset * dims.width;
    pixels.fill(statusBarVal, 0, split);
    pixels.fill(contentVal, split);
    return pixels;
  }

  it('accepts the first captured frame when no previous capture exists', () => {
    const frame = makeFrame(1, 10);
    expect(shouldAcceptFrame(frame, null, dims)).toBe(true);
    expect(isContentStale(frame, null, dims)).toBe(false);
    expect(isFrameIdentical(frame, null)).toBe(false);
  });

  it('rejects a frame that is completely identical to the previous capture', () => {
    const frame1 = makeFrame(1, 10);
    const frame2 = makeFrame(1, 10);
    expect(isFrameIdentical(frame2, frame1)).toBe(true);
    expect(isContentStale(frame2, frame1, dims)).toBe(true);
    expect(shouldAcceptFrame(frame2, frame1, dims)).toBe(false);
  });

  it('rejects a stale frame when only status bar pixels differ', () => {
    const prev = makeFrame(1, 10);
    const next = makeFrame(2, 10); // Status bar changed (clock/icons), but content did not!

    expect(isFrameIdentical(next, prev)).toBe(false);
    expect(isContentStale(next, prev, dims)).toBe(true);
    expect(shouldAcceptFrame(next, prev, dims)).toBe(false);
  });

  it('accepts a new frame when content below the status bar updates', () => {
    const prev = makeFrame(1, 10);
    const next = makeFrame(1, 20); // Content updated to new screen

    expect(isFrameIdentical(next, prev)).toBe(false);
    expect(isContentStale(next, prev, dims)).toBe(false);
    expect(shouldAcceptFrame(next, prev, dims)).toBe(true);
  });

  it('safely clamps topInset bounds', () => {
    const prev = [1, 2, 3, 4];
    const next = [1, 2, 3, 4];
    expect(isContentStale(next, prev, { width: 2, height: 2, topInset: -5 })).toBe(true);
    expect(isContentStale(next, prev, { width: 2, height: 2, topInset: 100 })).toBe(true);
  });
});
