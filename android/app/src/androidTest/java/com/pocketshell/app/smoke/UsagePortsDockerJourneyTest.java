package com.pocketshell.app.smoke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.WebView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.pocketshell.app.MainActivity;

import org.json.JSONArray;
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
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Usage and tunnel policy through the packaged JS app, Docker host and Android SSH plugin. */
@RunWith(AndroidJUnit4.class)
public final class UsagePortsDockerJourneyTest {
    private static final long WAIT_TIMEOUT_MILLIS = 45_000;
    private ActivityScenario<MainActivity> scenario;
    private String activeRunId;
    private File artifactDirectory;
    private String activeSessionTag;
    private int httpRemotePort;
    private boolean fixtureHttpServerMayBeRunning;

    @Before
    public void launchPackagedShell() {
        scenario = ActivityScenario.launch(MainActivity.class);
    }

    @After
    public void closeShell() {
        if (fixtureHttpServerMayBeRunning && scenario != null && activeRunId != null) {
            try {
                String stem = serverStem(activeRunId);
                String cleanupMarker = marker(activeRunId, "HTTP_CLEANUP");
                sendComposerCommandAndAwaitMarker(
                        "pidfile=" + stem + ".pid; checkfile=" + stem + ".cleanup-check; "
                                + "if [ -r \"$pidfile\" ]; then pid=\"$(cat \"$pidfile\" 2>/dev/null || true)\"; "
                                + "case \"$pid\" in ''|*[!0-9]*) printf 'decision=cleanup-invalid-pid\\n' > \"$checkfile\";; "
                                + "*) if [ -r \"/proc/$pid/cmdline\" ]; then "
                                + "args=\"$(tr '\\000' ' ' < \"/proc/$pid/cmdline\" 2>/dev/null || true)\"; "
                                + "case \"$args\" in *'python3 -m http.server " + httpRemotePort
                                + " --bind 127.0.0.1'*) printf 'pid=%s\\ncmdline=%s\\ndecision=cleanup-kill\\n' \"$pid\" \"$args\" > \"$checkfile\"; "
                                + "kill \"$pid\" 2>/dev/null || true;; "
                                + "*) printf 'pid=%s\\ncmdline=%s\\ndecision=cleanup-identity-mismatch\\n' \"$pid\" \"$args\" > \"$checkfile\";; esac; "
                                + "else printf 'pid=%s\\ncmdline=<missing>\\ndecision=already-stopped\\n' \"$pid\" > \"$checkfile\"; fi;; esac; fi; "
                                + "sleep 1; printf '%s\\n' '" + cleanupMarker + "'",
                        cleanupMarker, "cleanup test HTTP service", activeSessionTag, "HTTP_CLEANUP");
            } catch (Exception | AssertionError cleanupFailure) {
                Log.w("UsagePortsDockerJourney", "RUN " + activeRunId + " HTTP fixture cleanup failed: "
                        + cleanupFailure.getClass().getSimpleName());
            }
        }
        if (scenario != null) scenario.close();
    }

    @Test
    public void usageAndPortForwardingPoliciesUseDockerAndNativePlugin() throws Exception {
        try {
            runUsageAndPortForwardingPoliciesUseDockerAndNativePlugin();
        } catch (Throwable failure) {
            try {
                captureFailureDiagnostics(failure);
            } catch (Throwable diagnosticFailure) {
                failure.addSuppressed(diagnosticFailure);
                Log.e("UsagePortsDockerJourney", "RUN " + activeRunId
                        + " failure diagnostics could not be fully captured", diagnosticFailure);
            }
            if (failure instanceof Exception) throw (Exception) failure;
            if (failure instanceof Error) throw (Error) failure;
            throw new AssertionError(failure);
        }
    }

    private void runUsageAndPortForwardingPoliciesUseDockerAndNativePlugin() throws Exception {
        var arguments = InstrumentationRegistry.getArguments();
        String host = arguments.getString("sshHost", "10.0.2.2");
        String port = arguments.getString("sshPort");
        String encodedKey = arguments.getString("sshPrivateKeyBase64");
        String runId = arguments.getString("sshSessionName", "js2859-" + System.currentTimeMillis());
        activeRunId = runId.matches("[A-Za-z0-9][A-Za-z0-9_-]{2,38}") ? runId : "usage-ports-failure";
        httpRemotePort = 8_000 + Math.floorMod(activeRunId.hashCode(), 2_001);
        artifactDirectory = new File(
                InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null),
                "pocketshell-usage-ports/" + activeRunId);
        assertTrue("run artifact directory must be new", artifactDirectory.mkdirs());
        assertNotNull("pass the Docker fixture port with sshPort", port);
        assertNotNull("pass the test-only key with sshPrivateKeyBase64", encodedKey);
        String privateKey = new String(Base64.getDecoder().decode(encodedKey), StandardCharsets.UTF_8);
        assertTrue("run ID must be a safe, unique fixture tag prefix",
                runId.matches("[A-Za-z0-9][A-Za-z0-9_-]{2,38}"));
        activeRunId = runId;
        String sessionTag = runId + "-usage";
        activeSessionTag = sessionTag;

        awaitJsTrue("document.querySelector('[data-testid=build-status] > span:nth-child(2)')?.textContent.trim() === 'Build verified'");
        installJsFailureProbe();
        setValue("[data-testid=ssh-host]", host);
        setValue("[data-testid=ssh-port]", port);
        setValue("[data-testid=ssh-username]", "testuser");
        setValue("[data-testid=ssh-private-key]", privateKey);
        click("[data-testid=ssh-connect]");
        awaitJsTrue("!!document.querySelector('[data-testid=host-key-decision]')"
                + " || ['connected','listing'].includes(document.querySelector('.app-shell')?.dataset.sshPhase)");
        if ("true".equals(evalString("!!document.querySelector('[data-testid=host-key-decision]')"))) {
            String fingerprint = evalString("document.querySelector('[data-testid=host-key-fingerprint]')?.textContent.trim() ?? ''");
            assertTrue("the Docker SSH server must present its real host-key fingerprint",
                    fingerprint.startsWith("SHA256:") && fingerprint.length() > 20);
            click("[data-testid=trust-host-key]");
        }
        awaitJsTrue("['connected','listing'].includes(document.querySelector('.app-shell')?.dataset.sshPhase)");
        awaitJsTrue("!!document.querySelector('[data-testid=refresh-sessions]')");
        String firstConnectionId = currentConnectionId();
        String firstGenerationId = currentGenerationId();

        setValue("[data-testid=new-session-name]", sessionTag);
        click("[data-testid=create-session]");
        awaitJsTrue("Array.from(document.querySelectorAll('[data-session-tag]')).some((node) => node.dataset.sessionTag === "
                + JSONObject.quote(sessionTag) + ")");
        String sessionId = evalString("document.querySelector('[data-session-tag=\"" + sessionTag
                + "\"]')?.dataset.sessionId ?? ''");
        assertTrue("the Docker session must expose its aplexer UUID", sessionId.matches("[a-f0-9-]{36}"));
        click("[data-session-tag=\"" + sessionTag + "\"]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.sshPhase === 'live'"
                + " && document.querySelector('.app-shell')?.dataset.sshSelectedTag === " + JSONObject.quote(sessionTag));
        awaitTerminalReady();

        String stem = serverStem(runId);
        String serverStartedMarker = marker(runId, "HTTP_STARTED");
        String serverStoppedMarker = marker(runId, "HTTP_STOPPED");
        fixtureHttpServerMayBeRunning = true;
        sendComposerCommandAndAwaitMarker(
                "python3 -m http.server " + httpRemotePort + " --bind 127.0.0.1 >" + stem
                        + ".log 2>&1 & echo $! > " + stem + ".pid; sleep 0.5; printf '%s\\n' '"
                        + serverStartedMarker + "'",
                serverStartedMarker, "start test HTTP service", sessionTag, "HTTP_START");

