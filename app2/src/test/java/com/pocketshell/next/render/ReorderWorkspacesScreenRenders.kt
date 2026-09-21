package com.pocketshell.next.render

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import com.pocketshell.core.hostapi.SessionRow
import com.pocketshell.core.hostapi.WorkspaceMembership
import com.pocketshell.next.workspaces.HostWorkspacesUiState
import com.pocketshell.next.workspaces.ReorderWorkspacesScreen
import com.pocketshell.next.workspaces.RegisteredWorkspaceRoot
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
 * Fresh Quiet renders for the persistent ordering page (#2636 D17,
 * destination ReorderWorkspaces). Run with:
 *
 * ./gradlew :app2:testDebugUnitTest --tests '*ReorderWorkspacesScreenRenders*' --rerun-tasks
 *
 * Both captures call the real [ReorderWorkspacesScreen] with fixture-only
 * [HostWorkspacesUiState]: the populated two-root order with per-row 48dp move
 * controls, and the no-saved-roots state that explains the way out. The Hilt
 * route (and its ON_START refresh) stays unexercised here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class ReorderWorkspacesScreenRenders {

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
    fun reorderWorkspacesPopulated() = render("i2636-reorder-workspaces-populated") {
        ReorderWorkspacesScreen(
            state = HostWorkspacesUiState(
                hostId = 7,
                hostLabel = "hetzner",
                loaded = true,
                roots = roots(),
            ),
            onBack = {},
            onMoveRoot = { _, _ -> },
            onMoveWorkspace = { _, _, _ -> },
        )
    }

    @Test
    fun reorderWorkspacesNoSavedRoots() = render("i2636-reorder-workspaces-no-roots") {
        ReorderWorkspacesScreen(
            state = HostWorkspacesUiState(
                hostId = 7,
                hostLabel = "hetzner",
                loaded = true,
                roots = emptyList(),
            ),
            onBack = {},
            onMoveRoot = { _, _ -> },
            onMoveWorkspace = { _, _, _ -> },
        )
    }

    /** Two saved roots, the first with workspaces and the second empty. */
    private fun roots(): List<WorkspaceRootProjection> = projectWorkspaceRoots(
        sessions = listOf(session("root-shell", "/home/alexey/git")),
        memberships = listOf(
            WorkspaceMembership("/home/alexey/git/pocketshell", "~/git/pocketshell"),
            WorkspaceMembership("/home/alexey/git/empty", "~/git/empty"),
        ),
        registeredRoots = listOf(
            // id > 0 is what makes a projection a REGISTERED root; the reorder
            // page lists only those (Room row identities, unlike the host
            // screen's inferred sections).
            RegisteredWorkspaceRoot(path = "/home/alexey/git", label = "Git", createdAt = 1L, id = 1L),
            RegisteredWorkspaceRoot(path = "/home/alexey/work", label = "Work", createdAt = 2L, id = 2L),
        ),
    )

    private fun session(name: String, workspace: String): SessionRow = SessionRow(
        name = name,
        id = null,
        workspace = workspace,
        tag = null,
        engine = null,
        profile = null,
        agent = null,
        agentState = null,
        agentStateSource = null,
        attached = true,
        createdEpoch = 1L,
        activityEpoch = null,
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
