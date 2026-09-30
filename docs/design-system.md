# PocketShell Design System

PocketShell 0.6.0 is a Vue app running in a Capacitor WebView. It shares one
design system with the desktop (Electron) and web clients: a CSS token set,
theme and font data, a few interaction primitives and a small set of Vue
components. They all live in
[pocketshell-core](https://github.com/PocketShell-io/pocketshell-core) under
`packages/ui/src/`. The Android repository adds only a phone layer on top of
that: safe areas, touch-sized controls, phone navigation and screen layout.

The Compose/Material 3 design system this document used to describe was
deleted with the Kotlin `app2/` and `shared/ui-kit` modules. Its last version is
on the 0.5.x line (`git show release/0.5.x:docs/design-system.md`). Do not use
it, or the Kotlin render harness it described, as a reference for 0.6.0 work.

## Source map

The core paths below are relative to `vendor/pocketshell-core/`, the pinned
core submodule.

| Area | Source |
|------|--------|
| Colour, type, space, radius, density and motion tokens | `packages/ui/src/tokens.css` (`:root`) |
| Theme records (token values plus xterm palette) | `packages/ui/src/themes.ts` (`THEMES`, `resolveTheme`, `THEME_CHOICE_DEFAULT`, `THEME_CHOICE_SYSTEM`) |
| Mono family and size policy | `packages/ui/src/fonts.ts` (`fontCssVariables`, `resolveMonoStack`, `clampFontSize`, `TERMINAL_FONT_SIZE_DEFAULT`) |
| Shared CSS primitives | `packages/ui/src/primitives.css` |
| Stylesheet entry (Inter Variable, tokens, primitives) | `packages/ui/src/styles.css` |
| Shared components | `packages/ui/src/components/AppIcon.vue`, `packages/ui/src/components/ComposerControls.vue` |
| Package entry and consumer contract | `packages/ui/src/index.ts`, `packages/ui/README.md` |
| Desktop/web app views that have not been shared with the phone yet | `packages/ui/src/app/` |
| Android: stylesheet and theme wiring | [`src/main.ts`](../src/main.ts), [`src/sharedUiDefaults.ts`](../src/sharedUiDefaults.ts) |
| Android: phone layer | [`src/styles.css`](../src/styles.css), the `<style>` blocks in [`src/App.vue`](../src/App.vue) and [`src/components/`](../src/components) |
| Android: token guard | [`tests/unit/designTokens.test.ts`](../tests/unit/designTokens.test.ts) |
| Visual brief | [`design-language.md`](design-language.md), [`ux-rules.md`](ux-rules.md), [`decisions.md`](decisions.md) |

**How the alias resolves.** Android imports the package as `@pocketshell/ui`.
The alias is set in `vite.config.ts`, `vitest.config.ts` and `tsconfig.json`.
It still points at the pre-move copy in `vendor/pocketshell-desktop/packages/ui/src/`.
The core issue that moved the package into core states that Android should map
the alias to core's `packages/ui/src` instead. At the current pins the two
copies are identical except `AppIcon.vue`, where core's is newer. Until the
alias moves, a token or component change must land in core. It reaches Android
only once the alias points at core, or the desktop pin is bumped.

When this document and the code disagree, the code in `packages/ui` wins. Fix
this document.

## Tokens

`tokens.css` is the only place a raw design value is written. Everything else
reads the custom properties:

- **Surfaces:** `--bg`, `--surface`, `--surface-2`, `--surface-3`, `--scrim`.
- **Text:** `--fg`, `--fg-secondary`, `--fg-muted`. The WCAG ratios are
  recorded on each line. `--fg-muted` is for text of 15px and larger, or for
  decoration only.
- **Lines:** `--border`, `--border-soft`, `--border-strong` (inputs and
  controls).
- **Accent:** `--accent`, `--accent-dim`, `--accent-soft`, `--on-accent`.
- **Status:** `--success`, `--warning`, `--error`, `--agent` and their
  `-soft` fills.
- **States:** `--state-hover`, `--state-active`, `--state-selected`, the
  focus-ring tokens and `--disabled-opacity`.
- **Elevation:** `--shadow-overlay`, `--shadow-popover`, `--shadow-card`.
- **Terminal and code:** `--term-*` and `--code-*`, derived from Windows
  Terminal's Campbell scheme.
- **Type:** `--font-ui` (Inter Variable) and `--font-mono`. The scale is
  `--fs-100`…`--fs-600` (11, 12, 13, 15, 18, 20px), with matching `--lh-*`
  line heights and the `--fw-*` weights.
- **Space:** `--sp-1`…`--sp-6` (4, 8, 12, 16, 24, 32px).
- **Radius:** `--r-sm` 4px, `--r-md` 6px, `--r-lg` 10px, `--r-xl` 14px.
- **Density:** `--row-h`, `--row-pad-x`, `--row-pad-y`, `--control-h`,
  `--control-h-sm`, `--topbar-h`, `--tabbar-h`. These are desktop sizes; the
  phone layer sets its own touch sizes (see below).
- **Motion:** `--dur-fast` 150ms, `--dur-normal` 200ms, `--dur-slow` 280ms
  (overlay entrance only), `--ease`, `--ease-out`.

## Themes and fonts

`themes.ts` holds one record per theme: its token values, its xterm palette,
and whether it is light or dark. The themes are dark, light, Solarized Light,
Nord, Gruvbox Dark and One Dark. The default is `dark`, and `system` follows
the OS between `dark` and `light`. Applying a theme writes the record's
tokens onto `<html>`. Android does this in `applySharedUiDefaults`
(`src/sharedUiDefaults.ts`), and the Settings screen lists `THEMES` directly.
Add a theme by adding one record in `themes.ts`. Never override token values
per screen.

`fonts.ts` owns the mono policy: one family for the terminal, editor and mono
chrome, and separate terminal and editor sizes. Android passes the fallback
`ui-monospace, monospace` stack. `packages/ui/README.md` says Android should
bundle JetBrains Mono. That is not done yet.

## Shared primitives and components

`primitives.css` defines the focus ring and `.icon-btn` (square ghost icon
button, `.sm` variant). It also has `.btn-ghost` (labelled ghost button),
`.muted`, `.error`, `.empty` and the `.spin` refresh animation. A single
`prefers-reduced-motion` rule covers every transition and animation. Use these
classes before writing a local equivalent.

- `AppIcon` is the single icon set. Its type is `AppIconName`, and it renders
  inline SVG geometry, so there are no icon fonts and no raster images. Add an
  icon in core's `AppIcon.vue`, not as a local SVG.
- `ComposerControls` is the composer's control row: attach, draw, slash
  commands, discard and send. It takes state as props and reports actions as
  events. Android's `PromptComposer.vue` owns the behaviour.

The larger components in `packages/ui/src/app/` are the desktop and web app.
Examples are `SessionTree`, `PromptComposer`, `TerminalView`, `FileTree` and
the `views/`. They depend on Pinia stores and the `PocketShellApi` transport.
Android cannot import them until they are split into presentational parts or
Android implements that API. The phone screens in `src/components/` are local
re-implementations in the meantime, and they must still use only the shared
tokens, primitives and components.

## Phone layer

Android's `src/styles.css` and the component `<style>` blocks own only what is
specific to the phone:

- safe-area padding, driven by `--safe-area-inset-*`. `App.vue` sets these at
  runtime from Capacitor and the IME insets. The composer stays above the IME.
- touch targets. [design-language.md](design-language.md) asks for 48px. The
  workspace navigation buttons are 48px, but most other controls in
  `src/styles.css` are 44px, which is a known gap. Never use the desktop
  `--control-h` (28px) for a tap target.
- the phone app bar, workspace navigation, host/session flow and screen
  layout. The shell caps its width at 1040px on large screens.
- Android Back handling and the keyboard-visible state.

`tests/unit/designTokens.test.ts` runs in the JS unit gate
(`scripts/run-js-unit-gate.sh`). It fails when `src/styles.css`, `App.vue` or a
file in `src/components/` contains a colour literal instead of a token. It also
fails when one of them reads a `var(--…)` token that is not declared in the
shared `tokens.css`, in the phone styles, or in the runtime inset list above.

## Rules

Do:

- Start from a shared token, primitive or component; move a pattern into
  `packages/ui` when a second client needs it.
- Use mono (`--font-mono`) for commands, paths, IDs and ports, not for labels.
- Keep rows dense, but give new tap targets the 48px minimum.
- Put destructive actions behind a confirmation.
- Attach emulator screenshots for any visual change (see below).

Don't:

- Add colours, radii, shadows, motion or font sizes outside `tokens.css`, or
  give a new value no named role.
- Fork a shared component or primitive locally because a screen is "special".
- Use semantic status colours as decoration.
- Nest cards inside cards, or use cards as page sections.
- Add decorative motion.

## Visual checks

- Fast first check: run `pnpm dev` and look at the screen in a browser at a
  phone-sized viewport. It needs no emulator. For a headless screenshot, run
  `chrome --headless=new --window-size=412,915 --screenshot=out.png
  http://localhost:5173/`. The browser has no native SSH, so connected screens
  cannot be reached this way. In dev mode the build-verification banner reads
  "failed", because no packaged manifest exists; that is expected.
- Acceptance: the packaged APK on an emulator, via `scripts/connected-test.sh`
  and its JS-first lanes, with screenshots of the real screen. See
  [review-standards.md](review-standards.md) and [testing.md](testing.md).
  Maintainer mockups are direction, not pixel specs. Compare against them on
  the real app, keyboard included where it matters.

## The design kit (historical)

[`docs/design-kit/`](design-kit/README.md) is the "PocketShell Quiet" handoff
from 2026-09-06, which targeted the Compose app. Its generated Kotlin files
were deleted in #2938. Its `tokens.json` palette and type scale are not what
the Vue app applies. `catalog.json`, `spec/` and the SVG icons remain as
screen and behaviour reference. A value taken from them has to be added to
`packages/ui` before any screen uses it.
