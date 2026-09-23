package com.pocketshell.app.smoke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.os.SystemClock;
import android.util.Log;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Packaged Android journey over a real sshd + aplexer Docker fixture. */
@RunWith(AndroidJUnit4.class)
public final class SshPtyDockerJourneyTest {
    private static final long WAIT_TIMEOUT_MILLIS = 45_000;
    private static final long BACKGROUND_GRACE_MILLIS = 30_000;
    private ActivityScenario<MainActivity> scenario;
    private String activeRunId;

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
        String markerA = marker(runId, "A");
        String markerB = marker(runId, "B");
        String markerC = marker(runId, "C");
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
        click("[aria-label='PocketShell home']");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'home'");

        setValue("[data-testid=ssh-host]", host);
        setValue("[data-testid=ssh-port]", port);
        setValue("[data-testid=ssh-username]", "testuser");
        setValue("[data-testid=ssh-private-key]", privateKey);
        click("[data-testid=ssh-connect]");

        awaitJsTrue("!!document.querySelector('[data-testid=host-key-decision]') || !!document.querySelector('[data-testid=ssh-message]')");
        String fingerprint = evalString("document.querySelector('[data-testid=host-key-fingerprint]')?.textContent.trim() ?? ''");
        assertTrue("the real SSH server must present its SHA-256 host key; fingerprint=" + fingerprint
                + "; visible page=" + evalString("document.body.innerText"),
                fingerprint.startsWith("SHA256:") && fingerprint.length() > 20);
        click("[data-testid=trust-host-key]");
        awaitJsTrue("['connected','listing'].includes(document.querySelector('.app-shell')?.dataset.sshPhase)");
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

        JSONObject switchA = attachAndCapture(rowA, "switch-a", markerA, artifactDirectory);
        String originalConnectionId = switchA.getString("connectionId");
        checkpoints.put(switchA);
        JSONObject switchB = attachAndCapture(rowB, "switch-b", markerB, artifactDirectory);
        checkpoints.put(switchB);
        JSONObject switchC = attachAndCapture(rowC, "switch-c", markerC, artifactDirectory);
        checkpoints.put(switchC);
        JSONObject switchAReturn = attachAndCapture(rowA, "switch-a-return", markerA, artifactDirectory);
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
        JSONObject withinGrace = captureCurrent("background-within-grace", markerA, artifactDirectory);
        checkpoints.put(withinGrace);

