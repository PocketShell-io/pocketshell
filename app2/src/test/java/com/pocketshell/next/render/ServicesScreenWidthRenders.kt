package com.pocketshell.next.render

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.takahirom.roborazzi.captureRoboImage
import com.pocketshell.core.portfwd.AutoForwarderSupervisor.ConnectionState
import com.pocketshell.core.portfwd.TunnelInfo
import com.pocketshell.next.ports.PortForwardUiState
import com.pocketshell.next.ports.ServicesScreen
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
        captureRoboImage("build/renders/$name.png") {
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
        captureRoboImage("build/renders/$name.png") {
            PocketShellTheme {
                Surface(Modifier.fillMaxSize(), color = PocketShellColors.Background) {
                    content()
                }
            }
        }
    }
}

private fun servicesState() = PortForwardUiState(
    hostId = 1,
    hostName = "hetzner",
    hostSubtitle = "alexey@hetzner:22",
    enabled = true,
    connection = ConnectionState.Connected,
    rows = listOf(
        TunnelInfo(5173, 35173, "vite", TunnelInfo.Status.FORWARDING),
        TunnelInfo(8000, 8000, "python", TunnelInfo.Status.AVAILABLE),
    ),
    loading = false,
)
