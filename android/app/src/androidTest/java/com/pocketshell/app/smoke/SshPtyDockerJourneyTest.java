package com.pocketshell.app.smoke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.InputMethodManager;
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

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
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
    private int nativePluginCallSequence;

    @Before
    public void launchPackagedShell() {
        scenario = ActivityScenario.launch(MainActivity.class);
    }

    @After
    public void closeShell() {
        if (scenario != null) scenario.close();
    }

    @Test
    public void sshSessionSwitchingGraceAndAbruptServerDropReconnectAgainstDockerFixture() throws Exception {
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
        assertPostFocusResizeRaceGuard();
        String sessionA = runId + "-a";
        String sessionB = runId + "-b";
        String sessionC = runId + "-c";
        String markerASwitch = marker("A_SWITCH");
        String markerBSwitch = marker("B_SWITCH");
        String markerCSwitch = marker("C_SWITCH");
        String markerAReturn = marker("A_RETURN");
        String markerAWithinGrace = marker("A_WITHIN_GRACE");
        String markerAAfterExpiry = marker("A_AFTER_EXPIRY");
        android.content.Context targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File externalFilesDirectory = targetContext.getExternalFilesDir(null);
        assertNotNull("target app external files directory must be available for same-run artifacts", externalFilesDirectory);
        File artifactDirectory = new File(externalFilesDirectory, "pocketshell-lifecycle/" + runId);
        assertTrue("run artifact directory must be new", artifactDirectory.mkdirs());
        Log.i("PocketshellJourneyAsset", "DIRECTORY|" + runId + "|" + targetContext.getPackageName()
                + "|" + artifactDirectory.getAbsolutePath());
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

        org.json.JSONArray agentMetadata = new org.json.JSONArray();
        org.json.JSONArray agentMetadataScreenshots = new org.json.JSONArray();
        JSONObject initialA = readAgentUiEvidence("initial-a-unknown", sessionA);
        assertNoRenderedAgentMetadata(initialA, "new plain-shell session A before metadata arrives");
        agentMetadata.put(initialA);
        JSONObject initialB = readAgentUiEvidence("initial-b-unknown", sessionB);
        assertNoRenderedAgentMetadata(initialB, "new plain-shell session B before metadata arrives");
        agentMetadata.put(initialB);
        JSONObject initialUnknownC = readAgentUiEvidence("initial-c-unknown", sessionC);
        assertNoRenderedAgentMetadata(initialUnknownC, "new plain-shell session C");
        agentMetadata.put(initialUnknownC);

        JSONObject agentA = attachSession(rowA, "agent-seed-a");
        // Keep the host's reported value fresh through the A→B→A check, but
        // cap fixture processes so a failed test cannot leave an endless loop.
        submitTerminalCommand("timeout 300 sh -c 'while :; do a state-report waiting >/dev/null 2>&1; sleep 1; done' "
                + ">/dev/null 2>&1 & bash -c 'exec -a claude sleep 300' >/dev/null 2>&1 &", "agent-seed-a");
        refreshSessionListAndWaitForMetadata(sessionA, "claude", "waiting");
        JSONObject lateA = readAgentUiEvidence("late-a-reported", sessionA);
        assertAgentMetadata(lateA, "claude", "waiting", "Claude Code", "Waiting", "late A host metadata");
        assertEquals("late metadata must remain bound to the selected A session", sessionA,
                lateA.getString("selectedTag"));
        agentMetadata.put(lateA);

        JSONObject agentB = attachSession(rowB, "agent-seed-b");
        submitTerminalCommand("timeout 300 sh -c 'while :; do a state-report idle >/dev/null 2>&1; sleep 1; done' "
                + ">/dev/null 2>&1 & bash -c 'exec -a codex sleep 300' >/dev/null 2>&1 &", "agent-seed-b");
        refreshSessionListAndWaitForMetadata(sessionB, "codex", "idle");
        JSONObject listWithMetadata = readAgentUiEvidence("list-a-and-b-reported", sessionB, sessionA);
        assertAgentMetadata(listWithMetadata, "codex", "idle", "Codex", "Idle", "selected B session list");
        assertEquals("the session list must retain A's own identity after B reports metadata", "claude",
                listWithMetadata.getJSONObject("otherSession").getString("agent"));
        agentMetadata.put(listWithMetadata);
        agentMetadataScreenshots.put(captureFullDeviceScreenshot(
                "agent-list-a-b-reported.png", artifactDirectory, sessionB, "sessions-list"));

        JSONObject selectedB = attachSession(agentB, "agent-selected-b");
        JSONObject bChrome = readAgentUiEvidence("selected-b-codex-idle", sessionB);
        assertSelectedSessionChromeAgent(bChrome, "codex", "idle", "Codex", "Idle", "selected B session chrome");
        agentMetadata.put(bChrome);
        agentMetadataScreenshots.put(captureFullDeviceScreenshot(
                "agent-selected-codex-idle.png", artifactDirectory, sessionB, "selected-terminal"));

        JSONObject returnedA = attachSession(agentA, "agent-selected-a-return");
        JSONObject aChrome = readAgentUiEvidence("selected-a-claude-waiting", sessionA);
        assertSelectedSessionChromeAgent(aChrome, "claude", "waiting", "Claude Code", "Waiting", "selected A return chrome");
        agentMetadata.put(aChrome);
        agentMetadataScreenshots.put(captureFullDeviceScreenshot(
                "agent-selected-claude-waiting.png", artifactDirectory, sessionA, "selected-terminal"));

        click("[data-testid=open-sessions]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.homeSurface === 'sessions'");
        awaitJsTrue("['connected','listing','live'].includes(document.querySelector('.app-shell')?.dataset.sshPhase)");

        JSONObject uncertainMutation = createAmbiguousSessionAndReconcile(runId, artifactDirectory);

        JSONObject switchA = attachAndCapture(rowA, "switch-a", markerASwitch, artifactDirectory);
        assertUnchangedViewportFitsAreCoalesced("switch-a");
        String originalConnectionId = switchA.getString("connectionId");
        checkpoints.put(switchA);
        JSONObject switchB = attachAndCapture(rowB, "switch-b", markerBSwitch, artifactDirectory);
        checkpoints.put(switchB);
        JSONObject switchC = attachAndCapture(rowC, "switch-c", markerCSwitch, artifactDirectory);
        JSONObject unknownCChrome = readAgentUiEvidence("selected-c-unknown", sessionC);
        assertNoRenderedAgentMetadata(unknownCChrome, "selected plain-shell session C");
        assertEquals("unknown C metadata must remain bound to C", sessionC,
                unknownCChrome.getString("selectedTag"));
        agentMetadata.put(unknownCChrome);
        agentMetadataScreenshots.put(captureFullDeviceScreenshot(
                "agent-selected-unknown-shell.png", artifactDirectory, sessionC, "selected-terminal"));
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

        String markerAfterAbruptDrop = marker("A_AFTER_ABRUPT_DROP");
        JSONObject abruptTransportDrop = abruptlyDropServerTransportAndRecover(
                runId, rowA, markerAfterAbruptDrop, artifactDirectory);
        JSONObject closeResourcesBeforeDisconnect = openNativeResourcesForClose(runId);

        org.json.JSONArray phaseEvents = new org.json.JSONArray(evalString("JSON.stringify(window.__pocketshellJourney?.phases ?? [])"));
        org.json.JSONArray diagnosticEvents = new org.json.JSONArray(evalString("JSON.stringify(JSON.parse(localStorage.getItem('pocketshell.js.diagnostics.v1') || '[]'))"));
        assertDiagnosticLifecycle(diagnosticEvents);
        JSONObject summary = new JSONObject()
                .put("schema", 1)
                .put("runId", runId)
                .put("sshPort", Integer.parseInt(port))
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
                .put("agentMetadata", agentMetadata)
                .put("agentMetadataScreenshots", agentMetadataScreenshots)
                .put("checkpoints", checkpoints)
                .put("phaseEvents", phaseEvents)
                .put("bridgeEvents", bridgeEvents)
                .put("diagnosticEvents", diagnosticEvents);
        summary.put("abruptTransportDrop", abruptTransportDrop);
        summary.put("uncertainMutation", uncertainMutation);

        String connectionIdBeforeClose = currentConnectionId();
        String generationIdBeforeClose = currentGenerationId();
        long finalDisconnectRequestedEpochMs = System.currentTimeMillis();
        long finalDisconnectRequestedElapsedMs = SystemClock.elapsedRealtime();
        Log.i("SshPtyDockerJourney", "RUN " + runId + " NATIVE_CLOSE_REQUESTED "
                + new JSONObject().put("connectionId", connectionIdBeforeClose)
                .put("generationId", generationIdBeforeClose)
                .put("requestedAtEpochMs", finalDisconnectRequestedEpochMs)
                .put("requestedAtElapsedRealtimeMs", finalDisconnectRequestedElapsedMs)
                .put("localForwardPort", closeResourcesBeforeDisconnect.getInt("localPort")));
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

        JSONObject nativeCloseSnapshot = awaitZeroNativeResourceSnapshot(
                finalDisconnectRequestedElapsedMs + 2_000L, runId);
        long nativeCloseSnapshotVerifiedEpochMs = System.currentTimeMillis();
        long nativeCloseSnapshotVerifiedElapsedMs = SystemClock.elapsedRealtime();
        assertTrue("SSH, PTY, SFTP, forward and native I/O workers must close within two seconds",
                nativeCloseSnapshotVerifiedElapsedMs - finalDisconnectRequestedElapsedMs <= 2_000L);
        assertLocalForwardClosed(closeResourcesBeforeDisconnect.getInt("localPort"));

        // Keep the Docker socket watcher alive for at least a second after the
        // device-side close so the host oracle can require stable zero sockets.
        SystemClock.sleep(1_250L);
        summary.put("nativeResourceClose", new JSONObject()
                .put("connectionId", connectionIdBeforeClose)
                .put("generationId", generationIdBeforeClose)
                .put("disconnectRequestedAtEpochMs", finalDisconnectRequestedEpochMs)
                .put("disconnectRequestedAtElapsedRealtimeMs", finalDisconnectRequestedElapsedMs)
                .put("snapshotVerifiedAtEpochMs", nativeCloseSnapshotVerifiedEpochMs)
                .put("snapshotVerifiedAtElapsedRealtimeMs", nativeCloseSnapshotVerifiedElapsedMs)
                .put("nativeCloseBoundMs", 2_000)
                .put("snapshot", nativeCloseSnapshot)
                .put("forward", new JSONObject()
                        .put("forwardId", closeResourcesBeforeDisconnect.getString("forwardId"))
                        .put("localPort", closeResourcesBeforeDisconnect.getInt("localPort"))
                        .put("sshBannerVerified", closeResourcesBeforeDisconnect.getBoolean("sshBannerVerified"))
                        .put("connectionRefusedAfterClose", true)));
        writeText(new File(artifactDirectory, "journey-summary.json"), summary.toString(2));
        Log.i("SshPtyDockerJourney", "RUN " + runId + " " + summary);
        writeArtifactManifest(artifactDirectory);
    }

    private JSONObject openNativeResourcesForClose(String runId) throws Exception {
        JSONObject connection = new JSONObject(evalString("JSON.stringify((()=>{const root=document.querySelector('.app-shell');"
                + "return {connectionId:root?.dataset.sshConnectionId??'',generationId:root?.dataset.sshGenerationId??''};})())"));
        String connectionId = connection.getString("connectionId");
        String generationId = connection.getString("generationId");
        assertTrue("the final resource-close fixture must start with a live JS-owned connection", !connectionId.isEmpty());
        assertTrue("the final resource-close fixture must have a live JS-owned generation", !generationId.isEmpty());

        JSONObject sftp = callNativePlugin("sftpList", new JSONObject()
                .put("requestId", "close-proof-sftp-" + runId)
                .put("connectionId", connectionId)
                .put("generationId", generationId)
                .put("path", "/home/testuser"));
        assertEquals("the close fixture must create and exercise a real SFTP client",
                "close-proof-sftp-" + runId, sftp.getString("requestId"));
        assertTrue("SFTP listing must return the fixture home directory", sftp.optJSONArray("entries") != null
                && sftp.getJSONArray("entries").length() > 0);

        JSONObject forward = callNativePlugin("openPortForward", new JSONObject()
                .put("requestId", "close-proof-forward-" + runId)
                .put("connectionId", connectionId)
                .put("generationId", generationId)
                .put("remoteHost", "127.0.0.1")
                .put("remotePort", 22)
                .put("localPort", 0));
        assertEquals("the close fixture must create a real SSH local forward",
                "close-proof-forward-" + runId, forward.getString("requestId"));
        int localPort = forward.getInt("localPort");
        assertTrue("native forward must allocate a local port", localPort > 0 && localPort <= 65535);

        boolean sshBannerVerified = verifyLocalForwardReachesSshd(localPort);
        assertTrue("the local native forward must reach the Docker fixture SSH banner", sshBannerVerified);

        JSONObject liveSnapshot = awaitNativeResourceSnapshotWithForward(connectionId, generationId, runId);
        assertTrue("the live close fixture must own a PTY", liveSnapshot.getInt("ptys") > 0);
        assertEquals("the live close fixture must own its SFTP client", 1, liveSnapshot.getInt("sftpClients"));
        assertEquals("the live close fixture must own its local forward", 1, liveSnapshot.getInt("forwards"));
        assertEquals("the listener worker must still be live before disconnect", 1,
                liveSnapshot.getInt("activeForwardWorkers"));
        assertEquals("completed command readers must not linger before disconnect", 0,
                liveSnapshot.getInt("activeExecStreamReaders"));
        Log.i("SshPtyDockerJourney", "RUN " + runId + " NATIVE_CLOSE_RESOURCES_OPEN "
                + new JSONObject().put("connectionId", connectionId)
                .put("generationId", generationId)
                .put("localPort", localPort)
                .put("snapshot", liveSnapshot));

        return new JSONObject()
                .put("forwardId", forward.getString("forwardId"))
                .put("localPort", localPort)
                .put("sshBannerVerified", sshBannerVerified);
    }

    private JSONObject awaitNativeResourceSnapshotWithForward(
            String connectionId, String generationId, String runId) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 10_000L;
        JSONObject latest = null;
        while (SystemClock.elapsedRealtime() < deadline) {
            latest = callNativePlugin("resourceSnapshot", new JSONObject()
                    .put("requestId", "close-proof-live-snapshot-" + runId + "-" + nativePluginCallSequence)
                    .put("connectionId", connectionId)
                    .put("generationId", generationId));
            if (latest.getInt("forwards") == 1 && latest.getInt("activeForwardWorkers") == 1) return latest;
            SystemClock.sleep(100L);
        }
        throw new AssertionError("the real forward listener did not become live in time; snapshot=" + latest);
    }

    private JSONObject awaitZeroNativeResourceSnapshot(long deadlineElapsedMs, String runId) throws Exception {
        JSONObject latest = null;
        while (SystemClock.elapsedRealtime() <= deadlineElapsedMs) {
            latest = callNativePlugin("resourceSnapshot", new JSONObject()
                    .put("requestId", "close-proof-zero-snapshot-" + runId + "-" + nativePluginCallSequence));
            if (latest.getInt("connections") == 0 && latest.getInt("ptys") == 0
                    && latest.getInt("sftpClients") == 0 && latest.getInt("forwards") == 0
                    && latest.getInt("activeForwardWorkers") == 0
                    && latest.getInt("activeExecStreamReaders") == 0) {
                return latest;
            }
            SystemClock.sleep(100L);
        }
        throw new AssertionError("native resources did not reach zero within two seconds; snapshot=" + latest);
    }

    private JSONObject callNativePlugin(String method, JSONObject options) throws Exception {
        int sequence = ++nativePluginCallSequence;
        String callId = "native-close-call-" + sequence;
        String methodsKey = "__pocketshellNativeCloseCalls";
        String start = "(() => {const id=" + JSONObject.quote(callId) + ";"
                + "const calls=window." + methodsKey + "||(window." + methodsKey + "={});"
                + "const plugin=window.Capacitor?.Plugins?.SshCapability;"
                + "if(!plugin||typeof plugin[" + JSONObject.quote(method) + "]!=='function')"
                + "throw new Error('missing native SSH method: '+" + JSONObject.quote(method) + ");"
                + "const args=JSON.parse(" + JSONObject.quote(options.toString()) + ");"
                + "plugin[" + JSONObject.quote(method) + "](args).then(value=>calls[id]={settled:true,value})"
                + ".catch(error=>calls[id]={settled:true,error:String(error?.message??error)});"
                + "return 'started';})()";
        assertEquals("native plugin call must start for " + method, "started", evalString(start));
        awaitJsTrue("window." + methodsKey + "?.[" + JSONObject.quote(callId) + "]?.settled === true", 10_000);
        JSONObject result = new JSONObject(evalString("JSON.stringify(window." + methodsKey + "["
                + JSONObject.quote(callId) + "])"));
        assertFalse("native " + method + " call must resolve instead of reject: " + result, result.has("error"));
        return result.getJSONObject("value");
    }

    private static boolean verifyLocalForwardReachesSshd(int localPort) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), localPort), 2_000);
            socket.setSoTimeout(2_000);
            byte[] banner = new byte[4];
            int offset = 0;
            while (offset < banner.length) {
                int count = socket.getInputStream().read(banner, offset, banner.length - offset);
                if (count < 0) return false;
                offset += count;
            }
            return new String(banner, StandardCharsets.US_ASCII).startsWith("SSH-");
        }
    }

    private static void assertLocalForwardClosed(int localPort) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), localPort), 500);
            throw new AssertionError("native disconnect left the local forwarding listener reachable on " + localPort);
        } catch (java.net.ConnectException expected) {
            // The native listener was physically closed; no server accepts new clients.
        }
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

    private JSONObject attachSession(JSONObject row, String checkpoint) throws Exception {
        String tag = row.getString("tag");
        if (!"sessions".equals(evalString("document.querySelector('.app-shell')?.dataset.homeSurface ?? ''"))) {
            click("[data-testid=open-sessions]");
            awaitJsTrue("document.querySelector('.app-shell')?.dataset.homeSurface === 'sessions'");
        }
        awaitJsTrue("Array.from(document.querySelectorAll('[data-session-tag]')).some((node) => node.dataset.sessionTag === "
                + JSONObject.quote(tag) + ")");
        click("[data-session-tag=\"" + tag + "\"]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.sshPhase === 'live'"
                + " && document.querySelector('.app-shell')?.dataset.sshSelectedTag === " + JSONObject.quote(tag));
        assertEquals("selected session identity must come from the live host row at " + checkpoint,
                row.getString("name"), selectedSessionName());
        assertEquals("selected session ID must come from the live host row at " + checkpoint,
                row.getString("id"), selectedSessionId());
        awaitTerminalReady(checkpoint);
        return row;
    }

    private void submitTerminalCommand(String command, String checkpoint) throws Exception {
        JSONObject before = terminalInputStats();
        assertEquals("no terminal input may be pending before " + checkpoint, 0, before.getInt("pending"));
        pasteTerminalText(command, checkpoint);
        waitForTerminalInputDrain(before.getInt("ackCount"), before.getInt("failureCount"), checkpoint + " command paste");
        JSONObject beforeEnter = terminalInputStats();
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER);
        waitForTerminalInputDrain(beforeEnter.getInt("ackCount"), beforeEnter.getInt("failureCount"), checkpoint + " command Enter");
        SystemClock.sleep(1_500);
    }

    private void refreshSessionListAndWaitForMetadata(String tag, String agent, String state) throws Exception {
        if (!"sessions".equals(evalString("document.querySelector('.app-shell')?.dataset.homeSurface ?? ''"))) {
            click("[data-testid=open-sessions]");
            awaitJsTrue("document.querySelector('.app-shell')?.dataset.homeSurface === 'sessions'");
        }
        click("[data-testid=refresh-sessions]");
        String tagLiteral = JSONObject.quote(tag);
        awaitJsTrue("(() => {const row=Array.from(document.querySelectorAll('[data-session-tag]')).find((node)=>node.dataset.sessionTag==="
                + tagLiteral + ");return !!row&&row.dataset.sessionAgent===" + JSONObject.quote(agent)
                + "&&row.dataset.sessionAgentState===" + JSONObject.quote(state)
                + "&&row.dataset.sessionAgentStateSource==='reported';})()", 20_000);
    }

    private JSONObject readAgentUiEvidence(String stage, String tag) throws Exception {
        return readAgentUiEvidence(stage, tag, null);
    }

    private JSONObject readAgentUiEvidence(String stage, String tag, String otherTag) throws Exception {
        String value = evalString("(() => {const rows=Array.from(document.querySelectorAll('[data-session-tag]'));"
                + "const row=rows.find((node)=>node.dataset.sessionTag===" + JSONObject.quote(tag) + ");"
                + "const shell=document.querySelector('.app-shell');"
                + "const text=(root,testid)=>root?.querySelector('[data-testid='+JSON.stringify(testid)+']')?.textContent.trim()??'';"
                + "const aria=(root,testid)=>root?.querySelector('[data-testid='+JSON.stringify(testid)+']')?.getAttribute('aria-label')??'';"
                + "const context=document.querySelector('.session-context');"
                + "const other=rows.find((node)=>node.dataset.sessionTag==="
                + JSONObject.quote(otherTag == null ? "" : otherTag) + ");"
                + "return JSON.stringify({stage:" + JSONObject.quote(stage) + ",tag:" + JSONObject.quote(tag)
                + ",rowPresent:!!row,rowId:row?.dataset.sessionId??'',agent:row?.dataset.sessionAgent??'',"
                + "state:row?.dataset.sessionAgentState??'',source:row?.dataset.sessionAgentStateSource??'',"
                + "rowIdentity:text(row,'session-agent-identity'),rowState:text(row,'session-agent-state'),"
                + "rowIdentityAria:aria(row,'session-agent-identity'),rowStateAria:aria(row,'session-agent-state'),"
                + "selectedTag:shell?.dataset.sshSelectedTag??'',selectedAgent:shell?.dataset.sshSelectedAgent??'',"
                + "selectedKind:shell?.dataset.sshSelectedAgentKind??'',"
                + "selectedState:shell?.dataset.sshSelectedAgentState??'',selectedSource:shell?.dataset.sshSelectedAgentStateSource??'',"
                + "contextIdentity:text(context,'session-agent-identity'),contextState:text(context,'session-agent-state'),"
                + "contextIdentityAria:aria(context,'session-agent-identity'),contextStateAria:aria(context,'session-agent-state'),"
                + "otherSession:other?{tag:other.dataset.sessionTag??'',agent:other.dataset.sessionAgent??'',"
                + "state:other.dataset.sessionAgentState??'',source:other.dataset.sessionAgentStateSource??'',"
                + "identity:text(other,'session-agent-identity'),stateLabel:text(other,'session-agent-state'),"
                + "identityAria:aria(other,'session-agent-identity'),stateAria:aria(other,'session-agent-state')}:null});})()");
        JSONObject evidence = new JSONObject(value);
        if (!evidence.getBoolean("rowPresent") && tag.equals(evidence.getString("selectedTag"))) {
            evidence.put("agent", evidence.getString("selectedKind"));
            evidence.put("state", evidence.getString("selectedState"));
            evidence.put("source", evidence.getString("selectedSource"));
        }
        evidence.put("atEpochMs", System.currentTimeMillis());
        return evidence;
    }

    private void assertAgentMetadata(JSONObject evidence, String agent, String state, String agentLabel,
            String stateLabel, String context) throws Exception {
        assertTrue(context + " session-list row must be present", evidence.getBoolean("rowPresent"));
        assertEquals(context + " host identity", agent, evidence.getString("agent"));
        assertEquals(context + " host state", state, evidence.getString("state"));
        assertEquals(context + " state must be host-reported", "reported", evidence.getString("source"));
        assertEquals(context + " session-list identity", agentLabel, evidence.getString("rowIdentity"));
        assertEquals(context + " session-list state", stateLabel, evidence.getString("rowState"));
        assertEquals(context + " identity accessibility label", agentLabel + " agent", evidence.getString("rowIdentityAria"));
        assertEquals(context + " state accessibility label", "Agent state: " + stateLabel, evidence.getString("rowStateAria"));
        assertEquals(context + " selected identity", agentLabel, evidence.getString("selectedAgent"));
        assertEquals(context + " selected state", state, evidence.getString("selectedState"));
        assertEquals(context + " selected context identity", agentLabel, evidence.getString("contextIdentity"));
        assertEquals(context + " selected context state", stateLabel, evidence.getString("contextState"));
        assertEquals(context + " selected identity accessibility label", agentLabel + " agent",
                evidence.getString("contextIdentityAria"));
        assertEquals(context + " selected state accessibility label", "Agent state: " + stateLabel,
                evidence.getString("contextStateAria"));
    }

    private void assertNoRenderedAgentMetadata(JSONObject evidence, String context) throws Exception {
        assertEquals(context + " host agent must remain absent", "", evidence.getString("agent"));
        assertFalse(context + " cannot claim a host-reported state without a known agent report",
                "reported".equals(evidence.getString("source")));
        assertEquals(context + " session-list identity affordance must be absent", "", evidence.getString("rowIdentity"));
        assertEquals(context + " session-list state affordance must be absent", "", evidence.getString("rowState"));
        assertEquals(context + " session-list identity accessibility label must be absent", "", evidence.getString("rowIdentityAria"));
        assertEquals(context + " session-list state accessibility label must be absent", "", evidence.getString("rowStateAria"));
        assertEquals(context + " selected identity affordance must be absent", "", evidence.getString("selectedAgent"));
        assertEquals(context + " selected state affordance must be absent", "", evidence.getString("selectedState"));
        assertEquals(context + " selected chrome identity affordance must be absent", "", evidence.getString("contextIdentity"));
        assertEquals(context + " selected chrome state affordance must be absent", "", evidence.getString("contextState"));
        assertEquals(context + " selected chrome identity accessibility label must be absent", "",
                evidence.getString("contextIdentityAria"));
        assertEquals(context + " selected chrome state accessibility label must be absent", "",
                evidence.getString("contextStateAria"));
    }

    private void assertSelectedSessionChromeAgent(JSONObject evidence, String agent, String state, String agentLabel,
            String stateLabel, String context) throws Exception {
        assertEquals(context + " selected tag", evidence.getString("tag"), evidence.getString("selectedTag"));
        assertEquals(context + " selected host identity", agent, evidence.getString("agent"));
        assertEquals(context + " selected host state", state, evidence.getString("state"));
        assertEquals(context + " selected host state source", "reported", evidence.getString("source"));
        assertEquals(context + " selected chrome identity", agentLabel, evidence.getString("selectedAgent"));
        assertEquals(context + " selected chrome state", state, evidence.getString("selectedState"));
        assertEquals(context + " selected app-bar identity", agentLabel, evidence.getString("contextIdentity"));
        assertEquals(context + " selected app-bar state", stateLabel, evidence.getString("contextState"));
        assertEquals(context + " selected app-bar identity accessibility label", agentLabel + " agent",
                evidence.getString("contextIdentityAria"));
        assertEquals(context + " selected app-bar state accessibility label", "Agent state: " + stateLabel,
                evidence.getString("contextStateAria"));
    }

    private JSONObject captureFullDeviceScreenshot(String name, File artifactDirectory, String tag, String surface)
            throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        waitForNextWebViewFrame();
        SystemClock.sleep(150);
        Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull("Android must provide a same-run full-device screenshot for " + name, screenshot);
        int width = screenshot.getWidth();
        int height = screenshot.getHeight();
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try {
            assertTrue("full-device bitmap must encode as PNG for " + name,
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, encoded));
        } finally {
            screenshot.recycle();
        }
        byte[] bytes = encoded.toByteArray();
        File file = new File(artifactDirectory, name);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
        }
        assertTrue("full-device PNG must be non-empty for " + name, file.isFile() && file.length() > 1_000);
        emitArtifact(name, bytes);
        JSONObject result = new JSONObject()
                .put("file", name)
                .put("tag", tag)
                .put("surface", surface)
                .put("width", width)
                .put("height", height)
                .put("bytes", bytes.length)
                .put("sha256", hex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                .put("capturedAtEpochMs", System.currentTimeMillis());
        Log.i("SshPtyDockerJourney", "RUN " + activeRunId + " AGENT_SCREENSHOT " + result);
        return result;
    }

    private JSONObject createAmbiguousSessionAndReconcile(String runId, File artifactDirectory) throws Exception {
        String target = runId + "-uncertain-create";
        String oldConnectionId = currentConnectionId();
        String phaseBeforeCreate = currentPhase();
        assertTrue("uncertain create must start on a connected host transport after session refresh",
                "connected".equals(phaseBeforeCreate) || "listing".equals(phaseBeforeCreate)
                        || "live".equals(phaseBeforeCreate));
        assertTrue("uncertain create must start with a physical SSH connection", !oldConnectionId.isEmpty());

        String prefix = "/tmp/pocketshell-uncertain-mutation-" + target;
        JSONObject arm = awaitNativeSshExecOnCurrentConnection(
                "uncertain-mutation-arm-" + runId,
                ": > " + shellQuote(prefix + ".armed"),
                15_000);
        assertEquals("Docker fixture must arm the exact one-shot uncertain create", 0, arm.getInt("exitCode"));
        assertFalse("Docker fixture arm command must not time out", arm.getBoolean("timedOut"));
        Log.i("SshPtyDockerJourney", "RUN " + runId + " UNCERTAIN_MUTATION_ARMED "
                + new JSONObject().put("target", target).put("connectionId", oldConnectionId));

        setValue("[data-testid=new-session-name]", target);
        String createUiBeforeSubmit = evalString("JSON.stringify({surface:document.querySelector('.app-shell')?.dataset.homeSurface??'',"
                + "phase:document.querySelector('.app-shell')?.dataset.sshPhase??'',"
                + "inputValue:document.querySelector('[data-testid=new-session-name]')?.value??null,"
                + "createDisabled:document.querySelector('[data-testid=create-session]')?.disabled??null,"
                + "warning:document.querySelector('[data-testid=uncertain-mutation]')?.textContent.trim()??'',"
                + "message:document.querySelector('.connection-message')?.textContent.trim()??''})");
        Log.i("SshPtyDockerJourney", "RUN " + runId + " UNCERTAIN_MUTATION_UI_BEFORE_SUBMIT " + createUiBeforeSubmit);
        assertFalse("uncertain create button must be enabled after setting its name: " + createUiBeforeSubmit,
                new JSONObject(createUiBeforeSubmit).getBoolean("createDisabled"));
        long mutationRequestedAtEpochMs = System.currentTimeMillis();
        click("[data-testid=create-session]");
        String uncertainWarningCondition = "(() => {"
                + "const root=document.querySelector('.app-shell');"
                + "const warning=document.querySelector('[data-testid=uncertain-mutation]');"
                + "const target=" + JSONObject.quote(target) + ";"
                + "return !!warning && warning.textContent.includes(target)"
                + " && warning.textContent.includes('may have completed')"
                + " && warning.dataset.state === 'unknown'"
                + " && root?.dataset.sshPhase === 'listing'"
                + " && !!root.dataset.sshConnectionId"
                + " && root.dataset.sshConnectionId !== " + JSONObject.quote(oldConnectionId)
                + " && !Array.from(document.querySelectorAll('[data-session-tag]'))"
                + ".some((node)=>node.dataset.sessionTag===target);})()";
        try {
            awaitJsTrue(uncertainWarningCondition, 20_000);
        } catch (AssertionError failure) {
            String createUiAfterTimeout = evalString("JSON.stringify({surface:document.querySelector('.app-shell')?.dataset.homeSurface??'',"
                    + "phase:document.querySelector('.app-shell')?.dataset.sshPhase??'',"
                    + "connectionId:document.querySelector('.app-shell')?.dataset.sshConnectionId??'',"
                    + "inputValue:document.querySelector('[data-testid=new-session-name]')?.value??null,"
                    + "createDisabled:document.querySelector('[data-testid=create-session]')?.disabled??null,"
                    + "warning:document.querySelector('[data-testid=uncertain-mutation]')?.textContent.trim()??'',"
                    + "warningState:document.querySelector('[data-testid=uncertain-mutation]')?.dataset.state??'',"
                    + "message:document.querySelector('.connection-message')?.textContent.trim()??'',"
                    + "sessions:Array.from(document.querySelectorAll('[data-session-tag]')).map((node)=>node.dataset.sessionTag)})");
            Log.e("SshPtyDockerJourney", "RUN " + runId + " UNCERTAIN_MUTATION_UI_AFTER_TIMEOUT " + createUiAfterTimeout,
                    failure);
            throw failure;
        }
        String connectionDuringFreshList = currentConnectionId();
        assertTrue("the fresh list must use a different SSH transport", !connectionDuringFreshList.isEmpty()
                && !oldConnectionId.equals(connectionDuringFreshList));
        // The fixture records FRESH_LIST_STARTED at the first instruction of its list wrapper,
        // then sleeps for ten seconds. Allow the new channel request to reach that wrapper
        // before sampling the state; the result checker later verifies the exact host event
        // timestamp against this device sample using the same-run clock-offset evidence.
        SystemClock.sleep(1_000);
        awaitJsTrue("(() => {"
                + "const root=document.querySelector('.app-shell');"
                + "const warning=document.querySelector('[data-testid=uncertain-mutation]');"
                + "const target=" + JSONObject.quote(target) + ";"
                + "return !!warning && warning.dataset.state === 'unknown'"
                + " && root?.dataset.sshPhase === 'listing'"
                + " && root.dataset.sshConnectionId === " + JSONObject.quote(connectionDuringFreshList)
                + " && !Array.from(document.querySelectorAll('[data-session-tag]'))"
                + ".some((node)=>node.dataset.sessionTag===target);})()", 2_000);
        long uncertaintyObservedAtEpochMs = System.currentTimeMillis();
        String uncertaintyWarning = evalString(
                "document.querySelector('[data-testid=uncertain-mutation]')?.textContent.trim() ?? ''");
        String initialMutationState = evalString(
                "document.querySelector('[data-testid=uncertain-mutation]')?.dataset.state ?? ''");
        assertEquals("the JS controller must expose an unknown result before its fresh listing completes",
                "unknown", initialMutationState);
        assertTrue("the fresh listing must use a new SSH transport", !connectionDuringFreshList.isEmpty()
                && !oldConnectionId.equals(connectionDuringFreshList));
        Log.i("SshPtyDockerJourney", "RUN " + runId + " UNCERTAIN_MUTATION_REPORTED "
                + new JSONObject().put("target", target)
                .put("oldConnectionId", oldConnectionId)
                .put("newConnectionId", connectionDuringFreshList)
                .put("phase", currentPhase())
                .put("observedAtEpochMs", uncertaintyObservedAtEpochMs)
                .put("warning", uncertaintyWarning));

        String mutationReconciledCondition = "(() => {const root=document.querySelector('.app-shell');"
                + "return ['connected','live'].includes(root?.dataset.sshPhase)"
                + " && root?.dataset.sshConnectionId === " + JSONObject.quote(connectionDuringFreshList)
                + " && document.querySelector('[data-testid=uncertain-mutation]')?.dataset.state === 'observed-applied'"
                + " && Array.from(document.querySelectorAll('[data-session-tag]'))"
                + ".filter((node)=>node.dataset.sessionTag === " + JSONObject.quote(target) + ").length === 1;})()";
        try {
            awaitJsTrue(mutationReconciledCondition, 25_000);
        } catch (AssertionError failure) {
            String reconciliationUi = evalString("JSON.stringify({surface:document.querySelector('.app-shell')?.dataset.homeSurface??'',"
                    + "phase:document.querySelector('.app-shell')?.dataset.sshPhase??'',"
                    + "connectionId:document.querySelector('.app-shell')?.dataset.sshConnectionId??'',"
                    + "selectedTag:document.querySelector('.app-shell')?.dataset.sshSelectedTag??'',"
                    + "warningState:document.querySelector('[data-testid=uncertain-mutation]')?.dataset.state??'',"
                    + "sessions:Array.from(document.querySelectorAll('[data-session-tag]')).map((node)=>node.dataset.sessionTag)})");
            Log.e("SshPtyDockerJourney", "RUN " + runId + " UNCERTAIN_MUTATION_RECONCILIATION_UI " + reconciliationUi,
                    failure);
            throw failure;
        }
        long reconciledAtEpochMs = System.currentTimeMillis();
        JSONObject createdRow = findSessionRow(target);

        JSONObject proofResult = awaitNativeSshExecOnCurrentConnection(
                "uncertain-mutation-proof-" + runId,
                "cat " + shellQuote(prefix + ".proof"),
                15_000);
        assertEquals("Docker create proof must be readable over the fresh SSH connection", 0,
                proofResult.getInt("exitCode"));
        assertFalse("Docker create proof read must not time out", proofResult.getBoolean("timedOut"));
        String serverProof = proofResult.getString("stdout");
        assertTrue("Docker create proof must identify the run and prove the response was withheld",
                serverProof.contains("run_id=" + runId + "\n")
                        && serverProof.contains("target=" + target + "\n")
                        && serverProof.contains("host_cli_exit_code=0\n")
                        && serverProof.contains("host_cli_created=true\n")
                        && serverProof.contains("response_forwarded=false\n")
                        && serverProof.contains("signal=SIGKILL\n"));

        JSONObject eventsResult = awaitNativeSshExecOnCurrentConnection(
                "uncertain-mutation-events-" + runId,
                "cat " + shellQuote(prefix + ".events"),
                15_000);
        assertEquals("Docker mutation event log must be readable over the fresh SSH connection", 0,
                eventsResult.getInt("exitCode"));
        assertFalse("Docker mutation event log read must not time out", eventsResult.getBoolean("timedOut"));
        String fixtureEvents = eventsResult.getString("stdout");
        long freshListStartedAtEpochMs = fixtureEventTimestamp(fixtureEvents, "FRESH_LIST_STARTED", target);
        writeText(new File(artifactDirectory, "uncertain-mutation-server-proof.txt"), serverProof);
        writeText(new File(artifactDirectory, "uncertain-mutation-fixture-events.txt"), fixtureEvents);

        org.json.JSONArray phases = new org.json.JSONArray(
                evalString("JSON.stringify(window.__pocketshellJourney?.phases ?? [])"));
        int reconnectingPhases = 0;
        int listingPhases = 0;
        for (int index = 0; index < phases.length(); index += 1) {
            JSONObject phase = phases.getJSONObject(index);
            if (phase.optLong("at") < mutationRequestedAtEpochMs) continue;
            if ("reconnecting".equals(phase.optString("phase"))) reconnectingPhases += 1;
            if ("listing".equals(phase.optString("phase"))
                    && connectionDuringFreshList.equals(phase.optString("connectionId"))) listingPhases += 1;
        }
        Log.i("SshPtyDockerJourney", "RUN " + runId + " UNCERTAIN_MUTATION_PHASES " + phases);
        assertEquals("one JS reconnect must follow the lost create response", 1, reconnectingPhases);
        assertEquals("one fresh session-list phase must reconcile the uncertain create", 1, listingPhases);
        assertEquals("the fixture's host CLI create identity must match the session shown in the refreshed UI",
                createdRow.getString("name"), proofLine(serverProof, "host_cli_name="));
        assertTrue("the host CLI identity must be qualified with the requested session tag",
                createdRow.getString("name").endsWith(":" + target));
        assertEquals("the fresh session list must expose the independent host UUID", createdRow.getString("id"),
                proofLine(serverProof, "host_cli_session_id="));
        Log.i("SshPtyDockerJourney", "RUN " + runId + " UNCERTAIN_MUTATION_RECONCILED "
                + new JSONObject().put("target", target)
                .put("sessionId", createdRow.getString("id"))
                .put("connectionId", connectionDuringFreshList)
                .put("reconnectingPhaseCount", reconnectingPhases)
                .put("freshListPhaseCount", listingPhases)
                .put("reconciledAtEpochMs", reconciledAtEpochMs));

        return new JSONObject()
                .put("assertions", new org.json.JSONArray()
                        .put("real-host-session-create-completed-before-response-drop")
                        .put("ssh-create-response-withheld-before-android-received-it")
                        .put("controller-reported-create-uncertainty-before-fresh-list-completed")
                        .put("no-automatic-create-replay")
                        .put("fresh-session-list-reconciled-created-row")
                        .put("independent-docker-aplexer-snapshot-confirmed-created-row"))
                .put("kind", "create-session")
                .put("target", target)
                .put("oldConnectionId", oldConnectionId)
                .put("newConnectionId", connectionDuringFreshList)
                .put("mutationRequestedAtEpochMs", mutationRequestedAtEpochMs)
                .put("uncertaintyObservedAtEpochMs", uncertaintyObservedAtEpochMs)
                .put("freshListStartedAtEpochMs", freshListStartedAtEpochMs)
                .put("freshListObservedAtEpochMs", reconciledAtEpochMs)
                .put("uncertaintyPhase", "listing")
                .put("initialControllerState", initialMutationState)
                .put("reconciledControllerState", evalString(
                        "document.querySelector('[data-testid=uncertain-mutation]')?.dataset.state ?? ''"))
                .put("targetRowCountBeforeFreshList", 0)
                .put("reportedWarning", uncertaintyWarning)
                .put("reconnectingPhaseCount", reconnectingPhases)
                .put("freshListPhaseCount", listingPhases)
                .put("sessionRow", createdRow)
                .put("serverProofFile", "uncertain-mutation-server-proof.txt")
                .put("fixtureEventsFile", "uncertain-mutation-fixture-events.txt");
    }

    private JSONObject attachAndCapture(JSONObject row, String checkpoint, String marker, File artifactDirectory) throws Exception {
        String tag = row.getString("tag");
        JSONObject resizeBefore = terminalResizeStats();
        assertEquals("no native terminal resize may be pending before " + checkpoint, 0,
                resizeBefore.getInt("pending"));
        if (!"sessions".equals(evalString("document.querySelector('.app-shell')?.dataset.homeSurface ?? ''"))) {
            click("[data-testid=open-sessions]");
            awaitJsTrue("document.querySelector('.app-shell')?.dataset.homeSurface === 'sessions'");
        }
        awaitJsTrue("Array.from(document.querySelectorAll('[data-session-tag]')).some((node) => node.dataset.sessionTag === "
                + JSONObject.quote(tag) + ")");
        click("[data-session-tag=\"" + tag + "\"]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.sshPhase === 'live'"
                + " && document.querySelector('.app-shell')?.dataset.sshSelectedTag === " + JSONObject.quote(tag));
        assertEquals("selected session identity must come from the live host row", row.getString("name"), selectedSessionName());
        assertEquals("selected session ID must come from the live host row", row.getString("id"), selectedSessionId());
        assertEquals("selected workspace must come from the live host row", row.getString("workspace"), selectedWorkspace());
        awaitNativeResizeAckAfter(resizeBefore.getInt("ackCount"), checkpoint);
        JSONObject checkpointData = sendMarkerAndCapture(checkpoint, marker, artifactDirectory);
        Log.i("SshPtyDockerJourney", "RUN " + row.getString("tag") + " CHECKPOINT " + checkpointData);
        return checkpointData;
    }

    private JSONObject sendMarkerAndCapture(String checkpoint, String marker, File artifactDirectory) throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        awaitTerminalReady(checkpoint);
        int terminalColumns = currentTerminalColumns();
        assertTrue("remote output marker must fit one terminal row for " + checkpoint + " (columns="
                + terminalColumns + "): " + marker, marker.length() <= terminalColumns);
        JSONObject before = terminalInputStats();
        assertEquals("no terminal input may be pending before " + checkpoint, 0, before.getInt("pending"));
        assertEquals("terminal input failures must remain zero before " + checkpoint, 0, before.getInt("failureCount"));
        int markerAccent = markerAccentColor(activeRunId, marker);
        // Switch B's short-lived keyboard-up viewport can put the marker at
        // the physical row boundary. Leave two blank rows after that marker
        // only, keeping other sessions' current-screen evidence intact.
        String markerTrailingRows = "switch-b".equals(checkpoint) ? "\\n\\n\\n" : "\\n";
        String markerFormat = String.format(Locale.ROOT,
                "\\033[1;38;2;0;0;0m\\033[48;2;%d;%d;%dm%%s\\033[0m%s",
                Color.red(markerAccent), Color.green(markerAccent), Color.blue(markerAccent), markerTrailingRows);
        String markerCommand = "printf '" + markerFormat + "' '" + marker + "'";
        // Inject the shell command through xterm's paste handler to avoid emulator per-character IME
        // duplication. The packaged composer journey separately exercises the real Android IME path.
        pasteTerminalText(markerCommand, checkpoint);
        waitForTerminalInputDrain(before.getInt("ackCount"), before.getInt("failureCount"), checkpoint + " command paste");

        JSONObject beforeEnter = terminalInputStats();
        assertEquals("pasted marker command must finish before Enter for " + checkpoint, 0,
                beforeEnter.getInt("pending"));
        assertEquals("terminal input failures must remain zero before Enter for " + checkpoint,
                before.getInt("failureCount"), beforeEnter.getInt("failureCount"));
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER);
        waitForTerminalInputDrain(beforeEnter.getInt("ackCount"), beforeEnter.getInt("failureCount"), checkpoint + " command Enter");
        awaitExactMarkerRow(marker, checkpoint);
        JSONObject checkpointData = captureCurrent(checkpoint, marker, artifactDirectory);
        Log.i("SshPtyDockerJourney", "RUN " + activeRunId + " ACK_DRAIN " + checkpoint
                + " " + terminalInputStats());
        return checkpointData;
    }

    private void pasteTerminalText(String text, String checkpoint) throws Exception {
        String result = evalString("JSON.stringify((() => {"
                + "const textarea=document.querySelector('#terminal-viewport .xterm-helper-textarea');"
                + "const focused=!!textarea&&document.activeElement===textarea;"
                + "if(!focused||typeof DataTransfer==='undefined'||typeof ClipboardEvent==='undefined')"
                + "return {focused,eventAccepted:false,clipboardText:''};"
                + "const transfer=new DataTransfer();transfer.setData('text/plain'," + JSONObject.quote(text) + ");"
                + "const event=new ClipboardEvent('paste',{clipboardData:transfer,bubbles:true,cancelable:true});"
                + "const eventAccepted=textarea.dispatchEvent(event);"
                + "return {focused,eventAccepted,clipboardText:event.clipboardData?.getData('text/plain')??''};})())");
        JSONObject paste = new JSONObject(result);
        assertTrue("xterm helper textarea must be focused for marker paste at " + checkpoint,
                paste.getBoolean("focused"));
        assertTrue("xterm must accept the marker ClipboardEvent at " + checkpoint,
                paste.getBoolean("eventAccepted"));
        assertEquals("xterm paste clipboard must contain the exact command at " + checkpoint,
                text, paste.getString("clipboardText"));
    }

    private void awaitTerminalReady(String checkpoint) throws Exception {
        awaitJsTrue("(() => {const viewport=document.querySelector('#terminal-viewport');"
                + "const textarea=viewport?.querySelector('.xterm-helper-textarea');"
                + "if(!viewport||!textarea||viewport.dataset.enabled!=='true') return false;"
                + "textarea.focus(); return document.activeElement===textarea;})()");
        // Focusing xterm may open or dismiss Android's IME after the attach's
        // first resize acknowledgement. Wait for the accepted PTY grid to
        // match the current xterm grid and for the viewport to remain settled
        // before reading columns or sending terminal input.
        awaitStableNativeResizeState(-1, checkpoint + " after terminal focus");
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
        awaitTerminalReady("post-expiry Ctrl-C cleanup");
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
        hideImeForTerminalCapture(checkpoint);
        evalString("(() => {const main=document.querySelector('.screen-content.home-screen');"
                + "const viewport=document.querySelector('#terminal-viewport'); if(!main||!viewport) throw new Error('live terminal page missing');"
                + "main.scrollTop += viewport.getBoundingClientRect().top-main.getBoundingClientRect().top-8;"
                + "const scroller=viewport.querySelector('.xterm-viewport'); if(scroller) scroller.scrollTop=scroller.scrollHeight;"
                + "return 'scrolled-to-terminal';})()");
        String viewportFitCondition = "(() => {const main=document.querySelector('.screen-content.home-screen');"
                + "const viewport=document.querySelector('#terminal-viewport'); const title=document.querySelector('#terminal-title');"
                + "const r=viewport?.getBoundingClientRect(),m=main?.getBoundingClientRect();"
                + "return !!r&&!!m&&viewport.dataset.enabled==='true'&&title?.textContent.trim()===" + JSONObject.quote(selectedName)
                // Android WebView reports a sub-2 CSS-pixel top overlap after
                // relayout; allow that rounding/paint-boundary slop while
                // still rejecting a visibly clipped terminal viewport.
                + "&&r.top>=m.top-4&&r.bottom<=m.bottom+1&&r.left>=m.left&&r.right<=m.right;})()";
        try {
            awaitJsTrue(viewportFitCondition);
        } catch (AssertionError failure) {
            String geometry = evalString("JSON.stringify((()=>{const root=document.querySelector('.app-shell');"
                    + "const main=document.querySelector('.screen-content.home-screen');"
                    + "const viewport=document.querySelector('#terminal-viewport');"
                    + "const box=(node)=>{const r=node?.getBoundingClientRect();return r?{top:r.top,bottom:r.bottom,left:r.left,right:r.right,width:r.width,height:r.height}:null};"
                    + "const active=document.activeElement;return {surface:root?.dataset.homeSurface??'',phase:root?.dataset.sshPhase??'',"
                    + "selectedTag:root?.dataset.sshSelectedTag??'',selectedName:root?.dataset.sshSelectedSession??'',"
                    + "viewportEnabled:viewport?.dataset.enabled??'',title:document.querySelector('#terminal-title')?.textContent.trim()??'',"
                    + "inner:{width:innerWidth,height:innerHeight},visual:{offsetTop:visualViewport?.offsetTop??null,height:visualViewport?.height??null},"
                    + "main:box(main),viewport:box(viewport),mainScrollTop:main?.scrollTop??null,"
                    + "mainClientHeight:main?.clientHeight??null,mainScrollHeight:main?.scrollHeight??null,"
                    + "activeElement:active?{tag:active.tagName,id:active.id,type:active.type??''}:null};})())");
            Log.e("SshPtyDockerJourney", "RUN " + activeRunId + " VIEWPORT_FIT_FAILURE " + checkpoint + " " + geometry,
                    failure);
            throw failure;
        }
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
        JSONObject layout = terminalLayoutDiagnostics();
        layout.put("markerGeometry", geometry);
        JSONObject rect = geometry.getJSONObject("viewport");
        JSONObject markerRect = geometry.getJSONObject("marker");
        assertTrue("terminal viewport must have positive visible dimensions", rect.getDouble("width") > 0 && rect.getDouble("height") > 0);
        assertTrue("exact marker row must fit inside terminal viewport", markerRect.getDouble("left") >= rect.getDouble("left")
                && markerRect.getDouble("top") >= rect.getDouble("top") && markerRect.getDouble("right") <= rect.getDouble("right")
                && markerRect.getDouble("bottom") <= rect.getDouble("bottom"));
        writeText(new File(artifactDirectory, checkpoint + "-visible-terminal.txt"), text);
        writeText(new File(artifactDirectory, checkpoint + "-terminal-layout.json"), layout.toString(2));
        JSONObject screenshot = captureViewportPng(checkpoint, marker, rect, markerRect, artifactDirectory);
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
                .put("terminalLayoutFile", checkpoint + "-terminal-layout.json")
                .put("screenshotPixels", screenshot);
    }

    private void hideImeForTerminalCapture(String checkpoint) throws Exception {
        boolean imeWasVisible = isImeVisible();
        JSONObject resizeBefore = terminalResizeStats();
        evalString("(() => {const active=document.activeElement;"
                + "if(active instanceof HTMLElement) active.blur(); return 'terminal focus cleared for viewport capture';})()");
        requestImeHide();
        long deadline = SystemClock.uptimeMillis() + 10_000;
        int stableHiddenSamples = 0;
        while (SystemClock.uptimeMillis() < deadline) {
            if (!isImeVisible()) {
                stableHiddenSamples += 1;
                if (stableHiddenSamples >= 3) {
                    int previousAckCount = resizeBefore.getInt("ackCount") - (imeWasVisible ? 0 : 1);
                    JSONObject resizeAfter = awaitStableNativeResizeState(
                            previousAckCount, checkpoint + " after hiding IME");
                    Log.i("SshPtyDockerJourney", "RUN " + activeRunId + " TERMINAL_CAPTURE_IME_HIDDEN "
                            + new JSONObject().put("checkpoint", checkpoint).put("imeWasVisible", imeWasVisible)
                            .put("resize", resizeAfter));
                    return;
                }
            } else {
                stableHiddenSamples = 0;
                requestImeHide();
            }
            Thread.sleep(100);
        }
        throw new AssertionError(checkpoint + " Android IME did not stay hidden for terminal viewport capture");
    }

    private boolean isImeVisible() {
        AtomicReference<Boolean> visible = new AtomicReference<>(false);
        scenario.onActivity(activity -> {
            WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
            visible.set(insets != null && android.os.Build.VERSION.SDK_INT >= 30
                    && insets.isVisible(WindowInsets.Type.ime()));
        });
        return visible.get();
    }

    private void requestImeHide() {
        scenario.onActivity(activity -> {
            android.view.View decor = activity.getWindow().getDecorView();
            android.view.WindowInsetsController controller = decor.getWindowInsetsController();
            if (controller != null) controller.hide(WindowInsets.Type.ime());
            InputMethodManager inputMethodManager = activity.getSystemService(InputMethodManager.class);
            if (inputMethodManager != null) {
                inputMethodManager.hideSoftInputFromWindow(decor.getWindowToken(), 0);
            }
        });
    }

    private JSONObject terminalLayoutDiagnostics() throws Exception {
        String script = "(() => {window.dispatchEvent(new Event('pocketshell:terminal-geometry-request'));"
                + "const box=(el)=>{if(!el)return null;const r=el.getBoundingClientRect(),s=getComputedStyle(el);return {"
                + "rect:{left:r.left,top:r.top,right:r.right,bottom:r.bottom,width:r.width,height:r.height},"
                + "clientWidth:el.clientWidth,clientHeight:el.clientHeight,scrollWidth:el.scrollWidth,scrollHeight:el.scrollHeight,"
                + "scrollTop:el.scrollTop,display:s.display,position:s.position,height:s.height,minHeight:s.minHeight,maxHeight:s.maxHeight,"
                + "flex:s.flex,flexBasis:s.flexBasis,flexGrow:s.flexGrow,flexShrink:s.flexShrink,overflowX:s.overflowX,overflowY:s.overflowY};};"
                + "const shell=document.querySelector('.app-shell'),main=document.querySelector('.screen-content.home-screen'),"
                + "live=document.querySelector('.live-workspace'),panel=document.querySelector('.terminal-panel'),host=document.querySelector('#terminal-viewport'),"
                + "xterm=host?.querySelector('.xterm'),scroll=host?.querySelector('.xterm-viewport'),screen=host?.querySelector('.xterm-screen'),"
                + "rows=host?.querySelector('.xterm-rows'),row=host?.querySelector('.xterm-rows > div'),composer=document.querySelector('.composer-panel'),"
                + "vv=window.visualViewport,rootStyle=getComputedStyle(document.documentElement),shellStyle=shell?getComputedStyle(shell):null;"
                + "const rowHeight=row?.getBoundingClientRect().height??0;return JSON.stringify({capturedAtEpochMs:Date.now(),"
                + "screen:{width:window.screen.width,height:window.screen.height,availWidth:window.screen.availWidth,availHeight:window.screen.availHeight},"
                + "window:{innerWidth:window.innerWidth,innerHeight:window.innerHeight,outerWidth:window.outerWidth,outerHeight:window.outerHeight,"
                + "devicePixelRatio:window.devicePixelRatio,documentClientWidth:document.documentElement.clientWidth,"
                + "documentClientHeight:document.documentElement.clientHeight,scrollX:window.scrollX,scrollY:window.scrollY},"
                + "visualViewport:vv?{width:vv.width,height:vv.height,offsetLeft:vv.offsetLeft,offsetTop:vv.offsetTop,"
                + "pageLeft:vv.pageLeft,pageTop:vv.pageTop,scale:vv.scale}:null,"
                + "keyboard:{visible:shell?.dataset.keyboardVisible??null,composerMode:shell?.dataset.keyboardComposerMode??null,"
                + "nativePlatform:shell?.dataset.nativePlatform??null,activeElement:document.activeElement?.tagName??null,"
                + "safeAreaBottom:rootStyle.getPropertyValue('--safe-area-inset-bottom').trim(),"
                + "shellSafeBottom:shellStyle?.getPropertyValue('--android-shell-safe-bottom').trim()??null},"
                + "elements:{shell:box(shell),appBar:box(document.querySelector('.app-bar--workspace')),main:box(main),"
                + "liveWorkspace:box(live),terminalPanel:box(panel),terminalHost:box(host),xterm:box(xterm),"
                + "xtermViewport:box(scroll),xtermScreen:box(screen),xtermRows:box(rows),composer:box(composer)},"
                + "xtermDom:{domRows:rows?.children.length??0,rowHeight,cssVisibleRows:rowHeight>0?screen.clientHeight/rowHeight:null,"
                + "viewportScrollTop:scroll?.scrollTop??null,viewportScrollHeight:scroll?.scrollHeight??null,"
                + "viewportClientHeight:scroll?.clientHeight??null,runtime:window.__ps2875TerminalRuntimeGeometry??null}});})()";
        return new JSONObject(evalString(script));
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
                + "window.__ps2857CaptureTerminalEvidence=true;"
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
        AtomicReference<JSONObject> nativeWindow = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            android.view.View decor = activity.getWindow().getDecorView();
            WebView view = decor instanceof WebView ? (WebView) decor : findWebView((android.view.ViewGroup) decor);
            assertNotNull("the packaged activity must contain its Capacitor WebView", view);
            int[] location = new int[2];
            view.getLocationOnScreen(location);
            Rect visibleFrame = new Rect();
            decor.getWindowVisibleDisplayFrame(visibleFrame);
            android.view.WindowInsets rootInsets = decor.getRootWindowInsets();
            boolean imeVisible = false;
            int systemBarsTop = 0;
            int systemBarsBottom = 0;
            int imeBottom = 0;
            if (rootInsets != null && android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets systemBars = rootInsets.getInsets(android.view.WindowInsets.Type.systemBars());
                android.graphics.Insets ime = rootInsets.getInsets(android.view.WindowInsets.Type.ime());
                imeVisible = rootInsets.isVisible(android.view.WindowInsets.Type.ime());
                systemBarsTop = systemBars.top;
                systemBarsBottom = systemBars.bottom;
                imeBottom = ime.bottom;
            }
            try {
                nativeWindow.set(new JSONObject()
                        .put("decorWidth", decor.getWidth())
                        .put("decorHeight", decor.getHeight())
                        .put("webViewX", location[0])
                        .put("webViewY", location[1])
                        .put("webViewWidth", view.getWidth())
                        .put("webViewHeight", view.getHeight())
                        .put("windowVisibleFrame", new JSONObject()
                                .put("left", visibleFrame.left).put("top", visibleFrame.top)
                                .put("right", visibleFrame.right).put("bottom", visibleFrame.bottom)
                                .put("width", visibleFrame.width()).put("height", visibleFrame.height()))
                        .put("systemBarsTop", systemBarsTop)
                        .put("systemBarsBottom", systemBarsBottom)
                        .put("imeBottom", imeBottom)
                        .put("imeVisible", imeVisible));
            } catch (JSONException error) {
                throw new IllegalStateException("could not serialize native WebView bounds", error);
            }
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
        JSONObject nativeBounds = nativeWindow.get();
        assertNotNull("native WebView bounds must be recorded", nativeBounds);
        if ("background-within-grace".equals(checkpoint)) {
            JSONObject visibleFrame = nativeBounds.getJSONObject("windowVisibleFrame");
            assertFalse("the IME must be hidden after lifecycle resume before screenshot acceptance",
                    nativeBounds.getBoolean("imeVisible"));
            assertTrue("resumed WebView must fill the available window after the IME closes: native="
                            + nativeBounds,
                    nativeBounds.getInt("webViewHeight") >= visibleFrame.getInt("height") - 120);
        }
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
        int markerAccent = markerAccentColor(activeRunId, marker);
        Bitmap full = takeScreenshotWhenMarkerIsPainted(
                markerLeft, markerTop, markerRight, markerBottom, checkpoint, markerAccent, artifactDirectory);
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
                .put("nativeWindow", nativeBounds)
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
            int left, int top, int right, int bottom, String checkpoint, int expectedAccent, File artifactDirectory
    ) throws Exception {
        long deadline = SystemClock.uptimeMillis() + SCREENSHOT_MARKER_WAIT_MILLIS;
        int lastAccentPixels = 0;
        long lastSampleEpochMs = 0;
        Bitmap lastFrame = null;
        while (SystemClock.uptimeMillis() < deadline) {
            Bitmap frame = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            assertNotNull("Android must provide a same-run viewport screenshot", frame);
            if (left >= 0 && top >= 0 && right <= frame.getWidth() && bottom <= frame.getHeight()
                    && right > left && bottom > top) {
                lastAccentPixels = countPixelsNearColor(frame, left, top, right, bottom,
                        expectedAccent, SCREENSHOT_MARKER_ACCENT_TOLERANCE);
                if (lastAccentPixels >= SCREENSHOT_MARKER_ACCENT_MIN_PIXELS) {
                    if (lastFrame != null) lastFrame.recycle();
                    return frame;
                }
            }
            if (lastFrame != null) lastFrame.recycle();
            lastFrame = frame;
            lastSampleEpochMs = System.currentTimeMillis();
            waitForNextWebViewFrame();
            Thread.sleep(50);
        }
        if (lastFrame != null) {
            try {
                persistScreenshotFailureEvidence(lastFrame, left, top, right, bottom, checkpoint,
                        expectedAccent, lastAccentPixels, lastSampleEpochMs, artifactDirectory);
            } catch (Throwable diagnosticsFailure) {
                Log.e("SshPtyDockerJourney", "RUN " + activeRunId
                        + " could not persist same-frame screenshot failure evidence", diagnosticsFailure);
            } finally {
                lastFrame.recycle();
            }
        }
        throw new AssertionError("Android screenshot never painted the current terminal marker row for " + checkpoint
                + " (accent pixels=" + lastAccentPixels + ", required=" + SCREENSHOT_MARKER_ACCENT_MIN_PIXELS + ")");
    }

    private void persistScreenshotFailureEvidence(
            Bitmap frame, int left, int top, int right, int bottom, String checkpoint, int expectedAccent,
            int accentPixels, long sampledAtEpochMs, File artifactDirectory
    ) throws Exception {
        String screenshotName = checkpoint + "-same-frame-failure.png";
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        if (!frame.compress(Bitmap.CompressFormat.PNG, 100, encoded)) {
            throw new IllegalStateException("same-frame failure screenshot could not be encoded as PNG");
        }
        byte[] pngBytes = encoded.toByteArray();
        try (FileOutputStream output = new FileOutputStream(new File(artifactDirectory, screenshotName))) {
            output.write(pngBytes);
        }
        emitArtifact(screenshotName, pngBytes);

        JSONObject state = new JSONObject()
                .put("schema", 1)
                .put("runId", activeRunId)
                .put("checkpoint", checkpoint)
                .put("sampledAtEpochMs", sampledAtEpochMs)
                .put("stateCapturedAtEpochMs", System.currentTimeMillis())
                .put("screenWidth", frame.getWidth())
                .put("screenHeight", frame.getHeight())
                .put("markerBounds", new JSONObject()
                        .put("left", left).put("top", top).put("right", right).put("bottom", bottom))
                .put("markerAccentRgb", colorString(expectedAccent))
                .put("markerAccentPixels", accentPixels)
                .put("requiredAccentPixels", SCREENSHOT_MARKER_ACCENT_MIN_PIXELS);

        try {
            AtomicReference<JSONObject> activityState = new AtomicReference<>();
            scenario.onActivity(activity -> {
                android.view.View decor = activity.getWindow().getDecorView();
                try {
                    WebView webView = decor instanceof WebView ? (WebView) decor
                            : findWebView((android.view.ViewGroup) decor);
                    int[] webViewLocation = new int[] {-1, -1};
                    if (webView != null) webView.getLocationOnScreen(webViewLocation);
                    Rect visibleFrame = new Rect();
                    decor.getWindowVisibleDisplayFrame(visibleFrame);
                    JSONObject webViewGeometry = new JSONObject()
                            .put("present", webView != null)
                            .put("attached", webView != null && webView.isAttachedToWindow())
                            .put("shown", webView != null && webView.isShown())
                            .put("x", webViewLocation[0])
                            .put("y", webViewLocation[1])
                            .put("width", webView == null ? 0 : webView.getWidth())
                            .put("height", webView == null ? 0 : webView.getHeight());
                    JSONObject visibleFrameGeometry = new JSONObject()
                            .put("left", visibleFrame.left).put("top", visibleFrame.top)
                            .put("right", visibleFrame.right).put("bottom", visibleFrame.bottom)
                            .put("width", visibleFrame.width()).put("height", visibleFrame.height());
                    android.view.WindowInsets rootInsets = decor.getRootWindowInsets();
                    if (rootInsets != null && android.os.Build.VERSION.SDK_INT >= 30) {
                        android.graphics.Insets systemBars = rootInsets.getInsets(android.view.WindowInsets.Type.systemBars());
                        android.graphics.Insets ime = rootInsets.getInsets(android.view.WindowInsets.Type.ime());
                        webViewGeometry.put("systemBarsInsets", new JSONObject()
                                .put("top", systemBars.top).put("bottom", systemBars.bottom));
                        webViewGeometry.put("imeInsets", new JSONObject()
                                .put("top", ime.top).put("bottom", ime.bottom)
                                .put("visible", rootInsets.isVisible(android.view.WindowInsets.Type.ime())));
                    }
                    activityState.set(new JSONObject()
                            .put("className", activity.getClass().getName())
                            .put("lifecycle", activity.getLifecycle().getCurrentState().name())
                            .put("hasWindowFocus", activity.hasWindowFocus())
                            .put("isFinishing", activity.isFinishing())
                            .put("isDestroyed", activity.isDestroyed())
                            .put("decorAttached", decor.isAttachedToWindow())
                            .put("decorShown", decor.isShown())
                            .put("decorVisibility", decor.getVisibility())
                            .put("decorWindowVisibility", decor.getWindowVisibility())
                            .put("decorHasWindowFocus", decor.hasWindowFocus())
                            .put("decorWidth", decor.getWidth())
                            .put("decorHeight", decor.getHeight())
                            .put("webView", webViewGeometry)
                            .put("windowVisibleDisplayFrame", visibleFrameGeometry));
                } catch (JSONException error) {
                    throw new IllegalStateException("could not serialize same-frame activity state", error);
                }
            });
            state.put("activity", activityState.get());
        } catch (Exception stateError) {
            state.put("activityStateError", stateError.getClass().getName() + ": " + stateError.getMessage());
        }

        try {
            state.put("webViewLayout", terminalLayoutDiagnostics());
        } catch (Exception layoutError) {
            state.put("webViewLayoutError", layoutError.getClass().getName() + ": " + layoutError.getMessage());
        }

        try {
            org.json.JSONArray windows = new org.json.JSONArray();
            List<AccessibilityWindowInfo> activeWindows = InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation().getWindows();
            for (AccessibilityWindowInfo window : activeWindows) {
                Rect bounds = new Rect();
                window.getBoundsInScreen(bounds);
                AccessibilityNodeInfo root = window.getRoot();
                CharSequence packageName = root == null ? null : root.getPackageName();
                CharSequence className = root == null ? null : root.getClassName();
                windows.put(new JSONObject()
                        .put("id", window.getId())
                        .put("type", window.getType())
                        .put("title", window.getTitle() == null ? "" : window.getTitle().toString())
                        .put("active", window.isActive())
                        .put("focused", window.isFocused())
                        .put("packageName", packageName == null ? "" : packageName.toString())
                        .put("rootClassName", className == null ? "" : className.toString())
                        .put("bounds", new JSONObject().put("left", bounds.left).put("top", bounds.top)
                                .put("right", bounds.right).put("bottom", bounds.bottom)));
            }
            state.put("accessibilityWindows", windows);
        } catch (Exception windowError) {
            state.put("accessibilityWindowStateError", windowError.getClass().getName() + ": " + windowError.getMessage());
        }

        try {
            state.put("systemForegroundWindowState", captureSystemForegroundWindowState());
        } catch (Exception shellError) {
            state.put("systemForegroundWindowStateError", shellError.getClass().getName() + ": " + shellError.getMessage());
        }
        writeText(new File(artifactDirectory, checkpoint + "-screenshot-failure-state.json"), state.toString(2));
    }

    private String captureSystemForegroundWindowState() throws Exception {
        String activities = runUiAutomationCommand("dumpsys activity activities");
        String windows = runUiAutomationCommand("dumpsys window");
        StringBuilder result = new StringBuilder();
        appendMatchingLines(result, "activity", activities,
                "topResumedActivity", "mResumedActivity", "ResumedActivity");
        appendMatchingLines(result, "window", windows,
                "mCurrentFocus", "mFocusedApp", "mInputMethodWindow");
        return result.toString();
    }

    private String runUiAutomationCommand(String command) throws Exception {
        ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand(command);
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ParcelFileDescriptor.AutoCloseInputStream(descriptor), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line).append('\n');
        }
        return result.toString();
    }

    private void appendMatchingLines(StringBuilder destination, String label, String text, String... needles) {
        for (String line : text.split("\\R")) {
            for (String needle : needles) {
                if (line.contains(needle)) {
                    destination.append(label).append(": ").append(line.trim()).append('\n');
                    break;
                }
            }
        }
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

    private int markerAccentColor(String runId, String marker) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((runId + "\0" + marker).getBytes(StandardCharsets.UTF_8));
        // Keep the marker-specific color deterministic and bright enough for
        // black terminal text to remain legible in the device screenshot OCR.
        int[] rgb = new int[] {
                240 + (digest[2] & 0x0f),
                224 + (digest[3] & 0x1f),
                digest[4] & 0x3f,
        };
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
        String sha256 = hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        Log.i("PocketshellJourneyAsset", "FILE|" + activeRunId + "|" + name + "|" + bytes.length + "|" + sha256);
    }

    private void writeArtifactManifest(File artifactDirectory) throws Exception {
        File manifestFile = new File(artifactDirectory, "artifact-manifest.json");
        assertFalse("run artifact manifest must not already exist", manifestFile.exists());
        File[] files = artifactDirectory.listFiles();
        assertNotNull("run artifact directory must remain readable", files);
        Arrays.sort(files, (first, second) -> first.getName().compareTo(second.getName()));

        org.json.JSONArray artifacts = new org.json.JSONArray();
        for (File file : files) {
            if (file.equals(manifestFile)) continue;
            assertTrue("run artifacts must be regular files with safe names: " + file.getName(),
                    file.isFile() && file.getName().matches("[A-Za-z0-9._-]{1,100}"));
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            artifacts.put(new JSONObject()
                    .put("name", file.getName())
                    .put("sizeBytes", bytes.length)
                    .put("sha256", hex(MessageDigest.getInstance("SHA-256").digest(bytes))));
        }

        byte[] manifest = new JSONObject()
                .put("schema", 1)
                .put("runId", activeRunId)
                .put("artifacts", artifacts)
                .toString(2)
                .getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream output = new FileOutputStream(manifestFile)) {
            output.write(manifest);
        }
        String manifestDigest = hex(MessageDigest.getInstance("SHA-256").digest(manifest));
        Log.i("PocketshellJourneyAsset", "MANIFEST|" + activeRunId + "|" + artifacts.length() + "|" + manifestDigest);
        awaitHostArtifactPull(artifactDirectory.getParentFile(), manifestDigest);
    }

    private void awaitHostArtifactPull(File artifactParent, String manifestDigest) throws Exception {
        File acknowledgment = new File(artifactParent, ".host-pull-complete-" + activeRunId);
        File failure = new File(artifactParent, ".host-pull-failed-" + activeRunId);
        assertFalse("same-run host pull acknowledgment must be unique", acknowledgment.exists());
        long deadline = SystemClock.elapsedRealtime() + 60_000;
        while (SystemClock.elapsedRealtime() < deadline && !acknowledgment.isFile() && !failure.isFile()) {
            SystemClock.sleep(100);
        }
        if (failure.isFile()) {
            assertTrue("host artifact pull failure signal must be removable", failure.delete());
            throw new AssertionError("host failed to pull and verify the same-run lifecycle artifacts");
        }
        assertTrue("host must pull and verify the exact same-run artifacts before instrumentation exits",
                acknowledgment.isFile());
        assertTrue("same-run host pull acknowledgment must be removable", acknowledgment.delete());
        Log.i("PocketshellJourneyAsset", "PULLED|" + activeRunId + "|" + manifestDigest);
    }

    private String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private String marker(String session) {
        String phase;
        switch (session) {
            case "A_SWITCH": phase = "AS"; break;
            case "B_SWITCH": phase = "BS"; break;
            case "C_SWITCH": phase = "CS"; break;
            case "A_RETURN": phase = "AR"; break;
            case "A_WITHIN_GRACE": phase = "AG"; break;
            case "A_AFTER_EXPIRY": phase = "AE"; break;
            case "A_AFTER_ABRUPT_DROP": phase = "AD"; break;
            default: throw new IllegalArgumentException("unknown lifecycle marker phase: " + session);
        }
        return "REMOTE_OUTPUT_TREASURE_" + phase;
    }

    private JSONObject abruptlyDropServerTransportAndRecover(
            String runId, JSONObject expectedSession, String marker, File artifactDirectory
    ) throws Exception {
        String oldConnectionId = currentConnectionId();
        String oldGenerationId = currentGenerationId();
        String expectedTag = expectedSession.getString("tag");
        String expectedSessionId = expectedSession.getString("id");
        assertEquals("abrupt server-drop trigger must run while the selected PTY is live", "live", currentPhase());
        assertEquals("abrupt server-drop trigger must run on the selected A session", expectedTag, currentSelectedTag());
        assertEquals("abrupt server-drop trigger must retain the selected A session identity",
                expectedSessionId, selectedSessionId());
        awaitTerminalReady("before abrupt server-side transport drop");

        int resizeAckBeforeDrop = terminalResizeStats().getInt("ackCount");
        String triggerRequestId = "abrupt-drop-trigger-" + runId;
        String proofPath = "/tmp/pocketshell-server-transport-drop-" + runId + ".txt";
        String triggerCommand = "set -eu\n"
                + "parent_pid=\"$PPID\"\n"
                + "parent_args=$(ps -p \"$parent_pid\" -o args=)\n"
                + "parent_uid=$(ps -p \"$parent_pid\" -o uid= | tr -d ' ')\n"
                + "case \"$parent_args\" in *\"sshd-session: testuser@pts/\"*) ;; *) "
                + "printf 'unexpected SSH server parent: %s\\n' \"$parent_args\" >&2; exit 97 ;; esac\n"
                + "printf 'run_id=%s\\nserver_pid=%s\\nserver_uid=%s\\nserver_args=%s\\nsignal=SIGKILL\\n' "
                + JSONObject.quote(runId) + " \"$parent_pid\" \"$parent_uid\" \"$parent_args\" > "
                + shellQuote(proofPath) + "\n"
                + "kill -KILL \"$parent_pid\"\n";
        long dropRequestedAtEpochMs = System.currentTimeMillis();
        Log.i("SshPtyDockerJourney", "RUN " + runId + " ABRUPT_DROP_SERVER_KILL_REQUESTED "
                + new JSONObject().put("connectionId", oldConnectionId)
                .put("generationId", oldGenerationId)
                .put("selectedTag", expectedTag)
                .put("selectedId", expectedSessionId)
                .put("requestId", triggerRequestId)
                .put("signal", "SIGKILL"));
        evalString(nativeExecLaunchScript(triggerRequestId, oldConnectionId, oldGenerationId, triggerCommand, 12_000));

        awaitJsTrue("window.__pocketshellJourney?.bridgeEvents?.filter((event) => event.state === 'lost' "
                + "&& event.connectionId === " + JSONObject.quote(oldConnectionId) + ").length === 1 "
                + "|| window.__pocketshellJourney?.sshExecs?.[" + JSONObject.quote(triggerRequestId)
                + "]?.settled === true", 20_000);
        JSONObject triggerState = new JSONObject(evalString("JSON.stringify(window.__pocketshellJourney.sshExecs["
                + JSONObject.quote(triggerRequestId) + "])"));
        Log.i("SshPtyDockerJourney", "RUN " + runId + " ABRUPT_DROP_TRIGGER_EXEC_SETTLED " + triggerState);
        String triggerError = triggerState.optString("error").toLowerCase(java.util.Locale.ROOT);
        assertTrue("server-side SIGKILL must interrupt its SSH command through transport closure: " + triggerState,
                triggerState.optJSONObject("result") == null
                        && (triggerError.contains("broken transport") || triggerError.contains("eof")
                        || triggerError.contains("connection reset") || triggerError.contains("socket closed")));

        awaitJsTrue("window.__pocketshellJourney?.bridgeEvents?.filter((event) => event.state === 'lost' "
                + "&& event.connectionId === " + JSONObject.quote(oldConnectionId) + ").length === 1", 20_000);
        JSONObject lostEvent = findNativeLostEvent(oldConnectionId);
        Log.i("SshPtyDockerJourney", "RUN " + runId + " ABRUPT_DROP_NATIVE_LOST " + lostEvent);

        try {
            awaitJsTrue("document.querySelector('.app-shell')?.dataset.sshPhase === 'live'"
                    + " && document.querySelector('.app-shell')?.dataset.sshConnectionId !== "
                    + JSONObject.quote(oldConnectionId)
                    + " && document.querySelector('.app-shell')?.dataset.sshSelectedTag === "
                    + JSONObject.quote(expectedTag), 90_000);
        } catch (AssertionError reconnectFailure) {
            try {
                logForegroundReconnectDebug();
            } catch (Exception diagnosticFailure) {
                Log.w("SshPtyDockerJourney", "RUN " + runId + " abrupt-drop reconnect diagnostics failed: "
                        + diagnosticFailure.getClass().getSimpleName());
            }
            throw reconnectFailure;
        }

        long reconnectLiveAtEpochMs = System.currentTimeMillis();
        awaitNativeResizeAckAfter(resizeAckBeforeDrop, "abrupt-server-drop-recovered");
        String newConnectionId = currentConnectionId();
        String newGenerationId = currentGenerationId();
        assertTrue("server-side SSH termination must create a fresh native connection", !newConnectionId.isEmpty()
                && !oldConnectionId.equals(newConnectionId));
        assertTrue("server-side SSH termination must attach a fresh PTY generation", !newGenerationId.isEmpty()
                && !oldGenerationId.equals(newGenerationId));
        assertEquals("abrupt-drop recovery must select the same aplexer session ID", expectedSessionId, selectedSessionId());
        assertEquals("abrupt-drop recovery must keep the same selected session tag", expectedTag, currentSelectedTag());
        assertEquals("abrupt-drop recovery must keep the same selected session name",
                expectedSession.getString("name"), selectedSessionName());
        awaitTerminalReady("after abrupt server-side transport recovery");

        org.json.JSONArray phasesAfterDrop = new org.json.JSONArray(
                evalString("JSON.stringify(window.__pocketshellJourney?.phases ?? [])"));
        int reconnectingPhases = 0;
        int recoveredLivePhaseObservations = 0;
        java.util.Set<String> recoveredLiveAttachIdentities = new java.util.HashSet<>();
        for (int index = 0; index < phasesAfterDrop.length(); index += 1) {
            JSONObject phase = phasesAfterDrop.getJSONObject(index);
            if (phase.optLong("at") < dropRequestedAtEpochMs) continue;
            if ("reconnecting".equals(phase.optString("phase"))) reconnectingPhases += 1;
            if ("live".equals(phase.optString("phase"))
                    && newConnectionId.equals(phase.optString("connectionId"))
                    && expectedSessionId.equals(phase.optString("selectedId"))
                    && expectedTag.equals(phase.optString("tag"))) {
                recoveredLivePhaseObservations += 1;
                recoveredLiveAttachIdentities.add(phase.optString("connectionId") + ":"
                        + phase.optString("generationId"));
            }
        }
        int recoveredLivePhases = recoveredLiveAttachIdentities.size();
        assertEquals("one JS reconnect state must follow the single server-side SSH loss", 1, reconnectingPhases);
        assertTrue("fresh JS session attach must become live for the selected host session",
                recoveredLivePhaseObservations > 0);
        assertEquals("one JS live attach identity must recover the selected host session", 1, recoveredLivePhases);

        JSONObject proofResult = awaitNativeSshExec("abrupt-drop-proof-" + runId,
                "cat " + shellQuote(proofPath), 15_000);
        assertEquals("server-side transport termination proof must be read on the fresh SSH connection", 0,
                proofResult.getInt("exitCode"));
        assertFalse("server-side transport termination proof command must not time out",
                proofResult.getBoolean("timedOut"));
        String serverProof = proofResult.getString("stdout");
        assertTrue("Docker sshd must record the unique journey before killing its current SSH process",
                serverProof.contains("run_id=" + runId + "\n")
                        && serverProof.contains("server_pid=")
                        && serverProof.contains("server_uid=")
                        && serverProof.contains("sshd")
                        && serverProof.contains("testuser@pts/")
                        && serverProof.contains("signal=SIGKILL"));
        writeText(new File(artifactDirectory, "abrupt-drop-server-proof.txt"), serverProof);

        JSONObject inputBefore = terminalInputStats();
        JSONObject recoveryCheckpoint = sendMarkerAndCapture("abrupt-drop-recovered", marker, artifactDirectory);
        JSONObject inputAfter = terminalInputStats();
        assertEquals("post-reconnect PTY input must leave no pending bytes", 0, inputAfter.getInt("pending"));
        assertEquals("post-reconnect PTY input must not fail", inputBefore.getInt("failureCount"),
                inputAfter.getInt("failureCount"));
        assertTrue("post-reconnect marker command must be acknowledged by the native PTY bridge",
                inputAfter.getInt("ackCount") > inputBefore.getInt("ackCount"));
        assertEquals("post-reconnect viewport must bind to the fresh SSH connection", newConnectionId,
                recoveryCheckpoint.getString("connectionId"));
        assertEquals("post-reconnect viewport must bind to the fresh PTY generation", newGenerationId,
                recoveryCheckpoint.getString("generationId"));
        captureFullScreenPng("abrupt-drop-recovered-full-screen.png", artifactDirectory);

        JSONObject hostCaptureResult = awaitNativeSshExec("abrupt-drop-host-capture-" + runId,
                "/usr/bin/a capture --bytes 65536 " + expectedSessionId, 20_000);
        assertEquals("the recovered host CLI capture must complete successfully", 0,
                hostCaptureResult.getInt("exitCode"));
        assertFalse("the recovered host CLI capture must not time out", hostCaptureResult.getBoolean("timedOut"));
        int markerLineCount = countExactMarkerLines(hostCaptureResult.getString("stdout"), marker);
        assertEquals("a fresh host-side a capture must contain the post-reconnect PTY marker exactly once",
                1, markerLineCount);

        JSONObject result = new JSONObject()
                .put("assertions", new org.json.JSONArray()
                        .put("server-side-sshd-transport-killed")
                        .put("native-transport-loss-observed-once")
                        .put("single-js-reconnect-and-session-reattach")
                        .put("fresh-terminal-viewport-captured")
                        .put("post-reconnect-pty-bytes-present-on-host-exactly-once"))
                .put("trigger", "server-side-sshd-session-sigkill")
                .put("triggerRequestId", triggerRequestId)
                .put("triggerSignal", "SIGKILL")
                .put("triggerProofFile", "abrupt-drop-server-proof.txt")
                .put("oldConnectionId", oldConnectionId)
                .put("oldGenerationId", oldGenerationId)
                .put("newConnectionId", newConnectionId)
                .put("newGenerationId", newGenerationId)
                .put("nativeLostEventCount", 1)
                .put("reconnectingPhaseCount", reconnectingPhases)
                .put("recoveredLivePhaseCount", recoveredLivePhases)
                .put("dropRequestedAtEpochMs", dropRequestedAtEpochMs)
                .put("lossObservedAtEpochMs", lostEvent.getLong("atEpochMs"))
                .put("reconnectLiveAtEpochMs", reconnectLiveAtEpochMs)
                .put("selectedTag", expectedTag)
                .put("selectedId", expectedSessionId)
                .put("serverPid", extractProofInteger(serverProof, "server_pid="))
                .put("serverUid", extractProofInteger(serverProof, "server_uid="))
                .put("serverArgs", proofLine(serverProof, "server_args="))
                .put("hostInputMarker", marker)
                .put("hostCaptureSource", "SshCapability.exec /usr/bin/a capture --bytes 65536")
                .put("hostCaptureExactMarkerLineCount", markerLineCount)
                .put("inputAckCountBefore", inputBefore.getInt("ackCount"))
                .put("inputAckCountAfter", inputAfter.getInt("ackCount"))
                .put("inputFailureCountAfter", inputAfter.getInt("failureCount"))
                .put("inputPendingAfter", inputAfter.getInt("pending"))
                .put("recoveryCheckpoint", recoveryCheckpoint)
                .put("fullScreenPng", "abrupt-drop-recovered-full-screen.png");
        Log.i("SshPtyDockerJourney", "RUN " + runId + " ABRUPT_DROP_RECOVERED " + result);
        return result;
    }

    private String nativeExecLaunchScript(
            String requestId, String connectionId, String generationId, String command, int timeoutMs
    ) {
        return "(() => {const journey=window.__pocketshellJourney;"
                + "if(!journey) throw new Error('lifecycle recorder missing');"
                + "journey.sshExecs=journey.sshExecs||{};"
                + "const entry={settled:false,result:null,error:''};journey.sshExecs[" + JSONObject.quote(requestId) + "]=entry;"
                + "const plugin=window.Capacitor?.Plugins?.SshCapability;"
                + "if(!plugin?.exec) throw new Error('SSH exec bridge missing');"
                + "plugin.exec({requestId:" + JSONObject.quote(requestId)
                + ",connectionId:" + JSONObject.quote(connectionId)
                + ",generationId:" + JSONObject.quote(generationId)
                + ",command:" + JSONObject.quote(command)
                + ",timeoutMs:" + timeoutMs + "}).then((result)=>{entry.result=result;entry.settled=true;},(error)=>{"
                + "entry.error=String(error?.message??error);entry.settled=true;});return 'started';})()";
    }

    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private JSONObject awaitNativeSshExec(String requestId, String command, long timeoutMs) throws Exception {
        assertEquals("host exec oracle requires the recovered live connection", "live", currentPhase());
        return awaitNativeSshExecOnCurrentConnection(requestId, command, timeoutMs);
    }

    private JSONObject awaitNativeSshExecOnCurrentConnection(String requestId, String command, long timeoutMs) throws Exception {
        String connectionId = currentConnectionId();
        String generationId = currentGenerationId();
        assertTrue("host exec oracle requires an active native connection", !connectionId.isEmpty() && !generationId.isEmpty());
        assertEquals("native host exec launch must be accepted", "started", evalString(nativeExecLaunchScript(
                requestId, connectionId, generationId, command, Math.toIntExact(timeoutMs))));
        awaitJsTrue("window.__pocketshellJourney?.sshExecs?.[" + JSONObject.quote(requestId) + "]?.settled === true",
                timeoutMs + 5_000);
        JSONObject state = new JSONObject(evalString("JSON.stringify(window.__pocketshellJourney.sshExecs["
                + JSONObject.quote(requestId) + "])"));
        assertEquals("native host exec must not reject on the recovered transport: " + state.optString("error"),
                "", state.optString("error"));
        JSONObject result = state.optJSONObject("result");
        assertNotNull("native host exec result must be present", result);
        assertEquals("native host exec result must echo its request ID", requestId, result.getString("requestId"));
        assertEquals("native host exec result must echo the active connection", connectionId,
                result.getString("connectionId"));
        assertEquals("native host exec result must echo the active generation", generationId,
                result.getString("generationId"));
        return result;
    }

    private JSONObject findNativeLostEvent(String connectionId) throws Exception {
        org.json.JSONArray events = new org.json.JSONArray(
                evalString("JSON.stringify(window.__pocketshellJourney?.bridgeEvents ?? [])"));
        JSONObject found = null;
        for (int index = 0; index < events.length(); index += 1) {
            JSONObject event = events.getJSONObject(index);
            if ("lost".equals(event.optString("state")) && connectionId.equals(event.optString("connectionId"))) {
                assertNull("one abrupt server-side transport must emit exactly one native lost event", found);
                found = event;
            }
        }
        assertNotNull("server-side SSH termination must reach the native transport listener", found);
        return found;
    }

    private int countExactMarkerLines(String raw, String marker) {
        String plain = Pattern.compile("\\u001B\\[[0-?]*[ -/]*[@-~]").matcher(raw).replaceAll("");
        int count = 0;
        for (String line : plain.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            if (marker.equals(line.trim())) count += 1;
        }
        return count;
    }

    private int extractProofInteger(String proof, String prefix) {
        try {
            return Integer.parseInt(proofLine(proof, prefix));
        } catch (NumberFormatException error) {
            throw new AssertionError("server-side SSH termination proof has invalid " + prefix, error);
        }
    }

    private String proofLine(String proof, String prefix) {
        for (String line : proof.split("\\R")) {
            if (line.startsWith(prefix)) return line.substring(prefix.length()).trim();
        }
        throw new AssertionError("server-side SSH termination proof is missing " + prefix);
    }

    private long fixtureEventTimestamp(String events, String kind, String target) {
        Long found = null;
        for (String line : events.split("\\R")) {
            String[] fields = line.split("\\|", -1);
            if (fields.length == 3 && kind.equals(fields[0]) && target.equals(fields[2])) {
                assertNull("Docker fixture must report one " + kind + " event for " + target, found);
                try {
                    found = Long.parseLong(fields[1]);
                } catch (NumberFormatException error) {
                    throw new AssertionError("Docker fixture event has an invalid host timestamp: " + line, error);
                }
            }
        }
        assertNotNull("Docker fixture must report " + kind + " for " + target, found);
        return found;
    }

    private void captureFullScreenPng(String name, File artifactDirectory) throws Exception {
        Bitmap fullScreen = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull("Android must provide a same-run full-screen capture after abrupt transport recovery", fullScreen);
        File png = new File(artifactDirectory, name);
        try (FileOutputStream output = new FileOutputStream(png)) {
            assertTrue("full-screen bitmap must encode as PNG", fullScreen.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally {
            fullScreen.recycle();
        }
        assertTrue("full-screen PNG must be non-empty", png.isFile() && png.length() > 100);
        emitArtifact(name, java.nio.file.Files.readAllBytes(png.toPath()));
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
                + "window.dispatchEvent(new Event('pocketshell:terminal-geometry-request'));"
                + "const status=document.querySelector('[data-testid=terminal-resize-status]')?.textContent.trim()||'';"
                + "const accepted=status.match(/^(\\d+)\\s*[×x]\\s*(\\d+)\\s+accepted by SSH$/);"
                + "const runtime=window.__ps2875TerminalRuntimeGeometry;"
                + "const viewport=document.querySelector('#terminal-viewport');const rect=viewport?.getBoundingClientRect();"
                + "const vv=window.visualViewport;"
                + "return root?{pending:Number(root.dataset.sshTerminalResizePending||0),"
                + "ackCount:Number(root.dataset.sshTerminalResizeAcks||0),"
                + "failureCount:Number(root.dataset.sshTerminalResizeFailures||0),"
                + "status,acceptedCols:accepted?Number(accepted[1]):0,acceptedRows:accepted?Number(accepted[2]):0,"
                + "runtimeCols:Number(runtime?.cols||0),runtimeRows:Number(runtime?.rows||0),"
                + "viewportWidth:rect?.width||0,viewportHeight:rect?.height||0,"
                + "windowWidth:window.innerWidth,windowHeight:window.innerHeight,"
                + "visualViewportWidth:vv?.width??0,visualViewportHeight:vv?.height??0,"
                + "keyboardVisible:root.dataset.keyboardVisible||''}:null;})())"));
    }

    private void awaitNativeResizeAckAfter(int previousAckCount, String checkpoint) throws Exception {
        try {
            awaitJsTrue("(() => {const root=document.querySelector('.app-shell');"
                + "const status=document.querySelector('[data-testid=terminal-resize-status]')?.textContent.trim()||'';"
                + "return !!root && Number(root.dataset.sshTerminalResizeAcks||0) > " + previousAckCount
                + " && Number(root.dataset.sshTerminalResizePending||0) === 0"
                + " && Number(root.dataset.sshTerminalResizeFailures||0) === 0"
                + " && status.endsWith('accepted by SSH');})()", WAIT_TIMEOUT_MILLIS);
        } catch (AssertionError failure) {
            throw new AssertionError(checkpoint + " resize state: " + evalString("JSON.stringify((()=>{const root=document.querySelector('.app-shell');return {phase:root?.dataset.sshPhase,surface:root?.dataset.homeSurface,selected:root?.dataset.sshSelectedTag,resize:document.querySelector('[data-testid=terminal-resize-status]')?.textContent,acks:root?.dataset.sshTerminalResizeAcks,pending:root?.dataset.sshTerminalResizePending,failures:root?.dataset.sshTerminalResizeFailures}})())"), failure);
        }
        JSONObject resize = awaitStableNativeResizeState(previousAckCount, checkpoint);
        assertEquals(checkpoint + " must finish with no pending native resize", 0, resize.getInt("pending"));
        assertTrue(checkpoint + " must have a fresh native resize acknowledgement",
                resize.getInt("ackCount") > previousAckCount);
        assertEquals(checkpoint + " must not report a native resize failure", 0, resize.getInt("failureCount"));
    }

    private JSONObject awaitStableNativeResizeState(int previousAckCount, String checkpoint) throws Exception {
        long deadline = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS;
        long stableSince = -1;
        JSONObject previousSettled = null;
        while (SystemClock.uptimeMillis() < deadline) {
            JSONObject current = terminalResizeStats();
            boolean settled = isSettledResizeObservation(current, previousAckCount);
            if (!settled) {
                stableSince = -1;
                previousSettled = null;
            } else if (previousSettled == null || !sameSettledResizeState(previousSettled, current)) {
                stableSince = SystemClock.uptimeMillis();
                previousSettled = current;
            } else if (SystemClock.uptimeMillis() - stableSince >= 350) {
                return current;
            }
            Thread.sleep(100);
        }
        throw new AssertionError(checkpoint + " resize state did not remain settled: " + terminalResizeStats());
    }

    private boolean isSettledResizeObservation(JSONObject current, int previousAckCount) throws JSONException {
        return current.getInt("pending") == 0
                && current.getInt("ackCount") > previousAckCount
                && current.getInt("failureCount") == 0
                && current.getInt("acceptedCols") > 0
                && current.getInt("acceptedCols") == current.getInt("runtimeCols")
                && current.getInt("acceptedRows") == current.getInt("runtimeRows")
                && current.getDouble("viewportWidth") > 0
                && current.getDouble("viewportHeight") > 0;
    }

    private void assertPostFocusResizeRaceGuard() throws JSONException {
        JSONObject lateFit = resizeObservation(8, 0, 5, 120, 578, 578, "true")
                .put("status", "37 × 5 (local fit)")
                // The status parser only accepts the explicit SSH acknowledgement form.
                // A local-fit label therefore parses as zero accepted columns and rows.
                .put("acceptedCols", 0);
        JSONObject staleAck = resizeObservation(8, 15, 5, 120, 578, 578, "true");
        JSONObject acceptedAfterIme = resizeObservation(9, 5, 5, 120, 578, 578, "true");

        assertFalse("a post-focus local fit must not be mistaken for an accepted PTY resize",
                isSettledResizeObservation(lateFit, 7));
        assertFalse("an earlier SSH acknowledgement must not settle after the xterm grid changes",
                isSettledResizeObservation(staleAck, 7));
        assertTrue("a fresh SSH acknowledgement for the current IME-sized xterm grid must settle",
                isSettledResizeObservation(acceptedAfterIme, 7));
        assertGeometryChangesResetStability(acceptedAfterIme);
    }

    private JSONObject resizeObservation(int ackCount, int acceptedRows, int runtimeRows,
                                         double viewportHeight, int windowHeight,
                                         double visualViewportHeight, String keyboardVisible) throws JSONException {
        return new JSONObject()
                .put("pending", 0)
                .put("ackCount", ackCount)
                .put("failureCount", 0)
                .put("status", "37 × " + acceptedRows + " accepted by SSH")
                .put("acceptedCols", 37)
                .put("acceptedRows", acceptedRows)
                .put("runtimeCols", 37)
                .put("runtimeRows", runtimeRows)
                .put("viewportWidth", 370.7)
                .put("viewportHeight", viewportHeight)
                .put("windowWidth", 412)
                .put("windowHeight", windowHeight)
                .put("visualViewportWidth", 412.2)
                .put("visualViewportHeight", visualViewportHeight)
                .put("keyboardVisible", keyboardVisible);
    }

    private void assertGeometryChangesResetStability(JSONObject settled) throws JSONException {
        // Keep ACK, status, and both accepted/runtime grids identical while varying
        // each geometry signal independently. This proves the stability check is
        // sensitive to the viewport/IME transition itself.
        assertGeometryFieldChangeResetsStability(settled, "viewportWidth", 371.7);
        assertGeometryFieldChangeResetsStability(settled, "viewportHeight", 121);
        assertGeometryFieldChangeResetsStability(settled, "windowWidth", 413);
        assertGeometryFieldChangeResetsStability(settled, "windowHeight", 579);
        assertGeometryFieldChangeResetsStability(settled, "visualViewportWidth", 413.2);
        assertGeometryFieldChangeResetsStability(settled, "visualViewportHeight", 579);
        assertGeometryFieldChangeResetsStability(settled, "keyboardVisible", "false");

        assertTrue("an identical accepted grid and geometry can complete the stability window",
                sameSettledResizeState(settled, new JSONObject(settled.toString())));
    }

    private void assertGeometryFieldChangeResetsStability(JSONObject settled, String field, Object changedValue)
            throws JSONException {
        JSONObject changed = new JSONObject(settled.toString()).put(field, changedValue);
        assertFalse("a geometry-only change to " + field + " must reset the stable observation window",
                sameSettledResizeState(settled, changed));
        assertTrue("an identical follow-up after the " + field + " change can complete the stability window",
                sameSettledResizeState(changed, new JSONObject(changed.toString())));
    }

    private boolean sameSettledResizeState(JSONObject previous, JSONObject current) throws JSONException {
        return previous.getInt("ackCount") == current.getInt("ackCount")
                && previous.getString("status").equals(current.getString("status"))
                && previous.getInt("acceptedCols") == current.getInt("acceptedCols")
                && previous.getInt("acceptedRows") == current.getInt("acceptedRows")
                && previous.getInt("runtimeCols") == current.getInt("runtimeCols")
                && previous.getInt("runtimeRows") == current.getInt("runtimeRows")
                && previous.getInt("windowWidth") == current.getInt("windowWidth")
                && previous.getInt("windowHeight") == current.getInt("windowHeight")
                && previous.getString("keyboardVisible").equals(current.getString("keyboardVisible"))
                && nearlyEqual(previous.getDouble("viewportWidth"), current.getDouble("viewportWidth"))
                && nearlyEqual(previous.getDouble("viewportHeight"), current.getDouble("viewportHeight"))
                && nearlyEqual(previous.getDouble("visualViewportWidth"), current.getDouble("visualViewportWidth"))
                && nearlyEqual(previous.getDouble("visualViewportHeight"), current.getDouble("visualViewportHeight"));
    }

    private boolean nearlyEqual(double left, double right) {
        return Math.abs(left - right) < 0.5;
    }

    private void assertUnchangedViewportFitsAreCoalesced(String checkpoint) throws Exception {
        JSONObject before = terminalResizeStats();
        String viewportBefore = evalString("JSON.stringify({innerWidth,innerHeight,screenWidth:screen.width,screenHeight:screen.height,"
                + "visualWidth:visualViewport?.width??null,visualHeight:visualViewport?.height??null})");
        assertEquals(checkpoint + " must start with no pending resize", 0, before.getInt("pending"));
        assertTrue(checkpoint + " must have a settled SSH resize before the coalescing probe",
                before.getString("status").endsWith("accepted by SSH"));

        evalString("(() => {window.__ps2875ResizeProbeDone=false;window.__ps2875ResizeProbeFrames=0;"
                + "for(let i=0;i<5;i++)window.dispatchEvent(new Event('resize'));"
                + "const next=()=>{window.__ps2875ResizeProbeFrames+=1;"
                + "if(window.__ps2875ResizeProbeFrames>=4){window.__ps2875ResizeProbeDone=true;return;}"
                + "requestAnimationFrame(next);};requestAnimationFrame(next);return 'scheduled';})()");
        awaitJsTrue("window.__ps2875ResizeProbeDone===true", 5_000);
        awaitJsTrue("Number(document.querySelector('.app-shell')?.dataset.sshTerminalResizePending||0)===0", 10_000);

        JSONObject after = terminalResizeStats();
        String viewportAfter = evalString("JSON.stringify({innerWidth,innerHeight,screenWidth:screen.width,screenHeight:screen.height,"
                + "visualWidth:visualViewport?.width??null,visualHeight:visualViewport?.height??null})");
        assertEquals(checkpoint + " synthetic resize events must not alter viewport dimensions", viewportBefore, viewportAfter);
        assertEquals(checkpoint + " coalescing probe must keep the accepted PTY grid unchanged",
                before.getString("status"), after.getString("status"));
        assertEquals(checkpoint + " five unchanged window-fit notifications must not start native resizes",
                before.getInt("ackCount"), after.getInt("ackCount"));
        assertEquals(checkpoint + " unchanged window-fit notifications must not fail native resizes",
                before.getInt("failureCount"), after.getInt("failureCount"));
        assertEquals(checkpoint + " coalescing probe must end with no pending native resize", 0,
                after.getInt("pending"));
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
