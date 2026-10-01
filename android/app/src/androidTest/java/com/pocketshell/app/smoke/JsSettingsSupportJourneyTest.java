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
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.WebView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.pocketshell.app.MainActivity;
import com.pocketshell.app.NativeCrashRecorder;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Packaged API 35 journeys for issue #2861: J16 (support / diagnostic capture
 * and export) and J24 (settings reach with Android Back). Named apart from
 * #2897's voice-settings class to avoid the J24 file-name clash.
 */
@RunWith(AndroidJUnit4.class)
public final class JsSettingsSupportJourneyTest {
    private static final long JS_TIMEOUT_SECONDS = 15;
    private static final long WAIT_TIMEOUT_MILLIS = 15_000;
    private static final String SERVICES = "window.__ps2861PlatformServices";
    private static final String NATIVE_SEED_HOST = "nativeseed.corp.example.com";
    private static final String NATIVE_SEED_PASSWORD = "NATIVESEEDPW481";
    private static final String NATIVE_SEED_KEY = "MIIEvNATIVESEEDKEYBODY";
    private static final String RUNTIME_SEED_HOST = "prodbox.corp.example.com";
    private static final String RUNTIME_SEED_TOKEN = "ghp_SEEDEDabcdef123456";
    private static final String RUNTIME_SEED_PASSWORD = "SEEDEDPW123";
    private static final String[] NATIVE_SEEDS = {NATIVE_SEED_HOST, NATIVE_SEED_PASSWORD, NATIVE_SEED_KEY};
    private static final String[] ALL_SEEDS = {NATIVE_SEED_HOST, NATIVE_SEED_PASSWORD, NATIVE_SEED_KEY,
            RUNTIME_SEED_HOST, RUNTIME_SEED_TOKEN, RUNTIME_SEED_PASSWORD, "support-journey"};
    private static final String SHARED_SETTINGS_KEY = "pocketshell.settings.v1";

    private ActivityScenario<MainActivity> scenario;
    private String runId;
    private byte[] lastScreenshot;

    @Before
    public void launchPackagedShell() {
        runId = InstrumentationRegistry.getArguments().getString("screenshotRunId", "js2861-" + System.currentTimeMillis());
        assertTrue("screenshot run id must be path safe", runId.matches("[A-Za-z0-9][A-Za-z0-9_-]{2,48}"));
        clearNativeCrashReports();
        scenario = ActivityScenario.launch(MainActivity.class);
    }

    @After
    public void closeShell() {
        if (scenario != null) {
            try {
                // Each method starts from default settings on this per-worktree test install.
                evalRaw("localStorage.removeItem(" + JSONObject.quote(SHARED_SETTINGS_KEY) + "); 'reset'");
            } catch (Throwable ignored) {
                // The activity may already be gone after a failure.
            }
            scenario.close();
        }
        clearNativeCrashReports();
    }

