package com.pocketshell.next.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * The faithful-mapping proof for the #2636 D12 seam: the app2 `CrashReport`
 * the store produces maps onto the pure [CrashReportDisplay] mirror with
 * display-identical values, across every field the screens paint — not one
 * happy path. This is the test that lets the family's PNGs be compared
 * byte-for-byte across the move: the shared screens paint the mirrors, so if
 * the mirrors equal what the store produced, the pixels cannot change.
 *
 * The core model's `file: File` is deliberately NOT carried (the D11
 * `hostId`-drop rule): no screen paints it, and the view model resolves
 * read/delete against its own core list by id. The nullable fields'
 * null-passthrough is asserted, because the store legitimately produces
 * `appVersion`/`topFrame` nulls for legacy report files.
 */
class CrashReportsDisplayMappingTest {

    @Test
    fun `a full report maps all six display fields verbatim`() {
        val core = CrashReport(
            id = "20260606-120000-000",
            timestamp = Instant.parse("2026-06-06T12:00:00Z"),
            file = File("unused.txt"),
            summary = "IllegalStateException: session failed",
            contextSummary = "Session · host=devbox · session=agent-main",
            appVersion = "0.2.8",
            topFrame = "com.pocketshell.next.terminal.SessionScreenKt.render(SessionScreen.kt:540)",
        )

        assertEquals(
            CrashReportDisplay(
                id = "20260606-120000-000",
                timestamp = Instant.parse("2026-06-06T12:00:00Z"),
                summary = "IllegalStateException: session failed",
                contextSummary = "Session · host=devbox · session=agent-main",
                appVersion = "0.2.8",
                topFrame = "com.pocketshell.next.terminal.SessionScreenKt.render(SessionScreen.kt:540)",
            ),
            core.toDisplay(),
        )
    }

    @Test
    fun `nullable fields pass through as null when the store could not recover them`() {
        val core = CrashReport(
            id = "20260101-000000-000",
            timestamp = Instant.ofEpochMilli(1_734_568_000_000),
            file = File("unused.txt"),
            summary = "Crash report",
            contextSummary = "Context unavailable",
            appVersion = null,
            topFrame = null,
        )

        val display = core.toDisplay()
        assertNull(display.appVersion)
        assertNull(display.topFrame)
        assertEquals("20260101-000000-000", display.id)
        assertEquals(Instant.ofEpochMilli(1_734_568_000_000), display.timestamp)
        assertEquals("Crash report", display.summary)
        assertEquals("Context unavailable", display.contextSummary)
    }

    @Test
    fun `the display mirror does not gain a file field - id stays the LazyColumn key`() {
        // The mirror is data-class-equal only on the six painted fields; the
        // core list's file identity must not leak into it (two reports are the
        // same display row iff id/timestamp/summary/context/app/topFrame match).
        val fileA = File("a.txt")
        val fileB = File("b.txt")
        fun core(file: File) = CrashReport(
            id = "20260606-120000-000",
            timestamp = Instant.parse("2026-06-06T12:00:00Z"),
            file = file,
            summary = "IllegalStateException: session failed",
            contextSummary = "Session",
            appVersion = "0.2.8",
            topFrame = null,
        )

        assertEquals(core(fileA).toDisplay(), core(fileB).toDisplay())
    }
}
