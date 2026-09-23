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

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Packaged composer journey against the real agents/aplexer Docker fixture. */
@RunWith(AndroidJUnit4.class)
public final class JsComposerDockerJourneyTest {
    private static final long WAIT_TIMEOUT_MILLIS = 45_000;
    private static final long JS_TIMEOUT_SECONDS = 15;

    private ActivityScenario<MainActivity> scenario;
    private String bytesSession;
    private String uncertainSession;

    @Before
    public void launchPackagedShell() {
        scenario = ActivityScenario.launch(MainActivity.class);
    }

    @After
    public void closeShell() {
        if (scenario != null) scenario.close();
    }

    @Test
    public void composerWritesUtf8AndMultilineInsertAndRetainsAfterDrop() throws Exception {
        assertTrue("safe-area and keyboard assertions require API 35+", Build.VERSION.SDK_INT >= 35);
        var arguments = InstrumentationRegistry.getArguments();
        String host = arguments.getString("sshHost", "10.0.2.2");
        String port = arguments.getString("sshPort");
        String encodedKey = arguments.getString("sshPrivateKeyBase64");
        String nameBase = arguments.getString("sshSessionName");
        String artifactRunId = arguments.getString("artifactRunId", nameBase);
        assertNotNull("pass the Docker fixture port with sshPort", port);
        assertNotNull("pass the test-only key with sshPrivateKeyBase64", encodedKey);
        assertNotNull("pass unique composer session names with sshSessionName", nameBase);
        String privateKey = new String(Base64.getDecoder().decode(encodedKey), StandardCharsets.UTF_8);
        bytesSession = nameBase + "-bytes";
        uncertainSession = nameBase + "-uncertain";

        awaitJsTrue("document.querySelector('[data-testid=build-status] > span:nth-child(2)')?.textContent.trim() === 'Build verified'");
        evalString("window.__ps2857CaptureTerminalEvidence = true; 'terminal evidence enabled'");
        setValue("[data-testid=ssh-host]", host);
        setValue("[data-testid=ssh-port]", port);
        setValue("[data-testid=ssh-username]", "testuser");
        setValue("[data-testid=ssh-private-key]", privateKey);
        click("[data-testid=ssh-connect]");
        awaitTrustOrConnected();
        awaitJsTrue("['connected','listing'].includes(document.querySelector('.app-shell')?.dataset.sshPhase)");

        createSession(bytesSession);
        createSession(uncertainSession);
        attachSession(bytesSession);

        String sentMarker = "PS2857_SENT_" + nameBase;
        String sentMarkerPrefix = "PS2857_SENT_";
        String unicodeCommand = "printf '%s' 'café 🧪' | od -An -tx1 | tr -d '[:space:]' | tee /tmp/"
                + bytesSession + "-unicode.hex; printf '\\n%s%s\\n' '" + sentMarkerPrefix + "' '" + nameBase
                + "' | tee /tmp/"
                + bytesSession + "-sent-output.marker";
        assertTrue("the sent-output marker must not be present verbatim in the command echo", !unicodeCommand.contains(sentMarker));
        setComposerDraft(unicodeCommand);
        showKeyboardAndCapture(artifactRunId);
        assertTrue("Send must be activated while the Android IME is visible", isImeVisible());
        tapComposerAction(".composer-shared-controls .send");
        awaitDeliveredAndCleared();
        waitForTerminalMarkerOrCaptureWindow(sentMarker);
        savePostSendArtifacts(artifactRunId, sentMarker, unicodeCommand);

        String multilineFile = "/tmp/" + bytesSession + "-multiline.raw";
        setComposerDraft("cat > " + multilineFile);
        tapComposerAction(".composer-shared-controls .send");
        awaitDeliveredAndCleared();
        SystemClock.sleep(500);

        String multilinePayload = "alpha\nβeta\n🙂";
        setComposerDraft(multilinePayload);
        tapComposerAction("[data-testid=composer-insert]");
        awaitInsertedAndCleared();
        setComposerDraft("\u0004\u0004");
        tapComposerAction("[data-testid=composer-insert]");
        awaitInsertedAndCleared();

        String insertMarker = "PS2857_INSERT_" + nameBase;
        String insertCommand = "printf '%s' '" + insertMarker + "' > /tmp/" + bytesSession + "-insert.marker";
        setComposerDraft(insertCommand);
        tapComposerAction("[data-testid=composer-insert]");
        awaitJsTrue("document.querySelector('[data-testid=composer-status]')?.textContent.includes('without pressing Enter')"
                + " && document.querySelector('[data-testid=prompt-draft]')?.value === ''");
        awaitJsTrue("(window.__ps2857TerminalVisibleText || '').includes(" + JSONObject.quote(insertMarker) + ")",
                10_000);

        setComposerDraft("discard-me-" + nameBase);
        tapComposerAction("[data-testid=composer-discard]");
        awaitJsTrue("document.querySelector('[data-testid=composer-discard]')?.textContent.trim() === 'Discard?'"
                + " && document.querySelector('[data-testid=composer-status]')?.textContent.includes('Tap Discard again')");
        tapComposerAction("[data-testid=composer-discard]");
        awaitJsTrue("document.querySelector('[data-testid=prompt-draft]')?.value === ''"
                + " && document.querySelector('[data-testid=composer-status]')?.textContent.includes('Draft cleared')");

        attachSession(uncertainSession);
        String uncertainMarker = "PS2857_UNCERTAIN_" + nameBase;
        String uncertainCommand = "printf '%s' '" + uncertainMarker + "' > /tmp/" + uncertainSession + "-uncertain.marker\n"
                + "# PS2857_MULTILINE_SUFFIX_" + nameBase;
        setComposerDraft(uncertainCommand);
        armDisconnectAfterFirstAcknowledgement();
        tapComposerAction(".composer-shared-controls .send");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.sshPhase === 'idle'", 30_000);
        awaitJsTrue("!document.querySelector('[data-testid=prompt-composer]')", 10_000);

        click("[data-testid=ssh-connect]");
        awaitTrustOrConnected();
        awaitJsTrue("['connected','listing'].includes(document.querySelector('.app-shell')?.dataset.sshPhase)");
        attachSession(uncertainSession);
        awaitJsTrue("document.querySelector('[data-testid=prompt-draft]')?.value === " + JSONObject.quote(uncertainCommand));
        assertEquals("uncertain delivery must keep the exact draft after reattach", uncertainCommand,
                evalString("document.querySelector('[data-testid=prompt-draft]')?.value ?? ''"));
    }