    /** J16: native crash, uncaught JS errors and a real SSH bridge failure are captured, reviewed, deleted and exported. */
    @Test
    public void j16SupportReportsCaptureNativeRuntimeAndSshFailuresAndExport() throws Exception {
        assertTrue("packaged support journey runs on API 35+", Build.VERSION.SDK_INT >= 35);
        awaitBuildVerified();

        long reloadRequestedAt = System.currentTimeMillis();
        // Request the probe in the navigation URL itself: nothing to persist
        // across the reload, so the reloaded page cannot miss the request.
        evalRaw("location.replace(location.origin + location.pathname + '?ps2861Probe=1'); 'reload'");
        AssertionError probeTimeout = null;
        try {
            awaitJsTrue("typeof " + SERVICES + "?.diagnostics?.list === 'function'", 45_000);
        } catch (AssertionError error) {
            probeTimeout = error;
        }
        // Name the step the probe stopped at: did the key survive the reload,
        // did a new page run main.ts (installedAt), did getInfo answer?
        String probe = evalString("JSON.stringify({search: location.search,"
                + " state: window.__ps2861ProbeState ?? null, readyState: document.readyState,"
                + " route: document.querySelector('.app-shell')?.dataset.route ?? null,"
                + " build: document.querySelector('[data-testid=build-status]')?.textContent.trim() ?? null,"
                + " exposed: typeof " + SERVICES + ", now: Date.now()})");
        android.util.Log.i("PocketshellSettingsEvidence", "PROBE reloadRequestedAt=" + reloadRequestedAt + " " + probe);
        if (probeTimeout != null) {
            throw new AssertionError("diagnostics probe never appeared; reloadRequestedAt=" + reloadRequestedAt
                    + " observed=" + probe, probeTimeout);
        }
        awaitBuildVerified();
        evalAsync(SERVICES + ".diagnostics.clear()");
        awaitReports(0);

        // Native crash seeded with a named host, a PKCS#8 block and a password in
        // the exception and cause messages: the Java recorder must write none of it.
        File crash = NativeCrashRecorder.record(targetContext(), Thread.currentThread(),
                new IllegalStateException("password=" + NATIVE_SEED_PASSWORD + " -----BEGIN PRIVATE KEY-----\n"
                        + NATIVE_SEED_KEY + "\n-----END PRIVATE KEY-----",
                        new java.net.UnknownHostException("Unable to resolve host \"" + NATIVE_SEED_HOST + "\"")));
        assertNotNull("the Java recorder must write a native crash report", crash);
        assertTrue("the native crash report must exist on disk", crash.isFile());

        // WebView treats evaluateJavascript code as an opaque (muted) script: its
        // errors reach the console but never the page's error listeners. Throw
        // from a same-origin inline script instead, like real app code does.
        evalRaw("(() => {const script = document.createElement('script');"
                + "script.textContent = \"setTimeout(() => { throw new TypeError('Could not reconnect to " + RUNTIME_SEED_HOST
                + " after 5 attempts.'); }, 0);"
                + " Promise.reject(new RangeError('Authorization: Bearer " + RUNTIME_SEED_TOKEN
                + " {\\\"password\\\":\\\"" + RUNTIME_SEED_PASSWORD + "\\\"}'));\";"
                + "document.head.appendChild(script); script.remove(); return 'thrown';})()");
        awaitReports(3);

        JSONArray reports = listReports();
        String serialized = reports.toString();
        assertTrue("uncaught window error captured by class", serialized.contains("Uncaught error: TypeError"));
        assertTrue("unhandled rejection captured by class", serialized.contains("Unhandled promise rejection: RangeError"));
        assertTrue("native crash listed", serialized.contains("Native crash: IllegalStateException"));
        assertTrue("the native cause class is kept", serialized.contains("java.net.UnknownHostException"));
        for (String secret : ALL_SEEDS) {
            assertFalse("reports must be redacted: " + secret + " in " + serialized, serialized.contains(secret));
        }

        // A real native SSH bridge failure through the connection form.
        setValue("[data-testid=ssh-host]", RUNTIME_SEED_HOST);
        setValue("[data-testid=ssh-port]", "2222");
        setValue("[data-testid=ssh-username]", "support-journey");
        selectDockerKey();
        scrollIntoView("[data-testid=ssh-connect]");
        tapDomCenter("[data-testid=ssh-connect]");
        awaitJsTrue("(document.querySelector('[data-testid=ssh-message]')?.textContent.trim().length ?? 0) > 0", 45_000);
        String message = evalString("document.querySelector('[data-testid=ssh-message]').textContent.trim()");

        openSettingsFromHome();
        scrollIntoView("[data-testid=open-diagnostics]");
        tapDomCenter("[data-testid=open-diagnostics]");
        awaitRoute("diagnostics");
        awaitJsTrue("[...document.querySelectorAll('[data-testid=diagnostics-events] small')]"
                + ".some((node) => node.textContent.includes('connect'))");
        captureScreenshot("j16-diagnostics-events.png", "#diagnostics-page-title");
        scrollIntoView("[data-testid=review-diagnostics-export]");
        tapDomCenter("[data-testid=review-diagnostics-export]");
        awaitRoute("diagnostics-report");
        String export = evalString("document.querySelector('[data-testid=diagnostics-report-preview]').textContent");
        JSONObject exported = new JSONObject(export);
        boolean sshFailure = false;
        JSONArray events = exported.getJSONArray("events");
        for (int index = 0; index < events.length(); index += 1) {
            JSONObject event = events.getJSONObject(index);
            if ("connect".equals(event.getString("operation")) && event.getString("kind").startsWith("ssh-")) sshFailure = true;
        }
        assertTrue("the SSH bridge failure (" + message + ") must be in the reviewed export: " + export, sshFailure);
        assertFalse("export must not contain the host", export.contains(RUNTIME_SEED_HOST));
        assertFalse("export must not contain key material", export.contains("OPENSSH PRIVATE KEY"));
        // The screenshot must show the report screen itself, not the list it replaced.
        evalRaw("window.scrollTo(0, 0); document.querySelector('[data-testid=diagnostics-report-screen]')"
                + "?.scrollIntoView({block:'start',behavior:'instant'}); 'top'");
        awaitJsTrue("document.querySelector('[data-testid=diagnostics-screen]') === null"
                + " && (() => {const r = document.querySelector('#diagnostics-report-title')?.getBoundingClientRect();"
                + " return !!r && r.top >= 0 && r.bottom <= innerHeight;})()");
        captureScreenshot("j16-diagnostics-export-preview.png", "#diagnostics-report-title");

        String nativeId = null;
        for (int index = 0; index < reports.length(); index += 1) {
            String id = reports.getJSONObject(index).getString("id");
            if (id.startsWith("native:")) nativeId = id;
        }
        assertNotNull("native report id", nativeId);
        assertEquals("true", evalAsync(SERVICES + ".diagnostics.remove(" + JSONObject.quote(nativeId) + ")"));
        assertFalse("deleting the native report removes its file", crash.exists());
        assertEquals("false", evalAsync(SERVICES + ".diagnostics.remove(" + JSONObject.quote(nativeId) + ")"));
        assertFalse("the deleted native report is gone from the list", listReports().toString().contains(nativeId));

        int remaining = listReports().length();
        assertTrue("the two runtime reports remain after deleting the native one", remaining >= 2);
        String cleared = evalAsync(SERVICES + ".diagnostics.clear()");
        assertEquals("clear reports how many it removed", String.valueOf(remaining), cleared);
        awaitReports(0);

        // Debug builds are what we install for testing: no bridge call, plugin
        // result or console message may reach logcat with a seeded secret, a
        // host name or private-key material (capacitor.config loggingBehavior).
        // Scope to this app process: the device buffer also holds earlier runs.
        String pid = shell("pidof " + targetContext().getPackageName()).trim();
        assertTrue("the app process must be running: " + pid, pid.matches("\\d+"));
        String logcat = shell("logcat -d -v brief --pid=" + pid);
        assertTrue("same-run app logcat must be readable", !logcat.isEmpty());
        for (String secret : ALL_SEEDS) {
            assertFalse("same-run logcat must not contain seed " + secret, logcat.contains(secret));
        }
        assertFalse("same-run logcat must not contain private-key material", logcat.contains("PRIVATE KEY"));

        pressBack();
        awaitRoute("diagnostics");
        pressBack();
        awaitRoute("settings");
        pressBack();
        awaitRoute("home");
    }

