package com.pocketshell.app.smoke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Base64;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.core.content.FileProvider;

import com.pocketshell.app.MainActivity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Packaged-device checks for the JS-first shell; these exercise the installed APK WebView. */
@RunWith(AndroidJUnit4.class)
public final class JsShellPackagedSmokeTest {
    private static final long JS_TIMEOUT_SECONDS = 15;
    private static final long WAIT_TIMEOUT_MILLIS = 12_000;

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
    public void launchShowsVerifiedSourcesAndAssetIdentity() throws Exception {
        JSONObject manifest = packagedManifest();
        String expectedCoreRevision = manifest.getString("coreSourceRevision");
        String expectedUiRevision = manifest.getString("uiSourceRevision");
        String expectedAssetHash = manifest.getString("bundleAssetHash");

        assertTrue("manifest core revision must be a full git revision", expectedCoreRevision.matches("[a-f0-9]{40}"));
        assertTrue("manifest shared UI revision must be a full git revision", expectedUiRevision.matches("[a-f0-9]{40}"));
        assertTrue("manifest aggregate asset hash must be SHA-256", expectedAssetHash.matches("[a-f0-9]{64}"));
        awaitJsTrue("document.querySelector('[data-testid=build-status] > span:nth-child(2)')?.textContent.trim() === 'Build verified'");

        String visibleIdentity = evalString("document.querySelector('.build-strip__detail')?.textContent.trim()");
        assertTrue("the visible build strip must identify the pinned core", visibleIdentity.contains(expectedCoreRevision.substring(0, 12)));
        assertTrue("the visible build strip must identify the pinned shared UI", visibleIdentity.contains(expectedUiRevision.substring(0, 12)));
        assertTrue("the visible build strip must identify the packaged assets", visibleIdentity.contains(expectedAssetHash.substring(0, 12)));
        assertEquals(expectedCoreRevision, evalString("document.querySelector('[data-testid=core-revision]')?.textContent.trim()"));
        assertEquals(expectedUiRevision, evalString("document.querySelector('[data-testid=ui-revision]')?.textContent.trim()"));
        assertEquals(expectedAssetHash, evalString("document.querySelector('[data-testid=bundle-asset-hash]')?.textContent.trim()"));
        JSONObject statusBounds = evalJson("(() => {const node = document.querySelector('[data-testid=build-status]');"
                + "const rect = node.getBoundingClientRect();"
                + "return JSON.stringify({top: rect.top, bottom: rect.bottom, height: innerHeight});})()");
        assertTrue("verified status and short build identity must be on screen",
                statusBounds.getDouble("top") >= 0 && statusBounds.getDouble("bottom") <= statusBounds.getDouble("height"));
    }

