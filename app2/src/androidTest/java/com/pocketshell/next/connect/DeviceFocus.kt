package com.pocketshell.next.connect

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.AssumptionViolatedException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Tells a DEVICE-WIDE window-focus outage apart from one screen failing to
 * take focus (issue #2830).
 *
 * ## The failure this exists for
 *
 * `app2-journey` run 35435668085 went red twice on the SAME tree (`9424a3900`)
 * with a growing failure count — 5 failures over 2 classes on attempt 1, 17
 * over 8 classes on attempt 2 — and 14 of the 17 carried one byte-identical
 * message: [awaitWindowFocus]'s `the window never took focus … the
 * focus-settle signal stayed false`. The device log said why:
 *
 * ```
 * E ActivityManager: ANR in com.google.android.apps.nexuslauncher (…/.NexusLauncherActivity)
 * E ActivityManager: Reason: Input dispatching timed out (Application does not have a focused window).
 * E ActivityManager: Load: 10.52 / 2.4 / 0.79
 * E ActivityManager: ----- Output from /proc/pressure/cpu -----
 * E ActivityManager: some avg10=77.71 avg60=30.47 avg300=7.60
 * ```
 *
 * The hosted runner was CPU-starved, the emulator's LAUNCHER ANR'd on input
 * dispatch, and the window manager was left with no focused window at all. The
 * oracle observed that correctly — the window really never took focus — but it
 * said so in the vocabulary of a product defect, once per window-sensitive
 * journey, so the lane read as "8 journeys broke" instead of "the device is
 * wedged, rerun".
 *
 * ## The distinction, stated as an observable
 *
 * `activity.window.decorView.hasWindowFocus()` is false in BOTH cases, so it
 * cannot be the discriminator. The window manager's own focus is:
 *
 *  - `mCurrentFocus=null` on every display — **nobody** on the device holds
 *    focus. Nothing this screen does can win focus from a window manager that
 *    is handing it to no one; this is the environment ([DeviceFocusVerdict.NO_FOCUSED_WINDOW]).
 *  - `mCurrentFocus=Window{… Application Not Responding: <pkg>}` — the system
 *    is holding an ANR dialog over every screen on the device, for a package
 *    that is NOT the app under test. Focus is granted, but to a window no test
 *    can win it from and no test can dismiss: only a user (or a reboot) takes
 *    that dialog down. This is the environment too
 *    ([DeviceFocusVerdict.ANR_DIALOG_HOLDS_FOCUS]) — see the run-35462083590
 *    note below, which is the shape that taught us the distinction.
 *  - `mCurrentFocus=Window{… <pkg>/<cls>}` — focus exists and went somewhere
 *    else. That is a real, product-shaped answer ("the launcher has focus, so
 *    the app never came forward" / "a popup holds it"), and it is deliberately
 *    NOT called an outage: `J06BackgroundGraceReturnJourney` backgrounds the
 *    app on purpose, so "the launcher holds focus" is exactly the state its
 *    own product bug would leave behind. Naming the window in the failure
 *    message gives the on-call the same answer without laundering a defect
 *    into an infra retry. An ANR dialog for the APP UNDER TEST stays in this
 *    bucket on purpose: our own app going unresponsive is a product defect,
 *    and calling it infra would launder it into a retry.
 *  - the probe itself failed — say so and fall back to the product-shaped
 *    message. An unprovable infra claim must never mask a product failure.
 *
 * ## Run 35462083590: the wedge that held focus (issue #2838)
 *
 * The first REAL launcher ANR after #2830 landed produced 19 failures over 12
 * classes and **zero** outage skips. The reason was one window:
 *
 * ```
 * mCurrentFocus=Window{a491573 u0 Application Not Responding: com.google.android.apps.nexuslauncher}
 * ```
 *
 * The launcher ANR'd, and the platform put up its "isn't responding" dialog —
 * which IS a focused window. `mCurrentFocus` was therefore not null, the
 * verdict came back `FOCUSED_ELSEWHERE`, and the whole #2830 chain never
 * started: no [DeviceWindowFocusOutageException], no [DeviceFocusOutage]
 * record, no skips, and no [DEVICE_FOCUS_OUTAGE_MARKER] anywhere in the
 * artifacts for `--report-primary-cause` to find (it printed "nothing to
 * report" on a run whose every failure named the ANR dialog). One
 * mis-classification, all three symptoms.
 *
 * Two facts from that run's own `artifacts/app2-journey/logcat.txt` shape the
 * fix:
 *
 *  - the dialog is DURABLE. Two of them (`a491573`, then `ec8e38c`) held focus
 *    across 25 minutes and 12 classes. Nothing in an unattended instrumentation
 *    run dismisses an ANR dialog.
 *  - the `ANR in …` banner is NOT. That 16 MB log carried zero `ANR in ` lines:
 *    the launcher ANR'd during the install/boot churn, BEFORE the suite's
 *    `adb logcat -c`. So the dialog — not the banner — is the evidence that
 *    survives, which is why the verdict is read off `dumpsys window` and why
 *    an empty [readSystemAnrLines] must not weaken the call.
 *
 * ## Why the shell, and why it works from an instrumented test
 *
 * `adb` is not on the device. [shellCommand] goes through
 * `UiAutomation.executeShellCommand`, whose [android.app.UiAutomationConnection]
 * lives in the `am instrument` (shell, uid 2000) process, so `dumpsys window`
 * and `logcat -d` run with the shell's permissions — the same route
 * `UiDevice.executeShellCommand` uses. There is no shell interpreter behind it
 * (`Runtime.exec`, not `sh -c`), so every command here is a single argv with
 * no pipes and the filtering happens in Kotlin.
 */

/**
 * The one grep-able signature of an environment focus outage.
 *
 * Shared by the failure message, the skip message of every subsequent waiter,
 * and `scripts/ci-app2-journey-suite.sh`'s job-summary scan — so "did the lane
 * die of a device wedge?" is one string to search for, in the test XML, in the
 * gradle log and in the job summary.
 */
const val DEVICE_FOCUS_OUTAGE_MARKER: String = "INFRA: device window-focus outage"

/**
 * Thrown instead of a bare `AssertionError` when the DEVICE, not the screen,
 * failed to grant window focus.
 *
 * A distinct type, so the failure reads as `…DeviceWindowFocusOutageException`
 * in the JUnit XML's `type=` attribute and in the report's failure list: a
 * reader (and a grep) can tell it from the product-shaped assertion without
 * parsing prose.
 */
class DeviceWindowFocusOutageException(message: String) : RuntimeException(message)

/** How the window manager's focus was resolved at a timeout. */
enum class DeviceFocusVerdict {
    /** No window on any display holds focus — the environment is wedged. */
    NO_FOCUSED_WINDOW,

    /**
     * Every focused window is a system ANR dialog for a package OTHER than the
     * app under test — the environment is wedged behind a dialog no test can
     * win focus from or dismiss (issue #2838).
     */
    ANR_DIALOG_HOLDS_FOCUS,

    /** A window holds focus (ours or another app's) — product-shaped. */
    FOCUSED_ELSEWHERE,

    /** The probe could not be read — product-shaped, conservatively. */
    UNKNOWN,
}

/**
 * The window title the platform gives an "isn't responding" dialog.
 *
 * `AppNotRespondingDialog` sets `WindowManager.LayoutParams.setTitle("Application
 * Not Responding: " + app.processName)` — a debug title, not a localized user
 * string, so it reads the same on every device and locale the lane runs on.
 * Matching on it is how a wedged device is told from a busy one: no app can put
 * this window up for another package, and no instrumented test can take it down.
 */
const val ANR_DIALOG_WINDOW_TITLE_PREFIX: String = "Application Not Responding: "

/**
 * The ANR'd process named by a `mCurrentFocus` window description, or null when
 * that window is not an ANR dialog.
 *
 * `Window{a491573 u0 Application Not Responding: com.google.android.apps.nexuslauncher}`
 * → `com.google.android.apps.nexuslauncher`. The title is the last field of the
 * description, so the process name runs to the closing brace.
 */
fun anrDialogProcess(focusedWindow: String): String? {
    val start = focusedWindow.indexOf(ANR_DIALOG_WINDOW_TITLE_PREFIX)
    if (start < 0) return null
    val process = focusedWindow.substring(start + ANR_DIALOG_WINDOW_TITLE_PREFIX.length)
        .substringBefore('}')
        .trim()
    return process.ifEmpty { null }
}

/**
 * What `dumpsys window` says about focus right now.
 *
 * [focusedWindows] is every non-null `mCurrentFocus` value found, one per
 * display. Empty with [probeFailure] null means the dump was readable and
 * nothing holds focus; [probeFailure] non-null means the dump could not be
 * read or carried no `mCurrentFocus` line at all, which is NOT the same claim.
 */
data class DeviceFocusState(
    val focusedWindows: List<String>,
    val probeFailure: String?,
    val appUnderTest: String,
) {
    /**
     * The ANR'd processes whose dialogs hold focus, excluding the app under
     * test's own.
     *
     * A dialog for OUR package is not listed: our app going unresponsive is a
     * product defect and must keep failing as one (issue #2838). An empty
     * [appUnderTest] means the caller could not name the app under test, and an
     * outage this code cannot prove is foreign is one it does not get to claim
     * — so the whole list is then empty and the verdict falls back to the
     * product-shaped answer, the same way an unreadable probe does.
     */
    val foreignAnrDialogs: List<String>
        get() {
            if (appUnderTest.isEmpty()) return emptyList()
            return focusedWindows.mapNotNull(::anrDialogProcess)
                .filter { it != appUnderTest && !it.startsWith(appUnderTest + ":") }
        }

    val verdict: DeviceFocusVerdict
        get() = when {
            probeFailure != null -> DeviceFocusVerdict.UNKNOWN
            focusedWindows.isEmpty() -> DeviceFocusVerdict.NO_FOCUSED_WINDOW
            // EVERY focused window must be a foreign ANR dialog. One ordinary
            // focused window anywhere means focus is reachable on that display,
            // which is a product-shaped answer — an outage claim may not rest
            // on a partial reading.
            foreignAnrDialogs.size == focusedWindows.size ->
                DeviceFocusVerdict.ANR_DIALOG_HOLDS_FOCUS
            else -> DeviceFocusVerdict.FOCUSED_ELSEWHERE
        }

    /**
     * True when the DEVICE, not this screen, is why focus never arrived — the
     * one question every caller actually asks.
     *
     * Named rather than compared against a verdict, because #2830 shipped two
     * `verdict == NO_FOCUSED_WINDOW` comparisons in `SettleOracles.kt` and
     * adding a second outage shape to the enum would otherwise have had to find
     * both (issue #2838).
     */
    val isOutage: Boolean
        get() = verdict == DeviceFocusVerdict.NO_FOCUSED_WINDOW ||
            verdict == DeviceFocusVerdict.ANR_DIALOG_HOLDS_FOCUS

    /** One line for a failure message. */
    fun describe(): String = when {
        probeFailure != null -> "device focus could not be read ($probeFailure)"
        focusedWindows.isEmpty() -> "no window on the device holds focus (mCurrentFocus=null)"
        verdict == DeviceFocusVerdict.ANR_DIALOG_HOLDS_FOCUS ->
            "the device's focused window is a system ANR dialog for " +
                "${foreignAnrDialogs.joinToString(", ")} — the platform is holding it over " +
                "every screen and nothing in an unattended run dismisses it " +
                "(${focusedWindows.joinToString(", ")})"
        else -> "the device's focused window is ${focusedWindows.joinToString(", ")}"
    }
}

/** The `mCurrentFocus=` reader, split out so the incident's own bytes can pin it. */
private val CURRENT_FOCUS = Regex("""mCurrentFocus\s*=\s*(\S.*?)\s*$""", RegexOption.MULTILINE)

/**
 * Parses `dumpsys window`'s focus lines.
 *
 * `mCurrentFocus=null` and `mCurrentFocus=Window{ee7f61f u0 pkg/cls}` are the
 * two shapes the platform emits, one per display. A dump with no
 * `mCurrentFocus` line at all is a probe failure, not "nothing has focus" —
 * the two would otherwise be indistinguishable, and only one of them is
 * grounds for calling an environment outage.
 */
fun parseDeviceFocusState(dump: String, appUnderTest: String): DeviceFocusState {
    val matches = CURRENT_FOCUS.findAll(dump).map { it.groupValues[1].trim() }.toList()
    if (matches.isEmpty()) {
        return DeviceFocusState(
            focusedWindows = emptyList(),
            probeFailure = "no mCurrentFocus line in the ${dump.length}-byte dumpsys window output",
            appUnderTest = appUnderTest,
        )
    }
    return DeviceFocusState(
        focusedWindows = matches.filter { it != "null" },
        probeFailure = null,
        appUnderTest = appUnderTest,
    )
}

/**
 * The package the instrumentation is testing, or `""` when even that cannot be
 * read.
 *
 * Load-bearing for the ANR-dialog verdict: it is what tells "the LAUNCHER is
 * wedged" (environment) from "OUR app is wedged" (product). Under the
 * per-worktree `applicationId` of `scripts/connected-test.sh` this is
 * `com.pocketshell.app.i<issue>`, which is also the process name the platform
 * puts in the dialog title, so the comparison holds on a sibling-agent
 * emulator as well as in CI.
 */
fun appUnderTestPackage(): String =
    runCatching {
        InstrumentationRegistry.getInstrumentation().targetContext.packageName
    }.getOrElse { "" }

/**
 * Reads the device's focus state off the real window manager.
 *
 * `dumpsys window displays` carries `mCurrentFocus`/`mFocusedApp` in ~22 KB
 * where the full `dumpsys window` costs ~55 KB; the full dump is the fallback
 * for a platform whose `displays` section ever drops the line.
 */
fun readDeviceFocusState(): DeviceFocusState {
    val app = appUnderTestPackage()
    val narrow = runCatching { shellCommand("dumpsys window displays") }
    val narrowState = narrow.map { parseDeviceFocusState(it, app) }.getOrNull()
    if (narrowState != null && narrowState.probeFailure == null) return narrowState

    val wide = runCatching { shellCommand("dumpsys window") }
    wide.getOrNull()?.let { return parseDeviceFocusState(it, app) }

    val reason = wide.exceptionOrNull() ?: narrow.exceptionOrNull()
    return DeviceFocusState(
        focusedWindows = emptyList(),
        probeFailure = "dumpsys window failed: ${reason?.javaClass?.simpleName}: ${reason?.message}",
        appUnderTest = app,
    )
}

/**
 * Matches the ActivityManager's ANR banner and the lines that explain it: the
 * dispatch reason, the load average, and the `/proc/pressure/cpu` readout that
 * was the smoking gun in run 35435668085 (`some avg10=77.71`).
 */
private val ANR_EVIDENCE = Regex("""ANR in |Reason: Input dispatching timed out|Load: |avg10=""")

/**
 * The `ANR in …` evidence currently in the device log, newest lines last.
 *
 * `scripts/ci-app2-journey-suite.sh` clears logcat before the suite starts, so
 * anything here happened during THIS run. The `Reason:`/`Load:` lines come
 * along because they are what turns "an ANR happened" into "the runner was
 * starved and input dispatch timed out" — the two facts the on-call needs.
 */
fun readSystemAnrLines(limit: Int = 12): List<String> =
    runCatching { shellCommand("logcat -d -b main -s ActivityManager:E -t 4000") }
        .map { anrEvidenceLines(it, limit) }
        .getOrElse { emptyList() }

/** The ANR-evidence lines of a logcat dump, newest last. Split out so the real incident's bytes can pin it. */
fun anrEvidenceLines(dump: String, limit: Int = 12): List<String> =
    dump.lineSequence()
        .filter { ANR_EVIDENCE.containsMatchIn(it) }
        .map { it.trim() }
        .toList()
        .takeLast(limit)

/**
 * How long a shell probe may take before it is abandoned.
 *
 * The probes run on a device that is, by hypothesis, already wedged, and
 * `executeShellCommand`'s pipe read has no deadline of its own. An unbounded
 * wait inside a diagnosis is the exact shape `BoundedWait` exists to ban
 * (issue #2479) — a wedged `dumpsys` would park the suite instead of failing
 * it. On expiry the probe reports a failure, which makes the verdict
 * `UNKNOWN`, which falls back to the product-shaped assertion: an outage this
 * code could not prove is one it does not get to claim.
 */
private const val SHELL_TIMEOUT_MS: Long = 8_000L

/**
 * The worst case of ONE [readDeviceFocusState] call.
 *
 * It runs at most two probes — the narrow `dumpsys window displays`, then the
 * wide `dumpsys window` fallback when the narrow one could not be parsed — and
 * each is bounded by [SHELL_TIMEOUT_MS]. Published because a caller that wants
 * to assert "this path did not spend an unbounded amount of time" must derive
 * its bound from the code's own constants rather than from a stopwatch reading
 * taken on one box (issue #2833, follow-up 4): a number derived here moves
 * with the timeout it depends on, and cannot be made flaky by a contended
 * lane.
 */
const val DEVICE_FOCUS_PROBE_BUDGET_MS: Long = 2 * SHELL_TIMEOUT_MS

/**
 * Runs [command] with the shell's identity and returns its stdout, bounded by
 * [SHELL_TIMEOUT_MS].
 *
 * No pipes, no globs: `UiAutomation.executeShellCommand` hands the string to
 * `Runtime.exec`, so a `|` would be passed to the program as an argument.
 *
 * The read happens on a daemon worker so the deadline can be enforced from the
 * caller's thread; closing the stream is what unblocks a worker still parked
 * on the pipe.
 */
fun shellCommand(command: String): String {
    val descriptor = InstrumentationRegistry.getInstrumentation()
        .uiAutomation
        .executeShellCommand(command)
    val stream = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
    val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "device-focus-shell").apply { isDaemon = true }
    }
    try {
        return worker.submit<String> { stream.readBytes().toString(Charsets.UTF_8) }
            .get(SHELL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    } catch (timeout: TimeoutException) {
        throw IllegalStateException(
            "`$command` produced nothing within ${SHELL_TIMEOUT_MS}ms — the device is not " +
                "answering shell probes",
            timeout,
        )
    } finally {
        runCatching { stream.close() }
        worker.shutdownNow()
    }
}

/**
 * The process-wide record of "this device already told us it has no focused
 * window".
 *
 * ## Why process-wide, and why it is the whole point
 *
 * The journey lane runs all 27 classes in ONE instrumentation process on
 * purpose (issue #2474), so a plain object outlives every class in the run.
 * That is the lever this issue needs: the FIRST waiter to time out on a wedged
 * device diagnoses it, records it here and fails with
 * [DeviceWindowFocusOutageException]; every waiter after it reads the record
 * and throws [org.junit.AssumptionViolatedException] instead — reported as a
 * SKIP, not a failure. One environment cause, reported once, instead of 14
 * identical product-looking assertion errors spread over 8 classes.
 *
 * ## Why it can be cleared
 *
 * An outage that ends must not poison the rest of the run. A waiter that finds
 * a record still gives the device a short [FOCUS_OUTAGE_RECHECK_MS] look; if
 * focus has come back, the record is dropped and the run continues normally
 * from there. A device that stays wedged short-circuits in seconds instead of
 * burning a full per-call budget per class — the "abort fast, one signal"
 * half of issue #2830.
 */
object DeviceFocusOutage {

    /**
     * The logcat tag the outage is echoed under.
     *
     * The record lives in memory, which is exactly where a post-mortem cannot
     * reach it: run 35462083590's on-call had `artifacts/app2-journey/logcat.txt`
     * and `gradle.log` and nothing else. Echoing the report to the device log
     * puts [DEVICE_FOCUS_OUTAGE_MARKER] in a THIRD artifact — one that survives
     * a truncated gradle log and an unparsable XML — which is what
     * `scripts/ci-app2-journey-suite.sh`'s `outage_verdict` now scans as its
     * last resort (issue #2838).
     */
    const val LOG_TAG: String = "DeviceFocusOutage"

    @Volatile
    private var report: String? = null

    /** The first outage report of this process, or null while the device is healthy. */
    fun recorded(): String? = report

    /**
     * Records the first outage. Later reports do not overwrite the first one.
     *
     * The echo happens only on the FIRST record, for the same reason the report
     * does: one environment cause, said once.
     */
    @Synchronized
    fun record(message: String) {
        if (report != null) return
        report = message
        // pid, because "did the record carry across classes?" is a question the
        // artifacts must answer on their own: one pid over many classes is the
        // single-process property (issue #2474) this skip depends on.
        Log.e(LOG_TAG, "pid=${android.os.Process.myPid()} $message")
    }

    /** Drops the record — the device granted focus again, or a test is resetting state. */
    fun clear() {
        report = null
    }

    /**
     * Puts back a report this process had ALREADY made, without echoing it
     * again.
     *
     * The two classes that manufacture the wedge ([DeviceFocusOutageReproTest],
     * [DeviceFocusDiagnosisTest]) clear the record in `@After` and must restore
     * whatever they inherited, because on a genuinely wedged device that report
     * is the one every later class keeps skipping against. Routing that through
     * [record] would re-log the marker once per class and turn "one environment
     * cause, said once" into a per-class echo in `logcat.txt` — the exact noise
     * issue #2830 exists to remove.
     */
    fun restore(inherited: String?) {
        report = inherited
    }
}

/**
 * Stands the caller aside when a device-wide focus outage is ALREADY on record
 * and the device still reads that way.
 *
 * For the preconditions [awaitWindowFocus] cannot speak for: a class that
 * checks `hasWindowFocus()` itself, or asserts on the TEXT of a
 * product-shaped focus failure, has an oracle that is simply wrong about a
 * wedged device — it was written for one where focus is obtainable. On run
 * 35462083590 that is 6 of the 19 failures ([DeviceFocusOutageReproTest] x2,
 * [InputFocusRaceTest] x4), none of which had reached the behaviour it pins.
 *
 * ## Why it never records one itself
 *
 * The FIRST discovery of a wedge must stay a FAILURE — that is
 * [awaitWindowFocus]'s contract, and it is what keeps the lane
 * red-with-diagnosis instead of green-with-skips (issue #2838's acceptance is
 * explicit about that). This helper only stands aside against a report that
 * already exists, so a wedged run always carries exactly one outage failure
 * and N skips, never zero failures.
 *
 * ## Why it re-probes
 *
 * A sticky record would skip every later focus-dependent test after one bad
 * minute, which is the anti-masking property
 * [DeviceFocusOutageReproTest.aRecordedOutageStopsSkippingOnceTheDeviceRecovers]
 * exists for. A recovered device drops the record here too and the caller runs
 * normally. Costs one null check on a healthy device: with nothing on record
 * there is no probe at all.
 */
fun assumeNoRecordedDeviceFocusOutage(what: String) {
    val recorded = DeviceFocusOutage.recorded() ?: return
    val state = readDeviceFocusState()
    if (!state.isOutage) {
        DeviceFocusOutage.clear()
        return
    }
    throw AssumptionViolatedException(
        "$DEVICE_FOCUS_OUTAGE_MARKER already reported in this instrumentation run, and the " +
            "device was re-probed just now: ${state.describe()}. Skipping $what, whose " +
            "preconditions assume a device that can grant window focus at all, rather than " +
            "restating one environment cause per class. The report this skip stands on:" +
            "\n$recorded",
    )
}

/**
 * How long a waiter looks for focus once an outage is already on record.
 *
 * Long enough that a device which recovered between two classes is seen to
 * have recovered (a healthy window takes focus in well under a second), short
 * enough that a device which is still wedged costs the remaining classes
 * seconds rather than a full budget each.
 */
const val FOCUS_OUTAGE_RECHECK_MS: Long = 5_000L

/**
 * The message an environment outage fails with.
 *
 * Opens with [DEVICE_FOCUS_OUTAGE_MARKER] so the signature is the first thing
 * in the failure text, names the settle point it was waiting on, states the
 * device-level observation that makes it an environment call rather than a
 * product one, and ends with the action (rerun the lane) — the whole triage an
 * on-call would otherwise reconstruct from a logcat artifact.
 */
fun deviceFocusOutageMessage(
    what: String,
    timeoutMs: Long,
    state: DeviceFocusState,
    anrLines: List<String>,
): String = buildString {
    append(DEVICE_FOCUS_OUTAGE_MARKER)
    append(" — the DEVICE never granted window focus for ")
    append(what)
    append(" within ")
    append(timeoutMs)
    append("ms: ")
    append(state.describe())
    append(". ")
    if (state.verdict == DeviceFocusVerdict.ANR_DIALOG_HOLDS_FOCUS) {
        append("No screen can take focus from a system ANR dialog, and no instrumented test ")
        append("can dismiss one — only a user or a reboot does. ")
    } else {
        append("No screen can take focus from a window manager that is granting it to nobody, ")
    }
    append("so this is an environment wedge — a launcher/system ANR on a starved runner ")
    append("leaves exactly this state — not a product failure in this screen. ")
    append("Every later focus wait in this process is SKIPPED against this one report ")
    append("rather than repeating it per class. Action: rerun the app2-journey lane ")
    append("(issue #2830).")
    if (anrLines.isEmpty()) {
        append("\ndevice log: no `ANR in …` line in logcat (the wedge may predate the log buffer).")
        if (state.verdict == DeviceFocusVerdict.ANR_DIALOG_HOLDS_FOCUS) {
            append(" That is the NORMAL shape for this verdict and weakens nothing: the ANR ")
            append("dialog above is the standing evidence, and it outlives the banner — on ")
            append("run 35462083590 the dialog held focus for 25 minutes while the log the ")
            append("suite cleared at startup carried no `ANR in ` line at all (issue #2838).")
        }
    } else {
        append("\ndevice log — the ANR that wedged the window manager:\n")
        append(anrLines.joinToString("\n") { "  $it" })
    }
}
