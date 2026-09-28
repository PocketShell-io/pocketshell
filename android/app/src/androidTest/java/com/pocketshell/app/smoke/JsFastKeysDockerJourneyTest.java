package com.pocketshell.app.smoke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Insets;
import android.os.Build;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
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
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Real packaged fast-key controls with API 35 IME geometry and a Docker PTY byte oracle. */
@RunWith(AndroidJUnit4.class)
public final class JsFastKeysDockerJourneyTest {
    private static final String ASSET_TAG = "PS2884Asset";
    private static final long WAIT_TIMEOUT_MILLIS = 45_000;
    private static final long JS_TIMEOUT_SECONDS = 15;
    private static final long UI_CALLBACK_TIMEOUT_MILLIS = 8_000;
    private static final int ASSET_CHUNK_SIZE = 2_800;
    private static final int MAX_CATALOG_SWIPE_ATTEMPTS = 8;
    private static final int ACCEPTED_ANDROID_TERMINAL_VIEWPORT_CAP_DP = 144;
    // WebView can round adjacent CSS rectangles apart by a tiny fraction at shared edges.
    private static final double TERMINAL_VIEWPORT_ROUNDING_EPSILON_CSS_PX = 0.01;

    private ActivityScenario<MainActivity> scenario;
    private MainActivity packagedActivity;
    private WebView packagedWebView;
    private String artifactRunId;
    private String firstSession;
    private JSONObject acceptedKeyboardGrid;
    private JSONObject journey = new JSONObject();
    private JSONArray geometryTrace = new JSONArray();

    @Before
    public void launchPackagedShell() {
        scenario = ActivityScenario.launch(MainActivity.class);
        scenario.onActivity(activity -> {
            packagedActivity = activity;
            packagedWebView = findWebView(activity.getWindow().getDecorView());
        });
        assertNotNull("packaged activity must start with its WebView", packagedWebView);
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
        grantMicrophonePermissionForJourney();
        installNativeSpeechBridgeObserver();
        evalString("window.__ps2857CaptureTerminalEvidence = true; window.__ps2884HotkeyWrites = [];"
                + "window.__ps2884CaptureResizeFitEvidence = true; window.__ps2884ResizeFitEvents = [];"
                + "window.__ps2884ResizeAckEvents = []; window.__ps2884ResizeFitMarker = 'journey-start';"
                + "window.__ps2884PointerEvents = []; window.__ps2884FocusEvents = [];"
                + "for (const type of ['pointerdown','pointerup','pointercancel','click']) window.addEventListener(type, event => {"
                + "const button=event.target instanceof Element ? event.target.closest('button') : null;"
                + "window.__ps2884PointerEvents.push({type,pointerId:event.pointerId??null,pointerType:event.pointerType??null,"
                + "detail:event.detail??null,clientX:event.clientX??null,clientY:event.clientY??null,"
                + "targetTestId:event.target instanceof Element?event.target.getAttribute('data-testid'):null,"
                + "buttonTestId:button?.dataset.testid??null,key:button?.dataset.keyId??button?.getAttribute('aria-label')??null,"
                + "buttonDisabled:button?.disabled??null,defaultPrevented:event.defaultPrevented,"
                + "activeElement:document.activeElement?.getAttribute('data-testid')??document.activeElement?.tagName??null});"
                + "});"
                + "const describeFocusNode=node=>{if(!(node instanceof Element))return null;return {tag:node.tagName.toLowerCase(),"
                + "id:node.id||null,testId:node.getAttribute('data-testid'),className:String(node.className||'').slice(0,80)};};"
                + "for(const type of ['focusin','focusout'])document.addEventListener(type,event=>{"
                + "const entries=window.__ps2884FocusEvents;const shell=document.querySelector('.app-shell');"
                + "const gate=window.__ps2884AttachAutofocusGate;entries.push({type,atMs:Math.round(performance.now()),"
                + "target:describeFocusNode(event.target),related:describeFocusNode(event.relatedTarget),"
                + "active:describeFocusNode(document.activeElement),keyboardVisible:shell?.dataset.keyboardVisible||'false',"
                + "attachEpoch:shell?.dataset.sshAttachEpoch??null,attachFocusPending:shell?.dataset.sshAttachFocusPending??null,"
                + "attachPromptFocusEpoch:shell?.dataset.sshAttachPromptFocusEpoch??null,"
                + "attachResizeAckEpoch:shell?.dataset.sshAttachResizeAckEpoch??null,"
                + "terminalAutofocusAllowed:shell?.dataset.sshTerminalAutofocusAllowed??null,"
                + "composerOpen:shell?.dataset.promptComposerOpen??null,"
                + "attachAutofocusGate:gate?{pending:gate.pending??0,released:gate.released??false,"
                + "entered:(gate.entered??[]).map(entry=>({source:entry.source,atMs:entry.atMs}))}:null});"
                + "if(entries.length>40)entries.shift();},true);"
                + "window.__ps2884JsDiagnostics={events:[]};const recordJsDiagnostic=(kind,value)=>{const events=window.__ps2884JsDiagnostics.events;"
                + "events.push({kind,atMs:Math.round(performance.now()),value:String(value).slice(0,500)});if(events.length>30)events.shift();};"
                + "for(const kind of ['error','warn']){const original=console[kind].bind(console);console[kind]=(...args)=>{"
                + "recordJsDiagnostic('console.'+kind,args.map(value=>value instanceof Error?value.stack||value.message:value).join(' '));original(...args);};}"
                + "window.addEventListener('error',event=>recordJsDiagnostic('window.error',event.message+' @ '+event.filename+':'+event.lineno));"
                + "window.addEventListener('unhandledrejection',event=>recordJsDiagnostic('unhandledrejection',event.reason?.stack||event.reason));"
                + "'evidence enabled'");
        // SystemClock.uptimeMillis() is Android's monotonic clock. This interval
        // ends after the attached live prompt is present and a rendered frame settles.
        long connectToPromptStartedAt = SystemClock.uptimeMillis();
        connect(host, port, privateKey);
        createSession(firstSession);
        createSession(dictationTargetSession);
        attachSession(firstSession);
        awaitTerminalResizeIdle();
        awaitJsTrue("!!document.querySelector('[data-testid=prompt-composer-launcher]')"
                + " && document.querySelector('.app-shell')?.dataset.sshPhase === 'live'");
        assertEquals("the dock must have one Prompt entry; prompt dictation starts inside the composer", "false",
                evalRaw("!!document.querySelector('[data-testid=prompt-dictation-launcher]')"));
        openPromptComposerSheet();
        String preservedDraft = "keep this draft across keys " + nameBase;
        setValue("[data-testid=prompt-draft]", preservedDraft);
        JSONObject promptComposerEntry = evalJson("(() => {const panel=document.querySelector('[data-testid=prompt-composer]');"
                + "const mic=document.querySelector('[data-testid=composer-dictate]');const rect=mic?.getBoundingClientRect();"
                + "const keys=document.querySelector('[data-testid=composer-open-keys]');const keysRect=keys?.getBoundingClientRect();"
                + "return JSON.stringify({role:panel?.getAttribute('role')??'',modal:panel?.getAttribute('aria-modal')??'',"
                + "micLabel:mic?.getAttribute('aria-label')??'',micVisible:!!mic&&mic.getClientRects().length>0,"
                + "micWidth:rect?.width??0,micHeight:rect?.height??0,keysLabel:keys?.getAttribute('aria-label')??'',"
                + "keysVisible:!!keys&&keys.getClientRects().length>0,keysWidth:keysRect?.width??0,keysHeight:keysRect?.height??0});})()");
        assertTrue("Compose must open a modal with a reachable prompt dictation action: " + promptComposerEntry,
                "dialog".equals(promptComposerEntry.optString("role"))
                        && "true".equals(promptComposerEntry.optString("modal"))
                        && "Dictate prompt draft".equals(promptComposerEntry.optString("micLabel"))
                        && promptComposerEntry.optBoolean("micVisible")
                        && promptComposerEntry.optDouble("micWidth") >= 47.9
                        && promptComposerEntry.optDouble("micHeight") >= 47.9
                        && "More terminal keys".equals(promptComposerEntry.optString("keysLabel"))
                        && promptComposerEntry.optBoolean("keysVisible")
                        && promptComposerEntry.optDouble("keysWidth") >= 47.9
                        && promptComposerEntry.optDouble("keysHeight") >= 47.9);
        journey.put("promptComposerEntry", promptComposerEntry);
        tapDomCenter("[data-testid=prompt-draft]");
        awaitImeVisible(true);
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.keyboardVisible === 'true'");
        JSONObject composerKeyboard = captureGeometry("composer-keys-before-ime-open");
        JSONObject composerKeyboardGrid = runtimeGrid(composerKeyboard);
        assertEquals("the typed prompt draft must be visible before opening keys", preservedDraft,
                evalString("document.querySelector('[data-testid=prompt-draft]')?.value ?? ''"));
        tapDomCenter("[data-testid=composer-open-keys]");
        awaitJsTrue("!document.querySelector('[data-testid=prompt-composer]')"
                + " && !!document.querySelector('[data-testid=mobile-hotkeys-sheet]')"
                + " && document.querySelector('[data-testid=mobile-hotkeys]')?.dataset.paletteOpen === 'true'"
                + " && document.querySelector('.app-shell')?.dataset.keyboardVisible === 'true'");
        awaitImeVisible(true);
        awaitTerminalResizeIdle();
        JSONObject composerKeys = captureGeometry("composer-to-keys-ime-open");
        assertEquals("opening More keys from the composer leaves only the catalog expanded", 1,
                composerKeys.getInt("expandedInputSurfaceCount"));
        assertTrue("More keys from Compose must hand off to the one terminal palette surface with the IME open: " + composerKeys,
                composerKeys.isNull("composerPanel")
                        && "main".equals(composerKeys.getString("fastKeysPage"))
                        && composerKeys.getJSONObject("androidIme").getBoolean("visible")
                        && composerKeys.getBoolean("keyboardVisible")
                        && composerKeys.getBoolean("activeElementInsideTerminal")
                        && !composerKeys.getBoolean("activeElementIsPromptDraft"));
        assertUnchangedTerminalGrid("composer-to-keys handoff with IME open", composerKeyboardGrid,
                runtimeGrid(composerKeys));
        assertHotkeyBarReachable(composerKeys);
        assertDockDestinationLabels(composerKeys);
        captureScreenshot("fastkeys-composer-keys-ime-open.png");
        tapDomCenter("[data-testid=prompt-composer-launcher]");
        awaitJsTrue("document.querySelector('[data-testid=prompt-composer]')?.getAttribute('role') === 'dialog'"
                + " && !document.querySelector('[data-testid=mobile-hotkeys-sheet]')"
                + " && document.querySelector('[data-testid=mobile-hotkeys]')?.dataset.paletteOpen === 'false'");
        String restoredDraft = evalString("document.querySelector('[data-testid=prompt-draft]')?.value ?? ''");
        assertEquals("Compose must reopen with the draft that was present before opening terminal keys",
                preservedDraft, restoredDraft);
        awaitImeVisible(true);
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.keyboardVisible === 'true'"
                + " && document.activeElement === document.querySelector('[data-testid=prompt-draft]')");
        awaitTerminalResizeIdle();
        awaitRenderedFrame();
        JSONObject composerReturn = captureGeometry("keys-to-composer-return");
        assertEquals("opening Prompt from the catalog leaves only the composer expanded", 1,
                composerReturn.getInt("expandedInputSurfaceCount"));
        assertTrue("the return action must restore the modal composer, draft focus, and Android IME, then close the palette: " + composerReturn,
                !composerReturn.isNull("composerPanel")
                        && "closed".equals(composerReturn.getString("fastKeysPage"))
                        && composerReturn.getBoolean("keyboardVisible")
                        && composerReturn.getJSONObject("androidIme").getBoolean("visible")
                        && composerReturn.getBoolean("activeElementIsPromptDraft")
                        && preservedDraft.equals(composerReturn.getString("composerDraftValue"))
                        && isImeVisible());
        captureScreenshot("fastkeys-composer-returned.png");
        journey.put("composerKeysTransition", new JSONObject()
                .put("draftBefore", preservedDraft)
                .put("draftAfterReturn", restoredDraft)
                .put("composerVisibleDuringKeys", false)
                .put("paletteOpenDuringKeys", true)
                .put("imeVisibleDuringKeys", composerKeys.getJSONObject("androidIme").getBoolean("visible"))
                .put("keyboardVisibleDuringKeys", composerKeys.getBoolean("keyboardVisible"))
                .put("imeVisibleAfterReturn", composerReturn.getJSONObject("androidIme").getBoolean("visible"))
                .put("keyboardVisibleAfterReturn", composerReturn.getBoolean("keyboardVisible"))
                .put("terminalGridBefore", composerKeyboardGrid)
                .put("terminalGridDuringKeys", runtimeGrid(composerKeys))
                .put("stableDockControls", composerKeys.getJSONArray("stableDockControls"))
                .put("inlineDictationMic", composerKeys.getJSONObject("inlineDictationMic")));
        setValue("[data-testid=prompt-draft]", "");
        closePromptComposerSheet();
        awaitRenderedFrame();
        journey.put("connectToPromptMs", SystemClock.uptimeMillis() - connectToPromptStartedAt);

