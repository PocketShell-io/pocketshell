package com.pocketshell.next.ports

import com.pocketshell.core.portfwd.AutoForwarderSupervisor.ConnectionState
import com.pocketshell.core.portfwd.TunnelInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The faithful-mapping proof for the #2636 D11 seam: every `core.portfwd`
 * shape the ports screens consume maps onto its pure display mirror with
 * display-identical values, across the WHOLE class — every connection-state
 * constant, every tunnel-status constant, a full seven-field `TunnelInfo`
 * (including the traffic fields' defaults) — not one happy path. This is the
 * test that lets the family's PNGs be compared byte-for-byte across the move:
 * the shared screens paint the mirrors, so if the mirrors equal what core
 * produced, the pixels cannot change.
 *
 * Unlike D10 there is no re-derived-derivation sweep: the display state's one
 * derivation (`scanning`) moved WITH the family (the core-typed
 * `PortForwardUiState` was hard-cut, no shim — D22), so it is pinned by the
 * shared module's `PortForwardDisplayFamilyTest` instead, and the mapping
 * here is a pure value copy asserted field-by-field.
 */
class PortForwardDisplayMappingTest {

    @Test
    fun `every ConnectionState constant maps to the mirror constant of the same name`() {
        for (state in ConnectionState.entries) {
            assertEquals(
                ConnectionStateDisplay.valueOf(state.name),
                state.toDisplay(),
            )
        }
        // Guard the guard: the exhaustive `when` in the mapper would fail the
        // build on a new core constant, but this loop fails if one side ever
        // RENAMES without the other.
        assertEquals(ConnectionState.entries.size, ConnectionStateDisplay.entries.size)
    }

    @Test
    fun `every TunnelInfo Status constant maps to the mirror constant of the same name`() {
        for (status in TunnelInfo.Status.entries) {
            assertEquals(
                TunnelStatusDisplay.valueOf(status.name),
                status.toDisplay(),
            )
        }
        assertEquals(TunnelInfo.Status.entries.size, TunnelStatusDisplay.entries.size)
    }

    @Test
    fun `a full tunnel maps all seven fields verbatim`() {
        val core = TunnelInfo(
            remotePort = 5_173,
            localPort = 35_173,
            process = "vite",
            status = TunnelInfo.Status.FORWARDING,
            bytesIn = 2_048,
            bytesOut = 8_192,
            speedBps = 1_024,
        )

        assertEquals(
            TunnelDisplay(
                remotePort = 5_173,
                localPort = 35_173,
                process = "vite",
                status = TunnelStatusDisplay.FORWARDING,
                bytesIn = 2_048,
                bytesOut = 8_192,
                speedBps = 1_024,
            ),
            core.toDisplay(),
        )
    }

    @Test
    fun `a bare discovered tunnel maps with the core defaults intact`() {
        // The discovery paths build rows without traffic; the mirror's own
        // defaults must agree with core's so an omitted field can never paint
        // a different cell.
        val core = TunnelInfo(
            remotePort = 22,
            localPort = 0,
            process = "sshd",
            status = TunnelInfo.Status.AVAILABLE,
        )

        val display = core.toDisplay()
        assertEquals(0L, display.bytesIn)
        assertEquals(0L, display.bytesOut)
        assertEquals(0L, display.speedBps)
        assertEquals(TunnelDisplay(22, 0, "sshd", TunnelStatusDisplay.AVAILABLE), display)
    }

    @Test
    fun `mapping is status-transparent for every lifecycle constant`() {
        // One row per core status, mapped: the identity the LazyColumn keys on
        // (remote:local:status) must survive the seam verbatim.
        for (status in TunnelInfo.Status.entries) {
            val core = TunnelInfo(remotePort = 8_080, localPort = 8_080, process = "p", status = status)
            val display = core.toDisplay()
            assertEquals(status.toDisplay(), display.status)
            assertEquals(
                "${core.remotePort}:${core.localPort}:${core.status}",
                "${display.remotePort}:${display.localPort}:${display.status}",
            )
        }
    }
}
