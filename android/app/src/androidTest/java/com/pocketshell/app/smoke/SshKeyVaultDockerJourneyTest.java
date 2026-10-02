package com.pocketshell.app.smoke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.SystemClock;
import android.webkit.WebView;

import androidx.core.content.FileProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.intent.Intents;
import androidx.test.espresso.intent.matcher.IntentMatchers;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.pocketshell.app.MainActivity;

import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Packaged opaque-key import and SSH authentication journey against Docker sshd. */
@RunWith(AndroidJUnit4.class)
public final class SshKeyVaultDockerJourneyTest {
    private static final long WAIT_TIMEOUT_MILLIS = 45_000;
    private static final long JS_TIMEOUT_SECONDS = 15;
    private static final String KEY_FINGERPRINT = "SHA256:geJoGi64Up5pm2TGC6bdVNrvlIA1vuPIOtNKo2tLsuQ";
    private static final String TEST_PASSPHRASE = "pocketshell-vault-test-passphrase";

    private ActivityScenario<MainActivity> scenario;
    private File stagedEncryptedKey;
    private File screenshotDirectory;
    private Bitmap previousScreenshot;

    @Before public void launchPackagedShell() {
        Intents.init();
        scenario = ActivityScenario.launch(MainActivity.class);
    }

    @After public void closePackagedShell() {
        if (scenario != null) scenario.close();
        Intents.release();
        if (stagedEncryptedKey != null) stagedEncryptedKey.delete();
        if (previousScreenshot != null) previousScreenshot.recycle();
    }

