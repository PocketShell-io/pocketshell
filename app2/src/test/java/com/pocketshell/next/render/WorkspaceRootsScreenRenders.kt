package com.pocketshell.next.render

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import com.pocketshell.next.settings.WorkspaceRootRow
import com.pocketshell.next.settings.WorkspaceRootsScreen
import com.pocketshell.next.settings.WorkspaceRootsUiState
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
 * Fresh Quiet renders for the per-host project-root manager (#2636 D17,
 * destination WorkspaceRoots). Run with:
 *
 * ./gradlew :app2:testDebugUnitTest --tests '*WorkspaceRootsScreenRenders*' --rerun-tasks
 *
 * Both captures call the real [WorkspaceRootsScreen] with fixture-only
 * [WorkspaceRootsUiState]: the populated root list (rows open the per-root
 * action sheet on tap) and the first-run empty state above the add action.
 * The Hilt route/view model pair stays unexercised here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class WorkspaceRootsScreenRenders {

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
    fun workspaceRootsPopulated() = render("i2636-workspace-roots-populated") {
        WorkspaceRootsScreen(
            state = WorkspaceRootsUiState(
                hostName = "hetzner",
                loaded = true,
                roots = listOf(
                    WorkspaceRootRow(
                        id = 1,
                        label = "Git",
                        path = "/home/alexey/git",
                        workspaceCount = 4,
                    ),
                    WorkspaceRootRow(
                        id = 2,
                        label = "",
                        path = "/srv/work",
                        workspaceCount = 1,
                    ),
                ),
            ),
            onBack = {},
            onDeleteRoot = {},
            onOpenAddRoot = {},
            onRootAction = { _, _ -> },
        )
    }

    @Test
    fun workspaceRootsEmpty() = render("i2636-workspace-roots-empty") {
        WorkspaceRootsScreen(
            state = WorkspaceRootsUiState(
                hostName = "hetzner",
                loaded = true,
                roots = emptyList(),
            ),
            onBack = {},
            onDeleteRoot = {},
            onOpenAddRoot = {},
            onRootAction = { _, _ -> },
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
