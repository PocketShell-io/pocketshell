package com.pocketshell.uikit.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

/**
 * The core list row — the one source of truth for flat rows in
 * the app (host list, sessions, settings, conversation list, port-forward
 * panel, …). Encodes the issue #489 row pattern and the design language locked
 * on #479:
 *
 * ```
 * ┌──────────────────────────────────────────────────────┐
 * │ ● │  agent-main                       [Claude]  [⋮]   │   <- leading / title / trailing
 * │   │  ~/proj/agent                                     │   <- subtitle (mono, muted)
 * └──────────────────────────────────────────────────────┘
 * ```
 *
 * Slots (every visual region is a caller-supplied lambda so screens compose
 * their own status dot / avatar / badge / kebab without re-encoding the row):
 *
 * - **[leading]** (optional) — status dot ([StatusDot]) / avatar / icon,
 *   centred in a slot at least [PocketShellDensity.icon] wide so every row in
 *   a list shares one title gutter whatever the glyph measures (#2796). Pass
 *   `null` for a flush-left title (e.g. settings rows) — a row without the
 *   lambda gets no slot and no gutter at all.
 * - **title** — the primary scan target, [PocketShellType.body] (14sp) on the
 *   bright text token.
 * - **[subtitle]** (optional) — paths / IDs / `user@host`, rendered
 *   [PocketShellType.metadata] (11sp) on the muted token. The default is a
 *   single ellipsised line; callers such as [WorkspaceRow] may opt into a
 *   second line when the label itself is part of navigation.
 * - **[trailing]** (optional) — badge ([Badge]) / count / kebab ([Kebab]). One
 *   overflow affordance per row (design language: avoid multiple inline action
 *   buttons).
 *
 * ### Density and touch floor
 *
 * Rows use the Quiet 56dp minimum and 20dp screen gutter. The whole row is the
 * tap target when [onClick] is supplied, and wrapped content is allowed to grow.
 *
 * `rowPadV` belongs to the **text block**, not to the slots. A row's height is
 * its title/subtitle column plus that padding, floored at `rowMinHeight`; a
 * [leading] glyph or a [trailing] affordance is centred inside whatever that
 * comes to. Issue #2806: padding every child instead meant [Kebab]'s hard-sized
 * 48dp trigger measured 48 + 2 × 16 = 80dp and dragged the whole row up with
 * it, so a host row (with a kebab) stood 16.7dp taller than the "SSH keys" row
 * (without one) directly under it in the same list. A slot affordance is an
 * affordance, not content — it must not set the row's height.
 *
 * Colours stay on the always-dark raw tokens (#477 single dark scheme) so the
 * row never flips with the system light setting.
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    titleMaxLines: Int = 1,
    subtitleMaxLines: Int = 1,
    titleStyle: TextStyle = PocketShellType.body,
    subtitleStyle: TextStyle = PocketShellType.metadata,
    titleWeight: FontWeight? = null,
    subtitleContent: (@Composable () -> Unit)? = null,
) {
    // Every standard row is a 56dp minimum hit target. WorkspaceRow raises
    // this to the separate 64dp workspace navigation target.
    val minHeight = PocketShellDensity.rowMinHeight

    Column(
        modifier = if (onClick == null) modifier.fillMaxWidth() else Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = minHeight)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(role = Role.Button, onClick = onClick)
                    } else {
                        Modifier
                    },
                )
                .then(if (onClick != null) modifier else Modifier)
                .padding(horizontal = PocketShellDensity.screenGutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
        if (leading != null) {
            // The leading slot is centred in a box at least one
            // [PocketShellDensity.icon] wide, so an 8dp status dot, an 18dp
            // session-kind mark and a 24dp glyph all put the title's left edge
            // at the same x and a stacked list reads as a clean column. Before
            // #2796 this box took its content's width and the comment here
            // described a fix that was never applied.
            //
            // A floor, not a clamp: leading content that is legitimately wider
            // than the icon box - a padded glyph, or an interactive control
            // carrying its own [PocketShellDensity.tapTargetMin] hit area -
            // keeps its own width instead of being squeezed under it. Nothing
            // in a shared row primitive may shrink a caller's glyph or drop a
            // hit target below the 48dp floor (see [PocketShellDensity]).
            Box(
                modifier = Modifier.widthIn(min = PocketShellDensity.icon),
                contentAlignment = Alignment.Center,
            ) {
                leading()
            }
            Spacer(modifier = Modifier.width(PocketShellSpacing.md))
        }

        // The vertical padding lives here rather than on the Row so the text
        // block alone decides how tall the row grows past its floor (#2806).
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = PocketShellDensity.rowPadV),
        ) {
            Text(
                text = title,
                color = PocketShellColors.Text,
                style = titleStyle,
                fontWeight = titleWeight,
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null || subtitleContent != null) {
                Spacer(modifier = Modifier.size(2.dp))
                if (subtitleContent != null) {
                    subtitleContent()
                } else {
                    Text(
                        text = requireNotNull(subtitle),
                        color = PocketShellColors.TextMuted,
                        style = subtitleStyle,
                        maxLines = subtitleMaxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (trailing != null) {
            Spacer(modifier = Modifier.width(PocketShellSpacing.sm))
            Row(
                horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                trailing()
            }
        }
        }
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = PocketShellDensity.screenGutter),
            color = PocketShellColors.BorderSoft,
        )
    }
}
