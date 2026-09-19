package com.pocketshell.uikit.theme

import java.io.File
import org.json.JSONObject

/**
 * Test-side reader for the design kit's single machine-readable token source:
 * `docs/design-kit/design-system/tokens.json` (#2717).
 *
 * Before this helper the token pins in [QuietThemeTokenTest] were hand-copied
 * constants — the same two-copies failure #2630 diagnosed one level up: the
 * pins agreed with the code and both could drift from the file the kit README
 * calls "THE source of truth". Every pin now reads the file, and
 * [QuietThemeTokenTest]'s kit-theme-sync test additionally parses the
 * *generated* `docs/design-kit/android/PocketShellTheme.kt` so regenerating
 * from a stale (or hand-edited) JSON cannot silently reintroduce old numbers.
 *
 * JSON parsing uses `org.json`, which Robolectric provides on the JVM; the
 * helper is only meant to be loaded from Robolectric tests.
 */
internal object DesignKitTokens {

    /** Path of the token source, relative to the repository root. */
    const val TOKENS_RELATIVE_PATH = "docs/design-kit/design-system/tokens.json"

    /** Path of the generated Android hand-off theme, relative to the repository root. */
    const val KIT_THEME_RELATIVE_PATH = "docs/design-kit/android/PocketShellTheme.kt"

    /** Path of the generated browser hand-off stylesheet, relative to the repository root. */
    const val TOKENS_CSS_RELATIVE_PATH = "docs/design-kit/design-system/tokens.css"

    /** Path of the design-system prose doc, relative to the repository root. */
    const val DESIGN_SYSTEM_DOC_RELATIVE_PATH = "docs/design-system.md"

    /** Path of the Kotlin spacing/density source, relative to the repository root. */
    const val DENSITY_SOURCE_RELATIVE_PATH =
        "shared/ui-kit/src/main/java/com/pocketshell/uikit/theme/Spacing.kt"

    val tokensJsonText: String by lazy { locate(TOKENS_RELATIVE_PATH).readText() }

    val kitThemeText: String by lazy { locate(KIT_THEME_RELATIVE_PATH).readText() }

    val tokensCssText: String by lazy { locate(TOKENS_CSS_RELATIVE_PATH).readText() }

    val designSystemDocText: String by lazy { locate(DESIGN_SYSTEM_DOC_RELATIVE_PATH).readText() }

    val densitySourceText: String by lazy { locate(DENSITY_SOURCE_RELATIVE_PATH).readText() }

    val root: JSONObject by lazy { JSONObject(tokensJsonText) }

    fun type(role: String): JSONObject = root.getJSONObject("type").getJSONObject(role)

    fun typeSizeSp(role: String): Int = type(role).getInt("sizeSp")

    fun typeLineHeightSp(role: String): Int = type(role).getInt("lineHeightSp")

    fun typeWeight(role: String): Int = type(role).getInt("weight")

    fun sizeDp(key: String): Int = root.getJSONObject("size").getInt(key)

    fun spaceDp(key: String): Int = root.getJSONObject("space").getInt(key)

    fun radiusDp(key: String): Int = root.getJSONObject("radius").getInt(key)

    /**
     * A CSS colour from the JSON's `color` block as an Android ARGB long.
     *
     * The JSON stores CSS notation — `#RRGGBB`, or `#RRGGBBAA` for the scrim —
     * while Compose's `Color(0x...)` literals are ARGB, so the alpha moves from
     * the tail to the head.
     */
    fun colorArgb(key: String): Long {
        val css = root.getJSONObject("color").getString(key).removePrefix("#")
        return when (css.length) {
            6 -> (css.toLong(16) or 0xFF000000)
            8 -> (css.substring(6, 8) + css.substring(0, 6)).toLong(16)
            else -> error("Unsupported CSS colour length for $key: #$css")
        }
    }

    /** `val name = 56.dp` in the generated kit theme. */
    fun kitDp(name: String): Int =
        kitRegex("""val\s+$name\s*=\s*(\d+)\.dp""").groupValues[1].toInt()

