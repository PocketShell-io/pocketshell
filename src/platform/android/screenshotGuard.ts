/**
 * Screenshot frame staleness detector (#3067).
 *
 * Verifies that a newly captured screenshot frame represents a distinct UI state
 * rather than a stale capture of the previous screen whose identical-frame
 * check was defeated by status-bar clock/icon updates.
 */

export interface FramePixelDimensions {
  width: number;
  height: number;
  topInset: number;
}

/**
 * Returns true if the content area (pixels below topInset) in current is identical
 * to previous, indicating that the app UI has not rendered a new frame.
 */
export function isContentStale(
  currentPixels: ArrayLike<number>,
  previousPixels: ArrayLike<number> | null,
  dims: FramePixelDimensions,
): boolean {
  if (!previousPixels) return false;
  if (currentPixels.length !== previousPixels.length) return false;
  const { width, height, topInset } = dims;
  const startY = Math.max(0, Math.min(topInset, height - 1));
  const startIndex = startY * width;
  for (let i = startIndex; i < currentPixels.length; i++) {
    if (currentPixels[i] !== previousPixels[i]) return false;
  }
  return true;
}

/**
 * Returns true if the entire frame (including status bar) is byte-identical.
 */
export function isFrameIdentical(
  current: ArrayLike<number>,
  previous: ArrayLike<number> | null,
): boolean {
  if (!previous) return false;
  if (current.length !== previous.length) return false;
  for (let i = 0; i < current.length; i++) {
    if (current[i] !== previous[i]) return false;
  }
  return true;
}

/**
 * Evaluates whether a candidate capture should be accepted.
 * A frame is rejected as stale if either:
 *  1. The whole frame is identical to previous, OR
 *  2. The content area below the status bar is identical to previous (status-bar change only).
 */
export function shouldAcceptFrame(
  current: ArrayLike<number>,
  previous: ArrayLike<number> | null,
  dims: FramePixelDimensions,
): boolean {
  if (!previous) return true;
  if (isFrameIdentical(current, previous)) return false;
  if (isContentStale(current, previous, dims)) return false;
  return true;
}
