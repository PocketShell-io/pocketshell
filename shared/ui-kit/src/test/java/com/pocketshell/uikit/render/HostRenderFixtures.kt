package com.pocketshell.uikit.render

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.components.AgentKindBadge
import com.pocketshell.uikit.components.AgentStateChip
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.StatusDot
import com.pocketshell.uikit.model.ConnectionStatus
import com.pocketshell.uikit.model.PillKind
import com.pocketshell.uikit.model.SessionAgentState
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellType

/**
 * Issue #1237: the agent-state chip (idle / working / waiting-for-input), on a
 * screen header plus the three chip variants standalone. Rendered against the
 * real theme so the fast JVM check shows the chip vocabulary in one image; the
 * emulator screenshot is the acceptance. (The fixture used to stage the chips
 * on `HostCard`s; the dead-canon sweep in #2717 retired that component.)
 */
@Composable
internal fun AgentStateChipsRender() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenHeader(title = "Hosts", subtitle = "4 hosts · 3 active")
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AgentStateChip(state = SessionAgentState.WaitingForInput)
            AgentStateChip(state = SessionAgentState.Working)
            AgentStateChip(state = SessionAgentState.Idle)
        }
    }
}

/**
 * Issue #1701: the session-list trailing lane after the full status and agent
 * words were hard-cut. Covers every state × both same-colour primary agent
 * kinds, proving the CL/CX monograms stay distinct while the long session name
 * receives the reclaimed width.
 */
@Composable
internal fun SessionListChipIconsRender() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ScreenHeader(
            title = "Sessions",
            subtitle = "6 active · compact status and kind",
        )
        SessionListIconRow(SessionAgentState.Working, "CL", "Claude")
        SessionListIconRow(SessionAgentState.WaitingForInput, "CL", "Claude")
        SessionListIconRow(SessionAgentState.Idle, "CL", "Claude")
        SessionListIconRow(SessionAgentState.Working, "CX", "Codex")
        SessionListIconRow(SessionAgentState.WaitingForInput, "CX", "Codex")
        SessionListIconRow(SessionAgentState.Idle, "CX", "Codex")
    }
}

@Composable
private fun SessionListIconRow(
    state: SessionAgentState,
    monogram: String,
    label: String,
) {
    ListRow(
        title = "git-course-management-platform-service",
        leading = {
            StatusDot(status = ConnectionStatus.Connected)
        },
        trailing = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AgentStateChip(state = state)
                AgentKindBadge(
                    monogram = monogram,
                    label = label,
                    isAgent = true,
                )
            }
        },
        onClick = {},
    )
}

@Composable
internal fun UsageGlancePillRender() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ScreenHeader(
            title = "Hosts",
            subtitle = "5 hosts · 4 active",
            trailing = {
                UsageGlancePillFacsimile(attribution = "Codex 7d", percent = "72%", dot = PillKind.Warn)
                AppBarPillFacsimile {
                    Text(
                        "2",
                        color = PocketShellColors.Text,
                        style = PocketShellType.bodyDense,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                AppBarGearFacsimile()
            },
        )
        ScreenHeader(
            title = "Hosts",
            subtitle = "5 hosts · 4 active",
            trailing = {
                UsageGlancePillFacsimile(attribution = "Claude", percent = "63%", dot = PillKind.Ok, staleClock = "13:40")
                AppBarGearFacsimile()
            },
        )
    }
}

/**
 * Issue #1241: ui-kit facsimile of the app-module app-bar usage glance pill
 * ([com.pocketshell.app.usage.UsageGlancePill]) for the fast JVM render.
 * Mirrors the app pill's chrome so the design read is faithful; the emulator
 * screenshot is the acceptance.
 */
@Composable
private fun UsageGlancePillFacsimile(
    attribution: String,
    percent: String,
    dot: PillKind,
    staleClock: String? = null,
) {
    val alpha = if (staleClock != null) 0.6f else 1f
    val dotColor = when (dot) {
        PillKind.Ok -> PocketShellColors.Green
        PillKind.Warn -> PocketShellColors.Amber
        PillKind.Blocked -> PocketShellColors.Red
        PillKind.Error -> PocketShellColors.TextMuted
    }
    Row(
        modifier = Modifier
            .height(32.dp)
            .background(color = PocketShellColors.SurfaceElev, shape = PocketShellShapes.large)
            .border(width = 1.dp, color = PocketShellColors.BorderSoft, shape = PocketShellShapes.large)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(dotColor.copy(alpha = alpha)))
        Spacer(modifier = Modifier.width(6.dp))
        // #1566: provider (+window) attribution muted, percent bold.
        Text(
            text = attribution,
            color = PocketShellColors.TextSecondary.copy(alpha = alpha),
            style = PocketShellType.bodyDense,
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = percent,
            color = PocketShellColors.Text.copy(alpha = alpha),
            style = PocketShellType.bodyDense,
            fontWeight = FontWeight.SemiBold,
        )
        if (staleClock != null) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = staleClock, color = PocketShellColors.TextMuted, style = PocketShellType.bodyDense)
        }
    }
}

@Composable
private fun AppBarPillFacsimile(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .height(32.dp)
            .background(color = PocketShellColors.SurfaceElev, shape = PocketShellShapes.large)
            .border(width = 1.dp, color = PocketShellColors.BorderSoft, shape = PocketShellShapes.large)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun AppBarGearFacsimile() {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(color = PocketShellColors.SurfaceElev)
            .border(width = 1.dp, color = PocketShellColors.BorderSoft, shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text("⚙", color = PocketShellColors.TextSecondary)
    }
}

/**
 * Issue #2532: session-tree header with a visible Back (top-left) and Usage
 * next to Files/Ports. This mirrors the [ScreenHeader] slot layout with the
 * shared ui-kit primitives (the app2 tree screen itself was removed in
 * #2726).
 */
@Composable
internal fun SessionTreeHeaderBackAndUsageRender() {
    ScreenHeader(
        title = "Sessions",
        subtitle = "3 sessions · 2 workspaces",
        leading = {
            PocketShellButton(
                text = "Back",
                onClick = {},
                variant = ButtonVariant.Text,
                compact = true,
            )
        },
        trailing = {
            PocketShellButton(text = "Files", onClick = {}, variant = ButtonVariant.Text, compact = true)
            PocketShellButton(text = "Ports", onClick = {}, variant = ButtonVariant.Text, compact = true)
            PocketShellButton(text = "Usage", onClick = {}, variant = ButtonVariant.Text, compact = true)
        },
    )
}

/**
 * Issue #2532: session terminal header with Back and a Usage affordance
 * (text button when the glance pill has no reading — the missing-button bug).
 */
@Composable
internal fun SessionHeaderBackAndUsageRender() {
    ScreenHeader(
        title = "git-pocketshell",
        subtitle = "attaching",
        leading = {
            PocketShellButton(
                text = "Back",
                onClick = {},
                variant = ButtonVariant.Text,
                compact = true,
            )
        },
        trailing = {
            PocketShellButton(
                text = "Usage",
                onClick = {},
                variant = ButtonVariant.Text,
                compact = true,
            )
        },
    )
}
