import { describe, expect, it } from 'vitest';
import {
  TerminalImeInput,
  attachTerminalImeInput,
  type ImeKeyDown,
} from '../../src/platform/android/terminalImeInput';

const DEL = '\x7f';

function key(keyValue: string, keyCode: number, extra: Partial<ImeKeyDown> = {}): ImeKeyDown {
  return { key: keyValue, keyCode, isComposing: false, ctrlKey: false, altKey: false, metaKey: false, ...extra };
}

const IME_KEY = key('Unidentified', 229);

/**
 * Applies InputConnection edits to a textarea the way Chromium does, firing
 * the same callbacks in the same order the #2952 journey trace recorded:
 * keydown 229, compositionstart, input(insertCompositionText), compositionend
 * on a commit, keyup 229.
 */
class ChromiumTextarea {
  value = '';
  private cursor = 0;
  private composition: [number, number] | null = null;
  readonly sent: string[] = [];
  readonly model: TerminalImeInput;

  constructor() {
    const self = this;
    this.model = new TerminalImeInput(
      {
        get value() {
          return self.value;
        },
        setValue(value) {
          self.value = value;
          self.cursor = value.length;
          self.composition = null;
        },
      },
      (data) => this.sent.push(data),
    );
  }

  get bytes(): string {
    return this.sent.join('');
  }

  private replace(start: number, end: number, text: string): void {
    this.value = this.value.slice(0, start) + text + this.value.slice(end);
    this.cursor = start + text.length;
  }

  private ime(edit: () => void): void {
    expect(this.model.keyDown(IME_KEY)).toBe('ime');
    edit();
    this.model.keyUp(IME_KEY);
  }

  compose(text: string): this {
    this.ime(() => {
      if (!this.composition) {
        this.composition = [this.cursor, this.cursor];
        this.model.compositionStart();
      }
      const [start, end] = this.composition;
      this.replace(start, end, text);
      this.composition = [start, start + text.length];
      this.model.input('insertCompositionText');
    });
    return this;
  }

  composeWord(word: string): this {
    for (let end = 1; end <= word.length; end++) this.compose(word.slice(0, end));
    return this;
  }

  commit(text: string): this {
    this.ime(() => {
      if (this.composition) {
        const [start, end] = this.composition;
        this.replace(start, end, text);
        this.model.input('insertCompositionText');
        this.composition = null;
        this.model.compositionEnd();
      } else {
        this.replace(this.cursor, this.cursor, text);
        this.model.input(text === '\n' ? 'insertLineBreak' : 'insertText');
      }
    });
    return this;
  }

  finish(): this {
    this.ime(() => {
      if (!this.composition) return;
      this.composition = null;
      this.model.compositionEnd();
    });
    return this;
  }

  /** setComposingRegion over the n characters before the cursor: no text changes. */
  recompose(n: number): this {
    this.ime(() => {
      this.composition = [this.cursor - n, this.cursor];
      this.model.compositionStart();
    });
    return this;
  }

  /** Moves the cursor (setSelection with an empty range): the text is unchanged. */
  moveCursor(to: number): this {
    this.ime(() => {
      this.cursor = to;
    });
    return this;
  }

  /** Replaces [start, end) — a selection or a word the keyboard rewrites — committing `text`. */
  replaceRange(start: number, end: number, text: string): this {
    this.ime(() => {
      this.replace(start, end, text);
      this.model.input(text === '' ? 'deleteContentBackward' : 'insertReplacementText');
    });
    return this;
  }

  deleteBefore(n: number): this {
    this.ime(() => {
      this.replace(this.cursor - n, this.cursor, '');
      if (this.composition) this.composition = null;
      this.model.input('deleteContentBackward');
    });
    return this;
  }

