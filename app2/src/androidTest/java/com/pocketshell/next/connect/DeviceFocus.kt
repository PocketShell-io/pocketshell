package com.pocketshell.next.connect

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
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
 *  - `mCurrentFocus=Window{… <pkg>/<cls>}` — focus exists and went somewhere
 *    else. That is a real, product-shaped answer ("the launcher has focus, so
 *    the app never came forward" / "a popup holds it"), and it is deliberately
 *    NOT called an outage: `J06BackgroundGraceReturnJourney` backgrounds the
 *    app on purpose, so "the launcher holds focus" is exactly the state its
 *    own product bug would leave behind. Naming the window in the failure
 *    message gives the on-call the same answer without laundering a defect
 *    into an infra retry.
 *  - the probe itself failed — say so and fall back to the product-shaped
 *    message. An unprovable infra claim must never mask a product failure.
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

    /** A window holds focus (ours or another app's) — product-shaped. */
    FOCUSED_ELSEWHERE,

    /** The probe could not be read — product-shaped, conservatively. */
    UNKNOWN,
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
) {
    val verdict: DeviceFocusVerdict
        get() = when {
            probeFailure != null -> DeviceFocusVerdict.UNKNOWN
            focusedWindows.isEmpty() -> DeviceFocusVerdict.NO_FOCUSED_WINDOW
            else -> DeviceFocusVerdict.FOCUSED_ELSEWHERE
        }

    /** One line for a failure message. */
    fun describe(): String = when {
        probeFailure != null -> "device focus could not be read ($probeFailure)"
        focusedWindows.isEmpty() -> "no window on the device holds focus (mCurrentFocus=null)"
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
fun parseDeviceFocusState(dump: String): DeviceFocusState {
    val matches = CURRENT_FOCUS.findAll(dump).map { it.groupValues[1].trim() }.toList()
    if (matches.isEmpty()) {
        return DeviceFocusState(
            focusedWindows = emptyList(),
            probeFailure = "no mCurrentFocus line in the ${dump.length}-byte dumpsys window output",
        )
    }
    return DeviceFocusState(
        focusedWindows = matches.filter { it != "null" },
        probeFailure = null,
    )
}

/**
 * Reads the device's focus state off the real window manager.
 *
 * `dumpsys window displays` carries `mCurrentFocus`/`mFocusedApp` in ~22 KB
 * where the full `dumpsys window` costs ~55 KB; the full dump is the fallback
 * for a platform whose `displays` section ever drops the line.
 */
fun readDeviceFocusState(): DeviceFocusState {
    val narrow = runCatching { shellCommand("dumpsys window displays") }
    val narrowState = narrow.map { parseDeviceFocusState(it) }.getOrNull()
    if (narrowState != null && narrowState.probeFailure == null) return narrowState

    val wide = runCatching { shellCommand("dumpsys window") }
    wide.getOrNull()?.let { return parseDeviceFocusState(it) }

    val reason = wide.exceptionOrNull() ?: narrow.exceptionOrNull()
    return DeviceFocusState(
        focusedWindows = emptyList(),
        probeFailure = "dumpsys window failed: ${reason?.javaClass?.simpleName}: ${reason?.message}",
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

    @Volatile
    private var report: String? = null

    /** The first outage report of this process, or null while the device is healthy. */
    fun recorded(): String? = report

    /** Records the first outage. Later reports do not overwrite the first one. */
    @Synchronized
    fun record(message: String) {
        if (report == null) report = message
    }

    /** Drops the record — the device granted focus again, or a test is resetting state. */
    fun clear() {
        report = null
    }
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
    append(". No screen can take focus from a window manager that is granting it to nobody, ")
    append("so this is an environment wedge — a launcher/system ANR on a starved runner ")
    append("leaves exactly this state — not a product failure in this screen. ")
    append("Every later focus wait in this process is SKIPPED against this one report ")
    append("rather than repeating it per class. Action: rerun the app2-journey lane ")
    append("(issue #2830).")
    if (anrLines.isEmpty()) {
        append("\ndevice log: no `ANR in …` line in logcat (the wedge may predate the log buffer).")
    } else {
        append("\ndevice log — the ANR that wedged the window manager:\n")
        append(anrLines.joinToString("\n") { "  $it" })
    }
}
