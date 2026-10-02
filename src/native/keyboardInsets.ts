import { registerPlugin, type Plugin, type PluginListenerHandle } from '@capacitor/core';

export type KeyboardInsetsState = {
  supported: boolean;
  imeVisible: boolean;
  safeBottomDp: number;
  /** How far the WebView still reaches under the visible IME (dp); 0 once Android resized it. */
  imeOverlapDp?: number;
};

/** The bottom insets the shell reserves for one native IME state (CSS px = dp). */
export function shellBottomInsets(state: KeyboardInsetsState): { safeAreaBottom: number; imeOverlapBottom: number } {
  const overlap = state.imeVisible && Number.isFinite(state.imeOverlapDp) && (state.imeOverlapDp ?? 0) > 0
    ? Math.round(state.imeOverlapDp ?? 0)
    : 0;
  return { safeAreaBottom: state.imeVisible ? 0 : state.safeBottomDp, imeOverlapBottom: overlap };
}

type NativeKeyboardInsetsPlugin = Plugin & {
  getState(): Promise<KeyboardInsetsState>;
  hideIme(): Promise<void>;
  addListener(
    eventName: 'imeInsetsChanged',
    listener: (state: KeyboardInsetsState) => void,
  ): Promise<PluginListenerHandle>;
};

/** Android WindowInsets is the source of truth for IME state and safe-bottom. */
export const keyboardInsets = registerPlugin<NativeKeyboardInsetsPlugin>('KeyboardInsets');
