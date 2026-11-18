/**
 * The shared shell's fast-key dock (#3158, S6 of #3118): what each page of the
 * always-visible key row holds and the exact bytes a key sends. Terminal keys
 * come from pocketshell-core's catalog (`terminalKeyBytes`, through the legacy
 * shell's `mobileHotkeysModel` for the Ctrl+C/Ctrl+D hold gesture); digits are
 * plain ASCII, which the core catalog does not list because they need no
 * policy.
 *
 * One row of FAST_KEYS_DOCK_CELLS fixed cells, and it never scrolls (#3158
 * review round 6, maintainer-facing call): a sideways drag on a row at the
 * screen's bottom edge is the Pixel launcher's quick-switch gesture, so there
 * is no sideways drag to make. Six 48px cells fit a 360dp phone beside the
 * shell's side rail (303 CSS px there; 355 at 411dp). Every page's LAST cell
 * is its page key: "More" from the terminal keys, the next page of a longer
 * group, or "Keys" back home. Everything else is one tap on a page:
 *
 *   keys      Esc  Tab  ↑  ↓  ⏎  More
 *   more      ⇧Tab ←  →  Ctrl 123  Keys
 *   digits    1  2  3  4  5  6–0
 *   digits-2  6  7  8  9  0  Keys
 *   ctrl…     five chords per page, most-used first, then Keys
 */
import {
  HOTKEY_CTRL_PAGE_ROWS,
  terminalKeyBytes,
  type HoldableControlKey,
  type TerminalHotkey,
  type TerminalKeyId,
} from '@pocketshell/core';

export type FastKeysDockPage = 'keys' | 'more' | 'digits' | 'digits-2' | 'ctrl' | `ctrl-${number}`;

/** One button on a dock page. */
export type FastKeysDockKey =
  | { kind: 'terminal'; id: TerminalKeyId; label: string; ariaLabel: string; holdable: boolean }
  | { kind: 'digit'; id: `digit-${number}`; label: string; ariaLabel: string; text: string }
  | { kind: 'page'; id: `page-${FastKeysDockPage}`; label: string; ariaLabel: string; page: FastKeysDockPage };

/** Smallest tap target (the key cell) the dock may render, in CSS px (= dp in the WebView). */
export const FAST_KEYS_MIN_TARGET_PX = 48;
/** Smallest visible keycap inside a cell. */
export const FAST_KEYS_MIN_CAP_PX = 44;
/** The dock row's height; the terminal is sized to the space above it. */
export const FAST_KEYS_DOCK_HEIGHT_PX = 48;
/** Cells in the row, on every page: 6 x 48px fits the narrowest phone (360dp) beside the side rail. */
export const FAST_KEYS_DOCK_CELLS = 6;

function terminalKey(id: TerminalKeyId, label: string, name: string): FastKeysDockKey {
  return { kind: 'terminal', id, label, ariaLabel: `Send ${name}`, holdable: isHoldableKey(id) };
}

export function isHoldableKey(id: TerminalKeyId): id is HoldableControlKey {
  return id === 'ctrl-c' || id === 'ctrl-d';
}

function pageKey(page: FastKeysDockPage, label: string, ariaLabel: string): FastKeysDockKey {
  return { kind: 'page', id: `page-${page}`, label, ariaLabel, page };
}

const HOME = pageKey('keys', 'Keys', 'Show terminal keys');

const KEYS_PAGE: readonly FastKeysDockKey[] = Object.freeze([
  terminalKey('escape', 'Esc', 'Escape'),
  terminalKey('tab', 'Tab', 'Tab'),
  terminalKey('arrow-up', '↑', 'Up arrow'),
  terminalKey('arrow-down', '↓', 'Down arrow'),
  terminalKey('enter', '⏎', 'Enter'),
  pageKey('more', 'More', 'Show more keys'),
]);

const MORE_PAGE: readonly FastKeysDockKey[] = Object.freeze([
  terminalKey('shift-tab', '⇧Tab', 'Shift+Tab'),
  terminalKey('arrow-left', '←', 'Left arrow'),
  terminalKey('arrow-right', '→', 'Right arrow'),
  pageKey('ctrl', 'Ctrl', 'Show Ctrl keys'),
  pageKey('digits', '123', 'Show number keys'),
  HOME,
]);

function digitKey(digit: number): FastKeysDockKey {
  return { kind: 'digit', id: `digit-${digit}`, label: String(digit), ariaLabel: `Send ${digit}`, text: String(digit) };
}

const DIGITS_PAGE: readonly FastKeysDockKey[] = Object.freeze([
  ...[1, 2, 3, 4, 5].map(digitKey),
  pageKey('digits-2', '6–0', 'Show number keys 6 to 0'),
]);

