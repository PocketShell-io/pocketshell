package com.pocketshell.uikit.theme

import androidx.compose.ui.unit.dp

/**
 * PocketShell spacing scale — the 4 dp base grid shared by Linear and Material 3.
 *
 * The single source of truth for the rung values is
 * `docs/design-kit/design-system/tokens.json` (`space` block) — edit there
 * first, then mirror here; `QuietThemeTokenTest` fails when the two drift
 * apart (#2717). Call sites reach for these named rungs instead of freehand
 * `.dp` literals, and since #2812 that is enforced rather than merely asked
 * for: every padding, gap and margin under `app2/src/main`,
 * `shared/ui-kit/src/main` and `shared/ui-screens/src/main` must land on a
 * rung, and `TokenLiteralGuardTest` fails the ui-kit JVM gate when one does
 * not. Before #2812 this paragraph was the whole enforcement: the #2635 audit
 * counted ~58 off-grid literals living under it at `1caa29c1b`, 47 of which were
 * still in the guard's scope at `9424a3900`.
 *
 * The grid governs *layout spacing*: the distance between elements. It does
 * not govern hairlines (1 dp borders), corner radii (the `radius` ladder —
 * `scripts/check-design-tokens.sh`), or component geometry (stroke widths,
 * glyph boxes, drawn instruments, key-cap boxes). Those are allowlisted
 * one-by-one in `TokenLiteralGuardTest`, each row carrying the reason it is
 * not spacing.
 *
 * The scale follows the Quiet design kit. Row and touch dimensions live in
 * [PocketShellDensity] so spacing and hit targets cannot drift independently.
 * The old 32 dp `section` rung was retired in #2717: sections separate with
 * [PocketShellDensity.sectionGap] (24 dp), the next rung down.
 */
object PocketShellSpacing {
    /** 4 dp — micro-gaps (icon-to-label, inline separators). */
    val xs = 4.dp

    /** 8 dp — standard gap (chip-to-chip, row-to-row padding), key bar gap. */
    val sm = 8.dp

    /** 12 dp — local control gaps and compact inline padding. */
    val md = 12.dp

    /** 16 dp — large padding (app bar, sheet header, row internal), dialog padding. */
    val lg = 16.dp

    /** 20 dp — the Quiet screen gutter and primary page inset. */
    val xl = 20.dp

    /** 24 dp — sheet and large surface inset. */
    val xxl = 24.dp
}

/**
 * PocketShell geometry shared by rows, chips and the workspace tree.
 *
 * **Visual density is kept separate from the touch floor.** [rowPadV]/[chipPadV]
 * shrink the *paint* so more rows fit per screen, while [tapTargetMin] (48 dp) is
 * the a11y hit-area floor every interactive element must still honour via
 * `Modifier.sizeIn` / `minimumInteractiveComponentSize`. Shrinking the paint must
 * never shrink the hit area below 48 dp.
 *
 * The row minima are the `size.listRowMin` / `size.workspaceRowMin` values in
 * `docs/design-kit/design-system/tokens.json` (#2630 reconciled them down from
 * 72/88 dp — a one-item Hosts screen spent ~360 dp of a 915 dp phone on six
 * items at the old minima).
 */
object PocketShellDensity {
    /**
     * 56 dp — standard row minimum height (`tokens.json` `size.listRowMin`).
     *
     * Clears [tapTargetMin] with 8 dp to spare and still fits a title +
     * subtitle at the reconciled 14sp/11sp rungs.
     */
    val rowMinHeight = 56.dp

    /** 64 dp — the workspace row's primary navigation target (`size.workspaceRowMin`). */
    val workspaceRowMinHeight = 64.dp

    /**
     * The standard row's minimum touch and reading height.
     *
     * An alias of [rowMinHeight], not a second value: #2630 shipped because
     * duplicated copies of one token drifted.
     */
    val standardRowMinHeight = rowMinHeight

    /**
     * 56 dp — input/field minimum height (`tokens.json` `size.fieldMin`).
     *
     * The composer draft editor's floor (#2747): a draft field is a reading
     * surface, not just a touch target, so it holds the field rung rather
     * than only [tapTargetMin].
     */
    val fieldMin = 56.dp

    /**
     * 56 dp — button minimum height (`tokens.json` `size.buttonMin`).
     *
     * Every visible button keeps this floor even when it renders compact
     * inside a banner or dialog row (#2800: it was a `56.dp` literal in
     * `PocketShellButton` while the token existed unbound).
     */
    val buttonMin = 56.dp

    /** 16 dp — row vertical padding. Rows may grow for wrapped content. */
    val rowPadV = 16.dp

    /**
     * 20 dp — the Quiet screen gutter (`tokens.json` `size.screenGutter`),
     * used by standard and workspace rows and by every screen's page inset.
     *
     * Named for the token, not for the one call site it started at (#2800):
     * the pre-#2800 Kotlin spelling hid this JSON key behind a non-token name.
     */
    val screenGutter = 20.dp

    /**
     * 6 dp — chip vertical paint. A deliberate off-grid exception (#2812).
     *
     * A chip is the smallest labelled surface in the app, and 6 dp around an
     * 11-13 sp label's line box draws a 28-30 dp chip: recognisably a chip,
     * between the 24 dp badge and the 32 dp-plus row. Both neighbouring rungs
     * break that — 4 dp draws a 24-26 dp chip indistinguishable from a badge,
     * 8 dp a 32-34 dp one that crowds every dense row it sits in. The 4 dp grid
     * governs the gaps *between* elements; this is a component's own paint.
     *
     * Enforced as an exception rather than left as an oversight:
     * `TokenLiteralGuardTest` allowlists exactly this declaration, so a second
     * off-grid chip value cannot appear without a reason of its own. Touch is
     * unaffected either way — the 48 dp floor is [tapTargetMin]'s job, never
     * this paint's (see the class KDoc).
     */
    val chipPadV = 6.dp

    /**
     * 10 dp — chip horizontal paint. The same deliberate off-grid exception as
     * [chipPadV] (#2812).
     *
     * Wider than the vertical paint on purpose, so a one- or two-glyph label
     * (`2`, `^C`) still reads as a chip instead of a square. The rungs either
     * side put it flush with the label (8 dp) or as wide as a row's own `md`
     * inset (12 dp).
     */
    val chipPadH = 10.dp

    /** 24 dp — separation between independent sections (the retired 32 dp rung's replacement). */
    val sectionGap = 24.dp

    /** 16 dp — indent applied per workspace-tree nesting level. */
    val treeIndent = 16.dp

    /** 48 dp — a11y touch-target floor. Visual density never drops the hit area below this. */
    val tapTargetMin = 48.dp

    /**
     * 24 dp — the standard icon/glyph box (`tokens.json` `size.icon`).
     *
     * The size an `Icon` gets when it is the row's or button's primary
     * affordance. Sub-24 dp glyph boxes use [metadataIcon].
     */
    val icon = 24.dp

    /**
     * 18 dp — the metadata icon/glyph box (`tokens.json` `size.metadataIcon`).
     *
     * The smaller glyph that sits beside metadata text (kebab items, sheet
     * headers, session-kind marks) rather than carrying the row itself.
     */
    val metadataIcon = 18.dp
}
