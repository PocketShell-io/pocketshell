package com.pocketshell.uikit.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import com.pocketshell.uikit.model.ConnectionStatus
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellType

/**
 * Quiet's primary workspace navigation row (issue #2635 D1 remainder: the
 * dense one-line grammar). The whole row is the drill-in target; callers
 * provide the short label and — via [trailing] — the compact
 * `count + recency` glance. No chevron: on this list EVERY row drills in, so
 * the affordance is pure repeated chrome.
 *
 * [active] paints a leading dot when the workspace has any attached session —
 * the same "is anything warm in here" signal the session list's dots carry.
 *
 * Issue #2721: the row also carries a long-press ([onLongClick]) — the design
 * language's "always available alternate action". The tap opens the
 * workspace's terminal; the long-press opens its management actions, which no
 * longer have a workspace screen to live on.
 */
@Composable
fun WorkspaceRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    active: Boolean = false,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    ListRow(
        title = title,
        subtitle = subtitle,
        leading = if (active) {
            { StatusDot(status = ConnectionStatus.Connected) }
        } else {
            null
        },
        trailing = trailing,
        onClick = onClick,
        onLongClick = onLongClick,
        titleMaxLines = 1,
        subtitleMaxLines = 1,
        titleStyle = PocketShellType.bodyDense,
        titleWeight = FontWeight.SemiBold,
        modifier = modifier
            .heightIn(min = PocketShellDensity.workspaceRowMinHeight)
            .let { base -> if (testTag == null) base else base.testTag(testTag) },
    )
}
