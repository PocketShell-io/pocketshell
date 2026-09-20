package com.pocketshell.uikit.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.theme.PocketShellColors

/**
 * Quiet's full-row radio choice — a [ListRow] whose trailing slot is the radio
 * mark (#2804).
 *
 * It was a second, hand-rolled copy of the row body until the #2635 audit's P-2
 * found it: same job as [ListRow] (a title with one supporting line), but its
 * own supporting-line colour. Building it ON [ListRow] is what makes the one
 * grammar structural instead of a convention — the title rung, the
 * supporting-line rung ([com.pocketshell.uikit.theme.PocketShellType.metadata]
 * on [PocketShellColors.TextMuted]), the 56dp floor, the 20dp gutter and the
 * divider all come from the one primitive.
 *
 * The row — not the mark — owns the selection semantics: [Modifier.selectable]
 * with [Role.RadioButton] is passed in as the row's `modifier`, so TalkBack
 * announces one control with its selected state instead of two nested radio
 * targets, and the whole row stays the tap target. [QuietRadioMark] is
 * deliberately decorative.
 *
 * [showDivider] forwards [ListRow]'s divider switch: a choice group that sits
 * inside a deliberately gapped form (the SSH-key generate page, the unlock
 * sheet) turns the divider off rather than floating a hairline in the gap.
 */
@Composable
fun QuietChoiceRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    showDivider: Boolean = true,
) {
    ListRow(
        title = title,
        subtitle = subtitle,
        trailing = { QuietRadioMark(selected = selected) },
        showDivider = showDivider,
        modifier = modifier.selectable(
            selected = selected,
            role = Role.RadioButton,
            onClick = onClick,
        ),
    )
}

@Composable
private fun QuietRadioMark(selected: Boolean) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .border(
                width = 1.dp,
                color = if (selected) PocketShellColors.Text else PocketShellColors.Border,
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(PocketShellColors.Text, CircleShape),
            )
        }
    }
}
