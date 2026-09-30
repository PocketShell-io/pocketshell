package com.pocketshell.app.smoke;

import android.app.Activity;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.WebView;

import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Issue #2946: proves an Android-injected key reaches the packaged WebView's
 * DOM before a journey depends on injected input.
 *
 * <p>On some hosted API 35 boots a system app (Pixel Launcher, Messaging,
 * SDK setup) hits an ANR and its "isn't responding" dialog owns window focus.
 * Every injected key and tap then goes to that dialog while the app's own
 * view still reports {@code webViewHasFocus=true}, so journeys failed later
 * with messages that looked like product races (lost Enter ack, lost Back,
 * lost tap focus). This probe injects a no-op Shift key through the same
 * {@code Instrumentation.sendKeyDownUpSync} path the journeys use and waits
 * for a capturing {@code keydown} listener in the page to see it. When the key
 * does not arrive it fails once, with the {@link #SIGNATURE} prefix and the
 * system focus owner, and logs full {@code dumpsys} output under {@link #TAG}.
 * It never retries the probe.
 */
final class AndroidInputDeliveryProbe {
    static final String SIGNATURE = "ANDROID_INPUT_INJECTION_NOT_DELIVERED";
    static final String TAG = "PocketshellInputProbe";
    private static final long DELIVERY_TIMEOUT_MILLIS = 5_000;
    private static final int LOG_CHUNK_CHARS = 3_500;
    private static final Pattern WINDOW_TITLE = Pattern.compile("Window\\{[0-9a-f]+ u\\d+ ([^}]*)\\}");
    private static final Pattern SYSTEM_ERROR_WINDOW =
            Pattern.compile("^(Application Not Responding|Application Error)(: .*)?$|.*isn.t responding.*");

    /** Evaluates JavaScript in the packaged WebView and returns the raw JSON result. */
    interface PageEvaluator {
        String evalRaw(String expression) throws Exception;
    }

    /** Runs an action on the activity that owns the packaged WebView. */
    interface ActivityRunner {
        void run(Consumer<Activity> action);
    }

    private AndroidInputDeliveryProbe() {}

    static void assertInjectedKeyReachesPage(String context, PageEvaluator page, ActivityRunner activity)
            throws Exception {
        String token = "i2946-" + SystemClock.uptimeMillis();
        String armed = page.evalRaw(armExpression(token));
        if (!"true".equals(armed)) {
            throw new AssertionError(SIGNATURE + ": could not arm the page keydown listener at " + context
                    + " (result=" + armed + ")");
        }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_SHIFT_LEFT);
        long started = SystemClock.uptimeMillis();
        long deadline = started + DELIVERY_TIMEOUT_MILLIS;
        String observed = "<not evaluated>";
        while (SystemClock.uptimeMillis() < deadline) {
            observed = page.evalRaw(observedExpression(token));
            if ("true".equals(observed)) {
                page.evalRaw(disarmExpression());
                Log.i(TAG, "INPUT_PROBE_OK context=" + context + " deliveredWithinMs="
                        + (SystemClock.uptimeMillis() - started));
                return;
            }
            Thread.sleep(50);
        }

        String pageState = safeEval(page, pageStateExpression(token));
        page.evalRaw(disarmExpression());
        String nativeState = nativeFocusState(activity);
        SystemInputState system = SystemInputState.capture();
        system.logFullDumps(context);
        throw new AssertionError(SIGNATURE + ": an Android-injected KEYCODE_SHIFT_LEFT did not reach the"
                + " packaged WebView page within " + DELIVERY_TIMEOUT_MILLIS + " ms at " + context
                + "; inputDispatcherFocus=" + inputDispatcherFocus(system.input)
                + "; windowManagerFocus=" + system.focusSummary()
                + "; systemErrorWindows=" + system.errorWindows
                + "; page=" + pageState
                + "; native=" + nativeState
                + "; full dumpsys input/window/input_method/activity are in logcat tag " + TAG);
    }

    private static String armExpression(String token) {
        return "(() => {const prior = window.__ps2946InputProbe;"
                + "if (prior?.listener) window.removeEventListener('keydown', prior.listener, true);"
                + "const state = {token: " + JSONObject.quote(token) + ", seen: false, key: null, code: null,"
                + " target: null};"
                + "state.listener = (event) => {if (event.key === 'Shift' || event.code === 'ShiftLeft') {"
                + "state.seen = true; state.key = event.key; state.code = event.code;"
                + "state.target = event.target?.tagName ?? null;}};"
                + "window.addEventListener('keydown', state.listener, true);"
                + "window.__ps2946InputProbe = state; return true;})()";
    }

    private static String observedExpression(String token) {
        return "window.__ps2946InputProbe?.token === " + JSONObject.quote(token)
                + " && window.__ps2946InputProbe.seen === true";
    }

    private static String disarmExpression() {
        return "(() => {const state = window.__ps2946InputProbe;"
                + "if (state?.listener) window.removeEventListener('keydown', state.listener, true);"
                + "delete window.__ps2946InputProbe; return true;})()";
    }

    private static String pageStateExpression(String token) {
        return "JSON.stringify({armedToken: window.__ps2946InputProbe?.token === " + JSONObject.quote(token)
                + ", keySeen: window.__ps2946InputProbe?.seen ?? null,"
                + " documentHasFocus: document.hasFocus(), visibilityState: document.visibilityState,"
                + " activeTag: document.activeElement?.tagName ?? null,"
                + " activeClass: String(document.activeElement?.className ?? ''),"
                + " activeTestId: document.activeElement?.dataset?.testid ?? null,"
                + " route: document.querySelector('.app-shell')?.dataset.route ?? null})";
    }

    private static String safeEval(PageEvaluator page, String expression) {
        try {
            Object decoded = new JSONTokener(page.evalRaw(expression)).nextValue();
            return String.valueOf(decoded);
        } catch (Exception | AssertionError error) {
            return "<page state unavailable: " + error + ">";
        }
    }

    private static String nativeFocusState(ActivityRunner runner) {
        AtomicReference<String> state = new AtomicReference<>("<not captured>");
        try {
            runner.run(activity -> {
                View decor = activity.getWindow().getDecorView();
                WebView webView = findWebView(decor);
                View focused = decor.findFocus();
                state.set("activityHasWindowFocus=" + activity.hasWindowFocus()
                        + ", webViewHasWindowFocus=" + (webView != null && webView.hasWindowFocus())
                        + ", webViewIsFocused=" + (webView != null && webView.isFocused())
                        + ", focusedView=" + (focused == null ? "none" : focused.getClass().getName()));
            });
        } catch (RuntimeException | AssertionError error) {
            state.set("<native state unavailable: " + error + ">");
        }
        return state.get();
    }

    private static WebView findWebView(View view) {
        if (view instanceof WebView) return (WebView) view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                WebView child = findWebView(group.getChildAt(index));
                if (child != null) return child;
            }
        }
        return null;
    }

    static String runShell(String command) {
        try {
            ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .executeShellCommand(command);
            StringBuilder result = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new ParcelFileDescriptor.AutoCloseInputStream(descriptor), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) result.append(line).append('\n');
            }
            return result.toString();
        } catch (Exception error) {
            return "<'" + command + "' failed: " + error + ">";
        }
    }

    /**
     * The window InputDispatcher currently routes keys to. WindowManager's
     * mCurrentFocus can change before the dispatcher applies it, so this is
     * the authoritative owner for injected input. Only the live dispatcher
     * state is read, never its "at time of last ANR" snapshot.
     */
    static String currentInputDispatcherFocus() {
        return inputDispatcherFocus(runShell("dumpsys input"));
    }

    static String inputDispatcherFocus(String dumpsysInput) {
        int start = dumpsysInput.indexOf("Input Dispatcher State:");
        if (start < 0) return "<no Input Dispatcher State in dumpsys input>";
        int end = dumpsysInput.indexOf("Input Dispatcher State at time of last ANR", start);
        String live = dumpsysInput.substring(start, end < 0 ? dumpsysInput.length() : end);
        int focused = live.indexOf("FocusedWindows:");
        if (focused < 0) return "<no FocusedWindows in live dispatcher state>";
        String[] lines = live.substring(focused).split("\\R");
        if (lines.length < 2 || !lines[1].contains("name='")) return "<no focused window>";
        Matcher name = Pattern.compile("name='([^']*)'").matcher(lines[1]);
        return name.find() ? name.group(1) : "<unparsed: " + lines[1].trim() + ">";
    }

    /** A point-in-time copy of the system's input focus and any system error dialogs. */
    static final class SystemInputState {
        final String displays;
        final String windows;
        final String input;
        final String inputMethod;
        final String activities;
        final List<String> errorWindows;

        private SystemInputState(String displays, String windows, String input, String inputMethod,
                                 String activities) {
            this.displays = displays;
            this.windows = windows;
            this.input = input;
            this.inputMethod = inputMethod;
            this.activities = activities;
            this.errorWindows = systemErrorWindows(windows);
        }

        static SystemInputState capture() {
            return new SystemInputState(
                    runShell("dumpsys window displays"),
                    runShell("dumpsys window windows"),
                    runShell("dumpsys input"),
                    runShell("dumpsys input_method"),
                    runShell("dumpsys activity activities"));
        }

        String focusSummary() {
            return focusLines(displays);
        }

        static String focusLines(String displays) {
            StringBuilder summary = new StringBuilder();
            for (String line : displays.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("mCurrentFocus=") || trimmed.startsWith("mFocusedApp=")) {
                    if (summary.length() > 0) summary.append(", ");
                    summary.append(trimmed);
                }
            }
            return summary.length() == 0 ? "<no focus lines in dumpsys window displays>" : summary.toString();
        }

        /** Titles of live windows that are system ANR/crash dialogs. */
        static List<String> systemErrorWindows(String windows) {
            List<String> titles = new ArrayList<>();
            for (String line : windows.split("\\R")) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("Window #")) continue;
                Matcher matcher = WINDOW_TITLE.matcher(trimmed);
                if (matcher.find() && SYSTEM_ERROR_WINDOW.matcher(matcher.group(1)).matches()) {
                    titles.add(matcher.group(1));
                }
            }
            return titles;
        }

        void logFullDumps(String context) {
            Log.e(TAG, "INPUT_PROBE_FAILED context=" + context + " focus=" + focusSummary()
                    + " errorWindows=" + errorWindows);
            logChunked(context, "dumpsys window displays", displays);
            logChunked(context, "dumpsys window windows", windows);
            logChunked(context, "dumpsys input", input);
            logChunked(context, "dumpsys input_method", inputMethod);
            logChunked(context, "dumpsys activity activities", activities);
        }

        private static void logChunked(String context, String label, String text) {
            int chunks = Math.max(1, (text.length() + LOG_CHUNK_CHARS - 1) / LOG_CHUNK_CHARS);
            for (int index = 0; index < chunks; index++) {
                int start = index * LOG_CHUNK_CHARS;
                int end = Math.min(text.length(), start + LOG_CHUNK_CHARS);
                Log.e(TAG, "DUMP|" + context + "|" + label + "|" + (index + 1) + "/" + chunks + "\n"
                        + text.substring(start, end));
            }
        }
    }
}
