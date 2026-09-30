import { registerPlugin, type Plugin, type PluginListenerHandle } from '@capacitor/core';

export type KeyboardInsetsState = {
  supported: boolean;
  imeVisible: boolean;
  safeBottomDp: number;
};

type NativeKeyboardInsetsPlugin = Plugin & {
  getState(): Promise<KeyboardInsetsState>;
  addListener(
    eventName: 'imeInsetsChanged',
    listener: (state: KeyboardInsetsState) => void,
  ): Promise<PluginListenerHandle>;
};

/** Android WindowInsets is the source of truth for IME state and safe-bottom. */
export const keyboardInsets = registerPlugin<NativeKeyboardInsetsPlugin>('KeyboardInsets');
