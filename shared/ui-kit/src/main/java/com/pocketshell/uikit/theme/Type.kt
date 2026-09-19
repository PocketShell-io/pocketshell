package com.pocketshell.uikit.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Typography for PocketShell.
 *
 * The single source of truth for these numbers is
 * `docs/design-kit/design-system/tokens.json` (`type` block) — edit there
 * first, then mirror here. `docs/design-language.md` § Type and
 * `docs/design-system.md`'s token table cite the same file rather than
 * restating the numbers (#2717).
 *
 * | Rung             | Size | Line height | M3 slot         |
 * |------------------|------|-------------|-----------------|
 * | Screen heading   | 20sp | 26sp        | `headlineSmall` |
 * | Title            | 16sp | 22sp        | `titleMedium`   |
 * | Body             | 14sp | 20sp        | `bodyMedium`    |
 * | Caption / label  | 11sp | 16sp        | `labelSmall`    |
 *
 * Issue #2630: the Quiet redesign shipped every rung one step larger than this
 * (28/20/18/16) while its own doc comment claimed to follow the spec, which is
 * what made the app read as oversized on a phone. If a rung has to change
 * again, change `tokens.json` first and mirror it here — the code never leads
 * the spec, and `QuietThemeTokenTest` fails when the two drift apart.
 *
 * The dense/mono/key-cap rungs ([bodyDense], [bodyMono], [labelMono],
 * [keycap]) sit deliberately
 * between these and are NOT part of this M3 table — but they ARE `type` roles
 * in `tokens.json` and pinned by `QuietThemeTokenTest` like every other rung
 * (#2810). They used to exist only here, in no token file and no test, which
 * is how the desktop client came to derive its body size by reading this
 * file's source instead of the token file.
 *
 * Font families:
 *
 * - UI chrome: Android system default (Roboto on most devices). The design
 *   spec calls for Inter or SF Pro, but bundling Inter is deferred per the
 *   issue's non-goals — "system mono fallback for now; bundling fonts is a
 *   follow-up". The system sans-serif is close enough for v1.
 * - Terminal and inline code: [JetBrainsMonoFamily], which today resolves to
 *   [FontFamily.Monospace] (system monospace). When we bundle the actual
 *   JetBrains Mono `.ttf` files (follow-up issue), swap the alias's value;
 *   call sites need no edits.
 */

/**
 * Alias for the monospace family used in terminals and inline code.
 *
 * Today: system monospace (Roboto Mono on most Android builds). Tomorrow:
 * bundled JetBrains Mono. Kept as a named alias so all downstream call sites
 * — terminal surface, inline `<code>` runs — flip in one place when the
 * bundled font lands.
 */
val JetBrainsMonoFamily: FontFamily = FontFamily.Monospace

/**
 * Material 3 typography for PocketShell. Only the slots we actually use today
 * are overridden; everything else inherits Material's defaults so unanticipated
 * components don't render with garbage sizes.
 */
val PocketShellTypography: Typography = Typography(
    // 20sp screen headings.
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),

    // 16sp titles and workspace names.
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),

    // 14sp body — the default reading size for settings and standard rows.
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),

    // 11sp captions, metadata and labels.
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
    ),
)

/**
 * The new dense/mono type rungs (#461 §3.2 / Δ7 / Δ8).
 *
 * **Deliberately NOT M3 `Typography` slots.** Overriding a previously-default
 * Material slot (e.g. `titleSmall`, `bodyLarge`, `labelMedium`) would silently
 * restyle every component that already reads `MaterialTheme.typography.*` for
 * that slot — including the app-bar title and section labels, which would flip
 * to monospace. Slice 0 must be a no-op visually, so these rungs ship as
 * standalone [TextStyle] constants that call sites opt into explicitly:
 *
 * ```kotlin
 * Text(text = path, style = PocketShellType.bodyMono)
 * ```
 *
 * Font bundling stays deferred (#461 decision #5): the mono rungs use the system
 * monospace family via [JetBrainsMonoFamily].
 */
