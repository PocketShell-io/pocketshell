package com.pocketshell.next.render

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import com.pocketshell.core.hostapi.EngineInfo
import com.pocketshell.core.hostapi.ProfileInfo
import com.pocketshell.next.tree.CreateSessionSheetContent
import com.pocketshell.next.tree.CreateSessionState
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
 * Fresh Quiet render for the WorkspaceStart destination (#2636 D17): the
 * workspace route that opens the new-session sheet on entry. Run with:
 *
 * ./gradlew :app2:testDebugUnitTest --tests '*WorkspaceStartRenders*' --rerun-tasks
 *
 * The capture composes [CreateSessionSheetContent] — the production sheet
 * body, split from its ModalBottomSheet container by the app precisely so a
 * JVM capture can see it (the same sanctioned split
 * TerminalActionsSheetRenders and WorkspaceToolsSheetRenders use; a modal
 * sheet's popup window does not appear in a Roborazzi root capture). The
 * workspace list beneath the sheet is the destination already covered by
 * QuietWorkspaceRenders; the sheet is the part unique to WorkspaceStart.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class WorkspaceStartRenders {

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
    fun workspaceStartCreateSessionSheet() = render("i2636-workspace-start-create-sheet") {
        CreateSessionSheetContent(
            state = CreateSessionState(
                visible = true,
                engines = listOf(
                    engine("claude"),
                    engine("codex"),
                ),
                profiles = listOf(
                    ProfileInfo(
                        name = "default",
                        engine = "claude",
                        configDir = null,
                        isDefault = true,
                    ),
                ),
            ),
            defaultFolder = "/home/alexey/git/pocketshell",
            onSubmit = {},
            onCancel = {},
            existingSessionNames = listOf("shell"),
            onRefreshEngines = {},
        )
    }

    /** A healthy host engine row, the shape `engines list --json` reports. */
    private fun engine(id: String) = EngineInfo(
        id = id,
        label = id.replaceFirstChar { it.uppercase() },
        family = id,
        harness = id,
        providerMark = "",
        usageProvider = null,
        enabled = true,
        available = true,
        availableForCreate = true,
        unavailableReason = null,
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
