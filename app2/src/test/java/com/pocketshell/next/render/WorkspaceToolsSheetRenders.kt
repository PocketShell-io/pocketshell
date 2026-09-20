package com.pocketshell.next.render

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import com.pocketshell.next.workspaces.HostToolsSheetContent
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
 * Fresh Quiet render for the host tools sheet (#2635 R5) — the Workspaces
 * screen's overflow sheet with its seven real rows. Run with:
 *
 * ./gradlew :app2:testDebugUnitTest --tests '*WorkspaceToolsSheetRenders*' --rerun-tasks
 *
 * Composes [HostToolsSheetContent], the production sheet body extracted from
 * its modal container the same way [com.pocketshell.next.files.FileToolsSheetContent]
 * and [com.pocketshell.next.workspaces.RootActionsSheetContent] were, so the
 * capture shows the real rows without Robolectric's modal window.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class WorkspaceToolsSheetRenders {

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
    fun workspaceToolsSheetRows() = render("i2762-workspace-tools-sheet") {
        HostToolsSheetContent(
            hostLabel = "hetzner",
        )
    }

    /**
     * Issue #2808 D-4: the same sheet on a host too small to pin the search
     * field, where it grows the "Find a workspace" row that replaces it.
     */
    @Test
    fun workspaceToolsSheetWithFind() = render("i2808-workspace-tools-sheet-find") {
        HostToolsSheetContent(
            hostLabel = "hetzner",
            showFindWorkspace = true,
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