    /** J24: every settings destination is reachable and Android Back returns through the stack to home. */
    @Test
    public void j24SettingsDestinationsReachableWithAndroidBackAndPersist() throws Exception {
        assertTrue("packaged settings journey runs on API 35+", Build.VERSION.SDK_INT >= 35);
        awaitBuildVerified();
        openSettingsFromHome();
        captureScreenshot("j24-settings.png", "#settings-title");

        String[][] destinations = {
                {"open-terminal-settings", "settings-terminal", "terminal-settings-title"},
                {"open-connection-settings", "settings-connections", "connection-settings-title"},
                {"open-ports", "ports", "ports-title"},
                {"open-usage", "usage", "usage-title"},
                {"open-voice-settings", "settings-voice", "voice-settings-title"},
                {"open-snippet-settings", "settings-snippets", "host-snippets-title"},
                {"open-advanced-settings", "settings-advanced", "advanced-settings-title"},
                {"open-diagnostics", "diagnostics", "diagnostics-page-title"},
                {"open-about", "about", "about-title"},
        };
        for (String[] destination : destinations) {
            String selector = "[data-testid=" + destination[0] + "]";
            scrollIntoView(selector);
            tapDomCenter(selector);
            awaitRoute(destination[1]);
            awaitJsTrue("!!document.getElementById(" + JSONObject.quote(destination[2]) + ")");
            captureScreenshot("j24-" + destination[1] + ".png", "#" + destination[2]);
            if ("settings-connections".equals(destination[1])) exerciseGraceChoices();
            if ("settings-advanced".equals(destination[1])) {
                tapDomCenter("[data-testid=open-account-sync]");
                awaitRoute("settings-account");
                pressBack();
                awaitRoute("settings-advanced");
            }
            if ("about".equals(destination[1])) {
                tapDomCenter("[data-testid=open-update-status]");
                awaitRoute("about-update");
                pressBack();
                awaitRoute("about");
            }
            pressBack();
            awaitRoute("settings");
        }
        pressBack();
        awaitRoute("home");
        awaitJsTrue("!!document.querySelector('#hosts-title')");

        scenario.recreate();
        awaitBuildVerified();
        JSONObject saved = new JSONObject(evalString("localStorage.getItem(" + JSONObject.quote(SHARED_SETTINGS_KEY) + ")"));
        assertEquals("the 10-minute grace survives activity recreation", 600_000, saved.getInt("backgroundGraceMs"));
        openSettingsFromHome();
        tapDomCenter("[data-testid=open-connection-settings]");
        awaitRoute("settings-connections");
        awaitJsTrue("document.querySelector('[data-testid=setting-background-grace]').value === '600000'");
        captureScreenshot("j24-connections-after-recreate.png", "#connection-settings-title");
        setSelect("[data-testid=setting-background-grace]", "90000");
        awaitJsTrue("JSON.parse(localStorage.getItem(" + JSONObject.quote(SHARED_SETTINGS_KEY) + ")).backgroundGraceMs === 90000");
        pressBack();
        awaitRoute("settings");
        pressBack();
        awaitRoute("home");
    }

