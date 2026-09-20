package com.pocketshell.next.connect

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.pocketshell.next.hosts.HOST_LIST_TAG
import com.pocketshell.next.hosts.hostRowTag
import com.pocketshell.next.terminal.SESSION_SCREEN_TAG
import com.pocketshell.next.tree.sessionRowTag
import com.pocketshell.next.workspaces.HOST_WORKSPACES_EMPTY_TAG
import com.pocketshell.next.workspaces.HOST_WORKSPACES_ERROR_TAG
import com.pocketshell.next.workspaces.HOST_WORKSPACES_LIST_TAG
import com.pocketshell.next.workspaces.HOST_WORKSPACES_LOADING_TAG
import com.pocketshell.next.workspaces.HOST_WORKSPACES_TAG
import com.pocketshell.next.workspaces.WORKSPACE_SCREEN_TAG
import com.pocketshell.next.workspaces.workspaceRowTag
import com.pocketshell.next.workspaces.workspaceSessionRowTag
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage

/**
 * Device-test navigation for the Quiet host → workspace → session hierarchy.
 *
 * The production route no longer exposes the legacy session-tree screen. A
 * session whose cwd is exactly a root appears on the host screen; a session
 * below a root appears after opening that workspace row. Keeping that choice
 * here makes every terminal/composer journey assert the same real route.
 */
fun ComposeTestRule.openQuietHost(hostId: Long, timeoutMillis: Long = 60_000L) {
    returnToHostListIfNeeded(hostId, timeoutMillis)
    clickQuietTag(
        tag = hostRowTag(hostId),
        timeoutMillis = timeoutMillis,
        recover = {
            backOutOfRouteBelow(
                hereTag = HOST_LIST_TAG,
                belowTags = arrayOf(
                    HOST_WORKSPACES_TAG,
                    WORKSPACE_SCREEN_TAG,
                    SESSION_SCREEN_TAG,
                ),
            )
        },
    )
    awaitQuietTag(HOST_WORKSPACES_TAG, timeoutMillis)
    waitUntil(timeoutMillis) {
        listOf(
            HOST_WORKSPACES_LIST_TAG,
            HOST_WORKSPACES_EMPTY_TAG,
            HOST_WORKSPACES_ERROR_TAG,
            HOST_WORKSPACES_LOADING_TAG,
        ).any { onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty() }
    }
}

/**
 * A journey class keeps one Activity instance for all of its test methods,
 * while its seed rule deliberately replaces the host row between methods.
 * Return through the real back stack before looking for the newly seeded row;
 * otherwise the next method remains on the previous host's workspace route
 * and the failure is reported as a missing host instead of a navigation bug.
 */
private fun ComposeTestRule.returnToHostListIfNeeded(hostId: Long, timeoutMillis: Long) {
    val rowTag = hostRowTag(hostId)
    fun has(tag: String): Boolean = hasQuietTag(tag)

    runCatching {
        waitUntil(minOf(timeoutMillis, 1_000L)) { has(rowTag) || has(HOST_LIST_TAG) }
    }
    if (has(rowTag) || has(HOST_LIST_TAG)) return

    repeat(6) {
        pressBackOnce()
        if (has(rowTag) || has(HOST_LIST_TAG)) return
    }
}

