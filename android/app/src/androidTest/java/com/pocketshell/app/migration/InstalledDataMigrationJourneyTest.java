package com.pocketshell.app.migration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.pocketshell.app.MainActivity;

import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Real-WebView import check for the signed 0.5.6-to-candidate upgrade fixture.
 * Dispatched by scripts/connected-js-key-vault-signed-upgrade.sh from the blocking packaged
 * lane (scripts/ci-js-first-packaged-lanes.sh). Missing fixture arguments fail instead of
 * skipping, so a mis-wired run can never report a vacuous green.
 */
@RunWith(AndroidJUnit4.class)
public final class InstalledDataMigrationJourneyTest {
    private static final long JS_TIMEOUT_SECONDS = 15;
    private static final long MIGRATION_TIMEOUT_MILLIS = 30_000;
    private static final String FIXTURE_OPT_IN = "installedDataMigrationFixture";
    private static final String CONNECT_OPT_IN = "installedDataMigrationConnect";
    private static final String MALFORMED_ENCRYPTED_OPT_IN = "installedDataMigrationMalformedEncryptedFixture";
    private static final String MALFORMED_ENCRYPTED_PREFS = "pocketshell-voice-secrets";
    private static final String KEY_KEYSET = "__androidx_security_crypto_encrypted_prefs_key_keyset__";
    private static final String VALUE_KEYSET = "__androidx_security_crypto_encrypted_prefs_value_keyset__";
    private static final String EXPECTED_HOST_KEY_ARGUMENT = "installedDataMigrationExpectedHostKey";
    private static final String DEFAULT_EXPECTED_HOST_KEY =
        "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    private Context targetContext;
    private ActivityScenario<MainActivity> scenario;
    private Map<String, String> sourceHashes;
    private File createdMalformedPreferences;
    private File evidenceDirectory;

    @Before
    public void requireAndSnapshotFixture() throws Exception {
        boolean requested = "true".equals(
            InstrumentationRegistry.getArguments().getString(FIXTURE_OPT_IN));
        assertTrue(
            "run only through the signed 0.5.6 upgrade runner, which passes -e " + FIXTURE_OPT_IN + " true",
            requested);
        targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String evidenceRunId = InstrumentationRegistry.getArguments().getString("installedDataMigrationRunId");
        if (evidenceRunId != null) {
            assertTrue("migration evidence run ID must be path-safe",
                evidenceRunId.matches("[A-Za-z0-9][A-Za-z0-9_-]{2,38}"));
            File externalFiles = targetContext.getExternalFilesDir(null);
            assertNotNull("target app external files directory must be available", externalFiles);
            evidenceDirectory = new File(externalFiles, "pocketshell-installed-data-migration/" + evidenceRunId);
            assertTrue("run-scoped migration evidence directory must be new", evidenceDirectory.mkdirs());
        }
        if (malformedEncryptedFixtureRequested()) {
            File source = new File(new File(targetContext.getApplicationInfo().dataDir, "shared_prefs"),
                MALFORMED_ENCRYPTED_PREFS + ".xml");
            assertTrue("the malformed test must not replace existing encrypted preferences", !source.exists());
            SharedPreferences preferences = targetContext.getSharedPreferences(
                MALFORMED_ENCRYPTED_PREFS, Context.MODE_PRIVATE);
            assertTrue("could not create the synthetic malformed encrypted source",
                preferences.edit().putString(KEY_KEYSET, "00").putString(VALUE_KEYSET, "00").commit());
            createdMalformedPreferences = source;
        }
        sourceHashes = snapshotSourceHashes();
    }

    @After
    public void closeShell() {
        if (scenario != null) scenario.close();
        if (createdMalformedPreferences != null) {
            targetContext.getSharedPreferences(MALFORMED_ENCRYPTED_PREFS, Context.MODE_PRIVATE)
                .edit().clear().commit();
            assertTrue("could not remove synthetic malformed preferences", createdMalformedPreferences.delete());
        }
    }

