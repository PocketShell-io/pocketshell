package com.pocketshell.uikit.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.icons.PocketShellIcons
import com.pocketshell.uikit.model.KeyBinding
import com.pocketshell.uikit.model.KeyKind
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

/** Stable test tags for the session bottom terminal bar (#2612). */
const val SESSION_TERMINAL_BAR_TAG: String = "session-terminal-bar"
const val SESSION_BAR_COMPOSE_TAG: String = "session-bar-compose"
const val SESSION_BAR_ARROW_UP_TAG: String = "session-bar-arrow-up"
const val SESSION_BAR_ARROW_DOWN_TAG: String = "session-bar-arrow-down"
const val SESSION_BAR_ENTER_TAG: String = "session-bar-enter"
const val SESSION_BAR_MORE_KEYS_TAG: String = "session-bar-more-keys"
const val SESSION_BAR_ENTER_DIVIDER_TAG: String = "session-bar-enter-divider"

/** Stable test tags for the key-bar dictation surface (#2475). */
const val SESSION_BAR_MIC_TAG: String = "session-bar-mic"
const val SESSION_BAR_DICTATION_CHIP_TAG: String = "session-bar-dictation-chip"

/** Visible label semantics for the composer launcher in the bar. */
const val SESSION_BAR_COMPOSE_LABEL: String = "Prompt Composer"

/** Visible label semantics for the More keys affordance in the bar. */
const val SESSION_BAR_MORE_KEYS_LABEL: String = "More terminal keys"

/** Visible label semantics for the dictation mic in the bar (#2475). */
const val SESSION_BAR_MIC_LABEL: String = "Dictate to terminal"

/**
 * Where the key-bar dictation (#2475) is, in the vocabulary the ui-kit bar
 * renders. app2 maps its controller state onto this; the bar never learns
 * about recognizer sessions or PTY writes.
 */
enum class SessionBarDictationPhase {
    Idle,
    Listening,
    Transcribing,
}

/**
 * The one-tap terminal-navigation keys the bar owns (#2612).
 *
 * An enum rather than `[KeyBinding]` strings so the ui-kit bar never has to
 * duplicate the wire labels — app2 maps each value to its PTY bytes through
 * its own `keyBarBytes` table, and the routing stays pinned there.
 */
enum class SessionNavKey {
    ArrowUp,
    ArrowDown,
    Enter,
}

/**
 * The session screen's persistent bottom terminal bar (#2612).
 *
 * Issue #2612 revisits the #2631 "no docked chrome" shape for the three keys
 * interactive terminal menus need most: `↑`, `↓`, and `Enter` sit permanently
 * on the bar, one tap each, no panel to open and close around every menu
 * navigation. The composer launcher and the `More keys` affordance (which
 * opens the floating hotkeys palette) complete the row, and #2475 adds the
 * dictation mic at the trailing end:
 *
 * ```
 * [Compose]  [ ↑ ]  [ ↓ ]  |  [Enter]  [More keys]  [Mic]
 * ```
 *
 * Every control is an EXACT [PocketShellDensity.tapTargetMin] slot. That is
 * not styling, it is arithmetic: at the narrowest supported phone (360dp,
 * the #2612 floor test's worst case) six floor-sized controls plus the
 * divider fill the row, so the pre-#2475 weighted slots (which let the
 * Material 58dp button default leak past the 48dp floor on one side and
 * squeezed the weighted keys to 34dp on the other) became exact-sized
 * slots — the design system's non-negotiable touch floor, held on both
 * ends, at every width. Slack on wide phones goes to the trailing edge,
 * exactly like a hardware keyboard's unused right side.
 *
 * - The arrow glyphs route as [SessionNavKey.ArrowUp] / [ArrowDown]; `Enter`
 *   is separated from the arrows by a hairline divider so a rushed tap cannot
 *   confirm a selection by accident.
 * - [keysEnabled] mutes the key cluster while the session cannot receive
 *   bytes (attaching / reconnecting); the callbacks simply do not fire.
 * - [showKeys] = false removes the whole key cluster *and* `More keys`,
 *   leaving the composer launcher and the mic — the user's "no key chrome"
 *   setting and the failed-session state both use it; the mic is input, not
 *   a key, and travels with the launcher.
 * - While [dictationPhase] is active (or [dictationText] carries an error to
 *   clear), a status strip renders ABOVE the key row — never squeezed into
 *   it, because the key row already fills the narrowest phone exactly. It is
 *   the ONLY place partial transcription results ever appear — nothing here
 *   writes to a terminal.
 *
 * Unlike the floating hotkeys palette this bar is DOCKED: it is a stable
 * full-width strip the caller places below the terminal slot, and it never
 * drags. The docked #2631 launcher bar this replaces had no room for
 * three permanent keys; a strip below the viewport keeps every terminal row
 * readable while making the high-frequency keys reachable without covering
 * the content the user is navigating.
 */
