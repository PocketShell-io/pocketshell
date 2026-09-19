package com.pocketshell.uikit.theme

import androidx.compose.ui.unit.dp

/**
 * PocketShell spacing scale — the 4 dp base grid shared by Linear and Material 3.
 *
 * The single source of truth for the rung values is
 * `docs/design-kit/design-system/tokens.json` (`space` block) — edit there
 * first, then mirror here; `QuietThemeTokenTest` fails when the two drift
 * apart (#2717). Call sites should reach for these named rungs instead of
 * freehand `.dp` literals so the 4 dp grid stays enforced; if a padding/gap/margin
 * value doesn't land on a rung, it's a bug or scope creep (§3).
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
     * `rowPadH` hid a JSON key behind a Kotlin-only spelling.
     */
    val screenGutter = 20.dp

    /**
     * Deprecated spelling of [screenGutter], kept as an alias (never a second
     * value — #2630's drift class) only because `SectionHeader.kt` is frozen
     * under review #2790 and still reads it. Delete once that lands; new call
     * sites use [screenGutter].
     */
    val rowPadH = screenGutter

    /** 6 dp — chip vertical padding. */
    val chipPadV = 6.dp

    /** 10 dp — chip horizontal padding. */
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
