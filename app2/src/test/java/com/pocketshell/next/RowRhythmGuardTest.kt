package com.pocketshell.next

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The enforcement half of the one inter-row rhythm (#2804, #2635 audit P-3).
 *
 * `ListRow` paints a divider under every row, and that divider IS the
 * separator. The audit found the same list written three ways — Hosts with no
 * `verticalArrangement` (dividers, zero gap), the Settings index with
 * `spacedBy(xs)` and a divider, the settings sub-page scaffold with
 * `spacedBy(md)` and a divider — so two of the three floated a hairline in the
 * middle of a gap, which reads as neither a separator nor a group break.
 *
 * One rhythm is now the rule: **divider, zero gap**. A block that is
 * deliberately gapped (a form, a sheet, rows interleaved with fields, prose or
 * buttons) turns the divider off with `showDivider = false` instead of doubling
 * up. This test fails the build on any list that does both.
 *
 * ## How it decides
 *
 * It reads `app2/src/main` and `shared/ui-screens/src/main` off disk — no
 * compile dependency, so one test in `app2` covers both roots, the same shape
 * [SystemBarColorTokenTest] uses for its source pin. For every `Column` /
 * `LazyColumn` whose arguments carry `verticalArrangement =
 * Arrangement.spacedBy(...)`, it walks the container's own lambda body and
 * collects the divider-bearing row calls that are that container's DIRECT
 * visual children.
 *
 * "Direct visual child" is resolved by descending through constructs that do
 * not introduce a layout of their own — `if` / `else` / `when` branches,
 * `forEach`, `items` / `item` / `itemsIndexed` — and stopping at any
 * upper-camel call with a trailing lambda, which in Compose is another
 * composable owning its own content. So the rows of a nested ungapped
 * `Column { ListRow(); ListRow() }` belong to that inner list (correct: a
 * dense block inside a gapped page), while `items(hosts) { ListRow(...) }`
 * belongs to the gapped `LazyColumn` (correct: the gap lands between those
 * rows).
 *
 * ## Known bound, stated rather than hidden
 *
 * The scan is lexical and single-file, so a container that takes its content
 * as a `content = <lambda parameter>` — `SettingsPageScaffold`'s `LazyColumn`,
 * `SshKeysScreen`'s page/inset columns — has no rows to find here. The
 * scaffold is the one the audit named, so it gets its own named pin below
 * ([settingsPageScaffoldSpacesNothingAroundItsCallerSuppliedRows]); the
 * SSH-keys columns carry prose and fields, and the choice rows their callers
 * pass sit at `showDivider = false` for that reason.
 */
class RowRhythmGuardTest {

    @Test
    fun scanRootsAreRealAndPopulated() {
        // A guard pointed at a tree that moved reports perfection over nothing
        // — the failure mode `check-design-tokens.sh` actually shipped. The
        // floors below are part of the contract.
        SCAN_ROOTS.forEach { (root, minimumFiles) ->
            val dir = repoFile(root)
            assertTrue("scan root $root does not exist — this guard would scan nothing", dir.isDirectory)
            val kotlinFiles = dir.walkTopDown().count { it.isFile && it.extension == "kt" }
            assertTrue(
                "scan root $root has $kotlinFiles Kotlin files, expected at least $minimumFiles — " +
                    "a shrunken root means this guard is reporting perfection over nothing",
                kotlinFiles >= minimumFiles,
            )
        }
    }

    @Test
    fun noGappedListAlsoPaintsRowDividers() {
        val offenders = SCAN_ROOTS.keys.flatMap { root ->
            repoFile(root).walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .sortedBy { it.path }
                .flatMap { file ->
                    RowRhythm.findings(file.readText()).map { finding ->
                        "$root/${file.relativeTo(repoFile(root)).path}: " +
                            "${finding.container} at line ${finding.containerLine} adds " +
                            "${finding.gap} and holds ${finding.row} at line ${finding.rowLine}"
                    }
                }
        }
        assertEquals(
            "a list combines a verticalArrangement gap with a row that still paints its divider " +
                "(#2804). Pick one: drop the `verticalArrangement` so the dividers separate the rows " +
                "(the Hosts rhythm, densest), or pass `showDivider = false` on the rows if the gap is " +
                "deliberate (a form, a sheet, rows interleaved with fields/prose/buttons).",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun settingsPageScaffoldSpacesNothingAroundItsCallerSuppliedRows() {
        // The scaffold's LazyColumn takes `content` from its caller, so the
        // scan above cannot see the rows it holds. Every categorized settings
        // sub-page renders through it, and it is where the widest of the three
        // rhythms (a 12dp gap around every divider) lived, so it is pinned by
        // name instead.
        val source = RowRhythm.stripCommentsAndStrings(
            repoFile("shared/ui-screens/src/main/java/com/pocketshell/next/settings/SettingsPageScaffold.kt")
                .readText(),
        )
        val lazyColumnArgs = Regex("""\bLazyColumn\s*\(""").find(source)
            ?.let { match ->
                val open = match.range.last
                source.substring(open, RowRhythm.matchForward(source, open, '(', ')') + 1)
            }
            ?: error("SettingsPageScaffold.kt no longer calls LazyColumn — update this pin")
        assertTrue(
            "SettingsPageScaffold's LazyColumn is the list every categorized settings sub-page " +
                "renders into, and its items are divider-bearing rows it cannot see. It must add no " +
                "gap of its own (#2804); a block that needs breathing room (SettingsDescription, " +
                "SettingsSlider, a page button) carries its own vertical padding instead.",
            !lazyColumnArgs.contains("verticalArrangement"),
        )
        assertTrue(
            "SettingsPageScaffold's LazyColumn no longer forwards caller content — update this pin",
            lazyColumnArgs.contains("content = content"),
        )
    }

    // ---- the detector's own red/green, so a green scan above cannot be vacuous ----

    @Test
    fun detectorFlagsAGappedListWhoseRowsKeepTheirDividers() {
        val findings = RowRhythm.findings(
            """
            @Composable
            private fun Offender(items: List<String>) {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.md),
                ) {
                    item { SectionHeader(label = "Hosts") }
                    items(items) { name -> ListRow(title = name) }
                }
            }
            """.trimIndent(),
        )
        assertEquals(listOf("LazyColumn" to "ListRow"), findings.map { it.container to it.row })
    }

    @Test
    fun detectorAcceptsTheDocumentedEscapeHatch() {
        assertEquals(
            emptyList<RowRhythm.Finding>(),
            RowRhythm.findings(
                """
                @Composable
                private fun Form() {
                    Column(verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm)) {
                        OutlinedTextField(value = "", onValueChange = {})
                        ListRow(title = "Browse folders", showDivider = false)
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun detectorAcceptsAnUngappedListNestedInsideAGappedPage() {
        // The grammar this guard is protecting: gapped BLOCKS, dense rows
        // inside a block. Flagging this would push dividers off dense lists.
        assertEquals(
            emptyList<RowRhythm.Finding>(),
            RowRhythm.findings(
                """
                @Composable
                private fun Page(options: List<String>) {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.md)) {
                        item { SectionHeader(label = "Speech recognition") }
                        item {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                options.forEach { option -> QuietChoiceRow(title = option) }
                            }
                        }
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun detectorSeesRowsBehindForEachAndBranches() {
        // The nesting the real tree uses: a `forEach` or an `if` is not a
        // layout, so the rows it emits are still the gapped list's children.
        val findings = RowRhythm.findings(
            """
            @Composable
            private fun Picker(programs: List<String>, expanded: Boolean) {
                Column(verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs)) {
                    programs.forEach { program -> ListRow(title = program) }
                    if (expanded) {
                        WorkspaceRow(title = "More", onClick = {})
                    }
                }
            }
            """.trimIndent(),
        )
        assertEquals(listOf("ListRow", "WorkspaceRow"), findings.map { it.row })
    }

    @Test
    fun detectorIgnoresRowNamesInsideCommentsAndStrings() {
        assertEquals(
            emptyList<RowRhythm.Finding>(),
            RowRhythm.findings(
                """
                @Composable
                private fun Prose() {
                    Column(verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.md)) {
                        // ListRow(title = "not a call")
                        Text(text = "ListRow(title = 1)")
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun detectorSurvivesNestedQuotesInsideAStringTemplate() {
        // CreateSessionSheet.kt really contains
        // `"In ${'$'}{name.ifBlank { "current workspace" }}"`; a string skipper that
        // stops at the first inner quote loses brace balance for the rest of
        // the file and the scan silently stops finding anything.
        val findings = RowRhythm.findings(
            """
            @Composable
            private fun Templated(name: String) {
                Column(verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs)) {
                    Text(text = "In ${'$'}{name.ifBlank { "current workspace" }}")
                    ListRow(title = name)
                }
            }
            """.trimIndent(),
        )
        assertEquals(listOf("ListRow"), findings.map { it.row })
    }

    private companion object {
        /** Root -> the Kotlin-file floor that proves the root is still the real tree. */
        private val SCAN_ROOTS = mapOf(
            "app2/src/main" to 120,
            "shared/ui-screens/src/main" to 30,
        )

        private fun repoFile(path: String): File =
            listOf(File(path), File("../$path"))
                .firstOrNull { it.exists() }
                ?: error("Could not locate $path from ${File(".").absolutePath}")
    }
}

/**
 * The lexical scan behind [RowRhythmGuardTest]. Kept as a plain object with a
 * pure `String -> findings` entry point so the detector itself has red and
 * green arms in the test above rather than only ever being run over a tree
 * that is expected to be clean.
 */
internal object RowRhythm {

    data class Finding(
        val container: String,
        val containerLine: Int,
        val gap: String,
        val row: String,
        val rowLine: Int,
    )

    /** Rows that paint a `ListRow` divider — directly, or by wrapping one. */
    private val ROW_CALLS = setOf("ListRow", "QuietChoiceRow", "WorkspaceRow")

    private val CONTAINER = Regex("""\b(Column|LazyColumn)\s*\(""")
    // `[\w.]*` so a fully-qualified `androidx.compose.foundation.layout.
    // Arrangement.spacedBy(...)` — three call sites spell it that way — is not
    // a hole in the scan.
    private val SPACED_BY = Regex("""verticalArrangement\s*=\s*([\w.]*\bspacedBy\s*\([^\n]*)""")
    private val SHOW_DIVIDER_OFF = Regex("""showDivider\s*=\s*false""")

    fun findings(rawSource: String): List<Finding> {
        val source = stripCommentsAndStrings(rawSource)
        val findings = mutableListOf<Finding>()
        CONTAINER.findAll(source).forEach { match ->
            val open = match.range.last
            val close = matchForward(source, open, '(', ')')
            if (close < 0) return@forEach
            val args = source.substring(open, close + 1)
            val gap = SPACED_BY.find(args)?.groupValues?.get(1)?.trim()?.trimEnd(',') ?: return@forEach
            var body = close + 1
            while (body < source.length && source[body].isWhitespace()) body++
            if (body >= source.length || source[body] != '{') return@forEach
            val bodyEnd = matchForward(source, body, '{', '}')
            if (bodyEnd < 0) return@forEach
            val rows = mutableListOf<Pair<Int, String>>()
            collectDirectRows(source, body + 1, bodyEnd, rows)
            rows.forEach { (offset, name) ->
                val callOpen = source.indexOf('(', offset)
                val callClose = matchForward(source, callOpen, '(', ')')
                val callArgs = if (callClose > 0) source.substring(callOpen, callClose + 1) else ""
                if (!SHOW_DIVIDER_OFF.containsMatchIn(callArgs)) {
                    findings += Finding(
                        container = match.groupValues[1],
                        containerLine = lineOf(source, match.range.first),
                        gap = gap,
                        row = name,
                        rowLine = lineOf(source, offset),
                    )
                }
            }
        }
        return findings
    }

    /**
     * Collects the row calls in `[from, to)` that are the enclosing
     * container's own children: descend through non-layout constructs, stop at
     * any upper-camel call that owns a trailing content lambda.
     */
    private fun collectDirectRows(src: String, from: Int, to: Int, out: MutableList<Pair<Int, String>>) {
        var i = from
        while (i < to) {
            val ch = src[i]
            if (ch == '{') {
                // A bare block: an `if`/`else`/`when` branch or a lambda value.
                val end = matchForward(src, i, '{', '}')
                if (end < 0 || end >= to) return
                collectDirectRows(src, i + 1, end, out)
                i = end + 1
                continue
            }
            if (ch.isIdentifierStart() && (i == 0 || !src[i - 1].isIdentifierPart())) {
                var j = i
                while (j < to && src[j].isIdentifierPart()) j++
                val name = src.substring(i, j)
                var k = j
                while (k < to && src[k].isWhitespace()) k++
                val parenOpen = if (k < to && src[k] == '(') k else -1
                val parenClose = if (parenOpen >= 0) matchForward(src, parenOpen, '(', ')') else -1
                var lambdaStart = if (parenClose > 0) parenClose + 1 else k
                while (lambdaStart < to && src[lambdaStart].isWhitespace()) lambdaStart++
                val hasLambda = lambdaStart < to && src[lambdaStart] == '{'
                val lambdaEnd = if (hasLambda) matchForward(src, lambdaStart, '{', '}') else -1
                when {
                    name in ROW_CALLS && parenClose > 0 -> {
                        out += i to name
                        i = parenClose + 1
                    }
                    // An upper-camel call is another composable: its content is
                    // its own, not this container's.
                    name.first().isUpperCase() ->
                        i = when {
                            hasLambda && lambdaEnd > 0 -> lambdaEnd + 1
                            parenClose > 0 -> parenClose + 1
                            else -> j
                        }
                    hasLambda && lambdaEnd > 0 -> {
                        collectDirectRows(src, lambdaStart + 1, lambdaEnd, out)
                        i = lambdaEnd + 1
                    }
                    else -> i = if (parenClose > 0) parenClose + 1 else j
                }
                continue
            }
            i++
        }
    }

    fun matchForward(src: String, start: Int, open: Char, close: Char): Int {
        var depth = 0
        var i = start
        while (i < src.length) {
            when (src[i]) {
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    /**
     * Blanks comments and literals while keeping every newline, so line
     * numbers stay honest and a row name written in prose or in a string is
     * not mistaken for a call.
     */
    fun stripCommentsAndStrings(src: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < src.length) {
            when {
                src.startsWith("/*", i) -> {
                    val end = src.indexOf("*/", i + 2).let { if (it < 0) src.length else it + 2 }
                    out.appendNewlinesOf(src, i, end)
                    i = end
                }
                src.startsWith("//", i) -> {
                    i = src.indexOf('\n', i).let { if (it < 0) src.length else it }
                }
                src[i] == '"' -> {
                    val end = skipStringLiteral(src, i)
                    out.append("\"\"")
                    out.appendNewlinesOf(src, i, end)
                    i = end
                }
                src[i] == '\'' -> {
                    var j = i + 1
                    while (j < src.length && src[j] != '\'') {
                        if (src[j] == '\\') j++
                        j++
                    }
                    out.append("''")
                    i = (j + 1).coerceAtMost(src.length)
                }
                else -> {
                    out.append(src[i])
                    i++
                }
            }
        }
        return out.toString()
    }

    /** Index just past the literal that starts at [start] (its opening quote). */
    private fun skipStringLiteral(src: String, start: Int): Int {
        if (src.startsWith("\"\"\"", start)) {
            val end = src.indexOf("\"\"\"", start + 3)
            return if (end < 0) src.length else end + 3
        }
        var i = start + 1
        while (i < src.length) {
            when {
                src[i] == '\\' -> i += 2
                // A `${…}` template holds real code, including nested string
                // literals; skipping it as a unit is what keeps the rest of the
                // file's brace balance intact.
                src.startsWith("\${", i) -> i = skipInterpolation(src, i + 1)
                src[i] == '"' -> return i + 1
                else -> i++
            }
        }
        return src.length
    }

    /** Index just past the `{…}` of a string template that opens at [start]. */
    private fun skipInterpolation(src: String, start: Int): Int {
        var depth = 0
        var i = start
        while (i < src.length) {
            when {
                src[i] == '"' -> {
                    i = skipStringLiteral(src, i)
                    continue
                }
                src[i] == '{' -> depth++
                src[i] == '}' -> {
                    depth--
                    if (depth == 0) return i + 1
                }
            }
            i++
        }
        return src.length
    }

    private fun StringBuilder.appendNewlinesOf(src: String, from: Int, to: Int) {
        for (index in from until to.coerceAtMost(src.length)) {
            if (src[index] == '\n') append('\n')
        }
    }

    private fun lineOf(src: String, offset: Int): Int =
        src.substring(0, offset).count { it == '\n' } + 1

    private fun Char.isIdentifierStart(): Boolean = isLetter() || this == '_'

    private fun Char.isIdentifierPart(): Boolean = isLetterOrDigit() || this == '_'
}
