package com.pocketshell.next.ports

import com.pocketshell.core.portfwd.AutoForwarderSupervisor.ConnectionState
import com.pocketshell.core.portfwd.TunnelInfo

/**
 * Maps one core `TunnelInfo` onto the pure [TunnelDisplay] the shared ports
 * screens paint (#2636 D11).
 *
 * This is the ports family's whole core → display seam, next to the single
 * ingestion point ([PortForwardViewModel], which folds the controller
 * snapshot into [PortForwardDisplayState]) — no `com.pocketshell.core.portfwd`
 * type ever crosses into `shared:ui-screens`. Every value is copied verbatim,
 * never re-implemented, so the screens cannot drift from the supervisor's
 * snapshot; the mirror carries no derivation of its own beyond the re-derived
 * `scanning` flag on the display state, pinned equal to core's by
 * `PortForwardDisplayMappingTest` — which is what lets the family's PNGs be
 * compared byte-for-byte across the move.
 */
internal fun TunnelInfo.toDisplay(): TunnelDisplay =
    TunnelDisplay(
        remotePort = remotePort,
        localPort = localPort,
        process = process,
        status = status.toDisplay(),
        bytesIn = bytesIn,
        bytesOut = bytesOut,
        speedBps = speedBps,
    )

/**
 * Exhaustive on purpose: a NEW core `TunnelInfo.Status` constant fails this
 * compile instead of silently rendering through a hole — deciding what a new
 * lifecycle state looks like on screen is the app's job, not the mirror's.
 */
internal fun TunnelInfo.Status.toDisplay(): TunnelStatusDisplay = when (this) {
    TunnelInfo.Status.FORWARDING -> TunnelStatusDisplay.FORWARDING
    TunnelInfo.Status.AVAILABLE -> TunnelStatusDisplay.AVAILABLE
    TunnelInfo.Status.FAILED -> TunnelStatusDisplay.FAILED
    TunnelInfo.Status.STOPPED -> TunnelStatusDisplay.STOPPED
}

/**
 * Exhaustive on purpose, same rule: a NEW core `ConnectionState` constant
 * fails this compile instead of silently painting through a hole.
 */
internal fun ConnectionState.toDisplay(): ConnectionStateDisplay = when (this) {
    ConnectionState.Idle -> ConnectionStateDisplay.Idle
    ConnectionState.Connecting -> ConnectionStateDisplay.Connecting
    ConnectionState.Connected -> ConnectionStateDisplay.Connected
    ConnectionState.Reconnecting -> ConnectionStateDisplay.Reconnecting
    ConnectionState.Lost -> ConnectionStateDisplay.Lost
}