    /** `fontSize` / `lineHeight` of `val name = TextStyle(...)` in the generated kit theme. */
    fun kitType(name: String): Pair<Int, Int> {
        val body = kitRegex("""val\s+$name\s*=\s*TextStyle\((?:[^()]|\([^()]*\))*\)""").groupValues[0]
        val size = Regex("""fontSize\s*=\s*(\d+)\.sp""").find(body)?.groupValues?.get(1)
        val lineHeight = Regex("""lineHeight\s*=\s*(\d+)\.sp""").find(body)?.groupValues?.get(1)
        return Pair(
            size?.toInt() ?: error("fontSize not found for $name"),
            lineHeight?.toInt() ?: error("lineHeight not found for $name"),
        )
    }

    /** `FontWeight(700)` of `val name = TextStyle(...)` in the generated kit theme. */
    fun kitTypeWeight(name: String): Int =
        Regex("""val\s+$name\s*=\s*TextStyle\((?:[^()]|\([^()]*\))*""")
            .find(kitThemeText)
            ?.groupValues
            ?.get(0)
            ?.let { Regex("""FontWeight\((\d+)\)""").find(it)?.groupValues?.get(1) }
            ?.toInt()
            ?: error("FontWeight not found for $name")

    /** `Color(0xFF10171E)` in the generated kit theme as an ARGB long. */
    fun kitColorArgb(name: String): Long =
        kitRegex("""val\s+$name\s*=\s*Color\((0x[0-9A-Fa-f]{8})\)""")
            .groupValues[1]
            .removePrefix("0x")
            .toLong(16)

    // ── the generated artifacts' own declaration SETS ───────────────────────
    //
    // #2829 (2): the per-key loops above walk `tokens.json` and look the key up
    // in the generated artifact, so they only ever catch a token that drifted
    // or disappeared. A val the artifact carries with NO key behind it — the
    // shape a regeneration from a stale JSON leaves, and the shape the retired
    // 32 dp `spaceSection` rung had — passes every one of them silently. The
    // readers below expose the artifact's own val names so the pins can assert
    // set EQUALITY instead, the same both-ways discipline the Kotlin side got
    // in #2800/#2810.

    /** Every `val <name> = <n>.dp` declared in the generated kit theme. */
    val kitDpNames: Set<String> by lazy { kitNames("""val\s+(\w+)\s*=\s*\d+\.dp""") }

    /** Every `val <name> = Color(0x…)` declared in the generated kit theme. */
    val kitColorNames: Set<String> by lazy { kitNames("""val\s+(\w+)\s*=\s*Color\(0x""") }

    /** Every `val <name> = TextStyle(…)` declared in the generated kit theme. */
    val kitTextStyleNames: Set<String> by lazy { kitNames("""val\s+(\w+)\s*=\s*TextStyle\(""") }

    private fun kitNames(pattern: String): Set<String> =
        Regex(pattern).findAll(kitThemeText).map { it.groupValues[1] }.toSet()

    // ── the generated browser hand-off (tokens.css) ──────────────────────────
    //
    // #2829 (4): `tokens.css` is the kit's OTHER generated artifact and nothing
    // read it — no test parsed it, no script checked it. It is one half of the
    // same hand-off `PocketShellTheme.kt` is the other half of, so it drifts
    // the same way and needs the same pin.

    /** `--<name>:<value>;` in the generated stylesheet, verbatim and trimmed. */
    fun cssVar(name: String): String =
        Regex("""--${Regex.escape(name)}:\s*([^;]+);""")
            .find(tokensCssText)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?: error("CSS custom property --$name not found in $TOKENS_CSS_RELATIVE_PATH")

    /** `--<name>:<n>px;` as an Int. */
    fun cssPx(name: String): Int =
        cssVar(name).removeSuffix("px").toIntOrNull()
            ?: error("CSS custom property --$name is not a plain px value: ${cssVar(name)}")

    /**
     * A `type` facet from the stylesheet's `calc(<n>px * var(--font-scale,1))`.
     *
     * The browser scales type with a viewport variable the JSON has no concept
     * of, so the number is pinned out of the `calc()` rather than compared as a
     * whole string.
     */
    fun cssTypePx(role: String, facet: String): Int =
        Regex("""calc\((\d+)px""")
            .find(cssVar("type-$role-$facet"))
            ?.groupValues
            ?.get(1)
            ?.toInt()
            ?: error("CSS --type-$role-$facet is not a calc(<n>px …) value: ${cssVar("type-$role-$facet")}")

