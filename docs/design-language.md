# Design Language

Termius-inspired. Built once in the `ui-kit` shared module so both PocketShell and `ssh-auto-forward-android` converge.

The numbers for this language — colour, type, spacing, size, radius — live in
[`design-kit/design-system/tokens.json`](design-kit/design-system/tokens.json),
the single machine-readable source of truth (#2717). This document states the
*language*; the JSON states the values. `QuietThemeTokenTest` fails the build
when the Kotlin in `ui-kit` drifts from the file.

## Surface

- Background: deep navy/charcoal, never pure black (see `color.background`)
- Elevated cards: one step lighter than background; hairline 1dp border instead of heavy shadows
- Corner radius: the `radius` ladder — 4dp badge, 8dp chip, 12dp field/button/card, 24dp sheet
- Padding: 16–20dp internal on cards, 12dp between rows

## Colour

- One bright accent (cyan/teal in the Termius spirit) for: active state, connection-state dots, primary actions
- Everything else: neutral grayscale ramp
- Status dots: tiny coloured circles (connected / disconnected / connecting-pulse). Never text labels — the dot carries a steady state with no words; visible state words are reserved for transitional/failed states (#2717 T2), and TalkBack hears the sentence via the dot's `contentDescription` when no words are shown.
- Semantic colour kept to status only (green = ok, amber = warning, red = error). UI chrome stays neutral.

## Type

- UI chrome: system sans (bundling Inter/SF Pro deferred)
- Terminal + inline code: system mono (bundling JetBrains Mono deferred)
- Sizes: the `type` block of `tokens.json` — four rungs from 11sp captions to 20sp screen headings; one restrained scale

## Components (to live in `ui-kit`)

- `StatusDot` — animated for `connecting`, solid for steady states
- `TerminalSurface` — wraps the vendored Termux `terminal-view`; handles swipe/long-press overlays
- `SlideOverPanel` — the port panel pattern; consistent across screens

`HostCard`, `SessionRow`, `Breadcrumb`, and `CommandChip` were removed in the
#2717 dead-canon sweep: they had zero consumers in the shipped app and kept
inviting "converge onto this" rewrites nothing asked for.

## Motion

- Sheet transitions: 200ms ease-out
- Session swipe: 1:1 with finger, snap on release, haptic tick at boundary
- Pulse: connection-state dot pulses while connecting
- No bouncy easings, no parallax. Calm.

## Touch targets

- Minimum 48dp tap area everywhere
- Long-press = always available alternate action
- Edge swipes reserved for quick actions (don't block system back gesture)

## What we are *not* doing

- No iOS-style frosted blur
- No bright illustrations / mascots
- No animated gradients on cards
- No emoji in chrome
