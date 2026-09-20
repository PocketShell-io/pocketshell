package com.pocketshell.next.connect

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #2783 item 3 / on-call w17b: [clickQuietTag] must be able to walk BACK
 * to the route its row lives on, because there is a real app state in which
 * waiting for that row can never work.
 *
 * ## The failure this pins
 *
 * app2 run 35437700722 attempt 1 (journey job 105885154988, `5371d3b05`) lost
 * `J12UsagePanelJourney.theGlancePillOpensThePanelAndExpandsACardOnCompactRowTap`
 * to `ComposeTimeoutException: Condition still not satisfied after 60000 ms` —
 * the WHOLE budget, inside `clickQuietTag`'s own await
 * (`awaitQuietTag(QuietNavigation.kt:140) ← clickQuietTag(:173) ←
 * openQuietHost(:39)`), while its per-method logcat showed the app had already
 * connected host 9801 and started navigating five seconds earlier:
 * `navigation effect host=9801`, `Hosts route hiding during navigation handoff
 * host=9801`, then sixty seconds of nothing.
 *
 * Nothing in the journey asked for that connect: `MainActivity` resumes the
 * last opened host on RESUMED (`MainActivity.kt:922-950` → `ConnectGate`'s
 * dial at `ConnectGate.kt:87-92`), and an earlier method of the same class had
 * already written that preference by tapping the row — and
 * `createAndroidComposeRule` launches a fresh Activity for every test method,
 * so the dial runs again each time. The journey's own click races the app's
 * cold-start dial to the same host, and loses sometimes.
 *
 * ## Why no amount of retrying could have saved it
 *
 * [com.pocketshell.next.connect.ConnectGate] (`ConnectGate.kt:117-122`) does
 * not hide a banner during that handoff — it replaces the ENTIRE Hosts route
 * with a transparent `Box`:
 *
 * ```kotlin
 * if (navigationInFlight || state.navigateToHostId != null) {
 *     Box(modifier = modifier.fillMaxSize())      // no list, no rows
 * } else {
 *     Column(modifier = modifier.fillMaxSize()) { … content(onOpenHost) … }
 * }
 * ```
 *
 * so the tree carries neither `host-list` nor any `host-row-*`, and
 * `navigationInFlight` is cleared by a lifecycle `ON_START` — which arrives
 * when Back makes Hosts visible again. The row is not late, it is UNREACHABLE,
 * and the pre-fix loop's only move was to ask for it again until the journey
 * ran out of budget.
 *
 * [aRowHiddenByANavigationHandoffIsReachedByBackingOutOfTheRouteBelow] holds
 * that state still and is the red→green gate: the await the pre-fix helper
 * spent its whole budget on is asserted to fail against this fixture FIRST, in
 * the test, before the recovering helper is asked to succeed.
 *
 * ## Modelled, and where the model stops
 *
 * The fixture reproduces `ConnectGate`'s SHAPE (transparent route, destination
 * composed beneath, Back restores the list), not `ConnectGate` itself — the real
 * gate needs Hilt, a `ConnectViewModel` and a live dial, and the five-second
 * window that opened it on the lane is a race nobody can schedule. What is
 * asserted is the property that failed: a click whose row is hidden by a route
 * the helper can walk out of must still land, on the caller's budget.
 *
 * ## Why these run on the device rather than the JVM
 *
 * [clickQuietTag] is journey-harness code under `app2/src/androidTest`, which
 * the JVM unit lane does not compile, and both halves of the behaviour — real
 * semantics-tree resolution and a real Back through the Activity's own
 * dispatcher — only exist on a device. Wiring is automatic:
 * `.github/workflows/app2.yml`'s `app2-journey` job runs
 * `:app2:connectedDebugAndroidTest` ONCE, unfiltered, so every class under
 * `app2/src/androidTest/` runs. Nothing here needs a fixture host, the app's
 * own screens, or the network.
 */
