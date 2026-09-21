package com.pocketshell.next.ports

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.LoadingIndicator
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.components.SegmentedToggle
import com.pocketshell.uikit.components.SpinnerSize
import com.pocketshell.uikit.components.StatusDot
import com.pocketshell.uikit.model.ConnectionStatus
import com.pocketshell.uikit.theme.LocalPocketShellSemantic
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

/** Stable test tags. */
const val PORT_TABLE_TAG: String = "port_forward_table"
const val FORWARDING_TOGGLE_TAG: String = "port_forward_toggle"
const val SHOW_ALL_PORTS_TAG: String = "port_forward_show_all_ports"
const val PORT_FORWARD_BACK_TAG: String = "port-forward-back"

fun portRowTag(remotePort: Int): String = "port-row-$remotePort"

/**
 * The port-forward screen (rewrite task P-4).
 *
 * Moved to the shared presentation module (#2636 D11): stateless, it paints
 * the pure [PortForwardDisplayState] and the `core.portfwd` display mirrors —
 * the Hilt route that binds the view model and starts the foreground service
 * stays in app2 (`app2/.../ports/PortForwardRoute.kt`), the D10 usage-seam
 * shape with the route file named for the route it holds (a same-named
 * `PortForwardScreen.kt` on both sides of the seam would collide on the
 * `PortForwardScreenKt` JVM facade).
 *
 * Three controls and a table:
 * - An Off/On [SegmentedToggle] for the whole host. The old client used a small
 *   `Switch` in a dense row and the maintainer could not tell it was tappable
 *   (#751); the segmented toggle is the canonical "pick one of N" control and
 *   makes both the state and the affordance unmistakable.
 * - A "Show hidden/noisy ports" checkbox, surfacing the hidden-row count so the
 *   user knows the table is filtered rather than empty.
 * - One row per discovered port: remote, local, process, status, traffic, and a
 *   Start/Stop action.
 */
@Composable
fun PortForwardScreen(
    state: PortForwardDisplayState,
    onSetEnabled: (Boolean) -> Unit,
    onTogglePort: (Int) -> Unit,
    onSetShowAllPorts: (Boolean) -> Unit,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background),
    ) {
        ScreenHeader(
            title = state.hostName,
            subtitle = state.hostSubtitle.ifBlank { state.connection.headerLabel },
            onBack = onBack,
            backTestTag = PORT_FORWARD_BACK_TAG,
            trailing = { StatusDot(status = state.connection.toConnectionStatus(state.enabled)) },
        )

        ForwardingToggleRow(enabled = state.enabled, onEnabledChange = onSetEnabled)

        ShowAllPortsRow(
            checked = state.showAllPorts,
            hiddenCount = state.hiddenCount,
            onCheckedChange = onSetShowAllPorts,
        )

        SectionHeader(label = "Ports", count = state.rows.size)
        PortTableHeader(PORT_COLUMNS)

        when {
            !state.enabled -> CenteredMessage(
                text = "Forwarding is off. Turn it on to discover listening ports.",
                modifier = Modifier.weight(1f),
            )

            state.connection == ConnectionStateDisplay.Lost -> CenteredMessage(
                text = pausedMessage(state.attention),
                modifier = Modifier.weight(1f),
            )

            state.rows.isEmpty() && state.hiddenCount > 0 -> CenteredMessage(
                text = hiddenPortsMessage(state.hiddenCount),
                modifier = Modifier.weight(1f),
            )

            state.scanning -> Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                LoadingIndicator.Spinner(size = SpinnerSize.Medium, label = "Scanning ports…")
            }

            else -> {
                val rowKeys = tunnelRowKeys(state.rows)
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .testTag(PORT_TABLE_TAG),
                    contentPadding = PaddingValues(bottom = PocketShellSpacing.md),
                ) {
                    itemsIndexed(state.rows, key = { index, _ -> rowKeys[index] }) { _, tunnel ->
                        PortForwardRow(tunnel = tunnel, onToggle = { onTogglePort(tunnel.remotePort) })
                    }
                }
            }
        }
    }
}

/**
 * Stable, guaranteed-UNIQUE LazyColumn keys.
 *
 * Keying rows on `remotePort` alone crashed the old client: the list can
 * legitimately contain two rows for one remote port (the same port discovered on
 * two interfaces, or a forwarded row co-existing with its AVAILABLE twin), and
 * `LazyColumn` throws `IllegalArgumentException: Key "22" already used` on a
 * collision. The key is the row's `(remote, local, status)` identity plus an
 * occurrence counter, so it is stable across recompositions AND collision-free
 * whatever the data model produces.
 */
