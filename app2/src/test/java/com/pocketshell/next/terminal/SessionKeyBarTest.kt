package com.pocketshell.next.terminal

import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.next.composer.ComposerUiState
import com.pocketshell.uikit.components.SESSION_BAR_ARROW_DOWN_TAG
import com.pocketshell.uikit.components.SESSION_BAR_ARROW_UP_TAG
import com.pocketshell.uikit.components.SESSION_BAR_COMPOSE_TAG
import com.pocketshell.uikit.components.SESSION_BAR_ENTER_TAG
import com.pocketshell.uikit.components.SESSION_BAR_MORE_KEYS_TAG
import com.pocketshell.uikit.components.SESSION_TERMINAL_BAR_TAG
import com.pocketshell.uikit.components.TERMINAL_HOTKEYS_PALETTE_TAG
import com.pocketshell.uikit.components.TerminalHotkeysPaletteOverlay
import com.pocketshell.uikit.model.KeyBinding
import com.pocketshell.uikit.theme.PocketShellTheme
import com.termux.view.TerminalView
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The #2612 bottom terminal bar + floating hotkeys palette wired into the
 * session screen.
 *
 * `KeyBytesTest` pins what each key MEANS; `navKeyBytes` pins the bar's
 * three persistent keys. This pins that the REAL bar and the REAL palette
 * are on the screen, that a tap reaches [keyBarBytes] on the PTY path, and
 * that the palette neither steals terminal rows nor closes itself after a
 * key. J03 proves the same round trip end to end against a real host.
 */