    @Test
    public void startupStagesLegacyDataAndLeavesOriginalFilesUntouched() throws Exception {
        assertTrue("the migrated-host cycle must not seed malformed encrypted preferences",
            !malformedEncryptedFixtureRequested());
        scenario = ActivityScenario.launch(MainActivity.class);
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.migrationStatus === 'complete'");
        awaitJsTrue("document.querySelector('[data-testid=installed-data-migration-error]') === null");

        evalRaw("(() => {"
            + "window.__installedDataMigrationProbe = 'pending';"
            + "const open = indexedDB.open('pocketshell-installed-data-v1');"
            + "open.onerror = () => window.__installedDataMigrationProbe = 'open-error';"
            + "open.onsuccess = () => {"
            + "const db = open.result;"
            + "if (!db.objectStoreNames.contains('records')) { window.__installedDataMigrationProbe = 'missing-store'; return; }"
            + "const read = db.transaction('records', 'readonly').objectStore('records').get('legacy-import-v1');"
            + "read.onerror = () => window.__installedDataMigrationProbe = 'read-error';"
            + "read.onsuccess = () => {"
            + "const snapshot = read.result?.snapshot;"
            + "window.__installedDataMigrationProbe = JSON.stringify({"
            + "status: read.result?.status,"
            + "hostId: snapshot?.database?.tables?.hosts?.find(row => row.id === 41)?.id,"
            + "host: (() => {const row = snapshot?.database?.tables?.hosts?.find(item => item.id === 41); return row ? {id: row.id, name: row.name, hostname: row.hostname, port: row.port, username: row.username} : null;})(),"
            + "snippet: snapshot?.database?.tables?.snippets?.find(row => row.id === 1)?.body,"
            + "draft: snapshot?.preferences?.composer_drafts?.entries?.['host-41']?.value,"
            + "syncUnknown: snapshot?.preferences?.next_sync_selection?.entries?.sync_future_field?.value,"
            + "legacyTerminalSize: snapshot?.preferences?.next_settings?.entries?.terminal_text_size_px?.value,"
            + "legacyGrace: snapshot?.preferences?.next_settings?.entries?.background_grace_millis?.value,"
            + "platform: window.Capacitor?.getPlatform?.()"
            + "});"
            + "};"
            + "};"
            + "return 'started';"
            + "})()");
        awaitJsTrue("typeof window.__installedDataMigrationProbe === 'string' && window.__installedDataMigrationProbe !== 'pending'");
        JSONObject imported = new JSONObject(evalString("window.__installedDataMigrationProbe"));
        assertEquals("complete", imported.getString("status"));
        assertEquals(41, imported.getInt("hostId"));
        assertEquals("echo preserved-snippet", imported.getString("snippet"));
        assertEquals("Draft kept after update", imported.getString("draft"));
        assertEquals("unknown value retained", imported.getString("syncUnknown"));
        assertEquals(32, imported.getInt("legacyTerminalSize"));
        assertEquals("90000", imported.getString("legacyGrace"));

        JSONObject legacyHost = imported.getJSONObject("host");
        assertEquals(41, legacyHost.getInt("id"));
        awaitJsTrue("document.querySelector('[data-testid=legacy-host-select] option[value=\"41\"]')?.textContent.trim().length > 0");
        evalRaw("(() => {const select = document.querySelector('[data-testid=legacy-host-select]');"
            + "select.value = '41'; select.dispatchEvent(new Event('change', {bubbles: true})); return 'selected';})()");
        awaitJsTrue("document.querySelector('[data-testid=ssh-host]')?.value === "
            + JSONObject.quote(legacyHost.getString("hostname"))
            + " && document.querySelector('[data-testid=ssh-username]')?.value === "
            + JSONObject.quote(legacyHost.getString("username")));
        assertEquals(String.valueOf(legacyHost.getInt("port")), evalString("document.querySelector('[data-testid=ssh-port]')?.value ?? ''"));
        assertEquals("private key bytes must not be exposed by the host selection UI", "hidden",
            evalString("document.querySelector('[data-testid=ssh-private-key]') ? 'visible' : 'hidden'"));

        JSONObject settings = evalJson("localStorage.getItem('pocketshell.js.settings.v1') || '{}'");
        assertTrue("legacy terminal size must be mapped into JS settings: " + settings + " / import=" + imported,
            settings.optInt("terminalFontSize", -1) >= 8 && settings.optInt("terminalFontSize", -1) <= 32);
        assertEquals(90_000, settings.getInt("backgroundGraceMs"));

        JSONObject pin = evalJson("localStorage.getItem('pocketshell.ssh.host-key.41') || '{}'");
        String expectedHostKey = InstrumentationRegistry.getArguments()
            .getString(EXPECTED_HOST_KEY_ARGUMENT, DEFAULT_EXPECTED_HOST_KEY);
        assertEquals(expectedHostKey, pin.getString("fingerprintSha256"));

        if ("true".equals(InstrumentationRegistry.getArguments().getString(CONNECT_OPT_IN))) {
            String handle = evalString("document.querySelector('[data-testid=ssh-key-selection]')?.value ?? ''");
            assertTrue("the migrated host must select an opaque native vault handle", handle.matches("[0-9a-fA-F-]{36}"));
            evalRaw("document.querySelector('[data-testid=ssh-connect]')?.click(); 'connect-clicked'");
            awaitJsTrue("['connected','listing'].includes(document.querySelector('.app-shell')?.dataset.sshPhase)"
                + " && document.querySelector('.app-shell')?.dataset.homeSurface === 'sessions'");
            awaitJsTrue("document.querySelector('[data-testid=session-list], [data-testid=empty-sessions]') !== null");
            evalRaw("document.querySelector('[data-testid=ssh-disconnect]')?.click(); 'disconnect-clicked'");
            awaitJsTrue("!['connected','listing','live'].includes(document.querySelector('.app-shell')?.dataset.sshPhase)");
            awaitJsTrue("document.querySelector('[data-testid=ssh-resources]')?.dataset.snapshotState === 'verified'");
            assertEquals("the native resolver connection must close cleanly", "0",
                evalString("document.querySelector('[data-testid=ssh-resource-connections]')?.textContent.trim()"));
        }

        Map<String, String> afterHashes = snapshotSourceHashes();
        assertEquals("the migration must not alter any original installed source file", sourceHashes, afterHashes);
        preserveSourceHashes("migration-source-hashes.json", afterHashes);
    }

