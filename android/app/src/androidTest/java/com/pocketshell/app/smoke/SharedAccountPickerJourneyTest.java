package com.pocketshell.app.smoke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;
import android.webkit.WebView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.pocketshell.app.GoogleSyncEnvironment;
import com.pocketshell.app.GoogleSyncSession;
import com.pocketshell.app.MainActivity;
import com.pocketshell.app.SharedShellLaunch;
import com.pocketshell.app.SyncAuthException;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Issue #3063: the shared app's host picker with a Google account, on the
 * packaged APK. The same two fake edges as {@link AccountSyncJourneyTest}
 * (Google's account chooser and the sync API, installed through
 * {@link GoogleSyncEnvironment}); everything between them is production: the
 * GoogleSync plugin, the encrypted token store, the WebView envelope crypto,
 * the shared HostPickerView and AccountView, and the Android platform's sync
 * group, account route and account-host key prompt.
 *
 * <p>The account holds hosts the DESKTOP client wrote. Signing in from
 * the picker's header button and unlocking with the passphrase must list every
 * account host on home with no sync step and no upload, keep the decrypted copy
 * out of WebView storage, and ask for this phone's key when an account host is
 * tapped.
 */
@RunWith(AndroidJUnit4.class)
public final class SharedAccountPickerJourneyTest {
    private static final String TAG = "PocketshellAccountSync";
    private static final long JS_TIMEOUT_SECONDS = 15;
    private static final long WAIT_TIMEOUT_MILLIS = 20_000;
    private static final long SYNC_TIMEOUT_MILLIS = 120_000;
    /** Unique marker in the fake ID token's signature; must never leave native code. */
    static final String TOKEN_SIGNATURE = "FAKEIDTOKEN3020SIGNATUREdoNotLeak";
    private static final String EMAIL = "phone-journey@example.com";
    private static final String PASSPHRASE = "correct horse battery staple";
    private static final String HOSTS_KEY = "pocketshell.android.hosts.v1";
    private static final String ACCOUNT_HOSTS_KEY = "pocketshell.sync.account-hosts.v1";
    private static final String SHARED_SETTINGS_KEY = "pocketshell.settings.v1";
    private static final String AUTH_PREFS = "pocketshell-sync-auth";

    /** The account as the desktop wrote it: hetzner carries fields only the desktop owns. */
    private static JSONObject desktopAccount() throws Exception {
        return new JSONObject().put("hosts", new JSONArray()
                .put(new JSONObject().put("name", "hetzner").put("hostname", "135.181.114.209").put("port", 22)
                        .put("user", "alexey").put("identityFile", "~/.ssh/id_ed25519").put("proxyJump", "bastion")
                        .put("localForwards", new JSONArray().put(new JSONObject().put("kind", "local")
                                .put("listenHost", "").put("listenPort", 8080).put("destHost", "localhost").put("destPort", 80)))
                        .put("futureDirective", new JSONObject().put("mode", "opaque")))
                .put(new JSONObject().put("name", "bäckerei").put("hostname", "höfn.internal").put("port", 2222).put("user", "root")));
    }

    private ActivityScenario<MainActivity> scenario;
    private String runId;
    private ScreenshotGuard.Frame lastScreenshot;
    private FakeSyncBackend backend;
    private FakeGoogle google;
    // Main-thread-only platform animation state: autofocus can move Cancel after DOM layout.
    private final java.util.Set<WindowInsetsAnimation> imeAnimations = new java.util.HashSet<>();
    private int imeAnimationEnds;


    @Before
    public void installFakeEdges() {
        runId = InstrumentationRegistry.getArguments().getString("screenshotRunId", "js3063-" + System.currentTimeMillis());
        assertTrue("screenshot run id must be path safe", runId.matches("[A-Za-z0-9][A-Za-z0-9_-]{2,48}"));
        backend = new FakeSyncBackend();
        google = new FakeGoogle();
        GoogleSyncEnvironment.installForTesting(activity -> google, backend);
        targetContext().deleteSharedPreferences(AUTH_PREFS);
    }

    @After
    public void restore() {
        if (scenario != null) {
            try {
                evalRaw("['" + HOSTS_KEY + "','" + ACCOUNT_HOSTS_KEY + "','" + SHARED_SETTINGS_KEY
                        + "'].forEach((key) => localStorage.removeItem(key)); 'reset'");
            } catch (Throwable ignored) {
                // The activity may already be gone after a failure.
            }
            scenario.close();
        }
        GoogleSyncEnvironment.resetForTesting();
        targetContext().deleteSharedPreferences(AUTH_PREFS);
    }

