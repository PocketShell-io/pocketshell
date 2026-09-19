# PocketShell Design System

This is the authoritative design-system audit and foundation for issue #461.
It is a docs/spec slice: runtime Kotlin and Compose code must not change as part
of #461 implementation work.

The screen inventory below began before the app2 rewrite. Its Drift and
Standardize-on columns are historical audit input; current product
session UI is the host workspaces screens (`HostWorkspacesScreen` /
`WorkspaceScreen`) plus `SessionScreen`, backed by aplexer (the old
`SessionTreeScreen` was deleted in #2726). New design work must use those
current surfaces and the current paths in `app2/` and `shared/`.

PocketShell is a Material 3 Compose app with a dark, compact dev-tool dialect.
The foundation combines Material 3 structure with terminal/productivity cues
from Warp, VS Code, Termius, and Linear: dark-first surfaces, dense rows,
monospace where content is command- or path-shaped, restrained motion, and
visible but quiet status.

## Source Map

Use these files as citations when migrating screens:

| Area | Current source |
|------|----------------|
| Theme entry point | [`MainActivity.kt`](../app2/src/main/java/com/pocketshell/next/MainActivity.kt), [`Theme.kt`](../shared/ui-kit/src/main/java/com/pocketshell/uikit/theme/Theme.kt) |
| Navigation inventory | [`Destinations.kt`](../app2/src/main/java/com/pocketshell/next/nav/Destinations.kt), [`MainActivity.kt`](../app2/src/main/java/com/pocketshell/next/MainActivity.kt) |
| Colour tokens | [`Color.kt`](../shared/ui-kit/src/main/java/com/pocketshell/uikit/theme/Color.kt) |
| Type tokens | [`Type.kt`](../shared/ui-kit/src/main/java/com/pocketshell/uikit/theme/Type.kt) |
| Spacing and density tokens | [`Spacing.kt`](../shared/ui-kit/src/main/java/com/pocketshell/uikit/theme/Spacing.kt) |
| Shape tokens | [`Shape.kt`](../shared/ui-kit/src/main/java/com/pocketshell/uikit/theme/Shape.kt) |
| Shared components | [`shared/ui-kit/components`](../shared/ui-kit/src/main/java/com/pocketshell/uikit/components) |
| Fast render harness | [`DesignRenders.kt`](../shared/ui-kit/src/test/java/com/pocketshell/uikit/render/DesignRenders.kt), [`scripts/render.sh`](../scripts/render.sh) |
| Emulator visual audit | [`docs/testing.md`](testing.md), [`capture-walkthrough-screenshots.sh`](../scripts/capture-walkthrough-screenshots.sh) over the [`app2/src/androidTest`](../app2/src/androidTest/java/com/pocketshell/next) journeys |
| Visual brief | [`design-language.md`](design-language.md), [`ux-rules.md`](ux-rules.md), [`decisions.md`](decisions.md) |

When this document and the code disagree, treat the disagreement as drift. Fix
the implementation in a migration issue, or update this document only after a
maintainer decision.

## Current Audit

### Tokens

| Token area | Current state | Drift / risk | Decision |
|------------|---------------|--------------|----------|
| Colour | `PocketShellColors` defines dark surface, text, accent, semantic, border, and terminal tokens in [`Color.kt`](../shared/ui-kit/src/main/java/com/pocketshell/uikit/theme/Color.kt). `PocketShellSemanticColors` adds status/agent/accent roles via `LocalPocketShellSemantic`. | Many screens still import raw `PocketShellColors` directly. That is acceptable during migration, but new code should prefer `MaterialTheme.colorScheme` for M3 roles and semantic locals for status/agent roles. Terminal selection still has hard-coded token-equivalent colours in [`SmartSelectionAffordanceOverlay.kt`](../shared/core-terminal/src/main/java/com/pocketshell/core/terminal/selection/SmartSelectionAffordanceOverlay.kt). | Keep the existing dark palette. Add no new colours unless a maintainer approves a new role. |
| M3 scheme | [`Theme.kt`](../shared/ui-kit/src/main/java/com/pocketshell/uikit/theme/Theme.kt) maps background, surface, surfaceVariant, primary, outline, inverse, and error slots. PocketShell is always dark. | `surfaceContainer*`, `secondaryContainer`, and related selected/container slots remain M3 defaults because filling them is a visible change for menus, switches, cards, chips, and segmented controls. | Migrate M3 container slots in a visual-audited slice, not silently in a token-only slice. |
| Type | [`Type.kt`](../shared/ui-kit/src/main/java/com/pocketshell/uikit/theme/Type.kt) mirrors the `type` block of [`tokens.json`](design-kit/design-system/tokens.json) — the single source of truth (#2717): `headlineSmall` screen 20/26, `titleMedium` title 16/22, `bodyMedium` body 14/20, `labelSmall` caption 11/16. `PocketShellType` adds the off-M3 dense/mono/key-cap rungs `bodyDense` 13sp, `bodyMono` 13sp, `labelMono` 11/14, and `keycap` 12/16 (#2812), all of them `type` roles in the same JSON (#2810) — the JSON records their metrics, and the test additionally pins the mono family, which the JSON has no per-role field for. `QuietThemeTokenTest` reads the JSON and fails on drift. | #2812 mapped the composer/hotkeys region (nine freehand sizes: 8, 9, 11, 12, 13, 14, 15, 16, 18sp) onto the rungs and made `TokenLiteralGuardTest` fail on any font-size literal that is not a rung's value; the markdown *document* heading ladder (24/17/15sp) is allowlisted there with its reason, not fixed. | Use M3 slots for normal chrome, `PocketShellType.bodyDense` for compact rows, `bodyMono` for paths/commands/IDs, and `labelMono` for compact code labels. |
| Spacing | [`Spacing.kt`](../shared/ui-kit/src/main/java/com/pocketshell/uikit/theme/Spacing.kt) mirrors the `space` block of `tokens.json`: `xs` 4dp through `xxl` 24dp (the 32dp `section` rung was retired in #2717 — sections separate with `PocketShellDensity.sectionGap`, 24dp). | Enforced since #2812: `TokenLiteralGuardTest` fails on any off-grid `.dp` literal in `app2/src/main`, `shared/ui-kit/src/main` or `shared/ui-screens/src/main` that is not allowlisted with a written reason. The surviving exceptions are hairlines, radii (the `radius` ladder's job), component geometry (strokes, glyph boxes, drawn instruments, key-cap boxes), and the two chip-paint tokens. | Keep the base spacing scale small. Add component-specific geometry tokens only when a pattern repeats across surfaces — and add the `TokenLiteralGuardTest` row that says why it is not spacing. |
| Density | `PocketShellDensity` mirrors the `size` block of `tokens.json` for row minima: `rowMinHeight` 56dp, `workspaceRowMinHeight` 64dp, `tapTargetMin` 48dp, plus component geometry (`rowPadV` 16dp, `chipPadV` 6dp and `chipPadH` 10dp — documented off-grid chip-paint exceptions per #2812, `sectionGap` 24dp, `treeIndent` 16dp, `fieldMin` 56dp input/field floor per #2747). | Some compact rows draw below the visual density target, and some touch areas depend on surrounding layout rather than explicit `sizeIn`. | Visual density can be compact; touch targets stay at least 48dp. |
| Shape | [`Shape.kt`](../shared/ui-kit/src/main/java/com/pocketshell/uikit/theme/Shape.kt) maps the `radius` ladder of `tokens.json` into M3 shape slots: field/button corners (12dp) to `small`/`medium`, sheet corners (24dp) to `large`. The full ladder is `{4 badge, 8 chip, 12 field/button/card, 24 sheet}`. | Screens still create local `RoundedCornerShape` values for micro badges, key slots, cards, and sheets. Some are legitimate component geometry; repeated values should move into shared components. | 4dp badge, 8dp chip, 12dp field/button/card, 24dp sheet. Avoid new radii; `scripts/check-design-tokens.sh` allows exactly the ladder. |
| Elevation | No standalone elevation token. Components mostly use borders; [`MicButton.kt`](../shared/ui-kit/src/main/java/com/pocketshell/uikit/components/MicButton.kt) is the visible exception. | Local surfaces sometimes simulate card hierarchy by adding nested panels. | Hairline borders separate surfaces. FAB/mic is the only normal chrome with shadow. |
| Motion | No `Motion.kt` exists on current `origin/main`. Motion is local and ad hoc, for example `MicButton` uses a recording pulse. | Older docs called `MotionDurations` codified; that is stale. `animate*` / `tween` values cannot be audited centrally today. | Define motion values in this spec now; add code tokens only in a later runtime slice. |

### Shared UI Kit

The current reusable catalog lives under
[`shared/ui-kit/components`](../shared/ui-kit/src/main/java/com/pocketshell/uikit/components):

| Component | Current role | Drift / next use |
|-----------|--------------|------------------|
| `ScreenHeader` | Page title, optional subtitle, compact trailing slot. | Use for every non-terminal full-screen header. Do not invent bespoke top bars. |
| `SectionHeader` | Title-case section label with optional count. | Use above row groups, not as large page headings. |
| `ListRow` | Dense row with leading, title, subtitle, trailing, and click slot. | Canonical row for settings, files, repos, keys, folders, share targets, crash reports, costs, and jobs. |
| `Badge` / `Pill` | Compact labels for agent, shell, active, warning, neutral, and usage states. | Badge roles should replace one-off chips and hand-styled labels. |
| `StatusDot` | Connection/status dot using `ConnectionStatus`. | Prefer this over local dot composables. Extend role mapping if needed. |
| `Kebab` | Shared overflow trigger and menu item model. | Replace raw `DropdownMenu` blocks when menus have common section/destructive/status rows. A kebab opens actions; it must not directly perform or confirm an action. |
| `SegmentedToggle` / `Tabs` | Compact mode/tab controls. | Use for mode switches and Terminal/Conversation tabs; avoid radio groups for view density. |
| `MicButton` / `MicIcon` | Composer dictation FAB and icon. | Shared surface for #453; no second mic glyph or text-only dictate chip. |
| `PocketShellButton` | Canonical button: `ButtonVariant.Primary` (filled accent CTA), `Secondary` (outlined accent), `Text` (muted Cancel/Retry), `Destructive` (red-text confirm). | Use for EVERY tappable button. Replaces all raw Material `Button`/`TextButton` and the per-screen `ButtonDefaults.buttonColors(Accent…)` block. Do not hand-declare button colours, shape, or weight. |
| `LoadingIndicator` | Canonical **indeterminate** loading affordance: `Bar` (linear "in flight" strip) + `Spinner` (circular "something is happening", `SpinnerSize.Small`/`Medium`, optional label). | Use for ANY "busy, no known percentage" state. Replaces all raw Material `LinearProgressIndicator`/`CircularProgressIndicator`. Do not hand-pick a spinner diameter or bar height. |
| `ProgressBar` | Usage/progress fill (**determinate**, `progress: Float`). | Use only when the percentage is KNOWN (usage quota, download). For unknown-duration work use `LoadingIndicator` instead. |

### Implementation Drift Themes

The audit found these repeated drift patterns:

- Raw `PocketShellColors` are still the common path in screens. Keep existing
  code until each surface migrates, but new components should expose semantic
  roles instead of colour parameters.
- Raw `.dp` and `.sp` literals are widespread. Not every literal is wrong, but
  repeated row, chip, card, sheet, and timeline values should move into shared
  components or named geometry constants.
- Some screens have local versions of shared ideas: status dots in
  `PortForwardPanelScreen` and `FolderListScreen`, section headers in session menus,
  rows in file/share/repo/settings surfaces, and tab/segmented controls around
  terminal chrome.
- Motion is not centralized. Until a runtime token is added, new motion must
  cite this spec and stay local to state comprehension.
- Dialog and sheet styling is repeated across host import, snippets, session
  lifecycle, env copy, bootstrap, and composer flows. Standardize the outer
  container and action row before polishing individual content.

## Foundation

### Colour Roles

Dark mode is the product mode. Do not add light-mode conditionals in #461
follow-up work.

Source of truth: `docs/design-kit/design-system/tokens.json`; the production
values ship as `PocketShellColors` (`shared/ui-kit/src/main/java/com/pocketshell/uikit/theme/Color.kt`).

| Role | Token | Hex | Usage |
|------|-------|-----|-------|
| App background | `Background` | `#10171E` | Root surface, page chrome |
| Surface | `Surface` | `#19222B` | Cards, dialogs, sheet content, row groups |
| Elevated surface | `SurfaceElev` | `#222D38` | Nested controls, active chips, key slots |
| Divider | `BorderSoft` | `#2B3946` | Hairline separators, quiet borders |
| Input border | `Border` | `#64778A` | Field/button boundaries, active separators |
| Terminal background | `TermBg` | `#0B1117` | Terminal viewport only |
| Text primary | `Text` | `#F0F3F7` | Headings, row titles, primary labels |
| Text secondary | `TextSecondary` | `#A6B2C1` | Subtitles, inactive chrome |
| Text muted | `TextMuted` | `#92A0B0` | Captions, timestamps, low-emphasis counts |
| Accent | `Accent` | `#53D8EC` | Primary actions, active state, links, mic |
| Accent soft | `AccentSoft` | `#222D38` | Alias of `SurfaceElev`: active chip fill, hint/banner fill (Quiet has no accent-fill tint) |
| Accent border | `AccentDim` | `#64778A` | Alias of `Border`: accent borders, active separators |
| On accent | `OnAccent` | `#082027` | Text/icons on accent fill |
| Status active | `statusActive` | `#5CDF89` | Connected, attached, healthy |
| Status connecting | `statusConnecting` | `#E6BC78` | Connecting, pending, attention |
| Status error | `statusError` | `#F3A1A1` | Failed, blocked, destructive confirmation |
| Agent | `agentAccent` | `#92A0B0` | Agent badges and assistant role marks (Quiet keeps session marks muted) |
| Scrim | `Scrim` | `#00000099` | Non-Material overlays |

`AccentSoft`/`AccentDim`/`agentAccent` are kept as neutral aliases of the Quiet
tokens for source compatibility (`Color.kt`); components move to the Quiet
primitives in later slices.

Rule: semantic colour is for status, role, and action. It is not page chrome.

### Type Scale

| Role | Token | Size | Weight | Usage |
|------|-------|------|--------|-------|
| Screen heading | `MaterialTheme.typography.headlineSmall` | 20sp | Bold | `ScreenHeader` title |
| Title | `titleMedium` | 16sp | SemiBold | Dialog/sheet titles, card titles |
| Body | `bodyMedium` | 14sp | Normal | Normal content text |
| Dense body | `PocketShellType.bodyDense` | 13sp | Normal | Dense rows, conversation summaries, settings rows |
| Mono body | `PocketShellType.bodyMono` | 13sp | Normal mono | Paths, commands, host subtitles, IDs |
| Caption | `labelSmall` | 11sp | Medium | Section labels, timestamps |
| Mono caption | `PocketShellType.labelMono` | 11sp | Normal mono | Tool badges, counters, IDs |
| Key cap | `PocketShellType.keycap` | 12sp | Medium mono | Key-bar and hotkeys-palette key caps (arrows use `title`; `keycapSqueezeSize` 9sp / `keycapCueSize` 8sp are named sub-rung glyph sizes, not rungs) |

Do not use display typography in PocketShell chrome. The viewport is for work,
not marketing.

### Spacing And Density

The spacing rungs and density geometry are the single source of truth in
[`tokens.json`](design-kit/design-system/tokens.json) (`space` and `size`
blocks), mirrored by [`Spacing.kt`](../shared/ui-kit/src/main/java/com/pocketshell/uikit/theme/Spacing.kt);
`QuietThemeTokenTest` pins the two together (#2717) and `TokenLiteralGuardTest`
enforces the grid at the call sites (#2812): a padding, gap or margin that does
not land on a rung fails the ui-kit JVM gate unless it is allowlisted there with
the reason it is component geometry rather than layout spacing. Screen-level padding
should normally be the `lg` 16dp rung; row groups can use the `md` 12dp rung
internal padding when density matters.

Density defaults (Kotlin side; row minima come from the JSON `size` block):

| Token | Value | Usage |
|-------|-------|-------|
| `rowMinHeight` | `tokens.json` `size.listRowMin` (56dp) | Visual minimum for list/tree rows |
| `fieldMin` | `tokens.json` `size.fieldMin` (56dp) | Input/field minimum height (#2747 — composer draft editor floor) |
| `workspaceRowMinHeight` | `tokens.json` `size.workspaceRowMin` (64dp) | Workspace row navigation target |
| `rowPadV` | 16dp | Row vertical padding |
| `screenGutter` | 20dp | Row horizontal padding (the screen gutter rung) |
| `chipPadV` | 6dp | Chip vertical paint — deliberate off-grid exception (#2812) |
| `chipPadH` | 10dp | Chip horizontal paint — deliberate off-grid exception (#2812) |
| `sectionGap` | 24dp (`space.xxl`) | Gap between independent sections (#2717 T3 retired the 32dp `section` rung) |
| `treeIndent` | 16dp | Folder tree nesting |
| `tapTargetMin` | 48dp (`size.touchMin`) | Accessibility floor for touch targets |

Touch target rule: compact paint is allowed; compact hit areas are not.

### Radius, Elevation, And Borders

The radius ladder is the `radius` block of
[`tokens.json`](design-kit/design-system/tokens.json): `{4 badge, 8 chip,
12 field/button/card, 24 sheet}`. `scripts/check-design-tokens.sh` allows
exactly those values.

| Pattern | Radius | Separation |
|---------|--------|------------|
| Micro badges | 4dp (`badge`) | none |
| Chips, key slots | 8dp (`chip`) | 1dp `BorderSoft`; active uses `AccentDim` |
| List rows, cards, fields, buttons | 12dp (`field`/`button`/`card`) | 1dp `BorderSoft` or no border inside a grouped surface |
| Bottom sheets | 24dp top corners (`sheet`) | `Surface` container, no decorative shadow |
| Micro role badges below the ladder | 3-6dp | Local only until promoted into `Badge` |

Do not nest cards inside cards. Use full-width sections, rows, and simple
surface bands.

### Motion

Motion is a spec decision today, not a codified Kotlin token. Use these values
until a future runtime slice adds `Motion.kt`:

| Motion | Duration | Easing | Use |
|--------|----------|--------|-----|
| Fast | 150ms | ease-out | Chip dismiss, menu fade, small highlight |
| Normal | 200ms | ease-out | Sheet open/close, tab change |
| Slow | 400ms | ease-out | Progress fill, recording level easing |
| Pulse | 1.5s loop | steps/two-state | Connecting status only |
| Cursor blink | 1.05s loop | steps/two-state | Terminal/caret only |

No decorative motion. No idle animation that implies background work or drains
battery. Animation must communicate state.

## Component Catalog

### App Shell

Use `PocketShellTheme` at the root. Full-screen pages use a `Surface` on
`Background`, then `ScreenHeader`, then dense content. Terminal pages are the
exception: terminal viewport is full-height and owns keyboard behavior.

Do:

- Use `ScreenHeader` for title/subtitle/trailing actions.
- Keep headers compact and adjacent to content.
- Use icon buttons or `Kebab` for secondary actions.

Don't:

- Add large hero sections, marketing copy, or nested panel chrome.
- Add a custom top bar when `ScreenHeader` fits.

### Buttons: the canonical `PocketShellButton` (#756)

Before #756 there were **zero** ui-kit buttons. The audit (#756) found ~142 raw
Material `Button`/`TextButton`/`IconButton` call sites, and the **same** accent
primary-CTA colour block
(`ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent,
disabled… )`, plus `fontWeight = SemiBold` re-applied per call) was hand-copied
into **9 different files** — so the filled-button colour/weight/shape was defined
nine different times and the muted Cancel/Retry treatment was whatever Material
defaulted to per theme.

[`PocketShellButton`](../shared/ui-kit/src/main/java/com/pocketshell/uikit/components/PocketShellButton.kt)
is the single button every tappable button site converges onto. Pick a
`ButtonVariant`, pass a label — colour, shape (8dp), typography, and the disabled
treatment all come from the theme tokens, never from a per-call `colors` block.

| Variant | Use | Treatment |
|---------|-----|-----------|
| `ButtonVariant.Primary` | The page/dialog's ONE main affordance (Add host, Save, Start, Create). | Filled accent container, `OnAccent` SemiBold label. |
| `ButtonVariant.Secondary` | A lower-emphasis affirmative action beside a Primary (e.g. "Browse" next to "Save"). | Outlined: accent label + `accentDim` border, no fill. |
| `ButtonVariant.Text` | The muted, chrome-less action: Cancel / Retry / dialog dismiss / inline links. | No container, `TextSecondary` label. |
| `ButtonVariant.Destructive` | The confirm action of a delete/reset/stop flow. | Red TEXT only (NOT a filled red slab) — matches "destructive confirmation uses red text only on the confirm action". |

Rules:

- **Pick a variant, never a `colors`/`shape`.** There is intentionally no
  per-call colour or radius knob. If a genuinely new treatment is needed, add a
  `ButtonVariant` (and justify it) rather than passing raw values at the call
  site.
- **One Primary per action group.** A screen / dialog action row has at most one
  Primary; everything else is Secondary / Text.
- **Canonical dialog action row** is a `Text` Cancel followed by a `Primary`
  confirm, right-aligned. Destructive dialogs swap the confirm for a
  `Destructive` variant.
- The label-`String` overload is the common case; the `content` slot overload is
  the escape hatch for an icon + label button (it still inherits the variant
  container/shape/disabled treatment).
- Disabled state collapses to ONE muted treatment across all variants
  (`Border` container / `TextMuted` label for filled, muted label for the rest).

### Rows

`ListRow` is the default row primitive. It has leading, title, optional subtitle,
trailing, and click slots. Use `bodyDense` title and mono subtitle when the
subtitle is a path, host, command, or ID.

Long-name invariant: row titles are flexible and ellipsized; trailing controls
are fixed-size and non-shrinking. A long session, host, file, folder, repo, key,
or job name must never compress away the kebab, action button, badge, or status
target. Every interactive trailing control keeps at least a 48dp touch target.

Standard row variants:

| Variant | Pattern |
|---------|---------|
| Navigation row | title, text subtitle, trailing chevron |
| File/path row | title, mono path subtitle, file/folder leading icon |
| Status row | leading `StatusDot`, title, mono/detail subtitle, trailing badge |
| Destructive row | normal row text, destructive action only in menu/dialog confirm |
| Loading row | title stays stable, trailing progress/spinner |

Do not make one-off row padding or font sizes unless the row is a terminal
viewport renderer.

### Row Grammar: The Two Tiers (#2759)

The app has exactly two row height tiers. Both are `ListRow` underneath —
[`WorkspaceRow`](../shared/ui-kit/src/main/java/com/pocketshell/uikit/components/WorkspaceRow.kt)
composes it — so the leading / title / subtitle / trailing slot grammar is
identical; only the density rung and the row's job differ.

| Tier | Component | Min height | Treatment | Use for |
|------|-----------|------------|-----------|---------|
| Primary navigation | `WorkspaceRow` | 64dp — `PocketShellDensity.workspaceRowMinHeight` (`tokens.json` `size.workspaceRowMin`) | `PocketShellType.workspace` SemiBold title, 2-line title/subtitle, trailing `NavigationChevron` | Workspace drill-in targets. The whole row is the one affordance; no kebab. |
| Standard | `ListRow` | 56dp — `PocketShellDensity.rowMinHeight` (`tokens.json` `size.listRowMin`) | `body`/`metadata` by default, `bodyDense` for dense content | Every entity and content row: hosts, sessions, keys, files, settings, tools, empty states. |

Rules:

- Pick the tier by what the row opens, not by which screen it is on. A row that
  drills into a workspace subtree is `WorkspaceRow`; everything else — including
  root-level session rows rendered next to workspace rows — stays `ListRow`.
- The tiers legitimately sit in one list. The host workspaces screen stacks
  64dp workspace rows directly above 56dp root-session rows; that 8dp step is
  the hierarchy signal, not a bug to normalize away.
- Navigation rows carry no inline actions. Their actions live at the scope that
  owns them (host tools in the header kebab, root actions in the section-label
  sheet — see Overflow Menus below). Content rows may carry the one row kebab.
- Both tiers clear the 48dp `tapTargetMin` floor; the taller rung is hierarchy,
  not hit-area compensation.

Shipped reference:
[`HostWorkspacesScreen.kt`](../app2/src/main/java/com/pocketshell/next/workspaces/HostWorkspacesScreen.kt)
— workspace rows at :987-997, root-session `ListRow`s via
`WorkspaceSessionRow` at :1003-1020.

### Sections

Use `SectionHeader` for row groups. Count is inline (`Title - N` or equivalent
component text), not a separate chip unless it is actionable.

Empty sections render a compact neutral row or empty-state block. Do not render
large empty cards with explanatory copy.

### Badges, Pills, And Status Dots

Use `Badge` for role and state labels, `Pill` for short status/cost/quota labels,
and `StatusDot` for live connection state. Dots carry status at a glance; nearby
text supplies context for accessibility.

Do:

- Use green only for active/healthy.
- Use amber for connecting, idle attention, or caution.
- Use red only for error or destructive confirmation.
- Use purple only for agent/assistant role.

Don't:

- Use semantic colours for generic chrome.
- Spell status in all caps in every row.

### Cards And Grouped Surfaces

Cards are repeated items such as usage provider cards and summary cards. A
card uses `Surface`, the `card` rung of the `tokens.json` radius ladder (12dp),
and a quiet border. Prefer rows inside a full-width section when content is
navigational or list-like.

Do not put cards inside cards. If content needs hierarchy, use spacing, section
headers, leading icons, and muted text.

### Sheets And Dialogs

Sheets handle browse/select/create flows. Dialogs handle confirmation, short
text entry, passphrase/API-key entry, and blocking errors.

Standard sheet pattern:

- `ModalBottomSheet`, `Surface` container, 24dp top corners (`tokens.json` `radius.sheet`).
- [`SheetHeader`](../shared/ui-kit/src/main/java/com/pocketshell/uikit/components/SheetHeader.kt)
  title row, optional search/filter, dense `LazyColumn`, fixed action row only
  when needed.
- Rows use `ListRow`, `Badge`, `StatusDot`, and `Kebab` where possible.

`SheetHeader` is the canonical bottom-sheet title surface: `titleMedium`
SemiBold title, optional muted dense subtitle, optional trailing slot, and an
optional close affordance. The sheet body keeps owning its height, scrolling,
and horizontal padding; the header owns only the title/subtitle/action grammar.

Standard dialog pattern:

- `AlertDialog` on `Surface`.
- `titleMedium` title, `bodyMedium` or `bodyDense` body.
- Primary action uses `Accent`; destructive confirmation uses red text only on
  the confirm action.
- Cancel action is muted.

### Overflow Menus

Use `Kebab` where the menu is row/screen overflow. Raw `DropdownMenu` is allowed
only where the menu needs custom anchoring or section layout, and then should
copy the shared density and text treatment.

Overflow invariant: tapping a kebab opens an action menu or sheet. It never
directly triggers a destructive confirmation. Destructive actions are explicit
menu items such as "Stop session" or "Delete key"; selecting that item then
opens the confirmation dialog.

Kebab scoping — row vs screen (#2759):

- **Row kebab** (`Kebab` in the row's trailing slot, menu anchored to the row)
  carries actions on that row's one item: Edit/Delete on a host
  ([`HostListScreen.kt:165-169`](../shared/ui-screens/src/main/java/com/pocketshell/next/hosts/HostListScreen.kt)),
  Delete on a key
  ([`SshKeysScreen.kt:688-695`](../app2/src/main/java/com/pocketshell/next/hosts/SshKeysScreen.kt)).
- **Screen kebab** (`KebabTrigger` in the `ScreenHeader` trailing slot opening
  a bottom sheet; `UsageScreen` uses a header-anchored `Kebab` for its single
  screen action) carries screen-scoped actions only — things that act on the
  whole surface, never on one row: host tools
  ([`HostWorkspacesScreen.kt:271-277`](../app2/src/main/java/com/pocketshell/next/workspaces/HostWorkspacesScreen.kt)),
  workspace actions (`WorkspaceScreen.kt:178`), terminal actions
  (`SessionScreen.kt:381`), file tools (`FileExplorerScreen.kt:346`), viewer
  actions (`ViewerScreen.kt:259`), "Refresh usage" (`UsageScreen.kt:180`).
- Never scope-hop. An item action in the screen sheet would fire on the wrong
  target; a screen action repeated in every row would render once per row.
- Scoped-group variant: where a screen shows several scoped groups (workspace
  roots), the `SectionHeader` label opens a sheet for that group's subtree
  (`RootActionsSheet`) — group actions belong to neither the rows nor the
  screen menu.

Menus should group actions by scope:

- "In this session"
- "On this host"
- "Diagnostics"
- "Danger"

Destructive actions stay in overflow plus confirmation, not on primary rows.

### Composer Controls

The composer system includes `UnifiedComposer`, `PromptComposerSheet`,
`MicButton`, attachment chips, snippet entry, send controls, and pending
transcription states.

Do:

- Use the shared `MicButton` and `MicIcon` for all dictation entry points.
- Use paperclip/attachment affordances for attachments, not text-heavy buttons.
- Keep recording/transcribing state visible but compact.
- Route conversation sends through the prompt composer, not terminal keybar.

Don't:

- Add a second "dictate" chip beside the mic FAB.
- Invent a new recording glyph or state colour.

### Terminal Chrome And Keybar

Terminal screens use `TermBg`, `TerminalHotkeysPanel`, the in-session
`SessionTabStrip`, the closed-session `SessionLauncherBar`, and right-reachable
bottom controls. (`KeyBar` and `CommandChip` were retired with the #2717
dead-canon sweep; the hotkeys chip styling now lives inside `SessionLauncherBar`.)

Do:

- Offer terminal key chrome only for terminal input, not Conversation.
- Keep Terminal/Conversation as compact tabs.
- Put terminal content in the blacker terminal viewport.
- Keep controls reachable near the bottom/right thumb area.

Don't:

- Let command chips push primary composer/snippet controls off screen.
- Add text-heavy chrome above the terminal viewport.

### List And Tree Hierarchy

Folder and session hierarchy uses indentation, section headers, `StatusDot`,
`Badge`, and dense `ListRow` grammar. Actions collapse into overflow.

Do:

- Use `treeIndent` per level.
- Keep folder/session rows compact.
- Show agent/shell with `Badge`.
- Keep `+` create action visible where it is the primary next action.

Don't:

- Use ASCII tree glyphs in row titles.
- Put per-folder "E / ..." action clutter in the main scan path.

### Disclosure (expand/collapse) — the canonical `DisclosureIcon` (#840)

Every expand/collapse row uses the ONE shared `DisclosureIcon`
(`shared/ui-kit/.../components/DisclosureIcon.kt`): a single filled triangle
drawn once and **rotated** 90° (▶ collapsed → ▼ expanded), animated ~120ms.
Collapsed and expanded are provably the same shape turning, never a glyph swap.

Do:

- Use `DisclosureIcon(expanded = …)` for any inline expand/collapse toggle
  (conversation tool-call card, composer pending-queue, folder/session tree,
  system-note rows).
- Pass `tint` to match the surface (muted-secondary by default; accent where the
  row is accent-coloured).

Don't:

- Hand-roll a `Text("›")` / `Text("v")` glyph pair, or draw two separate
  triangle `Path`s for the two states — that is the #840 "two different icons"
  bug. Hard-cut (D22): there is no second disclosure affordance.
- Confuse it with the navigation drill-in chevron (`NavigationChevron` on nav
  `ListRow`s) or the dropdown trigger (`▾`) — those are different, deliberate
  affordances.

### Empty, Loading, And Error States

States should occupy the same structural area as the eventual content.

| State | Pattern |
|-------|---------|
| Loading | Screen header remains, content area shows the canonical `LoadingIndicator` (see below) |
| Empty | One neutral message row plus one primary action if useful |
| Error | Red status/badge, concise message, retry action |
| Permission/setup needed | Amber attention badge/row plus direct action |

Do not use full-page explanation cards unless the screen has no other content.

#### Loading: the canonical `LoadingIndicator` (#756)

Loading is the maintainer's #1 consistency complaint — "sometimes a bar,
sometimes a spinning thing". The cause was structural: the only ui-kit progress
component (`ProgressBar`) was determinate-only, so every "busy, no known
percentage" site fell back to a raw Material 3 indicator hand-configured with
its own size, stroke, colour, and track (the audit found ~21 sites at 8
different spinner diameters and 3+ bar heights). The fix is one shared
component, [`LoadingIndicator`](../shared/ui-kit/src/main/java/com/pocketshell/uikit/components/LoadingIndicator.kt),
with two indeterminate variants. The progress vocabulary is now exactly three
things:

| Affordance | When | API |
|------------|------|-----|
| `LoadingIndicator.Bar()` | Indeterminate, **in-flight strip** — first-connect, reconnecting, refresh. The standard top/inline progress bar. | One height + accent fill on a muted track. No height knob. |
| `LoadingIndicator.Spinner(size, label?, onAccent?)` | Indeterminate, **"something is happening"** — full-screen/section loaders, inline row reveals, pending items, in-button submit progress. | Diameter + stroke come from the enumerated `SpinnerSize` (`Small` inline, `Medium` centered). Optional `label` ("Attaching…", "waiting for session data…") renders below. Set `onAccent = true` for a spinner shown ON an accent-filled surface (e.g. a primary CTA mid-submit) so the arc inverts to the on-accent content colour and stays visible. **Never** a raw spinner `dp`. |
| `ProgressBar(progress, kind)` | **Determinate** — percentage is known (usage quota, download). | The existing `Float` API; the percentage-known sibling. |

Rules:

- Pick a `SpinnerSize` rung, never a free `dp`. If a genuinely new geometry is
  needed, add a rung to the `SpinnerSize` enum (and justify it) rather than
  passing a raw value at the call site — that is what stops the 8-diameter
  drift from coming back.
- Colour comes from `LocalPocketShellSemantic` (accent fill / muted track), so
  no call site reintroduces a raw Material default or per-screen hex. The one
  exception is `Spinner(onAccent = true)`, which paints the canonical on-accent
  content colour (same as an accent button's label) for spinners drawn ON an
  accent fill — use it ONLY inside an accent-coloured container.
- `LoadingIndicator` is decorative + label only (no `onClick`). Any
  cancel/retry lives in a button or row beside it.

Migrating the ~21 existing raw indicators onto this component is tracked as
follow-up slices (shared spinners/bars first; terminal loading bars are gated by
the current connection work).

## Screen And Sheet Inventory

> Audit snapshot: every source link below points at a surface that exists on
> `main` (#2794 repointed the survivors at `app2/` / `shared/ui-screens/` and
> deleted the rows for screens the rewrite removed). The Drift and
> Standardize-on columns are still pre-rewrite audit input and have not been
> re-audited against the current screens. Components named in them that are
> not in the catalog above — `HostCard`, `SessionRow`,
> `Breadcrumb`, `KeyBar`, `CommandChip` — were retired as dead canon in #2717
> and must not be reintroduced.

### Primary Navigation Screens

This inventory is from [`Destinations.kt`](../app2/src/main/java/com/pocketshell/next/nav/Destinations.kt)
and the `NavHost` graph in [`MainActivity.kt`](../app2/src/main/java/com/pocketshell/next/MainActivity.kt).

| Screen | Current components / patterns | Drift | Standardize on |
|--------|-------------------------------|-------|----------------|
| Host list [`HostListScreen.kt`](../shared/ui-screens/src/main/java/com/pocketshell/next/hosts/HostListScreen.kt), [`HostListRoute.kt`](../app2/src/main/java/com/pocketshell/next/hosts/HostListRoute.kt) | `ScreenHeader`, `SectionHeader`, `HostCard`, `Kebab`, host import/share/passphrase dialogs, usage badges. | Strongest shared-component adoption, but host trailing/usage/overflow still owns local glue and dialogs repeat styling. | Keep `HostCard`; move per-host usage/status/overflow grammar into reusable row/card slots. |
| Add/Edit host [`AddEditHostScreen.kt`](../app2/src/main/java/com/pocketshell/next/hosts/AddEditHostScreen.kt) | `ScreenHeader`, local tabs, text fields, key dropdown, discard dialog. | Form field, dropdown, and tab styling are local. Embedded key management is not expressed as shared rows. | Shared form section, field error pattern, `SegmentedToggle` or tabs, shared dialog actions. |
| Settings [`SettingsScreen.kt`](../shared/ui-screens/src/main/java/com/pocketshell/next/settings/SettingsScreen.kt), [`SettingsRoute.kt`](../app2/src/main/java/com/pocketshell/next/settings/SettingsRoute.kt) | `ScreenHeader`, `SectionHeader`, `ListRow`, switches/sliders/dialogs, API-key dialogs. | Good row adoption, but controls and repeated API-key dialogs are local. | Settings section + settings row component; shared secret-entry dialog. |
| Usage [`UsageScreen.kt`](../app2/src/main/java/com/pocketshell/next/usage/UsageScreen.kt) | `Breadcrumb`, `Pill`, `ProgressBar`, provider cards, blocked badges. | Uses custom card grammar rather than shared card/list-row pattern. | Usage provider card tokenized with shared badge/progress roles. |
| Diagnostics / crash reports [`CrashReportsScreen.kt`](../app2/src/main/java/com/pocketshell/next/crash/CrashReportsScreen.kt) | `ScreenHeader`, `ListRow`, mono body for report snippets. | Mostly aligned; long report text needs shared mono detail treatment. | Keep `ListRow`; standardize detail panes/empty states. |
| Services and tunnels [`ServicesScreen.kt`](../app2/src/main/java/com/pocketshell/next/ports/ServicesScreen.kt), [`TunnelDetailScreen.kt`](../app2/src/main/java/com/pocketshell/next/ports/TunnelDetailScreen.kt), [`AddTunnelScreen.kt`](../app2/src/main/java/com/pocketshell/next/ports/AddTunnelScreen.kt) | Local status dot, tables, toggles, dense rows, semantic colours. | Does not use shared `StatusDot` or row/card catalog consistently. | `StatusDot`, metric/list rows, progress/error roles, shared table-density rules. |
| Workspace roots [`WorkspaceRootsScreen.kt`](../app2/src/main/java/com/pocketshell/next/settings/WorkspaceRootsScreen.kt) | `ScreenHeader`, `ListRow`, `Kebab`, `SegmentedToggle`, dialogs. | Section cards and edit dialogs are local. | Shared settings/list management rows, shared add/edit folder dialog. |
| File viewer [`ViewerScreen.kt`](../app2/src/main/java/com/pocketshell/next/files/ViewerScreen.kt) | `ScreenHeader`, text/image/binary viewer states, share/copy actions. | File chrome is local; action placement can diverge from file explorer. | Shared file header/action row, mono text body, empty/error file state. |
| File explorer [`FileExplorerScreen.kt`](../app2/src/main/java/com/pocketshell/next/files/FileExplorerScreen.kt) | `ListRow`, alert dialog, folder/file listing. | Header mirrors file viewer but does not use `ScreenHeader`; file rows need one shared file grammar. | Shared file browser scaffold, `ListRow` file/folder row, path breadcrumb. |
| Session tree (host workspaces) [`HostWorkspacesScreen.kt`](../app2/src/main/java/com/pocketshell/next/workspaces/HostWorkspacesScreen.kt) / [`WorkspaceScreen.kt`](../app2/src/main/java/com/pocketshell/next/workspaces/WorkspaceScreen.kt) | Host workspaces, live session rows, agent/shell badges, create-session sheet. | Tree rows and create/error states must keep host truth visible. | Shared tree row, status/agent badge, session-create sheet, and explicit error state. |
| Session [`SessionScreen.kt`](../app2/src/main/java/com/pocketshell/next/terminal/SessionScreen.kt) | `ScreenHeader` carrying the connection-status dot, the `UsageGlancePill` usage badge and a `KebabTrigger` for lifecycle actions; a `SessionContextBar` session-switcher row; the terminal viewport; the floating `TerminalHotkeysPaletteOverlay`; the docked `SessionTerminalBar`; and the `PromptComposerSheet` composer. | Terminal chrome and reconnect states must remain consistent with the workspace's host session identity. | Terminal shell pattern: `ScreenHeader` with status and usage, context bar, floating hotkeys palette, docked terminal bar, composer sheet, and overflow menu. |

### Hosted Sheets, Dialogs, And Secondary Surfaces

| Surface | Current patterns | Drift | Standardize on |
|---------|------------------|-------|----------------|
| Prompt composer [`PromptComposerSheet.kt`](../app2/src/main/java/com/pocketshell/next/composer/PromptComposerSheet.kt) and [`ComposerBar.kt`](../app2/src/main/java/com/pocketshell/next/composer/ComposerBar.kt) | `ModalBottomSheet`, `MicButton`, recording/transcribing states, attachment chips, API-key dialog. | Many local controls and state rows; motion is local. | Composer component family: mic, paperclip, state row, attachment chip, send actions. |
| Session create sheet [`CreateSessionSheet.kt`](../app2/src/main/java/com/pocketshell/next/tree/CreateSessionSheet.kt) | `ModalBottomSheet`, shell/agent choices. | Choice rows are local. | Shared session-create picker with agent/shell badges. |
| Share host picker [`ShareActivity.kt`](../app2/src/main/java/com/pocketshell/next/share/ShareActivity.kt), [`SharePickerScreen.kt`](../shared/ui-screens/src/main/java/com/pocketshell/next/share/SharePickerScreen.kt) | Share-specific host/target lists, `ListRow`, dialogs. | Header and picker flow are separate from app host chooser. | Shared chooser row and target picker sheet. |
| SSH keys [`SshKeysScreen.kt`](../app2/src/main/java/com/pocketshell/next/hosts/SshKeysScreen.kt) | `ListRow`, `Kebab`, key rows, unlock dialog. | Not a nav destination but important form-management surface; needs `ScreenHeader` when standalone. | Shared key row, secret/unlock dialog. |
| Dictation surfaces [`InlineDictationController.kt`](../app2/src/main/java/com/pocketshell/next/terminal/InlineDictationController.kt), [`ComposerRecordingSurfaces.kt`](../app2/src/main/java/com/pocketshell/next/composer/ComposerRecordingSurfaces.kt) | Terminal-bar inline dictation plus the composer's recording panel. | Must not drift from composer and mic tokens. | `MicButton`, shared recording states, shared transcript controls. |

## Migration Slices

### Slice 0: Spec And Audit

This document is Slice 0. It locks the foundation, current drift, component
catalog, screen inventory, screenshot plan, and open maintainer decisions. It
does not touch runtime UI.

### Slice 1: Token Hygiene

- Fix stale docs/code references such as the missing `Motion.kt` claim.
- Add runtime motion tokens only after maintainer approval.
- Decide whether to add spacing tokens beyond `lg` or keep repeated values in
  component geometry.
- Fill M3 `surfaceContainer*` and selected-container slots only with emulator
  visual evidence because it changes menus, switches, cards, and selected
  controls.

### Slice 2: Shared Component Consolidation

- Promote repeated local status dots to shared `StatusDot`.
- Extend `ListRow` or add focused variants for file rows, setting rows, secret
  rows, repo rows, and job rows.
- Standardize `AlertDialog` and `ModalBottomSheet` wrappers.
- Add a shared empty/loading/error state component.
- Add a shared overflow menu section model if `Kebab` cannot cover sectioned
  menus.

### Slice 3: In-Flight UI Issues

| Issue | Token/component consumption |
|-------|-----------------------------|
| #453 Prompt composer recording UI | `MicButton`, `MicIcon`, composer state row, `Accent`/`OnAccent`, `bodyDense`, `labelMono`, attachment chip grammar, approved motion pulse only while recording/transcribing. |
| #454 Session bottom controls | `CommandChip`, `KeyBar`, `PocketShellDensity`, `PocketShellType.bodyMono`, 48dp touch floor, sticky primary cluster, no duplicate dictate chip. |
| #455 Folder/session tree | `ScreenHeader`, `SectionHeader`, `ListRow`, `Badge`, `StatusDot`, `treeIndent`, `bodyDense`, `bodyMono`, action overflow, compact row density. |
| #459 Conversation tab | `Tabs`, `UnifiedComposer`, `PocketShellType.bodyDense`, `labelMono`, tool-call row pattern, conversation timeline render target, no terminal keybar in Conversation. |

### Slice 4: Screen Families

Migrate by family, not by random file:

1. Host/settings/list-management family: Host list, Settings, SSH keys, Watched
   folders, Crash reports, AI costs.
2. Workspace family: Folder list, Repo browser, Env files.
3. Terminal family: Session, recurring jobs, usage in-session chip, port forwarding.
   chip, port forwarding.
4. File/share family: File explorer, File viewer, Share host picker.
5. Modal family: Composer, snippets, agent commands, bootstrap, folder context,
   session type picker, root project add.

Each migration PR should include before/after screenshots or render artifacts
for the touched family.

## Screenshot And Render Plan

### Fast Render Harness

Use this first for shared tokens and components:

```bash
scripts/render.sh
scripts/render.sh hostListScreen
scripts/render.sh conversationTimeline
```

Current #461 run result:

- Command: `scripts/render.sh`
- Result: success in 28 seconds.
- Generated artifacts:
  - `shared/ui-kit/build/renders/screen-header.png`
  - `shared/ui-kit/build/renders/list-row.png`
  - `shared/ui-kit/build/renders/host-list-screen.png`
  - `shared/ui-kit/build/renders/conversation-timeline.png`

Add render cases before migrating a shared component if no representative case
exists.

### Emulator Visual Audit

Use this for full journey screenshots:

```bash
scripts/capture-walkthrough-screenshots.sh
```

Expected output is documented in [`docs/testing.md`](testing.md): app2's
journeys render through `JourneyScreenshots.capture` into one directory per
journey, and the script fails unless every one of them produced at least one
frame:

- `build/walkthrough-visual-pass/<run-id>/screenshots/files/j01-connect-trust/`
- `.../j02-session-tree/`, `.../j03-attach-type/`, `.../j04-create-session/`
- `.../j05-reconnect/`, `.../j06-background-grace-return/`
- `.../j07-composer-send/`, `.../j08-voice-dictation/`
- `.../j10-files/`, `.../j11-share-upload/`, `.../j12-usage-panel/`

(Issue #2481: `scripts/phone-walkthrough.sh visual-audit` and its nine fixed
PNG names are gone with the three `app` module screenshot tests behind them —
one of which captured the conversation view, itself a cut feature.)

This command uses emulator and Docker-backed instrumentation, so it is not the
cheap inner loop. Run it for migration PRs that affect app screens or sheets.

### Focused Screenshot Tests

Run targeted connected tests when a slice touches a covered surface:

```bash
scripts/connected-test.sh --suffix design-session \
  -Pandroid.testInstrumentationRunnerArguments.class=com.pocketshell.next.tree.J04CreateSessionJourney

scripts/connected-test.sh --suffix design-composer \
  -Pandroid.testInstrumentationRunnerArguments.class=com.pocketshell.app.composer.PromptComposerVisualScreenshotTest

scripts/connected-test.sh --suffix design-folder \
  -Pandroid.testInstrumentationRunnerArguments.class=com.pocketshell.app.projects.FolderContextActionSheetScreenshotTest

scripts/connected-test.sh --suffix design-costs \
  -Pandroid.testInstrumentationRunnerArguments.class=com.pocketshell.app.costs.CostsScreenScreenshotTest
```

Many screenshot tests write under
`/sdcard/Android/media/com.pocketshell.app/additional_test_output/<test-name>/`.
Pull or copy those artifacts into the issue report. Full-device screenshots are
advisory for terminal content; terminal viewport captures and text assertions
remain authoritative.

## Do / Don't Rules

Do:

- Start with an existing shared component.
- Cite this document when adding a new UI pattern.
- Use `ScreenHeader`, `SectionHeader`, `ListRow`, `Badge`, `StatusDot`,
  `Kebab`, `Tabs`, `SegmentedToggle`, and `MicButton`
  before inventing local chrome.
- Keep rows dense but touch targets at least 48dp.
- Use mono for command/path/ID content, not for every label.
- Put destructive actions in overflow plus confirmation.
- Include render or screenshot evidence for visual migrations.

Don't:

- Add new colours, radii, shadows, motion values, or font sizes without a named
  role.
- Add a bespoke top bar, status dot, badge, command chip, mic, or row because a
  screen is "special".
- Use cards as page sections or nest cards inside cards.
- Use semantic colours as decoration.
- Add decorative animation or background-work-looking motion.
- Use the legacy `TerminalLabActivity` as design precedent.

## Migration Checklist

For every UI migration issue:

- [ ] Identify the screen family and source files.
- [ ] List current shared components already in use.
- [ ] Replace repeated local row/status/badge/menu/sheet/dialog patterns with
      shared components or document why not.
- [ ] Use only approved colour, type, spacing, density, radius, and motion roles.
- [ ] Preserve 48dp hit targets.
- [ ] Add a long-label row case proving the primary title ellipsizes and the
      trailing kebab/action/badge remains visible and tappable.
- [ ] Verify every kebab opens an action menu/sheet first; destructive menu
      items then open confirmation.
- [ ] Add or update a fast render case when touching `shared/ui-kit`.
- [ ] Run `scripts/render.sh` for shared components.
- [ ] Run targeted connected screenshot tests for screen/sheet changes.
- [ ] Attach artifact paths and note whether screenshots are full-device or
      authoritative viewport captures.
- [ ] Leave unrelated runtime churn out of the migration.

## Drift Guardrail

`scripts/check-design-tokens.sh` (issue #461, slice 2 / G7) is a cheap grep-only
guardrail that flags **new** off-ladder UI literals in `app/src/main` so the
incremental token migration doesn't lose ground. It catches:

- `RoundedCornerShape(<N>.dp)` where `<N>` is not an on-ladder radius
  (8 / 14 / 20 / 28 — the `PocketShellShapes` rungs).
- `fontSize = <N>.sp` where `<N>` is not an on-ladder size
  (11 / 13 / 14 / 16 / 20 — the `PocketShellType` rungs).

It uses a committed per-file **baseline** (`scripts/design-token-baseline.txt`)
of the current offender backlog, so it does not try to force the whole Slice D
sweep at once — it only fails when a file gains new offenders (or a brand-new
file ships with any). Genuine sub-ladder component geometry (a thin progress
track, an icon glyph size) is legitimate; name a private `*Radius`/`*Size`
constant with a cite to this document, then `--update` the baseline. Migrating a
screen lowers a file's count; the script reports the improvement and prompts a
re-baseline. The reviewer runs it from the worktree; it is intentionally not a
slow Gradle/emulator job.

```bash
scripts/check-design-tokens.sh            # check against the baseline
scripts/check-design-tokens.sh --update   # re-baseline after a migration
```

## Open Maintainer Decisions

1. Should `Motion.kt` be added to `shared/ui-kit/theme`, and should it expose
   Compose `FiniteAnimationSpec` helpers or plain duration/easing constants?
2. Should the spacing scale remain `xs` through `lg`, or should repeated 20dp,
   24dp, 28dp, 38dp, and 40dp values become named component geometry tokens?
3. When should M3 `surfaceContainer*`, selected-container, switch-track, and menu
   colours move from defaults to the PocketShell palette?
4. RESOLVED (#2717): `SessionRow` was retired as dead canon rather than
   revived; folder/tree/flat session lists keep their live dedicated rows.
5. Should `Kebab` grow section headers, destructive roles, and disabled/status
   rows, or should complex terminal menus keep local `DropdownMenu` blocks?
6. Should secret entry and reveal flows share one dialog across Settings, Env,
   Host key unlock, and Composer API key setup?
7. Should future light mode stay out of scope permanently, or remain a deferred
   portability target after the dark dev-tool system is stable?
