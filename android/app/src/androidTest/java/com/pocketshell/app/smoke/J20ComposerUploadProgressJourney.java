package com.pocketshell.app.smoke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.WebView;

import androidx.core.content.FileProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.intent.Intents;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import net.schmizz.sshj.DefaultConfig;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.SecurityUtils;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.userauth.password.PasswordFinder;
import com.pocketshell.app.MainActivity;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.json.JSONArray;
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
import java.security.Provider;
import java.security.Security;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.intent.Intents.intending;

/**
 * Packaged JS composer journey for real byte-level SFTP attachment progress.
 *
 * The picker response is a provider-backed set of three real files. From that
 * result onward this exercises the production JS selection, chunk reader,
 * attachment aggregator, Capacitor SFTP write, and Android sshj transport.
 * The app connects through a bandwidth toxic installed before its first dial;
 * the independent sshj oracle connects directly to the agents fixture.
 */
@RunWith(AndroidJUnit4.class)
public final class J20ComposerUploadProgressJourney {
    private static final String TAG = "J20ComposerUploadProgress";
    private static final String JOURNEY = "j20-composer-upload-progress";
    private static final String USERNAME = "testuser";
    private static final int FILE_COUNT = 3;
    private static final int PAYLOAD_BYTES = 512 * 1024;
    private static final int TOXIC_RATE_KBPS = 64;
    private static final long WAIT_TIMEOUT_MILLIS = 90_000;
    private static final long POLL_INTERVAL_MILLIS = 60;
    private static final long JS_TIMEOUT_SECONDS = 15;
    private static final String REMOTE_ATTACHMENTS = "/home/testuser/.pocketshell/attachments";
    private static final String PROXY_NAME = "agents_ssh";

    private ActivityScenario<MainActivity> scenario;
    private String artifactRunId;
    private String payloadStem;
    private String sessionName;
    private String host;
    private String privateKeyPem;
    private int proxyPort;
    private int directPort;
    private int toxiproxyApiPort;
    private String toxicName;
    private boolean proxyPrepared;
    private boolean intentsInitialized;
    private boolean retainUploadsForExternalHostOracle;
    private final List<File> sourceFiles = new ArrayList<>();
    private final List<Uri> sourceUris = new ArrayList<>();
    private final Map<String, byte[]> sourcePayloads = new HashMap<>();

    @Before
    public void launchPackagedShell() throws Exception {
        assertTrue("the packaged upload journey requires API 35+", Build.VERSION.SDK_INT >= 35);
        var arguments = InstrumentationRegistry.getArguments();
        artifactRunId = arguments.getString("artifactRunId",
                "i2929-" + Long.toString(System.currentTimeMillis(), 36));
        assertTrue("artifactRunId must be safe for isolated fixture and artifact names",
                artifactRunId.matches("[A-Za-z0-9_-]{4,48}"));
        payloadStem = "j20p-" + artifactRunId;
        sessionName = "j20-upload-" + artifactRunId;
        host = arguments.getString("sshHost", "10.0.2.2");
        String appPort = arguments.getString("sshPort");
        String encodedKey = arguments.getString("sshPrivateKeyBase64");
        assertNotNull("pass the Toxiproxy SSH port with sshPort", appPort);
        assertNotNull("pass the fixture key with sshPrivateKeyBase64", encodedKey);
        proxyPort = Integer.parseInt(appPort);
        directPort = integerArgument(arguments.getString("agentsPort"),
                proxyPort == 2228 ? 2222 : proxyPort - 10);
        toxiproxyApiPort = integerArgument(arguments.getString("toxiproxyApiPort"),
                directPort == 2222 ? 8474 : 8474 + directPort - 2222);
        assertEquals("the app must dial this pool lane's published Toxiproxy SSH port",
                directPort == 2222 ? 2228 : directPort + 10, proxyPort);
        assertEquals("the fixture args must use this pool lane's published Toxiproxy API port",
                directPort == 2222 ? 8474 : 8474 + directPort - 2222, toxiproxyApiPort);
        privateKeyPem = new String(Base64.getDecoder().decode(encodedKey), StandardCharsets.UTF_8);
        toxicName = "i2929_" + artifactRunId.replace('-', '_');
        createProviderBackedFiles();
        scenario = ActivityScenario.launch(MainActivity.class);
    }

    @After
    public void closeShellAndCleanFixture() {
        if (scenario != null) scenario.close();
        if (intentsInitialized) {
            Intents.release();
            intentsInitialized = false;
        }
        for (Uri uri : sourceUris) {
            try {
                InstrumentationRegistry.getInstrumentation().getTargetContext()
                        .getContentResolver().delete(uri, null, null);
            } catch (RuntimeException ignored) {
                // The test's primary result should survive provider cleanup trouble.
            }
        }
        for (File file : sourceFiles) {
            if (file.exists()) file.delete();
        }
        if (privateKeyPem != null && directPort > 0) {
            try {
                removeSlowUploadToxic();
                if (!retainUploadsForExternalHostOracle) cleanupPayloadsBestEffort();
                execOnFixture("pocketshell sessions kill -- " + shellQuote(sessionName) + " >/dev/null 2>&1 || true");
            } catch (Exception error) {
                Log.e(TAG, "best-effort fixture cleanup failed for " + artifactRunId, error);
            }
        }
    }

