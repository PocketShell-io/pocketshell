package com.pocketshell.app.smoke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
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
import com.pocketshell.app.SyncAuthException;

import org.json.JSONArray;
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
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Issue #3020: Google sign-in and settings sync on the packaged APK.
 *
 * <p>Only the two edges a CI emulator cannot reach are faked, both installed
 * through {@link GoogleSyncEnvironment} before the activity starts: Google's
 * account chooser (a scripted ID token) and the sync API (an in-process
 * backend holding an encrypted slot). Everything between them is production:
 * the Capacitor plugin, the encrypted token store, the WebView's PBKDF2/AES-GCM
 * envelope, core's runSyncRound, the Account screen, and the home host picker.
 *
 * <p>The account starts with an envelope the DESKTOP client wrote; the phone
 * must read it, show its hosts, merge its own host in, survive another client
 * writing mid-sync, keep that client's desktop-only fields, and never expose
 * the ID token to the WebView, its storage, or logcat.
 */
@RunWith(AndroidJUnit4.class)
public final class AccountSyncJourneyTest {
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

    /** Written by the desktop client's SyncCrypto.ts (see tests/unit/syncCrypto.test.ts). */
    private static final String DESKTOP_ENVELOPE = "{\"v\":1,\"kdf\":\"pbkdf2-sha256\",\"iter\":600000,"
            + "\"salt\":\"pqH7wTMSdf45pMlYo0Tfpw==\",\"iv\":\"/hOH2hHO8BXuAVIP\",\"ct\":\"o4m6kdyYvsiPjWDpp4ZihqDTmD9HAutBJLPbatZiS7rw4hGb8yQRd8Oygu"
            + "M5BTorSLja73RKmMbodf0qDOEVFFsdmGKMdqi4QKcQEr1l1ma2XwlQvCL9Bsko08gvH6FGFCdEW5id4dlT95pCu0AN"
            + "yLaR8zftF3v498aBuI/HvqAAAKVmwyQHrtF+GEDV/MwZnRn8KmHjxElOtsegXqZ5MA+48hIhZzMecMwBNLNVz/4=\"}";

    private ActivityScenario<MainActivity> scenario;
    private String runId;
    private byte[] lastScreenshot;
    private FakeSyncBackend backend;
    private FakeGoogle google;

