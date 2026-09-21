package com.pocketshell.next.ports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.pocketshell.uikit.model.ConnectionStatus

/**
 * The moved ports family's pure-display assertions, on the plain JVM
 * (#2636 D11) — the D10 `UsageDisplayFamilyTest` shape.
 *
 * These pin the vocabulary and derivations that used to live as internals of
 * app2's `PortForwardScreen.kt` (and were asserted by app2's screen test):
 * byte formatting, the row-key scheme, the paused/hidden-count copy, the
 * local-port mismatch marker, and the THREE per-surface status/connection
 * wordings (ports table vs Services list vs tunnel detail — the same enum,
 * three deliberate choices of word). The rendered-tree behaviour itself is
 * still proven app-side by `PortForwardScreenTest`/`ServicesScreenTest`;
 * the core ↔ mirror equivalence is swept by app2's
 * `PortForwardDisplayMappingTest`.
 */
class PortForwardDisplayFamilyTest {

    // ── byte formatting ──────────────────────────────────────────────────────

    @Test
    fun `byte formatting steps through the units`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.0 KB", formatBytes(1_024))
        assertEquals("1.0 MB", formatBytes(1_024L * 1_024))
        assertEquals("1.0 GB", formatBytes(1_024L * 1_024 * 1_024))
    }

    // ── LazyColumn row keys ──────────────────────────────────────────────────

    @Test
    fun `row keys are unique even when a remote port appears twice`() {
        // The old client crashed here (`Key "22" already used`): the same
        // remote port can legitimately appear twice — discovered on two
        // interfaces, or a forwarded row beside its still-AVAILABLE twin.
        val duplicated = listOf(
            TunnelDisplay(3_000, 3_000, "vite", TunnelStatusDisplay.FORWARDING),
            TunnelDisplay(3_000, 3_000, "vite", TunnelStatusDisplay.AVAILABLE),
        )
        assertEquals(
            "keys must be unique for LazyColumn",
            2,
            tunnelRowKeys(duplicated).toSet().size,
        )
    }

    @Test
    fun `row keys encode the row identity and stay stable`() {
        val rows = listOf(
            TunnelDisplay(22, 7_432, "sshd", TunnelStatusDisplay.AVAILABLE),
        )
        assertEquals("22:7432:AVAILABLE", tunnelRowKeys(rows).single())
    }

    // ── the show-all / hidden-count copy ─────────────────────────────────────

    @Test
    fun `the hidden-count label is only shown while rows are actually hidden`() {
        assertEquals("Show hidden/noisy ports", showAllPortsLabel(checked = true, hiddenCount = 4))
        assertEquals("Show hidden/noisy ports", showAllPortsLabel(checked = false, hiddenCount = 0))
        assertEquals(
            "Show hidden/noisy ports (4 hidden)",
            showAllPortsLabel(checked = false, hiddenCount = 4),
        )
        assertEquals("1 noisy port hidden.", hiddenPortsMessage(1))
        assertEquals("2 noisy ports hidden.", hiddenPortsMessage(2))
    }

    // ── the terminal paused copy (#2491) ─────────────────────────────────────

    @Test
    fun `the paused message falls back to a plain reason when none is known`() {
        assertEquals(
            "Forwarding paused. This host could not be reached.",
            pausedMessage(null),
        )
        assertEquals("Forwarding paused. Fix the key.", pausedMessage("Fix the key."))
    }

    // ── the local-port mismatch marker (#2498) ───────────────────────────────

    @Test
    fun `the mismatch marker fires only for a forwarding row with a walked-up bind`() {
        assertEquals(
            "differs from remote",
            localPortMismatchLabel(remotePort = 3_000, localPort = 3_003, forwarding = true),
        )
        assertNull(
            "a plain mirror must render no note",
            localPortMismatchLabel(remotePort = 3_000, localPort = 3_000, forwarding = true),
        )
        assertNull(
            "an unforwarded row hides its local port entirely",
            localPortMismatchLabel(remotePort = 3_000, localPort = 3_003, forwarding = false),
        )
    }

    // ── the three per-surface status vocabularies ────────────────────────────

    @Test
    fun `the ports table reads AVAILABLE as Available`() {
        assertEquals("Forwarding", TunnelStatusDisplay.FORWARDING.statusLabel)
        assertEquals("Available", TunnelStatusDisplay.AVAILABLE.statusLabel)
        assertEquals("Failed", TunnelStatusDisplay.FAILED.statusLabel)
        assertEquals("Stopped", TunnelStatusDisplay.STOPPED.statusLabel)
    }

    @Test
    fun `the Services list reads AVAILABLE as Not forwarded`() {
        // An AVAILABLE port reads as a service you COULD forward, not as one
        // that is up — the deliberate wording split from the ports table.
        assertEquals("Forwarding", TunnelStatusDisplay.FORWARDING.servicesStatusLabel)
        assertEquals("Not forwarded", TunnelStatusDisplay.AVAILABLE.servicesStatusLabel)
        assertEquals("Failed", TunnelStatusDisplay.FAILED.servicesStatusLabel)
        assertEquals("Stopped", TunnelStatusDisplay.STOPPED.servicesStatusLabel)
    }

    @Test
    fun `the detail page reads AVAILABLE plainly as Available`() {
        assertEquals("Forwarding", TunnelStatusDisplay.FORWARDING.detailStatusLabel)
        assertEquals("Available", TunnelStatusDisplay.AVAILABLE.detailStatusLabel)
        assertEquals("Failed", TunnelStatusDisplay.FAILED.detailStatusLabel)
        assertEquals("Stopped", TunnelStatusDisplay.STOPPED.detailStatusLabel)
    }

    // ── the connection vocabularies ──────────────────────────────────────────

    @Test
    fun `the header wording separates a parked host from a reconnecting one`() {
        // "Needs attention" is terminal wording; "Reconnecting" is not (#2491).
        assertEquals("Idle", ConnectionStateDisplay.Idle.headerLabel)
        assertEquals("Connecting", ConnectionStateDisplay.Connecting.headerLabel)
        assertEquals("Connected", ConnectionStateDisplay.Connected.headerLabel)
        assertEquals("Reconnecting", ConnectionStateDisplay.Reconnecting.headerLabel)
        assertEquals("Needs attention", ConnectionStateDisplay.Lost.headerLabel)
    }

    @Test
    fun `the Services wording reads discovery-off as Off`() {
        assertEquals("Off", ConnectionStateDisplay.Connected.quietLabel(enabled = false))
        assertEquals("Connected", ConnectionStateDisplay.Connected.quietLabel(enabled = true))
        assertEquals("Idle", ConnectionStateDisplay.Idle.quietLabel(enabled = true))
        assertEquals("Connecting", ConnectionStateDisplay.Connecting.quietLabel(enabled = true))
        assertEquals("Needs attention", ConnectionStateDisplay.Lost.quietLabel(enabled = true))
    }

    @Test
    fun `the status dot maps the connection like the header does`() {
        assertEquals(ConnectionStatus.Idle, ConnectionStateDisplay.Idle.toConnectionStatus(enabled = true))
        assertEquals(ConnectionStatus.Connected, ConnectionStateDisplay.Connected.toConnectionStatus(enabled = true))
        assertEquals(ConnectionStatus.Error, ConnectionStateDisplay.Lost.toConnectionStatus(enabled = true))
        assertEquals(ConnectionStatus.Connecting, ConnectionStateDisplay.Reconnecting.toConnectionStatus(enabled = true))
        // The toggle owns the idle look: even a live connection shows idle
        // while discovery is off.
        assertEquals(ConnectionStatus.Idle, ConnectionStateDisplay.Connected.toConnectionStatus(enabled = false))
    }

    // ── the scanning derivation ──────────────────────────────────────────────

    @Test
    fun `scanning is exactly on-with-nothing-discovered-and-not-terminal`() {
        val base = PortForwardDisplayState(enabled = true)
        assertTrue("on, nothing yet, no filter gap, still trying", base.scanning)

        assertFalse("rows arrived — no longer scanning", base.copy(rows = listOf(tunnel())).scanning)
        assertFalse(
            "hidden rows explain the empty table",
            base.copy(hiddenCount = 1).scanning,
        )
        assertFalse(
            "terminal Lost is a parked state, not work in flight (#2491)",
            base.copy(connection = ConnectionStateDisplay.Lost).scanning,
        )
        assertFalse("discovery off is not scanning", base.copy(enabled = false).scanning)
    }

    @Test
    fun `the display state starts loading with an empty idle snapshot`() {
        val cold = PortForwardDisplayState()
        assertTrue(cold.loading)
        assertTrue(cold.rows.isEmpty())
        assertEquals(ConnectionStateDisplay.Idle, cold.connection)
        assertFalse(cold.enabled)
        assertFalse("cold is not scanning: discovery is off", cold.scanning)
    }

    private fun tunnel() = TunnelDisplay(5173, 35_173, "vite", TunnelStatusDisplay.FORWARDING)
}
