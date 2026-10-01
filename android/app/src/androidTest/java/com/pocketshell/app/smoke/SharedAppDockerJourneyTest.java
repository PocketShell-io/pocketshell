package com.pocketshell.app.smoke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.pocketshell.app.MainActivity;
import com.pocketshell.app.SharedShellLaunch;
import com.pocketshell.app.ime.ScriptedIme;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * #2936 stage 1 acceptance, typed through a real input method since #2952: a
 * launch that opts into the shared PocketShell app (core packages/ui) boots
 * it exactly once, and its host picker, session tree and terminal drive real
 * aplexer sessions on a Docker agents lane through Android's PocketShellApi,
 * core's ConnectionController and the native plugin.
 *
 * The runner (scripts/connected-js-shared-app.sh) creates three sessions of
 * this run's own, each in its own folder ({@code sessionRun}-a / -b / -c), so
 * the journey never depends on what earlier lanes left on the fixture.
 *
 * Every keystroke goes through {@link ScriptedIme}: an installed Android input
 * method that edits the focused xterm textarea through the framework
 * InputConnection the way a phone keyboard does (composing spans, word
 * commits, digits as key events, autocorrect rewrites). Neither a paste nor a
 * synthesized per-character key event stands in for typing.
 *
 * Load-bearing assertions are host-evaluated: the typed command holds an
 * arithmetic expansion, so the expected line ("PS2936_42_<marker>") exists only
 * if the keystrokes reached the remote shell and its output came back through
 * the PTY; and {@link #sharedTerminalDeliversImeEditsAsExactBytes} compares the
 * exact bytes a raw-mode reader on the host received.
 */
@RunWith(AndroidJUnit4.class)
public class SharedAppDockerJourneyTest {
    private static final String TAG = "PocketshellImeJourney";
    private static final long WAIT_TIMEOUT_MILLIS = 45_000;
    /** The visible terminal's text: hidden panes keep their own rows. */
    private static final String VISIBLE_TERMINAL =
            "([...document.querySelectorAll('.xterm-rows')].find((n)=>n.offsetParent!==null)?.innerText ?? '')";
    private static final String VISIBLE_TEXTAREA =
            "[...document.querySelectorAll('.xterm')].find((n)=>n.offsetParent!==null)?.querySelector('.xterm-helper-textarea')";
    /**
     * The bytes {@link #sharedTerminalDeliversImeEditsAsExactBytes} must put on
     * the host, in order (hex). Mirrored by EXPECTED_IME_BYTES_HEX in
     * scripts/check-js-shared-app-results.py, which checks the host's own copy.
     */
    static final String EXPECTED_IME_BYTES =
            "echo "                    // composed letter by letter, committed by space
            + "word\u007fk "           // backspace inside the composing word, then a new letter
            + "teh\u007f\u007fhe "     // autocorrect replaces the composing word at commit
            + "\u007fn "               // backspace into the committed word, re-composed, extended
            + "ls -la"                 // a multi-character commit (keyboard clipboard chip)
            + "\r"                     // Enter committed as a newline by the IME
            + "a1b"                    // a digit key event between composing letters
            + "ok"                     // composition finished without a commit
            + "\t\r"                   // hardware Tab and Enter
            + "PASTE1";                // a real clipboard paste on the textarea

    /** Both tests run in one instrumentation process; the first adds the run's host. */
    private static boolean fixtureHostAdded;
    private ActivityScenario<MainActivity> scenario;
    private ScriptedIme ime;

    @Before
    public void launchSharedApp() {
        ime = ScriptedIme.select();
        scenario = ActivityScenario.launch(SharedShellLaunch.intent());
    }

    @After
    public void closeApp() {
        if (scenario != null) scenario.close();
        if (ime != null) ime.close();
    }

    @Test
    public void sharedAppListsAttachesAndTypesIntoFixtureSession() throws Exception {
        String run = sessionRun();
        String folderA = run + "-a";
        String folderB = run + "-b";
        openFixtureHost(run);

        // Listing: this run's folders, from `pocketshell sessions list`.
        awaitFolder(folderA);
        awaitFolder(folderB);

        // First attach of A: aplexer's attach snapshot paints the prompt.
        openFolder(folderA);
        awaitJsTrue(VISIBLE_TERMINAL + ".includes('$')");
        typeLine("echo PS2936_$((6*7))_" + run + "_a1");
        awaitTerminalLine("PS2936_42_" + run + "_a1");
        // Output A will produce while it is NOT attached: only aplexer's
        // re-attach snapshot can bring it to the pane.
        String late = "(sleep 3; echo PS2936_LATE_$((6*7))_" + run + ") &";
        typeLine(late);
        // Leave only once the shell has the whole line (its echo is back):
        // switching closes A's PTY, and keystrokes still in flight would die.
        // (Rows are joined: a phone-width terminal wraps the long command.)
        awaitJsTrue(VISIBLE_TERMINAL + ".replace(/\\n/g, '').includes(" + JSONObject.quote(late) + ")");

        // Switch to B (a fresh attach on the controller's one PTY) and stay
        // there until A's late line has been written on the host.
        long leftA = SystemClock.uptimeMillis();
        openFolder(folderB);
        awaitJsTrue(VISIBLE_TERMINAL + ".includes('$') && !" + VISIBLE_TERMINAL + ".includes('PS2936_42_" + run + "_a1')");
        long stayOnB = 5_000 - (SystemClock.uptimeMillis() - leftA);
        if (stayOnB > 0) Thread.sleep(stayOnB);

        // Re-open A: an already-used session must repaint with what happened
        // while it was away (the late line), not come up blank or stale. The
        // late line is also the sync point that the re-join has completed.
        openFolder(folderA);
        // The background job prints after the prompt, so it shares the "$ " row.
        awaitJsTrue(terminalHasLine("PS2936_LATE_42_" + run, "endsWith"));
        awaitTerminalLine("PS2936_42_" + run + "_a1");
        // Typing right after the re-attach's terminal reset: #2936 captured
        // "echo PS2 PS936_..." here.
        typeLine("echo PS2936_$((6*7))_" + run + "_a2");
        awaitTerminalLine("PS2936_42_" + run + "_a2");
        screenshot("typed-after-reattach");
    }

    /**
     * #2952: every edit a phone keyboard makes reaches the host exactly once,
     * as the bytes a terminal user means. Session C runs a raw-mode reader on
     * the host (staged by the runner) that records every byte until Ctrl+D,
     * then prints them as hex and writes them to a host file the runner checks
     * independently.
     */
    @Test
    public void sharedTerminalDeliversImeEditsAsExactBytes() throws Exception {
        String run = sessionRun();
        String folderC = run + "-c";
        openFixtureHost(run);
        awaitFolder(folderC);
        openFolder(folderC);
        awaitJsTrue(VISIBLE_TERMINAL + ".includes('$')");

        // Setup only, not the path under test: start the host's raw reader.
        pasteIntoTerminal("python3 ~/" + folderC + "/ps2952-capture.py ~/" + folderC + "/ps2952-bytes.hex");
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER);
        awaitJsTrue(VISIBLE_TERMINAL + ".includes('PS2952_READY')");

        focusTerminalForIme();
        Bundle typed = runIme(ScriptedIme.script()
                // Gboard: a word grows as a composing span; space commits it.
                .composeWord("echo").commit("echo").commit(" ")
                // Backspace inside the composing word shortens the span.
                .composeWord("word").compose("wor").compose("work").commit("work").commit(" ")
                // Autocorrect: the composing "teh" is committed as "the".
                .composeWord("teh").commit("the").commit(" ")
                // Backspace into the committed word: the keyboard deletes the
                // space, re-opens "the" as composing text (no new text), and
                // the next letter extends it. Re-sending the re-opened word
                // is exactly #2936's duplicated letters.
                .deleteBefore(1).recompose(3).compose("then").commit("then").commit(" ")
                // A clipboard-chip insert: several characters in one commit.
                .commit("ls -la")
                // Enter in a multi-line field: a committed newline.
                .commit("\n")
                // LatinIME sends digits as key events, between composing words.
                .composeWord("a").commit("a").key(KeyEvent.KEYCODE_1).composeWord("b").commit("b")
                // A composition the keyboard finishes in place (focus change, suggestion bar dismissal).
                .composeWord("ok").finish());
        Log.i(TAG, "ime script finished " + ScriptedIme.describe(typed));

        // Hardware keys: a Bluetooth keyboard's Tab and Enter.
        recordPageKeyUps();
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_TAB);
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER);
        // sendKeyDownUpSync returns once the view hierarchy has the keys, but the
        // WebView hands them to its renderer asynchronously, and an
        // evaluateJavascript paste can overtake them (the host then got
        // "PASTE1\r" instead of "\rPASTE1"). A person cannot paste inside that
        // window; wait until the page has seen Enter's keyup — after its
        // keydown, where xterm sends "\r" — before pasting.
        awaitJsTrue("(window.__psKeyUps ?? []).join(',').endsWith('Tab,Enter')");
        // A real clipboard paste on the focused textarea.
        pasteIntoTerminal("PASTE1");
        assertTerminalKeepsFocus("after typing");
        // Ctrl+D (hardware chord) ends the host reader.
        long now = SystemClock.uptimeMillis();
        InstrumentationRegistry.getInstrumentation().sendKeySync(new KeyEvent(now, now, KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_D, 0, KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON));
        InstrumentationRegistry.getInstrumentation().sendKeySync(new KeyEvent(now, SystemClock.uptimeMillis(),
                KeyEvent.ACTION_UP, KeyEvent.KEYCODE_D, 0, KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON));

        String hex = awaitCapturedHex();
        screenshot("ime-bytes");
        assertEquals("the host must receive each keyboard edit exactly once, as typed (decoded: "
                        + JSONObject.quote(decodeHex(hex)) + ")",
                hex(EXPECTED_IME_BYTES), hex);
    }

    private String sessionRun() {
        String run = InstrumentationRegistry.getArguments().getString("sessionRun");
        assertNotNull("pass the runner-created session run id with sessionRun", run);
        assertTrue("sessionRun must be a safe folder token", run.matches("[a-z0-9-]{6,40}"));
        return run;
    }

    /**
     * Boots the shared app and opens this run's fixture host, adding it (and
     * importing the runner's key) only if an earlier test of the run has not.
     */
    private void openFixtureHost(String run) throws Exception {
        var arguments = InstrumentationRegistry.getArguments();
        String host = arguments.getString("sshHost", "10.0.2.2");
        String port = arguments.getString("sshPort");
        String keyPath = arguments.getString("sshPrivateKeyPath");
        assertNotNull("pass the Docker fixture port with sshPort", port);
        assertNotNull("pass the runner-staged test key with sshPrivateKeyPath", keyPath);

        // Exactly one shell booted, chosen before the WebView loaded: no
        // default shell first, no reload into the shared one.
        awaitJsTrue("!!document.querySelector('.host-list, .empty')");
        assertEquals("[\"shared\"]", evalString("sessionStorage.getItem('pocketshell.shell-boot-log')"));
        assertEquals("the opt-in launch must load exactly one page (no load-then-reload)", 1, pageStartCount());

        String hostRow = "[...document.querySelectorAll('.host-row')].some((n)=>n.textContent.includes('fixture-" + run + "'))";
        if (fixtureHostAdded) {
            // An earlier test of this run added it (and consumed the staged
            // key); the saved host list loads asynchronously after the picker.
            awaitJsTrue(hostRow);
        } else {
            fixtureHostAdded = true;
            // The fixture key enters the Android key vault from a content URI; only
            // its handle crosses into the WebView (#2926).
            java.io.File keyDocument = SshKeyVaultTestSupport.copyDockerKeyDocument(
                    InstrumentationRegistry.getInstrumentation().getTargetContext(), keyPath, run);
            evalString(SshKeyVaultTestSupport.beginImport(SshKeyVaultTestSupport.asContentUri(
                    InstrumentationRegistry.getInstrumentation().getTargetContext(), keyDocument), "Docker fixture key"));
            awaitJsTrue("window." + SshKeyVaultTestSupport.IMPORT_RESULT + "?.state === 'ready'");
            String handle = evalString("window." + SshKeyVaultTestSupport.IMPORT_RESULT + ".handleId");
            keyDocument.delete();

            evalString("(() => {const b=[...document.querySelectorAll('button')].find((n)=>n.textContent.trim()==='Add a host');"
                    + "if(!b) throw new Error('no Add a host action'); b.click(); return 'ok';})()");
            awaitJsTrue("!!document.querySelector('[data-testid=android-add-host]')");
            setValue("[data-testid=host-name]", "fixture-" + run);
            setValue("[data-testid=host-hostname]", host);
            setValue("[data-testid=host-port]", port);
            setValue("[data-testid=host-user]", "testuser");
            awaitJsTrue("[...document.querySelectorAll('[data-testid=host-key] option')].some((o)=>o.value===" + JSONObject.quote(handle) + ")");
            setSelect("[data-testid=host-key]", handle);
            click("[data-testid=host-save]");
            awaitJsTrue(hostRow);
        }
        evalString("(() => {[...document.querySelectorAll('.host-row')].find((n)=>n.textContent.includes('fixture-" + run
                + "')).click(); return 'ok';})()");
    }

    private int pageStartCount() {
        AtomicReference<Integer> count = new AtomicReference<>();
        scenario.onActivity(activity -> count.set(activity.pageStartCount()));
        return count.get();
    }

    private void awaitFolder(String folder) throws Exception {
        awaitJsTrue("[...document.querySelectorAll('.dir-header')].some((n)=>n.textContent.includes(" + JSONObject.quote(folder) + "))");
    }

    private void openFolder(String folder) throws Exception {
        evalString("(() => {const row=[...document.querySelectorAll('.dir-header')].find((n)=>n.textContent.includes("
                + JSONObject.quote(folder) + ")); if(!row) throw new Error('no folder row'); row.click(); return 'ok';})()");
    }

    /**
     * Types a line through the scripted input method, the way the emulator's
     * LatinIME types it (composing words, digits as key events, symbols and
     * Enter as commits), then requires the terminal to still own focus: a key
     * event must not divert the rest of the line into the composer.
     */
    private void typeLine(String text) throws Exception {
        focusTerminalForIme();
        Bundle typed = runIme(ScriptedIme.script().typeLikeLatinIme(text).commit("\n"));
        Log.i(TAG, "typed " + JSONObject.quote(text) + " " + ScriptedIme.describe(typed));
        assertTerminalKeepsFocus("after typing " + text);
    }

    /**
     * Gives the visible terminal's textarea DOM and Android focus and asks for
     * the keyboard, then waits until the scripted IME is bound to it.
     */
    private void focusTerminalForIme() throws Exception {
        armInputTrace();
        CountDownLatch shown = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            WebView webView = activity.getBridge().getWebView();
            webView.requestFocus();
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.showSoftInput(webView, 0);
            shown.countDown();
        });
        assertTrue(shown.await(10, TimeUnit.SECONDS));
        // The IME is bound to the textarea once it reports a text editor
        // (a non-editable focus is TYPE_NULL) while the textarea holds DOM focus.
        String app = InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName();
        long deadline = SystemClock.uptimeMillis() + 15_000;
        String last = "<not probed>";
        while (SystemClock.uptimeMillis() < deadline) {
            String focused = evalString("(() => {const t=" + VISIBLE_TEXTAREA + ";"
                    + "if(!t) throw new Error('no visible terminal'); if (document.activeElement!==t) t.focus();"
                    + "return String(document.activeElement===t);})()");
            try {
                Bundle probe = ime.run(ScriptedIme.script(), app);
                last = "domFocus=" + focused + " " + ScriptedIme.describe(probe);
                if ("true".equals(focused) && probe.getInt("editorInputType") != 0) {
                    Log.i(TAG, "IME bound to the terminal: " + last);
                    logInputTrace("focus");
                    armInputTrace();
                    return;
                }
            } catch (AssertionError error) {
                last = "domFocus=" + focused + " " + error.getMessage();
            }
            Thread.sleep(250);
        }
        logInputTrace("focus failed");
        throw new AssertionError("the scripted IME never bound to the focused terminal textarea: " + last);
    }

    private Bundle runIme(ScriptedIme.Script script) throws Exception {
        String app = InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName();
        try {
            return ime.run(script, app);
        } catch (AssertionError error) {
            logInputTrace("failed IME script");
            throw error;
        }
    }

    /**
     * Diagnostics only: records, in the page, the keyboard/IME/focus events
     * the terminal's textarea sees, so a failure shows what the keyboard did.
     */
    private void armInputTrace() throws Exception {
        evalRaw("(() => {if (window.__ps2952Trace) return true; const trace = window.__ps2952Trace = [];"
                + "const describe = (n) => n ? n.tagName + '.' + String(n.className ?? '').split(' ')[0] : 'null';"
                + "for (const type of ['keydown','keyup','beforeinput','input','compositionstart','compositionupdate',"
                + "'compositionend','focusin','focusout','paste']) {"
                + "document.addEventListener(type, (e) => {if (trace.length > 400) trace.shift();"
                + "trace.push([type, describe(e.target), e.key ?? '', e.keyCode ?? '', e.inputType ?? '', e.data ?? '',"
                + "e.target?.value ?? '', describe(document.activeElement)].join('|'));}, true);}"
                + "return true;})()");
    }

    private void logInputTrace(String context) {
        try {
            String trace = evalString("JSON.stringify(window.__ps2952Trace ?? [])");
            Log.i(TAG, "input trace " + context + ": " + trace);
            evalRaw("(window.__ps2952Trace ?? []).length = 0, true");
        } catch (Exception error) {
            Log.w(TAG, "input trace unavailable: " + error);
        }
    }

    private void assertTerminalKeepsFocus(String context) throws Exception {
        logInputTrace(context);
        String state = evalString("JSON.stringify({terminal: document.activeElement === " + VISIBLE_TEXTAREA + ","
                + " active: document.activeElement?.tagName + '.' + String(document.activeElement?.className ?? '')})");
        assertTrue("the terminal must keep focus " + context + " (no hand-off to the composer): " + state,
                new JSONObject(state).getBoolean("terminal"));
    }

    /**
     * A real ClipboardEvent paste on xterm's focused textarea, the event an
     * Android long-press Paste delivers.
     */
    /** Record the key of every keyup the page sees, in order, from now on. */
    private void recordPageKeyUps() throws Exception {
        evalString("(() => {window.__psKeyUps=[];"
                + "if(!window.__psKeyUpHook){window.__psKeyUpHook=true;"
                + "document.addEventListener('keyup',(e)=>window.__psKeyUps.push(e.key),true);}"
                + "return 'ok';})()");
    }

    private void pasteIntoTerminal(String text) throws Exception {
        String result = evalString("JSON.stringify((() => {"
                + "const textarea=" + VISIBLE_TEXTAREA + ";"
                + "if(!textarea) throw new Error('no visible terminal'); textarea.focus();"
                + "const transfer=new DataTransfer();transfer.setData('text/plain'," + JSONObject.quote(text) + ");"
                + "const event=new ClipboardEvent('paste',{clipboardData:transfer,bubbles:true,cancelable:true});"
                + "const accepted=textarea.dispatchEvent(event);"
                + "return {focused:document.activeElement===textarea,accepted};})())");
        JSONObject paste = new JSONObject(result);
        assertTrue("the visible terminal must hold focus for the paste: " + result, paste.getBoolean("focused"));
    }

    private String awaitCapturedHex() throws Exception {
        Pattern captured = Pattern.compile("PS2952_HEX:([0-9a-f]*):END");
        long deadline = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS;
        String text = "";
        while (SystemClock.uptimeMillis() < deadline) {
            text = evalString(VISIBLE_TERMINAL + ".replace(/\\n/g, '')");
            Matcher match = captured.matcher(text);
            if (match.find()) return match.group(1);
            Thread.sleep(150);
        }
        throw new AssertionError("the host reader never reported its bytes; terminal=" + text);
    }

    /** Evidence only: the runner pulls /data/local/tmp/ps2952-*.png next to the results. */
    private void screenshot(String name) {
        ScriptedIme.shell("screencap -p /data/local/tmp/ps2952-" + name + ".png");
    }

    static String hex(String text) {
        StringBuilder out = new StringBuilder();
        for (byte b : text.getBytes(java.nio.charset.StandardCharsets.UTF_8)) out.append(String.format("%02x", b & 0xff));
        return out.toString();
    }

    static String decodeHex(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private void awaitTerminalLine(String line) throws Exception {
        awaitJsTrue(terminalHasLine(line, "equals"));
    }

    /**
     * Whether the visible terminal shows {@code expected} as one logical line,
     * across xterm soft wraps. The matcher is the test APK's
     * {@code terminal-logical-lines.js} asset, which
     * tests/unit/terminalLogicalLines.test.ts replays against captured texts
     * (an exactly full-width line, a wrapped one, 4- and 5-digit run ids).
     */
    private static String terminalHasLine(String expected, String mode) throws java.io.IOException {
        return "(" + logicalLineMatcher() + ")(" + VISIBLE_TERMINAL + ", " + JSONObject.quote(expected) + ", "
                + JSONObject.quote(mode) + ")";
    }

    private static String logicalLineMatcher;

    private static synchronized String logicalLineMatcher() throws java.io.IOException {
        if (logicalLineMatcher == null) {
            try (java.io.InputStream input = InstrumentationRegistry.getInstrumentation().getContext().getAssets()
                    .open("terminal-logical-lines.js")) {
                logicalLineMatcher = new String(readAll(input), java.nio.charset.StandardCharsets.UTF_8)
                        .replaceFirst("(?s)^/\\*.*?\\*/\\s*", "").trim();
            }
        }
        return logicalLineMatcher;
    }

    private static byte[] readAll(java.io.InputStream input) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        for (int n; (n = input.read(buffer)) > 0; ) out.write(buffer, 0, n);
        return out.toByteArray();
    }

    private void setValue(String selector, String value) throws Exception {
        evalString("(() => {const node = document.querySelector(" + JSONObject.quote(selector) + ");"
                + "if (!node) throw new Error('missing ' + " + JSONObject.quote(selector) + ");"
                + "node.value = " + JSONObject.quote(value) + ";"
                + "node.dispatchEvent(new Event('input', {bubbles: true})); return 'set';})()");
    }

    private void setSelect(String selector, String value) throws Exception {
        evalString("(() => {const node = document.querySelector(" + JSONObject.quote(selector) + ");"
                + "if (!node) throw new Error('missing ' + " + JSONObject.quote(selector) + ");"
                + "node.value = " + JSONObject.quote(value) + ";"
                + "node.dispatchEvent(new Event('change', {bubbles: true})); return 'set';})()");
    }

    private void click(String selector) throws Exception {
        evalString("(() => {const node = document.querySelector(" + JSONObject.quote(selector) + ");"
                + "if (!node) throw new Error('missing ' + " + JSONObject.quote(selector) + ");"
                + "node.click(); return 'clicked';})()");
    }

    private void awaitJsTrue(String expression) throws Exception {
        long deadline = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS;
        String last = "<not evaluated>";
        while (SystemClock.uptimeMillis() < deadline) {
            last = evalRaw(expression);
            if ("true".equals(last)) return;
            Thread.sleep(150);
        }
        throw new AssertionError("WebView condition did not become true: " + expression + " (last result: " + last
                + "; terminal=" + evalString(VISIBLE_TERMINAL) + "; page=" + evalString("document.body.innerText") + ")");
    }

    private String evalString(String expression) throws Exception {
        Object decoded = new JSONTokener(evalRaw(expression)).nextValue();
        return decoded == null ? null : decoded.toString();
    }

    private String evalRaw(String expression) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        scenario.onActivity(activity -> {
            WebView webView = activity.getBridge().getWebView();
            assertNotNull("the packaged activity must contain its Capacitor WebView", webView);
            webView.evaluateJavascript(expression, value -> {
                result.set(value);
                latch.countDown();
            });
        });
        assertTrue("WebView JavaScript evaluation timed out", latch.await(10, TimeUnit.SECONDS));
        String value = result.get();
        if (value == null || "null".equals(value)) throw new JSONException("JavaScript returned null: " + expression);
        return value;
    }
}
