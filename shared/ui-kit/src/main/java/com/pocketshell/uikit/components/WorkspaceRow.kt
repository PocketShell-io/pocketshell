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
 * Issue #2721: the row also carries a long-press ([onLongClick]) — the design
 * language's "always available alternate action". The tap opens the
 * workspace's terminal; the long-press opens its management actions, which no
 * longer have a workspace screen to live on.
 */
@Composable
fun WorkspaceRow(
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    subtitleContent: (@Composable () -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    ListRow(
        title = title,
        subtitle = subtitle,
        subtitleContent = subtitleContent,
        trailing = { NavigationChevron() },
        onClick = onClick,
        onLongClick = onLongClick,
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