internal fun tunnelRowKeys(tunnels: List<TunnelDisplay>): List<String> {
    val seen = HashMap<String, Int>()
    return tunnels.map { tunnel ->
        val base = "${tunnel.remotePort}:${tunnel.localPort}:${tunnel.status}"
        val occurrence = seen.getOrDefault(base, 0)
        seen[base] = occurrence + 1
        if (occurrence == 0) base else "$base#$occurrence"
    }
}

internal fun showAllPortsLabel(checked: Boolean, hiddenCount: Int): String =
    if (!checked && hiddenCount > 0) {
        "Show hidden/noisy ports ($hiddenCount hidden)"
    } else {
        "Show hidden/noisy ports"
    }

internal fun hiddenPortsMessage(hiddenCount: Int): String =
    if (hiddenCount == 1) "1 noisy port hidden." else "$hiddenCount noisy ports hidden."

/**
 * What a host in the TERMINAL [ConnectionStateDisplay.Lost] state says (#2491).
 *
 * It must never claim a retry is coming: the supervisor has parked, and the
 * whole point of the state is that nothing will change until the user acts.
 * [attention] carries what to actually do when the dial site knew (an
 * unconfirmed host key, a deleted host row).
 */
internal fun pausedMessage(attention: String?): String =
    "Forwarding paused. " + (attention ?: "This host could not be reached.")

internal fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}

/**
 * Why the Local cell might show a number the user does not expect (issue
 * #2498).
 *
 * The allocator mirrors the remote port onto the same local port when it can,
 * but a phone-side collision walks the bind UP to the next free port (and a
 * persisted remap or an out-of-window manual tunnel binds a different port
 * too). The row always shows the real binding; this label is the explicit
 * signal that it differs from the remote port, so `localhost:3000` quietly
 * becoming `localhost:3003` is visible instead of silent. Null when the row
 * is not forwarding or the ports match.
 */
internal fun localPortMismatchLabel(remotePort: Int, localPort: Int, forwarding: Boolean): String? =
    if (forwarding && localPort != remotePort) "differs from remote" else null

private val PORT_COLUMNS: List<PortColumn> = listOf(
    PortColumn("Remote", 0.18f),
    PortColumn("Local", 0.16f),
    PortColumn("Process", 0.28f),
    PortColumn("Status", 0.18f),
    PortColumn("Traffic", 0.20f),
)

@Composable
private fun ForwardingToggleRow(enabled: Boolean, onEnabledChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.SurfaceElev)
            .border(1.dp, PocketShellColors.BorderSoft)
            .padding(
                horizontal = PocketShellDensity.screenGutter,
                vertical = PocketShellSpacing.sm,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Forward ports from this host",
                color = PocketShellColors.Text,
                style = PocketShellType.body,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (enabled) {
                    "On — tunnels stay alive while the app is in the background"
                } else {
                    "Off — nothing is forwarded and no connection is held"
                },
                color = PocketShellColors.TextSecondary,
                style = PocketShellType.body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(PocketShellSpacing.md))
        SegmentedToggle(
            labels = listOf("Off", "On"),
            selectedIndex = if (enabled) 1 else 0,
            onSelected = { onEnabledChange(it == 1) },
            modifier = Modifier.testTag(FORWARDING_TOGGLE_TAG),
            segmentTag = { index ->
                if (index == 1) "${FORWARDING_TOGGLE_TAG}_on" else "${FORWARDING_TOGGLE_TAG}_off"
            },
        )
    }
}

@Composable
private fun ShowAllPortsRow(checked: Boolean, hiddenCount: Int, onCheckedChange: (Boolean) -> Unit) {
    ListRow(
        title = showAllPortsLabel(checked, hiddenCount),
        modifier = Modifier
            .testTag(SHOW_ALL_PORTS_TAG)
            .defaultMinSize(minHeight = PocketShellDensity.tapTargetMin)
            .clickable(role = Role.Checkbox) { onCheckedChange(!checked) },
        leading = {
            Checkbox(
                checked = checked,
                onCheckedChange = null,
                colors = CheckboxDefaults.colors(
                    checkedColor = PocketShellColors.Accent,
                    uncheckedColor = PocketShellColors.TextSecondary,
                ),
            )
        },
    )
}

