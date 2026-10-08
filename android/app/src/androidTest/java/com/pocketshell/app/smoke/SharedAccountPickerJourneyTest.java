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
 * Issue #3063: the shared app's host picker with a Google account, on the
 * packaged APK. The same two fake edges as {@link AccountSyncJourneyTest}
 * (Google's account chooser and the sync API, installed through
 * {@link GoogleSyncEnvironment}); everything between them is production: the
 * GoogleSync plugin, the encrypted token store, the WebView envelope crypto,
 * the shared HostPickerView and AccountView, and the Android platform's sync
 * group, account route and account-host key prompt.
 *
 * <p>The account holds an envelope the DESKTOP client wrote. Signing in from
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
        backend.seed(DESKTOP_ENVELOPE, 3);
        launchSharedWithLocalHosts(new JSONArray().put(savedHost("phone-box", "192.168.1.9", 2200, "me")));

        // Signed out: one plain list and the account button in the header actions.
        awaitJsTrue("document.querySelector('.picker .header-actions .account-action')?.textContent.includes('Sign in') === true"
                + " && !document.querySelector('.picker .account-action.signed-in')"
                + " && [...document.querySelectorAll('.picker .host-name')].map((n) => n.textContent.trim()).join(',') === 'phone-box'"
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
        tapDomCenter("[data-testid=android-account-back]");

        // Signed in, still locked: the header shows the account, the list says how to reveal its hosts.
        awaitJsTrue("document.querySelector('.picker .account-action.signed-in')?.textContent.includes(" + JSONObject.quote(EMAIL) + ") === true"
                + " && !!document.querySelector('.picker .locked-note')");
        captureScreenshot("shared-picker-signed-in-locked.png", ".picker .locked-note");

        // Unlock with the passphrase (Check account): nothing is uploaded.
        tapDomCenter(".picker .locked-note .btn-ghost");
        awaitJsTrue("!!document.querySelector('[data-testid=android-account] .passphrase-field input:not([disabled])')");
        setValue("[data-testid=android-account] .passphrase-field input", PASSPHRASE);
        tapDomCenter("[data-testid=android-account] .host-heading .btn-ghost");
        awaitJsTrue("document.querySelector('[data-testid=android-account] .account-message')?.textContent.trim()"
                + " === 'Your account has 2 synced hosts.'", SYNC_TIMEOUT_MILLIS);
        tapDomCenter("[data-testid=android-account-back]");

        // Home now lists every account host as a row, beside the phone's own.
        String rows = "[...document.querySelectorAll('.picker .host-list')].map((list) => [...list.querySelectorAll('.host-name')]"
                + ".map((n) => n.textContent.trim()).join(',')).join('|')";
        awaitJsTrue(rows + " === 'hetzner,bäckerei|phone-box'"
                + " && [...document.querySelectorAll('.picker .group-label')].map((n) => n.textContent.trim()).join('|')"
                + " === 'From your account|On this phone'"
                + " && !document.querySelector('.picker .locked-note')");
        assertTrue("the account rows show the account's address",
                evalString("document.querySelector('.picker .host-list .host-detail')?.textContent.trim()").contains("alexey@135.181.114.209:22"));
        assertEquals("unlocking uploads nothing", 0, backend.puts.size());
        assertNoTokenInWebView("after unlock");
        assertEquals("the decrypted account copy never reaches WebView storage", "null",
                evalRaw("localStorage.getItem('" + ACCOUNT_HOSTS_KEY + "')"));
        assertFalse("no decrypted account address in WebView storage",
                evalString("JSON.stringify(Object.entries(localStorage))").contains("höfn.internal"));
        captureScreenshot("shared-picker-account-hosts.png", ".picker .group-label");

        // An account host with no key on this phone asks for the phone's key.
        tapDomCenter(".picker .host-list .host-item:nth-child(2) .host-row");
        awaitJsTrue("document.querySelector('[data-testid=account-host-key] #account-host-key-title')?.textContent.trim()"
                + " === 'Choose a key for bäckerei'"
                + " && document.querySelector('[data-testid=account-host-key-user]')?.value === 'root'");
        captureScreenshot("shared-account-host-key-prompt.png", "[data-testid=account-host-key-connect]");
        tapDomCenter("[data-testid=account-host-key-cancel]");
        awaitJsTrue("!document.querySelector('[data-testid=account-host-key-gate]')"
                + " && document.querySelector('.picker p.error')?.textContent.trim()"
                + " === 'No SSH key was chosen for “bäckerei” on this phone, so nothing was dialled.'");
        assertNoTokenInLogcat();
        Log.i(TAG, "SHARED_PICKER " + new JSONObject()
                .put("requests", backend.requests.size()).put("uploads", backend.puts.size())
                .put("accountRows", new JSONArray(evalString("JSON.stringify([...document.querySelector('.picker .host-list')"
                        + ".querySelectorAll('.host-name')].map((n) => n.textContent.trim()))"))));
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