        click("[aria-label='Settings']");
        click("[data-testid=open-usage]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'usage'");
        awaitJsTrue("!document.querySelector('[data-testid=usage-loading]')"
                + " && document.querySelector('[data-testid=usage-provider][data-provider=claude]')?.dataset.state === 'blocked'"
                + " && document.querySelector('[data-testid=usage-provider][data-provider=copilot]')?.dataset.state === 'approaching'"
                + " && document.querySelector('[data-testid=usage-provider][data-provider=fixture-critical]')?.dataset.state === 'critical'"
                + " && document.querySelector('[data-testid=usage-provider][data-provider=fixture-error]')?.dataset.state === 'error'"
                + " && !!document.querySelector('[data-testid=usage-provider][data-provider=codex] [data-testid=usage-reset-credits]')");
        String providerUsageText = evalString("document.querySelector('[data-testid=usage-provider][data-provider=fixture-error]')?.innerText ?? ''");
        assertTrue("the host provider error must be displayed verbatim", providerUsageText.contains("fixture provider credentials unavailable"));
        String usageProviders = evalString("JSON.stringify(Array.from(document.querySelectorAll('[data-testid=usage-provider]'))"
                + ".map((row)=>({provider:row.dataset.provider,state:row.dataset.state})))");
        assertEquals("reading usage must use the same live SSH transport", firstConnectionId,
                evalString("document.querySelector('.app-shell')?.dataset.sshConnectionId ?? ''"));
        captureScreenArtifact("provider-usage", artifactDirectory);

        click("[aria-label='Back']");
        click("[data-testid=open-ports]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'ports'");
        awaitJsTrue("!!document.querySelector('[data-testid=ports-screen]')");
        awaitJsTrue("Number(document.querySelector('[data-testid=ports-screen]')?.dataset.scanCount) > 0"
                + " && !document.querySelector('[data-testid=ports-loading]')"
                + " && !document.querySelector('[data-testid=port-scan]')?.disabled");
        String scanReadiness = evalString("(() => {const rows=document.querySelector('[data-testid=port-row-list]');"
                + "return rows?.dataset.scanOk === 'true' && !document.querySelector('[data-testid=port-scan-error]');})()");
        assertEquals("the initial Ports scan must complete successfully before checking discovered rows",
                "true", scanReadiness);
        String httpPortSelector = jsSelector(portRowSelector(httpRemotePort) + "[data-forwarded=true]");
        String sshPortSelector = jsSelector(portRowSelector(22));
        awaitJsTrue("Array.from(document.querySelectorAll('[data-testid=port-row]'))"
                + ".some((row)=>Number(row.dataset.remotePort)>10000)");
        String outOfRangePortValue = evalString("Array.from(document.querySelectorAll('[data-testid=port-row]'))"
                + ".map((row)=>Number(row.dataset.remotePort)).find((port)=>port>10000)?.toString() ?? ''");
        assertTrue("the fixture must expose a listener above the automatic forwarding range: " + outOfRangePortValue,
                outOfRangePortValue.matches("[0-9]{1,5}"));
        int outOfRangeRemotePort = Integer.parseInt(outOfRangePortValue);
        String outOfRangePortSelector = jsSelector(portRowSelector(outOfRangeRemotePort));
        awaitJsTrue("!!" + httpPortSelector
                + " && !!" + sshPortSelector
                + " && !!" + outOfRangePortSelector
                + " && !document.querySelector('[data-testid=port-scan]')?.disabled");
        String sshRowForwarded = evalString(sshPortSelector + "?.dataset.forwarded ?? ''");
        assertEquals("the low sshd port must be discovered but excluded from automatic forwarding", "false", sshRowForwarded);
        String outOfRangePortForwarded = evalString(outOfRangePortSelector + "?.dataset.forwarded ?? ''");
        assertEquals("the discovered Docker DNS listener above the automatic range must not be forwarded",
                "false", outOfRangePortForwarded);
        String automaticEndpoint = evalString(jsSelector(portRowSelector(httpRemotePort) + " [data-testid=port-forward-local]")
                + "?.innerText ?? ''");
        assertTrue("the interesting in-range listener must use the shared automatic policy: " + automaticEndpoint,
                automaticEndpoint.contains("auto"));
        int automaticLocalPort = portForwardLocalPort(httpRemotePort);
        captureScreenArtifact("ports-auto-forward", artifactDirectory);
        String httpStatus = readLocalHttpStatus(automaticLocalPort);
        assertTrue("the automatic Android tunnel must reach the Docker HTTP listener: " + httpStatus,
                httpStatus.startsWith("HTTP/1.0 200") || httpStatus.startsWith("HTTP/1.1 200"));
        int scansBeforeStop = portScanCount();
        assertTrue("the in-range listener must have been discovered by a completed scan", scansBeforeStop > 0);

        assertEquals("HTTP cleanup must start from the Ports screen", "ports",
                evalString("document.querySelector('.app-shell')?.dataset.route ?? ''"));
        assertEquals("the live Composer must be absent from the visible Usage/Ports route", "false",
                evalString(visibleComposerExpression()));
        assertEquals("the selected session must still have a live PTY before cleanup", "live",
                evalString("document.querySelector('.app-shell')?.dataset.sshPhase ?? ''"));
        Log.i("UsagePortsDockerJourney", "RUN " + runId
                + " HTTP_CLEANUP_START route=ports composerVisible=false sshPhase=live selectedTag=" + sessionTag);
        String stopCheckFile = stem + ".stop-check";
        stopHttpServerStrictly(runId, stem, stopCheckFile, httpRemotePort, sessionTag);
        fixtureHttpServerMayBeRunning = false;

        click("[aria-label='Settings']");
        click("[data-testid=open-ports]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'ports'");
        awaitJsTrue("Number(document.querySelector('[data-testid=ports-screen]')?.dataset.scanCount) === "
                + (scansBeforeStop + 1) + " && !document.querySelector('[data-testid=port-scan]')?.disabled");
        boolean autoListenerAfterFirstMissingScan = canConnectToLocalPort(automaticLocalPort);
        assertTrue("the first successful missing-port scan must retain the bounded auto tunnel",
                autoListenerAfterFirstMissingScan);
        click("[data-testid=port-scan]");
        awaitJsTrue("Number(document.querySelector('[data-testid=ports-screen]')?.dataset.scanCount) === "
                + (scansBeforeStop + 2) + " && !document.querySelector('[data-testid=port-scan]')?.disabled");
        awaitJsTrue("!" + jsSelector(portRowSelector(httpRemotePort)));
        boolean autoListenerAfterSecondMissingScan = canConnectToLocalPort(automaticLocalPort);
        assertFalse("the second successful missing-port scan must close the auto tunnel",
                autoListenerAfterSecondMissingScan);

        setValue("[data-testid=port-manual-input]", "22");
        click("[data-testid=port-manual-add]");
        awaitJsTrue("!!" + jsSelector(portRowSelector(22) + "[data-forwarded=true]"));
        String manualEndpoint = evalString(jsSelector(portRowSelector(22) + " [data-testid=port-forward-local]")
                + "?.innerText ?? ''");
        assertTrue("explicit low-port selection must be owned as a manual tunnel: " + manualEndpoint,
                manualEndpoint.contains("manual"));
        int manualLocalPort = portForwardLocalPort(22);
        String sshBanner = readLocalServiceBanner(manualLocalPort);
        assertTrue("manual local tunnel must reach the Docker fixture sshd: " + sshBanner,
                sshBanner.startsWith("SSH-2.0-"));
        captureScreenArtifact("ports-manual-forward", artifactDirectory);
        String storedPreferences = evalString("(() => {const key=Object.keys(localStorage).find((name)=>"
                + "name.startsWith('pocketshell.js.port-preferences.v1.')); return key ? localStorage.getItem(key) : '';})()");
        JSONObject preferences = new JSONObject(storedPreferences);
        assertEquals("only the explicit low port should be manually desired", 1,
                preferences.getJSONArray("desiredManualPorts").length());
        assertEquals("manual tunnel intent must be persisted per host", 22,
                preferences.getJSONArray("desiredManualPorts").getInt(0));

