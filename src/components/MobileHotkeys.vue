<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import {
  HOTKEY_CTRL_PAGE_ROWS,
  HOTKEY_PALETTE_MAIN_SECTIONS,
  SESSION_BAR_NAV_KEYS,
  type TerminalHotkey,
  type TerminalKeyId,
} from '@pocketshell/core';
import { AppIcon } from '@pocketshell/ui';
import { createMobileHotkeysActions, createMobileHotkeysState } from './mobileHotkeysModel';

// App integration mounts this overlay under a positioned terminal slot whose
// bounds follow the live viewport through IME resize. PTY writes, Back routing,
// and native keyboard/inset handling stay with the app shell.

const HOLD_THRESHOLD_MS = 500;

const props = withDefaults(defineProps<{
  /** Only a live PTY accepts key bytes. Reconnecting and attached states stay disabled. */
  enabled: boolean;
  /** Override only for deterministic tests; production uses Android's 500 ms long-press feel. */
  holdThresholdMs?: number;
}>(), {
  holdThresholdMs: HOLD_THRESHOLD_MS,
});

const emit = defineEmits<{
  /** The caller owns transport and writes these shared-core bytes to the active PTY. */
  send: [bytes: Uint8Array, key: TerminalKeyId];
  /** Lets the app's Android Back handler dismiss this non-modal floating palette. */
  paletteChange: [open: boolean];
}>();

const slot = ref<HTMLDivElement>();
const palette = ref<HTMLElement>();
const state = reactive(createMobileHotkeysState(props.enabled));
const actions = createMobileHotkeysActions(state, {
  send: (bytes, key) => emit('send', bytes, key),
  paletteChange: (open) => emit('paletteChange', open),
}, props.holdThresholdMs);
const paletteOpen = computed(() => state.paletteOpen);
const page = computed(() => state.page);
const paletteStyle = computed(() => state.dragPosition == null
  ? undefined
  : { left: `${state.dragPosition.left}px`, top: `${state.dragPosition.top}px`, right: 'auto', bottom: 'auto' });
const sendKey = actions.sendKey;
let sizeObserver: ResizeObserver | undefined;

function closePalette(): void { actions.closePalette(); }
function showCtrlPage(): void { actions.showCtrlPage(); }

function onPaletteKeyClick(event: MouseEvent, key: TerminalKeyId): void {
  actions.clickKey(key, event.detail);
}

function beginControlPointer(event: PointerEvent, key: TerminalKeyId): void {
  const started = actions.beginControlPointer(key, {
    button: event.button,
    isPrimary: event.isPrimary !== false,
    pointerId: event.pointerId,
    timeStamp: event.timeStamp,
  });
  const target = event.currentTarget as HTMLElement | null;
  if (started && target?.setPointerCapture) {
    target.setPointerCapture(event.pointerId);
  }
}

function finishControlPointer(event: PointerEvent): void {
  actions.finishControlPointer(event.pointerId, event.timeStamp);
}

function cancelControlPointer(event: PointerEvent): void {
  actions.cancelControlPointer(event.pointerId);
}

function onLauncherClick(): void {
  actions.togglePalette();
}

function showMainPage(): void {
  actions.showMainPage();
}

function beginDrag(event: PointerEvent): void {
  const target = event.target as Element | null;
  const bounds = slot.value?.getBoundingClientRect();
  const card = palette.value?.getBoundingClientRect();
  if (!bounds || !card) return;
  const started = actions.beginDrag({
    button: event.button,
    isPrimary: event.isPrimary !== false,
    pointerId: event.pointerId,
    timeStamp: event.timeStamp,
    clientX: event.clientX,
    clientY: event.clientY,
    targetIsControl: Boolean(target?.closest('button, a, input, select, textarea')),
  }, bounds, card);
  if (!started) return;
  const targetElement = event.currentTarget as HTMLElement | null;
  if (targetElement?.setPointerCapture) targetElement.setPointerCapture(event.pointerId);
  event.preventDefault();
}

function moveDrag(event: PointerEvent): void {
  const bounds = slot.value?.getBoundingClientRect();
  const card = palette.value?.getBoundingClientRect();
  if (!bounds || !card) return;
  actions.moveDrag(event.pointerId, event.clientX, event.clientY, bounds, card);
}

