package com.pocketshell.app.migration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
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
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Opt-in real-WebView import check for the signed-upgrade fixture. */
@RunWith(AndroidJUnit4.class)
public final class InstalledDataMigrationJourneyTest {
    private static final long JS_TIMEOUT_SECONDS = 15;
    private static final long MIGRATION_TIMEOUT_MILLIS = 30_000;
    private static final String FIXTURE_OPT_IN = "installedDataMigrationFixture";

    private Context targetContext;
    private ActivityScenario<MainActivity> scenario;
    private Map<String, String> sourceHashes;

    @Before
    public void requireAndSnapshotFixture() throws Exception {
        boolean requested = "true".equals(
            InstrumentationRegistry.getArguments().getString(FIXTURE_OPT_IN));
        org.junit.Assume.assumeTrue(
            "run only with the preserved synthetic 0.5.6 upgrade fixture and -e " + FIXTURE_OPT_IN + " true",
            requested);
        targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        sourceHashes = snapshotSourceHashes();
    }

    @After
    public void closeShell() {
        if (scenario != null) scenario.close();
    }

    @Test
    public void startupStagesLegacyDataAndLeavesOriginalFilesUntouched() throws Exception {
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
        JSONObject settings = evalJson("localStorage.getItem('pocketshell.js.settings.v1') || '{}'");
        assertTrue("legacy terminal size must be mapped into JS settings: " + settings + " / import=" + imported,
            settings.optInt("terminalFontSize", -1) >= 8 && settings.optInt("terminalFontSize", -1) <= 32);
        assertEquals(90_000, settings.getInt("backgroundGraceMs"));

        JSONObject pin = evalJson("localStorage.getItem('pocketshell.ssh.host-key.41') || '{}'");
        assertEquals("SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            pin.getString("fingerprintSha256"));

        assertEquals("the migration must not alter any original installed source file",
            sourceHashes, snapshotSourceHashes());
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
        return hashes;
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