  /**
   * A real key (hardware, or one an IME sends as a key event). `xtermBytes`
   * is what xterm's key mapping sends for it when the adapter leaves it to
   * xterm; `defaultEdit` is a textarea change the browser still makes.
   */
  pressKey(down: ImeKeyDown, xtermBytes: string, defaultEdit?: () => void): this {
    const disposition = this.model.keyDown(down);
    if (disposition === 'xterm') this.sent.push(xtermBytes);
    if (disposition === 'xterm' && defaultEdit) {
      defaultEdit();
      this.model.input('deleteContentBackward');
    }
    this.model.keyUp(down);
    return this;
  }

  /** xterm's paste handler: it sends the text itself and empties the textarea; the browser then inserts it. */
  paste(text: string): this {
    this.model.paste();
    this.sent.push(text);
    this.value = '';
    this.replace(0, 0, text);
    this.model.input('insertFromPaste');
    return this;
  }
}

/**
 * What a shell's line editor shows after these bytes: DEL rubs out one
 * character (readline deletes per code point), CR ends the line.
 */
function shellLine(bytes: string): string {
  const line: string[] = [];
  for (const char of Array.from(bytes)) {
    if (char === DEL) line.pop();
    else if (char === '\r') line.length = 0;
    else line.push(char);
  }
  return line.join('');
}

type Listener = (event: never) => void;

/**
 * A terminal container, its xterm textarea, the window and the document, with
 * events dispatched in the browser's order: the adapter's capture listener on
 * the container, then xterm's own textarea listener (recorded unless stopped).
 */
function fakeTerminal() {
  const registry = (owner: string) => {
    const listeners = new Map<string, Listener[]>();
    return {
      listeners,
      addEventListener: (type: string, listener: Listener) => {
        listeners.set(type, [...(listeners.get(type) ?? []), listener]);
      },
      removeEventListener: (type: string, listener: Listener) => {
        listeners.set(type, (listeners.get(type) ?? []).filter((entry) => entry !== listener));
      },
      owner,
    };
  };
  const container = registry('container');
  const windowTarget = registry('window');
  const documentTarget = { ...registry('document'), visibilityState: 'visible', defaultView: windowTarget };
  const textarea = { value: '', ownerDocument: documentTarget };
  const xtermSaw: string[] = [];
  const sent: string[] = [];
  const event = (type: string, init: Record<string, unknown>) => ({
    type,
    target: textarea as unknown,
    stopped: false,
    defaultPrevented: false,
    stopImmediatePropagation() {
      this.stopped = true;
    },
    preventDefault() {
      this.defaultPrevented = true;
    },
    key: '',
    keyCode: 0,
    isComposing: false,
    ctrlKey: false,
    altKey: false,
    metaKey: false,
    ...init,
  });
  const fire = (target: { listeners: Map<string, Listener[]> }, value: ReturnType<typeof event>) => {
    for (const listener of target.listeners.get(value.type) ?? []) {
      if (!value.stopped) (listener as (event: unknown) => void)(value);
    }
  };
  const detach = attachTerminalImeInput({
    textarea: textarea as unknown as HTMLTextAreaElement,
    element: container as unknown as HTMLElement,
    sessionKey: 's',
    sendInput: (data) => sent.push(data),
  });
  return {
    textarea,
    sent,
    xtermSaw,
    detach,
    listenerCount: () =>
      [container, windowTarget, documentTarget].reduce(
        (total, target) => total + [...target.listeners.values()].reduce((sum, list) => sum + list.length, 0),
        0,
      ),
    /** An event on the textarea: window capture, container capture, then xterm. */
    onTextarea(type: string, init: Record<string, unknown> = {}) {
      const value = event(type, init);
      if (type === 'keyup') fire(windowTarget, value);
      fire(container, value);
      if (!value.stopped && value.target === textarea) xtermSaw.push(`${type}:${value.key}`);
      return value;
    },
    /** A keyup that lands on another element (the composer took focus). */
    keyUpElsewhere(init: Record<string, unknown>) {
      fire(windowTarget, event('keyup', { ...init, target: {} }));
    },
    windowBlur() {
      fire(windowTarget, event('blur', { target: windowTarget }));
    },
    background() {
      documentTarget.visibilityState = 'hidden';
      fire(documentTarget, event('visibilitychange', { target: documentTarget }));
    },
    /** One keyboard edit: keydown 229, the textarea change, input. */
    imeCommit(text: string) {
      this.onTextarea('keydown', { key: 'Unidentified', keyCode: 229 });
      textarea.value += text;
      this.onTextarea('input', { inputType: 'insertText' });
      this.onTextarea('keyup', { key: 'Unidentified', keyCode: 229 });
    },
  };
}