function finishDrag(event: PointerEvent): void {
  actions.finishDrag(event.pointerId);
}

function clampPalette(): void {
  const bounds = slot.value?.getBoundingClientRect();
  const card = palette.value?.getBoundingClientRect();
  if (bounds && card) actions.clampDrag(bounds, card);
}

async function observePaletteSize(): Promise<void> {
  await nextTick();
  if (!sizeObserver) return;
  sizeObserver.disconnect();
  if (slot.value) sizeObserver.observe(slot.value);
  if (palette.value) sizeObserver.observe(palette.value);
  clampPalette();
}

function accessibleKeyName(key: TerminalHotkey): string {
  const names: Partial<Record<TerminalKeyId, string>> = {
    'arrow-left': 'Left arrow',
    'arrow-right': 'Right arrow',
    'arrow-up': 'Up arrow',
    'arrow-down': 'Down arrow',
    escape: 'Escape',
    'shift-tab': 'Shift+Tab',
  };
  if (key.id === 'ctrl-c') return 'Send Ctrl+C; hold to send Ctrl+C twice';
  if (key.id === 'ctrl-d') return 'Send Ctrl+D; hold to send Ctrl+D twice';
  if (key.id === 'ctrl-backslash') return 'Send Ctrl+backslash';
  if (key.id.startsWith('ctrl-')) return `Send Ctrl+${key.id.slice('ctrl-'.length).toUpperCase()}`;
  const name = names[key.id] ?? key.label;
  return `Send ${name}`;
}

watch(() => props.enabled, actions.setEnabled);
watch([paletteOpen, page], () => { void observePaletteSize(); });

onMounted(() => {
  if (typeof ResizeObserver === 'undefined') return;
  sizeObserver = new ResizeObserver(clampPalette);
  if (slot.value) sizeObserver.observe(slot.value);
  if (palette.value) sizeObserver.observe(palette.value);
});

onBeforeUnmount(() => sizeObserver?.disconnect());

defineExpose({
  closePalette: actions.closePalette,
  openPalette: actions.openPalette,
  togglePalette: actions.togglePalette,
  paletteOpen,
});
</script>

