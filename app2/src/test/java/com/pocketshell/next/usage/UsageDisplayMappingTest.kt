package com.pocketshell.next.usage

import com.pocketshell.core.usage.UsageProviderRecord
import com.pocketshell.core.usage.UsageResetCredit
import com.pocketshell.core.usage.UsageResetCredits
import com.pocketshell.core.usage.UsageStatus
import com.pocketshell.core.usage.UsageThresholdState
import com.pocketshell.core.usage.UsageWindow
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/**
 * The faithful-mapping proof for the #2636 D10 seam: every `core.usage` shape
 * the usage panel consumes maps onto its pure `UsageProviderRecordDisplay`
 * mirror with display-identical values, across the WHOLE class — every status
 * constant, every threshold band, a warn-percent sweep and a blocked/no-window
 * record — not one happy path. This is the test that lets the panel's PNGs be
 * compared byte-for-byte across the move: the shared screen paints the mirror,
 * so if the mirror equals what core derived, the pixels cannot change.
 *
 * The mirror's re-derived derivations (`thresholdState`/`isBlocked`/
 * `isNearLimit`/`mostConstrainedWindow`) are asserted equal to the core
 * record's own for every case, so a future edit to one side's bands fails here
 * instead of drifting on the maintainer's daily panel.
 */
class UsageDisplayMappingTest {

    @Test
    fun `every UsageStatus constant maps to the mirror constant of the same name`() {
        for (status in UsageStatus.entries) {
            assertEquals(
                UsageStatusDisplay.valueOf(status.name),
                status.toDisplay(),
            )
        }
        // Guard the guard: the exhaustive `when` in the mapper would fail the
        // build on a new core constant, but this loop fails if one side ever
        // RENAMES without the other.
        assertEquals(UsageStatus.entries.size, UsageStatusDisplay.entries.size)
    }

    @Test
    fun `threshold constants are mirrored verbatim`() {
        assertEquals(UsageProviderRecord.WARN_PERCENT, UsageProviderRecordDisplay.WARN_PERCENT, 0.0)
        assertEquals(
            UsageProviderRecord.DEFAULT_WARN_PERCENT,
            UsageProviderRecordDisplay.DEFAULT_WARN_PERCENT,
            0.0,
        )
        assertEquals(
            UsageProviderRecord.CRITICAL_PERCENT,
            UsageProviderRecordDisplay.CRITICAL_PERCENT,
            0.0,
        )
        assertEquals(
            UsageProviderRecord.EXCEEDED_PERCENT,
            UsageProviderRecordDisplay.EXCEEDED_PERCENT,
            0.0,
        )
    }

    @Test
    fun `warrantsWarning agrees between core and mirror for every band`() {
        for (state in UsageThresholdState.entries) {
            assertEquals(
                state.warrantsWarning,
                UsageThresholdStateDisplay.valueOf(state.name).warrantsWarning,
            )
        }
    }

    @Test
    fun `window mapping narrows to name percent resetAt with the derived percent`() {
        val core = UsageWindow(
            name = "7d",
            used = 6.0,
            limit = 8.0,
            unit = "tokens",
            resetAt = RESET_AT,
        )
        val display = core.toDisplay()

        assertEquals(UsageWindowDisplay(name = "7d", percent = 75.0, resetAt = RESET_AT), display)
        // The percent formula stays core-side: 6 of 8 tokens → 75%, not a
        // re-implementation that could drift from the parser.
        assertEquals(core.percent, display.percent, 0.0)
    }

    @Test
    fun `percent-unit windows carry the used value straight through`() {
        val core = UsageWindow(name = "5h", used = 12.5, limit = 100.0, unit = "percent", resetAt = null)
        assertEquals(12.5, core.toDisplay().percent, 0.0)
    }

    @Test
    fun `a zero or negative limit window maps to zero percent, like core`() {
        val core = UsageWindow(name = "5h", used = 3.0, limit = 0.0, unit = "tokens", resetAt = null)
        assertEquals(0.0, core.toDisplay().percent, 0.0)
    }

    @Test
    fun `reset credits map deeply`() {
        val core = UsageResetCredits(
            availableCount = 3,
            credits = listOf(
                UsageResetCredit(title = "Full reset", expiresAt = RESET_AT),
                UsageResetCredit(title = "Partial", expiresAt = null),
            ),
            unavailable = false,
        )
        assertEquals(
            UsageResetCreditsDisplay(
                availableCount = 3,
                credits = listOf(
                    UsageResetCreditDisplay(title = "Full reset", expiresAt = RESET_AT),
                    UsageResetCreditDisplay(title = "Partial", expiresAt = null),
                ),
                unavailable = false,
            ),
            core.toDisplay(),
        )
    }

    @Test
    fun `an unavailable credit inventory maps to unavailable`() {
        val core = UsageResetCredits(availableCount = null, credits = emptyList(), unavailable = true)
        assertEquals(
            UsageResetCreditsDisplay(availableCount = null, credits = emptyList(), unavailable = true),
            core.toDisplay(),
        )
    }

