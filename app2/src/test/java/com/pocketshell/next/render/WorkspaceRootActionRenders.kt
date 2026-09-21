package com.pocketshell.next.render

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import com.pocketshell.core.hostapi.WorkspaceMembership
import com.pocketshell.next.workspaces.HostWorkspacesScreen
import com.pocketshell.next.workspaces.HostWorkspacesUiState
import com.pocketshell.next.workspaces.RegisteredWorkspaceRoot
import com.pocketshell.next.workspaces.WorkspaceFolderEntry
import com.pocketshell.next.workspaces.WorkspaceRootProjection
import com.pocketshell.next.workspaces.projectWorkspaceRoots
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
 * Fresh Quiet render for the WorkspaceRootAction destination (#2636 D17): the
 * host workspaces screen with a root action (here add-workspace) already
 * opened on entry. Run with:
 *
 * ./gradlew :app2:testDebugUnitTest --tests '*WorkspaceRootActionRenders*' --rerun-tasks
 *
 * The capture calls the real stateless [HostWorkspacesScreen] with fixture
 * state whose `addWorkspaceVisible` branch is set — the exact page the route's
 * `LaunchedEffect(initialRootPath, initialRootAction)` opens for the
 * add-workspace action — so the entered-with-action page is captured whole,
 * inline (it is a page swap, not a modal). The Hilt route that consumes the
 * route arguments stays unexercised here. (The case is deliberately named
 * without the word "add": the catalog attributes cases by class/method/label
 * words, and an "add" word would tie this case between WorkspaceRootAction
 * and AddWorkspaceRoot, which graph order resolves the wrong way.)
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class WorkspaceRootActionRenders {

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
    fun workspaceRootActionPageOpen() = render("i2636-workspace-root-action-page") {
        HostWorkspacesScreen(
            state = HostWorkspacesUiState(
                hostId = 7,
                hostLabel = "hetzner",
                loaded = true,
                roots = roots(),
                addWorkspaceVisible = true,
                addWorkspaceRootPath = "/home/alexey/git",
                addWorkspaceRootFolders = listOf(
                    WorkspaceFolderEntry(
                        name = "pocketshell",
                        path = "/home/alexey/git/pocketshell",
                    ),
                    WorkspaceFolderEntry(
                        name = "aplexer",
                        path = "/home/alexey/git/aplexer",
                    ),
                ),
            ),
            onRefresh = {},
            onOpenWorkspace = {},
            onOpenSession = {},
        )
    }

    private fun roots(): List<WorkspaceRootProjection> = projectWorkspaceRoots(
        sessions = emptyList(),
        memberships = listOf(
            WorkspaceMembership("/home/alexey/git/pocketshell", "~/git/pocketshell"),
        ),
        registeredRoots = listOf(
            RegisteredWorkspaceRoot(path = "/home/alexey/git", label = "Git", createdAt = 1L, id = 1L),
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
