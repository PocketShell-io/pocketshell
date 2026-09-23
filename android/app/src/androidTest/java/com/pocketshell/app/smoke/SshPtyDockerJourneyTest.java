package com.pocketshell.app.smoke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.webkit.WebView;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.pocketshell.app.MainActivity;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Packaged Android journey over a real sshd + aplexer Docker fixture. */
@RunWith(AndroidJUnit4.class)
public final class SshPtyDockerJourneyTest {
    private static final long WAIT_TIMEOUT_MILLIS = 45_000;
    private static final long BACKGROUND_GRACE_MILLIS = 30_000;
    private static final long SCREENSHOT_MARKER_WAIT_MILLIS = 8_000;
    private static final int SCREENSHOT_MARKER_ACCENT_MIN_PIXELS = 4_096;
    private static final int SCREENSHOT_MARKER_ACCENT_TOLERANCE = 12;
    private ActivityScenario<MainActivity> scenario;
    private String activeRunId;
    private JSONObject graceTiming = new JSONObject();

    @Before
    public void launchPackagedShell() {
        scenario = ActivityScenario.launch(MainActivity.class);
    }

    @After
    public void closeShell() {
        if (scenario != null) scenario.close();
    }

    @Test
    public void sshSessionSwitchingAndBackgroundGraceAgainstDockerFixture() throws Exception {
        var arguments = InstrumentationRegistry.getArguments();
        String host = arguments.getString("sshHost", "10.0.2.2");
        String port = arguments.getString("sshPort");
        String encodedKey = arguments.getString("sshPrivateKeyBase64");
        assertNotNull("pass the Docker fixture port with sshPort", port);
        assertNotNull("pass the test-only key with sshPrivateKeyBase64", encodedKey);
        String privateKey = new String(Base64.getDecoder().decode(encodedKey), StandardCharsets.UTF_8);
        String runId = arguments.getString("sshSessionName", "js2861-" + System.currentTimeMillis());
        assertTrue("run ID must be a safe, unique fixture tag prefix", runId.matches("[A-Za-z0-9][A-Za-z0-9_-]{2,38}"));
        activeRunId = runId;
        String sessionA = runId + "-a";
        String sessionB = runId + "-b";
        String sessionC = runId + "-c";
        String markerASwitch = marker(runId, "A_SWITCH");
        String markerBSwitch = marker(runId, "B_SWITCH");
        String markerCSwitch = marker(runId, "C_SWITCH");
        String markerAReturn = marker(runId, "A_RETURN");
        String markerAWithinGrace = marker(runId, "A_WITHIN_GRACE");
        String markerAAfterExpiry = marker(runId, "A_AFTER_EXPIRY");
        File artifactDirectory = new File(
                InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null),
                "pocketshell-lifecycle/" + runId);
        assertTrue("run artifact directory must be new", artifactDirectory.mkdirs());
        org.json.JSONArray checkpoints = new org.json.JSONArray();

        awaitJsTrue("document.querySelector('[data-testid=build-status] > span:nth-child(2)')?.textContent.trim() === 'Build verified'");
        click("[aria-label='Settings']");
        click("[data-testid=open-connection-settings]");
        setValue("[data-testid=setting-background-grace]", Long.toString(BACKGROUND_GRACE_MILLIS));
        awaitJsTrue("document.querySelector('[data-testid=setting-background-grace]')?.value === '" + BACKGROUND_GRACE_MILLIS + "'");
        awaitJsTrue("JSON.parse(localStorage.getItem('pocketshell.js.settings.v1') || '{}').backgroundGraceMs === "
                + BACKGROUND_GRACE_MILLIS);
        click("[aria-label='PocketShell home']");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'home'");

        setValue("[data-testid=ssh-host]", host);
        setValue("[data-testid=ssh-port]", port);
        setValue("[data-testid=ssh-username]", "testuser");
        setValue("[data-testid=ssh-private-key]", privateKey);
        graceTiming.put("sshConnectRequestedEpochMs", System.currentTimeMillis())
                .put("sshConnectRequestedElapsedMs", SystemClock.elapsedRealtime());
        click("[data-testid=ssh-connect]");

        awaitJsTrue("!!document.querySelector('[data-testid=host-key-decision]') || !!document.querySelector('[data-testid=ssh-message]')");
        String fingerprint = evalString("document.querySelector('[data-testid=host-key-fingerprint]')?.textContent.trim() ?? ''");
        assertTrue("the real SSH server must present its SHA-256 host key; fingerprint=" + fingerprint
                + "; visible page=" + evalString("document.body.innerText"),
                fingerprint.startsWith("SHA256:") && fingerprint.length() > 20);
        click("[data-testid=trust-host-key]");
        awaitJsTrue("['connected','listing'].includes(document.querySelector('.app-shell')?.dataset.sshPhase)");
        awaitJsTrue("!!document.querySelector('.app-shell')?.dataset.sshConnectionId");
        graceTiming.put("sshConnectedEpochMs", System.currentTimeMillis())
                .put("sshConnectedElapsedMs", SystemClock.elapsedRealtime());
        awaitJsTrue("!!document.querySelector('[data-testid=refresh-sessions]')");
        installPhaseRecorder(runId);

        createSession(sessionA);
        createSession(sessionB);
        createSession(sessionC);
        JSONObject rowA = findSessionRow(sessionA);
        JSONObject rowB = findSessionRow(sessionB);
        JSONObject rowC = findSessionRow(sessionC);
        assertFalse("A and B must be distinct host sessions", rowA.getString("id").equals(rowB.getString("id")));
        assertFalse("B and C must be distinct host sessions", rowB.getString("id").equals(rowC.getString("id")));
        assertFalse("A and C must be distinct host sessions", rowA.getString("id").equals(rowC.getString("id")));

        JSONObject switchA = attachAndCapture(rowA, "switch-a", markerASwitch, artifactDirectory);
        String originalConnectionId = switchA.getString("connectionId");
        checkpoints.put(switchA);
        JSONObject switchB = attachAndCapture(rowB, "switch-b", markerBSwitch, artifactDirectory);
        checkpoints.put(switchB);
        JSONObject switchC = attachAndCapture(rowC, "switch-c", markerCSwitch, artifactDirectory);
        checkpoints.put(switchC);
        JSONObject switchAReturn = attachAndCapture(rowA, "switch-a-return", markerAReturn, artifactDirectory);
        checkpoints.put(switchAReturn);
        assertEquals("switching sessions must keep the same SSH transport", originalConnectionId, switchB.getString("connectionId"));
        assertEquals("switching sessions must keep the same SSH transport", originalConnectionId, switchC.getString("connectionId"));
        assertEquals("returning to A must keep the same SSH transport", originalConnectionId, switchAReturn.getString("connectionId"));
        String visibleText = visiblePageText().toLowerCase();
        assertFalse("the live session page must not show a disconnect band", visibleText.contains("disconnected"));
        assertFalse("the live session page must not show a closed-connection error", visibleText.contains("connection closed"));

        backgroundAndResumeWithinGrace();
        String connectionAfterGrace = currentConnectionId();
        assertEquals("resume within grace must retain the same native SSH connection", originalConnectionId, connectionAfterGrace);
        JSONObject withinGrace = sendMarkerAndCapture("background-within-grace", markerAWithinGrace, artifactDirectory);
        checkpoints.put(withinGrace);

