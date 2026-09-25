<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, useSlots, watch } from 'vue';
import {
  HOTKEY_CTRL_PAGE_ROWS,
  HOTKEY_PALETTE_MAIN_SECTIONS,
  SESSION_BAR_NAV_KEYS,
  type TerminalHotkey,
  type TerminalKeyId,
} from '@pocketshell/core';
import { AppIcon } from '@pocketshell/ui';
import { createMobileHotkeysActions, createMobileHotkeysState, type MobileHotkeysPage } from './mobileHotkeysModel';
import type { InlineDictationState } from '../session/inlineDictation';

// Keep the one-tap keys in the terminal flow. The full catalog is a compact,
// on-demand grid with its own vertical scroll area.
const initialDictationState: InlineDictationState = {
  phase: 'idle',
  preview: '',
  message: 'Tap the microphone to dictate at the terminal cursor.',
  tone: 'quiet',
};

const props = withDefaults(defineProps<{
  /** Only a live PTY accepts key bytes. Reconnecting and attached states stay disabled. */
  enabled: boolean;
  /** Re-focus the prompt after a physical hotkey tap so Android keeps the IME open. */
  keyboardVisible?: boolean;
  /** Android owns the inline terminal dictation affordance and status chip. */
  dictationAvailable?: boolean;
  dictationState?: InlineDictationState;
  dictationTargetKey?: string;
  /** Override only for deterministic tests; production uses Android's 500 ms long-press feel. */
  holdThresholdMs?: number;
}>(), {
  keyboardVisible: false,
  dictationAvailable: false,
  dictationState: () => ({
    phase: 'idle' as const,
    preview: '',
    message: 'Tap the microphone to dictate at the terminal cursor.',
    tone: 'quiet' as const,
  }),
  dictationTargetKey: '',
  holdThresholdMs: 500,
});

const emit = defineEmits<{
  /** The caller owns transport and writes these shared-core bytes to the active PTY. */
  send: [bytes: Uint8Array, key: TerminalKeyId];
  /** Lets the app's Android Back handler dismiss the fast-key tray. */
  paletteChange: [open: boolean];
  /** Lets the app preserve the terminal grid while compacting the composer on the Ctrl page. */
  pageChange: [page: MobileHotkeysPage];
  /** Re-focuses the prompt after a physical hotkey tap so Android keeps the IME open. */
  keepKeyboardOpen: [];
}>();

const slots = useSlots();
const state = reactive(createMobileHotkeysState(props.enabled));
const actions = createMobileHotkeysActions(state, {
  send: (bytes, key) => emit('send', bytes, key),
  paletteChange: (open) => emit('paletteChange', open),
}, props.holdThresholdMs);
const paletteOpen = computed(() => state.paletteOpen);
const page = computed(() => state.page);
const mainKeys = HOTKEY_PALETTE_MAIN_SECTIONS.flatMap((section) => section.keys.map((key) => ({
  ...key,
  section: section.title,
})));
const hasPersistentStatus = computed(() => Boolean(slots['persistent-status']));
const hasPersistentControls = computed(() => Boolean(slots['persistent-controls']));
const hasPersistentAccessory = computed(() => Boolean(slots['persistent-accessory']));
const dictationStatusVisible = computed(() => props.dictationAvailable && (
  props.dictationState.phase !== 'idle'
  || props.dictationState.tone !== 'quiet'
  || (props.dictationState.message !== '' && props.dictationState.message !== initialDictationState.message)
));
const sendKey = actions.sendKey;
let keyboardPointer: { id: number; button: Element } | null = null;

function closePalette(): void { actions.closePalette(); }
function showCtrlPage(): void { actions.showCtrlPage(); }
function showMainPage(): void { actions.showMainPage(); }

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

function preserveKeyboardFocus(event: PointerEvent): void {
  // Buttons above Android's IME must not take focus away from the composer:
  // WebView otherwise blurs the input and dismisses the native keyboard. Keep
  // the pointer identity so click can restore focus after the hotkey action.
  keyboardPointer = null;
  const target = event.target as Element | null;
  const button = target?.closest?.('button');
  if (props.keyboardVisible && button) {
    keyboardPointer = { id: event.pointerId, button };
    event.preventDefault();
  }
}

function cancelKeyboardPointer(event: PointerEvent): void {
  if (keyboardPointer?.id === event.pointerId) keyboardPointer = null;
}

