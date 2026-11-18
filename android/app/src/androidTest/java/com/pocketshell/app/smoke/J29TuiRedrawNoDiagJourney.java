package com.pocketshell.app.smoke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.WindowInsets;
import android.webkit.WebView;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * #3161: the maintainer's Pixel 7a showed {@code [error] i is not defined} and
 * then {@code [terminal-stall] terminal output parsing stalled} while
 * Antigravity CLI drew its "Do you trust the contents of this project?"
 * prompt, and both toasts sat under the status bar.
 *
 * <p>The root cause was in the shipped bundle only: esbuild's minifier
 * mis-lowered xterm's DECRQM handler into a write to an undeclared {@code i}.
 * Any host program that asks the terminal about a mode ({@code CSI ? Ps $ p},
 * which Antigravity, Claude Code and Gemini CLI send before painting with
 * synchronized output) threw, and the throw killed xterm's write loop.
 *
 * <p>{@link #antigravityTrustPromptRedrawsWithoutErrorOrStall} runs, in a real
 * aplexer session on the Docker fixture, a TUI the runner staged
 * (tests/docker/tui/ps3161-trust-prompt-tui.py): DECRQM queries, then fast
 * synchronized repaints of a box-drawn, coloured trust prompt. The diag sink
 * is watched two ways — the window error hook and the shared diag strip — and
 * any {@code [error]}/{@code terminal-stall} fails the journey. The host's
 * own record of the DECRPM answers the terminal sent back (read by the runner
 * over its own SSH connection, checked by check-js-shared-app-results.py) is
 * the independent oracle that the handler ran.
 *
 * <p>{@link #diagToastClearsTheStatusBarWithPlainWordsAndCopyDetails} raises
 * one unhandled error through the real capture path and measures the strip
 * against the OS's own status-bar inset, then checks the phone wording and
 * that "Copy details" puts the raw record on the Android clipboard.
 */
public class J29TuiRedrawNoDiagJourney extends SharedAppDockerJourneyTest {
    /**
     * The runner stages the TUI in ~/&lt;sessionRun&gt;-ps3161 and reads back the
     * ps3161-host-decrpm.hex the TUI writes there (PS3161_HOST_NAME in
     * scripts/check-js-shared-app-results.py).
     */
    static final String TUI_FOLDER_SUFFIX = "-ps3161";
    static final long PARSE_STALL_TIMEOUT_MILLIS = 2_000;

    /** Everything the diag sink saw since {@link #watchDiagSink}: window errors and strip rows. */
    private static final String DIAG_STATE = "JSON.stringify({"
            + "errors:(window.__ps3161?.errors ?? []),"
            + "strip:(document.querySelector('.diag-strip')?.innerText ?? '').trim(),"
            + "kinds:[...document.querySelectorAll('.diag-strip [data-diag-kind]')].map((n)=>n.dataset.diagKind)})";

    @Test
    public void antigravityTrustPromptRedrawsWithoutErrorOrStall() throws Exception {
        String run = "j29-" + Long.toString(System.currentTimeMillis(), 36);
        connectFixtureAndAttach(run);
        watchDiagSink();
        assertDiagSinkQuiet("before the TUI starts");

        String folder = "~/" + sessionRun() + TUI_FOLDER_SUFFIX;
        // Setup only, not the path under test: start the host's TUI.
        pasteIntoTerminal("python3 " + folder + "/ps3161-trust-prompt-tui.py " + folder + "/ps3161-host-decrpm.hex");
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER);

        // The prompt paints frame after frame; capture one mid-redraw.
        long deadline = SystemClock.uptimeMillis() + 30_000;
        boolean sawPrompt = false;
        while (SystemClock.uptimeMillis() < deadline && !diagSinkSawSomething()) {
            if (terminalIncludes("Yes, I trust this folder")) {
                if (!sawPrompt) committedScreenshot("j29-trust-prompt");
                sawPrompt = true;
            }
            if (terminalIncludes("PS3161_DONE_")) break;
            Thread.sleep(100);
        }
        // Load-bearing: nothing the TUI drew may reach the diag sink.
        assertDiagSinkQuiet("while the trust-prompt TUI redrew");
        assertTrue("the trust prompt must have been drawn in the pane", sawPrompt);
        // Every DECRQM was answered (the TUI counts the DECRPM replies it read).
        awaitJsTrue(terminalHasLine("PS3161_DONE_4", "equals"), 15_000);
        // A dead write loop is only declared after the stall timeout: wait it out twice.
        SystemClock.sleep(2 * PARSE_STALL_TIMEOUT_MILLIS + 500);
        assertDiagSinkQuiet("after the stall timeout");
        // The pane still parses output and takes keystrokes.
        typeLine("echo PS3161_ALIVE_$((6*7))");
        awaitTerminalLine("PS3161_ALIVE_42");
        assertDiagSinkQuiet("after typing into the pane again");
        committedScreenshot("j29-after-tui");
    }

    @Test
    public void diagToastClearsTheStatusBarWithPlainWordsAndCopyDetails() throws Exception {
        awaitJsTrue("!!document.querySelector('.host-list, .empty')");
        // The real capture path: a window `error` event carrying an Error, as an uncaught throw raises.
        evalString("(() => {window.dispatchEvent(new ErrorEvent('error', {message: 'i is not defined',"
                + " error: new ReferenceError('i is not defined')})); return 'raised';})()");
        awaitJsTrue("!!document.querySelector('.diag-strip')", 10_000);

        JSONObject strip = new JSONObject(evalString("JSON.stringify((() => {"
                + "const s=document.querySelector('.diag-strip'); const r=s.getBoundingClientRect();"
                + "const copy=s.querySelector('button.copy'); const c=copy?.getBoundingClientRect();"
                + "return {top:r.top, left:r.left, right:r.right, viewportWidth:window.innerWidth,"
                + " viewportHeight:window.innerHeight, compact:s.classList.contains('compact'), text:s.innerText,"
                + " kinds:[...s.querySelectorAll('[data-diag-kind]')].map((n)=>n.dataset.diagKind),"
                + " copy: copy ? {text:copy.textContent.trim(), x:c.left+c.width/2, y:c.top+c.height/2, w:c.width, h:c.height} : null};"
                + "})())"));
        JSONObject screen = nativeScreen();
        Log.i(TAG, "J29_DIAG_TOAST strip=" + strip + " screen=" + screen);

        // Placement: the strip's top edge is below the OS status bar / cutout, in screen pixels.
        double scale = screen.getDouble("webViewWidthPx") / strip.getDouble("viewportWidth");
        double stripTopPx = screen.getInt("webViewTopPx") + strip.getDouble("top") * scale;
        int statusBarPx = screen.getInt("statusBarTopPx");
        assertTrue("the OS must report a status bar to clear: " + screen, statusBarPx > 0);
        assertTrue("the diag toast must sit below the status bar (top " + stripTopPx + "px, status bar "
                + statusBarPx + "px): " + strip + " " + screen, stripTopPx >= statusBarPx);
        assertTrue("and right under it, not pushed down the screen (top " + stripTopPx + "px)",
                stripTopPx <= statusBarPx + 24 * screen.getDouble("density"));
        assertTrue("the toast stays inside the viewport width: " + strip,
                strip.getDouble("left") >= 0 && strip.getDouble("right") <= strip.getDouble("viewportWidth"));

        // Wording: a plain sentence and an action on a phone, not the raw developer record.
        assertTrue("a phone gets the compact strip: " + strip, strip.getBoolean("compact"));
        assertEquals(new JSONArray().put("error").toString(), strip.getJSONArray("kinds").toString());
        String text = strip.getString("text");
        assertTrue("plain wording: " + text, text.contains("Something went wrong in PocketShell."));
        assertFalse("no raw kind tag: " + text, text.contains("[error]"));
        assertFalse("no developer message: " + text, text.contains("is not defined"));
        JSONObject copy = strip.optJSONObject("copy");
        assertNotNull("the toast offers Copy details: " + strip, copy);
        assertEquals("Copy details", copy.getString("text"));
        assertTrue("Copy details is a 44dp+ touch target: " + copy, copy.getDouble("h") >= 44 && copy.getDouble("w") >= 44);

        // Evidence only: a swiftshader emulator composites the new layer a beat
        // after the WebView reports it committed.
        SystemClock.sleep(1_500);
        committedScreenshot("j29-diag-toast");

        // The action works: a real tap puts the raw record on the Android clipboard.
        tapViewportPoint(copy.getDouble("x"), copy.getDouble("y"), strip.getDouble("viewportWidth"));
        awaitJsTrue("document.querySelector('.diag-strip button.copy')?.textContent.trim() === 'Copied'", 10_000);
        String clip = androidClipboardText();
        assertTrue("the clipboard holds the raw record for a bug report: " + clip,
                clip != null && clip.startsWith("[error] i is not defined") && clip.contains("ReferenceError"));
        SystemClock.sleep(1_500);
        committedScreenshot("j29-diag-toast-copied");
    }

    /** Record every uncaught error and rejection the page sees from now on. */
    private void watchDiagSink() throws Exception {
        evalString("(() => {if(window.__ps3161) return 'armed'; window.__ps3161={errors:[]};"
                + "window.addEventListener('error',(e)=>window.__ps3161.errors.push('error: '+String(e.error?.stack ?? e.message)));"
                + "window.addEventListener('unhandledrejection',(e)=>window.__ps3161.errors.push('rejection: '+String(e.reason?.stack ?? e.reason)));"
                + "return 'armed';})()");
    }

    private boolean diagSinkSawSomething() throws Exception {
        JSONObject state = new JSONObject(evalString(DIAG_STATE));
        return state.getJSONArray("errors").length() > 0 || !state.getString("strip").isEmpty();
    }

    private void assertDiagSinkQuiet(String when) throws Exception {
        JSONObject state = new JSONObject(evalString(DIAG_STATE));
        if (state.getJSONArray("errors").length() > 0 || !state.getString("strip").isEmpty()) {
            // Diagnostics: let a wedged write loop reach the stall detector, so the
            // failure says whether a terminal-stall followed the first report.
            SystemClock.sleep(PARSE_STALL_TIMEOUT_MILLIS + 1_000);
            String later = evalString(DIAG_STATE);
            committedScreenshot("j29-diag-sink-failure");
            Log.e(TAG, "J29_DIAG_SINK " + when + " " + state + " later=" + later + " terminal=" + evalString(VISIBLE_TERMINAL));
            throw new AssertionError("the diag sink reported a problem " + when + ": " + state
                    + "; after the stall timeout: " + later);
        }
    }

    private boolean terminalIncludes(String needle) throws Exception {
        return Boolean.parseBoolean(evalString("String(" + VISIBLE_TERMINAL + ".replace(/\\n/g, '').includes("
                + JSONObject.quote(needle) + "))"));
    }

    /** The WebView's place on screen and the OS's own top inset, in physical pixels. */
    private JSONObject nativeScreen() {
        AtomicReference<JSONObject> result = new AtomicReference<>();
        scenario.onActivity(activity -> {
            try {
                JSONObject out = new JSONObject();
                WebView webView = activity.getBridge().getWebView();
                int[] location = new int[2];
                webView.getLocationOnScreen(location);
                out.put("webViewTopPx", location[1]);
                out.put("webViewWidthPx", webView.getWidth());
                out.put("density", activity.getResources().getDisplayMetrics().density);
                out.put("widthDp", Math.round(activity.getResources().getDisplayMetrics().widthPixels
                        / activity.getResources().getDisplayMetrics().density));
                WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
                int top = insets == null ? 0 : insets.getInsets(
                        WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout()).top;
                out.put("statusBarTopPx", top);
                result.set(out);
            } catch (Exception error) {
                throw new AssertionError(error);
            }
        });
        return result.get();
    }

    private void tapViewportPoint(double x, double y, double viewportWidth) {
        AtomicReference<float[]> screen = new AtomicReference<>();
        scenario.onActivity(activity -> {
            WebView webView = activity.getBridge().getWebView();
            int[] location = new int[2];
            webView.getLocationOnScreen(location);
            float scale = webView.getWidth() / (float) viewportWidth;
            float sx = location[0] + (float) x * scale;
            float sy = location[1] + (float) y * scale;
            Rect visible = new Rect();
            assertTrue(webView.getGlobalVisibleRect(visible));
            assertTrue("tap point must be on the visible WebView", visible.contains(Math.round(sx), Math.round(sy)));
            screen.set(new float[] {sx, sy});
        });
        PhysicalTap.tap(screen.get()[0], screen.get()[1]);
    }

    private String androidClipboardText() {
        AtomicReference<String> text = new AtomicReference<>();
        scenario.onActivity(activity -> {
            ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
            ClipData clip = clipboard.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence value = clip.getItemAt(0).coerceToText(activity);
                text.set(value == null ? null : value.toString());
            }
        });
        return text.get();
    }
}
