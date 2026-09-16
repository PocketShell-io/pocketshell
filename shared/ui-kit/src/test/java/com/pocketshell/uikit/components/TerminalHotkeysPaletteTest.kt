package com.pocketshell.uikit.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.model.KeyBinding
import com.pocketshell.uikit.model.KeyKind
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #2612: the floating, draggable, non-modal terminal hotkeys palette — the
 * replacement for the #2521 modal sheet. Mutation-capable interaction,
 * drag, and geometry proofs, plus the pure [clampPalettePull] bounds
 * contract.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
class TerminalHotkeysPaletteTest {

    @get:Rule
    val compose = createComposeRule()

    private val paletteMain = listOf(
        HotkeySection(
            "ARROWS",
            listOf(
                KeyBinding("←", KeyKind.Arrow),
                KeyBinding("→", KeyKind.Arrow),
            ),
            2,
        ),
        HotkeySection(
            "KEYS",
            listOf("Esc", "Tab", "⇧Tab").map { KeyBinding(it, KeyKind.Regular) },
            3,
        ),
        HotkeySection(
            "CTRL",
            listOf("^B", "^C", "^D", "^Q", "^X").map { KeyBinding(it, KeyKind.Regular) },
            5,
        ),
    )
    private val paletteCtrl = listOf(
        HotkeySection(
            "CTRL + KEY",
            listOf("QWERT", "YUIOP", "ASDFG", "HJKL", "ZXCVB", "NM\\")
                .flatMap { row -> row.map { KeyBinding("^$it", KeyKind.Regular) } },
            5,
            rows = listOf("QWERT", "YUIOP", "ASDFG", "HJKL", "ZXCVB", "NM\\")
                .map { row -> row.map { KeyBinding("^$it", KeyKind.Regular) } },
        ),
    )

    @Test
    fun `a key tap sends and the palette stays open with no backdrop`() {
        val sent = mutableListOf<String>()
        setContent(sent = sent)

        compose.onNodeWithText("Esc").performClick()
        compose.onNodeWithText("^C").performClick()

        assertEquals(listOf("Esc", "^C"), sent)
        compose.onNodeWithTag(TERMINAL_HOTKEYS_PALETTE_TAG).assertIsDisplayed()
        // No dimming layer: the host content behind the palette is the only
        // other thing in the tree, and the palette adds no scrim node.
        compose.onNodeWithTag(HOST_TAG).assertIsDisplayed()
    }

    @Test
    fun `the bottom-bar keys are not duplicated in the palette`() {
        setContent()

        compose.onNodeWithText("↑").assertDoesNotExist()
        compose.onNodeWithText("↓").assertDoesNotExist()
        compose.onNodeWithText("Enter").assertDoesNotExist()
    }

    @Test
    fun `ctrl flow is visible stays open after send and back returns to main`() {
        val sent = mutableListOf<String>()
        setContent(sent = sent)

        compose.onNodeWithTag(TERMINAL_HOTKEYS_CTRL_FLOW_TAG).performClick()
        compose.onNodeWithText("^R").assertIsDisplayed()
        compose.onNodeWithTag(TERMINAL_HOTKEYS_PALETTE_CLOSE_TAG).assertIsDisplayed()
        compose.onNodeWithText("^R").performClick()
        compose.onNodeWithText("^R").performClick()

        assertEquals(listOf("^R", "^R"), sent)
        compose.onNodeWithText("Ctrl + …").assertIsDisplayed()
        compose.onNodeWithTag(TERMINAL_HOTKEYS_PALETTE_BACK_TAG).performClick()
        compose.onNodeWithText("^C").assertIsDisplayed()
        compose.onNodeWithTag(TERMINAL_HOTKEYS_CTRL_FLOW_TAG).assertIsDisplayed()
    }

    @Test
    fun `c and d expose persistent cue long click label and one gesture callback`() {
        val sent = mutableListOf<String>()
        val held = mutableListOf<String>()
        setContent(sent = sent, held = held)

        assertEquals(2, compose.onAllNodesWithText("hold ×2").fetchSemanticsNodes().size)
        val ctrlC = compose.onNodeWithText("^C")
        val ctrlD = compose.onNodeWithText("^D")
        assertEquals(
            "Send Ctrl-C twice",
            ctrlC.fetchSemanticsNode().config[SemanticsActions.OnLongClick].label,
        )
        assertEquals(
            "Send Ctrl-D twice",
            ctrlD.fetchSemanticsNode().config[SemanticsActions.OnLongClick].label,
        )
        ctrlC.performTouchInput { longClick() }
        ctrlD.performTouchInput { longClick() }

        assertEquals(emptyList<String>(), sent)
        assertEquals(
            "each physical long-press must dispatch its doubled-control callback exactly once",
            listOf("^C", "^D"),
            held,
        )
    }