    @Before
    public void installFakeEdges() {
        runId = InstrumentationRegistry.getArguments().getString("screenshotRunId", "js3020-" + System.currentTimeMillis());
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
    public void googleSignInSyncsDesktopHostsKeepsCrossClientFieldsAndSignOutClearsToken() throws Exception {
        assertTrue("packaged account journey runs on API 35+", Build.VERSION.SDK_INT >= 35);
        backend.seed(DESKTOP_ENVELOPE, 3);
        launchWithLocalHosts(new JSONArray()
                .put(savedHost("hetzner", "hetzner.phone.lan", 22, "alexey"))
                .put(savedHost("phone-box", "192.168.1.9", 2200, "me")));

        openAccountScreen();
        awaitJsTrue("document.querySelector('[data-testid=account-sync-status]')?.dataset.signedIn === 'false'"
                + " && !!document.querySelector('[data-testid=account-sign-in]:not([disabled])')");
        captureScreenshot("account-signed-out.png", "#account-settings-title");
        int baselineEntries = authPrefsEntryCount();

        tapDomCenter("[data-testid=account-sign-in]");
        awaitJsTrue("document.querySelector('[data-testid=account-sync-email]')?.textContent.includes("
                + JSONObject.quote(EMAIL) + ") === true");
        assertEquals("one interactive account chooser", 1, google.interactive.get());
        assertEquals("the token is stored as exactly one encrypted entry", baselineEntries + 1, authPrefsEntryCount());
        String prefsXml = authPrefsXml();
        assertFalse("the token is encrypted at rest", prefsXml.contains(TOKEN_SIGNATURE));
        assertFalse("the token payload is encrypted at rest", prefsXml.contains(EMAIL));
        assertNoTokenInWebView("after sign-in");
        awaitJsTrue("!!document.querySelector('[data-testid=account-sync-host-phone-box]')");
        captureScreenshot("account-signed-in.png", "[data-testid=account-sync-email]");

        // A wrong passphrase reads nothing and uploads nothing.
        setValue("[data-testid=account-sync-passphrase]", "not the passphrase");
        tapDomCenter("[data-testid=account-sync-now]");
        awaitSyncMessage("error");
        assertEquals("Wrong sync passphrase, or the account data is corrupted.", syncMessage());
        assertEquals("a wrong passphrase uploads nothing", 0, backend.puts.size());
        captureScreenshot("account-wrong-passphrase.png", "[data-testid=account-sync-message]");

        // Tick the phone's hosts (a local alias is never auto-selected: the
        // user decides about hosts they can see), then sync for real.
        tapDomCenter("[data-testid=account-sync-select-phone-box]");
        awaitJsTrue("document.querySelector('[data-testid=account-sync-select-phone-box]')?.checked === true");
        tapDomCenter("[data-testid=account-sync-select-hetzner]");
        awaitJsTrue("document.querySelector('[data-testid=account-sync-select-hetzner]')?.checked === true");
        setValue("[data-testid=account-sync-passphrase]", PASSPHRASE);
        tapDomCenter("[data-testid=account-sync-now]");
        awaitSyncMessageText("ok", "Synced: 3 hosts in your account.");
        assertEquals(1, backend.puts.size());
        JSONArray firstUpload = decrypt(backend.puts.get(0).data).getJSONArray("hosts");
        assertEquals(List.of("phone-box", "hetzner", "bäckerei"), names(firstUpload));
        assertEquals("the phone's own address for a shared alias wins", "hetzner.phone.lan",
                firstUpload.getJSONObject(1).getString("hostname"));
        assertEquals("the desktop-only host is carried with its port", 2222, firstUpload.getJSONObject(2).getInt("port"));
        assertEquals("the upload is based on the pulled version", 3, backend.puts.get(0).baseVersion);
        awaitJsTrue("document.querySelector('[data-testid=account-sync-host-bäckerei]')?.dataset.where === 'account'");
        captureScreenshot("account-synced.png", "[data-testid=account-sync-message]");

        // Another client writes between the phone's pull and push: a desktop
        // directive on hetzner and a new host. The phone must re-pull, keep
        // both, and still win on its own field.
        backend.concurrentWrite = encrypt(new JSONObject().put("hosts", new JSONArray()
                .put(new JSONObject().put("name", "hetzner").put("hostname", "135.181.114.209").put("port", 22)
                        .put("user", "alexey").put("proxyJump", "bastion").put("identityFile", "~/.ssh/id_ed25519")
                        .put("futureDirective", new JSONObject().put("mode", "opaque")))
                .put(new JSONObject().put("name", "bäckerei").put("hostname", "höfn.internal").put("port", 2222).put("user", "root"))
                .put(new JSONObject().put("name", "web-box").put("hostname", "web.example.org").put("port", 22).put("user", "w"))
        ).toString());
        tapDomCenter("[data-testid=account-sync-now]");
        awaitSyncMessageText("ok", "Synced: 4 hosts in your account.");
        assertEquals("one conflict, one re-based upload", 1, backend.conflicts);
        JSONArray rebased = decrypt(backend.puts.get(backend.puts.size() - 1).data).getJSONArray("hosts");
        assertEquals(List.of("phone-box", "hetzner", "bäckerei", "web-box"), names(rebased));
        JSONObject hetzner = rebased.getJSONObject(1);
        assertEquals("hetzner.phone.lan", hetzner.getString("hostname"));
        assertEquals("the other client's jump host survives the phone's sync", "bastion", hetzner.getString("proxyJump"));
        assertEquals("~/.ssh/id_ed25519", hetzner.getString("identityFile"));
        assertEquals("unknown fields survive", "opaque", hetzner.getJSONObject("futureDirective").getString("mode"));
        for (FakeSyncBackend.Request request : backend.requests) {
            assertTrue("every sync request carried the native token: " + request.path, request.authorized);
        }
        assertNoTokenInWebView("after sync");

        // Hosts from the account are on the phone's home screen.
        pressBack();
        awaitRoute("settings");
        pressBack();
        awaitRoute("home");
        awaitJsTrue("[...document.querySelectorAll('[data-testid=synced-host-select] option')].map((o) => o.value).join(',')"
                + " === ',phone-box,hetzner,bäckerei,web-box'");
        setValue("[data-testid=synced-host-select]", "web-box");
        awaitJsTrue("document.querySelector('[data-testid=ssh-host]')?.value === 'web.example.org'"
                + " && document.querySelector('[data-testid=ssh-username]')?.value === 'w'"
                + " && document.querySelector('[data-testid=ssh-port]')?.value === '22'");
        captureScreenshot("home-synced-host.png", "[data-testid=synced-host-select]");

        // Sign out removes the token and the cached account copy.
        openSettings();
        tapDomCenter("[data-testid=open-account-settings]");
        awaitRoute("settings-account");
        awaitJsTrue("!!document.querySelector('[data-testid=account-sign-out]:not([disabled])')");
        tapDomCenter("[data-testid=account-sign-out]");
        awaitJsTrue("document.querySelector('[data-testid=account-sync-status]')?.dataset.signedIn === 'false'"
                + " && document.querySelector('[data-testid=account-sync-message]')?.dataset.kind === 'ok'");
        assertEquals("sign-out deletes the encrypted token entry", baselineEntries, authPrefsEntryCount());
        assertEquals("sign-out forgets the Credential Manager account", 1, google.cleared.get());
        assertEquals("the cached account copy is gone", "null", evalRaw("localStorage.getItem('" + ACCOUNT_HOSTS_KEY + "')"));
        captureScreenshot("account-after-sign-out.png", "[data-testid=account-sync-message]");
        int requestsBefore = backend.requests.size();
        pressBack();
        awaitRoute("settings");
        pressBack();
        awaitRoute("home");
        awaitJsTrue("document.querySelector('[data-testid=synced-host-select]') === null");
        assertEquals("signed out, nothing calls the sync service", requestsBefore, backend.requests.size());

        assertNoTokenInLogcat();
        Log.i(TAG, "EVIDENCE " + new JSONObject()
                .put("requests", backend.requests.size())
                .put("uploads", backend.puts.size())
                .put("conflicts", backend.conflicts)
                .put("finalHosts", new JSONArray(names(rebased))));
    }

    @Test
    public void unavailableGoogleSignInExplainsWhichPackageNeedsRegistering() throws Exception {
        assertTrue("packaged account journey runs on API 35+", Build.VERSION.SDK_INT >= 35);
        String packageName = targetContext().getPackageName();
        google.failure = new SyncAuthException(SyncAuthException.UNAVAILABLE,
                "Google sign-in is not available for " + packageName
                        + ". Add a Google account to this phone, or register this app's Android OAuth client"
                        + " (see docs/settings-sync.md).");
        launchWithLocalHosts(new JSONArray());
        // The pre-existing route: Settings → Advanced → Account & sync.
        openSettings();
        scrollIntoView("[data-testid=open-advanced-settings]");
        tapDomCenter("[data-testid=open-advanced-settings]");
        awaitRoute("settings-advanced");
        tapDomCenter("[data-testid=open-account-sync]");
        awaitRoute("settings-account");
        awaitJsTrue("!!document.querySelector('[data-testid=account-sign-in]:not([disabled])')");
        tapDomCenter("[data-testid=account-sign-in]");
        awaitSyncMessage("error");
        String message = syncMessage();
        assertTrue("the message names the package to register: " + message, message.contains(packageName));
        assertEquals("still signed out", "false",
                evalString("document.querySelector('[data-testid=account-sync-status]')?.dataset.signedIn"));
        assertEquals("nothing reached the sync service", 0, backend.requests.size());
        captureScreenshot("account-sign-in-unavailable.png", "[data-testid=account-sync-message]");
    }

    // ---- journey steps ------------------------------------------------------

    private void launchWithLocalHosts(JSONArray hosts) throws Exception {
        scenario = ActivityScenario.launch(MainActivity.class);
        awaitJsTrue("document.querySelector('[data-testid=build-status]')?.textContent.includes('Build verified') === true", 45_000);
        // location.reload() only schedules the navigation: the old document
        // keeps answering evaluateJavascript (Build verified, Back ready,
        // Settings button present) until the new one commits, which on a
        // starved emulator takes seconds. Tag the old document so the wait
        // below can only pass on the reloaded one (#3034): without it the
        // Settings tap landed on the outgoing page and was lost.
        String staleDocument = "reload-" + SystemClock.uptimeMillis();
        evalRaw("window.__pocketshellStaleDocument = " + JSONObject.quote(staleDocument) + ";"
                + "localStorage.removeItem('" + ACCOUNT_HOSTS_KEY + "'); localStorage.removeItem('" + SHARED_SETTINGS_KEY + "');"
                + "localStorage.setItem('" + HOSTS_KEY + "', " + JSONObject.quote(hosts.toString()) + ");"
                + "location.reload(); 'reload'");
        Log.i(TAG, "document right after reload() is still the old one: "
                + evalRaw("window.__pocketshellStaleDocument === " + JSONObject.quote(staleDocument)));
        awaitJsTrue("window.__pocketshellStaleDocument !== " + JSONObject.quote(staleDocument)
                + " && document.readyState === 'complete'"
                + " && document.querySelector('[data-testid=build-status]')?.textContent.includes('Build verified') === true"
                + " && localStorage.getItem('" + ACCOUNT_HOSTS_KEY + "') === null", 45_000);
    }

    private void openSettings() throws Exception {
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.backButtonReady === 'true'"
                + " && document.querySelector('.app-shell')?.dataset.route === 'home'"
                + " && !!document.querySelector('[aria-label=Settings]')");
        tapDomCenter("[aria-label=Settings]");
        awaitRoute("settings");
    }

