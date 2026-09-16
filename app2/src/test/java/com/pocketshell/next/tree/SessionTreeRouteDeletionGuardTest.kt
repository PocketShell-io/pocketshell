package com.pocketshell.next.tree

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * Issue #2726: `SessionTreeRoute`/`SessionTreeScreen` were unreachable — no
 * `composable(...)` destination ever hosted them (`Destination.Tree` resolves
 * to the Workspaces pattern) — so the composables and their tests were
 * deleted, and the shared test-tag vocabulary moved to [SessionTreeTags].
 *
 * This guard pins the deletion in both directions: the dead file stays gone,
 * and nothing under `app2/src/main` can quietly start referencing the deleted
 * names again (a new stale import is exactly how the drift would regrow).
 * Re-adding a live tree route is a real decision — bring back a destination
 * and fresh journeys, and delete this guard in the same change.
 */
class SessionTreeRouteDeletionGuardTest {

    @Test
    fun `the deleted screen file stays deleted`() {
        val screen = locate(
            "app2/src/main/java/com/pocketshell/next/tree/SessionTreeScreen.kt",
            "src/main/java/com/pocketshell/next/tree/SessionTreeScreen.kt",
        )
        assertFalse(
            "SessionTreeScreen.kt was deleted as unreachable (#2726) but exists again at " +
                screen.absolutePath,
            screen.exists(),
        )
    }

    @Test
    fun `no production source references the deleted route or screen names`() {
        val mainRoot = locateDir("app2/src/main", "src/main")
        val offenders = mainRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { DELETED_NAME.containsMatchIn(it.readText()) }
            .map { it.relativeTo(mainRoot).path }
            .toList()

        assertTrue(
            "SessionTreeRoute/SessionTreeScreen are deleted (#2726); these production " +
                "sources still reference them: $offenders",
            offenders.isEmpty(),
        )
    }

    /** Matches the deleted top-level names only — `SessionTreeViewModel` and
     *  friends stay alive as the workspace screens' tree-package API. */
    private companion object {
        val DELETED_NAME = Regex("""\bSessionTree(Route|Screen)\b""")
    }

    private fun locate(vararg candidates: String): File = candidates
        .asSequence()
        .map(::File)
        .firstOrNull { it.parentFile != null && it.parentFile.isDirectory }
        ?: error("Could not locate any of ${candidates.joinToString()} from ${File(".").absolutePath}")

    private fun locateDir(vararg candidates: String): File = candidates
        .asSequence()
        .map(::File)
        .firstOrNull { it.isDirectory }
        ?: error("Could not locate any of ${candidates.joinToString()} from ${File(".").absolutePath}")
}
