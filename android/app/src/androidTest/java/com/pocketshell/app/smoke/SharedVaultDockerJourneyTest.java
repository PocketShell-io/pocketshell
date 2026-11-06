package com.pocketshell.app.smoke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;
import android.webkit.WebView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.pocketshell.app.GoogleSyncEnvironment;
import com.pocketshell.app.GoogleSyncSession;
import com.pocketshell.app.SharedShellLaunch;
import com.pocketshell.app.SyncAuthException;

import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Key-vault W4 on the packaged APK: the maintainer's scenario end to end. The
 * web saved the host's SSH key in the account vault; the phone signs in,
 * unlocks the vault with the ONE account passphrase, sees the account host,
 * taps it and gets a shell on the real Docker agents fixture with that key —
 * with no "Choose a key" picker at any point, and no private key anywhere in
 * the app's storage.
 *
 * <p>Fakes are only the two edges every account journey fakes (Google's
 * account chooser and the sync API, {@link GoogleSyncEnvironment}). The vault
 * slots the fake API serves were built on the host by core's KeyVaultStore
 * (scripts/vault-journey-fixture), so the WebView runs core's real Argon2id,
 * check value, DEK unwrap and blob decryption, and native SSH authenticates
 * with the ephemeral in-memory handle against a real sshd.
 */
@RunWith(AndroidJUnit4.class)
public final class SharedVaultDockerJourneyTest {
    private static final String TAG = "PocketshellVaultJourney";
    private static final long JS_TIMEOUT_SECONDS = 15;
    private static final long WAIT_TIMEOUT_MILLIS = 20_000;
    /** Argon2id (32 MiB) in a swiftshader emulator WebView. */
    private static final long UNLOCK_TIMEOUT_MILLIS = 120_000;
    /**
     * Core's unlock sheet refreshes (dialog closes, hosts list) before the
     * Keystore seal + prefs commit on the native worker resolves, so the
     * sealed record can lag the listed hosts on a loaded emulator host.
     */
    private static final long REMEMBER_TIMEOUT_MILLIS = 30_000;
    static final String TOKEN_SIGNATURE = "FAKEGOOGLEIDTOKEN3086gwlaneDoNotLeak";
    /** Throwaway journey passphrase; the runner passes the same value to the fixture builder. */
    static final String VAULT_PASSPHRASE = "pocketshell-vault-test-passphrase gateway";
    private static final String HOSTS_KEY = "pocketshell.android.hosts.v1";
    private static final String SHARED_SETTINGS_KEY = "pocketshell.settings.v1";
    private static final String AUTH_PREFS = "pocketshell-sync-auth";
    private static final String DEVICE_KEY_PREFS = "pocketshell_vault_device_v1";
    private static final String STAGED_FIXTURE = "/data/local/tmp/pocketshell-vault-fixture-[A-Za-z0-9_-]+\\.json";
    private static final String KEY_PICKER = "[data-testid=account-host-key-gate]";
    private static final String UNLOCK = "[data-testid=vault-unlock-gate]";

    private ActivityScenario<?> scenario;
    private String runId;
    private ScreenshotGuard.Frame lastScreenshot;
    private GatewayDockerJourneyTest.FakeSyncBackend backend;
    private GatewayDockerJourneyTest harness;
    private String alias;
    private String privateKeyBody;
    private final java.util.Set<WindowInsetsAnimation> imeAnimations = new java.util.HashSet<>();
    private int imeAnimationEnds;

    @Before
    public void installFakeEdges() throws Exception {
        runId = InstrumentationRegistry.getArguments().getString("screenshotRunId", "vaultw4-" + System.currentTimeMillis());
        assertTrue("screenshot run id must be path safe", runId.matches("[A-Za-z0-9][A-Za-z0-9_-]{2,48}"));
        String fixturePath = InstrumentationRegistry.getArguments().getString("vaultFixture");
        assertNotNull("the runner stages the vault fixture and passes vaultFixture", fixturePath);
        assertTrue("vault fixture must be runner-staged: " + fixturePath, fixturePath.matches(STAGED_FIXTURE));
        // Read, never delete: both test methods read it; the runner removes it.
        JSONObject fixture = new JSONObject(shell("cat " + fixturePath));
        alias = fixture.getString("alias");
        privateKeyBody = fixture.getString("privateKeyMarker");
        harness = new GatewayDockerJourneyTest();
        harness.installFakeEdges();
        harness.selectVariant("active");
        backend = harness.backend;
        targetContext().deleteSharedPreferences(DEVICE_KEY_PREFS);
    }

