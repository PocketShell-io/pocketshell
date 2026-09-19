package com.pocketshell.next.connect

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The regression for issue #2783: [clickQuietTag] must keep trying until the
 * node the click needs is actually there.
 *
 * ## The failure this pins
 *
 * `J12UsagePanelJourney.theSessionPillFocusesTheSessionsDetectedAgentAndDropsTheWindowToken`
 * reddened Release Emulator Validation run 35383053367 on `d093a9a78` with
 *
 * ```
 * java.lang.AssertionError: Failed to inject touch input.
 * Reason: Expected exactly '1' node but could not find any node that satisfies:
 *   (TestTag = 'host-row-9801')
 *   at com.pocketshell.next.connect.QuietNavigationKt.openQuietHost(QuietNavigation.kt:40)
 * ```
 *
 * — the wait on line 39 found the row and the click on line 40 did not. The same
 * commit's unfiltered app2 run 35374191870 attempt 2 was green, so this is a
 * timing gap in the shared navigation helper, not a product defect. The gap
 * belongs to every journey reaching a screen through `openQuietHost` /
 * `openQuietSession`, which is why it is asserted here against a fixture rather
 * than left to whichever journey loses the race next.
 *
 * ## The actual defect, stated as a property
 *
 * `awaitQuietTag` waits for `onAllNodesWithTag(tag).isNotEmpty()` — **at least
 * one** node. The click needs `onNodeWithTag(tag)` — **exactly one** node. The
 * wait's predicate is strictly weaker than the click's, so a tree that satisfies
 * the wait can still fail the click, and the pre-fix helper spent its single
 * attempt on that tree and gave up.
 *
 * Both violations of "exactly one" are covered:
 *
 *  - [aDuplicatedRowIsClickedOnceTheTreeSettlesToASingleMatch] — **two** nodes.
 *    This is the case that can be held still, so it is the one that fails red
 *    against the pre-fix single-shot helper and green against the retrying one.
 *  - [aRowThatIsMissingWhenTheClickIsAttemptedIsAwaitedAndClicked] — **zero**
 *    nodes, the literal shape run 35383053367 hit. Honest caveat: the pre-fix
 *    helper also passes this one, because every observation point in Compose's
 *    test API syncs first, so a node that is simply absent-then-present is
 *    awaited successfully by either helper. The sub-millisecond disappearance
 *    that the hosted lane hit cannot be manufactured on demand from outside the
 *    helper; this case guards the zero-node contract, and the duplicate case
 *    above is what gates the fix.
 *
 * ## Why these run on the device rather than the JVM
 *
 * [clickQuietTag] is journey-harness code living in `app2/src/androidTest`,
 * which the JVM unit lane does not compile, and the behaviour under test is
 * Compose's real semantics-tree resolution. Wiring is automatic: `app2.yml`'s
 * `app2-journey` job runs `:app2:connectedDebugAndroidTest` ONCE, unfiltered, so
 * every class under `app2/src/androidTest/` runs. Nothing here needs a fixture
 * host, the app's own screens, or the network.
 */
@RunWith(AndroidJUnit4::class)
class QuietNavigationClickRaceTest {

    @get:Rule
    val compose = createComposeRule()

    /** A second node wearing [ROW_TAG]; the tree satisfies the wait but not the click. */
    private val duplicated = mutableStateOf(false)

    private val present = mutableStateOf(true)

    private val clicks = AtomicInteger(0)

    /**
     * The gate for this fix: a tree the wait accepts and the click rejects.
     *
     * Against the pre-fix `awaitQuietTag(tag); onNodeWithTag(tag).performClick()`
     * this fails on the spot with `Expected exactly '1' node but found '2'`.
     * The retrying helper keeps going, the duplicate is gone [SETTLE_MS] later,
     * and exactly one click lands.
     */
    @Test
    fun aDuplicatedRowIsClickedOnceTheTreeSettlesToASingleMatch() {
        duplicated.value = true
        showRows()

        // The wait's predicate is satisfied — this is why the pre-fix helper
        // went straight on to a click it could not perform.
        compose.awaitQuietTag(ROW_TAG, TIMEOUT_MS)
        assertEquals(
            "the fixture must really offer two matches",
            2,
            compose.onAllNodesWithTag(ROW_TAG).fetchSemanticsNodes().size,
        )
        val naive = runCatching { compose.onNodeWithTag(ROW_TAG).performClick() }
        assertTrue(
            "the single-shot click must fail against this tree — that IS the bug",
            naive.isFailure,
        )
        assertTrue(
            "expected the exactly-one failure, got: ${naive.exceptionOrNull()?.message}",
            naive.exceptionOrNull()?.message.orEmpty().contains("Expected exactly '1' node"),
        )
        assertEquals("a rejected click must not have been injected", 0, clicks.get())

        // Let the tree settle the way a recomposition does, from the main thread.
        Handler(Looper.getMainLooper()).postDelayed({ duplicated.value = false }, SETTLE_MS)

        compose.clickQuietTag(ROW_TAG, TIMEOUT_MS)

        assertEquals("the retrying click must land exactly one click", 1, clicks.get())
    }

