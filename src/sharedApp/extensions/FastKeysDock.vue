<script setup lang="ts">
/**
 * The phone's fast-key dock in the shared shell (#3158, S6 of #3118),
 * contributed to core's `terminal.dock` slot. Core renders it as the bottom
 * row of the terminal area — a flex sibling under the visible pane — so the
 * terminal is sized to the space above it rather than drawn under it, with
 * the soft keyboard open or closed (the WebView resizes for the keyboard, so
 * the dock then sits directly on it).
 *
 * The soft keyboard keeps the state the user left it in. While it is up, a
 * key's pointerdown is cancelled so focus stays in xterm's textarea and the
 * keyboard stays open. While it is down, the key takes focus like any button:
 * the terminal's textarea blurs, so the tap cannot bring the keyboard up
 * (Android WebView shows it for a tap while an editable element is focused).
 * The legacy shell's dock made the same split. After a tap with the keyboard
 * down the key gives focus up again, so a hardware keyboard's Enter cannot
 * re-fire the last dock key. The bytes go to the active pane through the slot
 * context's `sendInput`, the route a typed key takes.
 *
 * Phone layout only (D44): the dock renders in core's `compact` layout class
 * (`useLayoutClass`, <= 640px) and nothing at medium or desktop widths, so a
 * wide window keeps exactly the terminal it had.
 *
 * The row never scrolls (#3158 review round 6): six fixed cells per page, the
 * last one the page key (see fastKeysDockModel.ts). There is no sideways drag
 * on it for the launcher's bottom-edge quick-switch or the edge Back gesture
 * to compete with, so it needs no gesture exclusion either.
 */
import { computed, reactive, ref } from 'vue';
import type { TerminalDockContext } from '@ui/app/extensions';
import { useLayoutClass } from '@ui/app/layoutClass';
import type { TerminalKeyId } from '@pocketshell/core';
import { createMobileHotkeysActions, createMobileHotkeysState } from '@/components/mobileHotkeysModel';
import {
  bytesToInput,
  fastKeysDockPage,
  softKeyboardUp,
  type FastKeysDockKey,
  type FastKeysDockPage,
} from './fastKeysDockModel';

const props = withDefaults(defineProps<{
  context: TerminalDockContext;
  /** Override only for deterministic tests; production uses the legacy shell's 500 ms hold. */
  holdThresholdMs?: number;
}>(), { holdThresholdMs: 500 });

const layout = useLayoutClass();
const compact = computed(() => layout.value === 'compact');
const page = ref<FastKeysDockPage>('keys');
const keys = computed(() => fastKeysDockPage(page.value));

// The legacy shell's key model: the same bytes and the same Ctrl+C / Ctrl+D
// tap-or-hold resolution, so the two shells cannot drift.
const actions = createMobileHotkeysActions(reactive(createMobileHotkeysState(true)), {
  send: (bytes) => props.context.sendInput(bytesToInput(bytes)),
  paletteChange: () => undefined,
}, props.holdThresholdMs);

// The pointer that last began a Ctrl+C / Ctrl+D press. Its pointerup resolves
// the press, so the click that follows must not send it again: with the
// pointerdown cancelled (focus retention), Android WebView delivers that click
// with detail 0, which would otherwise read as a separate keyboard activation
// and double the byte (#3158: ^D ended the reader AND the shell).
let pointerPress: TerminalKeyId | null = null;

function onClick(event: MouseEvent, key: FastKeysDockKey): void {
  try {
    activate(event, key);
  } finally {
    // Keyboard down: the key took focus (see the header); give it back up so
    // a hardware keyboard's Enter does not re-activate this key.
    if (!keyboardIsUp()) (event.currentTarget as HTMLElement | null)?.blur?.();
  }
}

function activate(event: MouseEvent, key: FastKeysDockKey): void {
  if (key.kind === 'page') {
    page.value = key.page;
    return;
  }
  if (key.kind === 'digit') {
    props.context.sendInput(key.text);
    return;
  }
  // A holdable key resolves on pointerup; click only carries the keyboard /
  // assistive-technology one-tap, i.e. a click no pointer press preceded.
  if (key.holdable && pointerPress === key.id) {
    pointerPress = null;
    return;
  }
  actions.clickKey(key.id, key.holdable ? 0 : event.detail);
}

function keyboardIsUp(): boolean {
  if (typeof window === 'undefined') return false;
  return softKeyboardUp(window.screen?.height, window.visualViewport?.height ?? window.innerHeight);
}

