/**
 * Android's `terminal.inputAdapter` (#2952): soft-keyboard input for the
 * shared terminal, delivered to the shell exactly once.
 *
 * WHY xterm's own handling is not used on Android. A phone keyboard does not
 * type into a WebView with key events. It edits xterm's hidden textarea
 * through the InputConnection: it grows a composing span letter by letter,
 * commits the word on space, rewrites the composing word on autocorrect,
 * re-opens an already-committed word as composing text when the user
 * backspaces into it, and mixes in real key events (AOSP LatinIME sends digits
 * as KEYCODE_0..9; most keyboards send Enter and Delete-on-empty as keys).
 * xterm infers what to send from composition events plus textarea offsets
 * (CompositionHelper), which is built for desktop IMEs: re-opening committed
 * text re-sends it, and a key event arriving mid-composition flushes the
 * composition a second time. #2936 captured both on the API 35 emulator —
 * `echo PS2 PS936_…` and `PS2936_LLATE_…` were the bytes the page wrote.
 *
 * WHAT this does instead. The textarea's value is the keyboard's model of the
 * line, so every IME edit is read back as a difference against what the
 * shell has already been sent ({@link TerminalImeInput.sync}): characters the
 * keyboard removed become DEL, characters it added are sent as typed. Sending
 * eagerly is what every phone terminal does — the shell's own echo shows the
 * word as it is composed, and nothing waits on a commit that some keyboards
 * never send. A re-opened word changes nothing, so it sends nothing; an
 * autocorrect sends exactly the DELs and letters that turn one word into the
 * other. xterm never sees composition or input events (they are taken over in
 * the capture phase on the terminal container, which runs before xterm's
 * textarea listeners), so there is one delivery path.
 *
 * Keys stay xterm's: a key event with a real key code (a hardware keyboard,
 * or a key an IME sends as a key) goes through xterm's keyboard mapping —
 * Enter, Backspace, Tab, arrows, Ctrl chords — exactly as on desktop. Two
 * guards keep that single-delivery too: any textarea change that happens while
 * such a key is down is the key's own default action (xterm has already sent
 * the key's bytes), so it is absorbed rather than diffed — and a held key is
 * forgotten the moment its keyup can no longer be expected here (a keyup
 * anywhere in the window, the textarea losing focus, the window blurring, the
 * app going to the background), because a key left "held" would absorb every
 * later keyboard edit; and a plain
 * printable key is sent here directly instead of through xterm's key handler,
 * whose "typing opens the composer" hand-off would otherwise move focus to the
 * composer on the first digit LatinIME sends as a key, and the rest of the
 * line with it (#2936's `echo share` for `echo shared-app…`).
 *
 * Paste stays xterm's too (bracketed-paste aware): xterm consumes the paste
 * event and clears the textarea, and the textarea change the browser then
 * makes is absorbed.
 */
import type { TerminalInputAdapter, TerminalInputTarget } from '@ui/app/extensions';

/** The DEL byte a terminal's Backspace sends. */
const DEL = '\x7f';
/** Past this, an idle textarea is emptied so diffs stay short. */
const MAX_IDLE_VALUE = 512;

/** The editable the keyboard edits: xterm's helper textarea, or a test double. */
export interface ImeTextSurface {
  readonly value: string;
  /** Replaces the whole value (the keyboard is told the field changed). */
  setValue(value: string): void;
}

/** A keydown as this module needs to see it. */
export interface ImeKeyDown {
  key: string;
  keyCode: number;
  isComposing: boolean;
  ctrlKey: boolean;
  altKey: boolean;
  metaKey: boolean;
}

/** What the binding must do with a keydown. */
export type KeyDownDisposition =
  /** IME traffic: hide it from xterm, let the browser apply it, diff the result. */
  | 'ime'
  /** Sent here; cancel the event and hide it from xterm. */
  | 'sent'
  /** A real key: leave it to xterm's keyboard mapping. */
  | 'xterm';

