# Input Methods

The full alternative-to-typing strategy. PocketShell reduces keyboard reliance through four coordinated surfaces.

> **0.5.x history in places.** Sections that name Kotlin classes
> (`SessionViewModel`, `HotkeyCatalog.kt`, `KeyBytes.kt` and similar) describe
> the Kotlin app on `release/0.5.x`. On `main`, the 0.6.0 app's key-to-byte
> mapping belongs in pocketshell-core `src/terminalKeys.ts`, and parity for
> these surfaces is tracked under #2854.

## Overview

| Surface | Purpose | Trigger |
|---|---|---|
| Prompt Composer | Voice/text composing for agent prompts | Tap **Prompt** in the persistent dock; use the composer's mic to dictate a prompt |
| Inline dictation | Voice straight into the terminal | Tap the terminal mic in the persistent dock |
| Terminal hotkeys panel | Special keys, control combos, the `Ctrl+…` page's a–z letters, arrows | Tap More keys on `SessionTerminalBar`, or the hotkeys entry in the composer sheet |
| Command chips / snippets | Whole commands or prompt templates | Always-visible chip row when keyboard is down |

For session operations (detach, switch sessions, and stop) PocketShell uses
native UI controls rather than terminal control sequences — see
[Quick navigation](#quick-navigation) below.

---

## Voice input

### Engine

Prompt Composer dictation uses **Android built-in speech recognition** (`SpeechRecognizer`) only. A stored OpenAI API key does not select Whisper on this surface — that route crashed the phone after a v0.4.x upgrade restored the app id (#2520/#2521/#2529).

Availability depends on the device image and installed speech service. Language support, offline packs, network use, and privacy handling are controlled by that service (often Google Speech Services on Play devices), not PocketShell. Prompt partials stream into the draft while speaking; dictation ends when you tap **Stop**.

Whisper / `AudioRecorder` is not started from the composer mic. Future: support self-hosted `whisper.cpp` on one of the user's SSH hosts (out of v1 scope).

### Prompt Composer (primary voice surface)

~90% of voice input happens here, because agent prompts are sentences, not shell commands. Tap **Prompt** in the mobile dock to open this sheet. Tap its mic to dictate; Stop returns the transcript to the editable draft for review before Insert or Send.

```
┌─────────────────────────────────────────┐
│ <  agent-main · main pane         ...   │
├─────────────────────────────────────────┤
│  $ pocketshell sessions list --json    │
│  agent-main: 1 windows (attached)       │  terminal
│  $ _                                    │  (dimmed)
├─────────────────────────────────────────┤
│  Review dictation                   x   │  bottom sheet
│                                         │
│  ┌─────────────────────────────────┐    │
│  │ check the deploy log and tell   │    │
│  │ me what failed in the last run_ │    │  editable transcript
│  └─────────────────────────────────┘    │
│                                         │
│  Transcript ready. Edit it, then choose │
│  Insert or Send.                         │
│                                         │
│  [ Discard ]  [ Insert ]  [ Send ] [MIC]│
└─────────────────────────────────────────┘
```

Behaviours:
- Bottom sheet, modal over terminal (terminal dims behind)
- Single idle control row: grouped 📎 / `{}` / `/` pill on the left; Insert, filled Send, 44dp mic disc on the right. `{}` opens message history; `/` seeds a leading `/` for slash autocomplete.
- Mic tap requests `RECORD_AUDIO` if needed, then starts the system recognizer. Tap Stop in the recording controls to finish. The Android recognizer's own endpointing is treated as a pause, not the end of dictation (explicit-stop-only).
- Partials stream into the draft (and the recording panel) while speaking.
- After Stop, the transcript returns to the editable draft for review before Insert or Send.
- `Insert` writes to PTY without submitting (sheet stays open). `Send` flushes the live field, hides the IME, submits with Enter, and dismisses the sheet so you are back on the terminal with the keyboard down. A send that cannot leave keeps the sheet, the draft, and the undelivered chip.
- Keyboard up: the "Prompt Composer" title row hides; draft + action row sit on the IME. The title returns when the keyboard is down. IME inset is a flag — it is not subtracted from sheet height.
- Recording: timer + waveform replace the editor; Stop is in the recording panel and the bottom row offers `[Discard · Insert · Send]`. Attach / history / slash / mic hide mid-dictation.
- Sheet dismissed = transcript preserved as draft per session

### Inline dictation (escape hatch)

For short shell commands when the prompt composer is overkill. The persistent 48dp terminal control is labeled **Dictate** at rest, so its purpose is visible before recording. Starting dictation closes an open key catalog and enters a separate recording presentation above the unchanged key row: **Terminal · Listening**, elapsed time, waveform, and a preview of partial text, with a filled **Stop** control. Partials stay in the preview and never reach the PTY. Stop finishes recognition; only a validated final transcript is inserted once into the active terminal at its current insertion point. Enter remains a separate action, and no final transcript means no insertion. Starting, transcribing, errors, and unavailable states have distinct status/action labels. The portable preview, stop, and insertion policy lives in JS; Android provides speech recognition through its narrow adapter.

Inline dictation uses the same configured language and silence window as the prompt composer (4s default, adjustable from 2s to 60s under Settings → Advanced). A pause can end an Android recognition segment; PocketShell keeps dictation open until you tap Stop.

Both speech surfaces use the shared `@pocketshell/core` `DictationController` for turn lifecycle, recoverable endpoint restart, request-ID isolation, and explicit Stop behavior. Android starts one `SpeechRecognizer` turn per native call and reports partial, final, recoverable, or error events with that turn's request ID. Partials remain preview-only; the composer draft and terminal input receive finalized segments only, and terminal input is inserted only after explicit Stop. Cancellation on backgrounding or target changes invalidates late callbacks.

The Android dock order is **Prompt**, ↑, ↓, **Enter**, the keyboard icon
(accessible name **More terminal keys**), then the labeled **Dictate** control.
Prompt opens the shared composer; its in-composer mic starts prompt dictation
and returns editable text for review. The separate terminal control starts
inline dictation and becomes a filled, labeled **Stop** action while listening.
Its 40dp recording band sits directly above the 48dp key row and shows the
Terminal destination, Listening state, elapsed time, waveform, and preview.
The active key catalog closes when recording starts, avoiding a competing
surface; it can be reopened afterward if needed. Partials stay out of the PTY,
and Stop inserts validated final text once into the active terminal. The full
key catalog remains in normal terminal flow below the controls while the IME
stays open.

Used for: `git status`, file names mid-command, dictating an `ssh` target.

### Terminal keyboard modes

The embedded `TerminalView` defaults to raw command keyboard mode. Its IME
`inputType` is:

```
TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | TYPE_TEXT_FLAG_NO_SUGGESTIONS
```

Those password-like/no-suggestions flags are intentional. Shell input is
syntax, not prose: paths, flags, package names, branch names, hashes, and
commands can be corrupted if the keyboard silently autocorrects a token while
the text is being written to the PTY.

Settings -> Terminal exposes an explicit Smart text keyboard mode for users
who want swipe/autocorrect in the terminal. That mode requests:

```
TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_NORMAL | TYPE_TEXT_FLAG_AUTO_CORRECT
```

Smart text mode is still guarded: the input connection stages committed IME
text locally and sends it to the terminal only when Enter confirms the buffer.
This avoids byte-by-byte autocorrect churn in a live shell command. Prompt
Composer remains the preferred surface for prose and longer agent prompts.

---

## Terminal hotkeys panel

The terminal controls use a normal-flow dock below xterm. On Android, its
persistent row is ordered **Prompt**, ↑, ↓, **Enter**, the keyboard icon
(accessible name **More terminal keys**), then the labeled **Dictate** control.
Prompt opens the shared composer for typing; its in-composer mic starts prompt
dictation, then shows an editable transcript for review. The separate terminal
control starts inline speech and changes to a visibly labeled **Stop** action
while listening. Its 40dp status band above the row identifies the terminal,
shows Listening, elapsed time, a waveform, and the partial preview. Stop inserts
validated final text once into the active terminal. With the IME open,
the dock reserves its measured height while xterm keeps the same PTY grid and at
least five terminal rows remain visible.

More keys opens a flat, compact catalog in normal terminal flow. Its 48dp
header keeps Main and Ctrl navigation visible. Main presents all ten common
keys in one horizontally scrollable 48dp row; Ctrl scrolls vertically
through its QWERTY rows in the same 48dp viewport. Both pages remain reachable
while the Android IME is open and leave terminal output visually primary.
Key actions map through `@pocketshell/core` and write to the active PTY.
Long-pressing `^C` / `^D` sends the doubled interrupt/EOF sequence. Prompt,
navigation keys, More keys, the terminal mic, and page tabs have 48dp targets.
Prompt has its own labeled input group; navigation keys, More keys, and the
terminal mic share the terminal-controls group.

Main page (`HOTKEY_PALETTE_MAIN_SECTIONS`) — ↑ / ↓ / Enter stay in the
persistent row so they remain one tap away:

```
ARROWS           ←  →
KEYS             Esc  Tab  ⇧Tab
CTRL             ^B  ^C  ^D  ^Q  ^X
                 [Ctrl+…]
```

The `Ctrl` tab opens the separate Ctrl page. Its 48dp targets preserve
keyboard muscle memory in five-column QWERTY rows:

```
Q W E R T
Y U I O P
A S D F G
H J K L
Z X C V B
N M \
```

Each tap immediately sends that key's control byte and leaves the page open,
so sequences such as `^B ^B` need no re-entry. `^Q` is XON (`0x11`) and `^\`
is SIGQUIT (`0x1c`). The `Main` tab returns to common keys; the More keys
button closes the catalog. Reopening starts on the Main page. There is no
hidden sticky-modifier state, and literal letters belong to the system IME.

The Kotlin `TerminalHotkeysPaletteOverlay` and `HotkeyCatalog.kt` describe the
earlier native implementation; the current shared JS controls use the dock and
core key catalog described above.

The main-page `^C` and `^D` keycaps show a persistent `hold ×2` cue. A normal
tap sends one byte; holding sends the existing atomic two-byte sequence (`03
03` / `04 04`) without also firing the single tap. Two ordinary taps remain the
accessible fallback. The same panel and byte path are used for shell and
agent sessions; controls are disabled when the session is not live.

---

## Quick navigation

Session navigation is handled by the native UI. The terminal hotkeys panel is
for bytes that belong to the shell or the foreground workload:

| Action | Native UI |
|---|---|
| Detach session | Tap the back arrow `‹` on the breadcrumb. Session keeps running server-side. |
| Switch session | Tap the session name in the breadcrumb → dropdown of sessions on this host |
| List sessions across hosts | Swipe down to dashboard |
| Stop session | `⋮` menu on the session tree row |

For things genuinely without native UI (vim `Esc :wq`, less `q`, copy mode entry) → the terminal hotkeys panel handles them (direct keys, or `Ctrl+…` + a letter).

A power-user chord palette may return as opt-in settings post-v1 if real demand appears. v1 stays simple.

---

## Command chips / snippets

Already covered in [vision.md](vision.md) §4. Whole commands or prompt templates. Per-host library.

Distinct from the terminal hotkeys panel: chips send literal text strings; the hotkeys panel sends key codes / control bytes (and `Ctrl+<key>` from its `Ctrl+…` page).

---

## Screen real estate

Keyboard up:

```
┌────────────────────────────┐
│   terminal output          │
├────────────────────────────┤
│ Prompt ↑ ↓ Enter [⌨] [Mic]   │  persistent dock, Dictate labeled at rest
├────────────────────────────┤
│  q w e r t y u i o p       │
│   a s d f g h j k l        │  system keyboard
│    z x c v b n m  ⌫        │
└────────────────────────────┘
```

(The More keys icon opens the catalog below the persistent dock. While terminal
dictation is active, its Listening state, elapsed time, waveform, and partial
preview appear in the status band above the dock; the terminal action reads
Stop.)

Keyboard down:

```
┌────────────────────────────┐
│   terminal output          │
├────────────────────────────┤
│ git status   build   logs  │  command chips
├────────────────────────────┤
│ Prompt ↑ ↓ Enter [⌨] [Mic]   │  persistent dock, Dictate labeled at rest
└────────────────────────────┘
```

---

## Settings

Single "Input methods" settings screen with sub-pages:
- Voice: language hint for the system recognizer, and the silence window (default 4s, floor 2s, max 60s; persisted as `voice_silence_seconds`). Dictation is explicit-stop-only — a pause ends a turn, not the recording.
- Terminal hotkeys panel: which keys appear, ordering
- Snippets: organize, share, per-host

---

## Not in v1

- Voice commands inside dictation ("new line", "period") — raw transcript only
- Wake-word activation ("Hey shell") — too unreliable, too battery-hungry
- Chord palette for session-management sequences — native session controls cover this surface.
- Multilingual auto-detection — fixed locale per session, user-configurable
- Self-hosted Whisper on user's own SSH host — on brand but adds setup complexity; deferred