        int resizeAckBeforeExpiry = terminalResizeStats().getInt("ackCount");
        graceTiming.put("beyondGraceResizeAckBefore", resizeAckBeforeExpiry);
        backgroundBeyondGrace(originalConnectionId);
        try {
            awaitJsTrue("document.querySelector('.app-shell')?.dataset.sshPhase === 'live'"
                    + " && document.querySelector('.app-shell')?.dataset.sshConnectionId !== "
                    + JSONObject.quote(originalConnectionId), 90_000);
        } catch (AssertionError reconnectFailure) {
            try {
                logForegroundReconnectDebug();
            } catch (Exception diagnosticFailure) {
                Log.w("SshPtyDockerJourney", "RUN " + activeRunId + " reconnect failure diagnostics failed: "
                        + diagnosticFailure.getClass().getSimpleName());
            }
            throw reconnectFailure;
        }
        awaitNativeResizeAckAfter(resizeAckBeforeExpiry, "reconnected-after-expiry");
        String reconnectedId = currentConnectionId();
        graceTiming.put("beyondGraceLiveEpochMs", System.currentTimeMillis())
                .put("beyondGraceLiveElapsedMs", SystemClock.elapsedRealtime());
        awaitJsTrue("window.__pocketshellJourney?.bridgeEvents?.some((event) => event.state === 'closed'"
                + " && event.reason === 'grace-expired' && event.connectionId === "
                + JSONObject.quote(originalConnectionId) + ")", 15_000);
        graceTiming.put("beyondGraceExpiryEventObservedAfterForegroundEpochMs", System.currentTimeMillis())
                .put("beyondGraceExpiryEventObservedAfterForegroundElapsedMs", SystemClock.elapsedRealtime());
        logGraceTiming("beyond-grace-expiry-event-observed");
        org.json.JSONArray bridgeEvents = new org.json.JSONArray(evalString("JSON.stringify(window.__pocketshellJourney?.bridgeEvents ?? [])"));
        JSONObject nativeExpiry = findExpiryBridgeEvent(bridgeEvents, originalConnectionId);
        assertNotNull("foreground return must deliver the queued native grace-expired event", nativeExpiry);
        long nativeDeadlineElapsedMs = nativeExpiry.getLong("nativeGraceDeadlineElapsedRealtimeMs");
        long nativeClosedElapsedMs = nativeExpiry.getLong("nativeClosedAtElapsedRealtimeMs");
        long nativeTransportCloseCompletedElapsedMs = nativeExpiry.getLong("nativeTransportCloseCompletedAtElapsedRealtimeMs");
        assertTrue("native raw SSH socket must be closed before the host sees teardown",
                nativeExpiry.getBoolean("nativeSocketClosedAfterClose"));
        assertEquals("normal grace expiry must not report an SSHJ disconnect error", "",
                nativeExpiry.getString("nativeSshjDisconnectErrorClass"));
        assertEquals("normal grace expiry must not report an SSHJ close error", "",
                nativeExpiry.getString("nativeSshjClientCloseErrorClass"));
        assertEquals("normal grace expiry must not fall back because the cleanup executor rejected work", "",
                nativeExpiry.getString("nativeCleanupExecutorRejectErrorClass"));
        long nativeExpiryDispatchedElapsedMs = nativeExpiry.getLong("nativeGraceExpiryDispatchedAtElapsedRealtimeMs");
        long nativeExpiryDispatchedEpochMs = nativeExpiry.getLong("nativeGraceExpiryDispatchedAtEpochMs");
        graceTiming.put("nativeGraceDeadlineEpochMs", nativeExpiry.getLong("nativeGraceDeadlineEpochMs"))
                .put("nativeGraceDeadlineElapsedRealtimeMs", nativeDeadlineElapsedMs)
                .put("nativeGraceExpiryDispatchedAtEpochMs", nativeExpiryDispatchedEpochMs)
                .put("nativeGraceExpiryDispatchedAtElapsedRealtimeMs", nativeExpiryDispatchedElapsedMs)
                .put("nativeTransportCloseCompletedAtEpochMs", nativeExpiry.getLong("nativeTransportCloseCompletedAtEpochMs"))
                .put("nativeTransportCloseCompletedAtElapsedRealtimeMs", nativeTransportCloseCompletedElapsedMs)
                .put("nativeSocketClosedAfterClose", nativeExpiry.getBoolean("nativeSocketClosedAfterClose"))
                .put("nativeClientSocketDetachedAfterClose", nativeExpiry.getBoolean("nativeClientSocketDetachedAfterClose"))
                .put("nativeClientReportedConnectedAfterClose", nativeExpiry.getBoolean("nativeClientReportedConnectedAfterClose"))
                .put("nativeSshjDisconnectErrorClass", nativeExpiry.getString("nativeSshjDisconnectErrorClass"))
                .put("nativeSshjClientCloseErrorClass", nativeExpiry.getString("nativeSshjClientCloseErrorClass"))
                .put("nativeRawSocketCloseFallbackUsed", nativeExpiry.getBoolean("nativeRawSocketCloseFallbackUsed"))
                .put("nativeRawSocketCloseErrorClass", nativeExpiry.getString("nativeRawSocketCloseErrorClass"))
                .put("nativeCleanupExecutorRejectErrorClass", nativeExpiry.getString("nativeCleanupExecutorRejectErrorClass"));
        assertTrue("native grace event must expose a positive scheduled deadline", nativeDeadlineElapsedMs > 0);
        assertTrue("native close handler must start at or after its monotonic grace deadline",
                nativeClosedElapsedMs >= nativeDeadlineElapsedMs);
        assertTrue("native transport close must complete at or after its monotonic grace deadline",
                nativeTransportCloseCompletedElapsedMs >= nativeDeadlineElapsedMs);
        assertTrue("native transport close must complete before foreground returns",
                nativeTransportCloseCompletedElapsedMs <= graceTiming.getLong("beyondGraceForegroundRequestedElapsedMs"));
        assertTrue("native close handler must start before foreground returns",
                nativeClosedElapsedMs <= graceTiming.getLong("beyondGraceForegroundRequestedElapsedMs"));
        assertTrue("native bridge event must follow transport close completion",
                nativeExpiry.getLong("atEpochMs") >= nativeExpiry.getLong("nativeTransportCloseCompletedAtEpochMs"));
        assertTrue("native deadline must match the selected grace window observed by the journey",
                Math.abs(nativeDeadlineElapsedMs - graceTiming.getLong("beyondGraceDeadlineElapsedMs")) <= 2_000);
        assertTrue("expiry must create a new physical SSH connection", !reconnectedId.isEmpty() && !originalConnectionId.equals(reconnectedId));
        assertEquals("expiry must reattach the same host session", sessionA, currentSelectedTag());
        assertFalse("expiry must replace the SSH transport generation", switchAReturn.getString("generationId").equals(currentGenerationId()));
        sendControlCAndDrain();
        JSONObject afterExpiry = sendMarkerAndCapture("reconnected-after-expiry", markerAAfterExpiry, artifactDirectory);
        checkpoints.put(afterExpiry);

