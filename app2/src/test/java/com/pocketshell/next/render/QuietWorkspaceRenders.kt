package com.pocketshell.next.render

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.pocketshell.core.hostapi.SessionRow
import com.pocketshell.core.hostapi.WorkspaceMembership
import com.pocketshell.next.tree.CreateSessionState
import com.pocketshell.next.tree.SessionTreeUiState
import com.pocketshell.next.workspaces.HostWorkspacesScreen
import com.pocketshell.next.workspaces.HostWorkspacesUiState
import com.pocketshell.next.workspaces.RegisteredWorkspaceRoot
import com.pocketshell.next.workspaces.WorkspaceActionsSheetContent
import com.pocketshell.next.workspaces.WorkspaceProjection
import com.pocketshell.next.workspaces.WorkspaceRootProjection
import com.pocketshell.next.workspaces.WorkspaceStartScreen
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

    @Test
    fun workspaceStartEmpty() = render("i2721-workspace-start-empty") {
        WorkspaceStartScreen(
            state = workspaceState("/home/alexey/git/empty"),
            onOpenSession = { _, _ -> },
        )
    }

    @Test
    fun workspaceStartNotice() = render("i2721-workspace-start-notice") {
        // The idempotent-create notice: the found session was opened, nothing
        // was made — issue #2721's behaviour note made visible.
        WorkspaceStartScreen(
            state = workspaceState("/home/alexey/git/pocketshell").copy(
                create = CreateSessionState(
                    notice = "Session \"pocketshell:main\" already existed — " +
                        "nothing new was created; opened it.",
                ),
            ),
            onOpenSession = { _, _ -> },
        )
    }

    @Test
    fun workspaceStartFontScale20() = render(
        name = "i2721-workspace-start-font-scale-20",
        fontScale = 2.0f,
    ) {
        WorkspaceStartScreen(
            state = workspaceState("/home/alexey/git/pocketshell/feature-with-a-long-name"),
            onOpenSession = { _, _ -> },
        )
    }

    @Test
    fun workspaceLongPressActions() = render("i2721-workspace-long-press-actions") {
        WorkspaceActionsSheetContent(
            workspace = WorkspaceProjection(
                path = "/home/alexey/git/pocketshell",
                label = "pocketshell",
                displayPath = "~/git/pocketshell",
                sessions = emptyList(),
                durable = true,
            ),
            onNewSession = {},
            onBrowseFiles = {},
            onCopyPath = {},
            onReorder = {},
            onRemove = {},
            onDismiss = {},
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
        captureRoboImage("build/renders/$name.png") {
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
