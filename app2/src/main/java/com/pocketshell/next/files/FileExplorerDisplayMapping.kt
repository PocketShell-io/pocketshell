package com.pocketshell.next.files

import com.pocketshell.core.transport.SftpEntry

/** The files family's exhaustive app/core → shared-display seam (#2636 D14). */
internal fun FileExplorerUiState.toDisplay(): FileExplorerDisplayState =
    FileExplorerDisplayState(
        subtitle = fileLocationSubtitle(hostName, path),
        path = path,
        entries = entries.map(SftpEntry::toDisplay),
        crumbs = crumbs.map { FileCrumbDisplay(label = it.label, path = it.path) },
        loading = loading,
        loaded = loaded,
        failure = failure,
        transfer = transfer.toDisplay(),
        transfers = toTransfersUiState(),
        toolsVisible = toolsVisible,
        actionEntry = actionEntry?.toDisplay(),
        createFolder = createFolder.toDisplay(),
        newTextFile = newTextFile.toDisplay(),
        renameFile = renameFile.toDisplay(),
        deleteFile = deleteFile.toDisplay(),
        transfersVisible = transfersVisible,
        operationMessage = operationMessage,
    )

internal fun SftpEntry.toDisplay(): FileEntryDisplay = FileEntryDisplay(
    path = path,
    name = name,
    isDirectory = isDirectory,
    sizeBytes = sizeBytes,
    modifiedEpochMs = modifiedEpochMs,
)

internal fun TransferState.toDisplay(): FileTransferDisplayState = when (this) {
    TransferState.Idle -> FileTransferDisplayState.Idle
    is TransferState.Running -> FileTransferDisplayState.Running(
        name = name,
        uploading = uploading,
        source = source,
        destination = destination,
        bytesTransferred = bytesTransferred,
        totalBytes = totalBytes,
        id = id,
    )
    is TransferState.Done -> FileTransferDisplayState.Done(message, source, destination, id)
    is TransferState.Failed -> FileTransferDisplayState.Failed(message, source, destination, id)
}

private fun CreateFolderUiState.toDisplay() = CreateFolderDisplayState(visible, name, submitting, failure)

private fun NewTextFileUiState.toDisplay() = NewTextFileDisplayState(visible, name, submitting, failure)

private fun RenameFileUiState.toDisplay() =
    RenameFileDisplayState(entry?.toDisplay(), name, submitting, failure)

private fun DeleteFileUiState.toDisplay() =
    DeleteFileDisplayState(entry?.toDisplay(), submitting, failure)
