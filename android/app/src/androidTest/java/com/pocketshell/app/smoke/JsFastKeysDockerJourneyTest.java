package com.pocketshell.app.smoke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Insets;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Real packaged fast-key controls with API 35 IME geometry and a Docker PTY byte oracle. */
@RunWith(AndroidJUnit4.class)
public final class JsFastKeysDockerJourneyTest {
    private static final String ASSET_TAG = "PS2884Asset";
    private static final long WAIT_TIMEOUT_MILLIS = 45_000;
    private static final long JS_TIMEOUT_SECONDS = 15;
    private static final int ASSET_CHUNK_SIZE = 2_800;

    private ActivityScenario<MainActivity> scenario;
    private String artifactRunId;
    private String firstSession;
    private JSONObject journey = new JSONObject();
    private JSONArray geometryTrace = new JSONArray();

    @Before
    public void launchPackagedShell() {
        scenario = ActivityScenario.launch(MainActivity.class);
    }

    @After
    public void closeShell() {
        if (scenario != null) scenario.close();
    }

    @Test
    public void fastKeysStayReachableAndWriteExactBytesAcrossImeBackAndReconnect() throws Exception {
        assertTrue("fast-key screen assertions require Android API 35+", Build.VERSION.SDK_INT >= 35);
        var arguments = InstrumentationRegistry.getArguments();
        String host = arguments.getString("sshHost", "10.0.2.2");
        String port = arguments.getString("sshPort");
        String encodedKey = arguments.getString("sshPrivateKeyBase64");
        String nameBase = arguments.getString("sshSessionName");
        artifactRunId = arguments.getString("artifactRunId", nameBase);
        assertNotNull("pass the Docker fixture port with sshPort", port);
        assertNotNull("pass the fixture key with sshPrivateKeyBase64", encodedKey);
        assertNotNull("pass a unique fast-key session prefix with sshSessionName", nameBase);
        firstSession = nameBase + "-keys";
        String privateKey = new String(Base64.getDecoder().decode(encodedKey), StandardCharsets.UTF_8);

        awaitJsTrue("document.querySelector('[data-testid=build-status] > span:nth-child(2)')?.textContent.trim() === 'Build verified'");
        evalString("window.__ps2857CaptureTerminalEvidence = true; window.__ps2884HotkeyWrites = [];"
                + "window.__ps2884PointerEvents = [];"
                + "for (const type of ['pointerdown','pointerup','pointercancel','click']) window.addEventListener(type, event => {"
                + "const button=event.target instanceof Element ? event.target.closest('button') : null;"
                + "window.__ps2884PointerEvents.push({type,pointerId:event.pointerId??null,detail:event.detail??null,"
                + "key:button?.dataset.keyId??button?.getAttribute('aria-label')??null,defaultPrevented:event.defaultPrevented,"
                + "activeElement:document.activeElement?.getAttribute('data-testid')??document.activeElement?.tagName??null});"
                + "}); 'evidence enabled'");
        connect(host, port, privateKey);
        createSession(firstSession);
        attachSession(firstSession);
        awaitTerminalResizeIdle();

        String firstRaw = "/tmp/" + firstSession + "-bytes.raw";
        String firstReady = "PS2884_READY_" + nameBase;
        String firstDone = "PS2884_DONE_" + nameBase;
        prepareByteCapture(firstRaw, 19, firstReady, firstDone);
        tapDomCenter("[data-testid=prompt-draft]");
        awaitImeVisible(true);
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.keyboardVisible === 'true'"
                + " && document.querySelector('.app-shell')?.dataset.keyboardComposerMode === 'true'");
        awaitJsTrue("document.querySelector('[data-testid=mobile-hotkeys]')?.dataset.enabled === 'true'"
                + " && document.querySelector('[data-testid=mobile-hotkeys]')?.dataset.keyboardVisible === 'true'");
        JSONObject keyboardGeometry = captureGeometry("keyboard-up-compact-row");
        assertHotkeyBarReachable(keyboardGeometry);
        assertTrue("keyboard-up screenshot must include visible Android IME", isImeVisible());
        captureScreenshot("fastkeys-ime-open.png");

        tapDomCenter("[data-key-id='arrow-up']");
        awaitHotkeyWrites(1);
        awaitImeVisible(true, 3_000);
        tapDomCenter("[data-key-id='arrow-down']");
        awaitHotkeyWrites(2);
        awaitImeVisible(true, 3_000);
        JSONObject afterNavigationTaps = captureGeometry("after-navigation-row-taps");
        assertTrue("quick navigation taps must keep the keyboard row active", afterNavigationTaps.getBoolean("keyboardVisible"));
        assertTrue("quick navigation taps must leave the Android IME open", afterNavigationTaps.getJSONObject("androidIme").getBoolean("visible"));

        int resizeAcksBeforePalette = terminalResizeAcks();
        JSONObject gridBeforePalette = runtimeGrid(captureGeometry("before-palette"));
        tapDomCenter("[data-testid=mobile-hotkeys-launcher]");
        awaitJsTrue("document.querySelector('[data-testid=mobile-hotkeys]')?.dataset.paletteOpen === 'true'");
        SystemClock.sleep(300);
        JSONObject paletteGeometry = captureGeometry("palette-open-ime-up");
        JSONObject gridWithPalette = runtimeGrid(paletteGeometry);
        assertEquals("opening the floating palette must keep xterm columns", gridBeforePalette.getInt("cols"), gridWithPalette.getInt("cols"));
        assertEquals("opening the floating palette must keep xterm rows", gridBeforePalette.getInt("rows"), gridWithPalette.getInt("rows"));
        assertEquals("opening the floating palette must not resize the SSH PTY", resizeAcksBeforePalette, terminalResizeAcks());
        assertPaletteInsideTerminalSlot(paletteGeometry);
        captureScreenshot("fastkeys-palette-ime-open.png");