    @Test
    public void packagedAndroidAdaptersDeliverSharedTextAndExactFileBytes() throws Exception {
        scenario.close();

        byte[] sharedBytes = "packed café 🧪".getBytes(StandardCharsets.UTF_8);
        File sharedFile = new File(targetContext().getCacheDir(), "platform-input-share.txt");
        try (FileOutputStream output = new FileOutputStream(sharedFile)) {
            output.write(sharedBytes);
        }
        Uri sharedUri = FileProvider.getUriForFile(
                targetContext(), targetContext().getPackageName() + ".fileprovider", sharedFile);
        Intent share = new Intent(Intent.ACTION_SEND)
                .setClass(targetContext(), MainActivity.class)
                .setType("text/plain")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(Intent.EXTRA_SUBJECT, "Packaged share subject")
                .putExtra(Intent.EXTRA_TEXT, "Packaged share body")
                .putExtra(Intent.EXTRA_STREAM, sharedUri);
        share.setClipData(ClipData.newUri(targetContext().getContentResolver(), "shared file", sharedUri));
        scenario = ActivityScenario.launch(share);

        awaitJsTrue("document.querySelector('[data-testid=build-status]') !== null");
        awaitJsTrue("(() => {const plugin=window.Capacitor?.Plugins?.DocumentContent; if(!plugin?.addListener) return false;"
                + "plugin.addListener('shareReceived',(event)=>window.__ps2857SharedContent=event); return true;})()");
        awaitJsTrue("window.__ps2857SharedContent?.text === 'Packaged share body'"
                + " && window.__ps2857SharedContent?.subject === 'Packaged share subject'"
                + " && window.__ps2857SharedContent?.files?.length === 1");

        JSONObject sharedMetadata = evalJson("(() => {const file=window.__ps2857SharedContent.files[0];"
                + "return JSON.stringify({name:file.name,sizeBytes:file.sizeBytes,mimeType:file.mimeType});})()");
        assertEquals(sharedFile.getName(), sharedMetadata.getString("name"));
        assertEquals(sharedBytes.length, sharedMetadata.getInt("sizeBytes"));

        String expectedBase64 = Base64.encodeToString(sharedBytes, Base64.NO_WRAP);
        evalRaw("(() => {const plugin=window.Capacitor.Plugins.DocumentContent;"
                + "const fileId=window.__ps2857SharedContent.files[0].fileId;"
                + "plugin.readPickedFileChunk({fileId,offset:0,maxBytes:65536}).then(async (chunk)=>{"
                + "window.__ps2857SharedChunk=chunk.base64; await plugin.releasePickedFile({fileId});});})()");
        awaitJsTrue("window.__ps2857SharedChunk === " + JSONObject.quote(expectedBase64));

        evalRaw("window.Capacitor.Plugins.SpeechRecognition.getCapabilities().then((value)=>window.__ps2857SpeechCapabilities=value)");
        awaitJsTrue("typeof window.__ps2857SpeechCapabilities?.speechRecognitionAvailable === 'boolean'"
                + " && typeof window.__ps2857SpeechCapabilities?.microphonePermissionGranted === 'boolean'");

        evalRaw("window.__ps2857PickerResult=null; window.Capacitor.Plugins.DocumentContent.pickFiles({mimeType:'*/*',multiple:true})"
                + ".then((value)=>window.__ps2857PickerResult=value)");
        Thread.sleep(700);
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        awaitJsTrue("window.__ps2857PickerResult?.cancelled === true && window.__ps2857PickerResult?.files?.length === 0");
        assertTrue("the packaged share fixture should be removed", sharedFile.delete());
    }

