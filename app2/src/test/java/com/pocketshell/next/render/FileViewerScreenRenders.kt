package com.pocketshell.next.render

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import com.pocketshell.next.files.FileKind
import com.pocketshell.next.files.ViewerContent
import com.pocketshell.next.files.ViewerScreen
import com.pocketshell.next.files.ViewerUiState
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import com.pocketshell.testsupport.LeakGuard
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fresh Quiet renders for the remote file viewer/editor (#2635 R5,
 * destination FileViewer). Run with:
 *
 * ./gradlew :app2:testDebugUnitTest --tests '*FileViewerScreenRenders*' --rerun-tasks
 *
 * Text preview and the editing buffer are the two states the composer spends
 * its time in; the binary capture pins the bounded hex-dump path that must
 * never render as a blank screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class FileViewerScreenRenders {

    // Issue #2724: record-mode captures compose screens a plain unit run never
    // composes, so a leak from that composition fails HERE (the class-guard
    // grace catches post-test stragglers), not whichever runTest class is next.
    @get:Rule
    val leakGuard = LeakGuard()

    // Issue #2733: frozen frame clock for record captures — see
    // [captureFrozenRender]. Without it animated states never let the
    // Robolectric main looper drain and record mode wedges.
    @get:Rule
    val composeRule = createComposeRule()

    companion object {
        @JvmStatic
        @get:ClassRule
        val leakGuardClass = LeakGuard.classGuard()
    }

    @Test
    fun fileViewerTextPreview() = render("i2762-file-viewer-text-preview") {
        ViewerScreen(
            state = ViewerUiState(
                hostName = "hetzner",
                path = "/home/alexey/projects/pocketshell/deploy.sh",
                loaded = true,
                kind = FileKind.TEXT,
                content = ViewerContent.Text(
                    """
                    #!/usr/bin/env bash
                    set -euo pipefail

                    # Rebuild the dev-box agent stack and restart the units.
                    docker compose -f /home/alexey/projects/pocketshell/compose.yaml build
                    docker compose -f /home/alexey/projects/pocketshell/compose.yaml up -d

                    systemctl --user restart pocketshell-agent.path
                    echo "stack rebuilt"
                    """.trimIndent(),
                ),
            ),
            onBack = {},
            onEdit = {},
            onDraftChange = {},
            onSave = {},
            onCancelEdit = {},
            onToggleMarkdown = {},
            onDismissSaved = {},
        )
    }

    @Test
    fun fileViewerEditingDraft() = render("i2762-file-viewer-editing-draft") {
        ViewerScreen(
            state = ViewerUiState(
                hostName = "hetzner",
                path = "/home/alexey/projects/pocketshell/notes.md",
                loaded = true,
                kind = FileKind.TEXT,
                markdownCapable = true,
                content = ViewerContent.Text("# notes\n\n- old line\n"),
                editing = true,
                draft = "# notes\n\n- old line\n- added from the phone while on the train\n",
            ),
            onBack = {},
            onEdit = {},
            onDraftChange = {},
            onSave = {},
            onCancelEdit = {},
            onToggleMarkdown = {},
            onDismissSaved = {},
        )
    }

    @Test
    fun fileViewerBinaryHexDump() = render("i2762-file-viewer-binary-hex-dump") {
        ViewerScreen(
            state = ViewerUiState(
                hostName = "hetzner",
                path = "/home/alexey/projects/pocketshell/blob.bin",
                loaded = true,
                kind = FileKind.BINARY,
                content = ViewerContent.Binary(
                    byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 0x02, 0x01, 0x01, 0x00, 0x00, 0x00),
                ),
            ),
            onBack = {},
            onEdit = {},
            onDraftChange = {},
            onSave = {},
            onCancelEdit = {},
            onToggleMarkdown = {},
            onDismissSaved = {},
        )
    }

    private fun render(name: String, content: @Composable () -> Unit) {
        composeRule.captureFrozenRender("build/renders/$name.png") {
            PocketShellTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = PocketShellColors.Background,
                ) {
                    content()
                }
            }
        }
    }
}