        dragPaletteHeader();
        JSONObject draggedPalette = captureGeometry("palette-dragged-ime-up");
        assertPaletteInsideTerminalSlot(draggedPalette);
        assertTrue("dragging the palette header must move the card", paletteMoved(paletteGeometry, draggedPalette));

        sendPaletteKey("escape");
        sendPaletteKey("tab");
        sendPaletteKey("shift-tab");
        scrollPaletteTo("[data-testid=mobile-hotkeys-open-ctrl-page]");
        tapDomCenter("[data-testid=mobile-hotkeys-open-ctrl-page]");
        awaitJsTrue("!!document.querySelector('[data-testid=mobile-hotkeys-ctrl-page]')");
        scrollPaletteTo("[data-key-id='ctrl-q']");
        sendPaletteKey("ctrl-q");
        tapDomCenter("[aria-label='Back to terminal hotkeys']");
        awaitJsTrue("!!document.querySelector('[data-testid=mobile-hotkeys-main-page]')");
        scrollPaletteTo("[data-key-id='ctrl-c']");
        sendControl("ctrl-c", false);
        sendControl("ctrl-c", true);
        scrollPaletteTo("[data-key-id='ctrl-d']");
        sendControl("ctrl-d", false);
        sendControl("ctrl-d", true);
        cancelControlPress("ctrl-c");

        int writesBeforeEnter = hotkeyWrites().length();
        int pointerEventsBeforeEnter = pointerEventCount();
        tapDomCenter("[data-key-id='enter']");
        assertTrue("compact Enter must receive the tap while the floating palette is open",
                hotkeyClickSince("enter", pointerEventsBeforeEnter));
        awaitHotkeyWrites(writesBeforeEnter + 1);
        awaitJsTrue("(window.__ps2857TerminalVisibleText || '').includes(" + JSONObject.quote(firstDone) + ")", 15_000);
        JSONArray expectedFirstWrites = expectedFirstWrites();
        assertEquals("each visible fast-key action must be one typed-byte PTY write", expectedFirstWrites.toString(), hotkeyWrites().toString());
        journey.put("firstHotkeyWrites", hotkeyWrites());
        journey.put("firstSessionRawFile", firstRaw);

        int writesBeforeBack = hotkeyWrites().length();
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        awaitImeVisible(false);
        awaitJsTrue("document.querySelector('[data-testid=mobile-hotkeys]')?.dataset.paletteOpen === 'true'"
                + " && document.querySelector('.app-shell')?.dataset.sshPhase === 'live'", 10_000);
        JSONObject paletteAfterImeBack = captureGeometry("palette-open-ime-dismissed");
        assertPaletteInsideTerminalSlot(paletteAfterImeBack);
        assertEquals("Android Back dismissing the IME must not send a terminal byte", writesBeforeBack, hotkeyWrites().length());
        captureScreenshot("fastkeys-palette-ime-dismissed.png");

        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        awaitJsTrue("document.querySelector('[data-testid=mobile-hotkeys]')?.dataset.paletteOpen === 'false'"
                + " && document.querySelector('.app-shell')?.dataset.homeSurface === 'live'"
                + " && document.querySelector('.app-shell')?.dataset.sshPhase === 'live'");
        assertEquals("Android Back closing the palette must not send a terminal byte", writesBeforeBack, hotkeyWrites().length());
        captureScreenshot("fastkeys-palette-closed.png");
        JSONObject beforeReconnectGeometry = captureGeometry("before-reconnect");

        click("[data-testid=ssh-disconnect]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.sshPhase === 'idle'"
                + " && !document.querySelector('[data-testid=mobile-hotkeys]')");
        assertTrue("disconnect must unmount the hotkey controls",
                !"true".equals(evalRaw("!!document.querySelector('[data-testid=mobile-hotkeys]')")));

