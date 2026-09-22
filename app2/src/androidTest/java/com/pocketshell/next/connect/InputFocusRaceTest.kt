package com.pocketshell.next.connect

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.PopupWindow
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.AssumptionViolatedException
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The regression for issue #2789: [awaitInputFocus] must not let a key be
 * injected until the platform really delivers it.
 *
 * ## The failure this pins
 *
 * `J03AttachAndTypeJourney#attachingToARealSessionRendersItAndAcceptsTyping`
 * reddened two consecutive hosted `main` heads (`app2` runs 35419099211 and
 * 35420884479, `6157a6926` / `0bf6545dd`) with a byte-identical signature, and
 * was green on the third (35421432711, `073086374`). It typed
 * `echo pocketshell-u4-ok`; the host's own `a capture` and the phone's
 * viewport AGREED that what arrived was `cketshell-u4-ok` — **`echo po`, the
 * leading 7 characters, gone**. Not a slow render: bytes that never crossed the
 * wire. The discriminator between the reds and the green was one logcat line,
 * present on both reds and absent on the green:
 *
 * ```
 * InputDispatcher: Waiting because no window has focus but ActivityRecord{…
 *   com.pocketshell.app/…MainActivity …} may eventually add a window …
 *   Will wait for 60000ms
 * ```
 *
 * Same observable as #1854 (`printf` → `tf`, 4 chars) against the module the
 * rewrite deleted.
 *
 * ## The actual defect, stated as a property
 *
 * The pre-fix harness shape — [preFixFocusShape], copied verbatim from the
 * deleted `J03AttachAndTypeJourney.focusTerminal` — observes NEITHER thing the
 * dispatch it precedes depends on:
 *
 *  - `View.requestFocus()` is a *request*. A view that is not attached, not
 *    laid out, or not focusable refuses it, and the only place that answer
 *    appears is [View.hasFocus].
 *  - `Instrumentation.waitForIdleSync()` proves the main LOOPER went idle. The
 *    platform `InputDispatcher` routes to the focused WINDOW, and the two are
 *    unrelated quantities — an idle looper says nothing about
 *    [View.hasWindowFocus].
 *
 * So the shape returns while the target can still receive nothing, and
 * `sendStringSync` hands its events to a dispatcher that drops them. Because
 * focus then arrives mid-line, the loss is a PREFIX and the rest of the line
 * lands — which is why it reads as a corrupt command rather than a missing one.
 *
 * ## The oracle is the RECEIVED BYTES, not "we called requestFocus"
 *
 * Every case below asserts what [KeyProbeView] was actually handed by the real
 * platform dispatch path, exactly as J03 asserts the host's `a capture`. A test
 * that asserted `requestFocus()` had been called would pass against the defect.
 *
 * Both halves of the precondition are held false deterministically, each in the
 * way that can be held still:
 *
 *  - [keysDispatchedAtAViewThatNeverGotFocusAreLostAndTheOracleWaitsForIt] —
 *    the VIEW half, held by a probe that is not yet focusable. The pre-fix
 *    shape completes with `hasFocus()` false and the keys vanish; the oracle
 *    waits, and the full string arrives.
 *  - [keysDispatchedWithTheWindowFocusElsewhereAreLostAndTheOracleWaitsForIt] —
 *    the WINDOW half, held by a focusable [PopupWindow], which is how the
 *    activity's window can be made to report `hasWindowFocus() == false` on
 *    demand. This is the synthetic stand-in the issue's scope item 3 calls for:
 *    the hosted runner's own sub-second focus gap cannot be manufactured from
 *    outside the platform, and skipping the assertion instead is what D33
 *    forbids. Note the probe keeps `hasFocus() == true` throughout — view focus
 *    is per-window — which is exactly why checking only the view half would
 *    still ship the bug.
 *
 * ## Why these run on the device rather than the JVM
 *
 * [awaitInputFocus] is journey-harness code in `app2/src/androidTest`, which
 * the JVM unit lane does not compile, and the behaviour under test IS the
 * platform's real input dispatch. Wiring is automatic: `app2.yml`'s
 * `app2-journey` job runs `:app2:connectedDebugAndroidTest` ONCE, unfiltered,
 * so every class under `app2/src/androidTest/` runs. Nothing here needs a
 * fixture host, the app's own screens, or the network.
 */
