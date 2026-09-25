package com.pocketshell.app.smoke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Real packaged-WebView interaction journey for mobile Voice settings. */
@RunWith(AndroidJUnit4.class)
public final class J24SettingsReachJourney {
    private static final long JS_TIMEOUT_SECONDS = 15;
    private static final long WAIT_TIMEOUT_MILLIS = 15_000;
    private static final String SETTINGS_KEY = "pocketshell.js.settings.v1";

    private ActivityScenario<MainActivity> scenario;
    private String artifactRunId;
    private JSONObject journeyEvidence;

    @Before
    public void launchPackagedShell() {
        AccessibilityServiceInfo serviceInfo = new AccessibilityServiceInfo();
        serviceInfo.eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                | AccessibilityEvent.TYPE_VIEW_FOCUSED
                | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        serviceInfo.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        serviceInfo.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        serviceInfo.notificationTimeout = 100;
        InstrumentationRegistry.getInstrumentation().getUiAutomation().setServiceInfo(serviceInfo);
        scenario = ActivityScenario.launch(MainActivity.class);
    }

    @After
    public void closeShell() {
        if (scenario != null) scenario.close();
    }

    @Test
    public void voiceLanguageAndSilenceControlsPersistAcrossNavigationAndActivityRelaunch() throws Exception {
        assertTrue("Voice settings geometry and accessibility checks require API 35+", Build.VERSION.SDK_INT >= 35);
        artifactRunId = InstrumentationRegistry.getArguments().getString(
                "artifactRunId", "js2857-voice-" + System.currentTimeMillis());
        assertTrue("artifact run id must be tag safe", artifactRunId.matches("[A-Za-z0-9_-]{3,48}"));
        journeyEvidence = new JSONObject()
                .put("schema", 1)
                .put("runId", artifactRunId)
                .put("api", Build.VERSION.SDK_INT)
                .put("targetPackage", InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName());

        awaitBuildVerified();
        JSONObject launchState = readSettingsSnapshot();
        assertEquals("the clean packaged run must start with automatic language detection",
                "auto", launchState.getJSONObject("saved").optString("dictationLanguageTag", "auto"));
        assertEquals("the clean packaged run must start at the product's four-second default",
                4_000, launchState.getJSONObject("saved").optInt("dictationSilenceWindowMs", 4_000));

        openVoiceSettings();
        JSONObject initial = captureVoiceScreen("voice-settings-controls.png");
        journeyEvidence.put("initial", initial);
        assertVoiceControls(initial, "auto", 4, false);
        JSONArray initialAccessibility = captureAccessibilityTree();
        journeyEvidence.put("initialAccessibilityTree", initialAccessibility);
        JSONObject initialAccessibilityStatus = accessibilityCaptureStatus(initialAccessibility);
        journeyEvidence.put("nativeAccessibilityCapture", initialAccessibilityStatus);
        saveEvidenceJson();
        assertNativeWebViewHost(initialAccessibility);
        if (initialAccessibilityStatus.getBoolean("webVirtualDescendantsAvailable")) {
            assertTalkBackSemantics(initialAccessibility);
        }

        enterTextThroughKeyboard("[data-testid=setting-dictation-language]", "de-DE");
        awaitJsTrue("JSON.parse(localStorage.getItem(" + JSONObject.quote(SETTINGS_KEY)
                + ") || '{}').dictationLanguageTag === 'de-DE'");
        dragSilenceSliderTo(9);
        awaitJsTrue("document.querySelector('[data-testid=setting-dictation-silence]')?.value === '9'"
                + " && document.querySelector('[data-testid=dictation-silence-value]')?.textContent.trim() === '9 seconds'"
                + " && JSON.parse(localStorage.getItem(" + JSONObject.quote(SETTINGS_KEY)
                + ") || '{}').dictationSilenceWindowMs === 9000");
        JSONObject changed = captureVoiceScreen("voice-settings-changed.png");
        journeyEvidence.put("changed", changed);
        assertVoiceControls(changed, "de-DE", 9, false);
        saveEvidenceJson();

        leaveVoiceSettingsForSettings();
        openVoiceFromSettings();
        JSONObject afterNavigation = captureVoiceScreen("voice-settings-after-navigation.png");
        journeyEvidence.put("afterNavigation", afterNavigation);
        assertVoiceControls(afterNavigation, "de-DE", 9, false);
        saveEvidenceJson();

        relaunchActivity();
        openVoiceSettings();
        JSONObject afterRelaunch = captureVoiceScreen("voice-settings-after-activity-relaunch.png");
        journeyEvidence.put("afterActivityRelaunch", afterRelaunch);
        assertVoiceControls(afterRelaunch, "de-DE", 9, false);
        saveEvidenceJson();

        enterTextThroughKeyboard("[data-testid=setting-dictation-language]", "auto");
        awaitJsTrue("JSON.parse(localStorage.getItem(" + JSONObject.quote(SETTINGS_KEY)
                + ") || '{}').dictationLanguageTag === 'auto'");
        JSONObject automatic = captureVoiceScreen("voice-settings-auto-language.png");
        journeyEvidence.put("explicitAutoLanguage", automatic);
        assertVoiceControls(automatic, "auto", 9, false);
        saveEvidenceJson();

        enterTextThroughKeyboard("[data-testid=setting-dictation-language]", "de-DE");
        awaitJsTrue("JSON.parse(localStorage.getItem(" + JSONObject.quote(SETTINGS_KEY)
                + ") || '{}').dictationLanguageTag === 'de-DE'");

        enterTextThroughKeyboard("[data-testid=setting-dictation-language]", "en_US");
        awaitJsTrue("document.querySelector('[data-testid=setting-dictation-language]')?.getAttribute('aria-invalid') === 'true'"
                + " && document.querySelector('[data-testid=setting-dictation-language]')?.getAttribute('aria-describedby')"
                + " === 'dictation-language-help dictation-language-error'"
                + " && document.querySelector('[data-testid=setting-dictation-language]')?.value === 'de-DE'"
                + " && document.querySelector('#dictation-language-error[role=alert]') !== null"
                + " && JSON.parse(localStorage.getItem(" + JSONObject.quote(SETTINGS_KEY)
                + ") || '{}').dictationLanguageTag === 'de-DE'");
        JSONObject invalid = captureVoiceScreen("voice-settings-invalid-language.png");
        journeyEvidence.put("invalidLanguage", invalid);
        assertVoiceControls(invalid, "de-DE", 9, true);
        JSONArray invalidAccessibility = captureAccessibilityTree();
        journeyEvidence.put("invalidLanguageAccessibilityTree", invalidAccessibility);
        JSONObject invalidAccessibilityStatus = accessibilityCaptureStatus(invalidAccessibility);
        journeyEvidence.put("invalidNativeAccessibilityCapture", invalidAccessibilityStatus);
        if (invalidAccessibilityStatus.getBoolean("webVirtualDescendantsAvailable")) {
            String invalidTree = invalidAccessibility.toString().toLowerCase(Locale.ROOT);
            assertTrue("native accessibility tree must announce the invalid BCP-47 explanation",
                    invalidTree.contains("valid bcp-47"));
        }
        assertTrue("invalid BCP-47 input must expose its actionable DOM validation explanation",
                findControl(invalid, "language").getString("error").contains("valid BCP-47"));
        saveEvidenceJson();
    }

