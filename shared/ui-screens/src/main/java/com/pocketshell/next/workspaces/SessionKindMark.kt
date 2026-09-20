package com.pocketshell.next.workspaces

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.pocketshell.uikit.icons.PocketShellIcons
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity

/**
 * Quiet, monochrome session mark used as metadata beside a readable name.
 *
 * Lives in the shared presentation module (#2636 D8) — a pure agent-key to
 * icon mapping; the workspaces screens/tree that render it stay in app2.
 *
 * The mark always occupies exactly one [PocketShellDensity.metadataIcon] box.
 * A session whose kind has no glyph — no `agent` reported by the host, or a
 * plain shell where the caller did not ask for [showShell] — draws a
 * transparent spacer of that same size rather than nothing at all. Emitting
 * nothing is what made a list's title left edge depend on host data: a row
 * with a known agent indented 18dp further than its neighbour without one
 * (#2796). Callers may therefore treat the mark as a fixed-footprint slot.
 */
@Composable
fun SessionKindMark(
    agent: String?,
    modifier: Modifier = Modifier,
    showShell: Boolean = false,
) {
    val key = agent?.trim()?.lowercase()
    val icon: ImageVector? = when (key) {
        "claude" -> PocketShellIcons.Hexagon
        "codex" -> PocketShellIcons.Code
        "opencode", "open_code", "open-code" -> PocketShellIcons.Terminal
        "grok" -> PocketShellIcons.Zap
        "shell", null, "", "unknown" -> if (showShell) PocketShellIcons.Terminal else null
        else -> null
    }
    if (icon == null) {
        Spacer(modifier = modifier.size(PocketShellDensity.metadataIcon))
    } else {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = PocketShellColors.TextMuted,
            modifier = modifier.size(PocketShellDensity.metadataIcon),
        )
    }
}