@RunWith(AndroidJUnit4::class)
class InputFocusRaceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var probe: KeyProbeView? = null

    private var popup: PopupWindow? = null

    /**
     * Every case here assumes a device on which window focus is OBTAINABLE —
     * the fixtures take it away and give it back on purpose — so a device-wide
     * outage makes all four oracles wrong rather than failing (issue #2838).
     *
     * Run 35462083590 proved the cost: this class contributed 4 of that run's
     * 19 failures, every one of them `the window never took focus … the
     * focus-settle signal stayed false` raised by a precondition, none having
     * reached the #2789 behaviour the class exists to pin. It stands aside
     * against a report that already exists and never creates one, so the run
     * still carries exactly one outage FAILURE and the diagnosis with it.
     */
    @Before
    fun standAsideOnADeviceFocusOutage() {
        assumeNoRecordedDeviceFocusOutage("InputFocusRaceTest")
    }

    /**
     * A popup left up by a failing case would take window focus away from the
     * NEXT test class in the unfiltered suite (#2474 runs them all in one
     * process). Best effort: if it is already gone there is nothing to dismiss.
     */
    @After
    fun dismissPopup() {
        runCatching {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                popup?.dismiss()
            }
        }
        popup = null
    }

    /**
     * The gate for the view half: a probe the pre-fix shape declares ready and
     * the platform will not deliver to.
     */
    @Test
    @Ignore("quarantined: #2830, expires 2026-10-06 — hosted-emulator device-wedge family: FAILED on app2 runs 35576360998 and 35701244946 (attempt 1 each, commit 2fd666a6c, journey-only reds whose failures rotate across attempts of identical bytes), each with the harness verdict 'PRIMARY CAUSE: the device wedged, not the product'; the view-half case is the one that needs the platform to hold focus steady, the same outage this class already stands aside on per #2838")
    fun keysDispatchedAtAViewThatNeverGotFocusAreLostAndTheOracleWaitsForIt() {
        showProbe(focusable = false)

        // The pre-fix shape completes — this is why J03 went on to type.
        preFixFocusShape()
        assertFalse(
            "the fixture must really hold the view unfocused, or the loss below " +
                "would prove nothing",
            readOnMain { it.hasFocus() },
        )
        // Through the real oracle, not a bare read: on a healthy device it
        // returns as soon as the window has focus (which it does — the probe
        // is the only window up), and on a wedged one it names the device
        // instead of reporting "the window half was not satisfied" as though
        // this fixture had failed to arrange it (issue #2838).
        compose.awaitWindowFocus("the probe's window before isolating the view half", TIMEOUT_MS)
        assertTrue(
            "the WINDOW half must be satisfied here, so this case isolates the " +
                "view half",
            readOnMain { it.hasWindowFocus() },
        )

        // The #2789 observable: the bytes are gone, and nothing failed.
        sendString(LOST_PREFIX)
        assertStaysEmpty()

        // Let the view become focusable the way a laid-out recomposition does.
        onMainAfter(SETTLE_MS) { it.setProbeFocusable(true) }

        compose.awaitInputFocus("the probe view", TIMEOUT_MS) { probe }

        assertTrue(
            "the oracle must not return before the platform says the view has focus",
            readOnMain { it.hasFocus() && it.hasWindowFocus() },
        )
        sendString(MARKER)
        assertReceived(MARKER)
    }

    /**
     * The gate for the window half, and the literal shape of the two reds: the
     * view holds focus, the window does not, and the keys go elsewhere.
     */
    @Test
    fun keysDispatchedWithTheWindowFocusElsewhereAreLostAndTheOracleWaitsForIt() {
        showProbe(focusable = true)
        compose.awaitInputFocus("the probe view before the window loses focus", TIMEOUT_MS) {
            probe
        }
        assertReceived("")

        takeWindowFocusAway()

        // The pre-fix shape completes here too — an idle main looper is not
        // window focus, which is the whole defect.
        preFixFocusShape()
        assertTrue(
            "the probe must still hold VIEW focus — view focus is per-window, so " +
                "a view-only check would call this state ready",
            readOnMain { it.hasFocus() },
        )
        assertFalse(
            "the fixture must really hold the activity's window unfocused",
            readOnMain { it.hasWindowFocus() },
        )

        sendString(LOST_PREFIX)
        assertStaysEmpty()

        onMainAfter(SETTLE_MS) { popup?.dismiss() }

        compose.awaitInputFocus("the probe view", TIMEOUT_MS) { probe }

        assertTrue(
            "the oracle must not return before the window is focused again",
            readOnMain { it.hasFocus() && it.hasWindowFocus() },
        )
        sendString(MARKER)
        assertReceived(MARKER)
    }

    /**
     * An oracle that turned a genuinely unfocusable view into a hang — or worse,
     * into a pass — would be a cure worse than the flake. It must own its clock
     * and still fail, naming the settle point.
     */
    @Test
    fun aViewThatNeverTakesFocusStillFailsAtItsOwnDeadline() {
        showProbe(focusable = false)
        val started = SystemClock.uptimeMillis()

        val result = runCatching {
            compose.awaitInputFocus("a view that cannot take focus", SHORT_TIMEOUT_MS) { probe }
        }

        val elapsed = SystemClock.uptimeMillis() - started
        // `runCatching` catches the two device-wedge shapes as readily as the
        // failure this case pins, and asserting on their TEXT is how run
        // 35462083590 turned a wedge into `the failure must name the settle
        // point and the observed state, got: …`. Neither is this case's
        // subject, and each already means the right thing where it is raised —
        // so hand them straight back (issue #2838):
        //
        //  - [DeviceWindowFocusOutageException] is the FIRST discovery of the
        //    wedge, and it must stay a FAILURE. A run that skipped its own
        //    diagnosis would end green-with-skips, i.e. blind.
        //  - [AssumptionViolatedException] is a later waiter skipping against
        //    that one report, and JUnit reports it as a skip on the way out.
        val raised = result.exceptionOrNull()
        if (raised is DeviceWindowFocusOutageException || raised is AssumptionViolatedException) {
            throw raised
        }
        assertTrue("a view that never takes focus must fail", result.isFailure)
        val message = raised?.message.orEmpty()
        assertTrue(
            "the failure must name the settle point and the observed state, got: $message",
            message.contains("never took input focus") && message.contains("hasFocus=false"),
        )
        assertTrue(
            "the oracle must give up on its own ${SHORT_TIMEOUT_MS}ms deadline " +
                "(took ${elapsed}ms)",
            elapsed < SHORT_TIMEOUT_MS * 4,
        )
    }

    /**
     * The no-op path stays a no-op: an already-focused view must not be made to
     * wait out a poll rhythm before every keystroke. J03 calls this before each
     * of its injections, so a per-call tax would be paid dozens of times.
     */
    @Test
    fun anAlreadyFocusedViewIsNotMadeToWait() {
        showProbe(focusable = true)
        compose.awaitInputFocus("the probe view", TIMEOUT_MS) { probe }
        val started = SystemClock.uptimeMillis()

        compose.awaitInputFocus("the probe view again", TIMEOUT_MS) { probe }

        val elapsed = SystemClock.uptimeMillis() - started
        assertTrue(
            "a focused view must be accepted on the first poll (took ${elapsed}ms)",
            elapsed < SETTLE_MS,
        )
        sendString(MARKER)
        assertReceived(MARKER)
    }

    // --- the pre-fix shape, verbatim ----------------------------------------

    /**
     * `J03AttachAndTypeJourney.focusTerminal` as it stood before this fix, kept
     * here so the cases above run the real defect rather than a description of
     * it. Deliberately identical, including the `waitForIdleSync()` that reads
     * as a settle and is not one.
     */
    private fun preFixFocusShape() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        compose.awaitIdle("before taking probe focus")
        instrumentation.runOnMainSync {
            val view = probe
            checkNotNull(view) { "no probe view on screen to type into" }
            view.requestFocus()
        }
        instrumentation.waitForIdleSync()
    }

    // --- fixture -------------------------------------------------------------

    private fun showProbe(focusable: Boolean) {
        compose.setContent {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    KeyProbeView(context).also {
                        it.setBackgroundColor(Color.DKGRAY)
                        it.setProbeFocusable(focusable)
                        probe = it
                    }
                },
            )
        }
        compose.waitForIdle()
        checkNotNull(probe) { "the probe view was never created" }
    }

    /**
     * Makes the activity's window report `hasWindowFocus() == false`, the state
     * the hosted reds were in when they typed.
     *
     * A focusable [PopupWindow] is the one way to hold that still from inside
     * an instrumentation test: it becomes the focused window, so injected keys
     * go to it instead of the probe — the same routing that loses a keystroke
     * to a Compose key-bar slot, and the same routing `InputDispatcher` was
     * logging about on the reds. If the state cannot be established the test
     * FAILS here rather than skipping its load-bearing assertion (D33).
     */
    private fun takeWindowFocusAway() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val host = compose.activity.window.decorView
            popup = PopupWindow(View(compose.activity), POPUP_PX, POPUP_PX).apply {
                isFocusable = true
                inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
                setBackgroundDrawable(null)
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
                showAtLocation(host, 0, 0, 0)
            }
        }
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.awaitIdle("waiting for the activity window to lose focus")
            if (!readOnMain { it.hasWindowFocus() }) return
            SystemClock.sleep(SETTLE_POLL_MS)
        }
        throw AssertionError(
            "the fixture could not take window focus away from the activity within " +
                "${TIMEOUT_MS}ms, so the window half of #2789 cannot be asserted " +
                "here. This is a fixture failure, not a pass",
        )
    }

    /** Runs [action] on the main looper [delayMs] from now, the way a recomposition lands. */
    private fun onMainAfter(delayMs: Long, action: (KeyProbeView) -> Unit) {
        val view = checkNotNull(probe) { "no probe view on screen" }
        Handler(Looper.getMainLooper()).postDelayed({ action(view) }, delayMs)
    }

    private fun <T> readOnMain(read: (KeyProbeView) -> T): T =
        compose.runOnUiThread { read(checkNotNull(probe) { "no probe view on screen" }) }

    // --- oracles -------------------------------------------------------------

    private fun sendString(text: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.sendStringSync(text)
        instrumentation.waitForIdleSync()
    }

    private fun received(): String = readOnMain { it.received() }

    private fun assertReceived(expected: String) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        var seen = received()
        while (seen != expected && SystemClock.elapsedRealtime() < deadline) {
            compose.awaitIdle("waiting for the injected keys to arrive")
            seen = received()
            SystemClock.sleep(SETTLE_POLL_MS)
        }
        assertEquals(
            "every injected character must reach the focused view — a PREFIX " +
                "missing here is the #2789 defect",
            expected,
            seen,
        )
    }

    /**
     * Asserts the keys stayed lost, polled rather than sampled once.
     *
     * A single read immediately after the injection could be green merely for
     * being early. This holds the claim across [LOSS_GRACE_MS] of real time
     * with the looper driven between reads, so "nothing arrived" means nothing
     * arrived rather than "nothing had arrived yet".
     */
    private fun assertStaysEmpty() {
        val deadline = SystemClock.elapsedRealtime() + LOSS_GRACE_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.awaitIdle("confirming the injected keys were dropped")
            assertEquals(
                "keys dispatched without the focus precondition must be LOST — a " +
                    "delivery here means the fixture did not hold the defect state",
                "",
                received(),
            )
            SystemClock.sleep(SETTLE_POLL_MS)
        }
    }

    /**
     * A view that records what the platform's key dispatch actually handed it.
     *
     * `onKeyDown` rather than a listener: that is the entry point the vendored
     * `TerminalView` uses to reach `inputCodePoint` → `TerminalSession.write`,
     * so the probe sits on the same dispatch path the journeys do.
     */
    class KeyProbeView(context: Context) : View(context) {

        private val keys = StringBuilder()

        fun setProbeFocusable(focusable: Boolean) {
            isFocusable = focusable
            isFocusableInTouchMode = focusable
        }

        fun received(): String = keys.toString()

        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
            val character = event.unicodeChar
            if (character != 0) {
                keys.append(character.toChar())
                return true
            }
            return super.onKeyDown(keyCode, event)
        }
    }

    private companion object {

        const val TIMEOUT_MS = 30_000L

        /** Small, because the property is "it gives up on ITS deadline". */
        const val SHORT_TIMEOUT_MS = 1_500L

        /**
         * Comfortably longer than [SETTLE_POLL_MS], so a green can only come
         * from the oracle having polled, and well inside [TIMEOUT_MS].
         */
        const val SETTLE_MS = 750L

        /**
         * How long the loss is held before it is believed. Long enough to cover
         * a deferred delivery landing late, short enough to be paid four times
         * in one class.
         */
        const val LOSS_GRACE_MS = 1_500L

        /** The string whose loss J03 observed, in the shape J03 lost it. */
        const val LOST_PREFIX = "echo po"

        const val MARKER = "pocketshell-2789-ok"

        /** Big enough to be a real window, small enough to cover nothing. */
        const val POPUP_PX = 8
    }
}
