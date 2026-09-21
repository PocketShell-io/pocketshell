package com.pocketshell.next.ports

/**
 * Pure display mirrors of the `core.portfwd` model family (#2636 D11).
 *
 * The ports family's presentation layer ([PortForwardScreen],
 * [ServicesScreen], [TunnelDetailScreen], [AddTunnelScreen] and the
 * [PortForwardDisplayState] they paint) moved into this module, but the
 * boundary design forbids a `:shared:core-portfwd` dependency — this module's
 * only project dependency stays `:shared:ui-kit`. So the D3 release-check
 * seam applies at family scale, exactly like #2636 D10 did for the usage
 * panel: the two `core.portfwd` types the screens paint
 * (`AutoForwarderSupervisor.ConnectionState` and `TunnelInfo` with its
 * `Status` enum) get pure display mirrors here, and app2 maps core → mirror
 * at its one ingestion point (the `toDisplay()` adapters next to
 * `PortForwardViewModel`, which folds the controller snapshot into
 * [PortForwardDisplayState]), exactly like `ReleaseInfo` →
 * `ReleaseUpdateDisplay` (#2636 D3) and the usage family's
 * `UsageFetcher` mapping (#2636 D10).
 *
 * Field-by-field decisions (the extraction's "decide per field" list):
 *
 *  - [TunnelDisplay] mirrors `TunnelInfo` whole: every one of its seven
 *    fields is display-read (the ports table paints all seven; Services and
 *    the tunnel detail paint the port/process/status subset), so there is no
 *    narrowing to do — and no core-only field that could silently change
 *    under a stale mirror.
 *  - [PortForwardDisplayState] narrows by exactly one field: core state's
 *    `hostId` (the Room row key the ViewModel receives from its nav args)
 *    is never painted by any screen, so the display state drops it. The
 *    `scanning` derivation is re-derived here from the mirror's own
 *    material; its equivalence to core's is pinned by app2's
 *    `PortForwardDisplayMappingTest` sweep, which is what lets the family's
 *    PNGs be compared byte-for-byte across the move.
 *  - The enum constants are copied verbatim (names AND casing — core's
 *    `ConnectionState` is camelCase, `TunnelInfo.Status` SCREAMING_CASE);
 *    the same test asserts the mirror and core value sets stay equal.
 */
enum class ConnectionStateDisplay {
    Idle,
    Connecting,
    Connected,
    Reconnecting,

    /**
     * Terminal, not "still trying": the supervisor has stopped dialling and
     * only the user can change the outcome (#2491).
     */
    Lost,
}

/** Lifecycle state of one tunnel, mirroring core's `TunnelInfo.Status`. */
enum class TunnelStatusDisplay {
    /** Forward is up and accepting connections locally. */
    FORWARDING,

    /**
     * Remote port is listening but outside the auto-forward window and not
     * manually opted in. No local socket bound.
     */
    AVAILABLE,

    /** The forward failed to start; held so the UI can offer a retry. */
    FAILED,

    /** Previously open; the SSH session dropped and it will be recreated. */
    STOPPED,
}

/**
 * One tunnel row as the ports screens paint it, mirrored verbatim from core's
 * `TunnelInfo` (all seven fields are display-read).
 */
data class TunnelDisplay(
    /** Remote port the forward targets. */
    val remotePort: Int,
    /** Loopback port on the device that the user connects to. */
    val localPort: Int,
    /** Process name discovered on the remote side, or empty if unknown. */
    val process: String,
    /** Lifecycle state of this tunnel. */
    val status: TunnelStatusDisplay,
    /** Bytes pushed device → remote since the tunnel opened. */
    val bytesIn: Long = 0,
    /** Bytes pulled remote → device since the tunnel opened. */
    val bytesOut: Long = 0,
    /**
     * Instantaneous throughput in bytes per second, smoothed over the last
     * scan interval. Zero until the second scan tick observes traffic.
     */
    val speedBps: Long = 0,
)

/**
 * Everything the ports screens render: the display half of what was app2's
 * core-typed `PortForwardUiState`, now painted directly by the moved screens
 * (#2636 D11).
 *
 * [rows] is already filtered and ordered — the screen paints what it is given
 * and makes no visibility decisions of its own, so "which ports are
 * interesting" has exactly one implementation (app2's
 * `InterestingPortFilter`) and one test.
 */
data class PortForwardDisplayState(
    val hostName: String = "",
    val hostSubtitle: String = "",
    /** The durable `hosts.enabled` intent, as last read/written. */
    val enabled: Boolean = false,
    val connection: ConnectionStateDisplay = ConnectionStateDisplay.Idle,
    /**
     * What the user has to DO when [connection] is terminal
     * ([ConnectionStateDisplay.Lost]) — an unconfirmed host key, a deleted
     * host row. Null when the controller has no better explanation than
     * "could not connect", and null whenever [connection] is NOT terminal.
     */
    val attention: String? = null,
    val rows: List<TunnelDisplay> = emptyList(),
    /** All discovered ports for Services & tunnels, before the legacy noise filter. */
    val discoveredRows: List<TunnelDisplay> = emptyList(),
    /** Remote ports with a durable user-selected remote-to-local mapping. */
    val manualRemotePorts: Set<Int> = emptySet(),
    /** Durable Quiet labels for manually added tunnels, keyed by remote port. */
    val manualTunnelNames: Map<Int, String> = emptyMap(),
    /** Remote port to a locally verified HTTP(S) URL, for the browser handoff. */
    val verifiedHttpServices: Map<Int, String> = emptyMap(),
    val showAllPorts: Boolean = false,
    /** Rows the default filter is hiding right now. */
    val hiddenCount: Int = 0,
    /** True until the host row and the persisted checkbox have been read. */
    val loading: Boolean = true,
) {
    /** Forwarding is on, but nothing has been discovered yet. */
    val scanning: Boolean
        get() = enabled && rows.isEmpty() && hiddenCount == 0 && connection != ConnectionStateDisplay.Lost
}