<template>
  <div
    ref="slot"
    class="mobile-hotkeys"
    data-testid="mobile-hotkeys"
    :data-enabled="enabled"
    :data-palette-open="paletteOpen"
  >
    <div class="mobile-hotkeys__bar" role="toolbar" aria-label="Terminal navigation keys">
      <div class="mobile-hotkeys__navigation">
        <button
          v-for="key in SESSION_BAR_NAV_KEYS"
          :key="key.id"
          class="mobile-hotkeys__key mobile-hotkeys__key--navigation"
          type="button"
          :data-key-id="key.id"
          :aria-label="`Send ${key.id === 'arrow-up' ? 'Up arrow' : key.id === 'arrow-down' ? 'Down arrow' : key.label}`"
          :disabled="!enabled"
          @click="sendKey(key.id)"
        >
          {{ key.label }}
        </button>
      </div>
      <button
        class="mobile-hotkeys__launcher"
        type="button"
        data-testid="mobile-hotkeys-launcher"
        aria-controls="mobile-hotkeys-palette"
        :aria-label="paletteOpen ? 'Close terminal hotkeys' : 'Open terminal hotkeys'"
        :aria-expanded="paletteOpen"
        :disabled="!enabled"
        @click="onLauncherClick"
      >
        <AppIcon name="terminal" aria-hidden="true" />
        <span>Keys</span>
      </button>
    </div>

    <section
      v-if="paletteOpen"
      id="mobile-hotkeys-palette"
      ref="palette"
      class="mobile-hotkeys__palette"
      data-testid="mobile-hotkeys-palette"
      role="region"
      :aria-label="page === 'ctrl' ? 'Control keys' : 'Terminal hotkeys'"
      :style="paletteStyle"
    >
      <header
        class="mobile-hotkeys__palette-header"
        data-testid="mobile-hotkeys-drag-handle"
        @pointerdown="beginDrag"
        @pointermove="moveDrag"
        @pointerup="finishDrag"
        @pointercancel="finishDrag"
        @lostpointercapture="finishDrag"
      >
        <button
          v-if="page === 'ctrl'"
          class="mobile-hotkeys__header-button"
          type="button"
          aria-label="Back to terminal hotkeys"
          :disabled="!enabled"
          @click="showMainPage"
        >
          <AppIcon name="arrow-left" aria-hidden="true" />
        </button>
        <span class="mobile-hotkeys__title" :data-testid="`mobile-hotkeys-title-${page}`">
          {{ page === 'ctrl' ? 'Control keys' : 'Terminal hotkeys' }}
        </span>
        <button
          class="mobile-hotkeys__header-button mobile-hotkeys__close"
          type="button"
          aria-label="Close terminal hotkeys"
          :disabled="!enabled"
          @click="closePalette"
        >
          <AppIcon name="close" aria-hidden="true" />
        </button>
      </header>

      <div v-if="page === 'main'" class="mobile-hotkeys__content" data-testid="mobile-hotkeys-main-page">
        <section
          v-for="section in HOTKEY_PALETTE_MAIN_SECTIONS"
          :key="section.title"
          class="mobile-hotkeys__section"
          :aria-label="section.title"
        >
          <h3>{{ section.title }}</h3>
          <div class="mobile-hotkeys__main-keys">
            <button
              v-for="key in section.keys"
              :key="key.id"
              class="mobile-hotkeys__key mobile-hotkeys__key--palette"
              :class="{ 'mobile-hotkeys__key--holdable': key.id === 'ctrl-c' || key.id === 'ctrl-d' }"
              type="button"
              :data-key-id="key.id"
              :aria-label="accessibleKeyName(key)"
              :disabled="!enabled"
              @pointerdown="beginControlPointer($event, key.id)"
              @pointerup="finishControlPointer"
              @pointercancel="cancelControlPointer"
              @lostpointercapture="cancelControlPointer"
              @click="onPaletteKeyClick($event, key.id)"
            >
              <span class="mobile-hotkeys__keycap">{{ key.label }}</span>
              <small v-if="key.id === 'ctrl-c' || key.id === 'ctrl-d'">hold ×2</small>
            </button>
            <button
              v-if="section.title === 'CTRL'"
              class="mobile-hotkeys__key mobile-hotkeys__key--ctrl-page"
              type="button"
              data-testid="mobile-hotkeys-open-ctrl-page"
              aria-label="Open Ctrl plus letter keys"
              :disabled="!enabled"
              @click="showCtrlPage"
            >
              Ctrl+…
            </button>
          </div>
        </section>
      </div>

      <div v-else class="mobile-hotkeys__content mobile-hotkeys__content--ctrl" data-testid="mobile-hotkeys-ctrl-page">
        <div v-for="(row, rowIndex) in HOTKEY_CTRL_PAGE_ROWS" :key="rowIndex" class="mobile-hotkeys__ctrl-row">
          <button
            v-for="key in row"
            :key="key.id"
            class="mobile-hotkeys__key mobile-hotkeys__key--ctrl"
            type="button"
            :data-key-id="key.id"
            :aria-label="accessibleKeyName(key)"
            :disabled="!enabled"
            @click="sendKey(key.id)"
          >
            {{ key.label }}
          </button>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
/* This root is mounted as an absolute overlay inside the terminal slot. Its
   controls float over xterm and never contribute to the cell-grid geometry. */
.mobile-hotkeys {
  position: absolute;
  z-index: 5;
  inset: 0;
  overflow: hidden;
  pointer-events: none;
}

.mobile-hotkeys button { color: inherit; font: inherit; }
.mobile-hotkeys button:focus-visible { outline: var(--focus-ring-width) solid var(--focus-ring); outline-offset: 2px; }
.mobile-hotkeys button:disabled { cursor: default; opacity: var(--disabled-opacity); }

.mobile-hotkeys__bar {
  position: absolute;
  right: 8px;
  bottom: 8px;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: var(--sp-1);
  pointer-events: auto;
}

