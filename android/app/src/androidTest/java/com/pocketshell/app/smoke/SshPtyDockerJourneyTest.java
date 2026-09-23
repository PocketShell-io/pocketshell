package com.pocketshell.app.smoke;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.os.SystemClock;
import android.util.Log;
import android.webkit.WebView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.pocketshell.app.MainActivity;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Packaged Android journey over a real sshd + aplexer Docker fixture. */
@RunWith(AndroidJUnit4.class)
public final class SshPtyDockerJourneyTest {
    private static final long WAIT_TIMEOUT_MILLIS = 45_000;
    private ActivityScenario<MainActivity> scenario;

    @Before
    public void launchPackagedShell() {
        scenario = ActivityScenario.launch(MainActivity.class);
    }

    @After
    public void closeShell() {
        if (scenario != null) scenario.close();
    }

    @Test
    public void sshTrustSessionAndPtyRoundTripAgainstDockerFixture() throws Exception {
        var arguments = InstrumentationRegistry.getArguments();
        String host = arguments.getString("sshHost", "10.0.2.2");
        String port = arguments.getString("sshPort");
        String encodedKey = arguments.getString("sshPrivateKeyBase64");
        assertNotNull("pass the Docker fixture port with sshPort", port);
        assertNotNull("pass the test-only key with sshPrivateKeyBase64", encodedKey);
        String privateKey = new String(Base64.getDecoder().decode(encodedKey), StandardCharsets.UTF_8);
        String sessionName = arguments.getString("sshSessionName", "js-ssh-" + System.currentTimeMillis());

        awaitJsTrue("document.querySelector('[data-testid=build-status] > span:nth-child(2)')?.textContent.trim() === 'Build verified'");
        setValue("[data-testid=ssh-host]", host);
        setValue("[data-testid=ssh-port]", port);
        setValue("[data-testid=ssh-username]", "testuser");
        setValue("[data-testid=ssh-private-key]", privateKey);
        click("[data-testid=ssh-connect]");

        awaitJsTrue("!!document.querySelector('[data-testid=host-key-decision]') || !!document.querySelector('[data-testid=ssh-message]')");
        String fingerprint = evalString("document.querySelector('[data-testid=host-key-fingerprint]')?.textContent.trim() ?? ''");
        assertTrue("the real SSH server must present its SHA-256 host key; fingerprint=" + fingerprint
                + "; visible page=" + evalString("document.body.innerText"),
                fingerprint.startsWith("SHA256:") && fingerprint.length() > 20);
        click("[data-testid=trust-host-key]");
        awaitJsTrue("['connected','listing'].includes(document.querySelector('.app-shell')?.dataset.sshPhase)");
        awaitJsTrue("!!document.querySelector('[data-testid=refresh-sessions]')");

        setValue("[data-testid=new-session-name]", sessionName);
        click("[data-testid=create-session]");
        String findCreatedSession = "Array.from(document.querySelectorAll('[data-session-name]')).find((node) => node.dataset.sessionName.endsWith("
                + JSONObject.quote(sessionName) + "))";
        awaitJsTrue(findCreatedSession + " !== undefined");
        String actualSessionName = evalString(findCreatedSession + "?.dataset.sessionName ?? ''");
        String sessionSelector = "[data-session-name=\"" + actualSessionName + "\"]";
        String listedSession = evalString("document.querySelector(" + JSONObject.quote(sessionSelector + " .session-name") + ")?.textContent.trim() ?? ''");
        assertTrue("new host session must appear in the live list", actualSessionName.equals(listedSession) && actualSessionName.endsWith(sessionName));

        click(sessionSelector);
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.sshPhase === 'live'");
        awaitJsTrue("document.querySelector('[data-testid=terminal-resize-status]')?.textContent.includes('accepted by SSH')");
        awaitJsTrue("!!document.querySelector('#terminal-viewport .xterm-helper-textarea')");

        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        InstrumentationRegistry.getInstrumentation().sendStringSync("printf 'JS_SSH_PTY_OK\\n'\n");
        awaitJsTrue("document.querySelector('#terminal-viewport .xterm-rows')?.textContent.includes('JS_SSH_PTY_OK')");
        String terminalText = evalString("document.querySelector('#terminal-viewport .xterm-rows')?.textContent ?? ''");
        assertTrue("PTY output bytes must reach the packaged terminal viewport", terminalText.contains("JS_SSH_PTY_OK"));

        evalString("(() => {document.querySelector('#terminal-title')?.scrollIntoView({block: 'center', behavior: 'instant'}); return 'scrolled';})()");
        awaitJsTrue("(() => {const rect = document.querySelector('#terminal-viewport')?.getBoundingClientRect(); return !!rect && rect.top >= 0 && rect.bottom <= innerHeight;})()");
        Log.i("SshPtyDockerJourney", "LIVE_TERMINAL_READY " + sessionName);
        // Leave a short capture window so a connected-run wrapper can save the
        // actual packaged WebView and terminal frame for reviewer evidence.
        SystemClock.sleep(4_000);

        click("[data-testid=ssh-disconnect]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.sshPhase === 'idle'");
        awaitJsTrue("document.querySelector('[data-testid=ssh-resources]')?.textContent.replace(/\\s/g, '').includes('Connections0PTYchannels0SFTPclients0Portforwards0')");
    }

