package com.pocketshell.app.smoke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Process;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;
import android.webkit.WebView;

import androidx.lifecycle.Lifecycle;
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
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
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
    private static final String RESTART_PREFS = "pocketshell-vault-journey-restart";
    private static final String RESTART_TEST = "rememberOnThisDeviceUnlocksSilentlyNextLaunchAndSignOutForgets";
    private static final String STAGED_FIXTURE = "/data/local/tmp/pocketshell-vault-fixture-[A-Za-z0-9_-]+\\.json";
    private static final String KEY_PICKER = "[data-testid=account-host-key-gate]";
    private static final String UNLOCK = "[data-testid=vault-unlock-gate]";

    private ActivityScenario<?> scenario;
    private Activity packagedActivity;
    private String runId;
    private ScreenshotGuard.Frame lastScreenshot;
    private GatewayDockerJourneyTest.FakeSyncBackend backend;
    private GatewayDockerJourneyTest harness;
    private String alias;
    private String privateKeyBody;
    private final java.util.Set<WindowInsetsAnimation> imeAnimations = new java.util.HashSet<>();
    private int imeAnimationEnds;
    private boolean keepRememberedAccount;

    @Rule public final TestName testName = new TestName();

    @Before
    public void installFakeEdges() throws Exception {
        runId = InstrumentationRegistry.getArguments().getString("screenshotRunId", "vaultw4-" + System.currentTimeMillis());
        assertTrue("screenshot run id must be path safe", runId.matches("[A-Za-z0-9][A-Za-z0-9_-]{2,48}"));
        String fixturePath = InstrumentationRegistry.getArguments().getString("vaultFixture");
        assertNotNull("the runner stages the vault fixture and passes vaultFixture", fixturePath);
        assertTrue("vault fixture must be runner-staged: " + fixturePath, fixturePath.matches(STAGED_FIXTURE));
        // Every phase uses the same server identity and vault; the runner removes the fixture.
        JSONObject fixture = new JSONObject(shell("cat " + fixturePath));
        alias = fixture.getString("alias");
        privateKeyBody = fixture.getString("privateKeyMarker");
        harness = new GatewayDockerJourneyTest();
        boolean restarting = RESTART_TEST.equals(testName.getMethodName());
        harness.installFakeEdges(restarting);
        harness.selectVariant("active");
        backend = harness.backend;
        if (!restarting) {
            targetContext().deleteSharedPreferences(DEVICE_KEY_PREFS);
            targetContext().deleteSharedPreferences(RESTART_PREFS);
        }
    }

    @After
    public void restore() throws Exception {
        if (scenario != null) {
            if (!keepRememberedAccount) {
                try {
                    evalRaw("Object.keys(localStorage).filter((key) => key.startsWith('pocketshell.ssh.host-key.')"
                            + " || ['" + HOSTS_KEY + "','" + SHARED_SETTINGS_KEY + "','pocketshell.trustedServers.v1'].includes(key))"
                            + ".forEach((key) => localStorage.removeItem(key)); 'reset'");
                } catch (Throwable ignored) {
                    // The activity may already be gone after a failure.
                }
            }
            scenario.close();
            packagedActivity = null;
        }
        harness.scenario = null;
        harness.restore(keepRememberedAccount);
        if (!keepRememberedAccount) {
            targetContext().deleteSharedPreferences(AUTH_PREFS);
            targetContext().deleteSharedPreferences(DEVICE_KEY_PREFS);
            targetContext().deleteSharedPreferences(RESTART_PREFS);
        }
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

        // The very first tap must own the unlock even while launch is still
        // waiting for the authenticated vault header.
        CountDownLatch vaultHeaderGate = new CountDownLatch(1);
        backend.vaultHeaderGate = vaultHeaderGate;
        try {
            relaunch();
            assertTrue("launch is waiting for the vault header",
                    backend.vaultHeaderRequested.await(20, TimeUnit.SECONDS));
            awaitAccountHostListed();
            connectAccountHost(true, vaultHeaderGate);
        } finally {
            vaultHeaderGate.countDown();
            backend.vaultHeaderGate = null;
        }

        // Reopen a cached host while the signed-in account's vault is locked.
        // Dismiss the launch prompt first so the physical row tap owns the
        // next prompt and the following connection.
        relaunch();
        awaitJsTrue("!!document.querySelector('" + UNLOCK + " [data-testid=vault-unlock-passphrase]')", 45_000);
        tapDomCenter("[data-testid=vault-unlock-cancel]");
        awaitJsTrue("!document.querySelector('" + UNLOCK + "')");
        awaitAccountHostListed();
        connectAccountHost(true, null);
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

    @Test
    public void seedRememberedVaultForProcessRestart() throws Exception {
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

        watchKeyPicker();
        scenario.moveToState(Lifecycle.State.CREATED);
        scenario.moveToState(Lifecycle.State.RESUMED);
        awaitVerifiedAccountHostListed();
        assertEquals("a remembered vault stays open on return", "0", evalRaw("window.__psMaxSheets"));

        assertTrue("commit the same-run restart witness", targetContext()
                .getSharedPreferences(RESTART_PREFS, Context.MODE_PRIVATE).edit()
                .putString("run", runId).putInt("seedPid", Process.myPid()).commit());
        keepRememberedAccount = true;
    }

    /** The runner has force-stopped the seeded app before starting this method. */
    @Test
    public void rememberOnThisDeviceUnlocksSilentlyNextLaunchAndSignOutForgets() throws Exception {
        SharedPreferences witness = targetContext().getSharedPreferences(RESTART_PREFS, Context.MODE_PRIVATE);
        assertEquals("this run seeded the remembered vault", runId, witness.getString("run", null));
        int seedPid = witness.getInt("seedPid", -1);
        assertTrue("the vault is being opened in a new process: seed=" + seedPid + " current=" + Process.myPid(),
                seedPid > 0 && seedPid != Process.myPid());
        File sealed = deviceKeyPrefs();
        assertTrue("the Keystore-sealed record survived the stop", sealed.exists() && hasRecord(sealed));

        backend.vaultHeaderOffline = true;
        launchSharedShell();
        awaitJsTrue("!!document.querySelector('[data-testid=trusted-servers]')", 45_000);
        watchKeyPicker();
        awaitJsTrue("!!document.querySelector('[data-testid=trusted-servers][data-sync-phase=offline]')");
        awaitAccountHostListed();
        assertEquals("an offline launch never treats the cached list as verified", "false",
                evalRaw("!!document.querySelector('[data-testid=trusted-servers-list]')"));
        assertTrue("temporary network loss preserves the sealed record", sealed.exists() && hasRecord(sealed));
        backend.vaultHeaderOffline = false;
        evalRaw("window.dispatchEvent(new Event('online')); 'network back'");
        awaitVerifiedAccountHostListed();
        assertEquals("a remembered vault asks for nothing in the new process", "0", evalRaw("window.__psMaxSheets"));
        assertTrue("the fresh process read the current vault header with the current account credential",
                backend.reads.contains("/settings/vault"));
        Log.i(TAG, "VAULT_REMEMBERED_COLD_START seedPid=" + seedPid + " reopenedPid=" + Process.myPid());
        connectAccountHost(false, null);
        assertNoKeyPickerEverShown();

        // Sign out from the shared Account screen: the record and the held keys go.
        relaunch();
        awaitAccountHostListed();
        tapDomCenter(".picker .header-actions .account-action");
        awaitJsTrue("!!document.querySelector('[data-testid=android-account] .account-identity .btn-ghost:not([disabled])')");
        tapDomCenter("[data-testid=android-account] .account-identity .btn-ghost");
        awaitJsTrue("!!document.querySelector('[data-testid=android-account] .account-login')");
        // The Account screen shows the sign-out result. The host list still
        // has the same contents as the earlier screenshot.
        captureScreenshot("vault-signed-out-forgotten.png", "[data-testid=android-account] .account-login");
        assertFalse("sign-out forgets the remembered vault", sealed.exists() && hasRecord(sealed));
        assertNoPrivateKeyAtRest();
        assertNoSecretsInLogcat();
    }

    // ---- journey steps ------------------------------------------------------

    private void launchSharedShell() {
        harness.scenario = ActivityScenario.launch(SharedShellLaunch.intent());
        scenario = harness.scenario;
        scenario.onActivity(activity -> packagedActivity = activity);
    }

    private void signInAndRelaunch() throws Exception {
        launchSharedShell();
        onActivity(activity -> findWebView(activity.getWindow().getDecorView())
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
        // #3189 keeps a saved list after a lost sign-in; a no-account start drops one an earlier journey left.
        awaitJsTrue("!!document.querySelector('[data-testid=server-google-login]')"
                + " || !!document.querySelector('[data-testid=server-reauth]')", 60_000);
        if ("true".equals(evalRaw("String(!!document.querySelector('[data-testid=server-reauth]'))").replace("\"", ""))) {
            evalRaw("localStorage.removeItem('pocketshell.trustedServers.v1'); location.reload(); 'reload'");
            awaitJsTrue("!!document.querySelector('[data-testid=server-google-login]')", 60_000);
        }
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
            onActivity(activity -> {
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
        evalRaw("window.__psSawKeyPicker = !!document.querySelector('" + KEY_PICKER + "');"
                + "window.__psMaxSheets = document.querySelectorAll('" + UNLOCK + "').length;"
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
        watchKeyPicker();
    }

    private void awaitAccountHostListed() throws Exception {
        awaitJsTrue("[...document.querySelectorAll('[data-testid=trusted-server-connect]:not([disabled])')].some((n) => n.textContent.trim() === "
                + JSONObject.quote(alias) + ")", UNLOCK_TIMEOUT_MILLIS);
    }

    private void awaitVerifiedAccountHostListed() throws Exception {
        awaitJsTrue("[...document.querySelectorAll('[data-testid=trusted-servers-list] [data-testid=trusted-server-connect]')]"
                + ".some(n => n.textContent.trim() === " + JSONObject.quote(alias) + ")", UNLOCK_TIMEOUT_MILLIS);
    }

    private void connectAccountHost(boolean locked, CountDownLatch vaultHeaderGate) throws Exception {
        String phase = vaultHeaderGate != null ? "pending" : locked ? "type" : "remember";
        String fingerprint = harness.authorizeVault("vault-" + phase + "-authorize");
        harness.installDialRecorder();
        int mark = harness.controller(new JSONObject().put("op", "mark")).getInt("mark");
        if (locked) tapLockedAccountHost(vaultHeaderGate);
        else tapDomCenter("[data-testid=trusted-server-connect]");
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

    private void tapLockedAccountHost(CountDownLatch vaultHeaderGate) throws Exception {
        evalRaw("""
                (() => {
                  const row = '[data-testid=trusted-server-connect]';
                  const timing = window.__vaultTap = {};
                  document.addEventListener('pointerdown', event => {
                    if (timing.pressed === undefined && event.target.closest(row)) timing.pressed = performance.now();
                  }, true);
                  document.addEventListener('click', event => {
                    if (timing.selected === undefined && event.target.closest(row)) timing.selected = performance.now();
                  }, true);
                  const sample = () => {
                    if (timing.selected === undefined) return;
                    if (timing.busy === undefined && document.querySelector(row + '[aria-busy=true]')) {
                      timing.busy = performance.now();
                    }
                    const sheet = document.querySelector('[data-testid=vault-unlock-gate] [role=dialog]');
                    if (timing.opened === undefined && sheet?.getBoundingClientRect().height > 0) {
                      timing.opened = performance.now();
                      timing.title = sheet.querySelector('h2').textContent.trim();
                    }
                  };
                  new MutationObserver(sample).observe(document.body, {
                    subtree: true, childList: true, attributes: true, attributeFilter: ['aria-busy'],
                  });
                  return true;
                })()
                """);
        tapDomCenter("[data-testid=trusted-server-connect]");
        if (vaultHeaderGate != null) {
            awaitJsTrue("window.__vaultTap?.busy !== undefined");
            assertEquals("a pending vault header never opens a passphrase prompt", "false",
                    evalRaw("!!document.querySelector('" + UNLOCK + "')"));
            evalRaw("window.__vaultTap.headerReleased = performance.now(); true");
            vaultHeaderGate.countDown();
        }
        // Releasing the fixture starts the native response and WebView delivery.
        // The 300 ms tap budget covers the already-confirmed account.
        awaitJsTrue("window.__vaultTap?.opened !== undefined");
        JSONObject timing = new JSONObject(evalString("JSON.stringify(window.__vaultTap)"));
        if (vaultHeaderGate != null)
            timing.put("afterHeaderReleasedMs", timing.getDouble("opened") - timing.getDouble("headerReleased"));
        Log.i(TAG, "VAULT_LOCKED_HOST_TAP " + timing);
        assertTrue("the physical host tap shows feedback within 100 ms: " + timing,
                timing.getDouble("busy") - timing.getDouble("pressed") <= 100);
        assertEquals("the prompt says how to unlock the keys for the selected host",
                "Unlock your key vault to connect to " + alias, timing.getString("title"));
        if (vaultHeaderGate == null) {
            assertTrue("the confirmed account opens the selected-host prompt within 300 ms: " + timing,
                    timing.getDouble("opened") - timing.getDouble("pressed") <= 300);
            captureScreenshot("vault-unlock-selected-host.png", "[data-testid=vault-unlock-submit]");
        }
        setValue("[data-testid=vault-unlock-passphrase]", VAULT_PASSPHRASE);
        tapDomCenter("[data-testid=vault-unlock-submit]");
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

    /**
     * The unlock dialog is centred in the visual viewport, so the IME that its autofocused passphrase field
     * raises moves it after the first measurement; tapping a stale rect lands beside the control (S4a layout
     * made this visible). Wait until the target's rect is identical across consecutive samples.
     */
    private void awaitRectSettled(String selector) throws Exception {
        String sample = "(() => {const n = document.querySelector(" + JSONObject.quote(selector) + ");"
                + "if (!n) return ''; const r = n.getBoundingClientRect();"
                + "return [r.left, r.top, r.width, r.height, innerHeight, visualViewport ? visualViewport.height : 0].map(Math.round).join(',');})()";
        String previous = null;
        int stable = 0;
        long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            String current = evalString(sample);
            stable = !current.isEmpty() && current.equals(previous) ? stable + 1 : 0;
            if (stable >= 3) return;
            previous = current;
            Thread.sleep(200);
        }
        throw new AssertionError("target never stopped moving before the tap: " + selector + " last=" + previous);
    }

    private void tapDomCenter(String selector) throws Exception {
        scrollIntoView(selector);
        awaitRectSettled(selector);
        JSONObject point = new JSONObject(evalString("(() => {const element = document.querySelector("
                + JSONObject.quote(selector) + "); if (!element) return JSON.stringify({missing: true});"
                + "const rect = element.getBoundingClientRect();"
                + "return JSON.stringify({x: rect.left + rect.width / 2, y: rect.top + rect.height / 2,"
                + "viewportWidth: innerWidth});})()"));
        assertFalse("WebView target element must exist: " + selector, point.optBoolean("missing"));
        AtomicReference<float[]> screen = new AtomicReference<>();
        onActivity(activity -> {
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
        onActivity(activity -> {
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
            last = JsVisibility.pollRaw(this::evalRaw, expression);
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

    /** Match the composer journey's bounded main-thread dispatch when the IME keeps the looper busy. */
    private void onActivity(ActivityScenario.ActivityAction<Activity> action) throws InterruptedException {
        Activity activity = packagedActivity;
        assertNotNull("the packaged activity has launched", activity);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        activity.runOnUiThread(() -> {
            try {
                action.perform(activity);
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                done.countDown();
            }
        });
        assertTrue("timed out running on the packaged activity's main thread", done.await(JS_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (failure.get() instanceof Error error) throw error;
        if (failure.get() instanceof RuntimeException error) throw error;
        if (failure.get() != null) throw new RuntimeException(failure.get());
    }

    private String evalRaw(String expression) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        onActivity(activity -> {
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
