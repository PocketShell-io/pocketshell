package com.pocketshell.next.files

/**
 * The pure UI state of the Transfers page: what the screen paints and nothing
 * else.
 *
 * Lives in the shared presentation module (#2636 D9). app2's
 * `FileExplorerUiState` stays in `FileExplorerViewModel.kt` — it carries
 * `SftpEntry` (core-transport) and `RemotePath.Crumb`, neither of which may
 * cross this module's boundary — and the app2-side
 * `FileExplorerUiState.toTransfersUiState()` adapter maps it onto the display
 * shapes below, the same service/result seam `ReleaseInfo` →
 * `ReleaseUpdateDisplay` uses for the settings pages (#2636 D3).
 *
 * [FileTransferStatus] and [FileTransferRecord] moved here verbatim from
 * `FileExplorerViewModel.kt`: they are pure display records already, and the
 * shared package name is unchanged, so every app2 reference to them still
 * resolves without an import edit.
 */

/** The lifecycle state shown by the full Transfers surface. */
enum class FileTransferStatus {
    Running,
    Completed,
    Failed,
}

/**
 * A durable-in-this-screen record of one upload or download.
 *
 * The transport API is deliberately whole-file shaped, so an unknown remote
 * size is represented by a null [totalBytes] and the UI renders an indeterminate
 * transfer. Local uploads can report measured stream bytes as they are read.
 */
data class FileTransferRecord(
    val id: Long,
    val name: String,
    val uploading: Boolean,
    val source: String,
    val destination: String,
    val bytesTransferred: Long = 0L,
    val totalBytes: Long? = null,
    val status: FileTransferStatus = FileTransferStatus.Running,
    val message: String? = null,
)

/**
 * Everything [TransfersScreen] renders.
 *
 * [subtitle] arrives already spelled rather than as a `hostName`/`path` pair:
 * app2's `fileLocationSubtitle` helper resolves it through
 * `com.pocketshell.next.workspaces.displayRemotePath`, whose file imports
 * `core-hostapi`, so that helper stays app2-side and hands the rendered string
 * across the seam.
 */
data class TransfersUiState(
    /** Header context — "host · /path" — as the app2 side spelled it. */
    val subtitle: String? = null,
    /** Records remain visible after a transfer completes or fails. */
    val transferRecords: List<FileTransferRecord> = emptyList(),
)
