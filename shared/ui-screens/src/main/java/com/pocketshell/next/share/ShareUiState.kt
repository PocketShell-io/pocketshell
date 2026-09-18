package com.pocketshell.next.share

/**
 * The pure UI state of the share target: what the picker paints and nothing
 * else.
 *
 * Lives in the shared presentation module (#2636 D4); the Hilt ViewModel that
 * produces it (Room, connections, upload transport) and the payload types it
 * abstracts over stay in app2's `ShareViewModel.kt`.
 */

/** One host the share can be routed to. */
data class ShareHostRow(
    val id: Long,
    val name: String,
    /** `username@hostname` — the muted mono subtitle, same shape as the host list. */
    val subtitle: String,
    /**
     * The app already holds a live connection to this host. Shown on the row,
     * and the tie-breaker that lets the picker be skipped when the user is
     * plainly "in" one host right now.
     */
    val connected: Boolean,
)

/** Where the share has got to. */
sealed interface ShareUploadState {

    /** Nothing in flight — the picker (or the empty state) is on screen. */
    data object Idle : ShareUploadState

    data class Running(val hostName: String, val detail: String) : ShareUploadState

    /** Every item landed. [paths] are the absolute remote paths, in order. */
    data class Success(
        val hostName: String,
        val paths: List<String>,
    ) : ShareUploadState {
        /**
         * Names the host, NOT the path: the paths are listed under this banner
         * verbatim, and saying the same 70-character path twice on one small
         * screen is how the one line that matters stops being read.
         */
        val message: String
            get() = if (paths.size == 1) {
                "Sent to $hostName"
            } else {
                "Sent ${paths.size} files to $hostName"
            }
    }

    /**
     * At least one item did not land.
     *
     * [uploaded] is not cosmetic: a partial failure that reported only the error
     * would leave the user unable to tell whether the other three screenshots
     * are on the host or not.
     */
    data class Failed(
        val hostName: String,
        val message: String,
        val uploaded: List<String> = emptyList(),
        val failedNames: List<String> = emptyList(),
    ) : ShareUploadState
}

/** Everything [SharePickerScreen] paints. */
data class ShareUiState(
    /** Display labels of the staged items, in share order. */
    val items: List<String> = emptyList(),
    val hosts: List<ShareHostRow> = emptyList(),
    /**
     * Room has answered. Separates "still loading" from "no hosts configured",
     * which need very different screens — the second one is a dead end the user
     * has to be told about.
     */
    val hostsLoaded: Boolean = false,
    val upload: ShareUploadState = ShareUploadState.Idle,
) {
    /** A transfer is in flight — no second one may start on top of it. */
    val busy: Boolean get() = upload is ShareUploadState.Running
}
