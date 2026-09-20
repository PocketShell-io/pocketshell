package com.pocketshell.uikit.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import com.pocketshell.uikit.theme.PocketShellTheme
import com.pocketshell.uikit.theme.PocketShellType
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * JVM (Robolectric) behaviour tests for the shared [EmptyState] (#756): content
 * rendering, the optional description / icon / action slots, and that the action
 * affordance routes its tap. These run under the real [PocketShellTheme] on the
 * host JVM (`:shared:ui-kit:testDebugUnitTest`) — no emulator needed.
 *
 * [supportingLineRendersExactlyTheRungItsKDocNames] closes the #2635 audit's
 * P-4: the KDoc named `bodyDense` twice while the code rendered `body` — the
 * same doc-vs-code drift #2630 shipped an entire type scale on. It reads the
 * rung name out of the KDoc, resolves that rung off [PocketShellType], and
 * compares it with what Compose actually laid the line out with, so the two
 * halves are pinned to each other rather than to a number this test repeats.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class EmptyStateTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersTitleOnly() {
        composeRule.setContent {
            PocketShellTheme {
                EmptyState(title = "No sessions")
            }
        }
        composeRule.onNodeWithText("No sessions").assertIsDisplayed()
    }

    @Test
    fun rendersDescriptionWhenProvided() {
        composeRule.setContent {
            PocketShellTheme {
                EmptyState(
                    title = "No panes yet",
                    description = "Create a session to see it here.",
                )
            }
        }
        composeRule.onNodeWithText("No panes yet").assertIsDisplayed()
        composeRule.onNodeWithText("Create a session to see it here.")
            .assertIsDisplayed()
    }

    @Test
    fun rendersActionSlotAndRoutesTap() {
        var clicks = 0
        composeRule.setContent {
            PocketShellTheme {
                EmptyState(
                    title = "No hosts",
                    description = "Add your first host to get started.",
                    icon = Icons.Filled.Info,
                    action = {
                        PocketShellButton(
                            text = "Add host",
                            onClick = { clicks++ },
                            variant = ButtonVariant.Primary,
                        )
                    },
                )
            }
        }
        composeRule.onNodeWithText("No hosts").assertIsDisplayed()
        composeRule.onNodeWithText("Add your first host to get started.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Add host").assertIsDisplayed()
        composeRule.onNodeWithText("Add host").performClick()
        composeRule.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun supportingLineRendersExactlyTheRungItsKDocNames() {
        val documentedRung =
            Regex("The supporting line is \\[PocketShellType\\.(\\w+)]")
                .find(emptyStateSource())
                ?.groupValues
                ?.get(1)
                ?: error(
                    "EmptyState.kt's KDoc no longer names its supporting-line rung as " +
                        "[PocketShellType.<rung>] — restore it or update this pin (#2804)",
                )

        composeRule.setContent {
            PocketShellTheme {
                EmptyState(title = "No hosts", description = DESCRIPTION)
            }
        }

        val rendered = mutableListOf<TextLayoutResult>()
        val layout = checkNotNull(
            composeRule.onNodeWithText(DESCRIPTION, useUnmergedTree = true)
                .fetchSemanticsNode()
                .config[SemanticsActions.GetTextLayoutResult]
                .action,
        ) { "the supporting line exposes no text layout to read" }
        layout(rendered)

        assertEquals(
            "EmptyState's KDoc names PocketShellType." + documentedRung + " but the supporting " +
                "line renders a different rung (#2804, #2635 audit P-4)",
            rungFontSize(documentedRung),
            rendered.first().layoutInput.style.fontSize,
        )
    }

    @Test
    fun omitsDescriptionWhenNull() {
        val description = "this should not render"
        composeRule.setContent {
            PocketShellTheme {
                EmptyState(title = "Nothing here")
            }
        }
        // The title shows; the (absent) supporting line does not exist.
        composeRule.onNodeWithText("Nothing here").assertIsDisplayed()
        composeRule.onNodeWithText(description).assertDoesNotExist()
    }

    /** The rung's own `fontSize`, resolved off [PocketShellType] by the KDoc's name for it. */
    private fun rungFontSize(rung: String) = PocketShellType::class.java
        .getMethod("get" + rung.replaceFirstChar { it.uppercase() })
        .invoke(PocketShellType)
        .let { (it as TextStyle).fontSize }

    private fun emptyStateSource(): String = sequenceOf(
        "src/main/java/com/pocketshell/uikit/components/EmptyState.kt",
        "../../shared/ui-kit/src/main/java/com/pocketshell/uikit/components/EmptyState.kt",
    ).map(::File).firstOrNull { it.isFile }
        ?.readText()
        ?: error("Could not locate EmptyState.kt from " + File(".").absolutePath)

    private companion object {
        const val DESCRIPTION = "Add your first host to get started."
    }
}
