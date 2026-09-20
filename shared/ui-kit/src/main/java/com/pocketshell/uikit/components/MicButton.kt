package com.pocketshell.uikit.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.model.MicButtonState
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity

/**
 * How one [MicButtonState] paints (#2802 C-2).
 *
 * Pulled out of [MicButton] as a plain value so "which mic state gets the
 * bright accent" is a table a test can read, not three `when` blocks a
 * reviewer has to cross-check. [border] is null when the state has no
 * outline; [shadow] is the accent elevation cue.
 */
internal data class MicButtonTreatment(
    val fill: Color,
    val glyph: Color,
    val border: Color?,
    val shadow: Boolean,
)

/**
 * The single source of the mic's visual weight.
 *
 * Recording is the only accent-filled state — see the [MicButton] KDoc for
 * why an idle mic is a secondary action and not a second primary.
 */
internal fun micButtonTreatment(state: MicButtonState): MicButtonTreatment = when (state) {
    MicButtonState.Idle -> MicButtonTreatment(
        fill = PocketShellColors.SurfaceElev,
        glyph = PocketShellColors.Text,
        border = PocketShellColors.Border,
        shadow = false,
    )
    MicButtonState.Recording -> MicButtonTreatment(
        fill = PocketShellColors.Accent,
        glyph = PocketShellColors.OnAccent,
        border = null,
        shadow = true,
    )
    MicButtonState.Disabled -> MicButtonTreatment(
        fill = PocketShellColors.SurfaceElev,
        glyph = PocketShellColors.TextMuted,
        border = null,
        shadow = false,
    )
}

/**
 * Round microphone button at the leading edge of the prompt composer.
 *
 * Visual recipe:
 * - Round, on the shared button diameter (`PocketShellDensity.buttonMin`)
 * - The fill, glyph tint, border and shadow all come from
 *   [micButtonTreatment] — one table, so the "which state is the accent
 *   one" question has exactly one answer in the tree.
 * - The recording shadow approximates the CSS
 *   `box-shadow: 0 8px 26px rgba(34,211,238,0.45)` via `Modifier.shadow`
 *   with an accent-tinted spot/ambient colour. Note: shadow tinting on
 *   API 26 falls back to a black shadow (the spot/ambient colour
 *   parameters require API 28+), which still gives the correct elevation
 *   cue.
 *
 * State behaviour (#2802 C-2):
 * - [MicButtonState.Idle] — the SECONDARY outline treatment the composer's
 *   Insert pill wears: elevated-surface fill, hairline `Border`, primary
 *   text glyph, no shadow, no animation. Tappable. A mic at rest is neither
 *   an active state nor the row's primary action, and `design-language.md`
 *   reserves the bright accent for exactly those two; before #2802 the idle
 *   mic and the filled Send pill carried identical weight side by side, so
 *   the row had two competing primaries and no answer to "what do I tap".
 * - [MicButtonState.Recording] — accent fill, on-accent glyph, accent drop
 *   shadow, pulsing opacity (1.0 -> 0.55). The accent here IS the active
 *   state: it is the one moment the mic is doing something. Tappable to
 *   stop recording.
 * - [MicButtonState.Disabled] — surface-elev fill, muted glyph, no border,
 *   no shadow, not tappable (callback is wired but `clickable` is
 *   disabled). Idle and Disabled share a fill and are told apart by the
 *   border plus the glyph tint.
 *
 * The glyph is the shared filled-microphone [MicGlyphIcon] `ImageVector`
 * (see `MicIcon.kt`) rendered via [Icon]. Issue #453: this replaced the
 * old `Text("●")` dot (a black dot in a cyan disc that read as a
 * record/power button — the maintainer's #1 complaint), so the band's mic
 * now shows a real microphone glyph everywhere this shared component is
 * used.
 */
@Composable
fun MicButton(
    state: MicButtonState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled: Boolean = state != MicButtonState.Disabled
    val treatment: MicButtonTreatment = micButtonTreatment(state)
    val baseColor: Color = treatment.fill
    val glyphColor: Color = treatment.glyph

    // Pulse only while recording — `rememberInfiniteTransition` adds
    // a permanent animation graph, so we gate the animateFloat call
    // behind the state branch.
    val pulseAlpha: Float = if (state == MicButtonState.Recording) {
        val transition = rememberInfiniteTransition(label = "mic-pulse")
        val v by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.55f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 450, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "mic-pulse-alpha",
        )
        v
    } else {
        1f
    }

    // CSS uses `box-shadow: 0 8px 26px rgba(34,211,238,0.45)`. Compose
    // expresses drop shadows via `Modifier.shadow(elevation, shape, ...)`.
    // 8dp elevation reproduces the y-offset / spread reasonably on the
    // platform shadow renderer, with the accent colour piped in as both
    // ambient and spot tint (API 28+). #2802: only the recording state
    // carries it — an elevation cue is accent weight, and an idle mic has
    // none to carry.
    val shadowModifier: Modifier = if (treatment.shadow) {
        Modifier.shadow(
            elevation = 8.dp,
            shape = CircleShape,
            ambientColor = PocketShellColors.Accent,
            spotColor = PocketShellColors.Accent,
        )
    } else {
        Modifier
    }

    // #2802: the hairline that makes the idle mic read as the Insert pill's
    // sibling rather than as the disabled mic.
    val borderModifier: Modifier = treatment.border?.let { color ->
        Modifier.border(width = 1.dp, color = color, shape = CircleShape)
    } ?: Modifier

    Box(
        modifier = modifier
            .size(PocketShellDensity.buttonMin)
            .then(shadowModifier)
            .background(
                color = baseColor.copy(alpha = baseColor.alpha * pulseAlpha),
                shape = CircleShape,
            )
            .then(borderModifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Dictate" },
        contentAlignment = Alignment.Center,
    ) {
        // Issue #453: the shared filled-microphone glyph (MicGlyphIcon),
        // replacing the old `Text("●")` dot. This is the band's mic — the
        // first thing the user sees on the agent/Conversation pane — so the
        // fix lands here at the source for every MicButton call site.
        Icon(
            imageVector = MicGlyphIcon,
            contentDescription = null,
            tint = glyphColor,
            modifier = Modifier.size(PocketShellDensity.icon),
        )
    }
}