    private void setValue(String selector, String value) throws Exception {
        evalString("(() => {const node = document.querySelector(" + JSONObject.quote(selector) + ");"
                + "if (!node) throw new Error('missing ' + " + JSONObject.quote(selector) + ");"
                + "node.value = " + JSONObject.quote(value) + ";"
                + "node.dispatchEvent(new Event('input', {bubbles: true}));"
                + "node.dispatchEvent(new Event('change', {bubbles: true})); return 'set';})()");
    }

    private void click(String selector) throws Exception {
        evalString("(() => {const node = document.querySelector(" + JSONObject.quote(selector) + ");"
                + "if (!node) throw new Error('missing ' + " + JSONObject.quote(selector) + ");"
                + "node.click(); return 'clicked';})()");
    }

    private void awaitJsTrue(String expression) throws Exception {
        long deadline = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS;
        String last = "<not evaluated>";
        while (SystemClock.uptimeMillis() < deadline) {
            last = evalRaw(expression);
            if ("true".equals(last)) return;
            Thread.sleep(100);
        }
        throw new AssertionError("WebView condition did not become true: " + expression + " (last result: " + last
                + "; page=" + evalString("document.body.innerText") + ")");
    }

    private String evalString(String expression) throws Exception {
        String raw = evalRaw(expression);
        Object decoded = new JSONTokener(raw).nextValue();
        return decoded == null ? null : decoded.toString();
    }

    private String evalRaw(String expression) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        scenario.onActivity(activity -> {
            WebView webView = null;
            android.view.View decor = activity.getWindow().getDecorView();
            if (decor instanceof WebView) webView = (WebView) decor;
            if (webView == null) webView = findWebView((android.view.ViewGroup) decor);
            assertNotNull("the packaged activity must contain its Capacitor WebView", webView);
            webView.evaluateJavascript(expression, value -> {
                result.set(value);
                latch.countDown();
            });
        });
        assertTrue("WebView JavaScript evaluation timed out", latch.await(10, TimeUnit.SECONDS));
        String value = result.get();
        if (value == null || "null".equals(value)) throw new JSONException("JavaScript returned null: " + expression);
        return value;
    }

    private static WebView findWebView(android.view.ViewGroup root) {
        for (int index = 0; index < root.getChildCount(); index++) {
            android.view.View child = root.getChildAt(index);
            if (child instanceof WebView) return (WebView) child;
            if (child instanceof android.view.ViewGroup) {
                WebView nested = findWebView((android.view.ViewGroup) child);
                if (nested != null) return nested;
            }
        }
        return null;
    }
}
