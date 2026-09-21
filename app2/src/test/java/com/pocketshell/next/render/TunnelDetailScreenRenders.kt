package com.pocketshell.next.render

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import com.pocketshell.next.ports.TunnelDetailScreen
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
 * Fresh Quiet renders for the tunnel detail page (#2636 D17, destination
 * TunnelDetail). Run with:
 *
 * ./gradlew :app2:testDebugUnitTest --tests '*TunnelDetailScreenRenders*' --rerun-tasks
 *
 * Both captures call the real shared [TunnelDetailScreen] with the
 * [TunnelDisplay] mirror (the D11 seam): an active discovered tunnel with a
 * verified HTTP service, and the disappeared-tunnel empty state. The app-side
 * route that resolves the tunnel from the view model and owns the clipboard
 * and stop/remove actions stays unexercised here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class TunnelDetailScreenRenders {

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
    fun tunnelDetailForwardingVerified() = render("i2636-tunnel-detail-forwarding") {
        TunnelDetailScreen(
            hostName = "hetzner",
            tunnel = TunnelDisplay(
                remotePort = 5173,
                localPort = 35173,
                process = "vite",
                status = TunnelStatusDisplay.FORWARDING,
                bytesIn = 148_912,
                bytesOut = 2_310_554,
            ),
            verifiedUrl = "http://127.0.0.1:35173",
            onBack = {},
            onCopyAddress = {},
            onStop = {},
            onOpenBrowser = {},
        )
    }

    @Test
    fun tunnelDetailUnavailable() = render("i2636-tunnel-detail-unavailable") {
        TunnelDetailScreen(
            hostName = "hetzner",
            tunnel = null,
            onBack = {},
            onCopyAddress = {},
            onStop = {},
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