        String firstRaw = "/tmp/" + firstSession + "-bytes.raw";
        String firstReady = "PS2884_READY_" + nameBase;
        String firstDone = "PS2884_DONE_" + nameBase;
        prepareByteCapture(firstRaw, 19, firstReady, firstDone);
        tapDomCenter(".terminal-viewport");
        awaitImeVisible(true);
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.keyboardVisible === 'true'"
                + " && document.querySelector('.app-shell')?.dataset.keyboardComposerMode === 'true'");
        awaitJsTrue("document.querySelector('[data-testid=mobile-hotkeys]')?.dataset.enabled === 'true'"
                + " && document.querySelector('[data-testid=mobile-hotkeys]')?.dataset.keyboardVisible === 'true'");
        awaitTerminalResizeIdle();
        awaitRenderedFrame();
        JSONObject keyboardGeometry = captureGeometry("keyboard-up-compact-row");
        assertTrue("keyboard-up screenshot must include visible Android IME", isImeVisible());
        // Save the real packaged frame before the xterm/host containment gate.
        // If the CSS surface is genuinely displaced, the same-run PNGs let us
        // distinguish that from a stale DOM rectangle measurement.
        captureScreenshot("fastkeys-ime-open.png");
        captureScreenshot("fastkeys-row-closed-ime-open.png");
        captureTerminalViewportScreenshot("fastkeys-row-closed-ime-open-viewport.png", keyboardGeometry);
        acceptedKeyboardGrid = assertAcceptedKeyboardUpViewport("initial API 35 keyboard-up baseline", keyboardGeometry);
        assertHotkeyBarReachable(keyboardGeometry);
        assertDictationMicReachable(keyboardGeometry);
        journey.put("narrowToolbarReachability", verifyNarrowToolbarReachability());

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
        captureScreenshot("fastkeys-sheet-main-ime-open.png");
        captureTerminalViewportScreenshot("fastkeys-sheet-main-ime-open-viewport.png", mainStartGeometry);

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
        JSONObject gridWithCtrlTray = runtimeGrid(ctrlTrayGeometry);
        assertUnchangedTerminalGrid("opening the Ctrl fast-key tray", gridBeforePalette, gridWithCtrlTray);
        assertEquals("opening the Ctrl fast-key tray must not resize the SSH PTY", resizeAcksBeforePalette, terminalResizeAcks());
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
        captureScreenshot("fastkeys-sheet-ctrl-ime-open.png");
        captureTerminalViewportScreenshot("fastkeys-sheet-ctrl-ime-open-viewport.png", ctrlStartGeometry);
        sendPaletteKey("ctrl-q");
        tapDomCenter("[data-testid=mobile-hotkeys-back-main-page]");
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
        assertEquals("open main catalog adds a compact rail below the persistent key row", 145,
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
        journey.put("firstSessionGeometryOracleFile", firstRaw + ".geometry");

        exerciseDockedDictation(nameBase, dictationTargetSession);
        exercisePromptDictationFromComposer();

        // The dictation journey switches sessions and backgrounds/resumes the
        // app. Re-establish the exact precondition for the layered Back check
        // instead of assuming either the IME or the palette survived that flow.
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.sshPhase === 'live'"
                + " && document.querySelector('.app-shell')?.dataset.homeSurface === 'live'"
                + " && document.querySelector('[data-testid=mobile-hotkeys]')?.dataset.paletteOpen === 'false'");
        tapDomCenter(".terminal-viewport");
        awaitImeVisible(true);
        String recoveryFocusPredicate = "document.querySelector('.app-shell')?.dataset.keyboardVisible === 'true'"
                + " && document.activeElement?.classList.contains('xterm-helper-textarea') === true";
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
                + " && !!document.querySelector('[data-testid=prompt-composer-launcher]')");
        openPromptComposerSheet();
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
        closePromptComposerSheet();
        tapDomCenter(".terminal-viewport");
        awaitImeVisible(true);
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
        tapDomCenter(".terminal-viewport");
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
        tapDomCenter("[data-testid=mobile-hotkeys-launcher]");
        awaitJsTrue("document.querySelector('[data-testid=mobile-hotkeys]')?.dataset.paletteOpen === 'true'");
        JSONObject idleCatalog = captureGeometry("dictation-idle-catalog-ime-open");
        assertEquals("the idle expanded surface is the key catalog alone", 1, idleCatalog.getInt("expandedInputSurfaceCount"));
        assertCatalogSheetGeometry(idleCatalog, "main");
        assertDockDestinationLabels(idleCatalog);
        assertDictationMicReachable(idleCatalog);
        captureScreenshot("fastkeys-dictation-idle-catalog-ime-open.png");
        tapDomCenter("[data-testid=mobile-hotkeys-launcher]");
        awaitJsTrue("document.querySelector('[data-testid=mobile-hotkeys]')?.dataset.paletteOpen === 'false'");

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
        tapDomCenter(".terminal-viewport");
        awaitImeVisible(true);
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.keyboardVisible === 'true'");
        markResizeFitPhase("dictation-receiver-after-prompt-retap");
        awaitTerminalResizeIdle();
        awaitRenderedFrame();

        JSONObject readyGeometry = captureGeometry("dictation-ready-ime-open");
        assertDictationMicReachable(readyGeometry);
        assertUnchangedTerminalGrid("preparing the host-side dictation receiver", stableGrid, runtimeGrid(readyGeometry));
        assertEquals("host-side receiver setup must settle back to the initial terminal viewport height",
                idle.getJSONObject("terminalGridViewport").getDouble("height"),
                readyGeometry.getJSONObject("terminalGridViewport").getDouble("height"), 0.5);
        stableResizeAcks = readyGeometry.getInt("resizeAcks");
        int writesBeforeListening = terminalInputAcknowledgements();
        String targetBefore = evalString("document.querySelector('[data-testid=inline-dictation-bar]')?.dataset.targetKey ?? ''");
        tapDomCenter("[data-testid=mobile-hotkeys-launcher]");
        awaitJsTrue("document.querySelector('[data-testid=mobile-hotkeys]')?.dataset.paletteOpen === 'true'");
        JSONObject catalogBeforeListening = captureGeometry("dictation-catalog-before-listening-ime-open");
        assertEquals("the key catalog is the only expanded surface before starting dictation", 1,
                catalogBeforeListening.getInt("expandedInputSurfaceCount"));
        assertCatalogSheetGeometry(catalogBeforeListening, "main");
        assertAtLeastFiveRows("Main catalog before inline dictation", catalogBeforeListening);
        tapDomCenter("[data-testid=inline-dictation-toggle]");
        awaitJsTrue("document.querySelector('[data-testid=inline-dictation-bar]')?.dataset.phase === 'listening'"
                + " && !document.querySelector('[data-testid=mobile-hotkeys-sheet]')"
                + " && !document.querySelector('[data-testid=prompt-composer]')"
                + " && document.querySelector('[data-testid=mobile-hotkeys]')?.dataset.paletteOpen === 'false'");
        assertEquals("one dock tap must start only one Android speech plugin session", 1, nativeSpeechCallCount("startCount"));
        JSONObject start = evalJson("JSON.stringify(window.__ps2857NativeSpeechEvidence?.startOptions ?? null)");
        String requestId = start.getString("requestId");
        assertTrue("the packaged path must start the real native plugin in debug event-injection mode", start.optBoolean("testMode"));
        JSONObject partialInjection = new JSONObject(injectNativeDictationTestEvent("partial", dictatedText));
        assertEquals("native partial event must target the active request", requestId, partialInjection.getString("requestId"));
        awaitJsTrue("document.querySelector('[data-testid=inline-dictation-preview]')?.textContent.trim() === "
                + JSONObject.quote(dictatedText));
        int writesAfterPartial = terminalInputAcknowledgements();
        JSONObject listening = captureGeometry("dictation-listening-ime-open");
        assertEquals("recording starts after the catalog closes and does not open the prompt composer", 0,
                listening.getInt("expandedInputSurfaceCount"));
        assertTerminalViewportCap("showing a dictation partial", idle, listening);
        assertTrayBelowTerminalViewport(listening);
        assertDictationMicReachable(listening);
        assertHotkeyBarReachable(listening);
        assertEquals("dictation previews must stay local to the dock", writesBeforeListening, terminalInputAcknowledgements());
        assertDictationStableStage("showing a dictation partial", idle, listening, stableGrid, stableResizeAcks);
        JSONObject listeningMic = listening.getJSONObject("inlineDictationMic");
        assertEquals("listening exposes an accessible Stop and insert action", "Stop dictation and insert at terminal cursor",
                listeningMic.getString("label"));
        assertEquals("listening mic title matches its accessible action", "Stop dictation and insert at terminal cursor",
                listeningMic.getString("title"));
        assertEquals("listening exposes the visible Stop action in the dock", "Stop", listeningMic.getString("visibleText"));
        assertEquals("listening exposes its distinct active button state", "listening", listeningMic.getString("micState"));
        assertTrue("Stop caption remains readable against its button surface: " + listeningMic,
                listeningMic.getDouble("captionContrastRatio") >= 4.5);
        assertTrue("Stop glyph remains distinguishable against its button surface: " + listeningMic,
                listeningMic.getDouble("iconContrastRatio") >= 3.0);
        assertTrue("listening keeps a visible mic/Stop icon in its reachable target", listeningMic.getBoolean("iconVisible"));
        awaitRenderedFrame();
        awaitJsTrue("document.querySelector('[data-testid=inline-dictation-bar]')?.dataset.phase === 'listening'"
                + " && document.querySelector('[data-testid=inline-dictation-preview]')?.textContent.trim() === "
                + JSONObject.quote(dictatedText));
        awaitImeVisible(true);
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.keyboardVisible === 'true'");
        assertTrue("listening screenshot must include the visible Android IME", isImeVisible());
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
        awaitJsTrue("window.__ps2857NativeSpeechEvidence?.stopOptions?.requestId === " + JSONObject.quote(requestId));
        assertEquals("explicit Stop must call the Android speech plugin once", 1, nativeSpeechCallCount("stopCount"));
        assertEquals("explicit Stop alone must not insert before a final result", writesBeforeListening,
                terminalInputAcknowledgements());
        JSONObject finalInjection = new JSONObject(injectNativeDictationTestEvent("result", dictatedText));
        awaitJsTrue("document.querySelector('[data-testid=inline-dictation-preview]')?.textContent.trim() === "
                + JSONObject.quote(dictatedText));
        int writesAfterFinalBeforeStopped = terminalInputAcknowledgements();
        assertEquals("final recognition remains staged until native stopped", writesBeforeListening,
                writesAfterFinalBeforeStopped);
        JSONObject finalAwaitingStopped = captureGeometry("dictation-final-awaiting-stopped");
        assertTerminalViewportCap("staging final dictation text", idle, finalAwaitingStopped);
        assertTrayBelowTerminalViewport(finalAwaitingStopped);
        assertDictationMicReachable(finalAwaitingStopped);
        assertDictationStableStage("staging final dictation text", idle, finalAwaitingStopped, stableGrid,
                stableResizeAcks);
        captureScreenshot("fastkeys-dictation-transcribing-ime-open.png");
        JSONObject finishInjection = new JSONObject(injectNativeDictationTestEvent("finish", null));
        awaitJsTrue("document.querySelector('[data-testid=inline-dictation-bar]')?.dataset.phase === 'idle'"
                + " && !document.querySelector('[data-testid=inline-dictation-status]')"
                + " && Number(document.querySelector('.app-shell')?.dataset.sshTerminalInputAcks) === "
                + (writesBeforeListening + 1), 15_000);
        int writesAfterStopped = terminalInputAcknowledgements();
        awaitDockGeometrySettled("inserting final dictation text");
        JSONObject finalInsertedGeometry = captureGeometry("dictation-final-inserted");
        assertTerminalViewportCap("inserting final dictation text", idle, finalInsertedGeometry);
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
        awaitJsTrue("(() => {const bar=document.querySelector('[data-testid=inline-dictation-bar]');"
                + "const status=document.querySelector('[data-testid=inline-dictation-status]');"
                + "const toggle=document.querySelector('[data-testid=inline-dictation-toggle]');"
                + "return bar?.dataset.phase==='idle'&&!status"
                + "&&!document.querySelector('[data-testid=inline-dictation-preview]')&&toggle?.disabled===false;})()");
        captureScreenshot("fastkeys-dictation-stopped-ime-open.png");
        captureTerminalViewportScreenshot("fastkeys-dictation-stopped-ime-open-viewport.png", finalInsertedGeometry);
        int insertedNativeStartCalls = nativeSpeechCallCount("startCount");
        int insertedNativeStopCalls = nativeSpeechCallCount("stopCount");
        String insertedStopRequestId = evalString("window.__ps2857NativeSpeechEvidence?.stopOptions?.requestId ?? ''");
        journey.put("terminalNativeDictation", new JSONObject()
                .put("bridge", "Capacitor SpeechRecognition plugin")
                .put("debugTestMode", true)
                .put("requestId", requestId)
                .put("startOptions", start)
                .put("startResult", new JSONObject(evalString("JSON.stringify(window.__ps2857NativeSpeechEvidence?.startResult ?? {})")))
                .put("stopResult", new JSONObject(evalString("JSON.stringify(window.__ps2857NativeSpeechEvidence?.stopResult ?? {})")))
                .put("partialInjection", partialInjection)
                .put("finalInjection", finalInjection)
                .put("finishInjection", finishInjection)
                .put("explicitStopRequestId", insertedStopRequestId)
                .put("finalAndStoppedInjectedThroughNativePlugin", true)
                .put("startCalls", insertedNativeStartCalls)
                .put("stopCalls", insertedNativeStopCalls)
                .put("writesBeforePartial", writesBeforeListening)
                .put("writesAfterPartial", writesAfterPartial)
                .put("writesAfterFinalBeforeStopped", writesAfterFinalBeforeStopped)
                .put("writesAfterStopped", writesAfterStopped));
        evalString("window.__ps2857DictationTestMode = false; 'debug speech event mode disabled'");
        installControlledSpeechAdapter();

        // Keep the host receiver open through the background/resume geometry check; the
        // post-Stop keyboard character completes its exact byte capture afterward.
        String draftBeforePostStopInput = evalString("document.querySelector('[data-testid=prompt-draft]')?.value ?? ''");
        assertEquals("the command receiver must leave PromptComposer's draft empty", "", draftBeforePostStopInput);
        int postStopInputChunkStart = Integer.parseInt(evalString("String(window.__ps2857AppTerminalInputChunks?.length ?? 0)"));
        int writesBeforeBackgroundCancel = terminalInputAcknowledgements();
        int resizeAcksBeforeBackgroundResume = terminalResizeAcks();
        tapDomCenter("[data-testid=inline-dictation-toggle]");
        awaitJsTrue("document.querySelector('[data-testid=inline-dictation-bar]')?.dataset.phase === 'listening'");
        assertEquals("background cancellation must have one active recognizer", 2, controlledSpeechCallCount("startCount"));
        String backgroundRequest = evalJson("JSON.stringify(window.__ps2857ControlledSpeech.startOptions ?? null)").getString("requestId");
        evalString("window.__ps2857ControlledSpeech.emit('partial', 'must be cancelled on background'); 'partial emitted'");
        awaitJsTrue("document.querySelector('[data-testid=inline-dictation-preview]')?.textContent.trim() === 'must be cancelled on background'");
        scenario.moveToState(Lifecycle.State.CREATED);
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.sshPhase === 'background'", 10_000);
        scenario.moveToState(Lifecycle.State.RESUMED);
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.sshPhase === 'live'", 15_000);
        awaitTerminalResizeIdle();
        awaitJsTrue("Number(document.querySelector('.app-shell')?.dataset.sshTerminalResizeAcks ?? 0) > "
                + resizeAcksBeforeBackgroundResume
                + " && document.querySelector('[data-testid=terminal-resize-status]')?.textContent.trim().endsWith('accepted by SSH') === true");
        evalString("window.__ps2857ControlledSpeech.emit('result', 'late background result'); 'late result emitted'");
        evalString("window.__ps2857ControlledSpeech.emit('stopped'); 'stopped emitted'");
        awaitJsTrue("document.querySelector('[data-testid=inline-dictation-bar]')?.dataset.phase === 'idle'");
        assertEquals("background results must not write after resume", writesBeforeBackgroundCancel,
                terminalInputAcknowledgements());
        assertEquals("backgrounding must stop the pending recognizer", backgroundRequest,
                evalString("window.__ps2857ControlledSpeech?.stopOptions?.requestId ?? ''"));
        int resizeAcksAfterBackgroundResume = terminalResizeAcks();
        journey.put("dictationBackgroundCancel", new JSONObject().put("requestId", backgroundRequest)
                .put("stopRequestId", evalString("window.__ps2857ControlledSpeech?.stopOptions?.requestId ?? ''"))
                .put("lateResultEmitted", true).put("stoppedEmitted", true)
                .put("nativeStartCalls", 2)
                .put("nativeStopCalls", 2)
                .put("resizeAcksBeforeResume", resizeAcksBeforeBackgroundResume)
                .put("resizeAcksAfterResume", resizeAcksAfterBackgroundResume)
                .put("writesBefore", writesBeforeBackgroundCancel).put("writesAfter", terminalInputAcknowledgements()));
        JSONObject backgroundGeometry = captureGeometry("dictation-background-cancel-resumed");
        assertBackgroundResumeGeometryAcknowledged(backgroundGeometry, resizeAcksBeforeBackgroundResume);
        captureScreenshot("fastkeys-dictation-background-cancel-resumed.png");
        captureTerminalViewportScreenshot("fastkeys-dictation-background-cancel-resumed-viewport.png", backgroundGeometry);
        assertDictationMicReachable(backgroundGeometry);

        tapDomCenter(".terminal-viewport");
        awaitImeVisible(true);
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.keyboardVisible === 'true'"
                + " && document.querySelector('.app-shell')?.dataset.keyboardComposerMode === 'true'");
        awaitTerminalResizeIdle();
        JSONObject postResumeKeyboardGeometry = captureGeometry("dictation-post-resume-ime-open");
        assertAcceptedKeyboardUpViewport("IME reopening after the hidden resume", postResumeKeyboardGeometry);
        assertDictationMicReachable(postResumeKeyboardGeometry);
        assertTrayBelowTerminalViewport(postResumeKeyboardGeometry);
        assertTrue("reopening the IME must receive a fresh host PTY size acknowledgement",
                postResumeKeyboardGeometry.getInt("resizeAcks") > backgroundGeometry.getInt("resizeAcks"));
        assertGridHasAcceptedResizeAck(postResumeKeyboardGeometry, "IME reopening after the hidden resume");
        captureScreenshot("fastkeys-dictation-post-resume-ime-open.png");
        captureTerminalViewportScreenshot("fastkeys-dictation-post-resume-ime-open-viewport.png", postResumeKeyboardGeometry);

        // Type one real character through Android's keyboard after Stop. The host receiver
        // is still waiting for it, so this proves subsequent text reaches the PTY instead
        // of silently landing in PromptComposer's separate draft.
        InstrumentationRegistry.getInstrumentation().sendStringSync(postStopKeyboardText);
        try {
            awaitJsTrue("(window.__ps2857TerminalVisibleText || '').includes(" + JSONObject.quote(doneMarker) + ")", 15_000);
        } catch (AssertionError markerFailure) {
            try {
                capturePostStopDoneMarkerFailure(readyMarker, doneMarker, postStopInputChunkStart);
            } catch (Exception | AssertionError evidenceFailure) {
                markerFailure.addSuppressed(evidenceFailure);
            }
            throw markerFailure;
        }
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
        assertDictationStableStage("typing after Stop", postResumeKeyboardGeometry, postStopKeyboardGeometry,
                runtimeGrid(postResumeKeyboardGeometry), postResumeKeyboardGeometry.getInt("resizeAcks"));
        awaitRenderedFrame();
        JSONObject postResumeUiState = captureDictationCheckpoint("dictation-post-resume-after-keyboard-input");
        journey.put("dictationPostResumeUiState", postResumeUiState);
        awaitJsTrue("(() => {const bar=document.querySelector('[data-testid=inline-dictation-bar]');"
                + "const toggle=document.querySelector('[data-testid=inline-dictation-toggle]');"
                + "return bar?.dataset.phase==='idle'&&toggle?.disabled===false"
                + "&&!bar.querySelector('[data-testid=inline-dictation-preview]');})()");
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
                .put("nativeStartCalls", insertedNativeStartCalls)
                .put("nativeStopCalls", insertedNativeStopCalls)
                .put("stopRequestId", insertedStopRequestId)
                .put("rawFile", rawFile)
                .put("geometryOracleFile", rawFile + ".geometry")
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
        assertEquals("error recovery must start one fresh recognizer", 3, controlledSpeechCallCount("startCount"));
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
        assertTrayBelowTerminalViewport(errorGeometry);
        assertDictationMicReachable(errorGeometry);
        assertDictationStableStage("showing recognizer error status", postResumeKeyboardGeometry, errorGeometry,
                runtimeGrid(postResumeKeyboardGeometry), postResumeKeyboardGeometry.getInt("resizeAcks"));
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
        assertEquals("attach cancellation must not create a second recognizer", 4, controlledSpeechCallCount("startCount"));
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

        tapDomCenter(".terminal-viewport");
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

        attachSession(firstSession);
    }

    private void exercisePromptDictationFromComposer() throws Exception {
        Log.i("PS2897Prompt", "checkpoint Prompt composer dictation waiting for live terminal");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.sshPhase === 'live'"
                + " && document.querySelector('[data-testid=prompt-composer-launcher]')?.disabled === false"
                + " && !document.querySelector('[data-testid=prompt-dictation-launcher]')");
        openPromptComposerSheet();
        JSONObject composerReady = awaitPromptDictationComposerReady();
        journey.put("promptDictationComposerReady", composerReady);
        Log.i("PS2897Prompt", "checkpoint composer Dictate ready after IME/resize/frame settle " + composerReady);
        int writesBefore = terminalInputAcknowledgements();
        int startsBefore = controlledSpeechCallCount("startCount");
        int stopsBefore = controlledSpeechCallCount("stopCount");
        int pointerEventsBefore = pointerEventCount();
        tapDomCenter("[data-testid=composer-dictate]");
        JSONObject dictateTapState = capturePromptDictationComposerTapState(pointerEventsBefore);
        journey.put("promptComposerDictateTap", dictateTapState);
        Log.i("PS2897Prompt", "checkpoint composer Dictate tap returned " + dictateTapState);
        try {
            awaitJsTrue("document.querySelector('[data-testid=prompt-composer]')?.getAttribute('role') === 'dialog'"
                    + " && document.querySelector('[data-testid=prompt-composer]')?.dataset.dictationState === 'recording'");
        } catch (AssertionError error) {
            JSONObject failedState = capturePromptDictationComposerTapState(pointerEventsBefore);
            Log.e("PS2897Prompt", "composer Dictate failed to reach recording " + failedState, error);
            throw new AssertionError("composer Dictate did not reach recording state; tap diagnostics=" + failedState, error);
        }
        Log.i("PS2897Prompt", "checkpoint composer Dictate reached recording");
        assertEquals("one Dictate tap starts exactly one prompt recognizer", startsBefore + 1,
                controlledSpeechCallCount("startCount"));
        assertEquals("prompt dictation stays separate from terminal-cursor dictation", "idle",
                evalString("document.querySelector('[data-testid=inline-dictation-bar]')?.dataset.phase ?? ''"));
        assertEquals("opening prompt dictation must not write to the terminal", writesBefore,
                terminalInputAcknowledgements());
        String transcript = "explain why the build failed";
        evalString("window.__ps2857ControlledSpeech.emit('partial', " + JSONObject.quote(transcript) + "); 'partial emitted'");
        awaitJsTrue("document.querySelector('[data-testid=composer-recording-preview]')?.textContent.trim() === "
                + JSONObject.quote(transcript));
        awaitRenderedFrame();
        JSONObject keyboardHidden;
        try {
            keyboardHidden = awaitPromptDictationKeyboardHidden();
        } catch (AssertionError error) {
            // Preserve the live recording surface if either keyboard source
            // remains visible so a failed hide transition has visual evidence.
            captureScreenshot("fastkeys-prompt-dictation-recording.png");
            throw error;
        }
        awaitRenderedFrame();
        JSONObject recording = evalJson("(() => {const panel=document.querySelector('[data-testid=prompt-composer]');"
                + "const stop=document.querySelector('[data-testid=composer-recording-stop]');"
                + "return JSON.stringify({state:panel?.dataset.dictationState??'',title:document.querySelector('#composer-title')?.textContent.trim()??'',"
                + "stopLabel:stop?.getAttribute('aria-label')??'',draft:document.querySelector('[data-testid=prompt-draft]')?.value??'',"
                + "keyboardVisible:document.querySelector('.app-shell')?.dataset.keyboardVisible==='true'});})()");
        recording.put("nativeIme", keyboardHidden.getJSONObject("nativeIme"));
        assertEquals("the composer microphone opens the explicit recording state", "recording",
                recording.getString("state"));
        assertEquals("recording names its prompt destination", "Prompt dictation", recording.getString("title"));
        assertEquals("recording provides a visible accessible Stop action",
                "Stop dictation and keep the recognized text in the editable draft", recording.getString("stopLabel"));
        assertEquals("the recording sheet hides the Android keyboard", false,
                recording.getBoolean("keyboardVisible"));
        assertEquals("native WindowInsets confirms composer dictation dismissed Android's IME", false,
                recording.getJSONObject("nativeIme").getBoolean("visible"));
        captureScreenshot("fastkeys-prompt-dictation-recording.png");

        Log.i("PS2897Prompt", "checkpoint before tapping explicit Stop");
        tapDomCenter("[data-testid=composer-recording-stop]");
        Log.i("PS2897Prompt", "checkpoint Stop tap returned; waiting for Transcribing");
        awaitJsTrue("document.querySelector('[data-testid=prompt-composer]')?.dataset.dictationState === 'transcribing'");
        Log.i("PS2897Prompt", "checkpoint DOM reached Transcribing; checking native stop count");
        assertEquals("explicit Stop reaches the native adapter exactly once", stopsBefore + 1,
                controlledSpeechCallCount("stopCount"));
        JSONObject transcribing = evalJson("(() => {const panel=document.querySelector('[data-testid=prompt-composer]');"
                + "return JSON.stringify({state:panel?.dataset.dictationState??'',title:document.querySelector('#composer-title')?.textContent.trim()??''});})()");
        assertEquals("explicit Stop exposes the transcribing phase before review", "transcribing",
                transcribing.getString("state"));
        awaitRenderedFrame();
        Log.i("PS2897Prompt", "checkpoint native stop count passed; capturing Transcribing screenshot");
        captureScreenshot("fastkeys-prompt-dictation-transcribing.png");
        Log.i("PS2897Prompt", "checkpoint Transcribing screenshot returned; emitting final result");
        evalString("window.__ps2857ControlledSpeech.emit('result', " + JSONObject.quote(transcript) + "); 'result emitted'");
        Log.i("PS2897Prompt", "checkpoint final result emitted; emitting stopped callback");
        evalString("window.__ps2857ControlledSpeech.emit('stopped'); 'stopped emitted'");
        Log.i("PS2897Prompt", "checkpoint stopped callback emitted; waiting for editable review");
        awaitJsTrue("(() => {const panel=document.querySelector('[data-testid=prompt-composer]');"
                + "return panel?.dataset.dictationState==='review'"
                + "&& document.querySelector('[data-testid=prompt-draft]')?.value===" + JSONObject.quote(transcript)
                + "&& document.querySelector('#composer-title')?.textContent.trim()==='Review dictation'"
                + "&& document.querySelector('[data-testid=composer-mode-status]')?.textContent.trim()==='REVIEW'"
                + "&& !!document.querySelector('[data-testid=composer-dictation-review]')"
                + "&& !document.querySelector('[data-testid=composer-recording-mode]');})()");
        Log.i("PS2897Prompt", "checkpoint transcript reached editable review");
        JSONObject review = evalJson("(() => {const draft=document.querySelector('[data-testid=prompt-draft]');"
                + "const actions=Array.from(document.querySelectorAll('[data-testid=composer-actions] button')).map(node=>node.textContent.trim()||node.title);"
                + "return JSON.stringify({state:document.querySelector('[data-testid=prompt-composer]')?.dataset.dictationState??'',"
                + "title:document.querySelector('#composer-title')?.textContent.trim()??'',"
                + "mode:document.querySelector('[data-testid=composer-mode-status]')?.textContent.trim()??'',"
                + "reviewText:document.querySelector('[data-testid=composer-dictation-review]')?.textContent.trim()??'',"
                + "recordingModePresent:!!document.querySelector('[data-testid=composer-recording-mode]'),"
                + "draftLabel:draft?.getAttribute('aria-label')??'',draftReadOnly:draft?.getAttribute('aria-readonly')??'',"
                + "discard:!!document.querySelector('[data-testid=composer-discard]'),insert:!!document.querySelector('[data-testid=composer-insert]'),"
                + "send:!!document.querySelector('.composer-shared-controls .send'),actions});})()");
        assertEquals("the stopped prompt transcript enters editable review", "review", review.getString("state"));
        assertEquals("review title must be part of the rendered sheet", "Review dictation", review.getString("title"));
        assertEquals("review phase must be visibly labeled", "REVIEW", review.getString("mode"));
        assertTrue("editable review copy must be visible in the rendered sheet", review.getString("reviewText").contains("Transcript ready"));
        assertEquals("recording/transcribing surface must be removed before the review screenshot", false,
                review.getBoolean("recordingModePresent"));
        assertTrue("review labels the transcript as editable", review.getString("draftLabel").contains("editable"));
        assertEquals("review unlocks the transcript field", "false", review.getString("draftReadOnly"));
        assertTrue("review offers Discard, Insert, and Send", review.getBoolean("discard")
                && review.getBoolean("insert") && review.getBoolean("send"));
        assertEquals("recognition review never sends automatically", writesBefore, terminalInputAcknowledgements());
        awaitRenderedFrame();
        captureScreenshot("fastkeys-prompt-dictation-review.png");

        String editedTranscript = "edited: " + transcript;
        setValue("[data-testid=prompt-draft]", editedTranscript);
        assertEquals("the recognized transcript remains editable before delivery", editedTranscript,
                evalString("document.querySelector('[data-testid=prompt-draft]')?.value ?? ''"));
        tapDomCenter("[data-testid=composer-discard]");
        awaitJsTrue("document.querySelector('[data-testid=composer-discard]')?.textContent.trim() === 'Discard?'");
        tapDomCenter("[data-testid=composer-discard]");
        awaitJsTrue("document.querySelector('[data-testid=prompt-draft]')?.value === ''"
                + " && document.querySelector('[data-testid=prompt-composer]')?.dataset.dictationState === 'idle'");
        assertEquals("Discard clears the reviewed prompt without touching the terminal", writesBefore,
                terminalInputAcknowledgements());
        JSONObject evidence = new JSONObject().put("entry", "Prompt composer microphone")
                .put("nativeStartCalls", controlledSpeechCallCount("startCount") - startsBefore)
                .put("nativeStopCalls", controlledSpeechCallCount("stopCount") - stopsBefore)
                .put("recording", recording).put("transcribing", transcribing).put("review", review).put("transcript", transcript)
                .put("editedTranscript", editedTranscript).put("editable", true)
                .put("actions", new JSONArray().put("Discard").put("Insert").put("Send"))
                .put("discardCleared", true).put("terminalWrites", terminalInputAcknowledgements() - writesBefore);
        journey.put("promptDictationFromComposer", evidence);
        closePromptComposerSheet();
    }

    private JSONObject awaitPromptDictationKeyboardHidden() throws Exception {
        long deadline = SystemClock.uptimeMillis() + 6_000;
        boolean nativeImeVisible = true;
        String appKeyboardVisible = "unknown";
        while (SystemClock.uptimeMillis() < deadline) {
            nativeImeVisible = isImeVisible();
            appKeyboardVisible = evalString("document.querySelector('.app-shell')?.dataset.keyboardVisible ?? 'missing'");
            if (!nativeImeVisible && "false".equals(appKeyboardVisible)) {
                SystemClock.sleep(120);
                if (!isImeVisible()
                        && "false".equals(evalString("document.querySelector('.app-shell')?.dataset.keyboardVisible ?? 'missing'"))) {
                    JSONObject settled = evalJson("(() => {const shell=document.querySelector('.app-shell');"
                            + "return JSON.stringify({keyboardVisible:shell?.dataset.keyboardVisible??null,"
                            + "keyboardComposerMode:shell?.dataset.keyboardComposerMode??null,"
                            + "activeElement:document.activeElement?.outerHTML?.slice(0,180)??null,"
                            + "focusEvents:(window.__ps2884FocusEvents??[]).slice(-10),"
                            + "visualViewport:{height:window.visualViewport?.height??innerHeight,offsetTop:window.visualViewport?.offsetTop??0}});})()");
                    settled.put("nativeIme", readNativeImeState());
                    Log.i("PS2897Prompt", "recording state has both native and app keyboard hidden " + settled);
                    return settled;
                }
            }
            SystemClock.sleep(100);
        }
        JSONObject failedState = evalJson("(() => {const shell=document.querySelector('.app-shell');"
                + "return JSON.stringify({keyboardVisible:shell?.dataset.keyboardVisible??null,"
                + "keyboardComposerMode:shell?.dataset.keyboardComposerMode??null,"
                + "activeElement:document.activeElement?.outerHTML?.slice(0,180)??null,"
                + "focusEvents:(window.__ps2884FocusEvents??[]).slice(-12),"
                + "visualViewport:{height:window.visualViewport?.height??innerHeight,offsetTop:window.visualViewport?.offsetTop??0}});})()");
        failedState.put("nativeIme", readNativeImeState());
        Log.e("PS2897Prompt", "timed out waiting for both native and app keyboard state to hide: " + failedState);
        throw new AssertionError("Prompt dictation recording must dismiss both the native IME and app keyboard state "
                + "within 6000ms; lastNativeVisible=" + nativeImeVisible + ", lastAppKeyboardVisible="
                + appKeyboardVisible + ", state=" + failedState);
    }

    private JSONObject awaitPromptDictationComposerReady() throws Exception {
        Log.i("PS2897Prompt", "waiting for native IME visible before composer Dictate tap");
        awaitImeVisible(true);
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.keyboardVisible === 'true'"
                + " && document.querySelector('.app-shell')?.dataset.keyboardComposerMode === 'true'"
                + " && document.querySelector('.app-shell')?.dataset.sshPhase === 'live'");
        awaitTerminalResizeIdle();
        awaitRenderedFrame();

        JSONObject state = capturePromptDictationComposerTapState(pointerEventCount());
        JSONObject button = state.getJSONObject("button");
        JSONObject bounds = button.getJSONObject("bounds");
        JSONObject hit = button.getJSONObject("centerTarget");
        JSONObject nativeIme = state.getJSONObject("nativeIme");
        assertTrue("composer Dictate must be enabled, at least 48dp, and fully visible after IME resize settles: " + state,
                !button.getBoolean("disabled") && button.getBoolean("visible")
                        && bounds.getDouble("width") >= 47.9 && bounds.getDouble("height") >= 47.9);
        assertEquals("the center of the composer Dictate target must hit that same button before touch injection: " + state,
                "composer-dictate", hit.optString("buttonTestId"));
        assertTrue("composer Dictate tap requires the native and WebView keyboard state to agree: " + state,
                nativeIme.getBoolean("visible") && "true".equals(state.optString("keyboardVisible"))
                        && state.getBoolean("keyboardComposerMode"));
        assertEquals("composer Dictate tap requires an idle PTY resize pipeline: " + state, 0,
                state.getInt("resizePending"));
        assertTrue("composer Dictate tap requires an acknowledged, failure-free PTY resize: " + state,
                state.getInt("resizeAcks") > 0 && state.getInt("resizeFailures") == 0);
        return state;
    }

    private void assertDictationMicReachable(JSONObject geometry) throws Exception {
        JSONObject bar = geometry.optJSONObject("inlineDictationBar");
        JSONObject mic = geometry.optJSONObject("inlineDictationMic");
        assertNotNull("one inline dictation component must live in the fast-key dock", bar);
        assertNotNull("the docked dictation component must render its persistent microphone action", mic);
        assertEquals("the dock must have exactly one inline controller surface", 1,
                geometry.getInt("inlineDictationBarCount"));
        assertEquals("the dock must have exactly one microphone target", 1,
                geometry.getInt("inlineDictationMicCount"));
        assertTrue("the mic target must be exactly 48dp wide and tall: " + mic,
                Math.abs(mic.getDouble("width") - 48.0) < 0.5 && Math.abs(mic.getDouble("height") - 48.0) < 0.5);
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
        String expectedAccessibleLabel = "listening".equals(phase) ? "Stop dictation and insert at terminal cursor"
                : "starting".equals(phase) ? "Cancel terminal cursor dictation request"
                : "cancelling".equals(phase) ? "Cancelling terminal dictation"
                : "stopping".equals(phase) ? "Transcribing speech for terminal cursor"
                : "inserting".equals(phase) ? "Inserting speech at terminal cursor"
                : mic.getBoolean("disabled") ? "Terminal cursor dictation unavailable"
                : "error".equals(tone) ? "Retry terminal cursor dictation" : "Dictate at terminal cursor";
        String expectedMicState = "listening".equals(phase) ? "listening"
                : transcribing ? "transcribing" : "starting".equals(phase) ? "starting"
                : "error".equals(tone) ? "error" : "idle";
        String expectedDockLabel = "listening".equals(phase) ? "Stop"
                : "starting".equals(phase) ? "Cancel"
                : transcribing ? "Wait"
                : "error".equals(tone) ? "Retry"
                : mic.getBoolean("disabled") ? "Unavailable" : "Dictate";
        assertEquals("inline dictation keeps a phase-specific accessible action label: " + geometry,
                expectedAccessibleLabel, mic.getString("label"));
        assertEquals("inline dictation title mirrors its phase-specific accessible action: " + geometry,
                expectedAccessibleLabel, mic.getString("title"));
        assertTrue("inline dictation keeps a visible microphone icon: " + geometry, mic.getBoolean("iconVisible"));
        String expectedIconPaths = "listening".equals(phase) ? "[\"M7 7h10v10H7z\"]"
                : "[\"M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3z\",\"M19 10v2a7 7 0 0 1-14 0v-2\",\"M12 19v3M8 22h8\"]";
        assertEquals("active terminal dictation exposes a Stop glyph, returning to the microphone afterward",
                expectedIconPaths,
                mic.getJSONArray("iconPaths").toString());
        assertEquals("only the listening mic is exposed as pressed: " + geometry,
                "listening".equals(phase), mic.getBoolean("pressed"));
        assertEquals("inline dictation exposes its idle/listening/transcribing/error state: " + geometry,
                expectedMicState, mic.getString("micState"));
        assertEquals("visible terminal control names its current action: " + geometry,
                expectedDockLabel, mic.getString("visibleText"));
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
        boolean shouldShowStatus = !"idle".equals(phase) || "error".equals(tone) || "warning".equals(tone);
        assertEquals("listening/transcribing/error states expose a status strip above the row: " + geometry,
                shouldShowStatus, geometry.getBoolean("inlineDictationStatusVisible"));
        if (geometry.getBoolean("inlineDictationStatusVisible")) {
            assertTrue("the dictation status chip must render on one line", geometry.getBoolean("inlineDictationStatusOneLine"));
            assertTrue("the dictation status chip must stay inside the dock", geometry.getBoolean("inlineDictationStatusInsideBar"));
            JSONObject statusRow = geometry.getJSONObject("inlineDictationStatusRow");
            assertTrue("dictation status must precede persistent keys on both catalog pages: " + geometry,
                    geometry.getBoolean("inlineDictationStatusAboveKeybar")
                            && !geometry.getBoolean("inlineDictationStatusInsideSheetHeader")
                            && statusRow.getDouble("bottom") <= keybar.getDouble("top") + 0.5);
            double expectedStatusRowHeight = "listening".equals(phase) ? 39.5 : 31.5;
            assertTrue("the status row keeps its phase-specific height above the 48dp key row", statusRow.getDouble("height") >= expectedStatusRowHeight);
            JSONObject statusMetrics = geometry.getJSONObject("inlineDictationStatusMetrics");
            assertTrue("dictation status uses readable 11px text and a 16px line", statusMetrics.getDouble("fontSize") >= 11
                    && statusMetrics.getDouble("lineHeight") >= 16);
            if ("listening".equals(phase)) {
                assertTrue("recording band fills its 40dp row with a distinct Listening treatment",
                        statusMetrics.getDouble("height") >= 37.5);
            } else {
                assertTrue("dictation status keeps 6px vertical chip padding", statusMetrics.getDouble("paddingTop") >= 6
                        && statusMetrics.getDouble("paddingBottom") >= 6);
                assertTrue("dictation status chip fits the padded line", statusMetrics.getDouble("height") >= 29.5);
            }
            if ("listening".equals(phase)) {
                assertTrue("Listening status names the terminal destination and shows its separate elapsed timer: " + geometry,
                        geometry.getString("inlineDictationStatusText").contains("Terminal")
                                && geometry.getString("inlineDictationStatusText").contains("Listening")
                                && geometry.getString("inlineDictationElapsed").matches("\\d{2,}:\\d{2}"));
                assertEquals("recording mode shows the complete waveform vocabulary", 12,
                        geometry.getInt("inlineDictationWaveformBars"));
                assertEquals("recording action is visibly captioned Stop", "Stop", mic.getString("visibleText"));
                assertTrue("partial transcript remains a preview inside the recording band",
                        !geometry.getString("inlineDictationPreview").isEmpty());
            } else if ("error".equals(tone)) {
                assertTrue("terminal cursor destination and error are named in the status chip: " + geometry,
                        geometry.getString("inlineDictationStatusText").contains("Terminal · Error ·"));
            }
        } else {
            assertEquals("idle default hint must not consume a status row", "idle", geometry.getString("inlineDictationPhase"));
        }
        int attachEpoch = geometry.getInt("sshAttachEpoch");
        assertTrue("dictation target identity must include the current attach epoch: " + geometry,
                geometry.getString("inlineDictationTargetKey").endsWith("/attach-" + attachEpoch));
    }

    private void grantMicrophonePermissionForJourney() throws Exception {
        String packageName = InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName();
        ParcelFileDescriptor command = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand("pm grant " + packageName + " android.permission.RECORD_AUDIO");
        if (command != null) command.close();
    }

    private void installNativeSpeechBridgeObserver() throws Exception {
        String installed = evalString("(() => {"
                + "const cap=window.Capacitor;"
                + "if(!cap||typeof cap.nativePromise!=='function')return 'missing-capacitor-bridge';"
                + "const nativePromise=cap.nativePromise.bind(cap);"
                + "const state={startOptions:null,stopOptions:null,startResult:null,stopResult:null,startCount:0,stopCount:0};"
                + "window.__ps2857NativeSpeechEvidence=state;window.__ps2857DictationTestMode=true;"
                + "cap.nativePromise=(plugin,method,options)=>{"
                + "if(plugin!=='SpeechRecognition'||!['startDictation','stopDictation'].includes(method))return nativePromise(plugin,method,options);"
                + "const copied=JSON.parse(JSON.stringify(options));"
                + "if(method==='startDictation'){state.startCount+=1;state.startOptions=copied;state.stopOptions=null;state.startResult=null;}"
                + "else{state.stopCount+=1;state.stopOptions=copied;state.stopResult=null;}"
                + "return nativePromise(plugin,method,options).then(result=>{"
                + "if(method==='startDictation')state.startResult=JSON.parse(JSON.stringify(result));"
                + "else state.stopResult=JSON.parse(JSON.stringify(result));return result;});};"
                + "return 'observing-native-speech-bridge';})()");
        assertEquals("test must observe and delegate the real Android speech bridge", "observing-native-speech-bridge", installed);
    }

    private String injectNativeDictationTestEvent(String type, String text) throws Exception {
        JSONObject options = new JSONObject().put("type", type);
        if (text != null) options.put("text", text);
        evalString("window.__ps2897NativeDictationInjection = null; (() => {"
                + "const plugin=window.Capacitor?.Plugins?.SpeechRecognition;"
                + "if(!plugin?.injectTestDictationEvent)throw new Error('native debug dictation injection is unavailable');"
                + "plugin.injectTestDictationEvent(JSON.parse(" + JSONObject.quote(options.toString()) + "))"
                + ".then(result=>window.__ps2897NativeDictationInjection=JSON.stringify(result))"
                + ".catch(error=>window.__ps2897NativeDictationInjection='ERROR: '+String(error));return 'queued';})()");
        awaitJsTrue("typeof window.__ps2897NativeDictationInjection === 'string'");
        String result = evalString("window.__ps2897NativeDictationInjection");
        JSONObject response = new JSONObject(result);
        assertTrue("Android's packaged SpeechRecognition plugin must emit an injected event: " + response,
                response.optBoolean("emitted"));
        evalString("window.__ps2897NativeDictationInjection = null; 'cleared'");
        return response.toString();
    }

    private int nativeSpeechCallCount(String name) throws Exception {
        return Integer.parseInt(evalString("String(window.__ps2857NativeSpeechEvidence?.["
                + JSONObject.quote(name) + "] ?? 0)"));
    }

    private void installControlledSpeechAdapter() throws Exception {
        String installed = evalString("(() => {"
                + "const cap=window.Capacitor;"
                + "if(!cap||typeof cap.nativePromise!=='function'||typeof cap.nativeCallback!=='function')return 'missing-capacitor-bridge';"
                + "const nativePromise=cap.nativePromise.bind(cap);const nativeCallback=cap.nativeCallback.bind(cap);"
                + "const state={startOptions:null,stopOptions:null,requestId:null,listener:null,startCount:1,stopCount:1,"
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
        String geometryOraclePath = rawPath + ".geometry";
        // Keep the receiver's temporary sampler out of the parent interactive
        // shell's job table. Bash's long Terminated notice can evict DONE from
        // the 38x6 alternate-screen viewport before the visibility check.
        String command = "(set +m; stty raw -echo; (while :; do stty size </dev/tty >> " + geometryOraclePath
                + "; sleep 0.1; done) & ptyGeometryMonitor=$!; printf '\\r\\n" + readyMarker + "\\r\\n'; dd bs=1 count=" + byteCount
                + " status=none > " + rawPath + "; stty sane; kill \"$ptyGeometryMonitor\" 2>/dev/null || true;"
                + " wait \"$ptyGeometryMonitor\" 2>/dev/null || true; printf '\\n" + doneMarker + "\\n')";
        markResizeFitPhase("set-receiver-draft:" + readyMarker);
        openPromptComposerSheet();
        setValue("[data-testid=prompt-draft]", command);
        markResizeFitPhase("send-receiver-command:" + readyMarker);
        click(".composer-shared-controls .send");
        awaitJsTrue("document.querySelector('[data-testid=prompt-draft]')?.value === ''");
        awaitJsTrue("(window.__ps2857TerminalVisibleText || '').includes(" + JSONObject.quote(readyMarker) + ")", 15_000);
        SystemClock.sleep(300);
        closePromptComposerSheet();
        markResizeFitPhase("receiver-ready:" + readyMarker);
    }

    private void openPromptComposerSheet() throws Exception {
        if (!"true".equals(evalRaw("!!document.querySelector('[data-testid=prompt-composer]')"))) {
            tapDomCenter("[data-testid=prompt-composer-launcher]");
        }
        awaitJsTrue("document.querySelector('[data-testid=prompt-composer]')?.getAttribute('role') === 'dialog'"
                + " && document.querySelector('[data-testid=prompt-composer]')?.getAttribute('aria-modal') === 'true'");
    }

    private void closePromptComposerSheet() throws Exception {
        if ("true".equals(evalRaw("!!document.querySelector('[data-testid=prompt-composer]')"))) {
            tapDomCenter("[data-testid=composer-close]");
        }
        awaitJsTrue("!document.querySelector('[data-testid=prompt-composer]')");
        awaitImeVisible(false);
    }

    private void markResizeFitPhase(String phase) throws Exception {
        evalString("window.__ps2884ResizeFitMarker=" + JSONObject.quote(phase) + "; 'resize marker set'");
    }

    private String terminalEvidence(String readyMarker, String doneMarker) throws Exception {
        return evalString("(() => {const ready=" + JSONObject.quote(readyMarker)
                + ", done=" + JSONObject.quote(doneMarker)
                + ", app=String(window.__ps2857AppTerminalLastChunk??''), term=String(window.__ps2857TerminalLastWriteText??''), visible=String(window.__ps2857TerminalVisibleText??'');"
                + "const hex=s=>Array.from(new TextEncoder().encode(s)).map(b=>b.toString(16).padStart(2,'0')).join('');"
                + "const appTail=app.slice(-1500),termTail=term.slice(-1500);"
                + "return JSON.stringify({appDeliveries:window.__ps2857AppTerminalDeliveryCount??0,"
                + "appLastContainsReady:app.includes(ready),appLastContainsDone:app.includes(done),"
                + "appLastText:appTail,appLastHex:hex(appTail),terminalWrites:window.__ps2857TerminalWriteCount??0,"
                + "writeCallbacks:window.__ps2857TerminalWriteCallbackCount??0,writeParsedEvents:window.__ps2857TerminalWriteParsedCount??0,"
                + "renderCount:window.__ps2857TerminalRenderCount??0,renderRange:String(window.__ps2857TerminalLastRenderRange??''),"
                + "bufferState:String(window.__ps2857TerminalBufferState??''),"
                + "terminalLastContainsReady:term.includes(ready),terminalLastContainsDone:term.includes(done),"
                + "terminalLastWriteText:termTail,terminalLastWriteHex:hex(termTail),"
                + "visibleContainsReady:visible.includes(ready),visibleContainsDone:visible.includes(done)});})()");
    }

    private void capturePostStopDoneMarkerFailure(String readyMarker, String doneMarker, int inputChunkStart)
            throws Exception {
        captureCurrentDeviceScreenshotDirect("fastkeys-dictation-post-stop-marker-failure.png");
        JSONObject state = evalJson("(() => {const shell=document.querySelector('.app-shell');"
                + "const active=document.activeElement;const rows=document.querySelector('.xterm-rows');"
                + "const chunks=window.__ps2857AppTerminalInputChunks??[];"
                + "return JSON.stringify({runId:" + JSONObject.quote(artifactRunId)
                + ",stage:'post-stop-done-marker-timeout',route:shell?.dataset.route??null,"
                + "homeSurface:shell?.dataset.homeSurface??null,sshPhase:shell?.dataset.sshPhase??null,"
                + "keyboardVisible:shell?.dataset.keyboardVisible??null,keyboardComposerMode:shell?.dataset.keyboardComposerMode??null,"
                + "activeElement:{tag:active?.tagName??'',className:typeof active?.className==='string'?active.className:'',"
                + "testId:active?.getAttribute?.('data-testid')??''},"
                + "terminalVisibleText:String(window.__ps2857TerminalVisibleText??'').slice(-12000),"
                + "xtermRowsText:rows?.innerText?.slice(-12000)??'',"
                + "appLastInputText:String(window.__ps2857AppTerminalLastChunk??''),"
                + "xtermLastWriteText:String(window.__ps2857TerminalLastWriteText??''),"
                + "appTerminalDeliveryCount:window.__ps2857AppTerminalDeliveryCount??0,"
                + "terminalWriteCount:window.__ps2857TerminalWriteCount??0,"
                + "terminalWriteCallbacks:window.__ps2857TerminalWriteCallbackCount??0,"
                + "terminalWriteParsedEvents:window.__ps2857TerminalWriteParsedCount??0,"
                + "terminalInputAcks:Number(shell?.dataset.sshTerminalInputAcks??0),"
                + "terminalInputPending:Number(shell?.dataset.sshTerminalInputPending??0),"
                + "terminalInputChunks:chunks.slice(" + inputChunkStart + "),"
                + "runtimeGeometry:window.__ps2875TerminalRuntimeGeometry??null,"
                + "expectedReadyMarker:" + JSONObject.quote(readyMarker) + ","
                + "expectedDoneMarker:" + JSONObject.quote(doneMarker) + "});})()");
        state.put("terminalEvidence", new JSONObject(terminalEvidence(readyMarker, doneMarker)))
                .put("geometry", captureGeometry("dictation-post-stop-done-marker-failure"));
        byte[] json = state.toString(2).getBytes(StandardCharsets.UTF_8);
        emitArtifact("fastkeys-dictation-post-stop-marker-failure.json", json);
        Log.i("PS2884DictationFailure", state.toString());
    }

    private void captureCurrentDeviceScreenshotDirect(String name) throws Exception {
        Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull("full-device failure screenshot must be available for " + name, screenshot);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        boolean compressed = screenshot.compress(Bitmap.CompressFormat.PNG, 100, output);
        screenshot.recycle();
        assertTrue("full-device failure screenshot must be non-empty for " + name,
                compressed && output.size() >= 1_024);
        emitArtifact(name, output.toByteArray());
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
                + "const style=getComputedStyle(content),axis=style.overflowX==='auto'||style.overflowX==='scroll'?'horizontal':'vertical';"
                + "const bounds=b=>({left:b.left,right:b.right,top:b.top,bottom:b.bottom,width:b.width,height:b.height});"
                + "return JSON.stringify({missing:false,width:r.width,height:r.height,targetBounds:bounds(r),contentBounds:bounds(c),"
                + "axis,snapType:style.scrollSnapType,scrollLeft:content.scrollLeft,scrollTop:content.scrollTop,"
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
        boolean horizontal = "horizontal".equals(target.getString("axis"));
        assertTrue("catalog swipe viewport must snap rows to keep 48dp keys aligned: " + target,
                target.getString("snapType").contains(horizontal ? "x mandatory" : "y mandatory"));
        if (!horizontal) {
            double scrollTop = target.getDouble("scrollTop");
            double snappedScrollTop = Math.rint(scrollTop / 48.0) * 48.0;
            assertTrue("physical Ctrl swipes must settle on a complete 48dp row: " + target,
                    Math.abs(scrollTop - snappedScrollTop) <= 0.75);
        }
        return target;
    }

    private void sendControl(String keyId, boolean hold) throws Exception {
        swipeFastKeyIntoView("[data-key-id='" + keyId + "']");
        int previous = hotkeyWrites().length();
        if (hold) longPressDomCenter("[data-key-id='" + keyId + "']", 700);
        else tapDomCenter("[data-key-id='" + keyId + "']");
        awaitHotkeyWrites(previous + 1);
        awaitJsTrue("document.activeElement?.classList.contains('xterm-helper-textarea') === true", 3_000);
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
        if (".mobile-hotkeys__main-keys".equals(containerSelector)) {
            assertMainCatalogEndpointReachability(containerSelector, keyIds, reachable);
        }
        assertEquals("physical catalog reachability swipes must not activate keys or write to the PTY",
                writesBeforeReachabilitySwipes, hotkeyWrites().length());
        return reachable;
    }

    private void assertMainCatalogEndpointReachability(
            String selector, JSONArray keyIds, JSONArray reachable) throws Exception {
        JSONObject endpoint = evalJson("(() => {const node=document.querySelector(" + JSONObject.quote(selector) + ");"
                + "if(!node)return JSON.stringify({missing:true});"
                + "const c=node.getBoundingClientRect(),v=window.visualViewport;"
                + "const bounds=r=>({left:r.left,right:r.right,top:r.top,bottom:r.bottom,width:r.width,height:r.height});"
                + "const keys=Array.from(node.querySelectorAll('button[data-key-id]')).map(key=>{const r=key.getBoundingClientRect();"
                + "const intersectsContent=r.right>c.left&&r.left<c.right&&r.bottom>c.top&&r.top<c.bottom;"
                + "const insideContent=r.left>=c.left&&r.right<=c.right&&r.top>=c.top&&r.bottom<=c.bottom;"
                + "return {keyId:key.dataset.keyId,bounds:bounds(r),intersectsContent,insideContent,width:r.width,height:r.height,"
                + "insideViewport:r.left>=0&&r.top>=0&&r.right<=innerWidth&&r.bottom<=(v?.height??innerHeight)};});"
                + "return JSON.stringify({missing:false,scrollLeft:node.scrollLeft,contentBounds:bounds(c),keys});})()");
        assertTrue("the Main rail endpoint must be measurable after physical swipes: " + endpoint,
                !endpoint.optBoolean("missing", true));
        assertEquals("the Main rail endpoint must measure every catalog key", keyIds.length(),
                endpoint.getJSONArray("keys").length());
        double scrollLeft = endpoint.getDouble("scrollLeft");
        double snappedScrollLeft = Math.rint(scrollLeft / 56.0) * 56.0;
        assertTrue("Main rail end clamp must stay on the 56dp key-slot boundary: " + endpoint,
                Math.abs(scrollLeft - snappedScrollLeft) <= 0.75);
        JSONObject contentBounds = endpoint.getJSONObject("contentBounds");
        JSONArray endpointKeys = endpoint.getJSONArray("keys");
        for (int index = 0; index < endpointKeys.length(); index += 1) {
            JSONObject key = endpointKeys.getJSONObject(index);
            String keyId = key.getString("keyId");
            assertEquals("Main endpoint keys must keep their shared-core order", keyIds.getString(index), keyId);
            assertTrue("every Main endpoint target that intersects the rail must remain a full 48dp target: "
                            + keyId + "; " + endpoint,
                    !key.getBoolean("intersectsContent") || (key.getBoolean("insideContent")
                            && key.getDouble("width") >= 47.9 && key.getDouble("height") >= 47.9
                            && key.getBoolean("insideViewport")));
            reachable.getJSONObject(index)
                    .put("endpointBounds", key.getJSONObject("bounds"))
                    .put("endpointContentBounds", contentBounds)
                    .put("endpointIntersectsContent", key.getBoolean("intersectsContent"))
                    .put("endpointInsideContent", key.getBoolean("insideContent"))
                    .put("endpointInsideViewport", key.getBoolean("insideViewport"))
                    .put("endpointScrollLeft", scrollLeft);
        }
    }

    private void scrollCatalogToStart(String selector) throws Exception {
        evalString("(() => {const node=document.querySelector(" + JSONObject.quote(selector) + ");"
                + "if(!node)throw new Error('missing catalog scroller '+" + JSONObject.quote(selector) + ");"
                + "node.scrollTop=0;node.scrollLeft=0;return String(node.scrollTop);})()");
        awaitRenderedFrame();
        awaitJsTrue("(() => {const node=document.querySelector(" + JSONObject.quote(selector) + ");"
                + "const first=node?.querySelector('button[data-key-id]'),c=node?.getBoundingClientRect(),r=first?.getBoundingClientRect();"
                + "return !!node&&!!c&&!!r&&node.scrollTop<=0.5&&node.scrollLeft<=8.5"
                + "&&r.left>=c.left-0.5&&r.right<=c.right+0.5&&r.top>=c.top-0.5&&r.bottom<=c.bottom+0.5;})()");
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
                startY = towardEnd ? container.getDouble("bottom") - 2 : container.getDouble("top") + 2;
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
                + "const horizontal=style.overflowX==='auto'||style.overflowX==='scroll',y=c.top+c.height/2;"
                + "const verticalAnchor=(()=>{for(let x=c.left+24;x<c.right-16;x+=4){const hits=[c.top+2,y,c.bottom-2].map(py=>document.elementFromPoint(x,py));"
                + "const insideScroller=hits.every(hit=>!!hit&&(hit===container||container.contains(hit)));"
                + "if(insideScroller&&hits.every(hit=>!hit.closest('button')))return {x,y,insideScroller,clearOfButtons:true};}"
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
                + "const hit=document.elementFromPoint(x,y);return {label:n.getAttribute('aria-label')||'',title:n.title||'',"
                + "top:r.top,bottom:r.bottom,left:r.left,right:r.right,width:r.width,height:r.height,disabled:!!n.disabled,"
                + "visibleWidthInKeybar,visibleHeightInKeybar,"
                + "pressed:n.getAttribute('aria-pressed')==='true',checked:n.getAttribute('aria-checked')==='true',"
                + "micState:n.dataset.micState||'',"
                + "hitTarget:!!hit&&(hit===n||n.contains(hit)),"
                + "insideViewport:r.top>=0&&r.left>=0&&r.bottom<=(v?.height??innerHeight)+0.5&&r.right<=innerWidth+0.5};};"
                + "const shell=document.querySelector('.app-shell');const slot=document.querySelector('[data-testid=terminal-slot]');"
                + "const tray=document.querySelector('[data-testid=mobile-hotkeys]');const trayRect=rect('[data-testid=mobile-hotkeys]');"
                + "const slotRect=rect('[data-testid=terminal-slot]');const terminalRect=rect('.terminal-viewport');"
                + "const terminalGridRect=rect('.terminal-viewport');const terminalXtermSurface=rect('.terminal-viewport > .xterm');"
                + "const describeTerminalNode=node=>{if(!node)return null;const r=node.getBoundingClientRect(),s=getComputedStyle(node),op=node.offsetParent;"
                + "const opRect=op?.getBoundingClientRect();return {tag:node.tagName.toLowerCase(),className:String(node.className||''),"
                + "rect:{top:r.top,bottom:r.bottom,left:r.left,right:r.right,width:r.width,height:r.height},position:s.position,"
                + "top:s.top,bottom:s.bottom,left:s.left,right:s.right,transform:s.transform,translate:s.translate,"
                + "marginTop:s.marginTop,marginBottom:s.marginBottom,display:s.display,overflow:s.overflow,height:s.height,"
                + "offsetTop:node.offsetTop,offsetLeft:node.offsetLeft,scrollTop:node.scrollTop,scrollLeft:node.scrollLeft,"
                + "scrollHeight:node.scrollHeight,clientHeight:node.clientHeight,"
                + "offsetParent:op?{tag:op.tagName.toLowerCase(),className:String(op.className||''),top:opRect?.top??null,bottom:opRect?.bottom??null}:null};};"
                + "const terminalXtermNode=document.querySelector('.terminal-viewport > .xterm');"
                + "const terminalXtermMetrics=describeTerminalNode(terminalXtermNode);const terminalXtermAncestors=[];"
                + "for(let node=terminalXtermNode;node&&node instanceof HTMLElement&&terminalXtermAncestors.length<5;node=node.parentElement){"
                + "terminalXtermAncestors.push(describeTerminalNode(node));if(node.matches('.terminal-viewport'))break;}"
                + "const terminalXtermChildSurfaces=terminalXtermNode?['.xterm-viewport','.xterm-screen','.xterm-screen canvas'].map(selector=>"
                + "describeTerminalNode(terminalXtermNode.querySelector(selector))):[];"
                + "const runtimeGeometry=window.__ps2875TerminalRuntimeGeometry??null;"
                + "const visibleTerminalRows=runtimeGeometry&&terminalXtermSurface&&runtimeGeometry.cellHeight>0?Math.floor((terminalXtermSurface.height-8)/runtimeGeometry.cellHeight):0;"
                + "const catalogSheetNode=document.querySelector('[data-testid=mobile-hotkeys-sheet]');"
                + "const catalogSheetRole=catalogSheetNode?.getAttribute('role')??'';"
                + "const catalogSheet=rect('[data-testid=mobile-hotkeys-sheet]');"
                + "const catalogSurfaceStyle=catalogSheetNode?(()=>{const s=getComputedStyle(catalogSheetNode);return {backgroundColor:s.backgroundColor,borderRadius:s.borderRadius,"
                + "borderTopWidth:s.borderTopWidth,borderRightWidth:s.borderRightWidth,borderBottomWidth:s.borderBottomWidth,borderLeftWidth:s.borderLeftWidth,boxShadow:s.boxShadow};})():null;"
                + "const promptComposerLauncherNode=document.querySelector('[data-testid=prompt-composer-launcher]');"
                + "const promptDictationLauncherNode=document.querySelector('[data-testid=prompt-dictation-launcher]');"
                + "const promptInputGroupNode=document.querySelector('[data-testid=mobile-hotkeys-prompt-group]');"
                + "const terminalControlsGroupNode=document.querySelector('[data-testid=mobile-hotkeys-terminal-group]');"
                + "const groupInfo=node=>{if(!node)return null;const r=node.getBoundingClientRect();return {role:node.getAttribute('role')??'',label:node.getAttribute('aria-label')??'',"
                + "left:r.left,right:r.right,top:r.top,bottom:r.bottom,width:r.width,height:r.height};};"
                + "const promptComposerIconNode=promptComposerLauncherNode?.querySelector('svg');"
                + "const measuredBounds=node=>{if(!node)return null;const r=node.getBoundingClientRect();"
                + "return {top:r.top,bottom:r.bottom,left:r.left,right:r.right,width:r.width,height:r.height};};"
                + "const promptComposerBounds=measuredBounds(promptComposerLauncherNode);"
                + "const promptComposerIconBounds=measuredBounds(promptComposerIconNode);"
                + "const promptComposerIconStyle=promptComposerIconNode?getComputedStyle(promptComposerIconNode):null;"
                + "const insideBounds=(outer,inner)=>!!outer&&!!inner&&inner.left>=outer.left-0.5&&inner.right<=outer.right+0.5"
                + "&&inner.top>=outer.top-0.5&&inner.bottom<=outer.bottom+0.5;"
                + "const promptComposerLauncher=promptComposerLauncherNode?{...target(promptComposerLauncherNode),"
                + "iconBounds:promptComposerIconBounds,"
                + "visibleText:promptComposerLauncherNode.innerText.trim(),"
                + "title:promptComposerLauncherNode.getAttribute('title')??'',"
                + "iconComputedWidth:promptComposerIconNode?getComputedStyle(promptComposerIconNode).width:'',"
                + "iconComputedHeight:promptComposerIconNode?getComputedStyle(promptComposerIconNode).height:'',"
                + "iconVisible:!!promptComposerIconNode&&promptComposerIconStyle?.display!=='none'"
                + "&&promptComposerIconStyle?.visibility!=='hidden'&&Number.parseFloat(promptComposerIconStyle?.opacity??'1')>0"
                + "&&!!promptComposerIconBounds&&promptComposerIconBounds.width>0&&promptComposerIconBounds.height>0,"
                + "iconInside:insideBounds(promptComposerBounds,promptComposerIconBounds)}:null;"
                + "const promptDictationLauncher=promptDictationLauncherNode?{...target(promptDictationLauncherNode),"
                + "visibleText:promptDictationLauncherNode.innerText.trim(),iconCount:promptDictationLauncherNode.querySelectorAll('svg').length}:null;"
                + "const promptInputGroup=groupInfo(promptInputGroupNode),terminalControlsGroup=groupInfo(terminalControlsGroupNode);"
                + "const pageActionNode=tray?.querySelector(tray.dataset.palettePage==='ctrl'?'[data-testid=mobile-hotkeys-back-main-page]':'[data-testid=mobile-hotkeys-open-ctrl-page]');"
                + "const pageAction=pageActionNode?target(pageActionNode):null;"
                + "const catalogTabsNode=tray?.querySelector('.mobile-hotkeys__page-tabs');"
                + "const catalogTabsBounds=catalogTabsNode?.getBoundingClientRect();"
                + "const catalogTabs=Array.from(catalogTabsNode?.querySelectorAll('button')??[]).map(node=>{const t=target(node),r=node.getBoundingClientRect();"
                + "return {...t,selected:node.getAttribute('aria-pressed')==='true',insideCatalogSheet:!!catalogSheet&&r.left>=catalogSheet.left-0.5&&r.right<=catalogSheet.right+0.5"
                + "&&r.top>=catalogSheet.top-0.5&&r.bottom<=catalogSheet.bottom+0.5};});"
                + "const catalogTitleNode=tray?.querySelector('[data-testid=mobile-hotkeys-sheet-title]');"
                + "const catalogTitleBounds=catalogTitleNode?.getBoundingClientRect();"
                + "const catalogTitle=catalogTitleNode&&catalogTitleBounds?{text:catalogTitleNode.textContent.trim(),top:catalogTitleBounds.top,bottom:catalogTitleBounds.bottom,"
                + "left:catalogTitleBounds.left,right:catalogTitleBounds.right,width:catalogTitleBounds.width,height:catalogTitleBounds.height,"
                + "fits:catalogTitleNode.scrollWidth<=catalogTitleNode.clientWidth+1}:null;"
                + "const catalogScroller=tray?.querySelector('.mobile-hotkeys__main-keys,.mobile-hotkeys__ctrl-grid');"
                + "const catalogScrollStyle=catalogScroller?getComputedStyle(catalogScroller):null;"
                + "const catalogKeyNode=catalogScroller?.querySelector('button[data-key-id]');"
                + "const catalogKeyStyle=catalogKeyNode?(()=>{const s=getComputedStyle(catalogKeyNode);return {backgroundColor:s.backgroundColor,borderRadius:s.borderRadius,borderColor:s.borderColor,fontSize:s.fontSize,fontToken:getComputedStyle(document.documentElement).getPropertyValue('--fs-300').trim(),fontFamily:s.fontFamily};})():null;"
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
                + "scrollSnapType:catalogScrollStyle?.scrollSnapType??'',"
                + "axis:catalogScrollStyle?.overflowY==='auto'?'vertical':'horizontal'}:null;"
                + "const mainCatalogNode=tray?.querySelector('.mobile-hotkeys__main-keys');"
                + "const mainRowNodes=Array.from(mainCatalogNode?.querySelectorAll('.mobile-hotkeys__main-row')??[]);"
                + "const mainKeyRects=Array.from(mainCatalogNode?.querySelectorAll('button[data-key-id]')??[]).map(node=>node.getBoundingClientRect());"
                + "const mainRowWidths=mainRowNodes.map(row=>{const top=row.getBoundingClientRect().top,keys=mainKeyRects.filter(r=>Math.abs(r.top-top)<1);"
                + "return keys.length?Math.max(...keys.map(r=>r.right))-Math.min(...keys.map(r=>r.left)):0;});"
                + "const mainKeyLayout={rowCount:mainRowNodes.length,columnGap:mainRowNodes[0]?getComputedStyle(mainRowNodes[0]).columnGap:'',"
                + "maxRowWidth:mainRowWidths.length?Math.max(...mainRowWidths):0};"
                + "const overlaps=(a,b)=>!!a&&!!b&&a.left<b.right&&a.right>b.left&&a.top<b.bottom&&a.bottom>b.top;"
                + "const sheetInside=pageActionNode&&catalogSheet?(()=>{const a=pageActionNode.getBoundingClientRect();return a.left>=catalogSheet.left-0.5&&a.right<=catalogSheet.right+0.5&&a.top>=catalogSheet.top-0.5&&a.bottom<=catalogSheet.bottom+0.5;})():false;"
                + "const composerRect=rect('.composer-panel');"
                + "const composerDraftRect=rect('[data-testid=prompt-draft]');"
                + "const composerActionRow=rect('[data-testid=composer-actions]');"
                + "const composerPanelNode=document.querySelector('.composer-panel');const composerPanelBounds=composerPanelNode?.getBoundingClientRect();"
                + "const expandedInputSurfaceCount=[document.querySelector('[data-testid=prompt-composer]'),document.querySelector('[data-testid=mobile-hotkeys-sheet]')].filter(Boolean).length;"
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
                + "const inlineDictationMicIcon=inlineDictationMicNode?.querySelector('svg');"
                + "const inlineDictationMicIconStyle=inlineDictationMicIcon?getComputedStyle(inlineDictationMicIcon):null;"
                + "const inlineDictationMicStyle=inlineDictationMicNode?getComputedStyle(inlineDictationMicNode):null;"
                + "const inlineDictationMicCaption=inlineDictationMicNode?.querySelector('[data-testid=inline-dictation-dock-label]');"
                + "const inlineDictationMicCaptionStyle=inlineDictationMicCaption?getComputedStyle(inlineDictationMicCaption):null;"
                + "const contrastRatio=(foreground,background)=>{const luminance=color=>{const rgb=color.match(/[\\d.]+/g)?.slice(0,3).map(Number);"
                + "if(!rgb||rgb.length<3)return 0;const linear=rgb.map(value=>{const channel=value/255;return channel<=.04045?channel/12.92:Math.pow((channel+.055)/1.055,2.4);});"
                + "return .2126*linear[0]+.7152*linear[1]+.0722*linear[2];};const [a,b]=[luminance(foreground),luminance(background)].sort((x,y)=>y-x);return (a+.05)/(b+.05);};"
                + "const inlineDictationMicIconRect=inlineDictationMicIcon?.getBoundingClientRect();"
                + "const inlineDictationDestination=inlineDictationMicNode?.parentElement?.querySelector('[data-testid=inline-dictation-destination]');"
                + "const inlineDictationDestinationLabels=inlineDictationDestination?Array.from(inlineDictationDestination.children).map(node=>node.textContent.trim()):[];"
                + "const inlineDictationMic=inlineDictationMicNode?{...target(inlineDictationMicNode),"
                + "title:inlineDictationMicNode.getAttribute('title')??'',"
                + "visibleText:inlineDictationMicNode.innerText.trim(),"
                + "captionContrastRatio:inlineDictationMicCaptionStyle&&inlineDictationMicStyle?contrastRatio(inlineDictationMicCaptionStyle.color,inlineDictationMicStyle.backgroundColor):0,"
                + "iconContrastRatio:inlineDictationMicIconStyle&&inlineDictationMicStyle?contrastRatio(inlineDictationMicIconStyle.color,inlineDictationMicStyle.backgroundColor):0,"
                + "destinationLabels:inlineDictationDestinationLabels,destinationLabelBounds:rect('[data-testid=inline-dictation-destination]'),"
                + "iconBounds:inlineDictationMicIconRect?{top:inlineDictationMicIconRect.top,bottom:inlineDictationMicIconRect.bottom,left:inlineDictationMicIconRect.left,right:inlineDictationMicIconRect.right,width:inlineDictationMicIconRect.width,height:inlineDictationMicIconRect.height}:null,"
                + "iconVisible:!!inlineDictationMicIcon&&inlineDictationMicIconStyle?.display!=='none'"
                + "&&inlineDictationMicIconStyle?.visibility!=='hidden'&&Number.parseFloat(inlineDictationMicIconStyle?.opacity??'1')>0"
                + "&&inlineDictationMicIcon.getBoundingClientRect().width>0&&inlineDictationMicIcon.getBoundingClientRect().height>0,"
                + "iconPaths:Array.from(inlineDictationMicIcon?.querySelectorAll('path')??[]).map(path=>path.getAttribute('d'))}:null;"
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
                + "const inlineDictationStatusMetrics=inlineDictationStatusNode&&inlineDictationStatusStyle?{height:inlineDictationStatusNode.getBoundingClientRect().height,"
                + "fontSize:parseFloat(inlineDictationStatusStyle.fontSize),lineHeight:parseFloat(inlineDictationStatusStyle.lineHeight),"
                + "paddingTop:parseFloat(inlineDictationStatusStyle.paddingTop),paddingBottom:parseFloat(inlineDictationStatusStyle.paddingBottom)}:null;"
                + "const inlineDictationElapsedNode=document.querySelector('[data-testid=inline-dictation-elapsed]');"
                + "const inlineDictationWaveformNode=document.querySelector('[data-testid=inline-dictation-waveform]');"
                + "const fitEvents=window.__ps2884ResizeFitEvents??[],ackEvents=window.__ps2884ResizeAckEvents??[];"
                + "const fitCursor=window.__ps2884ResizeFitTraceCursor??0,ackCursor=window.__ps2884ResizeAckTraceCursor??0;"
                + "const fitEventsSince=fitEvents.slice(fitCursor),ackEventsSince=ackEvents.slice(ackCursor);"
                + "window.__ps2884ResizeFitTraceCursor=fitEvents.length;window.__ps2884ResizeAckTraceCursor=ackEvents.length;"
                + "const inlineDictationStatusOneLine=!!inlineDictationStatusNode&&inlineDictationStatusStyle?.whiteSpace==='nowrap'"
                + "&&inlineDictationStatusNode.clientHeight>0&&inlineDictationStatusNode.scrollHeight<=inlineDictationStatusNode.clientHeight+1;"
                + "const keys=Array.from(document.querySelectorAll('[data-testid=mobile-hotkeys] .mobile-hotkeys__navigation button,"
                + "[data-testid=mobile-hotkeys-launcher]')).map(target);"
                + "const stableDockControls=['[data-testid=prompt-composer-launcher]','[data-key-id=arrow-up]',"
                + "'[data-key-id=arrow-down]','[data-key-id=enter]','[data-testid=mobile-hotkeys-launcher]',"
                + "'[data-testid=inline-dictation-toggle]'].map(selector=>document.querySelector(selector)).filter(Boolean).map(node=>({"
                + "...target(node),visibleText:node.innerText.trim(),iconCount:node.querySelectorAll('svg').length}));"
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
                + "href:location.href,route:shell?.dataset.route||'',sshPhase:shell?.dataset.sshPhase||'',homeSurface:shell?.dataset.homeSurface||'',"
                + "sshAttachEpoch:Number(shell?.dataset.sshAttachEpoch??-1),"
                + "terminalPanel:terminalPanelRect,terminalSlot:terminalSlotRect,terminalSlotInsideTerminalPanel,terminalViewport:terminalRect,terminalGridViewport:terminalGridRect,terminalXtermSurface,"
                + "terminalXtermMetrics,terminalXtermAncestors,terminalXtermChildSurfaces,"
                + "terminalCanvas:slotRect,terminalCanvasEndsAtDock:!!slotRect&&!!trayRect&&Math.abs(slotRect.bottom-trayRect.bottom)<=1,"
                + "terminalCanvasStyle:(()=>{const canvas=document.querySelector('.terminal-slot'),panel=document.querySelector('.terminal-panel');"
                + "if(!canvas||!panel)return null;const cs=getComputedStyle(canvas),ps=getComputedStyle(panel);return {borderRadius:cs.borderRadius,borderWidth:cs.borderWidth,"
                + "backgroundColor:cs.backgroundColor,panelBackgroundColor:ps.backgroundColor};})(),"
                + "catalogScrollerInsideSheet,catalogScrollerBounds:catalogScrollerRect?{top:catalogScrollerRect.top,bottom:catalogScrollerRect.bottom,"
                + "left:catalogScrollerRect.left,right:catalogScrollerRect.right,width:catalogScrollerRect.width,height:catalogScrollerRect.height}:null,"
                + "terminalViewportDockCapPx:Number(slot?.dataset.terminalViewportDockCap??0),"
                + "terminalHotkeysDockHeightPx:Number(slot?.dataset.terminalHotkeysDockHeight??0),"
                + "mobileHotkeysClassName:tray?String(tray.className):'',"
                + "mobileHotkeys:trayRect,navigationTargets:keys,stableDockControls,promptComposerLauncher,promptDictationLauncher,promptInputGroup,terminalControlsGroup,enterDivider,persistentRowMetrics,hotkeyControls,"
                + "catalogSheet,catalogSheetRole,catalogSheetModal:catalogSheetNode?.getAttribute('aria-modal')??null,catalogSurfaceStyle,"
                + "visibleTerminalRows,runtimeGeometry,"
                + "catalogSheetBelowTerminalViewport:!!catalogSheet&&!!terminalRect&&catalogSheet.top>=terminalRect.bottom,"
                + "catalogSheetIntersectsComposer:overlaps(catalogSheet,composerRect),catalogPageAction:pageAction?{...pageAction,insideCatalogSheet:!!sheetInside}:null,"
                + "catalogTabs,catalogTabList:rect('.mobile-hotkeys__page-tabs'),catalogKeyStyle,mainKeyLayout,"
                + "catalogTitle,catalogHeader:rect('.mobile-hotkeys__sheet-header'),"
                + "catalogHeaderControlsDoNotOverlap:!!catalogTitleBounds&&!!catalogTabsBounds&&(()=>{const a=catalogTabsBounds;"
                + "return !overlaps(catalogTitleBounds,a);})(),"
                + "catalogScrollerSelector:catalogScroller?.matches('.mobile-hotkeys__ctrl-grid')?'.mobile-hotkeys__ctrl-grid':"
                + "catalogScroller?.matches('.mobile-hotkeys__main-keys')?'.mobile-hotkeys__main-keys':null,catalogScrollMetrics,"
                + "mainCatalog:rect('.mobile-hotkeys__main-keys'),ctrlCatalog:rect('.mobile-hotkeys__ctrl-grid'),"
                + "composerPanel:composerRect,composerDraft:composerDraftRect,composerActionRow,composerActions,"
                + "expandedInputSurfaceCount,"
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
                + "inlineDictationElapsed:inlineDictationElapsedNode?.textContent.trim()??'',"
                + "inlineDictationWaveformBars:inlineDictationWaveformNode?.querySelectorAll('i').length??0,"
                + "inlineDictationPreview:inlineDictationStatusNode?.querySelector('[data-testid=inline-dictation-preview]')?.textContent.trim()??'',"
                + "inlineDictationStatusMetrics,"
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
                + "top:r.top,bottom:r.bottom,height:r.height,minHeight:style.minHeight,flex:style.flex,padding:style.padding,overflow:style.overflow}];})),"
                + "fastKeysTray:tray&&trayRect&&slotRect&&terminalRect?{bounds:trayRect,insideSlot:trayRect.top>=slotRect.top-0.5"
                + "&&trayRect.left>=slotRect.left-0.5&&trayRect.bottom<=slotRect.bottom+0.5&&trayRect.right<=slotRect.right+0.5,"
                + "insideTerminalPanel:trayRect.top>=rect('.terminal-panel').top"
                + "&&trayRect.bottom<=rect('.terminal-panel').bottom,"
                + "belowTerminalViewport:trayRect.top-terminalRect.bottom>=-" + TERMINAL_VIEWPORT_ROUNDING_EPSILON_CSS_PX
                + ",intersectsTerminalViewport:trayRect.top-terminalRect.bottom<-" + TERMINAL_VIEWPORT_ROUNDING_EPSILON_CSS_PX
                + "&&trayRect.bottom>terminalRect.top,intersectsComposerPanel:!!composerRect&&trayRect.left<composerRect.right"
                + "&&trayRect.right>composerRect.left&&trayRect.top<composerRect.bottom&&trayRect.bottom>composerRect.top}:null,"
                + "resizeAcks:Number(shell?.dataset.sshTerminalResizeAcks??0),resizePending:Number(shell?.dataset.sshTerminalResizePending??0),"
                + "resizeFailures:Number(shell?.dataset.sshTerminalResizeFailures??0),hotkeyWrites:window.__ps2884HotkeyWrites??[],"
                + "terminalInputAcks:Number(shell?.dataset.sshTerminalInputAcks??0),"
                + "resizeTraceMarker:window.__ps2884ResizeFitMarker??'',resizeFitEvents:fitEventsSince,resizeAckEvents:ackEventsSince,"
                + "resizeStatus:document.querySelector('[data-testid=terminal-resize-status]')?.textContent.trim()??'',"
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

    private JSONObject captureDictationCheckpoint(String stage) throws Exception {
        JSONObject state = evalJson("(() => {const shell=document.querySelector('.app-shell');"
                + "const bar=document.querySelector('[data-testid=inline-dictation-bar]');"
                + "const status=document.querySelector('[data-testid=inline-dictation-status]');"
                + "const toggle=document.querySelector('[data-testid=inline-dictation-toggle]');"
                + "const active=document.activeElement;return JSON.stringify({stage:" + JSONObject.quote(stage)
                + ",href:location.href,route:shell?.dataset.route??null,homeSurface:shell?.dataset.homeSurface??null,"
                + "sshPhase:shell?.dataset.sshPhase??null,sshAttachEpoch:shell?.dataset.sshAttachEpoch??null,"
                + "selectedSession:shell?.dataset.sshSelectedSession??null,keyboardVisible:shell?.dataset.keyboardVisible??null,"
                + "keyboardComposerMode:shell?.dataset.keyboardComposerMode??null,terminalViewportFocused:shell?.dataset.terminalViewportFocused??null,"
                + "activeElement:active?.outerHTML?.slice(0,240)??null,inlineDictationBarPresent:!!bar,"
                + "dictationPhase:bar?.dataset.phase??null,dictationTone:bar?.dataset.dictationTone??null,"
                + "dictationTargetKey:bar?.dataset.targetKey??null,dictationStatusPresent:!!status,"
                + "dictationStatusText:status?.textContent?.trim()??null,"
                + "dictationPreview:status?.querySelector('[data-testid=inline-dictation-preview]')?.textContent?.trim()??null,"
                + "dictationTogglePresent:!!toggle,dictationToggleDisabled:toggle?.disabled??null,"
                + "resizeAcks:Number(shell?.dataset.sshTerminalResizeAcks??0),resizePending:Number(shell?.dataset.sshTerminalResizePending??0),"
                + "resizeFailures:Number(shell?.dataset.sshTerminalResizeFailures??0),resizeStatus:document.querySelector('[data-testid=terminal-resize-status]')?.textContent?.trim()??null,"
                + "jsDiagnostics:window.__ps2884JsDiagnostics?.events?.slice(-20)??[]});})()")
                .put("nativeIme", readNativeImeState());
        Log.i("PS2884Geometry", "CHECKPOINT " + artifactRunId + " " + stage + " " + state);
        return state;
    }

    private JSONObject readNativeImeState() throws Exception {
        return runOnUiThread("read native IME state", () -> {
            View decor = packagedActivity.getWindow().getDecorView();
            WindowInsets insets = decor.getRootWindowInsets();
            WebView webView = packagedWebView;
            float density = packagedActivity.getResources().getDisplayMetrics().density;
            Insets ime = insets == null ? Insets.NONE : insets.getInsets(WindowInsets.Type.ime());
            Insets bars = insets == null ? Insets.NONE : insets.getInsets(WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout());
            int[] location = new int[2];
            if (webView != null) webView.getLocationOnScreen(location);
            return new JSONObject()
                    .put("visible", insets != null && insets.isVisible(WindowInsets.Type.ime()))
                    .put("imeBottomDp", ime.bottom / density)
                    .put("statusTopDp", bars.top / density)
                    .put("density", density)
                    .put("webViewScreenX", location[0])
                    .put("webViewScreenY", location[1])
                    .put("webViewWidthPx", webView == null ? 0 : webView.getWidth())
                    .put("webViewHeightPx", webView == null ? 0 : webView.getHeight());
        });
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
        JSONObject composeLauncher = geometry.optJSONObject("promptComposerLauncher");
        assertNotNull("the Android toolbar must keep the Prompt composer one tap away", composeLauncher);
        assertTrue("prompt composer launcher must remain a measured 48dp hit target above the IME: " + composeLauncher,
                composeLauncher.getDouble("width") >= 47.9 && composeLauncher.getDouble("height") >= 47.9
                        && composeLauncher.getDouble("visibleWidthInKeybar") >= 47.9
                        && composeLauncher.getDouble("visibleHeightInKeybar") >= 47.9
                        && composeLauncher.getBoolean("insideViewport") && composeLauncher.getBoolean("hitTarget")
                        && !composeLauncher.getBoolean("disabled")
                        && "Open prompt composer to type or dictate a prompt".equals(composeLauncher.getString("label")));
        JSONObject promptIconBounds = composeLauncher.optJSONObject("iconBounds");
        assertNotNull("Prompt icon must expose its computed SVG bounds", promptIconBounds);
        assertEquals("Compose launcher exposes a clear accessible name", "Open prompt composer to type or dictate a prompt",
                composeLauncher.getString("label"));
        assertEquals("Compose launcher title matches its accessible name", "Open prompt composer to type or dictate a prompt",
                composeLauncher.getString("title"));
        assertEquals("Compose launcher visibly identifies its destination", "Prompt",
                composeLauncher.getString("visibleText"));
        assertTrue("Compose icon must be visible, 20px, and inside its 48dp launcher: " + composeLauncher,
                composeLauncher.getBoolean("iconVisible")
                        && "20px".equals(composeLauncher.getString("iconComputedWidth"))
                        && "20px".equals(composeLauncher.getString("iconComputedHeight"))
                        && promptIconBounds.getDouble("width") >= 19.5
                        && promptIconBounds.getDouble("height") >= 19.5
                        && promptIconBounds.getDouble("left") >= composeLauncher.getDouble("left")
                        && promptIconBounds.getDouble("right") <= composeLauncher.getDouble("right")
                        && promptIconBounds.getDouble("top") >= composeLauncher.getDouble("top")
                        && promptIconBounds.getDouble("bottom") <= composeLauncher.getDouble("bottom")
                        && composeLauncher.getBoolean("iconInside"));
        assertTrue("prompt dictation is available from inside the composer, not as a dock shortcut",
                geometry.isNull("promptDictationLauncher"));
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
        assertEquals("persistent row keeps navigation, More keys, and the inline terminal mic reachable",
                expected, labels);
        assertTrue("keyboard geometry must confirm native IME visibility", geometry.getJSONObject("androidIme").getBoolean("visible"));
        assertTrue("keyboard geometry must include a positive native IME inset", geometry.getJSONObject("androidIme").getDouble("imeBottomDp") > 0);
    }

    private void assertDockDestinationLabels(JSONObject geometry) throws Exception {
        JSONArray controls = geometry.getJSONArray("stableDockControls");
        assertEquals("the mobile dock keeps one Prompt action, terminal keys, and Dictate in grouped order", 6, controls.length());
        JSONObject promptGroup = geometry.optJSONObject("promptInputGroup");
        JSONObject terminalGroup = geometry.optJSONObject("terminalControlsGroup");
        assertNotNull("Prompt has a measured input group", promptGroup);
        assertNotNull("navigation keys, More, and Dictate share a measured terminal group", terminalGroup);
        assertEquals("prompt group has a distinct accessible name", "Prompt input", promptGroup.getString("label"));
        assertEquals("prompt controls use group semantics", "group", promptGroup.getString("role"));
        assertEquals("terminal group has a distinct accessible name", "Terminal controls", terminalGroup.getString("label"));
        assertEquals("terminal actions use group semantics", "group", terminalGroup.getString("role"));
        assertTrue("prompt group preserves a 48dp row height", Math.abs(promptGroup.getDouble("height") - 48.0) < 0.5);
        assertTrue("terminal group preserves a 48dp row height", Math.abs(terminalGroup.getDouble("height") - 48.0) < 0.5);
        String moreLabel = "closed".equals(geometry.getString("fastKeysPage")) ? "More terminal keys" : "Close terminal keys";
        List<String> expected = List.of("Open prompt composer to type or dictate a prompt",
                "Send Up arrow", "Send Down arrow", "Send Enter", moreLabel, "Dictate at terminal cursor");
        for (int index = 0; index < controls.length(); index += 1) {
            JSONObject control = controls.getJSONObject(index);
            assertEquals("dock control accessibility name follows its slot", expected.get(index), control.getString("label"));
            double expectedWidth = 48.0;
            assertTrue("dock controls retain their measured touch target: " + control,
                    Math.abs(control.getDouble("width") - expectedWidth) < 0.5
                            && Math.abs(control.getDouble("height") - 48.0) < 0.5
                            && control.getBoolean("insideViewport") && control.getBoolean("hitTarget"));
        }
        JSONObject promptButton = controls.getJSONObject(0);
        assertTrue("the single Prompt target remains inside its input group",
                promptGroup.getDouble("left") <= promptButton.getDouble("left") + 0.5
                        && promptGroup.getDouble("right") >= promptButton.getDouble("right") - 0.5
                        && promptGroup.getDouble("top") <= promptButton.getDouble("top") + 0.5
                        && promptGroup.getDouble("bottom") >= promptButton.getDouble("bottom") - 0.5);
        JSONObject upButton = controls.getJSONObject(1);
        JSONObject cursorButton = controls.getJSONObject(5);
        assertTrue("navigation and Dictate targets remain inside their shared terminal group",
                terminalGroup.getDouble("left") <= upButton.getDouble("left") + 0.5
                        && terminalGroup.getDouble("right") >= cursorButton.getDouble("right") - 0.5
                        && terminalGroup.getDouble("top") <= upButton.getDouble("top") + 0.5
                        && terminalGroup.getDouble("bottom") >= cursorButton.getDouble("bottom") - 0.5);
        assertEquals("Prompt launcher keeps a short visible destination label", "Prompt",
                controls.getJSONObject(0).getString("visibleText"));
        assertEquals("More keys stays a compact icon control", "", controls.getJSONObject(4).getString("visibleText"));
        assertEquals("terminal dictation remains visibly labeled in the persistent row", "Dictate",
                controls.getJSONObject(5).getString("visibleText"));
        assertTrue("there is no standalone Prompt Dictate action in the dock", geometry.isNull("promptDictationLauncher"));
        for (int index : List.of(0, 4, 5)) {
            assertEquals("Prompt, keys, and Dictate retain one visual icon", 1,
                    controls.getJSONObject(index).getInt("iconCount"));
        }
        JSONObject mic = geometry.getJSONObject("inlineDictationMic");
        assertEquals("Mic accessible action names its destination", "Dictate at terminal cursor", mic.getString("label"));
        assertEquals("terminal mic keeps its accessible title", "Dictate at terminal cursor", mic.getString("title"));
        assertEquals("idle terminal mic exposes Dictate inside its 48dp target", "Dictate", mic.getString("visibleText"));
        assertEquals("terminal mic does not add a visible caption beside its icon", new JSONArray(),
                mic.getJSONArray("destinationLabels"));
        assertTrue("terminal mic does not add a duplicate caption beside its icon", mic.isNull("destinationLabelBounds"));
        assertEquals("the idle mic remains the microphone icon", "[\"M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3z\",\"M19 10v2a7 7 0 0 1-14 0v-2\",\"M12 19v3M8 22h8\"]",
                mic.getJSONArray("iconPaths").toString());
    }

    private JSONObject verifyNarrowToolbarReachability() throws Exception {
        int writesBefore = hotkeyWrites().length();
        JSONObject result = new JSONObject(evalString("(() => {"
                + "const tray=document.querySelector('.mobile-hotkeys'),bar=document.querySelector('.mobile-hotkeys__bar');"
                + "if(!tray||!bar)return JSON.stringify({missing:true});"
                + "const savedStyle=tray.getAttribute('style'),savedScrollLeft=bar.scrollLeft;"
                + "tray.style.width='330px';tray.style.maxWidth='330px';tray.style.minWidth='0';"
                + "bar.scrollLeft=0;const clientWidth=bar.clientWidth,scrollWidth=bar.scrollWidth;"
                + "const b=bar.getBoundingClientRect(),clip={left:b.left+bar.clientLeft,top:b.top+bar.clientTop,"
                + "right:b.left+bar.clientLeft+bar.clientWidth,bottom:b.top+bar.clientTop+bar.clientHeight};"
                + "const buttons=Array.from(bar.querySelectorAll('button'));"
                + "const targets=buttons.map(node=>{const r=node.getBoundingClientRect();"
                + "const visibleWidth=Math.max(0,Math.min(r.right,clip.right)-Math.max(r.left,clip.left));"
                + "const visibleHeight=Math.max(0,Math.min(r.bottom,clip.bottom)-Math.max(r.top,clip.top));"
                + "const hit=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2),icon=node.querySelector('svg'),iconStyle=icon?getComputedStyle(icon):null;"
                + "return {label:node.getAttribute('aria-label')||node.textContent.trim(),title:node.getAttribute('title')||'',visibleText:node.innerText.trim(),"
                + "iconVisible:!!icon&&iconStyle?.display!=='none'&&iconStyle?.visibility!=='hidden'"
                + "&&Number.parseFloat(iconStyle?.opacity??'1')>0&&icon.getBoundingClientRect().width>0&&icon.getBoundingClientRect().height>0,"
                + "left:r.left,right:r.right,width:r.width,height:r.height,insideToolbar:r.left>=clip.left-0.5&&r.right<=clip.right+0.5,"
                + "visibleWidth,visibleHeight,hitTarget:!!hit&&(hit===node||node.contains(hit)),"
                + "disabled:!!node.disabled};});"
                + "const mic=bar.querySelector('[data-testid=inline-dictation-toggle]'),m=mic?.getBoundingClientRect();let finalMic=null;if(m){const hit=document.elementFromPoint(m.left+m.width/2,m.top+m.height/2),icon=mic.querySelector('svg'),iconStyle=icon?getComputedStyle(icon):null;"
                + "finalMic={left:m.left,right:m.right,top:m.top,bottom:m.bottom,width:m.width,height:m.height,"
                + "label:mic.getAttribute('aria-label')||'',title:mic.getAttribute('title')||'',"
                + "visibleText:mic.innerText.trim(),"
                + "iconVisible:!!icon&&iconStyle?.display!=='none'&&iconStyle?.visibility!=='hidden'"
                + "&&Number.parseFloat(iconStyle?.opacity??'1')>0&&icon.getBoundingClientRect().width>0&&icon.getBoundingClientRect().height>0,"
                + "insideToolbar:m.left>=clip.left-0.5&&m.right<=clip.right+0.5&&m.top>=clip.top-0.5&&m.bottom<=clip.bottom+0.5,"
                + "hitTarget:!!hit&&(hit===mic||mic.contains(hit))};}"
                + "const maxScrollLeft=bar.scrollWidth-bar.clientWidth;"
                + "if(savedStyle===null)tray.removeAttribute('style');else tray.setAttribute('style',savedStyle);bar.scrollLeft=savedScrollLeft;"
                + "return JSON.stringify({clientWidth,clientHeight:bar.clientHeight,scrollWidth,maxScrollLeft,scrollable:scrollWidth>clientWidth+1,targets,finalMic});"
                + "})()"));
        assertTrue("narrow-width probe must find the persistent toolbar: " + result, !result.optBoolean("missing"));
        assertEquals("narrow toolbar overflow state must match its measured scroll range: " + result,
                result.getDouble("scrollWidth") > result.getDouble("clientWidth") + 1,
                result.getBoolean("scrollable"));
        assertTrue("at 330px all persistent dock controls must fit without horizontal overflow: " + result,
                !result.getBoolean("scrollable")
                        && result.getDouble("scrollWidth") <= result.getDouble("clientWidth") + 1
                        && result.getDouble("maxScrollLeft") <= 1);
        JSONArray targets = result.getJSONArray("targets");
        assertEquals("narrow-width toolbar keeps Prompt, navigation, More keys, and terminal Dictate reachable", 6, targets.length());
        assertEquals("narrow dock exposes one Prompt entry and the terminal dictation control in accessible order",
                List.of("Open prompt composer to type or dictate a prompt", "Send Up arrow", "Send Down arrow",
                        "Send Enter", "More terminal keys", "Dictate at terminal cursor"),
                narrowToolbarLabels(targets));
        for (int index = 0; index < targets.length(); index += 1) {
            JSONObject target = targets.getJSONObject(index);
            double expectedWidth = 48.0;
            assertTrue("narrow toolbar target remains reachable at its measured width: " + target,
                    Math.abs(target.getDouble("width") - expectedWidth) < 0.5
                            && target.getDouble("height") >= 47.9
                            && target.getDouble("visibleWidth") >= 47.9 && target.getDouble("visibleHeight") >= 47.9
                            && target.getBoolean("hitTarget") && target.getBoolean("insideToolbar")
                            && !target.getBoolean("disabled"));
            assertTrue("narrow dock control title, when present, matches its accessible name: " + target,
                    target.getString("title").isEmpty() || target.getString("label").equals(target.getString("title")));
        }
        assertTrue("the Prompt icon remains visible at the 330px viewport", targets.getJSONObject(0).getBoolean("iconVisible"));
        JSONObject finalMic = result.getJSONObject("finalMic");
        assertTrue("narrow-width Dictate remains a fully visible 48dp hit target without scrolling: " + result,
                finalMic.getDouble("width") >= 47.9 && finalMic.getDouble("height") >= 47.9
                        && finalMic.getBoolean("insideToolbar") && finalMic.getBoolean("hitTarget")
                        && finalMic.getBoolean("iconVisible")
                        && "Dictate".equals(finalMic.getString("visibleText"))
                        && "Dictate at terminal cursor".equals(finalMic.getString("label"))
                        && finalMic.getString("label").equals(finalMic.getString("title")));
        int writesAfter = hotkeyWrites().length();
        assertEquals("testing the narrow toolbar must not write bytes to the PTY", writesBefore, writesAfter);
        result.put("ptyWritesBefore", writesBefore);
        result.put("ptyWritesAfter", writesAfter);
        return result;
    }

    private List<String> narrowToolbarLabels(JSONArray targets) throws Exception {
        List<String> labels = new ArrayList<>();
        for (int index = 0; index < targets.length(); index += 1) {
            labels.add(targets.getJSONObject(index).getString("label"));
        }
        return labels;
    }

    private void assertCatalogSheetGeometry(JSONObject geometry, String page) throws Exception {
        assertEquals("the packaged fast-key evidence must come from the API 35 device", 35,
                geometry.getInt("androidApi"));
        assertTrue("both catalog pages must be measured with the native keyboard open", geometry.getBoolean("keyboardVisible")
                && geometry.getJSONObject("androidIme").getBoolean("visible")
                && geometry.getJSONObject("androidIme").getDouble("imeBottomDp") > 0);
        JSONObject sheet = geometry.optJSONObject("catalogSheet");
        assertNotNull("the key catalog must render as a compact terminal rail", sheet);
        assertEquals("the compact in-flow catalog must fit one 48dp tab row and one 48dp key row: " + geometry,
                96, sheet.getDouble("height"), 0.5);
        JSONObject dock = geometry.getJSONObject("mobileHotkeys");
        int expectedCatalogDockHeight = "listening".equals(geometry.getString("inlineDictationPhase")) ? 185
                : geometry.getBoolean("inlineDictationStatusVisible") ? 177 : 145;
        assertEquals("the open terminal dock reserves the key row, phase-sized status, and bounded catalog: " + geometry,
                expectedCatalogDockHeight,
                dock.getDouble("height"), 0.5);
        JSONObject terminalPanel = geometry.getJSONObject("terminalPanel");
        JSONObject terminalSlot = geometry.getJSONObject("terminalSlot");
        assertTrue("catalog state must keep its terminal slot within the clipped panel: " + geometry,
                terminalSlot.getDouble("top") >= terminalPanel.getDouble("top") - 0.5
                        && terminalSlot.getDouble("bottom") <= terminalPanel.getDouble("bottom") + 0.5);
        assertTrue("the in-flow catalog must remain within the terminal slot: " + geometry,
                sheet.getDouble("top") >= geometry.getJSONObject("terminalSlot").getDouble("top") - 0.5
                        && sheet.getDouble("bottom") <= geometry.getJSONObject("terminalSlot").getDouble("bottom") + 0.5);
        assertTrue("the catalog rail must not overlap xterm or composer: " + geometry,
                geometry.getBoolean("catalogSheetBelowTerminalViewport")
                        && !geometry.getBoolean("catalogSheetIntersectsComposer"));
        assertTrue("the full terminal surface must end flush at the dock: " + geometry,
                geometry.getBoolean("terminalCanvasEndsAtDock"));
        JSONObject canvasStyle = geometry.getJSONObject("terminalCanvasStyle");
        assertEquals("terminal surface and panel must share one background", canvasStyle.getString("panelBackgroundColor"),
                canvasStyle.getString("backgroundColor"));
        assertEquals("terminal surface must not add nested rounded chrome", "0px", canvasStyle.getString("borderRadius"));
        JSONObject terminalHeading = geometry.getJSONObject("layout").getJSONObject(".panel-heading--terminal");
        assertTrue("the terminal title stays in the hierarchy while fast keys are open",
                !"none".equals(terminalHeading.getString("display")) && terminalHeading.getDouble("height") >= 24);
        assertEquals("catalog remains a terminal-context region, not a floating dialog", "region",
                geometry.getString("catalogSheetRole"));
        assertTrue("in-flow catalog must not claim modal semantics", geometry.isNull("catalogSheetModal"));
        JSONObject title = geometry.getJSONObject("catalogTitle");
        assertEquals("the muted catalog caption must stay short", "Keys", title.getString("text"));
        assertEquals("catalog page title and controls use one 48dp header", 48,
                geometry.getJSONObject("catalogHeader").getDouble("height"), 0.5);
        assertTrue("catalog title must remain fully visible at the device width: " + geometry, title.getBoolean("fits"));
        assertTrue("catalog caption and Main/Ctrl tabs must not overlap: " + geometry,
                geometry.getBoolean("catalogHeaderControlsDoNotOverlap"));
        assertCatalogTabsReachable(geometry, page);
        JSONObject catalogSurfaceStyle = geometry.getJSONObject("catalogSurfaceStyle");
        assertEquals("the expanded key catalog must remain visually flat on the terminal surface",
                "rgba(0, 0, 0, 0)", catalogSurfaceStyle.getString("backgroundColor"));
        assertEquals("the key catalog must not read as a rounded card", "0px", catalogSurfaceStyle.getString("borderRadius"));
        assertEquals("the key catalog must not add card side borders", "0px", catalogSurfaceStyle.getString("borderLeftWidth"));
        assertEquals("the key catalog must not add card side borders", "0px", catalogSurfaceStyle.getString("borderRightWidth"));
        assertEquals("the key catalog must not add a card bottom border", "0px", catalogSurfaceStyle.getString("borderBottomWidth"));
        JSONObject catalogKeyStyle = geometry.getJSONObject("catalogKeyStyle");
        assertEquals("key targets retain transparent, secondary key-slot styling", "rgba(0, 0, 0, 0)",
                catalogKeyStyle.getString("backgroundColor"));
        assertEquals("key labels use the shared UI-kit dense type token", "13px", catalogKeyStyle.getString("fontToken"));
        double scaledKeyFontSize = Double.parseDouble(catalogKeyStyle.getString("fontSize").replace("px", ""));
        assertTrue("key labels remain compact with Android text scaling: " + catalogKeyStyle,
                scaledKeyFontSize >= 13 && scaledKeyFontSize <= 17);
        JSONObject scroll = geometry.getJSONObject("catalogScrollMetrics");
        JSONObject scroller = geometry.getJSONObject("catalogScrollerBounds");
        assertTrue("catalog scroller must remain fully inside the 96dp rail after border sizing: " + geometry,
                geometry.getBoolean("catalogScrollerInsideSheet")
                        && scroller.getDouble("top") >= sheet.getDouble("top") - TERMINAL_VIEWPORT_ROUNDING_EPSILON_CSS_PX
                        && scroller.getDouble("bottom") <= sheet.getDouble("bottom") + TERMINAL_VIEWPORT_ROUNDING_EPSILON_CSS_PX
                        && scroller.getDouble("left") >= sheet.getDouble("left") - TERMINAL_VIEWPORT_ROUNDING_EPSILON_CSS_PX
                        && scroller.getDouble("right") <= sheet.getDouble("right") + TERMINAL_VIEWPORT_ROUNDING_EPSILON_CSS_PX
                        && Math.abs(scroller.getDouble("height") - 48) <= 1);
        assertEquals("the compact catalog shows one complete 48dp key row: " + geometry,
                48, scroll.getDouble("clientHeight"), 1.0);
        if ("main".equals(page)) {
            assertEquals("the Main keys use a horizontal swipe rail", "horizontal", scroll.getString("axis"));
            assertTrue("Main exposes every key through horizontal swiping: " + geometry,
                    scroll.getDouble("scrollWidth") > scroll.getDouble("clientWidth") + 1
                            && scroll.getDouble("scrollHeight") <= scroll.getDouble("clientHeight") + 1);
            assertMainCatalogSingleRow(scroll, geometry);
            JSONObject mainKeyLayout = geometry.getJSONObject("mainKeyLayout");
            assertEquals("Main uses one compact horizontal row", 1, mainKeyLayout.getInt("rowCount"));
            assertEquals("Main keys keep a compact 8dp slot gap", "8px", mainKeyLayout.getString("columnGap"));
            assertTrue("all Main keys stay in one scrollable touch row: " + mainKeyLayout,
                    mainKeyLayout.getDouble("maxRowWidth") > 500 && mainKeyLayout.getDouble("maxRowWidth") <= 600);
        } else {
            assertEquals("the Ctrl key rows keep vertical QWERTY scrolling", "vertical", scroll.getString("axis"));
            assertTrue("Ctrl keys do not scroll sideways: " + geometry,
                    scroll.getDouble("scrollWidth") <= scroll.getDouble("clientWidth") + 1);
            assertTrue("Ctrl page keeps extra letter rows in its vertical scroller: " + geometry,
                    scroll.getDouble("scrollHeight") > scroll.getDouble("clientHeight") + 1);
        }
        assertTrue("the terminal heading and at least five terminal rows remain visible: " + geometry,
                !"none".equals(terminalHeading.getString("display")) && terminalHeading.getDouble("height") >= 24
                        && terminalHeading.getDouble("bottom") <= geometry.getJSONObject("terminalViewport").getDouble("top") + 0.5
                        && geometry.getInt("visibleTerminalRows") >= 5 && runtimeGrid(geometry).getInt("rows") >= 5);
        JSONObject viewport = geometry.getJSONObject("visualViewport");
        double imeEdge = geometry.optDouble("imeEdgeCssY", viewport.getDouble("height") + viewport.optDouble("offsetTop", 0));
        assertTrue("compact catalog and persistent toolbar must clear the measured IME edge: " + geometry,
                dock.getDouble("bottom") <= imeEdge + 0.5);
    }

    private void assertMainCatalogSingleRow(JSONObject scroll, JSONObject geometry) throws Exception {
        JSONArray buttonRects = scroll.getJSONArray("buttonRects");
        assertEquals("the packaged main catalog measures all ten common keys", 10, buttonRects.length());
        List<Double> rowTops = new ArrayList<>();
        List<Integer> rowCounts = new ArrayList<>();
        for (int index = 0; index < buttonRects.length(); index += 1) {
            double rowTop = buttonRects.getJSONObject(index).getDouble("top");
            int row = 0;
            while (row < rowTops.size() && Math.abs(rowTops.get(row) - rowTop) >= 1) row += 1;
            if (row == rowTops.size()) {
                rowTops.add(rowTop);
                rowCounts.add(0);
            }
            rowCounts.set(row, rowCounts.get(row) + 1);
        }
        assertEquals("common keys must use one horizontally scrollable row: " + geometry, 1, rowCounts.size());
        assertEquals("the common-key row must contain all ten 48dp targets: " + geometry,
                List.of(10), rowCounts);
    }

    private void assertCatalogPageActionReachable(JSONObject geometry) throws Exception {
        JSONObject action = geometry.optJSONObject("catalogPageAction");
        assertNotNull("catalog sheet must expose its page action", action);
        assertTrue("catalog page action must meet the 48dp touch target: " + action,
                action.getDouble("width") >= 47.9 && action.getDouble("height") >= 47.9);
        assertTrue("catalog page action must remain visible above the IME: " + action,
                action.getBoolean("insideViewport") && action.getBoolean("insideCatalogSheet"));
    }

    private void assertCatalogTabsReachable(JSONObject geometry, String page) throws Exception {
        JSONArray tabs = geometry.getJSONArray("catalogTabs");
        assertEquals("the in-flow catalog exposes explicit Main and Ctrl navigation", 2, tabs.length());
        boolean mainSelected = false;
        boolean ctrlSelected = false;
        for (int index = 0; index < tabs.length(); index += 1) {
            JSONObject tab = tabs.getJSONObject(index);
            assertTrue("Main/Ctrl page controls meet the 48dp touch target and clear the IME: " + tab,
                    tab.getDouble("width") >= 47.9 && tab.getDouble("height") >= 47.9
                            && tab.getBoolean("insideViewport") && tab.getBoolean("insideCatalogSheet")
                            && tab.getBoolean("hitTarget"));
            if ("Select Main keys".equals(tab.getString("label"))) mainSelected = tab.getBoolean("selected");
            if ("Select Ctrl keys".equals(tab.getString("label"))) ctrlSelected = tab.getBoolean("selected");
        }
        assertTrue("the selected Main/Ctrl tab must match the visible catalog page", mainSelected == "main".equals(page)
                && ctrlSelected == "ctrl".equals(page));
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
        JSONObject terminalCanvas = geometry.getJSONObject("terminalCanvas");
        assertTrue("fast-key lane must be below the capped xterm viewport inside the flat terminal canvas: "
                        + geometry,
                tray.getBoolean("belowTerminalViewport")
                        && trayViewportGap >= -TERMINAL_VIEWPORT_ROUNDING_EPSILON_CSS_PX);
        assertTrue("full terminal canvas must include the capped grid, expected empty output area, and input rail: " + geometry,
                terminalCanvas.getDouble("top") <= terminalViewport.getDouble("top") + 0.5
                        && terminalCanvas.getDouble("bottom") >= trayBounds.getDouble("bottom") - 1
                        && terminalCanvas.getDouble("height") >= terminalViewport.getDouble("height") + trayBounds.getDouble("height"));
        JSONObject gridViewport = geometry.getJSONObject("terminalGridViewport");
        assertTrue("capped xterm grid must sit within the full terminal canvas: " + geometry,
                gridViewport.getDouble("top") >= terminalViewport.getDouble("top") - 0.5
                        && gridViewport.getDouble("bottom") <= terminalViewport.getDouble("bottom") + 0.5
                        && gridViewport.getDouble("bottom") <= terminalCanvas.getDouble("bottom") + 0.5);
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

    private void awaitDockGeometrySettled(String action) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 5_000;
        JSONObject previous = null;
        JSONObject last = null;
        while (SystemClock.uptimeMillis() < deadline) {
            last = readDockSizingState();
            double reserved = last.getDouble("reservedHeightPx");
            double rendered = last.getDouble("renderedHeightPx");
            boolean matches = Math.abs(reserved - rendered) <= 0.5;
            boolean stable = previous != null
                    && Math.abs(previous.getDouble("reservedHeightPx") - reserved) <= 0.1
                    && Math.abs(previous.getDouble("renderedHeightPx") - rendered) <= 0.1
                    && previous.getString("className").equals(last.getString("className"))
                    && previous.getBoolean("statusVisible") == last.getBoolean("statusVisible");
            if (matches && stable) {
                awaitRenderedFrame();
                JSONObject afterFrame = readDockSizingState();
                if (Math.abs(afterFrame.getDouble("reservedHeightPx") - afterFrame.getDouble("renderedHeightPx")) <= 0.5
                        && Math.abs(afterFrame.getDouble("reservedHeightPx") - reserved) <= 0.1
                        && Math.abs(afterFrame.getDouble("renderedHeightPx") - rendered) <= 0.1
                        && afterFrame.getString("className").equals(last.getString("className"))
                        && afterFrame.getBoolean("statusVisible") == last.getBoolean("statusVisible")) {
                    return;
                }
                previous = afterFrame;
            } else {
                previous = last;
            }
            Thread.sleep(60);
        }
        throw new AssertionError(action + " did not settle to the reserved terminal dock height; last=" + last);
    }

    private JSONObject readDockSizingState() throws Exception {
        return evalJson("(() => {const slot=document.querySelector('[data-testid=terminal-slot]');"
                + "const dock=document.querySelector('[data-testid=mobile-hotkeys]');"
                + "return JSON.stringify({reservedHeightPx:Number(slot?.dataset.terminalHotkeysDockHeight??0),"
                + "renderedHeightPx:dock?.getBoundingClientRect().height??0,className:dock?String(dock.className):'',"
                + "statusVisible:!!document.querySelector('[data-testid=inline-dictation-status-row]')});})()");
    }

    private void assertTerminalViewportCap(String action, JSONObject baselineGeometry, JSONObject afterGeometry)
            throws Exception {
        JSONObject baselineViewport = baselineGeometry.getJSONObject("terminalGridViewport");
        JSONObject afterViewport = afterGeometry.getJSONObject("terminalViewport");
        JSONObject afterGridViewport = afterGeometry.getJSONObject("terminalGridViewport");
        int cap = Math.min(ACCEPTED_ANDROID_TERMINAL_VIEWPORT_CAP_DP,
                (int) Math.floor(baselineViewport.getDouble("height")));
        assertEquals(action + " must retain the accepted capped keyboard-up viewport; before=" + baselineGeometry
                        + "; after=" + afterGeometry,
                cap,
                afterGeometry.getInt("terminalViewportDockCapPx"));
        assertEquals(action + " must keep the xterm viewport at that height; before=" + baselineGeometry
                        + "; after=" + afterGeometry,
                cap, afterGridViewport.getDouble("height"), 0.5);
        JSONObject canvas = afterGeometry.getJSONObject("terminalCanvas");
        JSONObject dock = afterGeometry.getJSONObject("fastKeysTray").getJSONObject("bounds");
        assertTrue(action + " must let the terminal canvas continue to the dock; after=" + afterGeometry,
                afterViewport.getDouble("height") >= afterGridViewport.getDouble("height") - 0.5
                        && canvas.getDouble("height") >= afterViewport.getDouble("height") + dock.getDouble("height")
                        && afterGeometry.getBoolean("terminalCanvasEndsAtDock"));
        double dockHeight = afterGeometry.getDouble("terminalHotkeysDockHeightPx");
        double renderedDockHeight = afterGeometry.getJSONObject("fastKeysTray").getJSONObject("bounds").getDouble("height");
        assertEquals(action + " dock height state must match the rendered dock; after=" + afterGeometry,
                dockHeight, renderedDockHeight, 0.5);
        assertTrue(action + " must reserve the capped viewport, rendered dock, and 1px flow gap; after=" + afterGeometry,
                afterGeometry.getJSONObject("terminalSlot").getDouble("height") + 0.5 >= cap + renderedDockHeight + 1);
    }

    private JSONObject assertAcceptedKeyboardUpViewport(String action, JSONObject geometry) throws Exception {
        JSONObject viewport = geometry.getJSONObject("terminalGridViewport");
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

    private void assertBackgroundResumeGeometryAcknowledged(JSONObject geometry, int resizeAcksBeforeResume)
            throws Exception {
        JSONObject grid = runtimeGrid(geometry);
        assertTrue("app resume after dismissing the IME must describe the keyboard-hidden layout", !geometry.getBoolean("keyboardVisible"));
        assertTrue("the native IME must be hidden at the resumed geometry checkpoint", !geometry.getJSONObject("androidIme").getBoolean("visible"));
        assertEquals("resumed PTY geometry must finish all native resize requests", 0, geometry.getInt("resizePending"));
        assertEquals("resumed PTY geometry must not hide a resize failure", 0, geometry.getInt("resizeFailures"));
        assertTrue("the resized host PTY must acknowledge a fresh post-resume size; geometry=" + geometry,
                geometry.getInt("resizeAcks") > resizeAcksBeforeResume);
        assertEquals("visible resize status must report the accepted local grid; geometry=" + geometry,
                grid.getInt("cols") + " × " + grid.getInt("rows") + " accepted by SSH", geometry.getString("resizeStatus"));

        JSONArray fitEvents = geometry.getJSONArray("resizeFitEvents");
        JSONArray ackEvents = geometry.getJSONArray("resizeAckEvents");
        JSONObject resumeFit = null;
        JSONObject resumeAck = null;
        for (int index = 0; index < fitEvents.length(); index++) {
            JSONObject event = fitEvents.getJSONObject(index);
            // Activity resume may first fit an intermediate size and then let
            // ResizeObserver publish the final restored terminal dimensions.
            // Match the actual final-grid request and its ACK, regardless of
            // which valid fit trigger produced it.
            if (event.optInt("cols", -1) == grid.getInt("cols")
                    && event.optInt("rows", -1) == grid.getInt("rows")
                    && !event.optString("reason").isEmpty()
                    && event.optInt("requestId", -1) > 0) resumeFit = event;
        }
        assertNotNull("resume must record a final-grid xterm fit request; geometry=" + geometry, resumeFit);
        for (int index = 0; index < ackEvents.length(); index++) {
            JSONObject event = ackEvents.getJSONObject(index);
            if (event.optInt("requestId", -1) == resumeFit.optInt("requestId", -2)) resumeAck = event;
        }
        assertNotNull("resume fit must have a matching native resize ACK; geometry=" + geometry, resumeAck);
        assertEquals("resume resize ACK must accept the fitted columns", grid.getInt("cols"), resumeAck.getInt("cols"));
        assertEquals("resume resize ACK must accept the fitted rows", grid.getInt("rows"), resumeAck.getInt("rows"));
        assertEquals("resume resize ACK must be accepted", "accepted", resumeAck.getString("result"));
        assertEquals("resume resize ACK must belong to the live PTY attachment", geometry.getInt("sshAttachEpoch"), resumeAck.getInt("attachEpoch"));
        assertTrue("resume resize ACK must follow its local fit", resumeAck.getDouble("atMs") >= resumeFit.getDouble("atMs"));
    }

    private void assertGridHasAcceptedResizeAck(JSONObject geometry, String action) throws Exception {
        JSONObject grid = runtimeGrid(geometry);
        assertEquals(action + " must finish without pending resize work", 0, geometry.getInt("resizePending"));
        assertEquals(action + " must not hide a resize failure", 0, geometry.getInt("resizeFailures"));
        assertEquals(action + " must report the accepted local xterm grid",
                grid.getInt("cols") + " × " + grid.getInt("rows") + " accepted by SSH", geometry.getString("resizeStatus"));
        JSONArray fitEvents = geometry.getJSONArray("resizeFitEvents");
        JSONArray ackEvents = geometry.getJSONArray("resizeAckEvents");
        JSONObject matchedFit = null;
        JSONObject matchedAck = null;
        for (int index = 0; index < fitEvents.length(); index++) {
            JSONObject event = fitEvents.getJSONObject(index);
            if (event.optInt("cols", -1) == grid.getInt("cols")
                    && event.optInt("rows", -1) == grid.getInt("rows")
                    && event.optInt("requestId", -1) > 0) matchedFit = event;
        }
        assertNotNull(action + " must record an xterm fit request", matchedFit);
        for (int index = 0; index < ackEvents.length(); index++) {
            JSONObject event = ackEvents.getJSONObject(index);
            if (event.optInt("requestId", -1) == matchedFit.optInt("requestId", -2)) matchedAck = event;
        }
        assertNotNull(action + " fit must have a matching resize acknowledgement", matchedAck);
        assertEquals(action + " ACK must accept the fitted columns", grid.getInt("cols"), matchedAck.getInt("cols"));
        assertEquals(action + " ACK must accept the fitted rows", grid.getInt("rows"), matchedAck.getInt("rows"));
        assertEquals(action + " ACK must be accepted", "accepted", matchedAck.getString("result"));
        assertEquals(action + " ACK must belong to the live PTY attachment",
                geometry.getInt("sshAttachEpoch"), matchedAck.getInt("attachEpoch"));
        assertTrue(action + " ACK must follow its local fit", matchedAck.getDouble("atMs") >= matchedFit.getDouble("atMs"));
    }

    private void assertAtLeastFiveRows(String action, JSONObject geometry) throws Exception {
        int rows = runtimeGrid(geometry).getInt("rows");
        int visibleRows = geometry.getInt("visibleTerminalRows");
        JSONObject host = geometry.getJSONObject("terminalViewport");
        JSONObject surface = geometry.getJSONObject("terminalXtermSurface");
        JSONArray xtermAncestors = geometry.getJSONArray("terminalXtermAncestors");
        JSONObject terminalHostMetrics = xtermAncestors.getJSONObject(1);
        assertTrue(action + " must keep at least five physical terminal rows visible; geometry=" + geometry, visibleRows >= 5);
        assertTrue(action + " must retain a PTY grid with at least five rows; geometry=" + geometry, rows >= 5);
        assertEquals(action + " must not let IME focus scroll the capped terminal host; geometry=" + geometry,
                0, terminalHostMetrics.getDouble("scrollTop"), 0.5);
        assertTrue(action + " xterm surface must stay within its capped host: " + geometry,
                surface.getDouble("top") >= host.getDouble("top") - 0.5
                        && surface.getDouble("bottom") <= host.getDouble("bottom") + 0.5);
    }

    private void awaitRenderedFrame() throws Exception {
        evalString("(() => {window.__ps2884RenderedFrame = false;"
                + "requestAnimationFrame(() => requestAnimationFrame(() => {window.__ps2884RenderedFrame = true;}));"
                + "return 'scheduled';})()");
        awaitJsTrue("window.__ps2884RenderedFrame === true");
        CountDownLatch visualStateReady = new CountDownLatch(1);
        runOnUiThread("request WebView visual state", () -> {
            WebView webView = packagedWebView;
            assertNotNull("packaged activity must contain a WebView before screenshot capture", webView);
            webView.postVisualStateCallback(SystemClock.uptimeMillis(), new WebView.VisualStateCallback() {
                @Override
                public void onComplete(long requestId) {
                    visualStateReady.countDown();
                }
            });
            return null;
        });
        assertTrue("WebView visual state must be ready before screenshot capture",
                visualStateReady.await(JS_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        CountDownLatch decorDrawReady = new CountDownLatch(1);
        runOnUiThread("wait for rendered Android decor frames", () -> {
            View decor = packagedActivity.getWindow().getDecorView();
            ViewTreeObserver observer = decor.getViewTreeObserver();
            int[] drawCount = {0};
            ViewTreeObserver.OnDrawListener[] listener = new ViewTreeObserver.OnDrawListener[1];
            listener[0] = () -> {
                drawCount[0] += 1;
                if (drawCount[0] >= 2) {
                    decor.post(() -> {
                        ViewTreeObserver current = decor.getViewTreeObserver();
                        if (current.isAlive()) current.removeOnDrawListener(listener[0]);
                        decorDrawReady.countDown();
                    });
                } else {
                    decor.postInvalidateOnAnimation();
                }
            };
            observer.addOnDrawListener(listener[0]);
            decor.postInvalidateOnAnimation();
            return null;
        });
        assertTrue("Android decor must draw two frames after the WebView visual state before screenshot capture",
                decorDrawReady.await(JS_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        CountDownLatch nativeFramesReady = new CountDownLatch(2);
        runOnUiThread("wait for Android frames after WebView visual state", () -> {
            Choreographer choreographer = Choreographer.getInstance();
            Choreographer.FrameCallback[] callback = new Choreographer.FrameCallback[1];
            callback[0] = frameTimeNanos -> {
                nativeFramesReady.countDown();
                if (nativeFramesReady.getCount() > 0) choreographer.postFrameCallback(callback[0]);
            };
            choreographer.postFrameCallback(callback[0]);
            return null;
        });
        assertTrue("Android must draw two frames after the WebView visual state before screenshot capture",
                nativeFramesReady.await(JS_TIMEOUT_SECONDS, TimeUnit.SECONDS));
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

    private JSONObject capturePromptDictationComposerTapState(int pointerEventOffset) throws Exception {
        JSONObject state = evalJson("(() => {const shell=document.querySelector('.app-shell');"
                + "const button=document.querySelector('[data-testid=composer-dictate]');"
                + "const rect=button?.getBoundingClientRect();const viewport=window.visualViewport;"
                + "const center=rect?document.elementFromPoint(rect.left+rect.width/2,rect.top+rect.height/2):null;"
                + "const composer=document.querySelector('[data-testid=prompt-composer]');"
                + "const active=document.activeElement;const events=window.__ps2884PointerEvents??[];"
                + "return JSON.stringify({atMs:Math.round(performance.now()),route:shell?.dataset.route??null,"
                + "sshPhase:shell?.dataset.sshPhase??null,keyboardVisible:shell?.dataset.keyboardVisible??null,"
                + "keyboardComposerMode:shell?.dataset.keyboardComposerMode==='true',"
                + "resizePending:Number(shell?.dataset.sshTerminalResizePending??0),"
                + "resizeAcks:Number(shell?.dataset.sshTerminalResizeAcks??0),"
                + "resizeFailures:Number(shell?.dataset.sshTerminalResizeFailures??0),"
                + "paletteOpen:document.querySelector('[data-testid=mobile-hotkeys]')?.dataset.paletteOpen??null,"
                + "button:button?{disabled:button.disabled,connected:button.isConnected,"
                + "bounds:{left:rect.left,top:rect.top,right:rect.right,bottom:rect.bottom,width:rect.width,height:rect.height},"
                + "visible:rect.top>=0&&rect.left>=0&&rect.bottom<=(viewport?.height??innerHeight)+0.5"
                + "&&rect.right<=innerWidth+0.5&&button.getClientRects().length>0,"
                + "visualViewport:{height:viewport?.height??innerHeight,offsetTop:viewport?.offsetTop??0},"
                + "centerTarget:{tag:center?.tagName?.toLowerCase()??null,testId:center?.getAttribute('data-testid')??null,"
                + "buttonTestId:center?.closest('button')?.dataset.testid??null}}:null,"
                + "composer:composer?{role:composer.getAttribute('role'),state:composer.dataset.dictationState??null,"
                + "visible:composer.getClientRects().length>0,text:composer.innerText?.slice(0,240)??''}:null,"
                + "activeElement:active?.outerHTML?.slice(0,180)??null,"
                + "speechStartCount:window.__ps2857ControlledSpeech?.startCount??null,"
                + "pointerEvents:events.slice(" + pointerEventOffset + ").slice(-12),"
                + "jsDiagnostics:window.__ps2884JsDiagnostics?.events?.slice(-8)??[]});})()")
                .put("nativeIme", readNativeImeState());
        return state;
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
        assertTrue("UiAutomation screenshot capture must not block the WebView main thread",
                Thread.currentThread() != Looper.getMainLooper().getThread());
        Log.i("PS2897Prompt", "screenshot " + name + " before instrumentation-thread capture");
        Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        Log.i("PS2897Prompt", "screenshot " + name + " takeScreenshot returned=" + (screenshot != null));
        assertNotNull("full-screen screenshot must be captured for " + name, screenshot);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        boolean compressed = screenshot.compress(Bitmap.CompressFormat.PNG, 100, output);
        screenshot.recycle();
        byte[] pngBytes = compressed && output.size() >= 1_024 ? output.toByteArray() : null;
        assertNotNull("full-screen screenshot must be encoded for " + name, pngBytes);
        emitArtifact(name, pngBytes);
        Log.i("PS2897Prompt", "screenshot " + name + " artifact emitted");
    }

    private void captureTerminalViewportScreenshot(String name, JSONObject geometry) throws Exception {
        JSONObject viewport = geometry.getJSONObject("terminalViewport");
        float[] topLeft = screenPoint((float) viewport.getDouble("left"), (float) viewport.getDouble("top"));
        float[] bottomRight = screenPoint((float) viewport.getDouble("right"), (float) viewport.getDouble("bottom"));
        AtomicReference<String> cropError = new AtomicReference<>();
        byte[] pngBytes = runOnUiThread("capture terminal viewport " + name, () -> {
            Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            if (screenshot == null) {
                cropError.set("device screenshot was unavailable");
                return null;
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
                return null;
            }
            Bitmap crop = Bitmap.createBitmap(screenshot, left, top, right - left, bottom - top);
            screenshot.recycle();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            boolean compressed = crop.compress(Bitmap.CompressFormat.PNG, 100, output);
            crop.recycle();
            return compressed && output.size() >= 1_024 ? output.toByteArray() : null;
        });
        if (cropError.get() != null) throw new AssertionError(cropError.get());
        assertNotNull("terminal viewport crop must be captured for " + name, pngBytes);
        emitArtifact(name, pngBytes);
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
        try {
            return runOnUiThread("read Android IME visibility", () -> {
                WindowInsets insets = packagedActivity.getWindow().getDecorView().getRootWindowInsets();
                return insets != null && insets.isVisible(WindowInsets.Type.ime());
            });
        } catch (Exception error) {
            throw new AssertionError("could not read Android IME visibility", error);
        }
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
        String webState = evalString("(() => {const shell=document.querySelector('.app-shell');"
                + "const panel=document.querySelector('[data-testid=prompt-composer]');"
                + "const draft=document.querySelector('[data-testid=prompt-draft]');"
                + "const active=document.activeElement;const gate=window.__ps2884AttachAutofocusGate;"
                + "const describe=node=>node instanceof Element?{tag:node.tagName.toLowerCase(),id:node.id||null,"
                + "testId:node.getAttribute('data-testid'),className:String(node.className||'').slice(0,80)}:null;"
                + "return JSON.stringify({sshPhase:shell?.dataset.sshPhase??null,homeSurface:shell?.dataset.homeSurface??null,"
                + "attachEpoch:shell?.dataset.sshAttachEpoch??null,attachFocusPending:shell?.dataset.sshAttachFocusPending??null,"
                + "attachPromptFocusEpoch:shell?.dataset.sshAttachPromptFocusEpoch??null,"
                + "attachResizeAckEpoch:shell?.dataset.sshAttachResizeAckEpoch??null,"
                + "terminalAutofocusAllowed:shell?.dataset.sshTerminalAutofocusAllowed??null,"
                + "resizePending:shell?.dataset.sshTerminalResizePending??null,"
                + "resizeFailures:shell?.dataset.sshTerminalResizeFailures??null,"
                + "keyboardVisible:shell?.dataset.keyboardVisible??null,keyboardComposerMode:shell?.dataset.keyboardComposerMode??null,"
                + "composerOpen:shell?.dataset.promptComposerOpen??null,composerPresent:!!panel,"
                + "draftConnected:draft?.isConnected??false,draftVisible:!!draft&&draft.getClientRects().length>0,"
                + "draftFocused:active===draft,activeElement:describe(active),"
                + "attachAutofocusGate:gate?{entered:gate.entered??[],pending:gate.pending??0,released:gate.released??false}:null,"
                + "focusEvents:(window.__ps2884FocusEvents??[]).slice(-24)});})()");
        boolean androidImeVisible = isImeVisible();
        throw new AssertionError("Android IME visibility did not become " + visible
                + " (AndroidImeVisible=" + androidImeVisible + "; attach/composer=" + webState + ")");
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
        return runOnUiThread("map CSS point to Android screen", () -> {
            WebView webView = packagedWebView;
            assertNotNull("packaged screen must contain a WebView", webView);
            int[] location = new int[2];
            webView.getLocationOnScreen(location);
            float scaleX = webView.getWidth() / (float) cssWidth;
            float scaleY = webView.getHeight() / (float) cssHeight;
            return new float[]{location[0] + cssX * scaleX, location[1] + cssY * scaleY};
        });
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

    private <T> T runOnUiThread(String label, Callable<T> action) throws Exception {
        WebView webView = packagedWebView;
        assertNotNull("packaged activity must contain a WebView for " + label, webView);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        boolean posted = webView.post(() -> {
            try {
                result.set(action.call());
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                completed.countDown();
            }
        });
        assertTrue("could not enqueue UI operation: " + label, posted);
        if (!completed.await(UI_CALLBACK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            String mainThread = mainLooperThreadState();
            Log.e("PS2897Prompt", label + " timed out waiting for WebView main-thread dispatch: " + mainThread);
            throw new AssertionError(label + " timed out waiting for WebView main-thread dispatch: " + mainThread);
        }
        Throwable error = failure.get();
        if (error instanceof Exception) throw (Exception) error;
        if (error instanceof Error) throw (Error) error;
        if (error != null) throw new RuntimeException(error);
        return result.get();
    }

    private String mainLooperThreadState() {
        Thread mainThread = Looper.getMainLooper().getThread();
        StringBuilder result = new StringBuilder("state=").append(mainThread.getState());
        StackTraceElement[] stack = mainThread.getStackTrace();
        for (int index = 0; index < Math.min(stack.length, 20); index += 1) {
            result.append("\n  at ").append(stack[index]);
        }
        return result.toString();
    }

    private void awaitJsTrue(String expression) throws Exception {
        awaitJsTrue(expression, WAIT_TIMEOUT_MILLIS);
    }

    private void awaitJsTrue(String expression, long timeoutMillis) throws Exception {
        boolean traceTranscribing = expression.contains("dataset.dictationState === 'transcribing'");
        if (traceTranscribing) Log.i("PS2897Prompt", "awaiting transcribing DOM state; timeoutMs=" + timeoutMillis);
        long deadline = SystemClock.uptimeMillis() + timeoutMillis;
        String last = "<not evaluated>";
        while (SystemClock.uptimeMillis() < deadline) {
            last = evalRaw(expression);
            if ("true".equals(last)) {
                if (traceTranscribing) Log.i("PS2897Prompt", "transcribing DOM condition returned true");
                return;
            }
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
        boolean traceTranscribing = expression.contains("dataset.dictationState === 'transcribing'");
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        WebView webView = packagedWebView;
        assertNotNull("packaged Capacitor activity must contain a WebView", webView);
        if (traceTranscribing) Log.i("PS2897Prompt", "transcribing eval before bounded WebView.post");
        boolean posted = webView.post(() -> {
            if (traceTranscribing) Log.i("PS2897Prompt", "transcribing WebView.post callback entered");
            webView.evaluateJavascript(expression, value -> {
                if (traceTranscribing) Log.i("PS2897Prompt", "transcribing evaluateJavascript callback value=" + value);
                result.set(value);
                latch.countDown();
            });
        });
        assertTrue("could not enqueue JavaScript evaluation on the packaged WebView", posted);
        if (traceTranscribing) Log.i("PS2897Prompt", "transcribing eval WebView.post returned; awaiting JS callback");
        assertTrue("timed out evaluating packaged WebView JavaScript", latch.await(JS_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (traceTranscribing) Log.i("PS2897Prompt", "transcribing eval JS callback completed");
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