const CONTROL = key('Control', 17, { ctrlKey: true });
const CTRL_V = key('v', 86, { ctrlKey: true });

describe('Android terminal IME input (#2952)', () => {
  it('sends each letter of a Gboard-style composing word once, and the committing space', () => {
    const editor = new ChromiumTextarea().composeWord('echo').commit('echo').commit(' ');
    expect(editor.bytes).toBe('echo ');
    expect(editor.sent).toEqual(['e', 'c', 'h', 'o', ' ']);
  });

  it('turns a backspace inside the composing word into one DEL', () => {
    const editor = new ChromiumTextarea().composeWord('word').compose('wor').compose('work').commit('work');
    expect(editor.bytes).toBe(`word${DEL}k`);
  });

  it('sends exactly the DELs and letters an autocorrect replacement needs', () => {
    const editor = new ChromiumTextarea().composeWord('teh').commit('the').commit(' ');
    expect(editor.bytes).toBe(`teh${DEL}${DEL}he `);
  });

  it('re-sends nothing when the keyboard re-opens a committed word as composing text', () => {
    // #2936: `echo PS2 PS936_…` — the re-opened word was flushed a second time.
    const editor = new ChromiumTextarea().composeWord('the').commit('the').commit(' ');
    editor.sent.length = 0;
    editor.deleteBefore(1).recompose(3).compose('then').commit('then').commit(' ');
    expect(editor.bytes).toBe(`${DEL}n `);
  });

  it('sends a committed newline as CR and starts the next line from an empty textarea', () => {
    const editor = new ChromiumTextarea().composeWord('ls').commit('ls').commit('\n');
    expect(editor.bytes).toBe('ls\r');
    expect(editor.value).toBe('');
    editor.composeWord('ok').finish();
    expect(editor.bytes).toBe('ls\rok');
  });

  it('sends a multi-character commit (clipboard chip, voice) once', () => {
    expect(new ChromiumTextarea().commit('ls -la').bytes).toBe('ls -la');
  });

  it('sends a printable key event itself, so typing never hands the line to the composer', () => {
    // LatinIME types digits as KEYCODE_0..9 key events between composing words.
    const editor = new ChromiumTextarea().composeWord('a').commit('a');
    expect(editor.model.keyDown(key('1', 49))).toBe('sent');
    editor.composeWord('b').commit('b');
    expect(editor.bytes).toBe('a1b');
  });

  it('sends a printable key event once even while a word is still composing', () => {
    const editor = new ChromiumTextarea().composeWord('PS');
    expect(editor.model.keyDown(key('2', 50))).toBe('sent');
    editor.commit('PS');
    expect(editor.bytes).toBe('PS2');
  });

  it('leaves Enter, Backspace, Tab, arrows and chords to xterm and absorbs their default edits', () => {
    const editor = new ChromiumTextarea().composeWord('ab').commit('ab');
    // A synthetic Backspace key whose deletion still lands in the textarea:
    // xterm already sent DEL, so the deletion must not send a second one.
    editor.pressKey(key('Backspace', 8), DEL, () => {
      editor.value = editor.value.slice(0, -1);
    });
    expect(editor.bytes).toBe(`ab${DEL}`);
    editor.pressKey(key('Tab', 9), '\t').pressKey(key('Enter', 13), '\r');
    expect(editor.model.keyDown(key('c', 67, { ctrlKey: true }))).toBe('xterm');
    expect(editor.model.keyDown(key('x', 88, { altKey: true }))).toBe('xterm');
    expect(editor.model.keyDown(key('ArrowLeft', 37))).toBe('xterm');
    expect(editor.bytes).toBe(`ab${DEL}\t\r`);
    // After a key xterm handled, the keyboard starts from an empty field.
    editor.model.keyUp(key('c', 67));
    editor.model.keyUp(key('x', 88));
    editor.model.keyUp(key('ArrowLeft', 37));
    expect(editor.value).toBe('');
  });

  it('leaves paste to xterm and does not re-send the pasted text', () => {
    const editor = new ChromiumTextarea().composeWord('git').commit('git').commit(' ').paste('status');
    editor.composeWord('x').commit('x');
    expect(editor.bytes).toBe('git statusx');
  });

  it('deletes a character outside the BMP as one character', () => {
    const editor = new ChromiumTextarea().commit('ok😀');
    editor.deleteBefore(2);
    expect(editor.bytes).toBe(`ok😀${DEL}`);
  });

  it('replays the shared-app IME journey script to exactly the bytes the host must receive', () => {
    // SharedAppDockerJourneyTest#sharedTerminalDeliversImeEditsAsExactBytes,
    // op for op (hardware Tab/Enter and the paste follow the IME script).
    const editor = new ChromiumTextarea()
      .composeWord('echo').commit('echo').commit(' ')
      .composeWord('word').compose('wor').compose('work').commit('work').commit(' ')
      .composeWord('teh').commit('the').commit(' ')
      .deleteBefore(1).recompose(3).compose('then').commit('then').commit(' ')
      .commit('ls -la')
      .commit('\n')
      .composeWord('a').commit('a');
    editor.pressKey(key('1', 49), '1');
    editor.composeWord('b').commit('b').composeWord('ok').finish();
    editor.pressKey(key('Tab', 9), '\t').pressKey(key('Enter', 13), '\r').paste('PASTE1');
    expect(editor.bytes).toBe(`echo word${DEL}k teh${DEL}${DEL}he ${DEL}n ls -la\ra1bok\t\rPASTE1`);
  });

  it('takes composition and input over from xterm in the capture phase, leaving real keys to it', () => {
    const terminal = fakeTerminal();
    terminal.onTextarea('keydown', { key: 'Unidentified', keyCode: 229 });
    terminal.onTextarea('compositionstart');
    terminal.textarea.value = 'hi';
    terminal.onTextarea('compositionupdate');
    terminal.onTextarea('input', { inputType: 'insertCompositionText' });
    terminal.onTextarea('compositionend');
    terminal.onTextarea('keyup', { key: 'Unidentified', keyCode: 229 });
    const printable = terminal.onTextarea('keydown', { key: '7', keyCode: 55 });
    terminal.onTextarea('keydown', { key: 'Enter', keyCode: 13 });
    terminal.onTextarea('paste');

    expect(terminal.sent.join('')).toBe('hi7');
    expect(printable.defaultPrevented).toBe(true);
    // xterm saw only what it maps itself: real keys, keyups and paste.
    expect(terminal.xtermSaw).toEqual(['keyup:Unidentified', 'keydown:Enter', 'paste:']);
    // An event aimed at another element inside the terminal is not touched.
    terminal.onTextarea('input', { inputType: 'insertText', target: {} });
    expect(terminal.sent.join('')).toBe('hi7');

    terminal.detach();
    expect(terminal.listenerCount()).toBe(0);
  });

  it('forgets a held key when focus leaves the textarea before its keyup (blur while held)', () => {
    const editor = new ChromiumTextarea();
    expect(editor.model.keyDown(CONTROL)).toBe('xterm');
    expect(editor.model.keyDown(CTRL_V)).toBe('xterm');
    // Ctrl+V hands focus to the composer: both keyups land there, not here.
    editor.model.releaseKeys();
    expect(editor.model.holdsKeys).toBe(false);
    editor.composeWord('ls').commit('ls');
    expect(editor.bytes).toBe('ls');
  });

  it('forgets a held key when the app goes to the background before its keyup (background while held)', () => {
    const editor = new ChromiumTextarea().composeWord('git').commit('git').commit(' ');
    expect(editor.model.keyDown(key('ArrowUp', 38))).toBe('xterm');
    editor.model.releaseKeys();
    editor.commit('x');
    // The keyboard's text starts afresh after the release; nothing is re-sent.
    expect(editor.bytes).toBe('git x');
  });

  it('releases held keys on the textarea losing focus, a keyup anywhere, window blur and backgrounding', () => {
    const scenarios: Array<[string, (terminal: ReturnType<typeof fakeTerminal>) => void]> = [
      ['textarea focusout', (terminal) => terminal.onTextarea('focusout')],
      ['keyup on the composer', (terminal) => {
        terminal.keyUpElsewhere({ key: 'v', keyCode: 86, ctrlKey: true });
        terminal.keyUpElsewhere({ key: 'Control', keyCode: 17 });
      }],
      ['window blur', (terminal) => terminal.windowBlur()],
      ['visibilitychange to hidden', (terminal) => terminal.background()],
    ];
    for (const [label, release] of scenarios) {
      const terminal = fakeTerminal();
      terminal.onTextarea('keydown', { key: 'Control', keyCode: 17, ctrlKey: true });
      terminal.onTextarea('keydown', { key: 'v', keyCode: 86, ctrlKey: true });
      release(terminal);
      terminal.imeCommit('ls');
      expect(terminal.sent.join(''), label).toBe('ls');
      terminal.detach();
    }
  });

  it('rewrites the line from the edit point when the keyboard edits after a cursor move', () => {
    const editor = new ChromiumTextarea().commit('hello world');
    editor.moveCursor(5);
    expect(editor.bytes).toBe('hello world');
    editor.replaceRange(5, 5, 'X');
    expect(editor.bytes).toBe(`hello world${DEL.repeat(6)}X world`);
    expect(shellLine(editor.bytes)).toBe('helloX world');
  });

  it('replaces a selection with exactly the new text', () => {
    const editor = new ChromiumTextarea().commit('cat old.txt');
    editor.replaceRange(4, 7, 'new');
    expect(editor.bytes).toBe(`cat old.txt${DEL.repeat(7)}new.txt`);
    expect(shellLine(editor.bytes)).toBe('cat new.txt');
  });

  it('deletes inside a word away from the end', () => {
    const editor = new ChromiumTextarea().commit('grep pattern file');
    editor.replaceRange(8, 9, '');
    expect(editor.bytes).toBe(`grep pattern file${DEL.repeat(9)}ern file`);
    expect(shellLine(editor.bytes)).toBe('grep patern file');
  });

  it('turns a predicted space the keyboard removes before punctuation into one DEL', () => {
    const editor = new ChromiumTextarea().composeWord('word').commit('word').commit(' ');
    editor.deleteBefore(1).commit('.');
    expect(editor.bytes).toBe(`word ${DEL}.`);
    expect(shellLine(editor.bytes)).toBe('word.');
  });

  it('sends one DEL per code point when a multi-code-point emoji is deleted', () => {
    const family = '\u{1F468}\u200D\u{1F469}\u200D\u{1F467}'; // 5 code points, 8 UTF-16 units
    const flag = '\u{1F1EB}\u{1F1F7}'; // 2 code points
    const editor = new ChromiumTextarea().commit(`a${family}`);
    editor.deleteBefore(family.length);
    expect(editor.bytes).toBe(`a${family}${DEL.repeat(5)}`);
    expect(shellLine(editor.bytes)).toBe('a');
    const flagged = new ChromiumTextarea().commit(`b${flag}`);
    flagged.deleteBefore(flag.length);
    expect(flagged.bytes).toBe(`b${flag}${DEL.repeat(2)}`);
    expect(shellLine(flagged.bytes)).toBe('b');
  });
});
