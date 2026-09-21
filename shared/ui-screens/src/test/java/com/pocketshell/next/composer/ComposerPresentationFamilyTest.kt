package com.pocketshell.next.composer

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks the #2636-D15 composer display extraction at its module boundary. */
class ComposerPresentationFamilyTest {

    @Test
    fun `composer display family is platform free and exclusively shared owned`() {
        val root = repositoryRoot()
        val sharedComposer = File(root, "shared/ui-screens/src/main/java/com/pocketshell/next/composer")
        val appComposer = File(root, "app2/src/main/java/com/pocketshell/next/composer")
        val movedFiles = listOf(
            "ComposerAttachmentTiles.kt",
            "ComposerBar.kt",
            "ComposerRecordingSurfaces.kt",
            "ComposerSendCommit.kt",
            "MessageHistorySheet.kt",
            "SlashCommandAutocomplete.kt",
        )

        assertEquals(
            "every D15 display file must be owned by shared:ui-screens",
            emptyList<String>(),
            movedFiles.filterNot { File(sharedComposer, it).isFile },
        )
        assertEquals(
            "same-named app2 files would collide on their JVM facades",
            emptyList<String>(),
            movedFiles.filter { File(appComposer, it).exists() },
        )

        val displaySources = (movedFiles + "PromptComposerContent.kt")
            .associateWith { File(sharedComposer, it).readText() }
        val platformImports = displaySources.flatMap { (name, source) ->
            Regex("""(?m)^import android\..+$""").findAll(source).map { "$name: ${it.value}" }.toList()
        }
        assertEquals(
            "shared composer display code must not import Android platform APIs",
            emptyList<String>(),
            platformImports,
        )
        assertTrue(
            "PromptComposerContent must leave the permission route in app2",
            File(appComposer, "PromptComposerSheet.kt").readText()
                .contains("rememberLauncherForActivityResult"),
        )
    }

    @Test
    fun `attachment labels are stable display values`() {
        assertEquals("PNG", extensionLabel("capture.png"))
        assertEquals("GZ", extensionLabel("archive.tar.gz"))
        assertEquals("FILE", extensionLabel("README"))
        assertEquals("LONGE", extensionLabel("thing.longextension"))
    }

    @Test
    fun `waveform envelope is symmetric and bounded`() {
        val heights = (0 until 30).map(::barEnvelopeHeightDp)
        assertEquals(heights, heights.reversed())
        assertTrue(heights.all { it in 6f..28f })
        assertTrue(heights[14] > heights.first())
    }

    private fun repositoryRoot(): File {
        val start = File(System.getProperty("user.dir") ?: error("no user.dir"))
        return generateSequence(start) { it.parentFile }
            .firstOrNull { File(it, "shared/ui-screens").isDirectory && File(it, "app2").isDirectory }
            ?: error("could not locate repository root from ${start.absolutePath}")
    }
}