    fun cssTypeWeight(role: String): Int = cssVar("type-$role-weight").toInt()

    /** Every `--<prefix>-<name>` custom property declared in the stylesheet. */
    fun cssVarNames(prefix: String): Set<String> =
        Regex("""--${Regex.escape(prefix)}-([A-Za-z]+):""")
            .findAll(tokensCssText)
            .map { it.groupValues[1] }
            .toSet()

    /** Every `type` role the stylesheet emits, read off its `--type-<role>-size` properties. */
    val cssTypeRoles: Set<String> by lazy {
        Regex("""--type-([A-Za-z]+)-size:""")
            .findAll(tokensCssText)
            .map { it.groupValues[1] }
            .toSet()
    }

    /** Every bare `--<name>:` custom property (the `color` block has no prefix). */
    val cssBareVarNames: Set<String> by lazy {
        Regex("""--([A-Za-z]+):""").findAll(tokensCssText).map { it.groupValues[1] }.toSet()
    }

    // ── docs/design-system.md ────────────────────────────────────────────────
    //
    // #2829 (1): the density table is the human-facing half of
    // `PocketShellDensity`, and #2800 added four rungs to the Kotlin object
    // without adding them to the table. Prose cannot be compiled, so the only
    // thing that keeps it honest is a test that reads it.

    /**
     * The `Token` column of the density table under `### Spacing And Density`
     * in `docs/design-system.md`.
     *
     * Anchored on the HEADING, not on the table's own caption: an earlier
     * draft cut at the first occurrence of the caption text and a single
     * cross-reference to it elsewhere in the doc silently moved the cut point,
     * leaving the parser returning an empty set.
     */
    val docDensityTableTokens: Set<String> by lazy {
        val section = designSystemDocText
            .substringAfter(DENSITY_SECTION_HEADING, "")
            .takeIf { it.isNotEmpty() }
            ?: error(
                "`$DENSITY_SECTION_HEADING` not found in $DESIGN_SYSTEM_DOC_RELATIVE_PATH",
            )
        section.lineSequence()
            // Stop at the next markdown heading so a later table cannot leak in.
            .takeWhile { line -> !line.startsWith("#") }
            .mapNotNull { Regex("""^\|\s*`(\w+)`\s*\|""").find(it)?.groupValues?.get(1) }
            .toSet()
    }

    private const val DENSITY_SECTION_HEADING = "### Spacing And Density"

    /** Every `val <name>` declared inside `object PocketShellDensity` in `Spacing.kt`. */
    val densityValNames: Set<String> by lazy {
        val body = densitySourceText
            .substringAfter("object PocketShellDensity {", "")
            .takeIf { it.isNotEmpty() }
            ?: error("`object PocketShellDensity` not found in $DENSITY_SOURCE_RELATIVE_PATH")
        body.lineSequence()
            .takeWhile { it != "}" }
            .mapNotNull { Regex("""^\s{4}val\s+(\w+)\s*=""").find(it)?.groupValues?.get(1) }
            .toSet()
    }

    /** M3 shape slot -> kit radius val, mirroring `PocketShellQuietTheme`'s mapping. */
    fun kitShapeSlotRadius(slot: String): Int = when (slot) {
        "small" -> kitDp("fieldRadius")
        "medium" -> kitDp("buttonRadius")
        "large" -> kitDp("sheetRadius")
        else -> error("Unknown M3 shape slot $slot")
    }

    private fun kitRegex(pattern: String): MatchResult =
        Regex(pattern).find(kitThemeText) ?: error("Pattern not found in kit theme: $pattern")

    /**
     * Resolve a repo file whether the JVM test's working directory is the repo
     * root (some gates) or the module directory (Gradle's default).
     */
    private fun locate(relativePath: String): File =
        sequenceOf(relativePath, "../../$relativePath")
            .map(::File)
            .firstOrNull { it.isFile }
            ?: error(
                "Could not locate $relativePath from ${File(".").absolutePath} — " +
                    "run from the repo root or the module directory",
            )
}
