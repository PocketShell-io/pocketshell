package com.pocketshell.next.terminal

import com.pocketshell.core.hostapi.HostCliError
import com.pocketshell.core.transport.ConnectResult
import com.pocketshell.next.connect.ConnectionsRegistry
import com.pocketshell.next.hostcli.HostCliClientFactory

/** How one stop attempt ended. */
internal sealed interface StopOutcome {

    /** The host accepted the kill; the route should pop back to the tree. */
    data object Stopped : StopOutcome

    /** The kill did not happen; [message] is the user-facing banner. */
    data class Failed(val message: String) : StopOutcome
}

/**
 * `pocketshell sessions kill -- NAME` for the session this screen is attached
 * to (issue #2495 extraction of [SessionViewModel.runStop]; the ViewModel maps
 * [StopOutcome] onto its banners and its leave signal).
 *
 * Kill is name-addressed on the host CLI, so when an id is held (issue #2572)
 * the CURRENT name is resolved from a fresh listing first: killing the stale
 * label would either fail after a rename or — the silent-collision case this
 * issue exists to kill — stop whichever NEW session took the old name. A
 * session the listing no longer knows is a loud "no longer running", never a
 * guess.
 */
internal class SessionStopper(
    private val registry: ConnectionsRegistry,
    private val clients: HostCliClientFactory,
) {
    /**
     * Resolves the session's current name (by id when one is held, else the
     * label as-is) and kills it. Never throws; every failure comes back as
     * [StopOutcome.Failed] with a message the screen can show.
     */
    suspend fun stop(hostId: Long, sessionId: String?, label: String): StopOutcome {
        val connection = when (val result = registry.getOrConnect(hostId)) {
            is ConnectResult.Connected -> result.connection
            is ConnectResult.NeedsTrust -> return StopOutcome.Failed(
                "This host's key still needs to be confirmed. Open it from the host " +
                    "list to review the key.",
            )
            is ConnectResult.Failed -> return StopOutcome.Failed(result.message)
        }
        val client = clients.create(connection)
        val name: String = if (sessionId == null) {
            label
        } else {
            val listing = client.listSessions().fold(
                onSuccess = { it },
                onFailure = { error -> return StopOutcome.Failed(stopFailure(error)) },
            )
            val row = listing.sessions.firstOrNull { it.id == sessionId }
            if (row == null) {
                return StopOutcome.Failed("Session \"$label\" is no longer running on the host.")
            }
            row.name
        }
        return client.killSession(name).fold(
            onSuccess = { StopOutcome.Stopped },
            onFailure = { error -> StopOutcome.Failed(stopFailure(error)) },
        )
    }

    private fun stopFailure(error: Throwable): String = when (error) {
        is HostCliError -> error.userMessage
        else -> "Could not stop the session on the host: " + SessionEndMessages.describe(error)
    }
}
