# Reconnect policy

PocketShell automatically reconnects only for visible, active SSH terminal
screens after an unexpected transport drop. The remote session is owned by
aplexer and remains independent of the phone's SSH transport.

## Foreground drop (U-7: `ReconnectController`)

`ReconnectController` owns a fixed retry ladder: immediate retry (0 ms — the
common case is a blip the very next dial rides through), then 1s, 2s, 5s, and
10s. After five failed attempts it gives up: the screen moves to `Failed` and
keeps a manual Reconnect action for the last known host and aplexer session
target. The attempt counter lives in `SessionViewModel` and resets when the
user taps Retry or the app returns to the foreground.

While reconnecting, the session status is `Reconnecting`, prompt sending
remains disabled, and the terminal surface is not cleared — the emulator keeps
its last rendered frame until the new PTY supplies bytes. The user can cancel
or wait for the session to return to `Connected`.

The shortness of the ladder is the design: the pre-rewrite client's episode
budgets, jitter, storm classes and liveness probes never made a reconnect land
sooner (diagnosis doc §3.4), so none of that machinery was carried over.

## Background grace (U-8: `GraceCoordinator`, D21)

Backgrounding does not detach anything and runs no reconnect ladder. While the
app is in the background with live connections, `GraceCoordinator` holds the
SSH transport open for a bounded grace window: it arms one delayed close per
live connection (`HostConnection.scheduleGraceClose`) and shows a count-down
notification (`GraceService`, with wake lock). The window defaults to 90
seconds and is a user setting (`backgroundGraceMillis`), read once per armed
window, so changing it takes effect on the next background.

Returning to the foreground inside the window cancels every pending close and
takes the service down — the ride-through is silent, with no reconnect banner.

If the window elapses, the transport closes itself with close reason
`GraceExpired`. That reason is how `SessionViewModel` knows an expired window
is a link to reattach rather than a session that ended: the next foreground
visit reconnects and reattaches to the same host session, whose aplexer
workload survived the transport close.

Port forwarding is the separate foreground-service exception and owns its own
reconnect loop while forwarding is enabled.
