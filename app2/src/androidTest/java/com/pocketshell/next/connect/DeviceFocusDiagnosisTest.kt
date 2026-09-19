package com.pocketshell.next.connect

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
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
        DeviceFocusOutage.clear()
        preexistingOutage?.let { DeviceFocusOutage.record(it) }
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
        val state = parseDeviceFocusState("Error: unknown service window\n")

        assertEquals(DeviceFocusVerdict.UNKNOWN, state.verdict)
        assertTrue(state.probeFailure, state.probeFailure!!.contains("no mCurrentFocus line"))
        assertTrue(state.describe(), state.describe().contains("could not be read"))
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
            state = parseDeviceFocusState("  mCurrentFocus=null"),
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
            state = parseDeviceFocusState("  mCurrentFocus=null"),
            anrLines = emptyList(),
        )

        assertTrue(message, message.contains("no `ANR in …` line in logcat"))
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

    /** The logcat route works too — the evidence half of the same probe. */
    @Test
    fun theRealLogcatProbeIsReadable() {
        val dump = shellCommand("logcat -d -b main -t 20")

        assertTrue("logcat -d must be readable from an instrumented test", dump.isNotEmpty())
        // Does not throw, whatever the device log happens to hold right now.
        readSystemAnrLines()
    }

    private companion object {
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
