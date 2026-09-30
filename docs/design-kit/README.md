# PocketShell Quiet — design kit (v1.0.0, 2026-09-06)

> **Historical.** This kit was delivered for the Kotlin/Compose app, which the
> 0.6.0 JS-first rewrite deleted. Its generated Kotlin (`android/`) was removed
> in #2938. The live design system is the shared Vue/token package in
> pocketshell-core `packages/ui`, documented in
> [`../design-system.md`](../design-system.md). Its `tokens.json` palette is
> **not** what the Vue app applies. Use `catalog.json`, `spec/` and the icons
> as screen and behaviour reference only, and land any value in
> `packages/ui` before a screen uses it. `spec/` is kept verbatim, so its
> references to Kotlin files, Compose and `android/` describe files that no
> longer exist.

The maintainer's full redesign of the app, delivered as a handoff package.

## What is here

| Path | What it is |
|---|---|
| `design-system/tokens.json` | Colour, type, spacing, size, radius and motion as delivered. Superseded by pocketshell-core `packages/ui/src/tokens.css`. |
| `design-system/tokens.css` | Generated from `tokens.json`. Do not hand-edit. |
| `design-system/catalog.json` | All 79 frames: content, route and state descriptions. Machine-readable equivalent of the interactive prototype. |
| `design-system/icons.json` | Icon path data for the SVG icons. |
| `design-system/prototype.{css,js}`, `template.html` | The browser renderer, for regenerating the prototype. |
| `icons/*.svg` | 45 icons, same paths as `icons.json`. |
| `spec/` | `AndroidHandoff.md`, `DesignSystem.md`, `ScreenSpecifications.md`, `AcceptanceTests.md`, `FeedbackTraceability.md`, `screen-inventory.csv`, and the kit's own `KitReadme.md`. |

## What is deliberately not here

The heavy review artifacts are **not** committed — they are large, regenerable,
and the repo is not their home:

- `screens/` (81 PNGs, 6.9 MB), `Screen_library.pdf` (6.2 MB), `index.html`
  (1.9 MB interactive prototype), `qa/` (1.9 MB), `Design_overview.jpg`,
  `assets/approved-reference.png`.

They are attached to the redesign umbrella issue as release assets, following
the same pattern as maintainer screenshots (see the `screenshot-to-issue`
skill). `design-system/catalog.json` carries the same screen data in a form an
agent can actually read.

## Read this before implementing anything

`spec/AndroidHandoff.md` holds the behaviour contract. One point from it is
easy to miss and expensive to get wrong:

1. **A display name is not identity.** Resolve a host-specific canonical
   absolute path and keep the display path separate, or `~/git` and
   `/home/alexey/git` become duplicate roots on the same host.

`spec/AndroidHandoff.md` also carries the route-migration table from every
current destination to its proposed surface, and the native contracts for
status, Back, keyboard insets, drafts, modals, native handoffs and security.
Those are requirements, not suggestions — several encode bugs this app has
already had.

## Changing the design

Do not edit this kit to change the app. Change the shared tokens and
components in pocketshell-core `packages/ui` (see
[`../design-system.md`](../design-system.md)). The kit's own `build.py` and
`tools/`, which regenerate the prototype, were never committed here; they live
in the original kit archive.

## Provenance

Delivered by the maintainer on 2026-09-06 as `PocketShell_Design_Kit.zip`
(16.5 MB) alongside `Screen_library.pdf` and `PocketShell_Prototype.html`.
Attribution for icon and reference sources is in `THIRD_PARTY_NOTICES.md`.