    @Test public void importsEncryptedDocumentConnectsAndKeepsSecretsOutOfWebViewState() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String host = requiredArgument("sshHost");
        String port = requiredArgument("sshPort");
        String keyFixtureName = requiredArgument("keyFixtureName");
        String runId = requiredArgument("keyVaultRunId");
        assertTrue("the key import must come from the staged app-private external file", keyFixtureName.matches("key-[A-Za-z0-9_-]+\\.pem"));
        assertTrue("run ID must be path-safe", runId.matches("[A-Za-z0-9][A-Za-z0-9_-]{2,38}"));
        File externalFiles = context.getExternalFilesDir(null);
        assertNotNull("target app external files directory must be available", externalFiles);
        stagedEncryptedKey = new File(externalFiles, keyFixtureName);
        assertTrue("the runner must stage the encrypted test key on the device", stagedEncryptedKey.isFile());
        Uri documentUri = FileProvider.getUriForFile(
            context,
            context.getPackageName() + ".fileprovider",
            stagedEncryptedKey
        );
        context.grantUriPermission(context.getPackageName(), documentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        screenshotDirectory = new File(externalFiles, "pocketshell-key-vault/" + runId);
        assertTrue("run-scoped screenshot directory must be new", screenshotDirectory.mkdirs());

        awaitJsTrue("document.querySelector('[data-testid=build-status]')?.dataset.state === 'verified'");
        click("[data-testid=open-ssh-keys]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'keys' && !!document.querySelector('[data-testid=ssh-key-import-tab]')");
        setValue("[data-testid=ssh-key-label]", "Docker fixture key");
        setValue("[data-testid=ssh-key-import-passphrase]", TEST_PASSPHRASE);
        Intents.intending(IntentMatchers.hasAction(Intent.ACTION_OPEN_DOCUMENT))
            .respondWith(new Instrumentation.ActivityResult(
                Activity.RESULT_OK,
                new Intent().setData(documentUri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ));
        click("[data-testid=import-ssh-key]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'home' && !!document.querySelector('[data-testid=ssh-key-selection]')?.value");
        String importedKeyHandle = evalString("document.querySelector('[data-testid=ssh-key-selection]')?.value ?? ''");
        assertTrue("the WebView receives only an opaque UUID handle", importedKeyHandle.matches("[0-9a-fA-F-]{36}"));
        assertEquals("the selected key details must retain its full public fingerprint", KEY_FINGERPRINT,
            evalString("document.querySelector('[data-testid=selected-ssh-key-fingerprint]')?.textContent.trim() ?? ''"));
        assertVaultCiphertextOnly(context, importedKeyHandle);
        assertNoWebViewSecrets();

        click("[data-testid=open-ssh-keys]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'keys' && document.querySelector('[data-testid=ssh-key-count]')?.textContent.trim() === '1'");
        assertTrue("the import passphrase field must be empty", "".equals(evalString("document.querySelector('[data-testid=ssh-key-import-passphrase]')?.value ?? ''")));
        assertEquals("the imported key must match the Docker authorized public key", KEY_FINGERPRINT,
            evalString("document.querySelector('[data-testid^=ssh-key-] .key-row__copy code')?.textContent.trim() ?? ''"));
        captureScreenshot("ssh-keys.png");

        click("[data-testid^=select-ssh-key-]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'home' && document.querySelector('[data-testid=ssh-key-selection]')?.value === '" + importedKeyHandle + "'");
        setValue("[data-testid=ssh-host]", host);
        setValue("[data-testid=ssh-port]", port);
        setValue("[data-testid=ssh-username]", "testuser");
        captureScreenshot("ssh-host-form.png");
        assertHostFormTextFits();
        // #3023: native resource counts are a hidden test hook, never chrome on the host form.
        assertEquals("the SSH resource counts must not render on the host form", "true",
            evalString("(() => {const hook=document.querySelector('[data-testid=ssh-resources]');"
                + "return String(!!hook && hook.hidden && hook.getClientRects().length === 0"
                + " && !document.body.innerText.includes('PTY channels'));})()"));
        setValue("[data-testid=legacy-key-passphrase]", TEST_PASSPHRASE);
        long importedConnectStartedAt = SystemClock.elapsedRealtime();
        click("[data-testid=ssh-connect]");
        awaitJsTrue("!!document.querySelector('[data-testid=host-key-decision]') || !!document.querySelector('[data-testid=ssh-message]')");
        assertTrue("the connection passphrase clears from the visible input after one attempt",
            "".equals(evalString("document.querySelector('[data-testid=legacy-key-passphrase]')?.value ?? ''")));
        assertNoWebViewSecrets();
        String hostKey = evalString("document.querySelector('[data-testid=host-key-fingerprint]')?.textContent.trim() ?? ''");
        assertTrue("the Docker host must present a SHA-256 host-key fingerprint", hostKey.startsWith("SHA256:") && hostKey.length() > 20);
        click("[data-testid=trust-host-key]");
        awaitJsTrue("['connected','listing'].includes(document.querySelector('.app-shell')?.dataset.sshPhase)");
        click("[data-testid=open-sessions]");
        String sessionName = "keyvault-" + runId;
        setValue("[data-testid=new-session-name]", sessionName);
        click("[data-testid=create-session]");
        awaitJsTrue("Array.from(document.querySelectorAll('[data-testid=session-list] .session-row')).some(row => row.dataset.sessionTag === '" + sessionName + "')");
        click("[data-testid=session-list] .session-row[data-session-tag=\"" + sessionName + "\"]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.homeSurface === 'live' && document.querySelector('#terminal-viewport')?.dataset.enabled === 'true'");
        long importedSessionAttachedAt = SystemClock.elapsedRealtime();
        assertNoWebViewSecrets();

        // #2926 review: the core only retries retryable dial codes. After a
        // server-side loss, core redials the encrypted stored key without the
        // transient passphrase; that must fail once as AUTH_FAILED and stop,
        // never loop through every reconnect attempt as a transport error.
        assertLockedStoredKeyReconnectStopsAfterOneAttempt(runId);
        setValue("[data-testid=legacy-key-passphrase]", TEST_PASSPHRASE);
        click("[data-testid=ssh-connect]");
        awaitJsTrue("['connected','listing'].includes(document.querySelector('.app-shell')?.dataset.sshPhase)");
        click("[data-testid=open-sessions]");
        awaitJsTrue("Array.from(document.querySelectorAll('[data-testid=session-list] .session-row')).some(row => row.dataset.sessionTag === '" + sessionName + "')");
        click("[data-testid=session-list] .session-row[data-session-tag=\"" + sessionName + "\"]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.homeSurface === 'live' && document.querySelector('#terminal-viewport')?.dataset.enabled === 'true'");
        assertNoWebViewSecrets();

        seedLegacyKeyHostReference(importedKeyHandle);
        click("[data-testid=open-ssh-keys]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'keys' && document.querySelector('[data-testid=ssh-key-count]')?.textContent.trim() === '1'");
        click("[data-testid=delete-ssh-key-" + importedKeyHandle + "]");
        awaitJsTrue("!!document.querySelector('[data-testid=ssh-key-delete-confirmation]')");
        String deleteConfirmation = evalString("document.querySelector('[data-testid=ssh-key-delete-confirmation]')?.textContent ?? ''");
        assertTrue("the delete dialog must name each affected saved host", deleteConfirmation.contains("Saved Docker host"));
        assertTrue("the delete dialog must show the explicit association-and-delete action", deleteConfirmation.contains("Remove associations and delete key"));
        assertVaultContainsHandle(importedKeyHandle, true, "opening the confirmation must leave the key in the vault");
        captureScreenshot("ssh-key-delete-confirmation.png");
        click("[data-testid=cancel-delete-ssh-key]");
        awaitJsTrue("!document.querySelector('[data-testid=ssh-key-delete-confirmation]')");
        assertVaultContainsHandle(importedKeyHandle, true, "cancel must leave the imported key available");
        click("[data-testid=delete-ssh-key-" + importedKeyHandle + "]");
        awaitJsTrue("!!document.querySelector('[data-testid=ssh-key-delete-confirmation]')");
        click("[data-testid=confirm-delete-ssh-key]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'home'");
        assertVaultContainsHandle(importedKeyHandle, false, "explicit confirmation must delete the native key");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.homeSurface === 'live'");
        click("[data-testid=open-connection]");
        awaitJsTrue("document.querySelector('[data-testid=ssh-key-selection]')?.value === ''");
        click("[data-testid=open-sessions]");
        awaitJsTrue("!!document.querySelector('[data-testid=session-list]')");
        click("[data-testid=session-list] .session-row[data-session-tag=\"" + sessionName + "\"]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.homeSurface === 'live'");

        click("button[aria-label=\"Settings\"]");
        awaitJsTrue("!!document.querySelector('[data-testid=settings-screen]')");
        click("[data-testid=open-diagnostics]");
        awaitJsTrue("!!document.querySelector('[data-testid=diagnostics-screen]')");
        click("[data-testid=review-diagnostics-export]");
        awaitJsTrue("!!document.querySelector('[data-testid=diagnostics-report-preview]')");
        String diagnosticExport = evalString("document.querySelector('[data-testid=diagnostics-report-preview]')?.textContent ?? ''");
        JSONObject diagnosticReport = new JSONObject(diagnosticExport);
        assertEquals("the reviewed diagnostics export must use the current schema", 1, diagnosticReport.getInt("schema"));
        assertTrue("the real Diagnostics preview must contain same-run build events",
            diagnosticExport.contains("\"kind\": \"app-started\"")
                && diagnosticExport.contains("\"kind\": \"build-verified\""));
        assertFalse("the actual Diagnostics preview must not contain a key PEM", diagnosticExport.contains("PRIVATE KEY"));
        assertFalse("the actual Diagnostics preview must not contain the passphrase", diagnosticExport.contains(TEST_PASSPHRASE));
        assertFalse("the actual Diagnostics preview must not contain the fixture address", diagnosticExport.contains(host));
        assertFalse("the actual Diagnostics preview must not contain the fixture user", diagnosticExport.contains("testuser"));
        writeEvidence("diagnostics-export-preview.json", diagnosticExport);
        captureScreenshot("diagnostics-export-preview.png");

        JSONArray measurements = new JSONArray();
        measurements.put(connectionTiming("imported-key", sessionName, importedConnectStartedAt, importedSessionAttachedAt));

        // The second connection uses a key generated inside Android secure storage.
        // Its public half is derived only by instrumentation and added to this
        // isolated Docker fixture before the app is allowed to connect.
        click("button[aria-label=\"Back\"]");
        click("button[aria-label=\"Back\"]");
        click("button[aria-label=\"Back\"]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.homeSurface === 'live'");
        click("[data-testid=ssh-disconnect]");
        awaitJsTrue("!['connected','listing','live'].includes(document.querySelector('.app-shell')?.dataset.sshPhase)");
        click("[data-testid=open-ssh-keys]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'keys'");
        click("[data-testid=ssh-key-generate-tab]");
        setValue("[data-testid=ssh-key-label]", "Generated Docker key");
        click("[data-testid=generate-ssh-key]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'home' && document.querySelector('[data-testid=ssh-key-selection]')?.selectedOptions[0]?.textContent.includes('Generated Docker key')");
        String generatedKeyHandle = evalString("document.querySelector('[data-testid=ssh-key-selection]')?.value ?? ''");
        String generatedFingerprint = evalString("document.querySelector('[data-testid=selected-ssh-key-fingerprint]')?.textContent.trim() ?? ''");
        assertTrue("the generated key remains an opaque selected UUID handle", generatedKeyHandle.matches("[0-9a-fA-F-]{36}"));
        assertTrue("the generated key exposes only its public fingerprint", generatedFingerprint.startsWith("SHA256:") && generatedFingerprint.length() > 20);
        assertVaultCiphertextOnly(context, generatedKeyHandle);
        File publicKeyEvidence = new File(screenshotDirectory, "generated-authorized-key.pub");
        File generatedFingerprintEvidence = new File(screenshotDirectory, "generated-key-fingerprint.txt");
        com.pocketshell.app.GeneratedSshKeyTestSupport.writeDockerAuthorizationEvidence(
            context, generatedKeyHandle, generatedFingerprint, publicKeyEvidence, generatedFingerprintEvidence);
        awaitFile(new File(screenshotDirectory, "generated-key-authorized"), 90_000);
        assertEquals("the runner must authorize the exact selected generated key fingerprint",
            generatedFingerprint, new String(java.nio.file.Files.readAllBytes(generatedFingerprintEvidence.toPath()), StandardCharsets.US_ASCII).trim());

        long generatedConnectStartedAt = SystemClock.elapsedRealtime();
        click("[data-testid=ssh-connect]");
        awaitJsTrue("!!document.querySelector('[data-testid=trust-host-key]') || ['connected','listing'].includes(document.querySelector('.app-shell')?.dataset.sshPhase) || !!document.querySelector('[data-testid=ssh-message]')");
        if ("true".equals(evalString("!!document.querySelector('[data-testid=trust-host-key]')"))) click("[data-testid=trust-host-key]");
        awaitJsTrue("['connected','listing'].includes(document.querySelector('.app-shell')?.dataset.sshPhase)");
        click("[data-testid=open-sessions]");
        String generatedSessionName = "keyvault-generated-" + runId;
        setValue("[data-testid=new-session-name]", generatedSessionName);
        click("[data-testid=create-session]");
        awaitJsTrue("Array.from(document.querySelectorAll('[data-testid=session-list] .session-row')).some(row => row.dataset.sessionTag === " + JSONObject.quote(generatedSessionName) + ")");
        click("[data-testid=session-list] .session-row[data-session-tag=\"" + generatedSessionName + "\"]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.homeSurface === 'live' && document.querySelector('#terminal-viewport')?.dataset.enabled === 'true'");
        long generatedSessionAttachedAt = SystemClock.elapsedRealtime();
        measurements.put(connectionTiming("generated-key", generatedSessionName, generatedConnectStartedAt, generatedSessionAttachedAt));
        writeEvidence("connect-to-session-timing.json", new JSONObject()
            .put("schema", 1)
            .put("runId", runId)
            .put("measurements", measurements)
            .toString(2));
        awaitFile(new File(screenshotDirectory, "artifacts-captured"), 30_000);
        assertNoWebViewSecrets();
    }

    private void assertLockedStoredKeyReconnectStopsAfterOneAttempt(String runId) throws Exception {
        String connectionId = evalString("document.querySelector('.app-shell')?.dataset.sshConnectionId ?? ''");
        String generationId = evalString("document.querySelector('.app-shell')?.dataset.sshGenerationId ?? ''");
        assertFalse("the stored-key session must be live before the server drop", connectionId.isEmpty() || generationId.isEmpty());
        evalString("(() => {const root=document.querySelector('.app-shell');"
            + "const record={samples:[]};window.__ps2926Reconnect=record;"
            + "const sample=()=>{const next={at:Date.now(),phase:root.dataset.sshPhase,"
            + "retryAttempt:Number(root.dataset.sshRetryAttempt||0),"
            + "message:(document.querySelector('[data-testid=ssh-message]')?.textContent??'').trim()};"
            + "const last=record.samples.at(-1);"
            + "if(!last||last.phase!==next.phase||last.retryAttempt!==next.retryAttempt||last.message!==next.message)record.samples.push(next);};"
            + "sample();record.observer=new MutationObserver(sample);"
            + "record.observer.observe(root,{attributes:true,subtree:true,childList:true,characterData:true});return 'recording';})()");
        // Do not clear logcat (the runner's secret oracle scans all of it);
        // only codes logged after this point belong to the drop.
        String codesBeforeDrop = nativeStoredKeyFailureCodes();
        // Kill this connection's sshd session process on the Docker host: the
        // app sees an abrupt transport loss and core starts its reconnect loop.
        evalString("(() => {window.Capacitor.Plugins.SshCapability.exec({requestId:" + JSONObject.quote("i2926-drop-" + runId)
            + ",connectionId:" + JSONObject.quote(connectionId) + ",generationId:" + JSONObject.quote(generationId)
            + ",command:'kill -KILL \"$PPID\"',timeoutMs:12000}).catch(()=>undefined);return 'dropped';})()");
        awaitJsTrue("window.__ps2926Reconnect.samples.some(sample => sample.phase === 'reconnecting')");
        awaitJsTrue("['lost','error','idle','disconnected'].includes(document.querySelector('.app-shell')?.dataset.sshPhase)"
            + " && !window.__ps2926Reconnect.samples.slice(-1).some(sample => ['reconnecting','connecting'].includes(sample.phase))");
        // A retryable misclassification redials on the 250/500/1000/2000 ms
        // schedule; hold past the whole schedule so any redial is recorded.
        SystemClock.sleep(6_000);
        JSONArray samples = new JSONArray(evalString("(() => {window.__ps2926Reconnect.observer.disconnect();"
            + "return JSON.stringify(window.__ps2926Reconnect.samples);})()"));
        writeEvidence("locked-key-reconnect-phases.json", samples.toString(2));
        int maxRetryAttempt = 0;
        for (int index = 0; index < samples.length(); index += 1) {
            maxRetryAttempt = Math.max(maxRetryAttempt, samples.getJSONObject(index).optInt("retryAttempt"));
        }
        String allCodes = nativeStoredKeyFailureCodes();
        assertTrue("native code log must only grow", allCodes.startsWith(codesBeforeDrop));
        String nativeCodes = allCodes.substring(codesBeforeDrop.length());
        writeEvidence("locked-key-reconnect-native-codes.txt", nativeCodes.isEmpty() ? "<none>\n" : nativeCodes);
        assertEquals("a locked stored key is AUTH_FAILED: core must dial once and stop, phases=" + samples
            + " native=" + nativeCodes, 1, maxRetryAttempt);
        assertEquals("the one reconnect dial must fail natively as AUTH_FAILED, native=" + nativeCodes,
            "AUTH_FAILED", nativeCodes.trim());
        String last = samples.getJSONObject(samples.length() - 1).optString("phase");
        assertFalse("the reconnect loop must be stopped after the auth failure, phases=" + samples,
            "reconnecting".equals(last) || "connecting".equals(last));
        if (!"true".equals(evalString("String(!!document.querySelector('[data-testid=ssh-connect]'))"))) {
            click("[data-testid=open-connection]");
        }
        awaitJsTrue("!!document.querySelector('[data-testid=legacy-key-passphrase]')");
    }

    /** Native classified codes since the drop, one per line, from the plugin's code-only log. */
    private String nativeStoredKeyFailureCodes() throws Exception {
        StringBuilder codes = new StringBuilder();
        for (String line : shell("logcat -d -v raw -s PocketShellSshKey:I").split("\n")) {
            int index = line.indexOf("stored-key connect failed code=");
            if (index >= 0) codes.append(line.substring(index + "stored-key connect failed code=".length()).trim()).append('\n');
        }
        return codes.toString();
    }

    private String shell(String command) throws Exception {
        android.os.ParcelFileDescriptor output = InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);
        try (java.io.InputStream stream = new android.os.ParcelFileDescriptor.AutoCloseInputStream(output)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void assertHostFormTextFits() throws Exception {
        String failures = evalString("(() => {const failures=[];"
            + "const select=document.querySelector('[data-testid=ssh-key-selection]');"
            + "const style=getComputedStyle(select);const canvas=document.createElement('canvas');"
            + "const context=canvas.getContext('2d');context.font=style.font;"
            + "const selectedWidth=context.measureText(select.selectedOptions[0].textContent.trim()).width;"
            + "const available=select.clientWidth-parseFloat(style.paddingLeft)-parseFloat(style.paddingRight)-24;"
            + "if(selectedWidth>available)failures.push('Selected SSH key text clips: '+selectedWidth+' > '+available);"
            + "return failures.join('; ');})()");
        assertEquals("the real phone host form must display the selected key without clipping", "", failures);
    }

    private static JSONObject connectionTiming(String credential, String sessionTag, long startedAt, long attachedAt) throws JSONException {
        assertTrue("connect-to-session time must be monotonic", attachedAt >= startedAt);
        return new JSONObject()
            .put("credential", credential)
            .put("start", "SSH connect button activated")
            .put("end", "session attached with live terminal")
            .put("sessionTag", sessionTag)
            .put("elapsedMillis", attachedAt - startedAt);
    }

    private void seedLegacyKeyHostReference(String handleId) throws Exception {
        evalRaw("window.__ps2926HostReferenceSeed='pending';"
            + "(async()=>{try{const request=indexedDB.open('pocketshell-installed-data-v1');"
            + "const db=await new Promise((resolve,reject)=>{request.onsuccess=()=>resolve(request.result);request.onerror=()=>reject(request.error);});"
            + "const tx=db.transaction('records','readwrite');const store=tx.objectStore('records');"
            + "const record=await new Promise((resolve,reject)=>{const get=store.get('legacy-import-v1');get.onsuccess=()=>resolve(get.result);get.onerror=()=>reject(get.error);});"
            + "if(!record)throw new Error('completed migration record is missing');"
            + "record.credentialHandles={...(record.credentialHandles||{}),'7':" + JSONObject.quote(handleId) + "};"
            + "record.credentialHandleTombstones=[];"
            + "record.snapshot.database.tables.hosts=[{id:41,name:'Saved Docker host',hostname:'fixture.invalid',port:22,username:'testuser',keyId:7}];"
            + "store.put(record);await new Promise((resolve,reject)=>{tx.oncomplete=resolve;tx.onerror=()=>reject(tx.error);tx.onabort=()=>reject(tx.error);});"
            + "db.close();window.__ps2926HostReferenceSeed='done';"
            + "}catch(error){window.__ps2926HostReferenceSeed='failed:'+String(error?.message||error);}})();'started'");
        awaitJsTrue("window.__ps2926HostReferenceSeed !== 'pending'");
        assertEquals("a host reference must be available for the UI confirmation", "done",
            evalString("window.__ps2926HostReferenceSeed"));
    }

    private void assertVaultContainsHandle(String handleId, boolean expected, String message) throws Exception {
        evalRaw("window.__ps2926VaultCheck='pending';"
            + "(async()=>{try{const listed=await window.Capacitor.Plugins.SshKeyVault.listKeys();"
            + "window.__ps2926VaultCheck=String(listed.keys.some(key=>key.handleId===" + JSONObject.quote(handleId) + "));"
            + "}catch(error){window.__ps2926VaultCheck='failed:'+String(error?.message||error);}})();'started'");
        awaitJsTrue("window.__ps2926VaultCheck !== 'pending'");
        assertEquals(message, Boolean.toString(expected), evalString("window.__ps2926VaultCheck"));
    }

    private void writeEvidence(String name, String text) throws Exception {
        File output = new File(screenshotDirectory, name);
        try (FileOutputStream stream = new FileOutputStream(output)) {
            stream.write(text.getBytes(StandardCharsets.UTF_8));
            stream.getFD().sync();
        }
        assertTrue("same-run evidence file must be durable", output.isFile() && output.length() > 0);
    }

    private static void awaitFile(File file, long timeoutMillis) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeoutMillis;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (file.isFile() && file.length() >= 0) return;
            Thread.sleep(100);
        }
        throw new AssertionError("timed out waiting for the runner to authorize the generated key");
    }