@RunWith(AndroidJUnit4::class)
class SessionKeyBarTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var rootView: View? = null

    @Test
    fun `the bottom bar is present while still attaching`() {
        setContent(SessionUiState.Connecting)

        composeRule.onNodeWithTag(SESSION_TERMINAL_BAR_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SESSION_BAR_COMPOSE_TAG).assertIsDisplayed()
    }

    @Test
    fun `the bottom bar is present once the session is live`() {
        setContent(SessionUiState.Live(createRemoteTerminalSession()))

        composeRule.onNodeWithTag(SESSION_TERMINAL_BAR_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SESSION_BAR_COMPOSE_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SESSION_BAR_ARROW_UP_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SESSION_BAR_ARROW_DOWN_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SESSION_BAR_ENTER_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SESSION_BAR_MORE_KEYS_TAG).assertIsDisplayed()
    }

    /**
     * #2612: the palette-only keys stay OFF the always-visible chrome — only
     * ↑ / ↓ / Enter are permanent now. (Esc / Tab / Ctrl live in the floating
     * palette behind More keys.)
     */
    @Test
    fun `closed chrome shows the nav keys but no palette keys`() {
        setContent(SessionUiState.Live(createRemoteTerminalSession()))

        composeRule.onNodeWithText(KEY_LABEL_ENTER).assertIsDisplayed()
        listOf("Ctrl", "Esc", "Tab").forEach { label ->
            composeRule.onNodeWithText(label).assertDoesNotExist()
        }
    }

    @Test
    fun `a failed session has no key controls`() {
        setContent(SessionUiState.Failed("Session \"$SESSION\" ended (exit 3)."))

        composeRule.onNodeWithTag(SESSION_BAR_MORE_KEYS_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(SESSION_BAR_ARROW_UP_TAG).assertDoesNotExist()
    }

    @Test
    fun `tapping up on the bar sends the CSI up sequence`() {
        val sent = mutableListOf<ByteArray>()
        setContent(SessionUiState.Live(createRemoteTerminalSession()), onHotkeySend = { sent += it })

        composeRule.onNodeWithTag(SESSION_BAR_ARROW_UP_TAG).performClick()
        composeRule.waitForIdle()

        assertArrayEquals(byteArrayOf(0x1B, '['.code.toByte(), 'A'.code.toByte()), sent.single())
    }

    @Test
    fun `tapping down on the bar sends the CSI down sequence`() {
        val sent = mutableListOf<ByteArray>()
        setContent(SessionUiState.Live(createRemoteTerminalSession()), onHotkeySend = { sent += it })

        composeRule.onNodeWithTag(SESSION_BAR_ARROW_DOWN_TAG).performClick()
        composeRule.waitForIdle()

        assertArrayEquals(byteArrayOf(0x1B, '['.code.toByte(), 'B'.code.toByte()), sent.single())
    }

    @Test
    fun `tapping enter on the bar sends carriage return`() {
        val sent = mutableListOf<ByteArray>()
        setContent(SessionUiState.Live(createRemoteTerminalSession()), onHotkeySend = { sent += it })

        composeRule.onNodeWithTag(SESSION_BAR_ENTER_TAG).performClick()
        composeRule.waitForIdle()

        assertArrayEquals(byteArrayOf(0x0D), sent.single())
    }

    @Test
    fun `tapping the more keys chip opens the palette`() {
        setContent(SessionUiState.Live(createRemoteTerminalSession()))

        composeRule.onNodeWithTag(SESSION_BAR_MORE_KEYS_TAG).performClick()
        composeRule.waitForIdle()

        // Existence, not assertIsDisplayed: Robolectric's display predicate
        // disagrees with the real layout while the TerminalView AndroidView
        // shares the screen (bounds pinned green in SessionScreenTest's
        // docks/palette test; the emulator pass is the visual acceptance).
        composeRule.onNodeWithTag(TERMINAL_HOTKEYS_PALETTE_TAG).assertExists()
        composeRule.onNodeWithText("^C").assertExists()
        composeRule.onNodeWithText(KEY_LABEL_SHIFT_TAB).assertExists()
    }

    @Test
    fun `tapping escape on the palette sends the escape byte and stays open`() {
        val sent = tapPaletteKey(KEY_LABEL_ESC)
        assertEquals(1, sent.size)
        assertArrayEquals(byteArrayOf(0x1B), sent.single())
        composeRule.onNodeWithTag(TERMINAL_HOTKEYS_PALETTE_TAG).assertIsDisplayed()
    }

    @Test
    fun `tapping caret-C on the palette sends the interrupt byte`() {
        val sent = tapPaletteKey("^C")
        assertArrayEquals(byteArrayOf(0x03), sent.single())
        composeRule.onNodeWithTag(TERMINAL_HOTKEYS_PALETTE_TAG).assertIsDisplayed()
    }

    @Test
    fun `opening the composer overlay does not change the terminal slot size`() {
        val sizes = mutableListOf<Pair<Int, Int>>()
        setContent(
            SessionUiState.Connecting,
            onResized = { c, r -> sizes += c to r },
            cellMetrics = NARROW_CELLS,
        )
        composeRule.waitForIdle()
        assertTrue("no size was reported while attaching", sizes.isNotEmpty())
        val closedSize = sizes.last()

        composeRule.onNodeWithTag(SESSION_BAR_COMPOSE_TAG).performClick()
        composeRule.waitForIdle()

        assertEquals(
            "opening the composer sheet must not steal terminal rows",
            closedSize,
            sizes.last(),
        )
    }

    @Test
    fun `opening the palette does not change the terminal slot size`() {
        val sizes = mutableListOf<Pair<Int, Int>>()
        setContent(
            SessionUiState.Connecting,
            onResized = { c, r -> sizes += c to r },
            cellMetrics = NARROW_CELLS,
        )
        composeRule.waitForIdle()
        val closedSize = sizes.last()

        composeRule.onNodeWithTag(SESSION_BAR_MORE_KEYS_TAG).performClick()
        composeRule.waitForIdle()

        assertEquals(
            "opening the floating palette must not steal terminal rows",
            closedSize,
            sizes.last(),
        )
    }

    @Test
    fun `the terminal slot reports its size while still attaching`() {
        val sizes = mutableListOf<Pair<Int, Int>>()
        setContent(
            SessionUiState.Connecting,
            onResized = { c, r -> sizes += c to r },
            cellMetrics = NARROW_CELLS,
        )

        assertTrue("no size was reported while attaching", sizes.isNotEmpty())
        val (cols, rows) = sizes.last()
        assertTrue("columns must be usable, got $cols", cols >= MIN_TERMINAL_CELLS)
        assertTrue("rows must be usable, got $rows", rows >= MIN_TERMINAL_CELLS)
    }

    @Test
    fun `the reported columns are the measured viewport divided by the glyph width`() {
        val sizes = mutableListOf<Pair<Int, Int>>()
        setContent(
            SessionUiState.Connecting,
            onResized = { c, r -> sizes += c to r },
            cellMetrics = NARROW_CELLS,
        )

        val viewportWidthPx = requireNotNull(rootView).width
        assertTrue("Robolectric gave the composition no width", viewportWidthPx > 0)
        val expected = maxOf(
            MIN_TERMINAL_CELLS,
            (viewportWidthPx / NARROW_CELLS.cellWidthPx).toInt(),
        )

        assertEquals(expected, sizes.last().first)
    }

    @Test
    fun `the screen stops estimating the size once the terminal exists`() {
        val sizes = mutableListOf<Pair<Int, Int>>()
        setContent(
            SessionUiState.Live(createRemoteTerminalSession()),
            onResized = { c, r -> sizes += c to r },
        )

        composeRule.waitForIdle()
        assertTrue("the estimate must not run while live, got $sizes", sizes.isEmpty())
    }

    @Test
    fun `an unmodified keystroke does not go through the hotkey send path`() {
        val sent = mutableListOf<ByteArray>()
        setContent(SessionUiState.Live(createRemoteTerminalSession()), onHotkeySend = { sent += it })

        typeCodePoint('c')

        assertTrue("unmodified typing must not be re-routed, got $sent", sent.isEmpty())
    }

    /**
     * Drive the production palette + [keyBarBytes] without the full screen.
     * The palette is the real overlay; bytes are captured exactly as the
     * session screen routes them.
     */
    private fun tapPaletteKey(label: String): List<ByteArray> {
        val sent = mutableListOf<ByteArray>()
        composeRule.setContent {
            PocketShellTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    TerminalHotkeysPaletteOverlay(
                        mainSections = HOTKEY_PALETTE_MAIN_SECTIONS,
                        ctrlSections = HOTKEY_CTRL_SECTIONS,
                        onKey = { binding: KeyBinding ->
                            keyBarBytes(binding.label)?.let { sent += it }
                        },
                        onLongKey = { binding: KeyBinding ->
                            when (binding.label) {
                                "^C" -> keyBarBytes(KEY_LABEL_INTERRUPT_X2)?.let { sent += it }
                                "^D" -> keyBarBytes(KEY_LABEL_EOF_X2)?.let { sent += it }
                            }
                        },
                        onClose = {},
                        longPressActions = HOTKEY_LONG_PRESS_ACTIONS,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(label).performClick()
        composeRule.waitForIdle()
        return sent
    }

    private fun typeCodePoint(character: Char) {
        val view = requireNotNull(terminalView()) { "no TerminalView in the composition" }
        composeRule.runOnUiThread {
            view.inputCodePoint(0, character.code, false, false)
        }
        composeRule.waitForIdle()
    }

    private fun terminalView(): TerminalView? {
        val root = requireNotNull(rootView) { "the composition never reported its View" }
        return findTerminalView(root.rootView)
    }

    private fun findTerminalView(view: View): TerminalView? {
        if (view is TerminalView) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findTerminalView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun setContent(
        state: SessionUiState,
        onResized: (Int, Int) -> Unit = { _, _ -> },
        onHotkeySend: (ByteArray) -> Unit = {},
        cellMetrics: TerminalCellMetrics = NARROW_CELLS,
        initiallyShowComposer: Boolean = false,
        initiallyShowHotkeys: Boolean = false,
    ) {
        composeRule.setContent {
            rootView = LocalView.current
            PocketShellTheme {
                SessionScreen(
                    state = state,
                    composerState = ComposerUiState(),
                    sessionName = SESSION,
                    onBack = {},
                    onResized = onResized,
                    onRetry = {},
                    onHotkeySend = onHotkeySend,
                    onDraftChange = {},
                    onSend = { true },
                    onInsert = {},
                    onAttach = {},
                    onMicTap = {},
                    onCancelRecording = {},
                    onToggleHistory = {},
                    onTogglePreview = {},
                    onRemoveAttachment = {},
                    onDismissNotice = {},
                    onDiscardDraft = {},
                    onUseHistoryEntry = {},
                    cellMetrics = cellMetrics,
                    initiallyShowComposer = initiallyShowComposer,
                    initiallyShowHotkeys = initiallyShowHotkeys,
                )
            }
        }
        composeRule.waitForIdle()
    }

    private companion object {
        const val SESSION = "git-pocketshell"

        val NARROW_CELLS = TerminalCellMetrics(
            cellWidthPx = 16.8f,
            lineHeightPx = 31,
            lineSpacingAndAscentPx = 4,
        )
    }
}
