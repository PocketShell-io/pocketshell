package com.pocketshell.next.render

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import com.pocketshell.next.ports.ConnectionStateDisplay
import com.pocketshell.next.ports.PortForwardDisplayState
import com.pocketshell.next.ports.ServicesScreen
import com.pocketshell.next.ports.TunnelDisplay
import com.pocketshell.next.ports.TunnelStatusDisplay
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

/** Width anchors for the real Services & tunnels screen. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h800dp-night-xxhdpi")
class ServicesScreen360Renders {

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
    fun servicesAt360() = render("services-360-active") {
        ServicesScreen(
            state = servicesState(),
            onBack = {},
            onSetDiscovery = {},
            onOpenTunnel = {},
            onAddTunnel = {},
        )
    }

    private fun render(name: String, content: @Composable () -> Unit) {
        composeRule.captureFrozenRender("build/renders/$name.png") {
            PocketShellTheme {
                Surface(Modifier.fillMaxSize(), color = PocketShellColors.Background) {
                    content()
                }
            }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w600dp-h915dp-night-xxhdpi")
class ServicesScreen600Renders {

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
    fun servicesAt600() = render("services-600-active") {
        ServicesScreen(
            state = servicesState(),
            onBack = {},
            onSetDiscovery = {},
            onOpenTunnel = {},
            onAddTunnel = {},
        )
    }

    private fun render(name: String, content: @Composable () -> Unit) {
        composeRule.captureFrozenRender("build/renders/$name.png") {
            PocketShellTheme {
                Surface(Modifier.fillMaxSize(), color = PocketShellColors.Background) {
                    content()
                }
            }
        }
    }
}

private fun servicesState() = PortForwardDisplayState(
    hostName = "hetzner",
    hostSubtitle = "alexey@hetzner:22",
    enabled = true,
    connection = ConnectionStateDisplay.Connected,
    rows = listOf(
        TunnelDisplay(5173, 35173, "vite", TunnelStatusDisplay.FORWARDING),
        TunnelDisplay(8000, 8000, "python", TunnelStatusDisplay.AVAILABLE),
    ),
    loading = false,
)
