package com.pocketshell.next.files

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.pocketshell.core.transport.SftpEntry

/** Header context shared by the file browser and its transfer history. */
internal fun fileLocationSubtitle(hostName: String, path: String): String? {
    val host = hostName.trim().takeIf { it.isNotEmpty() }
    val location = com.pocketshell.next.workspaces.displayRemotePath(path)
    return listOfNotNull(host, location).joinToString(" · ").takeIf { it.isNotEmpty() }
}

/**
 * App-side route for the shared file-explorer display surface.
 *
 * SAF launchers, lifecycle/Hilt wiring, clipboard access, transport entries,
 * and path ingestion stay here. [FileExplorerScreen] receives only the pure
 * display mirrors produced by [FileExplorerUiState.toDisplay].
 */
@Composable
fun FileExplorerRoute(
    onOpenFile: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FileExplorerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.refresh() }

    LaunchedEffect(state.newFilePathToOpen) {
        state.newFilePathToOpen?.let { path ->
            viewModel.consumeNewFilePath()
            onOpenFile(path)
        }
    }

    val uploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri != null) {
            val resolver = context.contentResolver
            val document = describeDocument(
                queryColumns = { columns ->
                    resolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (!cursor.moveToFirst()) return@use null
                        columns.associateWith { column ->
                            val index = cursor.getColumnIndex(column)
                            if (index < 0 || cursor.isNull(index)) null else cursor.getString(index)
                        }
                    }
                },
                fallbackName = uri.lastPathSegment ?: "upload",
            )
            viewModel.upload(
                displayName = document.name,
                declaredSize = document.size,
                openStream = { resolver.openInputStream(uri) },
            )
        }
    }

    var pendingDownload by remember { mutableStateOf<SftpEntry?>(null) }
    val downloadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri: Uri? ->
        val entry = pendingDownload
        pendingDownload = null
        if (uri != null && entry != null) {
            viewModel.download(entry) { bytes ->
                val stream = context.contentResolver.openOutputStream(uri)
                    ?: throw java.io.IOException("could not open the chosen destination")
                stream.use { it.write(bytes) }
            }
        }
    }

    fun coreEntry(path: String): SftpEntry? = sequenceOf(
        state.entries.asSequence(),
        listOfNotNull(state.actionEntry, state.renameFile.entry, state.deleteFile.entry).asSequence(),
    ).flatten().firstOrNull { it.path == path }

    FileExplorerScreen(
        state = state.toDisplay(),
        onBack = onBack,
        onUp = viewModel::goUp,
        onOpenDirectory = { coreEntry(it.path)?.let(viewModel::openDirectory) },
        onOpenFile = { onOpenFile(it.path) },
        onNavigateTo = viewModel::navigateTo,
        onUpload = { uploadLauncher.launch("*/*") },
        onDownload = { display ->
            coreEntry(display.path)?.let { entry ->
                pendingDownload = entry
                downloadLauncher.launch(entry.name)
            }
        },
        onCopyPath = { path ->
            context.getSystemService(android.content.ClipboardManager::class.java)
                ?.setPrimaryClip(android.content.ClipData.newPlainText("Remote path", path))
            viewModel.dismissActions()
        },
        onDismissTransfer = viewModel::dismissTransfer,
        onRetry = viewModel::refresh,
        onOpenTools = viewModel::openTools,
        onDismissTools = viewModel::dismissTools,
        onOpenActions = { coreEntry(it.path)?.let(viewModel::openActions) },
        onDismissActions = viewModel::dismissActions,
        onOpenCreateFolder = viewModel::openCreateFolder,
        onCreateFolderNameChange = viewModel::setCreateFolderName,
        onCreateFolder = viewModel::createFolder,
        onDismissCreateFolder = viewModel::dismissCreateFolder,
        onOpenRename = { coreEntry(it.path)?.let(viewModel::openRename) },
        onRenameNameChange = viewModel::setRenameName,
        onRename = viewModel::renameFile,
        onDismissRename = viewModel::dismissRename,
        onRequestDelete = { coreEntry(it.path)?.let(viewModel::requestDelete) },
        onConfirmDelete = viewModel::confirmDelete,
        onDismissDelete = viewModel::dismissDelete,
        onOpenTransfers = viewModel::openTransfers,
        onDismissTransfers = viewModel::dismissTransfers,
        onRetryTransfer = viewModel::retryTransfer,
        onNewTextFile = viewModel::openNewTextFile,
        onNewTextFileNameChange = viewModel::setNewTextFileName,
        onCreateNewTextFile = viewModel::createNewTextFile,
        onDismissNewTextFile = viewModel::dismissNewTextFile,
        onDismissOperationMessage = viewModel::dismissOperationMessage,
        modifier = modifier,
    )
}

/** What the SAF picker told us about the chosen document. */
internal data class PickedDocument(val name: String, val size: Long)

internal fun describeDocument(
    queryColumns: (List<String>) -> Map<String, String?>?,
    fallbackName: String,
): PickedDocument {
    val columns = runCatching {
        queryColumns(listOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
    }.getOrNull().orEmpty()
    val name = columns[OpenableColumns.DISPLAY_NAME]?.takeIf { it.isNotBlank() } ?: fallbackName
    val size = columns[OpenableColumns.SIZE]?.toLongOrNull() ?: -1L
    return PickedDocument(name = sanitizeUploadName(name), size = size)
}