        click("[aria-label='PocketShell home']");
        String oldConnectionId = currentConnectionId();
        String oldGenerationId = currentGenerationId();
        click("[data-testid=ssh-disconnect]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.sshPhase === 'idle'"
                + " && document.querySelector('[data-testid=ssh-resources]')?.dataset.snapshotState === 'verified'"
                + " && document.querySelector('[data-testid=ssh-resource-forwards]')?.textContent.trim() === '0'");
        boolean manualListenerAfterDisconnect = canConnectToLocalPort(manualLocalPort);
        assertFalse("disconnect must release the manual local listener", manualListenerAfterDisconnect);
        int forwardsAfterDisconnect = Integer.parseInt(evalString(
                "document.querySelector('[data-testid=ssh-resource-forwards]')?.textContent.trim() ?? '-1'"));

        click("[data-testid=ssh-connect]");
        awaitJsTrue("!!document.querySelector('[data-testid=host-key-decision]')"
                + " || ['connected','listing'].includes(document.querySelector('.app-shell')?.dataset.sshPhase)");
        if ("true".equals(evalString("!!document.querySelector('[data-testid=host-key-decision]')"))) {
            click("[data-testid=trust-host-key]");
        }
        awaitJsTrue("['connected','listing'].includes(document.querySelector('.app-shell')?.dataset.sshPhase)"
                + " && document.querySelector('.app-shell')?.dataset.sshConnectionId !== " + JSONObject.quote(oldConnectionId)
                + " && document.querySelector('.app-shell')?.dataset.sshGenerationId !== " + JSONObject.quote(oldGenerationId));
        String reconnectedId = currentConnectionId();
        String reconnectedGeneration = currentGenerationId();
        click("[aria-label='Settings']");
        click("[data-testid=open-ports]");
        awaitJsTrue("!!" + jsSelector(portRowSelector(22) + "[data-forwarded=true]")
                + " && !document.querySelector('[data-testid=port-scan]')?.disabled");
        int reconnectedManualLocalPort = portForwardLocalPort(22);
        String reconnectedBanner = readLocalServiceBanner(reconnectedManualLocalPort);
        assertTrue("JS desired tunnel policy must reopen the manual port on the new SSH generation: " + reconnectedBanner,
                reconnectedBanner.startsWith("SSH-2.0-"));
        assertEquals("manual tunnel row must be bound to the new generation", reconnectedGeneration,
                evalString("document.querySelector('.app-shell')?.dataset.sshGenerationId ?? ''"));

        click("[aria-label='PocketShell home']");
        click("[data-testid=ssh-disconnect]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.sshPhase === 'idle'"
                + " && document.querySelector('[data-testid=ssh-resources]')?.dataset.snapshotState === 'verified'"
                + " && document.querySelector('[data-testid=ssh-resource-forwards]')?.textContent.trim() === '0'");
        boolean reconnectedListenerAfterDisconnect = canConnectToLocalPort(reconnectedManualLocalPort);
        assertFalse("final native cleanup must release the reconnected manual listener",
                reconnectedListenerAfterDisconnect);
        int forwardsAfterFinalDisconnect = Integer.parseInt(evalString(
                "document.querySelector('[data-testid=ssh-resource-forwards]')?.textContent.trim() ?? '-1'"));