    @Test
    public void sharedTrustedPickerNeverAdoptsUntrustedAccountRowsAfterSignInOrSettingsSync() throws Exception {
        assertTrue("packaged account journey runs on API 35+", Build.VERSION.SDK_INT >= 35);
        backend.seed(encrypt(desktopAccount().toString()), 3);
        // A host-list envelope is not a key vault. Serve a genuine core-created
        // vault for this existing-vault scenario, under the same verified account.
        String fixturePath = InstrumentationRegistry.getArguments().getString("vaultFixture");
        assertNotNull("the runner stages a real encrypted vault", fixturePath);
        assertTrue("runner-staged vault path", fixturePath.matches("/data/local/tmp/pocketshell-picker-vault-[A-Za-z0-9_-]+\\.json"));
        backend.vaultSlots = new JSONObject(shell("cat " + fixturePath)).getJSONObject("slots");
        backend.vaultSlots.remove("main"); // Preserve the desktop host-list envelope seeded above.
        assertTrue("a real vault header is required", backend.vaultSlots.has("vault"));
        launchSharedWithLocalHosts(new JSONArray()
                .put(savedHost("phone-box", "192.168.1.9", 2200, "me"))
                .put(savedHost("hetzner", "hetzner.phone.lan", 22, "alexey")));
        awaitJsTrue("!!document.querySelector('[data-testid=trusted-servers]')"
                + " && !!document.querySelector('[data-testid=server-google-login]')");
        assertOnlySavedLocalPickerRows();
        assertAccountButtonIsTouchSized();
        captureScreenshot("shared-picker-signed-out.png", "[data-testid=trusted-servers]");
        tapDomCenter(".picker .header-actions .account-action");
        awaitJsTrue("!!document.querySelector('[data-testid=android-account] .account-login .account-primary:not([disabled])')");
        tapDomCenter("[data-testid=android-account] .account-login .account-primary");
        awaitJsTrue("document.querySelector('[data-testid=android-account] .account-identity')?.textContent.includes("
                + JSONObject.quote(EMAIL) + ") === true");
        assertEquals("one interactive account chooser", 1, google.interactive.get());
        backToPicker();
        awaitJsTrue("!!document.querySelector('[data-testid=vault-unlock-gate]')");
        captureVaultScreenshot("shared-picker-signed-in-locked.png");
        dismissVaultUnlockAndAwaitPicker();
        awaitJsTrue("!!document.querySelector('[data-testid=server-vault-unlock]')");
        assertOnlySavedLocalPickerRows();
        // Settings decrypt never grants authenticated server trust.
        tapDomCenter(".picker .header-actions .account-action");
        awaitJsTrue("!!document.querySelector('[data-testid=android-account] .passphrase-field input:not([disabled])')");
        setValue("[data-testid=android-account] .passphrase-field input", PASSPHRASE);
        tapDomCenter("[data-testid=android-account] .host-heading .btn-ghost");
        awaitJsTrue("document.querySelector('[data-testid=android-account] .account-message')?.textContent.trim()"
                + " === 'Your account has 2 synced hosts.'", SYNC_TIMEOUT_MILLIS);
        assertEquals("decrypting settings uploads nothing", 0, backend.puts.size());
        backToPicker();
        awaitJsTrue("!!document.querySelector('[data-testid=vault-unlock-gate]')");
        dismissVaultUnlockAndAwaitPicker();
        assertOnlySavedLocalPickerRows();
        awaitJsTrue("!document.querySelector('[data-testid=trusted-server-connect]')"
                + " && !document.querySelector('[data-testid=account-host-key-gate]')");
        assertNoTokenInWebView("after settings decrypt");
        assertEquals("the decrypted settings copy never reaches storage", "null", evalRaw("localStorage.getItem('" + ACCOUNT_HOSTS_KEY + "')"));
        assertFalse("no decrypted account address at rest", evalString("JSON.stringify(Object.entries(localStorage))").contains("höfn.internal"));
        captureScreenshot("shared-picker-account-hosts.png", "[data-testid=trusted-servers]");
        // One Sync now from the shared Account screen: the account keeps both
        // hosts, and hetzner keeps every field the desktop owns.
        tapDomCenter(".picker .header-actions .account-action");
        awaitJsTrue("!!document.querySelector('[data-testid=android-account] .account-actions .account-primary:not([disabled])')");
        tapDomCenter("[data-testid=android-account] .account-actions .account-primary");
        awaitJsTrue("document.querySelector('[data-testid=android-account] .account-message')?.textContent.trim()"
                + " === 'Synced: 2 hosts in your account.'", SYNC_TIMEOUT_MILLIS);
        assertEquals("one upload", 1, backend.puts.size());
        JSONArray uploaded = decrypt(backend.puts.get(0).data).getJSONArray("hosts");
        List<String> uploadedNames = names(uploaded);
        Collections.sort(uploadedNames);
        assertEquals("Sync now keeps every account host", List.of("bäckerei", "hetzner"), uploadedNames);
        JSONObject hetzner = byName(uploaded, "hetzner");
        assertEquals("the phone's own address wins", "hetzner.phone.lan", hetzner.getString("hostname"));
        assertEquals("identityFile survives", "~/.ssh/id_ed25519", hetzner.getString("identityFile"));
        assertEquals("proxyJump survives", "bastion", hetzner.getString("proxyJump"));
        assertEquals("forwards survive", 8080, hetzner.getJSONArray("localForwards").getJSONObject(0).getInt("listenPort"));
        assertEquals("unknown fields survive", "opaque", hetzner.getJSONObject("futureDirective").getString("mode"));
        assertEquals("an account-only host is carried unchanged", "höfn.internal", byName(uploaded, "bäckerei").getString("hostname"));
        captureScreenshot("shared-account-synced.png", "[data-testid=android-account] .account-message");
        backToPicker();
        awaitJsTrue("!!document.querySelector('[data-testid=vault-unlock-gate]')");
        dismissVaultUnlockAndAwaitPicker();
        assertOnlySavedLocalPickerRows();
        awaitJsTrue("!document.querySelector('[data-testid=trusted-server-connect]')"
                + " && !document.querySelector('[data-testid=account-host-key-gate]')");
        assertNoTokenInLogcat();
        Log.i(TAG, "SHARED_PICKER " + new JSONObject()
                .put("requests", backend.requests.size()).put("uploads", backend.puts.size())
                .put("accountRows", new JSONArray()).put("untrustedRowsHidden", true)
                .put("syncedHosts", new JSONArray(uploadedNames))
                .put("keptDesktopFields", true));
    }