    @Test
    public void malformedEncryptedPreferencesAppearInPackagedWebView() throws Exception {
        assertTrue("the runner must request the malformed encrypted-preferences fixture",
            malformedEncryptedFixtureRequested());
        scenario = ActivityScenario.launch(MainActivity.class);
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.migrationStatus === 'partial'");
        awaitJsTrue("document.querySelector('[data-testid=installed-data-migration-error]')?.textContent.includes('pocketshell-voice-secrets') === true");
        String warning = evalString("document.querySelector('[data-testid=installed-data-migration-error]')?.textContent ?? ''");
        assertTrue("the partial import must identify the unreadable encrypted source: " + warning,
            warning.contains("Encrypted preferences") && warning.contains("pocketshell-voice-secrets"));
        Map<String, String> afterHashes = snapshotSourceHashes();
        assertEquals("the packaged partial import must not change any source file", sourceHashes, afterHashes);
        preserveSourceHashes("malformed-encrypted-source-hashes.json", afterHashes);
    }

    @Test
    public void malformedPrivateKeyAppearsInPackagedWebViewAndLeavesSourceUntouched() throws Exception {
        assertEquals("the runner must seed the malformed private-key fixture", "true",
            InstrumentationRegistry.getArguments().getString("installedDataMigrationMalformedKeyFixture"));
        scenario = ActivityScenario.launch(MainActivity.class);
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.migrationStatus === 'partial'");
        awaitJsTrue("document.querySelector('[data-testid=installed-data-migration-error]')?.textContent.includes('Migrated Docker key') === true");
        String warning = evalString("document.querySelector('[data-testid=installed-data-migration-error]')?.textContent ?? ''");
        assertTrue("an unreadable key must be named and its preserved source explained: " + warning,
            warning.contains("original") && warning.contains("remains"));
        evalRaw("(() => {const select=document.querySelector('[data-testid=legacy-host-select]');"
            + "select.value='41';select.dispatchEvent(new Event('change',{bubbles:true}));return 'selected';})()");
        awaitJsTrue("document.querySelector('[data-testid=ssh-key-selection]')?.value === ''");
        awaitJsTrue("document.querySelector('[data-testid=ssh-message]')?.textContent.includes('not available') === true");
        evalRaw("document.querySelector('[data-testid=ssh-connect]')?.click(); 'connect-clicked'");
        awaitJsTrue("document.querySelector('[data-testid=ssh-message]')?.textContent.includes('select an SSH key') === true");
        assertEquals("a malformed migrated key must not leave a connectable dangling handle", "idle",
            evalString("document.querySelector('.app-shell')?.dataset.sshPhase"));
        Map<String, String> afterHashes = snapshotSourceHashes();
        assertEquals("the malformed key and every legacy source must remain untouched", sourceHashes, afterHashes);
        preserveSourceHashes("malformed-key-source-hashes.json", afterHashes);
    }

