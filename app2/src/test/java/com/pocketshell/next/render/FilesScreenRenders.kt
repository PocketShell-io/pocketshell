package com.pocketshell.next.render

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import com.pocketshell.core.transport.SftpEntry
import com.pocketshell.next.files.FileExplorerScreen
import com.pocketshell.next.files.FileExplorerUiState
import com.pocketshell.next.files.FileToolsSheetContent
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
 * Fresh Quiet renders for the remote file explorer (#2635 R5, destination
 * Files). Run with:
 *
 * ./gradlew :app2:testDebugUnitTest --tests '*FilesScreenRenders*' --rerun-tasks
 *
 * The three screen captures exercise the populated listing, the healthy empty
 * folder and the failed listing — the three states the #2635 audit calls out
 * as visually distinct. The tools-sheet capture composes the content-only
 * sheet body, mirroring its production container.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class FilesScreenRenders {

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

        /** Fixed mtime anchor so the "3h ago" subtitles are deterministic. */
        const val NOW_MS = 1_726_600_000_000L
    }

    @Test
    fun filesListingPopulated() = render("i2762-files-listing-populated") {
        FileExplorerScreen(
            state = FileExplorerUiState(
                hostName = "hetzner",
                path = "/home/alexey/projects/pocketshell",
                entries = listing(),
                loaded = true,
            ),
            onBack = {},
            onUp = {},
            onOpenDirectory = {},
            onOpenFile = {},
            onNavigateTo = {},
            onUpload = {},
            onDownload = {},
            onDismissTransfer = {},
            onRetry = {},
            nowMs = NOW_MS,
        )
    }

    @Test
    fun filesListingEmptyFolder() = render("i2762-files-listing-empty-folder") {
        FileExplorerScreen(
            state = FileExplorerUiState(
                hostName = "hetzner",
                path = "/home/alexey/inbox/empty-dir",
                entries = emptyList(),
                loaded = true,
            ),
            onBack = {},
            onUp = {},
            onOpenDirectory = {},
            onOpenFile = {},
            onNavigateTo = {},
            onUpload = {},
            onDownload = {},
            onDismissTransfer = {},
            onRetry = {},
            nowMs = NOW_MS,
        )
    }

    @Test
    fun filesListingFailedWithRetry() = render("i2762-files-listing-failed-retry") {
        FileExplorerScreen(
            state = FileExplorerUiState(
                hostName = "hetzner",
                path = "/var/log/collectd",
                entries = emptyList(),
                loaded = true,
                failure = "SFTP read failed: permission denied",
            ),
            onBack = {},
            onUp = {},
            onOpenDirectory = {},
            onOpenFile = {},
            onNavigateTo = {},
            onUpload = {},
            onDownload = {},
            onDismissTransfer = {},
            onRetry = {},
            nowMs = NOW_MS,
        )
    }

    @Test
    fun filesToolsSheetMenu() = render("i2762-files-tools-sheet") {
        FileToolsSheetContent(
            path = "/home/alexey/projects/pocketshell",
            onUpload = {},
            onCreateFolder = {},
            onNewTextFile = {},
            onOpenTransfers = {},
            onDismiss = {},
        )
    }

    private fun listing(): List<SftpEntry> = listOf(
        SftpEntry(
            path = "/home/alexey/projects/pocketshell/app2",
            isDirectory = true,
            sizeBytes = 4096,
            modifiedEpochMs = NOW_MS - 3L * 60L * 60L * 1000L,
        ),
        SftpEntry(
            path = "/home/alexey/projects/pocketshell/docs",
            isDirectory = true,
            sizeBytes = 4096,
            modifiedEpochMs = NOW_MS - 2L * 24L * 60L * 60L * 1000L,
        ),
        SftpEntry(
            path = "/home/alexey/projects/pocketshell/README.md",
            isDirectory = false,
            sizeBytes = 48_213,
            modifiedEpochMs = NOW_MS - 26L * 60L * 60L * 1000L,
        ),
        SftpEntry(
            path = "/home/alexey/projects/pocketshell/deploy.sh",
            isDirectory = false,
            sizeBytes = 1_204,
            modifiedEpochMs = NOW_MS - 5L * 24L * 60L * 60L * 1000L,
        ),
        SftpEntry(
            path = "/home/alexey/projects/pocketshell/session-trace.log",
            isDirectory = false,
            sizeBytes = 912_884,
            modifiedEpochMs = NOW_MS - 40L * 60L * 1000L,
        ),
    )

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