    @Test
    fun `record mapping pre-spells display strings and carries raw fields`() {
        val core = record(
            provider = "claude",
            status = UsageStatus.Warn,
            windows = listOf(window("5h", 42.0)),
            blockReason = "weekly quota exhausted",
            lastError = "http error 401",
            resetCredits = UsageResetCredits(
                availableCount = 1,
                credits = listOf(UsageResetCredit(title = "Full reset", expiresAt = RESET_AT)),
                unavailable = false,
            ),
        )
        val display = core.toDisplay()

        // displayName and percent are PRE-SPELLED from core's own derivations.
        assertEquals(core.displayName, display.displayName)
        assertEquals(core.provider, display.provider)
        assertEquals(UsageStatusDisplay.Warn, display.status)
        assertEquals(core.rawStatus, display.rawStatus)
        assertEquals(core.blockReason, display.blockReason)
        assertEquals(core.lastError, display.lastError)
        assertEquals(
            UsageResetCreditsDisplay(
                availableCount = 1,
                credits = listOf(UsageResetCreditDisplay(title = "Full reset", expiresAt = RESET_AT)),
                unavailable = false,
            ),
            display.resetCredits,
        )
        assertEquals(
            core.windows.map { it.resetAt },
            display.windows.map { it.resetAt },
        )
        assertEquals(
            core.windows.map { it.percent },
            display.windows.map { it.percent },
        )
    }

    /** The whole threshold class: a percent × warnPercent sweep, core vs mirror. */
    @Test
    fun `thresholdState derivation agrees with core across the percent and warnPercent sweep`() {
        for (percent in SWEEP) {
            for (warnPercent in WARN_SWEEP) {
                val core = record(provider = "claude", windows = listOf(window("5h", percent)))
                val display = core.toDisplay()
                assertEquals(
                    "core vs mirror thresholdState at percent=$percent warnPercent=$warnPercent",
                    core.thresholdState(warnPercent).name,
                    display.thresholdState(warnPercent).name,
                )
                assertEquals(
                    "isBlocked at percent=$percent",
                    core.isBlocked,
                    display.isBlocked,
                )
                assertEquals(
                    "isNearLimit at percent=$percent",
                    core.isNearLimit,
                    display.isNearLimit,
                )
                assertEquals(
                    "mostConstrainedWindow percent at percent=$percent",
                    core.mostConstrainedWindow?.percent,
                    display.mostConstrainedWindow?.percent,
                )
            }
        }
    }

    @Test
    fun `status-blocked agrees across the sweep with and without windows`() {
        for (percent in SWEEP) {
            for (windows in listOf(listOf(window("5h", percent)), emptyList())) {
                val core = record(provider = "claude", status = UsageStatus.Blocked, windows = windows)
                val display = core.toDisplay()
                assertEquals(
                    core.thresholdState(DEFAULT_WARN).name,
                    display.thresholdState(DEFAULT_WARN).name,
                )
                assertEquals(core.isBlocked, display.isBlocked)
            }
        }
    }

    @Test
    fun `a record with no windows thresholds to Ok on both sides`() {
        val core = record(provider = "codex", status = UsageStatus.Error, windows = emptyList())
        val display = core.toDisplay()
        assertEquals(UsageThresholdState.Ok.name, core.thresholdState().name)
        assertEquals(UsageThresholdStateDisplay.Ok.name, display.thresholdState().name)
    }

    @Test
    fun `most constrained window keeps the first of tied windows on both sides`() {
        val core = record(
            provider = "claude",
            windows = listOf(window("5h", 50.0), window("7d", 50.0)),
        )
        val display = core.toDisplay()
        assertEquals(core.mostConstrainedWindow?.name, display.mostConstrainedWindow?.name)
        assertEquals("5h", display.mostConstrainedWindow?.name)
    }

    @Test
    fun `displayName is pre-spelled from core for every alias class`() {
        for (provider in listOf(
            "claude", "codex", "go", "opencode", "copilot", "grok", "zai", "something_new",
        )) {
            val core = record(provider = provider, windows = emptyList())
            assertEquals(
                "displayName for provider $provider",
                core.displayName,
                core.toDisplay().displayName,
            )
        }
    }

    private fun record(
        provider: String,
        status: UsageStatus = UsageStatus.Ok,
        windows: List<UsageWindow>,
        blockReason: String? = null,
        lastError: String? = null,
        resetCredits: UsageResetCredits? = null,
    ): UsageProviderRecord = UsageProviderRecord(
        provider = provider,
        status = status,
        windows = windows,
        rawStatus = status.name.lowercase(),
        blockReason = blockReason,
        lastError = lastError,
        resetCredits = resetCredits,
    )

    private fun window(name: String, percent: Double): UsageWindow =
        UsageWindow(name = name, used = percent, limit = 100.0, unit = "percent", resetAt = null)

    private companion object {
        val RESET_AT: Instant = Instant.parse("2026-09-21T00:13:00Z")
        const val DEFAULT_WARN: Double = UsageProviderRecord.DEFAULT_WARN_PERCENT

        /** Every band edge plus interior points: 0…100 inclusive. */
        val SWEEP: List<Double> = (0..100 step 5).map { it.toDouble() } + listOf(84.9, 94.9, 99.9)

        /** Default, boundary-collapse (warn ≥ critical) and custom low/high. */
        val WARN_SWEEP: List<Double> = listOf(50.0, 80.0, 90.0, 95.0, 97.5, 100.0)
    }
}