/**
 * The composition state machine, free of the DOM so it can be driven with the
 * exact edit sequences keyboards produce.
 */
export class TerminalImeInput {
  /** The textarea value the shell has been brought in line with. */
  private sent = '';
  private composing = false;
  /** Real keys currently down that xterm is handling (their default actions are absorbed). */
  private keysDown = new Set<string>();

  constructor(
    private readonly surface: ImeTextSurface,
    private readonly send: (data: string) => void,
  ) {
    this.sent = surface.value;
  }

  get isComposing(): boolean {
    return this.composing;
  }

  keyDown(event: ImeKeyDown): KeyDownDisposition {
    if (isImeKey(event)) return 'ime';
    if (isPlainPrintable(event)) {
      this.send(event.key);
      return 'sent';
    }
    this.keysDown.add(keyIdentity(event));
    return 'xterm';
  }

  keyUp(event: Pick<ImeKeyDown, 'key' | 'keyCode'>): void {
    if (!this.keysDown.delete(keyIdentity(event))) return;
    // A key xterm handled moved the shell's line on (Enter, an arrow, ^C):
    // the keyboard's old text no longer describes it, so start it afresh.
    if (this.keysDown.size === 0 && !this.composing) this.reset();
  }

  /**
   * Focus left the textarea, the window blurred, or the app went to the
   * background: no keyup for a held key is coming here. Forget the held keys
   * (what the keys changed was already delivered by xterm) and start the
   * keyboard's text afresh, so the next edit is diffed, not absorbed.
   */
  releaseKeys(): void {
    if (this.keysDown.size === 0) return;
    this.keysDown.clear();
    if (this.composing) this.absorb();
    else this.reset();
  }

  /** Whether a real key is being treated as held (its default edits are absorbed). */
  get holdsKeys(): boolean {
    return this.keysDown.size > 0;
  }

  compositionStart(): void {
    this.composing = true;
  }

  compositionEnd(): void {
    this.composing = false;
    this.sync();
    this.trimIfIdle();
  }

  /** An `input` event: the browser has applied an edit to the textarea. */
  input(inputType: string): void {
    if (this.keysDown.size > 0 || inputType === 'insertFromPaste' || inputType === 'insertFromDrop') {
      // Already delivered: by xterm's key mapping, or by xterm's paste handler.
      this.absorb();
      return;
    }
    this.sync();
    this.trimIfIdle();
  }

  /** A paste event, before xterm handles it (xterm sends it and empties the textarea). */
  paste(): void {
    this.sent = '';
  }

  /** Send the difference between what the shell has and what the textarea now says. */
  sync(): void {
    const value = this.surface.value;
    if (value === this.sent) return;
    const before = Array.from(this.sent);
    const after = Array.from(value);
    let common = 0;
    while (common < before.length && common < after.length && before[common] === after[common]) common++;
    const removed = before.length - common;
    const added = after.slice(common).join('');
    this.sent = value;
    const data = DEL.repeat(removed) + added.replace(/\r?\n/g, '\r');
    if (data) this.send(data);
    // A newline ended the line: the keyboard's text no longer describes it.
    if (/[\r\n]/.test(added) && !this.composing) this.reset();
  }

  /** Forget the textarea's text without sending anything. */
  reset(): void {
    if (this.surface.value !== '') this.surface.setValue('');
    this.sent = '';
  }

  private absorb(): void {
    this.sent = this.surface.value;
  }

  private trimIfIdle(): void {
    if (!this.composing && this.keysDown.size === 0 && this.surface.value.length > MAX_IDLE_VALUE) this.reset();
  }
}

/**
 * A keydown that belongs to the input method: Chromium reports keyboard
 * edits as keyCode 229 ("Unidentified"/"Process"), and anything inside a
 * composition is the IME's.
 */
export function isImeKey(event: ImeKeyDown): boolean {
  return event.isComposing || event.keyCode === 229 || event.key === 'Unidentified' || event.key === 'Process';
}

