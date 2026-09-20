package com.pocketshell.next.render

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.junit4.createComposeRule
import com.pocketshell.core.hostapi.SessionRow
import com.pocketshell.core.hostapi.WorkspaceMembership
import com.pocketshell.next.tree.SessionTreeUiState
import com.pocketshell.next.workspaces.HostWorkspacesScreen
import com.pocketshell.next.workspaces.HostWorkspacesUiState
import com.pocketshell.next.workspaces.RegisteredWorkspaceRoot
import com.pocketshell.next.workspaces.WorkspaceProjection
import com.pocketshell.next.workspaces.WorkspaceRootProjection
import com.pocketshell.next.workspaces.WorkspaceScreen
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

/** Production composable renders for the Quiet host/workspace projection. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class QuietWorkspaceRenders {

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
    @Config(qualifiers = "w360dp-h915dp-night-xxhdpi")
    fun hostWorkspaces360() = render("i2607-host-workspaces-360") {
        HostWorkspacesScreen(
            state = hostState(),
            onRefresh = {},
            onOpenWorkspace = {},
            onOpenSession = {},
        )
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
    fun hostWorkspaces412() = render("i2607-host-workspaces-412") {
        HostWorkspacesScreen(
            state = hostState(),
            onRefresh = {},
            onOpenWorkspace = {},
            onOpenSession = {},
        )
    }

    @Test
    @Config(qualifiers = "w600dp-h915dp-night-xxhdpi")
    fun hostWorkspaces600() = render("i2607-host-workspaces-600") {
        HostWorkspacesScreen(
            state = hostState(),
            onRefresh = {},
            onOpenWorkspace = {},
            onOpenSession = {},
        )
    }

    /**
     * Issue #2808 D-4: the same screen on a host big enough to earn the search
     * field. The four-workspace captures above show the gated-off state; this
     * one is the other side of WORKSPACE_SEARCH_MIN_WORKSPACES, so the pair
     * can be looked at together.
     */
    @Test
    @Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
    fun hostWorkspaces412Searchable() = render("i2808-host-workspaces-412-searchable") {
        HostWorkspacesScreen(
            state = manyWorkspacesState(),
            onRefresh = {},
            onOpenWorkspace = {},
            onOpenSession = {},
        )
    }

    @Test
    fun emptyWorkspaceDetail() = render("i2607-empty-workspace-detail") {
        WorkspaceScreen(state = workspaceState("/home/alexey/git/empty"), onRefresh = {}, onOpenSession = { _, _ -> })
    }

    @Test
    fun populatedWorkspaceDetail() = render("i2607-populated-workspace-detail") {
        WorkspaceScreen(
            state = workspaceState(
                path = "/home/alexey/git/pocketshell",
                names = listOf("shell", "agent-review"),
            ),
            onRefresh = {},
            onOpenSession = { _, _ -> },
        )
    }

    @Test
    fun populatedWorkspaceDetailFontScale13() = render(
        name = "i2607-populated-workspace-detail-font-scale-13",
        fontScale = 1.3f,
    ) {
        WorkspaceScreen(
            state = workspaceState(
                path = "/home/alexey/git/pocketshell/feature-with-a-long-name",
                names = listOf("shell", "agent-review"),
            ),
            onRefresh = {},
            onOpenSession = { _, _ -> },
        )
    }

    @Test
    fun populatedWorkspaceDetailFontScale20() = render(
        name = "i2607-populated-workspace-detail-font-scale-20",
        fontScale = 2.0f,
    ) {
        WorkspaceScreen(
            state = workspaceState(
                path = "/home/alexey/git/pocketshell/feature-with-a-long-name",
                names = listOf("shell", "agent-review"),
            ),
            onRefresh = {},
            onOpenSession = { _, _ -> },
        )
    }

    private fun hostState(): HostWorkspacesUiState {
        val roots = projectWorkspaceRoots(
            sessions = listOf(session("root-shell", "/home/alexey/git")),
            memberships = listOf(
                WorkspaceMembership("/home/alexey/git/pocketshell", "~/git/pocketshell"),
                WorkspaceMembership("/home/alexey/git/empty", "~/git/empty"),
                WorkspaceMembership("/home/alexey/work/mobile", "~/work/mobile"),
            ),
            registeredRoots = listOf(
                RegisteredWorkspaceRoot("/home/alexey/git", "Git", 1L),
                RegisteredWorkspaceRoot("/home/alexey/work", "Work", 2L),
            ),
        )
        return HostWorkspacesUiState(
            hostId = 7,
            hostLabel = "hetzner",
            loaded = true,
            roots = roots,
        )
    }

    /** A host at [com.pocketshell.next.workspaces.WORKSPACE_SEARCH_MIN_WORKSPACES]. */
    private fun manyWorkspacesState(): HostWorkspacesUiState {
        val roots = projectWorkspaceRoots(
            sessions = emptyList(),
            memberships = (1..8).map { index ->
                WorkspaceMembership("/home/alexey/git/project-$index", "~/git/project-$index")
            },
            registeredRoots = listOf(RegisteredWorkspaceRoot("/home/alexey/git", "Git", 1L)),
        )
        return HostWorkspacesUiState(
            hostId = 7,
            hostLabel = "hetzner",
            loaded = true,
            roots = roots,
        )
    }

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

    private fun workspaceState(path: String, names: List<String> = emptyList()): SessionTreeUiState =
        SessionTreeUiState(
            hostId = 7,
            workspacePath = path,
            loaded = true,
            workspaceSessions = names.map { session(it, path) },
        )

    private fun render(
        name: String,
        fontScale: Float? = null,
        content: @Composable () -> Unit,
    ) {
        composeRule.captureFrozenRender("build/renders/$name.png") {
            PocketShellTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = PocketShellColors.Background,
                ) {
                    if (fontScale == null) {
                        content()
                    } else {
                        val density = LocalDensity.current
                        CompositionLocalProvider(
                            LocalDensity provides Density(density.density, fontScale),
                        ) {
                            content()
                        }
                    }
                }
            }
        }
    }
}
