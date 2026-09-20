package com.pocketshell.uikit.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellType

/**
 * Quiet's primary workspace navigation row. The whole row is the drill-in
 * target; callers provide only the short label and a useful muted summary.
 *
 * It is a [ListRow] at the workspace rungs, so it paints [ListRow]'s divider
 * and forwards [ListRow]'s [showDivider] switch — a workspace row dropped into
 * a deliberately gapped block turns the divider off there rather than floating
 * a hairline in the gap (#2804). `RowRhythmGuardTest` treats it as a
 * divider-bearing row for exactly that reason.
 */
@Composable
fun WorkspaceRow(
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    subtitleContent: (@Composable () -> Unit)? = null,
    showDivider: Boolean = true,
) {
    ListRow(
        title = title,
        subtitle = subtitle,
        subtitleContent = subtitleContent,
        trailing = { NavigationChevron() },
        onClick = onClick,
        showDivider = showDivider,
        titleMaxLines = 2,
        subtitleMaxLines = 2,
        titleStyle = PocketShellType.workspace,
        subtitleStyle = PocketShellType.metadata,
        titleWeight = FontWeight.SemiBold,
        modifier = modifier
            .heightIn(min = PocketShellDensity.workspaceRowMinHeight)
            .let { base -> if (testTag == null) base else base.testTag(testTag) },
    )
}