    private void awaitTrustOrConnected() throws Exception {
        awaitJsTrue("!!document.querySelector('[data-testid=host-key-decision]')"
                + " || ['connected','listing'].includes(document.querySelector('.app-shell')?.dataset.sshPhase)");
        if ("true".equals(evalRaw("!!document.querySelector('[data-testid=host-key-decision]')"))) {
            click("[data-testid=trust-host-key]");
        }
    }

    private void createSession(String name) throws Exception {
        setValue("[data-testid=new-session-name]", name);
        click("[data-testid=create-session]");
        String match = "Array.from(document.querySelectorAll('[data-session-name]')).find(node => node.dataset.sessionName.endsWith("
                + JSONObject.quote(name) + "))";
        awaitJsTrue(match + " !== undefined");
    }

    private void attachSession(String suffixName) throws Exception {
        String match = "Array.from(document.querySelectorAll('[data-session-name]')).find(node => node.dataset.sessionName.endsWith("
                + JSONObject.quote(suffixName) + "))";
        awaitJsTrue(match + " !== undefined");
        String actualName = evalString(match + "?.dataset.sessionName ?? ''");
        click("[data-session-name=" + JSONObject.quote(actualName) + "]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.sshPhase === 'live'");
        awaitJsTrue("!!document.querySelector('[data-testid=prompt-composer]')");
    }

    private void setComposerDraft(String value) throws Exception {
        setValue("[data-testid=prompt-draft]", value);
        awaitJsTrue("document.querySelector('[data-testid=prompt-draft]')?.value === " + JSONObject.quote(value));
    }

