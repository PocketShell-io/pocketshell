/**
 * What the Android platform hands the shared app root: its mono font stack
 * and its insets (core `@ui/app/insets.ts`). The WebView draws edge to edge,
 * so the system bars — and, in landscape, the cutout and navigation bar at
 * the sides — cover the page; the shared CSS keeps content out from under
 * them through `--ps-inset-top/bottom/left/right`. The keyboard inset stays at 0
 * here — the WebView resizes for the IME. Native navigation clearance is
 * bound separately because Android WebView does not expose it through env().
 */
import { writeAppInsets, type AppInsets, type InsetTarget } from '@ui/app/insets';
import type { KeyboardInsetsState } from '@/native/keyboardInsets';
import { createKeyboardInsetsStateSync } from '@/native/keyboardInsetsState';

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

interface BottomInsetsNative {
  getState(): Promise<KeyboardInsetsState>;
  addListener(event: 'imeInsetsChanged', listener: () => void): Promise<{ remove(): Promise<void> }>;
}

/** The shared shell uses the same native bottom-inset source as the legacy shell.
 * The WebView resizes for IME, so only the remaining navigation-bar inset is applied. */
export function bindAndroidBottomInsets(native: BottomInsetsNative, target?: InsetTarget): () => void {
  let disposed = false;
  let remove: (() => Promise<void>) | null = null;
  const sync = createKeyboardInsetsStateSync(() => native.getState(), state => {
    if (!state.supported || !Number.isFinite(state.safeBottomDp) || state.safeBottomDp < 0) return;
    writeAppInsets({ bottom: state.imeVisible ? 0 : state.safeBottomDp }, target);
  }, () => undefined);
  const listener = native.addListener('imeInsetsChanged', () => sync.refresh());
  void listener.then(handle => {
    if (disposed) void handle.remove().catch(() => undefined);
    else remove = () => handle.remove();
  }, () => undefined);
  sync.refresh();
  return () => {
    disposed = true;
    sync.dispose();
    if (remove) void remove().catch(() => undefined);
  };
}
