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
import androidx.lifecycle.Lifecycle;

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
    private static final int MAX_CATALOG_SWIPE_ATTEMPTS = 8;
    private static final int ACCEPTED_ANDROID_TERMINAL_VIEWPORT_CAP_DP = 144;
    // WebView can round adjacent CSS rectangles apart by a tiny fraction at shared edges.
    private static final double TERMINAL_VIEWPORT_ROUNDING_EPSILON_CSS_PX = 0.01;

    private ActivityScenario<MainActivity> scenario;
    private String artifactRunId;
    private String firstSession;
    private JSONObject acceptedKeyboardGrid;
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
        String dictationTargetSession = nameBase + "-dictation-target";
        String privateKey = new String(Base64.getDecoder().decode(encodedKey), StandardCharsets.UTF_8);

        awaitJsTrue("document.querySelector('[data-testid=build-status] > span:nth-child(2)')?.textContent.trim() === 'Build verified'");
        installControlledSpeechAdapter();
        evalString("window.__ps2857CaptureTerminalEvidence = true; window.__ps2884HotkeyWrites = [];"
                + "window.__ps2884CaptureResizeFitEvidence = true; window.__ps2884ResizeFitEvents = [];"
                + "window.__ps2884ResizeAckEvents = []; window.__ps2884ResizeFitMarker = 'journey-start';"
                + "window.__ps2884PointerEvents = []; window.__ps2884FocusEvents = [];"
                + "for (const type of ['pointerdown','pointerup','pointercancel','click']) window.addEventListener(type, event => {"
                + "const button=event.target instanceof Element ? event.target.closest('button') : null;"
                + "window.__ps2884PointerEvents.push({type,pointerId:event.pointerId??null,detail:event.detail??null,"
                + "key:button?.dataset.keyId??button?.getAttribute('aria-label')??null,defaultPrevented:event.defaultPrevented,"
                + "activeElement:document.activeElement?.getAttribute('data-testid')??document.activeElement?.tagName??null});"
                + "});"
                + "const describeFocusNode=node=>{if(!(node instanceof Element))return null;return {tag:node.tagName.toLowerCase(),"
                + "id:node.id||null,testId:node.getAttribute('data-testid'),className:String(node.className||'').slice(0,80)};};"
                + "for(const type of ['focusin','focusout'])document.addEventListener(type,event=>{"
                + "const entries=window.__ps2884FocusEvents;entries.push({type,atMs:Math.round(performance.now()),"
                + "target:describeFocusNode(event.target),related:describeFocusNode(event.relatedTarget),"
                + "active:describeFocusNode(document.activeElement),keyboardVisible:document.querySelector('.app-shell')?.dataset.keyboardVisible||'false'});"
                + "if(entries.length>40)entries.shift();},true); 'evidence enabled'");
        // SystemClock.uptimeMillis() is Android's monotonic clock. This interval
        // ends after the attached live prompt is present and a rendered frame settles.
        long connectToPromptStartedAt = SystemClock.uptimeMillis();
        connect(host, port, privateKey);
        createSession(firstSession);
        createSession(dictationTargetSession);
        attachSession(firstSession);
        awaitTerminalResizeIdle();
        awaitJsTrue("!!document.querySelector('[data-testid=prompt-draft]')"
                + " && document.querySelector('.app-shell')?.dataset.sshPhase === 'live'");
        awaitRenderedFrame();
        journey.put("connectToPromptMs", SystemClock.uptimeMillis() - connectToPromptStartedAt);

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
        awaitTerminalResizeIdle();
        awaitRenderedFrame();
        JSONObject keyboardGeometry = captureGeometry("keyboard-up-compact-row");
        acceptedKeyboardGrid = assertAcceptedKeyboardUpViewport("initial API 35 keyboard-up baseline", keyboardGeometry);
        assertHotkeyBarReachable(keyboardGeometry);
        assertDictationMicReachable(keyboardGeometry);
        journey.put("narrowToolbarReachability", verifyNarrowToolbarReachability());
        assertTrue("keyboard-up screenshot must include visible Android IME", isImeVisible());
        captureScreenshot("fastkeys-ime-open.png");
        captureScreenshot("fastkeys-row-closed-ime-open.png");
        captureTerminalViewportScreenshot("fastkeys-row-closed-ime-open-viewport.png", keyboardGeometry);

        tapDomCenter("[data-key-id='arrow-up']");
        awaitHotkeyWrites(1);
        awaitImeVisible(true, 3_000);
        tapDomCenter("[data-key-id='arrow-down']");
        awaitHotkeyWrites(2);
        awaitImeVisible(true, 3_000);
        JSONObject afterNavigationTaps = captureGeometry("after-navigation-row-taps");
        assertUnchangedTerminalGrid("navigation taps after the accepted keyboard-up baseline",
                acceptedKeyboardGrid, runtimeGrid(afterNavigationTaps));
        assertDictationMicReachable(afterNavigationTaps);
        assertTrue("quick navigation taps must keep the keyboard row active", afterNavigationTaps.getBoolean("keyboardVisible"));
        assertTrue("quick navigation taps must leave the Android IME open", afterNavigationTaps.getJSONObject("androidIme").getBoolean("visible"));

        int resizeAcksBeforePalette = terminalResizeAcks();
        JSONObject beforeTray = captureGeometry("before-fast-keys");
        JSONObject gridBeforePalette = runtimeGrid(beforeTray);
        assertUnchangedTerminalGrid("opening the fast-key catalog from the accepted keyboard-up baseline",
                acceptedKeyboardGrid, gridBeforePalette);
        tapDomCenter("[data-testid=mobile-hotkeys-launcher]");
        awaitJsTrue("document.querySelector('[data-testid=mobile-hotkeys]')?.dataset.paletteOpen === 'true'");
        SystemClock.sleep(300);
        JSONObject mainTrayGeometry = captureGeometry("fast-keys-main-open-ime-up");
        JSONObject gridWithMainTray = runtimeGrid(mainTrayGeometry);
        assertCatalogSheetGeometry(mainTrayGeometry, "main");
        assertCatalogPageActionReachable(mainTrayGeometry);
        assertTerminalViewportCap("opening the main fast-key tray", beforeTray, mainTrayGeometry);
        assertUnchangedTerminalGrid("opening the main fast-key tray", gridBeforePalette, gridWithMainTray);
        assertAtLeastFiveRows("main fast-key catalog", mainTrayGeometry);
        assertEquals("opening the main fast-key tray must not resize the SSH PTY", resizeAcksBeforePalette, terminalResizeAcks());
        assertTrayBelowTerminalViewport(mainTrayGeometry);
        assertDictationMicReachable(mainTrayGeometry);
        assertHotkeyBarReachable(mainTrayGeometry);
        assertComposerActionsReachable(mainTrayGeometry, false);
        JSONArray mainCatalogKeys = assertCatalogReachable(".mobile-hotkeys__main-keys", 10);
        JSONObject mainCatalogGeometry = captureGeometry("fast-keys-main-catalog-reachable");
        assertTerminalViewportCap("scrolling the main fast-key catalog", beforeTray, mainCatalogGeometry);
        assertDictationMicReachable(mainCatalogGeometry);
        assertHotkeyBarReachable(mainCatalogGeometry);
        assertAtLeastFiveRows("scrolled main fast-key catalog", mainCatalogGeometry);
        captureScreenshot("fastkeys-sheet-main-tail-ime-open.png");
        captureTerminalViewportScreenshot("fastkeys-sheet-main-tail-ime-open-viewport.png", mainCatalogGeometry);
        scrollCatalogToStart(".mobile-hotkeys__main-keys");
        JSONObject mainStartGeometry = captureGeometry("fast-keys-main-catalog-open-ime-up");
        assertCatalogSheetGeometry(mainStartGeometry, "main");
        assertComposerActionsReachable(mainStartGeometry, false);
        captureScreenshot("fastkeys-sheet-main-ime-open.png");
        captureTerminalViewportScreenshot("fastkeys-sheet-main-ime-open-viewport.png", mainStartGeometry);

        evalString("(() => {const draft=document.querySelector('[data-testid=prompt-draft]');draft.value='PS2897_ACTION_REACHABILITY';"
                + "draft.dispatchEvent(new Event('input',{bubbles:true}));return 'composer action probe staged';})()");
        awaitJsTrue("document.querySelector('[data-testid=prompt-draft]')?.value === 'PS2897_ACTION_REACHABILITY'"
                + " && document.querySelector('[data-testid=composer-discard]')?.disabled === false"
                + " && document.querySelector('[data-testid=composer-insert]')?.disabled === false"
                + " && document.querySelector('.composer-shared-controls .send')?.disabled === false");
        awaitRenderedFrame();
        JSONObject composerActionGeometry = captureGeometry("fast-keys-main-composer-actions-ime-up");
        assertComposerActionsReachable(composerActionGeometry, true);
        assertAtLeastFiveRows("main catalog with composer action probe", composerActionGeometry);
        awaitRenderedFrame();
        captureScreenshot("fastkeys-main-composer-actions-ime-open.png");
        awaitRenderedFrame();
        captureTerminalViewportScreenshot("fastkeys-main-composer-actions-ime-open-viewport.png", composerActionGeometry);
        int inputAcksBeforeComposerAction = terminalInputAcknowledgements();
        tapDomCenter("[data-testid=composer-discard]");
        awaitJsTrue("document.querySelector('[data-testid=composer-discard]')?.textContent.includes('Discard?')");
        assertTrue("discard tap keeps the keyboard and main key catalog available",
                isImeVisible() && "main".equals(captureGeometry("composer-discard-confirmation").getString("fastKeysPage")));
        tapDomCenter("[data-testid=composer-discard]");
        awaitJsTrue("document.querySelector('[data-testid=prompt-draft]')?.value === ''");
        awaitImeVisible(true);
        JSONObject composerActionCleared = captureGeometry("fast-keys-main-composer-actions-cleared-ime-up");
        assertComposerActionsReachable(composerActionCleared, false);
        assertEquals("composer action taps must not write to the terminal PTY",
                inputAcksBeforeComposerAction, terminalInputAcknowledgements());

        sendPaletteKey("escape");
        sendPaletteKey("tab");
        sendPaletteKey("shift-tab");
        tapDomCenter("[data-testid=mobile-hotkeys-open-ctrl-page]");
        awaitJsTrue("document.querySelector('[data-testid=mobile-hotkeys]')?.dataset.palettePage === 'ctrl'");
        JSONObject ctrlTrayGeometry = captureGeometry("fast-keys-ctrl-open-ime-up");
        assertCatalogSheetGeometry(ctrlTrayGeometry, "ctrl");
        assertCatalogPageActionReachable(ctrlTrayGeometry);
        assertTerminalViewportCap("opening the Ctrl fast-key tray", beforeTray, ctrlTrayGeometry);
        assertTrayBelowTerminalViewport(ctrlTrayGeometry);
        assertDictationMicReachable(ctrlTrayGeometry);
        assertHotkeyBarReachable(ctrlTrayGeometry);
        assertComposerActionsReachable(ctrlTrayGeometry, false);
        JSONObject gridWithCtrlTray = runtimeGrid(ctrlTrayGeometry);
        assertUnchangedTerminalGrid("opening the Ctrl fast-key tray", gridBeforePalette, gridWithCtrlTray);
        assertEquals("opening the Ctrl fast-key tray must not resize the SSH PTY", resizeAcksBeforePalette, terminalResizeAcks());
        assertTrue("the IME-up Ctrl tray must compact the composer to at most 104dp: " + ctrlTrayGeometry,
                ctrlTrayGeometry.getJSONObject("composerPanel").getDouble("height") <= 104.1);
        if (gridWithCtrlTray.getInt("rows") < 5) {
            throw new AssertionError("Ctrl fast keys must leave at least five terminal rows visible: " + ctrlTrayGeometry);
        }
        JSONArray ctrlCatalogKeys = assertCatalogReachable(".mobile-hotkeys__ctrl-grid", 27);
        journey.put("catalogReachability", new JSONObject()
                .put("mainKeys", mainCatalogKeys)
                .put("ctrlKeys", ctrlCatalogKeys));
        JSONObject ctrlCatalogGeometry = captureGeometry("fast-keys-ctrl-catalog-reachable");
        assertTerminalViewportCap("scrolling the Ctrl fast-key catalog", beforeTray, ctrlCatalogGeometry);
        assertDictationMicReachable(ctrlCatalogGeometry);
        assertHotkeyBarReachable(ctrlCatalogGeometry);
        captureScreenshot("fastkeys-sheet-ctrl-tail-ime-open.png");
        captureTerminalViewportScreenshot("fastkeys-sheet-ctrl-tail-ime-open-viewport.png", ctrlCatalogGeometry);
        scrollCatalogToStart(".mobile-hotkeys__ctrl-grid");
        JSONObject ctrlStartGeometry = captureGeometry("fast-keys-ctrl-catalog-open-ime-up");
        assertCatalogSheetGeometry(ctrlStartGeometry, "ctrl");
        assertComposerActionsReachable(ctrlStartGeometry, false);
        captureScreenshot("fastkeys-sheet-ctrl-ime-open.png");
        captureTerminalViewportScreenshot("fastkeys-sheet-ctrl-ime-open-viewport.png", ctrlStartGeometry);
        sendPaletteKey("ctrl-q");
        tapDomCenter("[aria-label='Back to terminal hotkeys']");
        awaitJsTrue("!!document.querySelector('[data-testid=mobile-hotkeys-main-page]')");
        sendControl("ctrl-c", false);
        sendControl("ctrl-c", true);
        sendControl("ctrl-d", false);
        sendControl("ctrl-d", true);
        cancelControlPress("ctrl-c");

        tapDomCenter("[data-testid=mobile-hotkeys-launcher]");
        awaitJsTrue("document.querySelector('[data-testid=mobile-hotkeys]')?.dataset.paletteOpen === 'false'");
        awaitRenderedFrame();
        JSONObject closedTrayGeometry = captureGeometry("fast-keys-closed-ime-up");
        assertDictationMicReachable(closedTrayGeometry);
        assertUnchangedTerminalGrid("closing the fast-key tray", gridBeforePalette, runtimeGrid(closedTrayGeometry));
        assertEquals("closing the fast-key tray must not resize the SSH PTY", resizeAcksBeforePalette, terminalResizeAcks());
        assertEquals("closed Android navigation lane reserves its key row and containment pixel", 49,
                (int) closedTrayGeometry.getJSONObject("fastKeysTray").getJSONObject("bounds").getDouble("height"));
        assertEquals("open main catalog adds its bounded sheet below the persistent key row", 193,
                (int) mainTrayGeometry.getJSONObject("fastKeysTray").getJSONObject("bounds").getDouble("height"));
        assertHotkeyBarReachable(closedTrayGeometry);

        int writesBeforeEnter = hotkeyWrites().length();
        int pointerEventsBeforeEnter = pointerEventCount();
        // Enter stays on the one-tap navigation row, outside the open palette.
        // Measure its physical tap through the rendered host completion marker.
        long tapToVisibleOutputStartedAt = SystemClock.uptimeMillis();
        tapDomCenter("[data-key-id='enter']");
        assertTrue("compact Enter must receive the physical tap after closing the palette",
                hotkeyClickSince("enter", pointerEventsBeforeEnter));
        awaitHotkeyWrites(writesBeforeEnter + 1);
        awaitJsTrue("(window.__ps2857TerminalVisibleText || '').includes(" + JSONObject.quote(firstDone) + ")", 15_000);
        awaitRenderedFrame();
        journey.put("tapToVisibleOutputMs", SystemClock.uptimeMillis() - tapToVisibleOutputStartedAt);
        journey.put("firstDoneMarker", firstDone);
        JSONArray expectedFirstWrites = expectedFirstWrites();
        assertEquals("each visible fast-key action must be one typed-byte PTY write", expectedFirstWrites.toString(), hotkeyWrites().toString());
        journey.put("firstHotkeyWrites", hotkeyWrites());
        journey.put("firstSessionRawFile", firstRaw);

        exerciseDockedDictation(nameBase, dictationTargetSession);

        // The dictation journey switches sessions and backgrounds/resumes the
        // app. Re-establish the exact precondition for the layered Back check
        // instead of assuming either the IME or the palette survived that flow.
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.sshPhase === 'live'"
                + " && document.querySelector('.app-shell')?.dataset.homeSurface === 'live'"
                + " && document.querySelector('[data-testid=mobile-hotkeys]')?.dataset.paletteOpen === 'false'");
        tapDomCenter("[data-testid=prompt-draft]");
        awaitImeVisible(true);
        String recoveryFocusPredicate = "document.querySelector('.app-shell')?.dataset.keyboardVisible === 'true'"
                + " && document.activeElement?.matches('[data-testid=prompt-draft]') === true";
        try {
            awaitJsTrue(recoveryFocusPredicate);
        } catch (AssertionError focusFailure) {
            String focusState = evalString("JSON.stringify({keyboardVisible:document.querySelector('.app-shell')?.dataset.keyboardVisible??null,"
                    + "keyboardComposerMode:document.querySelector('.app-shell')?.dataset.keyboardComposerMode??null,"
                    + "activeElement:document.activeElement?.outerHTML?.slice(0,240)??null,"
                    + "draft:document.querySelector('[data-testid=prompt-draft]')?.outerHTML?.slice(0,360)??null,"
                    + "draftFocused:document.activeElement===document.querySelector('[data-testid=prompt-draft]'),"
                    + "visualViewport:{height:window.visualViewport?.height??innerHeight,width:window.visualViewport?.width??innerWidth},"
                    + "focusEvents:(window.__ps2884FocusEvents??[]).slice(-24)})");
            JSONObject recoveryGeometry = captureGeometry("post-dictation-session-return-focus-failure");
            throw new AssertionError("post-dictation focus recovery failed; AndroidImeVisible=" + isImeVisible()
                    + "; DOM=" + focusState + "; geometry=" + recoveryGeometry, focusFailure);
        }
        tapDomCenter("[data-testid=mobile-hotkeys-launcher]");
        awaitJsTrue("document.querySelector('[data-testid=mobile-hotkeys]')?.dataset.paletteOpen === 'true'");
        awaitImeVisible(true);
        JSONObject backLayerPrecondition = captureGeometry("fast-keys-open-ime-up-before-back");
        assertHotkeyBarReachable(backLayerPrecondition);
        assertDictationMicReachable(backLayerPrecondition);
        journey.put("backLayerPrecondition", backLayerPrecondition);

        int writesBeforeBack = hotkeyWrites().length();
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        awaitImeVisible(false);
        awaitJsTrue("document.querySelector('[data-testid=mobile-hotkeys]')?.dataset.paletteOpen === 'true'"
                + " && document.querySelector('.app-shell')?.dataset.sshPhase === 'live'", 10_000);
        awaitRenderedFrame();
        JSONObject trayAfterImeBack = captureGeometry("fast-keys-open-ime-dismissed");
        assertTrayBelowTerminalViewport(trayAfterImeBack);
        assertDictationMicReachable(trayAfterImeBack);
        assertEquals("Android Back dismissing the IME must not send a terminal byte", writesBeforeBack, hotkeyWrites().length());
        captureScreenshot("fastkeys-tray-ime-dismissed.png");

        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        awaitJsTrue("document.querySelector('[data-testid=mobile-hotkeys]')?.dataset.paletteOpen === 'false'"
                + " && document.querySelector('.app-shell')?.dataset.homeSurface === 'live'"
                + " && document.querySelector('.app-shell')?.dataset.sshPhase === 'live'");
        awaitRenderedFrame();
        assertTrue("Android Back must close the docked tray before capturing the closed state",
                "true".equals(evalRaw("document.querySelector('[data-testid=mobile-hotkeys]')?.dataset.paletteOpen === 'false'"
                        + " && !document.querySelector('[data-testid=mobile-hotkeys-main-page]')"
                        + " && !document.querySelector('[data-testid=mobile-hotkeys-ctrl-page]')"
                        + " && document.querySelector('[data-testid=mobile-hotkeys-launcher]')?.getAttribute('aria-expanded') === 'false'")));
        assertEquals("Android Back closing the palette must not send a terminal byte", writesBeforeBack, hotkeyWrites().length());
        captureScreenshot("fastkeys-tray-closed.png");
        JSONObject beforeReconnectGeometry = captureGeometry("before-reconnect");

        click("[data-testid=ssh-disconnect]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.sshPhase === 'idle'"
                + " && !document.querySelector('[data-testid=mobile-hotkeys]')");
        assertTrue("disconnect must unmount the hotkey controls",
                !"true".equals(evalRaw("!!document.querySelector('[data-testid=mobile-hotkeys]')")));

        installAttachAutofocusGate();
        connect(host, port, privateKey);
        attachSession(firstSession);
        awaitJsTrue("(window.__ps2884AttachAutofocusGate?.entered ?? []).some(event => event.source === 'terminal-enabled-watcher')"
                + " && (window.__ps2884AttachAutofocusGate?.entered ?? []).some(event => event.source === 'attach-resize')"
                + " && document.querySelector('.app-shell')?.dataset.sshPhase === 'live'"
                + " && document.querySelector('.app-shell')?.dataset.homeSurface === 'live'"
                + " && document.querySelector('.app-shell')?.dataset.sshAttachFocusPending === 'true'"
                + " && !!document.querySelector('[data-testid=prompt-draft]')");
        tapDomCenter("[data-testid=prompt-draft]");
        awaitImeVisible(true);
        awaitPromptFocusedForReattach();
        JSONObject earlyPromptTapWhileAttachHeld = evalJson("(() => {const shell=document.querySelector('.app-shell');const gate=window.__ps2884AttachAutofocusGate;"
                + "return JSON.stringify({activeElement:document.activeElement?.getAttribute('data-testid')||document.activeElement?.tagName||null,"
                + "keyboardVisible:shell?.dataset.keyboardVisible==='true',attachFocusPending:shell?.dataset.sshAttachFocusPending==='true',"
                + "attachEpoch:Number(shell?.dataset.sshAttachEpoch),attachResizeAckEpoch:Number(shell?.dataset.sshAttachResizeAckEpoch),"
                + "gate:{entered:gate?.entered??[],pending:gate?.pending??0,released:gate?.released??false},"
                + "focusEvents:(window.__ps2884FocusEvents??[]).slice(-12)});})()");
        earlyPromptTapWhileAttachHeld.put("androidImeVisible", isImeVisible());
        assertTrue("early prompt tap evidence must be captured while final attach resize/autofocus is still held",
                earlyPromptTapWhileAttachHeld.getBoolean("attachFocusPending")
                        && !earlyPromptTapWhileAttachHeld.getJSONObject("gate").getBoolean("released")
                        && earlyPromptTapWhileAttachHeld.getJSONObject("gate").getInt("pending") > 0
                        && hasAutofocusSource(earlyPromptTapWhileAttachHeld.getJSONObject("gate"), "terminal-enabled-watcher")
                        && hasAutofocusSource(earlyPromptTapWhileAttachHeld.getJSONObject("gate"), "attach-resize")
                        && earlyPromptTapWhileAttachHeld.getInt("attachResizeAckEpoch") != earlyPromptTapWhileAttachHeld.getInt("attachEpoch")
                        && earlyPromptTapWhileAttachHeld.getBoolean("androidImeVisible"));
        releaseAttachAutofocusGate();
        awaitJsTrue("(() => {const shell=document.querySelector('.app-shell');const gate=window.__ps2884AttachAutofocusGate;"
                + "return shell?.dataset.sshAttachFocusPending === 'false'"
                + " && Number(shell.dataset.sshAttachResizeAckEpoch) === Number(shell.dataset.sshAttachEpoch)"
                + " && Number(shell.dataset.sshTerminalResizePending) === 0"
                + " && Number(shell.dataset.sshTerminalResizeFailures) === 0"
                + " && gate.entered.some(event => event.source === 'terminal-enabled-watcher')"
                + " && gate.entered.some(event => event.source === 'attach-resize')"
                + " && gate.entered.some(event => event.source === 'attach-final-focus')"
                + " && gate?.released === true && gate.pending === 0;})()");
        awaitRenderedFrame();
        awaitPromptFocusedForReattach();
        awaitImeVisible(true);
        JSONObject earlyPromptTapAfterAttachFinished = evalJson("(() => {const shell=document.querySelector('.app-shell');const gate=window.__ps2884AttachAutofocusGate;"
                + "return JSON.stringify({activeElement:document.activeElement?.getAttribute('data-testid')||document.activeElement?.tagName||null,"
                + "keyboardVisible:shell?.dataset.keyboardVisible==='true',attachFocusPending:shell?.dataset.sshAttachFocusPending==='true',"
                + "attachEpoch:Number(shell?.dataset.sshAttachEpoch),attachResizeAckEpoch:Number(shell?.dataset.sshAttachResizeAckEpoch),"
                + "gate:{entered:gate?.entered??[],pending:gate?.pending??0,released:gate?.released??false},"
                + "focusEvents:(window.__ps2884FocusEvents??[]).slice(-12)});})()");
        earlyPromptTapAfterAttachFinished.put("androidImeVisible", isImeVisible());
        journey.put("reattachEarlyPromptTapWhileHeld", earlyPromptTapWhileAttachHeld);
        journey.put("reattachEarlyPromptTapAfterAttach", earlyPromptTapAfterAttachFinished);
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
        assertTrue("reattach geometry must describe the terminal-focused keyboard state", afterReconnectGeometry.getBoolean("keyboardVisible")
                && afterReconnectGeometry.getBoolean("keyboardComposerMode"));
        assertUnchangedTerminalGrid("reattach after the accepted keyboard-up baseline",
                acceptedKeyboardGrid, runtimeGrid(afterReconnectGeometry));
        assertAcceptedKeyboardUpViewport("reattached API 35 keyboard-up state", afterReconnectGeometry);
        JSONObject reattachedGrid = runtimeGrid(afterReconnectGeometry);
        assertHotkeyBarReachable(afterReconnectGeometry);
        assertDictationMicReachable(afterReconnectGeometry);
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
        long reconnectTapToVisibleOutputStartedAt = SystemClock.uptimeMillis();
        tapDomCenter("[data-key-id='arrow-up']");
        awaitHotkeyWrites(writesBeforeBack + 1);
        try {
            awaitJsTrue("(window.__ps2857TerminalVisibleText || '').includes(" + JSONObject.quote(resumedDone) + ")", 15_000);
        } catch (AssertionError error) {
            // Preserve the real packaged screen at the failed render boundary before instrumentation tears the app down.
            captureScreenshot("fastkeys-reconnected-ime-open.png");
            throw new AssertionError(error.getMessage() + "; terminal evidence=" + terminalEvidence(resumedReady, resumedDone), error);
        }
        awaitRenderedFrame();
        journey.put("reconnectTapToVisibleOutputMs", SystemClock.uptimeMillis() - reconnectTapToVisibleOutputStartedAt);
        journey.put("resumedDoneMarker", resumedDone);
        JSONArray expectedFinalWrites = expectedFirstWrites();
        expectedFinalWrites.put(write("arrow-up", 0x1b, 0x5b, 0x41));
        assertEquals("the reattached live session must emit the expected arrow bytes", expectedFinalWrites.toString(), hotkeyWrites().toString());
        journey.put("allHotkeyWrites", hotkeyWrites());
        journey.put("resumedSessionRawFile", resumedRaw);
        JSONObject reconnectedKeybarGeometry = captureGeometry("reconnected-keybar-ime-up");
        assertUnchangedTerminalGrid("reattached keybar after the accepted keyboard-up baseline",
                acceptedKeyboardGrid, runtimeGrid(reconnectedKeybarGeometry));
        assertAcceptedKeyboardUpViewport("reattached keybar API 35 keyboard-up state", reconnectedKeybarGeometry);
        assertDictationMicReachable(reconnectedKeybarGeometry);
        journey.put("finalGeometry", reconnectedKeybarGeometry);
        journey.put("beforeReconnectGeometry", beforeReconnectGeometry);
        journey.put("afterReconnectGeometry", afterReconnectGeometry);
        assertTrue("resumed session must keep the Android IME open", isImeVisible());
        captureScreenshot("fastkeys-reconnected-ime-open.png");
        captureTerminalViewportScreenshot("fastkeys-reconnected-ime-open-viewport.png", reconnectedKeybarGeometry);

        click("[data-testid=ssh-disconnect]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.sshPhase === 'idle'"
                + " && !document.querySelector('[data-testid=mobile-hotkeys]')");
        JSONObject afterReconnectLoss = captureGeometry("after-reconnect-loss");
        assertTrue("all hotkey controls must be removed after the reattached SSH session is lost",
                "idle".equals(afterReconnectLoss.getString("sshPhase"))
                        && afterReconnectLoss.isNull("mobileHotkeys")
                        && afterReconnectLoss.getJSONArray("navigationTargets").length() == 0
                        && afterReconnectLoss.getJSONArray("hotkeyControls").length() == 0);

        // Persist the final loss checkpoint with the reconnect write and timings.
        journey.put("geometryTrace", geometryTrace);
        journey.put("androidApi", Build.VERSION.SDK_INT);
        emitArtifact("fastkeys-journey.json", journey.toString(2).getBytes(StandardCharsets.UTF_8));
    }

    private void exerciseDockedDictation(String nameBase, String changedSession) throws Exception {
        JSONObject idle = captureGeometry("dictation-idle-ime-open");
        assertUnchangedTerminalGrid("dictation baseline after the accepted keyboard-up baseline",
                acceptedKeyboardGrid, runtimeGrid(idle));
        assertAcceptedKeyboardUpViewport("dictation keyboard-up baseline", idle);
        assertDictationMicReachable(idle);
        JSONObject stableGrid = runtimeGrid(idle);
        int stableResizeAcks;
        assertAtLeastFiveRows("initial inline dictation state", idle);
        captureScreenshot("fastkeys-dictation-idle-ime-open.png");

        String rawFile = "/tmp/" + firstSession + "-dictation.raw";
        String readyMarker = "PS2884_DICTATION_READY_" + nameBase;
        String doneMarker = "PS2884_DICTATION_DONE_" + nameBase;
        String marker = "PS2884_DICTATED_" + nameBase;
        String dictatedText = "printf '%s' '" + marker + "'";
        String postStopKeyboardText = "z";
        byte[] dictatedBytes = dictatedText.getBytes(StandardCharsets.UTF_8);
        int dictatedByteCount = dictatedBytes.length;
        int expectedHostByteCount = dictatedByteCount + postStopKeyboardText.getBytes(StandardCharsets.UTF_8).length;
        markResizeFitPhase("dictation-receiver-before");
        prepareByteCapture(rawFile, expectedHostByteCount, readyMarker, doneMarker);
        markResizeFitPhase("dictation-receiver-after-command");
        tapDomCenter("[data-testid=prompt-draft]");
        awaitImeVisible(true);
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.keyboardVisible === 'true'");
        markResizeFitPhase("dictation-receiver-after-prompt-retap");
        awaitTerminalResizeIdle();
        awaitRenderedFrame();

        JSONObject readyGeometry = captureGeometry("dictation-ready-ime-open");
        assertDictationMicReachable(readyGeometry);
        assertUnchangedTerminalGrid("preparing the host-side dictation receiver", stableGrid, runtimeGrid(readyGeometry));
        assertEquals("host-side receiver setup must settle back to the initial terminal viewport height",
                idle.getJSONObject("terminalViewport").getDouble("height"),
                readyGeometry.getJSONObject("terminalViewport").getDouble("height"), 0.5);
        stableResizeAcks = readyGeometry.getInt("resizeAcks");
        int writesBeforeListening = terminalInputAcknowledgements();
        String targetBefore = evalString("document.querySelector('[data-testid=inline-dictation-bar]')?.dataset.targetKey ?? ''");
        tapDomCenter("[data-testid=inline-dictation-toggle]");
        awaitJsTrue("document.querySelector('[data-testid=inline-dictation-bar]')?.dataset.phase === 'listening'");
        assertEquals("one dock tap must start only one native recognizer", 1, controlledSpeechCallCount("startCount"));
        JSONObject start = evalJson("JSON.stringify(window.__ps2857ControlledSpeech?.startOptions ?? null)");
        String requestId = start.getString("requestId");
        evalString("window.__ps2857ControlledSpeech.emit('partial', " + JSONObject.quote(dictatedText) + "); 'partial emitted'");
        awaitJsTrue("document.querySelector('[data-testid=inline-dictation-preview]')?.textContent.trim() === "
                + JSONObject.quote(dictatedText));
        int writesAfterPartial = terminalInputAcknowledgements();
        JSONObject listening = captureGeometry("dictation-listening-ime-open");
        assertTerminalViewportCap("showing a dictation partial", idle, listening);
        assertCompactStatusComposerLayout("listening status", listening);
        assertTrayBelowTerminalViewport(listening);
        assertDictationMicReachable(listening);
        assertHotkeyBarReachable(listening);
        assertEquals("dictation previews must stay local to the dock", writesBeforeListening, terminalInputAcknowledgements());
        assertDictationStableStage("showing a dictation partial", idle, listening, stableGrid, stableResizeAcks);
        assertEquals("listening mic stays icon-only with a Stop accessible action", "",
                listening.getJSONObject("inlineDictationMic").getString("visibleLabel"));
        awaitRenderedFrame();
        awaitJsTrue("document.querySelector('[data-testid=inline-dictation-bar]')?.dataset.phase === 'listening'"
                + " && document.querySelector('[data-testid=inline-dictation-preview]')?.textContent.trim() === "
                + JSONObject.quote(dictatedText));
        captureScreenshot("fastkeys-dictation-listening-ime-open.png");
        captureTerminalViewportScreenshot("fastkeys-dictation-listening-ime-open-viewport.png", listening);

        tapDomCenter("[data-testid=mobile-hotkeys-launcher]");
        awaitJsTrue("document.querySelector('[data-testid=mobile-hotkeys]')?.dataset.paletteOpen === 'true'");
        tapDomCenter("[data-testid=mobile-hotkeys-open-ctrl-page]");
        awaitJsTrue("document.querySelector('[data-testid=mobile-hotkeys]')?.dataset.palettePage === 'ctrl'");
        JSONObject ctrlListening = captureGeometry("dictation-listening-ctrl-open-ime-open");
        assertCatalogSheetGeometry(ctrlListening, "ctrl");
        assertTerminalViewportCap("showing listening status with the Ctrl catalog open", idle, ctrlListening);
        assertTrayBelowTerminalViewport(ctrlListening);
        assertDictationMicReachable(ctrlListening);
        assertHotkeyBarReachable(ctrlListening);
        assertCompactStatusComposerLayout("Ctrl catalog while dictation is listening", ctrlListening);
        assertAtLeastFiveRows("Ctrl catalog while inline dictation is listening", ctrlListening);
        assertEquals("opening the Ctrl catalog during dictation must keep the partial preview local",
                writesBeforeListening, terminalInputAcknowledgements());
        assertDictationStableStage("showing listening status with the Ctrl catalog open", idle, ctrlListening,
                stableGrid, stableResizeAcks);
        awaitRenderedFrame();
        awaitJsTrue("document.querySelector('[data-testid=inline-dictation-bar]')?.dataset.phase === 'listening'"
                + " && document.querySelector('[data-testid=inline-dictation-preview]')?.textContent.trim() === "
                + JSONObject.quote(dictatedText));
        captureScreenshot("fastkeys-dictation-listening-ctrl-ime-open.png");
        tapDomCenter("[data-testid=mobile-hotkeys-launcher]");
        awaitJsTrue("document.querySelector('[data-testid=mobile-hotkeys]')?.dataset.paletteOpen === 'false'"
                + " && document.querySelector('[data-testid=inline-dictation-bar]')?.dataset.phase === 'listening'");
        awaitImeVisible(true);

        tapDomCenter("[data-testid=inline-dictation-toggle]");
        awaitJsTrue("window.__ps2857ControlledSpeech?.stopOptions?.requestId === " + JSONObject.quote(requestId));
        assertEquals("explicit Stop must call the native recognizer once", 1, controlledSpeechCallCount("stopCount"));
        assertEquals("explicit Stop alone must not insert before a final result", writesBeforeListening,
                terminalInputAcknowledgements());
        evalString("window.__ps2857ControlledSpeech.emit('result', " + JSONObject.quote(dictatedText) + "); 'final emitted'");
        awaitJsTrue("document.querySelector('[data-testid=inline-dictation-preview]')?.textContent.trim() === "
                + JSONObject.quote(dictatedText));
        int writesAfterFinalBeforeStopped = terminalInputAcknowledgements();
        assertEquals("final recognition remains staged until native stopped", writesBeforeListening,
                writesAfterFinalBeforeStopped);
        JSONObject finalAwaitingStopped = captureGeometry("dictation-final-awaiting-stopped");
        assertTerminalViewportCap("staging final dictation text", idle, finalAwaitingStopped);
        assertCompactStatusComposerLayout("transcribing status", finalAwaitingStopped);
        assertTrayBelowTerminalViewport(finalAwaitingStopped);
        assertDictationMicReachable(finalAwaitingStopped);
        assertDictationStableStage("staging final dictation text", idle, finalAwaitingStopped, stableGrid,
                stableResizeAcks);
        captureScreenshot("fastkeys-dictation-transcribing-ime-open.png");
        evalString("window.__ps2857ControlledSpeech.emit('stopped'); 'stopped emitted'");
        awaitJsTrue("document.querySelector('[data-testid=inline-dictation-bar]')?.dataset.phase === 'idle'"
                + " && document.querySelector('[data-testid=inline-dictation-status]')?.textContent.includes('Inserted at the cursor')"
                + " && Number(document.querySelector('.app-shell')?.dataset.sshTerminalInputAcks) === "
                + (writesBeforeListening + 1), 15_000);
        int writesAfterStopped = terminalInputAcknowledgements();
        JSONObject finalInsertedGeometry = captureGeometry("dictation-final-inserted");
        assertTerminalViewportCap("inserting final dictation text", idle, finalInsertedGeometry);
        assertCompactStatusComposerLayout("final insertion status", finalInsertedGeometry);
        assertTrayBelowTerminalViewport(finalInsertedGeometry);
        assertDictationMicReachable(finalInsertedGeometry);
        assertTrue("final dictation insertion must return focus to xterm while keeping the native IME and compact layout active: "
                        + finalInsertedGeometry,
                finalInsertedGeometry.getBoolean("keyboardVisible")
                        && finalInsertedGeometry.getBoolean("keyboardComposerMode")
                        && finalInsertedGeometry.getJSONObject("androidIme").getBoolean("visible")
                        && finalInsertedGeometry.getBoolean("terminalViewportFocused")
                        && finalInsertedGeometry.getBoolean("activeElementInsideTerminal")
                        && !finalInsertedGeometry.getBoolean("activeElementIsPromptDraft")
                        && "".equals(finalInsertedGeometry.getString("composerDraftValue")));
        assertDictationStableStage("inserting final dictation text", idle, finalInsertedGeometry, stableGrid,
                stableResizeAcks);

        // Type one real character through Android's keyboard after Stop. The host receiver
        // is still waiting for it, so this proves subsequent text reaches the PTY instead
        // of silently landing in PromptComposer's separate draft.
        String draftBeforePostStopInput = evalString("document.querySelector('[data-testid=prompt-draft]')?.value ?? ''");
        assertEquals("the command receiver must leave PromptComposer's draft empty", "", draftBeforePostStopInput);
        int postStopInputChunkStart = Integer.parseInt(evalString("String(window.__ps2857AppTerminalInputChunks?.length ?? 0)"));
        InstrumentationRegistry.getInstrumentation().sendStringSync(postStopKeyboardText);
        awaitJsTrue("(window.__ps2857TerminalVisibleText || '').includes(" + JSONObject.quote(doneMarker) + ")", 15_000);
        awaitJsTrue("(() => {const shell=document.querySelector('.app-shell');"
                + "const chunks=(window.__ps2857AppTerminalInputChunks ?? []).slice(" + postStopInputChunkStart + ");"
                + "return Number(shell?.dataset.sshTerminalInputAcks) === " + (writesAfterStopped + 1)
                + " && Number(shell?.dataset.sshTerminalInputPending) === 0"
                + " && chunks.map(chunk=>chunk.text).join('') === " + JSONObject.quote(postStopKeyboardText)
                + " && chunks.every(chunk=>chunk.attachEpoch === " + finalInsertedGeometry.getInt("sshAttachEpoch")
                + " && chunk.phase === 'live');})()", 15_000);
        int writesAfterPostStopKeyboard = terminalInputAcknowledgements();
        JSONArray postStopInputChunks = new JSONArray(evalString("JSON.stringify((window.__ps2857AppTerminalInputChunks ?? []).slice("
                + postStopInputChunkStart + "))"));
        JSONObject postStopKeyboardGeometry = captureGeometry("dictation-post-stop-keyboard-input");
        assertTerminalViewportCap("typing after Stop", idle, postStopKeyboardGeometry);
        assertCompactStatusComposerLayout("post-Stop status", postStopKeyboardGeometry);
        assertTrayBelowTerminalViewport(postStopKeyboardGeometry);
        assertDictationMicReachable(postStopKeyboardGeometry);
        assertTrue("post-Stop keyboard input must stay focused in xterm with the IME open: "
                        + postStopKeyboardGeometry,
                postStopKeyboardGeometry.getBoolean("keyboardVisible")
                        && postStopKeyboardGeometry.getBoolean("keyboardComposerMode")
                        && postStopKeyboardGeometry.getJSONObject("androidIme").getBoolean("visible")
                        && postStopKeyboardGeometry.getBoolean("terminalViewportFocused")
                        && postStopKeyboardGeometry.getBoolean("activeElementInsideTerminal")
                        && !postStopKeyboardGeometry.getBoolean("activeElementIsPromptDraft"));
        assertEquals("post-Stop keyboard text must not enter the composer draft", draftBeforePostStopInput,
                postStopKeyboardGeometry.getString("composerDraftValue"));
        assertDictationStableStage("typing after Stop", idle, postStopKeyboardGeometry, stableGrid,
                stableResizeAcks);
        awaitRenderedFrame();
        awaitJsTrue("document.querySelector('[data-testid=inline-dictation-bar]')?.dataset.phase === 'idle'"
                + " && document.querySelector('[data-testid=inline-dictation-status]')?.textContent.includes('Inserted at the cursor')"
                + " && !document.querySelector('[data-testid=inline-dictation-preview]')"
                + " && document.querySelector('[data-testid=inline-dictation-toggle]')?.disabled === false");
        captureScreenshot("fastkeys-dictation-stopped-ime-open.png");
        captureTerminalViewportScreenshot("fastkeys-dictation-stopped-ime-open-viewport.png", finalInsertedGeometry);
        String expectedHostHex = hex((dictatedText + postStopKeyboardText).getBytes(StandardCharsets.UTF_8));
        journey.put("dictation", new JSONObject()
                .put("targetKey", targetBefore)
                .put("attachEpoch", Integer.parseInt(evalString("String(document.querySelector('.app-shell')?.dataset.sshAttachEpoch ?? '-1')")))
                .put("receiverSetupResizeAcks", readyGeometry.getInt("resizeAcks") - idle.getInt("resizeAcks"))
                .put("resizeAcksAtStableBaseline", stableResizeAcks)
                .put("requestId", requestId)
                .put("partialText", dictatedText)
                .put("finalText", dictatedText)
                .put("writesBeforePartial", writesBeforeListening)
                .put("writesAfterPartial", writesAfterPartial)
                .put("writesAfterStopBeforeFinal", writesBeforeListening)
                .put("writesAfterFinalBeforeStopped", writesAfterFinalBeforeStopped)
                .put("writesAfterStopped", writesAfterStopped)
                .put("writesAfterPostStopKeyboard", writesAfterPostStopKeyboard)
                .put("explicitStop", true)
                .put("finalReceived", true)
                .put("stoppedReceived", true)
                .put("nativeStartCalls", controlledSpeechCallCount("startCount"))
                .put("nativeStopCalls", controlledSpeechCallCount("stopCount"))
                .put("stopRequestId", evalString("window.__ps2857ControlledSpeech?.stopOptions?.requestId ?? ''"))
                .put("rawFile", rawFile)
                .put("dictatedTextHex", hex(dictatedBytes))
                .put("postStopKeyboardText", postStopKeyboardText)
                .put("postStopInputChunks", postStopInputChunks)
                .put("postStopKeyboardDraftBefore", draftBeforePostStopInput)
                .put("postStopKeyboardDraftAfter", postStopKeyboardGeometry.getString("composerDraftValue"))
                .put("postStopTerminalFocused", postStopKeyboardGeometry.getBoolean("activeElementInsideTerminal"))
                .put("expectedHostHex", expectedHostHex)
                .put("expectedByteCount", expectedHostByteCount)
                .put("expectedFinalByteCount", dictatedByteCount)
                .put("readyMarker", readyMarker)
                .put("doneMarker", doneMarker));

        int writesBeforeError = terminalInputAcknowledgements();
        tapDomCenter("[data-testid=inline-dictation-toggle]");
        awaitJsTrue("document.querySelector('[data-testid=inline-dictation-bar]')?.dataset.phase === 'listening'");
        assertEquals("error recovery must start one fresh recognizer", 2, controlledSpeechCallCount("startCount"));
        String errorRequest = evalJson("JSON.stringify(window.__ps2857ControlledSpeech.startOptions ?? null)").getString("requestId");
        evalString("window.__ps2857ControlledSpeech.emit('partial', 'discard this partial'); 'partial emitted'");
        awaitJsTrue("document.querySelector('[data-testid=inline-dictation-preview]')?.textContent.trim() === 'discard this partial'");
        evalString("window.__ps2857ControlledSpeech.emit('error', 'NETWORK'); 'error emitted'");
        awaitJsTrue("document.querySelector('[data-testid=inline-dictation-bar]')?.dataset.phase === 'stopping'"
                + " && document.querySelector('[data-testid=inline-dictation-bar]')?.dataset.dictationTone === 'error'");
        evalString("window.__ps2857ControlledSpeech.emit('stopped'); 'stopped emitted'");
        awaitJsTrue("document.querySelector('[data-testid=inline-dictation-bar]')?.dataset.phase === 'idle'"
                + " && document.querySelector('[data-testid=inline-dictation-bar]')?.dataset.dictationTone === 'error'");
        JSONObject errorGeometry = captureGeometry("dictation-error-ime-open");
        assertTerminalViewportCap("showing recognizer error status", idle, errorGeometry);
        assertCompactStatusComposerLayout("recognizer error status", errorGeometry);
        assertTrayBelowTerminalViewport(errorGeometry);
        assertDictationMicReachable(errorGeometry);
        assertDictationStableStage("showing recognizer error status", idle, errorGeometry, stableGrid,
                stableResizeAcks);
        captureScreenshot("fastkeys-dictation-error-ime-open.png");
        captureTerminalViewportScreenshot("fastkeys-dictation-error-ime-open-viewport.png", errorGeometry);
        assertEquals("recognizer errors must discard previews without writing", writesBeforeError, terminalInputAcknowledgements());
        journey.put("dictationError", new JSONObject().put("requestId", errorRequest)
                .put("tone", "error").put("writesBefore", writesBeforeError)
                .put("writesAfter", terminalInputAcknowledgements())
                .put("nativeStartCalls", controlledSpeechCallCount("startCount"))
                .put("phaseIdle", true).put("previewCleared", true));

        int writesBeforeAttachCancel = terminalInputAcknowledgements();
        String staleTarget = evalString("document.querySelector('[data-testid=inline-dictation-bar]')?.dataset.targetKey ?? ''");
        tapDomCenter("[data-testid=inline-dictation-toggle]");
        awaitJsTrue("document.querySelector('[data-testid=inline-dictation-bar]')?.dataset.phase === 'listening'");
        assertEquals("attach cancellation must not create a second recognizer", 3, controlledSpeechCallCount("startCount"));
        String attachRequest = evalJson("JSON.stringify(window.__ps2857ControlledSpeech.startOptions ?? null)").getString("requestId");
        evalString("window.__ps2857ControlledSpeech.emit('partial', 'must be cancelled on attach'); 'partial emitted'");
        int oldAttachEpoch = Integer.parseInt(evalString("String(document.querySelector('.app-shell')?.dataset.sshAttachEpoch ?? '-1')"));
        attachSession(changedSession);
        awaitJsTrue("document.querySelector('[data-testid=inline-dictation-bar]')?.dataset.targetKey !== "
                + JSONObject.quote(staleTarget)
                + " && window.__ps2857ControlledSpeech?.stopOptions?.requestId === " + JSONObject.quote(attachRequest));
        String changedTarget = evalString("document.querySelector('[data-testid=inline-dictation-bar]')?.dataset.targetKey ?? ''");
        int newAttachEpoch = Integer.parseInt(evalString("String(document.querySelector('.app-shell')?.dataset.sshAttachEpoch ?? '-1')"));
        assertTrue("the dictation target identity must change with the attach epoch", newAttachEpoch > oldAttachEpoch
                && changedTarget.endsWith("/attach-" + newAttachEpoch) && !changedTarget.equals(staleTarget));
        assertEquals("session attach must stop the pending recognizer", attachRequest,
                evalString("window.__ps2857ControlledSpeech?.stopOptions?.requestId ?? ''"));
        evalString("window.__ps2857ControlledSpeech.emit('result', 'late attach result'); 'late result emitted'");
        evalString("window.__ps2857ControlledSpeech.emit('stopped'); 'stopped emitted'");
        awaitJsTrue("document.querySelector('[data-testid=inline-dictation-bar]')?.dataset.phase === 'idle'");
        JSONObject attachCancelledGeometry = captureGeometry("dictation-attach-cancel-complete");
        assertDictationMicReachable(attachCancelledGeometry);
        captureScreenshot("fastkeys-dictation-attach-cancel.png");
        assertEquals("late attach results must not write to the new session", writesBeforeAttachCancel,
                terminalInputAcknowledgements());
        journey.put("dictationAttachCancel", new JSONObject().put("requestId", attachRequest)
                .put("stopRequestId", evalString("window.__ps2857ControlledSpeech?.stopOptions?.requestId ?? ''"))
                .put("oldTargetKey", staleTarget).put("newTargetKey", changedTarget)
                .put("oldAttachEpoch", oldAttachEpoch).put("newAttachEpoch", newAttachEpoch)
                .put("lateResultEmitted", true).put("stoppedEmitted", true)
                .put("nativeStartCalls", controlledSpeechCallCount("startCount"))
                .put("nativeStopCalls", controlledSpeechCallCount("stopCount"))
                .put("writesBefore", writesBeforeAttachCancel).put("writesAfter", terminalInputAcknowledgements()));

        tapDomCenter("[data-testid=prompt-draft]");
        awaitImeVisible(true);
        JSONObject changedSessionGeometry = captureGeometry("dictation-reattached-ime-open");
        assertUnchangedTerminalGrid("changed-session dictation reattach", stableGrid,
                runtimeGrid(changedSessionGeometry));
        assertAcceptedKeyboardUpViewport("changed-session dictation reattach", changedSessionGeometry);
        assertDictationMicReachable(changedSessionGeometry);
        assertTrayBelowTerminalViewport(changedSessionGeometry);
        assertTrue("dictation reattach must keep at least five xterm rows visible",
                runtimeGrid(changedSessionGeometry).getInt("rows") >= 5);
        captureScreenshot("fastkeys-dictation-reattached-ime-open.png");

        int writesBeforeBackgroundCancel = terminalInputAcknowledgements();
        tapDomCenter("[data-testid=inline-dictation-toggle]");
        awaitJsTrue("document.querySelector('[data-testid=inline-dictation-bar]')?.dataset.phase === 'listening'");
        assertEquals("background cancellation must have one active recognizer", 4, controlledSpeechCallCount("startCount"));
        String backgroundRequest = evalJson("JSON.stringify(window.__ps2857ControlledSpeech.startOptions ?? null)").getString("requestId");
        evalString("window.__ps2857ControlledSpeech.emit('partial', 'must be cancelled on background'); 'partial emitted'");
        awaitJsTrue("document.querySelector('[data-testid=inline-dictation-preview]')?.textContent.trim() === 'must be cancelled on background'");
        scenario.moveToState(Lifecycle.State.CREATED);
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.sshPhase === 'background'", 10_000);
        scenario.moveToState(Lifecycle.State.RESUMED);
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.sshPhase === 'live'", 15_000);
        evalString("window.__ps2857ControlledSpeech.emit('result', 'late background result'); 'late result emitted'");
        evalString("window.__ps2857ControlledSpeech.emit('stopped'); 'stopped emitted'");
        awaitJsTrue("document.querySelector('[data-testid=inline-dictation-bar]')?.dataset.phase === 'idle'");
        assertEquals("background results must not write after resume", writesBeforeBackgroundCancel,
                terminalInputAcknowledgements());
        assertEquals("backgrounding must stop the pending recognizer", backgroundRequest,
                evalString("window.__ps2857ControlledSpeech?.stopOptions?.requestId ?? ''"));
        journey.put("dictationBackgroundCancel", new JSONObject().put("requestId", backgroundRequest)
                .put("stopRequestId", evalString("window.__ps2857ControlledSpeech?.stopOptions?.requestId ?? ''"))
                .put("lateResultEmitted", true).put("stoppedEmitted", true)
                .put("nativeStartCalls", controlledSpeechCallCount("startCount"))
                .put("nativeStopCalls", controlledSpeechCallCount("stopCount"))
                .put("writesBefore", writesBeforeBackgroundCancel).put("writesAfter", terminalInputAcknowledgements()));
        JSONObject backgroundGeometry = captureGeometry("dictation-background-cancel-resumed");
        assertDictationMicReachable(backgroundGeometry);
        attachSession(firstSession);
    }

    private void assertDictationMicReachable(JSONObject geometry) throws Exception {
        JSONObject bar = geometry.optJSONObject("inlineDictationBar");
        JSONObject mic = geometry.optJSONObject("inlineDictationMic");
        assertNotNull("one inline dictation component must live in the fast-key dock", bar);
        assertNotNull("the docked dictation component must render its mic/Stop action", mic);
        assertEquals("the dock must have exactly one inline controller surface", 1,
                geometry.getInt("inlineDictationBarCount"));
        assertEquals("the dock must have exactly one mic/Stop target", 1,
                geometry.getInt("inlineDictationMicCount"));
        assertTrue("the mic target must be at least 48dp wide and tall: " + mic,
                mic.getDouble("width") >= 47.9 && mic.getDouble("height") >= 47.9);
        assertTrue("the mic must stay fully visible above the IME: " + mic, mic.getBoolean("insideViewport"));
        JSONObject keybar = geometry.getJSONObject("keybarClientRect");
        assertTrue("the persistent mic must remain fully inside the key row: " + geometry,
                geometry.getBoolean("inlineDictationMicInsideKeybar")
                        && mic.getDouble("visibleWidthInKeybar") >= 47.9
                        && mic.getDouble("visibleHeightInKeybar") >= 47.9
                        && mic.getDouble("top") >= keybar.getDouble("top") - 0.5
                        && mic.getDouble("bottom") <= keybar.getDouble("bottom") + 0.5);
        assertTrue("the mic must remain inside the fast-key dock: " + geometry,
                geometry.getBoolean("inlineDictationBarInsideTray") && geometry.getBoolean("inlineDictationMicInsideBar"));
        JSONObject rowMetrics = geometry.optJSONObject("persistentRowMetrics");
        assertNotNull("the live keyboard-width row must expose its measured content width", rowMetrics);
        assertTrue("the live keyboard row must contain all persistent controls without clipping or scrolling: " + geometry,
                !rowMetrics.getBoolean("scrollable")
                        && rowMetrics.getDouble("scrollWidth") <= rowMetrics.getDouble("clientWidth") + 0.5);
        String phase = geometry.getString("inlineDictationPhase");
        String tone = geometry.getString("inlineDictationTone");
        boolean transcribing = List.of("stopping", "cancelling", "inserting").contains(phase);
        String expectedAccessibleLabel = "listening".equals(phase) ? "Stop terminal dictation"
                : "starting".equals(phase) ? "Cancel terminal dictation request"
                : List.of("stopping", "cancelling").contains(phase) ? "Transcribing terminal speech"
                : "inserting".equals(phase) ? "Inserting terminal speech"
                : mic.getBoolean("disabled") ? "Terminal dictation unavailable" : "Dictate to terminal";
        String expectedMicState = "listening".equals(phase) ? "listening"
                : transcribing ? "transcribing" : "starting".equals(phase) ? "starting"
                : "error".equals(tone) ? "error" : "idle";
        assertEquals("persistent dictation mic stays icon-only, without crowding the toolbar: " + geometry,
                "", mic.getString("visibleLabel"));
        assertEquals("inline dictation keeps a phase-specific accessible action label: " + geometry,
                expectedAccessibleLabel, mic.getString("label"));
        assertEquals("only the listening mic is exposed as pressed: " + geometry,
                "listening".equals(phase), mic.getBoolean("pressed"));
        assertEquals("inline dictation exposes its idle/listening/transcribing/error state: " + geometry,
                expectedMicState, mic.getString("micState"));
        assertEquals("only the in-flight transcription action disables the microphone: " + geometry,
                transcribing, mic.getBoolean("disabled"));
        assertTrue("inline dictation mic center remains a direct hit target: " + mic, mic.getBoolean("hitTarget"));
        JSONArray navigationTargets = geometry.getJSONArray("navigationTargets");
        JSONObject fastKeysTarget = navigationTargets.getJSONObject(navigationTargets.length() - 1);
        JSONObject dockBounds = geometry.getJSONObject("mobileHotkeys");
        double trailingGap = mic.getDouble("left") - fastKeysTarget.getDouble("right");
        assertTrue("the live-terminal mic must follow the launcher as the last control, with dock slack after it: " + geometry,
                trailingGap >= -0.5 && trailingGap <= 8.5
                        && mic.getDouble("right") <= dockBounds.getDouble("right") + 0.5);
        if (geometry.getBoolean("inlineDictationStatusVisible")) {
            assertTrue("the dictation status chip must render on one line", geometry.getBoolean("inlineDictationStatusOneLine"));
            assertTrue("the dictation status chip must stay inside the dock", geometry.getBoolean("inlineDictationStatusInsideBar"));
            JSONObject statusRow = geometry.getJSONObject("inlineDictationStatusRow");
            assertTrue("dictation status must precede persistent keys on both catalog pages: " + geometry,
                    geometry.getBoolean("inlineDictationStatusAboveKeybar")
                            && !geometry.getBoolean("inlineDictationStatusInsideSheetHeader")
                            && statusRow.getDouble("bottom") <= keybar.getDouble("top") + 0.5);
            assertEquals("the Android dictation status is one readable 16dp line", 16,
                    statusRow.getDouble("height"), 0.5);
        } else {
            assertEquals("idle default hint must not consume a status row", "idle", geometry.getString("inlineDictationPhase"));
        }
        int attachEpoch = geometry.getInt("sshAttachEpoch");
        assertTrue("dictation target identity must include the current attach epoch: " + geometry,
                geometry.getString("inlineDictationTargetKey").endsWith("/attach-" + attachEpoch));
    }

    private void installControlledSpeechAdapter() throws Exception {
        String installed = evalString("(() => {"
                + "const cap=window.Capacitor;"
                + "if(!cap||typeof cap.nativePromise!=='function'||typeof cap.nativeCallback!=='function')return 'missing-capacitor-bridge';"
                + "const nativePromise=cap.nativePromise.bind(cap);const nativeCallback=cap.nativeCallback.bind(cap);"
                + "const state={startOptions:null,stopOptions:null,requestId:null,listener:null,startCount:0,stopCount:0,"
                + "emit(type,text){if(!this.listener)throw new Error('speech listener is not registered');"
                + "this.listener({requestId:this.requestId,type,...(text===undefined?{}:{text})});}};"
                + "window.__ps2857ControlledSpeech=state;"
                + "cap.nativePromise=(plugin,method,options)=>{"
                + "if(plugin!=='SpeechRecognition')return nativePromise(plugin,method,options);"
                + "if(method==='startDictation'){state.startCount+=1;state.startOptions=JSON.parse(JSON.stringify(options));state.stopOptions=null;state.requestId=options.requestId;"
                + "return Promise.resolve({requestId:state.requestId,started:true});}"
                + "if(method==='stopDictation'){state.stopCount+=1;state.stopOptions=JSON.parse(JSON.stringify(options));"
                + "return Promise.resolve({requestId:options.requestId,stopped:true});}"
                + "if(method==='getCapabilities')return Promise.resolve({speechRecognitionAvailable:true,microphonePermissionGranted:true});"
                + "return Promise.reject(new Error('unexpected controlled speech method '+method));};"
                + "cap.nativeCallback=(plugin,method,options,callback)=>{"
                + "if(plugin!=='SpeechRecognition')return nativeCallback(plugin,method,options,callback);"
                + "if(method==='addListener'){state.listener=callback;return Promise.resolve({callbackId:'controlled-dictation'});}"
                + "if(method==='removeListener')return Promise.resolve({removed:true});"
                + "return Promise.reject(new Error('unexpected controlled speech callback '+method));};"
                + "return 'installed';})()");
        assertEquals("test must replace only the Android speech bridge", "installed", installed);
    }

    private int terminalInputAcknowledgements() throws Exception {
        return Integer.parseInt(evalString("document.querySelector('.app-shell')?.dataset.sshTerminalInputAcks ?? '0'"));
    }

    private int controlledSpeechCallCount(String name) throws Exception {
        return Integer.parseInt(evalString("String(window.__ps2857ControlledSpeech?.[" + JSONObject.quote(name) + "] ?? 0)"));
    }

    private String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
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
        markResizeFitPhase("set-receiver-draft:" + readyMarker);
        setValue("[data-testid=prompt-draft]", command);
        markResizeFitPhase("send-receiver-command:" + readyMarker);
        click(".composer-shared-controls .send");
        awaitJsTrue("document.querySelector('[data-testid=prompt-draft]')?.value === ''");
        awaitJsTrue("(window.__ps2857TerminalVisibleText || '').includes(" + JSONObject.quote(readyMarker) + ")", 15_000);
        SystemClock.sleep(300);
        markResizeFitPhase("receiver-ready:" + readyMarker);
    }

    private void markResizeFitPhase(String phase) throws Exception {
        evalString("window.__ps2884ResizeFitMarker=" + JSONObject.quote(phase) + "; 'resize marker set'");
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
        swipeFastKeyIntoView(selector);
        assertCatalogActionReachable(selector, keyId);
        int previous = hotkeyWrites().length();
        tapDomCenter(selector);
        awaitHotkeyWrites(previous + 1);
        awaitImeVisible(true);
        assertTrue("the fast-key tray must stay open after key taps", "true".equals(evalRaw(
                "document.querySelector('[data-testid=mobile-hotkeys]')?.dataset.paletteOpen === 'true'")));
    }

    private JSONObject assertCatalogActionReachable(String selector, String keyId) throws Exception {
        JSONObject target = evalJson("(() => {const node=document.querySelector(" + JSONObject.quote(selector) + ");"
                + "const content=node?.closest('.mobile-hotkeys__main-keys,.mobile-hotkeys__ctrl-grid');if(!node||!content)return JSON.stringify({missing:true});"
                + "const r=node.getBoundingClientRect(),c=content.getBoundingClientRect(),v=window.visualViewport;"
                + "const bounds=b=>({left:b.left,right:b.right,top:b.top,bottom:b.bottom,width:b.width,height:b.height});"
                + "return JSON.stringify({missing:false,width:r.width,height:r.height,targetBounds:bounds(r),contentBounds:bounds(c),"
                + "insideContent:r.left>=c.left-0.5&&r.right<=c.right+0.5&&r.top>=c.top-0.5&&r.bottom<=c.bottom+0.5,"
                + "insideViewport:r.left>=0&&r.top>=0&&r.right<=innerWidth&&r.bottom<=(v?.height??innerHeight)});})()");
        target.put("keyId", keyId);
        assertTrue("catalog action must exist before it is tapped: " + target, !target.optBoolean("missing", true));
        assertTrue("catalog action must retain its full 48dp touch-target height: " + target,
                target.getDouble("height") >= 47.9);
        assertTrue("catalog action must retain its full 48dp touch-target width: " + target,
                target.getDouble("width") >= 47.9);
        assertTrue("catalog action must be fully visible inside its scroll viewport: " + target,
                target.getBoolean("insideContent"));
        assertTrue("catalog action must remain inside the visible Android viewport: " + target,
                target.getBoolean("insideViewport"));
        return target;
    }

    private void sendControl(String keyId, boolean hold) throws Exception {
        swipeFastKeyIntoView("[data-key-id='" + keyId + "']");
        int previous = hotkeyWrites().length();
        if (hold) longPressDomCenter("[data-key-id='" + keyId + "']", 700);
        else tapDomCenter("[data-key-id='" + keyId + "']");
        awaitHotkeyWrites(previous + 1);
        awaitJsTrue("document.activeElement?.matches('[data-testid=prompt-draft]') === true", 3_000);
        awaitImeVisible(true);
    }

    private void cancelControlPress(String keyId) throws Exception {
        swipeFastKeyIntoView("[data-key-id='" + keyId + "']");
        int beforeCancel = hotkeyWrites().length();
        float[] point = screenPoint("[data-key-id='" + keyId + "']");
        long downTime = SystemClock.uptimeMillis();
        injectTouch(MotionEvent.ACTION_DOWN, point[0], point[1], downTime, downTime);
        SystemClock.sleep(80);
        injectTouch(MotionEvent.ACTION_CANCEL, point[0], point[1], downTime, SystemClock.uptimeMillis());
        SystemClock.sleep(250);
        assertEquals("pointer cancellation must not send a tap or hold", beforeCancel, hotkeyWrites().length());
    }

    private JSONArray assertCatalogReachable(String containerSelector, int expectedKeys) throws Exception {
        JSONArray keyIds = new JSONArray(evalString("JSON.stringify(Array.from(document.querySelectorAll(" + JSONObject.quote(containerSelector + " [data-key-id]")
                + ")).map(node => node.dataset.keyId))"));
        assertEquals("the docked catalog must render every offered key", expectedKeys, keyIds.length());
        int writesBeforeReachabilitySwipes = hotkeyWrites().length();
        List<String> seen = new ArrayList<>();
        JSONArray reachable = new JSONArray();
        for (int index = 0; index < keyIds.length(); index += 1) {
            String keyId = keyIds.getString(index);
            assertTrue("catalog keys must be unique: " + keyId, !seen.contains(keyId));
            seen.add(keyId);
            String selector = containerSelector + " [data-key-id='" + keyId + "']";
            // This pass proves physical scroll reachability without changing the byte oracle.
            swipeFastKeyIntoView(selector);
            reachable.put(assertCatalogActionReachable(selector, keyId));
        }
        assertEquals("physical catalog reachability swipes must not activate keys or write to the PTY",
                writesBeforeReachabilitySwipes, hotkeyWrites().length());
        return reachable;
    }

    private void scrollCatalogToStart(String selector) throws Exception {
        evalString("(() => {const node=document.querySelector(" + JSONObject.quote(selector) + ");"
                + "if(!node)throw new Error('missing catalog scroller '+" + JSONObject.quote(selector) + ");"
                + "node.scrollTop=0;node.scrollLeft=0;return String(node.scrollTop);})()");
        awaitRenderedFrame();
        awaitJsTrue("document.querySelector(" + JSONObject.quote(selector) + ")?.scrollTop === 0");
    }

    private void swipeFastKeyIntoView(String selector) throws Exception {
        JSONObject geometry = fastKeyGeometry(selector);
        assertTrue("the requested fast key must exist inside a docked catalog: " + selector,
                !geometry.optBoolean("missing", true));
        for (int attempt = 0; attempt < MAX_CATALOG_SWIPE_ATTEMPTS; attempt += 1) {
            if (geometry.getBoolean("insideContent") && geometry.getBoolean("insideViewport")) return;

            JSONObject key = geometry.getJSONObject("key");
            JSONObject container = geometry.getJSONObject("container");
            boolean horizontal = "horizontal".equals(geometry.getString("axis"));
            boolean towardEnd = horizontal
                    ? key.getDouble("right") > container.getDouble("right")
                    : key.getDouble("bottom") > container.getDouble("bottom");
            boolean towardStart = horizontal
                    ? key.getDouble("left") < container.getDouble("left")
                    : key.getDouble("top") < container.getDouble("top");
            assertTrue("catalog target must be scrollable into view: " + selector + "; " + geometry,
                    towardEnd || towardStart);

            JSONObject anchors = geometry.getJSONObject("swipeAnchors");
            JSONObject anchor = horizontal
                    ? anchors.getJSONObject(towardEnd ? "right" : "left")
                    : anchors.getJSONObject("vertical");
            if (!horizontal) {
                assertTrue("catalog swipes must start in a blank gutter away from the Android back edge: " + geometry,
                        !anchor.isNull("x") && !anchor.isNull("y") && anchor.optBoolean("clearOfButtons"));
            }
            double dimension = horizontal ? container.getDouble("width") : container.getDouble("height");
            double overflow = horizontal
                    ? (towardEnd ? key.getDouble("right") - container.getDouble("right")
                            : container.getDouble("left") - key.getDouble("left"))
                    : (towardEnd ? key.getDouble("bottom") - container.getDouble("bottom")
                            : container.getDouble("top") - key.getDouble("top"));
            double distance = Math.min(Math.max(48, overflow + 12), dimension * 0.7);
            double startX = anchor.getDouble("x");
            double startY = anchor.getDouble("y");
            double endX = startX;
            double endY = startY;
            if (horizontal) {
                endX += towardEnd ? -distance : distance;
            } else {
                endY = towardEnd ? container.getDouble("top") + 2 : container.getDouble("bottom") - 2;
            }
            assertTrue("injected swipe must stay within the catalog viewport: " + geometry,
                    endX >= container.getDouble("left") + 1
                            && endX <= container.getDouble("right") - 1
                            && endY >= container.getDouble("top") + 1
                            && endY <= container.getDouble("bottom") - 1);

            double beforeOffset = geometry.getDouble(horizontal ? "scrollLeft" : "scrollTop");
            float[] start = screenPoint((float) startX, (float) startY);
            float[] end = screenPoint((float) endX, (float) endY);
            int writesBeforeSwipe = hotkeyWrites().length();
            injectSwipe(start[0], start[1], end[0], end[1]);
            assertEquals("a physical catalog swipe must not activate a key or write to the PTY: " + selector,
                    writesBeforeSwipe, hotkeyWrites().length());
            geometry = fastKeyGeometry(selector);
            geometry.put("lastInjectedSwipe", new JSONObject()
                    .put("cssStartX", startX)
                    .put("cssStartY", startY)
                    .put("cssEndX", endX)
                    .put("cssEndY", endY)
                    .put("screenStartX", start[0])
                    .put("screenStartY", start[1])
                    .put("screenEndX", end[0])
                    .put("screenEndY", end[1]));
            double afterOffset = geometry.getDouble(horizontal ? "scrollLeft" : "scrollTop");
            double offsetDelta = afterOffset - beforeOffset;
            assertTrue("an Android swipe must move the catalog scroll position: " + geometry,
                    !geometry.optBoolean("missing", true) && Math.abs(offsetDelta) > 0.5);
            assertTrue("Android swipe direction must move toward the requested key: " + geometry,
                    towardEnd ? offsetDelta > 0.5 : offsetDelta < -0.5);
        }
        assertTrue("bounded Android swipes must leave the requested 48dp key fully visible: " + selector + "; " + geometry,
                geometry.getBoolean("insideContent") && geometry.getBoolean("insideViewport"));
    }

    private JSONObject fastKeyGeometry(String selector) throws Exception {
        return evalJson("(() => {const target=document.querySelector(" + JSONObject.quote(selector) + ");"
                + "const container=target?.closest('.mobile-hotkeys__main-keys,.mobile-hotkeys__ctrl-grid');"
                + "if(!target||!container)return JSON.stringify({missing:true});"
                + "const r=target.getBoundingClientRect(),c=container.getBoundingClientRect(),v=window.visualViewport;"
                + "const style=getComputedStyle(container),buttonRects=Array.from(container.querySelectorAll('button[data-key-id]')).map(n=>{const b=n.getBoundingClientRect();"
                + "return {keyId:n.dataset.keyId,top:b.top,bottom:b.bottom,width:b.width,height:b.height};});"
                + "const horizontal=container.matches('.mobile-hotkeys__main-keys'),y=c.top+c.height/2;"
                + "const verticalAnchor=(()=>{for(let x=c.left+24;x<c.right-16;x+=4){const hit=document.elementFromPoint(x,y);"
                + "const insideScroller=!!hit&&(hit===container||container.contains(hit));"
                + "if(insideScroller&&!hit.closest('button'))return {x,y,insideScroller,clearOfButtons:true};}"
                + "return {x:null,y:null,insideScroller:false,clearOfButtons:false};})();"
                + "const freeX=fromRight=>{const step=fromRight?-1:1,start=fromRight?c.right-1:c.left+1;"
                + "for(let x=start;fromRight?x>c.left+1:x<c.right-1;x+=step){if(!document.elementFromPoint(x,y)?.closest('button'))return {x,y};}return null;};"
                + "const swipeAnchors=horizontal?{left:freeX(false),right:freeX(true),vertical:null}:"
                + "{left:null,right:null,vertical:verticalAnchor};"
                + "return JSON.stringify({missing:false,axis:horizontal?'horizontal':'vertical',"
                + "insideContent:r.left>=c.left-0.5&&r.right<=c.right+0.5&&r.top>=c.top-0.5&&r.bottom<=c.bottom+0.5,"
                + "insideViewport:r.left>=0&&r.top>=0&&r.bottom<=(v?.height??innerHeight)+0.5&&r.right<=innerWidth+0.5,"
                + "key:{left:r.left,right:r.right,top:r.top,bottom:r.bottom,width:r.width,height:r.height},"
                + "container:{left:c.left,right:c.right,top:c.top,bottom:c.bottom,width:c.width,height:c.height},"
                + "layout:{rowGap:style.rowGap,columnGap:style.columnGap,gridAutoRows:style.gridAutoRows,"
                + "clientHeight:container.clientHeight,scrollHeight:container.scrollHeight,scrollTop:container.scrollTop,buttonRects},"
                + "swipeAnchors,scrollLeft:container.scrollLeft,scrollTop:container.scrollTop});})()");
    }

    private void injectSwipe(float startX, float startY, float endX, float endY) {
        long downTime = SystemClock.uptimeMillis();
        injectTouch(MotionEvent.ACTION_DOWN, startX, startY, downTime, downTime);
        SystemClock.sleep(60);
        int moveSteps = 4;
        for (int step = 1; step <= moveSteps; step += 1) {
            SystemClock.sleep(35);
            float progress = step / (float) moveSteps;
            injectTouch(MotionEvent.ACTION_MOVE,
                    startX + (endX - startX) * progress,
                    startY + (endY - startY) * progress,
                    downTime,
                    SystemClock.uptimeMillis());
        }
        SystemClock.sleep(35);
        injectTouch(MotionEvent.ACTION_UP, endX, endY, downTime, SystemClock.uptimeMillis());
        SystemClock.sleep(160);
    }

    private JSONObject captureGeometry(String stage) throws Exception {
        JSONObject dom = evalJson("(() => {window.dispatchEvent(new Event('pocketshell:terminal-geometry-request'));"
                + "const rect=s=>{const n=document.querySelector(s);if(!n)return null;const r=n.getBoundingClientRect();"
                + "return {top:r.top,bottom:r.bottom,left:r.left,right:r.right,width:r.width,height:r.height};};"
                + "const target=n=>{const r=n.getBoundingClientRect();const v=window.visualViewport;const rowNode=document.querySelector('.mobile-hotkeys__bar');"
                + "const row=rowNode?.getBoundingClientRect(),clip=row&&rowNode?{left:row.left+rowNode.clientLeft,top:row.top+rowNode.clientTop,"
                + "right:row.left+rowNode.clientLeft+rowNode.clientWidth,bottom:row.top+rowNode.clientTop+rowNode.clientHeight}:null;"
                + "const visibleWidthInKeybar=clip?Math.max(0,Math.min(r.right,clip.right)-Math.max(r.left,clip.left)):null;"
                + "const visibleHeightInKeybar=clip?Math.max(0,Math.min(r.bottom,clip.bottom)-Math.max(r.top,clip.top)):null;"
                + "const x=r.left+r.width/2,y=r.top+r.height/2;"
                + "const hit=document.elementFromPoint(x,y);return {label:n.getAttribute('aria-label')||'',"
                + "top:r.top,bottom:r.bottom,left:r.left,right:r.right,width:r.width,height:r.height,disabled:!!n.disabled,"
                + "visibleWidthInKeybar,visibleHeightInKeybar,"
                + "pressed:n.getAttribute('aria-pressed')==='true',checked:n.getAttribute('aria-checked')==='true',"
                + "micState:n.dataset.micState||'',"
                + "hitTarget:!!hit&&(hit===n||n.contains(hit)),"
                + "insideViewport:r.top>=0&&r.left>=0&&r.bottom<=(v?.height??innerHeight)+0.5&&r.right<=innerWidth+0.5};};"
                + "const shell=document.querySelector('.app-shell');const slot=document.querySelector('[data-testid=terminal-slot]');"
                + "const tray=document.querySelector('[data-testid=mobile-hotkeys]');const trayRect=rect('[data-testid=mobile-hotkeys]');"
                + "const slotRect=rect('[data-testid=terminal-slot]');const terminalRect=rect('.terminal-viewport');"
                + "const runtimeGeometry=window.__ps2875TerminalRuntimeGeometry??null;"
                + "const visibleTerminalRows=runtimeGeometry&&terminalRect&&runtimeGeometry.cellHeight>0?Math.floor((terminalRect.height-8)/runtimeGeometry.cellHeight):0;"
                + "const catalogSheetNode=document.querySelector('[data-testid=mobile-hotkeys-sheet]');"
                + "const catalogSheet=rect('[data-testid=mobile-hotkeys-sheet]');"
                + "const pageActionNode=document.querySelector('[data-testid=mobile-hotkeys-open-ctrl-page],[data-testid=mobile-hotkeys-back-main-page]');"
                + "const pageAction=pageActionNode?target(pageActionNode):null;"
                + "const catalogTitleNode=tray?.querySelector('[data-testid=mobile-hotkeys-sheet-title]');"
                + "const catalogTitleBounds=catalogTitleNode?.getBoundingClientRect();"
                + "const catalogTitle=catalogTitleNode&&catalogTitleBounds?{text:catalogTitleNode.textContent.trim(),top:catalogTitleBounds.top,bottom:catalogTitleBounds.bottom,"
                + "left:catalogTitleBounds.left,right:catalogTitleBounds.right,width:catalogTitleBounds.width,height:catalogTitleBounds.height,"
                + "fits:catalogTitleNode.scrollWidth<=catalogTitleNode.clientWidth+1}:null;"
                + "const catalogScroller=tray?.querySelector('.mobile-hotkeys__main-keys,.mobile-hotkeys__ctrl-grid');"
                + "const catalogScrollStyle=catalogScroller?getComputedStyle(catalogScroller):null;"
                + "const catalogScrollerRect=catalogScroller?.getBoundingClientRect();"
                + "const catalogScrollerInsideSheet=!!catalogScrollerRect&&!!catalogSheet"
                + "&&catalogScrollerRect.top>=catalogSheet.top-" + TERMINAL_VIEWPORT_ROUNDING_EPSILON_CSS_PX
                + "&&catalogScrollerRect.bottom<=catalogSheet.bottom+" + TERMINAL_VIEWPORT_ROUNDING_EPSILON_CSS_PX
                + "&&catalogScrollerRect.left>=catalogSheet.left-" + TERMINAL_VIEWPORT_ROUNDING_EPSILON_CSS_PX
                + "&&catalogScrollerRect.right<=catalogSheet.right+" + TERMINAL_VIEWPORT_ROUNDING_EPSILON_CSS_PX + ";"
                + "const catalogButtonRects=catalogScroller?Array.from(catalogScroller.querySelectorAll('button[data-key-id]')).map(node=>{const r=node.getBoundingClientRect();"
                + "return {keyId:node.dataset.keyId,left:r.left,right:r.right,top:r.top,bottom:r.bottom,width:r.width,height:r.height};}):[];"
                + "const catalogScrollMetrics=catalogScroller?{clientWidth:catalogScroller.clientWidth,scrollWidth:catalogScroller.scrollWidth,scrollLeft:catalogScroller.scrollLeft,"
                + "clientHeight:catalogScroller.clientHeight,scrollHeight:catalogScroller.scrollHeight,scrollTop:catalogScroller.scrollTop,"
                + "rowGap:catalogScrollStyle?.rowGap??'',columnGap:catalogScrollStyle?.columnGap??'',gridAutoRows:catalogScrollStyle?.gridAutoRows??'',buttonRects:catalogButtonRects,"
                + "axis:catalogScroller.matches('.mobile-hotkeys__ctrl-grid')?'vertical':'grid'}:null;"
                + "const overlaps=(a,b)=>!!a&&!!b&&a.left<b.right&&a.right>b.left&&a.top<b.bottom&&a.bottom>b.top;"
                + "const sheetInside=pageActionNode&&catalogSheet?(()=>{const a=pageActionNode.getBoundingClientRect();return a.left>=catalogSheet.left-0.5&&a.right<=catalogSheet.right+0.5&&a.top>=catalogSheet.top-0.5&&a.bottom<=catalogSheet.bottom+0.5;})():false;"
                + "const composerRect=rect('.composer-panel');"
                + "const composerDraftRect=rect('[data-testid=prompt-draft]');"
                + "const composerActionRow=rect('[data-testid=composer-actions]');"
                + "const composerPanelNode=document.querySelector('.composer-panel');const composerPanelBounds=composerPanelNode?.getBoundingClientRect();"
                + "const composerActionSelectors=[['discard','[data-testid=composer-discard]'],['dictate','[data-testid=composer-dictate]'],"
                + "['insert','[data-testid=composer-insert]'],['send','.composer-shared-controls .send']];"
                + "const composerActions=composerActionSelectors.map(([action,selector])=>{const n=document.querySelector(selector);if(!n)return {action,missing:true};"
                + "const t=target(n),r=n.getBoundingClientRect();return {...t,action,testId:n.getAttribute('data-testid')||'',text:n.textContent.trim(),insideComposerPanel:!!composerPanelBounds"
                + "&&r.top>=composerPanelBounds.top-0.5&&r.left>=composerPanelBounds.left-0.5&&r.bottom<=composerPanelBounds.bottom+0.5"
                + "&&r.right<=composerPanelBounds.right+0.5};});"
                + "const activeElement=document.activeElement;const promptDraft=document.querySelector('[data-testid=prompt-draft]');"
                + "const inlineDictationBar=rect('[data-testid=inline-dictation-bar]');"
                + "const inlineDictationBarNode=document.querySelector('[data-testid=inline-dictation-bar]');"
                + "const inlineDictationMicNode=document.querySelector('[data-testid=inline-dictation-toggle]');"
                + "const inlineDictationMic=inlineDictationMicNode?{...target(inlineDictationMicNode),visibleLabel:"
                + "''}:null;"
                + "const enterDivider=rect('[data-testid=mobile-hotkeys-enter-divider]');"
                + "const inlineDictationStatusRow=rect('[data-testid=inline-dictation-status-row]');"
                + "const dictationSheetHeader=rect('.mobile-hotkeys__sheet-header');"
                + "const keybarRect=rect('.mobile-hotkeys__bar');"
                + "const keybarNode=document.querySelector('.mobile-hotkeys__bar');"
                + "const keybarClientRect=keybarNode?(()=>{const r=keybarNode.getBoundingClientRect(),left=r.left+keybarNode.clientLeft,top=r.top+keybarNode.clientTop;"
                + "return {left,top,right:left+keybarNode.clientWidth,bottom:top+keybarNode.clientHeight,width:keybarNode.clientWidth,height:keybarNode.clientHeight};})():null;"
                + "const persistentRowMetrics=keybarNode?{clientWidth:keybarNode.clientWidth,scrollWidth:keybarNode.scrollWidth,scrollLeft:keybarNode.scrollLeft,"
                + "scrollable:keybarNode.scrollWidth>keybarNode.clientWidth+1}:null;"
                + "const inlineDictationStatusNode=document.querySelector('[data-testid=inline-dictation-status]');"
                + "const inlineDictationStatusStyle=inlineDictationStatusNode?getComputedStyle(inlineDictationStatusNode):null;"
                + "const fitEvents=window.__ps2884ResizeFitEvents??[],ackEvents=window.__ps2884ResizeAckEvents??[];"
                + "const fitCursor=window.__ps2884ResizeFitTraceCursor??0,ackCursor=window.__ps2884ResizeAckTraceCursor??0;"
                + "const fitEventsSince=fitEvents.slice(fitCursor),ackEventsSince=ackEvents.slice(ackCursor);"
                + "window.__ps2884ResizeFitTraceCursor=fitEvents.length;window.__ps2884ResizeAckTraceCursor=ackEvents.length;"
                + "const inlineDictationStatusOneLine=!!inlineDictationStatusNode&&inlineDictationStatusStyle?.whiteSpace==='nowrap'"
                + "&&inlineDictationStatusNode.clientHeight>0&&inlineDictationStatusNode.scrollHeight<=inlineDictationStatusNode.clientHeight+1;"
                + "const keys=Array.from(document.querySelectorAll('[data-testid=mobile-hotkeys] .mobile-hotkeys__navigation button,"
                + "[data-testid=mobile-hotkeys-launcher]')).map(target);"
                + "const hotkeyControls=Array.from(document.querySelectorAll('[data-testid=mobile-hotkeys],"
                + "[data-testid=mobile-hotkeys-launcher],[data-testid=mobile-hotkeys-main-page],"
                + "[data-testid=mobile-hotkeys-ctrl-page],[data-key-id]')).map(node=>({"
                + "testId:node.getAttribute('data-testid'),keyId:node.getAttribute('data-key-id'),"
                + "disabled:'disabled' in node?!!node.disabled:null}));"
                + "const terminalPanelRect=rect('.terminal-panel'),terminalSlotRect=rect('[data-testid=terminal-slot]');"
                + "const terminalSlotInsideTerminalPanel=!!terminalPanelRect&&!!terminalSlotRect"
                + "&&terminalSlotRect.top>=terminalPanelRect.top-0.5&&terminalSlotRect.bottom<=terminalPanelRect.bottom+0.5;"
                + "return JSON.stringify({stage:" + JSONObject.quote(stage) + ",androidApi:" + Build.VERSION.SDK_INT + ","
                + "keyboardVisible:shell?.dataset.keyboardVisible==='true',keyboardComposerMode:shell?.dataset.keyboardComposerMode==='true',"
                + "terminalViewportFocused:shell?.dataset.terminalViewportFocused==='true',"
                + "activeElementInsideTerminal:!!activeElement?.closest('.terminal-viewport'),"
                + "activeElementIsPromptDraft:activeElement===promptDraft,composerDraftValue:promptDraft?.value??'',"
                + "activeElementTag:activeElement?.tagName?.toLowerCase()??'',"
                + "fastKeysPage:tray?.dataset.palettePage||'closed',"
                + "sshPhase:shell?.dataset.sshPhase||'',homeSurface:shell?.dataset.homeSurface||'',"
                + "sshAttachEpoch:Number(shell?.dataset.sshAttachEpoch??-1),"
                + "terminalPanel:terminalPanelRect,terminalSlot:terminalSlotRect,terminalSlotInsideTerminalPanel,terminalViewport:rect('.terminal-viewport'),"
                + "catalogScrollerInsideSheet,catalogScrollerBounds:catalogScrollerRect?{top:catalogScrollerRect.top,bottom:catalogScrollerRect.bottom,"
                + "left:catalogScrollerRect.left,right:catalogScrollerRect.right,width:catalogScrollerRect.width,height:catalogScrollerRect.height}:null,"
                + "terminalViewportDockCapPx:Number(slot?.dataset.terminalViewportDockCap??0),"
                + "terminalHotkeysDockHeightPx:Number(slot?.dataset.terminalHotkeysDockHeight??0),"
                + "mobileHotkeys:trayRect,navigationTargets:keys,enterDivider,persistentRowMetrics,hotkeyControls,"
                + "catalogSheet,catalogSheetModal:catalogSheetNode?.getAttribute('aria-modal')??null,"
                + "visibleTerminalRows,runtimeGeometry,"
                + "catalogSheetBelowTerminalViewport:!!catalogSheet&&!!terminalRect&&catalogSheet.top>=terminalRect.bottom,"
                + "catalogSheetIntersectsComposer:overlaps(catalogSheet,composerRect),catalogPageAction:pageAction?{...pageAction,insideCatalogSheet:!!sheetInside}:null,"
                + "catalogTitle,catalogHeader:rect('.mobile-hotkeys__sheet-header'),"
                + "catalogHeaderControlsDoNotOverlap:!!catalogTitleBounds&&!!pageActionNode&&(()=>{const a=pageActionNode.getBoundingClientRect();"
                + "return !overlaps(catalogTitleBounds,a);})(),"
                + "catalogScrollerSelector:catalogScroller?.matches('.mobile-hotkeys__ctrl-grid')?'.mobile-hotkeys__ctrl-grid':"
                + "catalogScroller?.matches('.mobile-hotkeys__main-keys')?'.mobile-hotkeys__main-keys':null,catalogScrollMetrics,"
                + "mainCatalog:rect('.mobile-hotkeys__main-keys'),ctrlCatalog:rect('.mobile-hotkeys__ctrl-grid'),"
                + "composerPanel:composerRect,composerDraft:composerDraftRect,composerActionRow,composerActions,"
                + "inlineDictationBar,keybarRect,keybarClientRect,"
                + "inlineDictationStatusRow,inlineDictationStatusVisible:!!inlineDictationStatusNode,"
                + "dictationSheetHeader,inlineDictationStatusInsideSheetHeader:inlineDictationStatusRow&&dictationSheetHeader?inlineDictationStatusRow.top>=dictationSheetHeader.top-0.5"
                + "&&inlineDictationStatusRow.left>=dictationSheetHeader.left-0.5&&inlineDictationStatusRow.bottom<=dictationSheetHeader.bottom+0.5"
                + "&&inlineDictationStatusRow.right<=dictationSheetHeader.right+0.5:false,"
                + "inlineDictationMic,"
                + "inlineDictationBarCount:document.querySelectorAll('[data-testid=inline-dictation-bar]').length,"
                + "inlineDictationMicCount:document.querySelectorAll('[data-testid=inline-dictation-toggle]').length,"
                + "inlineDictationTargetKey:inlineDictationBarNode?.dataset.targetKey??'',"
                + "inlineDictationPhase:inlineDictationBarNode?.dataset.phase??'',"
                + "inlineDictationTone:inlineDictationBarNode?.dataset.dictationTone??'',"
                + "inlineDictationStatusText:inlineDictationStatusNode?.textContent.trim()??'',"
                + "inlineDictationPreview:inlineDictationStatusNode?.querySelector('[data-testid=inline-dictation-preview]')?.textContent.trim()??'',"
                + "inlineDictationStatusOneLine,"
                + "inlineDictationStatusInsideBar:inlineDictationStatusRow&&inlineDictationBar?inlineDictationStatusRow.top>=inlineDictationBar.top-0.5"
                + "&&inlineDictationStatusRow.left>=inlineDictationBar.left-0.5&&inlineDictationStatusRow.bottom<=inlineDictationBar.bottom+0.5"
                + "&&inlineDictationStatusRow.right<=inlineDictationBar.right+0.5:false,"
                + "inlineDictationStatusAboveKeybar:inlineDictationStatusRow&&keybarRect?inlineDictationStatusRow.bottom<=keybarRect.top+0.5:false,"
                + "inlineDictationMicInsideBar:inlineDictationBarNode&&inlineDictationMicNode?(()=>{const b=inlineDictationBarNode.getBoundingClientRect(),m=inlineDictationMicNode.getBoundingClientRect();"
                + "return m.top>=b.top-0.5&&m.left>=b.left-0.5&&m.bottom<=b.bottom+0.5&&m.right<=b.right+0.5;})():false,"
                + "inlineDictationMicInsideKeybar:inlineDictationMicNode&&keybarNode?(()=>{const b=keybarNode.getBoundingClientRect(),m=inlineDictationMicNode.getBoundingClientRect();"
                + "return m.top>=b.top-0.5&&m.left>=b.left-0.5&&m.bottom<=b.bottom+0.5&&m.right<=b.right+0.5;})():false,"
                + "inlineDictationBarInsideTray:inlineDictationBar&&trayRect?inlineDictationBar.top>=trayRect.top-0.5"
                + "&&inlineDictationBar.left>=trayRect.left-0.5&&inlineDictationBar.bottom<=trayRect.bottom+0.5"
                + "&&inlineDictationBar.right<=trayRect.right+0.5:null,"
                + "layout:Object.fromEntries(['.app-shell','.screen-content','.home-screen--workspace','.live-workspace','.terminal-panel','.panel-heading--terminal',"
                + "'[data-testid=terminal-slot]','.terminal-viewport','.mobile-hotkeys','.composer-panel'].map(selector=>{const node=document.querySelector(selector);"
                + "if(!node)return [selector,null];const style=getComputedStyle(node),r=node.getBoundingClientRect();return [selector,{display:style.display,"
                + "height:r.height,minHeight:style.minHeight,flex:style.flex,padding:style.padding,overflow:style.overflow}];})),"
                + "fastKeysTray:tray&&trayRect&&slotRect&&terminalRect&&composerRect?{bounds:trayRect,insideSlot:trayRect.top>=slotRect.top-0.5"
                + "&&trayRect.left>=slotRect.left-0.5&&trayRect.bottom<=slotRect.bottom+0.5&&trayRect.right<=slotRect.right+0.5,"
                + "insideTerminalPanel:trayRect.top>=rect('.terminal-panel').top"
                + "&&trayRect.bottom<=rect('.terminal-panel').bottom,"
                + "belowTerminalViewport:trayRect.top-terminalRect.bottom>=-" + TERMINAL_VIEWPORT_ROUNDING_EPSILON_CSS_PX
                + ",intersectsTerminalViewport:trayRect.top-terminalRect.bottom<-" + TERMINAL_VIEWPORT_ROUNDING_EPSILON_CSS_PX
                + "&&trayRect.bottom>terminalRect.top,intersectsComposerPanel:trayRect.left<composerRect.right"
                + "&&trayRect.right>composerRect.left&&trayRect.top<composerRect.bottom&&trayRect.bottom>composerRect.top}:null,"
                + "resizeAcks:Number(shell?.dataset.sshTerminalResizeAcks??0),resizePending:Number(shell?.dataset.sshTerminalResizePending??0),"
                + "resizeFailures:Number(shell?.dataset.sshTerminalResizeFailures??0),hotkeyWrites:window.__ps2884HotkeyWrites??[],"
                + "terminalInputAcks:Number(shell?.dataset.sshTerminalInputAcks??0),"
                + "resizeTraceMarker:window.__ps2884ResizeFitMarker??'',resizeFitEvents:fitEventsSince,resizeAckEvents:ackEventsSince,"
                + "visualViewport:{height:window.visualViewport?.height??innerHeight,width:window.visualViewport?.width??innerWidth,"
                + "offsetTop:window.visualViewport?.offsetTop??0},"
                + "imeEdgeCssY:(window.visualViewport?.offsetTop??0)+(window.visualViewport?.height??innerHeight),innerWidth,innerHeight,"
                + "screenScroll:document.querySelector('.screen-content')?.scrollTop??null,"
                + "documentScroll:document.scrollingElement?.scrollTop??null});})()");
        dom.put("androidIme", readNativeImeState());
        geometryTrace.put(new JSONObject(dom.toString()));
        Log.i("PS2884Geometry", "RUN " + artifactRunId + " " + stage + " " + dom);
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
        JSONObject rowMetrics = geometry.optJSONObject("persistentRowMetrics");
        JSONObject keybar = geometry.optJSONObject("keybarClientRect");
        assertNotNull("persistent toolbar must expose its responsive width/overflow metrics", rowMetrics);
        assertNotNull("persistent toolbar must expose its vertical hit-target bounds", keybar);
        assertEquals("toolbar scrollability must agree with measured width", rowMetrics.getDouble("scrollWidth")
                        > rowMetrics.getDouble("clientWidth") + 1,
                rowMetrics.getBoolean("scrollable"));
        if (geometry.getJSONObject("visualViewport").getDouble("width") >= 400) {
            assertTrue("the 412px review layout keeps all persistent controls inline without scrolling: " + rowMetrics,
                    rowMetrics.getDouble("scrollWidth") <= rowMetrics.getDouble("clientWidth") + 1);
        }
        int expectedTargetCount = 4;
        assertEquals("compact hotkey row must expose navigation and the More keys launcher",
                expectedTargetCount, targets.length());
        JSONObject divider = geometry.getJSONObject("enterDivider");
        JSONObject downTarget = targets.getJSONObject(1);
        JSONObject enterTarget = targets.getJSONObject(2);
        assertTrue("Kotlin's hairline divider separates Down from Enter without taking a touch target: " + geometry,
                Math.abs(divider.getDouble("width") - 1) <= 0.5
                        && Math.abs(divider.getDouble("height") - 24) <= 0.5
                        && divider.getDouble("left") >= downTarget.getDouble("right") - 0.5
                        && divider.getDouble("right") <= enterTarget.getDouble("left") + 0.5);
        for (int index = 0; index < targets.length(); index += 1) {
            JSONObject target = targets.getJSONObject(index);
            assertTrue("persistent key target must fit inside the clipped toolbar row: " + target + "; row=" + keybar,
                    target.getDouble("left") >= keybar.getDouble("left") - 0.5
                            && target.getDouble("right") <= keybar.getDouble("right") + 0.5
                            && target.getDouble("top") >= keybar.getDouble("top") - 0.5
                            && target.getDouble("bottom") <= keybar.getDouble("bottom") + 0.5
                            && target.getDouble("visibleWidthInKeybar") >= 47.9
                            && target.getDouble("visibleHeightInKeybar") >= 47.9);
        }
        List<String> labels = new ArrayList<>();
        for (int index = 0; index < targets.length(); index += 1) {
            JSONObject target = targets.getJSONObject(index);
            labels.add(target.getString("label"));
            assertTrue("hotkey accessible target must have a 48dp minimum height: " + target, target.getDouble("height") >= 47.9);
            assertTrue("hotkey accessible target must have a 48dp minimum width: " + target, target.getDouble("width") >= 47.9);
            assertTrue("hotkey target must remain above the IME and inside the viewport: " + target, target.getBoolean("insideViewport"));
            assertTrue("live hotkey target must be enabled: " + target, !target.getBoolean("disabled"));
        }
        String page = geometry.getString("fastKeysPage");
        List<String> expected = new ArrayList<>(List.of("Send Up arrow", "Send Down arrow", "Send Enter"));
        expected.add("closed".equals(page) ? "More terminal keys" : "Close terminal keys");
        assertEquals("persistent row keeps navigation, Fast Keys page, launcher, and trailing mic reachable",
                expected, labels);
        assertTrue("keyboard geometry must confirm native IME visibility", geometry.getJSONObject("androidIme").getBoolean("visible"));
        assertTrue("keyboard geometry must include a positive native IME inset", geometry.getJSONObject("androidIme").getDouble("imeBottomDp") > 0);
    }

    private JSONObject verifyNarrowToolbarReachability() throws Exception {
        int writesBefore = hotkeyWrites().length();
        JSONObject result = new JSONObject(evalString("(() => {"
                + "const tray=document.querySelector('.mobile-hotkeys'),bar=document.querySelector('.mobile-hotkeys__bar');"
                + "if(!tray||!bar)return JSON.stringify({missing:true});"
                + "const savedStyle=tray.getAttribute('style'),savedScrollLeft=bar.scrollLeft;"
                + "tray.style.width='330px';tray.style.maxWidth='330px';tray.style.minWidth='0';"
                + "bar.scrollLeft=0;const clientWidth=bar.clientWidth,scrollWidth=bar.scrollWidth;"
                + "const targets=Array.from(bar.querySelectorAll('button')).map(node=>{node.scrollIntoView({block:'nearest',inline:'nearest'});"
                + "const r=node.getBoundingClientRect(),b=bar.getBoundingClientRect(),clip={left:b.left+bar.clientLeft,top:b.top+bar.clientTop,"
                + "right:b.left+bar.clientLeft+bar.clientWidth,bottom:b.top+bar.clientTop+bar.clientHeight};"
                + "const visibleWidth=Math.max(0,Math.min(r.right,clip.right)-Math.max(r.left,clip.left));"
                + "const visibleHeight=Math.max(0,Math.min(r.bottom,clip.bottom)-Math.max(r.top,clip.top));"
                + "const hit=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2);return {label:node.getAttribute('aria-label')||node.textContent.trim(),"
                + "left:r.left,right:r.right,width:r.width,height:r.height,insideToolbar:r.left>=b.left-0.5&&r.right<=b.right+0.5,"
                + "visibleWidth,visibleHeight,hitTarget:!!hit&&(hit===node||node.contains(hit)),"
                + "disabled:!!node.disabled};});"
                + "const maxScrollLeft=bar.scrollWidth-bar.clientWidth;"
                + "if(savedStyle===null)tray.removeAttribute('style');else tray.setAttribute('style',savedStyle);bar.scrollLeft=savedScrollLeft;"
                + "return JSON.stringify({clientWidth,clientHeight:bar.clientHeight,scrollWidth,maxScrollLeft,scrollable:scrollWidth>clientWidth+1,targets});"
                + "})()"));
        assertTrue("narrow-width probe must find the persistent toolbar: " + result, !result.optBoolean("missing"));
        assertTrue("a 330px toolbar must keep every persistent key inline without overflow: " + result,
                !result.getBoolean("scrollable") && result.getDouble("scrollWidth") <= result.getDouble("clientWidth") + 1);
        JSONArray targets = result.getJSONArray("targets");
        assertEquals("narrow-width toolbar keeps navigation, launcher, and mic reachable", 5, targets.length());
        for (int index = 0; index < targets.length(); index += 1) {
            JSONObject target = targets.getJSONObject(index);
            assertTrue("narrow toolbar target remains >=48dp and scroll-reachable: " + target,
                    target.getDouble("width") >= 47.9 && target.getDouble("height") >= 47.9
                            && target.getDouble("visibleWidth") >= 47.9 && target.getDouble("visibleHeight") >= 47.9
                            && target.getBoolean("hitTarget") && target.getBoolean("insideToolbar")
                            && !target.getBoolean("disabled"));
        }
        int writesAfter = hotkeyWrites().length();
        assertEquals("testing the narrow toolbar must not write bytes to the PTY", writesBefore, writesAfter);
        result.put("ptyWritesBefore", writesBefore);
        result.put("ptyWritesAfter", writesAfter);
        return result;
    }

    private void assertCatalogSheetGeometry(JSONObject geometry, String page) throws Exception {
        JSONObject sheet = geometry.optJSONObject("catalogSheet");
        assertNotNull("the full key catalog must render in its on-demand sheet", sheet);
        assertEquals("the on-demand Terminal keys surface must fit one header plus two 48dp key rows: " + geometry,
                144, sheet.getDouble("height"), 0.5);
        JSONObject dock = geometry.getJSONObject("mobileHotkeys");
        assertEquals("the open terminal dock reserves the key row, optional status, and bounded catalog: " + geometry,
                geometry.getBoolean("inlineDictationStatusVisible") ? 209 : 193,
                dock.getDouble("height"), 0.5);
        JSONObject terminalPanel = geometry.getJSONObject("terminalPanel");
        JSONObject terminalSlot = geometry.getJSONObject("terminalSlot");
        assertTrue("catalog state must keep its terminal slot within the clipped panel: " + geometry,
                terminalSlot.getDouble("top") >= terminalPanel.getDouble("top") - 0.5
                        && terminalSlot.getDouble("bottom") <= terminalPanel.getDouble("bottom") + 0.5);
        assertTrue("the catalog sheet must remain within the terminal slot: " + geometry,
                sheet.getDouble("top") >= geometry.getJSONObject("terminalSlot").getDouble("top") - 0.5
                        && sheet.getDouble("bottom") <= geometry.getJSONObject("terminalSlot").getDouble("bottom") + 0.5);
        assertTrue("the catalog sheet must not overlap xterm or composer: " + geometry,
                geometry.getBoolean("catalogSheetBelowTerminalViewport")
                        && !geometry.getBoolean("catalogSheetIntersectsComposer"));
        JSONObject terminalHeading = geometry.getJSONObject("layout").getJSONObject(".panel-heading--terminal");
        assertEquals("the titled key sheet replaces redundant terminal panel chrome while open", "none",
                terminalHeading.getString("display"));
        assertTrue("catalog sheet must use a nonmodal terminal-context surface: " + geometry,
                "false".equals(geometry.getString("catalogSheetModal")));
        JSONObject title = geometry.getJSONObject("catalogTitle");
        assertEquals("catalog title must match the selected key page", "main".equals(page) ? "Terminal keys" : "Ctrl keys",
                title.getString("text"));
        assertEquals("catalog page title and controls use one 48dp header", 48,
                geometry.getJSONObject("catalogHeader").getDouble("height"), 0.5);
        assertTrue("catalog title must remain fully visible at the device width: " + geometry, title.getBoolean("fits"));
        assertTrue("catalog title, optional one-line dictation status, and 48dp page action must not overlap: " + geometry,
                geometry.getBoolean("catalogHeaderControlsDoNotOverlap"));
        JSONObject scroll = geometry.getJSONObject("catalogScrollMetrics");
        JSONObject scroller = geometry.getJSONObject("catalogScrollerBounds");
        assertTrue("catalog scroller must remain fully inside the 144dp sheet after border sizing: " + geometry,
                geometry.getBoolean("catalogScrollerInsideSheet")
                        && scroller.getDouble("top") >= sheet.getDouble("top") - TERMINAL_VIEWPORT_ROUNDING_EPSILON_CSS_PX
                        && scroller.getDouble("bottom") <= sheet.getDouble("bottom") + TERMINAL_VIEWPORT_ROUNDING_EPSILON_CSS_PX
                        && scroller.getDouble("left") >= sheet.getDouble("left") - TERMINAL_VIEWPORT_ROUNDING_EPSILON_CSS_PX
                        && scroller.getDouble("right") <= sheet.getDouble("right") + TERMINAL_VIEWPORT_ROUNDING_EPSILON_CSS_PX
                        && Math.abs(scroller.getDouble("height") - 96) <= 1);
        assertTrue("the " + page + " catalog must fit its 48dp targets without horizontal scrolling: " + geometry,
                scroll.getDouble("scrollWidth") <= scroll.getDouble("clientWidth") + 1);
        if ("ctrl".equals(page)) {
            assertEquals("the Ctrl catalog must scroll vertically", "vertical", scroll.getString("axis"));
            assertTrue("the QWERTY Ctrl catalog must use bounded vertical scrolling: " + geometry,
                    scroll.getDouble("scrollHeight") > scroll.getDouble("clientHeight") + 1);
        } else {
            assertEquals("the main catalog must fit as a compact grid", "grid", scroll.getString("axis"));
            assertEquals("the ten common keys must fit in exactly two visible 48dp grid rows: " + geometry,
                    96, scroll.getDouble("clientHeight"), 1.0);
            assertTrue("the ten common keys must not force an additional catalog row: " + geometry,
                    scroll.getDouble("scrollHeight") <= scroll.getDouble("clientHeight") + 1);
        }
    }

    private void assertComposerActionsReachable(JSONObject geometry, boolean requireAllEnabled) throws Exception {
        assertComposerActionsReachable(geometry, requireAllEnabled, true);
    }

    private void assertComposerActionsReachable(JSONObject geometry, boolean requireAllEnabled,
                                                boolean requireCatalogOpen) throws Exception {
        JSONArray actions = geometry.optJSONArray("composerActions");
        assertNotNull("the keyboard-up composer must expose its action buttons: " + geometry, actions);
        assertEquals("composer retains Discard, Dictate, Insert, and Send actions", 4, actions.length());
        JSONObject panel = geometry.getJSONObject("composerPanel");
        JSONObject row = geometry.getJSONObject("composerActionRow");
        JSONObject viewport = geometry.getJSONObject("visualViewport");
        double imeEdgeCssY = geometry.optDouble("imeEdgeCssY",
                viewport.getDouble("height") + viewport.optDouble("offsetTop", 0));
        String composerGeometry = "panel=" + panel + "; draft=" + geometry.optJSONObject("composerDraft")
                + "; actionRow=" + row + "; actionTargets=" + actions + "; visualViewport=" + viewport
                + "; imeEdgeCssY=" + imeEdgeCssY + "; nativeIme=" + geometry.optJSONObject("androidIme");
        assertTrue("composer panel remains above the visible IME edge: " + composerGeometry,
                panel.getDouble("bottom") <= imeEdgeCssY + 0.5);
        assertTrue("composer actions keep a 4px gap above the visible IME edge: " + composerGeometry,
                row.getDouble("bottom") <= imeEdgeCssY - 4);
        int enabledTargets = 0;
        for (int index = 0; index < actions.length(); index += 1) {
            JSONObject action = actions.getJSONObject(index);
            assertTrue("composer action keeps a 48dp target: " + composerGeometry + "; action=" + action,
                    action.getDouble("width") >= 47.9 && action.getDouble("height") >= 47.9);
            assertTrue("composer action is fully inside the visible viewport, action row, and composer: "
                            + composerGeometry + "; action=" + action,
                    action.getBoolean("insideViewport") && action.getBoolean("insideComposerPanel")
                            && action.getDouble("top") >= row.getDouble("top") - 0.5
                            && action.getDouble("bottom") <= row.getDouble("bottom") + 0.5);
            if (!action.getBoolean("disabled")) {
                enabledTargets += 1;
                assertTrue("enabled composer action center resolves to the button hit target: "
                                + composerGeometry + "; action=" + action,
                        action.getBoolean("hitTarget"));
            }
            if (requireAllEnabled) {
                assertTrue("the staged draft makes every composer action tappable: " + composerGeometry + "; action=" + action,
                        !action.getBoolean("disabled") && action.getBoolean("hitTarget"));
            }
        }
        assertTrue("at least the composer dictation action stays enabled and tappable", enabledTargets > 0);
        assertTrue("geometry was captured with the Android IME open", geometry.getJSONObject("androidIme").getBoolean("visible"));
        if (requireCatalogOpen) {
            assertTrue("the keyboard-up composer action proof keeps the fast-key catalog open",
                    !"closed".equals(geometry.getString("fastKeysPage")));
        }
    }

    private void assertCompactStatusComposerLayout(String action, JSONObject geometry) throws Exception {
        JSONObject panel = geometry.getJSONObject("composerPanel");
        JSONObject draft = geometry.getJSONObject("composerDraft");
        JSONObject row = geometry.getJSONObject("composerActionRow");
        JSONArray actions = geometry.getJSONArray("composerActions");
        JSONObject viewport = geometry.getJSONObject("visualViewport");
        double imeEdgeCssY = geometry.optDouble("imeEdgeCssY",
                viewport.getDouble("height") + viewport.optDouble("offsetTop", 0));
        String composerGeometry = "panel=" + panel + "; draft=" + draft + "; actionRow=" + row
                + "; actionTargets=" + actions + "; visualViewport=" + viewport
                + "; imeEdgeCssY=" + imeEdgeCssY + "; nativeIme=" + geometry.optJSONObject("androidIme");
        assertTrue(action + " must keep its status row visible above the key controls",
                geometry.getBoolean("inlineDictationStatusVisible"));
        assertTrue(action + " must show its 80px composer panel: " + composerGeometry,
                Math.abs(panel.getDouble("height") - 80) <= 0.5);
        assertTrue(action + " must keep a usable 25px single-line prompt editor: " + composerGeometry,
                Math.abs(draft.getDouble("height") - 25) <= 0.5);
        assertTrue(action + " must retain its full 48px action row: " + composerGeometry,
                Math.abs(row.getDouble("height") - 48) <= 0.5);
        assertTrue(action + " editor and actions must remain fully inside the composer without overlap: " + composerGeometry,
                draft.getDouble("top") >= panel.getDouble("top") - 0.5
                        && row.getDouble("top") - draft.getDouble("bottom") >= 2
                        && row.getDouble("bottom") <= panel.getDouble("bottom") + 0.5);
        assertTrue(action + " composer and action row must clear the measured IME edge: " + composerGeometry,
                panel.getDouble("bottom") <= imeEdgeCssY + 0.5
                        && row.getDouble("bottom") <= imeEdgeCssY - 4);
        for (int index = 0; index < actions.length(); index += 1) {
            JSONObject target = actions.getJSONObject(index);
            assertTrue(action + " " + target.optString("action")
                            + " hit target must clear the measured IME edge by at least 4px: " + composerGeometry,
                    target.getDouble("bottom") <= imeEdgeCssY - 4);
        }
        assertComposerActionsReachable(geometry, false, false);
        JSONObject sheet = geometry.optJSONObject("catalogSheet");
        if (sheet != null) {
            assertTrue(action + " catalog must finish before the composer begins: sheet=" + sheet + "; panel=" + panel,
                    sheet.getDouble("bottom") <= panel.getDouble("top") + 0.5);
            assertTrue(action + " catalog must not overlap the composer",
                    !geometry.getBoolean("catalogSheetIntersectsComposer"));
        }
    }

    private void assertCatalogPageActionReachable(JSONObject geometry) throws Exception {
        JSONObject action = geometry.optJSONObject("catalogPageAction");
        assertNotNull("catalog sheet must expose its page action", action);
        assertTrue("catalog page action must meet the 48dp touch target: " + action,
                action.getDouble("width") >= 47.9 && action.getDouble("height") >= 47.9);
        assertTrue("catalog page action must remain visible above the IME: " + action,
                action.getBoolean("insideViewport") && action.getBoolean("insideCatalogSheet"));
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

    private void assertTrayBelowTerminalViewport(JSONObject geometry) throws Exception {
        JSONObject tray = geometry.getJSONObject("fastKeysTray");
        assertTrue("fast-key lane must stay inside the terminal slot: " + geometry, tray.getBoolean("insideSlot"));
        assertTrue("fast-key lane must stay inside the clipped terminal panel: " + geometry,
                tray.getBoolean("insideTerminalPanel"));
        JSONObject trayBounds = tray.getJSONObject("bounds");
        JSONObject terminalViewport = geometry.getJSONObject("terminalViewport");
        double trayViewportGap = trayBounds.getDouble("top") - terminalViewport.getDouble("bottom");
        assertTrue("fast-key lane must be below the xterm viewport within the documented 0.01px CSS rounding epsilon: "
                        + geometry,
                tray.getBoolean("belowTerminalViewport")
                        && trayViewportGap >= -TERMINAL_VIEWPORT_ROUNDING_EPSILON_CSS_PX);
        assertTrue("fast-key controls must not intersect terminal text: " + geometry, !tray.getBoolean("intersectsTerminalViewport"));
        assertTrue("fast-key lane must not overlap the composer panel: " + geometry, !tray.getBoolean("intersectsComposerPanel"));
        if (!geometry.isNull("inlineDictationBar")) {
            assertTrue("inline dictation must live inside the persistent dock, not as an overlapping extra row: " + geometry,
                    geometry.getBoolean("inlineDictationBarInsideTray"));
        }
        assertTrue("docked fast-key lane must remain in the visible WebView viewport", trayBounds.getDouble("top") >= 0
                && trayBounds.getDouble("bottom") <= geometry.getJSONObject("visualViewport").getDouble("height") + 0.5);
    }

    private void assertUnchangedTerminalGrid(String action, JSONObject before, JSONObject after) throws Exception {
        assertEquals(action + " must keep xterm columns; before=" + before + "; after=" + after,
                before.getInt("cols"), after.getInt("cols"));
        assertEquals(action + " must keep xterm rows; before=" + before + "; after=" + after,
                before.getInt("rows"), after.getInt("rows"));
    }

    private void assertTerminalViewportCap(String action, JSONObject baselineGeometry, JSONObject afterGeometry)
            throws Exception {
        JSONObject baselineViewport = baselineGeometry.getJSONObject("terminalViewport");
        JSONObject afterViewport = afterGeometry.getJSONObject("terminalViewport");
        int cap = Math.min(ACCEPTED_ANDROID_TERMINAL_VIEWPORT_CAP_DP,
                (int) Math.floor(baselineViewport.getDouble("height")));
        assertEquals(action + " must retain the accepted capped keyboard-up viewport; before=" + baselineGeometry
                        + "; after=" + afterGeometry,
                cap,
                afterGeometry.getInt("terminalViewportDockCapPx"));
        assertEquals(action + " must keep the xterm viewport at that height; before=" + baselineGeometry
                        + "; after=" + afterGeometry,
                cap, afterViewport.getDouble("height"), 0.5);
        double dockHeight = afterGeometry.getDouble("terminalHotkeysDockHeightPx");
        double renderedDockHeight = afterGeometry.getJSONObject("fastKeysTray").getJSONObject("bounds").getDouble("height");
        assertEquals(action + " dock height state must match the rendered dock; after=" + afterGeometry,
                dockHeight, renderedDockHeight, 0.5);
        assertTrue(action + " must reserve the capped viewport, rendered dock, and 1px flow gap; after=" + afterGeometry,
                afterGeometry.getJSONObject("terminalSlot").getDouble("height") + 0.5 >= cap + renderedDockHeight + 1);
    }

    private JSONObject assertAcceptedKeyboardUpViewport(String action, JSONObject geometry) throws Exception {
        JSONObject viewport = geometry.getJSONObject("terminalViewport");
        int expectedCap = Math.min(ACCEPTED_ANDROID_TERMINAL_VIEWPORT_CAP_DP,
                (int) Math.floor(viewport.getDouble("height")));
        assertEquals(action + " must apply the min(144px, measured viewport) cap; geometry=" + geometry,
                expectedCap, geometry.getInt("terminalViewportDockCapPx"));
        assertEquals(action + " must render xterm at the accepted viewport cap; geometry=" + geometry,
                expectedCap, viewport.getDouble("height"), 0.5);
        JSONObject grid = runtimeGrid(geometry);
        if (Build.VERSION.SDK_INT == 35 && expectedCap == ACCEPTED_ANDROID_TERMINAL_VIEWPORT_CAP_DP) {
            assertEquals(action + " must retain the accepted API 35 xterm column count; geometry=" + geometry,
                    38, grid.getInt("cols"));
            assertEquals(action + " must retain the accepted API 35 38×6 PTY grid; geometry=" + geometry,
                    6, grid.getInt("rows"));
        }
        assertAtLeastFiveRows(action, geometry);
        return grid;
    }

    private void assertDictationStableStage(String action, JSONObject baselineGeometry, JSONObject afterGeometry,
                                           JSONObject baselineGrid, int baselineResizeAcks) throws Exception {
        JSONObject afterGrid = runtimeGrid(afterGeometry);
        assertAtLeastFiveRows(action, afterGeometry);
        assertUnchangedTerminalGrid(action, baselineGrid, afterGrid);
        assertEquals(action + " must not request a PTY resize; before=" + baselineGeometry
                        + "; after=" + afterGeometry,
                baselineResizeAcks, afterGeometry.getInt("resizeAcks"));
        assertEquals(action + " must finish with native PTY resize idle; after=" + afterGeometry,
                0, afterGeometry.getInt("resizePending"));
    }

    private void assertAtLeastFiveRows(String action, JSONObject geometry) throws Exception {
        int rows = runtimeGrid(geometry).getInt("rows");
        int visibleRows = geometry.getInt("visibleTerminalRows");
        assertTrue(action + " must keep at least five physical terminal rows visible; geometry=" + geometry, visibleRows >= 5);
        assertTrue(action + " must retain a PTY grid with at least five rows; geometry=" + geometry, rows >= 5);
    }

    private void awaitRenderedFrame() throws Exception {
        evalString("(() => {window.__ps2884RenderedFrame = false;"
                + "requestAnimationFrame(() => requestAnimationFrame(() => {window.__ps2884RenderedFrame = true;}));"
                + "return 'scheduled';})()");
        awaitJsTrue("window.__ps2884RenderedFrame === true");
        CountDownLatch visualStateReady = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            WebView webView = findWebView(activity.getWindow().getDecorView());
            assertNotNull("packaged activity must contain a WebView before screenshot capture", webView);
            webView.postVisualStateCallback(SystemClock.uptimeMillis(), new WebView.VisualStateCallback() {
                @Override
                public void onComplete(long requestId) {
                    visualStateReady.countDown();
                }
            });
        });
        assertTrue("WebView visual state must be ready before screenshot capture",
                visualStateReady.await(JS_TIMEOUT_SECONDS, TimeUnit.SECONDS));
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

    private void captureTerminalViewportScreenshot(String name, JSONObject geometry) throws Exception {
        JSONObject viewport = geometry.getJSONObject("terminalViewport");
        float[] topLeft = screenPoint((float) viewport.getDouble("left"), (float) viewport.getDouble("top"));
        float[] bottomRight = screenPoint((float) viewport.getDouble("right"), (float) viewport.getDouble("bottom"));
        AtomicReference<byte[]> pngBytes = new AtomicReference<>();
        AtomicReference<String> cropError = new AtomicReference<>();
        scenario.onActivity(activity -> {
            Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            if (screenshot == null) {
                cropError.set("device screenshot was unavailable");
                return;
            }
            int left = Math.round(topLeft[0]);
            int top = Math.round(topLeft[1]);
            int right = Math.round(bottomRight[0]);
            int bottom = Math.round(bottomRight[1]);
            if (left < 0 || top < 0 || right > screenshot.getWidth() || bottom > screenshot.getHeight()
                    || right <= left || bottom <= top) {
                cropError.set("terminal viewport bounds escaped device screenshot: crop=" + left + "," + top + ","
                        + right + "," + bottom + " screenshot=" + screenshot.getWidth() + "x" + screenshot.getHeight());
                screenshot.recycle();
                return;
            }
            Bitmap crop = Bitmap.createBitmap(screenshot, left, top, right - left, bottom - top);
            screenshot.recycle();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            boolean compressed = crop.compress(Bitmap.CompressFormat.PNG, 100, output);
            crop.recycle();
            if (compressed && output.size() >= 1_024) pngBytes.set(output.toByteArray());
        });
        if (cropError.get() != null) throw new AssertionError(cropError.get());
        assertNotNull("terminal viewport crop must be captured for " + name, pngBytes.get());
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
                + "paletteOpen:document.querySelector('[data-testid=mobile-hotkeys]')?.dataset.paletteOpen??null,"
                + "focusEvents:(window.__ps2884FocusEvents??[]).slice(-20)})");
        throw new AssertionError("Android IME visibility did not become " + visible + " (WebView=" + webState + ")");
    }

    private void installAttachAutofocusGate() throws Exception {
        evalString("(() => {const gate=window.__ps2884AttachAutofocusGate={entered:[],pending:0,released:false,waiters:[]};"
                + "window.__ps2884BeforeAttachAutofocus=source=>{gate.entered.push({source,atMs:Math.round(performance.now())});"
                + "gate.pending+=1;return new Promise(resolve=>{const finish=()=>{gate.pending-=1;resolve();};"
                + "if(gate.released){queueMicrotask(finish);return;}gate.waiters.push(finish);});};return 'gate installed';})()");
    }

    private boolean hasAutofocusSource(JSONObject gate, String source) throws Exception {
        JSONArray entered = gate.optJSONArray("entered");
        if (entered == null) return false;
        for (int index = 0; index < entered.length(); index++) {
            JSONObject event = entered.optJSONObject(index);
            if (event != null && source.equals(event.optString("source"))) return true;
        }
        return false;
    }

    private void releaseAttachAutofocusGate() throws Exception {
        evalString("(() => {const gate=window.__ps2884AttachAutofocusGate;if(!gate)throw new Error('missing attach gate');"
                + "gate.released=true;for(const finish of gate.waiters.splice(0))finish();return 'gate released';})()");
    }

    private void awaitPromptFocusedForReattach() throws Exception {
        try {
            awaitJsTrue("document.activeElement?.matches('[data-testid=prompt-draft]') === true"
                    + " && document.querySelector('.app-shell')?.dataset.keyboardComposerMode === 'true'"
                    + " && document.querySelector('.app-shell')?.dataset.keyboardVisible === 'true'", 5_000);
        } catch (AssertionError error) {
            String state = evalString("JSON.stringify({activeElement:document.activeElement?.outerHTML?.slice(0,180)??null,"
                    + "keyboardVisible:document.querySelector('.app-shell')?.dataset.keyboardVisible??null,"
                    + "keyboardComposerMode:document.querySelector('.app-shell')?.dataset.keyboardComposerMode??null,"
                    + "attachFocusPending:document.querySelector('.app-shell')?.dataset.sshAttachFocusPending??null,"
                    + "attachEpoch:document.querySelector('.app-shell')?.dataset.sshAttachEpoch??null,"
                    + "attachResizeAckEpoch:document.querySelector('.app-shell')?.dataset.sshAttachResizeAckEpoch??null,"
                    + "gate:window.__ps2884AttachAutofocusGate??null,"
                    + "focusEvents:(window.__ps2884FocusEvents??[]).slice(-16)})");
            throw new AssertionError("reattach did not preserve prompt focus with the keyboard visible: " + state, error);
        }
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