/**
 * One printable character with no command modifier (Shift is part of the
 * character). Alt stays xterm's: it is Meta (ESC-prefix) in a terminal, and
 * AltGr arrives with it.
 */
export function isPlainPrintable(event: ImeKeyDown): boolean {
  if (event.ctrlKey || event.altKey || event.metaKey) return false;
  return Array.from(event.key).length === 1 && event.key >= ' ' && event.key !== '\x7f';
}

function keyIdentity(event: Pick<ImeKeyDown, 'key' | 'keyCode'>): string {
  return `${event.keyCode}:${event.key}`;
}

/** Binds a {@link TerminalImeInput} to a terminal's textarea; returns the detach. */
export function attachTerminalImeInput(target: TerminalInputTarget): () => void {
  const { textarea, element } = target;
  const doc = textarea.ownerDocument as Document | null;
  const win = doc?.defaultView ?? null;
  const model = new TerminalImeInput(
    {
      get value() {
        return textarea.value;
      },
      setValue(value) {
        textarea.value = value;
      },
    },
    (data) => target.sendInput(data),
  );
  // Only events aimed at this terminal's textarea; the container also holds
  // xterm's other elements, which this adapter does not touch.
  const fromTextarea = (event: Event) => event.target === textarea;

  const onKeyDown = (event: KeyboardEvent) => {
    if (!fromTextarea(event)) return;
    const disposition = model.keyDown(event);
    if (disposition === 'xterm') return;
    event.stopImmediatePropagation();
    if (disposition === 'sent') event.preventDefault();
  };
  // Keyups are taken from the whole window: a key that went down here can be
  // released after focus moved (Ctrl+V hands focus to the composer).
  const onKeyUp = (event: KeyboardEvent) => model.keyUp(event);
  const release = () => model.releaseKeys();
  const onFocusOut = (event: Event) => {
    if (fromTextarea(event)) release();
  };
  const onVisibility = () => {
    if (doc?.visibilityState === 'hidden') release();
  };
  const onCompositionStart = (event: CompositionEvent) => {
    if (!fromTextarea(event)) return;
    event.stopImmediatePropagation();
    model.compositionStart();
  };
  const onCompositionUpdate = (event: CompositionEvent) => {
    if (fromTextarea(event)) event.stopImmediatePropagation();
  };
  const onCompositionEnd = (event: CompositionEvent) => {
    if (!fromTextarea(event)) return;
    event.stopImmediatePropagation();
    model.compositionEnd();
  };
  const onInput = (event: Event) => {
    if (!fromTextarea(event)) return;
    event.stopImmediatePropagation();
    model.input((event as InputEvent).inputType ?? '');
  };
  const onPaste = (event: Event) => {
    if (fromTextarea(event)) model.paste();
  };

  const listeners: Array<[string, (event: never) => void]> = [
    ['keydown', onKeyDown],
    ['focusout', onFocusOut],
    ['compositionstart', onCompositionStart],
    ['compositionupdate', onCompositionUpdate],
    ['compositionend', onCompositionEnd],
    ['input', onInput],
    ['paste', onPaste],
  ];
  for (const [type, listener] of listeners) element.addEventListener(type, listener as EventListener, true);
  win?.addEventListener('keyup', onKeyUp as EventListener, true);
  win?.addEventListener('blur', release);
  win?.addEventListener('pagehide', release);
  doc?.addEventListener('visibilitychange', onVisibility);
  return () => {
    for (const [type, listener] of listeners) element.removeEventListener(type, listener as EventListener, true);
    win?.removeEventListener('keyup', onKeyUp as EventListener, true);
    win?.removeEventListener('blur', release);
    win?.removeEventListener('pagehide', release);
    doc?.removeEventListener('visibilitychange', onVisibility);
  };
}

/** The Android contribution to the shared app's `terminal.inputAdapter` slot. */
export const androidTerminalInputAdapter: TerminalInputAdapter = {
  id: 'android-ime',
  attach: attachTerminalImeInput,
};
