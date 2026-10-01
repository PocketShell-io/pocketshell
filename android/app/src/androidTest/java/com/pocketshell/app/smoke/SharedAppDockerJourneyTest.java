package com.pocketshell.app.smoke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.os.SystemClock;
import android.view.KeyEvent;
import android.webkit.WebView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.pocketshell.app.MainActivity;
import com.pocketshell.app.SharedShellLaunch;
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
 * #2936 stage 1 acceptance: a launch that opts into the shared PocketShell
 * app (core packages/ui) boots it exactly once, and its host picker, session
 * tree and terminal drive real aplexer sessions on a Docker agents lane
 * through Android's PocketShellApi, core's ConnectionController and the
 * native plugin.
 *
 * The runner (scripts/connected-js-shared-app.sh) creates two sessions of
 * this run's own, each in its own folder ({@code sessionRun}-a / -b), so the
 * journey never depends on what earlier lanes left on the fixture.
 *
 * Load-bearing assertions are host-evaluated output: the typed command holds
 * an arithmetic expansion, so the expected line ("PS2936_42_<marker>")
 * exists only if the keystrokes reached the remote shell and its output came
 * back through the PTY. Re-opening session A after B must show A's marker
 * again (aplexer's attach snapshot reaching the pane), then accept input.
 */
@RunWith(AndroidJUnit4.class)
public class SharedAppDockerJourneyTest {
    private static final long WAIT_TIMEOUT_MILLIS = 45_000;
    /** The visible terminal's text: hidden panes keep their own rows. */
    private static final String VISIBLE_TERMINAL =
            "([...document.querySelectorAll('.xterm-rows')].find((n)=>n.offsetParent!==null)?.innerText ?? '')";
    private ActivityScenario<MainActivity> scenario;

    @Before
    public void launchSharedApp() {
        scenario = ActivityScenario.launch(SharedShellLaunch.intent());
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
        String run = arguments.getString("sessionRun");
        assertNotNull("pass the Docker fixture port with sshPort", port);
        assertNotNull("pass the test-only key with sshPrivateKeyBase64", encodedKey);
        assertNotNull("pass the runner-created session run id with sessionRun", run);
        assertTrue("sessionRun must be a safe folder token", run.matches("[a-z0-9-]{6,40}"));
        String privateKey = new String(Base64.getDecoder().decode(encodedKey), StandardCharsets.UTF_8);
        String folderA = run + "-a";
        String folderB = run + "-b";

        // Exactly one shell booted, chosen before the WebView loaded: no
        // default shell first, no reload into the shared one.
        awaitJsTrue("!!document.querySelector('.host-list, .empty')");
        assertEquals("[\"shared\"]", evalString("sessionStorage.getItem('pocketshell.shell-boot-log')"));

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

        // Listing: this run's two folders, from `pocketshell sessions list`.
        awaitFolder(folderA);
        awaitFolder(folderB);

        // First attach of A: aplexer's attach snapshot paints the prompt.
        openFolder(folderA);
        awaitJsTrue(VISIBLE_TERMINAL + ".includes('$')");
        typeLine("echo PS2936_$((6*7))_" + run + "_a1");
        awaitTerminalLine("PS2936_42_" + run + "_a1");

        // Switch to B (a fresh attach on the controller's one PTY).
        openFolder(folderB);
        awaitJsTrue(VISIBLE_TERMINAL + ".includes('$') && !" + VISIBLE_TERMINAL + ".includes('PS2936_42_" + run + "_a1')");

        // Re-open A: an already-used session must repaint, not come up blank.
        openFolder(folderA);
        awaitTerminalLine("PS2936_42_" + run + "_a1");
        typeLine("echo PS2936_$((6*7))_" + run + "_a2");
        awaitTerminalLine("PS2936_42_" + run + "_a2");
    }

    private void awaitFolder(String folder) throws Exception {
        awaitJsTrue("[...document.querySelectorAll('.dir-header')].some((n)=>n.textContent.includes(" + JSONObject.quote(folder) + "))");
    }

    private void openFolder(String folder) throws Exception {
        evalString("(() => {const row=[...document.querySelectorAll('.dir-header')].find((n)=>n.textContent.includes("
                + JSONObject.quote(folder) + ")); if(!row) throw new Error('no folder row'); row.click(); return 'ok';})()");
    }

    /** Real key events through the WebView into the visible xterm. */
    private void typeLine(String text) throws Exception {
        evalString("(() => {const t=[...document.querySelectorAll('.xterm')].find((n)=>n.offsetParent!==null)"
                + "?.querySelector('.xterm-helper-textarea'); if(!t) throw new Error('no visible terminal'); t.focus(); return 'ok';})()");
        awaitJsTrue("document.activeElement?.classList.contains('xterm-helper-textarea') === true");
        InstrumentationRegistry.getInstrumentation().sendStringSync(text);
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER);
    }

    private void awaitTerminalLine(String line) throws Exception {
        awaitJsTrue(VISIBLE_TERMINAL + ".split('\\n').some((l)=>l.trim()===" + JSONObject.quote(line) + ")");
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
                + "; terminal=" + evalString(VISIBLE_TERMINAL) + "; page=" + evalString("document.body.innerText") + ")");
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