    /**
     * Reconnect when I return: OFF. Against the Docker fixture, a live session
     * outlives the background grace; returning must NOT redial on its own, must
     * show a visible Reconnect action, and Reconnect must reattach the SAME host
     * session on a new connection. The runner checks the session ID on the host.
     */
    @Test
    public void reconnectWhenIReturnOffWaitsThenReconnectsSameSession() throws Exception {
        assertTrue("packaged lifecycle setting journey runs on API 35+", Build.VERSION.SDK_INT >= 35);
        android.os.Bundle arguments = InstrumentationRegistry.getArguments();
        String host = arguments.getString("sshHost", "10.0.2.2");
        String port = arguments.getString("sshPort");
        assertNotNull("pass the Docker fixture port with sshPort", port);
        String tag = runId + "-r";
        awaitBuildVerified();

        openSettingsFromHome();
        tapDomCenter("[data-testid=open-connection-settings]");
        awaitRoute("settings-connections");
        setSelect("[data-testid=setting-background-grace]", "30000");
        awaitJsTrue("document.querySelector('[data-testid=setting-reconnect-on-return]')?.getAttribute('aria-checked') === 'true'");
        tapDomCenter("[data-testid=setting-reconnect-on-return]");
        awaitJsTrue("(() => {const s = JSON.parse(localStorage.getItem(" + JSONObject.quote(SHARED_SETTINGS_KEY)
                + ") || '{}'); return s.backgroundGraceMs === 30000 && s.reconnectOnReturn === false;})()");
        pressBack();
        awaitRoute("settings");
        pressBack();
        awaitRoute("home");

        setValue("[data-testid=ssh-host]", host);
        setValue("[data-testid=ssh-port]", port);
        setValue("[data-testid=ssh-username]", "testuser");
        selectDockerKey();
        clickJs("[data-testid=ssh-connect]");
        awaitJsTrue("!!document.querySelector('[data-testid=host-key-decision]')", 30_000);
        clickJs("[data-testid=trust-host-key]");
        awaitJsTrue("!!document.querySelector('[data-testid=refresh-sessions]')", 30_000);
        setValue("[data-testid=new-session-name]", tag);
        clickJs("[data-testid=create-session]");
        awaitJsTrue("Array.from(document.querySelectorAll('[data-session-tag]')).some((n) => n.dataset.sessionTag === "
                + JSONObject.quote(tag) + ")", 30_000);
        clickJs("[data-session-tag=\"" + tag + "\"]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.sshPhase === 'live'"
                + " && document.querySelector('.app-shell')?.dataset.sshSelectedTag === " + JSONObject.quote(tag), 30_000);
        String sessionId = shellData("sshSelectedSessionId");
        String firstConnection = shellData("sshConnectionId");
        assertTrue("the attached host session has an aplexer ID", sessionId.matches("[a-f0-9-]{36}"));

        scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.sshPhase === 'background'");
        SystemClock.sleep(30_000 + 8_000);
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);

