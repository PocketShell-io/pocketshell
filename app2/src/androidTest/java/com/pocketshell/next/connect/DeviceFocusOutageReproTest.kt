package com.pocketshell.next.connect

import android.app.Dialog
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
import org.junit.Assume.assumeTrue
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
 *  - [anAnrDialogHoldingTheDevicesFocusIsReportedOnceAsAnEnvironmentOutage] —
 *    the SECOND wedge shape, and the one a real ANR actually produces
 *    (issue #2838). Run 35462083590's launcher ANR'd and the platform put its
 *    "isn't responding" dialog up; that dialog holds focus, so `mCurrentFocus`
 *    was NOT null and #2830's `verdict == NO_FOCUSED_WINDOW` test said
 *    "product". 19 failures over 12 classes, zero outage skips, and no marker
 *    in any artifact for `--report-primary-cause` to find. The fixture here is
 *    a real [Dialog] whose WindowManager title is the platform's own
 *    (`Application Not Responding: <process>` — `Dialog.setTitle` writes the
 *    LayoutParams title, which is the string `dumpsys window` prints), so the
 *    whole chain runs on the real path: real window manager, real `dumpsys`,
 *    real classifier, real record, real skip.
 *  - [aWindowFocusedElsewhereIsAProductFailureNotAnOutage] — the arm that
 *    keeps the fix honest. Focus is taken by a focusable [PopupWindow], so the
 *    device HAS a focused window and this screen simply does not have it. That
 *    must still fail as #2781's product-shaped assertion: a fix that relabels
 *    every focus timeout as infra would launder
 *    `J06BackgroundGraceReturnJourney`'s own return-from-background bug into a
 *    retry.
 *  - [anAnrDialogForTheAppUnderTestIsAProductFailureNotAnOutage] — the same
 *    honesty test for the new verdict: an ANR dialog naming OUR package means
 *    OUR app stopped responding, which is a product defect the lane exists to
 *    catch and must not be laundered into "rerun the lane".
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
 * [restoreTheDevice], which also restores the process-wide outage record to
 * whatever it inherited — the unfiltered suite shares one process and one
 * device (issue #2474), so a fixture left up here would be the next class's
 * mystery failure.
 *
 * ## Precondition
 *
 * This class manufactures the wedge itself, so it needs a HEALTHY device to
 * start from: no outage on record, and a window manager that is granting focus
 * to somebody. [captureOutage] asserts neither — it SKIPS — because on an
 * already-wedged device those presumptions would turn one report into three
 * extra failures, which is the shape #2830 exists to prevent. The focus half
 * is polled rather than sampled, so a device that is merely slow to publish
 * the focus is waited for instead of being mistaken for a wedged one.
 */
@RunWith(AndroidJUnit4::class)
class DeviceFocusOutageReproTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var popup: PopupWindow? = null

    private var anrDialog: Dialog? = null

    private var preexistingOutage: String? = null

    /**
     * Records what this class inherited, and SKIPS it if the device is already
     * in the state the class exists to manufacture (issue #2833, follow-up 5).
     *
     * Every test here starts from "no outage is on record and the device can
     * grant focus" — `assertNull("no outage is on record before the first
     * waiter", …)` and both `assertThrows` arms presume it. On a genuinely
     * wedged hosted device an earlier journey has already recorded the outage,
     * and those presumptions become two or three EXTRA failures in precisely
     * the scenario #2830 exists to report once. A skip keeps that property
     * whole: the one report stands, and this class says why it stood aside
     * rather than adding noise to it.
     *
     * The device half goes through [awaitDeviceFocusGrantedToSomebody], not a
     * bare probe: a skip is only worth having if it cannot fire on a device
     * that was about to be fine.
     *
     * ## Why it did not fire on the real ANR (issue #2838)
     *
     * On run 35462083590 this class produced two of the run's 19 failures —
     * `the fixture must leave the device with no focused window, got: … Window{
     * a491573 u0 Application Not Responding: com.google.android.apps.nexuslauncher}`
     * and `expected:<DeviceWindowFocusOutageException> but was:<AssertionError>`
     * — i.e. the oracle misfired on precisely the wedge it exists to report.
     * Both halves of the precondition were satisfied by a genuinely wedged
     * device: nothing had recorded an outage (the classifier never called it
     * one), and the ANR dialog was "SOMEBODY" holding focus. The test is now
     * [DeviceFocusState.isOutage], which covers both wedge shapes, so this
     * class stands aside on a real ANR instead of adding noise to it.
     */
    @Before
    fun captureOutage() {
        preexistingOutage = DeviceFocusOutage.recorded()
        assumeTrue(
            "$DEVICE_FOCUS_OUTAGE_MARKER was already reported before this class ran, so the " +
                "device cannot be assumed healthy enough to manufacture the outage under test. " +
                "The report this skip stands on:\n$preexistingOutage",
            preexistingOutage == null,
        )
        val entryState = awaitDeviceFocusGrantedToSomebody()
        assumeTrue(
            "this class must start on a device that is not already wedged — it manufactures " +
                "the wedge itself — but the device already reads as: " + entryState.describe(),
            !entryState.isOutage,
        )
    }

    /**
     * Undoes this class's fixtures and its effect on the process-wide record.
     *
     * Clears `FLAG_NOT_FOCUSABLE`, dismisses the popup, then drops the record
     * this class created and puts back one it merely inherited. It does NOT
     * wait for focus to return: on a genuinely wedged device there would be
     * nothing to wait for, and the next class's own `awaitWindowFocus` is the
     * thing that re-earns the verdict from the device anyway. On a wedged
     * device the outage reported BEFORE this class ran is the single report
     * every later class must keep skipping against, and clearing it here would
     * let the suite report the same cause twice.
     */
    @After
    fun restoreTheDevice() {
        restoreDeviceWideFocus()
        runCatching {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { popup?.dismiss() }
        }
        popup = null
        runCatching {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { anrDialog?.dismiss() }
        }
        anrDialog = null
        DeviceFocusOutage.restore(preexistingOutage)
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
        // The budget has to sit strictly ABOVE the ceiling or the timing
        // assertion below says nothing — a skip that spent the caller's whole
        // budget would still land under the ceiling and pass. SKIP_BUDGET_MS
        // is derived from SKIP_CEILING_MS so that holds by construction; this
        // states the relation at the point that depends on it, so re-writing
        // either constant as a hand-picked literal reddens here instead of
        // quietly making this arm vacuous.
        assertTrue(
            "SKIP_BUDGET_MS (${SKIP_BUDGET_MS}ms) must stay above SKIP_CEILING_MS " +
                "(${SKIP_CEILING_MS}ms), or 'it did not spend the budget' is unfalsifiable",
            SKIP_CEILING_MS < SKIP_BUDGET_MS,
        )
        val startedAt = SystemClock.elapsedRealtime()
        val skip = assertThrows(AssumptionViolatedException::class.java) {
            compose.awaitWindowFocus("the next window-sensitive journey", SKIP_BUDGET_MS)
        }
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        val skipMessage = skip.message.orEmpty()
        assertTrue(skipMessage, skipMessage.contains(DEVICE_FOCUS_OUTAGE_MARKER))
        assertTrue(
            "the skip must quote the one report it stands on: $skipMessage",
            skipMessage.contains("rerun the app2-journey lane"),
        )
        assertTrue(
            "a known outage must abort within the skip path's OWN bounds " +
                "(${SKIP_CEILING_MS}ms = recheck + one bounded idle wait + the re-probe), not " +
                "spend the caller's ${SKIP_BUDGET_MS}ms budget — took ${elapsed}ms",
            elapsed < SKIP_CEILING_MS,
        )

        // 3. And an outage that ends does not poison the rest of the run.
        restoreDeviceWideFocus()
        compose.awaitWindowFocus("the recovered screen", LATER_WAIT_MS)
        assertNull("a recovered device drops the record", DeviceFocusOutage.recorded())
    }

    /**
     * Issue #2838's incident, end to end: an ANR dialog holding the device's
     * focus is the environment, reported once, skipped by everybody after it.
     *
     * The same three-act shape as the `mCurrentFocus=null` arm above, against
     * the wedge a REAL launcher ANR produces. Against the pre-#2838 classifier
     * act 1 raises a bare `AssertionError` saying "This is NOT a device-wide
     * focus outage" and act 2 spends its whole budget and raises another — the
     * 19-failures-over-12-classes shape of run 35462083590.
     */
    @Test
    fun anAnrDialogHoldingTheDevicesFocusIsReportedOnceAsAnEnvironmentOutage() {
        standUpAnrDialogFor(WEDGED_FOREIGN_PROCESS)

        val wedged = awaitDeviceFocus(DeviceFocusVerdict.ANR_DIALOG_HOLDS_FOCUS)
        assertEquals(
            "the fixture must leave a foreign ANR dialog holding the device's focus, got: " +
                wedged.describe(),
            DeviceFocusVerdict.ANR_DIALOG_HOLDS_FOCUS,
            wedged.verdict,
        )
        assertEquals(listOf(WEDGED_FOREIGN_PROCESS), wedged.foreignAnrDialogs)
        assertFalse(
            "and this activity's window must really be unfocused",
            compose.runOnUiThread { compose.activity.window.decorView.hasWindowFocus() },
        )
        assertNull("no outage is on record before the first waiter", DeviceFocusOutage.recorded())

        // 1. The FIRST waiter calls it what it is.
        val outage = assertThrows(DeviceWindowFocusOutageException::class.java) {
            compose.awaitWindowFocus("the first window-sensitive journey", FIRST_WAIT_MS)
        }
        val reported = outage.message.orEmpty()
        assertTrue(reported, reported.startsWith(DEVICE_FOCUS_OUTAGE_MARKER))
        assertTrue(reported, reported.contains("system ANR dialog"))
        assertTrue(reported, reported.contains(WEDGED_FOREIGN_PROCESS))
        assertFalse(
            "the pre-#2838 wording is the defect: $reported",
            reported.contains("This is NOT a device-wide focus outage"),
        )
        assertNotNull("the outage is recorded for the rest of the process", DeviceFocusOutage.recorded())

        // 2. The NEXT waiter — the next class of the unfiltered suite — skips,
        //    and does it inside the skip path's own bounds rather than burning
        //    a per-class budget. Twelve classes did burn one on 35462083590.
        val startedAt = SystemClock.elapsedRealtime()
        val skip = assertThrows(AssumptionViolatedException::class.java) {
            compose.awaitWindowFocus("the next window-sensitive journey", SKIP_BUDGET_MS)
        }
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        assertTrue(skip.message.orEmpty(), skip.message.orEmpty().contains(DEVICE_FOCUS_OUTAGE_MARKER))
        assertTrue(
            "a known outage must abort within ${SKIP_CEILING_MS}ms, not spend the caller's " +
                "${SKIP_BUDGET_MS}ms budget — took ${elapsed}ms",
            elapsed < SKIP_CEILING_MS,
        )

        // 3. Dismiss the dialog and the run carries on, as a rerun would.
        dismissAnrDialog()
        compose.awaitWindowFocus("the recovered screen", LATER_WAIT_MS)
        assertNull("a recovered device drops the record", DeviceFocusOutage.recorded())
    }

    /**
     * The honesty arm for the new verdict: an ANR dialog naming the APP UNDER
     * TEST is OUR app failing to respond — a product defect, not an
     * environment wedge, and never a licence to rerun the lane.
     */
    @Test
    fun anAnrDialogForTheAppUnderTestIsAProductFailureNotAnOutage() {
        val ourProcess = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        standUpAnrDialogFor(ourProcess)

        // Waited for by TITLE, not by verdict: FOCUSED_ELSEWHERE is also what
        // the activity's own window reads as, so a verdict-shaped wait would
        // be satisfied before the fixture was even up and the arm would prove
        // nothing.
        val ourDialog = ANR_DIALOG_WINDOW_TITLE_PREFIX + ourProcess
        val focused = awaitDeviceFocusOn(ourDialog)
        assertTrue(
            "the fixture must leave OUR ANR dialog holding focus, got: ${focused.describe()}",
            focused.focusedWindows.any { it.contains(ourDialog) },
        )
        assertEquals(
            "our own ANR dialog must read as an ordinary focused window, got: " +
                focused.describe(),
            DeviceFocusVerdict.FOCUSED_ELSEWHERE,
            focused.verdict,
        )
        assertFalse("…and must not be an outage", focused.isOutage)
        assertFalse(
            "and this activity's window must really be unfocused",
            compose.runOnUiThread { compose.activity.window.decorView.hasWindowFocus() },
        )

        val failure = assertThrows(AssertionError::class.java) {
            compose.awaitWindowFocus("a screen behind our own ANR dialog", FIRST_WAIT_MS)
        }
        assertEquals(
            "our own app going unresponsive is a product failure",
            AssertionError::class.java,
            failure.javaClass,
        )
        assertTrue(
            failure.message.orEmpty(),
            failure.message.orEmpty().contains("This is NOT a device-wide focus outage"),
        )
        assertNull("no outage may be recorded for a product failure", DeviceFocusOutage.recorded())
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

    /**
     * Stands up a window shaped EXACTLY like the platform's ANR dialog, for
     * [process].
     *
     * `AppNotRespondingDialog` is a [Dialog] whose WindowManager title is
     * `"Application Not Responding: " + processName`, and that title is the
     * string `dumpsys window` prints inside `Window{…}` — which is the only
     * thing the classifier reads. `Dialog.setTitle` writes BOTH the decor title
     * and `getAttributes().setTitle(…)`, so a plain dialog reproduces the
     * incident's `mCurrentFocus` byte for byte:
     *
     * ```
     * mCurrentFocus=Window{a491573 u0 Application Not Responding: com.google.android.apps.nexuslauncher}
     * ```
     *
     * The real thing cannot be summoned on demand — it needs a starved runner
     * and a system app that stops answering input — and D33 forbids skipping
     * the assertion instead of injecting the state. This injects the state the
     * classifier is defined over, through the real window manager, and every
     * hop after it is the production path.
     */
    private fun standUpAnrDialogFor(process: String) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            anrDialog = Dialog(compose.activity).apply {
                setTitle(ANR_DIALOG_WINDOW_TITLE_PREFIX + process)
                // A non-zero content size, so the window really is laid out and
                // really is a focus candidate — a zero-size window would make
                // this fixture prove nothing.
                setContentView(
                    View(compose.activity).apply {
                        minimumWidth = FIXTURE_WINDOW_PX
                        minimumHeight = FIXTURE_WINDOW_PX
                    },
                )
                setCancelable(false)
                show()
            }
        }
        compose.awaitIdle("standing up an ANR-shaped dialog for $process")
    }

    private fun dismissAnrDialog() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { anrDialog?.dismiss() }
        anrDialog = null
        compose.awaitIdle("dismissing the ANR-shaped dialog")
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
     * Polls until the device grants focus to SOMEBODY, or gives up and returns
     * the last reading so [captureOutage] can name it.
     *
     * A single read would be a race in the one direction that hurts: the rule
     * has only just launched this activity, and a device that is merely slow to
     * publish the focus reads exactly like a wedged one. Skipping on THAT would
     * turn the #2830 reproduction into a class that silently stops testing —
     * the failure mode the precondition (issue #2833, follow-up 5) must not
     * introduce while preventing another. So a `NO_FOCUSED_WINDOW` reading is
     * given [FIXTURE_SETTLE_MS], the same window this class allows its own
     * fixtures, to turn into a focus before it is believed. A device that is
     * genuinely wedged still reads wedged at the end of it, and still skips.
     */
    private fun awaitDeviceFocusGrantedToSomebody(): DeviceFocusState {
        val deadline = SystemClock.elapsedRealtime() + FIXTURE_SETTLE_MS
        var state = readDeviceFocusState()
        while (state.isOutage && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(SETTLE_POLL_MS)
            state = readDeviceFocusState()
        }
        return state
    }

    /**
     * Polls the real probe until the window whose description contains [needle]
     * holds the device's focus, or gives up and returns the last reading.
     *
     * The verdict-shaped [awaitDeviceFocus] cannot express "OUR ANR dialog is
     * up": that state's verdict is [DeviceFocusVerdict.FOCUSED_ELSEWHERE],
     * which the activity's own focused window already satisfies.
     */
    private fun awaitDeviceFocusOn(needle: String): DeviceFocusState {
        val deadline = SystemClock.elapsedRealtime() + FIXTURE_SETTLE_MS
        var state = readDeviceFocusState()
        while (state.focusedWindows.none { it.contains(needle) } &&
            SystemClock.elapsedRealtime() < deadline
        ) {
            SystemClock.sleep(SETTLE_POLL_MS)
            state = readDeviceFocusState()
        }
        return state
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

        /** A journey-sized budget for the recovered screen. */
        const val LATER_WAIT_MS = 20_000L

        /**
         * The worst case of the skip path, DERIVED from the constants that
         * bound each of its steps rather than measured on one box
         * (issue #2833, follow-up 4). Every term is a hard wall-clock bound, so
         * no amount of lane contention can push a healthy skip past it:
         *
         *  - [FOCUS_OUTAGE_RECHECK_MS] — the short look `awaitWindowFocus`
         *    gives a device that already has an outage on record, clamped
         *    below the caller's budget.
         *  - [ComposeIdle.BUDGET_MS] + [SETTLE_POLL_MS] — one poll iteration
         *    may start just under that deadline and still run a full bounded
         *    idle wait plus its sleep before the loop re-reads the clock.
         *  - [DEVICE_FOCUS_PROBE_BUDGET_MS] — the re-probe that decides skip
         *    vs. fail: at most two `dumpsys` calls, each individually bounded.
         *
         * The previous bound was `LATER_WAIT_MS / 2` — 10000ms, a stopwatch
         * assertion on the one lane whose subject is contention: the #2830
         * review measured the real skip cost at 6765ms against it, ~1.5x,
         * while the path's own ceiling was already well above both. Widening
         * that number by hand would have been the wrong fix; this one moves
         * with the four constants it is made of, and a retune of any of them
         * carries it along.
         */
        const val SKIP_CEILING_MS =
            FOCUS_OUTAGE_RECHECK_MS + ComposeIdle.BUDGET_MS + SETTLE_POLL_MS +
                DEVICE_FOCUS_PROBE_BUDGET_MS

        /**
         * The budget handed to the waiter that must be SKIPPED: twice
         * [SKIP_CEILING_MS], so "it aborted instead of spending the budget" is
         * a claim with teeth.
         *
         * The assertion under test is `elapsed < SKIP_CEILING_MS`, which says
         * nothing unless spending the WHOLE budget would land above that
         * ceiling — with a budget under the ceiling, the regression this arm
         * exists to catch (`minOf(timeoutMs, FOCUS_OUTAGE_RECHECK_MS)` losing
         * its clamp, so the skip polls the caller's full budget) would still
         * pass. Derived rather than written as a literal for the same reason
         * the ceiling is: retuning `ComposeIdle.BUDGET_MS` or the probe
         * timeout moves the ceiling, and a literal here could silently sink
         * below it and make this arm vacuous.
         *
         * Costs nothing on a green run — the skip path returns in seconds no
         * matter how large this is. Only a regression pays it.
         */
        const val SKIP_BUDGET_MS = 2 * SKIP_CEILING_MS

        /** How long the window manager gets to publish the fixture's focus change. */
        const val FIXTURE_SETTLE_MS = 10_000L

        /**
         * The process run 35462083590's ANR dialog named — the emulator's own
         * launcher, i.e. a package that is definitionally not the app under
         * test on any device this lane runs on.
         */
        const val WEDGED_FOREIGN_PROCESS = "com.google.android.apps.nexuslauncher"

        /** Big enough that the ANR-shaped fixture window is really laid out. */
        const val FIXTURE_WINDOW_PX = 200
    }
}
