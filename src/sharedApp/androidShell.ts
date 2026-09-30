/**
 * What the Android platform hands the shared app root: its mono font stack
 * and its insets (core `@ui/app/insets.ts`). The WebView draws edge to edge,
 * so the system bars — and, in landscape, the cutout and navigation bar at
 * the sides — cover the page; the shared CSS keeps content out from under
 * them through `--ps-inset-top/bottom/left/right`. The keyboard inset stays at 0
 * here — the WebView resizes for the IME — until the phone-layout stage
 * (#2936 A3) feeds it from the native keyboard insets.
 */
import { writeAppInsets, type AppInsets, type InsetTarget } from '@ui/app/insets';

/** No Consolas on a phone: the platform's own monospace. */
export const ANDROID_MONO_FALLBACK = 'ui-monospace, monospace';

export const ANDROID_INSETS: AppInsets = {
  top: 'env(safe-area-inset-top, 0px)',
  bottom: 'env(safe-area-inset-bottom, 0px)',
  left: 'env(safe-area-inset-left, 0px)',
  right: 'env(safe-area-inset-right, 0px)',
};

export function applyAndroidInsets(target?: InsetTarget): void {
  writeAppInsets(ANDROID_INSETS, target);
}
