package com.pocketshell.next.composer

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The enforcement half of #2802 C-3/C-4: one word per action, and no tag left
 * pointing at a deleted row.
 *
 * `ComposerBarTest` proves what the composer RENDERS. That is not enough on its
 * own for a vocabulary issue: the audit's finding was that the same action was
 * called four different things across the visible label, the accessible name,
 * the composable name and the test tag — and three of those four are source
 * facts a rendered assertion cannot see. So this test reads the composer
 * sources off disk, the way `TokenLiteralGuardTest` reads the UI roots, and
 * fails when a retired word comes back.
 *
 * Deliberately a *word* ban rather than a spelling check: the failure mode this
 * guards is somebody re-introducing "Paste" (or "Add to input") in one surface
 * while the others still say "Insert", which is exactly how the drift got here.
 */
class ComposerVocabularyTest {

    @Test
    fun scanRootsAreRealAndPopulated() {
        // Same anti-vacuity floor as TokenLiteralGuardTest: a guard pointed at
        // an empty tree reports perfection over nothing.
        SCAN_ROOTS.forEach { (root, minimumFiles) ->
            val dir = repoFile(root)
            assertTrue("scan root $root does not exist — this guard would scan nothing", dir.isDirectory)
            val count = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.count()
            assertTrue(
                "scan root $root has $count Kotlin files, expected at least $minimumFiles",
                count >= minimumFiles,
            )
        }
        assertTrue(
            "the composer package itself must be in scope — it is the file this issue is about",
            occurrencesOf("COMPOSER_INSERT_TAG").isNotEmpty(),
        )
    }

    /**
     * #2802 C-3: the pill's own name. `composer-insert` and `InsertButton`
     * already said *insert*; the visible strings moved to meet them.
     */
    @Test
    fun theInsertActionIsSpelledInsertEverywhere() {
        assertEquals("Insert", COMPOSER_INSERT_LABEL)
        assertTrue(
            "the accessible name must be the same verb, spelled out: $COMPOSER_INSERT_DESCRIPTION",
            COMPOSER_INSERT_DESCRIPTION.startsWith("Insert "),
        )
        assertTrue(
            "the test tag must carry the same verb: $COMPOSER_INSERT_TAG",
            COMPOSER_INSERT_TAG.contains("insert"),
        )
        assertEquals(
            "the insert pill is spelled `InsertButton` in exactly one place",
            listOf("app2/src/main/java/com/pocketshell/next/composer/ComposerBar.kt"),
            occurrencesOf("private fun InsertButton").map { it.first }.distinct(),
        )
    }

    @Test
    fun theRetiredInsertVocabularyIsGone() {
        RETIRED_WORDS.forEach { (word, why) ->
            assertEquals(
                "`$word` is back in the composer sources — $why (#2802 C-3)",
                emptyList<String>(),
                occurrencesOf(word).map { "${it.first}:${it.second}" },
            )
        }
    }

    /**
     * #2802 C-4, AC6: the composer's route to the hotkeys palette is a D22 hard
     * cut — the row, its tag, and any test still reaching for that tag. A test
     * left pointing at a deleted node is the "instrumentation pointing at dead
     * tags" the acceptance criteria call out; it would pass by asserting
     * nothing, or fail for the wrong reason.
     */
    @Test
    fun theComposerHasNoHotkeysEntryPointLeft() {
        assertEquals(
            "the `composer-tools-hotkeys` row is deleted, so nothing in the tree — source, " +
                "unit test or journey — may still name its tag (#2802 C-4)",
            emptyList<String>(),
            occurrencesOf("composer-tools-hotkeys", includeTests = true).map { "${it.first}:${it.second}" },
        )
        assertEquals(
            "`onOpenHotkeys` was the plumbing for that one row; D22 says the parameter goes " +
                "with it, not a dead lambda nobody passes",
            emptyList<String>(),
            occurrencesOf("onOpenHotkeys", includeTests = true).map { "${it.first}:${it.second}" },
        )
    }

    // --- scanning -----------------------------------------------------------

    /** `file` to `line number` for every live code line containing [needle]. */
    private fun occurrencesOf(needle: String, includeTests: Boolean = false): List<Pair<String, Int>> {
        val roots = if (includeTests) SCAN_ROOTS + TEST_SCAN_ROOTS else SCAN_ROOTS
        val hits = mutableListOf<Pair<String, Int>>()
        roots.keys.forEach { root ->
            val base = repoFile(root)
            base.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .sortedBy { it.path }
                .forEach { file ->
                    val relative = root + file.path.substringAfter(base.path)
                    // This guard file names every banned word by construction.
                    if (relative.endsWith("ComposerVocabularyTest.kt")) return@forEach
                    codeLines(file).forEachIndexed { index, code ->
                        // A test may PROVE a retired node is gone; it may not
                        // drive one. `assertDoesNotExist` is the difference
                        // between a deliberate absence proof and stale
                        // instrumentation pointing at a dead tag.
                        if (code.contains("assertDoesNotExist")) return@forEachIndexed
                        if (code.contains(needle)) hits += relative to (index + 1)
                    }
                }
        }
        return hits
    }

    /**
     * Source lines with comments stripped, so prose explaining WHY a word was
     * retired is not itself a violation. Same shape as
     * `TokenLiteralGuardTest.codeLines`, and for the same reason: without it
     * the only way to document a ban is to stop describing it.
     */
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

    private fun repoFile(relativePath: String): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull { it.exists() }
            ?: error("Could not locate $relativePath from ${File("").absolutePath}")

    private companion object {
        /** Production UI sources: where a user-visible word can come back. */
        val SCAN_ROOTS = linkedMapOf(
            "app2/src/main" to 100,
            "shared/ui-kit/src/main" to 30,
            "shared/ui-screens/src/main" to 20,
        )

        /** Added for the dead-tag sweep only (AC6). */
        val TEST_SCAN_ROOTS = linkedMapOf(
            "app2/src/test" to 50,
            "app2/src/androidTest" to 20,
            "shared/ui-kit/src/test" to 20,
        )

        val RETIRED_WORDS = listOf(
            "\"Paste\"" to "the insert pill's visible label is `Insert` now",
            "Paste without submitting" to "the accessible name is `$COMPOSER_INSERT_DESCRIPTION`",
            "Add to input" to
                "the `+` trigger and its sheet are both called `$COMPOSER_TOOLS_TITLE` — a noun, " +
                "matching the header grammar of every other sheet SessionScreen opens (C-5)",
        )
    }
}
