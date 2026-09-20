package com.pocketshell.next.tree

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.next.AppNavHost
import com.pocketshell.next.connect.TestConnectStack
import com.pocketshell.next.nav.Destination
import com.pocketshell.next.ports.PortForwardUiState
import com.pocketshell.next.ports.SERVICES_SCREEN_TAG
import com.pocketshell.next.ports.SERVICES_BACK_TAG
import com.pocketshell.next.ports.ServicesScreen
import com.pocketshell.next.usage.USAGE_SCREEN_TAG
import com.pocketshell.next.usage.UsageScreen
import com.pocketshell.next.usage.UsageScreenState
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #2532: the Workspaces (`Destination.Tree` — the legacy session-tree
 * name) and Services seams actually pop / open Usage. Screen tests prove the
 * live quiet screens fire the callbacks ([QuietWorkspaceScreenTest] for the
 * real ones); this suite proves [AppNavHost] wired those callbacks to
 * `popBackStack` / the host-scoped Usage destination. The composed screens
 * are deliberate stand-ins — the unreachable tree screen that used to sit
 * here was deleted (#2726) — because the wiring under test lives in
 * [AppNavHost], not in any screen. QuietWorkspaceScreenTest covers the real
 * screens' callbacks.
 */
@RunWith(AndroidJUnit4::class)
class SessionTreeNavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val stack = TestConnectStack()

    @After
    fun tearDown() {
        stack.close()
    }

    @Test
    fun `tree Back pops to Hosts`() {
        val nav = setContentWithNav()
        composeRule.runOnUiThread { nav.navigate(Destination.Tree.route(hostId = 7)) }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(STAND_IN_BACK_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(STAND_IN_BACK_TAG).performClick()
        composeRule.waitForIdle()

        assertEquals(Destination.Hosts.pattern, nav.currentBackStackEntry?.destination?.route)
        composeRule.onNodeWithText("Hosts").assertIsDisplayed()
    }

    @Test
    fun `tree Usage opens the usage panel`() {
        val nav = setContentWithNav()
        composeRule.runOnUiThread { nav.navigate(Destination.Tree.route(hostId = 7)) }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(STAND_IN_USAGE_TAG).performClick()
        composeRule.waitForIdle()

        assertEquals(Destination.HostUsage.pattern, nav.currentBackStackEntry?.destination?.route)
        composeRule.onNodeWithTag(USAGE_SCREEN_TAG).assertIsDisplayed()
    }

    @Test
    fun `ports Back pops to the tree`() {
        val nav = setContentWithNav()
        composeRule.runOnUiThread { nav.navigate(Destination.Tree.route(hostId = 7)) }
        composeRule.waitForIdle()
        composeRule.runOnUiThread { nav.navigate(Destination.Ports.route(hostId = 7)) }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SERVICES_SCREEN_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SERVICES_BACK_TAG).performClick()
        composeRule.waitForIdle()

        assertEquals(Destination.Tree.pattern, nav.currentBackStackEntry?.destination?.route)
        composeRule.onNodeWithText(STAND_IN_WORKSPACES_TEXT).assertIsDisplayed()
    }

    private companion object {
        const val STAND_IN_WORKSPACES_TEXT = "Workspaces stand-in"
        const val STAND_IN_BACK_TAG = "nav-test-workspaces-back"
        const val STAND_IN_USAGE_TAG = "nav-test-workspaces-usage"
    }

    private fun setContentWithNav(): NavHostController {
        lateinit var controller: NavHostController
        composeRule.setContent {
            controller = rememberNavController()
            PocketShellTheme {
                AppNavHost(
                    navController = controller,
                    hostsScreen = { Text("Hosts") },
                    connectViewModel = { stack.viewModel },
                    workspacesScreen = { _, actions, _ ->
                        Column {
                            Text(STAND_IN_WORKSPACES_TEXT)
                            Button(
                                onClick = actions.onBack,
                                modifier = Modifier.testTag(STAND_IN_BACK_TAG),
                            ) { Text("Back") }
                            Button(
                                onClick = actions.onOpenUsage,
                                modifier = Modifier.testTag(STAND_IN_USAGE_TAG),
                            ) { Text("Usage") }
                        }
                    },
                    servicesScreen = { onBack, _, _ ->
                        ServicesScreen(
                            state = PortForwardUiState(hostId = 7, hostName = "rmthz"),
                            onSetDiscovery = {},
                            onOpenTunnel = {},
                            onAddTunnel = {},
                            onBack = onBack,
                        )
                    },
                    usageScreen = { onBack ->
                        UsageScreen(
                            state = UsageScreenState(),
                            onBack = onBack,
                            onRefresh = {},
                        )
                    },
                    hostUsageScreen = { _, onBack ->
                        UsageScreen(
                            state = UsageScreenState(),
                            onBack = onBack,
                            onRefresh = {},
                        )
                    },
                )
            }
        }
        composeRule.waitForIdle()
        return controller
    }
}