function onPointerDown(event: PointerEvent, key: FastKeysDockKey): void {
  // Keyboard up: keep focus (and the keyboard) in the terminal.
  if (keyboardIsUp()) event.preventDefault();
  if (key.kind !== 'terminal' || !key.holdable) return;
  const started = actions.beginControlPointer(key.id as TerminalKeyId, {
    button: event.button,
    isPrimary: event.isPrimary !== false,
    pointerId: event.pointerId,
    timeStamp: event.timeStamp,
  });
  const target = event.currentTarget as HTMLElement | null;
  if (started) pointerPress = key.id;
  if (started && target?.setPointerCapture) target.setPointerCapture(event.pointerId);
}

function onPointerUp(event: PointerEvent): void {
  actions.finishControlPointer(event.pointerId, event.timeStamp);
}

function onPointerCancel(event: PointerEvent): void {
  // A cancelled press (the row scrolled under the finger) sends nothing and
  // no click follows it.
  pointerPress = null;
  actions.cancelControlPointer(event.pointerId);
}

function onLostPointerCapture(event: PointerEvent): void {
  // Fires after every pointerup too, BEFORE its click: keep `pointerPress`.
  actions.cancelControlPointer(event.pointerId);
}
</script>

<template>
  <div
    v-if="compact"
    class="fast-keys-dock"
    data-testid="fast-keys-dock"
    :data-page="page"
    role="toolbar"
    :aria-label="`Terminal keys: ${page}`"
  >
    <button
      v-for="key in keys"
      :key="key.id"
      class="fast-keys-dock__key"
      :class="{ 'fast-keys-dock__key--page': key.kind === 'page' }"
      type="button"
      :data-key-id="key.id"
      :aria-label="key.ariaLabel"
      :title="key.ariaLabel"
      @pointerdown="onPointerDown($event, key)"
      @pointerup="onPointerUp"
      @pointercancel="onPointerCancel"
      @lostpointercapture="onLostPointerCapture"
      @click="onClick($event, key)"
    >
      <span class="fast-keys-dock__cap">{{ key.label }}</span>
    </button>
  </div>
</template>

<style scoped>
/* One 48px row of six fixed cells (FAST_KEYS_DOCK_HEIGHT_PX,
   FAST_KEYS_DOCK_CELLS) that never scrolls. The cells share the row's width
   equally and are never narrower than 48px (FAST_KEYS_MIN_TARGET_PX): six fit
   the 303 CSS px a 360dp phone leaves beside the side rail. The cell is the
   hit target, the 44px keycap inside it the visual (#3127: 48 hit, 44 visual).
   A page's last key is its page key and always sits in the sixth cell, so
   More / Keys / the next page is in the same place on every page. The
   hairline above the row is an inset shadow, not a border, so it does not
   take a pixel from the cells. */
.fast-keys-dock {
  display: grid;
  grid-template-columns: repeat(6, minmax(48px, 1fr));
  flex: 0 0 48px;
  height: 48px;
  min-width: 0;
  overflow: hidden;
  touch-action: manipulation;
  background: var(--surface);
  box-shadow: inset 0 1px 0 var(--border-soft);
  box-sizing: border-box;
  color: var(--fg);
  font-family: var(--font-ui);
  user-select: none;
  -webkit-user-select: none;
  -webkit-touch-callout: none;
}
.fast-keys-dock__key {
  display: inline-flex;
  min-width: 48px;
  height: 100%;
  align-items: center;
  justify-content: center;
  margin: 0;
  padding: 0 2px;
  border: 0;
  background: transparent;
  color: inherit;
  font: inherit;
  font-size: var(--fs-300);
  cursor: pointer;
  touch-action: manipulation;
  -webkit-tap-highlight-color: transparent;
}
.fast-keys-dock__key:last-child {
  grid-column: -2 / -1;
}
.fast-keys-dock__cap {
  display: inline-flex;
  width: 100%;
  min-width: 44px;
  height: 44px;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  padding: 0 4px;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  background: var(--surface-2);
  box-sizing: border-box;
  white-space: nowrap;
}
.fast-keys-dock__key:active .fast-keys-dock__cap {
  background: var(--state-active);
}
.fast-keys-dock__key--page .fast-keys-dock__cap {
  color: var(--accent);
  border-color: var(--accent-dim);
}
.fast-keys-dock__key:focus-visible {
  outline: var(--focus-ring-width) solid var(--focus-ring);
  outline-offset: calc(-1 * var(--focus-ring-width));
}
</style>