        org.json.JSONArray phaseEvents = new org.json.JSONArray(evalString("JSON.stringify(window.__pocketshellJourney?.phases ?? [])"));
        org.json.JSONArray diagnosticEvents = new org.json.JSONArray(evalString("JSON.stringify(JSON.parse(localStorage.getItem('pocketshell.js.diagnostics.v1') || '[]'))"));
        assertDiagnosticLifecycle(diagnosticEvents);
        JSONObject summary = new JSONObject()
                .put("schema", 1)
                .put("runId", runId)
                .put("graceMs", BACKGROUND_GRACE_MILLIS)
                .put("initialConnectionId", originalConnectionId)
                .put("connectionAfterWithinGrace", connectionAfterGrace)
                .put("expiredConnectionId", originalConnectionId)
                .put("expiryBridgeEventObserved", hasExpiryBridgeEvent(bridgeEvents, originalConnectionId))
                .put("connectionAfterExpiry", reconnectedId)
                .put("graceTiming", graceTiming)
                .put("terminalInputPending", terminalInputStats().getInt("pending"))
                .put("terminalInputAcks", terminalInputStats().getInt("ackCount"))
                .put("terminalInputFailures", terminalInputStats().getInt("failureCount"))
                .put("sessions", new org.json.JSONArray().put(rowA).put(rowB).put(rowC))
                .put("checkpoints", checkpoints)
                .put("phaseEvents", phaseEvents)
                .put("bridgeEvents", bridgeEvents)
                .put("diagnosticEvents", diagnosticEvents);
        writeText(new File(artifactDirectory, "journey-summary.json"), summary.toString(2));
        Log.i("SshPtyDockerJourney", "RUN " + runId + " " + summary);

        click("[data-testid=ssh-disconnect]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.sshPhase === 'idle'");
        awaitJsTrue(
                "['verified', 'failed'].includes(document.querySelector('[data-testid=ssh-resources]')?.dataset.snapshotState ?? '')",
                10_000);
        String snapshotState = evalString("document.querySelector('[data-testid=ssh-resources]')?.dataset.snapshotState ?? ''");
        String snapshotError = evalString("document.querySelector('[data-testid=ssh-message]')?.textContent.trim() ?? ''");
        assertEquals("native resource snapshot must resolve successfully after disconnect; state=" + snapshotState
                + "; error=" + snapshotError, "verified", snapshotState);