    private void awaitDeliveredAndCleared() throws Exception {
        awaitJsTrue("document.querySelector('[data-testid=composer-status]')?.dataset.deliveryState === 'success'"
                + " && document.querySelector('[data-testid=composer-status]')?.textContent.includes('Sent to the terminal')"
                + " && document.querySelector('[data-testid=prompt-draft]')?.value === ''", 20_000);
    }

    private void awaitInsertedAndCleared() throws Exception {
        awaitJsTrue("document.querySelector('[data-testid=composer-status]')?.dataset.deliveryState === 'success'"
                + " && document.querySelector('[data-testid=composer-status]')?.textContent.includes('without pressing Enter')"
                + " && document.querySelector('[data-testid=prompt-draft]')?.value === ''", 20_000);
    }

    private void armDisconnectAfterFirstAcknowledgement() throws Exception {
        evalString("(() => {"
                + "if (window.__ps2857DropPoll) clearInterval(window.__ps2857DropPoll);"
                + "window.__ps2857DropPoll = setInterval(() => {"
                + "const composer = document.querySelector('[data-testid=prompt-composer]');"
                + "const status = document.querySelector('[data-testid=composer-status]');"
                + "if (Number(composer?.dataset.acknowledgedWrites ?? 0) === 1"
                + " && status?.dataset.deliveryIntent === 'submit') {"
                + "clearInterval(window.__ps2857DropPoll);"
                + "document.querySelector('[data-testid=ssh-disconnect]')?.click();"
                + "}"
                + "}, 2); return 'armed';})()");
    }