    @Test
    public void packagedMultipleShareReadsStandardStreamListWithoutClipData() throws Exception {
        scenario.close();

        File firstFile = new File(targetContext().getCacheDir(), "platform-input-list-first.txt");
        File secondFile = new File(targetContext().getCacheDir(), "platform-input-list-second.txt");
        byte[] firstBytes = "first shared file".getBytes(StandardCharsets.UTF_8);
        byte[] secondBytes = "second shared file".getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream output = new FileOutputStream(firstFile)) {
            output.write(firstBytes);
        }
        try (FileOutputStream output = new FileOutputStream(secondFile)) {
            output.write(secondBytes);
        }
        Uri firstUri = FileProvider.getUriForFile(
                targetContext(), targetContext().getPackageName() + ".fileprovider", firstFile);
        Uri secondUri = FileProvider.getUriForFile(
                targetContext(), targetContext().getPackageName() + ".fileprovider", secondFile);
        ArrayList<Uri> streams = new ArrayList<>();
        streams.add(firstUri);
        streams.add(secondUri);
        Intent share = new Intent(Intent.ACTION_SEND_MULTIPLE)
                .setClass(targetContext(), MainActivity.class)
                .setType("text/plain")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, streams);
        assertNull("the fixture must exercise EXTRA_STREAM without ClipData", share.getClipData());
        scenario = ActivityScenario.launch(share);

        awaitJsTrue("document.querySelector('[data-testid=build-status]') !== null");
        awaitJsTrue("(() => {const plugin=window.Capacitor?.Plugins?.DocumentContent; if(!plugin?.addListener) return false;"
                + "plugin.addListener('shareReceived',(event)=>window.__ps2857ListShare=event); return true;})()");
        awaitJsTrue("window.__ps2857ListShare?.files?.length === 2");

        JSONObject sharedFiles = evalJson("JSON.stringify({files:window.__ps2857ListShare.files.map(file=>({name:file.name,sizeBytes:file.sizeBytes}))})");
        JSONArray fileList = sharedFiles.getJSONArray("files");
        assertEquals(firstFile.getName(), fileList.getJSONObject(0).getString("name"));
        assertEquals(firstBytes.length, fileList.getJSONObject(0).getInt("sizeBytes"));
        assertEquals(secondFile.getName(), fileList.getJSONObject(1).getString("name"));
        assertEquals(secondBytes.length, fileList.getJSONObject(1).getInt("sizeBytes"));

        assertTrue("the first packaged stream fixture should be removed", firstFile.delete());
        assertTrue("the second packaged stream fixture should be removed", secondFile.delete());
    }

    @Test
    public void settingsAndAndroidBackReturnHome() throws Exception {
        awaitJsTrue("document.querySelector('[aria-label=Settings]') !== null");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.backButtonReady === 'true'");
        tapDomCenter("[aria-label=Settings]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'settings' && !!document.querySelector('#settings-title')");
        awaitJsTrue("document.querySelector('[data-testid=setting-theme]') !== null");

        tapDomCenter("[data-testid=open-terminal-settings]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'settings-terminal' && !!document.querySelector('#terminal-settings-title')");
        int initialFontSize = Integer.parseInt(evalString("document.querySelector('[data-testid=terminal-font-size]')?.textContent.trim()").replace(" px", ""));
        tapDomCenter("[aria-label='Increase terminal text size']");
        awaitJsTrue("document.querySelector('[data-testid=terminal-font-size]')?.textContent.trim() === '" + (initialFontSize + 1) + " px'");
        awaitJsTrue("getComputedStyle(document.documentElement).getPropertyValue('--term-font-size').trim() === '" + (initialFontSize + 1) + "px'");
        tapDomCenter("[aria-label='Decrease terminal text size']");
        tapDomCenter("[data-testid=terminal-font-size-input]");
        awaitJsTrue("document.activeElement === document.querySelector('[data-testid=terminal-font-size-input]')");
        awaitImeVisible(true);

        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        awaitImeVisible(false);
        awaitRoute("settings-terminal");
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        awaitRoute("settings");
        tapDomCenter("[data-testid=open-connection-settings]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'settings-connections' && !!document.querySelector('#connection-settings-title')");
        awaitJsTrue("document.querySelector('[data-testid=setting-background-grace]')?.value === '90000'");

        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        awaitRoute("settings");
        tapDomCenter("[data-testid=open-diagnostics]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'diagnostics' && !!document.querySelector('#diagnostics-page-title')");
        awaitJsTrue("document.querySelector('[data-testid=diagnostics-events] li button') !== null");
        tapDomCenter("[data-testid=diagnostics-events] li button");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'diagnostics-report' && !!document.querySelector('[data-testid=selected-diagnostic-event]')");
        String diagnosticExport = evalString("document.querySelector('[data-testid=diagnostics-report-preview]')?.textContent");
        assertTrue("the reviewed support report must contain its schema", diagnosticExport.contains("\"schema\": 1"));
        assertTrue("the reviewed support report must declare its privacy scope", diagnosticExport.contains("normalized error codes only"));
        assertTrue("the support report must not contain the host form's private key", !diagnosticExport.contains("OPENSSH PRIVATE KEY"));

        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        awaitRoute("diagnostics");
        tapDomCenter("[data-testid=review-diagnostics-export]");
        awaitRoute("diagnostics-report");
        awaitJsTrue("document.querySelector('[data-testid=selected-diagnostic-event]') === null");
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        awaitRoute("diagnostics");

        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        awaitRoute("settings");
        tapDomCenter("[data-testid=open-about]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'about' && !!document.querySelector('#about-title')");
        awaitJsTrue("document.querySelector('[data-testid=about-core-revision]')?.textContent.trim().length === 40");
        tapDomCenter("[data-testid=open-update-status]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'about-update' && !!document.querySelector('#update-title')");
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        awaitRoute("about");
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        awaitRoute("settings");

        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        awaitHomeAfterBack();
    }

    private void awaitRoute(String route) throws Exception {
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === " + JSONObject.quote(route));
    }

    @Test
    public void composerInputStaysAboveImeWithinSafeArea() throws Exception {
        assertTrue("safe-area CSS injection is supported by the API 35+ smoke device", Build.VERSION.SDK_INT >= 35);

        Insets systemInsets = readRootInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        float density = targetContext().getResources().getDisplayMetrics().density;
        float expectedSafeTop = Math.round(systemInsets.top / density);
        float expectedSafeBottom = Math.round(systemInsets.bottom / density);

        awaitJsTrue("parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--safe-area-inset-top')) >= 0");
        JSONObject beforeIme = evalJson("(() => {"
                + "const root = getComputedStyle(document.documentElement);"
                + "const shell = document.querySelector('.app-shell');"
                + "const shellStyle = getComputedStyle(shell);"
                + "return JSON.stringify({"
                + "safeTop: parseFloat(root.getPropertyValue('--safe-area-inset-top')),"
                + "safeBottom: parseFloat(root.getPropertyValue('--safe-area-inset-bottom')),"
                + "paddingTop: parseFloat(shellStyle.paddingTop),"
                + "paddingBottom: parseFloat(shellStyle.paddingBottom),"
                + "appBarTop: document.querySelector('.app-bar').getBoundingClientRect().top"
                + "});})()");
        assertEquals("status bar inset must reach CSS", expectedSafeTop, beforeIme.getDouble("safeTop"), 1.0);
        assertEquals("safe top padding must be applied to the shell", expectedSafeTop, beforeIme.getDouble("paddingTop"), 1.0);
        assertEquals("safe bottom inset must reach CSS", expectedSafeBottom, beforeIme.getDouble("safeBottom"), 1.0);
        assertEquals("safe bottom padding must be applied to the shell", expectedSafeBottom, beforeIme.getDouble("paddingBottom"), 1.0);
        assertEquals("app content must begin below the status bar", expectedSafeTop, beforeIme.getDouble("appBarTop"), 1.0);

        evalString("(() => { const input = document.querySelector('[data-testid=ssh-host]'); input.scrollIntoView({block: 'center', behavior: 'instant'}); return 'ready'; })()");
        awaitComposerInputSettled();
        tapDomCenter("[data-testid=ssh-host]");
        awaitComposerFocused();
        awaitImeVisible(true);
        awaitImeSafeAreaSettled(expectedSafeTop);

        JSONObject duringIme = evalJson("(() => {"
                + "const root = getComputedStyle(document.documentElement);"
                + "const shell = document.querySelector('.app-shell');"
                + "const shellStyle = getComputedStyle(shell);"
                + "const inputElement = document.querySelector('[data-testid=ssh-host]');"
                + "const input = inputElement.getBoundingClientRect();"
                + "const hostPanelVisible = getComputedStyle(document.querySelector('.host-panel')).display !== 'none';"
                + "return JSON.stringify({"
                + "safeTop: parseFloat(root.getPropertyValue('--safe-area-inset-top')),"
                + "safeBottom: parseFloat(root.getPropertyValue('--safe-area-inset-bottom')),"
                + "paddingTop: parseFloat(shellStyle.paddingTop),"
                + "paddingBottom: parseFloat(shellStyle.paddingBottom),"
                + "inputTop: input.top,inputBottom: input.bottom,"
                + "inputFocused: document.activeElement === inputElement,"
                + "hostPanelVisible,"
                + "keyboardVisible: shell.dataset.keyboardVisible === 'true',"
                + "keyboardComposerMode: shell.dataset.keyboardComposerMode === 'true',"
                + "screenHeight: window.screen.height,"
                + "innerHeight: window.innerHeight,"
                + "viewportHeight: window.visualViewport ? window.visualViewport.height : window.innerHeight,"
                + "viewportScale: window.visualViewport ? window.visualViewport.scale : 1"
                + "});})()");
        android.util.Log.i("JsShellPackagedSmokeTest", "IME_SAFE_AREA dom=" + duringIme + "; android=" + nativeImeState());
        assertEquals("top system bar clearance must persist while the IME is open", expectedSafeTop, duringIme.getDouble("safeTop"), 1.0);
        assertEquals("IME inset must replace the navigation safe-area padding", 0.0, duringIme.getDouble("safeBottom"), 1.0);
        assertEquals("safe-area padding must remain clear of the IME", 0.0, duringIme.getDouble("paddingBottom"), 1.0);
        assertTrue("JS keyboard mode must follow native IME visibility", duringIme.getBoolean("keyboardVisible"));
        assertTrue("host entry must remain visible while its focused field owns the IME", duringIme.getBoolean("hostPanelVisible"));
        assertTrue("host input must keep focus while its keyboard is open", duringIme.getBoolean("inputFocused"));
        assertTrue("compact composer layout must stay off during host entry", !duringIme.getBoolean("keyboardComposerMode"));
        assertTrue("the SSH host input must be above the visual viewport bottom",
                duringIme.getDouble("inputBottom") <= duringIme.getDouble("viewportHeight") + 1.0);

        int imeBottom = readRootInsets(WindowInsets.Type.ime()).bottom;
        assertTrue("the platform must report an open IME inset", imeBottom > 0);
        int[] inputScreenBounds = domRectOnScreen("[data-testid=ssh-host]");
        int[] displayMetrics = displaySize();
        int imeTopOnScreen = displayMetrics[1] - imeBottom;
        assertTrue("the focused SSH host input must remain above the physical IME", inputScreenBounds[1] <= imeTopOnScreen);

        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        awaitImeVisible(false);
    }

    private Context targetContext() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private JSONObject packagedManifest() throws IOException, JSONException {
        try (InputStream input = targetContext().getAssets().open("public/build-manifest.json")) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            JSONObject manifest = new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
            assertEquals(1, manifest.getInt("schema"));
            return manifest;
        }
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

    private void awaitHomeAfterBack() throws Exception {
        long deadline = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS;
        JSONObject last = navigationDomState();
        while (SystemClock.uptimeMillis() < deadline) {
            if ("home".equals(last.optString("route"))
                    && last.optBoolean("hasHostsTitle")
                    && last.optInt("backButtonEvents") > 0) {
                return;
            }
            Thread.sleep(100);
            last = navigationDomState();
        }
        throw new AssertionError("Android Back did not return to Hosts after a registered listener received it: " + last);
    }

    private JSONObject navigationDomState() throws Exception {
        return evalJson("(() => {const shell = document.querySelector('.app-shell');"
                + "return JSON.stringify({route: shell?.dataset.route ?? null,"
                + "backButtonReady: shell?.dataset.backButtonReady ?? null,"
                + "backButtonEvents: Number(shell?.dataset.backButtonEvents ?? 0),"
                + "hasHostsTitle: !!document.querySelector('#hosts-title'),"
                + "hasSettingsTitle: !!document.querySelector('#settings-title')});})()");
    }

    private String evalString(String expression) throws Exception {
        String raw = evalRaw(expression);
        Object decoded = new JSONTokener(raw).nextValue();
        return decoded == null ? null : decoded.toString();
    }

    private JSONObject evalJson(String expression) throws Exception {
        return new JSONObject(evalString(expression));
    }

    private JSONObject composerDomState() throws Exception {
        return evalJson("(() => {const input = document.querySelector('[data-testid=ssh-host]');"
                + "const active = document.activeElement;"
                + "const rect = input?.getBoundingClientRect();"
                + "return JSON.stringify({inputPresent: !!input, inputFocused: !!input && active === input,"
                + "activeTag: active?.tagName ?? null, activeId: active?.id ?? null,"
                + "inputTop: rect?.top ?? -1, inputBottom: rect?.bottom ?? -1,"
                + "innerHeight, visualViewportHeight: window.visualViewport?.height ?? innerHeight});})()");
    }

    private void awaitComposerInputSettled() throws Exception {
        long deadline = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS;
        JSONObject previous = null;
        JSONObject latest = composerDomState();
        int stableSamples = 0;
        while (SystemClock.uptimeMillis() < deadline) {
            boolean onScreen = latest.optDouble("inputTop") >= 0
                    && latest.optDouble("inputBottom") <= latest.optDouble("innerHeight");
            if (onScreen && previous != null
                    && Math.abs(latest.optDouble("inputTop") - previous.optDouble("inputTop")) < 0.5
                    && Math.abs(latest.optDouble("inputBottom") - previous.optDouble("inputBottom")) < 0.5) {
                stableSamples++;
                if (stableSamples >= 2) return;
            } else {
                stableSamples = 0;
            }
            previous = latest;
            Thread.sleep(100);
            latest = composerDomState();
        }
        throw new AssertionError("Composer input did not settle fully inside the WebView viewport: " + latest);
    }

    private void awaitComposerFocused() throws Exception {
        long deadline = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS;
        JSONObject latest = composerDomState();
        while (SystemClock.uptimeMillis() < deadline) {
            if (latest.optBoolean("inputFocused")) return;
            Thread.sleep(100);
            latest = composerDomState();
        }
        throw new AssertionError("Injected tap did not focus the SSH host input: DOM=" + latest
                + "; Android=" + nativeImeState());
    }

    private String nativeImeState() {
        AtomicReference<String> result = new AtomicReference<>("<not captured>");
        scenario.onActivity(activity -> {
            View decor = activity.getWindow().getDecorView();
            WindowInsets insets = decor.getRootWindowInsets();
            WebView webView = findWebView(decor);
            View focusedView = decor.findFocus();
            InputMethodManager inputMethodManager =
                    (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            int imeBottom = insets == null ? -1 : insets.getInsets(WindowInsets.Type.ime()).bottom;
            boolean imeVisible = insets != null && insets.isVisible(WindowInsets.Type.ime());
            int showWithHardwareKeyboard = Settings.Secure.getInt(
                    activity.getContentResolver(), "show_ime_with_hard_keyboard", -1);
            result.set("imeVisible=" + imeVisible
                    + ", imeBottom=" + imeBottom
                    + ", showImeWithHardKeyboard=" + showWithHardwareKeyboard
                    + ", webViewHasFocus=" + (webView != null && webView.hasFocus())
                    + ", webViewIsFocused=" + (webView != null && webView.isFocused())
                    + ", inputMethodActiveForWebView=" + (webView != null && inputMethodManager.isActive(webView))
                    + ", inputMethodAcceptingText=" + inputMethodManager.isAcceptingText()
                    + ", focusedView=" + (focusedView == null ? "none" : focusedView.getClass().getName())
                    + ", softInputMode=" + activity.getWindow().getAttributes().softInputMode);
        });
        return result.get();
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

    private void tapDomCenter(String selector) throws Exception {
        JSONObject point = evalJson("(() => {const element = document.querySelector("
                + JSONObject.quote(selector)
                + "); if (!element) return JSON.stringify({missing: true});"
                + "const rect = element.getBoundingClientRect();"
                + "return JSON.stringify({x: rect.left + rect.width / 2, y: rect.top + rect.height / 2, width: innerWidth});})()");
        assertTrue("WebView target element must exist", !point.optBoolean("missing"));

        AtomicReference<float[]> screenPoint = new AtomicReference<>();
        scenario.onActivity(activity -> {
            WebView webView = findWebView(activity.getWindow().getDecorView());
            int[] webViewLocation = new int[2];
            webView.getLocationOnScreen(webViewLocation);
            float pixelsPerCssPixel = webView.getWidth() / (float) point.optDouble("width");
            screenPoint.set(new float[] {
                    webViewLocation[0] + (float) point.optDouble("x") * pixelsPerCssPixel,
                    webViewLocation[1] + (float) point.optDouble("y") * pixelsPerCssPixel
            });
        });

        float[] pointOnScreen = screenPoint.get();
        assertNotNull("WebView tap position must be captured", pointOnScreen);
        long downTime = SystemClock.uptimeMillis();
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, pointOnScreen[0], pointOnScreen[1], 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        instrumentation.getUiAutomation().injectInputEvent(down, true);
        down.recycle();
        SystemClock.sleep(60);
        long upTime = SystemClock.uptimeMillis();
        MotionEvent up = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, pointOnScreen[0], pointOnScreen[1], 0);
        up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        instrumentation.getUiAutomation().injectInputEvent(up, true);
        up.recycle();
    }

    private int[] domRectOnScreen(String selector) throws Exception {
        JSONObject rect = evalJson("(() => {const box = document.querySelector("
                + JSONObject.quote(selector)
                + ").getBoundingClientRect();"
                + "return JSON.stringify({left: box.left, top: box.top, right: box.right, bottom: box.bottom, width: innerWidth});})()");
        AtomicReference<int[]> bounds = new AtomicReference<>();
        scenario.onActivity(activity -> {
            WebView webView = findWebView(activity.getWindow().getDecorView());
            int[] location = new int[2];
            webView.getLocationOnScreen(location);
            float scale = webView.getWidth() / (float) rect.optDouble("width");
            bounds.set(new int[] {
                    Math.round(location[0] + (float) rect.optDouble("left") * scale),
                    Math.round(location[1] + (float) rect.optDouble("top") * scale),
                    Math.round(location[0] + (float) rect.optDouble("right") * scale),
                    Math.round(location[1] + (float) rect.optDouble("bottom") * scale)
            });
        });
        return bounds.get();
    }

    private void awaitImeVisible(boolean visible) throws Exception {
        long deadline = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS;
        boolean last = !visible;
        int stableSamples = 0;
        while (SystemClock.uptimeMillis() < deadline) {
            AtomicReference<Boolean> state = new AtomicReference<>(false);
            scenario.onActivity(activity -> {
                WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
                state.set(insets != null && Build.VERSION.SDK_INT >= 30 && insets.isVisible(WindowInsets.Type.ime()));
            });
            last = state.get();
            if (last == visible) {
                stableSamples++;
                if (stableSamples >= 3) return;
            } else {
                stableSamples = 0;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("IME visibility did not become " + visible + " (last=" + last
                + "; DOM=" + composerDomState() + "; Android=" + nativeImeState() + ")");
    }

    /**
     * Native IME visibility drives the JS safe-area override. Wait for the
     * root CSS value, keyboard state, and actual shell padding to settle;
     * assertions still fail if navigation-bar padding remains under the IME.
     */
    private void awaitImeSafeAreaSettled(float expectedSafeTop) throws Exception {
        long deadline = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS;
        JSONObject previous = null;
        JSONObject latest = imeSafeAreaDomState();
        int stableSamples = 0;
        while (SystemClock.uptimeMillis() < deadline) {
            boolean correctInsets = closeTo(latest.optDouble("safeTop"), expectedSafeTop, 1.0)
                    && closeTo(latest.optDouble("safeBottom"), 0.0, 0.5)
                    && closeTo(latest.optDouble("paddingBottom"), 0.0, 0.5)
                    && latest.optBoolean("keyboardVisible");
            boolean stable = previous != null
                    && closeTo(latest.optDouble("safeTop"), previous.optDouble("safeTop"), 0.5)
                    && closeTo(latest.optDouble("safeBottom"), previous.optDouble("safeBottom"), 0.5)
                    && closeTo(latest.optDouble("paddingBottom"), previous.optDouble("paddingBottom"), 0.5)
                    && closeTo(latest.optDouble("viewportHeight"), previous.optDouble("viewportHeight"), 0.5);
            if (correctInsets && stable) {
                stableSamples++;
                if (stableSamples >= 2) return;
            } else {
                stableSamples = 0;
            }
            previous = latest;
            Thread.sleep(100);
            latest = imeSafeAreaDomState();
        }
        throw new AssertionError("Capacitor safe-area CSS did not settle after the IME opened: DOM=" + latest
                + "; Android=" + nativeImeState());
    }

    private JSONObject imeSafeAreaDomState() throws Exception {
        return evalJson("(() => {"
                + "const root = getComputedStyle(document.documentElement);"
                + "const shellStyle = getComputedStyle(document.querySelector('.app-shell'));"
                + "return JSON.stringify({"
                + "safeTop: parseFloat(root.getPropertyValue('--safe-area-inset-top')),"
                + "safeBottom: parseFloat(root.getPropertyValue('--safe-area-inset-bottom')),"
                + "paddingBottom: parseFloat(shellStyle.paddingBottom),"
                + "keyboardVisible: document.querySelector('.app-shell')?.dataset.keyboardVisible === 'true',"
                + "screenHeight: window.screen.height,"
                + "innerHeight: window.innerHeight,"
                + "viewportHeight: window.visualViewport ? window.visualViewport.height : window.innerHeight,"
                + "viewportScale: window.visualViewport ? window.visualViewport.scale : 1"
                + "});})()");
    }

    private static boolean closeTo(double actual, double expected, double tolerance) {
        return !Double.isNaN(actual) && Math.abs(actual - expected) <= tolerance;
    }

    private Insets readRootInsets(int typeMask) throws Exception {
        AtomicReference<Insets> result = new AtomicReference<>();
        scenario.onActivity(activity -> {
            WindowInsets windowInsets = activity.getWindow().getDecorView().getRootWindowInsets();
            assertNotNull("window insets must be available", windowInsets);
            result.set(windowInsets.getInsets(typeMask));
        });
        return result.get();
    }

    private int[] displaySize() {
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        scenario.onActivity(activity -> activity.getWindowManager().getDefaultDisplay().getRealMetrics(metrics));
        return new int[] {metrics.widthPixels, metrics.heightPixels};
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