/** Opens a live session through the real Quiet hierarchy and waits for terminal chrome. */
fun ComposeTestRule.openQuietSession(
    hostId: Long,
    sessionName: String,
    workspacePath: String,
    timeoutMillis: Long = 60_000L,
) {
    openQuietHost(hostId, timeoutMillis)
    val rootSessionTag = workspaceSessionRowTag(sessionName)
    val workspaceTag = workspaceRowTag(workspacePath)
    try {
        waitUntil(timeoutMillis) {
            val rootVisible = onAllNodesWithTag(rootSessionTag).fetchSemanticsNodes().isNotEmpty()
            val workspaceVisible = onAllNodesWithTag(workspaceTag).fetchSemanticsNodes().isNotEmpty()
            if (rootVisible || workspaceVisible) return@waitUntil true

            // The host list is a real LazyColumn. A session may be below the
            // first viewport when another journey has left additional
            // workspaces on the fixture. Listing is asynchronous, so retry
            // after the list itself appears rather than racing the initial
            // loading state.
            if (onAllNodesWithTag(HOST_WORKSPACES_LIST_TAG).fetchSemanticsNodes().isNotEmpty()) {
                val list = onNodeWithTag(HOST_WORKSPACES_LIST_TAG)
                runCatching { list.performScrollToNode(hasTestTag(rootSessionTag)) }
                runCatching { list.performScrollToNode(hasTestTag(workspaceTag)) }
                // Keep a user-like fallback for older Compose semantics trees
                // that cannot match an off-screen test tag through the
                // lazy-list provider. Repeated swipes are bounded by
                // [timeoutMillis] and make the journey independent of
                // unrelated session count.
                runCatching { list.performTouchInput { swipeUp() } }
            }
            false
        }
    } catch (error: Throwable) {
        val listCount = onAllNodesWithTag(HOST_WORKSPACES_LIST_TAG)
            .fetchSemanticsNodes().size
        val rootCount = onAllNodesWithTag(rootSessionTag).fetchSemanticsNodes().size
        val workspaceCount = onAllNodesWithTag(workspaceTag).fetchSemanticsNodes().size
        println(
            "QUIET_NAV_TIMEOUT session=$sessionName workspace=$workspacePath " +
                "listNodes=$listCount rootNodes=$rootCount workspaceNodes=$workspaceCount",
        )
        runCatching {
            JourneyScreenshots.capture("quiet-navigation-timeout", "quiet-navigation")
        }.onSuccess { shot -> println("QUIET_NAV_SCREENSHOT ${shot.absolutePath}") }
        throw error
    }
    if (onAllNodesWithTag(rootSessionTag).fetchSemanticsNodes().isNotEmpty()) {
        onNodeWithTag(rootSessionTag).performClick()
    } else {
        onNodeWithTag(workspaceTag).performClick()
        awaitQuietTag(WORKSPACE_SCREEN_TAG, timeoutMillis)
        clickQuietTag(
            tag = sessionRowTag(sessionName),
            timeoutMillis = timeoutMillis,
            recover = {
                backOutOfRouteBelow(
                    hereTag = WORKSPACE_SCREEN_TAG,
                    belowTags = arrayOf(SESSION_SCREEN_TAG),
                )
            },
        )
    }
    awaitQuietTag(SESSION_SCREEN_TAG, timeoutMillis)
}

