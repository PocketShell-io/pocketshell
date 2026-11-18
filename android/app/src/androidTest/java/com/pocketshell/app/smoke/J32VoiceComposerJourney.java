package com.pocketshell.app.smoke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.webkit.WebView;
import androidx.lifecycle.Lifecycle;
import androidx.test.platform.app.InstrumentationRegistry;
import com.pocketshell.app.MainActivity;
import com.pocketshell.app.ime.ScriptedIme;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * #3128: one-tap voice into the shared prompt composer on the phone.
 *
 * <p>The shared shell (launched explicitly, #3185) on a phone-width emulator. One physical tap on the
 * composer launcher mic opens the composer and starts Android's SpeechRecognizer at once; the
 * recognizer is the packaged plugin's debug event source (the J08/J23 fake provider:
 * {@code __ps2857DictationTestMode}, {@code injectTestDictationEvent}), so every partial, final
 * and endpointing outcome is a real native plugin event through the real adapter and core policy.
 *
 * <p>The oracle is the Docker host: the session runs a raw-mode reader that appends every byte the
 * pane's PTY receives to a host file. The journey reads its delta per phase over the app's exec
 * channel, and the runner re-reads the whole file over its own SSH connection
 * ({@code scripts/check-js-voice-composer-results.py}): exactly {@link #INSERT_TEXT} (no Enter),
 * then {@link #SEND_TEXT} plus one CR, and nothing from the HOME-while-listening phase.
 *
 * <p>Methods run in name order: the first use meets Android's RECORD_AUDIO prompt (the runner
 * uninstalls the package first, so the grant is genuinely absent).
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class J32VoiceComposerJourney extends SharedAppDockerJourneyTest {
    static final String INSERT_TEXT = "PS3128 insert check the deploy log";
    static final String SEND_TEXT = "PS3128 send the summary.";
    static final String HOME_TEXT = "PS3128 never written";
    static final String SWITCH_TEXT = "PS3128 words for the voice tab";

    private static final String LAUNCHER = "[data-testid=voice-composer-launcher]";
    private static final String PANEL = "[data-testid=composer-dictation]";
    private static final String DRAFT = "textarea[aria-label=\"Prompt draft\"]";
    private static final String CLOSE_COMPOSER = "button[aria-label=\"Close the prompt panel\"]";
    private static final String READER = "ps3128-reader.py";
    private static final String BYTES = "ps3128-bytes.bin";

    private String folder;
    private String transportId;
    private String generationId;
    /** The pane size before the one tap; the whole voice flow must leave it unchanged. */
    private String sizeBeforeTap;
    /** The host's SIGWINCH count for the reader's PTY when the flow started. */
    private int winchBeforeTap;

    /** First use: RECORD_AUDIO is asked for from the tap; partials stream; endpointing is a pause; Stop; Insert has no Enter. */
    @Test
    public void a1_oneTapListensStreamsPartialsUntilExplicitStopThenInsertsWithoutEnter() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("the runner installs the app fresh: RECORD_AUDIO must not be granted before the first use",
                PackageManager.PERMISSION_DENIED, target.checkSelfPermission(Manifest.permission.RECORD_AUDIO));
        int before = openSessionWithReader();
        enableFakeRecognizer();

        oneTapToListening(true);
        assertEquals("RECORD_AUDIO is granted through the first-use prompt",
                PackageManager.PERMISSION_GRANTED, target.checkSelfPermission(Manifest.permission.RECORD_AUDIO));

        // The prompt sits near the top of the pane: the cursor row is visible
        // without moving the view at all.
        assertCursorVisibleAboveCard(false);
        assertOneActionSet();

        // Partials stream into the composer draft itself.
        String turn1 = injectExpectingActiveTurn("partial", "PS3128 insert", null);
        awaitDraft("PS3128 insert");
        injectExpectingActiveTurn("partial", "PS3128 insert check", null);
        awaitDraft("PS3128 insert check");
        committedScreenshot("j32-partials-streaming");

        // The recognizer ends its turn (a final), then endpoints on silence: both are pauses.
        inject("result", "PS3128 insert check", null);
        String turn2 = awaitNewActiveTurn(turn1);
        assertPhase("listening");
        inject("recoverable", null, "speech-timeout");
        String turn3 = awaitNewActiveTurn(turn2);
        assertPhase("listening");
        injectExpectingActiveTurn("partial", "the deploy log", null);
        awaitDraft(INSERT_TEXT);
        SystemClock.sleep(1_500);
        assertPhase("listening");
        assertEquals("nothing reaches the PTY while dictating", before, hostByteCount());

        // Only the explicit Stop ends listening; the final settles the review.
        tapDom("[data-testid=composer-dictation-stop]");
        awaitJsTrue("document.querySelector('" + PANEL + "')?.dataset.phase === 'stopping'");
        inject("result", "the deploy log", null);
        awaitJsTrue("document.querySelector('" + PANEL + "')?.dataset.phase === 'review'");
        awaitDraft(INSERT_TEXT);
        assertFalse("the stop must not open another recognizer turn: " + turn3, activeTurnAfterProbe());
        committedScreenshot("j32-review");

        tapDom("[data-testid=composer-dictation-insert]");
        assertHostBytes(before, INSERT_TEXT);
        awaitDraft("");
        assertTrue("Insert keeps the composer open", exists(DRAFT));
        committedScreenshot("j32-inserted");

        // Closing the composer must not resize the remote terminal either. No
        // keyboard hide in between: the composer must not have raised it.
        String openSize = hostTtySize();
        tapDom(CLOSE_COMPOSER);
        awaitJsTrue("!document.querySelector('" + DRAFT + "')");
        awaitJsTrue("document.querySelector('.terminal-area')?.dataset.terminalLift === '0'");
        SystemClock.sleep(1_500);
        assertEquals("closing the composer must not resize the remote terminal (stty size)", openSize, hostTtySize());
        assertEquals("tap, dictate, stop, Insert and close must leave the remote terminal size as before the tap",
                sizeBeforeTap, hostTtySize());
        assertNoResizeSinceTap("tap, dictate, stop, Insert and close");
        committedScreenshot("j32-closed");
    }

    /** Send delivers the dictated draft as the exact bytes plus one Enter. */
    @Test
    public void a2_stopThenSendDeliversExactBytesWithEnter() throws Exception {
        int before = openSessionWithReader();
        // Output (not input) fills the pane, so the cursor row is at the very
        // bottom, where the card would cover it.
        fillPaneFromHost(80);
        enableFakeRecognizer();
        oneTapToListening(false);
        assertCursorVisibleAboveCard(true);
        assertOneActionSet();
        injectExpectingActiveTurn("partial", "PS3128 send the", null);
        awaitDraft("PS3128 send the");
        injectExpectingActiveTurn("partial", "PS3128 send the summary", null);
        tapDom("[data-testid=composer-dictation-stop]");
        inject("result", SEND_TEXT, null);
        awaitJsTrue("document.querySelector('" + PANEL + "')?.dataset.phase === 'review'");
        awaitDraft(SEND_TEXT);
        tapDom("[data-testid=composer-dictation-send]");
        assertHostBytes(before, SEND_TEXT + "\r");
        assertNoResizeSinceTap("tap, dictate, stop and Send");
        committedScreenshot("j32-sent");
    }

    /** HOME while listening stops the recognizer; nothing is written to the PTY; the words stay in the draft. */
    @Test
    public void a3_homeWhileListeningStopsWithoutWriting() throws Exception {
        int before = openSessionWithReader();
        enableFakeRecognizer();
        oneTapToListening(false);
        String turn = injectExpectingActiveTurn("partial", HOME_TEXT, null);
        awaitDraft(HOME_TEXT);

        ScriptedIme.shell("input keyevent KEYCODE_HOME");
        long deadline = SystemClock.uptimeMillis() + 15_000;
        while (SystemClock.uptimeMillis() < deadline && scenario.getState() != Lifecycle.State.CREATED) {
            Thread.sleep(100);
        }
        assertEquals("HOME backgrounds the app", Lifecycle.State.CREATED, scenario.getState());
        Thread.sleep(3_000);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.startActivity(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        scenario.moveToState(Lifecycle.State.RESUMED);

        awaitJsTrue("['review','idle'].includes(document.querySelector('" + PANEL + "')?.dataset.phase)");
        assertFalse("the recognizer turn " + turn + " must be released by the background", activeTurnAfterProbe());
        // A late event for the abandoned turn changes nothing.
        inject("partial", "late words after home", null);
        SystemClock.sleep(1_000);
        awaitDraft(HOME_TEXT);
        SystemClock.sleep(3_000);
        assertEquals("HOME while listening must write nothing to the PTY", before, hostByteCount());
        assertNoResizeSinceTap("tap, dictate, HOME and return");
        committedScreenshot("j32-after-home");
    }

    /** Switching to another session's tab while listening stops the recognizer there and then; nothing is written. */
    @Test
    public void a4_sessionSwitchWhileListeningStopsWithoutWriting() throws Exception {
        int before = openSessionWithReader();
        enableFakeRecognizer();
        oneTapToListening(false);
        String voiceSession = evalString("document.querySelector('" + PANEL + "').dataset.sessionKey");
        String turn = injectExpectingActiveTurn("partial", SWITCH_TEXT, null);
        awaitDraft(SWITCH_TEXT);

        String otherTag = sessionRun() + "-other";
        awaitJsTrue(tabExpression(otherTag));
        selectTab(otherTag);
        awaitJsTrue("document.querySelector('" + PANEL + "')?.dataset.sessionKey !== " + JSONObject.quote(voiceSession));
        // The composer now drafts for the other session: its draft is empty and its panel idle.
        awaitDraft("");
        assertPhase("idle");
        // Android side: the recognizer turn is released (the mic is off) ...
        assertFalse("switching session must release recognizer turn " + turn, activeTurnAfterProbe());
        // ... and a late event for it reaches no draft.
        inject("partial", "late words after the switch", null);
        SystemClock.sleep(1_000);
        awaitDraft("");
        committedScreenshot("j32-after-switch");
        SystemClock.sleep(2_000);
        assertEquals("a session switch while listening must write nothing to the PTY", before, hostByteCount());

        // The words stayed in the draft of the session they were spoken into (read from the
        // composer's persisted per-session drafts, so the voice tab is not re-attached), and the
        // late event reached no draft at all.
        awaitJsTrue("(() => {const all=Object.values(JSON.parse(localStorage.getItem('pocketshell.composer.v1')||'{}'));"
                + "return all.some((s)=>s.draft===" + JSONObject.quote(SWITCH_TEXT) + ")"
                + " && !all.some((s)=>String(s.draft).includes('late words'));})()");
        assertNoResizeSinceTap("tap, dictate and switch session");
        Log.i(TAG, "J32_SWITCH from " + voiceSession);
    }

    // ---- steps -------------------------------------------------------------------------------

    /** Opens this run's voice session and makes sure the host's byte reader runs; returns its byte count. */
    private int openSessionWithReader() throws Exception {
        String run = sessionRun();
        folder = run + "-voice";
        openFixtureHost(run);
        awaitFolder(folder);
        openFolder(folder);
        awaitJsTrue(tabExpression(folder));
        selectTab(folder);
        awaitJsTrue("[...document.querySelectorAll('.folder-bar button.tab')].some((n)=>n.textContent.includes("
                + JSONObject.quote(folder) + ") && (n.classList.contains('active') || n.getAttribute('aria-selected')==='true'))");
        // The pane shows a prompt, or the reader an earlier test of this run started (and filled).
        awaitJsTrue(VISIBLE_TERMINAL + ".includes('$') || " + VISIBLE_TERMINAL + ".includes('PS3128_READY') || "
                + VISIBLE_TERMINAL + ".includes('PS3128_FILL')");
        awaitJsTrue("[...(window.__pocketshellConnectionJournal ?? [])].some((e)=>e.transportId && e.generationId)");
        JSONObject transport = new JSONObject(evalString("JSON.stringify([...(window.__pocketshellConnectionJournal ?? [])]"
                + ".reverse().find((e)=>e.transportId && e.generationId) ?? null)"));
        transportId = transport.getString("transportId");
        generationId = transport.getString("generationId");
        JSONObject alive = exec("reader-alive", "p=\"$HOME/" + folder + "/" + BYTES + ".pid\"; "
                + "if [ -f \"$p\" ] && [ -f \"$HOME/" + folder + "/" + BYTES + ".tty\" ] && kill -0 \"$(cat \"$p\")\" 2>/dev/null; then echo alive; else echo absent; fi");
        if (!alive.optString("stdout").contains("alive")) {
            // Setup only, not the path under test: start the host's raw byte reader in the pane.
            pasteIntoTerminal("python3 ~/" + folder + "/" + READER + " ~/" + folder + "/" + BYTES);
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER);
            awaitJsTrue(VISIBLE_TERMINAL + ".includes('PS3128_READY')");
        }
        // The prompt panel starts closed, so the launcher (shown only while it is) is on screen.
        if (exists(CLOSE_COMPOSER)) click(CLOSE_COMPOSER);
        awaitJsTrue("!document.querySelector('" + DRAFT + "')");
        hideKeyboard();
        return hostByteCount();
    }

    private void enableFakeRecognizer() throws Exception {
        evalString("window.__ps2857DictationTestMode = true; 'debug speech event mode enabled'");
    }

    /**
     * ONE physical tap on the launcher: the composer is then open and the native recognizer has an
     * active turn, with no further tap (other than Android's own first-use permission prompt).
     */
    private void oneTapToListening(boolean expectPermissionPrompt) throws Exception {
        awaitJsTrue("(() => {const n=document.querySelector('" + LAUNCHER + "');if(!n)return false;"
                + "const r=n.getBoundingClientRect();return r.width>=48&&r.height>=48"
                + "&&document.elementFromPoint(r.left+r.width/2,r.top+r.height/2)?.closest('" + LAUNCHER + "')===n;})()");
        assertFalse("the composer starts closed", exists(DRAFT));
        committedScreenshot(expectPermissionPrompt ? "j32-launcher" : "j32-launcher-again");
        String sizeBefore = hostTtySize();
        sizeBeforeTap = sizeBefore;
        winchBeforeTap = hostWinchCount();
        tapDom(LAUNCHER);
        if (expectPermissionPrompt) allowMicrophoneFromSystemPrompt();
        awaitJsTrue("!!document.querySelector('" + DRAFT + "') && document.querySelector('" + PANEL + "')?.dataset.phase === 'listening'", 20_000);
        assertFalse("the launcher hides once the composer is open", exists(LAUNCHER));
        assertTrue("one tap leaves an active native recognizer turn", activeTurnAfterProbe());
        SystemClock.sleep(1_500);
        assertEquals("opening the composer and starting dictation must not resize the remote terminal (stty size)",
                sizeBefore, hostTtySize());
        assertNoResizeSinceTap("the one tap that opens the composer and starts listening");
        committedScreenshot(expectPermissionPrompt ? "j32-one-tap-listening" : "j32-one-tap-listening-again");
    }

    /** Android's runtime prompt for RECORD_AUDIO, answered "While using the app" with a physical tap. */
    private void allowMicrophoneFromSystemPrompt() throws Exception {
        UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        AccessibilityServiceInfo info = automation.getServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        automation.setServiceInfo(info);
        String[] ids = {
            "com.android.permissioncontroller:id/permission_allow_foreground_only_button",
            "com.android.permissioncontroller:id/permission_allow_button",
        };
        long deadline = SystemClock.uptimeMillis() + 20_000;
        while (SystemClock.uptimeMillis() < deadline) {
            for (AccessibilityWindowInfo window : automation.getWindows()) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                for (String id : ids) {
                    List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(id);
                    if (found == null || found.isEmpty()) continue;
                    Rect bounds = new Rect();
                    found.get(0).getBoundsInScreen(bounds);
                    Log.i(TAG, "J32_PERMISSION_PROMPT " + id + " " + bounds);
                    screenshot("j32-permission-prompt");
                    PhysicalTap.tap(bounds.exactCenterX(), bounds.exactCenterY());
                    return;
                }
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Android never asked for RECORD_AUDIO after the first mic tap");
    }

    private void tapDom(String selector) throws Exception {
        awaitJsTrue("(() => {const n=document.querySelector(" + JSONObject.quote(selector) + ");if(!n||n.disabled)return false;"
                + "const r=n.getBoundingClientRect();return r.width>0&&r.height>0"
                + "&&document.elementFromPoint(r.left+r.width/2,r.top+r.height/2)?.closest(" + JSONObject.quote(selector) + ")===n;})()");
        JSONObject point = new JSONObject(evalString("JSON.stringify((() => {const r=document.querySelector("
                + JSONObject.quote(selector) + ").getBoundingClientRect();"
                + "return {x:r.left+r.width/2,y:r.top+r.height/2,w:innerWidth};})())"));
        AtomicReference<float[]> screen = new AtomicReference<>();
        scenario.onActivity(activity -> {
            WebView webView = activity.getBridge().getWebView();
            int[] location = new int[2];
            webView.getLocationOnScreen(location);
            float scale = webView.getWidth() / (float) point.optDouble("w");
            screen.set(new float[] {
                location[0] + (float) point.optDouble("x") * scale,
                location[1] + (float) point.optDouble("y") * scale,
            });
        });
        PhysicalTap.tap(screen.get()[0], screen.get()[1]);
    }

    private void hideKeyboard() {
        scenario.onActivity(activity -> {
            android.view.View focus = activity.getCurrentFocus();
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                    activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (focus != null) imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        });
        SystemClock.sleep(800);
    }

    private void assertPhase(String phase) throws Exception {
        assertEquals("composer dictation phase", phase,
                evalString("document.querySelector('" + PANEL + "')?.dataset.phase ?? 'absent'"));
    }

    private void awaitDraft(String expected) throws Exception {
        awaitJsTrue("document.querySelector(" + JSONObject.quote(DRAFT) + ")?.value === " + JSONObject.quote(expected), 15_000);
    }

    // ---- the fake recognizer (the packaged plugin's debug event source) -------------------

    /** Inject one native recognizer event; returns the plugin's result. */
    private JSONObject inject(String type, String text, String code) throws Exception {
        JSONObject options = new JSONObject().put("type", type);
        if (text != null) options.put("text", text);
        if (code != null) options.put("code", code);
        evalString("window.__ps3128Injection = null; (() => {"
                + "const plugin=window.Capacitor?.Plugins?.SpeechRecognition;"
                + "if(!plugin?.injectTestDictationEvent)throw new Error('native debug dictation injection is unavailable');"
                + "plugin.injectTestDictationEvent(JSON.parse(" + JSONObject.quote(options.toString()) + "))"
                + ".then(r=>window.__ps3128Injection=JSON.stringify(r)).catch(e=>window.__ps3128Injection='ERROR: '+String(e));"
                + "return 'queued';})()");
        awaitJsTrue("typeof window.__ps3128Injection === 'string'");
        String result = evalString("window.__ps3128Injection");
        assertTrue("the packaged SpeechRecognition plugin must emit the injected " + type + ": " + result,
                result.startsWith("{") && new JSONObject(result).optBoolean("emitted"));
        return new JSONObject(result);
    }

    /** An event for the native turn that is active right now (its options echo proves it is live); returns its id. */
    private String injectExpectingActiveTurn(String type, String text, String code) throws Exception {
        JSONObject result = inject(type, text, code);
        assertTrue("the event must reach an ACTIVE native recognizer turn: " + result, result.has("silenceWindowMs"));
        return result.getString("requestId");
    }

    /** Whether a native turn is active, probed with a harmless audio-level event. */
    private boolean activeTurnAfterProbe() throws Exception {
        return inject("audio", null, "sound").has("silenceWindowMs");
    }

    /** After an endpoint the adapter must open a fresh native turn by itself. */
    private String awaitNewActiveTurn(String previous) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 10_000;
        while (SystemClock.uptimeMillis() < deadline) {
            JSONObject probe = inject("audio", null, "sound");
            if (probe.has("silenceWindowMs") && !previous.equals(probe.getString("requestId"))) {
                return probe.getString("requestId");
            }
            Thread.sleep(150);
        }
        throw new AssertionError("listening did not continue: no new native recognizer turn after " + previous);
    }

    // ---- the host byte oracle ---------------------------------------------------------------

    private JSONObject exec(String label, String command) throws Exception {
        return awaitNativeExec("j32-" + label + "-" + SystemClock.uptimeMillis(), transportId, generationId, command);
    }

    /** The pane PTY's size as the host sees it ({@code stty size} on the reader's own tty). */
    private String hostTtySize() throws Exception {
        JSONObject size = exec("stty", "stty -F \"$(cat \"$HOME/" + folder + "/" + BYTES + ".tty\")\" size");
        assertEquals("host stty must answer: " + size, 0, size.getInt("exitCode"));
        String rowsCols = size.optString("stdout").trim();
        assertTrue("stty size must be rows and columns: " + rowsCols, rowsCols.matches("[1-9][0-9]* [1-9][0-9]*"));
        Log.i(TAG, "J32_STTY_SIZE " + rowsCols);
        return rowsCols;
    }

    /**
     * G6: the CURSOR CELL itself is on screen, inside the terminal's visible box and above the open
     * card, and a hit test at its centre lands in the terminal (not the composer). The view is lifted
     * only as far as the cursor needs: {@code lifted} says whether this case needs any lift at all.
     */
    private void assertCursorVisibleAboveCard(boolean lifted) throws Exception {
        String probe = "JSON.stringify((() => {"
                + "const term=[...document.querySelectorAll('.xterm')].find((n)=>n.offsetParent!==null);"
                + "const cur=term?.querySelector('.xterm-rows .xterm-cursor');"
                + "const card=document.querySelector('.composer-root > .composer');"
                + "const body=document.querySelector('.tab-body');const area=document.querySelector('.terminal-area');"
                + "if(!term||!cur||!card||!body||!area)return {missing:[!!term,!!cur,!!card,!!body,!!area]};"
                + "const c=cur.getBoundingClientRect(),k=card.getBoundingClientRect(),b=body.getBoundingClientRect();"
                + "const hit=document.elementFromPoint(c.left+Math.max(1,c.width)/2,c.top+c.height/2);"
                + "return {lift:Number(area.dataset.terminalLift),cursorTop:c.top,cursorBottom:c.bottom,cursorH:c.height,"
                + "cardTop:k.top,bodyTop:b.top,hitInTerminal:!!hit&&term.contains(hit),hit:hit?hit.className:null};})())";
        JSONObject at = null;
        long deadline = SystemClock.uptimeMillis() + 10_000;
        while (SystemClock.uptimeMillis() < deadline) {
            at = new JSONObject(evalString(probe));
            if (!at.has("missing") && at.getDouble("cursorBottom") <= at.getDouble("cardTop") + 0.5) break;
            Thread.sleep(200);
        }
        Log.i(TAG, "J32_CURSOR " + at);
        assertFalse("terminal, cursor, card and panes must render: " + at, at.has("missing"));
        assertTrue("the cursor cell must have a real size: " + at, at.getDouble("cursorH") > 0);
        assertTrue("the cursor cell must end above the composer card: " + at,
                at.getDouble("cursorBottom") <= at.getDouble("cardTop") + 0.5);
        assertTrue("the cursor cell must not be lifted out of the top of the pane: " + at,
                at.getDouble("cursorTop") >= at.getDouble("bodyTop") - 0.5);
        assertTrue("a hit test on the cursor cell must land in the terminal, not under the card: " + at,
                at.getBoolean("hitInTerminal"));
        if (lifted) {
            assertTrue("a cursor at the bottom needs the view lifted: " + at, at.getDouble("lift") > 0);
            assertTrue("lifted only as far as the cursor needs (it sits right above the card): " + at,
                    at.getDouble("cardTop") - at.getDouble("cursorBottom") <= at.getDouble("cursorH") + 6);
        } else {
            assertEquals("a cursor already above the card must not move the view: " + at, 0.0, at.getDouble("lift"), 0.0);
        }
    }

    /** Exactly one Discard and one Send are on screen while dictation owns the composer. */
    private void assertOneActionSet() throws Exception {
        JSONObject counts = new JSONObject(evalString("JSON.stringify((() => {"
                + "const root=document.querySelector('.composer-root > .composer');"
                + "const vis=(b)=>{const r=b.getBoundingClientRect();return r.width>0&&r.height>0&&getComputedStyle(b).visibility!=='hidden';};"
                + "const bs=root?[...root.querySelectorAll('button')].filter(vis):[];"
                + "const n=(t)=>bs.filter((b)=>b.textContent.trim()===t).length;"
                + "return {send:n('Send'),discard:n('Discard'),insert:n('Insert'),stop:n('Stop')};})())"));
        Log.i(TAG, "J32_ACTIONS " + counts);
        assertEquals("exactly one Send while dictating: " + counts, 1, counts.getInt("send"));
        assertEquals("exactly one Discard while dictating: " + counts, 1, counts.getInt("discard"));
        assertEquals("exactly one Insert while dictating: " + counts, 1, counts.getInt("insert"));
    }

    /** The host counts SIGWINCH on the reader's PTY: any remote resize at all shows up here. */
    private int hostWinchCount() throws Exception {
        JSONObject read = exec("winch", "f=\"$HOME/" + folder + "/" + BYTES + ".winch\"; if [ -f \"$f\" ]; then cat \"$f\"; else echo 0; fi");
        return Integer.parseInt(read.optString("stdout").trim());
    }

    private void assertNoResizeSinceTap(String what) throws Exception {
        int now = hostWinchCount();
        Log.i(TAG, "J32_WINCH before=" + winchBeforeTap + " now=" + now + " (" + what + ")");
        assertEquals(what + " must not resize the remote terminal (host SIGWINCH count)", winchBeforeTap, now);
    }

    /** Ask the host reader to print {@code lines} lines of OUTPUT (no PTY input), leaving the cursor at the bottom. */
    private void fillPaneFromHost(int lines) throws Exception {
        exec("fill", "echo " + lines + " > \"$HOME/" + folder + "/" + BYTES + ".fill\"");
        awaitJsTrue(VISIBLE_TERMINAL + ".includes('PS3128_FILL_DONE')");
        SystemClock.sleep(800);
    }

    private int hostByteCount() throws Exception {
        JSONObject size = exec("size", "f=\"$HOME/" + folder + "/" + BYTES + "\"; if [ -f \"$f\" ]; then wc -c < \"$f\"; else echo 0; fi");
        return Integer.parseInt(size.optString("stdout").trim());
    }

    private String hostBytesHexFrom(int offset) throws Exception {
        JSONObject read = exec("hex", "python3 -c 'import sys;d=open(sys.argv[1],\"rb\").read();print(d[int(sys.argv[2]):].hex())' "
                + "\"$HOME/" + folder + "/" + BYTES + "\" " + offset);
        return read.optString("stdout").trim();
    }

    /** The PTY received exactly {@code expected} after {@code offset}, and nothing more for a settle window. */
    private void assertHostBytes(int offset, String expected) throws Exception {
        String want = hex(expected);
        long deadline = SystemClock.uptimeMillis() + 20_000;
        String got = "";
        while (SystemClock.uptimeMillis() < deadline) {
            got = hostBytesHexFrom(offset);
            if (got.length() >= want.length()) break;
            Thread.sleep(250);
        }
        SystemClock.sleep(1_500);
        got = hostBytesHexFrom(offset);
        Log.i(TAG, "J32_HOST_BYTES offset=" + offset + " hex=" + got);
        assertEquals("the host PTY must receive exactly the dictated bytes (decoded: " + JSONObject.quote(decodeHex(got)) + ")",
                want, got);
    }
}
