package com.pocketshell.uikit.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The composer Send/Discard/Stop pill family (#2763): geometry and state
 * permutations as promoted from app2's `ComposerBar` — pixel-identical move,
 * so the pills must all sit on the 48dp touch rung (`PocketShellDensity
 * .tapTargetMin`) and keep their distinct accessible names.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class ComposerControlsTest {

    @get:Rule
    val composeRule = createComposeRule()

    // --- Send: idle row (primary) ---

    @Test
    fun idleEnabledSendIsPrimaryPillAndFires() {
        var clicks = 0
        composeRule.setContent {
            PocketShellTheme {
                ComposerSendButton(
                    onClick = { clicks += 1 },
                    enabled = true,
                    modifier = Modifier.testTag("send"),
                )
            }
        }

        composeRule.onNodeWithTag("send")
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertHeightIsEqualTo(48.dp)
        composeRule.onNodeWithText("Send").assertIsDisplayed()

        composeRule.onNodeWithTag("send").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun idleDisabledSendKeepsPillHeightButIsUntappable() {
        composeRule.setContent {
            PocketShellTheme {
                ComposerSendButton(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.testTag("send"),
                )
            }
        }

        composeRule.onNodeWithTag("send")
            .assertIsDisplayed()
            .assertIsNotEnabled()
            .assertHeightIsEqualTo(48.dp)
    }

    // --- Send: recording/transcribing rows (demoted outline, #2602) ---

    @Test
    fun recordingEnabledSendStaysOnThePillRungAndFires() {
        var clicks = 0
        composeRule.setContent {
            PocketShellTheme {
                ComposerSendButton(
                    onClick = { clicks += 1 },
                    enabled = true,
                    recording = true,
                    modifier = Modifier.testTag("send"),
                )
            }
        }

        composeRule.onNodeWithTag("send")
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertHeightIsEqualTo(48.dp)
        composeRule.onNodeWithText("Send").assertIsDisplayed()

        composeRule.onNodeWithTag("send").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun recordingDisabledSendIsUntappable() {
        composeRule.setContent {
            PocketShellTheme {
                ComposerSendButton(
                    onClick = {},
                    enabled = false,
                    recording = true,
                    modifier = Modifier.testTag("send"),
                )
            }
        }

        composeRule.onNodeWithTag("send")
            .assertIsDisplayed()
            .assertIsNotEnabled()
            .assertHeightIsEqualTo(48.dp)
    }

    // --- Discard: recording + transcribing permutations ---

    @Test
    fun discardDefaultLabelKeepsSpelledOutAccessibleName() {
        var clicks = 0
        composeRule.setContent {
            PocketShellTheme {
                ComposerDiscardButton(
                    onClick = { clicks += 1 },
                    modifier = Modifier.testTag("discard"),
                )
            }
        }

        composeRule.onNodeWithContentDescription("Discard recording without transcribing")
            .assertIsDisplayed()
            .assertHeightIsEqualTo(48.dp)
        composeRule.onNodeWithText("Discard").assertIsDisplayed()

        composeRule.onNodeWithTag("discard").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun discardCustomLabelRendersForTheTranscribingRow() {
        composeRule.setContent {
            PocketShellTheme {
                ComposerDiscardButton(
                    onClick = {},
                    label = "Cancel",
                    modifier = Modifier.testTag("discard"),
                )
            }
        }

        // Same control, same accessible name contract, different visible verb.
        composeRule.onNodeWithContentDescription("Discard recording without transcribing")
            .assertIsDisplayed()
            .assertHeightIsEqualTo(48.dp)
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
        composeRule.onNodeWithText("Discard").assertDoesNotExist()
    }

    // --- Stop: the accent disc in the mic slot (#2598) ---

    @Test
    fun stopDiscIsSquareTouchTargetWithCallerOwnedName() {
        var clicks = 0
        composeRule.setContent {
            PocketShellTheme {
                ComposerStopRecordingButton(
                    onClick = { clicks += 1 },
                    contentDescription = "Stop dictating and keep the text",
                    modifier = Modifier.testTag("stop"),
                )
            }
        }

        composeRule.onNodeWithContentDescription("Stop dictating and keep the text")
            .assertIsDisplayed()
            .assertWidthIsEqualTo(48.dp)
            .assertHeightIsEqualTo(48.dp)

        composeRule.onNodeWithTag("stop").performClick()
        assertEquals(1, clicks)
    }
}