fun ComposeTestRule.awaitQuietTag(tag: String, timeoutMillis: Long = 60_000L) {
    waitUntil(timeoutMillis) {
        onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
}

/**
 * Awaits [tag] and clicks it, re-resolving the node on every attempt, and
 * running [recover] between attempts so a route that moved under the helper can
 * be walked back to instead of stared at.
 *
 * ## The await/click gap (#2783, fixed in 121e6eb95)
 *
 * [awaitQuietTag] proves the node existed at the moment the wait returned; it
 * does not promise the node is still there one statement later. Between the two
 * the screen can recompose — a seeded host row replaced between journey
 * methods, a `LazyColumn` re-emitting when its backing flow settles — and the
 * click then fails with
 * `Failed to inject touch input ... could not find any node that satisfies`
 * while the wait that just preceded it succeeded. That gap reddened
 * `J12UsagePanelJourney` on Release Emulator Validation run 35383053367
 * (`QuietNavigation.kt:39` awaited `host-row-9801`, `:40` could not find it)
 * while the SAME commit's unfiltered app2 run 35374191870 attempt 2 was green.
 * Retrying closes it without weakening the check.
 *
 * ## The route that never comes back (#2783 item 3, on-call w17b)
 *
 * Retrying alone is not enough, and one retry loop staring at a tag that cannot
 * return is WORSE than one failed click: it burns the caller's whole budget
 * before anyone sees a diagnosis. app2 run 35437700722 attempt 1 (journey job
 * 105885154988, `5371d3b05`) reddened the non-quarantined sibling
 * `theGlancePillOpensThePanelAndExpandsACardOnCompactRowTap` with
 *
 * ```
 * androidx.compose.ui.test.ComposeTimeoutException: Condition still not satisfied after 60000 ms
 *   at …QuietNavigationKt.awaitQuietTag(QuietNavigation.kt:140)
 *   at …QuietNavigationKt.clickQuietTag(QuietNavigation.kt:173)
 *   at …QuietNavigationKt.openQuietHost(QuietNavigation.kt:39)
 * ```
 *
 * — the FULL 60 s, on the first attempt's await (the loop's own arithmetic can
 * only report `60000` on iteration one), spent waiting for `host-row-9801` on a
 * screen that structurally could not show it. Its per-method logcat has the
 * host connecting and navigating five seconds before the wait even started:
 *
 * ```
 * 11:15:55.617 PocketShell.Connect: connect requested host=9801
 * 11:15:56.130 PocketShell.Connect: connect result host=9801 connected; navigation queued
 * 11:15:56.148 PocketShell.Connect: navigation effect host=9801
 * 11:15:56.149 PocketShell.Connect: Hosts route hiding during navigation handoff host=9801
 *                                   … 60 s of nothing …
 * 11:16:56.632 TestRunner: failed: theGlancePillOpensThePanelAndExpandsACardOnCompactRowTap
 * ```
 *
 * Nothing in the journey asked for that connect. `MainActivity` resumes the
 * last host the user opened (`MainActivity.kt:922-950` hands the validated
 * `appSettings.defaultHostId` to `ConnectGate`, which dials it in
 * `ConnectGate.kt:87-92`), and a journey class writes exactly that preference
 * the moment one of its earlier methods taps a host row. `createAndroidComposeRule`
 * launches a FRESH Activity per test method and the seed rule re-inserts the
 * same host id, so from a class's second method onward every launch races the
 * journey's own click against the app's cold-start dial to the same host —
 * usually the click wins, which is why this is a flake and not a break.
 *
 * That last line is not a metaphor. `ConnectGate` (app2 `ConnectGate.kt:117-122`)
 * replaces the whole Hosts route with a transparent `Box` while a navigation is
 * in flight, so during the handoff the tree carries NEITHER [HOST_LIST_TAG] nor
 * any `host-row-*`, and the flag that restores them is cleared by an
 * `ON_START` lifecycle event — i.e. by going BACK. A helper that only re-awaits
 * a tag cannot reach that state: nothing it does can make the row return.
 * [recover] is the escape, and pressing back is the only thing that is.
 *
 * ## Shape
 *
 * Each attempt awaits at most [ATTEMPT_AWAIT_SLICE_MS] rather than the whole
 * remaining budget, so the loop gets its recovery chances INSIDE the caller's
 * deadline instead of spending the deadline on one wait. Slicing does not
 * shorten the helper: a row that honestly takes 20 s to seed is still awaited
 * across slices until [timeoutMillis]. Nothing here waits unbounded — every
 * attempt's await is capped by the time left, so the helper always returns
 * control at its own deadline (the property `BoundedWaitTest` exists to
 * protect) — and a node that genuinely never arrives still fails, carrying the
 * real assertion error as its cause plus how many attempts and recoveries were
 * spent on it.
 */
internal fun ComposeTestRule.clickQuietTag(
    tag: String,
    timeoutMillis: Long = 60_000L,
    recover: () -> Boolean = { false },
) {
    val deadline = SystemClock.uptimeMillis() + timeoutMillis
    var lastError: Throwable? = null
    var attempts = 0
    var recoveries = 0
    do {
        val remaining = (deadline - SystemClock.uptimeMillis()).coerceAtLeast(1L)
        attempts++
        val attempt = runCatching {
            awaitQuietTag(tag, minOf(remaining, ATTEMPT_AWAIT_SLICE_MS))
            onNodeWithTag(tag).performClick()
        }
        if (attempt.isSuccess) return
        lastError = attempt.exceptionOrNull()
        // Best-effort by contract: a recovery that cannot run must not replace
        // the click's own diagnosis with its own, and only a recovery that
        // actually DID something may be counted as one.
        if (runCatching { recover() }.getOrDefault(false)) recoveries++
        SystemClock.sleep(CLICK_RETRY_BACKOFF_MS)
    } while (SystemClock.uptimeMillis() < deadline)
    val last = lastError
    throw AssertionError(
        "clickQuietTag($tag) gave up after ${timeoutMillis}ms " +
            "($attempts attempts, $recoveries recoveries); last error: $last",
        last,
    )
}

/**
 * Presses Back once when the tree is on a route BELOW the one [hereTag]'s row
 * lives on, so [clickQuietTag] can restore the precondition its click needs.
 *
 * Both guards matter and both are conservative:
 *
 *  - [hereTag] present ⇒ do nothing. We are already on the route that owns the
 *    row; the row is simply not there yet (a seed that has not landed, a list
 *    still loading), and Back would walk AWAY from it — or, on the host list,
 *    out of the app entirely, ending the journey's Activity mid-method.
 *  - none of [belowTags] present ⇒ do nothing. Without a positive signal that
 *    we are somewhere downstream there is nothing to back out OF, and a blind
 *    Back is the same activity-exit risk.
 *
 * The transparent-handoff state from the KDoc above satisfies both: no
 * [HOST_LIST_TAG], no row, and the destination already composed underneath
 * ([HOST_WORKSPACES_TAG]) because `ConnectGate` keeps its own route
 * transparent rather than removing it. Backing out of it fires the `ON_START`
 * that clears `navigationInFlight`, the host list re-renders with its rows, and
 * the next attempt clicks the row the journey actually asked for — rather than
 * ASSUMING the destination we can see belongs to the host we were asked to
 * open, which for a route-level tag shared by every host would pass the
 * journey on the wrong screen.
 *
 * @return true when a Back was actually dispatched, for the caller's counters.
 */
internal fun ComposeTestRule.backOutOfRouteBelow(
    hereTag: String,
    belowTags: Array<String>,
): Boolean {
    if (hasQuietTag(hereTag)) return false
    if (belowTags.none { hasQuietTag(it) }) return false
    pressBackOnce()
    return true
}

/** Whether the semantics tree currently carries at least one [tag] node. */
internal fun ComposeTestRule.hasQuietTag(tag: String): Boolean =
    onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

/**
 * One real Back through the Activity's own dispatcher — the same press the user
 * makes, so route-scoped `BackHandler`s and lifecycle effects run — followed by
 * a settle, because the caller's next decision reads the semantics tree.
 */
private fun ComposeTestRule.pressBackOnce() {
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        val resumed = ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(Stage.RESUMED)
            .firstOrNull()
        (resumed as? ComponentActivity)?.onBackPressedDispatcher?.onBackPressed()
            ?: resumed?.onBackPressed()
    }
    waitForIdle()
    SystemClock.sleep(BACK_SETTLE_MS)
}

/**
 * Short enough that a one-recomposition disappearance costs the journey
 * milliseconds, long enough that a genuinely missing node is not retried
 * thousands of times before its deadline.
 */
private const val CLICK_RETRY_BACKOFF_MS = 50L

/**
 * The most one attempt may spend waiting before the loop gets to recover.
 *
 * Long enough that the ordinary late arrival — a host row seeded between
 * journey methods, a workspace listing still in flight — is awaited inside a
 * single slice and costs no recovery at all; short enough that the default
 * 60 s budget still holds a dozen chances to walk back out of a route that
 * cannot produce the tag. It bounds an ATTEMPT, never the helper: the caller's
 * [clickQuietTag] deadline is what ends the loop.
 */
private const val ATTEMPT_AWAIT_SLICE_MS = 5_000L

/** Matches the settle the pre-#2783 back-press loop used. */
private const val BACK_SETTLE_MS = 100L