    /**
     * The zero-node half of the same contract, and the literal shape of run
     * 35383053367: the click is attempted against a tree with no match at all.
     *
     * See the class KDoc for why this one does not by itself separate the pre-fix
     * helper from the fixed one — it guards the contract, it does not gate it.
     */
    @Test
    fun aRowThatIsMissingWhenTheClickIsAttemptedIsAwaitedAndClicked() {
        showRows()
        compose.awaitQuietTag(ROW_TAG, TIMEOUT_MS)

        present.value = false
        compose.waitForIdle()
        assertTrue(
            "the fixture must actually remove the row before the click is attempted",
            compose.onAllNodesWithTag(ROW_TAG).fetchSemanticsNodes().isEmpty(),
        )
        val naive = runCatching { compose.onNodeWithTag(ROW_TAG).performClick() }
        assertTrue(
            "expected the lane's signature, got: ${naive.exceptionOrNull()?.message}",
            naive.exceptionOrNull()?.message.orEmpty().contains("could not find any node"),
        )

        Handler(Looper.getMainLooper()).postDelayed({ present.value = true }, SETTLE_MS)

        compose.clickQuietTag(ROW_TAG, TIMEOUT_MS)

        assertEquals("the row must be clicked once it is back", 1, clicks.get())
    }

    /**
     * A retry that turned a genuinely missing node into a hang — or worse, into a
     * pass — would be a cure worse than the flake. The helper must still own its
     * clock and still fail.
     */
    @Test
    fun aRowThatNeverAppearsStillFailsAtItsOwnDeadline() {
        compose.setContent { Text("nothing tagged here") }
        compose.waitForIdle()
        val started = SystemClock.uptimeMillis()

        val result = runCatching { compose.clickQuietTag(ROW_TAG, SHORT_TIMEOUT_MS) }

        val elapsed = SystemClock.uptimeMillis() - started
        assertTrue("a node that never arrives must still fail", result.isFailure)
        assertEquals("no click may be reported", 0, clicks.get())
        assertTrue(
            "the helper must give up on its own ${SHORT_TIMEOUT_MS}ms deadline " +
                "(took ${elapsed}ms)",
            elapsed < SHORT_TIMEOUT_MS * 4,
        )
    }

    /**
     * The retry must not double-fire. Every journey's navigation runs through
     * this helper, and a second tap on an already-opened host row would navigate
     * somewhere nobody asked for.
     */
    @Test
    fun aStableRowIsClickedExactlyOnce() {
        showRows()

        compose.clickQuietTag(ROW_TAG, TIMEOUT_MS)

        compose.waitForIdle()
        assertEquals("a present node must be clicked once, not retried", 1, clicks.get())
    }

    private fun showRows() {
        compose.setContent {
            Column {
                if (present.value) {
                    Text(
                        "host row",
                        Modifier
                            .testTag(ROW_TAG)
                            .clickable { clicks.incrementAndGet() },
                    )
                }
                if (duplicated.value) {
                    Text(
                        "a second row wearing the same tag",
                        Modifier
                            .testTag(ROW_TAG)
                            .clickable { clicks.incrementAndGet() },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private companion object {

        const val ROW_TAG = "quiet-navigation-race-row"

        const val TIMEOUT_MS = 15_000L

        /**
         * Comfortably longer than the helper's retry backoff, so a green can only
         * come from a retry, and well inside [TIMEOUT_MS].
         */
        const val SETTLE_MS = 750L

        /** Small, because the property is "it gives up on ITS deadline". */
        const val SHORT_TIMEOUT_MS = 1_500L
    }
}