    @Test
    public void aThreeFileUploadShowsTheBarMidFlightAndLeavesNoResidueAfterCompletion() throws Exception {
        JSONObject baselineGeometry;
        List<String> stagedPaths;
        try {
            prepareBandwidthLimitedProxy();
            connectAndAttachSession();
            registerNativeProgressObserver();

            setComposerDraft("Attach three test files");
            tapDomCenter("[data-testid=prompt-draft]");
            awaitImeVisible(true);
            awaitJsTrue("document.querySelector('.app-shell')?.dataset.keyboardVisible === 'true'"
                    + " && document.querySelector('.app-shell')?.dataset.keyboardComposerMode === 'true'");
            baselineGeometry = composerGeometry();

            Intent pickerResult = buildMultipleDocumentResult();
            Intents.init();
            intentsInitialized = true;
            try {
                intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(
                        new Instrumentation.ActivityResult(Activity.RESULT_OK, pickerResult));
                tapDomCenter("[data-testid=prompt-composer] [aria-label='Attach to prompt']");
                awaitJsTrue("document.querySelectorAll('[data-testid=composer-attachments] [data-attachment-state=pending]').length === 3"
                        + " || document.querySelector('[data-testid=composer-upload-progress]') !== null");
                assertPickerWasReallyLaunched();
            } finally {
                Intents.release();
                intentsInitialized = false;
            }

            JSONObject midFlight = awaitPositiveProgress();
            ensureKeyboardOpenDuringUpload();
            scrollUploadProgressIntoKeyboardViewport();
            awaitImeVisible(true);
            midFlight = composerProgressSnapshot();
            assertAccessibleDeterminateProgress(midFlight, "mid-flight");
            JSONObject nativeMidFlight = assertNativeProgressEvents(true);
            assertEquals("the keyboard must remain open for the actual upload screenshot",
                    true, isImeVisible());
            assertEquals("WebView keyboard mode must remain active for the actual upload screenshot",
                    "true", evalString("document.querySelector('.app-shell')?.dataset.keyboardComposerMode ?? 'false'"));
            assertFixedComposerStatusRow(baselineGeometry, midFlight, "mid-flight");
            capturePersistentScreenshot("mid-flight", midFlight, nativeMidFlight);

            awaitUploadComplete();
            ensureKeyboardOpenDuringUpload();
            scrollCompletedAttachmentsIntoKeyboardViewport();
            awaitImeVisible(true);
            JSONObject complete = composerProgressSnapshot();
            assertEquals("the completion state must retain all three staged attachment rows", FILE_COUNT,
                    complete.getInt("stagedCount"));
            assertEquals("completion must clear the determinate progress element", false,
                    complete.getBoolean("progressVisible"));
            assertEquals("completion must remove the progress track too", false,
                    complete.getBoolean("progressTrackVisible"));
            assertTrue("all retained attachment rows must fit above the keyboard in the completion screenshot",
                    complete.getBoolean("stagedRowsInViewport"));
            assertFixedComposerStatusRow(baselineGeometry, complete, "completed");
            JSONObject allNativeEvents = assertNativeProgressEvents(false);
            stagedPaths = readStagedPaths();
            assertEquals("the real JS composer must retain one staged path per selected file", FILE_COUNT,
                    stagedPaths.size());
            for (String path : stagedPaths) {
                assertTrue("staged files must use the production attachment directory: " + path,
                        path.startsWith(REMOTE_ATTACHMENTS + "/"));
                assertTrue("staged names must retain this run's payload marker: " + path,
                        path.contains(payloadStem));
            }
            assertNativeProgressPathsMatch(allNativeEvents, stagedPaths);
            capturePersistentScreenshot("completed", complete, allNativeEvents);

            verifyIndependentHostPayloads(stagedPaths);
            retainUploadsForExternalHostOracle = true;
            Log.i(TAG, "PASS|" + artifactRunId
                    + "|three full payload hashes verified; uploads retained for the independent host oracle");
        } finally {
            if (proxyPrepared) removeSlowUploadToxic();
        }
    }

