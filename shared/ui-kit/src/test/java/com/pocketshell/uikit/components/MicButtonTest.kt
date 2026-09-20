package com.pocketshell.uikit.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.InspectableValue
import androidx.compose.ui.platform.ValueElement
import androidx.compose.ui.platform.isDebugInspectorInfoEnabled
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.model.MicButtonState
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #2802 C-2 reproduce-first: the composer's idle row had TWO equally-weighted
 * accent primaries side by side — the filled Send pill and the filled idle mic
 * — with the outlined Insert pill between them. `docs/design-language.md`
 * reserves the bright accent for active state and primary actions, and a mic at
 * rest is neither. `MicButton.kt` even said so out loud: one
 * `MicButtonState.Idle, MicButtonState.Recording -> PocketShellColors.Accent`
 * arm for the fill and another for the glyph.
 *
 * ## Why the assertions read modifiers rather than pixels
 *
 * `captureToImage()` goes through `PixelCopy`, which Robolectric cannot serve
 * (it times out in `forceRedraw`), and Roborazzi captures are gated off in a
 * plain unit run — a pixel assertion here would either hang or quietly assert
 * nothing, which is the vacuous-green shape `docs/ci-pitfalls.md` warns about.
 * So these tests turn on Compose's debug inspector and read the `background`
 * and `border` modifiers the composable ACTUALLY applied to the rendered node.
 * That is the drawn recipe, not a restatement of the table: if [MicButton]
 * stops consulting [micButtonTreatment], or the table is right and the
 * composable wires it to the wrong slot, these fail.
 *
 * [onlyTheRecordingStateCarriesAccentWeight] pins the table itself, so the two
 * halves are checked independently and a reviewer can see them agree.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class MicButtonTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun enableInspector() {
        isDebugInspectorInfoEnabled = true
    }

    @After
    fun disableInspector() {
        isDebugInspectorInfoEnabled = false
    }

    // --- the fill: which state is allowed the accent ------------------------

    @Test
    fun `an idle mic is not accent filled`() {
        setContent(MicButtonState.Idle)

        assertEquals(
            "an idle mic must wear the Insert pill's elevated-surface fill, not the accent " +
                "(#2802 C-2: a mic at rest is neither an active state nor a primary action)",
            PocketShellColors.SurfaceElev,
            backgroundColor(),
        )
        assertNotEquals(
            "the idle mic must not repeat the Send pill's accent fill",
            PocketShellColors.Accent,
            backgroundColor(),
        )
    }

    @Test
    fun `an idle mic wears the Insert pill's hairline outline`() {
        setContent(MicButtonState.Idle)

        assertEquals(
            "the outline treatment is a 1dp `Border` hairline — the same recipe " +
                "`InsertButton` and the ui-kit Secondary pills use",
            PocketShellColors.Border to 1.dp,
            borderStroke(),
        )
    }

    @Test
    fun `a recording mic keeps the accent fill and no outline`() {
        setContent(MicButtonState.Recording)

        assertEquals(
            "recording IS the active state, so it keeps the bright accent (#2802 C-2)",
            PocketShellColors.Accent,
            backgroundColor(),
        )
        assertNull("an accent fill is its own edge; a border would double it", borderStroke())
    }

    /**
     * Idle and Disabled share a fill, so the hairline and the glyph tint are
     * what tell a tappable mic from a dead one. Without this, "outline the idle
     * mic" could be satisfied by making it look disabled.
     */
    @Test
    fun `an idle mic does not read as the disabled mic`() {
        setContent(MicButtonState.Disabled)

        assertEquals(PocketShellColors.SurfaceElev, backgroundColor())
        assertNull("a dead control gets no outline to invite a tap", borderStroke())
        assertNotEquals(
            "idle and disabled must be told apart by something",
            micButtonTreatment(MicButtonState.Idle),
            micButtonTreatment(MicButtonState.Disabled),
        )
    }

    // --- behaviour that must survive the restyle ----------------------------

    @Test
    fun `an idle mic is still a tappable dictate target on the button rung`() {
        var clicks = 0
        setContent(MicButtonState.Idle, onClick = { clicks += 1 })

        composeRule.onNodeWithTag(TAG)
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertHeightIsEqualTo(56.dp)
        composeRule.onNodeWithContentDescription("Dictate").assertIsDisplayed()

        composeRule.onNodeWithTag(TAG).performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun `a disabled mic does not fire`() {
        var clicks = 0
        setContent(MicButtonState.Disabled, onClick = { clicks += 1 })

        composeRule.onNodeWithTag(TAG).assertIsNotEnabled().performClick()
        assertEquals(0, clicks)
    }

    // --- the table the composable reads -------------------------------------

    @Test
    fun onlyTheRecordingStateCarriesAccentWeight() {
        val idle = micButtonTreatment(MicButtonState.Idle)
        val recording = micButtonTreatment(MicButtonState.Recording)
        val disabled = micButtonTreatment(MicButtonState.Disabled)

        assertEquals(PocketShellColors.SurfaceElev, idle.fill)
        assertEquals(PocketShellColors.Text, idle.glyph)
        assertEquals(PocketShellColors.Border, idle.border)
        assertEquals("an idle mic has no elevation cue to carry", false, idle.shadow)

        assertEquals(PocketShellColors.Accent, recording.fill)
        assertEquals(PocketShellColors.OnAccent, recording.glyph)
        assertNull(recording.border)
        assertEquals(true, recording.shadow)

        assertEquals(PocketShellColors.SurfaceElev, disabled.fill)
        assertEquals(PocketShellColors.TextMuted, disabled.glyph)
        assertNull(disabled.border)
        assertEquals(false, disabled.shadow)

        val states = listOf(idle, recording, disabled)
        assertEquals(
            "exactly one mic state may be accent-filled (#2802 C-2)",
            1,
            states.count { it.fill == PocketShellColors.Accent },
        )
        assertEquals(
            "the accent drop shadow is accent weight too — it goes with the fill",
            1,
            states.count { it.shadow },
        )
    }

    // --- helpers ------------------------------------------------------------

    private fun setContent(state: MicButtonState, onClick: () -> Unit = {}) {
        composeRule.setContent {
            PocketShellTheme {
                MicButton(state = state, onClick = onClick, modifier = Modifier.testTag(TAG))
            }
        }
    }

    /** Inspector name/value pairs for every modifier applied to the mic node. */
    private fun modifierElements(): List<Pair<String, List<ValueElement>>> =
        composeRule.onNodeWithTag(TAG)
            .fetchSemanticsNode()
            .layoutInfo
            .getModifierInfo()
            .mapNotNull { info ->
                (info.modifier as? InspectableValue)?.let { inspectable ->
                    (inspectable.nameFallback ?: "") to inspectable.inspectableElements.toList()
                }
            }

    private fun valueOf(modifier: String, key: String): Any? = modifierElements()
        .firstOrNull { it.first == modifier }
        ?.second
        ?.firstOrNull { it.name == key }
        ?.value

    /** The colour `Modifier.background(...)` was actually called with. */
    private fun backgroundColor(): Color? = valueOf("background", "color") as? Color

    /** The `Modifier.border(...)` stroke, or null when the state applies none. */
    private fun borderStroke(): Pair<Color, Dp>? {
        val elements = modifierElements().firstOrNull { it.first == "border" }?.second ?: return null
        val color = elements.firstOrNull { it.name == "color" }?.value as? Color ?: return null
        val width = elements.firstOrNull { it.name == "width" }?.value as? Dp ?: return null
        return color to width
    }

    private companion object {
        const val TAG = "mic"
    }
}
