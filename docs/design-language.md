# Design Language

Termius-inspired. One language across the Android, desktop and web clients.

This document states the *language*. The values (colour, type, spacing,
radius, motion) live in the shared token set, pocketshell-core
`packages/ui/src/tokens.css` and `themes.ts`. [design-system.md](design-system.md)
maps them. The Kotlin `ui-kit` and its `QuietThemeTokenTest` were deleted with
the Compose app. The 2026-09-06 design kit's `tokens.json` is historical and is
not what the app applies.

## Surface

- Background: deep charcoal, never pure black (`--bg`)
- Elevated cards: one step lighter than background (`--surface`); hairline 1px border instead of heavy shadows
- Corner radius: the `--r-*` ladder — `--r-sm` badge/chip, `--r-md` button/field, `--r-lg` card/panel, `--r-xl` overlay
- Padding: the `--sp-*` 4px grid — about 16px inside cards, 12px between rows

## Colour

- One bright accent (cyan/teal in the Termius spirit) for: active state, connection-state dots, primary actions
- Everything else: neutral grayscale ramp
- Status dots: tiny coloured circles (connected / disconnected / connecting-pulse). Never text labels — the dot carries a steady state with no words; visible state words are reserved for transitional/failed states (#2717 T2), and TalkBack hears the sentence via the dot's `contentDescription` when no words are shown.
- Semantic colour kept to status only (green = ok, amber = warning, red = error). UI chrome stays neutral.

## Type

- UI chrome: Inter Variable, bundled (`--font-ui`)
- Terminal + inline code: one mono family for the terminal, editor and mono
  chrome (`--font-mono`); bundling JetBrains Mono on Android is still pending
- Sizes: the `--fs-100`…`--fs-600` ladder (11, 12, 13, 15, 18, 20px), shared
  with the desktop and web clients. `--fs-300` (13px) is the dense workhorse
  rung. Changing a rung is a cross-product change, made in core's
  `tokens.css`, never in one screen.

## Components

Shared components live in pocketshell-core `packages/ui`. See
[design-system.md](design-system.md) for what exists and what the phone may
import. The patterns this language asks for:

- Status dot: animated for `connecting`, solid for steady states
- Slide-over panel: the port panel pattern, consistent across screens

## Motion

- Sheet transitions: 200ms ease-out
- Session swipe: 1:1 with finger, snap on release, haptic tick at boundary
- Pulse: connection-state dot pulses while connecting
- No bouncy easings, no parallax. Calm.

## Touch targets

- Minimum 48px tap area everywhere
- Long-press = always available alternate action
- Edge swipes reserved for quick actions (don't block system back gesture)

## What we are *not* doing

- No iOS-style frosted blur
- No bright illustrations / mascots
- No animated gradients on cards
- No emoji in chrome