function restoreKeyboardAfterPointerClick(event: MouseEvent): void {
  const pointer = keyboardPointer;
  keyboardPointer = null;
  const target = event.target as Element | null;
  if (event.detail > 0 && pointer && target?.closest?.('button') === pointer.button) {
    emit('keepKeyboardOpen');
  }
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
watch(page, (current) => emit('pageChange', current), { immediate: true, flush: 'sync' });

onBeforeUnmount(() => actions.closePalette());

defineExpose({
  closePalette: actions.closePalette,
  openPalette: actions.openPalette,
  togglePalette: actions.togglePalette,
  paletteOpen,
  page,
});
</script>

<template>
  <div
    class="mobile-hotkeys"
    :class="{
      'mobile-hotkeys--main-open': paletteOpen && page === 'main',
      'mobile-hotkeys--ctrl-open': paletteOpen && page === 'ctrl',
      'mobile-hotkeys--dictation-status-open': dictationStatusVisible,
      'mobile-hotkeys--dictation-available': dictationAvailable,
    }"
    data-testid="mobile-hotkeys"
    :data-enabled="enabled"
    :data-keyboard-visible="keyboardVisible"
    :data-palette-open="paletteOpen"
    :data-palette-page="paletteOpen ? page : 'closed'"
    @pointerdown="preserveKeyboardFocus"
    @pointercancel="cancelKeyboardPointer"
    @click="restoreKeyboardAfterPointerClick"
  >
    <div
      class="mobile-hotkeys__dictation-dock"
      :data-testid="dictationAvailable ? 'inline-dictation-bar' : undefined"
      :data-phase="dictationState.phase"
      :data-dictation-tone="dictationState.tone"
      :data-target-key="dictationTargetKey"
    >
      <div v-if="dictationStatusVisible" class="mobile-hotkeys__dictation-status-row" data-testid="inline-dictation-status-row">
        <p class="mobile-hotkeys__dictation-status" :data-dictation-tone="dictationState.tone"
          :data-dictation-phase="dictationState.phase" data-testid="inline-dictation-status" role="status" aria-live="polite">
          <span v-if="dictationState.phase === 'listening'" class="mobile-hotkeys__dictation-phase">Listening · </span>
          <span v-else-if="['stopping', 'inserting'].includes(dictationState.phase)" class="mobile-hotkeys__dictation-phase">Transcribing · </span>
          <span v-if="dictationState.preview" class="terminal-dictation-preview" data-testid="inline-dictation-preview" aria-live="off">
            {{ dictationState.preview }}
          </span>
          <span v-else data-testid="inline-dictation-message">{{ dictationState.message }}</span>
        </p>
      </div>

      <div class="mobile-hotkeys__bar" role="toolbar" aria-label="Persistent terminal keys">
        <div class="mobile-hotkeys__navigation" data-testid="mobile-hotkeys-navigation">
          <template v-for="key in SESSION_BAR_NAV_KEYS" :key="key.id">
          <button
            class="mobile-hotkeys__key mobile-hotkeys__key--navigation"
            :class="{ 'mobile-hotkeys__key--enter': key.id === 'enter' }"
            type="button"
            :data-key-id="key.id"
            :aria-label="key.id === 'arrow-up' ? 'Send Up arrow' : key.id === 'arrow-down' ? 'Send Down arrow' : 'Send Enter'"
            :disabled="!enabled"
            @click="sendKey(key.id)"
          >
            {{ key.label }}
          </button>
          <span
            v-if="key.id === 'arrow-down'"
            class="mobile-hotkeys__enter-divider"
            data-testid="mobile-hotkeys-enter-divider"
            aria-hidden="true"
          />
          </template>
        </div>

        <button
          class="mobile-hotkeys__launcher"
          type="button"
          data-testid="mobile-hotkeys-launcher"
          :aria-controls="!paletteOpen ? undefined : page === 'ctrl' ? 'mobile-hotkeys-ctrl-page' : 'mobile-hotkeys-main-page'"
          :aria-label="paletteOpen ? 'Close terminal keys' : 'More terminal keys'"
          :aria-expanded="paletteOpen"
          :disabled="!enabled"
          @click="onLauncherClick"
        >
          <AppIcon :name="paletteOpen ? 'close' : 'terminal'" aria-hidden="true" />
        </button>

        <div v-if="hasPersistentStatus || hasPersistentControls || hasPersistentAccessory || dictationAvailable" class="mobile-hotkeys__persistent-slots">
          <div v-if="hasPersistentStatus" class="mobile-hotkeys__persistent-status" data-testid="mobile-hotkeys-persistent-status">
            <slot name="persistent-status" />
          </div>
          <div v-if="hasPersistentControls" class="mobile-hotkeys__persistent-controls" data-testid="mobile-hotkeys-persistent-controls">
            <slot name="persistent-controls" />
          </div>
          <div v-if="hasPersistentAccessory" class="mobile-hotkeys__persistent-accessory" data-testid="mobile-hotkeys-persistent-accessory">
            <slot name="persistent-accessory" />
          </div>
        </div>
      </div>

      <section
        v-if="paletteOpen"
        class="mobile-hotkeys__sheet"
        data-testid="mobile-hotkeys-sheet"
        role="dialog"
        aria-modal="false"
        :aria-labelledby="page === 'ctrl' ? 'mobile-hotkeys-ctrl-title' : 'mobile-hotkeys-main-title'"
      >
        <header class="mobile-hotkeys__sheet-header">
          <div class="mobile-hotkeys__sheet-heading">
            <span
              :id="page === 'ctrl' ? 'mobile-hotkeys-ctrl-title' : 'mobile-hotkeys-main-title'"
              class="mobile-hotkeys__sheet-title"
              data-testid="mobile-hotkeys-sheet-title"
            >{{ page === 'ctrl' ? 'Ctrl keys' : 'Terminal keys' }}</span>
          </div>
          <button
            v-if="page === 'main'"
            class="mobile-hotkeys__page-action"
            type="button"
            data-testid="mobile-hotkeys-open-ctrl-page"
            aria-label="Open Ctrl plus letter keys"
            :disabled="!enabled"
            @click="showCtrlPage"
          >
            Ctrl+…
          </button>
          <button
            v-else
            class="mobile-hotkeys__page-action"
            type="button"
            data-testid="mobile-hotkeys-back-main-page"
            aria-label="Back to terminal hotkeys"
            :disabled="!enabled"
            @click="showMainPage"
          >
            <AppIcon name="arrow-left" aria-hidden="true" />
          </button>
        </header>
        <div
          v-if="page === 'main'"
          class="mobile-hotkeys__catalog-scroll mobile-hotkeys__main-keys"
          data-testid="mobile-hotkeys-main-page"
          id="mobile-hotkeys-main-page"
          role="group"
          aria-label="Common terminal keys"
        >
          <button
            v-for="key in mainKeys"
            :key="key.id"
            class="mobile-hotkeys__key mobile-hotkeys__key--catalog"
            :class="{ 'mobile-hotkeys__key--holdable': key.id === 'ctrl-c' || key.id === 'ctrl-d' }"
            type="button"
            :data-key-section="key.section"
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
        </div>

        <div
          v-else
          class="mobile-hotkeys__catalog-scroll mobile-hotkeys__ctrl-grid"
          id="mobile-hotkeys-ctrl-page"
          data-testid="mobile-hotkeys-ctrl-page"
          role="group"
          aria-label="QWERTY Ctrl keys"
        >
          <div
            v-for="(row, rowIndex) in HOTKEY_CTRL_PAGE_ROWS"
            :key="rowIndex"
            class="mobile-hotkeys__ctrl-row"
            role="group"
            :aria-label="`Ctrl key row ${rowIndex + 1}`"
          >
            <button
              v-for="key in row"
              :key="key.id"
              class="mobile-hotkeys__key mobile-hotkeys__key--catalog"
              type="button"
              :data-key-id="key.id"
              :aria-label="accessibleKeyName(key)"
              :disabled="!enabled"
              @click="sendKey(key.id)"
            >
              <span class="mobile-hotkeys__keycap">{{ key.label }}</span>
            </button>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.mobile-hotkeys {
  display: flex;
  min-width: 0;
  height: 48px;
  flex: 0 0 auto;
  flex-direction: column;
  overflow: hidden;
  color: var(--fg);
}
.mobile-hotkeys--main-open,
.mobile-hotkeys--ctrl-open { height: 192px; }
.mobile-hotkeys--dictation-status-open { height: 80px; }
.mobile-hotkeys--dictation-status-open.mobile-hotkeys--main-open,
.mobile-hotkeys--dictation-status-open.mobile-hotkeys--ctrl-open { height: 192px; }
.mobile-hotkeys--dictation-available { height: 49px; }
.mobile-hotkeys--dictation-available.mobile-hotkeys--dictation-status-open { height: 65px; }
.mobile-hotkeys--dictation-available.mobile-hotkeys--main-open,
.mobile-hotkeys--dictation-available.mobile-hotkeys--ctrl-open { height: 193px; }
.mobile-hotkeys--dictation-available.mobile-hotkeys--dictation-status-open.mobile-hotkeys--main-open,
.mobile-hotkeys--dictation-available.mobile-hotkeys--dictation-status-open.mobile-hotkeys--ctrl-open { height: 209px; }
.mobile-hotkeys__dictation-dock {
  display: flex;
  min-width: 0;
  height: 100%;
  flex: 0 0 auto;
  flex-direction: column;
  border-radius: 0;
  background: transparent;
  box-shadow: inset 0 1px 0 var(--border-soft);
}
.mobile-hotkeys--dictation-available .mobile-hotkeys__dictation-dock {
  border-top: 1px solid var(--border-soft);
  box-shadow: none;
}
.mobile-hotkeys__dictation-status-row { display: flex; min-width: 0; height: 32px; flex: 0 0 32px; align-items: center; padding: 2px 4px; }
.mobile-hotkeys--dictation-available .mobile-hotkeys__dictation-status-row {
  height: 16px;
  flex: 0 0 16px;
  padding: 0 4px;
}
.mobile-hotkeys__dictation-status {
  overflow: hidden;
  width: 100%;
  min-width: 0;
  height: 26px;
  margin: 0;
  border: 0;
  border-radius: 3px;
  background: var(--surface-2);
  padding: 0 8px;
  color: var(--fg-secondary);
  font-size: var(--fs-100);
  line-height: 16px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mobile-hotkeys__dictation-status[data-dictation-tone="success"] { color: var(--success); }
.mobile-hotkeys__dictation-status[data-dictation-tone="warning"] { color: var(--warning); }
.mobile-hotkeys__dictation-status[data-dictation-tone="error"] { color: var(--error); }
.mobile-hotkeys__dictation-status[data-dictation-phase="listening"] .mobile-hotkeys__dictation-phase { color: var(--accent); }
.mobile-hotkeys__dictation-phase { color: var(--fg-secondary); font-weight: 600; }
.mobile-hotkeys__dictation-status .terminal-dictation-preview { color: var(--fg); font-family: var(--font-mono); }
.mobile-hotkeys__dictation-status[data-dictation-tone="success"] .terminal-dictation-preview { color: var(--success); }
.mobile-hotkeys__dictation-status[data-dictation-tone="error"] .terminal-dictation-preview { color: var(--error); }
.mobile-hotkeys__dictation-status[data-dictation-tone="warning"] .terminal-dictation-preview { color: var(--warning); }
.mobile-hotkeys--dictation-available .mobile-hotkeys__dictation-status {
  height: 16px;
  padding: 0 6px;
  font-size: 11px;
  line-height: 12px;
}

.mobile-hotkeys button { color: inherit; font: inherit; }
.mobile-hotkeys button:focus-visible { outline: var(--focus-ring-width) solid var(--focus-ring); outline-offset: 2px; }
.mobile-hotkeys button:disabled { cursor: default; opacity: var(--disabled-opacity); }
.mobile-hotkeys__bar {
  display: flex;
  width: 100%;
  min-width: 0;
  height: 48px;
  flex: 0 0 48px;
  align-items: center;
  gap: 0;
  overflow-x: auto;
  overflow-y: hidden;
  overscroll-behavior-x: contain;
  scrollbar-width: none;
  touch-action: pan-x;
  padding-inline: 8px;
  background: transparent;
}
.mobile-hotkeys__bar::-webkit-scrollbar { display: none; }
.mobile-hotkeys__navigation { display: flex; flex: 0 0 auto; align-items: center; gap: var(--sp-2); }
.mobile-hotkeys__enter-divider { width: 1px; height: 24px; flex: 0 0 1px; background: var(--border-soft); }
.mobile-hotkeys__key,
.mobile-hotkeys__launcher,
.mobile-hotkeys__page-action {
  display: inline-flex;
  width: 48px;
  min-width: 48px;
  height: 48px;
  min-height: 48px;
  flex: 0 0 48px;
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
.mobile-hotkeys__page-action:hover:not(:disabled) {
  border-color: var(--border-strong);
  background: var(--state-hover);
}
.mobile-hotkeys__key:active:not(:disabled),
.mobile-hotkeys__launcher[aria-expanded="true"],
.mobile-hotkeys__page-action:active:not(:disabled) {
  border-color: var(--accent);
  color: var(--accent);
}
.mobile-hotkeys__key--navigation { font: 600 18px/1 var(--font-ui); }
.mobile-hotkeys__key--enter { font: 600 var(--fs-200)/1 var(--font-ui); }
.mobile-hotkeys__launcher {
  margin-left: var(--sp-2);
  border-color: transparent;
  background: transparent;
  color: var(--fg-secondary);
}
.mobile-hotkeys__launcher[aria-expanded="true"] { background: var(--state-selected); color: var(--accent); }
.mobile-hotkeys__launcher :deep(svg),
.mobile-hotkeys__page-action :deep(svg) { width: 18px; height: 18px; }

.mobile-hotkeys__persistent-slots { display: flex; min-width: 0; flex: 0 0 auto; align-items: center; justify-content: flex-end; gap: var(--sp-1); margin-left: 2px; }
.mobile-hotkeys__persistent-status { min-width: 0; flex: 1 1 auto; overflow: hidden; color: var(--fg-secondary); font-size: var(--fs-100); line-height: var(--lh-100); text-overflow: ellipsis; white-space: nowrap; }
.mobile-hotkeys__persistent-status :deep(*) { min-width: 0; max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mobile-hotkeys__persistent-controls,
.mobile-hotkeys__persistent-accessory { display: flex; min-width: 48px; flex: 0 0 auto; align-items: center; justify-content: flex-end; }
.mobile-hotkeys__persistent-controls :deep(button),
.mobile-hotkeys__persistent-accessory :deep(button) {
  width: 48px;
  min-width: 48px;
  max-width: 48px;
  height: 48px;
  min-height: 48px;
  max-height: 48px;
  flex: 0 0 48px;
  padding: 0;
}

.mobile-hotkeys__sheet {
  width: 100%;
  min-width: 0;
  height: 144px;
  min-height: 144px;
  flex: 0 0 144px;
  overflow: hidden;
  box-shadow: inset 0 1px 0 var(--border-soft);
  background: var(--surface);
}
.mobile-hotkeys__sheet-header {
  display: flex;
  width: 100%;
  height: 48px;
  min-height: 48px;
  flex: 0 0 48px;
  align-items: center;
  gap: var(--sp-1);
  border-bottom: 1px solid var(--border-soft);
  padding: 0 var(--sp-1);
}
.mobile-hotkeys__sheet-heading {
  display: flex;
  min-width: 0;
  flex: 1 1 auto;
  align-items: center;
}
.mobile-hotkeys__sheet-title { overflow: hidden; color: var(--fg); font-size: var(--fs-200); font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }
.mobile-hotkeys__catalog-scroll {
  display: grid;
  width: 100%;
  height: 96px;
  min-width: 0;
  align-content: start;
  gap: 4px;
  overflow-x: hidden;
  overflow-y: auto;
  padding: 0 var(--sp-1);
  touch-action: pan-y;
  overscroll-behavior-y: contain;
  scrollbar-width: none;
  -webkit-overflow-scrolling: touch;
}
.mobile-hotkeys__catalog-scroll::-webkit-scrollbar { display: none; }
.mobile-hotkeys__page-action { font: 500 var(--fs-100)/1 var(--font-mono); }
.mobile-hotkeys__main-keys { grid-template-columns: repeat(5, 48px); grid-auto-rows: 48px; justify-content: center; gap: 0 4px; }
.mobile-hotkeys__ctrl-grid { display: flex; height: 96px; min-height: 96px; flex: 0 0 96px; flex-direction: column; gap: 4px; }
.mobile-hotkeys__ctrl-row { display: grid; min-height: 48px; flex: 0 0 48px; grid-template-columns: repeat(auto-fit, 48px); justify-content: center; gap: 4px; }
.mobile-hotkeys__key--catalog { flex-direction: column; gap: 1px; padding: 2px; font: 500 12px/1.1 var(--font-mono); }
.mobile-hotkeys__keycap {
  display: inline-block;
  min-width: 1.6em;
  color: var(--fg);
  text-align: center;
  font-family: var(--font-mono);
  line-height: 1.35;
}
.mobile-hotkeys__key--catalog small { color: var(--fg-secondary); font: 9px/1 var(--font-ui); white-space: nowrap; }
.mobile-hotkeys__key--holdable { touch-action: none; user-select: none; -webkit-touch-callout: none; }

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
}
</style>
