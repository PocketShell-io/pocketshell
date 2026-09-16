package com.pocketshell.uikit.model

/**
 * Connection state rendered by `StatusDot`.
 *
 * Four conditions, deliberately coarse: the richer host-list states (known-but-
 * offline, per-host setup) are app-level projections, not dot states.
 *
 * - [Idle] — muted grey, no animation.
 * - [Connecting] — amber, pulses (1.4s linear infinite per the CSS).
 * - [Connected] — green with a soft outer glow.
 * - [Error] — red, no animation.
 */
enum class ConnectionStatus {
    Idle,
    Connecting,
    Connected,
    Error,
}
