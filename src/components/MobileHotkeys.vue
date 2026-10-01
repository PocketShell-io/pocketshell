<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, useSlots, watch } from 'vue';
import {
  HOTKEY_CTRL_PAGE_ROWS,
  HOTKEY_PALETTE_MAIN_SECTIONS,
  SESSION_BAR_NAV_KEYS,
  type TerminalHotkey,
  type TerminalKeyId,
} from '@pocketshell/core';
import AppIcon from '@ui/components/AppIcon.vue';
import { createMobileHotkeysActions, createMobileHotkeysState, type MobileHotkeysPage } from './mobileHotkeysModel';
import type { InlineDictationState } from '../session/inlineDictation';

// Keep the one-tap keys in the terminal flow. The full catalog is a compact,
// on-demand grid with its own vertical scroll area.

const props = withDefaults(defineProps<{
  /** Only a live PTY accepts key bytes. Reconnecting and attached states stay disabled. */
  enabled: boolean;
  /** Re-focus the prompt after a physical hotkey tap so Android keeps the IME open. */
  keyboardVisible?: boolean;
  /** Android owns the inline terminal dictation affordance and status chip. */
  dictationAvailable?: boolean;
  /** The app shares this value with PTY dock sizing so the status row cannot drift. */
  showInlineDictationStatus?: boolean;
  /** Adds the Kotlin-style Compose entry point for the shared prompt composer. */
  promptComposerAvailable?: boolean;
  /** Prompt stays visibly disabled while inline terminal recognition owns the dock. */
  promptComposerEnabled?: boolean;
  dictationState?: InlineDictationState;
  dictationTargetKey?: string;
  /** Override only for deterministic tests; production uses Android's 500 ms long-press feel. */
  holdThresholdMs?: number;
}>(), {
  keyboardVisible: false,
  dictationAvailable: false,
  showInlineDictationStatus: undefined,
  promptComposerAvailable: false,
  promptComposerEnabled: true,
  dictationState: () => ({
    phase: 'idle' as const,
    preview: '',
    message: 'Tap Dictate to speak at the terminal cursor.',
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
  /** Opens the shared prompt composer; prompt dictation starts from within it. */
  openComposer: [];
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
const MAIN_CATALOG_KEYS_PER_ROW = mainKeys.length;
const mainKeyRows = Array.from(
  { length: Math.ceil(mainKeys.length / MAIN_CATALOG_KEYS_PER_ROW) },
  (_, rowIndex) => mainKeys.slice(
    rowIndex * MAIN_CATALOG_KEYS_PER_ROW,
    (rowIndex + 1) * MAIN_CATALOG_KEYS_PER_ROW,
  ),
);
const ctrlRows = HOTKEY_CTRL_PAGE_ROWS;
const hasPersistentStatus = computed(() => Boolean(slots['persistent-status']));
const hasPersistentControls = computed(() => Boolean(slots['persistent-controls']));
const hasPersistentAccessory = computed(() => Boolean(slots['persistent-accessory']));
const dictationStatusVisible = computed(() => props.showInlineDictationStatus ?? (
  props.dictationAvailable && (
    props.dictationState.phase !== 'idle'
    || props.dictationState.tone === 'error'
    || props.dictationState.tone === 'warning'
  )
));
const dictationTranscriptRecoverable = computed(() => props.dictationAvailable
  && props.dictationState.phase === 'idle'
  && props.dictationState.tone === 'warning'
  && props.dictationState.preview.length > 0);
const dictationCopyFeedback = ref('');
const dictationElapsedMs = ref(0);
const dictationElapsedLabel = computed(() => {
  const totalSeconds = Math.floor(dictationElapsedMs.value / 1_000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;
});
const dictationWaveformBars = [8, 15, 11, 21, 13, 18, 9, 16, 11, 20, 12, 17];
const sendKey = actions.sendKey;
let keyboardPointer: { id: number; button: Element } | null = null;
let dictationStartedAt = 0;
let dictationClock: ReturnType<typeof setInterval> | undefined;

function stopDictationClock(): void {
  if (dictationClock !== undefined) clearInterval(dictationClock);
  dictationClock = undefined;
}

function closePalette(): void { actions.closePalette(); }
function showCtrlPage(): void { actions.showCtrlPage(); }
function showMainPage(): void { actions.showMainPage(); }

function copyWithLegacyClipboard(text: string): boolean {
  if (typeof document === 'undefined' || !document.body || typeof document.execCommand !== 'function') return false;
  const textarea = document.createElement('textarea');
  textarea.value = text;
  textarea.setAttribute('readonly', '');
  textarea.style.position = 'fixed';
  textarea.style.left = '-10000px';
  textarea.style.top = '0';
  try {
    document.body.append(textarea);
    textarea.select();
    return document.execCommand('copy');
  } catch {
    return false;
  } finally {
    textarea.remove();
  }
}

async function copyRecoverableTranscript(): Promise<void> {
  const transcript = props.dictationState.preview;
  if (!dictationTranscriptRecoverable.value || !transcript) return;
  let copied = false;
  try {
    if (typeof navigator !== 'undefined' && navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(transcript);
      copied = true;
    }
  } catch {
    // Fall back to the WebView clipboard path when the async API is unavailable.
  }
  if (!copied) copied = copyWithLegacyClipboard(transcript);
  dictationCopyFeedback.value = copied
    ? 'Transcript copied. Check the terminal before pasting it.'
    : 'Could not copy. The transcript remains available above; check the terminal before reusing it.';
}

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
watch(() => [dictationTranscriptRecoverable.value, props.dictationState.preview] as const, () => {
  dictationCopyFeedback.value = '';
});
watch(() => props.dictationState.phase, (phase) => {
  stopDictationClock();
  if (phase === 'starting') {
    dictationElapsedMs.value = 0;
    actions.closePalette();
  } else if (phase === 'listening') {
    dictationElapsedMs.value = 0;
    dictationStartedAt = Date.now();
    dictationClock = setInterval(() => {
      dictationElapsedMs.value = Date.now() - dictationStartedAt;
    }, 250);
    actions.closePalette();
  } else if (phase === 'idle') {
    dictationElapsedMs.value = 0;
  }
}, { immediate: true, flush: 'sync' });
watch(page, (current) => emit('pageChange', current), { immediate: true, flush: 'sync' });

onBeforeUnmount(() => {
  stopDictationClock();
  actions.closePalette();
});

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
      'mobile-hotkeys--dictation-listening': dictationStatusVisible && dictationState.phase === 'listening',
      'mobile-hotkeys--dictation-recovery': dictationTranscriptRecoverable,
      'mobile-hotkeys--dictation-available': dictationAvailable,
    }"
    data-testid="mobile-hotkeys"
    :data-enabled="enabled"
    :data-keyboard-visible="keyboardVisible"
    :data-palette-open="paletteOpen"
    :data-palette-page="paletteOpen ? page : 'closed'"
    :data-dictation-status-visible="dictationStatusVisible"
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
      <div v-if="dictationStatusVisible" class="mobile-hotkeys__dictation-status-row"
        :class="{ 'mobile-hotkeys__dictation-status-row--recording': dictationState.phase === 'listening' }"
        data-testid="inline-dictation-status-row">
        <div v-if="dictationState.phase === 'listening'" class="mobile-hotkeys__recording-status"
          data-testid="inline-dictation-status" data-dictation-tone="quiet" data-dictation-phase="listening"
          role="status" aria-live="polite" aria-label="Terminal listening. Stop inserts the final transcript at the terminal cursor.">
          <span class="mobile-hotkeys__recording-heading">
            <span class="mobile-hotkeys__dictation-destination">{{ 'Terminal' }}</span>
            <span class="mobile-hotkeys__recording-separator" aria-hidden="true">·</span>
            <span class="mobile-hotkeys__dictation-phase">{{ 'Listening' }}</span>
          </span>
          <span class="mobile-hotkeys__recording-elapsed" data-testid="inline-dictation-elapsed" aria-hidden="true">{{ dictationElapsedLabel }}</span>
          <span class="mobile-hotkeys__recording-waveform" data-testid="inline-dictation-waveform" aria-hidden="true">
            <i v-for="(height, index) in dictationWaveformBars" :key="index"
              :style="{ '--wave-height': `${height}px`, '--wave-delay': `${index * 55}ms` }" />
          </span>
          <span v-if="dictationState.preview" class="terminal-dictation-preview mobile-hotkeys__recording-preview"
            data-testid="inline-dictation-preview" aria-live="off">{{ dictationState.preview }}</span>
          <span v-else class="mobile-hotkeys__recording-preview" data-testid="inline-dictation-message" aria-live="off">{{ 'Speak now' }}</span>
        </div>
        <div v-else-if="dictationTranscriptRecoverable" class="mobile-hotkeys__dictation-recovery"
          data-testid="inline-dictation-recovery">
          <div class="mobile-hotkeys__dictation-recovery-header">
            <p class="mobile-hotkeys__dictation-recovery-message" :data-dictation-tone="dictationState.tone"
              data-testid="inline-dictation-status" role="status" aria-live="polite">
              <span class="mobile-hotkeys__dictation-destination">Terminal · </span>
              <span class="mobile-hotkeys__dictation-phase">Warning · </span>
              <span data-testid="inline-dictation-message">{{ dictationCopyFeedback || dictationState.message }}</span>
            </p>
            <button class="mobile-hotkeys__dictation-copy" type="button"
              data-testid="inline-dictation-copy-transcript"
              aria-label="Copy recognized transcript. Check the terminal for partial text before pasting."
              title="Copy recognized transcript. Check the terminal for partial text before pasting."
              @click="copyRecoverableTranscript">
              Copy
            </button>
          </div>
          <span class="mobile-hotkeys__dictation-recovery-preview terminal-dictation-preview"
            data-testid="inline-dictation-preview" :title="dictationState.preview" aria-live="off">
            {{ dictationState.preview }}
          </span>
        </div>
        <p v-else class="mobile-hotkeys__dictation-status" :data-dictation-tone="dictationState.tone"
          :data-dictation-phase="dictationState.phase" data-testid="inline-dictation-status" role="status" aria-live="polite">
          <span class="mobile-hotkeys__dictation-destination">Terminal · </span>
          <span v-if="dictationState.tone === 'error'" class="mobile-hotkeys__dictation-phase">Error · </span>
          <span v-else-if="dictationState.tone === 'warning'" class="mobile-hotkeys__dictation-phase">Warning · </span>
          <span v-else-if="dictationState.phase === 'starting'" class="mobile-hotkeys__dictation-phase">Starting · </span>
          <span v-else-if="dictationState.phase === 'stopping'" class="mobile-hotkeys__dictation-phase">Transcribing · </span>
          <span v-else-if="dictationState.phase === 'cancelling'" class="mobile-hotkeys__dictation-phase">Cancelling · </span>
          <span v-else-if="dictationState.phase === 'inserting'" class="mobile-hotkeys__dictation-phase">Inserting · </span>
          <span v-if="dictationState.preview" class="terminal-dictation-preview" data-testid="inline-dictation-preview" aria-live="off">
            {{ dictationState.preview }}
          </span>
          <span v-else data-testid="inline-dictation-message">{{ dictationState.message }}</span>
        </p>
      </div>

      <div class="mobile-hotkeys__bar" role="toolbar" aria-label="Persistent terminal keys">
        <div
          v-if="promptComposerAvailable"
          class="mobile-hotkeys__input-group"
          data-testid="mobile-hotkeys-prompt-group"
          role="group"
          aria-label="Prompt input"
        >
          <button
            class="mobile-hotkeys__key mobile-hotkeys__composer-launcher"
            type="button"
            data-testid="prompt-composer-launcher"
            :aria-label="promptComposerEnabled ? 'Open prompt composer to type or dictate a prompt' : 'Prompt unavailable while terminal dictation is active'"
            :title="promptComposerEnabled ? 'Open prompt composer to type or dictate a prompt' : 'Prompt unavailable while terminal dictation is active'"
            :disabled="!enabled || !promptComposerEnabled"
            @click="emit('openComposer')"
          >
            <AppIcon name="edit-2" aria-hidden="true" />
            <span class="mobile-hotkeys__dock-label" data-testid="prompt-composer-launcher-label" aria-hidden="true">Prompt</span>
          </button>
        </div>

        <div class="mobile-hotkeys__terminal-group" data-testid="mobile-hotkeys-terminal-group" role="group" aria-label="Terminal controls">
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
            :title="paletteOpen ? 'Close terminal keys' : 'More terminal keys'"
            :aria-expanded="paletteOpen"
            :disabled="!enabled"
            @click="onLauncherClick"
          >
            <svg class="mobile-hotkeys__keys-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor"
              stroke-linecap="round" stroke-linejoin="round" stroke-width="1.8" aria-hidden="true" focusable="false">
              <rect x="3" y="5" width="18" height="14" rx="2" />
              <path d="M7 9h.01M10.5 9h.01M14 9h.01M17.5 9h.01M7 12h.01M10.5 12h.01M14 12h.01M17.5 12h.01M8.5 15.5h7" />
            </svg>
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
      </div>

      <section
        v-if="paletteOpen"
        class="mobile-hotkeys__sheet"
        data-testid="mobile-hotkeys-sheet"
        role="region"
        :aria-label="page === 'ctrl' ? 'Ctrl key catalog' : 'Main key catalog'"
      >
        <header class="mobile-hotkeys__sheet-header">
          <span class="mobile-hotkeys__sheet-title" data-testid="mobile-hotkeys-sheet-title" aria-hidden="true">Keys</span>
          <span
            v-if="page === 'main'"
            class="mobile-hotkeys__main-scroll-hint"
            data-testid="mobile-hotkeys-main-scroll-hint"
            aria-hidden="true"
          >
            Swipe →
          </span>
          <div class="mobile-hotkeys__page-tabs" role="group" aria-label="Key catalog page">
            <button
              class="mobile-hotkeys__page-tab"
              :class="{ 'mobile-hotkeys__page-tab--selected': page === 'main' }"
              type="button"
              data-testid="mobile-hotkeys-back-main-page"
              aria-label="Select Main keys"
              :aria-pressed="page === 'main'"
              :disabled="!enabled"
              @click="showMainPage"
            >Main</button>
            <button
              class="mobile-hotkeys__page-tab"
              :class="{ 'mobile-hotkeys__page-tab--selected': page === 'ctrl' }"
              type="button"
              data-testid="mobile-hotkeys-open-ctrl-page"
              aria-label="Select Ctrl keys"
              :aria-pressed="page === 'ctrl'"
              :disabled="!enabled"
              @click="showCtrlPage"
            >Ctrl</button>
          </div>
        </header>
        <div
          v-if="page === 'main'"
          class="mobile-hotkeys__catalog-scroll mobile-hotkeys__main-keys"
          data-testid="mobile-hotkeys-main-page"
          id="mobile-hotkeys-main-page"
          role="group"
          aria-label="Common terminal keys"
        >
          <div
            v-for="(row, rowIndex) in mainKeyRows"
            :key="rowIndex"
            class="mobile-hotkeys__main-row"
            role="presentation"
          >
            <button
              v-for="key in row"
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
        </div>

        <div
          v-else
          class="mobile-hotkeys__catalog-scroll mobile-hotkeys__ctrl-grid"
          id="mobile-hotkeys-ctrl-page"
          data-testid="mobile-hotkeys-ctrl-page"
          role="group"
          aria-label="QWERTY Ctrl keys"
        >
          <div v-for="(row, rowIndex) in ctrlRows" :key="rowIndex" class="mobile-hotkeys__ctrl-row" role="presentation">
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
.mobile-hotkeys--ctrl-open { height: 144px; }
.mobile-hotkeys--dictation-status-open { height: 80px; }
.mobile-hotkeys--dictation-listening { height: 89px; }
.mobile-hotkeys--dictation-status-open.mobile-hotkeys--main-open,
.mobile-hotkeys--dictation-status-open.mobile-hotkeys--ctrl-open { height: 176px; }
.mobile-hotkeys--dictation-listening.mobile-hotkeys--main-open,
.mobile-hotkeys--dictation-listening.mobile-hotkeys--ctrl-open { height: 185px; }
.mobile-hotkeys--dictation-available { height: 49px; }
.mobile-hotkeys--dictation-available.mobile-hotkeys--dictation-status-open { height: 81px; }
.mobile-hotkeys--dictation-available.mobile-hotkeys--dictation-listening { height: 89px; }
.mobile-hotkeys--dictation-available.mobile-hotkeys--main-open,
.mobile-hotkeys--dictation-available.mobile-hotkeys--ctrl-open { height: 145px; }
.mobile-hotkeys--dictation-available.mobile-hotkeys--dictation-status-open.mobile-hotkeys--main-open,
.mobile-hotkeys--dictation-available.mobile-hotkeys--dictation-status-open.mobile-hotkeys--ctrl-open { height: 177px; }
.mobile-hotkeys--dictation-available.mobile-hotkeys--dictation-listening.mobile-hotkeys--main-open,
.mobile-hotkeys--dictation-available.mobile-hotkeys--dictation-listening.mobile-hotkeys--ctrl-open { height: 185px; }
.mobile-hotkeys--dictation-available.mobile-hotkeys--dictation-recovery { height: 113px; }
.mobile-hotkeys--dictation-available.mobile-hotkeys--dictation-status-open.mobile-hotkeys--dictation-recovery.mobile-hotkeys--main-open,
.mobile-hotkeys--dictation-available.mobile-hotkeys--dictation-status-open.mobile-hotkeys--dictation-recovery.mobile-hotkeys--ctrl-open { height: 209px; }
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
  height: 32px;
  flex: 0 0 32px;
  padding: 0 4px;
}
.mobile-hotkeys--dictation-listening .mobile-hotkeys__dictation-status-row,
.mobile-hotkeys__dictation-status-row--recording {
  height: 40px;
  flex-basis: 40px;
  padding: 0 4px;
}
.mobile-hotkeys--dictation-recovery .mobile-hotkeys__dictation-status-row {
  height: 64px;
  flex-basis: 64px;
  align-items: stretch;
  padding: 0 4px;
}
.mobile-hotkeys__dictation-recovery {
  display: flex;
  width: 100%;
  min-width: 0;
  height: 60px;
  flex-direction: column;
  justify-content: center;
  gap: 2px;
}
.mobile-hotkeys__dictation-recovery-header {
  display: flex;
  min-width: 0;
  height: 28px;
  flex: 0 0 28px;
  align-items: center;
  gap: 6px;
}
.mobile-hotkeys__dictation-recovery-message {
  display: block;
  min-width: 0;
  flex: 1 1 auto;
  overflow: hidden;
  margin: 0;
  color: var(--warning);
  font-size: 10px;
  line-height: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mobile-hotkeys__dictation-recovery-message .mobile-hotkeys__dictation-phase { color: var(--warning); }
.mobile-hotkeys__dictation-copy {
  min-width: 56px;
  height: 28px;
  flex: 0 0 auto;
  border: 1px solid var(--border-strong);
  border-radius: var(--r-sm);
  background: var(--surface-2);
  padding: 0 8px;
  color: var(--fg);
  font-size: 10px;
  font-weight: 600;
}
.mobile-hotkeys__dictation-recovery-preview {
  display: block;
  width: 100%;
  min-width: 0;
  height: 28px;
  overflow-x: auto;
  overflow-y: hidden;
  color: var(--fg);
  font: 11px/28px var(--font-mono);
  text-overflow: clip;
  white-space: nowrap;
  user-select: text;
  -webkit-user-select: text;
  touch-action: pan-x;
}
.mobile-hotkeys__recording-status {
  display: flex;
  width: 100%;
  min-width: 0;
  height: 38px;
  align-items: center;
  gap: 7px;
  overflow: hidden;
  border: 1px solid var(--accent-dim);
  border-radius: var(--r-sm);
  background: var(--state-selected);
  box-shadow: inset 3px 0 var(--accent);
  padding: 0 8px 0 10px;
  color: var(--fg);
  font-size: 11px;
  line-height: 16px;
  white-space: nowrap;
}
.mobile-hotkeys__recording-heading { display: inline-flex; min-width: max-content; align-items: center; gap: 4px; }
.mobile-hotkeys__recording-heading::before {
  content: "";
  width: 7px;
  height: 7px;
  flex: 0 0 7px;
  border-radius: 50%;
  background: var(--accent);
  animation: terminal-recording-pulse 1.2s ease-in-out infinite alternate;
}
.mobile-hotkeys__recording-separator { color: var(--fg-muted); }
.mobile-hotkeys__recording-elapsed {
  flex: 0 0 auto;
  color: var(--accent);
  font: 600 11px/1 var(--font-mono);
  font-variant-numeric: tabular-nums;
}
.mobile-hotkeys__recording-waveform { display: inline-flex; width: 40px; height: 24px; flex: 0 0 40px; align-items: center; justify-content: space-between; }
.mobile-hotkeys__recording-waveform i {
  display: block;
  width: 2px;
  height: var(--wave-height);
  max-height: 21px;
  border-radius: 999px;
  background: var(--accent);
  animation: terminal-recording-wave 620ms ease-in-out var(--wave-delay) infinite alternate;
}
.mobile-hotkeys__recording-preview {
  display: block;
  min-width: 0;
  flex: 1 1 auto;
  overflow: hidden;
  color: var(--fg-secondary);
  font: 11px/1.2 var(--font-mono);
  text-overflow: ellipsis;
}
.mobile-hotkeys__recording-preview.terminal-dictation-preview { color: var(--fg); }
@keyframes terminal-recording-pulse { from { opacity: 1; } to { opacity: 0.5; } }
@keyframes terminal-recording-wave { from { transform: scaleY(0.28); } to { transform: scaleY(1); } }
@media (prefers-reduced-motion: reduce) {
  .mobile-hotkeys__recording-heading::before,
  .mobile-hotkeys__recording-waveform i { animation: none; }
}
.mobile-hotkeys__dictation-status {
  overflow: hidden;
  width: 100%;
  min-width: 0;
  height: 30px;
  margin: 0;
  border: 1px solid var(--border-soft);
  border-radius: var(--r-sm);
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
.mobile-hotkeys__dictation-status[data-dictation-tone="success"] .mobile-hotkeys__dictation-phase { color: var(--success); }
.mobile-hotkeys__dictation-status[data-dictation-tone="warning"] .mobile-hotkeys__dictation-phase { color: var(--warning); }
.mobile-hotkeys__dictation-status[data-dictation-tone="error"] .mobile-hotkeys__dictation-phase { color: var(--error); }
.mobile-hotkeys__dictation-phase { color: var(--fg-secondary); font-weight: 600; }
.mobile-hotkeys__dictation-destination { color: var(--fg-muted); font-weight: 600; }
.mobile-hotkeys__dictation-status .terminal-dictation-preview { color: var(--fg); font-family: var(--font-mono); }
.mobile-hotkeys__dictation-status[data-dictation-tone="success"] .terminal-dictation-preview { color: var(--success); }
.mobile-hotkeys__dictation-status[data-dictation-tone="error"] .terminal-dictation-preview { color: var(--error); }
.mobile-hotkeys__dictation-status[data-dictation-tone="warning"] .terminal-dictation-preview { color: var(--warning); }
.mobile-hotkeys--dictation-available .mobile-hotkeys__dictation-status {
  height: 30px;
  padding: 6px 8px;
  font-size: 11px;
  line-height: 16px;
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
  gap: var(--sp-1);
  overflow-x: auto;
  overflow-y: hidden;
  overscroll-behavior-x: contain;
  scrollbar-width: none;
  touch-action: pan-x;
  padding-inline: 2px;
  background: transparent;
}
.mobile-hotkeys__bar::-webkit-scrollbar { display: none; }
.mobile-hotkeys__input-group,
.mobile-hotkeys__terminal-group {
  display: flex;
  height: 48px;
  min-width: 0;
  flex: 0 0 auto;
  align-items: center;
  border-radius: var(--r-md);
  box-shadow: inset 0 0 0 1px var(--border-soft);
}
.mobile-hotkeys__input-group { gap: 2px; }
.mobile-hotkeys__terminal-group { gap: var(--sp-1); }
.mobile-hotkeys__input-group .mobile-hotkeys__composer-launcher {
  border-color: transparent;
  background: transparent;
  border-radius: var(--r-md);
}
.mobile-hotkeys__input-group .mobile-hotkeys__composer-launcher:hover:not(:disabled),
.mobile-hotkeys__input-group .mobile-hotkeys__composer-launcher:active:not(:disabled) {
  background: var(--state-selected);
}
.mobile-hotkeys__navigation { display: flex; flex: 0 0 auto; align-items: center; gap: var(--sp-1); }
.mobile-hotkeys__enter-divider { width: 1px; height: 24px; flex: 0 0 1px; background: var(--border-soft); }
.mobile-hotkeys__key,
.mobile-hotkeys__launcher,
.mobile-hotkeys__page-tab {
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
.mobile-hotkeys__page-tab:hover:not(:disabled) {
  border-color: var(--border-strong);
  background: var(--state-hover);
}
.mobile-hotkeys__key:active:not(:disabled),
.mobile-hotkeys__launcher[aria-expanded="true"],
.mobile-hotkeys__page-tab:active:not(:disabled) {
  border-color: var(--accent);
  color: var(--accent);
}
.mobile-hotkeys__key--navigation { border-color: var(--border-soft); background: var(--surface-2); font: 600 18px/1 var(--font-ui); }
.mobile-hotkeys__key--enter { font: 600 var(--fs-200)/1 var(--font-ui); }
.mobile-hotkeys__composer-launcher {
  flex-direction: column;
  gap: 1px;
  width: 48px;
  min-width: 48px;
  flex: 0 0 48px;
  border-color: var(--border-soft);
  background: var(--surface-2);
  color: var(--fg);
  padding: 0;
}
.mobile-hotkeys__bar button.mobile-hotkeys__composer-launcher {
  color: var(--fg);
  font: inherit;
}
.mobile-hotkeys__composer-launcher:hover:not(:disabled),
.mobile-hotkeys__composer-launcher:active:not(:disabled) {
  border-color: var(--accent-dim);
  background: var(--state-selected);
  color: var(--accent);
}
.mobile-hotkeys__launcher {
  border-color: var(--border-soft);
  background: var(--surface-2);
  color: var(--fg-secondary);
}
.mobile-hotkeys__launcher[aria-expanded="true"] { border-color: var(--accent); background: var(--state-selected); color: var(--accent); }
.mobile-hotkeys__launcher :deep(svg),
.mobile-hotkeys__keys-icon { width: 20px; height: 20px; }
.mobile-hotkeys__bar button.mobile-hotkeys__composer-launcher :deep(svg) { width: 20px; height: 20px; }
.mobile-hotkeys__dock-label { color: var(--fg-muted); font: 600 var(--fs-100)/1 var(--font-ui); white-space: nowrap; }
.mobile-hotkeys__persistent-slots { display: flex; min-width: 0; flex: 0 0 auto; align-items: center; justify-content: flex-end; gap: 0; }
.mobile-hotkeys__persistent-status { min-width: 0; flex: 1 1 auto; overflow: hidden; color: var(--fg-secondary); font-size: var(--fs-100); line-height: var(--lh-100); text-overflow: ellipsis; white-space: nowrap; }
.mobile-hotkeys__persistent-status :deep(*) { min-width: 0; max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mobile-hotkeys__persistent-controls,
.mobile-hotkeys__persistent-accessory {
  position: relative;
  display: flex;
  width: 48px;
  min-width: 48px;
  max-width: 48px;
  height: 48px;
  flex: 0 0 48px;
  align-items: center;
  justify-content: center;
}
.mobile-hotkeys__persistent-accessory :deep(.terminal-dictation-button:hover:not(:disabled)) {
  border-color: var(--accent-dim);
  color: var(--accent);
}
.mobile-hotkeys__persistent-accessory :deep(.terminal-dictation-button) {
  flex-direction: column;
  gap: 1px;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--border-soft);
  border-radius: var(--r-md);
  background: var(--surface-2);
  padding: 0;
  color: var(--accent);
  font: 600 9px/1 var(--font-ui);
}
.mobile-hotkeys__persistent-accessory :deep(.terminal-dictation-button .terminal-dictation-label) {
  color: inherit;
  white-space: nowrap;
}
.mobile-hotkeys__persistent-accessory :deep(.terminal-dictation-button[data-mic-state="starting"]),
.mobile-hotkeys__persistent-accessory :deep(.terminal-dictation-button[data-mic-state="listening"]) {
  border-color: var(--accent);
  background: var(--surface-2);
  color: var(--accent);
  box-shadow: inset 0 0 0 1px var(--accent-soft);
}
.mobile-hotkeys__persistent-accessory :deep(.terminal-dictation-button[data-mic-state="listening"] .terminal-dictation-label) {
  color: var(--accent);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.01em;
}
.mobile-hotkeys__persistent-accessory :deep(.terminal-dictation-button[data-mic-state="transcribing"]) {
  border-color: var(--warning);
  background: var(--state-selected);
  color: var(--warning);
}
.mobile-hotkeys__persistent-accessory :deep(.terminal-dictation-button[data-mic-state="error"]) {
  border-color: var(--error);
  background: var(--surface-2);
  color: var(--error);
}
.mobile-hotkeys__persistent-accessory :deep(.terminal-dictation-button[data-mic-state="idle"]) {
  border-color: var(--accent-dim);
  background: var(--surface-2);
  color: var(--accent);
}
.mobile-hotkeys__persistent-accessory :deep(.terminal-dictation-button:disabled[data-mic-state="transcribing"]) {
  background: var(--state-selected);
  color: var(--warning);
}
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
.mobile-hotkeys__persistent-accessory :deep(.terminal-dictation-button) {
  justify-content: center;
  color: var(--fg);
}
.mobile-hotkeys__persistent-accessory :deep(.terminal-dictation-button[data-mic-state="idle"]) {
  color: var(--fg);
}

.mobile-hotkeys__sheet {
  display: flex;
  width: 100%;
  min-width: 0;
  height: 96px;
  min-height: 96px;
  flex: 0 0 96px;
  flex-direction: column;
  overflow: hidden;
  border-radius: 0;
  box-shadow: inset 0 1px 0 var(--border-soft);
  background: transparent;
}
.mobile-hotkeys__sheet-header {
  display: flex;
  width: 100%;
  height: 48px;
  min-height: 48px;
  flex: 0 0 48px;
  align-items: center;
  gap: var(--sp-2);
  padding: 0 var(--sp-1);
}
.mobile-hotkeys__main-scroll-hint {
  flex: 0 0 auto;
  margin-left: auto;
  padding: 0 4px;
  color: var(--fg-muted);
  font-size: var(--fs-100);
  line-height: var(--lh-100);
  white-space: nowrap;
}
.mobile-hotkeys__sheet-title {
  display: flex;
  min-width: 0;
  flex: 1 1 auto;
  align-items: center;
  overflow: hidden;
  color: var(--fg-muted);
  font-size: var(--fs-100);
  font-weight: 600;
  letter-spacing: 0.04em;
  text-overflow: ellipsis;
  text-transform: uppercase;
  white-space: nowrap;
}
.mobile-hotkeys__page-tabs { display: flex; height: 48px; flex: 0 0 auto; align-items: stretch; gap: var(--sp-1); }
.mobile-hotkeys__page-tab {
  position: relative;
  width: 56px;
  min-width: 48px;
  flex: 0 0 auto;
  border: 0;
  border-radius: 0;
  background: transparent;
  color: var(--fg-secondary);
  font: 500 var(--fs-300)/1 var(--font-ui);
}
.mobile-hotkeys__page-tab--selected { color: var(--accent); }
.mobile-hotkeys__page-tab--selected::after {
  position: absolute;
  right: var(--sp-2);
  bottom: 4px;
  left: var(--sp-2);
  height: 2px;
  border-radius: var(--r-sm);
  background: var(--accent);
  content: '';
}
.mobile-hotkeys__page-tab:hover:not(:disabled) { background: var(--state-hover); }
.mobile-hotkeys__catalog-scroll {
  display: flex;
  width: 100%;
  height: 48px;
  min-height: 48px;
  flex: 0 0 48px;
  min-width: 0;
  flex-direction: column;
  align-items: stretch;
  gap: 0;
  overflow-x: hidden;
  overflow-y: auto;
  padding: 0 var(--sp-2);
  touch-action: pan-y;
  scroll-snap-type: y mandatory;
  overscroll-behavior-y: contain;
  scrollbar-width: none;
  -webkit-overflow-scrolling: touch;
}
.mobile-hotkeys__catalog-scroll::-webkit-scrollbar { display: none; }
.mobile-hotkeys__main-keys {
  flex-direction: row;
  align-items: center;
  justify-content: flex-start;
  gap: 0;
  overflow-x: auto;
  overflow-y: hidden;
  scroll-snap-type: x mandatory;
  padding-inline: 0;
  padding-right: 0.33px;
  scroll-padding-inline: 0;
  overscroll-behavior-x: contain;
  touch-action: pan-x;
}
.mobile-hotkeys__main-row {
  display: flex;
  width: max-content;
  min-width: max-content;
  height: 48px;
  min-height: 48px;
  flex: 0 0 48px;
  align-items: center;
  gap: var(--sp-2);
}
.mobile-hotkeys__ctrl-grid { justify-content: flex-start; }
.mobile-hotkeys__ctrl-row { display: flex; height: 48px; min-height: 48px; flex: 0 0 48px; align-items: center; justify-content: flex-start; gap: var(--sp-2); scroll-snap-align: start; scroll-snap-stop: always; }
.mobile-hotkeys__key--catalog {
  flex-direction: column;
  gap: 1px;
  border-color: var(--border-soft);
  border-radius: var(--r-md);
  background: transparent;
  color: var(--fg-secondary);
  padding: 2px;
  font: 500 var(--fs-300)/1.1 var(--font-mono);
  scroll-snap-align: start;
  scroll-snap-stop: always;
}
.mobile-hotkeys__main-row > .mobile-hotkeys__key--catalog:last-child {
  scroll-snap-align: end;
  /* Balance the end snap so both edge keys stay inside fractional WebView bounds. */
  margin-right: 0.12px;
}
.mobile-hotkeys__key--catalog:hover:not(:disabled) {
  border-color: var(--accent-dim);
  background: var(--state-hover);
  color: var(--fg);
}
.mobile-hotkeys__key--catalog:active:not(:disabled) { border-color: var(--accent); color: var(--accent); }
.mobile-hotkeys__keycap {
  display: inline-block;
  min-width: 1.6em;
  color: inherit;
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
