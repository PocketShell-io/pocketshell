import {
  terminalControlKeyGestureBytes,
  terminalKeyBytes,
  type HoldableControlKey,
  type TerminalKeyId,
} from '@pocketshell/core';

export type MobileHotkeysPage = 'main' | 'ctrl';

export interface MobileHotkeysState {
  enabled: boolean;
  paletteOpen: boolean;
  page: MobileHotkeysPage;
}

export interface MobileHotkeysPointerStart {
  button: number;
  isPrimary: boolean;
  pointerId: number;
  timeStamp: number;
}

export interface MobileHotkeysEmitter {
  send(bytes: Uint8Array, key: TerminalKeyId): void;
  paletteChange(open: boolean): void;
}

const DEFAULT_HOLD_THRESHOLD_MS = 500;

function isHoldable(key: TerminalKeyId): key is HoldableControlKey {
  return key === 'ctrl-c' || key === 'ctrl-d';
}

export function createMobileHotkeysState(enabled: boolean): MobileHotkeysState {
  return { enabled, paletteOpen: false, page: 'main' };
}

/**
 * JS-owned UI behavior for the mobile fast-key surface. The component binds
 * this small state machine to pointer/click events; portable byte mapping
 * remains in pocketshell-core.
 */
export function createMobileHotkeysActions(
  state: MobileHotkeysState,
  emitter: MobileHotkeysEmitter,
  holdThresholdMs = DEFAULT_HOLD_THRESHOLD_MS,
) {
  let holdPointer: { key: HoldableControlKey; pointerId: number; startedAt: number } | null = null;
  function setPaletteOpen(open: boolean): void {
    if (open && !state.enabled) return;
    if (state.paletteOpen === open) return;
    state.paletteOpen = open;
    if (open) state.page = 'main';
    emitter.paletteChange(open);
  }

  function sendKey(key: TerminalKeyId): void {
    if (!state.enabled) return;
    emitter.send(terminalKeyBytes(key), key);
  }

  function sendControlGesture(key: HoldableControlKey, gesture: 'tap' | 'hold' | 'cancel'): void {
    if (!state.enabled) return;
    const bytes = terminalControlKeyGestureBytes(key, gesture);
    if (bytes != null) emitter.send(bytes, key);
  }

  return {
    setEnabled(enabled: boolean): void {
      state.enabled = enabled;
      if (enabled) return;
      holdPointer = null;
      setPaletteOpen(false);
    },
    openPalette(): void { setPaletteOpen(true); },
    closePalette(): void { setPaletteOpen(false); },
    togglePalette(): void { setPaletteOpen(!state.paletteOpen); },
    showMainPage(): void { state.page = 'main'; },
    showCtrlPage(): void { if (state.enabled && state.paletteOpen) state.page = 'ctrl'; },
    sendKey,
    clickKey(key: TerminalKeyId, clickDetail: number): void {
      if (isHoldable(key)) {
        // Pointer presses resolve on pointerup. detail 0 is the keyboard or
        // assistive-technology one-tap fallback for the same button.
        if (clickDetail === 0) sendControlGesture(key, 'tap');
        return;
      }
      sendKey(key);
    },
    beginControlPointer(key: TerminalKeyId, start: MobileHotkeysPointerStart): boolean {
      if (!state.enabled || !isHoldable(key) || start.button !== 0 || !start.isPrimary) return false;
      holdPointer = { key, pointerId: start.pointerId, startedAt: start.timeStamp };
      return true;
    },
    finishControlPointer(pointerId: number, timeStamp: number): void {
      if (holdPointer == null || holdPointer.pointerId !== pointerId) return;
      const press = holdPointer;
      holdPointer = null;
      if (!state.enabled) return;
      const duration = Math.max(0, timeStamp - press.startedAt);
      sendControlGesture(press.key, duration >= holdThresholdMs ? 'hold' : 'tap');
    },
    cancelControlPointer(pointerId: number): void {
      if (holdPointer?.pointerId !== pointerId) return;
      sendControlGesture(holdPointer.key, 'cancel');
      holdPointer = null;
    },
  };
}
