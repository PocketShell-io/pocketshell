package com.pocketshell.uikit.theme

import androidx.compose.ui.text.TextStyle
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The enforcement half of the 4 dp grid and the type ladder (#2812).
 *
 * `Spacing.kt` has claimed since #2717 that "call sites should reach for these
 * named rungs instead of freehand `.dp` literals so the 4 dp grid stays
 * enforced" and `Type.kt` has claimed the same for its rungs — with nothing
 * checking either. The #2635 audit counted the result: ~58 off-grid `.dp`
 * literals and nine distinct freehand font sizes in the composer region alone.
 * A documented rule with zero enforcement is how #2630 shipped an entire
 * type scale one step too large while its own KDoc claimed to follow the spec.
 *
 * This test is the enforcement. It reads the three UI source roots off disk —
 * no compile dependency, so one test in `shared/ui-kit` covers `app2` and
 * `shared/ui-screens` too — and fails when a `.dp` or `.sp` literal is neither
 * on a rung nor in [OFF_GRID_DP_ALLOWLIST] / [OFF_RUNG_SP_ALLOWLIST] with a
 * written reason.
 *
 * ## What the 4 dp grid governs
 *
 * **Layout spacing**: padding, margins, gaps, spacers — the distance between
 * UI elements. Those must land on a [PocketShellSpacing] rung. When an
 * off-grid value had to move, this issue rounded DOWN to the nearer rung
 * (density-first, the direction #2630/#2798 pushed the app), because every
 * off-grid spacing value in the tree sat exactly between two rungs.
 *
 * **Not** governed, and therefore allowlisted rather than "fixed":
 *
 * - Hairlines (`1.dp`, `1.5.dp`) — a 4 dp border is not a border. Allowed
 *   everywhere without a row.
 * - Corner radii — the `radius` ladder's domain (`tokens.json` `radius`,
 *   `scripts/check-design-tokens.sh`). Literals inside `RoundedCornerShape(…)`
 *   are skipped here so the two guards can't contradict each other.
 * - Component geometry: stroke widths, glyph boxes, drawn-instrument
 *   dimensions, key-cap boxes, badge boxes. A 2 dp spinner stroke is not a
 *   2 dp gap, and rounding it to 4 dp would change what the thing IS.
 * - The two density tokens that deliberately sit off-grid — `chipPadV` 6 dp
 *   and `chipPadH` 10 dp (#2812 AC4, reason on the rows below).
 *
 * ## What the type ladder governs
 *
 * Every font-size literal in the three roots must equal the `fontSize` of a
 * declared [PocketShellType] rung. The allowed set is read back off the rung
 * objects by reflection, so it cannot drift from `Type.kt`, and
 * [QuietThemeTokenTest] separately pins those rungs to `tokens.json`. Line
 * heights and sub-1sp letter spacing are out of scope (a line height is
 * ~1.3x its own rung by construction, not an independent rung).
 *
 * ## Both directions
 *
 * Counts are asserted for EXACT equality, not `<=`. A new literal reddens
 * because its count rises; a removed one reddens too, so the table can't
 * quietly keep rows for literals that are gone. This is the same discipline
 * [QuietThemeTokenTest]'s `size`-key-set assertion uses.
 */
class TokenLiteralGuardTest {

    @Test
    fun scanRootsAreRealAndPopulated() {
        // The lesson from `check-design-tokens.sh`: the rewrite deleted the
        // `app/src/main` it scanned, every count came back zero, and the guard
        // reported perfection over nothing. A guard pointed at an empty tree is
        // worse than no guard, so the floors below are part of the contract.
        SCAN_ROOTS.forEach { (root, minimumFiles) ->
            val dir = repoFile(root)
            assertTrue("scan root $root does not exist — this guard would scan nothing", dir.isDirectory)
            val kotlinFiles = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.count()
            assertTrue(
                "scan root $root has $kotlinFiles Kotlin files, expected at least $minimumFiles — " +
                    "a shrunken root means this guard is reporting perfection over nothing",
                kotlinFiles >= minimumFiles,
            )
        }
    }

    @Test
    fun typeRungFontSizesAreTheExpectedLadder() {
        // Anti-vacuity pin for the reflective read below: if `Type.kt` grows a
        // rung, this fails and someone has to decide whether the new size is
        // really a rung or just another freehand literal with a name.
        assertEquals(
            "the type ladder's font sizes changed — update this pin deliberately, and check " +
                "whether the new size belongs in the ladder at all (#2812)",
            setOf("11", "12", "13", "14", "16", "20"),
            typeRungFontSizes(),
        )
    }

    @Test
    fun everyAllowlistRowCarriesAReason() {
        (OFF_GRID_DP_ALLOWLIST + OFF_RUNG_SP_ALLOWLIST).forEach { row ->
            assertTrue(
                "allowlist row ${row.file} ${row.literal} has no reason — an exception without a " +
                    "written reason is just an unenforced rule again (#2812)",
                row.reason.trim().length >= 20,
            )
            assertTrue("allowlist row ${row.file} ${row.literal} has count ${row.count}", row.count >= 1)
        }
    }

    @Test
    fun offGridSpacingLiteralsAreOnAGridRungOrAllowlisted() {
        val actual = scan("dp") { value, occurrence ->
            // On-grid, or a hairline, is always fine.
            value == 0.0 || value % 4.0 == 0.0 || value == 1.0 || value == 1.5 ||
                // Radii belong to the `radius` ladder, not the spacing grid.
                occurrence.isRadius
        }
        assertEquals(
            OFF_GRID_DP_MESSAGE,
            render(OFF_GRID_DP_ALLOWLIST),
            render(actual),
        )
    }

    @Test
    fun fontSizeLiteralsLandOnATypeRungOrAreAllowlisted() {
        val rungs = typeRungFontSizes()
        val actual = scan("sp") { value, occurrence ->
            // Sub-1sp values are letter spacing (tracking), not a font size.
            value < 1.0 ||
                // A line height is derived from its own rung, not a rung itself.
                occurrence.isLineHeight ||
                literalOf(value) in rungs
        }
        assertEquals(
            OFF_RUNG_SP_MESSAGE,
            render(OFF_RUNG_SP_ALLOWLIST),
            render(actual),
        )
    }

    // ── scanning ─────────────────────────────────────────────────────────────

    private data class Occurrence(val isRadius: Boolean, val isLineHeight: Boolean)

    /**
     * Tally every `<n>.<unit>` literal in the scan roots that [allowed] rejects,
     * keyed by `"<repo-relative path> <literal>"`.
     */
    private fun scan(unit: String, allowed: (Double, Occurrence) -> Boolean): List<LiteralException> {
        val pattern = Regex("""(?<![\w.])(\d+(?:\.\d+)?)\.$unit(?![\w])""")
        val tally = sortedMapOf<String, Int>()
        SCAN_ROOTS.keys.forEach { root ->
            repoFile(root).walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .sortedBy { it.path }
                .forEach { file ->
                    val relative = root + file.path.substringAfter(repoFile(root).path)
                    codeLines(file).forEach { line ->
                        val radiusSpans = roundedCornerShapeSpans(line)
                        pattern.findAll(line).forEach { match ->
                            val value = match.groupValues[1].toDouble()
                            val occurrence = Occurrence(
                                isRadius = radiusSpans.any { match.range.first in it },
                                isLineHeight = LINE_HEIGHT_ASSIGNMENT
                                    .containsMatchIn(line.substring(0, match.range.first)),
                            )
                            if (!allowed(value, occurrence)) {
                                val key = "$relative ${literalOf(value)}"
                                tally[key] = (tally[key] ?: 0) + 1
                            }
                        }
                    }
                }
        }
        return tally.map { (key, count) ->
            val (file, literal) = key.split(' ', limit = 2)
            LiteralException(file, literal, count, "scanned")
        }
    }

    /** `12.0` -> `"12"`, `1.5` -> `"1.5"` so the rows read like the source. */
    private fun literalOf(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

    /** Source lines with comments stripped, so a KDoc example is not an offender. */
    private fun codeLines(file: File): List<String> {
        val out = mutableListOf<String>()
        var inBlockComment = false
        file.readLines().forEach { raw ->
            var line = raw
            if (inBlockComment) {
                val end = line.indexOf("*/")
                if (end < 0) {
                    out += ""
                    return@forEach
                }
                line = line.substring(end + 2)
                inBlockComment = false
            }
            while (true) {
                val start = line.indexOf("/*")
                if (start < 0) break
                val end = line.indexOf("*/", start + 2)
                if (end < 0) {
                    line = line.substring(0, start)
                    inBlockComment = true
                    break
                }
                line = line.substring(0, start) + line.substring(end + 2)
            }
            val lineComment = line.indexOf("//")
            out += if (lineComment >= 0) line.substring(0, lineComment) else line
        }
        return out
    }

    /** Character spans covered by `RoundedCornerShape(…)` calls on one line. */
    private fun roundedCornerShapeSpans(line: String): List<IntRange> {
        val spans = mutableListOf<IntRange>()
        var from = line.indexOf(ROUNDED_CORNER_SHAPE)
        while (from >= 0) {
            var depth = 0
            var cursor = from + ROUNDED_CORNER_SHAPE.length - 1
            var end = line.length - 1
            while (cursor < line.length) {
                when (line[cursor]) {
                    '(' -> depth++
                    ')' -> if (--depth == 0) {
                        end = cursor
                        cursor = line.length
                    }
                }
                cursor++
            }
            spans += from..end
            from = if (end + 1 < line.length) line.indexOf(ROUNDED_CORNER_SHAPE, end + 1) else -1
        }
        return spans
    }

    /** Every declared [PocketShellType] rung's font size, read back off the object. */
    private fun typeRungFontSizes(): Set<String> =
        PocketShellType::class.java.declaredMethods
            .filter { it.parameterCount == 0 && it.returnType == TextStyle::class.java }
            .map { it.invoke(PocketShellType) as TextStyle }
            .map { literalOf(it.fontSize.value.toDouble()) }
            .toSet()

    private fun render(rows: List<LiteralException>): String =
        rows.map { "${it.file} ${it.literal} x${it.count}" }.sorted().joinToString("\n")

    private fun repoFile(relativePath: String): File =
        sequenceOf(relativePath, "../../$relativePath")
            .map(::File)
            .firstOrNull { it.exists() }
            ?: error("Could not locate $relativePath from ${File(".").absolutePath}")

    /**
     * One allowlisted literal: a repo-relative file, the literal as it reads in
     * the source, how many times it appears there, and why that is allowed.
     */
    internal data class LiteralException(
        val file: String,
        val literal: String,
        val count: Int,
        val reason: String,
    )

    private companion object {
        const val ROUNDED_CORNER_SHAPE = "RoundedCornerShape("
        /** `lineHeight = ` immediately before the literal — the value IS a line height. */
        val LINE_HEIGHT_ASSIGNMENT = Regex("""lineHeight\s*=\s*$""")

        /** Scan root -> the minimum Kotlin-file count that proves it is really there. */
        val SCAN_ROOTS = linkedMapOf(
            "app2/src/main" to 100,
            "shared/ui-kit/src/main" to 30,
            "shared/ui-screens/src/main" to 20,
        )

        const val OFF_GRID_DP_MESSAGE =
            "Off-grid `.dp` literals changed (#2812). Left = allowlist, right = what is in the tree.\n" +
                "A row on the right that is missing on the left is a NEW off-grid literal: put the value " +
                "on a PocketShellSpacing rung, or — if it is component geometry rather than layout " +
                "spacing (a stroke width, a glyph box, a drawn instrument) — add a row to " +
                "OFF_GRID_DP_ALLOWLIST with the reason it is not spacing.\n" +
                "A row on the left that is missing on the right means an exception was fixed: delete " +
                "its row.\n"

        const val OFF_RUNG_SP_MESSAGE =
            "Off-rung font-size literals changed (#2812). Left = allowlist, right = what is in the tree.\n" +
                "Use a PocketShellType rung's `.fontSize` (or `style =` the rung) instead of a literal. " +
                "A genuinely sub-rung glyph size needs a named constant in Type.kt plus a row here " +
                "explaining why no rung fits.\n"

        val OFF_GRID_DP_ALLOWLIST: List<LiteralException> = listOf(
            LiteralException(
                "app2/src/main/java/com/pocketshell/next/composer/ComposerAttachmentTiles.kt",
                "22",
                1,
                "REMOVE_SIZE — the circular remove badge drawn in an attachment tile's corner. A " +
                    "badge box, not a gap: it is sized to sit inside the 64dp tile without covering the " +
                    "extension label, and the 48dp touch target is the separate outer Box.",
            ),
            LiteralException(
                "app2/src/main/java/com/pocketshell/next/composer/ComposerRecordingSurfaces.kt",
                "2",
                1,
                "ComposerWaveformBarRadius — the fully-rounded cap of a 3dp waveform bar (radius = " +
                    "half the bar's width). Drawing geometry of one painted object.",
            ),
            LiteralException(
                "app2/src/main/java/com/pocketshell/next/composer/ComposerRecordingSurfaces.kt",
                "3",
                2,
                "The dictation waveform's bar width and inter-bar gap. Thirty bars drawn as one " +
                    "instrument: on the 4dp rung they read as blocks rather than a waveform. Same class " +
                    "as a stroke width, not a layout gap.",
            ),
            LiteralException(
                "app2/src/main/java/com/pocketshell/next/ports/PortForwardScreen.kt",
                "2",
                1,
                "Optical leading between a title and its subtitle inside ONE text block — line " +
                    "spacing, not a gap between elements. See the ListRow row below for why moving this " +
                    "class to 4dp belongs to the row-height lane (#2806), not here.",
            ),
            LiteralException(
                "shared/ui-kit/src/main/java/com/pocketshell/uikit/components/ComposerControls.kt",
                "15",
                1,
                "ComposerStopGlyphSize — the stop square painted inside the 48dp accent disc. A glyph " +
                    "box, already documented as deliberate sub-ladder icon geometry (#2763).",
            ),
            LiteralException(
                "shared/ui-kit/src/main/java/com/pocketshell/uikit/components/DisclosureIcon.kt",
                "14",
                1,
                "DisclosureIconDefaultSize — the disclosure chevron's glyph box, between the 12dp and " +
                    "18dp icon steps. An icon size (the `size` ladder's domain), not spacing.",
            ),
            LiteralException(
                "shared/ui-kit/src/main/java/com/pocketshell/uikit/components/ListRow.kt",
                "2",
                1,
                "Optical leading between a row's title and subtitle inside one text block. Moving " +
                    "this to the 4dp rung adds 2dp to EVERY two-line row in the app (16 + 20 + gap + 16 + " +
                    "16 already exceeds the 56dp floor, so nothing absorbs it) — a global density change " +
                    "owned by the row-height lane (#2806), not a spacing-guard sweep.",
            ),
            LiteralException(
                "shared/ui-kit/src/main/java/com/pocketshell/uikit/components/LoadingIndicator.kt",
                "2",
                1,
                "SmallStroke — the small spinner's arc stroke width. A 4dp stroke on an 18dp spinner " +
                    "draws a disc, not a spinner.",
            ),
            LiteralException(
                "shared/ui-kit/src/main/java/com/pocketshell/uikit/components/LoadingIndicator.kt",
                "3",
                1,
                "MediumStroke — the medium spinner's arc stroke width.",
            ),
            LiteralException(
                "shared/ui-kit/src/main/java/com/pocketshell/uikit/components/NavigationChevron.kt",
                "2",
                1,
                "NavigationChevronStrokeWidth — the stroke of a chevron drawn on a Canvas.",
            ),
            LiteralException(
                "shared/ui-kit/src/main/java/com/pocketshell/uikit/components/ProgressBar.kt",
                "6",
                1,
                "The progress track's thickness. `docs/design-system.md` names a thin progress track " +
                    "as legitimate sub-ladder component geometry: 4dp reads as a hairline, 8dp as a row " +
                    "band.",
            ),
            LiteralException(
                "shared/ui-kit/src/main/java/com/pocketshell/uikit/components/QuietChoiceRow.kt",
                "10",
                1,
                "The selected radio dot inside the 20dp ring — exactly half the ring, so the dot " +
                    "stays concentric. A drawn glyph, not a gap.",
            ),
            LiteralException(
                "shared/ui-kit/src/main/java/com/pocketshell/uikit/components/ScreenHeader.kt",
                "2",
                1,
                "Optical leading between the screen title and its subtitle — the same one-text-block " +
                    "line spacing as the ListRow row above.",
            ),
            LiteralException(
                "shared/ui-kit/src/main/java/com/pocketshell/uikit/components/SegmentedToggle.kt",
                "2",
                1,
                "The segmented track's inset, which keeps the selected segment's fill off the track's " +
                    "1dp border. 4dp would spend an eighth of the 32dp track on the reveal; 0dp merges " +
                    "fill into border.",
            ),
            LiteralException(
                "shared/ui-kit/src/main/java/com/pocketshell/uikit/components/SessionTerminalBar.kt",
                "30",
                1,
                "KeySlot's minimum cap width — a key cap is a drawn box sized to its glyph, not a " +
                    "gap. The real touch floor is separate: the bar passes `tapTargetMin` as the slot's " +
                    "minHeight.",
            ),
            LiteralException(
                "shared/ui-kit/src/main/java/com/pocketshell/uikit/components/SessionTerminalBar.kt",
                "38",
                1,
                "KeySlot's default cap height — the historical KeyBar paint, kept deliberately so the " +
                    "recipe is unchanged (see the parameter's KDoc). Cap-box geometry, not spacing.",
            ),
            LiteralException(
                "shared/ui-kit/src/main/java/com/pocketshell/uikit/components/StatusDot.kt",
                "3",
                1,
                "The ring's offset from the core radius inside the dot's Canvas drawing — geometry of " +
                    "one painted glyph.",
            ),
            LiteralException(
                "shared/ui-kit/src/main/java/com/pocketshell/uikit/theme/Spacing.kt",
                "10",
                1,
                "chipPadH — the deliberate off-grid chip-paint exception (#2812), reasoned on the " +
                    "declaration's KDoc alongside chipPadV.",
            ),
            LiteralException(
                "shared/ui-kit/src/main/java/com/pocketshell/uikit/theme/Spacing.kt",
                "18",
                1,
                "metadataIcon — `tokens.json` `size.metadataIcon`, the 18dp metadata glyph box. An " +
                    "icon-size token (explicitly allowlisted by #2812's scope) and already pinned by " +
                    "QuietThemeTokenTest.",
            ),
            LiteralException(
                "shared/ui-kit/src/main/java/com/pocketshell/uikit/theme/Spacing.kt",
                "6",
                1,
                "chipPadV — the deliberate off-grid chip-paint exception (#2812). Full reasoning on " +
                    "the declaration's KDoc: 6dp draws a 28-30dp chip, and both neighbouring rungs turn " +
                    "it into either a badge or a row.",
            ),
            LiteralException(
                "shared/ui-screens/src/main/java/com/pocketshell/next/files/MarkdownRenderer.kt",
                "2",
                1,
                "The vertical rhythm between rendered markdown list items. The file/conversation " +
                    "viewer renders someone else's document, so its text rhythm follows the content " +
                    "rather than the app's chrome grid. Outside #2812's scope, recorded here so it cannot " +
                    "grow unnoticed.",
            ),
        )

        val OFF_RUNG_SP_ALLOWLIST: List<LiteralException> = listOf(
            LiteralException(
                "shared/ui-kit/src/main/java/com/pocketshell/uikit/theme/Type.kt",
                "8",
                1,
                "keycapCueSize — the hotkeys palette's long-press cue under a cap label: a secondary " +
                    "hint inside an already-small cap, not text to read. Reasoned in full on the " +
                    "declaration.",
            ),
            LiteralException(
                "shared/ui-kit/src/main/java/com/pocketshell/uikit/theme/Type.kt",
                "9",
                1,
                "keycapSqueezeSize — the key cap's long-label squeeze, deliberately below every " +
                    "reading rung. A 6+ character label (`Enter`, `PgDown`) cannot render at `keycap` in " +
                    "a 30dp slot without clipping, and a clipped key label makes the key unusable. " +
                    "Reasoned in full on the declaration.",
            ),
            LiteralException(
                "shared/ui-screens/src/main/java/com/pocketshell/next/files/MarkdownRenderer.kt",
                "15",
                1,
                "The rendered markdown document's h4 size — part of the same heading ladder as the h1 " +
                    "row (24sp) in this file: document typography for a rendered .md file, not app " +
                    "chrome.",
            ),
            LiteralException(
                "shared/ui-screens/src/main/java/com/pocketshell/next/files/MarkdownRenderer.kt",
                "17",
                1,
                "The rendered markdown document's h3 size — part of the same heading ladder as the h1 " +
                    "row (24sp) in this file: document typography for a rendered .md file, not app " +
                    "chrome.",
            ),
            LiteralException(
                "shared/ui-screens/src/main/java/com/pocketshell/next/files/MarkdownRenderer.kt",
                "24",
                1,
                "The rendered markdown document's h1 size. Document typography for someone else's .md " +
                    "file, not app chrome; #2812's T-3 scope was the composer/hotkeys region. Recorded so " +
                    "the heading ladder cannot grow while a later issue decides whether the viewer should " +
                    "adopt the app's rungs.",
            ),
        )
    }
}