        connect(host, port, privateKey);
        attachSession(firstSession);
        awaitTerminalResizeIdle();
        try {
            awaitImeVisible(true);
            awaitJsTrue("document.querySelector('.app-shell')?.dataset.keyboardVisible === 'true'"
                    + " && (window.visualViewport?.height ?? innerHeight) <= window.screen.height - 150", 10_000);
        } catch (AssertionError error) {
            JSONObject failureGeometry = captureGeometry("reattach-ime-wait-failure");
            captureScreenshot("fastkeys-reconnected-ime-open.png");
            throw new AssertionError("reattached terminal did not settle into the real keyboard-resized WebView; geometry="
                    + failureGeometry, error);
        }
        JSONObject afterReconnectGeometry = captureGeometry("after-reconnect");
        captureScreenshot("fastkeys-reconnected-ime-open.png");
        assertTrue("reattach geometry must describe the terminal-focused keyboard state", afterReconnectGeometry.getBoolean("keyboardVisible")
                && !afterReconnectGeometry.getBoolean("keyboardComposerMode"));
        JSONObject reattachedGrid = runtimeGrid(afterReconnectGeometry);
        assertHotkeyBarReachable(afterReconnectGeometry);
        assertHotkeyBarWithinTerminalPanel(afterReconnectGeometry);
        if (reattachedGrid.getInt("rows") < 5) {
            throw new AssertionError("reattached terminal must retain at least five visible rows; before="
                    + beforeReconnectGeometry + "; after=" + afterReconnectGeometry);
        }
        assertTrue("xterm evidence must include its measured row height", reattachedGrid.optDouble("cellHeight", 0) > 0);
        String resumedRaw = "/tmp/" + firstSession + "-resumed-bytes.raw";
        String resumedReady = "PS2884_RESUMED_READY_" + nameBase;
        String resumedDone = "PS2884_RESUMED_DONE_" + nameBase;
        try {
            prepareByteCapture(resumedRaw, 3, resumedReady, resumedDone);
        } catch (AssertionError error) {
            JSONObject failureGeometry = captureGeometry("reconnect-ready-failure");
            captureScreenshot("fastkeys-reconnected-ime-open.png");
            throw new AssertionError(error.getMessage() + "; terminal evidence=" + terminalEvidence(resumedReady, resumedDone)
                    + "; before reconnect=" + beforeReconnectGeometry + "; after reconnect=" + afterReconnectGeometry
                    + "; reconnect failure geometry=" + failureGeometry, error);
        }
        tapDomCenter("[data-testid=prompt-draft]");
        awaitImeVisible(true);
        awaitJsTrue("document.querySelector('[data-testid=mobile-hotkeys]')?.dataset.enabled === 'true'");
        tapDomCenter("[data-key-id='arrow-up']");
        awaitHotkeyWrites(writesBeforeBack + 1);
        try {
            awaitJsTrue("(window.__ps2857TerminalVisibleText || '').includes(" + JSONObject.quote(resumedDone) + ")", 15_000);
        } catch (AssertionError error) {
            // Preserve the real packaged screen at the failed render boundary before instrumentation tears the app down.
            captureScreenshot("fastkeys-reconnected-ime-open.png");
            throw new AssertionError(error.getMessage() + "; terminal evidence=" + terminalEvidence(resumedReady, resumedDone), error);
        }
        JSONArray expectedFinalWrites = expectedFirstWrites();
        expectedFinalWrites.put(write("arrow-up", 0x1b, 0x5b, 0x41));
        assertEquals("the reattached live session must emit the expected arrow bytes", expectedFinalWrites.toString(), hotkeyWrites().toString());
        journey.put("allHotkeyWrites", hotkeyWrites());
        journey.put("resumedSessionRawFile", resumedRaw);
        journey.put("finalGeometry", captureGeometry("reconnected-keybar-ime-up"));
        journey.put("beforeReconnectGeometry", beforeReconnectGeometry);
        journey.put("afterReconnectGeometry", afterReconnectGeometry);
        journey.put("geometryTrace", geometryTrace);
        assertTrue("resumed session must keep the Android IME open", isImeVisible());
        journey.put("androidApi", Build.VERSION.SDK_INT);
        emitArtifact("fastkeys-journey.json", journey.toString(2).getBytes(StandardCharsets.UTF_8));
    }

    private void connect(String host, String port, String privateKey) throws Exception {
        setValue("[data-testid=ssh-host]", host);
        setValue("[data-testid=ssh-port]", port);
        setValue("[data-testid=ssh-username]", "testuser");
        setValue("[data-testid=ssh-private-key]", privateKey);
        click("[data-testid=ssh-connect]");
        awaitJsTrue("!!document.querySelector('[data-testid=host-key-decision]'"
                + ") || ['connected','listing'].includes(document.querySelector('.app-shell')?.dataset.sshPhase)");
        if ("true".equals(evalRaw("!!document.querySelector('[data-testid=host-key-decision]')"))) {
            click("[data-testid=trust-host-key]");
        }
        awaitJsTrue("['connected','listing'].includes(document.querySelector('.app-shell')?.dataset.sshPhase)");
    }

    private void createSession(String name) throws Exception {
        awaitJsTrue("!!document.querySelector('[data-testid=new-session-name]')");
        setValue("[data-testid=new-session-name]", name);
        click("[data-testid=create-session]");
        awaitJsTrue("Array.from(document.querySelectorAll('[data-session-name]')).some(node => node.dataset.sessionName.endsWith("
                + JSONObject.quote(name) + "))");
    }

    private void attachSession(String name) throws Exception {
        if (!"sessions".equals(evalString("document.querySelector('.app-shell')?.dataset.homeSurface ?? ''"))) {
            click("[data-testid=open-sessions]");
        }
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.homeSurface === 'sessions'");
        String match = "Array.from(document.querySelectorAll('[data-session-name]')).find(node => node.dataset.sessionName.endsWith("
                + JSONObject.quote(name) + "))";
        awaitJsTrue(match + " !== undefined");
        String actualName = evalString(match + "?.dataset.sessionName ?? ''");
        click("[data-session-name=" + JSONObject.quote(actualName) + "]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.sshPhase === 'live'"
                + " && document.querySelector('.app-shell')?.dataset.homeSurface === 'live'");
    }

    private void prepareByteCapture(String rawPath, int byteCount, String readyMarker, String doneMarker) throws Exception {
        String command = "stty raw -echo; printf '\\r\\n" + readyMarker + "\\r\\n'; dd bs=1 count=" + byteCount
                + " status=none > " + rawPath + "; stty sane; printf '\\n" + doneMarker + "\\n'";
        setValue("[data-testid=prompt-draft]", command);
        click(".composer-shared-controls .send");
        awaitJsTrue("document.querySelector('[data-testid=prompt-draft]')?.value === ''");
        awaitJsTrue("(window.__ps2857TerminalVisibleText || '').includes(" + JSONObject.quote(readyMarker) + ")", 15_000);
        SystemClock.sleep(300);
    }

    private String terminalEvidence(String readyMarker, String doneMarker) throws Exception {
        return evalString("(() => {const ready=" + JSONObject.quote(readyMarker)
                + ", done=" + JSONObject.quote(doneMarker)
                + ", app=String(window.__ps2857AppTerminalLastChunk??''), term=String(window.__ps2857TerminalLastWriteText??''), visible=String(window.__ps2857TerminalVisibleText??'');"
                + "const hex=s=>Array.from(new TextEncoder().encode(s)).map(b=>b.toString(16).padStart(2,'0')).join('');"
                + "return JSON.stringify({appDeliveries:window.__ps2857AppTerminalDeliveryCount??0,"
                + "appLastContainsReady:app.includes(ready),appLastContainsDone:app.includes(done),"
                + "appLastHex:app.includes(ready)?hex(app):'',terminalWrites:window.__ps2857TerminalWriteCount??0,"
                + "writeCallbacks:window.__ps2857TerminalWriteCallbackCount??0,writeParsedEvents:window.__ps2857TerminalWriteParsedCount??0,"
                + "renderCount:window.__ps2857TerminalRenderCount??0,renderRange:String(window.__ps2857TerminalLastRenderRange??''),"
                + "bufferState:String(window.__ps2857TerminalBufferState??''),"
                + "terminalLastContainsReady:term.includes(ready),terminalLastHex:term.includes(ready)?hex(term):'',"
                + "visibleContainsReady:visible.includes(ready),visibleContainsDone:visible.includes(done)});})()");
    }

    private void sendPaletteKey(String keyId) throws Exception {
        String selector = "[data-key-id='" + keyId + "']";
        scrollPaletteTo(selector);
        assertPaletteActionReachable(selector);
        int previous = hotkeyWrites().length();
        tapDomCenter(selector);
        awaitHotkeyWrites(previous + 1);
        awaitImeVisible(true);
        assertTrue("the hotkeys palette must stay open after key taps", "true".equals(evalRaw(
                "document.querySelector('[data-testid=mobile-hotkeys]')?.dataset.paletteOpen === 'true'")));
    }

    private void assertPaletteActionReachable(String selector) throws Exception {
        JSONObject target = evalJson("(() => {const node=document.querySelector(" + JSONObject.quote(selector) + ");"
                + "const content=document.querySelector('.mobile-hotkeys__content');if(!node||!content)return JSON.stringify({missing:true});"
                + "const r=node.getBoundingClientRect(),c=content.getBoundingClientRect(),v=window.visualViewport;"
                + "return JSON.stringify({missing:false,width:r.width,height:r.height,"
                + "insideContent:r.left>=c.left&&r.right<=c.right&&r.top>=c.top&&r.bottom<=c.bottom,"
                + "insideViewport:r.left>=0&&r.top>=0&&r.right<=innerWidth&&r.bottom<=(v?.height??innerHeight)});})()");
        assertTrue("palette action must exist before it is tapped: " + target, !target.optBoolean("missing", true));
        assertTrue("palette action must retain its full 48dp touch-target height: " + target,
                target.getDouble("height") >= 47.9);
        assertTrue("palette action must retain its full 48dp touch-target width: " + target,
                target.getDouble("width") >= 47.9);
        assertTrue("palette action must be fully visible inside its scroll viewport: " + target,
                target.getBoolean("insideContent"));
        assertTrue("palette action must remain inside the visible Android viewport: " + target,
                target.getBoolean("insideViewport"));
    }

    private void sendControl(String keyId, boolean hold) throws Exception {
        int previous = hotkeyWrites().length();
        if (hold) longPressDomCenter("[data-key-id='" + keyId + "']", 700);
        else tapDomCenter("[data-key-id='" + keyId + "']");
        awaitHotkeyWrites(previous + 1);
        awaitJsTrue("document.activeElement?.matches('[data-testid=prompt-draft]') === true", 3_000);
        awaitImeVisible(true);
    }

    private void cancelControlPress(String keyId) throws Exception {
        int beforeCancel = hotkeyWrites().length();
        float[] point = screenPoint("[data-key-id='" + keyId + "']");
        long downTime = SystemClock.uptimeMillis();
        injectTouch(MotionEvent.ACTION_DOWN, point[0], point[1], downTime, downTime);
        SystemClock.sleep(80);
        injectTouch(MotionEvent.ACTION_CANCEL, point[0], point[1], downTime, SystemClock.uptimeMillis());
        SystemClock.sleep(250);
        assertEquals("pointer cancellation must not send a tap or hold", beforeCancel, hotkeyWrites().length());
    }

    private void dragPaletteHeader() throws Exception {
        JSONObject before = captureGeometry("palette-before-drag").getJSONObject("palette");
        JSONObject rect = before.getJSONObject("bounds");
        float startX = (float) (rect.getDouble("left") + Math.min(110, rect.getDouble("width") / 2));
        float startY = (float) (rect.getDouble("top") + 25);
        float[] screen = screenPoint(startX, startY);
        long downTime = SystemClock.uptimeMillis();
        injectTouch(MotionEvent.ACTION_DOWN, screen[0], screen[1], downTime, downTime);
        SystemClock.sleep(70);
        injectTouch(MotionEvent.ACTION_MOVE, screen[0] - 22, screen[1] + 14, downTime, SystemClock.uptimeMillis());
        SystemClock.sleep(60);
        injectTouch(MotionEvent.ACTION_UP, screen[0] - 22, screen[1] + 14, downTime, SystemClock.uptimeMillis());
        SystemClock.sleep(250);
    }

    private void scrollPaletteTo(String selector) throws Exception {
        for (int attempt = 0; attempt < 12; attempt += 1) {
            JSONObject geometry = evalJson("(() => {const target=document.querySelector(" + JSONObject.quote(selector) + ");"
                    + "const content=document.querySelector('.mobile-hotkeys__content');if(!target||!content)return JSON.stringify({missing:true});"
                    + "const r=target.getBoundingClientRect(),c=content.getBoundingClientRect();return JSON.stringify({missing:false,"
                    + "inside:r.top>=c.top&&r.bottom<=c.bottom,top:r.top,bottom:r.bottom,contentTop:c.top,contentBottom:c.bottom});})()");
            if (geometry.optBoolean("inside")) return;
            JSONObject content = evalJson("(() => {const r=document.querySelector('.mobile-hotkeys__content')?.getBoundingClientRect();"
                    + "return JSON.stringify(r?{left:r.left,right:r.right,top:r.top,bottom:r.bottom}:null);})()");
            assertTrue("palette content must exist to scroll controls into view", !content.isNull("top"));
            // Start in the content's side padding so a drag never begins on a
            // key button, whose focus-preserving default could cancel scrolling.
            float x = (float) (content.getDouble("left") + 4);
            boolean below = geometry.optDouble("bottom") > content.getDouble("bottom");
            float travel = (float) Math.min(32, content.getDouble("bottom") - content.getDouble("top") - 16);
            float startY = (float) (below ? content.getDouble("bottom") - 12 : content.getDouble("top") + 12);
            float endY = below ? startY - travel : startY + travel;
            float[] start = screenPoint(x, startY);
            float[] end = screenPoint(x, endY);
            long downTime = SystemClock.uptimeMillis();
            injectTouch(MotionEvent.ACTION_DOWN, start[0], start[1], downTime, downTime);
            SystemClock.sleep(60);
            injectTouch(MotionEvent.ACTION_MOVE, end[0], end[1], downTime, SystemClock.uptimeMillis());
            injectTouch(MotionEvent.ACTION_UP, end[0], end[1], downTime, SystemClock.uptimeMillis());
            SystemClock.sleep(120);
        }
        JSONObject last = evalJson("(() => {const n=document.querySelector(" + JSONObject.quote(selector) + ");"
                + "const c=document.querySelector('.mobile-hotkeys__content');const r=n?.getBoundingClientRect(),b=c?.getBoundingClientRect();"
                + "return JSON.stringify({target:r?{top:r.top,bottom:r.bottom}:null,content:b?{top:b.top,bottom:b.bottom,"
                + "scrollTop:c.scrollTop,scrollHeight:c.scrollHeight,clientHeight:c.clientHeight}:null,"
                + "pointerEvents:(window.__ps2884PointerEvents??[]).slice(-16)});})()");
        throw new AssertionError("could not scroll the requested fast-key control into the visible palette area: " + last);
    }

    private JSONObject captureGeometry(String stage) throws Exception {
        JSONObject dom = evalJson("(() => {window.dispatchEvent(new Event('pocketshell:terminal-geometry-request'));"
                + "const rect=s=>{const n=document.querySelector(s);if(!n)return null;const r=n.getBoundingClientRect();"
                + "return {top:r.top,bottom:r.bottom,left:r.left,right:r.right,width:r.width,height:r.height};};"
                + "const target=n=>{const r=n.getBoundingClientRect();const v=window.visualViewport;return {label:n.getAttribute('aria-label')||'',"
                + "top:r.top,bottom:r.bottom,left:r.left,right:r.right,width:r.width,height:r.height,disabled:!!n.disabled,"
                + "insideViewport:r.top>=0&&r.left>=0&&r.bottom<=(v?.height??innerHeight)+0.5&&r.right<=innerWidth+0.5};};"
                + "const shell=document.querySelector('.app-shell');const slot=document.querySelector('[data-testid=terminal-slot]');"
                + "const keys=Array.from(document.querySelectorAll('[data-testid=mobile-hotkeys] .mobile-hotkeys__navigation button,"
                + "[data-testid=mobile-hotkeys-launcher]')).map(target);const palette=document.querySelector('[data-testid=mobile-hotkeys-palette]');"
                + "return JSON.stringify({stage:" + JSONObject.quote(stage) + ",androidApi:" + Build.VERSION.SDK_INT + ","
                + "keyboardVisible:shell?.dataset.keyboardVisible==='true',keyboardComposerMode:shell?.dataset.keyboardComposerMode==='true',"
                + "sshPhase:shell?.dataset.sshPhase||'',homeSurface:shell?.dataset.homeSurface||'',"
                + "terminalPanel:rect('.terminal-panel'),terminalSlot:rect('[data-testid=terminal-slot]'),terminalViewport:rect('.terminal-viewport'),"
                + "mobileHotkeys:rect('[data-testid=mobile-hotkeys]'),navigationTargets:keys,"
                + "layout:Object.fromEntries(['.app-shell','.screen-content','.home-screen--workspace','.live-workspace','.terminal-panel',"
                + "'[data-testid=terminal-slot]','.terminal-viewport','.composer-panel'].map(selector=>{const node=document.querySelector(selector);"
                + "if(!node)return [selector,null];const style=getComputedStyle(node),r=node.getBoundingClientRect();return [selector,{display:style.display,"
                + "height:r.height,minHeight:style.minHeight,flex:style.flex,padding:style.padding,overflow:style.overflow}];})),"
                + "palette:palette?{bounds:rect('[data-testid=mobile-hotkeys-palette]'),insideSlot:(()=>{const a=palette.getBoundingClientRect(),b=slot.getBoundingClientRect();"
                + "return a.top>=b.top-0.5&&a.left>=b.left-0.5&&a.bottom<=b.bottom+0.5&&a.right<=b.right+0.5;})()}:null,"
                + "runtimeGeometry:window.__ps2875TerminalRuntimeGeometry??null,"
                + "resizeAcks:Number(shell?.dataset.sshTerminalResizeAcks??0),resizePending:Number(shell?.dataset.sshTerminalResizePending??0),"
                + "resizeFailures:Number(shell?.dataset.sshTerminalResizeFailures??0),hotkeyWrites:window.__ps2884HotkeyWrites??[],"
                + "visualViewport:{height:window.visualViewport?.height??innerHeight,width:window.visualViewport?.width??innerWidth,"
                + "offsetTop:window.visualViewport?.offsetTop??0},innerWidth,innerHeight,"
                + "screenScroll:document.querySelector('.screen-content')?.scrollTop??null,"
                + "documentScroll:document.scrollingElement?.scrollTop??null});})()");
        dom.put("androidIme", readNativeImeState());
        geometryTrace.put(new JSONObject(dom.toString()));
        return dom;
    }

    private JSONObject readNativeImeState() throws Exception {
        AtomicReference<JSONObject> result = new AtomicReference<>();
        scenario.onActivity(activity -> {
            View decor = activity.getWindow().getDecorView();
            WindowInsets insets = decor.getRootWindowInsets();
            WebView webView = findWebView(decor);
            float density = activity.getResources().getDisplayMetrics().density;
            Insets ime = insets == null ? Insets.NONE : insets.getInsets(WindowInsets.Type.ime());
            Insets bars = insets == null ? Insets.NONE : insets.getInsets(WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout());
            try {
                int[] location = new int[2];
                if (webView != null) webView.getLocationOnScreen(location);
                result.set(new JSONObject()
                        .put("visible", insets != null && insets.isVisible(WindowInsets.Type.ime()))
                        .put("imeBottomDp", ime.bottom / density)
                        .put("statusTopDp", bars.top / density)
                        .put("density", density)
                        .put("webViewScreenX", location[0])
                        .put("webViewScreenY", location[1])
                        .put("webViewWidthPx", webView == null ? 0 : webView.getWidth())
                        .put("webViewHeightPx", webView == null ? 0 : webView.getHeight()));
            } catch (JSONException error) {
                throw new RuntimeException(error);
            }
        });
        return result.get() == null ? new JSONObject() : result.get();
    }

    private void assertHotkeyBarReachable(JSONObject geometry) throws Exception {
        JSONArray targets = geometry.getJSONArray("navigationTargets");
        assertEquals("compact hotkey row must expose arrows, Enter, and launcher", 4, targets.length());
        List<String> labels = new ArrayList<>();
        for (int index = 0; index < targets.length(); index += 1) {
            JSONObject target = targets.getJSONObject(index);
            labels.add(target.getString("label"));
            assertTrue("hotkey accessible target must have a 48dp minimum height: " + target, target.getDouble("height") >= 47.9);
            assertTrue("hotkey accessible target must have a 48dp minimum width: " + target, target.getDouble("width") >= 47.9);
            assertTrue("hotkey target must remain above the IME and inside the viewport: " + target, target.getBoolean("insideViewport"));
            assertTrue("live hotkey target must be enabled: " + target, !target.getBoolean("disabled"));
        }
        assertEquals("compact row keeps all required controls reachable",
                List.of("Send Up arrow", "Send Down arrow", "Send Enter", "Open terminal hotkeys"), labels);
        assertTrue("keyboard geometry must confirm native IME visibility", geometry.getJSONObject("androidIme").getBoolean("visible"));
        assertTrue("keyboard geometry must include a positive native IME inset", geometry.getJSONObject("androidIme").getDouble("imeBottomDp") > 0);
    }

    private void assertHotkeyBarWithinTerminalPanel(JSONObject geometry) throws Exception {
        JSONObject panel = geometry.getJSONObject("terminalPanel");
        JSONArray targets = geometry.getJSONArray("navigationTargets");
        for (int index = 0; index < targets.length(); index += 1) {
            JSONObject target = targets.getJSONObject(index);
            assertTrue("keyboard-up hotkey must not be clipped outside the terminal panel: " + target + "; panel=" + panel,
                    target.getDouble("top") >= panel.getDouble("top") - 0.5
                            && target.getDouble("bottom") <= panel.getDouble("bottom") + 0.5);
        }
    }

    private void assertPaletteInsideTerminalSlot(JSONObject geometry) throws Exception {
        JSONObject palette = geometry.getJSONObject("palette");
        assertTrue("floating fast-key palette must stay inside the terminal slot: " + geometry, palette.getBoolean("insideSlot"));
        JSONObject bounds = palette.getJSONObject("bounds");
        assertTrue("floating palette must remain visible in the WebView viewport", bounds.getDouble("top") >= 0
                && bounds.getDouble("bottom") <= geometry.getJSONObject("visualViewport").getDouble("height") + 0.5);
    }

    private boolean paletteMoved(JSONObject before, JSONObject after) throws Exception {
        JSONObject start = before.getJSONObject("palette").getJSONObject("bounds");
        JSONObject end = after.getJSONObject("palette").getJSONObject("bounds");
        return Math.abs(start.getDouble("left") - end.getDouble("left")) > 1
                || Math.abs(start.getDouble("top") - end.getDouble("top")) > 1;
    }

    private JSONObject runtimeGrid(JSONObject geometry) throws Exception {
        assertTrue("terminal runtime geometry must be captured by the mounted xterm", geometry.has("runtimeGeometry")
                && !geometry.isNull("runtimeGeometry"));
        return geometry.getJSONObject("runtimeGeometry");
    }

    private int terminalResizeAcks() throws Exception {
        return Integer.parseInt(evalString("document.querySelector('.app-shell')?.dataset.sshTerminalResizeAcks ?? '0'"));
    }

    private void awaitTerminalResizeIdle() throws Exception {
        awaitJsTrue("Number(document.querySelector('.app-shell')?.dataset.sshTerminalResizePending ?? 0) === 0"
                + " && Number(document.querySelector('.app-shell')?.dataset.sshTerminalResizeAcks ?? 0) > 0"
                + " && Number(document.querySelector('.app-shell')?.dataset.sshTerminalResizeFailures ?? 0) === 0");
    }

    private JSONArray hotkeyWrites() throws Exception {
        return new JSONArray(evalString("JSON.stringify(window.__ps2884HotkeyWrites ?? [])"));
    }

    private int pointerEventCount() throws Exception {
        return Integer.parseInt(evalString("String((window.__ps2884PointerEvents ?? []).length)"));
    }

    private boolean hotkeyClickSince(String keyId, int offset) throws Exception {
        String expression = "JSON.stringify((window.__ps2884PointerEvents ?? []).slice(" + offset + ")"
                + ".some(event => event.type === 'click' && event.key === " + JSONObject.quote(keyId) + "))";
        return Boolean.parseBoolean(evalString(expression));
    }

    private void awaitHotkeyWrites(int count) throws Exception {
        try {
            awaitJsTrue("(window.__ps2884HotkeyWrites || []).length >= " + count, 10_000);
        } catch (AssertionError error) {
            String trace = evalString("JSON.stringify({writes:window.__ps2884HotkeyWrites??[],"
                    + "pointerEvents:window.__ps2884PointerEvents??[],"
                    + "activeElement:document.activeElement?.outerHTML?.slice(0,160)??null,"
                    + "keyboardVisible:document.querySelector('.app-shell')?.dataset.keyboardVisible??null})");
            throw new AssertionError("expected at least " + count + " hotkey writes; observed=" + trace, error);
        }
        int actual = hotkeyWrites().length();
        assertEquals("hotkey must emit exactly one atomic write", count, actual);
    }

    private JSONArray expectedFirstWrites() throws JSONException {
        JSONArray expected = new JSONArray();
        expected.put(write("arrow-up", 0x1b, 0x5b, 0x41));
        expected.put(write("arrow-down", 0x1b, 0x5b, 0x42));
        expected.put(write("escape", 0x1b));
        expected.put(write("tab", 0x09));
        expected.put(write("shift-tab", 0x1b, 0x5b, 0x5a));
        expected.put(write("ctrl-q", 0x11));
        expected.put(write("ctrl-c", 0x03));
        expected.put(write("ctrl-c", 0x03, 0x03));
        expected.put(write("ctrl-d", 0x04));
        expected.put(write("ctrl-d", 0x04, 0x04));
        expected.put(write("enter", 0x0d));
        return expected;
    }

    private JSONObject write(String key, int... bytes) throws JSONException {
        JSONArray payload = new JSONArray();
        for (int value : bytes) payload.put(value);
        return new JSONObject().put("key", key).put("bytes", payload);
    }

    private void captureScreenshot(String name) throws Exception {
        AtomicReference<byte[]> pngBytes = new AtomicReference<>();
        scenario.onActivity(activity -> {
            Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            if (screenshot == null) return;
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            boolean compressed = screenshot.compress(Bitmap.CompressFormat.PNG, 100, output);
            screenshot.recycle();
            if (compressed && output.size() >= 1_024) pngBytes.set(output.toByteArray());
        });
        assertNotNull("full-screen screenshot must be captured for " + name, pngBytes.get());
        emitArtifact(name, pngBytes.get());
    }

    private void emitArtifact(String name, byte[] bytes) throws Exception {
        String encoded = Base64.getEncoder().encodeToString(bytes);
        int chunks = (encoded.length() + ASSET_CHUNK_SIZE - 1) / ASSET_CHUNK_SIZE;
        String sha256 = hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        Log.i(ASSET_TAG, "BEGIN|" + artifactRunId + "|" + name + "|" + chunks + "|" + sha256);
        for (int index = 0; index < chunks; index += 1) {
            int start = index * ASSET_CHUNK_SIZE;
            int end = Math.min(encoded.length(), start + ASSET_CHUNK_SIZE);
            Log.i(ASSET_TAG, "DATA|" + artifactRunId + "|" + name + "|" + index + "|" + encoded.substring(start, end));
            SystemClock.sleep(15);
        }
        Log.i(ASSET_TAG, "END|" + artifactRunId + "|" + name);
    }

    private String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private boolean isImeVisible() {
        AtomicReference<Boolean> visible = new AtomicReference<>(false);
        scenario.onActivity(activity -> {
            WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
            visible.set(insets != null && insets.isVisible(WindowInsets.Type.ime()));
        });
        return visible.get();
    }

    private void awaitImeVisible(boolean visible) throws Exception {
        awaitImeVisible(visible, WAIT_TIMEOUT_MILLIS);
    }

    private void awaitImeVisible(boolean visible, long timeoutMillis) throws Exception {
        long deadline = SystemClock.uptimeMillis() + timeoutMillis;
        while (SystemClock.uptimeMillis() < deadline) {
            if (isImeVisible() == visible) {
                SystemClock.sleep(120);
                if (isImeVisible() == visible) return;
            }
            SystemClock.sleep(100);
        }
        String webState = evalString("JSON.stringify({activeElement:document.activeElement?.outerHTML?.slice(0,160)??null,"
                + "keyboardVisible:document.querySelector('.app-shell')?.dataset.keyboardVisible??null,"
                + "paletteOpen:document.querySelector('[data-testid=mobile-hotkeys]')?.dataset.paletteOpen??null})");
        throw new AssertionError("Android IME visibility did not become " + visible + " (WebView=" + webState + ")");
    }

    private void tapDomCenter(String selector) throws Exception {
        JSONObject point = domPoint(selector);
        assertTrue("fast-key target must be visible inside the Android viewport: " + point, point.getBoolean("visible"));
        float[] screen = screenPoint((float) point.getDouble("x"), (float) point.getDouble("y"));
        long downTime = SystemClock.uptimeMillis();
        injectTouch(MotionEvent.ACTION_DOWN, screen[0], screen[1], downTime, downTime);
        SystemClock.sleep(60);
        injectTouch(MotionEvent.ACTION_UP, screen[0], screen[1], downTime, SystemClock.uptimeMillis());
        SystemClock.sleep(100);
    }

    private void longPressDomCenter(String selector, long durationMillis) throws Exception {
        JSONObject point = domPoint(selector);
        assertTrue("holdable fast-key target must be visible inside the Android viewport: " + point, point.getBoolean("visible"));
        float[] screen = screenPoint((float) point.getDouble("x"), (float) point.getDouble("y"));
        long downTime = SystemClock.uptimeMillis();
        injectTouch(MotionEvent.ACTION_DOWN, screen[0], screen[1], downTime, downTime);
        SystemClock.sleep(durationMillis);
        injectTouch(MotionEvent.ACTION_UP, screen[0], screen[1], downTime, SystemClock.uptimeMillis());
        SystemClock.sleep(100);
    }

    private JSONObject domPoint(String selector) throws Exception {
        JSONObject point = evalJson("(() => {const n=document.querySelector(" + JSONObject.quote(selector) + ");"
                + "if(!n)return JSON.stringify({missing:true});const r=n.getBoundingClientRect(),v=window.visualViewport;"
                + "const x=r.left+r.width/2,y=r.top+r.height/2;return JSON.stringify({missing:false,x,y,width:innerWidth,cssHeight:innerHeight,"
                + "top:r.top,bottom:r.bottom,left:r.left,right:r.right,height:v?.height??innerHeight,disabled:!!n.disabled,"
                + "visible:r.top>=0&&r.left>=0&&r.bottom<=(v?.height??innerHeight)+0.5&&r.right<=innerWidth+0.5});})()");
        assertTrue("expected live control to exist: " + selector, !point.optBoolean("missing"));
        assertTrue("expected live control to be enabled: " + selector, !point.optBoolean("disabled"));
        return point;
    }

    private float[] screenPoint(String selector) throws Exception {
        JSONObject point = domPoint(selector);
        return screenPoint((float) point.getDouble("x"), (float) point.getDouble("y"));
    }

    private float[] screenPoint(float cssX, float cssY) throws Exception {
        double cssWidth = Double.parseDouble(evalString("String(innerWidth)"));
        double cssHeight = Double.parseDouble(evalString("String(innerHeight)"));
        AtomicReference<float[]> result = new AtomicReference<>();
        scenario.onActivity(activity -> {
            WebView webView = findWebView(activity.getWindow().getDecorView());
            assertNotNull("packaged screen must contain a WebView", webView);
            int[] location = new int[2];
            webView.getLocationOnScreen(location);
            float scaleX = webView.getWidth() / (float) cssWidth;
            float scaleY = webView.getHeight() / (float) cssHeight;
            result.set(new float[]{location[0] + cssX * scaleX, location[1] + cssY * scaleY});
        });
        return result.get();
    }

    private void injectTouch(int action, float x, float y, long downTime, long eventTime) {
        MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        boolean injected = InstrumentationRegistry.getInstrumentation().getUiAutomation().injectInputEvent(event, true);
        event.recycle();
        assertTrue("Android touchscreen event must be injected", injected);
    }

    private void setValue(String selector, String value) throws Exception {
        evalString("(() => {const node=document.querySelector(" + JSONObject.quote(selector) + ");"
                + "if(!node)throw new Error('missing '+ " + JSONObject.quote(selector) + ");node.value=" + JSONObject.quote(value) + ";"
                + "node.dispatchEvent(new Event('input',{bubbles:true}));node.dispatchEvent(new Event('change',{bubbles:true}));return 'set';})()");
    }

    private void click(String selector) throws Exception {
        evalString("(() => {const node=document.querySelector(" + JSONObject.quote(selector) + ");"
                + "if(!node)throw new Error('missing '+ " + JSONObject.quote(selector) + ");node.click();return 'clicked';})()");
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
            Thread.sleep(60);
        }
        throw new AssertionError("WebView condition did not become true: " + expression
                + " (last=" + last + "; page=" + evalString("document.body.innerText") + ")");
    }

    private String evalString(String expression) throws Exception {
        Object decoded = new JSONTokener(evalRaw(expression)).nextValue();
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
            assertNotNull("packaged Capacitor activity must contain a WebView", webView);
            webView.evaluateJavascript(expression, value -> {
                result.set(value);
                latch.countDown();
            });
        });
        assertTrue("timed out evaluating packaged WebView JavaScript", latch.await(JS_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (result.get() == null || "null".equals(result.get())) throw new JSONException("JavaScript returned null: " + expression);
        return result.get();
    }

    private WebView findWebView(View root) {
        if (root instanceof WebView) return (WebView) root;
        if (!(root instanceof android.view.ViewGroup)) return null;
        android.view.ViewGroup group = (android.view.ViewGroup) root;
        for (int index = 0; index < group.getChildCount(); index += 1) {
            WebView nested = findWebView(group.getChildAt(index));
            if (nested != null) return nested;
        }
        return null;
    }
}
