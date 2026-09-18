package com.pocketshell.uikit.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.uikit.icons.PocketShellIcons
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellShapes

/**
 * Pill geometry for the composer action controls (#2763).
 *
 * Every value derives from a design token per `docs/design-system.md`: the
 * corner is the quiet 12dp field/button rung (`PocketShellShapes.medium`,
 * `tokens.json` `radius.field`), and both pill heights are the 48dp
 * touch-target floor (`PocketShellDensity.tapTargetMin`, `size.touchMin`) —
 * the idle and recording rows share one height rung. The stop glyph is the
 * one deliberate sub-ladder component geometry: a 15dp icon inside the 48dp
 * disc, named here with that cite.
 */
internal val ComposerIdlePillHeight: Dp = PocketShellDensity.tapTargetMin
internal val ComposerRecordingPillHeight: Dp = PocketShellDensity.tapTargetMin

/** Stop-square glyph size inside the 48dp accent disc (sub-ladder icon geometry). */
internal val ComposerStopGlyphSize: Dp = 15.dp

/** Quiet field/button radius for the composer action pills. */
private val ComposerActionPillShape: Shape = PocketShellShapes.medium

/**
 * Send commits the draft and submits it.
 *
 * Idle, it is the row's primary: filled accent. On the recording/transcribing
 * rows it demotes to the shared Secondary outline (#2602): the trailing Stop
 * disc is the row's one accent, because a mis-tap on Stop costs nothing while
 * a mis-tap on Send submits a half-dictated sentence to a live session — the
 * two must not carry equal visual weight.
 *
 * Promoted verbatim from app2's `ComposerBar` (#2763): parameters are
 * deliberately primitive so the kit stays ignorant of any screen's state.
 */
@Composable
fun ComposerSendButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    recording: Boolean = false,
) {
    val height = if (recording) ComposerRecordingPillHeight else ComposerIdlePillHeight
    val containerColor =
        if (!recording && enabled) PocketShellColors.Accent else PocketShellColors.SurfaceElev
    val contentColor = when {
        !enabled -> PocketShellColors.TextMuted
        recording -> PocketShellColors.Accent
        else -> PocketShellColors.OnAccent
    }
    // Outline only on the dictation rows: idle Send keeps its borderless fill
    // (the one Primary there), recording Send reads as the ui-kit Secondary.
    val border = when {
        !recording -> Modifier
        enabled -> Modifier.border(1.dp, PocketShellColors.AccentDim, ComposerActionPillShape)
        else -> Modifier.border(1.dp, PocketShellColors.Border, ComposerActionPillShape)
    }
    Row(
        modifier = modifier
            .height(height)
            .clip(ComposerActionPillShape)
            .background(color = containerColor, shape = ComposerActionPillShape)
            .then(border)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (recording) 16.dp else 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(
            text = "Send",
            color = contentColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Icon(
            imageVector = PocketShellIcons.Send,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * Throws the recording away without transcribing.
 *
 * The outlined, quiet counterpart of [ComposerStopRecordingButton]: one of
 * these two discards the user's words and the other keeps them, so they must
 * not look alike. [label] is the visible text (the transcribing row says
 * "Cancel"); [contentDescription] is what TalkBack reads — spelled out so
 * "discard" never reads as Stop's twin.
 */
@Composable
fun ComposerDiscardButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Discard",
    contentDescription: String = "Discard recording without transcribing",
) {
    Row(
        modifier = modifier
            .height(ComposerRecordingPillHeight)
            .clip(ComposerActionPillShape)
            .background(PocketShellColors.SurfaceElev, ComposerActionPillShape)
            .border(1.dp, PocketShellColors.Border, ComposerActionPillShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = PocketShellColors.TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Ends a dictation and keeps the transcript (#2598).
 *
 * A filled accent disc with a stop square, in the same slot and at the same
 * size as the mic button: the mic turns into its own stop, which is the
 * idiom every voice recorder uses. Deliberately NOT the
 * [ComposerDiscardButton] outline — one of these two throws the user's words
 * away and the other keeps them, so they must not look alike.
 *
 * [contentDescription] is required because the accessible name is a
 * deliberate product decision (#2598: "stop" alone reads like Discard's
 * twin — the caller owns the exact wording).
 */
@Composable
fun ComposerStopRecordingButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(ComposerIdlePillHeight)
            .clip(CircleShape)
            .background(color = PocketShellColors.Accent, shape = CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = PocketShellIcons.Stop,
            contentDescription = null,
            tint = PocketShellColors.OnAccent,
            modifier = Modifier.size(ComposerStopGlyphSize),
        )
    }
}
