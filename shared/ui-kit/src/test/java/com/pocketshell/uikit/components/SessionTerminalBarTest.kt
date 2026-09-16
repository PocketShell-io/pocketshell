package com.pocketshell.uikit.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #2612: the session screen's persistent bottom terminal bar — ↑ / ↓ / Enter
 * one tap each, plus the composer launcher and the More keys affordance.
 */
@RunWith(RobolectricTestRunner::class)
class SessionTerminalBarTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `tapping each control fires exactly its own callback`() {
        val pressed = mutableListOf<SessionNavKey>()
        var composer = 0
        var moreKeys = 0
        setContent(
            onKey = { pressed += it },
            onOpenComposer = { composer += 1 },
            onMoreKeys = { moreKeys += 1 },
        )

        compose.onNodeWithTag(SESSION_BAR_ARROW_UP_TAG).performClick()
        compose.onNodeWithTag(SESSION_BAR_ARROW_DOWN_TAG).performClick()
        compose.onNodeWithTag(SESSION_BAR_ENTER_TAG).performClick()
        compose.onNodeWithTag(SESSION_BAR_COMPOSE_TAG).performClick()
        compose.onNodeWithTag(SESSION_BAR_MORE_KEYS_TAG).performClick()

        assertEquals(listOf(SessionNavKey.ArrowUp, SessionNavKey.ArrowDown, SessionNavKey.Enter), pressed)
        assertEquals(1, composer)
        assertEquals(1, moreKeys)
    }

    @Test
    fun `the bar exposes accessible labels for its icon-only controls`() {
        setContent()

        compose.onNodeWithContentDescription(SESSION_BAR_COMPOSE_LABEL).assertIsDisplayed()
        compose.onNodeWithContentDescription(SESSION_BAR_MORE_KEYS_LABEL).assertIsDisplayed()
        compose.onNodeWithContentDescription("Up arrow").assertIsDisplayed()
        compose.onNodeWithContentDescription("Down arrow").assertIsDisplayed()
        compose.onNodeWithContentDescription("Enter").assertIsDisplayed()
    }

    @Test
    fun `showKeys false leaves the composer launcher and the mic`() {
        val pressed = mutableListOf<SessionNavKey>()
        var moreKeys = 0
        setContent(
            onKey = { pressed += it },
            onMoreKeys = { moreKeys += 1 },
            showKeys = false,
        )

        compose.onNodeWithTag(SESSION_BAR_COMPOSE_TAG).assertIsDisplayed()
        // #2475: the mic is input, not a key — it travels with the launcher
        // when the "no key chrome" setting hides the key cluster.
        compose.onNodeWithTag(SESSION_BAR_MIC_TAG).assertIsDisplayed()
        compose.onNodeWithTag(SESSION_BAR_MORE_KEYS_TAG).assertDoesNotExist()
        compose.onNodeWithTag(SESSION_BAR_ARROW_UP_TAG).assertDoesNotExist()
        compose.onNodeWithTag(SESSION_BAR_ARROW_DOWN_TAG).assertDoesNotExist()
        compose.onNodeWithTag(SESSION_BAR_ENTER_TAG).assertDoesNotExist()

        compose.onNodeWithTag(SESSION_BAR_COMPOSE_TAG).performClick()
        compose.onNodeWithTag(SESSION_BAR_ARROW_UP_TAG).assertDoesNotExist()
        assertEquals(0, moreKeys)
        assertTrue(pressed.isEmpty())
    }

    @Test
    fun `disabled keys render but do not fire`() {
        val pressed = mutableListOf<SessionNavKey>()
        setContent(onKey = { pressed += it }, keysEnabled = false)

        compose.onNodeWithTag(SESSION_BAR_ARROW_UP_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(SESSION_BAR_ENTER_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(SESSION_BAR_ARROW_UP_TAG).performClick()
        compose.onNodeWithTag(SESSION_BAR_ENTER_TAG).performClick()

        assertTrue(pressed.isEmpty())
    }

    /**
     * The bar is one row of a11y-sized controls: every key chip keeps the
     * 48dp touch floor (the design system's non-negotiable hit-area rule),
     * and the row never grows past a third of the surface height — it is
     * docked chrome, not a panel.
     */
    @Test
    fun `every control keeps the 48dp touch floor in a compact row`() {
        setContent()

        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val bar = compose.onNodeWithTag(SESSION_TERMINAL_BAR_TAG).getUnclippedBoundsInRoot()
        val slots = listOf(
            SESSION_BAR_COMPOSE_TAG,
            SESSION_BAR_ARROW_UP_TAG,
            SESSION_BAR_ARROW_DOWN_TAG,
            SESSION_BAR_ENTER_TAG,
            SESSION_BAR_MORE_KEYS_TAG,
            SESSION_BAR_MIC_TAG,
        ).map { tag ->
            compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        }

        slots.forEach { bounds ->
            assertTrue(
                "touch target must be at least 48dp tall, got ${bounds.height}",
                bounds.height.value + 1f >= 48f,
            )
            assertTrue(
                "touch target must be at least 48dp wide, got ${bounds.width}",
                bounds.width.value + 1f >= 48f,
            )
        }
        assertTrue(
            "the bar must stay compact, got ${bar.height} of ${root.height}",
            bar.height < root.height / 3,
        )
    }

    /**
     * #2612: Enter must be visually separated from the arrows so a rushed
     * menu navigation cannot confirm a selection by accident. The hairline
     * divider between ↓ and Enter is that separation — it must exist and sit
     * strictly between the two keys.
     */
    @Test
    fun `enter is separated from the arrows by the divider`() {
        setContent()

        val divider = compose.onNodeWithTag(SESSION_BAR_ENTER_DIVIDER_TAG)
        divider.assertIsDisplayed()

        val down = compose.onNodeWithTag(SESSION_BAR_ARROW_DOWN_TAG).getUnclippedBoundsInRoot()
        val enter = compose.onNodeWithTag(SESSION_BAR_ENTER_TAG).getUnclippedBoundsInRoot()
        val dividerBounds = divider.getUnclippedBoundsInRoot()

        assertTrue(
            "the divider must sit between ↓ and Enter, got divider=$dividerBounds down=$down enter=$enter",
            dividerBounds.left > down.right - 1.dp && dividerBounds.right < enter.left + 1.dp,
        )
    }

    @Test
    fun `the palette-only keys are not on the bar`() {
        setContent()

        compose.onNodeWithText("Ctrl").assertDoesNotExist()
        compose.onNodeWithText("Esc").assertDoesNotExist()
        compose.onNodeWithText("Tab").assertDoesNotExist()
    }

    // ------------------------------------------------- #2475 dictation slot

    @Test
    fun `the mic exposes its accessible label and fires on tap`() {
        var taps = 0
        setContent(onMicTap = { taps += 1 })

        compose.onNodeWithContentDescription(SESSION_BAR_MIC_LABEL).assertIsDisplayed()
        compose.onNodeWithTag(SESSION_BAR_MIC_TAG).performClick()
        compose.onNodeWithTag(SESSION_BAR_MIC_TAG).performClick()

        assertEquals(2, taps)
    }

    @Test
    fun `a disabled mic renders but does not fire`() {
        var taps = 0
        setContent(onMicTap = { taps += 1 }, micEnabled = false)

        compose.onNodeWithTag(SESSION_BAR_MIC_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(SESSION_BAR_MIC_TAG).performClick()

        assertEquals(0, taps)
    }

    @Test
    fun `idle dictation shows no status chip`() {
        setContent()

        compose.onNodeWithTag(SESSION_BAR_DICTATION_CHIP_TAG).assertDoesNotExist()
    }

    @Test
    fun `listening shows the partial transcript in the status chip only`() {
        setContent(
            dictationPhase = SessionBarDictationPhase.Listening,
            dictationText = "hello world",
        )

        compose.onNodeWithTag(SESSION_BAR_DICTATION_CHIP_TAG).assertIsDisplayed()
        compose.onNodeWithTag(SESSION_BAR_DICTATION_CHIP_TAG)
            .assertTextContains("hello world", substring = true)
    }

    @Test
    fun `listening with no partial yet says it is listening`() {
        setContent(dictationPhase = SessionBarDictationPhase.Listening)

        compose.onNodeWithTag(SESSION_BAR_DICTATION_CHIP_TAG).assertIsDisplayed()
        compose.onNodeWithTag(SESSION_BAR_DICTATION_CHIP_TAG)
            .assertTextContains("Listening", substring = true)
    }

    @Test
    fun `transcribing says it is transcribing`() {
        setContent(dictationPhase = SessionBarDictationPhase.Transcribing)

        compose.onNodeWithTag(SESSION_BAR_DICTATION_CHIP_TAG).assertIsDisplayed()
        compose.onNodeWithTag(SESSION_BAR_DICTATION_CHIP_TAG)
            .assertTextContains("Transcribing", substring = true)
    }

    @Test
    fun `an idle dictation with an error message keeps the chip until cleared`() {
        setContent(dictationText = "Nothing was heard — try again.")

        compose.onNodeWithTag(SESSION_BAR_DICTATION_CHIP_TAG).assertIsDisplayed()
        compose.onNodeWithTag(SESSION_BAR_DICTATION_CHIP_TAG)
            .assertTextContains("Nothing was heard", substring = true)
    }

    private fun setContent(
        onKey: (SessionNavKey) -> Unit = {},
        onOpenComposer: () -> Unit = {},
        onMoreKeys: () -> Unit = {},
        keysEnabled: Boolean = true,
        showKeys: Boolean = true,
        onMicTap: () -> Unit = {},
        micEnabled: Boolean = true,
        dictationPhase: SessionBarDictationPhase = SessionBarDictationPhase.Idle,
        dictationText: String = "",
    ) {
        compose.setContent {
            PocketShellTheme {
                // Pinned to the narrowest supported phone so the geometry
                // assertions test the worst case, not Robolectric's default
                // viewport (same pattern as the palette flow tests).
                // requiredWidth: Robolectric's window is 320dp, so a plain
                // width(360) silently collapses to the window constraint and
                // the bar is measured 40dp narrower than the worst case the
                // floor rule must hold on.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .requiredWidth(360.dp),
                ) {
                    SessionTerminalBar(
                        onKey = onKey,
                        onOpenComposer = onOpenComposer,
                        onMoreKeys = onMoreKeys,
                        keysEnabled = keysEnabled,
                        showKeys = showKeys,
                        onMicTap = onMicTap,
                        micEnabled = micEnabled,
                        dictationPhase = dictationPhase,
                        dictationText = dictationText,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
        compose.waitForIdle()
    }
}
