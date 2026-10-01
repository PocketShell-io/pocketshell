package com.pocketshell.app.smoke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.webkit.WebView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.pocketshell.app.MainActivity;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Issue #2993: user-data writes must survive a process kill that lands
 * shortly after the UI acknowledged them.
 *
 * <p>The host-owned runner (scripts/connected-js-durable-storage.sh) drives
 * three {@code am instrument} invocations of this one method:
 * <ol>
 *   <li>{@code seed}: create two command chips, pick a theme and write raw
 *       localStorage keys the way shared-ui code does, then wait long enough
 *       for even a lazily-flushing WebView store to commit; the runner then
 *       force-stops the app.</li>
 *   <li>{@code mutate}: change the theme, delete one chip and update/remove
 *       the raw keys through the real UI and storage API, log the
 *       acknowledgement, then SIGKILL the process {@code durableKillDelayMs}
 *       (default 0) later, the way a swipe-away or low-memory kill does. The
 *       runner fails the run unless the measured ACK-to-KILL gap is below one
 *       second.</li>
 *   <li>{@code verify}: relaunch and require every mutation in the UI and in
 *       localStorage, so a deleted chip that reappears is red.</li>
 * </ol>
 */
@RunWith(AndroidJUnit4.class)
public final class DurableStorageRestartJourneyTest {
    private static final String TAG = "PS2993Durable";
    private static final long JS_TIMEOUT_SECONDS = 15;
    private static final long WAIT_TIMEOUT_MILLIS = 15_000;
    private static final long SEED_SETTLE_MILLIS = 8_000;
    private static final String SNIPPETS_KEY = "pocketshell.js.host-snippets.v1";
    private static final String SETTINGS_KEY = "pocketshell.js.settings.v1";
    private static final String RAW_UPDATE_KEY = "pocketshell.ps2993.shared-ui-probe";
    private static final String RAW_REMOVE_KEY = "pocketshell.ps2993.shared-ui-removed-probe";
    private static final String SEED_THEME = "light";
    private static final String MUTATED_THEME = "gruvbox-dark";

    private static final String USERNAME = "ps2993";

    private ActivityScenario<MainActivity> scenario;
    private String hostname;
    private String tag;
    private long killDelayMillis;

    @Before
    public void launchPackagedShell() {
        scenario = ActivityScenario.launch(MainActivity.class);
    }

    @After
    public void closeShell() {
        if (scenario != null) scenario.close();
    }

    @Test
    public void userDataWritesSurviveForceStopShortlyAfterAcknowledgement() throws Exception {
        assertTrue("the packaged durability journey runs on the API 35 lane", Build.VERSION.SDK_INT >= 35);
        Bundle arguments = InstrumentationRegistry.getArguments();
        String phase = arguments.getString("durablePhase");
        tag = arguments.getString("durableTag");
        killDelayMillis = Long.parseLong(arguments.getString("durableKillDelayMs", "0"));
        assertTrue("durableKillDelayMs must be 0-500", killDelayMillis >= 0 && killDelayMillis <= 500);
        assertNotNull("pass durablePhase=seed|mutate|verify", phase);
        assertNotNull("pass a run-unique durableTag", tag);
        assertTrue("durableTag must be 4-24 letters, digits or dashes", tag.matches("[A-Za-z0-9-]{4,24}"));

        hostname = "ps2993-" + tag.toLowerCase() + ".invalid";
        String keepLabel = "PS2993-KEEP-" + tag;
        String dropLabel = "PS2993-DROP-" + tag;
        String rawSeedValue = "seed-" + tag;
        String rawMutatedValue = "mutated-" + tag;

        awaitJsTrue("document.querySelector('[data-testid=build-status] > span:nth-child(2)')?.textContent.trim() === 'Build verified'");
        selectHost();
        switch (phase) {
            case "seed":
                seed(keepLabel, dropLabel, rawSeedValue);
                break;
            case "mutate":
                mutate(keepLabel, dropLabel, rawSeedValue, rawMutatedValue);
                break;
            case "verify":
                verify(keepLabel, dropLabel, rawMutatedValue);
                break;
            default:
                throw new AssertionError("durablePhase must be seed, mutate or verify; got " + phase);
        }
    }