        JSONObject summary = new JSONObject()
                .put("schema", 1)
                .put("runId", runId)
                .put("sessionTag", sessionTag)
                .put("sessionId", sessionId)
                .put("initialConnectionId", firstConnectionId)
                .put("initialGenerationId", firstGenerationId)
                .put("connectionBeforeReconnect", oldConnectionId)
                .put("generationBeforeReconnect", oldGenerationId)
                .put("reconnectedConnectionId", reconnectedId)
                .put("reconnectedGenerationId", reconnectedGeneration)
                .put("httpRemotePort", httpRemotePort)
                .put("httpStartedMarker", serverStartedMarker)
                .put("httpStoppedMarker", serverStoppedMarker)
                .put("httpStatus", httpStatus)
                .put("outOfRangeRemotePort", outOfRangeRemotePort)
                .put("outOfRangeListenerForwarded", false)
                .put("autoLocalPort", automaticLocalPort)
                .put("autoListenerAfterFirstMissingScan", autoListenerAfterFirstMissingScan)
                .put("autoListenerAfterSecondMissingScan", autoListenerAfterSecondMissingScan)
                .put("firstMissingScanRetained", autoListenerAfterFirstMissingScan)
                .put("secondMissingScanClosed", !autoListenerAfterSecondMissingScan)
                .put("manualRemotePort", 22)
                .put("manualLocalPort", manualLocalPort)
                .put("manualSshBanner", sshBanner)
                .put("reconnectedManualLocalPort", reconnectedManualLocalPort)
                .put("reconnectedSshBanner", reconnectedBanner)
                .put("manualListenerAfterDisconnect", manualListenerAfterDisconnect)
                .put("reconnectedListenerAfterDisconnect", reconnectedListenerAfterDisconnect)
                .put("nativeForwardCountAfterDisconnect", forwardsAfterDisconnect)
                .put("nativeForwardCountAfterFinalDisconnect", forwardsAfterFinalDisconnect)
                .put("usageProviders", new JSONTokener(usageProviders).nextValue())
                .put("screenshots", new org.json.JSONArray()
                        .put("provider-usage.png")
                        .put("ports-auto-forward.png")
                        .put("ports-manual-forward.png"));
        writeText(new File(artifactDirectory, "journey-summary.json"), summary.toString(2));
        Log.i("UsagePortsDockerJourney", "RUN " + runId + " " + summary);
    }

    private String serverStem(String runId) {
        return "/tmp/pocketshell-" + runId + "-usage-ports";
    }

    private String marker(String runId, String phase) throws Exception {
        String suffix;
        if ("HTTP_STARTED".equals(phase)) suffix = "HS";
        else if ("HTTP_STOPPED".equals(phase)) suffix = "HE";
        else if ("HTTP_CLEANUP".equals(phase)) suffix = "HC";
        else throw new IllegalArgumentException("unknown usage/ports marker phase: " + phase);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(runId.getBytes(StandardCharsets.UTF_8));
        StringBuilder token = new StringBuilder();
        for (int index = 0; index < 5; index += 1) token.append(String.format(java.util.Locale.ROOT, "%02X", digest[index] & 0xff));
        return "REMOTE_OUTPUT_" + token + "_" + suffix;
    }

    private void stopHttpServerStrictly(String runId, String stem, String checkFile, int remotePort,
                                        String sessionTag) throws Exception {
        String pidFile = stem + ".pid";
        String stoppedMarker = marker(runId, "HTTP_STOPPED");
        String command = "f=" + pidFile + "; c=" + checkFile + "; "
                + "[ -r \"$f\" ] || { echo decision=missing-pidfile >\"$c\"; exit 1; }; "
                + "p=$(cat \"$f\" 2>/dev/null); case \"$p\" in ''|*[!0-9]*) echo decision=invalid-pid >\"$c\"; exit 1;; esac; "
                + "if [ ! -r /proc/$p/cmdline ]; then printf 'pid=%s\\ncmdline=<missing>\\ndecision=already-stopped\\n' \"$p\" >\"$c\"; exit 1; fi; "
                + "a=$(tr '\\000' ' ' </proc/$p/cmdline); printf 'pid=%s\\ncmdline=%s\\n' \"$p\" \"$a\" >\"$c\"; "
                + "case \"$a\" in *'python3 -m http.server " + remotePort + " --bind 127.0.0.1'*) "
                + "echo decision=matched-kill >>\"$c\"; kill \"$p\" || { echo exitDecision=kill-failed >>\"$c\"; exit 1; };; "
                + "*) echo decision=identity-mismatch >>\"$c\"; exit 1;; esac; "
                + "for i in 1 2 3 4 5; do [ ! -e /proc/$p ] && break; sleep .2; done; "
                + "s=$(awk '{print $3}' /proc/$p/stat 2>/dev/null); "
                + "if [ -e /proc/$p ] && [ \"$s\" != Z ]; then echo exitDecision=still-running >>\"$c\"; exit 1; fi; "
                + "echo exitDecision=process-exited >>\"$c\"; printf '%s\\n' '" + stoppedMarker + "'";
        // Send the long fixture-control command through the packaged composer; keyboard injection can reorder PTY bytes.
        sendComposerCommandAndAwaitMarker(
                command, stoppedMarker, "stop test HTTP service", sessionTag, "HTTP_CLEANUP");
    }

    private void sendCommandAndAwaitMarker(String command, String marker, String checkpoint) throws Exception {
        JSONObject before = terminalInputStats();
        assertEquals("terminal input must be drained before " + checkpoint, 0, before.getInt("pending"));
        awaitTerminalReady();
        InstrumentationRegistry.getInstrumentation().sendStringSync(command + "\n");
        try {
            awaitExactMarkerRow(marker, checkpoint);
        } catch (AssertionError missingMarker) {
            throw new AssertionError(missingMarker.getMessage()
                    + "; terminalInputStats=" + terminalInputStats(), missingMarker);
        }
        waitForTerminalInputDrain(before.getInt("ackCount"), before.getInt("failureCount"), checkpoint);
    }

    private void sendComposerCommandAndAwaitMarker(String command, String marker, String checkpoint,
                                                   String sessionTag, String eventPrefix) throws Exception {
        String draftBeforeOpen = evalString("document.querySelector('[data-testid=prompt-draft]')?.value ?? ''");
        JSONObject inputBeforeOpen = terminalInputStats();
        openHomeLiveComposerAndAwaitConnectedTransport(sessionTag, eventPrefix);
        awaitJsTrue(visibleComposerExpression(), 15_000);
        assertEquals("opening Home Composer must preserve the existing draft", draftBeforeOpen,
                evalString("document.querySelector('[data-testid=prompt-draft]')?.value ?? ''"));
        JSONObject inputAfterOpen = terminalInputStats();
        assertEquals("opening Home Composer must not write terminal input",
                inputBeforeOpen.getInt("ackCount"), inputAfterOpen.getInt("ackCount"));
        assertEquals("opening Home Composer must not add terminal input failures",
                inputBeforeOpen.getInt("failureCount"), inputAfterOpen.getInt("failureCount"));
        assertEquals("opening Home Composer must leave terminal input pending count unchanged",
                inputBeforeOpen.getInt("pending"), inputAfterOpen.getInt("pending"));
        JSONObject before = terminalInputStats();
        assertEquals("terminal input must be drained before " + checkpoint, 0, before.getInt("pending"));
        assertEquals("terminal input failures must remain zero before " + checkpoint, 0, before.getInt("failureCount"));
        awaitJsTrue(visibleComposerExpression()
                + " && document.querySelector('[data-testid=prompt-composer]')?.dataset.transportState === 'connected'",
                15_000);
        Log.i("UsagePortsDockerJourney", "RUN " + activeRunId
                + " " + eventPrefix + "_COMPOSER_READY route=home homeSurface=live composerVisible=true "
                + "transportState=connected selectedTag=" + sessionTag);
        setValue("[data-testid=prompt-draft]", command);
        awaitJsTrue("document.querySelector('[data-testid=prompt-draft]')?.value === " + JSONObject.quote(command));
        click(".composer-shared-controls .send");
        awaitJsTrue("document.querySelector('[data-testid=composer-status]')?.dataset.deliveryState === 'success'"
                + " && document.querySelector('[data-testid=composer-status]')?.textContent.includes('Sent to the terminal')"
                + " && Number(document.querySelector('[data-testid=prompt-composer]')?.dataset.acknowledgedWrites) > 0"
                + " && document.querySelector('[data-testid=prompt-draft]')?.value === ''", 20_000);
        awaitExactMarkerRow(marker, checkpoint);
        JSONObject after = terminalInputStats();
        assertEquals("terminal input must remain drained after " + checkpoint, 0, after.getInt("pending"));
        assertEquals("terminal input failures must remain unchanged after " + checkpoint,
                before.getInt("failureCount"), after.getInt("failureCount"));
    }

    private void openHomeLiveComposerAndAwaitConnectedTransport(String sessionTag, String eventPrefix) throws Exception {
        if (!"home".equals(evalString("document.querySelector('.app-shell')?.dataset.route ?? ''"))) {
            click("[aria-label='PocketShell home']");
        }
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'home'", 10_000);

        String phase = evalString("document.querySelector('.app-shell')?.dataset.sshPhase ?? ''");
        if (!"live".equals(phase) && !"connected".equals(phase) && !"listing".equals(phase)) {
            if (!"connection".equals(evalString("document.querySelector('.app-shell')?.dataset.homeSurface ?? ''"))
                    && "true".equals(evalString("!!document.querySelector('[data-testid=open-connection]')"))) {
                click("[data-testid=open-connection]");
            }
            awaitJsTrue("!!document.querySelector('[data-testid=ssh-connect]')", 10_000);
            click("[data-testid=ssh-connect]");
            awaitJsTrue("!!document.querySelector('[data-testid=host-key-decision]')"
                    + " || ['connected','listing'].includes(document.querySelector('.app-shell')?.dataset.sshPhase)",
                    30_000);
            if ("true".equals(evalString("!!document.querySelector('[data-testid=host-key-decision]')"))) {
                click("[data-testid=trust-host-key]");
            }
            awaitJsTrue("['connected','listing'].includes(document.querySelector('.app-shell')?.dataset.sshPhase)",
                    30_000);
        }

        if (!"live".equals(evalString("document.querySelector('.app-shell')?.dataset.homeSurface ?? ''"))
                || !sessionTag.equals(evalString("document.querySelector('.app-shell')?.dataset.sshSelectedTag ?? ''"))) {
            if (!"sessions".equals(evalString("document.querySelector('.app-shell')?.dataset.homeSurface ?? ''"))) {
                click("[data-testid=open-sessions]");
            }
            String matchingSession = "Array.from(document.querySelectorAll('[data-session-tag]')).find((node)=>node.dataset.sessionTag === "
                    + JSONObject.quote(sessionTag) + ")";
            awaitJsTrue(matchingSession + " !== undefined", 15_000);
            click("[data-session-tag=\"" + sessionTag + "\"]");
            awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'home'"
                    + " && document.querySelector('.app-shell')?.dataset.homeSurface === 'live'"
                    + " && document.querySelector('.app-shell')?.dataset.sshPhase === 'live'"
                    + " && document.querySelector('.app-shell')?.dataset.sshSelectedTag === "
                    + JSONObject.quote(sessionTag), 30_000);
        }

        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'home'"
                + " && document.querySelector('.app-shell')?.dataset.homeSurface === 'live'"
                + " && document.querySelector('.app-shell')?.dataset.sshPhase === 'live'"
                + " && document.querySelector('.app-shell')?.dataset.sshSelectedTag === "
                + JSONObject.quote(sessionTag)
                + " && document.querySelector('#terminal-viewport')?.dataset.enabled === 'true'", 30_000);

        // Returning from Usage/Ports updates the route before the retained Home
        // surface becomes visible again. Wait for the draft to be a real touch
        // target before mapping its CSS coordinates into Android screen space.
        String composerDraftTapReady = visibleComposerExpression()
                + " && " + composerDraftTapTargetExpression();
        awaitJsTrue(composerDraftTapReady, 15_000);

        boolean composerVisible = "true".equals(evalRaw(visibleComposerExpression()));
        boolean composerDraftFocused = "true".equals(evalRaw(
                "document.activeElement === document.querySelector('[data-testid=prompt-draft]')"));
        if (!composerVisible || !composerDraftFocused) {
            openComposerWithPhysicalDraftTap(sessionTag, eventPrefix);
        }
        String composerReady = visibleComposerExpression()
                + " && document.activeElement === document.querySelector('[data-testid=prompt-draft]')";
        awaitJsTrue(composerReady, 15_000);
        // Read Composer transport readiness only after its visible UI is open
        // and the draft has focus through the Android touch path.
        awaitJsTrue(visibleComposerExpression()
                + " && document.activeElement === document.querySelector('[data-testid=prompt-draft]')"
                + " && document.querySelector('[data-testid=prompt-composer]')?.dataset.transportState === 'connected'",
                15_000);
    }

    private void openComposerWithPhysicalDraftTap(String sessionTag, String eventPrefix) throws Exception {
        JSONObject before = readComposerOpenState("before-physical-draft-tap");
        installComposerOpenTapRecorder();
        evalString("window.__ps2908ComposerOpenPointerEvents.length=0");

        JSONArray taps = new JSONArray();
        boolean trustedDraftPointerDown = false;
        boolean trustedDraftTapComplete = false;
        boolean draftFocused = false;
        JSONObject after = before;
        String ready = visibleComposerExpression()
                + " && document.activeElement === document.querySelector('[data-testid=prompt-draft]')";
        for (int attempt = 1; attempt <= 3; attempt++) {
            JSONObject tap;
            try {
                tap = tapComposerDraftCenter();
            } catch (AssertionError error) {
                JSONObject rejected = readComposerOpenState("draft-tap-target-rejected-attempt-" + attempt);
                throw new AssertionError("Composer could not be opened by a physical draft tap; sessionTag="
                        + sessionTag + "; attempt=" + attempt + "; before=" + before
                        + "; targetFailure=" + error.getMessage() + "; after=" + rejected, error);
            }
            taps.put(tap);

            long pointerDeadline = SystemClock.uptimeMillis() + 1_500;
            while (SystemClock.uptimeMillis() < pointerDeadline) {
                trustedDraftPointerDown = "true".equals(evalRaw(
                        "window.__ps2908ComposerOpenPointerEvents?.some(event=>event.type==='pointerdown'"
                                + "&&event.isTrusted===true&&event.targetIsDraft===true)===true"));
                trustedDraftTapComplete = "true".equals(evalRaw(completedTrustedDraftTapExpression()));
                draftFocused = "true".equals(evalRaw(ready));
                if (trustedDraftTapComplete && draftFocused) break;
                Thread.sleep(30);
            }
            after = readComposerOpenState("after-physical-draft-tap-attempt-" + attempt);
            if (trustedDraftTapComplete && draftFocused) break;
            if (attempt < 3) {
                after = awaitComposerDraftTapLayout(tap);
            }
        }
        String tapEvents = evalString("JSON.stringify(window.__ps2908ComposerOpenPointerEvents||[])");

        assertTrue("opening the Composer must follow a trusted Android pointer-down on its draft; before="
                        + before + "; taps=" + taps + "; events=" + tapEvents + "; after=" + after,
                trustedDraftPointerDown);
        assertTrue("opening the Composer must record a completed trusted pointerdown/pointerup/click on its draft; "
                        + "before=" + before + "; taps=" + taps + "; events=" + tapEvents + "; after=" + after,
                trustedDraftTapComplete);
        assertTrue("the completed physical draft tap must leave the Prompt draft focused; before=" + before
                + "; taps=" + taps + "; events=" + tapEvents + "; after=" + after, draftFocused);
        Log.i("UsagePortsDockerJourney", "RUN " + activeRunId
                + " " + eventPrefix + "_COMPOSER_TAP sessionTag=" + sessionTag
                + " trustedDraftPointerDown=" + trustedDraftPointerDown
                + " trustedDraftTapComplete=" + trustedDraftTapComplete
                + " draftFocused=" + draftFocused
                + " before=" + before + " taps=" + taps + " events=" + tapEvents + " after=" + after);
    }

    private void installComposerOpenTapRecorder() throws Exception {
        evalString("(() => {if(window.__ps2908ComposerOpenRecorderInstalled)return 'installed';"
                + "window.__ps2908ComposerOpenPointerEvents=[];"
                + "const record=(type,event)=>{const draft=document.querySelector('[data-testid=prompt-draft]');"
                + "window.__ps2908ComposerOpenPointerEvents.push({type,isTrusted:event.isTrusted===true,"
                + "targetIsDraft:event.target===draft,pointerType:event.pointerType||'',pointerId:event.pointerId??-1,"
                + "clientX:event.clientX,clientY:event.clientY});};"
                + "for(const type of ['pointerdown','pointerup','click'])document.addEventListener(type,event=>record(type,event),true);"
                + "window.__ps2908ComposerOpenRecorderInstalled=true;return 'installed';})()");
    }

    private String completedTrustedDraftTapExpression() {
        return "(() => {const events=window.__ps2908ComposerOpenPointerEvents||[];"
                + "return events.some((click,clickIndex)=>{if(click.type!=='click'||click.isTrusted!==true"
                + "||click.targetIsDraft!==true)return false;let upIndex=clickIndex-1;"
                + "while(upIndex>=0&&events[upIndex].type!=='pointerup')upIndex--;if(upIndex<0)return false;"
                + "const up=events[upIndex];let downIndex=upIndex-1;"
                + "while(downIndex>=0&&events[downIndex].type!=='pointerdown')downIndex--;if(downIndex<0)return false;"
                + "const down=events[downIndex];return up.isTrusted===true&&up.targetIsDraft===true"
                + "&&down.isTrusted===true&&down.targetIsDraft===true"
                + "&&down.pointerId===up.pointerId;});})()";
    }

    private JSONObject awaitComposerDraftTapLayout(JSONObject previousTap) throws Exception {
        long startedAt = SystemClock.uptimeMillis();
        long deadline = startedAt + 3_000;
        String previousLayout = "";
        int stableSamples = 0;
        JSONObject latest = new JSONObject();
        while (SystemClock.uptimeMillis() < deadline) {
            latest = readComposerOpenState("composer-draft-tap-layout-settling");
            JSONObject nativeState = latest.optJSONObject("native");
            JSONObject viewport = latest.optJSONObject("visualViewport");
            JSONObject draftRect = latest.optJSONObject("draftRect");
            boolean imeVisible = nativeState != null && nativeState.optBoolean("imeVisible");
            boolean draftTargetReady = "true".equals(evalRaw(composerDraftTapTargetExpression()));
            String layout = (nativeState == null ? "" : nativeState.optInt("webViewHeightPx")) + ":"
                    + (viewport == null ? "" : viewport.optDouble("height")) + ":"
                    + (draftRect == null ? "" : draftRect.toString());
            stableSamples = layout.equals(previousLayout) ? stableSamples + 1 : 0;
            boolean layoutSettled = stableSamples >= 2
                    && SystemClock.uptimeMillis() - startedAt >= 200
                    && (imeVisible || stableSamples >= 4);
            if (draftTargetReady && layoutSettled) {
                return latest;
            }
            previousLayout = layout;
            Thread.sleep(40);
        }
        throw new AssertionError("Composer draft layout did not stabilize for a fresh physical tap; previousTap="
                + previousTap + "; latest=" + latest);
    }

    private JSONObject tapComposerDraftCenter() throws Exception {
        JSONObject point = new JSONObject(evalString("(() => {const draft=document.querySelector('[data-testid=prompt-draft]');"
                + "if(!draft)return JSON.stringify({missing:true});const rect=draft.getBoundingClientRect();"
                + "const x=rect.left+rect.width/2,y=rect.top+rect.height/2,hit=document.elementFromPoint(x,y);"
                + "return JSON.stringify({missing:false,disabled:!!draft.disabled,connected:draft.isConnected,"
                + "x,y,width:innerWidth,cssHeight:innerHeight,top:rect.top,bottom:rect.bottom,left:rect.left,right:rect.right,"
                + "centerHitIsDraft:hit===draft,centerHitTag:hit?.tagName||'',"
                + "activeElementTag:document.activeElement?.tagName||'',activeElementTestId:document.activeElement?.getAttribute?.('data-testid')||''});})()"));
        assertTrue("Composer draft is missing at the Home touch target: " + point, !point.optBoolean("missing"));
        assertTrue("Composer draft is detached from the rendered route: " + point, point.optBoolean("connected"));
        assertTrue("Composer draft is disabled at the Home touch target: " + point, !point.optBoolean("disabled"));
        assertTrue("Composer draft has no visible physical tap target: " + point,
                point.optDouble("top", -1) >= 0 && point.optDouble("bottom", -1) <= point.optDouble("cssHeight") + 0.5
                        && point.optDouble("left", -1) >= 0 && point.optDouble("right", -1) <= point.optDouble("width") + 0.5
                        && point.optDouble("right") > point.optDouble("left")
                        && point.optDouble("bottom") > point.optDouble("top"));
        assertTrue("Composer draft center is intercepted by another DOM element: " + point,
                point.optBoolean("centerHitIsDraft"));

        AtomicReference<float[]> screenPoint = new AtomicReference<>();
        AtomicReference<JSONObject> nativeMapping = new AtomicReference<>();
        scenario.onActivity(activity -> {
            WebView webView = findWebView(activity.getWindow().getDecorView());
            assertNotNull("packaged Capacitor activity must contain a WebView", webView);
            int[] location = new int[2];
            webView.getLocationOnScreen(location);
            double cssWidth = point.optDouble("width");
            double cssHeight = point.optDouble("cssHeight");
            float scaleX = webView.getWidth() / (float) cssWidth;
            float scaleY = webView.getHeight() / (float) cssHeight;
            float screenX = location[0] + (float) point.optDouble("x") * scaleX;
            float screenY = location[1] + (float) point.optDouble("y") * scaleY;
            screenPoint.set(new float[]{screenX, screenY});
            View decor = activity.getWindow().getDecorView();
            WindowInsets insets = decor.getRootWindowInsets();
            View focusedView = decor.findFocus();
            try {
                nativeMapping.set(new JSONObject()
                        .put("webViewScreenX", location[0])
                        .put("webViewScreenY", location[1])
                        .put("webViewWidthPx", webView.getWidth())
                        .put("webViewHeightPx", webView.getHeight())
                        .put("cssWidth", cssWidth)
                        .put("cssHeight", cssHeight)
                        .put("scaleX", scaleX)
                        .put("scaleY", scaleY)
                        .put("windowHasFocus", decor.hasWindowFocus())
                        .put("webViewHasFocus", webView.hasFocus())
                        .put("nativeFocusedView", focusedView == null ? "" : focusedView.getClass().getName())
                        .put("imeVisible", insets != null && Build.VERSION.SDK_INT >= 30
                                && insets.isVisible(WindowInsets.Type.ime()))
                        .put("imeBottomPx", insets == null || Build.VERSION.SDK_INT < 30 ? 0
                                : insets.getInsets(WindowInsets.Type.ime()).bottom));
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });

        float[] screen = screenPoint.get();
        assertNotNull("native screen point for Composer draft was not mapped", screen);
        long downTime = SystemClock.uptimeMillis();
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, screen[0], screen[1], 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        boolean downInjected = instrumentation.getUiAutomation().injectInputEvent(down, true);
        down.recycle();
        assertTrue("Android touchscreen ACTION_DOWN for the Composer draft must be injected", downInjected);
        SystemClock.sleep(16);
        long upTime = SystemClock.uptimeMillis();
        MotionEvent up = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, screen[0], screen[1], 0);
        up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        boolean upInjected = instrumentation.getUiAutomation().injectInputEvent(up, true);
        up.recycle();
        assertTrue("Android touchscreen ACTION_UP for the Composer draft must be injected", upInjected);
        return new JSONObject(point.toString())
                .put("nativeMapping", nativeMapping.get())
                .put("screenX", screen[0])
                .put("screenY", screen[1])
                .put("touchDownUptimeMs", downTime)
                .put("touchUpUptimeMs", upTime)
                .put("downInjected", downInjected)
                .put("upInjected", upInjected);
    }

    private JSONObject readComposerOpenState(String stage) throws Exception {
        JSONObject dom = new JSONObject(evalString("(() => {const shell=document.querySelector('.app-shell');"
                + "const composer=document.querySelector('[data-testid=prompt-composer]');"
                + "const draft=document.querySelector('[data-testid=prompt-draft]');"
                + "const rect=node=>{const r=node?.getBoundingClientRect();return r?{top:r.top,bottom:r.bottom,left:r.left,right:r.right,width:r.width,height:r.height}:null};"
                + "const style=composer?getComputedStyle(composer):null;const draftRect=rect(draft);"
                + "const hit=draftRect?document.elementFromPoint(draftRect.left+draftRect.width/2,draftRect.top+draftRect.height/2):null;"
                + "const label=node=>node?{tag:node.tagName||'',id:node.id||'',testid:node.getAttribute?.('data-testid')||''}:null;"
                + "return JSON.stringify({route:shell?.dataset.route||'',homeSurface:shell?.dataset.homeSurface||'',"
                + "sshPhase:shell?.dataset.sshPhase||'',selectedTag:shell?.dataset.sshSelectedTag||'',"
                + "keyboardVisible:shell?.dataset.keyboardVisible==='true',keyboardComposerMode:shell?.dataset.keyboardComposerMode==='true',"
                + "composerPresent:!!composer,composerConnected:!!composer?.isConnected,composerRect:rect(composer),"
                + "composerDisplay:style?.display||'',composerVisibility:style?.visibility||'',composerOpacity:style?.opacity||'',"
                + "composerInert:!!composer?.closest('[inert],[aria-hidden=true]'),"
                + "draftPresent:!!draft,draftConnected:!!draft?.isConnected,draftDisabled:!!draft?.disabled,"
                + "draftFocused:document.activeElement===draft,activeElement:label(document.activeElement),"
                + "draftRect, draftCenterHit:label(hit),draftCenterHitIsDraft:hit===draft,"
                + "visualViewport:{width:window.visualViewport?.width??innerWidth,height:window.visualViewport?.height??innerHeight,"
                + "offsetLeft:window.visualViewport?.offsetLeft??0,offsetTop:window.visualViewport?.offsetTop??0},innerWidth,innerHeight});})()"));
        AtomicReference<JSONObject> nativeState = new AtomicReference<>();
        scenario.onActivity(activity -> {
            View decor = activity.getWindow().getDecorView();
            View focusedView = decor.findFocus();
            WebView webView = findWebView(decor);
            WindowInsets insets = decor.getRootWindowInsets();
            try {
                nativeState.set(new JSONObject()
                        .put("androidApi", Build.VERSION.SDK_INT)
                        .put("windowHasFocus", decor.hasWindowFocus())
                        .put("decorHasFocus", decor.hasFocus())
                        .put("focusedViewClass", focusedView == null ? "" : focusedView.getClass().getName())
                        .put("webViewPresent", webView != null)
                        .put("webViewHasFocus", webView != null && webView.hasFocus())
                        .put("webViewHeightPx", webView == null ? 0 : webView.getHeight())
                        .put("imeVisible", insets != null && Build.VERSION.SDK_INT >= 30
                                && insets.isVisible(WindowInsets.Type.ime()))
                        .put("imeBottomPx", insets == null || Build.VERSION.SDK_INT < 30 ? 0
                                : insets.getInsets(WindowInsets.Type.ime()).bottom));
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
        return dom.put("stage", stage)
                .put("capturedAtUptimeMs", SystemClock.uptimeMillis())
                .put("native", nativeState.get() == null ? new JSONObject() : nativeState.get());
    }

    private String visibleComposerExpression() {
        return "(() => {const shell=document.querySelector('.app-shell');"
                + "const composer=document.querySelector('[data-testid=prompt-composer]');"
                + "if(!shell||!composer)return false;const rect=composer.getBoundingClientRect();"
                + "const style=getComputedStyle(composer);"
                + "return shell.dataset.route==='home'&&shell.dataset.homeSurface==='live'"
                + "&&shell.dataset.sshPhase==='live'&&rect.width>0&&rect.height>0"
                + "&&rect.right>0&&rect.bottom>0&&rect.left<innerWidth&&rect.top<innerHeight"
                + "&&style.display!=='none'&&style.visibility!=='hidden'&&style.opacity!=='0'"
                + "&&!composer.closest('[inert],[aria-hidden=true]');})()";
    }

    private String composerDraftTapTargetExpression() {
        return "(() => {const draft=document.querySelector('[data-testid=prompt-draft]');"
                + "if(!draft||draft.disabled)return false;const rect=draft.getBoundingClientRect();"
                + "return document.elementFromPoint(rect.left+rect.width/2,rect.top+rect.height/2)===draft;})()";
    }

    private void awaitTerminalReady() throws Exception {
        awaitJsTrue("(() => {const viewport=document.querySelector('#terminal-viewport');"
                + "const textarea=viewport?.querySelector('.xterm-helper-textarea');"
                + "if(!viewport||!textarea||viewport.dataset.enabled!=='true') return false;"
                + "textarea.focus(); return document.activeElement===textarea;})()");
    }

    private void awaitExactMarkerRow(String marker, String checkpoint) throws Exception {
        awaitJsTrue("Array.from(document.querySelectorAll('#terminal-viewport .xterm-rows > div'))"
                + ".some((row)=>row.textContent.replaceAll(String.fromCharCode(160),' ').trim()==="
                + JSONObject.quote(marker) + ")", 30_000);
        assertTrue("remote host marker must be an exact terminal row for " + checkpoint,
                "true".equals(evalString("Array.from(document.querySelectorAll('#terminal-viewport .xterm-rows > div'))"
                        + ".some((row)=>row.textContent.replaceAll(String.fromCharCode(160),' ').trim()==="
                        + JSONObject.quote(marker) + ")")));
    }

    private void waitForTerminalInputDrain(int ackBefore, int failureBefore, String checkpoint) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 20_000;
        while (SystemClock.uptimeMillis() < deadline) {
            JSONObject stats = terminalInputStats();
            assertEquals("terminal input failed while sending " + checkpoint, failureBefore, stats.getInt("failureCount"));
            if (stats.getInt("pending") == 0 && stats.getInt("ackCount") > ackBefore) return;
            Thread.sleep(100);
        }
        throw new AssertionError("terminal input did not drain for " + checkpoint + ": " + terminalInputStats());
    }

    private JSONObject terminalInputStats() throws Exception {
        return new JSONObject()
                .put("pending", Integer.parseInt(evalString("document.querySelector('.app-shell')?.dataset.sshTerminalInputPending ?? '0'")))
                .put("ackCount", Integer.parseInt(evalString("document.querySelector('.app-shell')?.dataset.sshTerminalInputAcks ?? '0'")))
                .put("failureCount", Integer.parseInt(evalString("document.querySelector('.app-shell')?.dataset.sshTerminalInputFailures ?? '0'")));
    }

    private int portForwardLocalPort(int remotePort) throws Exception {
        String value = evalString(jsSelector(portRowSelector(remotePort)
                + "[data-forwarded=true] [data-testid=port-forward-local]") + "?.dataset.localPort ?? ''");
        assertTrue("forwarded remote port " + remotePort + " must expose a loopback port: " + value,
                value.matches("[0-9]{1,5}"));
        return Integer.parseInt(value);
    }

    private String portRowSelector(int remotePort) {
        return "[data-testid=port-row][data-remote-port=\"" + remotePort + "\"]";
    }

    private String jsSelector(String selector) {
        return "document.querySelector(" + JSONObject.quote(selector) + ")";
    }

    private String readLocalHttpStatus(int localPort) throws Exception {
        try (Socket socket = connectLocalPort(localPort)) {
            socket.setSoTimeout(5_000);
            OutputStream output = socket.getOutputStream();
            output.write("GET / HTTP/1.0\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            output.flush();
            return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
        }
    }

    private String readLocalServiceBanner(int localPort) throws Exception {
        try (Socket socket = connectLocalPort(localPort)) {
            socket.setSoTimeout(5_000);
            return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
        }
    }

    private Socket connectLocalPort(int localPort) throws Exception {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", localPort), 5_000);
        return socket;
    }

    private boolean canConnectToLocalPort(int localPort) {
        try (Socket ignored = connectLocalPort(localPort)) {
            return true;
        } catch (Exception expected) {
            return false;
        }
    }

    private int portScanCount() throws Exception {
        return Integer.parseInt(evalString("document.querySelector('[data-testid=ports-screen]')?.dataset.scanCount ?? '-1'"));
    }

    private void captureScreenArtifact(String name, File artifactDirectory) throws Exception {
        awaitScreenshotReadyState(name);
        awaitRenderedFrames();
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull("Android must provide a screenshot for " + name, bitmap);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue("PNG screenshot must encode for " + name, bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        bitmap.recycle();
        byte[] bytes = output.toByteArray();
        writeText(new File(artifactDirectory, name + ".png.sha256"), hex(MessageDigest.getInstance("SHA-256").digest(bytes)) + "\n");
        emitArtifact(name + ".png", bytes);
    }

    private void awaitScreenshotReadyState(String name) throws Exception {
        switch (name) {
            case "provider-usage":
                awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'usage'"
                        + " && !document.querySelector('[data-testid=usage-loading]')"
                        + " && document.querySelectorAll('[data-testid=usage-provider]').length === 5");
                break;
            case "ports-auto-forward":
                awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'ports'"
                        + " && !document.querySelector('[data-testid=ports-loading]')"
                        + " && !!" + jsSelector(portRowSelector(httpRemotePort) + "[data-forwarded=true]")
                        + " && !!" + jsSelector(portRowSelector(httpRemotePort) + " [data-testid=port-forward-local]")
                        + " && !document.querySelector('[data-testid=port-scan]')?.disabled");
                break;
            case "ports-manual-forward":
                awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'ports'"
                        + " && !!" + jsSelector(portRowSelector(22) + "[data-forwarded=true]")
                        + " && !!" + jsSelector(portRowSelector(22) + " [data-testid=port-forward-local]")
                        + " && !document.querySelector('[data-testid=port-scan]')?.disabled");
                break;
            default:
                throw new IllegalArgumentException("unknown usage/ports screenshot: " + name);
        }
    }

    private void awaitRenderedFrames() throws Exception {
        String token = evalString("(() => {const token=(window.__usagePortsPaintToken ?? 0)+1;"
                + "window.__usagePortsPaintToken=token; window.__usagePortsPaintedToken=0;"
                + "requestAnimationFrame(()=>requestAnimationFrame(()=>{"
                + "if(window.__usagePortsPaintToken===token) window.__usagePortsPaintedToken=token;"
                + "})); return String(token);})()");
        awaitJsTrue("window.__usagePortsPaintedToken === " + token, 5_000);
        CountDownLatch visualStateCommitted = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            WebView webView = findWebView(activity.getWindow().getDecorView());
            assertNotNull("packaged activity must contain its Capacitor WebView", webView);
            webView.postVisualStateCallback(SystemClock.uptimeMillis(),
                    new WebView.VisualStateCallback() {
                        @Override
                        public void onComplete(long requestId) {
                            visualStateCommitted.countDown();
                        }
                    });
        });
        assertTrue("WebView did not commit the state requested for the screenshot",
                visualStateCommitted.await(5, TimeUnit.SECONDS));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        SystemClock.sleep(300);
    }

    private void writeText(File file, String contents) throws Exception {
        byte[] bytes = contents.getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
        }
        emitArtifact(file.getName(), bytes);
    }

    private void installJsFailureProbe() throws Exception {
        evalString("(() => {if(window.__usagePortsJourneyErrorsInstalled) return 'installed';"
                + "window.__usagePortsJourneyErrorsInstalled=true; window.__usagePortsJourneyErrors=[];"
                + "const record=(type,value)=>{const errors=window.__usagePortsJourneyErrors;"
                + "errors.push({type,message:String(value??type).slice(0,1000)});"
                + "if(errors.length>50) errors.shift();};"
                + "window.addEventListener('error',(event)=>record('error',event.message||event.error));"
                + "window.addEventListener('unhandledrejection',(event)=>record('unhandledrejection',"
                + "event.reason?.stack||event.reason?.message||event.reason)); return 'installed';})()");
    }

    private void captureFailureDiagnostics(Throwable failure) throws Exception {
        if (scenario == null || activeRunId == null || artifactDirectory == null) return;

        JSONObject diagnostics = new JSONObject()
                .put("schema", 1)
                .put("runId", activeRunId)
                .put("failure", failure.getClass().getName())
                .put("message", String.valueOf(failure.getMessage()));
        try {
            String page = evalString("JSON.stringify((() => {"
                    + "const shell=document.querySelector('.app-shell');"
                    + "const screen=document.querySelector('[data-testid=ports-screen]');"
                    + "const scan=document.querySelector('[data-testid=port-scan]');"
                    + "const resources=document.querySelector('[data-testid=ssh-resources]');"
                    + "return {capturedAt:new Date().toISOString(),route:shell?.dataset.route??null,"
                    + "ssh:{phase:shell?.dataset.sshPhase??null,connectionId:shell?.dataset.sshConnectionId??null,"
                    + "generationId:shell?.dataset.sshGenerationId??null,selectedSession:shell?.dataset.sshSelectedSession??null,"
                    + "selectedSessionId:shell?.dataset.sshSelectedSessionId??null,selectedTag:shell?.dataset.sshSelectedTag??null,"
                    + "terminalInputPending:shell?.dataset.sshTerminalInputPending??null,"
                    + "resources:resources?.outerHTML??null,host:document.querySelector('[data-testid=ssh-host]')?.value??null,"
                    + "port:document.querySelector('[data-testid=ssh-port]')?.value??null},"
                    + "ports:{mounted:!!screen,scanCount:screen?.dataset.scanCount??null,"
                    + "loading:!!document.querySelector('[data-testid=ports-loading]'),"
                    + "scanButton:scan?{disabled:scan.disabled,text:scan.innerText}:null,"
                    + "scanError:document.querySelector('[data-testid=port-scan-error]')?.innerText??null,"
                    + "screenText:screen?.innerText?.slice(0,6000)??null,screenHtml:screen?.outerHTML?.slice(0,12000)??null,"
                    + "rows:Array.from(document.querySelectorAll('[data-testid=port-row]')).map((row)=>({"
                    + "remotePort:row.dataset.remotePort,forwarded:row.dataset.forwarded,state:row.dataset.state,"
                    + "text:row.innerText,html:row.outerHTML.slice(0,1500)}))},"
                    + "jsErrors:window.__usagePortsJourneyErrors??[],bodyText:document.body?.innerText?.slice(0,8000)??null};})())");
            diagnostics.put("page", new JSONObject(page));
        } catch (Exception | AssertionError pageFailure) {
            diagnostics.put("pageCaptureError", pageFailure.getClass().getSimpleName() + ": " + pageFailure.getMessage());
        }

        try {
            captureFailureScreenshot(artifactDirectory);
            diagnostics.put("screenshot", "failure-screen.png");
        } catch (Exception | AssertionError screenshotFailure) {
            diagnostics.put("screenshotCaptureError",
                    screenshotFailure.getClass().getSimpleName() + ": " + screenshotFailure.getMessage());
        }

        writeText(new File(artifactDirectory, "failure-diagnostics.json"), diagnostics.toString(2));
        JSONObject failureSummary = new JSONObject()
                .put("schema", 1)
                .put("runId", activeRunId)
                .put("outcome", "failed")
                .put("failure", failure.getClass().getName())
                .put("message", String.valueOf(failure.getMessage()))
                .put("screenshots", new org.json.JSONArray().put("failure-screen.png"));
        writeText(new File(artifactDirectory, "journey-summary.json"), failureSummary.toString(2));
        Log.e("UsagePortsDockerJourney", "RUN " + activeRunId + " failed; captured route, SSH, Ports, JS errors and screen");
    }

    private void captureFailureScreenshot(File directory) throws Exception {
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull("Android must provide a screenshot for failure diagnostics", bitmap);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        boolean encoded;
        try {
            encoded = bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        } finally {
            bitmap.recycle();
        }
        assertTrue("failure screenshot must encode as PNG", encoded);
        byte[] bytes = output.toByteArray();
        try (FileOutputStream file = new FileOutputStream(new File(directory, "failure-screen.png"))) {
            file.write(bytes);
        }
        emitArtifact("failure-screen.png", bytes);
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
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    private String currentConnectionId() throws Exception { return evalString("document.querySelector('.app-shell')?.dataset.sshConnectionId ?? ''"); }
    private String currentGenerationId() throws Exception { return evalString("document.querySelector('.app-shell')?.dataset.sshGenerationId ?? ''"); }

    private void setValue(String selector, String value) throws Exception {
        evalString("(() => {const node=document.querySelector(" + JSONObject.quote(selector) + ");"
                + "if(!node) throw new Error('missing ' + " + JSONObject.quote(selector) + ");"
                + "node.value=" + JSONObject.quote(value) + ";"
                + "node.dispatchEvent(new Event('input',{bubbles:true}));"
                + "node.dispatchEvent(new Event('change',{bubbles:true})); return 'set';})()");
    }

    private void click(String selector) throws Exception {
        evalString("(() => {const node=document.querySelector(" + JSONObject.quote(selector) + ");"
                + "if(!node) throw new Error('missing ' + " + JSONObject.quote(selector) + ");"
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
            View decor = activity.getWindow().getDecorView();
            WebView webView = findWebView(decor);
            assertNotNull("packaged activity must contain its Capacitor WebView", webView);
            webView.evaluateJavascript(expression, value -> {
                result.set(value);
                latch.countDown();
            });
        });
        assertTrue("WebView JavaScript evaluation timed out", latch.await(10, TimeUnit.SECONDS));
        String value = result.get();
        if (value == null) throw new AssertionError("WebView returned no value for expression: " + expression);
        return value;
    }

    private WebView findWebView(View view) {
        if (view instanceof WebView) return (WebView) view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index += 1) {
            WebView found = findWebView(group.getChildAt(index));
            if (found != null) return found;
        }
        return null;
    }
}
