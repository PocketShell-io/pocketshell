import {
  terminalControlKeyGestureBytes,
  terminalKeyBytes,
  type HoldableControlKey,
  type TerminalKeyId,
} from '@pocketshell/core';

export type MobileHotkeysPage = 'main' | 'ctrl';
export interface MobileHotkeysPoint { left: number; top: number }
export interface MobileHotkeysRect { left: number; top: number; width: number; height: number }

export interface MobileHotkeysState {
  enabled: boolean;
  paletteOpen: boolean;
  page: MobileHotkeysPage;
  dragPosition: MobileHotkeysPoint | null;
}

export interface MobileHotkeysPointerStart {
  button: number;
  isPrimary: boolean;
  pointerId: number;
  timeStamp: number;
}

export interface MobileHotkeysDragStart extends MobileHotkeysPointerStart {
  clientX: number;
  clientY: number;
  targetIsControl: boolean;
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
  return { enabled, paletteOpen: false, page: 'main', dragPosition: null };
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
  let dragPointer: {
    pointerId: number;
    startX: number;
    startY: number;
    startLeft: number;
    startTop: number;
  } | null = null;

  function setPaletteOpen(open: boolean): void {
    if (open && !state.enabled) return;
    if (state.paletteOpen === open) return;
    state.paletteOpen = open;
    dragPointer = null;
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
      dragPointer = null;
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
    beginDrag(start: MobileHotkeysDragStart, bounds: MobileHotkeysRect, card: MobileHotkeysRect): boolean {
      if (!state.enabled || !state.paletteOpen || start.button !== 0 || !start.isPrimary || start.targetIsControl) return false;
      dragPointer = {
        pointerId: start.pointerId,
        startX: start.clientX,
        startY: start.clientY,
        startLeft: card.left - bounds.left,
        startTop: card.top - bounds.top,
      };
      return true;
    },
    moveDrag(pointerId: number, clientX: number, clientY: number, bounds: MobileHotkeysRect, card: MobileHotkeysRect): void {
      if (dragPointer == null || dragPointer.pointerId !== pointerId) return;
      const maxLeft = Math.max(0, bounds.width - card.width);
      const maxTop = Math.max(0, bounds.height - card.height);
      const left = dragPointer.startLeft + clientX - dragPointer.startX;
      const top = dragPointer.startTop + clientY - dragPointer.startY;
      state.dragPosition = {
        left: Math.min(maxLeft, Math.max(0, left)),
        top: Math.min(maxTop, Math.max(0, top)),
      };
    },
    clampDrag(bounds: MobileHotkeysRect, card: MobileHotkeysRect): void {
      if (state.dragPosition == null) return;
      const clamped = {
        left: Math.min(Math.max(0, bounds.width - card.width), Math.max(0, state.dragPosition.left)),
        top: Math.min(Math.max(0, bounds.height - card.height), Math.max(0, state.dragPosition.top)),
      };
      if (clamped.left !== state.dragPosition.left || clamped.top !== state.dragPosition.top) {
        state.dragPosition = clamped;
      }
    },
    finishDrag(pointerId: number): void {
      if (dragPointer?.pointerId === pointerId) dragPointer = null;
    },
  };
}
