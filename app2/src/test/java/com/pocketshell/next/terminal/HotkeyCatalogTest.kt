package com.pocketshell.next.terminal

import com.pocketshell.uikit.model.KeyKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #2612 production catalog contract: the bottom bar's three permanent
 * keys plus the floating palette's extended set, replacing the single #2521
 * sheet catalog (#1662, restored #2521).
 */
class HotkeyCatalogTest {

    @Test
    fun `the bottom bar carries exactly up down enter`() {
        assertEquals(
            listOf(KEY_LABEL_ARROW_UP, KEY_LABEL_ARROW_DOWN, KEY_LABEL_ENTER),
            SESSION_BAR_NAV_KEYS.map { it.label },
        )
        assertEquals(
            "↑/↓ must be arrows, Enter regular",
            listOf(KeyKind.Arrow, KeyKind.Arrow, KeyKind.Regular),
            SESSION_BAR_NAV_KEYS.map { it.kind },
        )
    }

    @Test
    fun `palette main page is the extended set minus the bar keys`() {
        assertEquals(listOf("ARROWS", "KEYS", "CTRL"), HOTKEY_PALETTE_MAIN_SECTIONS.map { it.title })
        assertEquals(
            listOf(
                "←", "→",
                "Esc", "Tab", "⇧Tab",
                "^B", "^C", "^D", "^Q", "^X",
            ),
            HOTKEY_PALETTE_MAIN_SECTIONS.flatMap { section -> section.keys.map { it.label } },
        )
        assertTrue(HOTKEY_PALETTE_MAIN_SECTIONS.first().keys.all { it.kind == KeyKind.Arrow })
    }

    @Test
    fun `ctrl page preserves qwerty rows and every previously reachable control letter`() {
        val section = HOTKEY_CTRL_SECTIONS.single()
        assertEquals(
            listOf("QWERT", "YUIOP", "ASDFG", "HJKL", "ZXCVB", "NM\\"),
            section.rows.map { row -> row.joinToString("") { it.label.removePrefix("^") } },
        )
        assertEquals(
            (('A'..'Z').map { "^$it" } + "^\\").toSet(),
            section.keys.map { it.label }.toSet(),
        )
    }

    /**
     * #2612 acceptance bar: the bar + palette split must not silently remove
     * any control the old #1662/#2521 sheet exposed — the old catalog is a
     * subset of (bar ∪ palette ∪ ctrl page).
     */
    @Test
    fun `bar plus palette plus ctrl page preserves every previously reachable key`() {
        val oldCatalog = setOf(
            "←", "↑", "↓", "→",
            "Esc", "Tab", "⇧Tab", "Enter",
            "^B", "^C", "^D", "^Q", "^X",
        ) + HOTKEY_CTRL_SECTIONS.flatMap { it.keys }.map { it.label }
        val newCatalog = (
            SESSION_BAR_NAV_KEYS +
                HOTKEY_PALETTE_MAIN_SECTIONS.flatMap { it.keys } +
                HOTKEY_CTRL_SECTIONS.flatMap { it.keys }
            ).map { it.label }.toSet()

        assertEquals(
            "every previously reachable key must survive the sheet-to-bar/palette split",
            emptySet<String>(),
            oldCatalog - newCatalog,
        )
    }

    @Test
    fun `duplicated old catalog and literal letters are gone`() {
        val visible = (
            SESSION_BAR_NAV_KEYS +
                HOTKEY_PALETTE_MAIN_SECTIONS.flatMap { it.keys } +
                HOTKEY_CTRL_SECTIONS.flatMap { it.keys }
            ).map { it.label }

        listOf(
            KEY_LABEL_INTERRUPT_X2,
            KEY_LABEL_EOF_X2,
            "Ctrl",
            "a",
            "z",
        ).forEach { obsolete ->
            assertFalse("$obsolete must not remain a visible tile", visible.contains(obsolete))
        }
        assertEquals(2, visible.count { it == "^C" })
        assertEquals(2, visible.count { it == "^D" })
    }
}