@Composable
private fun PortForwardRow(tunnel: TunnelDisplay, onToggle: () -> Unit) {
    val forwarding = tunnel.status == TunnelStatusDisplay.FORWARDING
    val semantic = LocalPocketShellSemantic.current
    val statusColor: Color = when (tunnel.status) {
        TunnelStatusDisplay.FORWARDING -> semantic.statusActive
        TunnelStatusDisplay.AVAILABLE -> PocketShellColors.TextSecondary
        TunnelStatusDisplay.FAILED -> semantic.statusError
        TunnelStatusDisplay.STOPPED -> semantic.statusAttention
    }
    PortTableRow(
        onClick = onToggle,
        modifier = Modifier.testTag(portRowTag(tunnel.remotePort)),
    ) {
        PortBodyCell("${tunnel.remotePort}", 0.18f, monospace = true)
        // Local cell: the bound port, plus an explicit note when it differs
        // from the remote port (#2498) — same stacked pattern as Traffic.
        Column(modifier = Modifier.weight(0.16f)) {
            if (forwarding) {
                Text(
                    text = "${tunnel.localPort}",
                    color = PocketShellColors.TextSecondary,
                    style = PocketShellType.bodyMono,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                localPortMismatchLabel(
                    remotePort = tunnel.remotePort,
                    localPort = tunnel.localPort,
                    forwarding = forwarding,
                )?.let { mismatch ->
                    Text(
                        text = mismatch,
                        color = PocketShellColors.TextMuted,
                        style = PocketShellType.metadata,
                    )
                }
            } else {
                Text(
                    text = "-",
                    color = PocketShellColors.TextSecondary,
                    style = PocketShellType.bodyMono,
                )
            }
        }
        PortBodyCell(tunnel.process.ifBlank { "-" }, 0.28f)
        PortBodyCell(tunnel.status.statusLabel, 0.18f, color = statusColor)
        // Discovered/available rows have no traffic yet, so "0 B / 0 B/s" on
        // every row would be pure noise; the figures appear only where they mean
        // something.
        Column(modifier = Modifier.weight(0.20f)) {
            if (forwarding) {
                Text(
                    text = formatBytes(tunnel.bytesIn + tunnel.bytesOut),
                    color = PocketShellColors.TextSecondary,
                    style = PocketShellType.metadata,
                )
                Text(
                    text = "${formatBytes(tunnel.speedBps)}/s",
                    color = PocketShellColors.TextMuted,
                    style = PocketShellType.metadata,
                )
            } else {
                Text(
                    text = "-",
                    color = PocketShellColors.TextMuted,
                    style = PocketShellType.metadata,
                )
            }
        }
        Spacer(Modifier.width(PocketShellSpacing.sm))
        PocketShellButton(
            text = if (forwarding) "Stop" else "Start",
            onClick = onToggle,
            variant = if (forwarding) ButtonVariant.Text else ButtonVariant.Primary,
            compact = true,
        )
    }
}

@Composable
private fun CenteredMessage(text: String, modifier: Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            // The terminal "why forwarding stopped" copy is a sentence, not a
            // label, so it wraps — without a gutter it ran edge to edge (#2491).
            .padding(horizontal = PocketShellSpacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = PocketShellColors.TextSecondary,
            style = PocketShellType.body,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The header's connection vocabulary ("Needs attention" is terminal wording;
 * "Reconnecting" is not — #2491). Internal so the shared family test pins the
 * exact strings; `label` in the old app2 file, renamed for its one caller
 * (the ScreenHeader subtitle) now that the module carries a second, different
 * connection-wording helper for Services (`quietLabel`).
 */
internal val ConnectionStateDisplay.headerLabel: String
    get() = when (this) {
        ConnectionStateDisplay.Idle -> "Idle"
        ConnectionStateDisplay.Connecting -> "Connecting"
        ConnectionStateDisplay.Connected -> "Connected"
        ConnectionStateDisplay.Reconnecting -> "Reconnecting"
        // Terminal, not "still trying": the supervisor has stopped dialling and
        // only the user can change the outcome (#2491).
        ConnectionStateDisplay.Lost -> "Needs attention"
    }

internal fun ConnectionStateDisplay.toConnectionStatus(enabled: Boolean): ConnectionStatus = when {
    !enabled -> ConnectionStatus.Idle
    this == ConnectionStateDisplay.Connected -> ConnectionStatus.Connected
    this == ConnectionStateDisplay.Lost -> ConnectionStatus.Error
    this == ConnectionStateDisplay.Idle -> ConnectionStatus.Idle
    else -> ConnectionStatus.Connecting
}

/**
 * The ports-table row vocabulary. Distinct from Services' list wording
 * (`servicesStatusLabel` — "Not forwarded") and the detail page's
 * (`detailStatusLabel`): all three render the same core enum, each surface
 * with its own choice of word, exactly as before the move.
 */
internal val TunnelStatusDisplay.statusLabel: String
    get() = when (this) {
        TunnelStatusDisplay.FORWARDING -> "Forwarding"
        TunnelStatusDisplay.AVAILABLE -> "Available"
        TunnelStatusDisplay.FAILED -> "Failed"
        TunnelStatusDisplay.STOPPED -> "Stopped"
    }