    private void openAccountScreen() throws Exception {
        openSettings();
        scrollIntoView("[data-testid=open-account-settings]");
        tapDomCenter("[data-testid=open-account-settings]");
        awaitRoute("settings-account");
        awaitJsTrue("!!document.querySelector('#account-settings-title')");
    }

    private void awaitSyncMessage(String kind) throws Exception {
        awaitJsTrue("document.querySelector('[data-testid=account-settings-screen]')?.dataset.syncBusy === 'idle'"
                + " && document.querySelector('[data-testid=account-sync-message]')?.dataset.kind === " + JSONObject.quote(kind),
                SYNC_TIMEOUT_MILLIS);
    }

    private void awaitSyncMessageText(String kind, String text) throws Exception {
        awaitJsTrue("document.querySelector('[data-testid=account-settings-screen]')?.dataset.syncBusy === 'idle'"
                + " && document.querySelector('[data-testid=account-sync-message]')?.dataset.kind === " + JSONObject.quote(kind)
                + " && document.querySelector('[data-testid=account-sync-message]')?.textContent.trim() === " + JSONObject.quote(text),
                SYNC_TIMEOUT_MILLIS);
    }

    private String syncMessage() throws Exception {
        return evalString("document.querySelector('[data-testid=account-sync-message]')?.textContent.trim()");
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

    private File authPrefsFile() {
        return new File(targetContext().getDataDir(), "shared_prefs/" + AUTH_PREFS + ".xml");
    }

    private String authPrefsXml() throws Exception {
        File file = authPrefsFile();
        return file.isFile() ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) : "";
    }