    private void assertNoWebViewSecrets() throws Exception {
        evalString("window.__ps2926StorageAudit={state:'pending',content:''};"
            + "(async()=>{try{const names=await indexedDB.databases();const content=[];"
            + "for(const item of names){if(!item.name)continue;const db=await new Promise((resolve,reject)=>{"
            + "const request=indexedDB.open(item.name);request.onsuccess=()=>resolve(request.result);request.onerror=()=>reject(request.error);});"
            + "for(const storeName of Array.from(db.objectStoreNames)){const rows=await new Promise((resolve,reject)=>{"
            + "const request=db.transaction(storeName,'readonly').objectStore(storeName).getAll();"
            + "request.onsuccess=()=>resolve(request.result);request.onerror=()=>reject(request.error);});content.push(JSON.stringify(rows));}db.close();}"
            + "window.__ps2926StorageAudit={state:'done',content:content.join('|')};"
            + "}catch(error){window.__ps2926StorageAudit={state:'failed',content:String(error)};}})();'started'");
        awaitJsTrue("window.__ps2926StorageAudit?.state === 'done'");
        String evidence = evalString("JSON.stringify({dom:document.documentElement.outerHTML,local:JSON.stringify(localStorage),"
            + "session:JSON.stringify(sessionStorage),indexedDb:window.__ps2926StorageAudit.content})");
        assertFalse("private key PEM must never enter WebView DOM or browser storage", evidence.contains("PRIVATE KEY"));
        assertFalse("the transient passphrase must never enter WebView DOM or browser storage", evidence.contains(TEST_PASSPHRASE));
    }