const DIGITS_2_PAGE: readonly FastKeysDockKey[] = Object.freeze([...[6, 7, 8, 9, 0].map(digitKey), HOME]);

/**
 * The Ctrl pages are core's QWERTY Ctrl catalog, reordered so the chords a
 * terminal user reaches for most come first. Nothing is added or dropped: the
 * catalog stays the single list.
 */
const CTRL_PRIORITY: readonly TerminalKeyId[] = [
  'ctrl-c', 'ctrl-d', 'ctrl-z', 'ctrl-r', 'ctrl-l', 'ctrl-a', 'ctrl-e', 'ctrl-u', 'ctrl-k', 'ctrl-w', 'ctrl-b',
];

function ctrlName(key: TerminalHotkey): string {
  if (key.id === 'ctrl-backslash') return 'Ctrl+backslash';
  return `Ctrl+${key.id.slice('ctrl-'.length).toUpperCase()}`;
}

const CTRL_KEYS: readonly FastKeysDockKey[] = HOTKEY_CTRL_PAGE_ROWS.flat()
  .map((key, index) => ({ key, index }))
  .sort((a, b) => {
    const ap = CTRL_PRIORITY.indexOf(a.key.id);
    const bp = CTRL_PRIORITY.indexOf(b.key.id);
    const ar = ap === -1 ? CTRL_PRIORITY.length + a.index : ap;
    const br = bp === -1 ? CTRL_PRIORITY.length + b.index : bp;
    return ar - br;
  })
  .map(({ key }): FastKeysDockKey => {
    const holdable = isHoldableKey(key.id);
    const name = ctrlName(key);
    return {
      kind: 'terminal',
      id: key.id,
      label: key.label,
      ariaLabel: holdable ? `Send ${name}; hold to send ${name} twice` : `Send ${name}`,
      holdable,
    };
  });

/** `ctrl`, `ctrl-2`, `ctrl-3`, ...: five chords each, then the next page, the last one Keys. */
function ctrlPageId(n: number): FastKeysDockPage {
  return n === 1 ? 'ctrl' : `ctrl-${n}`;
}

const CTRL_PER_PAGE = FAST_KEYS_DOCK_CELLS - 1;
const CTRL_PAGE_COUNT = Math.ceil(CTRL_KEYS.length / CTRL_PER_PAGE);

const PAGES: Readonly<Record<string, readonly FastKeysDockKey[]>> = Object.freeze({
  keys: KEYS_PAGE,
  more: MORE_PAGE,
  digits: DIGITS_PAGE,
  'digits-2': DIGITS_2_PAGE,
  ...Object.fromEntries(Array.from({ length: CTRL_PAGE_COUNT }, (_, i) => {
    const n = i + 1;
    const chords = CTRL_KEYS.slice(i * CTRL_PER_PAGE, n * CTRL_PER_PAGE);
    const next = n < CTRL_PAGE_COUNT
      ? pageKey(ctrlPageId(n + 1), `${n + 1}/${CTRL_PAGE_COUNT}`, `Show more Ctrl keys (${n + 1} of ${CTRL_PAGE_COUNT})`)
      : HOME;
    return [ctrlPageId(n), Object.freeze([...chords, next])];
  })),
});

/** Every page, home first: what a test walks to prove each key is one tap from somewhere. */
export const FAST_KEYS_DOCK_PAGES: readonly FastKeysDockPage[] = Object.freeze(Object.keys(PAGES) as FastKeysDockPage[]);

/** The keys of a page, in order; the last is the page key. */
export function fastKeysDockPage(page: FastKeysDockPage): readonly FastKeysDockKey[] {
  const keys = PAGES[page];
  if (!keys) throw new Error(`unknown fast-key dock page: ${page}`);
  return keys;
}

/** Terminal bytes as the string `TerminalDockContext.sendInput` takes (all ASCII). */
export function bytesToInput(bytes: Uint8Array): string {
  return String.fromCharCode(...bytes);
}

/** The exact input a one-tap terminal key sends. */
export function terminalKeyInput(id: TerminalKeyId): string {
  return bytesToInput(terminalKeyBytes(id));
}

/** How much shorter than the screen the viewport must be to mean a soft keyboard is up (legacy shell's rule, App.vue). */
export const SOFT_KEYBOARD_MIN_HEIGHT_PX = 150;

/**
 * Whether a soft keyboard is up, read synchronously at the moment of a key
 * press: the WebView's viewport shrinks by the keyboard while the screen does
 * not. Without a screen (tests, SSR) there is no keyboard.
 */
export function softKeyboardUp(
  screenHeight: number | undefined,
  viewportHeight: number | undefined,
): boolean {
  if (!screenHeight || !viewportHeight) return false;
  return screenHeight - viewportHeight > SOFT_KEYBOARD_MIN_HEIGHT_PX;
}
