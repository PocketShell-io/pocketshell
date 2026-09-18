package com.pocketshell.next.composer

/**
 * A staged attachment as it survives a process death: the remote path it was
 * uploaded to plus the name the tile shows.
 *
 * The local preview `Uri` is deliberately absent. It is a permission grant this
 * process holds for this session, so persisting it would store a handle that is
 * invalid by the time it is read back; the tile renders perfectly well from the
 * remote path and the name, which is what the host has anyway.
 *
 * Lives in the shared presentation module (#2636 D2); the SharedPreferences
 * persistence around it stays in app2's draft store.
 */
data class StagedAttachment(
    val remotePath: String,
    val displayName: String,
    val mimeType: String? = null,
)
