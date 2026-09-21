package com.pocketshell.next.render

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import com.pocketshell.next.ports.AddTunnelScreen
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
 * Fresh Quiet renders for the manual tunnel form (#2636 D17, destination
 * AddTunnel). Run with:
 *
 * ./gradlew :app2:testDebugUnitTest --tests '*AddTunnelScreenRenders*' --rerun-tasks
 *
 * Both captures call the real shared [AddTunnelScreen]: the blank form opened
 * from the Services page, and the form while a local-port collision blocks
 * submission. The app-side route that owns the field state and the collision
 * check against the view model stays unexercised here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class AddTunnelScreenRenders {

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
    fun addTunnelBlankForm() = render("i2636-add-tunnel-blank-form") {
        AddTunnelScreen(
            name = "",
            remotePort = "",
            localPort = "",
            valid = false,
            onNameChange = {},
            onRemotePortChange = {},
            onLocalPortChange = {},
            onSubmit = {},
            onBack = {},
        )
    }

    @Test
    fun addTunnelLocalPortCollision() = render("i2636-add-tunnel-port-collision") {
        AddTunnelScreen(
            name = "grafana",
            remotePort = "3000",
            localPort = "3000",
            valid = false,
            localPortCollision = "Port 3000 is already used on this device",
            onNameChange = {},
            onRemotePortChange = {},
            onLocalPortChange = {},
            onSubmit = {},
            onBack = {},
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