@RunWith(AndroidJUnit4::class)
class QuietNavigationRouteRecoveryTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** `ConnectGate.navigationInFlight`: true ⇒ the list route is a bare Box. */
    private val navigationInFlight = mutableStateOf(false)

    /** Whether the list route currently offers its row. */
    private val rowVisible = mutableStateOf(false)

    private val clicks = AtomicInteger(0)

    private val backPresses = AtomicInteger(0)

    /**
     * Every Back is counted and CONSUMED, including one the helper should never
     * have pressed: an unconsumed Back on the list route would finish the
     * Activity, and a journey class shares one Activity across all of its
     * methods — the mutation has to be reported here, not paid for by the next
     * test. Clearing the flag is the fixture's stand-in for the real gate's
     * `ON_START` reset; it is a no-op when nothing is in flight.
     */
    @Before
    fun consumeAndCountEveryBack() {
        compose.runOnUiThread {
            compose.activity.onBackPressedDispatcher.addCallback(
                compose.activity,
                object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        backPresses.incrementAndGet()
                        navigationInFlight.value = false
                    }
                },
            )
        }
    }

    /**
     * The gate for this fix.
     *
     * The fixture starts in the handoff state run 35437700722 attempt 1 died in:
     * no list, no row, the destination composed underneath. The pre-fix helper's
     * only move — await the row — is asserted to fail here, because in this
     * state it can NEVER succeed. The recovering helper backs out of the route
     * below, the list comes back, and the row is clicked.
     */
    @Test
    fun aRowHiddenByANavigationHandoffIsReachedByBackingOutOfTheRouteBelow() {
        rowVisible.value = true
        navigationInFlight.value = true
        showRoutes()

        assertTrue(
            "the fixture must reproduce the handoff: the destination is composed",
            compose.hasQuietTag(DESTINATION_TAG),
        )
        assertFalse("…the list route is gone", compose.hasQuietTag(LIST_TAG))
        assertFalse("…and with it the row the click needs", compose.hasQuietTag(ROW_TAG))

        // This is what the pre-fix helper did with its entire 60s: ask again.
        val awaited = runCatching { compose.awaitQuietTag(ROW_TAG, UNREACHABLE_PROBE_MS) }
        assertTrue(
            "awaiting alone must fail against this tree — that IS the bug " +
                "(got ${awaited.exceptionOrNull()})",
            awaited.isFailure,
        )
        assertEquals("nothing may have been clicked yet", 0, clicks.get())

        compose.clickQuietTag(
            tag = ROW_TAG,
            timeoutMillis = TIMEOUT_MS,
            recover = {
                compose.backOutOfRouteBelow(
                    hereTag = LIST_TAG,
                    belowTags = arrayOf(DESTINATION_TAG),
                )
            },
        )

        assertEquals("the row must be clicked once the route is restored", 1, clicks.get())
        assertEquals(
            "one Back is enough — the recovery must not keep pressing after it worked",
            1,
            backPresses.get(),
        )
    }

    /**
     * The recovery's dangerous direction, and the reason it is guarded by
     * `hereTag` rather than by the absence of the row.
     *
     * A helper that pressed Back whenever the row was missing would, on a host
     * list whose row is merely late (a seed between journey methods, a listing
     * still in flight), walk out of the screen it was asked to click on — and
     * off the end of the back stack, taking the Activity with it. On the
     * row's own route the recovery must do NOTHING and the wait must be left to
     * do its job — even though a downstream tag is visible, which this fixture
     * keeps composed throughout precisely so that only the `hereTag` guard can
     * produce the green.
     */
    @Test
    fun aLateRowOnItsOwnRouteIsWaitedForAndNeverBackedOutOf() {
        rowVisible.value = false
        showRoutes()
        assertTrue("the fixture must start on the list route", compose.hasQuietTag(LIST_TAG))
        assertTrue(
            "…with a downstream tag visible, so only the hereTag guard can hold Back back",
            compose.hasQuietTag(DESTINATION_TAG),
        )

        // Later than one attempt slice, so the green can only come from the loop
        // continuing to wait across attempts rather than from a single await.
        Handler(Looper.getMainLooper()).postDelayed({ rowVisible.value = true }, LATE_ROW_MS)

        compose.clickQuietTag(
            tag = ROW_TAG,
            timeoutMillis = TIMEOUT_MS,
            recover = {
                compose.backOutOfRouteBelow(
                    hereTag = LIST_TAG,
                    belowTags = arrayOf(DESTINATION_TAG),
                )
            },
        )

        assertEquals("the late row must still be clicked", 1, clicks.get())
        assertEquals(
            "Back must never be pressed while the row's own route is on screen",
            0,
            backPresses.get(),
        )
    }

    /**
     * Slicing an attempt's await must not shorten the helper. The late row above
     * pins one end — a row arriving after more than one slice is still clicked;
     * this pins the other — a row that never arrives still fails on the
     * CALLER's deadline, and the failure still carries what actually went wrong.
     */
    @Test
    fun aRowThatNeverArrivesStillFailsOnTheCallersDeadlineWithItsCause() {
        rowVisible.value = false
        showRoutes()
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
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(
            "the failure must name the tag and the budget, got: $message",
            message.contains(ROW_TAG) && message.contains("${SHORT_TIMEOUT_MS}ms"),
        )
        assertTrue(
            "the real error must survive as the cause, got: " +
                "${result.exceptionOrNull()?.cause}",
            result.exceptionOrNull()?.cause != null,
        )
    }

    /**
     * `ConnectGate`'s shape: a transparent route while a navigation is in
     * flight, with the destination composed beneath it, and a Back that makes
     * the list visible again — the only way out of this state in either.
     */
    private fun showRoutes() {
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                Text("destination", Modifier.testTag(DESTINATION_TAG))
                if (!navigationInFlight.value) {
                    Column(Modifier.fillMaxSize()) {
                        Text("host list", Modifier.testTag(LIST_TAG))
                        if (rowVisible.value) {
                            Text(
                                "host row",
                                Modifier
                                    .testTag(ROW_TAG)
                                    .clickable { clicks.incrementAndGet() },
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private companion object {

        const val LIST_TAG = "quiet-navigation-recovery-list"

        const val ROW_TAG = "quiet-navigation-recovery-row"

        /** The route the handoff leaves composed underneath the transparent Box. */
        const val DESTINATION_TAG = "quiet-navigation-recovery-destination"

        const val TIMEOUT_MS = 20_000L

        /**
         * Longer than the helper's attempt slice, so a green proves the loop
         * kept waiting across attempts instead of one await getting lucky.
         */
        const val LATE_ROW_MS = 6_500L

        /**
         * Many times the fixture's settle, so "the await failed" means the row
         * is unreachable in this state rather than merely slow.
         */
        const val UNREACHABLE_PROBE_MS = 2_000L

        /** Small, because the property is "it gives up on ITS deadline". */
        const val SHORT_TIMEOUT_MS = 1_500L
    }
}
