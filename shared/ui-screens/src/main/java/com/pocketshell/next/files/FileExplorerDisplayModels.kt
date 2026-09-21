package com.pocketshell.next.files

/** Pure display mirror of the core transport entry painted by the file explorer. */
data class FileEntryDisplay(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedEpochMs: Long,
)

/** One already-resolved breadcrumb; remote path arithmetic stays app-side. */
data class FileCrumbDisplay(val label: String, val path: String)

/** Pure display mirror of the app-side transfer lifecycle. */
sealed interface FileTransferDisplayState {
    data object Idle : FileTransferDisplayState

    data class Running(
        val name: String,
        val uploading: Boolean,
        val source: String? = null,
        val destination: String? = null,
        val bytesTransferred: Long = 0L,
        val totalBytes: Long? = null,
        val id: Long? = null,
    ) : FileTransferDisplayState

    data class Done(
        val message: String,
        val source: String? = null,
        val destination: String? = null,
        val id: Long? = null,
    ) : FileTransferDisplayState

    data class Failed(
        val message: String,
        val source: String? = null,
        val destination: String? = null,
        val id: Long? = null,
    ) : FileTransferDisplayState
}

data class CreateFolderDisplayState(
    val visible: Boolean = false,
    val name: String = "",
    val submitting: Boolean = false,
    val failure: String? = null,
)

data class NewTextFileDisplayState(
    val visible: Boolean = false,
    val name: String = "",
    val submitting: Boolean = false,
    val failure: String? = null,
)

data class RenameFileDisplayState(
    val entry: FileEntryDisplay? = null,
    val name: String = "",
    val submitting: Boolean = false,
    val failure: String? = null,
) {
    val visible: Boolean get() = entry != null
}

data class DeleteFileDisplayState(
    val entry: FileEntryDisplay? = null,
    val submitting: Boolean = false,
    val failure: String? = null,
)

/** Everything the shared file explorer paints; no transport or Android types cross the seam. */
data class FileExplorerDisplayState(
    val subtitle: String? = null,
    val path: String = "",
    val entries: List<FileEntryDisplay> = emptyList(),
    val crumbs: List<FileCrumbDisplay> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val failure: String? = null,
    val transfer: FileTransferDisplayState = FileTransferDisplayState.Idle,
    val transfers: TransfersUiState = TransfersUiState(),
    val toolsVisible: Boolean = false,
    val actionEntry: FileEntryDisplay? = null,
    val createFolder: CreateFolderDisplayState = CreateFolderDisplayState(),
    val newTextFile: NewTextFileDisplayState = NewTextFileDisplayState(),
    val renameFile: RenameFileDisplayState = RenameFileDisplayState(),
    val deleteFile: DeleteFileDisplayState = DeleteFileDisplayState(),
    val transfersVisible: Boolean = false,
    val operationMessage: String? = null,
) {
    val isEmptyAndHealthy: Boolean
        get() = loaded && entries.isEmpty() && failure == null

    val transferring: Boolean
        get() = transfer is FileTransferDisplayState.Running
}

/** POSIX parent spelling needed only for display copy in rename/delete surfaces. */
internal fun fileDisplayParent(path: String): String {
    val trimmed = path.trimEnd('/')
    if (trimmed.isEmpty() || trimmed == "/") return "/"
    val cut = trimmed.lastIndexOf('/')
    return if (cut <= 0) "/" else trimmed.substring(0, cut)
}

internal fun fileDisplayJoin(parent: String, child: String): String =
    if (parent == "/") "/${child.trim('/')}" else "${parent.trimEnd('/')}/${child.trim('/')}"
