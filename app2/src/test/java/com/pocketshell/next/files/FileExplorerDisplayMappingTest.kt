package com.pocketshell.next.files

import com.pocketshell.core.transport.SftpEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the complete app/core → shared display seam introduced by #2636 D14. */
class FileExplorerDisplayMappingTest {

    @Test
    fun `core entry mapping copies every painted field`() {
        val core = SftpEntry(
            path = "/work/README.md",
            isDirectory = false,
            sizeBytes = 12_345,
            modifiedEpochMs = 1_726_600_000_000L,
        )

        assertEquals(
            FileEntryDisplay(
                path = "/work/README.md",
                name = "README.md",
                isDirectory = false,
                sizeBytes = 12_345,
                modifiedEpochMs = 1_726_600_000_000L,
            ),
            core.toDisplay(),
        )
    }

    @Test
    fun `every transfer variant maps without losing progress or endpoints`() {
        assertEquals(FileTransferDisplayState.Idle, TransferState.Idle.toDisplay())
        assertEquals(
            FileTransferDisplayState.Running("a.txt", true, "device", "/w/a.txt", 7, 10, 3),
            TransferState.Running("a.txt", true, "device", "/w/a.txt", 7, 10, 3).toDisplay(),
        )
        assertEquals(
            FileTransferDisplayState.Done("saved", "/w/a.txt", "device", 4),
            TransferState.Done("saved", "/w/a.txt", "device", 4).toDisplay(),
        )
        assertEquals(
            FileTransferDisplayState.Failed("nope", "device", "/w/a.txt", 5),
            TransferState.Failed("nope", "device", "/w/a.txt", 5).toDisplay(),
        )
    }

    @Test
    fun `whole state mapping resolves subtitle crumbs rows and transfer history`() {
        val record = FileTransferRecord(
            id = 8,
            name = "a.txt",
            uploading = true,
            source = "device",
            destination = "/home/alexey/git/a.txt",
        )
        val display = FileExplorerUiState(
            hostId = 42,
            hostName = "hetzner",
            path = "/home/alexey/git",
            entries = listOf(SftpEntry("/home/alexey/git/a.txt", false, 9, 10)),
            loaded = true,
            transferRecords = listOf(record),
            toolsVisible = true,
            transfersVisible = true,
            operationMessage = "Created folder docs",
        ).toDisplay()

        assertEquals("hetzner · ~/git", display.subtitle)
        assertEquals(listOf("/", "/home", "/home/alexey", "/home/alexey/git"), display.crumbs.map { it.path })
        assertEquals(listOf("a.txt"), display.entries.map { it.name })
        assertEquals(listOf(record), display.transfers.transferRecords)
        assertTrue(display.loaded)
        assertTrue(display.toolsVisible)
        assertTrue(display.transfersVisible)
        assertEquals("Created folder docs", display.operationMessage)
    }

    @Test
    fun `action and mutation forms map their selected entries and failures`() {
        val entry = SftpEntry("/w/old.txt", false, 4, 5)
        val display = FileExplorerUiState(
            path = "/w",
            actionEntry = entry,
            createFolder = CreateFolderUiState(true, "docs", true, "folder failed"),
            newTextFile = NewTextFileUiState(true, "notes.md", false, "file failed"),
            renameFile = RenameFileUiState(entry, "new.txt", true, "rename failed"),
            deleteFile = DeleteFileUiState(entry, true, "delete failed"),
        ).toDisplay()

        assertEquals(entry.path, display.actionEntry?.path)
        assertEquals(CreateFolderDisplayState(true, "docs", true, "folder failed"), display.createFolder)
        assertEquals(NewTextFileDisplayState(true, "notes.md", false, "file failed"), display.newTextFile)
        assertEquals("new.txt", display.renameFile.name)
        assertEquals(entry.path, display.renameFile.entry?.path)
        assertEquals(entry.path, display.deleteFile.entry?.path)
        assertEquals("delete failed", display.deleteFile.failure)
    }

    @Test
    fun `display derivations preserve empty and transferring distinctions`() {
        val empty = FileExplorerUiState(loaded = true).toDisplay()
        assertTrue(empty.isEmptyAndHealthy)
        assertFalse(empty.transferring)

        val running = FileExplorerUiState(
            transfer = TransferState.Running("a.txt", uploading = false),
            failure = "listing failed",
        ).toDisplay()
        assertFalse(running.isEmptyAndHealthy)
        assertTrue(running.transferring)
        assertNull(running.subtitle)
    }
}