    @After
    public void restore() throws Exception {
        if (scenario != null) {
            try {
                evalRaw("Object.keys(localStorage).filter((key) => key.startsWith('pocketshell.ssh.host-key.')"
                        + " || ['" + HOSTS_KEY + "','" + SHARED_SETTINGS_KEY + "'].includes(key))"
                        + ".forEach((key) => localStorage.removeItem(key)); 'reset'");
            } catch (Throwable ignored) {
                // The activity may already be gone after a failure.
            }
            scenario.close();
        }
        harness.scenario = null;
        harness.restore();
        targetContext().deleteSharedPreferences(AUTH_PREFS);
        targetContext().deleteSharedPreferences(DEVICE_KEY_PREFS);
    }

    /**
     * Type it every time (the default): a wrong passphrase says so and keeps
     * the sheet; the right one lists the account host (one passphrase also
     * unlocked the host list) and tapping it connects with the vault key.
     */
    @Test
    public void typeEveryTimeUnlockDialsTheWebSavedKeyWithNoKeyPicker() throws Exception {
        assertTrue("packaged vault journey runs on API 35+", Build.VERSION.SDK_INT >= 35);
        signInAndRelaunch();

        // Each launch asks once: a signed-in account with a vault this phone does not remember.
        awaitJsTrue("!!document.querySelector('" + UNLOCK + " [data-testid=vault-unlock-passphrase]')", 45_000);
        // Core's shared sheet is the only one (AppRoot mounts it; Android adds none).
        assertEquals("exactly one unlock sheet", "1", evalRaw("document.querySelectorAll('" + UNLOCK + "').length"));
        assertEquals("exactly one passphrase field", "1", evalRaw("document.querySelectorAll('[data-testid=vault-unlock-passphrase]').length"));
        assertEquals("type every time is the default", "true",
                evalRaw("document.querySelector('[data-testid=vault-unlock-type-every-time]').checked"));
        captureScreenshot("vault-unlock-on-launch.png", "[data-testid=vault-unlock-submit]");

        setValue("[data-testid=vault-unlock-passphrase]", "not the vault passphrase");
        tapDomCenter("[data-testid=vault-unlock-submit]");
        awaitJsTrue("(document.querySelector('[data-testid=vault-unlock-error]')?.textContent ?? '').trim()"
                + ".startsWith(\"That passphrase didn't unlock your vault.\")", UNLOCK_TIMEOUT_MILLIS);
        captureScreenshot("vault-unlock-wrong-passphrase.png", "[data-testid=vault-unlock-error]");

        setValue("[data-testid=vault-unlock-passphrase]", VAULT_PASSPHRASE);
        tapDomCenter("[data-testid=vault-unlock-submit]");
        awaitJsTrue("!document.querySelector('" + UNLOCK + "')", UNLOCK_TIMEOUT_MILLIS);

        // One passphrase: the vault carried the sync passphrase, so the account host is listed.
        awaitAccountHostListed();
        captureScreenshot("vault-unlocked-account-host.png", "[data-testid=trusted-server-connect]");

        connectAccountHost();
        captureTerminalScreenshot("vault-connected-with-vault-key.png");

        assertNoKeyPickerEverShown();
        assertEquals("the phone never writes the account", 0, backend.writes);
        assertTrue("the vault key blob was read", backend.reads.stream().anyMatch((path) -> path.startsWith("/settings/vk-")));
        assertFalse("type every time remembers nothing on the device", deviceKeyPrefs().exists() && hasRecord(deviceKeyPrefs()));
        assertNoPrivateKeyAtRest();
        assertNoSecretsInLogcat();
        Log.i(TAG, "VAULT_TYPE_EVERY_TIME " + new JSONObject()
                .put("reads", new org.json.JSONArray(backend.reads)).put("puts", backend.writes).put("connected", true));
    }

