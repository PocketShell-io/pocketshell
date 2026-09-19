package com.pocketshell.next.connect

import android.content.Context
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.PopupWindow
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.AssumptionViolatedException
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The reproduction for issue #2830: a device that grants window focus to
 * NOBODY must be reported ONCE, as an environment outage, on the real oracle.
 *
 * ## What run 35435668085 looked like, and what this holds still
 *
 * The lane went red twice on the same tree (`9424a3900`), 5 failures over 2
 * classes then 17 over 8, and 14 of the 17 were [awaitWindowFocus]'s
 * `the window never took focus … the focus-settle signal stayed false` — one
 * per window-sensitive journey, none of which had reached a product assertion.
 * The cause was one line in a logcat artifact nobody had opened yet: the
 * emulator's launcher ANR'd with `Input dispatching timed out (Application
 * does not have a focused window)` on a runner at load 10.52.
 *
 * Both halves of the defect are reproduced here on the REAL path — the real
 * [awaitWindowFocus], the real `dumpsys window` probe, the real process-wide
 * record:
 *
 *  - [aDeviceThatGrantsFocusToNobodyIsReportedOnceAsAnEnvironmentOutage] —
 *    device-wide focus is genuinely withheld (see [withholdDeviceWideFocus]),
 *    the first waiter fails as [DeviceWindowFocusOutageException] with
 *    [DEVICE_FOCUS_OUTAGE_MARKER], the SECOND waiter — standing in for the
 *    next class of the unfiltered suite — is SKIPPED against that one report
 *    instead of restating it, and does so fast rather than burning its own
 *    budget. Against the pre-fix oracle both waiters raise the same bare
 *    `AssertionError`, which is the 14-identical-failures shape.
 *  - [aWindowFocusedElsewhereIsAProductFailureNotAnOutage] — the arm that
 *    keeps the fix honest. Focus is taken by a focusable [PopupWindow], so the
 *    device HAS a focused window and this screen simply does not have it. That
 *    must still fail as #2781's product-shaped assertion: a fix that relabels
 *    every focus timeout as infra would launder
 *    `J06BackgroundGraceReturnJourney`'s own return-from-background bug into a
 *    retry.
 *
 * ## Why not a product screen
 *
 * A bare `ComponentActivity` (the [InputFocusRaceTest] idiom) is enough: the
 * behaviour under test is the oracle's reading of the platform's window focus,
 * not anything the app draws. Nothing here needs the fixture host, the
 * network, or the Hilt graph.
 *
 * ## Containment
 *
 * Both fixtures are local to this activity's own window and are undone in
 * [restoreTheDevice], which then waits for focus to come back before letting
 * the next class start — the unfiltered suite shares one process and one
 * device (issue #2474), so a fixture left up here would be the next class's
 * mystery failure.
 */
@RunWith(AndroidJUnit4::class)
class DeviceFocusOutageReproTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var popup: PopupWindow? = null

    private var preexistingOutage: String? = null

    @Before
    fun captureOutage() {
        preexistingOutage = DeviceFocusOutage.recorded()
    }

    /**
     * The record this class created is dropped; one it merely inherited is put
     * back. On a genuinely wedged device the outage reported BEFORE this class
     * ran is the single report every later class must keep skipping against,
     * and clearing it here would let the suite report the same cause twice.
     */
    @After
    fun restoreTheDevice() {
        restoreDeviceWideFocus()
        runCatching {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { popup?.dismiss() }
        }
        popup = null
        DeviceFocusOutage.clear()
        preexistingOutage?.let { DeviceFocusOutage.record(it) }
    }

    /**
     * The incident, end to end: one report for a device-wide outage, a fast
     * skip for everybody after it, and a clean recovery.
     */
    @Test
    fun aDeviceThatGrantsFocusToNobodyIsReportedOnceAsAnEnvironmentOutage() {
        withholdDeviceWideFocus()

        // The fixture has to really hold the state, or everything below proves
        // nothing: no window ANYWHERE on the device may have focus.
        val withheld = awaitDeviceFocus(DeviceFocusVerdict.NO_FOCUSED_WINDOW)
        assertEquals(
            "the fixture must leave the device with no focused window, got: ${withheld.describe()}",
            DeviceFocusVerdict.NO_FOCUSED_WINDOW,
            withheld.verdict,
        )
        assertFalse(
            "and this activity's window must really be unfocused",
            compose.runOnUiThread { compose.activity.window.decorView.hasWindowFocus() },
        )
        assertNull("no outage is on record before the first waiter", DeviceFocusOutage.recorded())

        // 1. The FIRST waiter diagnoses the device and says so in infra terms.
        val outage = assertThrows(DeviceWindowFocusOutageException::class.java) {
            compose.awaitWindowFocus("the first window-sensitive journey", FIRST_WAIT_MS)
        }
        val reported = outage.message.orEmpty()
        assertTrue(reported, reported.startsWith(DEVICE_FOCUS_OUTAGE_MARKER))
        assertTrue(reported, reported.contains("mCurrentFocus=null"))
        assertTrue(reported, reported.contains("rerun the app2-journey lane"))
        assertFalse(
            "the pre-fix product wording must not be what an outage reports: $reported",
            reported.contains("the focus-settle signal stayed false"),
        )
        assertNotNull("the outage is recorded for the rest of the process", DeviceFocusOutage.recorded())

        // 2. The NEXT waiter — the next class in the unfiltered suite — is
        //    skipped against that one report, and fast.
        val startedAt = SystemClock.elapsedRealtime()
        val skip = assertThrows(AssumptionViolatedException::class.java) {
            compose.awaitWindowFocus("the next window-sensitive journey", LATER_WAIT_MS)
        }
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        val skipMessage = skip.message.orEmpty()
        assertTrue(skipMessage, skipMessage.contains(DEVICE_FOCUS_OUTAGE_MARKER))
        assertTrue(
            "the skip must quote the one report it stands on: $skipMessage",
            skipMessage.contains("rerun the app2-journey lane"),
        )
        assertTrue(
            "a known outage must abort fast, not spend the caller's ${LATER_WAIT_MS}ms budget " +
                "(took ${elapsed}ms)",
            elapsed < LATER_WAIT_MS / 2,
        )

        // 3. And an outage that ends does not poison the rest of the run.
        restoreDeviceWideFocus()
        compose.awaitWindowFocus("the recovered screen", LATER_WAIT_MS)
        assertNull("a recovered device drops the record", DeviceFocusOutage.recorded())
    }

    /**
     * Focus exists, just not here: still a product failure, still #2781's
     * message — with the window that does have focus named in it.
     */
    @Test
    fun aWindowFocusedElsewhereIsAProductFailureNotAnOutage() {
        takeFocusWithAPopup()

        val focused = awaitDeviceFocus(DeviceFocusVerdict.FOCUSED_ELSEWHERE)
        assertEquals(
            "the fixture must leave SOME window focused, got: ${focused.describe()}",
            DeviceFocusVerdict.FOCUSED_ELSEWHERE,
            focused.verdict,
        )
        assertFalse(
            "…while this activity's own window is not the one holding it",
            compose.runOnUiThread { compose.activity.window.decorView.hasWindowFocus() },
        )

        val failure = assertThrows(AssertionError::class.java) {
            compose.awaitWindowFocus("a screen that lost focus to a popup", FIRST_WAIT_MS)
        }

        assertEquals(
            "a focus that exists elsewhere is not a device outage",
            AssertionError::class.java,
            failure.javaClass,
        )
        val message = failure.message.orEmpty()
        assertTrue(message, message.contains("the focus-settle signal stayed false"))
        assertTrue(message, message.contains("This is NOT a device-wide focus outage"))
        assertTrue(
            "the reader is told WHICH window has focus instead of having to open logcat: $message",
            message.contains("the device's focused window is Window{"),
        )
        assertNull("no outage may be recorded for a product failure", DeviceFocusOutage.recorded())
    }

    /**
     * The anti-masking arm: a recorded outage must not turn the NEXT genuine
     * product failure into a skip.
     *
     * A sticky "the device is wedged" record would do exactly that — one bad
     * minute early in a 27-class run would silently skip every focus-dependent
     * assertion after it, and the lane would go green-ish while proving
     * nothing. So the skip is re-earned from the device on every call: the
     * record only survives while the device is STILL granting focus to nobody.
     * Here it recovers, the screen still does not have focus, and the failure
     * is product-shaped.
     */
    @Test
    fun aRecordedOutageStopsSkippingOnceTheDeviceRecovers() {
        withholdDeviceWideFocus()
        awaitDeviceFocus(DeviceFocusVerdict.NO_FOCUSED_WINDOW)
        assertThrows(DeviceWindowFocusOutageException::class.java) {
            compose.awaitWindowFocus("the journey that met the wedge", FIRST_WAIT_MS)
        }
        assertNotNull("precondition: an outage is on record", DeviceFocusOutage.recorded())

        // The device comes back — but this screen's focus is taken by a popup,
        // which is a product-shaped state, not a device outage.
        restoreDeviceWideFocus()
        takeFocusWithAPopup()
        assertEquals(
            "precondition: the device grants focus again",
            DeviceFocusVerdict.FOCUSED_ELSEWHERE,
            awaitDeviceFocus(DeviceFocusVerdict.FOCUSED_ELSEWHERE).verdict,
        )

        val failure = assertThrows(AssertionError::class.java) {
            compose.awaitWindowFocus("the journey after the wedge ended", FIRST_WAIT_MS)
        }
        assertEquals(
            "a recovered device owes this caller a real verdict, not a skip",
            AssertionError::class.java,
            failure.javaClass,
        )
        assertTrue(
            failure.message.orEmpty(),
            failure.message.orEmpty().contains("the focus-settle signal stayed false"),
        )
        assertNull("the stale record is dropped once the device recovers", DeviceFocusOutage.recorded())
    }

    // ------------------------------------------------------------- fixtures

    /**
     * Leaves the device with no focused window at all — the incident's state.
     *
     * `FLAG_NOT_FOCUSABLE` on the only visible window is the one way to
     * manufacture it from inside an app: the window manager skips a
     * non-focusable window when it picks the focus, and everything behind an
     * opaque full-screen activity is not visible to be picked instead, so
     * `mCurrentFocus` goes null exactly as it did while the launcher was
     * ANR'd. The hosted runner's own wedge cannot be summoned on demand, and
     * D33 forbids skipping the assertion instead of injecting the state.
     */
    private fun withholdDeviceWideFocus() {
        compose.runOnUiThread {
            compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
        compose.awaitIdle("withholding device-wide window focus")
    }

    private fun restoreDeviceWideFocus() {
        runCatching {
            compose.runOnUiThread {
                compose.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            }
        }
    }

    /** Takes window focus away from the activity without taking it off the device. */
    private fun takeFocusWithAPopup() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context: Context = compose.activity
            popup = PopupWindow(View(context), 200, 200).apply {
                isFocusable = true
                showAtLocation(compose.activity.window.decorView, 0, 0, 0)
            }
        }
        compose.awaitIdle("taking window focus with a popup")
    }

    /**
     * Polls the real probe until it reports [want], or gives up and returns
     * whatever it last saw so the assertion can name it.
     */
    private fun awaitDeviceFocus(want: DeviceFocusVerdict): DeviceFocusState {
        val deadline = SystemClock.elapsedRealtime() + FIXTURE_SETTLE_MS
        var state = readDeviceFocusState()
        while (state.verdict != want && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(SETTLE_POLL_MS)
            state = readDeviceFocusState()
        }
        return state
    }

    private companion object {
        /** Short: the fixture holds the failure still, so there is nothing to wait out. */
        const val FIRST_WAIT_MS = 3_000L

        /** A journey-sized budget, so "it aborted fast" is a claim with teeth. */
        const val LATER_WAIT_MS = 20_000L

        /** How long the window manager gets to publish the fixture's focus change. */
        const val FIXTURE_SETTLE_MS = 10_000L
    }
}
