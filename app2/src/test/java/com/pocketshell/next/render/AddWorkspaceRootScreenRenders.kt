package com.pocketshell.next.render

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import com.pocketshell.next.settings.AddWorkspaceRootScreen
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
 * Fresh Quiet renders for the focused root-registration form (#2636 D17,
 * destination AddWorkspaceRoot). Run with:
 *
 * ./gradlew :app2:testDebugUnitTest --tests '*AddWorkspaceRootScreenRenders*' --rerun-tasks
 *
 * Both captures call the real [AddWorkspaceRootScreen] with fixture-only
 * [WorkspaceRootsUiState]: the blank form as opened, and the form while a
 * failed registration shows its error banner. The form fields are the
 * screen's own `rememberSaveable` state (they start empty on a fresh
 * composition, exactly like a freshly navigated route). The Hilt route stays
 * unexercised here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class AddWorkspaceRootScreenRenders {

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
    fun addWorkspaceRootBlankForm() = render("i2636-add-workspace-root-blank") {
        AddWorkspaceRootScreen(
            state = WorkspaceRootsUiState(hostName = "hetzner", loaded = true),
            onBack = {},
            onAddRoot = { _, _ -> },
        )
    }

    @Test
    fun addWorkspaceRootFailure() = render("i2636-add-workspace-root-failure") {
        AddWorkspaceRootScreen(
            state = WorkspaceRootsUiState(
                hostName = "hetzner",
                loaded = true,
                failure = "Host check failed: ~/projects does not exist",
            ),
            onBack = {},
            onAddRoot = { _, _ -> },
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