    /**
     * Remember on this device: the next launch unlocks silently (DEK record
     * sealed by the Keystore key), the account host dials straight away, and
     * signing out forgets the record.
     */
    @Test
    public void rememberOnThisDeviceUnlocksSilentlyNextLaunchAndSignOutForgets() throws Exception {
        assertTrue("packaged vault journey runs on API 35+", Build.VERSION.SDK_INT >= 35);
        signInAndRelaunch();
        awaitJsTrue("!!document.querySelector('" + UNLOCK + " [data-testid=vault-unlock-passphrase]')", 45_000);
        assertEquals("remember is initially off", "false",
                evalRaw("document.querySelector('[data-testid=vault-unlock-remember]').checked"));
        tapDomCenter("[data-testid=vault-unlock-remember]");
        awaitJsTrue("document.querySelector('[data-testid=vault-unlock-remember]')?.checked === true");
        assertEquals("one physical tap selects remember", "true",
                evalRaw("document.querySelector('[data-testid=vault-unlock-remember]').checked"));
        setValue("[data-testid=vault-unlock-passphrase]", VAULT_PASSPHRASE);
        captureScreenshot("vault-unlock-remember-chosen.png", "[data-testid=vault-unlock-remember]");
        tapDomCenter("[data-testid=vault-unlock-submit]");
        awaitJsTrue("!document.querySelector('" + UNLOCK + "')", UNLOCK_TIMEOUT_MILLIS);
        awaitAccountHostListed();

        File sealed = deviceKeyPrefs();
        awaitRememberedRecord(sealed);
        String stored = new String(Files.readAllBytes(sealed.toPath()), StandardCharsets.UTF_8);
        assertFalse("the remembered record is sealed, never plain JSON", stored.contains("vaultId") || stored.contains("\"dek\""));
        assertFalse("the passphrase is never remembered", stored.contains(VAULT_PASSPHRASE));

        relaunch();
        awaitAccountHostListed();
        assertEquals("a remembered vault asks for nothing on launch", "false", evalRaw("!!document.querySelector('" + UNLOCK + "')"));
        connectAccountHost();
        assertNoKeyPickerEverShown();

        // Sign out from the shared Account screen: the record and the held keys go.
        relaunch();
        awaitAccountHostListed();
        tapDomCenter(".picker .header-actions .account-action");
        awaitJsTrue("!!document.querySelector('[data-testid=android-account] .account-identity .btn-ghost:not([disabled])')");
        tapDomCenter("[data-testid=android-account] .account-identity .btn-ghost");
        awaitJsTrue("!!document.querySelector('[data-testid=android-account] .account-login')");
        // The post-sign-out Account screen is the sixth screenshot: a second
        // host-list capture here would be byte-identical to
        // vault-unlocked-account-host.png by design (same hosts, same route),
        // which the result checker rightly rejects as stale (#3067).
        captureScreenshot("vault-signed-out-forgotten.png", "[data-testid=android-account] .account-login");
        assertFalse("sign-out forgets the remembered vault", sealed.exists() && hasRecord(sealed));
        assertNoPrivateKeyAtRest();
        assertNoSecretsInLogcat();
    }

    // ---- journey steps ------------------------------------------------------

    private void signInAndRelaunch() throws Exception {
        harness.scenario = ActivityScenario.launch(SharedShellLaunch.intent());
        scenario = harness.scenario;
        scenario.onActivity(activity -> findWebView(activity.getWindow().getDecorView())
                .setWindowInsetsAnimationCallback(new WindowInsetsAnimation.Callback(
                        WindowInsetsAnimation.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                    @Override public void onPrepare(WindowInsetsAnimation animation) {
                        if ((animation.getTypeMask() & WindowInsets.Type.ime()) != 0) imeAnimations.add(animation);
                    }
                    @Override public WindowInsets onProgress(WindowInsets insets, List<WindowInsetsAnimation> running) { return insets; }
                    @Override public void onEnd(WindowInsetsAnimation animation) {
                        if ((animation.getTypeMask() & WindowInsets.Type.ime()) != 0) {
                            imeAnimations.remove(animation);
                            imeAnimationEnds++;
                        }
                    }
                }));
        awaitJsTrue("!!document.querySelector('[data-testid=server-google-login]')", 60_000);
        evalRaw("localStorage.removeItem('" + SHARED_SETTINGS_KEY + "'); localStorage.setItem('" + HOSTS_KEY + "','[]');"
                + " localStorage.removeItem('ps.lastFolder.gw-dev'); localStorage.removeItem('ps.lastFolder.gw-missing'); 'empty'");
        tapDomCenter("[data-testid=server-google-login]");
        awaitJsTrue("!!document.querySelector('[data-testid=vault-unlock-gate]')", 60_000);
        watchKeyPicker();
        // Autofocus opens the Android IME after the unlock DOM mounts. Observe
        // the native transition end before taking coordinates for either case.
        awaitImeSettled(true);
    }