object PocketShellType {
    /** 20sp screen heading — the `type.screen` rung in `tokens.json`. */
    val screen: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    )

    /** 16sp workspace or detail title — the `type.title` rung. */
    val title: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    )

    /** 16sp workspace name. Kept distinct for call-site readability. */
    val workspace: TextStyle = title

    /** 14sp standard row and explanatory body text — the `type.body` rung. */
    val body: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    )

    /** 11sp supporting text and metadata — the `type.metadata` rung. */
    val metadata: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 16.sp,
    )

    /** 11sp section and field label — the `type.label` rung, Medium weight. */
    val label: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
    )

    /** 14sp action label — body-sized so a button never outweighs a title. */
    val button: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    )

    /** 11sp terminal-adjacent app text; terminal output has its own grid. */
    val terminal: TextStyle = metadata

    // The `quiet*` names are the Quiet-redesign spellings of the same four
    // rungs. They are aliases, never independent values: #2630 shipped because
    // two parallel copies of one scale drifted apart from each other and from
    // the spec.

    /** @see screen */
    val quietScreen: TextStyle = screen

    /** @see title */
    val quietTitle: TextStyle = title

    /** @see body */
    val quietBody: TextStyle = body

    /** @see metadata */
    val quietMetadata: TextStyle = metadata

    /** @see label */
    val quietLabel: TextStyle = label

    /**
     * 13sp dense body (Δ8) — the canonical dense-row size between `labelSmall`(11)
     * and `bodyMedium`(14). Promotes the de-facto 13sp literal (the 2nd most-used
     * size in the app) into a real rung: dense list/tree rows, conversation lines,
     * settings rows. The `type.bodyDense` rung in `tokens.json`.
     */
    val bodyDense: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp, // ~1.35× of 13sp
    )

    /**
     * 13sp mono body (Δ7) — terminal-adjacent UI: host subtitles, paths, command
     * chips, aplexer names, tool-call previews. System monospace via
     * [JetBrainsMonoFamily] (bundling deferred). The `type.bodyMono` rung in
     * `tokens.json`; the JSON records the metrics, and the family — the only
     * thing distinguishing this rung from [bodyDense], which shares all three
     * numbers — is pinned in `QuietThemeTokenTest`.
     */
    val bodyMono: TextStyle = TextStyle(
        fontFamily = JetBrainsMonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp, // ~1.4× of 13sp
    )

    /**
     * 11sp mono label (Δ7) — inline counts/IDs in a mono context. System
     * monospace via [JetBrainsMonoFamily] (bundling deferred). The
     * `type.labelMono` rung in `tokens.json`.
     */
    val labelMono: TextStyle = TextStyle(
        fontFamily = JetBrainsMonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 14.sp, // ~1.3× of 11sp
    )

    /**
     * 12sp key-cap glyph — the `type.keycap` rung in `tokens.json`.
     *
     * A rung rather than a rounding of [bodyDense]: a key cap is a ~30dp box
     * that has to show `Esc`, `Tab` or `^C` unwrapped and in mono, so its glyph
     * sits one step below the 13sp dense-row rung. #2812 added it because the
     * terminal bar and the hotkeys palette were drawing the same key cap at two
     * different freehand sizes (12sp and 13sp) with nothing naming either.
     *
     * An arrow cap is not a key cap: arrows use [title] and the UI sans-serif —
     * a glyph, not a word. A label too long for a cap uses [keycapSqueezeSize].
     */
    val keycap: TextStyle = TextStyle(
        fontFamily = JetBrainsMonoFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp, // ~1.33× of 12sp
    )

    /**
     * 9sp — the key cap's long-label squeeze. Deliberately below every reading
     * rung, and therefore NOT a rung itself.
     *
     * A cap holding a 6+ character label (`Enter`, `PgDown`) cannot render at
     * [keycap] inside a 30dp slot without clipping, and a clipped key label
     * makes the key unusable. #2812 kept the value and named it rather than
     * rounding it onto a rung where it does not belong; `TokenLiteralGuardTest`
     * allowlists this one declaration with the same reason, so a THIRD squeeze
     * size cannot appear unnoticed.
     */
    val keycapSqueezeSize: TextUnit = 9.sp

    /**
     * 8sp — the hotkeys-palette long-press cue printed under a cap's label.
     *
     * A secondary hint inside an already-small cap rather than text to read;
     * like [keycapSqueezeSize] it is a named sub-rung value, not a rung, and is
     * allowlisted once in `TokenLiteralGuardTest`.
     */
    val keycapCueSize: TextUnit = 8.sp
}
