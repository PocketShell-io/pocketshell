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

    val tokensJsonText: String by lazy { locate(TOKENS_RELATIVE_PATH).readText() }

    val kitThemeText: String by lazy { locate(KIT_THEME_RELATIVE_PATH).readText() }

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