    private void awaitImeSettled(boolean visible) throws Exception {
        long deadline = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS;
        AtomicReference<Boolean> settled = new AtomicReference<>(false);
        while (SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity(activity -> {
                WebView webView = findWebView(activity.getWindow().getDecorView());
                WindowInsets insets = webView.getRootWindowInsets();
                settled.set(insets != null && insets.isVisible(WindowInsets.Type.ime()) == visible
                        && imeAnimations.isEmpty() && imeAnimationEnds > 0);
            });
            if (settled.get()) return;
            Thread.sleep(100); // Poll observed platform state; never infer settling from elapsed time.
        }
        throw new AssertionError("native IME did not finish its visibility transition: " + visible);
    }

    private void watchKeyPicker() throws Exception {
        evalRaw("window.__psSawKeyPicker = false; window.__psMaxSheets = 1;"
                + "new MutationObserver(() => { if (document.querySelector('" + KEY_PICKER + "')) window.__psSawKeyPicker = true;"
                + " window.__psMaxSheets = Math.max(window.__psMaxSheets, document.querySelectorAll('" + UNLOCK + "').length); })"
                + ".observe(document.body, {childList: true, subtree: true}); 'watching'");
    }

    /** A fresh document, as a new launch: the vault state lives in memory only. */
    private void relaunch() throws Exception {
        String stale = "relaunch-" + SystemClock.uptimeMillis();
        evalRaw("window.__pocketshellStaleDocument = " + JSONObject.quote(stale) + "; location.replace('/'); 'reload'");
        awaitJsTrue("window.__pocketshellStaleDocument !== " + JSONObject.quote(stale)
                + " && document.readyState === 'complete' && !!document.querySelector('[data-testid=trusted-servers]')", 45_000);
        // Record any key picker that ever mounts in this document.
        evalRaw("window.__psSawKeyPicker = !!document.querySelector('" + KEY_PICKER + "');"
                + "window.__psMaxSheets = document.querySelectorAll('" + UNLOCK + "').length;"
                + "new MutationObserver(() => { if (document.querySelector('" + KEY_PICKER + "')) window.__psSawKeyPicker = true;"
                + " window.__psMaxSheets = Math.max(window.__psMaxSheets, document.querySelectorAll('" + UNLOCK + "').length); })"
                + ".observe(document.body, {childList: true, subtree: true}); 'watching'");
    }

    private void awaitAccountHostListed() throws Exception {
        awaitJsTrue("[...document.querySelectorAll('[data-testid=trusted-server-connect]:not([disabled])')].some((n) => n.textContent.trim() === "
                + JSONObject.quote(alias) + ")", UNLOCK_TIMEOUT_MILLIS);
    }

    private void connectAccountHost() throws Exception {
        String phase = deviceKeyPrefs().exists() && hasRecord(deviceKeyPrefs()) ? "remember" : "type";
        String fingerprint = harness.authorizeVault("vault-" + phase + "-authorize");
        harness.installDialRecorder();
        int mark = harness.controller(new JSONObject().put("op", "mark")).getInt("mark");
        tapDomCenter("[data-testid=trusted-server-connect]");
        awaitJsTrue("[...document.querySelectorAll('.xterm-rows')].some(n => n.offsetParent !== null && n.innerText.includes('$'))", UNLOCK_TIMEOUT_MILLIS);
        assertFalse("the authenticated descriptor needs no manual trust", "true".equals(evalRaw("!!document.querySelector('[data-testid=host-key-trust-gate]')")));
        assertTrue("the host independently accepted only the decrypted vault credential",
                GatewayDockerJourneyTest.acceptedFingerprints(harness.sshdSince(mark, "vault-" + phase + "-connect")).contains(fingerprint));
        String file = "gwlane-vault-" + phase + "-" + harness.run + ".txt";
        harness.typeCommand("echo VAULT42_" + phase + "_" + harness.run + " > ~/" + file);
        String content = "";
        long deadline = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS;
        while (SystemClock.uptimeMillis() < deadline) {
            content = harness.hostFile(file, "vault-" + phase + "-io").trim();
            if (!content.isEmpty()) break;
            Thread.sleep(200);
        }
        assertEquals("the real remote shell executed the run-unique command", "VAULT42_" + phase + "_" + harness.run, content);
        harness.assertEveryDialWentThroughTheGateway(harness.nativeDials());
    }