    private void seed(String keepLabel, String dropLabel, String rawSeedValue) throws Exception {
        openSettings();
        chooseTheme(SEED_THEME);
        openSnippetSettings();
        createSnippet(keepLabel, "echo keep-" + keepLabel);
        createSnippet(dropLabel, "echo drop-" + dropLabel);
        evalString("(() => {localStorage.setItem(" + JSONObject.quote(RAW_UPDATE_KEY) + ", "
                + JSONObject.quote(rawSeedValue) + ");localStorage.setItem(" + JSONObject.quote(RAW_REMOVE_KEY)
                + ", 'present');return 'seeded';})()");
        JSONObject state = readState();
        assertTrue("both seed chips must be stored: " + state,
                contains(state.getJSONArray("labels"), keepLabel) && contains(state.getJSONArray("labels"), dropLabel));
        // Give a lazily-committing WebView store ample time, so the seed is a
        // durable baseline even on a build without the fix; the mutate phase
        // is the one that races the kill.
        Thread.sleep(SEED_SETTLE_MILLIS);
        Log.i(TAG, "SEED|" + state);
    }

    private void mutate(String keepLabel, String dropLabel, String rawSeedValue, String rawMutatedValue) throws Exception {
        JSONObject before = readState();
        assertTrue("seed chips must have survived the settled restart before mutating: " + before,
                contains(before.getJSONArray("labels"), keepLabel) && contains(before.getJSONArray("labels"), dropLabel));
        assertEquals("seed raw key must have survived the settled restart", rawSeedValue, before.optString("raw", null));
        assertEquals("seed theme must have survived the settled restart", SEED_THEME, before.optString("theme"));

        // The reported write (the chip deletion) and the raw shared-ui writes
        // come last, immediately before the acknowledgement and the kill.
        openSettings();
        chooseTheme(MUTATED_THEME);
        openSnippetSettings();
        deleteSnippet(dropLabel);
        evalString("(() => {localStorage.setItem(" + JSONObject.quote(RAW_UPDATE_KEY) + ", "
                + JSONObject.quote(rawMutatedValue) + ");localStorage.removeItem(" + JSONObject.quote(RAW_REMOVE_KEY)
                + ");return 'mutated';})()");
        JSONObject after = readState();
        assertTrue("the deleted chip must be gone from storage before the kill: " + after,
                !contains(after.getJSONArray("labels"), dropLabel) && contains(after.getJSONArray("labels"), keepLabel));
        assertEquals(MUTATED_THEME, after.optString("theme"));
        assertEquals(rawMutatedValue, after.optString("raw", null));
        assertTrue("removed raw key must be absent before the kill", after.isNull("removedRaw"));
        // The acknowledgement timestamp the runner measures the kill gap from.
        Log.i(TAG, "ACK|" + System.currentTimeMillis() + "|" + after);
        killOwnProcessAfter(killDelayMillis);
    }

    /**
     * SIGKILL the packaged process the way a swipe-away, force-stop or
     * low-memory kill does: no activity teardown, no WebView pause, no chance
     * for a lazy store to flush. The runner treats the resulting "Process
     * crashed" instrumentation result as this phase's expected outcome only
     * when the KILL line below follows the ACK line by under one second.
     */
    private void killOwnProcessAfter(long delayMillis) throws Exception {
        Thread.sleep(delayMillis);
        Log.i(TAG, "KILL|" + System.currentTimeMillis() + "|" + tag);
        Process.killProcess(Process.myPid());
        Thread.sleep(10_000);
        throw new AssertionError("the packaged process survived its own SIGKILL");
    }

    private void verify(String keepLabel, String dropLabel, String rawMutatedValue) throws Exception {
        openSettings();
        awaitJsTrue("document.querySelector('[data-testid=setting-theme]') !== null");
        String uiTheme = evalString("document.querySelector('[data-testid=setting-theme]').value");
        openSnippetSettings();
        JSONArray uiLabels = new JSONArray(evalString("JSON.stringify(Array.from(document.querySelectorAll("
                + "'[data-testid=host-snippet-list] li')).map(row=>row.querySelector('.managed-snippet__heading strong')"
                + "?.textContent.trim()||''))"));
        JSONObject state = readState();
        Log.i(TAG, "VERIFY|ui=" + uiLabels + "|theme=" + uiTheme + "|" + state);

        assertTrue("the chip deleted shortly before the force-stop reappeared after restart (#2993): ui="
                + uiLabels + " storage=" + state, !contains(uiLabels, dropLabel) && !contains(state.getJSONArray("labels"), dropLabel));
        assertTrue("the kept chip must still be listed after restart: " + uiLabels, contains(uiLabels, keepLabel));
        assertEquals("the theme changed shortly before the force-stop must survive restart", MUTATED_THEME, uiTheme);
        assertEquals("a raw shared-ui localStorage update must survive restart", rawMutatedValue, state.optString("raw", null));
        assertTrue("a raw shared-ui localStorage removal must survive restart", state.isNull("removedRaw"));
        assertEquals("Android must report the native durable storage backend",
                "native-durable", evalString("document.documentElement.dataset.durableStorage ?? ''"));
        // The synchronous writer is visible to every frame; without the page's
        // token it must refuse to write.
        assertEquals("a forged write token must be refused by the native writer", "unauthorized",
                evalString("window.PocketShellDurableStorage.setItem('0'.repeat(64), 'ps2993.forged', 'x')"));
    }

