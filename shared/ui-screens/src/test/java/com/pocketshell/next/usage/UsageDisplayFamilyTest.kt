package com.pocketshell.next.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * The moved usage family's JVM tests, running from `shared:ui-screens` itself
 * (#2636 D10). The family is pure Kotlin + `java.time` — no Robolectric, no
 * Compose — so its display strings, threshold derivations and state folding
 * pin here on the plain JVM, and the module's own `:test` task keeps them
 * green the way the app2 suites did before the move.
 */
class UsageDisplayFamilyTest {

    // ── mirror derivations ────────────────────────────────────────────────────

    @Test
    fun `mostConstrainedWindow picks the highest percent`() {
        val record = record(windows = listOf(window("5h", 12.0), window("7d", 60.0)))
        assertEquals("7d", record.mostConstrainedWindow?.name)
        assertEquals(60.0, record.mostConstrainedWindow!!.percent, 0.0)
    }

    @Test
    fun `mostConstrainedWindow is null with no windows`() {
        assertNull(record(windows = emptyList()).mostConstrainedWindow)
    }

    @Test
    fun `thresholdState bands follow the four-state ladder`() {
        fun state(percent: Double, warnPercent: Double = 80.0) =
            record(windows = listOf(window("5h", percent))).thresholdState(warnPercent)

        assertEquals(UsageThresholdStateDisplay.Ok, state(79.9))
        assertEquals(UsageThresholdStateDisplay.Approaching, state(80.0))
        assertEquals(UsageThresholdStateDisplay.Approaching, state(94.9))
        assertEquals(UsageThresholdStateDisplay.Critical, state(95.0))
        assertEquals(UsageThresholdStateDisplay.Critical, state(99.9))
        assertEquals(UsageThresholdStateDisplay.Exceeded, state(100.0))
    }

    @Test
    fun `a status-blocked record thresholds straight to Exceeded regardless of windows`() {
        assertEquals(
            UsageThresholdStateDisplay.Exceeded,
            record(status = UsageStatusDisplay.Blocked, windows = listOf(window("5h", 3.0)))
                .thresholdState(),
        )
        assertEquals(
            UsageThresholdStateDisplay.Exceeded,
            record(status = UsageStatusDisplay.Blocked, windows = emptyList()).thresholdState(),
        )
    }

    @Test
    fun `a warnPercent at or above critical collapses the approaching band`() {
        val record = record(windows = listOf(window("5h", 90.0)))
        assertEquals(UsageThresholdStateDisplay.Approaching, record.thresholdState(warnPercent = 80.0))
        assertEquals(UsageThresholdStateDisplay.Ok, record.thresholdState(warnPercent = 95.0))
    }

    @Test
    fun `no windows means Ok no matter the warnPercent`() {
        assertEquals(
            UsageThresholdStateDisplay.Ok,
            record(status = UsageStatusDisplay.Error, windows = emptyList()).thresholdState(10.0),
        )
    }

    @Test
    fun `warrantsWarning is true for every band above Ok`() {
        assertFalse(UsageThresholdStateDisplay.Ok.warrantsWarning)
        assertTrue(UsageThresholdStateDisplay.Approaching.warrantsWarning)
        assertTrue(UsageThresholdStateDisplay.Critical.warrantsWarning)
        assertTrue(UsageThresholdStateDisplay.Exceeded.warrantsWarning)
    }

    @Test
    fun `isBlocked comes from status or any exhausted window`() {
        assertTrue(record(status = UsageStatusDisplay.Blocked, windows = emptyList()).isBlocked)
        assertTrue(record(windows = listOf(window("5h", 12.0), window("7d", 100.0))).isBlocked)
        assertFalse(record(windows = listOf(window("5h", 99.9))).isBlocked)
    }

    @Test
    fun `isNearLimit tracks the warn band and never co-occurs with blocked`() {
        assertTrue(record(windows = listOf(window("5h", 85.0))).isNearLimit)
        assertFalse(record(windows = listOf(window("5h", 84.9))).isNearLimit)
        assertFalse(record(status = UsageStatusDisplay.Blocked, windows = listOf(window("5h", 99.0))).isNearLimit)
    }

    // ── window labels and percents ────────────────────────────────────────────

    @Test
    fun `windowLabel humanises the producer keys`() {
        assertEquals("5h window", windowLabel("5h"))
        assertEquals("7d window", windowLabel("7d"))
        assertEquals("Weekly limit", windowLabel("weekly"))
        assertEquals("Monthly limit", windowLabel("monthly"))
        assertEquals("Short term", windowLabel("short_term"))
    }

    @Test
    fun `formatPercent drops the trailing zero and formatPercentUsed suffixes used`() {
        assertEquals("60%", formatPercent(60.0))
        assertEquals("12.5%", formatPercent(12.5))
        assertEquals("60% used", formatPercentUsed(60.0))
        assertEquals("0% used", formatPercentUsed(0.0))
    }

    // ── reset countdown copy ──────────────────────────────────────────────────

    @Test
    fun `formatResetRelative buckets the countdown`() {
        val now = Instant.parse("2026-09-05T12:00:00Z")
        val utc = ZoneId.of("UTC")
        assertEquals(RESET_PLACEHOLDER, formatResetRelative(now, null, utc))
        assertEquals("now", formatResetRelative(now, now.minusSeconds(1), utc))
        assertEquals("in <1m", formatResetRelative(now, now.plusSeconds(59), utc))
        assertEquals("in 5m", formatResetRelative(now, now.plusSeconds(5 * 60), utc))
        assertEquals("in 2h 15m", formatResetRelative(now, now.plusSeconds((8_100).toLong()), utc))
        assertEquals("in 3h", formatResetRelative(now, now.plusSeconds(3 * 3_600), utc))
        // ~28h out, landing tomorrow: LOCAL calendar days, not ceil on seconds.
        assertEquals("in 1 day", formatResetRelative(now, now.plusSeconds(28 * 3_600), utc))
        assertEquals("in 3 days", formatResetRelative(now, now.plusSeconds(3 * 86_400), utc))
    }

    @Test
    fun `formatResetAbsolute renders the local date and time or null`() {
        val utc = ZoneId.of("UTC")
        assertNull(formatResetAbsolute(null, utc))
        assertEquals(
            "Mon Sep 7, 08:45",
            formatResetAbsolute(Instant.parse("2026-09-07T08:45:00Z"), utc),
        )
    }

    @Test
    fun `formatWindowFoot joins the reset clause and the scoped block reason`() {
        val now = Instant.parse("2026-09-05T12:00:00Z")
        val window = UsageWindowDisplay("7d", 60.0, now.plusSeconds(3 * 86_400))
        assertEquals(
            "resets in 3 days · Weekly quota exceeded",
            formatWindowFoot(window, now, "codex weekly quota exhausted", ZoneId.of("UTC")),
        )
        assertEquals("resets in 3 days", formatWindowFoot(window, now, null, ZoneId.of("UTC")))
    }

    @Test
    fun `formatCreditExpiry is render-only and never speaks reset language`() {
        val now = Instant.parse("2026-09-05T12:00:00Z")
        val utc = ZoneId.of("UTC")
        assertEquals(
            CreditExpiryText(primary = "Expiry unavailable", absolute = null),
            formatCreditExpiry(now, null, utc),
        )
        assertEquals(
            CreditExpiryText(primary = "expired", absolute = "Sat Sep 5, 11:00"),
            formatCreditExpiry(now, now.minusSeconds(3_600), utc),
        )
        assertEquals(
            CreditExpiryText(primary = "expires in 45m", absolute = "Sat Sep 5, 12:45"),
            formatCreditExpiry(now, now.plusSeconds(45 * 60), utc),
        )
        assertEquals(
            CreditExpiryText(primary = "expires in 1 day", absolute = "Sun Sep 6, 12:00"),
            formatCreditExpiry(now, now.plusSeconds(24 * 3_600), utc),
        )
    }

    @Test
    fun `soonestReset picks the smallest non-null reset across windows`() {
        val record = record(
            windows = listOf(
                window("5h", 10.0, LATER),
                window("7d", 60.0, SOONER),
                window("x", 20.0, null),
            ),
        )
        assertEquals(SOONER, soonestReset(record))
        assertNull(soonestReset(record(windows = listOf(window("5h", 10.0, null)))))
        assertNull(soonestReset(record(windows = emptyList())))
    }

    // ── block reason scoping ──────────────────────────────────────────────────

    @Test
    fun `quotaMessageForDisplay normalises quota copy`() {
        assertEquals("Weekly quota exceeded", quotaMessageForDisplay("codex weekly limit exhausted"))
        assertEquals("Quota exceeded", quotaMessageForDisplay("daily quota reached"))
        assertEquals("plain host text", quotaMessageForDisplay("plain host text"))
        assertEquals("", quotaMessageForDisplay("   "))
    }

    @Test
    fun `blockReasonForWindow scopes the reason to matching windows`() {
        val record = record(
            windows = listOf(window("5h", 98.0), window("7d", 60.0)),
            blockReason = "short_term limit reached",
        )
        assertEquals("short_term limit reached", blockReasonForWindow(record, record.windows[0]))
        assertNull(blockReasonForWindow(record, record.windows[1]))
    }

    @Test
    fun `an unscoped block reason rides only the most constrained window`() {
        val record = record(
            windows = listOf(window("5h", 12.0), window("7d", 60.0)),
            blockReason = "some new provider reason",
        )
        assertNull(blockReasonForWindow(record, record.windows[0]))
        assertEquals("some new provider reason", blockReasonForWindow(record, record.windows[1]))
    }

    @Test
    fun `a single-window record shows its block reason unscoped`() {
        val record = record(windows = listOf(window("5h", 98.0)), blockReason = "anything")
        assertEquals("anything", blockReasonForWindow(record, record.windows[0]))
    }

    @Test
    fun `blank block reasons never render`() {
        assertNull(blockReasonForWindow(record(windows = listOf(window("5h", 1.0)), blockReason = "  "), window("5h", 1.0)))
    }

    // ── status copy ladder ────────────────────────────────────────────────────

    @Test
    fun `statusLabel follows the warn and exceed ladder`() {
        assertEquals("OK", statusLabel(record(windows = listOf(window("5h", 10.0)))))
        assertEquals("Warn", statusLabel(record(windows = listOf(window("5h", 85.0)))))
        assertEquals("Exceeded", statusLabel(record(windows = listOf(window("5h", 100.0)))))
        assertEquals("Exceeded", statusLabel(record(status = UsageStatusDisplay.Blocked, windows = emptyList())))
        assertEquals("Unsupported", statusLabel(record(status = UsageStatusDisplay.Unsupported, windows = emptyList())))
        assertEquals(USAGE_DATA_UNAVAILABLE, statusLabel(record(status = UsageStatusDisplay.Error, windows = emptyList())))
        assertEquals(
            "Odd",
            statusLabel(record(status = UsageStatusDisplay.Unknown, rawStatus = "odd", windows = emptyList())),
        )
    }

    @Test
    fun `auth setup errors win the label over everything else`() {
        val record = record(
            status = UsageStatusDisplay.Error,
            windows = emptyList(),
            lastError = "claude authentication failed",
        )
        assertEquals(USAGE_AUTH_SETUP_REQUIRED, statusLabel(record))
        assertTrue(usageProviderStatusUi(record).needsAuthSetup)
    }

    @Test
    fun `usageProviderStateDescription spells the band or the failure`() {
        assertEquals("OK", usageProviderStateDescription(record(windows = listOf(window("5h", 10.0)))))
        assertEquals(
            "Approaching limit",
            usageProviderStateDescription(record(windows = listOf(window("5h", 85.0)))),
        )
        assertEquals(
            "Quota exceeded",
            usageProviderStateDescription(record(windows = listOf(window("5h", 100.0)))),
        )
        assertEquals(
            USAGE_DATA_UNAVAILABLE,
            usageProviderStateDescription(record(status = UsageStatusDisplay.Error, windows = emptyList())),
        )
    }

    @Test
    fun `usageTelemetryMessageForDisplay normalises the provider auth failures`() {
        assertEquals(
            CLAUDE_USAGE_AUTH_SETUP_MESSAGE,
            usageTelemetryMessageForDisplay("Error: claude /login required"),
        )
        assertEquals(
            CODEX_USAGE_AUTH_SETUP_MESSAGE,
            usageTelemetryMessageForDisplay("codex: no auth token found"),
        )
        assertEquals(
            PROVIDER_AUTH_COPY,
            usageTelemetryMessageForDisplay("http error 401"),
        )
        assertEquals("  trimmed  ".trim(), usageTelemetryMessageForDisplay("  trimmed  "))
        assertNull(usageTelemetryMessageForDisplay(null))
        assertNull(usageTelemetryMessageForDisplay("   "))
        assertEquals(
            "keep unknown telemetry verbatim",
            usageTelemetryMessageForDisplay("keep unknown telemetry verbatim"),
        )
    }

    // ── snapshot folding ──────────────────────────────────────────────────────

    @Test
    fun `usageScreenState folds the four snapshot outcomes into their buckets`() {
        val state = usageScreenState(
            snapshots = listOf(
                UsageSnapshot.Records(1, "hetzner", listOf(record(windows = listOf(window("5h", 10.0)))), NOW),
                UsageSnapshot.ToolMissing(2, "bare", NOW),
                UsageSnapshot.Failed(3, "broken", "disk on fire", NOW),
                UsageSnapshot.TimedOut(4, "slow", NOW),
            ),
            connectedHostCount = 4,
        )
        assertEquals(1, state.hosts.size)
        assertEquals("hetzner", state.hosts[0].hostName)
        assertEquals(NOW, state.hosts[0].lastSyncedAt)
        assertEquals("bare", state.missingToolHosts.single().hostName)
        assertEquals("broken", state.failedHosts.single { it.hostId == 3L }.hostName)
        assertEquals("disk on fire", state.failedHosts.single { it.hostId == 3L }.reason)
        // A timeout is its own visible state (#2498): "slow/unreachable".
        assertEquals("usage read timed out", state.failedHosts.single { it.hostId == 4L }.reason)
        assertEquals(1, state.providerCount)
        assertEquals(1, state.hostCount)
        assertTrue(state.loaded)
    }

    @Test
    fun `usageScreenState honours the selected host on every bucket`() {
        val state = usageScreenState(
            snapshots = listOf(
                UsageSnapshot.Records(1, "hetzner", listOf(record(windows = listOf(window("5h", 10.0)))), NOW),
                UsageSnapshot.Records(2, "builder", emptyList(), NOW),
                UsageSnapshot.Failed(3, "broken", "nope", NOW),
            ),
            connectedHostCount = 3,
            selectedHostId = 1,
        )
        assertEquals(listOf("hetzner"), state.hosts.map { it.hostName })
        assertEquals("hetzner", state.selectedHostName)
        assertTrue(state.failedHosts.isEmpty())
    }

    @Test
    fun `usageScreenState keeps the explicitly provided selected host name`() {
        val state = usageScreenState(
            snapshots = listOf(UsageSnapshot.Records(1, "hetzner", emptyList(), NOW)),
            connectedHostCount = 1,
            selectedHostId = 1,
            selectedHostName = "Hetznik",
        )
        assertEquals("Hetznik", state.selectedHostName)
    }

    @Test
    fun `screen empty states distinguish no hosts from hosts with no providers`() {
        assertTrue(UsageScreenState(loaded = true, connectedHostCount = 0).isEmptyWithNoConnectedHosts)
        assertFalse(UsageScreenState(loaded = false, connectedHostCount = 0).isEmptyWithNoConnectedHosts)

        val noProviders = UsageScreenState(
            hosts = listOf(UsageHostSnapshot(1, "hetzner", emptyList(), NOW)),
            loaded = true,
            connectedHostCount = 1,
        )
        assertTrue(noProviders.isEmptyWithConnectedHosts)

        val missingTool = noProviders.copy(missingToolHosts = listOf(UsageMissingToolHost(2, "bare")))
        assertFalse(missingTool.isEmptyWithConnectedHosts)
    }

    // ── dashboard rows ────────────────────────────────────────────────────────

    @Test
    fun `dashboardRows pick the most constrained window not the soonest reset`() {
        val state = UsageScreenState(
            hosts = listOf(
                UsageHostSnapshot(
                    1,
                    "hetzner",
                    listOf(
                        record(
                            windows = listOf(
                                window("5h", 10.0, SOONER),
                                window("7d", 60.0, LATER),
                            ),
                        ),
                    ),
                    NOW,
                ),
            ),
            loaded = true,
            connectedHostCount = 1,
        )
        val row = state.dashboardRows().single()
        assertEquals(60.0, row.percent, 0.0)
        assertEquals("60% used", row.percentLabel)
        assertEquals(SOONER, row.soonestReset)
        assertEquals(UsageThresholdStateDisplay.Ok, row.thresholdState)
        assertFalse(row.blocked)
    }

    @Test
    fun `dashboardRows sort by provider so slots stay stable`() {
        val state = UsageScreenState(
            hosts = listOf(
                UsageHostSnapshot(
                    1,
                    "hetzner",
                    listOf(
                        record(provider = "zai", windows = listOf(window("7d", 7.0))),
                        record(provider = "claude", windows = listOf(window("5h", 12.0))),
                    ),
                    NOW,
                ),
            ),
            loaded = true,
            connectedHostCount = 1,
        )
        assertEquals(listOf("Claude Code", "Zai"), state.dashboardRows().map { it.provider })
    }

    @Test
    fun `dashboardRows drop non thresholdable providers but never a blocked one`() {
        val state = UsageScreenState(
            hosts = listOf(
                UsageHostSnapshot(
                    1,
                    "hetzner",
                    listOf(
                        record(status = UsageStatusDisplay.Unsupported, windows = emptyList()),
                        record(provider = "claude-blocked", status = UsageStatusDisplay.Blocked, windows = emptyList()),
                    ),
                    NOW,
                ),
            ),
            loaded = true,
            connectedHostCount = 1,
        )
        val rows = state.dashboardRows()
        assertEquals(listOf("Claude"), rows.map { it.provider })
        assertEquals(100.0, rows.single().percent, 0.0)
        assertTrue(rows.single().blocked)
    }

    // ── sync label ────────────────────────────────────────────────────────────

    @Test
    fun `usageSyncLabel says syncing not synced or the latest fetch time`() {
        val utc = ZoneId.of("UTC")
        assertEquals("Syncing…", usageSyncLabel(UsageScreenState(isRefreshing = true), utc))
        assertEquals("Not synced yet", usageSyncLabel(UsageScreenState(), utc))
        assertEquals(
            "Last sync 12:00",
            usageSyncLabel(
                UsageScreenState(
                    hosts = listOf(
                        UsageHostSnapshot(1, "a", emptyList(), NOW.minusSeconds(60)),
                        UsageHostSnapshot(2, "b", emptyList(), NOW),
                    ),
                ),
                utc,
            ),
        )
    }

    private fun record(
        provider: String = "claude",
        status: UsageStatusDisplay = UsageStatusDisplay.Ok,
        windows: List<UsageWindowDisplay>,
        blockReason: String? = null,
        lastError: String? = null,
        rawStatus: String? = null,
    ): UsageProviderRecordDisplay = UsageProviderRecordDisplay(
        provider = provider,
        status = status,
        rawStatus = rawStatus ?: status.name.lowercase(),
        displayName = when (provider) {
            "claude" -> "Claude Code"
            "zai" -> "Zai"
            "claude-blocked" -> "Claude"
            else -> provider
        },
        windows = windows,
        blockReason = blockReason,
        lastError = lastError,
    )

    private fun window(name: String, percent: Double, resetAt: Instant? = null) =
        UsageWindowDisplay(name, percent, resetAt)

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-05T12:00:00Z")
        val SOONER: Instant = Instant.parse("2026-09-05T16:00:00Z")
        val LATER: Instant = Instant.parse("2026-09-10T12:00:00Z")

        /**
         * The file-private provider-message const, re-declared for the one
         * assertion that needs it: the 401/unauthorized family collapses to
         * the generic provider copy.
         */
        const val PROVIDER_AUTH_COPY: String =
            "Provider login needed on this host. " +
                "Sign in with the provider CLI on the host, then refresh usage."
    }
}
