package com.pocketshell.next.files

import com.pocketshell.uikit.components.FileIconClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM coverage for the display helpers that moved with the D14 screen. */
class FileExplorerDisplayFamilyTest {

    @Test
    fun `file action edit availability preserves dotfile and trailing dot semantics`() {
        assertEquals(true, isLikelyEditableFile(".png"))
        assertEquals(false, isLikelyEditableFile("a.png"))
        assertEquals(true, isLikelyEditableFile(".env"))
        assertEquals(true, isLikelyEditableFile("archive."))
    }

    @Test
    fun `relative time rejects missing and future values and steps through units`() {
        val now = 1_700_000_000_000L
        assertNull(relativeTime(0, now))
        assertNull(relativeTime(now + 1, now))
        assertEquals("just now", relativeTime(now - 5_000, now))
        assertEquals("3m ago", relativeTime(now - 180_000, now))
        assertEquals("2d ago", relativeTime(now - 172_800_000, now))
    }

    @Test
    fun `row subtitle omits directory size but keeps modified time`() {
        val now = 1_700_000_000_000L
        val directory = FileEntryDisplay("/w/src", "src", true, 4_096, now)
        val file = FileEntryDisplay("/w/a.txt", "a.txt", false, 2_048, now)
        assertEquals("just now", rowSubtitle(directory, now))
        assertEquals("2.0 KB · just now", rowSubtitle(file, now))
    }

    @Test
    fun `icon class uses folders before the file suffix map`() {
        assertEquals(FileIconClass.FOLDER, iconClassFor(FileEntryDisplay("/w/a.png", "a.png", true, 0, 0)))
        assertEquals(FileIconClass.IMAGE, iconClassFor(FileEntryDisplay("/w/a.png", "a.png", false, 0, 0)))
    }

    @Test
    fun `display parent handles root top-level and nested paths`() {
        assertEquals("/", fileDisplayParent("/"))
        assertEquals("/", fileDisplayParent("/home"))
        assertEquals("/home/alexey", fileDisplayParent("/home/alexey/file.txt"))
    }

    @Test
    fun `display state derives empty and transfer-in-flight flags`() {
        assertTrue(FileExplorerDisplayState(loaded = true).isEmptyAndHealthy)
        assertTrue(
            FileExplorerDisplayState(
                transfer = FileTransferDisplayState.Running("a", uploading = true),
            ).transferring,
        )
    }
}
