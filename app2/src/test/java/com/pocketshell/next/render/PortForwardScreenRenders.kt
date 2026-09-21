package com.pocketshell.next.render

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import com.pocketshell.next.ports.ConnectionStateDisplay
import com.pocketshell.next.ports.PortForwardDisplayState
import com.pocketshell.next.ports.PortForwardScreen
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

/**
 * Fast design renders for the port-forward screen's connection states, in the
 * same shape as [HostScreenRenders] (issue #555's harness, app2 half).
 *
 * They exist for issue #2491: a host that has STOPPED retrying has to look
 * different from one that is still trying, and "different" is a thing you look
 * at, not a thing an assertion settles. The behaviour itself is asserted in
 * `PortForwardScreenTest`; nothing here is the only check on anything.
 *
 * ```
 * ./gradlew :app2:testDebugUnitTest --tests '*PortForwardScreenRenders*' --rerun-tasks
 * # then open the PNGs under app2/build/renders/
 * ```
 */
/**
 * The needs-trust banner text, fixture-locally (#2636 C1) rather than through
 * the ports controller's production const. Byte-identical on purpose; the real
 * string stays pinned against the controller by `PortForwardScreenTest` /
 * the controller's own unit test, so a production copy change that ignores
 * this mirror still fails the suite.
 */
private const val NEEDS_TRUST_ATTENTION: String =
    "This host's key needs confirming. Open it from the host list and accept the key."

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class PortForwardScreenRenders {

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

    /** Terminal: the host key is unconfirmed, so nothing more will be tried. */
    @Test
    fun portForwardNeedsAttention() = render("i2491-port-forward-needs-attention") {
        PortForwardScreen(
            state = state(
                connection = ConnectionStateDisplay.Lost,
                attention = NEEDS_TRUST_ATTENTION,
            ),
            onSetEnabled = {},
            onTogglePort = {},
            onSetShowAllPorts = {},
        )
    }

    /** Transient: the dial keeps retrying on its own, so the screen says so. */
    @Test
    fun portForwardReconnecting() = render("i2491-port-forward-reconnecting") {
        PortForwardScreen(
            state = state(connection = ConnectionStateDisplay.Reconnecting),
            onSetEnabled = {},
            onTogglePort = {},
            onSetShowAllPorts = {},
        )
    }

    /** Healthy, for the side-by-side comparison. */
    @Test
    fun portForwardConnected() = render("i2491-port-forward-connected") {
        PortForwardScreen(
            state = state(
                connection = ConnectionStateDisplay.Connected,
                rows = listOf(
                    TunnelDisplay(
                        remotePort = 3_000,
                        localPort = 3_000,
                        process = "vite",
                        status = TunnelStatusDisplay.FORWARDING,
                        bytesIn = 2_048,
                        bytesOut = 8_192,
                    ),
                ),
            ),
            onSetEnabled = {},
            onTogglePort = {},
            onSetShowAllPorts = {},
        )
    }

    private fun state(
        connection: ConnectionStateDisplay,
        attention: String? = null,
        rows: List<TunnelDisplay> = emptyList(),
    ) = PortForwardDisplayState(
        hostName = "rmthz",
        hostSubtitle = "alexey@135.181.114.209:22",
        enabled = true,
        connection = connection,
        attention = attention,
        rows = rows,
        loading = false,
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
