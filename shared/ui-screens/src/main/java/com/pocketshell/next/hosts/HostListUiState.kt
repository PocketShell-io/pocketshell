package com.pocketshell.next.hosts

/**
 * One rendered row of the host list.
 *
 * Deliberately three primitive fields. The old client's row model carried a
 * bootstrap state, a live connection status, a session count, a "resume last
 * session" descriptor and an update-available flag, which is why its
 * ViewModel needed probe scheduling, cache-staleness rules and a connection
 * observer to keep them honest. app2's list is a read-only projection of the
 * `hosts` table: what Room emits is what the screen paints, so there is no
 * second source of truth to reconcile. Status indicators come back in a later
 * plan task (P-6) on top of the connections registry, not from here.
 *
 * Lives in the shared presentation module (#2636 D1); the Room→row projection
 * itself stays in app2's [com.pocketshell.next.hosts.HostListViewModel].
 */
data class HostRow(
    val id: Long,
    val name: String,
    /** `username@hostname` — the muted mono subtitle line on the row. */
    val subtitle: String,
)

/**
 * What [HostListScreen] renders.
 *
 * [loaded] exists only to separate "Room has not emitted yet" from "there
 * genuinely are no hosts" — without it a cold launch flashes the empty state
 * for a frame before the first query result arrives.
 */
data class HostListUiState(
    val hosts: List<HostRow> = emptyList(),
    val loaded: Boolean = false,
)