    /** Entries in the encrypted prefs file, keysets included (written synchronously by commit()). */
    private int authPrefsEntryCount() throws Exception {
        Matcher matcher = Pattern.compile("<string name=").matcher(authPrefsXml());
        int count = 0;
        while (matcher.find()) count += 1;
        return count;
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

    // ---- independent crypto oracle (javax.crypto, not the WebView) -----------

    private static JSONObject decrypt(String envelope) throws Exception {
        JSONObject fields = new JSONObject(envelope);
        assertEquals(1, fields.getInt("v"));
        assertEquals("pbkdf2-sha256", fields.getString("kdf"));
        assertEquals("the phone writes the desktop's full-strength KDF", 600_000, fields.getInt("iter"));
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
        // ASCII passphrase: every provider's char-to-byte conversion agrees.
        byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(new PBEKeySpec(PASSPHRASE.toCharArray(), salt, iterations, 256)).getEncoded();
        return new SecretKeySpec(key, "AES");
    }

    // ---- fake edges ----------------------------------------------------------

    /** Google's account chooser: a scripted ID token for one account. */
    static final class FakeGoogle implements GoogleSyncSession.SignInProvider {
        final AtomicInteger interactive = new AtomicInteger();
        final AtomicInteger cleared = new AtomicInteger();
        final String token;
        final String payloadSegment;
        volatile SyncAuthException failure;

        FakeGoogle() {
            long exp = System.currentTimeMillis() / 1000L + 3600L;
            payloadSegment = b64url("{\"sub\":\"journey-sub-3020\",\"email\":\"" + EMAIL + "\",\"exp\":" + exp + "}");
            token = b64url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}") + "." + payloadSegment + "." + TOKEN_SIGNATURE;
        }

        @Override
        public String obtainIdToken(boolean interactiveRequest) throws SyncAuthException {
            if (interactiveRequest) interactive.incrementAndGet();
            if (failure != null) throw failure;
            return token;
        }

        @Override
        public void clearCredentialState() {
            cleared.incrementAndGet();
        }

        private static String b64url(String json) {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * The pocketshell-sync API: one encrypted slot with an optimistic version,
     * the JWT authorizer reduced to "the Bearer token is the one Google
     * issued", and an optional other-client write landing just before a PUT.
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
        volatile String concurrentWrite;
        volatile int conflicts;
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
                if (concurrentWrite != null) {
                    data = concurrentWrite;
                    version += 1;
                    concurrentWrite = null;
                }
                if (put.getInt("version") != version) {
                    conflicts += 1;
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
        media.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PocketShell/JsAccountSync/" + runId);
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
                + " message: document.querySelector('[data-testid=account-sync-message]')?.textContent ?? null})");
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