        awaitJsTrue("document.querySelector('.app-shell')?.dataset.sshPhase === 'lost'", 30_000);
        awaitJsTrue("!!document.querySelector('[data-testid=ssh-reconnect]')");
        SystemClock.sleep(3_000);
        assertEquals("with reconnect-on-return off the app must not redial by itself", "lost", shellData("sshPhase"));
        assertEquals("no connection while waiting for Reconnect", "", shellData("sshConnectionId"));
        assertEquals("the selected session is remembered while waiting", sessionId, shellData("sshSelectedSessionId"));
        captureScreenshot("reconnect-off-waiting.png", "[data-testid=reconnect-banner]");

        tapDomCenter("[data-testid=ssh-reconnect]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.sshPhase === 'live'", 45_000);
        String secondConnection = shellData("sshConnectionId");
        assertEquals("Reconnect reattaches the same host session", sessionId, shellData("sshSelectedSessionId"));
        assertTrue("Reconnect uses a new connection", !secondConnection.isEmpty() && !secondConnection.equals(firstConnection));
        assertFalse("the Reconnect banner goes away once live", evalRaw("!!document.querySelector('[data-testid=reconnect-banner]')").equals("true"));
        captureScreenshot("reconnect-off-resumed.png", ".session-context");
        android.util.Log.i("PocketshellSettingsEvidence", "RECONNECT " + new JSONObject()
                .put("runId", runId).put("tag", tag).put("sessionId", sessionId)
                .put("firstConnectionId", firstConnection).put("secondConnectionId", secondConnection));
    }

    private String shellData(String key) throws Exception {
        String value = evalString("document.querySelector('.app-shell')?.dataset." + key + " ?? ''");
        return value == null ? "" : value;
    }

    private void clickJs(String selector) throws Exception {
        evalString("(() => {const node=document.querySelector(" + JSONObject.quote(selector) + ");"
                + "if(!node)throw new Error('missing ' + " + JSONObject.quote(selector) + ");"
                + "node.click();return 'clicked';})()");
    }

    /**
     * The connection form takes a key from the native key vault (#2926). The
     * runner stages the Docker fixture key once under /data/local/tmp; the
     * first method that needs it moves it into the app's private cache and
     * every method imports (or reuses) it by fingerprint and selects it.
     */
    private static File stagedKeyDocument;

    private void selectDockerKey() throws Exception {
        Context context = targetContext();
        if (stagedKeyDocument == null || !stagedKeyDocument.isFile()) {
            String keyPath = InstrumentationRegistry.getArguments().getString("sshPrivateKeyPath");
            assertNotNull("pass the runner-staged fixture key path with sshPrivateKeyPath", keyPath);
            stagedKeyDocument = SshKeyVaultTestSupport.copyDockerKeyDocument(context, keyPath, runId);
        }
        evalString(SshKeyVaultTestSupport.beginImport(
                SshKeyVaultTestSupport.asContentUri(context, stagedKeyDocument), "Docker fixture key"));
        awaitJsTrue("['ready','failed'].includes(window." + SshKeyVaultTestSupport.IMPORT_RESULT + "?.state)", 30_000);
        assertEquals("the fixture key must import into the native vault",
                "ready", evalString("window." + SshKeyVaultTestSupport.IMPORT_RESULT + ".state"));
        String handle = evalString("window." + SshKeyVaultTestSupport.IMPORT_RESULT + ".handleId");
        scrollIntoView("[data-testid=open-ssh-keys]");
        clickJs("[data-testid=open-ssh-keys]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'keys'"
                + " && !!document.querySelector('[data-testid=select-ssh-key-" + handle + "]')");
        clickJs("[data-testid=select-ssh-key-" + handle + "]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'home'"
                + " && document.querySelector('[data-testid=ssh-key-selection]')?.value === " + JSONObject.quote(handle));
    }

    private void exerciseGraceChoices() throws Exception {
        JSONArray values = new JSONArray(evalString("JSON.stringify([...document.querySelectorAll("
                + "'[data-testid=setting-background-grace] option')].map((option) => option.value))"));
        assertEquals("[\"30000\",\"60000\",\"90000\",\"300000\",\"600000\"]", values.toString());
        // The phone route renders core's shared Connections group (D42), not a local copy.
        awaitJsTrue("!!document.querySelector('[data-testid=settings-group-connections] [data-testid=setting-background-grace]')"
                + " && !!document.querySelector('[data-testid=settings-group-connections] button.switch[role=switch][data-testid=setting-reconnect-on-return]')");
        setSelect("[data-testid=setting-background-grace]", "600000");
        awaitJsTrue("JSON.parse(localStorage.getItem(" + JSONObject.quote(SHARED_SETTINGS_KEY) + ") || '{}').backgroundGraceMs === 600000");
        captureScreenshot("j24-connections-ten-minutes.png", "#connection-settings-title");
        // Reconnect-when-I-return lives in the same shared store the lifecycle reads.
        awaitJsTrue("document.querySelector('[data-testid=setting-reconnect-on-return]')?.getAttribute('aria-checked') === 'true'");
        tapDomCenter("[data-testid=setting-reconnect-on-return]");
        awaitJsTrue("JSON.parse(localStorage.getItem(" + JSONObject.quote(SHARED_SETTINGS_KEY) + ") || '{}').reconnectOnReturn === false");
        captureScreenshot("j24-connections-reconnect-off.png", "#connection-settings-title");
        tapDomCenter("[data-testid=setting-reconnect-on-return]");
        awaitJsTrue("JSON.parse(localStorage.getItem(" + JSONObject.quote(SHARED_SETTINGS_KEY) + ") || '{}').reconnectOnReturn === true");
    }

    private void openSettingsFromHome() throws Exception {
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.backButtonReady === 'true'");
        tapDomCenter("[aria-label=Settings]");
        awaitRoute("settings");
        awaitJsTrue("!!document.querySelector('#settings-title')");
    }

    private void awaitBuildVerified() throws Exception {
        awaitJsTrue("document.querySelector('[data-testid=build-status]')?.textContent.includes('Build verified') === true");
    }

    private void awaitReports(int expected) throws Exception {
        long deadline = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS;
        int last = -1;
        while (SystemClock.uptimeMillis() < deadline) {
            last = listReports().length();
            if (last == expected) return;
            Thread.sleep(150);
        }
        throw new AssertionError("expected " + expected + " diagnostic reports, last saw " + last + ": " + listReports());
    }

    private JSONArray listReports() throws Exception {
        return new JSONArray(evalAsync("JSON.stringify(await " + SERVICES + ".diagnostics.list())"));
    }

    /** Evaluate an async expression and return its String() form. */
    private String evalAsync(String expression) throws Exception {
        String slot = "__ps2861Async" + SystemClock.uptimeMillis();
        evalRaw("window." + slot + " = {done:false}; (async () => { try { window." + slot
                + " = {done:true, value:String(await (async () => " + expression + ")())}; } catch (error) { window."
                + slot + " = {done:true, error:String(error)}; } })(); 'started'");
        awaitJsTrue("window." + slot + ".done === true");
        JSONObject result = new JSONObject(evalString("JSON.stringify(window." + slot + ")"));
        evalRaw("delete window." + slot + "; 'cleared'");
        assertFalse("async probe failed: " + result.optString("error"), result.has("error"));
        return result.getString("value");
    }

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

    private void setSelect(String selector, String value) throws Exception {
        setValue(selector, value);
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
        JSONObject point = new JSONObject(evalString("(() => {const element = document.querySelector("
                + JSONObject.quote(selector) + "); if (!element) return JSON.stringify({missing: true});"
                + "const rect = element.getBoundingClientRect();"
                + "return JSON.stringify({x: rect.left + rect.width / 2, y: rect.top + rect.height / 2,"
                + "top: rect.top, bottom: rect.bottom, viewportWidth: innerWidth, viewportHeight: innerHeight});})()"));
        assertFalse("WebView target element must exist: " + selector, point.optBoolean("missing"));
        assertTrue("tap target must be inside the viewport: " + selector + " " + point,
                point.optDouble("top") >= 0 && point.optDouble("bottom") <= point.optDouble("viewportHeight"));
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
        long downTime = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, xy[0], xy[1], 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        InstrumentationRegistry.getInstrumentation().getUiAutomation().injectInputEvent(down, true);
        down.recycle();
        SystemClock.sleep(60);
        MotionEvent up = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, xy[0], xy[1], 0);
        up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        InstrumentationRegistry.getInstrumentation().getUiAutomation().injectInputEvent(up, true);
        up.recycle();
    }

    /**
     * Capture only once the target screen's marker is on screen, and never
     * accept a byte-identical copy of the previous capture: a stale frame of
     * the screen we just left is retried, then fails.
     */
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
        media.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PocketShell/JsSettings/" + runId);
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
        throw new AssertionError("JavaScript condition did not become true: " + expression + " (last: " + last + ")");
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
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private Context targetContext() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private void clearNativeCrashReports() {
        File[] files = NativeCrashRecorder.directory(targetContext()).listFiles();
        if (files == null) return;
        for (File file : files) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
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
