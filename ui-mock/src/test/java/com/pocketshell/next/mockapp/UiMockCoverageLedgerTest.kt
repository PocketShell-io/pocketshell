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
        // D17 closed the browser-fixture gaps: every destination now has at
        // least one catalogued production-composable fixture. Fixture and
        // interactive-state coverage remain distinct columns — a "yes" seam
        // never implies a fixture, and the GAP marker stays the mechanism for
        // any future gap.
        assertTrue(
            "the ledger must keep fixture coverage and the interactive-state seam as distinct columns, with every destination fixture-covered",
            ledger.all { (_, fixture, _) -> fixture.startsWith("yes") },
        )
        // D16 landed the runnable `:ui-mock-app` shell: the ledger must keep
        // stating the runnable count honestly (6/28 wired; the rest stay
        // explicit gaps, three as labeled placeholders). The exact wired set
        // is pinned by the runnable-column test below.
        assertTrue(
            "the ledger must keep stating the runnable-shell count honestly",
            File(root, "ui-mock/README.md").readText().contains("6/28 rendered by the runnable"),
        )
    }

    /**
     * Slice D16 added the fourth, "Runnable shell" column (#2636). Parsed
     * additively here so sibling lanes can keep editing the fixture/seam
     * columns; a merge-time union of both columns' edits is expected and safe.
     */
    @Test
    fun `runnable shell column names exactly the destinations the mock app really wires`() {
        val ledger = File(root, "ui-mock/README.md").readLines()
            .mapNotNull { line ->
                Regex("^\\| ([A-Z]\\w*) \\| (?:yes(?: \\([^|]+\\))?|GAP) \\| (?:yes|no) \\| (yes|no)(?: \\([^|]+\\))? \\|")
                    .find(line)
                    ?.let { it.groupValues[1] to it.groupValues[2] }
            }

        assertEquals("runnable column must cover all 28 rows", 28, ledger.size)
        assertEquals(
            "runnable shell set drifted from what :ui-mock-app actually wires",
            setOf("Hosts", "Ports", "Settings", "Usage", "HostForm", "SshKeys"),
            ledger.filter { it.second == "yes" }.map { it.first }.toSet(),
        )
    }
}
