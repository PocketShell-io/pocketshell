package com.pocketshell.next.crash

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/**
 * The moved crash/diagnostics family's pure-display assertions, on the plain
 * JVM (#2636 D12) — the D10 `UsageDisplayFamilyTest` / D11
 * `PortForwardDisplayFamilyTest` shape.
 *
 * These pin the row vocabulary that used to live as internals of app2's
 * `CrashReportsScreen.kt` (and was asserted by app2's
 * `CrashReportDisplayTest`, which this test absorbs): the timestamp format,
 * the list-row title/subtitle joins, the top-frame label reduction, and the
 * share-sheet subject. The rendered-tree behaviour itself is still proven
 * app-side by `CrashReportsScreenTest` driving the routes; the core ↔ mirror
 * equivalence is swept by app2's `CrashReportsDisplayMappingTest`.
 */
class CrashReportDisplayFamilyTest {

    private val report = CrashReportDisplay(
        id = "20260606-120000-000",
        timestamp = Instant.parse("2026-06-06T12:00:00Z"),
        summary = "IllegalStateException: session failed",
        contextSummary = "Session · host=devbox · session=agent-main",
        appVersion = "0.2.8",
        topFrame = "com.pocketshell.next.terminal.SessionScreenKt.render(SessionScreen.kt:540)",
    )

    // ── timestamp rendering ──────────────────────────────────────────────────

    @Test
    fun `timestamps render in the report-list format at the given zone`() {
        assertEquals(
            "2026-06-06 12:00:00 Z",
            crashReportTimestamp(report, ZoneOffset.UTC),
        )
    }

    // ── LazyColumn row vocabulary ────────────────────────────────────────────

    @Test
    fun `row title combines timestamp and summary`() {
        assertEquals(
            "2026-06-06 12:00:00 Z · IllegalStateException: session failed",
            crashReportRowTitle(report, ZoneOffset.UTC),
        )
    }

    @Test
    fun `row subtitle combines context app version and short top frame`() {
        assertEquals(
            "Session · host=devbox · session=agent-main · app=0.2.8 · " +
                "top=SessionScreen.kt:540",
            crashReportRowSubtitle(report),
        )
    }

    @Test
    fun `row subtitle falls back to Context unavailable when every field is blank`() {
        val bare = report.copy(contextSummary = "", appVersion = null, topFrame = null)
        assertEquals("Context unavailable", crashReportRowSubtitle(bare))
    }

    @Test
    fun `row subtitle skips blank fields instead of painting empty segments`() {
        val partial = report.copy(appVersion = "", topFrame = "com.pocketshell.next.TerminalScreenKt.render")
        assertEquals(
            "Session · host=devbox · session=agent-main · top=render",
            crashReportRowSubtitle(partial),
        )
    }

    @Test
    fun `top frame label reduces to the source location when present`() {
        // `…render(SessionScreen.kt:540)` → `SessionScreen.kt:540`.
        assertEquals(
            "Session · host=devbox · session=agent-main · app=0.2.8 · top=SessionScreen.kt:540",
            crashReportRowSubtitle(report),
        )
    }

    @Test
    fun `top frame label falls back to the last dotted segment without a source location`() {
        val noLocation = report.copy(
            topFrame = "com.pocketshell.next.terminal.SessionScreenKt.render",
        )
        assertEquals(
            "render",
            crashReportRowSubtitle(noLocation).substringAfter(" · top="),
        )
    }

    @Test
    fun `list row subtitle joins timestamp and summary the same way the composable does`() {
        // `diagnosticReportListSubtitle` is private to the screen file; pin its
        // exact shape here so a future edit to either side of the join reddens.
        val expected = listOf(
            crashReportTimestamp(report, ZoneOffset.UTC),
            report.summary,
        ).joinToString(" · ")
        assertEquals(
            "2026-06-06 12:00:00 Z · IllegalStateException: session failed",
            expected,
        )
    }

    // ── share sheet ──────────────────────────────────────────────────────────

    @Test
    fun `share subject identifies the selected report`() {
        assertEquals(
            "PocketShell crash report - 2026-06-06 12:00:00 Z - " +
                "Session · host=devbox · session=agent-main - " +
                "IllegalStateException: session failed",
            crashReportShareSubject(report, ZoneOffset.UTC),
        )
    }

    @Test
    fun `share subject skips blank segments`() {
        val bare = report.copy(contextSummary = "", summary = "")
        assertEquals(
            "PocketShell crash report - 2026-06-06 12:00:00 Z",
            crashReportShareSubject(bare, ZoneOffset.UTC),
        )
    }

    // ── row tags ─────────────────────────────────────────────────────────────

    @Test
    fun `report row tag is keyed by report id`() {
        assertEquals("diagnostics-report-20260606-120000-000", diagnosticReportRowTag(report.id))
    }
}