    private void openVoiceSettings() throws Exception {
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'home'"
                + " && document.querySelector('[aria-label=Settings]') !== null");
        tapDomCenter("[aria-label=Settings]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'settings'"
                + " && document.querySelector('#settings-title') !== null");
        openVoiceFromSettings();
    }

    private void openVoiceFromSettings() throws Exception {
        tapDomCenter("[data-testid=open-voice-settings]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'settings-voice'"
                + " && document.querySelector('[data-testid=voice-settings-screen]') !== null");
    }

    private void leaveVoiceSettingsForSettings() throws Exception {
        if (isImeVisible()) {
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            awaitImeVisible(false);
        }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'settings'"
                + " && document.querySelector('[data-testid=open-voice-settings]') !== null");
    }

    private void relaunchActivity() throws Exception {
        if (scenario != null) {
            scenario.close();
            scenario = null;
        }
        journeyEvidence.put("activityRelaunchMethod", "closed the prior ActivityScenario and launched a fresh MainActivity");
        scenario = ActivityScenario.launch(MainActivity.class);
        awaitBuildVerified();
    }

    private JSONObject captureVoiceScreen(String screenshotName) throws Exception {
        JSONObject dom = readSettingsSnapshot();
        JSONArray controls = new JSONArray()
                .put(dom.getJSONObject("language"))
                .put(dom.getJSONObject("silence"));
        JSONObject screen = new JSONObject()
                .put("route", dom.optString("route"))
                .put("viewport", dom.getJSONObject("viewport"))
                .put("saved", dom.getJSONObject("saved"))
                .put("controls", controls);
        byte[] screenshot = captureScreenshot();
        writeAppFile(screenshotName, screenshot);
        screen.put("screenshot", screenshotName);
        return screen;
    }

    private JSONObject readSettingsSnapshot() throws Exception {
        JSONObject dom = evalJson("(() => {"
                + "const shell=document.querySelector('.app-shell');"
                + "const language=document.querySelector('[data-testid=setting-dictation-language]');"
                + "const silence=document.querySelector('[data-testid=setting-dictation-silence]');"
                + "const value=document.querySelector('[data-testid=dictation-silence-value]');"
                + "const box=(element)=>{if(!element)return null;const r=element.getBoundingClientRect();"
                + "const s=getComputedStyle(element);return {left:r.left,top:r.top,right:r.right,bottom:r.bottom,width:r.width,height:r.height,"
                + "minHeight:s.minHeight,display:s.display,visible:r.width>0&&r.height>0};};"
                + "const languageLabel=document.getElementById('dictation-language-label');"
                + "const languageHelp=document.getElementById('dictation-language-help');"
                + "const described=(language?.getAttribute('aria-describedby')||'').split(/\\s+/).filter(Boolean);"
                + "const saved=JSON.parse(localStorage.getItem(" + JSONObject.quote(SETTINGS_KEY) + ")||'{}');"
                + "return JSON.stringify({route:shell?.dataset.route||'',viewport:{width:innerWidth,height:innerHeight,"
                + "visualHeight:visualViewport?.height??innerHeight,devicePixelRatio,scrollY:document.querySelector('.screen-content')?.scrollTop??0},"
                + "saved,language:{value:language?.value??null,tagName:language?.tagName??null,type:language?.type??null,"
                + "implicitRole:language?.type==='text'?'textbox':null,label:languageLabel?.textContent.trim()??null,"
                + "labels:Array.from(language?.labels||[]).map(label=>label.innerText.trim()),help:languageHelp?.textContent.trim()??null,"
                + "ariaLabelledby:language?.getAttribute('aria-labelledby'),ariaDescribedby:language?.getAttribute('aria-describedby'),"
                + "ariaInvalid:language?.getAttribute('aria-invalid'),error:document.querySelector('#dictation-language-error')?.textContent.trim()??null,"
                + "errorRole:document.querySelector('#dictation-language-error')?.getAttribute('role')??null,"
                + "describedTargets:described.map(id=>({id,exists:!!document.getElementById(id)})),bounds:box(language)},"
                + "silence:{value:silence?.value??null,tagName:silence?.tagName??null,type:silence?.type??null,"
                + "implicitRole:silence?.type==='range'?'slider':null,min:silence?.min??null,max:silence?.max??null,step:silence?.step??null,"
                + "ariaLabel:silence?.getAttribute('aria-label'),ariaValueText:silence?.getAttribute('aria-valuetext'),"
                + "output:value?.textContent.trim()??null,live:value?.getAttribute('aria-live')??null,bounds:box(silence)}});"
                + "})()");
        addScreenBounds(dom);
        return dom;
    }

    private void addScreenBounds(JSONObject dom) throws Exception {
        if (!"settings-voice".equals(dom.optString("route"))) return;
        JSONObject viewport = dom.getJSONObject("viewport");
        double cssWidth = viewport.getDouble("width");
        AtomicReference<int[]> locationAndWidth = new AtomicReference<>();
        runOnCurrentActivity(activity -> {
            WebView webView = findWebView(activity.getWindow().getDecorView());
            assertNotNull("the packaged activity must contain its Capacitor WebView", webView);
            int[] location = new int[2];
            webView.getLocationOnScreen(location);
            locationAndWidth.set(new int[] { location[0], location[1], webView.getWidth() });
        });
        int[] host = locationAndWidth.get();
        assertNotNull("WebView screen geometry must be available", host);
        double scale = host[2] / cssWidth;
        JSONArray controls = new JSONArray();
        for (String key : new String[] { "language", "silence" }) {
            JSONObject control = dom.getJSONObject(key);
            JSONObject bounds = control.getJSONObject("bounds");
            JSONObject screen = new JSONObject()
                    .put("left", Math.round(host[0] + bounds.getDouble("left") * scale))
                    .put("top", Math.round(host[1] + bounds.getDouble("top") * scale))
                    .put("right", Math.round(host[0] + bounds.getDouble("right") * scale))
                    .put("bottom", Math.round(host[1] + bounds.getDouble("bottom") * scale));
            control.put("screenBounds", screen);
            controls.put(screen);
        }
        dom.put("webViewScreenBounds", new JSONObject()
                .put("left", host[0]).put("top", host[1]).put("width", host[2]));
    }

    private void assertVoiceControls(JSONObject state, String language, int silence, boolean invalid) throws Exception {
        assertEquals("real app must remain on the Voice settings route", "settings-voice", state.getString("route"));
        JSONObject viewport = state.getJSONObject("viewport");
        JSONObject languageControl = findControl(state, "language");
        JSONObject silenceControl = findControl(state, "silence");
        assertEquals(language, languageControl.getString("value"));
        assertEquals("INPUT", languageControl.getString("tagName"));
        assertEquals("text", languageControl.getString("type"));
        assertEquals("textbox", languageControl.getString("implicitRole"));
        assertEquals(language, state.getJSONObject("saved").optString("dictationLanguageTag", "auto"));
        assertEquals(silence * 1_000, state.getJSONObject("saved").optInt("dictationSilenceWindowMs", silence * 1_000));
        assertEquals(Integer.toString(silence), silenceControl.getString("value"));
        assertEquals("INPUT", silenceControl.getString("tagName"));
        assertEquals("range", silenceControl.getString("type"));
        assertEquals("slider", silenceControl.getString("implicitRole"));
        assertEquals(silence + " seconds", silenceControl.getString("output"));
        assertEquals(silence + " seconds", silenceControl.getString("ariaValueText"));
        assertEquals("2", silenceControl.getString("min"));
        assertEquals("60", silenceControl.getString("max"));
        assertEquals("1", silenceControl.getString("step"));
        assertEquals("48px", languageControl.getJSONObject("bounds").getString("minHeight"));
        assertEquals("48px", silenceControl.getJSONObject("bounds").getString("minHeight"));
        assertTrue("language control has a real 48 CSS-pixel hit box", languageControl.getJSONObject("bounds").getDouble("height") >= 48);
        assertTrue("silence slider has a real 48 CSS-pixel hit box", silenceControl.getJSONObject("bounds").getDouble("height") >= 48);
        assertTrue("language field is on screen in the real WebView", onScreen(languageControl, viewport));
        assertTrue("silence slider is on screen in the real WebView", onScreen(silenceControl, viewport));
        assertEquals("dictation-language-label", languageControl.getString("ariaLabelledby"));
        assertEquals("Dictation language", languageControl.getString("label"));
        assertTrue("language field's visible hint must explain the automatic and BCP-47 choices",
                languageControl.getString("help").contains("Use auto")
                        && languageControl.getString("help").contains("BCP-47"));
        assertEquals("dictation-language-help", languageControl.getString("ariaDescribedby").split(" ")[0]);
        JSONArray describedTargets = languageControl.getJSONArray("describedTargets");
        assertTrue("the language help target named by aria-describedby must exist",
                describedTargets.getJSONObject(0).getBoolean("exists"));
        assertEquals(invalid ? "true" : "false", languageControl.getString("ariaInvalid"));
        if (invalid) {
            assertEquals("alert", languageControl.getString("errorRole"));
            assertTrue("invalid BCP-47 hint gives an actionable error", languageControl.getString("error").contains("valid BCP-47"));
            assertEquals("de-DE", state.getJSONObject("saved").optString("dictationLanguageTag"));
            assertEquals("aria-describedby must add the validation error node", 2, describedTargets.length());
            assertTrue("the described validation error node must exist", describedTargets.getJSONObject(1).getBoolean("exists"));
        } else {
            assertEquals("null", languageControl.optString("errorRole", "null"));
        }
    }

    private JSONObject findControl(JSONObject state, String name) throws Exception {
        return state.getJSONArray("controls").getJSONObject("language".equals(name) ? 0 : 1);
    }

    private boolean onScreen(JSONObject control, JSONObject viewport) throws Exception {
        JSONObject box = control.getJSONObject("bounds");
        return box.getDouble("top") >= 0
                && box.getDouble("bottom") <= viewport.getDouble("visualHeight")
                && box.getDouble("left") >= 0
                && box.getDouble("right") <= viewport.getDouble("width");
    }

    private JSONArray captureAccessibilityTree() throws Exception {
        awaitWebViewDrawn();
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        AccessibilityNodeInfo root = InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();
        assertNotNull("Android accessibility service must expose the active packaged window", root);
        JSONArray nodes = new JSONArray();
        appendAccessibilityNode(root, nodes, 0);
        root.recycle();
        return nodes;
    }

    private void appendAccessibilityNode(AccessibilityNodeInfo node, JSONArray output, int depth) throws Exception {
        if (depth > 18) return;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        JSONObject item = new JSONObject()
                .put("depth", depth)
                .put("className", charSequence(node.getClassName()))
                .put("text", charSequence(node.getText()))
                .put("contentDescription", charSequence(node.getContentDescription()))
                .put("viewId", node.getViewIdResourceName())
                .put("editable", node.isEditable())
                .put("clickable", node.isClickable())
                .put("focusable", node.isFocusable())
                .put("visibleToUser", node.isVisibleToUser())
                .put("bounds", new JSONObject().put("left", bounds.left).put("top", bounds.top)
                        .put("right", bounds.right).put("bottom", bounds.bottom));
        AccessibilityNodeInfo.RangeInfo range = node.getRangeInfo();
        if (range != null) {
            item.put("range", new JSONObject().put("type", range.getType()).put("min", range.getMin())
                    .put("max", range.getMax()).put("current", range.getCurrent()));
        }
        output.put(item);
        for (int index = 0; index < node.getChildCount(); index += 1) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child != null) {
                appendAccessibilityNode(child, output, depth + 1);
                child.recycle();
            }
        }
    }

    private JSONObject accessibilityCaptureStatus(JSONArray nodes) throws Exception {
        boolean hasWebView = false;
        boolean hasWebVirtualDescendants = false;
        for (int index = 0; index < nodes.length(); index += 1) {
            String className = nodes.getJSONObject(index).optString("className");
            if ("android.webkit.WebView".equals(className)) hasWebView = true;
            if (index > 6) hasWebVirtualDescendants = true;
        }
        return new JSONObject()
                .put("webViewHostVisible", hasWebView)
                .put("webVirtualDescendantsAvailable", hasWebVirtualDescendants)
                .put("limitation", hasWebVirtualDescendants ? JSONObject.NULL
                        : "API 35 test image UiAutomation exposes the native WebView host but no Chromium virtual descendants, even with enhanced web accessibility enabled.");
    }

    private void assertNativeWebViewHost(JSONArray nodes) throws Exception {
        assertTrue("Android accessibility tree must at least expose the packaged WebView host",
                accessibilityCaptureStatus(nodes).getBoolean("webViewHostVisible"));
    }

    private void assertTalkBackSemantics(JSONArray nodes) {
        String tree = nodes.toString().toLowerCase(Locale.ROOT);
        assertTrue("TalkBack tree must announce the language label", tree.contains("dictation language"));
        assertTrue("TalkBack tree must expose the language input as editable", tree.contains("\"editable\":true"));
        assertTrue("TalkBack tree must announce the recognition silence window", tree.contains("recognition silence window in seconds"));
        assertTrue("TalkBack tree must expose the silence window as a SeekBar", tree.contains("android.widget.seekbar"));
    }

    private void enterTextThroughKeyboard(String selector, String value) throws Exception {
        tapDomCenter(selector);
        awaitJsTrue("document.activeElement === document.querySelector(" + JSONObject.quote(selector) + ")");
        awaitImeVisible(true);
        selectAllWithHardwareKeys();
        InstrumentationRegistry.getInstrumentation().sendStringSync(value);
        awaitJsTrue("document.querySelector(" + JSONObject.quote(selector) + ")?.value === " + JSONObject.quote(value));
        tapDomCenter("#voice-settings-title");
        awaitJsTrue("document.activeElement !== document.querySelector(" + JSONObject.quote(selector) + ")");
        if (isImeVisible()) {
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            awaitImeVisible(false);
        }
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'settings-voice'"
                + " && document.querySelector(" + JSONObject.quote(selector) + ") !== null");
    }

    private void selectAllWithHardwareKeys() {
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        long down = SystemClock.uptimeMillis();
        injectKey(instrumentation, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, 0);
        injectKey(instrumentation, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON);
        injectKey(instrumentation, down, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON);
        injectKey(instrumentation, down, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, 0);
    }

    private void injectKey(android.app.Instrumentation instrumentation, long down, int action, int code, int meta) {
        long now = SystemClock.uptimeMillis();
        KeyEvent event = new KeyEvent(down, now, action, code, 0, meta);
        InstrumentationRegistry.getInstrumentation().getUiAutomation().injectInputEvent(event, true);
    }

    private void dragSilenceSliderTo(int targetSeconds) throws Exception {
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'settings-voice'"
                + " && document.querySelector('[data-testid=setting-dictation-silence]')?.type === 'range'");
        awaitWebViewDrawn();
        JSONObject geometry = evalJson("(() => {const input=document.querySelector('[data-testid=setting-dictation-silence]');"
                + "if(!input)return JSON.stringify({missing:true,route:document.querySelector('.app-shell')?.dataset.route??null});"
                + "const r=input.getBoundingClientRect();return JSON.stringify({left:r.left,top:r.top,width:r.width,height:r.height,"
                + "min:Number(input.min),max:Number(input.max),value:Number(input.value),viewportWidth:innerWidth});})()");
        assertTrue("real Voice route must retain its silence slider before touch drag; state=" + geometry,
                !geometry.optBoolean("missing"));
        double fraction = (targetSeconds - geometry.getDouble("min"))
                / (geometry.getDouble("max") - geometry.getDouble("min"));
        double currentFraction = (geometry.getDouble("value") - geometry.getDouble("min"))
                / (geometry.getDouble("max") - geometry.getDouble("min"));
        double thumbInset = Math.min(10, geometry.getDouble("width") / 8.0);
        float startX = (float) (geometry.getDouble("left") + thumbInset
                + currentFraction * (geometry.getDouble("width") - 2 * thumbInset));
        float endX = (float) (geometry.getDouble("left") + thumbInset
                + fraction * (geometry.getDouble("width") - 2 * thumbInset));
        float y = (float) (geometry.getDouble("top") + geometry.getDouble("height") / 2.0);
        float[] points = webCssPointsToScreen(startX, endX, y, geometry.getDouble("viewportWidth"));
        long downTime = SystemClock.uptimeMillis();
        injectTouch(MotionEvent.ACTION_DOWN, downTime, downTime, points[0], points[1]);
        int steps = 12;
        for (int index = 1; index <= steps; index += 1) {
            float fractionAlong = index / (float) steps;
            injectTouch(MotionEvent.ACTION_MOVE, downTime, SystemClock.uptimeMillis(),
                    points[0] + (points[2] - points[0]) * fractionAlong, points[1]);
            SystemClock.sleep(16);
        }
        injectTouch(MotionEvent.ACTION_UP, downTime, SystemClock.uptimeMillis(), points[2], points[1]);
        SystemClock.sleep(150);
    }

    private float[] webCssPointsToScreen(float startX, float endX, float y, double viewportWidth) {
        AtomicReference<float[]> screen = new AtomicReference<>();
        runOnCurrentActivity(activity -> {
            WebView webView = findWebView(activity.getWindow().getDecorView());
            int[] location = new int[2];
            webView.getLocationOnScreen(location);
            float scale = webView.getWidth() / (float) viewportWidth;
            screen.set(new float[] {
                    location[0] + startX * scale,
                    location[1] + y * scale,
                    location[0] + endX * scale,
                    location[1] + y * scale,
            });
        });
        return screen.get();
    }

    private void injectTouch(int action, long downTime, long eventTime, float x, float y) {
        MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        InstrumentationRegistry.getInstrumentation().getUiAutomation().injectInputEvent(event, true);
        event.recycle();
    }

    private void tapDomCenter(String selector) throws Exception {
        JSONObject point = evalJson("(() => {const element=document.querySelector(" + JSONObject.quote(selector) + ");"
                + "if(!element)return JSON.stringify({missing:true});const r=element.getBoundingClientRect();"
                + "return JSON.stringify({x:r.left+r.width/2,y:r.top+r.height/2,viewportWidth:innerWidth});})()");
        assertTrue("real WebView target must exist: " + selector, !point.optBoolean("missing"));
        float[] screen = webCssPointsToScreen((float) point.getDouble("x"), (float) point.getDouble("x"),
                (float) point.getDouble("y"), point.getDouble("viewportWidth"));
        long downTime = SystemClock.uptimeMillis();
        injectTouch(MotionEvent.ACTION_DOWN, downTime, downTime, screen[0], screen[1]);
        SystemClock.sleep(60);
        injectTouch(MotionEvent.ACTION_UP, downTime, SystemClock.uptimeMillis(), screen[0], screen[1]);
    }

    private void awaitBuildVerified() throws Exception {
        awaitJsTrue("document.querySelector('[data-testid=build-status] > span:nth-child(2)')?.textContent.trim() === 'Build verified'");
    }

    private void awaitJsTrue(String expression) throws Exception {
        long deadline = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS;
        String last = "<no result>";
        while (SystemClock.uptimeMillis() < deadline) {
            last = evalString(expression);
            if ("true".equals(last)) return;
            SystemClock.sleep(100);
        }
        assertTrue("timed out waiting for packaged WebView expression; last result=" + last + "; expression=" + expression, false);
    }

    private JSONObject evalJson(String expression) throws Exception {
        String value = evalString(expression);
        Object parsed = new JSONTokener(value).nextValue();
        assertTrue("packaged WebView expression must return a JSON object: " + value, parsed instanceof JSONObject);
        return (JSONObject) parsed;
    }

    private String evalString(String expression) throws Exception {
        String raw = evalRaw(expression);
        Object decoded = new JSONTokener(raw).nextValue();
        return decoded == null ? "null" : decoded.toString();
    }

    private String evalRaw(String expression) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        runOnCurrentActivity(activity -> {
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

    private void runOnCurrentActivity(Consumer<MainActivity> action) {
        scenario.onActivity(action::accept);
    }

    private byte[] captureScreenshot() throws Exception {
        awaitWebViewDrawn();
        Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull("real app screenshot must be present", screenshot);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            assertTrue("real app screenshot must encode as PNG", screenshot.compress(Bitmap.CompressFormat.PNG, 100, output));
            byte[] bytes = output.toByteArray();
            assertTrue("real app screenshot must have rendered content", bytes.length >= 1_024);
            return bytes;
        } finally {
            screenshot.recycle();
        }
    }

    private void awaitWebViewDrawn() throws Exception {
        CountDownLatch drawn = new CountDownLatch(1);
        runOnCurrentActivity(activity -> {
            WebView webView = findWebView(activity.getWindow().getDecorView());
            assertNotNull("the packaged activity must contain its Capacitor WebView", webView);
            webView.postVisualStateCallback(SystemClock.uptimeMillis(), new WebView.VisualStateCallback() {
                @Override
                public void onComplete(long requestId) {
                    drawn.countDown();
                }
            });
        });
        assertTrue("packaged WebView did not draw its latest Voice settings state",
                drawn.await(JS_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        SystemClock.sleep(500);
    }

    private void writeAppFile(String name, byte[] bytes) throws Exception {
        File destination = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getFilesDir(), name);
        try (FileOutputStream output = new FileOutputStream(destination)) {
            output.write(bytes);
        }
    }

    private void saveEvidenceJson() throws Exception {
        writeAppFile("voice-settings-journey.json", journeyEvidence.toString(2).getBytes(StandardCharsets.UTF_8));
    }

    private String charSequence(CharSequence value) {
        return value == null ? "" : value.toString();
    }

    private boolean isImeVisible() {
        AtomicReference<Boolean> visible = new AtomicReference<>(false);
        runOnCurrentActivity(activity -> {
            WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
            visible.set(insets != null && Build.VERSION.SDK_INT >= 30 && insets.isVisible(WindowInsets.Type.ime()));
        });
        return visible.get();
    }

    private void awaitImeVisible(boolean expected) throws Exception {
        long deadline = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS;
        while (SystemClock.uptimeMillis() < deadline) {
            if (isImeVisible() == expected) return;
            SystemClock.sleep(100);
        }
        assertEquals("Android IME visibility", expected, isImeVisible());
    }

    private WebView findWebView(View view) {
        if (view instanceof WebView) return (WebView) view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index += 1) {
            WebView match = findWebView(group.getChildAt(index));
            if (match != null) return match;
        }
        return null;
    }
}
