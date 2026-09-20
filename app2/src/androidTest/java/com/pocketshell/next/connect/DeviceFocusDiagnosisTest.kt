package com.pocketshell.next.connect

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The classifier half of issue #2830, pinned against run 35435668085's own
 * bytes.
 *
 * [awaitWindowFocus]'s environment-vs-product call is only as good as the two
 * readings it stands on: what `dumpsys window` says about focus, and what the
 * device log says about an ANR. Both are pure text once read, so they are
 * pinned here with the verbatim output shapes the incident produced — plus
 * two LIVE probes, because a classifier that is perfect over fixtures proves
 * nothing if the shell read behind it never works on a real device.
 *
 * Wiring is automatic: `app2.yml`'s `app2-journey` job runs
 * `:app2:connectedDebugAndroidTest` ONCE, unfiltered, so every class under
 * `app2/src/androidTest/` runs (issue #2474).
 */
@RunWith(AndroidJUnit4::class)
class DeviceFocusDiagnosisTest {

    private var preexistingOutage: String? = null

    /**
     * Nothing here should record an outage, but a failure mid-way could leave
     * one behind. The record is restored rather than merely cleared: on a
     * genuinely wedged device the outage this class did NOT create is the one
     * report the rest of the run must keep skipping against.
     */
    @Before
    fun captureOutage() {
        preexistingOutage = DeviceFocusOutage.recorded()
    }

    @After
    fun restoreOutage() {
        DeviceFocusOutage.restore(preexistingOutage)
    }

    // ---------------------------------------------------------------- parsing

    /**
     * The incident's own state: the window manager granting focus to nobody.
     *
     * `Reason: Input dispatching timed out (Application does not have a
     * focused window)` is the launcher ANR's own words for it.
     */
    @Test
    fun aDumpWhoseCurrentFocusIsNullIsADeviceWideOutage() {
        val state = parseDeviceFocusState(
            """
            WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)
              Display: mDisplayId=0 rootDwpc=null
                mCurrentFocus=null
                mFocusedApp=null
            """.trimIndent(),
            APP_UNDER_TEST,
        )

        assertEquals(DeviceFocusVerdict.NO_FOCUSED_WINDOW, state.verdict)
        assertEquals(emptyList<String>(), state.focusedWindows)
        assertNull(state.probeFailure)
        assertTrue(state.describe(), state.describe().contains("mCurrentFocus=null"))
    }

    /**
     * A focused window — even the LAUNCHER's — is not an outage.
     *
     * This is the arm that keeps the fix honest. `J06BackgroundGraceReturnJourney`
     * backgrounds the app on purpose, so "the launcher holds focus" is exactly
     * the state its own product bug would leave behind; calling that an
     * environment outage would launder a defect into a retry.
     */
    @Test
    fun aDumpWhoseCurrentFocusIsTheLauncherIsNotAnOutage() {
        val state = parseDeviceFocusState(
            "    mCurrentFocus=Window{ee7f61f u0 com.google.android.apps.nexuslauncher/" +
                "com.google.android.apps.nexuslauncher.NexusLauncherActivity}",
            APP_UNDER_TEST,
        )

        assertEquals(DeviceFocusVerdict.FOCUSED_ELSEWHERE, state.verdict)
        assertEquals(
            listOf(
                "Window{ee7f61f u0 com.google.android.apps.nexuslauncher/" +
                    "com.google.android.apps.nexuslauncher.NexusLauncherActivity}",
            ),
            state.focusedWindows,
        )
        assertTrue(state.describe(), state.describe().contains("nexuslauncher"))
    }

    /** One focused display among several is still focus: not an outage. */
    @Test
    fun aMultiDisplayDumpWithOneFocusedWindowIsNotAnOutage() {
        val state = parseDeviceFocusState(
            """
                mCurrentFocus=null
                mCurrentFocus=Window{a1b2c3 u0 com.pocketshell.app/com.pocketshell.next.MainActivity}
            """.trimIndent(),
            APP_UNDER_TEST,
        )

        assertEquals(DeviceFocusVerdict.FOCUSED_ELSEWHERE, state.verdict)
        assertEquals(1, state.focusedWindows.size)
    }

    /**
     * A dump with no focus line at all is a PROBE FAILURE, not "nobody has
     * focus".
     *
     * The two are indistinguishable by emptiness alone and only one of them
     * may fail a test as infra, so the parser keeps them apart and the oracle
     * falls back to the product-shaped assertion. An unprovable infra claim
     * must never mask a product failure.
     */
    @Test
    fun aDumpWithoutAFocusLineIsAProbeFailureNotAnOutage() {
        val state = parseDeviceFocusState("Error: unknown service window\n", APP_UNDER_TEST)

        assertEquals(DeviceFocusVerdict.UNKNOWN, state.verdict)
        assertTrue(state.probeFailure, state.probeFailure!!.contains("no mCurrentFocus line"))
        assertTrue(state.describe(), state.describe().contains("could not be read"))
    }

    // -------------------------------------------- the ANR dialog (issue #2838)

    /**
     * Run 35462083590's own `mCurrentFocus`, verbatim — the shape that made all
     * three #2830 mechanisms underperform at once.
     *
     * The launcher ANR'd, the platform put its "isn't responding" dialog up,
     * and that dialog IS a focused window. The #2830 classifier asked only
     * "is `mCurrentFocus` null?", answered no, and returned
     * `FOCUSED_ELSEWHERE` — so no outage was recorded, no later waiter skipped,
     * and no [DEVICE_FOCUS_OUTAGE_MARKER] reached the artifacts for
     * `--report-primary-cause` to find. 19 failures over 12 classes, zero
     * outage skips.
     */
    @Test
    fun theIncidentsAnrDialogWindowIsADeviceWideOutage() {
        val state = parseDeviceFocusState(INCIDENT_ANR_DIALOG_DUMP, APP_UNDER_TEST)

        assertEquals(DeviceFocusVerdict.ANR_DIALOG_HOLDS_FOCUS, state.verdict)
        assertTrue("an ANR dialog over every screen is the environment", state.isOutage)
        assertEquals(listOf("com.google.android.apps.nexuslauncher"), state.foreignAnrDialogs)
        assertTrue(state.describe(), state.describe().contains("system ANR dialog"))
        assertTrue(state.describe(), state.describe().contains("com.google.android.apps.nexuslauncher"))
    }

    /**
     * The honesty arm, and the reason the verdict is not simply "an ANR dialog
     * has focus".
     *
     * If the dialog names the APP UNDER TEST, our own app went unresponsive.
     * That is a product defect of exactly the kind this lane exists to catch,
     * and relabelling it as an environment wedge would launder it into a
     * "rerun the lane". The process-name variant is here too: the platform puts
     * the PROCESS name in the title, so a `:remote` subprocess of ours must be
     * recognised as ours as well.
     */
    @Test
    fun anAnrDialogForTheAppUnderTestIsAProductFailureNotAnOutage() {
        val ours = parseDeviceFocusState(
            "  mCurrentFocus=Window{a491573 u0 Application Not Responding: $APP_UNDER_TEST}",
            APP_UNDER_TEST,
        )
        assertEquals(DeviceFocusVerdict.FOCUSED_ELSEWHERE, ours.verdict)
        assertFalse("our own app's ANR is a product failure", ours.isOutage)
        assertEquals(emptyList<String>(), ours.foreignAnrDialogs)

        val ourSubprocess = parseDeviceFocusState(
            "  mCurrentFocus=Window{a491573 u0 Application Not Responding: $APP_UNDER_TEST:remote}",
            APP_UNDER_TEST,
        )
        assertEquals(DeviceFocusVerdict.FOCUSED_ELSEWHERE, ourSubprocess.verdict)
        assertFalse("…and so is one of our own subprocesses", ourSubprocess.isOutage)
    }

    /**
     * An outage claim may not rest on a partial reading: one ordinary focused
     * window anywhere means focus is reachable on that display.
     */
    @Test
    fun anAnrDialogBesideAnOrdinaryFocusedWindowIsNotAnOutage() {
        val state = parseDeviceFocusState(
            """
                mCurrentFocus=Window{a491573 u0 Application Not Responding: com.google.android.apps.nexuslauncher}
                mCurrentFocus=Window{a1b2c3 u0 com.pocketshell.app/com.pocketshell.next.MainActivity}
            """.trimIndent(),
            APP_UNDER_TEST,
        )

        assertEquals(DeviceFocusVerdict.FOCUSED_ELSEWHERE, state.verdict)
        assertFalse(state.describe(), state.isOutage)
    }

    /**
     * With no app under test named, "the dialog is for somebody ELSE" is
     * unprovable — and an unprovable infra claim must never mask a product
     * failure. So the state falls back to product-shaped, the same way an
     * unreadable probe does.
     */
    @Test
    fun anAnrDialogCannotBeCalledForeignWithoutAnAppUnderTest() {
        val state = parseDeviceFocusState(INCIDENT_ANR_DIALOG_DUMP, "")

        assertEquals(DeviceFocusVerdict.FOCUSED_ELSEWHERE, state.verdict)
        assertFalse(state.describe(), state.isOutage)
    }

    /** The title reader itself: it names the process, and only for a dialog. */
    @Test
    fun theAnrDialogTitleReaderNamesTheProcessAndNothingElse() {
        assertEquals(
            "com.google.android.apps.nexuslauncher",
            anrDialogProcess(
                "Window{a491573 u0 Application Not Responding: com.google.android.apps.nexuslauncher}",
            ),
        )
        assertNull(
            anrDialogProcess(
                "Window{ee7f61f u0 com.google.android.apps.nexuslauncher/" +
                    "com.google.android.apps.nexuslauncher.NexusLauncherActivity}",
            ),
        )
        assertNull(anrDialogProcess("Window{a1b2c3 u0 PopupWindow:3f1a}"))
    }

    /**
     * [DeviceFocusState.isOutage] is TOTAL over the verdict enum.
     *
     * #2830 shipped two hand-written `verdict == NO_FOCUSED_WINDOW` tests in
     * `SettleOracles.kt`, and adding a second outage shape would have had to
     * find both — this run's whole defect is one classifier that grew a case
     * the callers never learned about. So the mapping is asserted here for
     * EVERY enum constant: a new verdict added without a decision reddens by
     * name instead of silently defaulting to "not an outage".
     */
    @Test
    fun everyVerdictHasAnExplicitOutageDecision() {
        val outages = DeviceFocusVerdict.values().filter { verdict ->
            stateWith(verdict).isOutage
        }.toSet()

        assertEquals(
            setOf(
                DeviceFocusVerdict.NO_FOCUSED_WINDOW,
                DeviceFocusVerdict.ANR_DIALOG_HOLDS_FOCUS,
            ),
            outages,
        )
    }

    /** A [DeviceFocusState] that really reports [verdict], built from a dump. */
    private fun stateWith(verdict: DeviceFocusVerdict): DeviceFocusState {
        val state = when (verdict) {
            DeviceFocusVerdict.NO_FOCUSED_WINDOW ->
                parseDeviceFocusState("  mCurrentFocus=null", APP_UNDER_TEST)
            DeviceFocusVerdict.ANR_DIALOG_HOLDS_FOCUS ->
                parseDeviceFocusState(INCIDENT_ANR_DIALOG_DUMP, APP_UNDER_TEST)
            DeviceFocusVerdict.FOCUSED_ELSEWHERE -> parseDeviceFocusState(
                "  mCurrentFocus=Window{a1b2c3 u0 com.pocketshell.app/.MainActivity}",
                APP_UNDER_TEST,
            )
            DeviceFocusVerdict.UNKNOWN -> parseDeviceFocusState("Error: unknown service window", APP_UNDER_TEST)
        }
        assertEquals("the fixture for $verdict must really report it", verdict, state.verdict)
        return state
    }

    // ------------------------------------------------------------ ANR evidence

    /**
     * The five logcat lines from run 35435668085, verbatim. What the on-call
     * had to open an artifact to find is what the failure message now carries.
     */
    @Test
    fun theIncidentsLogcatYieldsTheAnrBannerReasonAndCpuPressure() {
        val lines = anrEvidenceLines(INCIDENT_LOGCAT)

        assertTrue(
            lines.toString(),
            lines.any { it.contains("ANR in com.google.android.apps.nexuslauncher") },
        )
        assertTrue(
            lines.toString(),
            lines.any { it.contains("Reason: Input dispatching timed out") },
        )
        assertTrue(lines.toString(), lines.any { it.contains("Load: 10.52") })
        assertTrue(lines.toString(), lines.any { it.contains("avg10=77.71") })
        assertTrue(
            "the unrelated line must not be carried as ANR evidence: $lines",
            lines.none { it.contains("Slow operation") },
        )
    }

    /** A quiet log yields no evidence — so the assertion above is not vacuous. */
    @Test
    fun aLogWithoutAnAnrYieldsNoEvidence() {
        assertEquals(
            emptyList<String>(),
            anrEvidenceLines("09-19 11:20:01.001 E ActivityManager: Slow operation: 120ms\n"),
        )
    }

    // ------------------------------------------------------------- the message

    /** The failure text an on-call reads: marker first, cause, evidence, action. */
    @Test
    fun theOutageMessageLeadsWithTheMarkerAndCarriesTheAnrEvidence() {
        val message = deviceFocusOutageMessage(
            what = "the session screen",
            timeoutMs = 20_000,
            state = parseDeviceFocusState("  mCurrentFocus=null", APP_UNDER_TEST),
            anrLines = anrEvidenceLines(INCIDENT_LOGCAT),
        )

        assertTrue(message, message.startsWith(DEVICE_FOCUS_OUTAGE_MARKER))
        assertTrue(message, message.contains("the session screen"))
        assertTrue(message, message.contains("20000ms"))
        assertTrue(message, message.contains("mCurrentFocus=null"))
        assertTrue(message, message.contains("ANR in com.google.android.apps.nexuslauncher"))
        assertTrue(message, message.contains("rerun the app2-journey lane"))
    }

    /** With no ANR in the log the message says so rather than implying one. */
    @Test
    fun theOutageMessageSaysWhenTheLogHeldNoAnr() {
        val message = deviceFocusOutageMessage(
            what = "the host list",
            timeoutMs = 5_000,
            state = parseDeviceFocusState("  mCurrentFocus=null", APP_UNDER_TEST),
            anrLines = emptyList(),
        )

        assertTrue(message, message.contains("no `ANR in …` line in logcat"))
    }

    /**
     * The ANR-dialog message names the dialog, says why no `ANR in …` banner is
     * expected, and still ends on the action.
     *
     * The missing banner is the norm for this verdict, not a weakness in it:
     * run 35462083590's 16 MB `logcat.txt` carried ZERO `ANR in ` lines while
     * two ANR dialogs held focus for 25 minutes — the launcher ANR'd during the
     * install/boot churn, before the suite's own `adb logcat -c`.
     */
    @Test
    fun theOutageMessageNamesTheAnrDialogAndWhyTheBannerIsAbsent() {
        val message = deviceFocusOutageMessage(
            what = "the hosts list after launch",
            timeoutMs = 60_000,
            state = parseDeviceFocusState(INCIDENT_ANR_DIALOG_DUMP, APP_UNDER_TEST),
            anrLines = emptyList(),
        )

        assertTrue(message, message.startsWith(DEVICE_FOCUS_OUTAGE_MARKER))
        assertTrue(message, message.contains("system ANR dialog"))
        assertTrue(message, message.contains("com.google.android.apps.nexuslauncher"))
        assertTrue(message, message.contains("no instrumented test"))
        assertTrue(
            "a missing banner must be explained, not left to read as weak evidence: $message",
            message.contains("outlives the banner"),
        )
        assertTrue(message, message.contains("rerun the app2-journey lane"))
    }

    // -------------------------------------------------------------- live probes

    /**
     * The shell route works on a real device.
     *
     * Without this, every fixture above would still pass against a
     * [readDeviceFocusState] that throws on every call and silently degrades
     * to `UNKNOWN` — i.e. against a fix that never fires. Deliberately does
     * NOT assert WHICH state the device is in: that would add a second failure
     * to a run whose whole point is to report the wedge once. The live
     * focused-window reading is asserted by
     * [DeviceFocusOutageReproTest.aWindowFocusedElsewhereIsAProductFailureNotAnOutage],
     * which owns the foreground while it looks.
     */
    @Test
    fun theRealWindowProbeReadsALiveFocusState() {
        val dump = shellCommand("dumpsys window displays")
        assertTrue(
            "dumpsys window must be readable from an instrumented test (got ${dump.length} bytes)",
            dump.contains("mCurrentFocus"),
        )

        assertNull(
            "the live dump parses without a probe failure",
            readDeviceFocusState().probeFailure,
        )
    }

    /**
     * The live probe really carries the app under test, which is what makes the
     * foreign-vs-ours ANR-dialog call possible at all.
     *
     * Without this, [readDeviceFocusState] could hand over `""` — the
     * documented "cannot prove it is foreign" fallback — and every ANR-dialog
     * wedge would quietly classify as product-shaped again, i.e. exactly the
     * #2838 defect with a passing fixture suite above it.
     */
    @Test
    fun theLiveProbeCarriesTheAppUnderTest() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext.packageName

        assertTrue("the instrumentation must know its target package", target.isNotEmpty())
        assertEquals(target, appUnderTestPackage())
        assertEquals(
            "readDeviceFocusState must hand the app under test to the classifier",
            target,
            readDeviceFocusState().appUnderTest,
        )
    }

    /** The logcat route works too — the evidence half of the same probe. */
    @Test
    fun theRealLogcatProbeIsReadable() {
        val dump = shellCommand("logcat -d -b main -t 20")

        assertTrue("logcat -d must be readable from an instrumented test", dump.isNotEmpty())
        // Does not throw, whatever the device log happens to hold right now.
        readSystemAnrLines()
    }

    private companion object {
        /** Stands in for the instrumentation's target package in the pure-text fixtures. */
        const val APP_UNDER_TEST = "com.pocketshell.app"

        /**
         * `dumpsys window displays` as run 35462083590 read it: the launcher's
         * ANR dialog holding the device's only focus (issue #2838).
         */
        const val INCIDENT_ANR_DIALOG_DUMP =
            "    mCurrentFocus=Window{a491573 u0 Application Not Responding: " +
                "com.google.android.apps.nexuslauncher}"

        /**
         * `artifacts/app2-journey/logcat.txt` from run 35435668085, the ANR
         * block plus one unrelated ActivityManager line around it.
         */
        val INCIDENT_LOGCAT = """
            09-19 11:24:11.100  1234  1250 E ActivityManager: Slow operation: 120ms
            09-19 11:24:12.205  1234  1250 E ActivityManager: ANR in com.google.android.apps.nexuslauncher (com.google.android.apps.nexuslauncher/.NexusLauncherActivity)
            09-19 11:24:12.205  1234  1250 E ActivityManager: Reason: Input dispatching timed out (Application does not have a focused window).
            09-19 11:24:12.205  1234  1250 E ActivityManager: Load: 10.52 / 2.4 / 0.79
            09-19 11:24:12.205  1234  1250 E ActivityManager: ----- Output from /proc/pressure/cpu -----
            09-19 11:24:12.205  1234  1250 E ActivityManager: some avg10=77.71 avg60=30.47 avg300=7.60
        """.trimIndent()
    }
}