        String requestId = evalString("document.querySelector('[data-testid=ssh-resources]')?.dataset.snapshotRequestId ?? ''");
        assertTrue("resource counts must come from the native close snapshot request", requestId.matches("ui-close-[0-9]+"));
        assertEquals("native connection count after close must be zero", "0",
                evalString("document.querySelector('[data-testid=ssh-resource-connections]')?.textContent.trim() ?? ''"));
        assertEquals("native PTY count after close must be zero", "0",
                evalString("document.querySelector('[data-testid=ssh-resource-ptys]')?.textContent.trim() ?? ''"));
        assertEquals("native SFTP count after close must be zero", "0",
                evalString("document.querySelector('[data-testid=ssh-resource-sftp]')?.textContent.trim() ?? ''"));
        assertEquals("native port-forward count after close must be zero", "0",
                evalString("document.querySelector('[data-testid=ssh-resource-forwards]')?.textContent.trim() ?? ''"));
    }

    private void createSession(String tag) throws Exception {
        setValue("[data-testid=new-session-name]", tag);
        click("[data-testid=create-session]");
        awaitJsTrue("Array.from(document.querySelectorAll('[data-session-tag]')).some((node) => node.dataset.sessionTag === "
                + JSONObject.quote(tag) + ")");
    }

    private JSONObject findSessionRow(String tag) throws Exception {
        String value = evalString("(() => {const node = Array.from(document.querySelectorAll('[data-session-tag]'))"
                + ".find((entry) => entry.dataset.sessionTag === " + JSONObject.quote(tag) + ");"
                + "return node ? JSON.stringify({name:node.dataset.sessionName,id:node.dataset.sessionId,"
                + "workspace:node.dataset.sessionWorkspace,tag:node.dataset.sessionTag}) : '';})()");
        assertTrue("the live host list must expose an aplexer identity row for " + tag + ": " + value, !value.isEmpty());
        JSONObject row = new JSONObject(value);
        assertEquals("listed host tag must match the newly created session", tag, row.getString("tag"));
        assertTrue("the host row must expose an immutable aplexer session ID", row.getString("id").matches("[a-f0-9-]{36}"));
        assertTrue("the host row must expose its workspace", row.getString("workspace").startsWith("/"));
        return row;
    }

    private JSONObject attachAndCapture(JSONObject row, String checkpoint, String marker, File artifactDirectory) throws Exception {
        String tag = row.getString("tag");
        JSONObject resizeBefore = terminalResizeStats();
        assertEquals("no native terminal resize may be pending before " + checkpoint, 0,
                resizeBefore.getInt("pending"));
        click("[data-session-tag=\"" + tag + "\"]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.sshPhase === 'live'"
                + " && document.querySelector('.app-shell')?.dataset.sshSelectedTag === " + JSONObject.quote(tag));
        assertEquals("selected session identity must come from the live host row", row.getString("name"), selectedSessionName());
        assertEquals("selected session ID must come from the live host row", row.getString("id"), selectedSessionId());
        assertEquals("selected workspace must come from the live host row", row.getString("workspace"), selectedWorkspace());
        awaitNativeResizeAckAfter(resizeBefore.getInt("ackCount"), checkpoint);
        awaitTerminalReady();
        JSONObject checkpointData = sendMarkerAndCapture(checkpoint, marker, artifactDirectory);
        Log.i("SshPtyDockerJourney", "RUN " + row.getString("tag") + " CHECKPOINT " + checkpointData);
        return checkpointData;
    }

    private JSONObject sendMarkerAndCapture(String checkpoint, String marker, File artifactDirectory) throws Exception {
        int terminalColumns = currentTerminalColumns();
        assertTrue("remote output marker must fit one terminal row for " + checkpoint + " (columns="
                + terminalColumns + "): " + marker, marker.length() <= terminalColumns);
        JSONObject before = terminalInputStats();
        assertEquals("no terminal input may be pending before " + checkpoint, 0, before.getInt("pending"));
        assertEquals("terminal input failures must remain zero before " + checkpoint, 0, before.getInt("failureCount"));
        awaitTerminalReady();
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        int markerAccent = markerAccentColor(marker);
        String markerFormat = String.format(Locale.ROOT,
                "\\033[38;2;0;0;0m\\033[48;2;%d;%d;%dm%%s\\033[0m\\n",
                Color.red(markerAccent), Color.green(markerAccent), Color.blue(markerAccent));
        InstrumentationRegistry.getInstrumentation().sendStringSync(
                "printf '" + markerFormat + "' '" + marker + "'\n");
        awaitExactMarkerRow(marker, checkpoint);
        waitForTerminalInputDrain(before.getInt("ackCount"), before.getInt("failureCount"), checkpoint);
        JSONObject checkpointData = captureCurrent(checkpoint, marker, artifactDirectory);
        Log.i("SshPtyDockerJourney", "RUN " + activeRunId + " ACK_DRAIN " + checkpoint
                + " " + terminalInputStats());
        return checkpointData;
    }

    private void awaitTerminalReady() throws Exception {
        awaitJsTrue("(() => {const viewport=document.querySelector('#terminal-viewport');"
                + "const textarea=viewport?.querySelector('.xterm-helper-textarea');"
                + "if(!viewport||!textarea||viewport.dataset.enabled!=='true') return false;"
                + "textarea.focus(); return document.activeElement===textarea;})()");
    }

    private void awaitExactMarkerRow(String marker, String checkpoint) throws Exception {
        awaitJsTrue("Array.from(document.querySelectorAll('#terminal-viewport .xterm-rows > div'))"
                + ".some((row) => row.textContent.replaceAll(String.fromCharCode(160), ' ').trim() === "
                + JSONObject.quote(marker) + ")", 30_000);
        assertTrue("terminal marker must occupy one exact output row for " + checkpoint + ": " + terminalViewportText(),
                exactTerminalRow(marker));
    }

    private boolean exactTerminalRow(String marker) throws Exception {
        String exact = evalString("Array.from(document.querySelectorAll('#terminal-viewport .xterm-rows > div'))"
                + ".some((row) => row.textContent.replaceAll(String.fromCharCode(160), ' ').trim() === "
                + JSONObject.quote(marker) + ")");
        return "true".equals(exact);
    }

    private void waitForTerminalInputDrain(int ackBefore, int failureBefore, String checkpoint) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 20_000;
        long stableSince = -1;
        int lastAck = -1;
        while (SystemClock.uptimeMillis() < deadline) {
            JSONObject stats = terminalInputStats();
            assertEquals("terminal input failure while sending " + checkpoint, failureBefore, stats.getInt("failureCount"));
            if (stats.getInt("pending") == 0 && stats.getInt("ackCount") > ackBefore) {
                int ack = stats.getInt("ackCount");
                if (ack == lastAck) {
                    if (stableSince >= 0 && SystemClock.uptimeMillis() - stableSince >= 500) return;
                } else {
                    lastAck = ack;
                    stableSince = SystemClock.uptimeMillis();
                }
            } else {
                stableSince = -1;
                lastAck = -1;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("terminal input did not drain and acknowledge for " + checkpoint
                + "; last stats=" + terminalInputStats());
    }

    private void sendControlCAndDrain() throws Exception {
        awaitTerminalReady();
        JSONObject before = terminalInputStats();
        assertEquals("reconnected terminal must have no pending input before clearing any partial line", 0,
                before.getInt("pending"));
        long now = SystemClock.uptimeMillis();
        KeyEvent interrupt = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, 0, KeyEvent.META_CTRL_ON);
        KeyEvent release = new KeyEvent(now + 1, now + 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_C, 0, KeyEvent.META_CTRL_ON);
        assertTrue("Ctrl-C down event must be injected", InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().injectInputEvent(interrupt, true));
        assertTrue("Ctrl-C up event must be injected", InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().injectInputEvent(release, true));
        waitForTerminalInputDrain(before.getInt("ackCount"), before.getInt("failureCount"), "post-expiry Ctrl-C cleanup");
    }

    private JSONObject captureCurrent(String checkpoint, String marker, File artifactDirectory) throws Exception {
        String selectedName = selectedSessionName();
        evalString("(() => {const main=document.querySelector('.screen-content.home-screen');"
                + "const viewport=document.querySelector('#terminal-viewport'); if(!main||!viewport) throw new Error('live terminal page missing');"
                + "main.scrollTop += viewport.getBoundingClientRect().top-main.getBoundingClientRect().top-8;"
                + "const scroller=viewport.querySelector('.xterm-viewport'); if(scroller) scroller.scrollTop=scroller.scrollHeight;"
                + "return 'scrolled-to-terminal';})()");
        awaitJsTrue("(() => {const main=document.querySelector('.screen-content.home-screen');"
                + "const viewport=document.querySelector('#terminal-viewport'); const title=document.querySelector('#terminal-title');"
                + "const r=viewport?.getBoundingClientRect(),m=main?.getBoundingClientRect();"
                + "return !!r&&!!m&&viewport.dataset.enabled==='true'&&title?.textContent.trim()===" + JSONObject.quote(selectedName)
                + "&&r.top>=m.top-1&&r.bottom<=m.bottom+1&&r.left>=m.left&&r.right<=m.right;})()");
        assertTrue("terminal output marker must be an exact xterm output row for " + checkpoint + ": " + terminalViewportText(),
                exactTerminalRow(marker));
        String text = terminalViewportText();
        JSONObject geometry = new JSONObject(evalString("JSON.stringify((() => {const viewport=document.querySelector('#terminal-viewport');"
                + "const marker=" + JSONObject.quote(marker) + ";const row=Array.from(viewport.querySelectorAll('.xterm-rows > div'))"
                + ".find((entry)=>entry.textContent.replaceAll(String.fromCharCode(160),' ').trim()===marker);"
                + "const r=viewport.getBoundingClientRect(),q=row?.getBoundingClientRect(),style=getComputedStyle(viewport);"
                + "return r&&q?{viewport:{left:r.left,top:r.top,right:r.right,bottom:r.bottom,width:r.width,height:r.height,"
                + "devicePixelRatio:window.devicePixelRatio,backgroundColor:style.backgroundColor},"
                + "marker:{left:q.left,top:q.top,right:q.right,bottom:q.bottom,width:q.width,height:q.height}}:null;})())"));
        JSONObject rect = geometry.getJSONObject("viewport");
        JSONObject markerRect = geometry.getJSONObject("marker");
        assertTrue("terminal viewport must have positive visible dimensions", rect.getDouble("width") > 0 && rect.getDouble("height") > 0);
        assertTrue("exact marker row must fit inside terminal viewport", markerRect.getDouble("left") >= rect.getDouble("left")
                && markerRect.getDouble("top") >= rect.getDouble("top") && markerRect.getDouble("right") <= rect.getDouble("right")
                && markerRect.getDouble("bottom") <= rect.getDouble("bottom"));
        JSONObject screenshot = captureViewportPng(checkpoint, marker, rect, markerRect, artifactDirectory);
        writeText(new File(artifactDirectory, checkpoint + "-visible-terminal.txt"), text);
        JSONObject input = terminalInputStats();
        assertEquals("no pending terminal input may remain at screenshot checkpoint", 0, input.getInt("pending"));
        assertEquals("terminal input failures must remain zero at screenshot checkpoint", 0, input.getInt("failureCount"));
        JSONObject resize = terminalResizeStats();
        assertEquals("no native terminal resize may remain pending at screenshot checkpoint", 0, resize.getInt("pending"));
        assertEquals("native terminal resize failures must remain zero at screenshot checkpoint", 0,
                resize.getInt("failureCount"));
        return new JSONObject()
                .put("checkpoint", checkpoint)
                .put("capturedAtEpochMs", System.currentTimeMillis())
                .put("phase", currentPhase())
                .put("selectedName", selectedSessionName())
                .put("selectedId", selectedSessionId())
                .put("workspace", selectedWorkspace())
                .put("tag", currentSelectedTag())
                .put("connectionId", currentConnectionId())
                .put("generationId", currentGenerationId())
                .put("marker", marker)
                .put("terminalColumns", currentTerminalColumns())
                .put("inputPending", input.getInt("pending"))
                .put("inputAckCount", input.getInt("ackCount"))
                .put("inputFailureCount", input.getInt("failureCount"))
                .put("terminalResizePending", resize.getInt("pending"))
                .put("terminalResizeAckCount", resize.getInt("ackCount"))
                .put("terminalResizeFailureCount", resize.getInt("failureCount"))
                .put("terminalResizeStatus", resize.getString("status"))
                .put("visibleTerminalFile", checkpoint + "-visible-terminal.txt")
                .put("viewportPng", checkpoint + "-viewport.png")
                .put("viewportRect", rect)
                .put("markerRect", markerRect)
                .put("screenshotPixels", screenshot);
    }

    private void backgroundAndResumeWithinGrace() throws Exception {
        int selectedGraceMs = currentGraceSettingMs();
        assertEquals("the selected 30-second setting must be active before backgrounding", BACKGROUND_GRACE_MILLIS, selectedGraceMs);
        long requestEpochMs = System.currentTimeMillis();
        long requestElapsedMs = SystemClock.elapsedRealtime();
        graceTiming.put("selectedGraceMs", selectedGraceMs)
                .put("withinGraceBackgroundRequestedEpochMs", requestEpochMs)
                .put("withinGraceBackgroundRequestedElapsedMs", requestElapsedMs);
        scenario.moveToState(Lifecycle.State.CREATED);
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.sshPhase === 'background'");
        long backgroundEpochMs = System.currentTimeMillis();
        long backgroundElapsedMs = SystemClock.elapsedRealtime();
        graceTiming.put("withinGraceBackgroundEpochMs", backgroundEpochMs)
                .put("withinGraceBackgroundElapsedMs", backgroundElapsedMs);
        logGraceTiming("within-grace-background");
        SystemClock.sleep(1_500);
        long foregroundRequestedEpochMs = System.currentTimeMillis();
        long foregroundRequestedElapsedMs = SystemClock.elapsedRealtime();
        graceTiming.put("withinGraceForegroundRequestedEpochMs", foregroundRequestedEpochMs)
                .put("withinGraceForegroundRequestedElapsedMs", foregroundRequestedElapsedMs);
        scenario.moveToState(Lifecycle.State.RESUMED);
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.sshPhase === 'live'");
        long foregroundEpochMs = System.currentTimeMillis();
        long foregroundElapsedMs = SystemClock.elapsedRealtime();
        graceTiming.put("withinGraceForegroundEpochMs", foregroundEpochMs)
                .put("withinGraceForegroundElapsedMs", foregroundElapsedMs)
                .put("withinGraceElapsedMs", foregroundElapsedMs - backgroundElapsedMs);
        logGraceTiming("within-grace-foreground");
        assertTrue("within-grace return must occur before the selected native deadline",
                foregroundElapsedMs - backgroundElapsedMs < selectedGraceMs);
        SystemClock.sleep(1_000);
    }

    private void backgroundBeyondGrace(String expectedConnectionId) throws Exception {
        int selectedGraceMs = currentGraceSettingMs();
        assertEquals("the selected 30-second setting must remain active before expiry test", BACKGROUND_GRACE_MILLIS, selectedGraceMs);
        assertEquals("the selected grace setting must be the same in both lifecycle phases", selectedGraceMs,
                graceTiming.getInt("selectedGraceMs"));
        long requestEpochMs = System.currentTimeMillis();
        long requestElapsedMs = SystemClock.elapsedRealtime();
        graceTiming.put("beyondGraceBackgroundRequestedEpochMs", requestEpochMs)
                .put("beyondGraceBackgroundRequestedElapsedMs", requestElapsedMs);
        scenario.moveToState(Lifecycle.State.CREATED);
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.sshPhase === 'background'");
        long backgroundEpochMs = System.currentTimeMillis();
        long backgroundElapsedMs = SystemClock.elapsedRealtime();
        long deadlineElapsedMs = backgroundElapsedMs + selectedGraceMs;
        graceTiming.put("beyondGraceBackgroundEpochMs", backgroundEpochMs)
                .put("beyondGraceBackgroundElapsedMs", backgroundElapsedMs)
                .put("beyondGraceDeadlineEpochMs", backgroundEpochMs + selectedGraceMs)
                .put("beyondGraceDeadlineElapsedMs", deadlineElapsedMs)
                .put("expiredConnectionId", expectedConnectionId);
        logGraceTiming("beyond-grace-background");
        // Keep the Activity stopped long enough to observe the host-side close,
        // then prove it stays down before the foreground reconnect begins.
        SystemClock.sleep(selectedGraceMs + 12_000);
        long waitCompleteEpochMs = System.currentTimeMillis();
        long waitCompleteElapsedMs = SystemClock.elapsedRealtime();
        graceTiming.put("beyondGraceWaitCompleteEpochMs", waitCompleteEpochMs)
                .put("beyondGraceWaitCompleteElapsedMs", waitCompleteElapsedMs)
                .put("beyondGraceElapsedMs", waitCompleteElapsedMs - backgroundElapsedMs);
        logGraceTiming("beyond-grace-wait-complete");
        assertTrue("background wait must provide ten seconds for bounded host-side socket close observation",
                waitCompleteElapsedMs >= deadlineElapsedMs + 10_000);
        long foregroundRequestedEpochMs = System.currentTimeMillis();
        long foregroundRequestedElapsedMs = SystemClock.elapsedRealtime();
        graceTiming.put("beyondGraceForegroundRequestedEpochMs", foregroundRequestedEpochMs)
                .put("beyondGraceForegroundRequestedElapsedMs", foregroundRequestedElapsedMs);
        logGraceTiming("beyond-grace-foreground-request");
        scenario.moveToState(Lifecycle.State.RESUMED);
    }

    private void installPhaseRecorder(String runId) throws Exception {
        String script = "(() => {const root=document.querySelector('.app-shell'); if(!root) throw new Error('app shell missing');"
                + "window.__pocketshellJourney={runId:" + JSONObject.quote(runId) + ",phases:[],bridgeEvents:[],bridgeListenerReady:false};"
                + "const sample=()=>{const d=root.dataset; const next={at:Date.now(),phase:d.sshPhase,"
                + "connectionId:d.sshConnectionId,generationId:d.sshGenerationId,selectedName:d.sshSelectedSession,"
                + "selectedId:d.sshSelectedSessionId,workspace:d.sshSelectedWorkspace,tag:d.sshSelectedTag,"
                + "retryAttempt:Number(d.sshRetryAttempt||0)}; const last=window.__pocketshellJourney.phases.at(-1);"
                + "if(!last||Object.keys(next).some((key)=>last[key]!==next[key])) window.__pocketshellJourney.phases.push(next);};"
                + "sample(); window.__pocketshellJourney.observer=new MutationObserver(sample);"
                + "window.__pocketshellJourney.observer.observe(root,{attributes:true});"
                + "const plugin=window.Capacitor?.Plugins?.SshCapability; if(!plugin?.addListener) throw new Error('SSH native event bridge missing');"
                + "plugin.addListener('connectionState',(event)=>window.__pocketshellJourney.bridgeEvents.push({...event,atEpochMs:Date.now()}))"
                + ".then(()=>window.__pocketshellJourney.bridgeListenerReady=true); return 'installed';})()";
        assertEquals("installed", evalString(script));
        awaitJsTrue("window.__pocketshellJourney?.bridgeListenerReady === true", 10_000);
    }

    private int currentGraceSettingMs() throws Exception {
        String raw = evalString("localStorage.getItem('pocketshell.js.settings.v1') ?? ''");
        assertTrue("persisted application settings must be readable", raw != null && !raw.isEmpty());
        return new JSONObject(raw).getInt("backgroundGraceMs");
    }

    private void logGraceTiming(String phase) {
        Log.i("SshPtyDockerJourney", "RUN " + activeRunId + " GRACE_TIMING " + phase + " " + graceTiming);
    }

    private void logForegroundReconnectDebug() throws Exception {
        String state = evalString("JSON.stringify((()=>{const root=document.querySelector('.app-shell');"
                + "const d=root?.dataset??{};"
                + "return {visibility:document.visibilityState,phase:d.sshPhase,connectionId:d.sshConnectionId,"
                + "generationId:d.sshGenerationId,selectedTag:d.sshSelectedTag,retryAttempt:d.sshRetryAttempt};})())");
        Log.i("SshPtyDockerJourney", "RUN " + activeRunId + " FOREGROUND_RECONNECT_DEBUG " + state);
        org.json.JSONArray phases = new org.json.JSONArray(evalString("JSON.stringify((window.__pocketshellJourney?.phases??[])"
                + ".slice(-20).map(({at,phase,connectionId,retryAttempt})=>({at,phase,connectionId,retryAttempt})))"));
        for (int index = 0; index < phases.length(); index += 1) {
            Log.i("SshPtyDockerJourney", "RUN " + activeRunId + " FOREGROUND_RECONNECT_PHASE "
                    + phases.getJSONObject(index));
        }
        org.json.JSONArray bridgeEvents = new org.json.JSONArray(evalString("JSON.stringify((window.__pocketshellJourney?.bridgeEvents??[])"
                + ".map(({connectionId,generationId,state,reason,atEpochMs,nativeSocketClosedAfterClose,"
                + "nativeTransportCloseCompletedAtElapsedRealtimeMs,nativeSshjDisconnectErrorClass,"
                + "nativeSshjClientCloseErrorClass,nativeCleanupExecutorRejectErrorClass})=>({connectionId,generationId,state,"
                + "reason,atEpochMs,nativeSocketClosedAfterClose,nativeTransportCloseCompletedAtElapsedRealtimeMs,"
                + "nativeSshjDisconnectErrorClass,nativeSshjClientCloseErrorClass,nativeCleanupExecutorRejectErrorClass})))"));
        for (int index = 0; index < bridgeEvents.length(); index += 1) {
            Log.i("SshPtyDockerJourney", "RUN " + activeRunId + " FOREGROUND_RECONNECT_BRIDGE "
                    + bridgeEvents.getJSONObject(index));
        }
        org.json.JSONArray lifecycle = new org.json.JSONArray(evalString("JSON.stringify(JSON.parse(localStorage.getItem('pocketshell.js.diagnostics.v1')||'[]')"
                + ".filter(({kind})=>kind==='app-backgrounded'||kind==='app-foregrounded')"
                + ".map(({atEpochMs,kind,source,result})=>({atEpochMs,kind,source,result})).slice(-12))"));
        for (int index = 0; index < lifecycle.length(); index += 1) {
            Log.i("SshPtyDockerJourney", "RUN " + activeRunId + " FOREGROUND_RECONNECT_LIFECYCLE "
                    + lifecycle.getJSONObject(index));
        }
    }

    private boolean hasExpiryBridgeEvent(org.json.JSONArray events, String connectionId) throws Exception {
        return findExpiryBridgeEvent(events, connectionId) != null;
    }

    private JSONObject findExpiryBridgeEvent(org.json.JSONArray events, String connectionId) throws Exception {
        for (int index = 0; index < events.length(); index += 1) {
            JSONObject event = events.getJSONObject(index);
            if ("closed".equals(event.optString("state")) && "grace-expired".equals(event.optString("reason"))
                    && connectionId.equals(event.optString("connectionId"))) return event;
        }
        return null;
    }

    private void assertDiagnosticLifecycle(org.json.JSONArray events) throws Exception {
        int backgrounded = 0;
        int foregrounded = 0;
        for (int index = 0; index < events.length(); index += 1) {
            JSONObject event = events.getJSONObject(index);
            if ("app-backgrounded".equals(event.optString("kind")) && "lifecycle".equals(event.optString("operation"))) backgrounded += 1;
            if ("app-foregrounded".equals(event.optString("kind")) && "lifecycle".equals(event.optString("operation"))) foregrounded += 1;
        }
        assertTrue("both requested app backgrounds must reach the lifecycle log", backgrounded >= 2);
        assertTrue("both foreground returns must reach the lifecycle log", foregrounded >= 2);
    }

    private JSONObject captureViewportPng(
            String checkpoint, String marker, JSONObject rect, JSONObject markerRect, File artifactDirectory
    ) throws Exception {
        AtomicReference<int[]> bounds = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            android.view.View decor = activity.getWindow().getDecorView();
            WebView view = decor instanceof WebView ? (WebView) decor : findWebView((android.view.ViewGroup) decor);
            assertNotNull("the packaged activity must contain its Capacitor WebView", view);
            int[] location = new int[2];
            view.getLocationOnScreen(location);
            float scale = (float) rect.optDouble("devicePixelRatio");
            assertTrue("WebView device pixel ratio must be finite and positive", scale > 0 && Float.isFinite(scale));
            bounds.set(new int[] {
                    location[0] + Math.round((float) rect.optDouble("left") * scale),
                    location[1] + Math.round((float) rect.optDouble("top") * scale),
                    location[0] + Math.round((float) rect.optDouble("right") * scale),
                    location[1] + Math.round((float) rect.optDouble("bottom") * scale),
            });
            latch.countDown();
        });
        assertTrue("WebView viewport bounds callback timed out", latch.await(10, TimeUnit.SECONDS));
        int[] crop = bounds.get();
        assertNotNull("viewport crop coordinates must be recorded", crop);
        int expectedWidth = Math.round((float) rect.optDouble("width") * (float) rect.optDouble("devicePixelRatio"));
        int expectedHeight = Math.round((float) rect.optDouble("height") * (float) rect.optDouble("devicePixelRatio"));
        assertTrue("viewport crop width must match CSS bounds at WebView DPR", Math.abs((crop[2] - crop[0]) - expectedWidth) <= 1);
        assertTrue("viewport crop height must match CSS bounds at WebView DPR", Math.abs((crop[3] - crop[1]) - expectedHeight) <= 1);
        int markerLeft = crop[0] + Math.round((float) (markerRect.optDouble("left") - rect.optDouble("left"))
                * (float) rect.optDouble("devicePixelRatio"));
        int markerTop = crop[1] + Math.round((float) (markerRect.optDouble("top") - rect.optDouble("top"))
                * (float) rect.optDouble("devicePixelRatio"));
        int markerRight = crop[0] + Math.round((float) (markerRect.optDouble("right") - rect.optDouble("left"))
                * (float) rect.optDouble("devicePixelRatio"));
        int markerBottom = crop[1] + Math.round((float) (markerRect.optDouble("bottom") - rect.optDouble("top"))
                * (float) rect.optDouble("devicePixelRatio"));
        assertTrue("marker row must map inside the physical viewport crop", markerLeft >= crop[0] && markerTop >= crop[1]
                && markerRight <= crop[2] && markerBottom <= crop[3] && markerRight > markerLeft && markerBottom > markerTop);
        int markerAccent = markerAccentColor(marker);
        Bitmap full = takeScreenshotWhenMarkerIsPainted(
                markerLeft, markerTop, markerRight, markerBottom, checkpoint, markerAccent);
        assertTrue("viewport crop must stay within the captured device image", crop[0] >= 0 && crop[1] >= 0
                && crop[2] <= full.getWidth() && crop[3] <= full.getHeight() && crop[2] > crop[0] && crop[3] > crop[1]);
        int markerAccentPixels = countPixelsNearColor(full, markerLeft, markerTop, markerRight, markerBottom,
                markerAccent, SCREENSHOT_MARKER_ACCENT_TOLERANCE);
        assertTrue("captured marker row must contain the ANSI accent painted by current terminal output",
                markerAccentPixels >= SCREENSHOT_MARKER_ACCENT_MIN_PIXELS);
        int sampleX = crop[2] - Math.max(2, Math.round(4 * (float) rect.optDouble("devicePixelRatio")));
        int sampleY = crop[3] - Math.max(2, Math.round(4 * (float) rect.optDouble("devicePixelRatio")));
        int backgroundPixel = full.getPixel(sampleX, sampleY);
        int[] expectedBackground = parseRgb(rect.optString("backgroundColor"));
        assertTrue("captured viewport background pixel must match WebView terminal CSS background: css="
                + rect.optString("backgroundColor") + ", pixel=" + colorString(backgroundPixel),
                colorNear(backgroundPixel, expectedBackground, 32));
        JSONObject pixelEvidence = new JSONObject()
                .put("devicePixelRatio", rect.optDouble("devicePixelRatio"))
                .put("cropWidth", crop[2] - crop[0])
                .put("cropHeight", crop[3] - crop[1])
                .put("markerAccentColor", colorString(markerAccent))
                .put("markerAccentPixels", markerAccentPixels)
                .put("markerAccentTolerance", SCREENSHOT_MARKER_ACCENT_TOLERANCE)
                .put("backgroundRgb", colorString(backgroundPixel));
        Bitmap viewport = Bitmap.createBitmap(full, crop[0], crop[1], crop[2] - crop[0], crop[3] - crop[1]);
        full.recycle();
        File png = new File(artifactDirectory, checkpoint + "-viewport.png");
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try {
            assertTrue("viewport bitmap must encode as PNG", viewport.compress(Bitmap.CompressFormat.PNG, 100, encoded));
        } finally {
            viewport.recycle();
        }
        byte[] pngBytes = encoded.toByteArray();
        try (FileOutputStream output = new FileOutputStream(png)) {
            output.write(pngBytes);
        }
        assertTrue("viewport PNG must be non-empty", png.isFile() && png.length() > 100);
        emitArtifact(png.getName(), pngBytes);
        return pixelEvidence;
    }

    private Bitmap takeScreenshotWhenMarkerIsPainted(
            int left, int top, int right, int bottom, String checkpoint, int expectedAccent
    ) throws Exception {
        long deadline = SystemClock.uptimeMillis() + SCREENSHOT_MARKER_WAIT_MILLIS;
        int lastAccentPixels = 0;
        while (SystemClock.uptimeMillis() < deadline) {
            Bitmap frame = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            assertNotNull("Android must provide a same-run viewport screenshot", frame);
            if (left >= 0 && top >= 0 && right <= frame.getWidth() && bottom <= frame.getHeight()
                    && right > left && bottom > top) {
                lastAccentPixels = countPixelsNearColor(frame, left, top, right, bottom,
                        expectedAccent, SCREENSHOT_MARKER_ACCENT_TOLERANCE);
                if (lastAccentPixels >= SCREENSHOT_MARKER_ACCENT_MIN_PIXELS) return frame;
            }
            frame.recycle();
            waitForNextWebViewFrame();
            Thread.sleep(50);
        }
        throw new AssertionError("Android screenshot never painted the current terminal marker row for " + checkpoint
                + " (accent pixels=" + lastAccentPixels + ", required=" + SCREENSHOT_MARKER_ACCENT_MIN_PIXELS + ")");
    }

    private void waitForNextWebViewFrame() throws Exception {
        CountDownLatch frameReady = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            android.view.View decor = activity.getWindow().getDecorView();
            WebView view = decor instanceof WebView ? (WebView) decor : findWebView((android.view.ViewGroup) decor);
            assertNotNull("the packaged activity must contain its Capacitor WebView", view);
            view.postInvalidateOnAnimation();
            Choreographer.getInstance().postFrameCallback(frameTimeNanos -> frameReady.countDown());
        });
        assertTrue("WebView did not reach a bounded presentation frame", frameReady.await(5, TimeUnit.SECONDS));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private int countPixelsNearColor(Bitmap bitmap, int left, int top, int right, int bottom, int expected, int tolerance) {
        int count = 0;
        int expectedRed = Color.red(expected);
        int expectedGreen = Color.green(expected);
        int expectedBlue = Color.blue(expected);
        for (int y = top; y < bottom; y += 1) {
            for (int x = left; x < right; x += 1) {
                int pixel = bitmap.getPixel(x, y);
                if (Math.abs(Color.red(pixel) - expectedRed) <= tolerance
                        && Math.abs(Color.green(pixel) - expectedGreen) <= tolerance
                        && Math.abs(Color.blue(pixel) - expectedBlue) <= tolerance) {
                    count += 1;
                }
            }
        }
        return count;
    }

    private int markerAccentColor(String marker) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(marker.getBytes(StandardCharsets.UTF_8));
        int[] rgb = new int[] {
                24 + (digest[2] & 0x3f),
                24 + (digest[3] & 0x3f),
                24 + (digest[4] & 0x3f),
        };
        int strongChannel = (digest[0] & 0xff) % 3;
        rgb[strongChannel] = 240 + (digest[1] & 0x0f);
        return Color.rgb(rgb[0], rgb[1], rgb[2]);
    }

    private int[] parseRgb(String color) {
        Matcher matcher = Pattern.compile("rgba?\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)").matcher(color);
        assertTrue("terminal background must be reported as rgb/rgba", matcher.find());
        return new int[] {Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3))};
    }

    private boolean colorNear(int actual, int[] expected, int tolerance) {
        return Math.abs(Color.red(actual) - expected[0]) <= tolerance
                && Math.abs(Color.green(actual) - expected[1]) <= tolerance
                && Math.abs(Color.blue(actual) - expected[2]) <= tolerance;
    }

    private String colorString(int color) {
        return Color.red(color) + "," + Color.green(color) + "," + Color.blue(color);
    }

    private void writeText(File file, String contents) throws Exception {
        byte[] bytes = contents.getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
        }
        emitArtifact(file.getName(), bytes);
    }

    private void emitArtifact(String name, byte[] bytes) throws Exception {
        String encoded = Base64.getEncoder().encodeToString(bytes);
        int chunkSize = 1_800;
        int chunks = (encoded.length() + chunkSize - 1) / chunkSize;
        String sha256 = hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        Log.i("PocketshellJourneyAsset", "BEGIN|" + activeRunId + "|" + name + "|" + chunks + "|" + sha256);
        for (int index = 0; index < chunks; index += 1) {
            int start = index * chunkSize;
            int end = Math.min(encoded.length(), start + chunkSize);
            Log.i("PocketshellJourneyAsset", "DATA|" + activeRunId + "|" + name + "|" + index + "|" + encoded.substring(start, end));
            Thread.sleep(15);
        }
        Log.i("PocketshellJourneyAsset", "END|" + activeRunId + "|" + name);
    }

    private String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private String marker(String runId, String session) {
        String phase;
        switch (session) {
            case "A_SWITCH": phase = "AS"; break;
            case "B_SWITCH": phase = "BS"; break;
            case "C_SWITCH": phase = "CS"; break;
            case "A_RETURN": phase = "AR"; break;
            case "A_WITHIN_GRACE": phase = "AG"; break;
            case "A_AFTER_EXPIRY": phase = "AE"; break;
            default: throw new IllegalArgumentException("unknown lifecycle marker phase: " + session);
        }
        try {
            String runToken = hex(MessageDigest.getInstance("SHA-256").digest(runId.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 10).toUpperCase();
            return "REMOTE_OUTPUT_" + runToken + "_" + phase;
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 is required to create unique lifecycle markers", error);
        }
    }

    private int currentTerminalColumns() throws Exception {
        String status = evalString("document.querySelector('[data-testid=terminal-resize-status]')?.textContent.trim() ?? ''");
        Matcher grid = Pattern.compile("(\\d+)\\s*[×x]\\s*\\d+\\s+accepted by SSH").matcher(status);
        assertTrue("terminal resize status must expose the accepted PTY column count: " + status, grid.find());
        return Integer.parseInt(grid.group(1));
    }

    private String terminalViewportText() throws Exception {
        return evalString("Array.from(document.querySelectorAll('#terminal-viewport .xterm-rows > div'))"
                + ".map((row)=>row.textContent.replaceAll(String.fromCharCode(160),' ').trim()).join('\\n')");
    }

    private JSONObject terminalInputStats() throws Exception {
        return new JSONObject(evalString("JSON.stringify((()=>{const root=document.querySelector('.app-shell');"
                + "return root?{pending:Number(root.dataset.sshTerminalInputPending||0),"
                + "ackCount:Number(root.dataset.sshTerminalInputAcks||0),"
                + "failureCount:Number(root.dataset.sshTerminalInputFailures||0)}:null;})())"));
    }

    private JSONObject terminalResizeStats() throws Exception {
        return new JSONObject(evalString("JSON.stringify((()=>{const root=document.querySelector('.app-shell');"
                + "return root?{pending:Number(root.dataset.sshTerminalResizePending||0),"
                + "ackCount:Number(root.dataset.sshTerminalResizeAcks||0),"
                + "failureCount:Number(root.dataset.sshTerminalResizeFailures||0),"
                + "status:document.querySelector('[data-testid=terminal-resize-status]')?.textContent.trim()||''}:null;})())"));
    }

    private void awaitNativeResizeAckAfter(int previousAckCount, String checkpoint) throws Exception {
        awaitJsTrue("(() => {const root=document.querySelector('.app-shell');"
                + "const status=document.querySelector('[data-testid=terminal-resize-status]')?.textContent.trim()||'';"
                + "return !!root && Number(root.dataset.sshTerminalResizeAcks||0) > " + previousAckCount
                + " && Number(root.dataset.sshTerminalResizePending||0) === 0"
                + " && Number(root.dataset.sshTerminalResizeFailures||0) === 0"
                + " && status.endsWith('accepted by SSH');})()", WAIT_TIMEOUT_MILLIS);
        JSONObject resize = terminalResizeStats();
        assertEquals(checkpoint + " must finish with no pending native resize", 0, resize.getInt("pending"));
        assertTrue(checkpoint + " must have a fresh native resize acknowledgement",
                resize.getInt("ackCount") > previousAckCount);
        assertEquals(checkpoint + " must not report a native resize failure", 0, resize.getInt("failureCount"));
    }

    private String visiblePageText() throws Exception { return evalString("document.body.innerText"); }
    private String currentPhase() throws Exception { return evalString("document.querySelector('.app-shell')?.dataset.sshPhase ?? ''"); }
    private String currentConnectionId() throws Exception { return evalString("document.querySelector('.app-shell')?.dataset.sshConnectionId ?? ''"); }
    private String currentGenerationId() throws Exception { return evalString("document.querySelector('.app-shell')?.dataset.sshGenerationId ?? ''"); }
    private String selectedSessionName() throws Exception { return evalString("document.querySelector('.app-shell')?.dataset.sshSelectedSession ?? ''"); }
    private String selectedSessionId() throws Exception { return evalString("document.querySelector('.app-shell')?.dataset.sshSelectedSessionId ?? ''"); }
    private String selectedWorkspace() throws Exception { return evalString("document.querySelector('.app-shell')?.dataset.sshSelectedWorkspace ?? ''"); }
    private String currentSelectedTag() throws Exception { return evalString("document.querySelector('.app-shell')?.dataset.sshSelectedTag ?? ''"); }

    private void setValue(String selector, String value) throws Exception {
        evalString("(() => {const node = document.querySelector(" + JSONObject.quote(selector) + ");"
                + "if (!node) throw new Error('missing ' + " + JSONObject.quote(selector) + ");"
                + "node.value = " + JSONObject.quote(value) + ";"
                + "node.dispatchEvent(new Event('input', {bubbles: true}));"
                + "node.dispatchEvent(new Event('change', {bubbles: true})); return 'set';})()");
    }

    private void click(String selector) throws Exception {
        evalString("(() => {const node = document.querySelector(" + JSONObject.quote(selector) + ");"
                + "if (!node) throw new Error('missing ' + " + JSONObject.quote(selector) + ");"
                + "node.click(); return 'clicked';})()");
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
            Thread.sleep(100);
        }
        throw new AssertionError("WebView condition did not become true: " + expression + " (last result: " + last
                + "; page=" + evalString("document.body.innerText") + ")");
    }

    private String evalString(String expression) throws Exception {
        String raw = evalRaw(expression);
        Object decoded = new JSONTokener(raw).nextValue();
        return decoded == null ? null : decoded.toString();
    }

    private String evalRaw(String expression) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        scenario.onActivity(activity -> {
            WebView webView = null;
            android.view.View decor = activity.getWindow().getDecorView();
            if (decor instanceof WebView) webView = (WebView) decor;
            if (webView == null) webView = findWebView((android.view.ViewGroup) decor);
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

    private static WebView findWebView(android.view.ViewGroup root) {
        for (int index = 0; index < root.getChildCount(); index++) {
            android.view.View child = root.getChildAt(index);
            if (child instanceof WebView) return (WebView) child;
            if (child instanceof android.view.ViewGroup) {
                WebView nested = findWebView((android.view.ViewGroup) child);
                if (nested != null) return nested;
            }
        }
        return null;
    }
}