    private boolean malformedEncryptedFixtureRequested() {
        return "true".equals(InstrumentationRegistry.getArguments().getString(MALFORMED_ENCRYPTED_OPT_IN));
    }

    private Map<String, String> snapshotSourceHashes() throws Exception {
        File root = new File(targetContext.getApplicationInfo().dataDir);
        Map<String, String> hashes = new LinkedHashMap<>();
        String[] relativePaths = {
            "databases/pocketshell.db",
            "shared_prefs/next_settings.xml",
            "shared_prefs/composer_drafts.xml",
            "shared_prefs/next_sync_selection.xml",
            "shared_prefs/workspace_order.xml",
            "files/ssh-keys/fixture.pem",
        };
        for (String relativePath : relativePaths) {
            File file = new File(root, relativePath);
            assertTrue("fixture source file is missing: " + relativePath, file.isFile());
            hashes.put(relativePath, sha256(file));
        }
        if (malformedEncryptedFixtureRequested()) {
            String relativePath = "shared_prefs/" + MALFORMED_ENCRYPTED_PREFS + ".xml";
            File file = new File(root, relativePath);
            assertTrue("the malformed encrypted fixture source is missing", file.isFile());
            hashes.put(relativePath, sha256(file));
        }
        return hashes;
    }

    private void preserveSourceHashes(String filename, Map<String, String> afterHashes) throws Exception {
        if (evidenceDirectory == null) return;
        JSONObject before = new JSONObject();
        JSONObject after = new JSONObject();
        for (Map.Entry<String, String> entry : sourceHashes.entrySet()) before.put(entry.getKey(), entry.getValue());
        for (Map.Entry<String, String> entry : afterHashes.entrySet()) after.put(entry.getKey(), entry.getValue());
        JSONObject report = new JSONObject()
            .put("schema", 1)
            .put("runId", InstrumentationRegistry.getArguments().getString("installedDataMigrationRunId"))
            .put("unchanged", sourceHashes.equals(afterHashes))
            .put("sourceHashesBefore", before)
            .put("sourceHashesAfter", after);
        File output = new File(evidenceDirectory, filename);
        try (FileOutputStream stream = new FileOutputStream(output)) {
            stream.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
            stream.getFD().sync();
        }
        assertTrue("same-run source hash evidence must be durable", output.isFile() && output.length() > 0);

        File captured = new File(evidenceDirectory, filename + ".captured");
        long deadline = SystemClock.uptimeMillis() + MIGRATION_TIMEOUT_MILLIS;
        while (SystemClock.uptimeMillis() < deadline && !captured.isFile()) Thread.sleep(100);
        assertTrue("the runner must preserve same-run source hashes before package cleanup", captured.isFile());
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = Files.readAllBytes(file.toPath());
        byte[] value = digest.digest(bytes);
        StringBuilder result = new StringBuilder();
        for (byte item : value) result.append(String.format("%02x", item));
        return result.toString();
    }

    private void awaitJsTrue(String expression) throws Exception {
        long deadline = SystemClock.uptimeMillis() + MIGRATION_TIMEOUT_MILLIS;
        String latest = "<not evaluated>";
        while (SystemClock.uptimeMillis() < deadline) {
            latest = evalRaw(expression);
            if ("true".equals(latest)) return;
            Thread.sleep(100);
        }
        throw new AssertionError("WebView condition did not become true: " + expression
            + " (last result: " + latest + "; page=" + evalString("document.body.innerText") + ")");
    }

    private String evalString(String expression) throws Exception {
        Object decoded = new JSONTokener(evalRaw(expression)).nextValue();
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
            assertNotNull("the packaged activity must contain its Capacitor WebView", webView);
            webView.evaluateJavascript(expression, value -> {
                result.set(value);
                latch.countDown();
            });
        });
        assertTrue("timed out evaluating packaged WebView JavaScript",
            latch.await(JS_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        return result.get();
    }

    private static WebView findWebView(View root) {
        if (root instanceof WebView) return (WebView) root;
        if (!(root instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) root;
        for (int index = 0; index < group.getChildCount(); index++) {
            WebView nested = findWebView(group.getChildAt(index));
            if (nested != null) return nested;
        }
        return null;
    }
}
