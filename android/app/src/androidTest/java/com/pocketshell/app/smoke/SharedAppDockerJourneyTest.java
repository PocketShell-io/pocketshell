package com.pocketshell.app.smoke;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.os.SystemClock;
import android.webkit.WebView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.pocketshell.app.MainActivity;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * #2936 stage 1 acceptance: the default launch mounts the SHARED PocketShell
 * app (core packages/ui) over Android's PocketShellApi, and its host picker,
 * session tree and terminal drive a real SSH/aplexer session on the Docker
 * agents fixture through core's ConnectionController and the native plugin.
 *
 * The load-bearing assertion is host-evaluated output: the typed command
 * contains an arithmetic expansion, so the expected line ("PS2936_42_<run>")
 * appears only if the keystrokes reached the remote shell and its output
 * came back through the PTY — local echo alone cannot produce it.
 */
@RunWith(AndroidJUnit4.class)
public class SharedAppDockerJourneyTest {
    private static final long WAIT_TIMEOUT_MILLIS = 45_000;
    private ActivityScenario<MainActivity> scenario;

    @Before
    public void launchSharedApp() {
        scenario = ActivityScenario.launch(MainActivity.class);
    }

    @After
    public void closeApp() {
        if (scenario != null) scenario.close();
    }

    @Test
    public void sharedAppListsAttachesAndTypesIntoFixtureSession() throws Exception {
        var arguments = InstrumentationRegistry.getArguments();
        String host = arguments.getString("sshHost", "10.0.2.2");
        String port = arguments.getString("sshPort");
        String encodedKey = arguments.getString("sshPrivateKeyBase64");
        assertNotNull("pass the Docker fixture port with sshPort", port);
        assertNotNull("pass the test-only key with sshPrivateKeyBase64", encodedKey);
        String privateKey = new String(Base64.getDecoder().decode(encodedKey), StandardCharsets.UTF_8);
        String run = Long.toString(System.currentTimeMillis(), 36);

        // The shared picker (not the legacy phone screen) is the launch surface.
        awaitJsTrue("!!document.querySelector('.host-list, .empty') && !document.querySelector('.app-shell')");
        evalString("(() => {const b=[...document.querySelectorAll('button')].find((n)=>n.textContent.trim()==='Add a host');"
                + "if(!b) throw new Error('no Add a host action'); b.click(); return 'ok';})()");
        awaitJsTrue("!!document.querySelector('[data-testid=android-add-host]')");
        setValue("[data-testid=host-name]", "fixture-" + run);
        setValue("[data-testid=host-hostname]", host);
        setValue("[data-testid=host-port]", port);
        setValue("[data-testid=host-user]", "testuser");
        setValue("[data-testid=host-private-key]", privateKey);
        click("[data-testid=host-save]");

        awaitJsTrue("[...document.querySelectorAll('.host-row')].some((n)=>n.textContent.includes('fixture-" + run + "'))");
        evalString("(() => {[...document.querySelectorAll('.host-row')].find((n)=>n.textContent.includes('fixture-" + run
                + "')).click(); return 'ok';})()");

        // Listing: the session tree renders folders from `pocketshell sessions list`.
        awaitJsTrue("document.querySelectorAll('.dir-header').length > 0");
        click(".dir-header");

        // Attach: the folder workspace mounts the shared TerminalView and the
        // controller's PTY paints a prompt into it.
        awaitJsTrue("(document.querySelector('.xterm-rows')?.innerText ?? '').includes('$')");
        evalString("(() => {document.querySelector('.xterm-helper-textarea').focus(); return 'ok';})()");
        awaitJsTrue("document.activeElement?.classList.contains('xterm-helper-textarea') === true");

        // Type with real key events through the WebView into xterm.
        InstrumentationRegistry.getInstrumentation().sendStringSync("echo PS2936_$((6*7))_" + run);
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_ENTER);

        awaitJsTrue("(document.querySelector('.xterm-rows')?.innerText ?? '').split('\\n')"
                + ".some((line)=>line.trim()==='PS2936_42_" + run + "')");
    }

    private void setValue(String selector, String value) throws Exception {
        evalString("(() => {const node = document.querySelector(" + JSONObject.quote(selector) + ");"
                + "if (!node) throw new Error('missing ' + " + JSONObject.quote(selector) + ");"
                + "node.value = " + JSONObject.quote(value) + ";"
                + "node.dispatchEvent(new Event('input', {bubbles: true})); return 'set';})()");
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
            Thread.sleep(150);
        }
        throw new AssertionError("WebView condition did not become true: " + expression + " (last result: " + last
                + "; page=" + evalString("document.body.innerText") + ")");
    }

    private String evalString(String expression) throws Exception {
        Object decoded = new JSONTokener(evalRaw(expression)).nextValue();
        return decoded == null ? null : decoded.toString();
    }

    private String evalRaw(String expression) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        scenario.onActivity(activity -> {
            WebView webView = activity.getBridge().getWebView();
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
}
