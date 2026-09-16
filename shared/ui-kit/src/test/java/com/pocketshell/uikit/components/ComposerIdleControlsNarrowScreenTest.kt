package com.pocketshell.uikit.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #2635 C3, pinned at the narrowest supported screen: the idle controls
 * row carries four controls (attach, tools, Send, mic) because paste moved off
 * the row onto Send's long-press. This is the render-side proof that nothing
 * is clipped at 360dp — the render PNG shows it, these bounds assertions PIN
 * it: every control fully inside the root, on one row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h915dp-night-xxhdpi")
class ComposerIdleControlsNarrowScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `every idle control fits inside a 360dp screen`() {
        compose.setContent {
            PocketShellTheme {
                ComposerIdleControls(
                    onAttach = {},
                    onOpenTools = {},
                    onSend = {},
                    onPaste = {},
                    onMicTap = {},
                )
            }
        }

        val rootWidth = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
        for (tag in listOf(
            COMPOSER_ATTACH_TAG,
            COMPOSER_TOOLS_TRIGGER_TAG,
            COMPOSER_SEND_TAG,
            COMPOSER_MIC_TAG,
        )) {
            compose.onNodeWithTag(tag).assertIsDisplayed()
            val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag leaks off the left edge", bounds.left >= 0f)
            assertTrue(
                "$tag is clipped at 360dp (right=${bounds.right} of root=$rootWidth)",
                bounds.right <= rootWidth,
            )
        }
    }
}
