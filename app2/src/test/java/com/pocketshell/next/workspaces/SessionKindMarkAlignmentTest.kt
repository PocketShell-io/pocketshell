package com.pocketshell.next.workspaces

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Issue #2796 — the reported defect itself, measured end to end.
 *
 * `WorkspaceScreen` and `HostWorkspacesScreen` pass
 * `leading = { SessionKindMark(agent = session.agent) }`. The lambda is always
 * non-null, so [ListRow] always emitted the leading slot; but before #2796 the
 * slot sized itself from its content AND [SessionKindMark] drew nothing at all
 * when the host reported no `agent`. A session list therefore showed a
 * different title left edge per row depending on host data — the #2635 audit
 * measured 32.7 dp for a `root-shell` row against ~50.7 dp for a Claude row in
 * the same column.
 *
 * Both #2796 changes are pinned here, each by the arm that can actually see
 * it: un-fixing [ListRow]'s leading box reds the alignment arms, and restoring
 * [SessionKindMark]'s `?: return` reds the footprint arm. They need separate
 * arms because the [ListRow] floor is strong enough to hold the gutter on its
 * own even when the mark draws nothing — see that arm's KDoc.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class SessionKindMarkAlignmentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private companion object {
        /** Device-pixel rounding slack; the relationship is exact in dp here. */
        const val TOLERANCE_DP = 0.5f

        /**
         * One row per session kind a host can report, titled by the kind so a
         * failure names the row. `null` and `"shell"` are the cases that used
         * to render nothing; `"nginx"` is an agent key the mark has no glyph
         * for, which took the same empty path.
         */
        val SESSION_ROWS: List<Pair<String, String?>> = listOf(
            "agent-main (claude)" to "claude",
            "root-shell (no agent)" to null,
            "review (codex)" to "codex",
            "notes (shell)" to "shell",
            "build (opencode)" to "opencode",
            "web (unmapped agent)" to "nginx",
        )

        /** Wrapper tag so a mark's own footprint can be measured apart from any row. */
        fun markTag(title: String): String = "session-kind-mark:$title"
    }

    private fun titleLeft(title: String): Dp =
        composeRule.onNodeWithText(title).getUnclippedBoundsInRoot().left

    private val sessionGutter: Dp
        get() = PocketShellDensity.screenGutter + PocketShellDensity.icon + PocketShellSpacing.md

    /**
     * The #2796 AC3 pin: a mixed agent/shell list is one column.
     */
    @Test
    fun `every session kind puts its title at the same left edge`() {
        composeRule.setContent {
            PocketShellTheme {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SESSION_ROWS.forEach { (title, agent) ->
                        ListRow(
                            title = title,
                            subtitle = "~/git/pocketshell",
                            leading = { SessionKindMark(agent = agent) },
                        )
                    }
                }
            }
        }

        val measured = SESSION_ROWS.map { (title, _) -> title to titleLeft(title) }
        measured.forEach { (title, left) ->
            assertTrue(
                "The session row \"$title\" put its title at $left, not the " +
                    "$sessionGutter every row in the list owes it (±$TOLERANCE_DP dp). " +
                    "A row's left text edge is following host data again (#2796).",
                (left - sessionGutter).value in -TOLERANCE_DP..TOLERANCE_DP,
            )
        }
        val distinct = measured.map { it.second }.distinct()
        assertTrue(
            "One session list produced ${distinct.size} different title left edges: " +
                "$measured. Expected exactly one (#2796).",
            distinct.size == 1,
        )
    }

    /**
     * The same list with `showShell = true`, where a shell session DOES get a
     * glyph. Alignment must not depend on that flag either — and this arm is
     * what `CreateSessionSheet` actually renders.
     */
    @Test
    fun `showShell does not move the title gutter`() {
        composeRule.setContent {
            PocketShellTheme {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SESSION_ROWS.forEach { (title, agent) ->
                        ListRow(
                            title = title,
                            leading = { SessionKindMark(agent = agent, showShell = true) },
                        )
                    }
                }
            }
        }

        val measured = SESSION_ROWS.map { (title, _) -> title to titleLeft(title) }
        measured.forEach { (title, left) ->
            assertTrue(
                "With showShell = true the session row \"$title\" put its title at $left, " +
                    "not $sessionGutter (±$TOLERANCE_DP dp) (#2796).",
                (left - sessionGutter).value in -TOLERANCE_DP..TOLERANCE_DP,
            )
        }
    }

    /**
     * Anti-vacuity (#2796 AC4). A workspace row passes no `leading` lambda and
     * must stay flush with the screen gutter, measurably left of the session
     * rows that do lead. Green before the fix and under either mutation — if
     * this ever reds, the fix has grown a phantom gutter.
     */
    @Test
    fun `a row with no leading lambda keeps a flush-left title`() {
        composeRule.setContent {
            PocketShellTheme {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ListRow(title = "pocketshell", subtitle = "~/git/pocketshell")
                    ListRow(title = "aplexer", subtitle = "~/git/aplexer")
                    ListRow(
                        title = "agent-main (claude)",
                        leading = { SessionKindMark(agent = "claude") },
                    )
                }
            }
        }

        val first = titleLeft("pocketshell")
        val second = titleLeft("aplexer")
        val led = titleLeft("agent-main (claude)")

        assertTrue(
            "A workspace row with no leading lambda put its title at $first, not the bare " +
                "${PocketShellDensity.screenGutter} screen gutter (±$TOLERANCE_DP dp). " +
                "#2796 must not reserve a slot for rows that never asked for one.",
            (first - PocketShellDensity.screenGutter).value in -TOLERANCE_DP..TOLERANCE_DP,
        )
        assertTrue(
            "The two leading-less rows disagree: $first vs $second.",
            (first - second).value in -TOLERANCE_DP..TOLERANCE_DP,
        )
        assertTrue(
            "A session row ($led) must indent past a leading-less workspace row ($first); " +
                "if they match, the alignment assertions measure nothing.",
            led > first,
        )
    }

    /**
     * #2796 AC2, pinned WITHOUT [ListRow] in the picture.
     *
     * [ListRow]'s `widthIn(min = icon)` floor is load-bearing enough that it
     * alone keeps the title gutter steady — an empty leading lambda still
     * measures one `icon`. So the alignment arms above cannot see whether
     * [SessionKindMark] kept its own footprint, and restoring its `?: return`
     * would leave them green while AC2 silently regressed. This arm measures
     * the mark directly: every session kind, glyph or no glyph, occupies
     * exactly one [PocketShellDensity.metadataIcon] box, so any caller — not
     * just [ListRow] — can treat the mark as a fixed-footprint slot.
     *
     * Each mark is wrapped in a tagged, unconstrained [Box] that takes its
     * content's size, so a mark that renders nothing measures 0 dp and reds
     * here by kind name.
     */
    @Test
    fun `every session kind occupies one metadataIcon box`() {
        composeRule.setContent {
            PocketShellTheme {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SESSION_ROWS.forEach { (title, agent) ->
                        Box(modifier = Modifier.testTag(markTag(title))) {
                            SessionKindMark(agent = agent)
                        }
                    }
                }
            }
        }

        val expected = PocketShellDensity.metadataIcon
        SESSION_ROWS.forEach { (title, agent) ->
            val box = composeRule.onNodeWithTag(markTag(title)).getUnclippedBoundsInRoot()
            assertTrue(
                "SessionKindMark(agent = ${agent?.let { "\"$it\"" } ?: "null"}) measured " +
                    "${box.width} x ${box.height}, not the $expected x $expected box every " +
                    "kind owes its caller (±$TOLERANCE_DP dp). A kind with no glyph must " +
                    "draw a transparent spacer, never nothing at all (#2796 AC2).",
                (box.width - expected).value in -TOLERANCE_DP..TOLERANCE_DP &&
                    (box.height - expected).value in -TOLERANCE_DP..TOLERANCE_DP,
            )
        }
    }
}
