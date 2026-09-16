package com.pocketshell.uikit.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.icons.PocketShellIcons
import com.pocketshell.uikit.model.KeyBinding
import com.pocketshell.uikit.model.KeyKind
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellSpacing

/** Stable test tags for the session bottom terminal bar (#2612). */
const val SESSION_TERMINAL_BAR_TAG: String = "session-terminal-bar"
const val SESSION_BAR_COMPOSE_TAG: String = "session-bar-compose"
const val SESSION_BAR_ARROW_UP_TAG: String = "session-bar-arrow-up"
const val SESSION_BAR_ARROW_DOWN_TAG: String = "session-bar-arrow-down"
const val SESSION_BAR_ENTER_TAG: String = "session-bar-enter"
const val SESSION_BAR_MORE_KEYS_TAG: String = "session-bar-more-keys"
const val SESSION_BAR_ENTER_DIVIDER_TAG: String = "session-bar-enter-divider"

/** Visible label semantics for the composer launcher in the bar. */
const val SESSION_BAR_COMPOSE_LABEL: String = "Prompt Composer"

/** Visible label semantics for the More keys affordance in the bar. */
const val SESSION_BAR_MORE_KEYS_LABEL: String = "More terminal keys"

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
 * opens the floating hotkeys palette) complete the row:
 *
 * ```
 * [Compose]  [ ↑ ]  [ ↓ ]  |  [Enter]  [More keys]
 * ```
 *
 * - The arrow glyphs route as [SessionNavKey.ArrowUp] / [ArrowDown]; `Enter`
 *   is separated from the arrows by a hairline divider so a rushed tap cannot
 *   confirm a selection by accident.
 * - [keysEnabled] mutes the key cluster while the session cannot receive
 *   bytes (attaching / reconnecting); the callbacks simply do not fire.
 * - [showKeys] = false removes the whole key cluster *and* `More keys`,
 *   leaving the composer launcher alone — the user's "no key chrome" setting
 *   and the failed-session state both use it.
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
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(color = PocketShellColors.Surface)
            .border(border = BorderStroke(1.dp, PocketShellColors.Border))
            .testTag(SESSION_TERMINAL_BAR_TAG)
            .padding(horizontal = PocketShellSpacing.md, vertical = PocketShellSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The 48dp touch floor is carried by this tagged box, not by the
        // button: PocketShellButton's compact internals keep their own dense
        // bounds, and the floor must be measurable on the bar's own node.
        Box(
            modifier = Modifier
                .defaultMinSize(minWidth = PocketShellDensity.tapTargetMin)
                .testTag(SESSION_BAR_COMPOSE_TAG),
        ) {
            PocketShellButton(
                onClick = onOpenComposer,
                modifier = Modifier.semantics { contentDescription = SESSION_BAR_COMPOSE_LABEL },
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
                    .weight(1f)
                    .testTag(SESSION_BAR_ARROW_UP_TAG),
            )
            NavKeySlot(
                navKey = SessionNavKey.ArrowDown,
                label = "↓",
                contentDescription = "Down arrow",
                enabled = keysEnabled,
                onTap = { onKey(SessionNavKey.ArrowDown) },
                modifier = Modifier
                    .weight(1f)
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
                    .weight(1.6f)
                    .testTag(SESSION_BAR_ENTER_TAG),
            )
            Box(
                modifier = Modifier
                    .defaultMinSize(minWidth = PocketShellDensity.tapTargetMin)
                    .testTag(SESSION_BAR_MORE_KEYS_TAG),
            ) {
                PocketShellButton(
                    onClick = onMoreKeys,
                    modifier = Modifier.semantics { contentDescription = SESSION_BAR_MORE_KEYS_LABEL },
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
