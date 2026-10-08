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
import android.view.KeyEvent;
import android.view.View;
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
    private byte[] lastScreenshot;
    private FakeSyncBackend backend;
    private FakeGoogle google;

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
    public void sharedPickerListsAccountHostsAfterSignInAndUnlockFromTheHeaderButton() throws Exception {
        assertTrue("packaged account journey runs on API 35+", Build.VERSION.SDK_INT >= 35);
        backend.seed(encrypt(desktopAccount().toString()), 3);
        // The phone has its own hetzner (as a migrated 0.5.x host would be): the
        // account must keep it, with the desktop's fields, through a phone sync.
        launchSharedWithLocalHosts(new JSONArray()
                .put(savedHost("phone-box", "192.168.1.9", 2200, "me"))
                .put(savedHost("hetzner", "hetzner.phone.lan", 22, "alexey")));

        // Signed out: one plain list and the account button in the header actions.
        awaitJsTrue("document.querySelector('.picker .header-actions .account-action')?.textContent.includes('Sign in') === true"
                + " && !document.querySelector('.picker .account-action.signed-in')"
                + " && [...document.querySelectorAll('.picker .host-name')].map((n) => n.textContent.trim()).join(',') === 'phone-box,hetzner'"
                + " && !document.querySelector('.picker .group-label')");
        assertAccountButtonIsTouchSized();
        captureScreenshot("shared-picker-signed-out.png", ".picker .account-action");

        // The header button is the way in: Account & sync, then Google sign-in.
        tapDomCenter(".picker .header-actions .account-action");
        awaitJsTrue("!!document.querySelector('[data-testid=android-account] .account-login .account-primary:not([disabled])')");
        tapDomCenter("[data-testid=android-account] .account-login .account-primary");
        awaitJsTrue("document.querySelector('[data-testid=android-account] .account-identity')?.textContent.includes("
                + JSONObject.quote(EMAIL) + ") === true");
        assertEquals("one interactive account chooser", 1, google.interactive.get());
        backToPicker();

        // Signed in, still locked: the header shows the account, the list says how to reveal its hosts.
        awaitJsTrue("document.querySelector('.picker .account-action.signed-in')?.textContent.includes(" + JSONObject.quote(EMAIL) + ") === true"
                + " && !!document.querySelector('.picker .locked-note')");
        captureScreenshot("shared-picker-signed-in-locked.png", ".picker .locked-note");

        // Unlock with the passphrase (Check account): nothing is uploaded, and
        // the phone's hetzner is kept for the account, not marked for removal.
        tapDomCenter(".picker .locked-note .btn-ghost");
        awaitJsTrue("!!document.querySelector('[data-testid=android-account] .passphrase-field input:not([disabled])')");
        setValue("[data-testid=android-account] .passphrase-field input", PASSPHRASE);
        tapDomCenter("[data-testid=android-account] .host-heading .btn-ghost");
        awaitJsTrue("document.querySelector('[data-testid=android-account] .account-message')?.textContent.trim()"
                + " === 'Your account has 2 synced hosts.'", SYNC_TIMEOUT_MILLIS);
        String hetznerRow = "[...document.querySelectorAll('[data-testid=android-account] .account-host-row')]"
                + ".find((row) => row.querySelector('.host-alias')?.textContent.trim() === 'hetzner')";
        awaitJsTrue("(() => {const row = " + hetznerRow + "; return !!row && row.querySelector('input').checked"
                + " && row.querySelector('.status-chip').textContent.trim() === 'In account';})()");
        assertEquals("unlocking uploads nothing", 0, backend.puts.size());
        backToPicker();

        // Home now lists the account's hosts this phone does not have, beside the phone's own.
        String rows = "[...document.querySelectorAll('.picker .host-list')].map((list) => [...list.querySelectorAll('.host-name')]"
                + ".map((n) => n.textContent.trim()).join(',')).join('|')";
        awaitJsTrue(rows + " === 'bäckerei|phone-box,hetzner'"
                + " && [...document.querySelectorAll('.picker .group-label')].map((n) => n.textContent.trim()).join('|')"
                + " === 'From your account|On this phone'"
                + " && !document.querySelector('.picker .locked-note')");
        assertTrue("the account rows show the account's address",
                evalString("document.querySelector('.picker .host-list .host-detail')?.textContent.trim()").contains("root@höfn.internal:2222"));
        assertNoTokenInWebView("after unlock");
        assertEquals("the decrypted account copy never reaches WebView storage", "null",
                evalRaw("localStorage.getItem('" + ACCOUNT_HOSTS_KEY + "')"));
        assertFalse("no decrypted account address in WebView storage",
                evalString("JSON.stringify(Object.entries(localStorage))").contains("höfn.internal"));
        captureScreenshot("shared-picker-account-hosts.png", ".picker .group-label");
        String accountRows = evalString("JSON.stringify([...document.querySelector('.picker .host-list')"
                + ".querySelectorAll('.host-name')].map((n) => n.textContent.trim()))");

        // An account host with no key on this phone asks for the phone's key.
        tapDomCenter(".picker .host-list .host-item:nth-child(1) .host-row");
        awaitJsTrue("document.querySelector('[data-testid=account-host-key] #account-host-key-title')?.textContent.trim()"
                + " === 'Choose a key for bäckerei'"
                + " && document.querySelector('[data-testid=account-host-key-reason]')?.dataset.reason === 'account'"
                + " && document.querySelector('[data-testid=account-host-key-user]')?.value === 'root'");
        captureScreenshot("shared-account-host-key-prompt.png", "[data-testid=account-host-key-connect]");
        tapDomCenter("[data-testid=account-host-key-cancel]");
        awaitJsTrue("!document.querySelector('[data-testid=account-host-key-gate]')"
                + " && document.querySelector('.picker p.error')?.textContent.trim()"
                + " === 'No SSH key was chosen for “bäckerei” on this phone, so nothing was dialled.'");

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
        assertNoTokenInLogcat();
        Log.i(TAG, "SHARED_PICKER " + new JSONObject()
                .put("requests", backend.requests.size()).put("uploads", backend.puts.size())
                .put("accountRows", new JSONArray(accountRows))
                .put("syncedHosts", new JSONArray(uploadedNames))
                .put("keptDesktopFields", true));
    }

    /**
     * The legacy home (still the default shell) after an app restart: the
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

    // ---- journey steps ------------------------------------------------------

    private void launchSharedWithLocalHosts(JSONArray hosts) throws Exception {
        scenario = ActivityScenario.launch(SharedShellLaunch.intent());
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
        scenario = ActivityScenario.launch(MainActivity.class);
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

    private void captureScreenshot(String name, String markerSelector) throws Exception {
        awaitJsTrue("(() => {const node = document.querySelector(" + JSONObject.quote(markerSelector) + ");"
                + " if (!node) return false; node.scrollIntoView({block:'nearest',behavior:'instant'});"
                + " const r = node.getBoundingClientRect(); return r.height > 0 && r.top >= 0 && r.bottom <= innerHeight;})()");
        byte[] bytes = null;
        for (int attempt = 0; attempt < 5; attempt += 1) {
            bytes = renderedScreenshot(name);
            if (lastScreenshot == null || !java.util.Arrays.equals(bytes, lastScreenshot)) break;
            bytes = null;
            Thread.sleep(400);
        }
        assertNotNull("screenshot " + name + " stayed byte-identical to the previous capture", bytes);
        lastScreenshot = bytes;
        writeScreenshot(name, bytes);
    }

    private byte[] renderedScreenshot(String name) throws Exception {
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
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, encoded));
        bitmap.recycle();
        byte[] bytes = encoded.toByteArray();
        assertTrue("screenshot must contain rendered UI: " + name, bytes.length > 1024);
        return bytes;
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
