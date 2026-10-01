package com.pocketshell.app.smoke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
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
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
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
 *
 * #2954 (U1) adds the D28 reconnect/EOF oracle on the shared shell, each in
 * a session of its own that the journey creates:
 *  - an abrupt server-side drop (our sshd-session SIGKILLed, the approach of
 *    SshPtyDockerJourneyTest) is recovered by exactly ONE ConnectionController
 *    ladder — the shared store runs none of its own — re-attaching the same
 *    aplexer session under the same logical id; then a refused-login give-up
 *    where nothing re-dials until the banner's Reconnect;
 *  - a real session end on a healthy transport reads as ended, with no
 *    reconnect, no new connection and no new dial.
 * Native dials are counted at the Capacitor bridge, so a failed dial counts.
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

    @Test
    public void sharedAppRecoversAbruptServerDropWithOneControllerReconnect() throws Exception {
        String run = Long.toString(System.currentTimeMillis(), 36);
        File artifacts = artifactDirectory(run);
        awaitJsTrue("!!document.querySelector('.host-list, .empty')");
        installRecoveryRecorder();
        connectFixtureAndAttach(run);
        typeLine("echo PS2954_BEFORE_$((6*7))_" + run);
        awaitTerminalLine("PS2954_BEFORE_42_" + run);

        JSONObject before = latestLiveJournalEntry();
        String logicalId = before.getString("connectionId");
        String oldTransport = before.getString("transportId");
        String oldGeneration = before.getString("generationId");
        String sessionId = before.getString("selectedId");
        String sessionTag = before.optString("selectedTag");
        int journalAtDrop = journalLength();
        int transportsBefore = nativeConnectionCount();
        assertTrue("our native transport is open before the drop", transportsBefore >= 1);
        int dialsAtDrop = nativeDialCount();

        // The abrupt drop: kill OUR sshd-session process on the Docker host.
        String proofPath = "/tmp/pocketshell-2954-drop-" + run + ".txt";
        String trigger = "set -eu\n"
                + "parent_pid=\"$PPID\"\n"
                + "parent_args=$(ps -p \"$parent_pid\" -o args=)\n"
                + "case \"$parent_args\" in *\"sshd-session: testuser\"*) ;; *) "
                + "printf 'unexpected SSH server parent: %s\\n' \"$parent_args\" >&2; exit 97 ;; esac\n"
                + "printf 'run_id=%s\\nserver_pid=%s\\nserver_args=%s\\nsignal=SIGKILL\\n' "
                + JSONObject.quote(run) + " \"$parent_pid\" \"$parent_args\" > " + shellQuote(proofPath) + "\n"
                + "kill -KILL \"$parent_pid\"\n";
        long dropAt = System.currentTimeMillis();
        String dropRequest = "u1-drop-" + run;
        assertEquals("started", evalString(nativeExec(dropRequest, oldTransport, oldGeneration, trigger, 12_000)));
        awaitJsTrue("window.__ps2954.lost.filter((e)=>e.connectionId===" + JSONObject.quote(oldTransport)
                + ").length === 1", WAIT_TIMEOUT_MILLIS);
        JSONObject dropExec = new JSONObject(evalString("JSON.stringify(window.__ps2954.execs["
                + JSONObject.quote(dropRequest) + "] ?? null)"));
        Log.i(TAG, "RUN " + run + " DROP_TRIGGER " + dropExec);
        Log.i(TAG, "RUN " + run + " NATIVE_EVENTS " + evalString("JSON.stringify(window.__ps2954.events)"));

        // Recovery: the controller re-attaches the same session under the same logical id.
        awaitJsTrue("(window.__pocketshellConnectionJournal ?? []).slice(" + journalAtDrop + ").some((e)=>"
                + "e.phase==='live' && e.connectionId===" + JSONObject.quote(logicalId)
                + " && e.transportId && e.transportId!==" + JSONObject.quote(oldTransport)
                + " && e.selectedId===" + JSONObject.quote(sessionId) + ")", 90_000);
        long liveAt = System.currentTimeMillis();
        // Outlast the old store ladder's first 5 s step, so a second ladder
        // (the stage-1 overlap) would have dialled by now.
        Thread.sleep(8_000);

        JSONArray afterDrop = new JSONArray(evalString("JSON.stringify((window.__pocketshellConnectionJournal ?? [])"
                + ".slice(" + journalAtDrop + "))"));
        int ladders = 0;
        int maxAttempt = 0;
        java.util.Set<String> transports = new java.util.LinkedHashSet<>();
        java.util.Set<String> logicalIds = new java.util.LinkedHashSet<>();
        for (int i = 0; i < afterDrop.length(); i += 1) {
            JSONObject entry = afterDrop.getJSONObject(i);
            logicalIds.add(entry.getString("connectionId"));
            if ("reconnecting".equals(entry.getString("phase")) && entry.getInt("retryAttempt") == 0) ladders += 1;
            maxAttempt = Math.max(maxAttempt, entry.getInt("retryAttempt"));
            String transport = entry.optString("transportId", "");
            if (!transport.isEmpty() && !"null".equals(transport) && !transport.equals(oldTransport)) {
                transports.add(transport);
            }
        }
        writeText(new File(artifacts, "journal-after-drop.json"), afterDrop.toString(2));
        Log.i(TAG, "RUN " + run + " JOURNAL_AFTER_DROP " + afterDrop);
        assertEquals("the drop must be recovered by exactly one controller ladder: " + afterDrop, 1, ladders);
        assertEquals("the ladder's first dial must land (sshd itself is up): " + afterDrop, 1, maxAttempt);
        assertEquals("exactly one new native transport after the drop: " + transports, 1, transports.size());
        assertEquals("exactly one native dial after the drop (failed dials count too)", 1,
                nativeDialCount() - dialsAtDrop);
        assertEquals("the shared store keeps ONE logical connection (no store re-dial): " + logicalIds,
                java.util.Collections.singleton(logicalId), logicalIds);
        assertEquals("no leaked or doubled native transport after recovery", transportsBefore, nativeConnectionCount());
        assertEquals("one native lost event for one abrupt drop", 1, Integer.parseInt(evalString(
                "String(window.__ps2954.lost.filter((e)=>e.connectionId===" + JSONObject.quote(oldTransport) + ").length)")));
        assertEquals("the recovered transport must not be reported lost", 0, Integer.parseInt(evalString(
                "String(window.__ps2954.lost.filter((e)=>e.connectionId!==" + JSONObject.quote(oldTransport) + ").length)")));
        JSONObject after = latestLiveJournalEntry();
        String newTransport = after.getString("transportId");
        assertEquals("recovery re-attached the same aplexer session", sessionId, after.getString("selectedId"));
        assertEquals("recovery kept the same session tag", sessionTag, after.optString("selectedTag"));
        assertEquals("the live transport is the recovered one", transports.iterator().next(), newTransport);

        // The shared banner read the controller's state, and cleared.
        JSONArray banners = new JSONArray(evalString("JSON.stringify(window.__ps2954.banners)"));
        writeText(new File(artifacts, "banner-texts.json"), banners.toString(2));
        Log.i(TAG, "RUN " + run + " BANNER_TEXTS " + banners);
        boolean sawReconnecting = false;
        for (int i = 0; i < banners.length(); i += 1) {
            if (banners.getString(i).contains("Reconnecting")) sawReconnecting = true;
        }
        awaitJsTrue("!document.querySelector('.link-lost')", WAIT_TIMEOUT_MILLIS);

        // The same terminal still types into the re-attached session.
        String marker = "PS2954_AFTER_42_" + run;
        typeLine("echo PS2954_AFTER_$((6*7))_" + run);
        awaitTerminalLine(marker);
        captureScreen(new File(artifacts, "recovered.png"));

        // Independent host oracle: the drop really happened on the Docker
        // host, and the post-recovery bytes are in the SAME host session.
        JSONObject proof = awaitNativeExec("u1-proof-" + run, newTransport, after.getString("generationId"),
                "cat " + shellQuote(proofPath));
        assertEquals(0, proof.getInt("exitCode"));
        assertTrue("the Docker host recorded the SIGKILL of our sshd-session: " + proof,
                proof.getString("stdout").contains("run_id=" + run) && proof.getString("stdout").contains("signal=SIGKILL"));
        JSONObject capture = awaitNativeExec("u1-capture-" + run, newTransport, after.getString("generationId"),
                "/usr/bin/a capture --bytes 65536 " + shellQuote(sessionId));
        assertEquals("host capture of the re-attached session must succeed: " + capture, 0, capture.getInt("exitCode"));
        assertEquals("the host session holds the post-recovery marker exactly once", 1,
                countExactLines(capture.getString("stdout"), marker));
        writeText(new File(artifacts, "host-proof.txt"), proof.getString("stdout"));

        JSONObject giveUp = refusedLoginsGiveUpThenRetry(run, artifacts, logicalId, sessionId, newTransport,
                after.getString("generationId"), transportsBefore);
        // Checked last so a regression in the recovery ownership above
        // reports first: the banner of the first drop read the controller.
        assertTrue("the shared banner must show the controller's reconnecting state: " + banners, sawReconnecting);

        JSONObject summary = new JSONObject()
                .put("run", run)
                .put("logicalConnectionId", logicalId)
                .put("oldTransportId", oldTransport)
                .put("newTransportId", newTransport)
                .put("sessionId", sessionId)
                .put("controllerLadders", ladders)
                .put("maxRetryAttempt", maxAttempt)
                .put("newTransports", transports.size())
                .put("nativeDials", 1)
                .put("nativeLostEvents", 1)
                .put("dropRequestedAtEpochMs", dropAt)
                .put("recoveredLiveAtEpochMs", liveAt)
                .put("hostMarkerLines", 1)
                .put("giveUpThenRetry", giveUp);
        writeText(new File(artifacts, "summary.json"), summary.toString(2));
        Log.i(TAG, "RUN " + run + " SHARED_APP_DROP_RECOVERED " + summary);
    }

    /**
     * A real session end is not a lost link (#2954). The controller now asks
     * the transport before reading a PTY EOF as "ended" (a dying transport
     * closes its channels before its lost event); on a healthy transport the
     * answer must stay "ended": no reconnect, no new connection, no dial.
     */
    @Test
    public void sharedAppReportsARealSessionEndWithoutReconnecting() throws Exception {
        String run = Long.toString(System.currentTimeMillis(), 36);
        File artifacts = artifactDirectory(run);
        awaitJsTrue("!!document.querySelector('.host-list, .empty')");
        installRecoveryRecorder();
        connectFixtureAndAttach(run);
        typeLine("echo PS2954_END_$((6*7))_" + run);
        awaitTerminalLine("PS2954_END_42_" + run);

        JSONObject before = latestLiveJournalEntry();
        String logicalId = before.getString("connectionId");
        String transport = before.getString("transportId");
        String generation = before.getString("generationId");
        String sessionId = before.getString("selectedId");
        int journalAtEnd = journalLength();
        int transportsBefore = nativeConnectionCount();
        int dialsBefore = nativeDialCount();

        // End the session on the host; our SSH connection stays up.
        JSONObject killed = awaitNativeExec("u1-end-" + run, transport, generation,
                "/usr/bin/a kill " + shellQuote(sessionId));
        Log.i(TAG, "RUN " + run + " SESSION_KILL " + killed);
        assertEquals("the host must end the session: " + killed, 0, killed.getInt("exitCode"));

        // Two legitimate orders: the PTY's EOF reaches the controller first
        // ("Session … ended", the pane prints [process exited]), or the shared
        // tree's poll drops the killed session's folder first and its pane
        // detaches (connected, nothing selected). Either way the connection
        // stays up and nothing reconnects — asserted below.
        String folder = "ps-shared-" + run;
        awaitJsTrue("(window.__pocketshellConnectionJournal ?? []).slice(" + journalAtEnd + ").some((e)=>"
                + "e.connectionId===" + JSONObject.quote(logicalId) + " && e.phase==='connected'"
                + " && ((e.error ?? '').includes('ended') || e.selectedId === null))", WAIT_TIMEOUT_MILLIS);
        awaitJsTrue("window.__ps2954.sawExited === true || ![...document.querySelectorAll('.dir-header')]"
                + ".some((n)=>n.textContent.includes(" + JSONObject.quote(folder) + "))", WAIT_TIMEOUT_MILLIS);
        Log.i(TAG, "RUN " + run + " SESSION_END_SIGNAL " + evalString("JSON.stringify({sawExited:window.__ps2954.sawExited,"
                + "ended:(window.__pocketshellConnectionJournal ?? []).slice(" + journalAtEnd
                + ").some((e)=>(e.error ?? '').includes('ended'))})"));
        // Long enough for any reconnect (controller ladder or store) to show.
        Thread.sleep(8_000);

        JSONArray after = new JSONArray(evalString("JSON.stringify((window.__pocketshellConnectionJournal ?? [])"
                + ".slice(" + journalAtEnd + "))"));
        writeText(new File(artifacts, "journal-session-end.json"), after.toString(2));
        Log.i(TAG, "RUN " + run + " JOURNAL_SESSION_END " + after);
        for (int i = 0; i < after.length(); i += 1) {
            JSONObject entry = after.getJSONObject(i);
            assertEquals("one logical connection: " + after, logicalId, entry.getString("connectionId"));
            assertFalse("a real session end must not reconnect: " + after,
                    "reconnecting".equals(entry.getString("phase")) || "lost".equals(entry.getString("phase")));
            String entryTransport = entry.optString("transportId", "");
            assertTrue("a real session end must not open a new connection: " + after,
                    entryTransport.isEmpty() || "null".equals(entryTransport) || entryTransport.equals(transport));
        }
        assertEquals("no native dial after a real session end", 0, nativeDialCount() - dialsBefore);
        assertEquals("the native connection count is unchanged", transportsBefore, nativeConnectionCount());
        assertEquals("no native lost event", 0, Integer.parseInt(evalString("String(window.__ps2954.lost.length)")));
        assertFalse("no lost-link banner for a session that ended",
                "true".equals(evalRaw("!!document.querySelector('.link-lost')")));
        captureScreen(new File(artifacts, "session-ended.png"));

        // The link is still good: the same transport answers a host command.
        JSONObject probe = awaitNativeExec("u1-end-probe-" + run, transport, generation, "echo alive");
        assertEquals("the connection must still serve commands after the session ended", "alive",
                probe.getString("stdout").trim());
        Log.i(TAG, "RUN " + run + " SESSION_END_OK " + new JSONObject()
                .put("logicalConnectionId", logicalId)
                .put("transportId", transport)
                .put("sessionId", sessionId)
                .put("entriesAfterEnd", after.length())
                .put("nativeDials", 0)
                .put("nativeConnections", transportsBefore));
    }

    /**
     * The non-happy host: the transport drops while the host refuses logins
     * (its authorized_keys is moved aside for 8 s), so the controller's
     * re-dial is refused and it gives up. The shared store must then stay
     * given up — on the stage-1 base its own ReconnectLoop re-dialled 5 s
     * later under a NEW logical connection — and the banner's Reconnect must
     * recover the same logical id and session through ONE controller ladder.
     */
    private JSONObject refusedLoginsGiveUpThenRetry(String run, File artifacts, String logicalId, String sessionId,
            String transport, String generation, int transportsBefore) throws Exception {
        int journalAtDrop = journalLength();
        int dialsAtDrop = nativeDialCount();
        String request = "u1-refuse-" + run;
        String trigger = "set -eu\n"
                + "parent_pid=\"$PPID\"\n"
                + "case \"$(ps -p \"$parent_pid\" -o args=)\" in *\"sshd-session: testuser\"*) ;; *) exit 97 ;; esac\n"
                + "mv \"$HOME/.ssh/authorized_keys\" \"$HOME/.ssh/authorized_keys.ps2954\"\n"
                + "setsid sh -c 'sleep 8; mv \"$HOME/.ssh/authorized_keys.ps2954\" \"$HOME/.ssh/authorized_keys\"' "
                + "</dev/null >/dev/null 2>&1 &\n"
                + "kill -KILL \"$parent_pid\"\n";
        long dropAt = System.currentTimeMillis();
        assertEquals("started", evalString(nativeExec(request, transport, generation, trigger, 12_000)));
        awaitJsTrue("window.__ps2954.lost.filter((e)=>e.connectionId===" + JSONObject.quote(transport)
                + ").length === 1", WAIT_TIMEOUT_MILLIS);
        awaitJsTrue("(window.__pocketshellConnectionJournal ?? []).slice(" + journalAtDrop + ").some((e)=>"
                + "e.phase==='lost' && e.connectionId===" + JSONObject.quote(logicalId) + ")", WAIT_TIMEOUT_MILLIS);
        // Logins are back after 8 s; the base store dialled at 5 s (a new logical id).
        // Hold well past both, then require that nothing re-dialled.
        long quietUntil = dropAt + 12_000;
        while (System.currentTimeMillis() < quietUntil) Thread.sleep(250);
        JSONArray given = new JSONArray(evalString("JSON.stringify((window.__pocketshellConnectionJournal ?? [])"
                + ".slice(" + journalAtDrop + "))"));
        writeText(new File(artifacts, "journal-give-up.json"), given.toString(2));
        Log.i(TAG, "RUN " + run + " JOURNAL_GIVE_UP " + given);
        int laddersBeforeRetry = 0;
        for (int i = 0; i < given.length(); i += 1) {
            JSONObject entry = given.getJSONObject(i);
            assertEquals("no second logical connection while given up: " + given, logicalId, entry.getString("connectionId"));
            if ("reconnecting".equals(entry.getString("phase")) && entry.getInt("retryAttempt") == 0) laddersBeforeRetry += 1;
            String entryTransport = entry.optString("transportId", "");
            assertTrue("nothing may re-dial after the controller gave up: " + given,
                    entryTransport.isEmpty() || "null".equals(entryTransport) || entryTransport.equals(transport));
        }
        assertEquals("the refused re-dial is one controller ladder: " + given, 1, laddersBeforeRetry);
        // A refused login is not retryable, so the ladder dials once; a
        // store ladder would dial again here, successfully or not.
        int dialsWhileGivenUp = nativeDialCount() - dialsAtDrop;
        assertEquals("exactly one native dial while given up (no store re-dial)", 1, dialsWhileGivenUp);
        assertEquals("still given up: " + given, "lost", given.getJSONObject(given.length() - 1).getString("phase"));
        String bannerText = evalString("document.querySelector('.link-lost-text')?.textContent?.trim() ?? ''");
        assertTrue("the banner shows the controller's give-up: " + bannerText, bannerText.contains("Could not reconnect"));
        assertEquals("the banner offers Reconnect", "Reconnect",
                evalString("document.querySelector('.reconnect-btn')?.textContent?.trim() ?? ''"));
        captureScreen(new File(artifacts, "given-up.png"));

        // Retry: the banner's Reconnect → api.ssh.reconnect(id) → one controller ladder.
        int journalAtRetry = journalLength();
        int dialsAtRetry = nativeDialCount();
        click(".reconnect-btn");
        awaitJsTrue("(window.__pocketshellConnectionJournal ?? []).slice(" + journalAtRetry + ").some((e)=>"
                + "e.phase==='live' && e.connectionId===" + JSONObject.quote(logicalId)
                + " && e.transportId && e.selectedId===" + JSONObject.quote(sessionId) + ")", 90_000);
        Thread.sleep(8_000);
        JSONArray retried = new JSONArray(evalString("JSON.stringify((window.__pocketshellConnectionJournal ?? [])"
                + ".slice(" + journalAtRetry + "))"));
        writeText(new File(artifacts, "journal-retry.json"), retried.toString(2));
        Log.i(TAG, "RUN " + run + " JOURNAL_RETRY " + retried);
        int retryLadders = 0;
        java.util.Set<String> retryTransports = new java.util.LinkedHashSet<>();
        for (int i = 0; i < retried.length(); i += 1) {
            JSONObject entry = retried.getJSONObject(i);
            assertEquals("Retry keeps the logical connection: " + retried, logicalId, entry.getString("connectionId"));
            if ("reconnecting".equals(entry.getString("phase")) && entry.getInt("retryAttempt") == 0) retryLadders += 1;
            String entryTransport = entry.optString("transportId", "");
            if (!entryTransport.isEmpty() && !"null".equals(entryTransport)) retryTransports.add(entryTransport);
        }
        assertEquals("Retry runs exactly one controller ladder: " + retried, 1, retryLadders);
        assertEquals("Retry opens exactly one native transport: " + retryTransports, 1, retryTransports.size());
        assertEquals("Retry makes exactly one native dial", 1, nativeDialCount() - dialsAtRetry);
        assertEquals("no leaked or doubled native transport after Retry", transportsBefore, nativeConnectionCount());
        awaitJsTrue("!document.querySelector('.link-lost')", WAIT_TIMEOUT_MILLIS);

        JSONObject live = latestLiveJournalEntry();
        assertEquals("Retry re-attached the same aplexer session", sessionId, live.getString("selectedId"));
        String marker = "PS2954_RETRY_42_" + run;
        typeLine("echo PS2954_RETRY_$((6*7))_" + run);
        awaitTerminalLine(marker);
        captureScreen(new File(artifacts, "retried.png"));
        JSONObject capture = awaitNativeExec("u1-retry-capture-" + run, live.getString("transportId"),
                live.getString("generationId"), "/usr/bin/a capture --bytes 65536 " + shellQuote(sessionId));
        assertEquals("host capture after Retry must succeed: " + capture, 0, capture.getInt("exitCode"));
        assertEquals("the host session holds the post-Retry marker exactly once", 1,
                countExactLines(capture.getString("stdout"), marker));
        return new JSONObject()
                .put("laddersBeforeRetry", laddersBeforeRetry)
                .put("retryLadders", retryLadders)
                .put("retryTransports", retryTransports.size())
                .put("dialsWhileGivenUp", dialsWhileGivenUp)
                .put("retryDials", 1)
                .put("giveUpBanner", bannerText)
                .put("hostMarkerLines", 1);
    }

    /**
     * Add the fixture host through the Android host route, connect, create a
     * shell session of this run's own (its own folder, so neither the other
     * tests nor earlier lanes share it) and open it.
     */
    /**
     * Open this run's fixture host (shared with the other tests of the run),
     * then create a shell session of this test's own — its own folder, so
     * neither the other tests nor earlier lanes share it — and open it.
     */
    private void connectFixtureAndAttach(String run) throws Exception {
        openFixtureHost(sessionRun());
        awaitJsTrue("document.querySelectorAll('.dir-header').length > 0");
        String folder = "ps-shared-" + run;
        JSONObject transport = new JSONObject(evalString("JSON.stringify([...(window.__pocketshellConnectionJournal ?? [])]"
                + ".reverse().find((e)=>e.transportId && e.generationId) ?? null)"));
        JSONObject created = awaitNativeExec("create-" + run, transport.getString("transportId"),
                transport.getString("generationId"), "set -eu\nmkdir -p \"$HOME/" + folder + "\"\n"
                + "PATH=\"$HOME/.local/bin:$PATH\" pocketshell sessions create --json --cwd \"$HOME/" + folder + "\" -- "
                + shellQuote("shell-" + run) + " >/dev/null");
        assertEquals("the journey's own shell session must be created: " + created, 0, created.getInt("exitCode"));
        awaitFolder(folder);
        openFolder(folder);
        // Attach: the controller's PTY paints a prompt into the shared pane.
        awaitJsTrue(VISIBLE_TERMINAL + ".includes('$')");
    }

    /**
     * Record, from before the connect: every native `connectionState` event
     * (the plugin's own listener, not the app's), the shared lost-link
     * banner's text each time it changes, and native exec results.
     */
    private void installRecoveryRecorder() throws Exception {
        String script = "(() => {window.__ps2954={lost:[],events:[],banners:[],dials:[],sawExited:false,execs:{},ready:false};"
                + "const plugin=window.Capacitor?.Plugins?.SshCapability;"
                // Every native dial, failed ones included: the plugin proxy
                // resolves Capacitor.nativePromise on each call.
                + "const nativePromise=window.Capacitor.nativePromise.bind(window.Capacitor);"
                + "window.Capacitor.nativePromise=(plugin,method,options)=>{"
                + "if(plugin==='SshCapability'&&method==='connect') window.__ps2954.dials.push({at:Date.now()});"
                + "return nativePromise(plugin,method,options);};"
                + "if(!plugin?.addListener) throw new Error('SSH native event bridge missing');"
                + "const sample=()=>{const t=document.querySelector('.link-lost-text')?.textContent?.trim();"
                + "if(t && window.__ps2954.banners.at(-1)!==t) window.__ps2954.banners.push(t);"
                + "if([...document.querySelectorAll('.xterm-rows')].some((n)=>n.textContent.includes('[process exited]')))"
                + " window.__ps2954.sawExited=true;};"
                + "new MutationObserver(sample).observe(document.body,{subtree:true,childList:true,characterData:true});"
                + "plugin.addListener('connectionState',(e)=>{window.__ps2954.events.push({...e,at:Date.now()});"
                + "if(e.state==='lost') window.__ps2954.lost.push({...e,at:Date.now()});})"
                + ".then(()=>{window.__ps2954.ready=true;}); return 'installed';})()";
        assertEquals("installed", evalString(script));
        awaitJsTrue("window.__ps2954?.ready === true", 10_000);
    }

    private JSONObject latestLiveJournalEntry() throws Exception {
        String raw = evalString("JSON.stringify([...(window.__pocketshellConnectionJournal ?? [])].reverse()"
                + ".find((e)=>e.phase==='live' && e.transportId && e.selectedId) ?? null)");
        assertTrue("the Android connection journal must record a live attach", raw != null && !"null".equals(raw));
        return new JSONObject(raw);
    }

    /** Native SSH dials (connect calls at the Capacitor bridge) since the recorder was installed. */
    private int nativeDialCount() throws Exception {
        return Integer.parseInt(evalString("String(window.__ps2954.dials.length)"));
    }

    private int journalLength() throws Exception {
        return Integer.parseInt(evalString("String((window.__pocketshellConnectionJournal ?? []).length)"));
    }

    /** Open native SSH connections, from the plugin itself. */
    private int nativeConnectionCount() throws Exception {
        String request = "u1-resources-" + SystemClock.uptimeMillis();
        evalString("(() => {window.__ps2954.execs[" + JSONObject.quote(request) + "]={settled:false};"
                + "window.Capacitor.Plugins.SshCapability.resourceSnapshot({requestId:" + JSONObject.quote(request) + "})"
                + ".then((r)=>{window.__ps2954.execs[" + JSONObject.quote(request) + "]={settled:true,result:r};},"
                + "(e)=>{window.__ps2954.execs[" + JSONObject.quote(request) + "]={settled:true,error:String(e?.message??e)};});"
                + "return 'started';})()");
        awaitJsTrue("window.__ps2954.execs[" + JSONObject.quote(request) + "].settled === true", 10_000);
        JSONObject state = new JSONObject(evalString("JSON.stringify(window.__ps2954.execs[" + JSONObject.quote(request) + "])"));
        JSONObject result = state.optJSONObject("result");
        assertNotNull("native resource snapshot failed: " + state, result);
        return result.getInt("connections");
    }

    private String nativeExec(String requestId, String connectionId, String generationId, String command, int timeoutMs) {
        String key = JSONObject.quote(requestId);
        return "(() => {const entry={settled:false,result:null,error:''};"
                + "(window.__ps2954 ??= {lost:[],banners:[],execs:{},ready:false}).execs[" + key + "]=entry;"
                + "window.Capacitor.Plugins.SshCapability.exec({requestId:" + key
                + ",connectionId:" + JSONObject.quote(connectionId)
                + ",generationId:" + JSONObject.quote(generationId)
                + ",command:" + JSONObject.quote(command)
                + ",timeoutMs:" + timeoutMs + "}).then((r)=>{entry.result=r;entry.settled=true;},"
                + "(e)=>{entry.error=String(e?.message??e);entry.settled=true;});return 'started';})()";
    }

    private JSONObject awaitNativeExec(String requestId, String connectionId, String generationId, String command)
            throws Exception {
        assertEquals("started", evalString(nativeExec(requestId, connectionId, generationId, command, 20_000)));
        awaitJsTrue("window.__ps2954.execs[" + JSONObject.quote(requestId) + "]?.settled === true", 25_000);
        JSONObject state = new JSONObject(evalString("JSON.stringify(window.__ps2954.execs["
                + JSONObject.quote(requestId) + "])"));
        assertEquals("native host exec must not reject: " + state, "", state.optString("error"));
        JSONObject result = state.optJSONObject("result");
        assertNotNull("native host exec result must be present", result);
        assertFalse("native host exec must not time out", result.optBoolean("timedOut"));
        return result;
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static int countExactLines(String raw, String expected) {
        String plain = raw.replaceAll("\u001B\\[[0-?]*[ -/]*[@-~]", "");
        int count = 0;
        for (String line : plain.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            if (expected.equals(line.trim())) count += 1;
        }
        return count;
    }

    private File artifactDirectory(String run) {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root = context.getExternalFilesDir(null);
        assertNotNull("target app external files directory must be available for same-run artifacts", root);
        File directory = new File(root, "pocketshell-shared-app/" + run);
        assertTrue("run artifact directory must be new", directory.mkdirs());
        Log.i(TAG, "ARTIFACTS " + directory.getAbsolutePath());
        return directory;
    }

    private static void writeText(File file, String text) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void captureScreen(File file) throws Exception {
        Bitmap screen = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull("a full-screen capture must be available", screen);
        try (FileOutputStream out = new FileOutputStream(file)) {
            assertTrue(screen.compress(Bitmap.CompressFormat.PNG, 100, out));
        }
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

    /**
     * Wait for an exact output line. A line longer than the phone-width
     * terminal soft-wraps onto the next rows (the runner's session ids vary
     * in length run to run), so a line also matches as a run of consecutive
     * rows that join to exactly it.
     */
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

    /**
     * What the connection layer was doing when a wait failed: the controller
     * journal's tail, the recorder's native events, dials and execs, and the
     * lost-link banner. Logged and put in the failure message, so a CI red is
     * diagnosable from its artifacts alone (#2954).
     */
    private String connectionDiagnostics() {
        try {
            return evalString("JSON.stringify({"
                    + "journal:(window.__pocketshellConnectionJournal ?? []).slice(-40),"
                    + "recorder:window.__ps2954 ? {dials:window.__ps2954.dials.length,lost:window.__ps2954.lost,"
                    + "events:window.__ps2954.events,banners:window.__ps2954.banners,sawExited:window.__ps2954.sawExited,"
                    + "execs:window.__ps2954.execs} : null,"
                    + "banner:document.querySelector('.link-lost-text')?.textContent?.trim() ?? null})");
        } catch (Exception error) {
            return "<diagnostics unavailable: " + error + ">";
        }
    }

    private void awaitJsTrue(String expression) throws Exception {
        awaitJsTrue(expression, WAIT_TIMEOUT_MILLIS);
    }

    private void awaitJsTrue(String expression, long timeoutMillis) throws Exception {
        long deadline = SystemClock.uptimeMillis() + timeoutMillis;
        String last = "<not evaluated>";
        while (SystemClock.uptimeMillis() < deadline) {
            last = evalRaw(expression);
            if ("true".equals(last)) return;
            Thread.sleep(150);
        }
        String diagnostics = connectionDiagnostics();
        Log.e(TAG, "WAIT_FAILED " + expression + " DIAGNOSTICS " + diagnostics);
        throw new AssertionError("WebView condition did not become true: " + expression + " (last result: " + last
                + "; terminal=" + evalString(VISIBLE_TERMINAL) + "; page=" + evalString("document.body.innerText")
                + "; diagnostics=" + diagnostics + ")");
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
