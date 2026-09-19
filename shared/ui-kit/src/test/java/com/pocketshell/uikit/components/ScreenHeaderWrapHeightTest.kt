package com.pocketshell.uikit.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.sp
import com.pocketshell.uikit.theme.PocketShellTheme
import com.pocketshell.uikit.theme.PocketShellType
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Issue #2826 — the JVM half of the wrapped-[ScreenHeader]-title contract, and
 * the durable record of what actually moved the number.
 *
 * ### What broke
 *
 * `UiKitPrimitivesTest.screenHeader_wrapsLongTitleAndKeepsSubtitle` asserted
 * `assertHeightIsAtLeast(60.dp)` on the title node. That 60 was two lines of
 * the *then-current* 28sp/34sp screen rung written down as a constant.
 * `9bb92c14b` (#2717) retuned `type.screen` to 20sp/26sp — the locked design
 * token — and the same correctly-wrapped two-line title measured 49.9 dp. The
 * component never regressed; the ORACLE went stale.
 *
 * The report originally blamed `d2690ecfa` (#2727, the nav-redesign revert)
 * because it was the last commit to touch `ScreenHeader.kt`. Its whole diff
 * there is an import reorder, a KDoc paragraph, the `statusTestTag` parameter
 * and `TextOverflow.Ellipsis` -> `Clip`; none of that is height-bearing, and
 * that commit message records that it deliberately KEPT #2717.
 * [wrappedTitleHeightIsAFunctionOfTheTypeRung] is that bisect as an executable
 * assertion: the measured height tracks the rung and nothing else.
 *
 * ### Why this test exists in `src/test` as well as `androidTest`
 *
 * The androidTest copy is only executed by an emulator lane. Until #2826 that
 * was the pre-release confidence gate alone, which had been blocking at the D37
 * nightly-fault guard before any test ran — so the red was invisible for days.
 * This JVM/Robolectric copy runs in the required `Unit tests` lane on every
 * push and PR, for free, so the class of regression cannot hide there again.
 * (#2826 also adds the connected execution lane; both, not either.)
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class ScreenHeaderWrapHeightTest {

    @get:Rule
    val composeRule = createComposeRule()

    private companion object {
        const val HEADER_TAG = "screen-header"
        const val TITLE_TAG = "screen-header-title"

        /** The same long title `UiKitPrimitivesTest` renders. */
        const val LONG_TITLE = "A very long workspace name that should wrap"

        /** [ScreenHeader]'s own `titleMaxLines` default. */
        const val TITLE_MAX_LINES = 2

        /** "Wrapped past one line", with slack for Compose's last-line trim. */
        const val WRAPPED_TITLE_MIN_LINES = 1.5f

        /**
         * Device-pixel rounding slack for the exact one-line-delta pin. The
         * relationship is exact in dp at this fixed @Config density; a whole
         * dp of tolerance keeps it honest without making it a literal.
         */
        const val DELTA_TOLERANCE_DP = 1f

        /** The pre-#2717 screen rung, kept only as the bisect's other arm. */
        val PRE_2717_SCREEN: TextStyle =
            PocketShellType.screen.copy(fontSize = 28.sp, lineHeight = 34.sp)
    }

    /**
     * `setContent` may be called once per test, so every arm a test needs is
     * composed in the SAME tree — a `Column` of full-width headers, one tag
     * each. Each header still measures at the full 412 dp window width, which
     * is what decides where the title wraps.
     */
    private fun setHeaders(vararg arms: Pair<String, TextStyle>) {
        composeRule.setContent {
            PocketShellTheme {
                Column(modifier = Modifier.fillMaxWidth()) {
                    arms.forEachIndexed { index, (title, style) ->
                        ScreenHeader(
                            title = title,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("$HEADER_TAG-$index"),
                            subtitle = "host.example · 2 sessions",
                            titleStyle = style,
                            titleTestTag = "$TITLE_TAG-$index",
                        )
                    }
                }
            }
        }
    }

    private fun titleHeight(index: Int): Dp =
        composeRule.onNodeWithTag("$TITLE_TAG-$index").getUnclippedBoundsInRoot().height

    private fun measureTitle(title: String, style: TextStyle): Dp {
        setHeaders(title to style)
        return titleHeight(0)
    }

    private fun lineHeightOf(style: TextStyle): Dp =
        with(composeRule.density) { style.lineHeight.toDp() }

    /**
     * The load-bearing pin, derived from the token rather than written down: a
     * long title occupies between 1.5 and [TITLE_MAX_LINES] lines of whatever
     * `type.screen` currently is. Retune the rung and this follows it.
     */
    @Test
    fun wrappedTitleSpansTwoLinesOfTheLiveScreenRung() {
        val line = lineHeightOf(PocketShellType.screen)
        val measured = measureTitle(LONG_TITLE, PocketShellType.screen)

        assertTrue(
            "The long ScreenHeader title measured $measured, below the two-line floor " +
                "${line * WRAPPED_TITLE_MIN_LINES} derived from the live type.screen rung " +
                "(lineHeight $line). It did not wrap (#2826).",
            measured >= line * WRAPPED_TITLE_MIN_LINES,
        )
        assertTrue(
            "The long ScreenHeader title measured $measured, above its $TITLE_MAX_LINES-line " +
                "budget ${line * TITLE_MAX_LINES} at the live type.screen rung (lineHeight " +
                "$line) — an extra line or padding appeared on the title node (#2826).",
            measured <= line * TITLE_MAX_LINES,
        )
    }

    /** Anti-vacuity: a short title must NOT clear the two-line floor. */
    @Test
    fun shortTitleStaysWithinOneLineOfTheLiveScreenRung() {
        val line = lineHeightOf(PocketShellType.screen)
        val measured = measureTitle("Hosts", PocketShellType.screen)

        assertTrue(
            "A short ScreenHeader title measured $measured, at or above the two-line floor " +
                "${line * WRAPPED_TITLE_MIN_LINES}; the wrap assertion would then pin nothing.",
            measured < line * WRAPPED_TITLE_MIN_LINES,
        )
        assertTrue(
            "A short ScreenHeader title collapsed to $measured; expected one line box of the " +
                "live type.screen rung (lineHeight $line).",
            measured > line / 2,
        )
    }

    /**
     * The exact statement of "what the intended wrapped-title height IS"
     * (#2826 AC2), independent of both the rung and the device density.
     *
     * Compose lays the first line out at the font's natural line box and every
     * FURTHER line at exactly the style's `lineHeight`, so the second line adds
     * precisely one `lineHeight` to the one-line measurement. Measured here:
     * 23.7 dp -> 49.7 dp at the live 20/26 rung, 33.0 dp -> 67.0 dp at the
     * pre-#2717 28/34 rung; 49.7 - 23.7 = 26 and 67.0 - 33.0 = 34, both exactly
     * their rung's line height. That relationship, not a dp literal, is the
     * oracle #2826 replaced.
     */
    @Test
    fun wrappingAddsExactlyOneLineHeightToTheTitle() {
        val line = lineHeightOf(PocketShellType.screen)
        setHeaders(
            "Hosts" to PocketShellType.screen,
            LONG_TITLE to PocketShellType.screen,
        )
        val oneLine = titleHeight(0)
        val twoLines = titleHeight(1)
        val delta = twoLines - oneLine

        assertTrue(
            "Wrapping the ScreenHeader title to a second line added $delta, not the " +
                "$line line height of the live type.screen rung (one line $oneLine, two " +
                "lines $twoLines). The title height is no longer one line box plus one " +
                "lineHeight per extra line (#2826).",
            (delta - line).value in -DELTA_TOLERANCE_DP..DELTA_TOLERANCE_DP,
        )
    }

    /**
     * The #2826 root-cause dispute, settled and kept settled.
     *
     * The wrapped title's height is a function of the title style's
     * `lineHeight` and of nothing else in [ScreenHeader]: rendering the SAME
     * component, same string, same width at the pre-#2717 28sp/34sp rung
     * produces a proportionally taller node — tall enough to clear the 60 dp
     * literal the old oracle hardcoded, which the live 20sp/26sp rung does not.
     * That is the whole regression, expressed as an assertion instead of a
     * bisect narrative.
     */
    @Test
    fun wrappedTitleHeightIsAFunctionOfTheTypeRung() {
        val liveLine = lineHeightOf(PocketShellType.screen)
        val legacyLine = lineHeightOf(PRE_2717_SCREEN)
        setHeaders(
            LONG_TITLE to PocketShellType.screen,
            LONG_TITLE to PRE_2717_SCREEN,
        )
        val live = titleHeight(0)
        val legacy = titleHeight(1)

        assertTrue(
            "The pre-#2717 rung ($legacyLine line height) must measure taller than the live " +
                "rung ($liveLine): live=$live legacy=$legacy. If they match, the title height " +
                "is no longer driven by type.screen and this test's premise is wrong (#2826).",
            legacy > live,
        )
        assertTrue(
            "At the pre-#2717 rung the title measured $legacy, under its own two-line floor " +
                "${legacyLine * WRAPPED_TITLE_MIN_LINES} — the legacy arm of the bisect is not " +
                "wrapping, so the comparison above proves nothing (#2826).",
            legacy >= legacyLine * WRAPPED_TITLE_MIN_LINES,
        )
        assertTrue(
            "Both arms must stay inside their own $TITLE_MAX_LINES-line budgets: " +
                "live=$live (<= ${liveLine * TITLE_MAX_LINES}), " +
                "legacy=$legacy (<= ${legacyLine * TITLE_MAX_LINES}).",
            live <= liveLine * TITLE_MAX_LINES && legacy <= legacyLine * TITLE_MAX_LINES,
        )
    }
}