        backgroundBeyondGrace(originalConnectionId);
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.sshPhase === 'live'"
                + " && document.querySelector('.app-shell')?.dataset.sshConnectionId !== "
                + JSONObject.quote(originalConnectionId), 90_000);
        String reconnectedId = currentConnectionId();
        assertTrue("expiry must create a new physical SSH connection", !reconnectedId.isEmpty() && !originalConnectionId.equals(reconnectedId));
        assertEquals("expiry must reattach the same host session", sessionA, currentSelectedTag());
        assertFalse("expiry must replace the SSH transport generation", switchAReturn.getString("generationId").equals(currentGenerationId()));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        String markerAfterExpiry = markerA + "_AFTER_EXPIRY";
        InstrumentationRegistry.getInstrumentation().sendStringSync("printf '%s\\n' '" + markerAfterExpiry + "'\n");
        awaitJsTrue("document.querySelector('#terminal-viewport .xterm-rows')?.textContent.includes(" + JSONObject.quote(markerAfterExpiry) + ")", 20_000);
        JSONObject afterExpiry = captureCurrent("reconnected-after-expiry", markerAfterExpiry, artifactDirectory);
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
                .put("expiryBridgeEventObserved", true)
                .put("connectionAfterExpiry", reconnectedId)
                .put("sessions", new org.json.JSONArray().put(rowA).put(rowB).put(rowC))
                .put("checkpoints", checkpoints)
                .put("phaseEvents", phaseEvents)
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
        click("[data-session-tag=\"" + tag + "\"]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.sshPhase === 'live'"
                + " && document.querySelector('.app-shell')?.dataset.sshSelectedTag === " + JSONObject.quote(tag));
        assertEquals("selected session identity must come from the live host row", row.getString("name"), selectedSessionName());
        assertEquals("selected session ID must come from the live host row", row.getString("id"), selectedSessionId());
        assertEquals("selected workspace must come from the live host row", row.getString("workspace"), selectedWorkspace());
        awaitJsTrue("document.querySelector('[data-testid=terminal-resize-status]')?.textContent.includes('accepted by SSH')");
        awaitJsTrue("!!document.querySelector('#terminal-viewport .xterm-helper-textarea')");
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        InstrumentationRegistry.getInstrumentation().sendStringSync("printf '%s\\n' '" + marker + "'\n");
        awaitJsTrue("document.querySelector('#terminal-viewport .xterm-rows')?.textContent.includes(" + JSONObject.quote(marker) + ")");
        JSONObject checkpointData = captureCurrent(checkpoint, marker, artifactDirectory);
        Log.i("SshPtyDockerJourney", "RUN " + row.getString("tag") + " CHECKPOINT " + checkpointData);
        return checkpointData;
    }

    private JSONObject captureCurrent(String checkpoint, String marker, File artifactDirectory) throws Exception {
        evalString("(() => {document.querySelector('#terminal-title')?.scrollIntoView({block:'start',behavior:'instant'});return 'scrolled';})()");
        awaitJsTrue("(() => {const rect=document.querySelector('#terminal-viewport')?.getBoundingClientRect();"
                + "return !!rect && rect.top >= 0 && rect.bottom <= innerHeight && rect.left >= 0 && rect.right <= innerWidth;})()");
        String text = terminalViewportText();
        assertTrue("terminal viewport must contain the marker for " + checkpoint + ": " + text, text.contains(marker));
        JSONObject rect = new JSONObject(evalString("JSON.stringify((() => {const r=document.querySelector('#terminal-viewport')?.getBoundingClientRect();"
                + "return r ? {left:r.left,top:r.top,right:r.right,bottom:r.bottom,width:r.width,height:r.height} : null;})())"));
        assertTrue("terminal viewport must have positive visible dimensions", rect.getDouble("width") > 0 && rect.getDouble("height") > 0);
        captureViewportPng(checkpoint, rect, artifactDirectory);
        writeText(new File(artifactDirectory, checkpoint + "-visible-terminal.txt"), text);
        return new JSONObject()
                .put("checkpoint", checkpoint)
                .put("phase", currentPhase())
                .put("selectedName", selectedSessionName())
                .put("selectedId", selectedSessionId())
                .put("workspace", selectedWorkspace())
                .put("tag", currentSelectedTag())
                .put("connectionId", currentConnectionId())
                .put("generationId", currentGenerationId())
                .put("marker", marker)
                .put("visibleTerminalFile", checkpoint + "-visible-terminal.txt")
                .put("viewportPng", checkpoint + "-viewport.png")
                .put("viewportRect", rect);
    }

    private void backgroundAndResumeWithinGrace() throws Exception {
        scenario.moveToState(Lifecycle.State.CREATED);
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.sshPhase === 'background'");
        SystemClock.sleep(1_500);
        scenario.moveToState(Lifecycle.State.RESUMED);
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.sshPhase === 'live'");
        SystemClock.sleep(1_000);
    }

    private void backgroundBeyondGrace(String expectedConnectionId) throws Exception {
        scenario.moveToState(Lifecycle.State.CREATED);
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.sshPhase === 'background'");
        SystemClock.sleep(BACKGROUND_GRACE_MILLIS + 2_500);
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.sshPhase === 'background'"
                + " && !document.querySelector('.app-shell')?.dataset.sshConnectionId", 10_000);
        assertEquals("native grace-expired bridge event must clear the spent transport before foreground", "", currentConnectionId());
        Log.i("SshPtyDockerJourney", "RUN " + activeRunId + " BRIDGE grace-expired connectionId=" + expectedConnectionId);
        scenario.moveToState(Lifecycle.State.RESUMED);
    }

    private void installPhaseRecorder(String runId) throws Exception {
        String script = "(() => {const root=document.querySelector('.app-shell'); if(!root) throw new Error('app shell missing');"
                + "window.__pocketshellJourney={runId:" + JSONObject.quote(runId) + ",phases:[]};"
                + "const sample=()=>{const d=root.dataset; const next={at:Date.now(),phase:d.sshPhase,"
                + "connectionId:d.sshConnectionId,generationId:d.sshGenerationId,selectedName:d.sshSelectedSession,"
                + "selectedId:d.sshSelectedSessionId,workspace:d.sshSelectedWorkspace,tag:d.sshSelectedTag,"
                + "retryAttempt:Number(d.sshRetryAttempt||0)}; const last=window.__pocketshellJourney.phases.at(-1);"
                + "if(!last||Object.keys(next).some((key)=>last[key]!==next[key])) window.__pocketshellJourney.phases.push(next);};"
                + "sample(); window.__pocketshellJourney.observer=new MutationObserver(sample);"
                + "window.__pocketshellJourney.observer.observe(root,{attributes:true}); return 'installed';})()";
        assertEquals("installed", evalString(script));
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

    private void captureViewportPng(String checkpoint, JSONObject rect, File artifactDirectory) throws Exception {
        AtomicReference<int[]> bounds = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            android.view.View decor = activity.getWindow().getDecorView();
            WebView view = decor instanceof WebView ? (WebView) decor : findWebView((android.view.ViewGroup) decor);
            assertNotNull("the packaged activity must contain its Capacitor WebView", view);
            int[] location = new int[2];
            view.getLocationOnScreen(location);
            float scale = view.getScale();
            bounds.set(new int[] {
                    location[0] + Math.round((float) rect.optDouble("left") * scale),
                    location[1] + Math.round((float) rect.optDouble("top") * scale),
                    location[0] + Math.round((float) rect.optDouble("right") * scale),
                    location[1] + Math.round((float) rect.optDouble("bottom") * scale),
            });
            latch.countDown();
        });
        assertTrue("WebView viewport bounds callback timed out", latch.await(10, TimeUnit.SECONDS));
        Bitmap full = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull("Android must provide a same-run viewport screenshot", full);
        int[] crop = bounds.get();
        assertNotNull("viewport crop coordinates must be recorded", crop);
        assertTrue("viewport crop must stay within the captured device image", crop[0] >= 0 && crop[1] >= 0
                && crop[2] <= full.getWidth() && crop[3] <= full.getHeight() && crop[2] > crop[0] && crop[3] > crop[1]);
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
        }
        Log.i("PocketshellJourneyAsset", "END|" + activeRunId + "|" + name);
    }

    private String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private String marker(String runId, String session) {
        return "PS2861_" + runId.toUpperCase().replaceAll("[^A-Z0-9]", "_") + "_" + session + "_VIEWPORT";
    }

    private String terminalViewportText() throws Exception {
        return evalString("document.querySelector('#terminal-viewport .xterm-rows')?.textContent ?? ''");
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
