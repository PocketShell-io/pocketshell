package com.pocketshell.next.composer

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Where the composer's send goes (rewrite task P-1).
 *
 * Deliberately two members. The composer needs to know whether the session is
 * attached AT THE MOMENT OF THE TAP and it needs somewhere to put bytes;
 * anything more would be the composer re-deriving session state that
 * `SessionViewModel` already owns, which is how the old client ended up with
 * two disagreeing views of one connection.
 *
 * [isLive] is a property, not a constructor value, so an implementation reads
 * the session's CURRENT state rather than one captured when the screen last
 * recomposed — a stale snapshot here would mean sending into a dead pane and
 * clearing the draft for it.
 *
 * Deliberately NOT in the shared presentation module (#2636 D2): this is the
 * transport-facing seam (live-session reads and PTY writes), so it stays in
 * app2 next to the session logic that implements it. The pure presentation
 * state it feeds lives in shared:ui-screens under the same package.
 */
interface SessionSink {

    /** True only when the session is attached and bytes can actually leave. */
    val isLive: Boolean

    /** Writes [bytes] to the session. Must not throw. */
    fun sendBytes(bytes: ByteArray)

    /** Emits when a PTY write failed after the composer thought the session was live. */
    val sendFailures: Flow<Unit>
        get() = emptyFlow()
}