    private void createProviderBackedFiles() throws Exception {
        android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        for (int index = 0; index < FILE_COUNT; index++) {
            String name = payloadFileName(index);
            byte[] payload = new byte[PAYLOAD_BYTES];
            for (int offset = 0; offset < payload.length; offset++) {
                payload[offset] = (byte) ((offset + index * 37) % 251);
            }
            File file = new File(context.getCacheDir(), name);
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(payload);
            }
            sourceFiles.add(file);
            sourcePayloads.put(name, payload);
            Uri uri = FileProvider.getUriForFile(context,
                    context.getPackageName() + ".fileprovider", file);
            sourceUris.add(uri);
        }
    }

    private Intent buildMultipleDocumentResult() {
        android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ClipData clip = ClipData.newUri(context.getContentResolver(), "three composer files", sourceUris.get(0));
        for (int index = 1; index < sourceUris.size(); index++) {
            clip.addItem(new ClipData.Item(sourceUris.get(index)));
        }
        Intent result = new Intent();
        result.setClipData(clip);
        result.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return result;
    }

    private void prepareBandwidthLimitedProxy() throws Exception {
        // The agents fixture and its proxy share the isolated compose network.
        // Reset this run's proxy before the app's first dial so the toxic also
        // applies to the connection that will carry the SFTP writes.
        execOnFixture("curl -sS -X DELETE http://network-fault-proxy:8474/proxies/"
                + PROXY_NAME + " >/dev/null 2>&1 || true");
        String createProxy = "{\"name\":\"" + PROXY_NAME + "\",\"listen\":\"0.0.0.0:2228\","
                + "\"upstream\":\"agents:22\",\"enabled\":true}";
        String proxyState = execOnFixture("curl -fsS -X POST -H 'Content-Type: application/json' --data "
                + shellQuote(createProxy) + " http://network-fault-proxy:8474/proxies");
        JSONObject configured = new JSONObject(proxyState);
        assertEquals("the app's SFTP transport must use an enabled fixture proxy", true,
                configured.getBoolean("enabled"));
        assertEquals("the proxy must forward to this isolated agents fixture", "agents:22",
                configured.getString("upstream"));

        String toxic = "{\"name\":\"" + toxicName + "\",\"type\":\"bandwidth\","
                + "\"stream\":\"upstream\",\"toxicity\":1.0,\"attributes\":{\"rate\":"
                + TOXIC_RATE_KBPS + "}}";
        execOnFixture("curl -fsS -X POST -H 'Content-Type: application/json' --data "
                + shellQuote(toxic) + " http://network-fault-proxy:8474/proxies/"
                + PROXY_NAME + "/toxics >/dev/null");
        String observed = execOnFixture("curl -fsS http://network-fault-proxy:8474/proxies/" + PROXY_NAME);
        assertTrue("Toxiproxy must report that the SFTP proxy is enabled", new JSONObject(observed).getBoolean("enabled"));
        String toxicState = execOnFixture("curl -fsS http://network-fault-proxy:8474/proxies/"
                + PROXY_NAME + "/toxics/" + toxicName);
        JSONObject installed = new JSONObject(toxicState);
        assertEquals("the upload must be throttled by a real Toxiproxy bandwidth toxic", "bandwidth",
                installed.getString("type"));
        assertEquals("the upload direction must be the app-to-host stream", "upstream",
                installed.getString("stream"));
        proxyPrepared = true;
        Log.i(TAG, "TOXIPROXY|" + artifactRunId + "|direct=" + host + ":" + directPort
                + "|app=" + host + ":" + proxyPort + "|rateKBps=" + TOXIC_RATE_KBPS);
    }

    private void connectAndAttachSession() throws Exception {
        awaitJsTrue("document.querySelector('[data-testid=build-status] > span:nth-child(2)')?.textContent.trim() === 'Build verified'");
        setValue("[data-testid=ssh-host]", host);
        setValue("[data-testid=ssh-port]", Integer.toString(proxyPort));
        setValue("[data-testid=ssh-username]", USERNAME);
        setValue("[data-testid=ssh-private-key]", privateKeyPem);
        click("[data-testid=ssh-connect]");
        awaitJsTrue("!!document.querySelector('[data-testid=host-key-decision]')"
                + " || ['connected','listing'].includes(document.querySelector('.app-shell')?.dataset.sshPhase)");
        if ("true".equals(evalRaw("!!document.querySelector('[data-testid=host-key-decision]')"))) {
            click("[data-testid=trust-host-key]");
        }
        awaitJsTrue("['connected','listing'].includes(document.querySelector('.app-shell')?.dataset.sshPhase)", 60_000);

        awaitJsTrue("!!document.querySelector('[data-testid=new-session-name]')", 20_000);
        setValue("[data-testid=new-session-name]", sessionName);
        click("[data-testid=create-session]");
        String matchingSession = "Array.from(document.querySelectorAll('[data-session-name]')).find(node => node.dataset.sessionName.endsWith("
                + JSONObject.quote(sessionName) + "))";
        awaitJsTrue(matchingSession + " !== undefined", 45_000);
        if (!"sessions".equals(evalString("document.querySelector('.app-shell')?.dataset.homeSurface ?? ''"))) {
            click("[data-testid=open-sessions]");
        }
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.homeSurface === 'sessions'");
        String actualName = evalString(matchingSession + "?.dataset.sessionName ?? ''");
        click("[data-session-name=" + JSONObject.quote(actualName) + "]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.sshPhase === 'live'"
                + " && !!document.querySelector('[data-testid=prompt-composer]')", 45_000);
    }

    private void registerNativeProgressObserver() throws Exception {
        String installed = evalString("(() => {const plugin=window.Capacitor?.Plugins?.SshCapability;"
                + "if(!plugin||typeof plugin.addListener!=='function')return 'missing-plugin';"
                + "window.__j20SftpProgressEvents=[];window.__j20SftpProgressListenerReady=false;"
                + "plugin.addListener('sftpWriteProgress',event=>window.__j20SftpProgressEvents.push({"
                + "requestId:event.requestId,connectionId:event.connectionId,generationId:event.generationId,"
                + "path:event.path,bytesWritten:event.bytesWritten,totalBytes:event.totalBytes})).then(()=>{"
                + "window.__j20SftpProgressListenerReady=true;});return 'registered';})()");
        assertEquals("the real Capacitor SFTP progress event must be observable", "registered", installed);
        awaitJsTrue("window.__j20SftpProgressListenerReady === true");
    }

    private void assertPickerWasReallyLaunched() {
        boolean observed = Intents.getIntents().stream().anyMatch(intent ->
                Intent.ACTION_OPEN_DOCUMENT.equals(intent.getAction())
                        && intent.getCategories() != null
                        && intent.getCategories().contains(Intent.CATEGORY_OPENABLE)
                        && intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false));
        assertTrue("the composer must invoke its real multiple-document picker", observed);
    }

    private JSONObject awaitPositiveProgress() throws Exception {
        long deadline = SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MILLIS;
        String last = "<not evaluated>";
        while (SystemClock.elapsedRealtime() < deadline) {
            JSONObject state = composerProgressSnapshot();
            last = state.toString();
            if (state.optBoolean("progressVisible") && state.optInt("value", 0) > 0) return state;
            if (state.optInt("stagedCount", 0) == FILE_COUNT) {
                throw new AssertionError("all three SFTP uploads completed without a nonzero determinate bar; "
                        + "this is the mutation discriminator for missing native byte-progress reporting. state=" + last);
            }
            if (state.optInt("issueCount", 0) > 0) {
                throw new AssertionError("the real picker/stager reported an attachment failure before progress: " + last);
            }
            SystemClock.sleep(POLL_INTERVAL_MILLIS);
        }
        throw new AssertionError("the real JS composer never displayed nonzero upload progress; "
                + "missing transport reporting must fail this journey. last=" + last);
    }

    private JSONObject composerProgressSnapshot() throws Exception {
        return evalJson("(() => {const shell=document.querySelector('.app-shell');"
                + "const bar=document.querySelector('[data-testid=composer-upload-progress]');"
                + "const status=document.querySelector('[data-testid=composer-status]');"
                + "const actions=document.querySelector('[data-testid=composer-actions]');"
                + "const fill=bar?.querySelector('.composer-upload-progress__fill');"
                + "const track=bar?.querySelector('.composer-upload-progress__track');"
                + "const rect=node=>{if(!node)return null;const r=node.getBoundingClientRect();"
                + "return {top:r.top,bottom:r.bottom,left:r.left,right:r.right,width:r.width,height:r.height};};"
                + "return JSON.stringify({keyboardVisible:shell?.dataset.keyboardVisible==='true',"
                + "keyboardComposerMode:shell?.dataset.keyboardComposerMode==='true',"
                + "progressVisible:!!bar,progressTrackVisible:!!track,role:bar?.getAttribute('role')??null,"
                + "viewportHeight:window.visualViewport?.height??innerHeight,progressRect:rect(bar),"
                + "label:bar?.getAttribute('aria-label')??null,min:Number(bar?.getAttribute('aria-valuemin')??-1),"
                + "max:Number(bar?.getAttribute('aria-valuemax')??-1),value:Number(bar?.getAttribute('aria-valuenow')??0),"
                + "valueText:bar?.getAttribute('aria-valuetext')??null,uploadPercent:Number(bar?.dataset.uploadPercent??0),"
                + "labelText:bar?.querySelector('.composer-upload-progress__label')?.textContent.trim()??null,"
                + "fillWidth:rect(fill)?.width??0,trackWidth:rect(track)?.width??0,statusRect:rect(status),"
                + "actionsRect:rect(actions),stagedCount:document.querySelectorAll('[data-testid=composer-attachments] [data-attachment-state=staged]').length,"
                + "stagedRowsInViewport:Array.from(document.querySelectorAll('[data-testid=composer-attachments] [data-attachment-state=staged]'))"
                + ".every(node=>{const r=node.getBoundingClientRect();const h=window.visualViewport?.height??innerHeight;return r.top>=0&&r.bottom<=h}),"
                + "pendingCount:document.querySelectorAll('[data-testid=composer-attachments] [data-attachment-state=pending]').length,"
                + "issueCount:document.querySelectorAll('[data-testid=composer-attachment-issues] li').length,"
                + "statusText:status?.textContent.trim()??null});})()");
    }

    private void assertAccessibleDeterminateProgress(JSONObject state, String phase) throws Exception {
        assertEquals(phase + " state must expose a progressbar role", "progressbar", state.getString("role"));
        assertEquals(phase + " state must label the uploaded attachment", "Attachment upload progress",
                state.getString("label"));
        assertEquals(phase + " state must use a 0..100 accessible range", 0, state.getInt("min"));
        assertEquals(phase + " state must use a 0..100 accessible range", 100, state.getInt("max"));
        assertTrue(phase + " progress must be a nonzero determinate value", state.getInt("value") > 0
                && state.getInt("value") < 100);
        assertEquals(phase + " visual and accessible percentages must agree", state.getInt("value"),
                state.getInt("uploadPercent"));
        assertTrue(phase + " accessible text must include file position and acknowledged byte totals",
                state.getString("valueText").contains(" of 3;")
                        && state.getString("valueText").contains("overall."));
        assertTrue(phase + " screenshot must contain a visibly nonempty determinate track",
                state.getDouble("fillWidth") > 0 && state.getDouble("trackWidth") > 0);
        JSONObject bar = state.getJSONObject("progressRect");
        assertTrue(phase + " progress row must fit inside the keyboard-safe viewport before capture: " + state,
                bar.getDouble("top") >= 0 && bar.getDouble("bottom") <= state.getDouble("viewportHeight"));
    }

    private JSONObject assertNativeProgressEvents(boolean requireInFlight) throws Exception {
        JSONObject snapshot = evalJson("JSON.stringify({events:window.__j20SftpProgressEvents||[]})");
        JSONArray events = snapshot.getJSONArray("events");
        assertTrue("at least one acknowledged native SFTP byte event must reach the packaged WebView",
                events.length() > 0);
        Map<String, Long> lastBytesByPath = new HashMap<>();
        Map<String, Long> totalBytesByPath = new HashMap<>();
        int positive = 0;
        for (int index = 0; index < events.length(); index++) {
            JSONObject event = events.getJSONObject(index);
            String path = event.getString("path");
            long written = event.getLong("bytesWritten");
            long total = event.getLong("totalBytes");
            assertTrue("native event must identify the production attachment path", path.startsWith(REMOTE_ATTACHMENTS + "/"));
            assertTrue("native event must report real positive acknowledged bytes", written > 0 && written <= total);
            assertEquals("each event must report this file's real size", PAYLOAD_BYTES, total);
            Long previous = lastBytesByPath.put(path, written);
            assertTrue("acknowledged byte events must increase monotonically for each file",
                    previous == null || written >= previous);
            totalBytesByPath.put(path, total);
            positive++;
        }
        assertTrue("transport progress must be observed while the throttled batch remains in flight",
                !requireInFlight || positive > 0);
        for (Map.Entry<String, Long> entry : totalBytesByPath.entrySet()) {
            if (!requireInFlight) {
                assertEquals("every completed file must end at its full native SFTP byte count",
                        entry.getValue(), lastBytesByPath.get(entry.getKey()));
            }
        }
        return snapshot;
    }

    private void ensureKeyboardOpenDuringUpload() throws Exception {
        if (!isImeVisible()) {
            tapDomCenter("[data-testid=prompt-draft]");
            awaitImeVisible(true);
        }
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.keyboardVisible === 'true'"
                + " && document.querySelector('.app-shell')?.dataset.keyboardComposerMode === 'true'");
        assertTrue("the Android IME must remain visible for the composer screenshot", isImeVisible());
    }

    private void scrollUploadProgressIntoKeyboardViewport() throws Exception {
        evalString("(() => {const bar=document.querySelector('[data-testid=composer-upload-progress]');"
                + "if(!bar)throw new Error('missing upload progress bar before screenshot');"
                + "bar.scrollIntoView({block:'end',inline:'nearest',behavior:'instant'});return 'scrolled';})()");
        awaitJsTrue("(() => {const bar=document.querySelector('[data-testid=composer-upload-progress]');"
                + "if(!bar)return false;const r=bar.getBoundingClientRect();"
                + "const h=window.visualViewport?.height??innerHeight;"
                + "return r.top>=0&&r.bottom<=h&&r.left>=0&&r.right<=innerWidth})()");
        assertTrue("the Android IME must stay open after bringing progress into view", isImeVisible());
    }

    private void scrollCompletedAttachmentsIntoKeyboardViewport() throws Exception {
        evalString("(() => {const rows=document.querySelectorAll('[data-testid=composer-attachments] [data-attachment-state=staged]');"
                + "if(rows.length!==3)throw new Error('expected three retained attachment rows');"
                + "rows[rows.length-1].scrollIntoView({block:'end',inline:'nearest',behavior:'instant'});return 'scrolled';})()");
        awaitJsTrue("(() => {const rows=Array.from(document.querySelectorAll("
                + "'[data-testid=composer-attachments] [data-attachment-state=staged]'));"
                + "const h=window.visualViewport?.height??innerHeight;return rows.length===3&&rows.every(node=>{"
                + "const r=node.getBoundingClientRect();return r.top>=0&&r.bottom<=h&&r.left>=0&&r.right<=innerWidth})})()");
        assertTrue("the Android IME must stay open after bringing completed rows into view", isImeVisible());
    }

    private void awaitUploadComplete() throws Exception {
        awaitJsTrue("document.querySelectorAll('[data-testid=composer-attachments] [data-attachment-state=staged]').length === 3"
                + " && document.querySelector('[data-testid=composer-upload-progress]') === null"
                + " && document.querySelector('.composer-upload-progress__track') === null"
                + " && document.querySelector('[data-testid=composer-attachment-issues]') === null",
                WAIT_TIMEOUT_MILLIS);
    }

    private List<String> readStagedPaths() throws Exception {
        JSONArray paths = evalJson("JSON.stringify({paths:Array.from(document.querySelectorAll("
                + "'[data-testid=composer-attachments] [data-attachment-state=staged]'))"
                + ".map(node=>node.dataset.attachmentPath||'')})").getJSONArray("paths");
        List<String> result = new ArrayList<>();
        for (int index = 0; index < paths.length(); index++) result.add(paths.getString(index));
        return result;
    }

    private void assertNativeProgressPathsMatch(JSONObject snapshot, List<String> stagedPaths) throws Exception {
        JSONArray events = snapshot.getJSONArray("events");
        java.util.Set<String> eventPaths = new java.util.HashSet<>();
        for (int index = 0; index < events.length(); index++) {
            eventPaths.add(events.getJSONObject(index).getString("path"));
        }
        assertEquals("the app's staged files must be exactly the files with native SFTP byte events",
                new java.util.HashSet<>(stagedPaths), eventPaths);
    }

    private JSONObject composerGeometry() throws Exception {
        return evalJson("(() => {const status=document.querySelector('[data-testid=composer-status]');"
                + "const actions=document.querySelector('[data-testid=composer-actions]');"
                + "const rect=node=>{const r=node?.getBoundingClientRect();return r?{top:r.top,bottom:r.bottom,height:r.height}:null};"
                + "return JSON.stringify({status:rect(status),actions:rect(actions),"
                + "actionsGap:actions&&status?actions.getBoundingClientRect().top-status.getBoundingClientRect().bottom:null});})()");
    }

    private void assertFixedComposerStatusRow(JSONObject baseline, JSONObject current, String phase) throws Exception {
        JSONObject baselineStatus = baseline.getJSONObject("status");
        JSONObject currentStatus = current.getJSONObject("statusRect");
        JSONObject baselineActions = baseline.getJSONObject("actions");
        JSONObject currentActions = current.getJSONObject("actionsRect");
        assertEquals(phase + " composer status row must remain 14 CSS pixels tall",
                14.0, currentStatus.getDouble("height"), 0.5);
        assertEquals(phase + " status row height must match its pre-upload size",
                baselineStatus.getDouble("height"), currentStatus.getDouble("height"), 0.5);
        assertEquals(phase + " action row must remain anchored immediately after the status row",
                baselineActions.getDouble("top") - baselineStatus.getDouble("bottom"),
                currentActions.getDouble("top") - currentStatus.getDouble("bottom"), 0.5);
    }

    private void capturePersistentScreenshot(String phase, JSONObject state, JSONObject nativeEvents) throws Exception {
        assertTrue("keyboard-up screenshot requires the actual Android IME", isImeVisible());
        awaitWebViewVisualState();
        android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull("UI automation must capture the real packaged app screen", screenshot);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        assertTrue("real app screenshot must encode as PNG", screenshot.compress(Bitmap.CompressFormat.PNG, 100, encoded));
        screenshot.recycle();
        byte[] png = encoded.toByteArray();
        assertTrue("screenshot must contain actual image data", png.length >= 1024);
        String name = "i2929-j20-" + phase + "-" + artifactRunId + ".png";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, name);
        values.put(MediaStore.Downloads.MIME_TYPE, "image/png");
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        assertNotNull("persistent screenshot must be saved to MediaStore Downloads", uri);
        try (java.io.OutputStream output = context.getContentResolver().openOutputStream(uri)) {
            assertNotNull("persistent screenshot output stream must open", output);
            output.write(png);
        }
        Log.i(TAG, "SCREENSHOT|" + artifactRunId + "|" + phase + "|" + uri
                + "|" + png.length + " bytes|ui=" + state + "|native=" + summarizeNativeProgressEvents(nativeEvents));
    }

    private JSONObject summarizeNativeProgressEvents(JSONObject nativeEvents) throws Exception {
        JSONArray events = nativeEvents.getJSONArray("events");
        Map<String, JSONObject> latestByPath = new TreeMap<>();
        for (int index = 0; index < events.length(); index++) {
            JSONObject event = events.getJSONObject(index);
            latestByPath.put(event.getString("path"), event);
        }
        JSONArray latest = new JSONArray();
        for (JSONObject event : latestByPath.values()) {
            latest.put(new JSONObject()
                    .put("path", event.getString("path"))
                    .put("bytesWritten", event.getLong("bytesWritten"))
                    .put("totalBytes", event.getLong("totalBytes")));
        }
        return new JSONObject().put("eventCount", events.length()).put("latestByPath", latest);
    }

    private void awaitWebViewVisualState() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            WebView webView = findWebView(activity.getWindow().getDecorView());
            assertNotNull("packaged app must contain its Capacitor WebView", webView);
            webView.postVisualStateCallback(SystemClock.uptimeMillis(), new WebView.VisualStateCallback() {
                @Override
                public void onComplete(long requestId) {
                    latch.countDown();
                }
            });
        });
        assertTrue("WebView did not render the measured composer state before screenshot capture",
                latch.await(JS_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        SystemClock.sleep(250);
    }

    private void verifyIndependentHostPayloads(List<String> paths) throws Exception {
        assertEquals("host oracle must check the exact three staged files", FILE_COUNT, paths.size());
        StringBuilder command = new StringBuilder();
        for (String path : paths) {
            command.append("printf '%s\\t' \"$(stat -c %s -- ")
                    .append(shellQuote(path))
                    .append(" 2>/dev/null)\"; sha256sum -- ")
                    .append(shellQuote(path))
                    .append(" | awk '{print $1}'; ");
        }
        String report = execOnFixture(command.toString());
        String[] lines = report.trim().split("\\R");
        assertEquals("independent fixture oracle must return one size/hash pair per file", FILE_COUNT, lines.length);
        for (int index = 0; index < paths.size(); index++) {
            String path = paths.get(index);
            String sourceName = path.substring(path.lastIndexOf('/') + 1);
            String matchedSource = null;
            for (String name : sourcePayloads.keySet()) {
                if (sourceName.endsWith(name)) matchedSource = name;
            }
            assertNotNull("host path must preserve its real picked filename: " + path, matchedSource);
            String[] actual = lines[index].split("\\t");
            assertEquals("host oracle must report actual file size", 2, actual.length);
            assertEquals("Docker host must hold the entire real payload", PAYLOAD_BYTES, Integer.parseInt(actual[0]));
            assertEquals("Docker host content must match the selected provider file byte-for-byte",
                    sha256(sourcePayloads.get(matchedSource)), actual[1]);
            Log.i(TAG, "HOST_PAYLOAD|" + artifactRunId + "|" + path + "|bytes=" + actual[0]
                    + "|sha256=" + actual[1]);
        }
    }

    private void cleanupPayloadsBestEffort() throws Exception {
        String pattern = shellQuote("*" + payloadStem + "*");
        execOnFixture(findRunPayloadsCommand(pattern, "-delete"));
    }

    private static String findRunPayloadsCommand(String quotedPattern, String action) {
        // Fresh Docker fixtures have no attachment directory. Treat that as
        // the empty result set while allowing SSH/find errors inside an
        // existing directory to fail the independent host oracle.
        return "if [ ! -d " + shellQuote(REMOTE_ATTACHMENTS) + " ]; then exit 0; fi; find "
                + shellQuote(REMOTE_ATTACHMENTS) + " -type f -name " + quotedPattern + " " + action;
    }

    private void removeSlowUploadToxic() throws Exception {
        if (toxicName == null) return;
        execOnFixture("curl -sS -X DELETE http://network-fault-proxy:8474/proxies/"
                + PROXY_NAME + "/toxics/" + toxicName + " >/dev/null 2>&1 || true");
        proxyPrepared = false;
    }

    /** Independent sshj connection to the direct agents port, not the app's proxy connection. */
    private String execOnFixture(String commandText) throws Exception {
        ensureSshCryptoProvider();
        SSHClient client = new SSHClient(new DefaultConfig());
        try {
            client.addHostKeyVerifier(new PromiscuousVerifier());
            client.setConnectTimeout(10_000);
            client.setTimeout(45_000);
            client.connect(host, directPort);
            client.authPublickey(USERNAME,
                    client.loadKeys(privateKeyPem, (String) null, (PasswordFinder) null));
            try (Session session = client.startSession()) {
                Session.Command running = session.exec(commandText);
                String stdout = new String(running.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String stderr = new String(running.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                running.join(45, TimeUnit.SECONDS);
                Integer exit = running.getExitStatus();
                assertNotNull("independent fixture SSH command must finish", exit);
                assertEquals("independent fixture SSH command failed: " + commandText + "\nstderr=" + stderr,
                        Integer.valueOf(0), exit);
                return stdout;
            }
        } finally {
            try {
                client.disconnect();
            } catch (Exception ignored) {
                // The oracle result is already captured; disconnect failure is not the assertion.
            }
        }
    }

    /** Match the production sshj adapter's Android provider fix for X25519. */
    private static synchronized void ensureSshCryptoProvider() {
        Provider installed = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME);
        if (installed == null || installed.getService("KeyPairGenerator", "X25519") == null) {
            if (installed != null) Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME);
            Security.insertProviderAt(new BouncyCastleProvider(), 1);
        }
        Provider selected = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME);
        if (selected == null || selected.getService("KeyPairGenerator", "X25519") == null) {
            throw new IllegalStateException("the bundled test BC provider does not offer X25519");
        }
        SecurityUtils.setRegisterBouncyCastle(false);
        SecurityUtils.setSecurityProvider(BouncyCastleProvider.PROVIDER_NAME);
    }

    private void setComposerDraft(String value) throws Exception {
        setValue("[data-testid=prompt-draft]", value);
        awaitJsTrue("document.querySelector('[data-testid=prompt-draft]')?.value === " + JSONObject.quote(value));
    }

    private void setValue(String selector, String value) throws Exception {
        evalString("(() => {const node=document.querySelector(" + JSONObject.quote(selector) + ");"
                + "if(!node)throw new Error('missing '+" + JSONObject.quote(selector) + ");"
                + "node.value=" + JSONObject.quote(value) + ";node.dispatchEvent(new Event('input',{bubbles:true}));"
                + "node.dispatchEvent(new Event('change',{bubbles:true}));return 'set';})()");
    }

    private void click(String selector) throws Exception {
        evalString("(() => {const node=document.querySelector(" + JSONObject.quote(selector) + ");"
                + "if(!node)throw new Error('missing '+" + JSONObject.quote(selector) + ");node.click();return 'clicked';})()");
    }

    private void tapDomCenter(String selector) throws Exception {
        JSONObject point = evalJson("(() => {const element=document.querySelector(" + JSONObject.quote(selector)
                + ");if(!element)return JSON.stringify({missing:true});const rect=element.getBoundingClientRect();"
                + "const height=window.visualViewport?.height??innerHeight;return JSON.stringify({x:rect.left+rect.width/2,"
                + "y:rect.top+rect.height/2,width:innerWidth,height,top:rect.top,bottom:rect.bottom,left:rect.left,"
                + "right:rect.right,disabled:!!element.disabled});})()");
        assertTrue("Android touch target must exist: " + selector, !point.optBoolean("missing"));
        assertTrue("Android touch target must be enabled: " + selector, !point.optBoolean("disabled"));
        assertTrue("Android touch target must be visible in the WebView: " + point,
                point.optDouble("top", -1) >= 0 && point.optDouble("bottom", -1) <= point.optDouble("height") + 0.5
                        && point.optDouble("left", -1) >= 0 && point.optDouble("right", -1) <= point.optDouble("width") + 0.5);
        AtomicReference<float[]> screenPoint = new AtomicReference<>();
        scenario.onActivity(activity -> {
            WebView webView = findWebView(activity.getWindow().getDecorView());
            assertNotNull("packaged app must contain its Capacitor WebView", webView);
            int[] location = new int[2];
            webView.getLocationOnScreen(location);
            float scale = webView.getWidth() / (float) point.optDouble("width");
            screenPoint.set(new float[] { location[0] + (float) point.optDouble("x") * scale,
                    location[1] + (float) point.optDouble("y") * scale });
        });
        float[] screen = screenPoint.get();
        long downTime = SystemClock.uptimeMillis();
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, screen[0], screen[1], 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        assertTrue("Android touchscreen ACTION_DOWN must be injected",
                instrumentation.getUiAutomation().injectInputEvent(down, true));
        down.recycle();
        SystemClock.sleep(50);
        MotionEvent up = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP,
                screen[0], screen[1], 0);
        up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        assertTrue("Android touchscreen ACTION_UP must be injected",
                instrumentation.getUiAutomation().injectInputEvent(up, true));
        up.recycle();
    }

    private boolean isImeVisible() {
        AtomicReference<Boolean> visible = new AtomicReference<>(false);
        scenario.onActivity(activity -> {
            WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
            visible.set(insets != null && insets.isVisible(WindowInsets.Type.ime()));
        });
        return visible.get();
    }

    private void awaitImeVisible(boolean visible) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 15_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isImeVisible() == visible) return;
            SystemClock.sleep(100);
        }
        throw new AssertionError("Android IME visibility did not become " + visible);
    }

    private void awaitJsTrue(String expression) throws Exception {
        awaitJsTrue(expression, 30_000);
    }

    private void awaitJsTrue(String expression, long timeoutMillis) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeoutMillis;
        String last = "<not evaluated>";
        while (SystemClock.elapsedRealtime() < deadline) {
            last = evalRaw(expression);
            if ("true".equals(last)) return;
            SystemClock.sleep(POLL_INTERVAL_MILLIS);
        }
        throw new AssertionError("packaged WebView condition did not become true: " + expression
                + " (last=" + last + "; page=" + evalString("document.body.innerText") + ")");
    }

    private String evalString(String expression) throws Exception {
        String raw = evalRaw(expression);
        Object decoded = new JSONTokener(raw).nextValue();
        return decoded == null ? null : decoded.toString();
    }

    private JSONObject evalJson(String expression) throws Exception {
        return new JSONObject(evalString(expression));
    }

    private String evalRaw(String expression) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        scenario.onActivity(activity -> {
            WebView webView = findWebView(activity.getWindow().getDecorView());
            assertNotNull("packaged Capacitor activity must contain a WebView", webView);
            webView.evaluateJavascript(expression, value -> {
                result.set(value);
                latch.countDown();
            });
        });
        assertTrue("timed out evaluating packaged WebView JavaScript",
                latch.await(JS_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (result.get() == null || "null".equals(result.get())) {
            throw new JSONException("JavaScript returned null: " + expression);
        }
        return result.get();
    }

    private static WebView findWebView(View view) {
        if (view instanceof WebView) return (WebView) view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                WebView found = findWebView(group.getChildAt(index));
                if (found != null) return found;
            }
        }
        return null;
    }

    private int integerArgument(String value, int fallback) {
        if (value == null || value.trim().isEmpty()) return fallback;
        return Integer.parseInt(value.trim());
    }

    private String payloadFileName(int index) {
        return payloadStem + "-" + (index + 1) + ".bin";
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String sha256(byte[] payload) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload);
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte value : digest) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }
}