    private boolean saveKeyboardScreenshot(String runId) throws Exception {
        AtomicReference<Boolean> saved = new AtomicReference<>(false);
        AtomicReference<byte[]> artifact = new AtomicReference<>();
        scenario.onActivity(activity -> {
            try {
                Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
                if (screenshot == null) return;
                ByteArrayOutputStream encoded = new ByteArrayOutputStream();
                boolean compressed = screenshot.compress(Bitmap.CompressFormat.PNG, 100, encoded);
                File destination = new File(activity.getFilesDir(), "composer-keyboard.png");
                byte[] png = encoded.toByteArray();
                if (compressed && png.length >= 1024) {
                    try (FileOutputStream output = new FileOutputStream(destination)) {
                        output.write(png);
                    }
                    artifact.set(png);
                    saved.set(destination.length() >= 1024);
                }
                screenshot.recycle();
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
        if (saved.get()) emitArtifact(runId, "composer-keyboard.png", artifact.get());
        return saved.get();
    }

    private void showKeyboardAndCapture(String runId) throws Exception {
        evalString("document.querySelector('[data-testid=prompt-draft]')?.scrollIntoView({block:'center', behavior:'instant'}); 'scrolled'");
        tapDomCenter("[data-testid=prompt-draft]");
        awaitImeVisible(true);
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.keyboardVisible === 'true'");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.keyboardComposerMode === 'true'");
        evalString("document.querySelector('[data-testid=composer-actions]')?.scrollIntoView({block:'end', behavior:'instant'}); 'scrolled'");
        SystemClock.sleep(350);
        assertTrue("Android IME must still be open for the keyboard-up capture", isImeVisible());
        assertTrue("capture the real keyboard-up composer before checking its visible bounds", saveKeyboardScreenshot(runId));
        String geometry = saveKeyboardGeometry(runId);
        try {
            awaitJsTrue("(() => {const shell=document.querySelector('.app-shell');"
                    + "const appBar=document.querySelector('.app-bar')?.getBoundingClientRect();"
                    + "const terminal=document.querySelector('.terminal-viewport')?.getBoundingClientRect();"
                    + "const height=window.visualViewport?.height ?? innerHeight;"
                    + "const selectors=['[data-testid=prompt-draft]','[data-testid=composer-status]',"
                    + "'[data-testid=composer-discard]','[data-testid=composer-insert]','.composer-shared-controls .send'];"
                    + "const visible=selectors.every(selector=>{const node=document.querySelector(selector);"
                    + "if(!node)return false;const rect=node.getBoundingClientRect();return rect.top >= 0 && rect.bottom <= height + 0.5"
                    + " && rect.left >= 0 && rect.right <= innerWidth + 0.5;});"
                    + "return shell?.dataset.keyboardVisible === 'true' && !!appBar && !!terminal && terminal.height >= 48"
                    + " && terminal.top >= 0 && terminal.bottom <= height + 0.5 && terminal.right <= innerWidth + 0.5"
                    + " && appBar.top >= parseFloat(getComputedStyle(shell).paddingTop) - 0.5 && visible;})()");
        } catch (AssertionError error) {
            throw new AssertionError(error.getMessage() + "; captured keyboard screenshot precedes geometry=" + geometry, error);
        }
        assertTrue("a real Android keyboard must still be open when the composer is captured", isImeVisible());
    }

    private void ensureImeVisible() throws Exception {
        boolean composerFocused = "true".equals(evalRaw(
                "!!document.activeElement?.closest('[data-testid=prompt-composer]')"));
        if (!isImeVisible() || !composerFocused) {
            evalString("document.querySelector('[data-testid=prompt-draft]')?.scrollIntoView({block:'center', behavior:'instant'}); 'scrolled'");
            tapDomCenter("[data-testid=prompt-draft]");
            awaitJsTrue("document.activeElement === document.querySelector('[data-testid=prompt-draft]')");
            awaitImeVisible(true);
        }
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.keyboardVisible === 'true'");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.keyboardComposerMode === 'true'");
        awaitImeVisible(true);
    }

    private void tapComposerAction(String selector) throws Exception {
        ensureImeVisible();
        evalString("document.querySelector(" + JSONObject.quote(selector)
                + ")?.scrollIntoView({block:'nearest', behavior:'instant'}); 'scrolled'");
        assertTrue("Android IME must be visible immediately before tapping " + selector, isImeVisible());
        tapDomCenter(selector);
    }

    private void waitForTerminalMarkerOrCaptureWindow(String marker) throws Exception {
        String quotedMarker = JSONObject.quote(marker);
        long deadline = SystemClock.uptimeMillis() + 5_000;
        while (SystemClock.uptimeMillis() < deadline) {
            String found = evalRaw("(window.__ps2857TerminalVisibleText || '').includes(" + quotedMarker + ")"
                    + " || Array.from(document.querySelectorAll('.terminal-viewport .xterm-rows > div'))"
                    + ".some(row => (row.textContent || '').includes(" + quotedMarker + "))");
            if ("true".equals(found)) return;
            Thread.sleep(100);
        }
    }

    private void savePostSendArtifacts(String runId, String expectedMarker, String submittedCommand) throws Exception {
        // Sending closes Android's IME and the current mobile screen can keep its
        // previous outer scroll position at the connection card. Scroll the
        // terminal into the real WebView viewport before capturing the rendered
        // output; this is an explicit post-send proof step, not evidence that the
        // output was visible immediately when Send was tapped.
        evalString("(() => {const viewport=document.querySelector('.terminal-viewport');"
                + "viewport?.scrollIntoView({block:'center',behavior:'instant'});"
                + "const terminalScroller=viewport?.querySelector('.xterm-viewport');"
                + "if(terminalScroller)terminalScroller.scrollTop=terminalScroller.scrollHeight;"
                + "return 'terminal-scrolled-into-view';})()");
        SystemClock.sleep(400);
        String report = evalString("(() => {const viewport=document.querySelector('.terminal-viewport');"
                + "const rect=viewport?.getBoundingClientRect();"
                + "const visibleText=window.__ps2857TerminalVisibleText || '';"
                + "const terminalDomText=Array.from(viewport?.querySelectorAll('.xterm-rows > div') ?? [])"
                + ".map(row=>row.textContent || '').join('\\n').slice(-4000);"
                + "return JSON.stringify({stage:'after-send',expectedMarker:" + JSONObject.quote(expectedMarker)
                + ",captureEnabled:window.__ps2857CaptureTerminalEvidence===true,"
                + "terminalEvidenceSource:'xterm-active-buffer-after-render',visibleTerminalText:visibleText,terminalDomText:terminalDomText,"
                + "appTerminalDeliveryCount:window.__ps2857AppTerminalDeliveryCount??0,appTerminalMissingRefCount:window.__ps2857AppTerminalMissingRefCount??0,"
                + "appTerminalLastChunk:window.__ps2857AppTerminalLastChunk??'',terminalWriteCount:window.__ps2857TerminalWriteCount??0,"
                + "terminalLastWriteText:window.__ps2857TerminalLastWriteText??'',terminalRenderCount:window.__ps2857TerminalRenderCount??0,"
                + "sentMarkerAbsentFromSubmittedCommand:" + !submittedCommand.contains(expectedMarker) + ","
                + "terminalViewport:rect?{top:rect.top,bottom:rect.bottom,left:rect.left,right:rect.right,width:rect.width,height:rect.height}:null,"
                + "visualViewport:{height:window.visualViewport?.height ?? innerHeight,width:window.visualViewport?.width ?? innerWidth},"
                + "keyboardVisible:document.querySelector('.app-shell')?.dataset.keyboardVisible==='true',"
                + "screenScrollTop:document.querySelector('.screen-content')?.scrollTop ?? null,"
                + "deliveryStatus:document.querySelector('[data-testid=composer-status]')?.textContent.trim() ?? ''});})() ");
        JSONObject measured = new JSONObject(report);
        SystemClock.sleep(250);
        byte[] reportBytes = measured.toString().getBytes(StandardCharsets.UTF_8);
        AtomicReference<byte[]> screenshotArtifact = new AtomicReference<>();
        AtomicReference<Boolean> saved = new AtomicReference<>(false);
        scenario.onActivity(activity -> {
            try {
                Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
                if (screenshot == null) return;
                ByteArrayOutputStream encoded = new ByteArrayOutputStream();
                boolean compressed = screenshot.compress(Bitmap.CompressFormat.PNG, 100, encoded);
                byte[] png = encoded.toByteArray();
                File destination = new File(activity.getFilesDir(), "composer-post-send.png");
                if (compressed && png.length >= 1024) {
                    try (FileOutputStream output = new FileOutputStream(destination)) {
                        output.write(png);
                    }
                    screenshotArtifact.set(png);
                    saved.set(destination.length() >= 1024);
                }
                screenshot.recycle();
                try (FileOutputStream output = new FileOutputStream(new File(activity.getFilesDir(), "composer-post-send-terminal.json"))) {
                    output.write(reportBytes);
                }
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
        assertTrue("same-run post-send screenshot must be captured", saved.get());
        emitArtifact(runId, "composer-post-send.png", screenshotArtifact.get());
        emitArtifact(runId, "composer-post-send-terminal.json", reportBytes);
        String visibleText = measured.getString("visibleTerminalText");
        assertTrue("same-run terminal viewport record must identify the successful send",
                measured.getString("deliveryStatus").contains("Sent to the terminal"));
        JSONObject viewport = measured.getJSONObject("terminalViewport");
        JSONObject visualViewport = measured.getJSONObject("visualViewport");
        assertTrue("same-run terminal viewport must remain onscreen", viewport.getDouble("top") >= 0
                && viewport.getDouble("bottom") <= visualViewport.getDouble("height") + 0.5
                && viewport.getDouble("left") >= 0
                && viewport.getDouble("right") <= visualViewport.getDouble("width") + 0.5
                && viewport.getDouble("height") >= 48);
        assertTrue("the app terminal subscription must deliver PTY bytes to its mounted Xterm component",
                measured.getInt("appTerminalDeliveryCount") > 0 && measured.getInt("appTerminalMissingRefCount") == 0
                        && measured.getInt("terminalWriteCount") > 0);
        assertTrue("the same-run active Xterm buffer and rendered DOM must contain the sent output",
                visibleText.contains(expectedMarker) && measured.getString("terminalDomText").contains(expectedMarker));
    }

    private String saveKeyboardGeometry(String runId) throws Exception {
        String geometry = evalString("(() => {const rect=(selector) => {const node=document.querySelector(selector);"
                + "if(!node)return null;const r=node.getBoundingClientRect();return {top:r.top,bottom:r.bottom,left:r.left,right:r.right,width:r.width,height:r.height};};"
                + "const style=(selector) => {const node=document.querySelector(selector);if(!node)return null;const s=getComputedStyle(node);return {display:s.display,position:s.position,visibility:s.visibility,overflow:s.overflow,overflowY:s.overflowY,zIndex:s.zIndex};};"
                + "return JSON.stringify({innerHeight,innerWidth,outerHeight,outerWidth,scrollY,clientHeight:document.documentElement.clientHeight,"
                + "visualViewport:window.visualViewport?{height:visualViewport.height,width:visualViewport.width,offsetTop:visualViewport.offsetTop}:null,"
                + "screen:{height:screen.height,width:screen.width},activeElement:document.activeElement?.outerHTML?.slice(0,300)??null,"
                + "shell:rect('.app-shell'),screenContent:rect('.screen-content'),terminal:rect('.terminal-panel'),"
                + "terminalViewport:rect('.terminal-viewport'),"
                + "appBar:rect('.app-bar'),draft:rect('[data-testid=prompt-draft]'),"
                + "status:rect('[data-testid=composer-status]'),actions:rect('[data-testid=composer-actions]'),"
                + "buttons:{discard:rect('[data-testid=composer-discard]'),insert:rect('[data-testid=composer-insert]'),send:rect('.composer-shared-controls .send')},"
                + "safeArea:{topCss:parseFloat(getComputedStyle(document.querySelector('.app-shell')).paddingTop)||0,"
                + "bottomCss:parseFloat(getComputedStyle(document.querySelector('.app-shell')).paddingBottom)||0,"
                + "shellTopPadding:parseFloat(getComputedStyle(document.querySelector('.app-shell')).paddingTop)||0,"
                + "shellBottomPadding:parseFloat(getComputedStyle(document.querySelector('.app-shell')).paddingBottom)||0,"
                + "keyboardVisible:document.querySelector('.app-shell')?.dataset.keyboardVisible==='true'},"
                + "styles:Object.fromEntries(['.app-shell','.screen-content','.home-screen','.terminal-panel','.terminal-viewport','.composer-panel','.composer-heading','.composer-draft','.composer-status','.composer-actions'].map(selector=>[selector,style(selector)])),"
                + "scroll:{top:document.querySelector('.screen-content')?.scrollTop,client:document.querySelector('.screen-content')?.clientHeight,"
                + "height:document.querySelector('.screen-content')?.scrollHeight}});})() ");
        JSONObject measured = new JSONObject(geometry);
        measured.put("androidImeVisible", isImeVisible());
        AtomicReference<JSONObject> nativeInsets = new AtomicReference<>();
        scenario.onActivity(activity -> {
            WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
            if (insets == null) return;
            float density = activity.getResources().getDisplayMetrics().density;
            Insets systemBars = insets.getInsets(WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsets.Type.ime());
            try {
                nativeInsets.set(new JSONObject()
                        .put("statusBarTopDp", systemBars.top / density)
                        .put("systemBottomDp", insets.getInsets(WindowInsets.Type.navigationBars()).bottom / density)
                        .put("imeBottomDp", ime.bottom / density)
                        .put("density", density));
            } catch (JSONException error) {
                throw new RuntimeException(error);
            }
        });
        measured.put("nativeInsets", nativeInsets.get());
        byte[] bytes = measured.toString().getBytes(StandardCharsets.UTF_8);
        scenario.onActivity(activity -> {
            File destination = new File(activity.getFilesDir(), "composer-keyboard-geometry.json");
            try (FileOutputStream output = new FileOutputStream(destination)) {
                output.write(bytes);
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
        emitArtifact(runId, "composer-keyboard-geometry.json", bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private void emitArtifact(String runId, String name, byte[] bytes) throws Exception {
        String encoded = Base64.getEncoder().encodeToString(bytes);
        int chunkSize = 2_800;
        int chunks = (encoded.length() + chunkSize - 1) / chunkSize;
        String sha256 = hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        Log.i("PS2857Asset", "BEGIN|" + runId + "|" + name + "|" + chunks + "|" + sha256);
        for (int index = 0; index < chunks; index += 1) {
            int start = index * chunkSize;
            int end = Math.min(encoded.length(), start + chunkSize);
            Log.i("PS2857Asset", "DATA|" + runId + "|" + name + "|" + index + "|" + encoded.substring(start, end));
            // Keep the logd producer below its per-tag burst limit. The
            // independent host collector still requires every indexed chunk
            // and verifies the complete payload hash.
            SystemClock.sleep(15);
        }
        Log.i("PS2857Asset", "END|" + runId + "|" + name);
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
            visible.set(insets != null && Build.VERSION.SDK_INT >= 30 && insets.isVisible(WindowInsets.Type.ime()));
        });
        return visible.get();
    }

    private void awaitImeVisible(boolean visible) throws Exception {
        long deadline = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS;
        while (SystemClock.uptimeMillis() < deadline) {
            if (isImeVisible() == visible) {
                Thread.sleep(120);
                if (isImeVisible() == visible) return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Android IME visibility did not become " + visible);
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

    private void tapDomCenter(String selector) throws Exception {
        JSONObject point = evalJson("(() => {const element = document.querySelector(" + JSONObject.quote(selector)
                + "); if (!element) return JSON.stringify({missing:true}); const rect=element.getBoundingClientRect();"
                + "const height=window.visualViewport?.height ?? innerHeight;"
                + "return JSON.stringify({x:rect.left+rect.width/2,y:rect.top+rect.height/2,width:innerWidth,top:rect.top,bottom:rect.bottom,left:rect.left,right:rect.right,height,disabled:!!element.disabled});})()");
        assertTrue("WebView touch target must exist", !point.optBoolean("missing"));
        assertTrue("WebView touch target must be enabled", !point.optBoolean("disabled"));
        assertTrue("WebView touch target must be visibly inside the Android viewport: " + point,
                point.optDouble("top", -1) >= 0 && point.optDouble("bottom", -1) <= point.optDouble("height") + 0.5
                        && point.optDouble("left", -1) >= 0 && point.optDouble("right", -1) <= point.optDouble("width") + 0.5);
        AtomicReference<float[]> screenPoint = new AtomicReference<>();
        scenario.onActivity(activity -> {
            WebView webView = findWebView(activity.getWindow().getDecorView());
            int[] location = new int[2];
            webView.getLocationOnScreen(location);
            float scale = webView.getWidth() / (float) point.optDouble("width");
            screenPoint.set(new float[] {location[0] + (float) point.optDouble("x") * scale,
                    location[1] + (float) point.optDouble("y") * scale});
        });
        float[] screen = screenPoint.get();
        long downTime = SystemClock.uptimeMillis();
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, screen[0], screen[1], 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        assertTrue("Android touchscreen ACTION_DOWN must be injected", instrumentation.getUiAutomation().injectInputEvent(down, true));
        down.recycle();
        SystemClock.sleep(60);
        MotionEvent up = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, screen[0], screen[1], 0);
        up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        assertTrue("Android touchscreen ACTION_UP must be injected", instrumentation.getUiAutomation().injectInputEvent(up, true));
        up.recycle();
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
        throw new AssertionError("WebView condition did not become true: " + expression + " (last=" + last
                + "; page=" + evalString("document.body.innerText") + ")");
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

    private static WebView findWebView(View view) {
        if (view instanceof WebView) return (WebView) view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                WebView found = findWebView(group.getChildAt(index));
                if (found != null) return found;
            }
        }
        return null;
    }
}