.mobile-hotkeys__navigation { display: flex; gap: var(--sp-1); }
.mobile-hotkeys__key,
.mobile-hotkeys__launcher,
.mobile-hotkeys__header-button {
  display: inline-flex;
  min-width: 48px;
  min-height: 48px;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--border-soft);
  border-radius: var(--r-md);
  background: var(--surface-2);
  color: var(--fg);
  cursor: pointer;
  transition: background var(--dur-fast) var(--ease), border-color var(--dur-fast) var(--ease), color var(--dur-fast) var(--ease);
}

.mobile-hotkeys__key:hover:not(:disabled),
.mobile-hotkeys__launcher:hover:not(:disabled),
.mobile-hotkeys__header-button:hover:not(:disabled) {
  border-color: var(--border-strong);
  background: var(--state-hover);
}

.mobile-hotkeys__key:active:not(:disabled),
.mobile-hotkeys__launcher[aria-expanded="true"] {
  border-color: var(--accent);
  color: var(--accent);
}

.mobile-hotkeys__key--navigation { font: 600 18px/1 var(--font-mono); }
.mobile-hotkeys__key--navigation[data-key-id="enter"] { min-width: 64px; font: 600 var(--fs-200)/1 var(--font-ui); }

.mobile-hotkeys__launcher {
  min-width: 76px;
  gap: var(--sp-1);
  padding: 0 var(--sp-2);
  font-size: var(--fs-200);
  font-weight: var(--fw-medium);
}
.mobile-hotkeys__launcher :deep(svg) { width: 16px; height: 16px; }

.mobile-hotkeys__palette {
  position: absolute;
  right: 8px;
  bottom: 64px;
  z-index: 2;
  display: flex;
  width: min(320px, calc(100% - 16px));
  max-height: min(calc(100% - 72px), 380px);
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
  background: var(--surface);
  box-shadow: var(--shadow-card);
  color: var(--fg);
  pointer-events: auto;
}

.mobile-hotkeys__palette-header {
  display: flex;
  min-height: 56px;
  flex: 0 0 auto;
  align-items: center;
  gap: var(--sp-2);
  border-bottom: 1px solid var(--border-soft);
  padding: 4px var(--sp-2);
  touch-action: none;
  user-select: none;
  cursor: grab;
}
.mobile-hotkeys__palette-header:active { cursor: grabbing; }
.mobile-hotkeys__title { min-width: 0; flex: 1 1 auto; font-size: var(--fs-300); font-weight: var(--fw-semibold); }
.mobile-hotkeys__header-button { flex: 0 0 48px; background: transparent; font-size: 24px; }
.mobile-hotkeys__header-button :deep(svg) { width: 18px; height: 18px; }
.mobile-hotkeys__close { color: var(--fg-secondary); }

.mobile-hotkeys__content { min-height: 0; overflow: auto; padding: var(--sp-2); }
.mobile-hotkeys__section + .mobile-hotkeys__section { margin-top: var(--sp-2); }
.mobile-hotkeys__section h3 { margin: 0 0 var(--sp-1); color: var(--fg-muted); font-size: var(--fs-100); font-weight: var(--fw-semibold); letter-spacing: 0.06em; }
.mobile-hotkeys__main-keys { display: flex; flex-wrap: wrap; gap: var(--sp-1); }
.mobile-hotkeys__key--palette { min-width: 52px; flex-direction: column; gap: 1px; padding: 3px var(--sp-2); font: 500 12px/1.1 var(--font-mono); }
.mobile-hotkeys__key--palette small { color: var(--fg-muted); font: 9px/1 var(--font-ui); }
.mobile-hotkeys__key--holdable { min-width: 58px; }
.mobile-hotkeys__key--ctrl-page { min-width: 88px; padding-inline: var(--sp-2); font-size: var(--fs-200); font-weight: var(--fw-medium); }

.mobile-hotkeys__content--ctrl { display: grid; gap: var(--sp-1); padding-block: var(--sp-3); }
.mobile-hotkeys__ctrl-row { display: flex; justify-content: center; gap: var(--sp-1); }
.mobile-hotkeys__key--ctrl { min-width: 48px; width: 100%; max-width: 56px; font: 500 12px/1 var(--font-mono); }

@media (max-height: 420px) {
  .mobile-hotkeys__content { padding-block: var(--sp-1); }
  .mobile-hotkeys__section + .mobile-hotkeys__section { margin-top: var(--sp-1); }
}
</style>
