package com.pocketshell.next.mockapp

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keeps the checked-in AC2 coverage ledger aligned with the production graph. */
class UiMockCoverageLedgerTest {
    private val root: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    @Test
    fun `coverage ledger lists each of the 28 current destinations exactly once`() {
        val destinationsSource = File(
            root,
            "app2/src/main/java/com/pocketshell/next/nav/Destinations.kt",
        ).readText()
        val allBlock = requireNotNull(
            Regex("val all:\\s*List<Destination>\\s*get\\(\\)\\s*=\\s*listOf\\(([^)]*)\\)")
                .find(destinationsSource),
        ).groupValues[1]
        val production = Regex("[A-Z]\\w*").findAll(allBlock).map { it.value }.toList()

        val ledger = File(root, "ui-mock/README.md").readLines()
            .mapNotNull { line ->
                Regex("^\\| ([A-Z]\\w*) \\| (yes(?: \\([^|]+\\))?|GAP) \\| (yes|no) \\|")
                    .find(line)
                    ?.let { Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
            }

        assertEquals("production destination count changed; update the AC2 ledger", 28, production.size)
        assertEquals("coverage ledger must preserve production graph order", production, ledger.map { it.first })
        assertEquals("coverage ledger contains duplicate destinations", ledger.size, ledger.map { it.first }.toSet().size)

        // D18 closed the interactive-state seam: every destination is now
        // representable in MockDestination + the pure reducer.
        val interactive = ledger.filter { it.third == "yes" }.map { it.first }.toSet()
        assertEquals(
            "every production destination must stay interactive-state representable",
            production.toSet(),
            interactive,
        )
        assertTrue(
            "the ledger must retain its explicit fixture gaps (the seam is not fixture coverage)",
            ledger.any { (name, fixture, _) -> name == "DiagnosticReport" && fixture == "GAP" },
        )
        assertTrue(
            "the ledger must keep stating that no destination is runnable in a standalone mock app yet",
            File(root, "ui-mock/README.md").readText().contains("0/28 claimed runnable"),
        )
    }
}
