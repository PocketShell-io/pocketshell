package com.pocketshell.app.smoke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Insets;
import android.os.Build;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
    public void launchShowsVerifiedCoreAndAssetIdentity() throws Exception {
        JSONObject manifest = packagedManifest();
        String expectedRevision = manifest.getString("coreSourceRevision");
        String expectedAssetHash = manifest.getString("bundleAssetHash");

        assertTrue("manifest core revision must be a full git revision", expectedRevision.matches("[a-f0-9]{40}"));
        assertTrue("manifest aggregate asset hash must be SHA-256", expectedAssetHash.matches("[a-f0-9]{64}"));
        awaitJsTrue("document.querySelector('[data-testid=build-status] > span:nth-child(2)')?.textContent.trim() === 'Build verified'");

        String visibleIdentity = evalString("document.querySelector('.build-strip__detail')?.textContent.trim()");
        assertTrue("the visible build strip must identify the pinned core", visibleIdentity.contains(expectedRevision.substring(0, 12)));
        assertTrue("the visible build strip must identify the packaged assets", visibleIdentity.contains(expectedAssetHash.substring(0, 12)));
        assertEquals(expectedRevision, evalString("document.querySelector('[data-testid=core-revision]')?.textContent.trim()"));
        assertEquals(expectedAssetHash, evalString("document.querySelector('[data-testid=bundle-asset-hash]')?.textContent.trim()"));
        JSONObject statusBounds = evalJson("(() => {const node = document.querySelector('[data-testid=build-status]');"
                + "const rect = node.getBoundingClientRect();"
                + "return JSON.stringify({top: rect.top, bottom: rect.bottom, height: innerHeight});})()");
        assertTrue("verified status and short build identity must be on screen",
                statusBounds.getDouble("top") >= 0 && statusBounds.getDouble("bottom") <= statusBounds.getDouble("height"));
    }

    @Test
    public void settingsAndAndroidBackReturnHome() throws Exception {
        awaitJsTrue("document.querySelector('[aria-label=Settings]') !== null");
        tapDomCenter("[aria-label=Settings]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'settings' && !!document.querySelector('#settings-title')");

        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'home' && !!document.querySelector('#hosts-title')");
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

        evalString("(() => { const input = document.querySelector('#preview-input'); input.scrollIntoView({block: 'center', behavior: 'instant'}); return 'ready'; })()");
        tapDomCenter("#preview-input");
        awaitImeVisible(true);

        JSONObject duringIme = evalJson("(() => {"
                + "const root = getComputedStyle(document.documentElement);"
                + "const shell = document.querySelector('.app-shell');"
                + "const shellStyle = getComputedStyle(shell);"
                + "const input = document.querySelector('#preview-input').getBoundingClientRect();"
                + "return JSON.stringify({"
                + "safeTop: parseFloat(root.getPropertyValue('--safe-area-inset-top')),"
                + "safeBottom: parseFloat(root.getPropertyValue('--safe-area-inset-bottom')),"
                + "paddingTop: parseFloat(shellStyle.paddingTop),"
                + "paddingBottom: parseFloat(shellStyle.paddingBottom),"
                + "inputTop: input.top,inputBottom: input.bottom,"
                + "viewportHeight: window.visualViewport ? window.visualViewport.height : window.innerHeight"
                + "});})()");
        assertEquals("top system bar clearance must persist while the IME is open", expectedSafeTop, duringIme.getDouble("safeTop"), 1.0);
        assertEquals("IME inset must replace the navigation safe-area padding", 0.0, duringIme.getDouble("safeBottom"), 1.0);
        assertEquals("safe-area padding must remain clear of the IME", 0.0, duringIme.getDouble("paddingBottom"), 1.0);
        assertTrue("the composer input must be above the visual viewport bottom",
                duringIme.getDouble("inputBottom") <= duringIme.getDouble("viewportHeight") + 1.0);

        int imeBottom = readRootInsets(WindowInsets.Type.ime()).bottom;
        assertTrue("the platform must report an open IME inset", imeBottom > 0);
        int[] inputScreenBounds = domRectOnScreen("#preview-input");
        int[] displayMetrics = displaySize();
        int imeTopOnScreen = displayMetrics[1] - imeBottom;
        assertTrue("the focused composer input must remain above the physical IME", inputScreenBounds[1] <= imeTopOnScreen);

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

    private String evalString(String expression) throws Exception {
        String raw = evalRaw(expression);
        Object decoded = new JSONTokener(raw).nextValue();
        return decoded == null ? null : decoded.toString();
    }

    private JSONObject evalJson(String expression) throws Exception {
        return new JSONObject(evalString(expression));
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
        while (SystemClock.uptimeMillis() < deadline) {
            AtomicReference<Boolean> state = new AtomicReference<>(false);
            scenario.onActivity(activity -> {
                WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
                state.set(insets != null && Build.VERSION.SDK_INT >= 30 && insets.isVisible(WindowInsets.Type.ime()));
            });
            last = state.get();
            if (last == visible) return;
            Thread.sleep(100);
        }
        throw new AssertionError("IME visibility did not become " + visible + " (last=" + last + ")");
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