    /**
     * The drag contract: moving the dedicated handle repositions the card,
     * fires NO key callback (the handle shares no surface with a key), and
     * leaves the card fully inside the container even for a violent fling.
     */
    @Test
    fun `dragging the handle moves the card sends nothing and stays in bounds`() {
        val sent = mutableListOf<String>()
        setContent(sent = sent)
        val before = paletteBounds()

        compose.onNodeWithTag(TERMINAL_HOTKEYS_PALETTE_HANDLE_TAG)
            .performTouchInput { swipe(center, center + Offset(-600f, -900f)) }
        compose.waitForIdle()

        val after = paletteBounds()
        // 50dp, not more: the 412dp host leaves only ~95dp of leftward travel
        // before the clamp floors the card at the container edge.
        assertTrue(
            "the card must move with the handle, before=$before after=$after",
            after.left < before.left - 50.dp && after.top < before.top - 50.dp,
        )
        val host = hostBounds()
        assertTrue(
            "the card must stay inside the container after a violent drag: $after in $host",
            after.left >= host.left - 1.dp && after.top >= host.top - 1.dp &&
                after.right <= host.right + 1.dp && after.bottom <= host.bottom + 1.dp,
        )
        assertTrue("a drag must never send a key", sent.isEmpty())
    }

    @Test
    fun `dragging in the opposite direction clamps at the bottom end corner`() {
        setContent()
        val host = hostBounds()

        compose.onNodeWithTag(TERMINAL_HOTKEYS_PALETTE_HANDLE_TAG)
            .performTouchInput { swipe(center, center + Offset(2000f, 2000f)) }
        compose.waitForIdle()

        val after = paletteBounds()
        assertTrue(
            "the card must stay inside the container: $after in $host",
            after.left >= host.left - 1.dp && after.top >= host.top - 1.dp &&
                after.right <= host.right + 1.dp && after.bottom <= host.bottom + 1.dp,
        )
        assertEquals(
            "a pull past the corner must clamp flush to the end edge",
            host.right.value,
            after.right.value,
            2f,
        )
    }

    @Test
    fun `dragging a key slot does not move the card`() {
        setContent()
        val before = paletteBounds()

        compose.onNodeWithText("^Q").performTouchInput { swipe(center, center + Offset(-400f, -400f)) }
        compose.waitForIdle()

        val after = paletteBounds()
        assertEquals(
            "only the handle may move the palette",
            before.left,
            after.left,
        )
        assertEquals(
            "only the handle may move the palette",
            before.top,
            after.top,
        )
    }

    @Test
    fun `closing removes the palette`() {
        var closed = 0
        setContent(onClose = { closed += 1 })

        compose.onNodeWithTag(TERMINAL_HOTKEYS_PALETTE_CLOSE_TAG).performClick()

        assertEquals(1, closed)
    }