    /**
     * The explicit legacy-shell account/settings capability after an app restart: the
     * decrypted account copy is in memory only (#3026), so the synced-host list
     * is gone until the passphrase unlocks it again, which the Account screen
     * says, and one "Show account hosts" brings it back without uploading.
     */
    @Test
    public void legacyHomeListsAccountHostsAgainAfterRestartOnceUnlocked() throws Exception {
        assertTrue("packaged account journey runs on API 35+", Build.VERSION.SDK_INT >= 35);
        backend.seed(encrypt(desktopAccount().toString()), 3);
        launchLegacyWithLocalHosts(new JSONArray().put(savedHost("phone-box", "192.168.1.9", 2200, "me")));

        openLegacyAccountScreen();
        awaitJsTrue("!!document.querySelector('[data-testid=account-sign-in]:not([disabled])')");
        tapDomCenter("[data-testid=account-sign-in]");
        awaitJsTrue("document.querySelector('[data-testid=account-sync-email]')?.textContent.includes(" + JSONObject.quote(EMAIL) + ") === true");
        unlockLegacyAccount();
        returnLegacyHome();
        String options = "[...document.querySelectorAll('[data-testid=synced-host-select] option')].map((o) => o.value).join(',')";
        awaitJsTrue(options + " === ',hetzner,bäckerei'");

        // Restart the app: the account hosts are gone until unlocked again.
        String staleDocument = "restart-" + SystemClock.uptimeMillis();
        evalRaw("window.__pocketshellStaleDocument = " + JSONObject.quote(staleDocument) + "; location.reload(); 'reload'");
        awaitJsTrue("window.__pocketshellStaleDocument !== " + JSONObject.quote(staleDocument)
                + " && document.readyState === 'complete'"
                + " && document.querySelector('[data-testid=build-status]')?.dataset.state === 'verified'", 45_000);
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'home'"
                + " && document.querySelector('[data-testid=synced-host-select]') === null");
        openLegacyAccountScreen();
        awaitJsTrue("!!document.querySelector('[data-testid=account-sync-locked]')");
        captureScreenshot("legacy-account-locked-after-restart.png", "[data-testid=account-sync-locked]");
        unlockLegacyAccount();
        returnLegacyHome();
        awaitJsTrue(options + " === ',hetzner,bäckerei'");
        captureScreenshot("legacy-home-synced-hosts-after-unlock.png", "[data-testid=synced-host-select]");
        assertEquals("unlocking uploads nothing", 0, backend.puts.size());
        assertFalse("no decrypted account address in WebView storage",
                evalString("JSON.stringify(Object.entries(localStorage))").contains("höfn.internal"));
        assertNoTokenInLogcat();
    }

    /** Local saved rows remain usable; account decryption grants no row or address authority. */
    private void assertOnlySavedLocalPickerRows() throws Exception {
        String rows = "JSON.stringify([...document.querySelectorAll('.picker .host-row')].map((row) => "
                + "row.querySelector('.host-name').textContent.trim() + '=' + "
                + "row.querySelector('[data-testid=host-detail]').textContent.trim()).sort())";
        String expected = new JSONArray().put("hetzner=alexey@hetzner.phone.lan:22")
                .put("phone-box=me@192.168.1.9:2200").toString();
        awaitJsTrue(rows + " === " + JSONObject.quote(expected));
        String screen = evalString("document.querySelector('.picker').textContent");
        assertFalse("the account-only host is never a picker row", screen.contains("bäckerei"));
        assertFalse("the account-only address is never exposed", screen.contains("höfn.internal"));
        assertFalse("a same-name account row never replaces the saved local address", screen.contains("135.181.114.209"));
    }

    // ---- journey steps ------------------------------------------------------

    private void launchSharedWithLocalHosts(JSONArray hosts) throws Exception {
        scenario = ActivityScenario.launch(SharedShellLaunch.intent());
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
        awaitJsTrue("!!document.querySelector('.picker .header-actions')", 45_000);
        String staleDocument = "reload-" + SystemClock.uptimeMillis();
        evalRaw("window.__pocketshellStaleDocument = " + JSONObject.quote(staleDocument) + ";"
                + "localStorage.removeItem('" + SHARED_SETTINGS_KEY + "');"
                + "localStorage.setItem('" + HOSTS_KEY + "', " + JSONObject.quote(hosts.toString()) + ");"
                + "location.reload(); 'reload'");
        awaitJsTrue("window.__pocketshellStaleDocument !== " + JSONObject.quote(staleDocument)
                + " && document.readyState === 'complete' && !!document.querySelector('.picker .header-actions')", 45_000);
    }

    private void launchLegacyWithLocalHosts(JSONArray hosts) throws Exception {
        scenario = ActivityScenario.launch(new Intent(targetContext(), MainActivity.class)
                .putExtra(MainActivity.EXTRA_SHELL, MainActivity.SHELL_LEGACY));
        awaitJsTrue("document.querySelector('[data-testid=build-status]')?.dataset.state === 'verified'", 45_000);
        String staleDocument = "reload-" + SystemClock.uptimeMillis();
        evalRaw("window.__pocketshellStaleDocument = " + JSONObject.quote(staleDocument) + ";"
                + "localStorage.removeItem('" + SHARED_SETTINGS_KEY + "');"
                + "localStorage.setItem('" + HOSTS_KEY + "', " + JSONObject.quote(hosts.toString()) + ");"
                + "location.reload(); 'reload'");
        awaitJsTrue("window.__pocketshellStaleDocument !== " + JSONObject.quote(staleDocument)
                + " && document.readyState === 'complete'"
                + " && document.querySelector('[data-testid=build-status]')?.dataset.state === 'verified'", 45_000);
    }

    private void openLegacyAccountScreen() throws Exception {
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.backButtonReady === 'true'"
                + " && document.querySelector('.app-shell')?.dataset.route === 'home'"
                + " && !!document.querySelector('[aria-label=Settings]')");
        tapDomCenter("[aria-label=Settings]");
        awaitRoute("settings");
        tapDomCenter("[data-testid=open-account-settings]");
        awaitRoute("settings-account");
    }

    private void unlockLegacyAccount() throws Exception {
        awaitJsTrue("!!document.querySelector('[data-testid=account-sync-unlock]:not([disabled])')");
        setValue("[data-testid=account-sync-passphrase]", PASSPHRASE);
        tapDomCenter("[data-testid=account-sync-unlock]");
        awaitJsTrue("document.querySelector('[data-testid=account-settings-screen]')?.dataset.syncBusy === 'idle'"
                + " && document.querySelector('[data-testid=account-sync-message]')?.textContent.trim()"
                + " === 'Your account has 2 hosts; they are listed on the home screen.'", SYNC_TIMEOUT_MILLIS);
    }

    private void returnLegacyHome() throws Exception {
        pressBack();
        awaitRoute("settings");
        pressBack();
        awaitRoute("home");
    }

    /** The picker remains mounted behind the modal; row absence cannot prove dismissal. */
    private String uncoveredPickerCondition() {
        return "(() => {if(document.querySelector('[data-testid=vault-unlock-gate]'))return false;"
                + "const button=document.querySelector('.picker .header-actions .account-action');"
                + "if(!button || !document.querySelector('[data-testid=server-vault-unlock]'))return false;"
                + "const r=button.getBoundingClientRect();const hit=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2);"
                + "return r.width>0 && r.height>0 && !!hit && (hit===button || button.contains(hit));})()";
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

    private void dismissVaultUnlockAndAwaitPicker() throws Exception {
        awaitJsTrue("!!document.querySelector('[data-testid=vault-unlock-gate]')");
        // Live red control: mounted/hidden rows must not pass the new dismissal barrier.
        assertEquals("a visible unlock sheet must block the picker", "false", evalRaw(uncoveredPickerCondition()));
        evalRaw("(() => {const gate=document.querySelector('[data-testid=vault-unlock-gate]');"
                + "const trace=window.__pickerDismissTrace={clicks:0,removed:false,reopened:false};"
                + "gate.querySelector('[data-testid=vault-unlock-cancel]').addEventListener('click',()=>trace.clicks++,{once:true});"
                + "window.__pickerDismissObserver?.disconnect();"
                + "window.__pickerDismissObserver=new MutationObserver(()=>{"
                + "if(!gate.isConnected)trace.removed=true;"
                + "const current=document.querySelector('[data-testid=vault-unlock-gate]');"
                + "if(current && current!==gate)trace.reopened=true;});"
                + "window.__pickerDismissObserver.observe(document.body,{childList:true,subtree:true});return 'armed';})()");
        // VaultUnlockForm autofocus opens the native IME asynchronously. A DOM-only
        // existence/rect check can capture coordinates before the keyboard moves them.
        awaitImeSettled(true);
        tapDomCenter("[data-testid=vault-unlock-cancel]");
        try {
            awaitJsTrue(uncoveredPickerCondition());
            assertEquals("Cancel must receive one actual click", "1", evalRaw("window.__pickerDismissTrace.clicks"));
            awaitImeSettled(false);
        } finally {
            Log.i(TAG, "VAULT_DISMISS " + evalString("JSON.stringify(window.__pickerDismissTrace)"));
            evalRaw("window.__pickerDismissObserver?.disconnect(); 'stopped'");
        }
    }

    /**
     * The Account screen's Back control: one real tap, which must land. It
     * sat under the status bar once (taps never reached it on a fresh
     * emulator), so the check also guards its top clearance.
     */
    private void backToPicker() throws Exception {
        tapDomCenter("[data-testid=android-account-back]");
        awaitJsTrue("!!document.querySelector('.picker .header-actions') && !document.querySelector('[data-testid=android-account]')");
    }

    /** The phone layer's 48px minimum (docs/design-system.md) on the header's account button. */
    private void assertAccountButtonIsTouchSized() throws Exception {
        JSONObject box = new JSONObject(evalString("(() => {const r = document.querySelector('.picker .account-action')"
                + ".getBoundingClientRect(); return JSON.stringify({w: r.width, h: r.height, right: r.right, vw: innerWidth});})()"));
        assertTrue("account button is at least 48px tall: " + box, box.getDouble("h") >= 47.5);
        assertTrue("account button fits on screen: " + box, box.getDouble("right") <= box.getDouble("vw"));
    }

    private void assertNoTokenInWebView(String when) throws Exception {
        String dump = evalString("JSON.stringify({local: Object.entries(localStorage), session: Object.entries(sessionStorage),"
                + " html: document.documentElement.outerHTML, cookie: document.cookie})");
        assertNotNull(dump);
        assertFalse("WebView state must not contain the ID token " + when, dump.contains(TOKEN_SIGNATURE));
        assertFalse("WebView state must not contain the token payload " + when, dump.contains(google.payloadSegment));
    }

    private void assertNoTokenInLogcat() throws Exception {
        String pid = shell("pidof " + targetContext().getPackageName()).trim();
        assertTrue("the app process must be running: " + pid, pid.matches("\\d+"));
        String logcat = shell("logcat -d -v brief --pid=" + pid);
        assertTrue("same-run app logcat must be readable", !logcat.isEmpty());
        assertFalse("logcat must not contain the ID token", logcat.contains(TOKEN_SIGNATURE));
        assertFalse("logcat must not contain the token payload", logcat.contains(google.payloadSegment));
        assertFalse("logcat must not contain the sync passphrase", logcat.contains(PASSPHRASE));
    }

    private static JSONObject savedHost(String name, String hostname, int port, String user) throws Exception {
        return new JSONObject().put("name", name).put("hostname", hostname).put("port", port).put("user", user)
                .put("keyHandleId", "");
    }

    private static List<String> names(JSONArray hosts) throws Exception {
        List<String> names = new ArrayList<>();
        for (int index = 0; index < hosts.length(); index += 1) names.add(hosts.getJSONObject(index).getString("name"));
        return names;
    }

    private static JSONObject byName(JSONArray hosts, String name) throws Exception {
        for (int index = 0; index < hosts.length(); index += 1) {
            if (name.equals(hosts.getJSONObject(index).getString("name"))) return hosts.getJSONObject(index);
        }
        throw new AssertionError("no host named " + name + " in " + hosts);
    }

    // ---- independent crypto oracle (javax.crypto, not the WebView) -----------

    private static JSONObject decrypt(String envelope) throws Exception {
        JSONObject fields = new JSONObject(envelope);
        byte[] salt = Base64.getDecoder().decode(fields.getString("salt"));
        byte[] iv = Base64.getDecoder().decode(fields.getString("iv"));
        byte[] ct = Base64.getDecoder().decode(fields.getString("ct"));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(salt, fields.getInt("iter")), new GCMParameterSpec(128, iv));
        return new JSONObject(new String(cipher.doFinal(ct), StandardCharsets.UTF_8));
    }

    private static String encrypt(String plaintext) throws Exception {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[16];
        byte[] iv = new byte[12];
        random.nextBytes(salt);
        random.nextBytes(iv);
        int iterations = 100_000;
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(salt, iterations), new GCMParameterSpec(128, iv));
        byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        return new JSONObject().put("v", 1).put("kdf", "pbkdf2-sha256").put("iter", iterations)
                .put("salt", Base64.getEncoder().encodeToString(salt))
                .put("iv", Base64.getEncoder().encodeToString(iv))
                .put("ct", Base64.getEncoder().encodeToString(ct)).toString();
    }

    private static SecretKeySpec key(byte[] salt, int iterations) throws Exception {
        byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(new PBEKeySpec(PASSPHRASE.toCharArray(), salt, iterations, 256)).getEncoded();
        return new SecretKeySpec(key, "AES");
    }

    // ---- fake edges ----------------------------------------------------------

    /** Google's account chooser: a scripted ID token for one account. */
    static final class FakeGoogle implements GoogleSyncSession.SignInProvider {
        final AtomicInteger interactive = new AtomicInteger();
        final String token;
        final String payloadSegment;

        FakeGoogle() {
            long exp = System.currentTimeMillis() / 1000L + 3600L;
            payloadSegment = b64url("{\"sub\":\"journey-sub-3020\",\"email\":\"" + EMAIL + "\",\"exp\":" + exp + "}");
            token = b64url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}") + "." + payloadSegment + "." + TOKEN_SIGNATURE;
        }

        @Override
        public String obtainIdToken(boolean interactiveRequest) throws SyncAuthException {
            if (interactiveRequest) interactive.incrementAndGet();
            return token;
        }

        @Override
        public void clearCredentialState() {
            // Nothing to forget: the scripted chooser holds no state.
        }

        private static String b64url(String json) {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * The pocketshell-sync API: one encrypted slot with an optimistic version,
     * and the JWT authorizer reduced to "the Bearer token is the one Google
     * issued".
     */
    final class FakeSyncBackend implements GoogleSyncSession.Transport {
        final class Request {
            final String method;
            final String path;
            final boolean authorized;

            Request(String method, String path, boolean authorized) {
                this.method = method;
                this.path = path;
                this.authorized = authorized;
            }
        }

        final class Put {
            final int baseVersion;
            final String data;

            Put(int baseVersion, String data) {
                this.baseVersion = baseVersion;
                this.data = data;
            }
        }

        final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
        final List<Put> puts = Collections.synchronizedList(new ArrayList<>());
        private String data;
        private int version;
        JSONObject vaultSlots = new JSONObject();

        synchronized void seed(String envelope, int atVersion) {
            data = envelope;
            version = atVersion;
        }

        @Override
        public synchronized GoogleSyncSession.Response send(String method, String url, Map<String, String> headers, String body) {
            String path = url.replaceFirst("^https://[^/]+", "");
            boolean authorized = ("Bearer " + google.token).equals(headers.get("Authorization"));
            requests.add(new Request(method, path, authorized));
            if (!authorized) return new GoogleSyncSession.Response(401, "{\"message\":\"Unauthorized\"}");
            if ("GET".equals(method) && "/me".equals(path)) return new GoogleSyncSession.Response(200,
                    "{\"sub\":\"journey-sub-3020\",\"email\":\"" + EMAIL + "\"}");
            if ("GET".equals(method) && "/settings".equals(path)) return new GoogleSyncSession.Response(200,
                    "{\"slots\":[{\"slot\":\"main\",\"version\":" + version + ",\"size\":" + (data == null ? 0 : data.length()) + ",\"updatedAt\":\"2026-11-05T00:00:00.000Z\"}]}");
            if ("GET".equals(method) && path.startsWith("/settings/") && vaultSlots.has(path.substring(10))) {
                try {
                    String slot = path.substring(10);
                    JSONObject stored = vaultSlots.getJSONObject(slot);
                    return new GoogleSyncSession.Response(200, new JSONObject().put("slot", slot)
                            .put("version", stored.getInt("version")).put("data", stored.getString("data")).toString());
                } catch (org.json.JSONException invalid) { throw new IllegalStateException(invalid); }
            }
            if (!"/settings/main".equals(path)) return new GoogleSyncSession.Response(404, "{\"message\":\"no route\"}");
            try {
                if ("GET".equals(method)) {
                    if (data == null) return new GoogleSyncSession.Response(404, "{\"message\":\"not found\"}");
                    return new GoogleSyncSession.Response(200, new JSONObject().put("slot", "main").put("version", version)
                            .put("data", data).toString());
                }
                JSONObject put = new JSONObject(body);
                if (put.getInt("version") != version) {
                    return new GoogleSyncSession.Response(409, new JSONObject().put("message", "version conflict")
                            .put("currentVersion", version).toString());
                }
                puts.add(new Put(put.getInt("version"), put.getString("data")));
                data = put.getString("data");
                version += 1;
                return new GoogleSyncSession.Response(200, new JSONObject().put("slot", "main").put("version", version).toString());
            } catch (Exception error) {
                return new GoogleSyncSession.Response(400, "{\"message\":\"bad request\"}");
            }
        }
    }

    // ---- WebView plumbing (same idiom as JsSettingsSupportJourneyTest) -------

    private void awaitRoute(String route) throws Exception {
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === " + JSONObject.quote(route));
    }

    private void pressBack() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
    }

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
                + "top: rect.top, bottom: rect.bottom, viewportWidth: innerWidth, viewportHeight: innerHeight});})()"));
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

    /** Screenshot readiness targets controls, not the deliberately full-height modal scrim. */
    private String vaultScreenshotGeometry() {
        return "JSON.stringify((() => {"
                + "const gates=document.querySelectorAll('[data-testid=vault-unlock-gate]');if(gates.length!==1)return null;const g=gates[0];"
                + "const ids=['passphrase','type-every-time','remember','cancel','submit'];const boxes=[];"
                + "const v=visualViewport;if(!v || !(v.scale>0) || !Number.isFinite(v.scale))return null;"
                + "for(const id of ids){const all=g.querySelectorAll('[data-testid=vault-unlock-'+id+']');if(all.length!==1)return null;"
                + "const input=all[0],n=(id==='remember'||id==='type-every-time')?input.closest('label'):input;"
                + "if(!n)return null;for(let a=n;a;a=a.parentElement){const c=getComputedStyle(a);if(c.display==='none'||c.visibility!=='visible'||Number(c.opacity)===0)return null;}"
                + "const s=getComputedStyle(n),r=n.getBoundingClientRect(),hit=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2);"
                + "const labelled=id==='passphrase'?Array.from(input.labels??[]).some(l=>l.textContent.trim().length>0):n.textContent.trim().length>0;"
                + "if(!labelled || s.display==='none'||s.visibility!=='visible'||Number(s.opacity)===0 || r.width<=0||r.height<=0"
                + "||r.left<v.offsetLeft||r.top<v.offsetTop||r.right>v.offsetLeft+v.width||r.bottom>v.offsetTop+v.height"
                + "||!hit||!(n===hit||n.contains(hit)))return null;"
                + "boxes.push([r.left,r.top,r.right,r.bottom]);}"
                + "return {width:innerWidth,offsetLeft:v.offsetLeft,offsetTop:v.offsetTop,viewportScale:v.scale,boxes};})())";
    }

    private String vaultNativeGeometry;
    private String vaultReadinessReason;

    private static double vaultNativeCoordinate(double css, double offset, double pixelsPerCss, double viewportScale, int origin) {
        return origin + (css - offset) * pixelsPerCss * viewportScale;
    }

    private final java.util.Set<Throwable> capturedVaultFailures =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    private void preserveVaultFailure(String name, Throwable failure) {
        // Nested cleanup and the outer screenshot barrier must retain the FIRST
        // failed-state frame rather than overwrite it after restoring the viewport.
        if (!capturedVaultFailures.add(failure)) return;
        try { captureViewportFailure(name); }
        catch (Throwable diagnosticFailure) { failure.addSuppressed(diagnosticFailure); }
    }

    private void captureVaultScreenshot(String name) throws Exception {
        try {
            awaitJsTrue("!!document.querySelector('[data-testid=vault-unlock-passphrase]')"
                    + " && !!document.querySelector('[data-testid=vault-unlock-submit]')"
                    + " && !!document.querySelector('[data-testid=vault-unlock-cancel]')"
                    + " && !document.querySelector('[data-testid=vault-setup-cancel]')");
            awaitImeSettled(true);
            assertVaultScreenshotNegativeControls();
            captureScreenshot(name, "[data-testid=vault-unlock-gate]");
        } catch (AssertionError | Exception failure) {
            preserveVaultFailure(name, failure);
            throw failure;
        }
    }


    private boolean vaultScreenshotReadyNow() throws Exception {
        vaultNativeGeometry = null;
        vaultReadinessReason = "DOM_GEOMETRY";
        // Typed focus rejection is observed independently before DOM/IME checks: the
        // native occluder control cannot pass merely because its IME also disappeared.
        AtomicReference<Boolean> focused = new AtomicReference<>(false);
        scenario.onActivity(activity -> focused.set(activity.hasWindowFocus()
                && findWebView(activity.getWindow().getDecorView()).hasWindowFocus()));
        if (!focused.get()) { vaultReadinessReason = "WINDOW_FOCUS"; return false; }
        String encoded = evalString(vaultScreenshotGeometry());
        if (encoded == null || "null".equals(encoded)) return false;
        JSONObject geometry = new JSONObject(encoded);
        AtomicReference<Boolean> ready = new AtomicReference<>(false);
        scenario.onActivity(activity -> {
            WebView view = findWebView(activity.getWindow().getDecorView());
            WindowInsets insets = view.getRootWindowInsets();
            if (!activity.hasWindowFocus() || !view.hasWindowFocus()) { vaultReadinessReason = "WINDOW_FOCUS"; return; }
            vaultReadinessReason = "IME_OR_LAYOUT";
            if (insets == null || !insets.isVisible(WindowInsets.Type.ime()) || !imeAnimations.isEmpty()
                    || imeAnimationEnds == 0 || view.isLayoutRequested()) return;
            vaultReadinessReason = "NATIVE_INTERSECTION";
            Rect visible = new Rect(), window = new Rect();
            if (!view.getGlobalVisibleRect(visible)) return;
            view.getWindowVisibleDisplayFrame(window);
            if (!visible.intersect(window)) return;
            int[] location = new int[2];
            view.getLocationOnScreen(location);
            // WindowMetrics describes the full window even when adjustResize shrinks its content.
            // Subtracting IME from an already resized decor height would double-subtract it.
            int keyboardTop = activity.getWindowManager().getCurrentWindowMetrics().getBounds().bottom
                    - insets.getInsets(WindowInsets.Type.ime()).bottom;
            visible.bottom = Math.min(visible.bottom, keyboardTop);
            try {
                double scale = view.getWidth() / geometry.getDouble("width");
                if (!(scale > 0) || visible.isEmpty()) return;
                double viewportScale = geometry.getDouble("viewportScale");
                double offsetLeft = geometry.getDouble("offsetLeft"), offsetTop = geometry.getDouble("offsetTop");
                JSONArray boxes = geometry.getJSONArray("boxes");
                for (int i = 0; i < boxes.length(); i++) {
                    JSONArray box = boxes.getJSONArray(i);
                    double left = vaultNativeCoordinate(box.getDouble(0), offsetLeft, scale, viewportScale, location[0]);
                    double top = vaultNativeCoordinate(box.getDouble(1), offsetTop, scale, viewportScale, location[1]);
                    double right = vaultNativeCoordinate(box.getDouble(2), offsetLeft, scale, viewportScale, location[0]);
                    double bottom = vaultNativeCoordinate(box.getDouble(3), offsetTop, scale, viewportScale, location[1]);
                    if (left < visible.left || top < visible.top || right > visible.right || bottom > visible.bottom) return;
                }
                vaultNativeGeometry = visible.flattenToString() + ":" + view.getWidth() + ":" + view.getHeight()
                        + ":" + location[0] + ":" + location[1] + ":" + keyboardTop;
                vaultReadinessReason = "READY";
                ready.set(true);
            } catch (org.json.JSONException invalid) { throw new IllegalStateException(invalid); }
        });
        return ready.get();
    }

    private void awaitVaultScreenshotReady() throws Exception {
        awaitImeSettled(true);
        long deadline = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS;
        String previous = null;
        int stable = 0;
        while (SystemClock.uptimeMillis() < deadline) {
            String geometry = evalString(vaultScreenshotGeometry());
            boolean ready = vaultScreenshotReadyNow();
            String snapshot = geometry + ":" + vaultNativeGeometry;
            if (ready && geometry != null && snapshot.equals(previous)) stable++;
            else stable = 0;
            if (stable >= 3) return;
            previous = snapshot;
            Thread.sleep(100);
        }
        throw new AssertionError("vault essential controls must be stable, labelled, hit-testable and wholly inside the native visible viewport with settled IME and window focus");
    }

    /** Actual native negative controls; always restore the owned WebView and window. */
    private void assertVaultScreenshotNegativeControls() throws Exception {
        awaitVaultScreenshotReady();
        AtomicInteger originalHeight = new AtomicInteger();
        Throwable clippingFailure = null;
        try {
            scenario.onActivity(activity -> {
                WebView view = findWebView(activity.getWindow().getDecorView());
                android.view.ViewGroup.LayoutParams params = view.getLayoutParams();
                originalHeight.set(params.height);
                params.height = 1;
                view.setLayoutParams(params);
            });
            long deadline = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS;
            AtomicReference<Boolean> shortened = new AtomicReference<>(false);
            while (!shortened.get() && SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity(activity -> shortened.set(findWebView(activity.getWindow().getDecorView()).getHeight() == 1));
                Thread.sleep(50);
            }
            assertTrue("negative control must actually shorten native WebView", shortened.get());
            assertFalse("clipped essential controls must reject screenshot readiness", vaultScreenshotReadyNow());
            Log.i(TAG, "VAULT_SCREENSHOT_CLIPPED nativeHeight=1 reason=" + vaultReadinessReason + " geometry=" + evalString(vaultScreenshotGeometry()));
        } catch (AssertionError | Exception failure) {
            clippingFailure = failure;
            preserveVaultFailure("shared-picker-signed-in-locked.png", failure);
            throw failure;
        } finally {
            try { scenario.onActivity(activity -> {
                WebView view = findWebView(activity.getWindow().getDecorView());
                android.view.ViewGroup.LayoutParams params = view.getLayoutParams();
                params.height = originalHeight.get();
                view.setLayoutParams(params);
            }); } catch (AssertionError | Exception failure) {
                if (clippingFailure != null) clippingFailure.addSuppressed(failure);
                else { preserveVaultFailure("shared-picker-signed-in-locked.png", failure); throw failure; }
            }
        }
        tapDomCenter("[data-testid=vault-unlock-passphrase]");
        awaitVaultScreenshotReady();
        AtomicReference<android.app.Dialog> occluder = new AtomicReference<>();
        Throwable occluderFailure = null;
        try {
            scenario.onActivity(activity -> {
                android.app.Dialog dialog = new android.app.Dialog(activity);
                dialog.setContentView(new View(activity));
                occluder.set(dialog);
                dialog.show();
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            AtomicReference<Boolean> lostFocus = new AtomicReference<>(false);
            long deadline = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS;
            while (!lostFocus.get() && SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity(activity -> lostFocus.set(!activity.hasWindowFocus()));
                Thread.sleep(50);
            }
            assertTrue("native dialog must actually take window focus", lostFocus.get());
            assertFalse("an occluding native window must reject screenshot readiness", vaultScreenshotReadyNow());
            assertEquals("focus must be the specific rejecting gate", "WINDOW_FOCUS", vaultReadinessReason);
            Log.i(TAG, "VAULT_SCREENSHOT_OCCLUDED activityFocus=false reason=" + vaultReadinessReason);
        } catch (AssertionError | Exception failure) {
            occluderFailure = failure;
            preserveVaultFailure("shared-picker-signed-in-locked.png", failure);
            throw failure;
        } finally {
            try { scenario.onActivity(activity -> { if (occluder.get() != null) occluder.get().dismiss(); }); }
            catch (AssertionError | Exception failure) {
                if (occluderFailure != null) occluderFailure.addSuppressed(failure);
                else { preserveVaultFailure("shared-picker-signed-in-locked.png", failure); throw failure; }
            }
        }
        restoreVaultInputWithOneTracedTap();
        Log.i(TAG, "VAULT_SCREENSHOT_NATIVE_NEGATIVES clipped=reject occluded=reject restored=ready");
    }

    /** Observe native/window/DOM stability without forcing focus, IME or viewport state. */
    private String vaultRestorationSnapshot() throws Exception {
        String encoded = evalString(vaultScreenshotGeometry());
        if (encoded == null || "null".equals(encoded)) return null;
        JSONObject state = new JSONObject(encoded);
        AtomicReference<String> snapshot = new AtomicReference<>();
        scenario.onActivity(activity -> {
            WebView view = findWebView(activity.getWindow().getDecorView());
            WindowInsets insets = view.getRootWindowInsets();
            if (!activity.hasWindowFocus() || !view.hasWindowFocus() || view.isLayoutRequested()
                    || view.getRootView().isLayoutRequested() || !imeAnimations.isEmpty() || insets == null) return;
            Rect visible = new Rect(), window = new Rect();
            if (!view.getGlobalVisibleRect(visible)) return;
            view.getWindowVisibleDisplayFrame(window);
            if (!visible.intersect(window)) return;
            int[] location = new int[2];
            view.getLocationOnScreen(location);
            int imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom;
            int keyboardTop = activity.getWindowManager().getCurrentWindowMetrics().getBounds().bottom - imeBottom;
            visible.bottom = Math.min(visible.bottom, keyboardTop);
            try {
                double ratio = view.getWidth() / state.getDouble("width"), zoom = state.getDouble("viewportScale");
                JSONArray boxes = state.getJSONArray("boxes");
                for (int i = 0; i < boxes.length(); i++) {
                    JSONArray box = boxes.getJSONArray(i);
                    double left = vaultNativeCoordinate(box.getDouble(0), state.getDouble("offsetLeft"), ratio, zoom, location[0]);
                    double top = vaultNativeCoordinate(box.getDouble(1), state.getDouble("offsetTop"), ratio, zoom, location[1]);
                    double right = vaultNativeCoordinate(box.getDouble(2), state.getDouble("offsetLeft"), ratio, zoom, location[0]);
                    double bottom = vaultNativeCoordinate(box.getDouble(3), state.getDouble("offsetTop"), ratio, zoom, location[1]);
                    if (left < visible.left || top < visible.top || right > visible.right || bottom > visible.bottom) return;
                }
                state.put("nativeX", location[0]).put("nativeY", location[1]).put("nativeWidth", view.getWidth())
                        .put("nativeHeight", view.getHeight()).put("nativeVisible", visible.flattenToString())
                        .put("windowBounds", activity.getWindowManager().getCurrentWindowMetrics().getBounds().flattenToString())
                        .put("imeVisible", insets.isVisible(WindowInsets.Type.ime())).put("imeBottom", imeBottom)
                        .put("activityFocus", activity.hasWindowFocus()).put("webViewFocus", view.hasWindowFocus())
                        .put("rootFocus", view.getRootView().hasWindowFocus());
                snapshot.set(state.toString());
            } catch (org.json.JSONException invalid) { throw new IllegalStateException(invalid); }
        });
        return snapshot.get();
    }

    private void restoreVaultInputWithOneTracedTap() throws Exception {
        long deadline = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS;
        String previous = null, stableSnapshot = null;
        ScreenshotGuard.Frame before = null;
        int stable = 0;
        while (SystemClock.uptimeMillis() < deadline) {
            String current = vaultRestorationSnapshot();
            stable = current != null && current.equals(previous) ? stable + 1 : 0;
            previous = current;
            if (stable >= 3) {
                before = renderedScreenshot("vault-dialog-restoration-before-tap");
                if (current.equals(vaultRestorationSnapshot())) { stableSnapshot = current; break; }
                stable = 0;
            }
            Thread.sleep(100); // Poll observed layout/animation state; never tap on a timer.
        }
        assertNotNull("Dialog restoration must stabilize real window, IME, layout and essential control bounds before its sole tap", stableSnapshot);
        writeScreenshot("shared-picker-dialog-restore-before-tap.png", before.bytes);
        JSONObject snapshot = new JSONObject(stableSnapshot);
        JSONArray input = snapshot.getJSONArray("boxes").getJSONArray(0);
        double ratio = snapshot.getDouble("nativeWidth") / snapshot.getDouble("width");
        float x = (float) vaultNativeCoordinate((input.getDouble(0) + input.getDouble(2)) / 2,
                snapshot.getDouble("offsetLeft"), ratio, snapshot.getDouble("viewportScale"), snapshot.getInt("nativeX"));
        float y = (float) vaultNativeCoordinate((input.getDouble(1) + input.getDouble(3)) / 2,
                snapshot.getDouble("offsetTop"), ratio, snapshot.getDouble("viewportScale"), snapshot.getInt("nativeY"));
        evalRaw("(() => {const input=document.querySelector('[data-testid=vault-unlock-passphrase]');"
                + "window.__vaultRestoreAbort?.abort();const abort=window.__vaultRestoreAbort=new AbortController();"
                + "const trace=window.__vaultRestoreTap={events:[],focusedBefore:document.activeElement===input};"
                + "for(const type of ['pointerdown','pointerup','click','focusin'])document.addEventListener(type,e=>{"
                + "if(trace.events.length<8)trace.events.push({type,input:e.target===input,trusted:e.isTrusted,x:e.clientX??null,y:e.clientY??null});"
                + "},{capture:true,signal:abort.signal});return 'armed';})()");
        Log.i(TAG, "VAULT_RESTORE_TAP_BEFORE " + snapshot.put("tapNativeX", x).put("tapNativeY", y)
                .put("frameDigest", Base64.getEncoder().encodeToString(before.contentDigest)));
        try {
            PhysicalTap.Result tap = PhysicalTap.tap(x, y); // Exactly ONE physical input tap, no retries or focus/IME injection.
            Log.i(TAG, "VAULT_RESTORE_TAP_DISPATCH down=" + tap.downInjected + " up=" + tap.upInjected
                    + " downTime=" + tap.downTime + " upTime=" + tap.upEventTime);
            awaitJsTrue("document.activeElement===document.querySelector('[data-testid=vault-unlock-passphrase]')");
            awaitJsTrue("(() => {const e=window.__vaultRestoreTap.events.filter(e=>e.type!=='focusin');"
                    + "return ['pointerdown','pointerup','click'].every(t=>e.filter(e=>e.type===t).length===1)"
                    + "&&e.every(e=>e.input&&e.trusted);})()");
            awaitVaultScreenshotReady();
        } finally {
            // Metadata-only evidence even when the actual focus/IME restoration fails.
            try {
                AtomicReference<String> nativeAfter = new AtomicReference<>();
                scenario.onActivity(activity -> {
                    WebView view = findWebView(activity.getWindow().getDecorView());
                    WindowInsets insets = view.getRootWindowInsets();
                    Rect visible = new Rect();
                    view.getGlobalVisibleRect(visible);
                    try {
                        nativeAfter.set(new JSONObject().put("activityFocus", activity.hasWindowFocus())
                                .put("webViewFocus", view.hasWindowFocus()).put("rootFocus", view.getRootView().hasWindowFocus())
                                .put("imeVisible", insets != null && insets.isVisible(WindowInsets.Type.ime()))
                                .put("imeBottom", insets == null ? 0 : insets.getInsets(WindowInsets.Type.ime()).bottom)
                                .put("imeAnimations", imeAnimations.size()).put("layoutPending", view.isLayoutRequested())
                                .put("nativeWidth", view.getWidth()).put("nativeHeight", view.getHeight())
                                .put("nativeVisible", visible.flattenToString()).toString());
                    } catch (org.json.JSONException invalid) { throw new IllegalStateException(invalid); }
                });
                Log.i(TAG, "VAULT_RESTORE_NATIVE_AFTER " + nativeAfter.get());
                ScreenshotGuard.Frame after = renderedScreenshot("vault-dialog-restoration-after-tap");
                writeScreenshot("shared-picker-dialog-restore-after-tap.png", after.bytes);
                Log.i(TAG, "VAULT_RESTORE_FRAME_AFTER " + Base64.getEncoder().encodeToString(after.contentDigest));
            } catch (Throwable diagnosticFailure) { Log.w(TAG, "restoration native trace unavailable: " + diagnosticFailure.getClass().getSimpleName()); }
            try { Log.i(TAG, "VAULT_RESTORE_TAP_AFTER " + evalString("JSON.stringify({trace:window.__vaultRestoreTap,inputFocused:document.activeElement===document.querySelector('[data-testid=vault-unlock-passphrase]')})")); }
            catch (Throwable diagnosticFailure) { Log.w(TAG, "restoration DOM trace unavailable: " + diagnosticFailure.getClass().getSimpleName()); }
            try { evalRaw("window.__vaultRestoreAbort?.abort(); 'stopped'"); }
            catch (Throwable ignored) { /* Preserve any original focus/IME failure. */ }
        }
    }

    private void captureScreenshot(String name, String markerSelector) throws Exception {
        try {
            if ("[data-testid=vault-unlock-gate]".equals(markerSelector)) {
                awaitVaultScreenshotReady();
            } else awaitJsTrue("(() => {const node = document.querySelector(" + JSONObject.quote(markerSelector) + ");"
                    + " if (!node) return false; node.scrollIntoView({block:'nearest',behavior:'instant'});"
                    + " const r = node.getBoundingClientRect(); return r.height > 0 && r.top >= 0 && r.bottom <= innerHeight;})()");
        } catch (AssertionError | Exception failure) {
            try { captureViewportFailure(name); }
            catch (Throwable diagnosticFailure) { Log.w(TAG, "viewport failure capture unavailable: " + diagnosticFailure.getClass().getSimpleName()); }
            throw failure;
        }
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

    /** Evidence only: preserve the original failing viewport assertion and native frame. */
    private void captureViewportFailure(String name) throws Exception {
        // Capture native pixels first, even if the later JS diagnostic bridge fails.
        writeScreenshot(name, renderedScreenshot(name).bytes);
        JSONObject evidence = new JSONObject(evalString("JSON.stringify((() => {"
                + "const rect=n=>n?{x:n.getBoundingClientRect().x,y:n.getBoundingClientRect().y,width:n.getBoundingClientRect().width,height:n.getBoundingClientRect().height,top:n.getBoundingClientRect().top,bottom:n.getBoundingClientRect().bottom}:null;"
                + "const hit=n=>{if(!n)return false;const r=n.getBoundingClientRect();return n.contains(document.elementFromPoint(r.x+r.width/2,r.y+r.height/2));};"
                + "const input=document.querySelector('[data-testid=vault-unlock-passphrase]'),submit=document.querySelector('[data-testid=vault-unlock-submit]'),cancel=document.querySelector('[data-testid=vault-unlock-cancel]'),v=visualViewport;"
                + "return {innerWidth,innerHeight,visualViewport:v?{width:v.width,height:v.height,offsetTop:v.offsetTop,offsetLeft:v.offsetLeft,scale:v.scale}:null,scrim:rect(document.querySelector('[data-testid=vault-unlock-gate]')),dialog:rect(document.querySelector('[data-testid=vault-unlock-gate] [role=dialog]')),input:rect(input),submit:rect(submit),cancel:rect(cancel),inputFocused:document.activeElement===input,inputHit:hit(input),submitHit:hit(submit),cancelHit:hit(cancel)};"
                + "})())"));
        scenario.onActivity(activity -> {
            WebView view = findWebView(activity.getWindow().getDecorView());
            Rect visible = new Rect();
            boolean shown = view.getGlobalVisibleRect(visible);
            WindowInsets insets = view.getRootWindowInsets();
            try {
                evidence.put("nativeWidth", view.getWidth()).put("nativeHeight", view.getHeight())
                        .put("nativeShown", shown).put("nativeVisibleRect", new JSONArray().put(visible.left).put(visible.top).put(visible.right).put(visible.bottom))
                        .put("imeVisible", insets != null && insets.isVisible(WindowInsets.Type.ime()))
                        .put("imeBottom", insets == null ? 0 : insets.getInsets(WindowInsets.Type.ime()).bottom)
                        .put("activityFocus", activity.hasWindowFocus()).put("webViewWindowFocus", view.hasWindowFocus())
                        .put("webViewInputFocus", view.hasFocus()).put("rootFocus", view.getRootView().hasWindowFocus())
                        .put("imeAnimations", imeAnimations.size()).put("imeAnimationEnds", imeAnimationEnds)
                        .put("layoutPending", view.isLayoutRequested())
                        .put("systemBars", insets == null ? "missing" : insets.getInsets(WindowInsets.Type.systemBars()).toString());
            } catch (org.json.JSONException invalid) { throw new IllegalStateException(invalid); }
        });
        Log.i(TAG, "PICKER_VIEWPORT_FAILURE " + evidence);
        // Same case-specific native artifact is pulled by the normal runner.
        // The original viewport failure is still rethrown by captureScreenshot.
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
        media.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PocketShell/JsAccountPicker/" + runId);
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
        String state = evalRaw("JSON.stringify({route: document.querySelector('.app-shell')?.dataset.route,"
                + " message: document.querySelector('[data-testid=account-sync-message]')?.textContent ?? null,"
                + " screen: (document.querySelector('#app')?.innerText ?? '').slice(0, 600)})");
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