@Composable
fun SessionTerminalBar(
    onKey: (SessionNavKey) -> Unit,
    onOpenComposer: () -> Unit,
    onMoreKeys: () -> Unit,
    modifier: Modifier = Modifier,
    keysEnabled: Boolean = true,
    showKeys: Boolean = true,
    onMicTap: () -> Unit = {},
    micEnabled: Boolean = true,
    dictationPhase: SessionBarDictationPhase = SessionBarDictationPhase.Idle,
    dictationText: String = "",
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(color = PocketShellColors.Surface)
            .border(border = BorderStroke(1.dp, PocketShellColors.Border))
            .testTag(SESSION_TERMINAL_BAR_TAG)
            .padding(all = PocketShellSpacing.sm),
    ) {
        // #2475: the dictation status strip sits ABOVE the key row — the key
        // row already fills the narrowest supported phone exactly, so a long
        // partial must never compete with the keys for width. This strip is
        // the ONLY surface partial transcription results ever render on.
        if (dictationPhase != SessionBarDictationPhase.Idle || dictationText.isNotBlank()) {
            DictationChip(
                phase = dictationPhase,
                text = dictationText,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = PocketShellSpacing.sm)
                    .testTag(SESSION_BAR_DICTATION_CHIP_TAG),
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The 48dp touch floor is carried by this tagged box, not by the
            // button: PocketShellButton's compact internals keep their own
            // dense bounds, and the floor must be measurable on the bar's own
            // node. Exact size, not a minimum — a minimum lets the Material
            // 58dp default leak past the floor the #2612 box meant to set.
            Box(
                modifier = Modifier
                    .size(PocketShellDensity.tapTargetMin)
                    .testTag(SESSION_BAR_COMPOSE_TAG),
            ) {
                PocketShellButton(
                    onClick = onOpenComposer,
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { contentDescription = SESSION_BAR_COMPOSE_LABEL },
                    variant = ButtonVariant.Primary,
                    compact = true,
                ) {
                    Icon(
                        imageVector = PocketShellIcons.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            if (showKeys) {
                NavKeySlot(
                    navKey = SessionNavKey.ArrowUp,
                    label = "↑",
                    contentDescription = "Up arrow",
                    enabled = keysEnabled,
                    onTap = { onKey(SessionNavKey.ArrowUp) },
                    modifier = Modifier
                        .width(PocketShellDensity.tapTargetMin)
                        .testTag(SESSION_BAR_ARROW_UP_TAG),
                )
                NavKeySlot(
                    navKey = SessionNavKey.ArrowDown,
                    label = "↓",
                    contentDescription = "Down arrow",
                    enabled = keysEnabled,
                    onTap = { onKey(SessionNavKey.ArrowDown) },
                    modifier = Modifier
                        .width(PocketShellDensity.tapTargetMin)
                        .testTag(SESSION_BAR_ARROW_DOWN_TAG),
                )
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(24.dp)
                        .background(PocketShellColors.Border)
                        .testTag(SESSION_BAR_ENTER_DIVIDER_TAG),
                )
                NavKeySlot(
                    navKey = SessionNavKey.Enter,
                    label = "Enter",
                    contentDescription = "Enter",
                    enabled = keysEnabled,
                    onTap = { onKey(SessionNavKey.Enter) },
                    modifier = Modifier
                        .width(PocketShellDensity.tapTargetMin)
                        .testTag(SESSION_BAR_ENTER_TAG),
                )
                Box(
                    modifier = Modifier
                        .size(PocketShellDensity.tapTargetMin)
                        .testTag(SESSION_BAR_MORE_KEYS_TAG),
                ) {
                    PocketShellButton(
                        onClick = onMoreKeys,
                        modifier = Modifier
                            .fillMaxSize()
                            .semantics { contentDescription = SESSION_BAR_MORE_KEYS_LABEL },
                        variant = ButtonVariant.Secondary,
                        compact = true,
                    ) {
                        Icon(
                            imageVector = PocketShellIcons.Keyboard,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            DictationMicSlot(
                phase = dictationPhase,
                enabled = micEnabled,
                onTap = onMicTap,
                modifier = Modifier
                    .size(PocketShellDensity.tapTargetMin)
                    .testTag(SESSION_BAR_MIC_TAG),
            )
        }
    }
}

/**
 * The bar's dictation mic (#2475). Idle is the quiet chrome treatment;
 * Listening tints the glyph accent (a live mic is the app actively working)
 * and Transcribing tints it amber (the user is blocked on the transcript) —
 * the same waiting/working colour semantics [AgentStateChip] uses, so the
 * bar's state reads with the kit's existing vocabulary.
 */
@Composable
private fun DictationMicSlot(
    phase: SessionBarDictationPhase,
    enabled: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PocketShellButton(
        onClick = onTap,
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = SESSION_BAR_MIC_LABEL },
        variant = ButtonVariant.Secondary,
        compact = true,
        enabled = enabled,
    ) {
        Icon(
            imageVector = PocketShellIcons.Mic,
            contentDescription = null,
            tint = when (phase) {
                SessionBarDictationPhase.Idle -> PocketShellColors.Text
                SessionBarDictationPhase.Listening -> PocketShellColors.Accent
                SessionBarDictationPhase.Transcribing -> PocketShellColors.Amber
            },
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * The dictation status strip (#2475) — the ONLY surface partial transcription
 * results are allowed to render on. Shows the live partial while listening,
 * the frozen partial (or "Transcribing…") while the recognizer resolves, or
 * a failure message while idle, until the next mic tap clears it. One line,
 * ellipsized: it is a status readout, not a transcript editor.
 */
@Composable
private fun DictationChip(
    phase: SessionBarDictationPhase,
    text: String,
    modifier: Modifier = Modifier,
) {
    val label = text.ifBlank {
        when (phase) {
            SessionBarDictationPhase.Listening -> "Listening…"
            SessionBarDictationPhase.Transcribing -> "Transcribing…"
            SessionBarDictationPhase.Idle -> ""
        }
    }
    Box(
        modifier = modifier
            // One semantics unit: TalkBack reads the partial as a single
            // announcement, and tests can assert the text on the tagged node.
            .semantics(mergeDescendants = true) {}
            .background(
                color = PocketShellColors.SurfaceElev,
                shape = PocketShellShapes.small,
            )
            .padding(horizontal = PocketShellDensity.chipPadH, vertical = PocketShellDensity.chipPadV),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = label,
            color = PocketShellColors.TextSecondary,
            style = PocketShellType.metadata,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * One bar key slot. [SessionNavKey] identity is carried for callers that need
 * it; the visual recipe is the shared [KeySlot] key treatment, at the bar's
 * 48dp touch floor.
 */
@Composable
private fun NavKeySlot(
    navKey: SessionNavKey,
    label: String,
    contentDescription: String,
    enabled: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    KeySlot(
        binding = KeyBinding(
            label = label,
            kind = when (navKey) {
                SessionNavKey.Enter -> KeyKind.Regular
                SessionNavKey.ArrowUp, SessionNavKey.ArrowDown -> KeyKind.Arrow
            },
        ),
        isActive = false,
        enabled = enabled,
        minHeight = PocketShellDensity.tapTargetMin,
        contentDescription = contentDescription,
        onTap = onTap,
        modifier = modifier,
    )
}
