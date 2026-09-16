package com.pocketshell.next.terminal

import com.pocketshell.uikit.components.HotkeyLongPressAction
import com.pocketshell.uikit.components.HotkeySection
import com.pocketshell.uikit.model.KeyBinding
import com.pocketshell.uikit.model.KeyKind

/** Visible label for the dedicated Ctrl-picker action (#1662). */
const val HOTKEY_CTRL_FLOW_LABEL: String = "Ctrl+…"

/**
 * Issue #2612: the three highest-frequency menu-navigation keys live
 * permanently on the session bottom bar, one tap each — no panel to open or
 * close around a terminal menu. Bytes route through [navKeyBytes].
 */
val SESSION_BAR_NAV_KEYS: List<KeyBinding> = listOf(
    KeyBinding(label = KEY_LABEL_ARROW_UP, kind = KeyKind.Arrow),
    KeyBinding(label = KEY_LABEL_ARROW_DOWN, kind = KeyKind.Arrow),
    KeyBinding(label = KEY_LABEL_ENTER, kind = KeyKind.Regular),
)

/**
 * Issue #2612 palette main page: the extended key set MINUS what the bottom
 * bar already carries permanently (↑ / ↓ / Enter — see
 * [SESSION_BAR_NAV_KEYS]).
 *
 * Ported from the #1662 sheet catalog with those three removed. Every
 * previously reachable key stays reachable: bar + palette + Ctrl page cover
 * exactly the old sheet's catalog (pinned by [HotkeyCatalogTest]).
 */
val HOTKEY_PALETTE_MAIN_SECTIONS: List<HotkeySection> = listOf(
    HotkeySection(
        title = "ARROWS",
        keys = listOf(
            KeyBinding(label = KEY_LABEL_ARROW_LEFT, kind = KeyKind.Arrow),
            KeyBinding(label = KEY_LABEL_ARROW_RIGHT, kind = KeyKind.Arrow),
        ),
        columns = 2,
    ),
    HotkeySection(
        title = "KEYS",
        keys = listOf(
            KeyBinding(label = KEY_LABEL_ESC, kind = KeyKind.Regular),
            KeyBinding(label = KEY_LABEL_TAB, kind = KeyKind.Regular),
            KeyBinding(label = KEY_LABEL_SHIFT_TAB, kind = KeyKind.Regular),
        ),
        columns = 3,
    ),
    HotkeySection(
        title = "CTRL",
        keys = listOf(
            KeyBinding(label = "^B", kind = KeyKind.Regular),
            KeyBinding(label = "^C", kind = KeyKind.Regular),
            KeyBinding(label = "^D", kind = KeyKind.Regular),
            KeyBinding(label = "^Q", kind = KeyKind.Regular),
            KeyBinding(label = "^X", kind = KeyKind.Regular),
        ),
        columns = 5,
    ),
)

private val CTRL_ROWS: List<String> = listOf("QWERT", "YUIOP", "ASDFG", "HJKL", "ZXCVB", "NM\\")

private fun controlBindings(keys: String): List<KeyBinding> =
    keys.map { key -> KeyBinding("^$key", KeyKind.Regular) }

/**
 * Issue #1662 Ctrl page: every control chord the old sheet exposed, arranged
 * by QWERTY muscle memory in five columns. Labels include the caret so the
 * same `^<char>` parser handles both pages.
 */
val HOTKEY_CTRL_SECTIONS: List<HotkeySection> = listOf(
    HotkeySection(
        title = "CTRL + KEY",
        keys = CTRL_ROWS.flatMap(::controlBindings),
        columns = 5,
        rows = CTRL_ROWS.map(::controlBindings),
    ),
)

/**
 * The palette's hold cues (from the #2521 sheet): long-press `^C` / `^D`
 * sends the doubled control bytes.
 */
val HOTKEY_LONG_PRESS_ACTIONS: Map<String, HotkeyLongPressAction> = mapOf(
    "^C" to HotkeyLongPressAction(
        cue = "hold ×2",
        accessibilityLabel = "Send Ctrl-C twice",
    ),
    "^D" to HotkeyLongPressAction(
        cue = "hold ×2",
        accessibilityLabel = "Send Ctrl-D twice",
    ),
)