    private void selectHost() throws Exception {
        awaitJsTrue("document.querySelector('[data-testid=ssh-host]') !== null");
        setValue("[data-testid=ssh-host]", hostname);
        setValue("[data-testid=ssh-port]", "22");
        setValue("[data-testid=ssh-username]", USERNAME);
    }

    private void openSettings() throws Exception {
        awaitJsTrue("document.querySelector('[aria-label=Settings]') !== null");
        click("[aria-label=Settings]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'settings'"
                + " && document.querySelector('[data-testid=setting-theme]') !== null");
    }

    private void chooseTheme(String theme) throws Exception {
        setValue("[data-testid=setting-theme]", theme);
        awaitJsTrue("JSON.parse(localStorage.getItem(" + JSONObject.quote(SETTINGS_KEY) + ")||'{}').themeChoice === "
                + JSONObject.quote(theme));
    }

    private void openSnippetSettings() throws Exception {
        click("[data-testid=open-snippet-settings]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'settings-snippets'"
                + " && !!document.querySelector('[data-testid=snippet-editor]')");
    }

    private void createSnippet(String label, String body) throws Exception {
        setValue("[data-testid=snippet-label]", label);
        setValue("[data-testid=snippet-body]", body);
        click("[data-testid=save-snippet]");
        awaitJsTrue(listedExpression(label));
    }

    private void deleteSnippet(String label) throws Exception {
        String deleteSelector = "button[aria-label=" + JSONObject.quote("Delete " + label) + "]";
        String confirmSelector = "button[aria-label=" + JSONObject.quote("Confirm delete " + label) + "]";
        awaitJsTrue("!!document.querySelector(" + JSONObject.quote(deleteSelector) + ")");
        click(deleteSelector);
        awaitJsTrue("!!document.querySelector(" + JSONObject.quote(confirmSelector) + ")");
        click(confirmSelector);
        awaitJsTrue("!(" + listedExpression(label) + ")");
    }

    private String listedExpression(String label) {
        return "Array.from(document.querySelectorAll('[data-testid=host-snippet-list] li'))"
                + ".some(row=>row.querySelector('.managed-snippet__heading strong')?.textContent.trim()==="
                + JSONObject.quote(label) + ")";
    }

    private JSONObject readState() throws Exception {
        String hostId = USERNAME + "@" + hostname + ":22";
        return new JSONObject(evalString("(() => {const hostId=" + JSONObject.quote(hostId) + ";"
                + "const saved=JSON.parse(localStorage.getItem(" + JSONObject.quote(SNIPPETS_KEY) + ")||'null');"
                + "const settings=JSON.parse(localStorage.getItem(" + JSONObject.quote(SETTINGS_KEY) + ")||'{}');"
                + "return JSON.stringify({hostId,labels:(saved?.snippets||[]).filter(item=>item.hostId===hostId)"
                + ".map(item=>item.label),theme:settings.themeChoice??null,"
                + "raw:localStorage.getItem(" + JSONObject.quote(RAW_UPDATE_KEY) + "),"
                + "removedRaw:localStorage.getItem(" + JSONObject.quote(RAW_REMOVE_KEY) + "),"
                + "backend:document.documentElement.dataset.durableStorage??null,"
                + "launchRestored:document.documentElement.dataset.durableStorageRestored??null,"
                + "launchRemoved:document.documentElement.dataset.durableStorageRemoved??null});})()"));
    }

    private static boolean contains(JSONArray values, String expected) throws Exception {
        for (int index = 0; index < values.length(); index++) {
            if (expected.equals(values.optString(index))) return true;
        }
        return false;
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
        throw new AssertionError("JavaScript condition did not become true: " + expression + " (last result: " + last + ")");
    }

    private String evalString(String expression) throws Exception {
        String raw = evalRaw(expression);
        Object decoded = new JSONTokener(raw).nextValue();
        return decoded == null || decoded == JSONObject.NULL ? null : decoded.toString();
    }

    private String evalRaw(String expression) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        scenario.onActivity(activity -> {
            WebView webView = findWebView(activity.getWindow().getDecorView());
            assertNotNull("the packaged activity must contain its Capacitor WebView", webView);
            webView.evaluateJavascript(expression, value -> {
                result.set(value);
                latch.countDown();
            });
        });
        assertTrue("timed out evaluating packaged WebView JavaScript", latch.await(JS_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        return result.get();
    }

    private static WebView findWebView(View view) {
        if (view instanceof WebView) return (WebView) view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                WebView child = findWebView(group.getChildAt(index));
                if (child != null) return child;
            }
        }
        return null;
    }
}
