package com.pocketshell.next.ports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.EmptyState
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellSpacing

const val TUNNEL_DETAIL_TAG = "tunnel_detail"
const val TUNNEL_COPY_ADDRESS_TAG = "tunnel_copy_address"
const val TUNNEL_STOP_TAG = "tunnel_stop"
const val TUNNEL_OPEN_BROWSER_TAG = "tunnel_open_browser"

/**
 * The tunnel detail page, moved to the shared presentation module (#2636 D11).
 *
 * Stateless: it paints a single [TunnelDisplay] mirror (null when the tunnel
 * has disappeared from discovery). The route that resolves the tunnel from the
 * view model state, owns the clipboard/Context handoff and stops or removes
 * the tunnel stays in app2 (`app2/.../ports/TunnelDetailRoute.kt`) — the D10
 * usage-seam shape with the route file named for the route it holds (a
 * same-named `TunnelDetailScreen.kt` on both sides of the seam would collide
 * on the `TunnelDetailScreenKt` JVM facade). The route's former inline
 * `Dispatchers`/`launch`/`withContext` work stays with it: this module
 * deliberately declares no coroutines dependency.
 */
@Composable
fun TunnelDetailScreen(
    hostName: String,
    tunnel: TunnelDisplay?,
    manual: Boolean = false,
    manualName: String? = null,
    onBack: () -> Unit,
    onCopyAddress: (Int) -> Unit,
    onStop: () -> Unit,
    verifiedUrl: String? = null,
    onOpenBrowser: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background)
            .testTag(TUNNEL_DETAIL_TAG),
    ) {
        ScreenHeader(
            title = manualName?.trim().takeUnless { it.isNullOrEmpty() }
                ?: tunnel?.process?.ifBlank { "Port ${tunnel.remotePort}" }
                ?: "Tunnel",
            subtitle = hostName,
            onBack = onBack,
        )
        if (tunnel == null) {
            EmptyState(
                title = "Tunnel unavailable",
                description = "This service is no longer active on the host.",
                modifier = Modifier.fillMaxSize(),
            )
            return@Column
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(PocketShellSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
        ) {
            // The forwarder targets the service's loopback listener on the host;
            // printing the host label here would imply a different remote bind.
            TunnelDetailRow("Remote", "127.0.0.1:${tunnel.remotePort}")
            TunnelDetailRow("On this phone", "127.0.0.1:${tunnel.localPort}")
            if (manual) {
                TunnelDetailRow("Name", manualName?.ifBlank { "Port ${tunnel.remotePort}" } ?: "Port ${tunnel.remotePort}")
                TunnelDetailRow("Mode", "Manual tunnel")
            }
            TunnelDetailRow("State", tunnel.status.detailStatusLabel)
            TunnelDetailRow("Traffic", "${formatBytes(tunnel.bytesIn + tunnel.bytesOut)} total")
            if (verifiedUrl != null) {
                PocketShellButton(
                    text = "Open in browser",
                    onClick = { onOpenBrowser(verifiedUrl) },
                    variant = ButtonVariant.Primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TUNNEL_OPEN_BROWSER_TAG),
                )
            }
            PocketShellButton(
                text = "Copy local address",
                onClick = { onCopyAddress(tunnel.localPort) },
                variant = ButtonVariant.Secondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TUNNEL_COPY_ADDRESS_TAG),
            )
            PocketShellButton(
                text = if (manual) "Remove tunnel" else "Stop tunnel",
                onClick = onStop,
                variant = ButtonVariant.Destructive,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TUNNEL_STOP_TAG),
            )
        }
    }
}

@Composable
private fun TunnelDetailRow(label: String, value: String) {
    ListRow(
        title = label,
        subtitle = value,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The detail page wording (AVAILABLE reads plainly as "Available", unlike the
 * Services list's "Not forwarded"). Distinct from `statusLabel` and
 * `servicesStatusLabel` — the three render the same core enum, each surface
 * with its own choice of word, exactly as before the move.
 */
internal val TunnelStatusDisplay.detailStatusLabel: String
    get() = when (this) {
        TunnelStatusDisplay.FORWARDING -> "Forwarding"
        TunnelStatusDisplay.AVAILABLE -> "Available"
        TunnelStatusDisplay.FAILED -> "Failed"
        TunnelStatusDisplay.STOPPED -> "Stopped"
    }