    private static void assertVaultCiphertextOnly(Context context, String handleId) throws Exception {
        File ciphertext = new File(new File(context.getFilesDir(), "credential-vault"), handleId + ".vault");
        assertTrue("the native key vault must persist one opaque handle file", ciphertext.isFile());
        byte[] bytes = java.nio.file.Files.readAllBytes(ciphertext.toPath());
        try {
            assertTrue("the stored native key must be encrypted and non-empty", bytes.length > 28);
            String raw = new String(bytes, StandardCharsets.ISO_8859_1);
            assertFalse("the vault file must not contain private-key PEM", raw.contains("PRIVATE KEY"));
            assertFalse("the vault file must not contain the unlock passphrase", raw.contains(TEST_PASSPHRASE));
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    private void captureScreenshot(String name) throws Exception {
        Bitmap bitmap = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            waitForWebViewVisualState();
            bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            assertNotNull("Android must provide an in-session screenshot", bitmap);
            if (previousScreenshot == null || !bitmap.sameAs(previousScreenshot)) break;
            bitmap.recycle();
            bitmap = null;
        }
        assertNotNull("the new screen must not reuse the previous captured frame: " + name, bitmap);
        File output = new File(screenshotDirectory, name);
        try (FileOutputStream stream = new FileOutputStream(output)) {
            assertTrue("Android screenshot must be a PNG", bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        }
        if (previousScreenshot != null) previousScreenshot.recycle();
        previousScreenshot = bitmap;
        assertTrue("screenshot must be present for later visual review", output.isFile() && output.length() > 0);
    }

    private void waitForWebViewVisualState() throws Exception {
        // Vue updates and browser layout can still be queued when an
        // evaluateJavascript callback returns. Cross the browser's next
        // rendering opportunity before asking Android to commit that frame.
        evalRaw("window.__ps2926VisualReady=false;"
            + "requestAnimationFrame(()=>requestAnimationFrame(()=>{window.__ps2926VisualReady=true;}));'queued'");
        awaitJsTrue("window.__ps2926VisualReady === true");
        CountDownLatch latch = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        scenario.onActivity(activity -> {
            WebView webView = findWebView(activity.getWindow().getDecorView());
            assertNotNull("packaged Capacitor activity must contain a WebView", webView);
            webView.postVisualStateCallback(SystemClock.uptimeMillis(), new WebView.VisualStateCallback() {
                @Override public void onComplete(long requestId) {
                    // Wait until the draw containing this DOM has been
                    // committed, then let accessibility reach its idle
                    // boundary before capturing the full device surface.
                    assertTrue("screen capture requires hardware rendering", webView.isHardwareAccelerated());
                    webView.getViewTreeObserver().registerFrameCommitCallback(latch::countDown);
                    webView.invalidate();
                }
            });
        });
        assertTrue("timed out waiting for the current WebView frame before screenshot", latch.await(10, TimeUnit.SECONDS));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        InstrumentationRegistry.getInstrumentation().getUiAutomation().waitForIdle(500, 10_000);
    }

    private static String requiredArgument(String key) {
        String value = InstrumentationRegistry.getArguments().getString(key);
        assertNotNull("pass instrumentation argument " + key, value);
        return value;
    }

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
        long deadline = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS;
        String last = "<not evaluated>";
        while (SystemClock.uptimeMillis() < deadline) {
            last = evalRaw("Boolean(" + expression + ")");
            if ("true".equals(last)) return;
            Thread.sleep(100);
        }
        throw new AssertionError("key-vault journey timed out: " + expression + " (last=" + last
            + "; page=" + evalString("document.body.innerText") + ")");
    }

    private String evalString(String expression) throws Exception {
        Object decoded = new JSONTokener(evalRaw(expression)).nextValue();
        return decoded == null ? null : decoded.toString();
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
        assertTrue("timed out evaluating packaged key-management UI", latch.await(JS_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (result.get() == null || "null".equals(result.get())) throw new JSONException("JavaScript returned null: " + expression);
        return result.get();
    }

    private static WebView findWebView(android.view.View view) {
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
}