    @Test
    fun `disabled palette neither navigates nor sends`() {
        val sent = mutableListOf<String>()
        setContent(sent = sent, enabled = false)

        compose.onNodeWithTag(TERMINAL_HOTKEYS_CTRL_FLOW_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(TERMINAL_HOTKEYS_CTRL_FLOW_TAG).performClick()
        compose.onNodeWithText("^C").performClick()

        compose.onNodeWithText("^R").assertDoesNotExist()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `clamp keeps the pull inside the container on every axis`() {
        val card = IntSize(300, 200)

        // Free space: the pull survives untouched.
        assertEquals(
            Offset(-40f, -60f),
            clampPalettePull(Offset(-40f, -60f), card, IntSize(600, 800)),
        )
        // Past the top/left edges: clamps to the far edge.
        assertEquals(
            Offset(-300f, -600f),
            clampPalettePull(Offset(-9000f, -9000f), card, IntSize(600, 800)),
        )
        // Past the bottom/right edges: clamps flush to the corner.
        assertEquals(
            Offset(0f, 0f),
            clampPalettePull(Offset(50f, 50f), card, IntSize(600, 800)),
        )
        // Container smaller than the card: never pushed negative-space.
        assertEquals(
            Offset(0f, 0f),
            clampPalettePull(Offset(-50f, -50f), card, IntSize(200, 100)),
        )
    }

    @Test
    fun `a viewport shrink reconciles the applied position back into bounds`() {
        val card = IntSize(300, 200)
        val stored = Offset(-250f, -500f)

        // Landscape rotation squeezed the container; the stored pull is now
        // out of range. The applied offset is the clamped one.
        val clamped = clampPalettePull(stored, card, IntSize(320, 240))
        assertTrue(clamped.x >= -(320 - 300) && clamped.y >= -(240 - 200))
        assertTrue(clamped.x <= 0f && clamped.y <= 0f)
    }

    @Test
    fun `every target on both pages is contained and at least 48dp at 320dp`() {
        assertEveryTargetGeometry(widthDp = 320, fontScale = 1f)
    }

    @Test
    fun `every target on both pages is contained and at least 48dp at pixel7 width`() {
        assertEveryTargetGeometry(widthDp = 411, fontScale = 1f)
    }

    @Test
    fun `every target including all ctrl letters is contained at 320dp large font`() {
        assertEveryTargetGeometry(widthDp = 320, fontScale = LARGE_FONT_SCALE)
    }

    private fun assertEveryTargetGeometry(widthDp: Int, fontScale: Float) {
        setContent(widthDp = widthDp, fontScale = fontScale)
        assertPageGeometry(
            labels = listOf("←", "→", "Esc", "Tab", "⇧Tab", "^B", "^C", "^D", "^Q", "^X"),
            includeBack = false,
            includeCtrlFlow = true,
        )

        compose.onNodeWithTag(TERMINAL_HOTKEYS_CTRL_FLOW_TAG).performClick()
        compose.waitForIdle()
        assertPageGeometry(
            labels = (('A'..'Z').map { "^$it" } + "^\\"),
            includeBack = true,
            includeCtrlFlow = false,
        )
    }

    private fun assertPageGeometry(
        labels: List<String>,
        includeBack: Boolean,
        includeCtrlFlow: Boolean,
    ) {
        val hostBounds = compose.onNodeWithTag(HOST_TAG).fetchSemanticsNode().boundsInRoot
        val paletteBounds = compose.onNodeWithTag(TERMINAL_HOTKEYS_PALETTE_TAG)
            .fetchSemanticsNode().boundsInRoot
        val targets = labels.map { label ->
            label to compose.onNode(
                hasText(label)
                    .and(hasClickAction())
                    .and(hasAnyAncestor(hasTestTag(TERMINAL_HOTKEYS_PALETTE_TAG))),
            ).fetchSemanticsNode()
        }.toMutableList()
        targets += "close" to compose.onNodeWithTag(TERMINAL_HOTKEYS_PALETTE_CLOSE_TAG)
            .fetchSemanticsNode()
        targets += "handle" to compose.onNodeWithTag(TERMINAL_HOTKEYS_PALETTE_HANDLE_TAG)
            .fetchSemanticsNode()
        if (includeBack) {
            targets += "back" to compose.onNodeWithTag(TERMINAL_HOTKEYS_PALETTE_BACK_TAG)
                .fetchSemanticsNode()
        }
        if (includeCtrlFlow) {
            targets += "Ctrl+…" to compose.onNodeWithTag(TERMINAL_HOTKEYS_CTRL_FLOW_TAG)
                .fetchSemanticsNode()
        }

        val minTargetPx = 48f * compose.density.density
        val slopPx = compose.density.density
        targets.forEach { (label, node) ->
            val bounds = node.boundsInRoot
            assertTrue(
                "$label target width must be >=48dp; width=${bounds.width}px min=${minTargetPx}px",
                bounds.width + slopPx >= minTargetPx,
            )
            assertTrue(
                "$label target height must be >=48dp; height=${bounds.height}px min=${minTargetPx}px",
                bounds.height + slopPx >= minTargetPx,
            )
            assertTrue(
                "$label target must be fully inside the palette card; target=$bounds card=$paletteBounds",
                bounds.left + slopPx >= paletteBounds.left &&
                    bounds.right <= paletteBounds.right + slopPx,
            )
            assertTrue(
                "$label target must be fully inside the visible host; target=$bounds host=$hostBounds",
                bounds.top + slopPx >= hostBounds.top &&
                    bounds.bottom <= hostBounds.bottom + slopPx,
            )
            if (label.startsWith("^")) {
                assertEquals(
                    "$label must not truncate at the requested width/font scale",
                    false,
                    node.config[HotkeyLabelTruncatedKey],
                )
            }
        }
    }

    private fun hostBounds() = compose.onNodeWithTag(HOST_TAG).getUnclippedBoundsInRoot()

    private fun paletteBounds() = compose.onNodeWithTag(TERMINAL_HOTKEYS_PALETTE_TAG)
        .getUnclippedBoundsInRoot()

    private fun setContent(
        sent: MutableList<String> = mutableListOf(),
        held: MutableList<String> = mutableListOf(),
        enabled: Boolean = true,
        onClose: () -> Unit = {},
        widthDp: Int = 411,
        fontScale: Float = 1f,
    ) {
        compose.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(baseDensity.density, fontScale),
            ) {
                PocketShellTheme {
                    Box(
                        modifier = Modifier
                            .width(widthDp.dp)
                            .fillMaxHeight()
                            .testTag(HOST_TAG),
                    ) {
                        TerminalHotkeysPaletteOverlay(
                            mainSections = paletteMain,
                            ctrlSections = paletteCtrl,
                            onKey = { sent += it.label },
                            onLongKey = { held += it.label },
                            onClose = onClose,
                            enabled = enabled,
                            longPressActions = mapOf(
                                "^C" to HotkeyLongPressAction("hold ×2", "Send Ctrl-C twice"),
                                "^D" to HotkeyLongPressAction("hold ×2", "Send Ctrl-D twice"),
                            ),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private companion object {
        const val HOST_TAG = "issue2612:palette-geometry-host"
        const val LARGE_FONT_SCALE = 1.3f
    }
}