    // ---- oracles ----------------------------------------------------------------

    private void assertNoKeyPickerEverShown() throws Exception {
        assertEquals("the account host never opened the Choose-a-key picker", "false", evalRaw("window.__psSawKeyPicker === true"));
        assertEquals("never more than one unlock sheet at once", "true", evalRaw("window.__psMaxSheets <= 1"));
    }

    /** No private key in the app's data directory: files, preferences, databases, WebView storage. */
    private void assertNoPrivateKeyAtRest() throws Exception {
        File dataDir = targetContext().getDataDir();
        List<String> hits = new ArrayList<>();
        scan(dataDir, hits);
        assertTrue("no vault private key at rest in the app's storage: " + hits, hits.isEmpty());
        String web = evalString("JSON.stringify({local: Object.entries(localStorage), session: Object.entries(sessionStorage)})");
        assertFalse("no private key in WebView storage", web.contains("PRIVATE KEY") || web.contains(privateKeyBody));
    }

    private void scan(File file, List<String> hits) throws Exception {
        if (file.isDirectory()) {
            String name = file.getName();
            if (name.equals("code_cache") || name.equals("lib")) return;
            File[] children = file.listFiles();
            if (children != null) for (File child : children) scan(child, hits);
            return;
        }
        if (!file.isFile() || file.length() > 8 * 1024 * 1024) return;
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.ISO_8859_1);
        if (text.contains(privateKeyBody) || text.contains(VAULT_PASSPHRASE)) hits.add(file.getAbsolutePath());
    }

    private void assertNoSecretsInLogcat() throws Exception {
        String pid = shell("pidof " + targetContext().getPackageName()).trim();
        assertTrue("the app process must be running: " + pid, pid.matches("\\d+"));
        String logcat = shell("logcat -d -v brief --pid=" + pid);
        assertFalse("logcat must not contain the ID token", logcat.contains(TOKEN_SIGNATURE));
        assertFalse("logcat must not contain the vault passphrase", logcat.contains(VAULT_PASSPHRASE));
        assertFalse("logcat must not contain key material", logcat.contains(privateKeyBody) || logcat.contains("PRIVATE KEY-----"));
    }

    private File deviceKeyPrefs() {
        return new File(targetContext().getDataDir(), "shared_prefs/" + DEVICE_KEY_PREFS + ".xml");
    }

    private static boolean hasRecord(File prefs) throws Exception {
        return new String(Files.readAllBytes(prefs.toPath()), StandardCharsets.UTF_8).contains("remembered:");
    }

    /**
     * The unlock sheet lists the hosts as soon as core's store unlocks, while
     * the Keystore seal + prefs commit still runs on the native worker: poll
     * for the sealed bytes instead of asserting once. A record that never
     * lands still fails, with the same message.
     */
    private void awaitRememberedRecord(File sealed) throws Exception {
        long deadline = SystemClock.uptimeMillis() + REMEMBER_TIMEOUT_MILLIS;
        while (SystemClock.uptimeMillis() < deadline) {
            if (sealed.exists() && hasRecord(sealed)) return;
            Thread.sleep(200);
        }
        assertTrue("the remembered record is stored", sealed.exists() && hasRecord(sealed));
    }

    // ---- WebView plumbing (same idiom as SharedAccountPickerJourneyTest) -----

    private void setValue(String selector, String value) throws Exception {
        evalString("(() => {const node=document.querySelector(" + JSONObject.quote(selector) + ");"
                + "if(!node)throw new Error('missing ' + " + JSONObject.quote(selector) + ");"
                + "node.value=" + JSONObject.quote(value) + ";node.dispatchEvent(new Event('input',{bubbles:true}));"
                + "node.dispatchEvent(new Event('change',{bubbles:true}));return 'set';})()");
    }

    private void scrollIntoView(String selector) throws Exception {
        String found = evalRaw("(() => {const element = document.querySelector(" + JSONObject.quote(selector) + ");"
                + "if (!element) return false; element.scrollIntoView({block:'center',inline:'nearest',behavior:'instant'});"
                + "return true;})()");
        assertEquals("WebView target must exist: " + selector, "true", found);
        awaitJsTrue("(() => {const r = document.querySelector(" + JSONObject.quote(selector) + ").getBoundingClientRect();"
                + "return r.width > 0 && r.height > 0 && r.top >= 0 && r.bottom <= innerHeight;})()");
    }

    private void tapDomCenter(String selector) throws Exception {
        scrollIntoView(selector);
        JSONObject point = new JSONObject(evalString("(() => {const element = document.querySelector("
                + JSONObject.quote(selector) + "); if (!element) return JSON.stringify({missing: true});"
                + "const rect = element.getBoundingClientRect();"
                + "return JSON.stringify({x: rect.left + rect.width / 2, y: rect.top + rect.height / 2,"
                + "viewportWidth: innerWidth});})()"));
        assertFalse("WebView target element must exist: " + selector, point.optBoolean("missing"));
        AtomicReference<float[]> screen = new AtomicReference<>();
        scenario.onActivity(activity -> {
            WebView webView = findWebView(activity.getWindow().getDecorView());
            assertNotNull(webView);
            int[] location = new int[2];
            webView.getLocationOnScreen(location);
            float scale = webView.getWidth() / (float) point.optDouble("viewportWidth");
            float x = location[0] + (float) point.optDouble("x") * scale;
            float y = location[1] + (float) point.optDouble("y") * scale;
            Rect visible = new Rect();
            assertTrue(webView.getGlobalVisibleRect(visible));
            assertTrue("tap point must be on the visible WebView: " + selector, visible.contains(Math.round(x), Math.round(y)));
            screen.set(new float[] {x, y});
        });
        float[] xy = screen.get();
        PhysicalTap.tap(xy[0], xy[1]);
    }

    /** A wrapped terminal buffer is not a viewport-sized screenshot marker. */
    private void captureTerminalScreenshot(String name) throws Exception {
        try {
            awaitJsTrue("(() => {const rect=n=>{const r=n.getBoundingClientRect();return {left:r.left,right:r.right,top:r.top,bottom:r.bottom,width:r.width,height:r.height};};"
                    + "const vv=visualViewport;const viewport={left:vv?.offsetLeft??0,top:vv?.offsetTop??0,width:vv?.width??innerWidth,height:vv?.height??innerHeight};"
                    + "const panes=[...document.querySelectorAll('.terminal-slot')];"
                    + "const active=panes.filter(n=>n.offsetParent!==null && rect(n).width>0 && rect(n).height>0);"
                    + "window.__vaultTerminalGeometry={viewport,activePaneCount:active.length,panes:panes.map(n=>({...rect(n),visible:n.offsetParent!==null}))};"
                    + "if(active.length!==1)return false;const pane=active[0];const container=pane.querySelector('.xterm-rows');"
                    + "const rows=container?[...container.children]:[];window.__vaultTerminalGeometry.container=container?rect(container):null;"
                    + "window.__vaultTerminalGeometry.rows=rows.map(n=>({...rect(n),receipt:n.textContent.includes('VAULT42')}));"
                    + "const row=rows.find(n=>{const r=rect(n);const hit=document.elementFromPoint((r.left+r.right)/2,(r.top+r.bottom)/2);"
                    + "return n.offsetParent!==null && getComputedStyle(n).visibility==='visible' && n.textContent.includes('VAULT42')"
                    + " && r.width>0 && r.height>0 && r.top>=viewport.top && r.bottom<=viewport.top+viewport.height"
                    + " && r.left>=viewport.left && r.right<=viewport.left+viewport.width && !!hit && pane.contains(hit);});"
                    + "if(!row)return false;row.dataset.vaultTerminalReceipt='active';return true;})()");
            // The short visible receipt tolerates soft wrapping; connectAccountHost
            // separately verified the full run-unique command on the real host.
            captureScreenshot(name, "[data-vault-terminal-receipt=active]");
        } finally {
            Log.i(TAG, "VAULT_TERMINAL_GEOMETRY " + evalString("JSON.stringify(window.__vaultTerminalGeometry ?? null)"));
        }
    }

    private void captureScreenshot(String name, String markerSelector) throws Exception {
        awaitJsTrue("(() => {const node = document.querySelector(" + JSONObject.quote(markerSelector) + ");"
                + " if (!node) return false; node.scrollIntoView({block:'nearest',behavior:'instant'});"
                + " const r = node.getBoundingClientRect(); return r.height > 0 && r.top >= 0 && r.bottom <= innerHeight;})()");
        ScreenshotGuard.Frame capture = null;
        for (int attempt = 0; attempt < 5; attempt += 1) {
            capture = renderedScreenshot(name);
            if (!capture.isStaleRelativeTo(lastScreenshot)) break;
            capture = null;
            Thread.sleep(400);
        }
        assertNotNull("screenshot " + name + " stayed byte-identical to the previous capture", capture);
        lastScreenshot = capture;
        writeScreenshot(name, capture.bytes);
    }

    private ScreenshotGuard.Frame renderedScreenshot(String name) throws Exception {
        CountDownLatch rendered = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            WebView webView = findWebView(activity.getWindow().getDecorView());
            assertNotNull(webView);
            webView.postVisualStateCallback(SystemClock.uptimeMillis(), new WebView.VisualStateCallback() {
                @Override
                public void onComplete(long requestId) {
                    rendered.countDown();
                }
            });
        });
        assertTrue("WebView must render before " + name, rendered.await(JS_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        Thread.sleep(250);
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull("screenshot " + name, bitmap);
        byte[] contentDigest = ScreenshotGuard.computeContentDigest(bitmap);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, encoded));
        bitmap.recycle();
        byte[] bytes = encoded.toByteArray();
        assertTrue("screenshot must contain rendered UI: " + name, bytes.length > 1024);
        return new ScreenshotGuard.Frame(bytes, contentDigest);
    }

    private void writeScreenshot(String name, byte[] bytes) throws Exception {
        ContentValues media = new ContentValues();
        media.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        media.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        media.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PocketShell/JsVaultJourney/" + runId);
        media.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = targetContext().getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, media);
        assertNotNull("screenshot export uri", uri);
        try (OutputStream output = targetContext().getContentResolver().openOutputStream(uri)) {
            assertNotNull(output);
            output.write(bytes);
        }
        media.clear();
        media.put(MediaStore.Images.Media.IS_PENDING, 0);
        assertEquals(1, targetContext().getContentResolver().update(uri, media, null, null));
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
        String state = evalRaw("JSON.stringify({screen: (document.querySelector('#app')?.innerText ?? '').slice(0, 600),"
                + " unlockError: document.querySelector('[data-testid=vault-unlock-error]')?.textContent ?? null,"
                + " pickerError: document.querySelector('.picker p.error')?.textContent ?? null})");
        throw new AssertionError("JavaScript condition did not become true: " + expression + " (last: " + last + ", state: " + state + ")");
    }

    private String evalString(String expression) throws Exception {
        Object decoded = new JSONTokener(evalRaw(expression)).nextValue();
        return decoded == null || decoded == JSONObject.NULL ? null : decoded.toString();
    }

    private String evalRaw(String expression) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        scenario.onActivity(activity -> {
            WebView webView = findWebView(activity.getWindow().getDecorView());
            assertNotNull("the packaged activity must contain its WebView", webView);
            webView.evaluateJavascript(expression, value -> {
                result.set(value);
                latch.countDown();
            });
        });
        assertTrue("timed out evaluating WebView JavaScript", latch.await(JS_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        return result.get();
    }

    private String shell(String command) throws Exception {
        android.os.ParcelFileDescriptor output = InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);
        try (java.io.InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(output)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Context targetContext() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private static WebView findWebView(View view) {
        if (view instanceof WebView) return (WebView) view;
        if (view instanceof android.view.ViewGroup group) {
            for (int index = 0; index < group.getChildCount(); index++) {
                WebView child = findWebView(group.getChildAt(index));
                if (child != null) return child;
            }
        }
        return null;
    }
}
