package com.pocketshell.app.smoke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Insets;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
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
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Packaged composer journey against the real agents/aplexer Docker fixture. */
@RunWith(AndroidJUnit4.class)
public final class JsComposerDockerJourneyTest {
    private static final long WAIT_TIMEOUT_MILLIS = 45_000;
    private static final long JS_TIMEOUT_SECONDS = 15;

    private ActivityScenario<MainActivity> scenario;
    private MainActivity packagedActivity;
    private WebView packagedWebView;
    private String bytesSession;
    private String uncertainSession;
    private String artifactRunId;
    private int hostOraclePort;
    private boolean forceFirstPostAttachTapMiss;
    private int composerFocusMaxAttempts = 2;
    private final JSONArray focusTapAttempts = new JSONArray();
    private JSONObject lastPhysicalTapEvidence;
    private JSONObject lastDictationTestEvent = new JSONObject();
    private boolean focusTraceEmitted;
    private boolean focusFailureCaptured;
    private ScheduledExecutorService journeyWatchdog;
    private volatile Thread journeyThread;
    private volatile String journeyCheckpoint = "not-started";
    private volatile long journeyCheckpointAtMs;
    private JSONObject lastBackgroundLifecycleTrace;

    @Before
    public void launchPackagedShell() {
        scenario = ActivityScenario.launch(MainActivity.class);
        scenario.onActivity(activity -> {
            packagedActivity = activity;
            packagedWebView = findWebView(activity.getWindow().getDecorView());
        });
        assertNotNull("packaged Capacitor activity must contain a WebView", packagedWebView);
    }

    @After
    public void closeShell() {
        if (journeyWatchdog != null) journeyWatchdog.shutdownNow();
        try {
            emitFocusTraceIfNeeded();
        } catch (Exception error) {
            Log.e("PS2891Focus", "could not emit composer tap trace before ActivityScenario teardown", error);
        }
        if (scenario != null) {
            stopWebAnimationsBeforeScenarioClose();
            scenario.close();
        }
    }

    @Test
    public void composerWritesUtf8AndMultilineInsertAndRetainsAfterDrop() throws Exception {
        assertTrue("safe-area and keyboard assertions require API 35+", Build.VERSION.SDK_INT >= 35);
        var arguments = InstrumentationRegistry.getArguments();
        String host = arguments.getString("sshHost", "10.0.2.2");
        String port = arguments.getString("sshPort");
        String encodedKey = arguments.getString("sshPrivateKeyBase64");
        String nameBase = arguments.getString("sshSessionName");
        artifactRunId = arguments.getString("artifactRunId", nameBase);
        forceFirstPostAttachTapMiss = Boolean.parseBoolean(
                arguments.getString("composerForceFirstPostAttachTapMiss", "false"));
        try {
            composerFocusMaxAttempts = Integer.parseInt(arguments.getString("composerFocusMaxAttempts", "2"));
        } catch (NumberFormatException error) {
            throw new AssertionError("composer focus attempt limit must be an integer from one to two", error);
        }
        assertTrue("composer focus attempt limit must stay bounded to one or two taps",
                composerFocusMaxAttempts >= 1 && composerFocusMaxAttempts <= 2);
        assertNotNull("pass the Docker fixture port with sshPort", port);
        assertNotNull("pass the test-only key with sshPrivateKeyBase64", encodedKey);
        assertNotNull("pass unique composer session names with sshSessionName", nameBase);
        startJourneyWatchdog();
        String privateKey = new String(Base64.getDecoder().decode(encodedKey), StandardCharsets.UTF_8);
        bytesSession = nameBase + "-bytes";
        uncertainSession = nameBase + "-uncertain";
        String hostOraclePortValue = arguments.getString("hostOraclePort");
        assertNotNull("pass the local Docker host-byte oracle port", hostOraclePortValue);
        try {
            hostOraclePort = Integer.parseInt(hostOraclePortValue);
        } catch (NumberFormatException error) {
            throw new AssertionError("host-byte oracle port must be an integer", error);
        }
        assertTrue("host-byte oracle port must be valid", hostOraclePort >= 1 && hostOraclePort <= 65_535);

        awaitJsTrue("document.querySelector('[data-testid=build-status] > span:nth-child(2)')?.textContent.trim() === 'Build verified'");
        checkpoint("build-ready");
        setVoicePreferencesForComposerJourney();
        evalString("window.__ps2857CaptureTerminalEvidence = true; 'terminal evidence enabled'");
        setValue("[data-testid=ssh-host]", host);
        setValue("[data-testid=ssh-port]", port);
        setValue("[data-testid=ssh-username]", "testuser");
        setValue("[data-testid=ssh-private-key]", privateKey);
        click("[data-testid=ssh-connect]");
        awaitTrustOrConnected();
        awaitJsTrue("['connected','listing'].includes(document.querySelector('.app-shell')?.dataset.sshPhase)");
        checkpoint("ssh-connected");

        createSession(bytesSession);
        createSession(uncertainSession);
        attachSession(bytesSession);
        verifyNestedAndroidBackKeepsLiveSession();
        checkpoint("nested-back-live-workspace-restored");

        exerciseComposerDictationMode(artifactRunId, nameBase);
        tapDomCenter("[data-testid=composer-close]");
        awaitJsTrue("!document.querySelector('[data-testid=prompt-composer]')");
        checkpoint("prompt-composer-closed-before-inline-terminal-dictation");
        installControlledSpeechAdapter();
        exerciseInlineTerminalDictation(nameBase, bytesSession, uncertainSession, artifactRunId);

        String sentMarker = "PS2857_SENT_" + nameBase;
        String sentMarkerPrefix = "PS2857_SENT_";
        String unicodeCommand = "printf '%s' 'café 🧪' | od -An -tx1 | tr -d '[:space:]' | tee /tmp/"
                + nameBase + "-u; printf '\\n%s%s\\n' '" + sentMarkerPrefix + "' '" + nameBase
                + "' | tee /tmp/" + nameBase + "-s; printf '\\n\\n\\n\\n\\n\\n\\n\\n\\n\\n\\n'";
        assertTrue("the sent-output marker must not be present verbatim in the command echo", !unicodeCommand.contains(sentMarker));
        openComposerAfterInlineWithEvidence(artifactRunId);
        setComposerDraft(unicodeCommand);
        showKeyboardAndCapture(artifactRunId);
        assertTrue("Send must be activated while the Android IME is visible", isImeVisible());
        long sendTouchUpUptimeMs = tapComposerAction(".composer-shared-controls .send");
        long sendToVisibleOutputLatencyMs = waitForTerminalMarkerOrCaptureWindow(sentMarker, sendTouchUpUptimeMs);
        awaitDeliveredAndCleared();
        savePostSendArtifacts(artifactRunId, sentMarker, unicodeCommand, sendToVisibleOutputLatencyMs);

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
        openComposerWithEvidence(artifactRunId, "uncertain-first-attach", "uncertain-session-first-composer-launcher");
        setComposerDraft(uncertainCommand);
        armDisconnectAfterFirstAcknowledgement();
        tapComposerAction(".composer-shared-controls .send");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.sshPhase === 'idle'", 30_000);
        awaitJsTrue("!document.querySelector('[data-testid=prompt-composer]')", 10_000);

        click("[data-testid=ssh-connect]");
        awaitTrustOrConnected();
        awaitJsTrue("['connected','listing'].includes(document.querySelector('.app-shell')?.dataset.sshPhase)");
        attachSession(uncertainSession);
        openComposerWithEvidence(artifactRunId, "uncertain-reattach", "uncertain-session-composer-launcher");
        setComposerDraft(uncertainCommand);
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

    private void verifyNestedAndroidBackKeepsLiveSession() throws Exception {
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.backButtonReady === 'true'");
        click("button[aria-label='Settings']");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'settings'");
        click("[data-testid=open-terminal-settings]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'settings-terminal'");

        // Exercise Android's real keyboard-dismiss path first. A Back key sent
        // while the IME is visible can be consumed by the IME before the
        // Capacitor App plugin sees it; the next Back must then reach the app.
        tapDomCenter("[data-testid=terminal-font-size-input]");
        awaitJsTrue("document.activeElement === document.querySelector('[data-testid=terminal-font-size-input]')");
        awaitImeVisible(true);
        int beforeImeBack = backButtonEventCount();
        Log.i("PS2857Back", "before-ime-dismiss|" + backUiState());
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        awaitImeVisible(false);
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'settings-terminal'"
                + " && document.querySelector('.app-shell')?.dataset.keyboardVisible === 'false'");
        int afterImeBack = backButtonEventCount();
        assertTrue("keyboard dismissal may be consumed by Android or delivered once to Capacitor",
                afterImeBack >= beforeImeBack && afterImeBack <= beforeImeBack + 1);
        Log.i("PS2857Back", "after-ime-dismiss|" + backUiState());

        // Tapping an ordinary settings surface models a user leaving the
        // numeric editor and dismisses any editor-owned PopupWindow before
        // route Back is sent.
        tapDomCenter("[data-testid=terminal-settings-screen] .settings-preview");
        awaitJsTrue("document.activeElement !== document.querySelector('[data-testid=terminal-font-size-input]')"
                + " && document.querySelector('.app-shell')?.dataset.keyboardVisible === 'false'");
        awaitImeVisible(false);
        Log.i("PS2857Back", "after-settings-surface-tap|" + backUiState());
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        SystemClock.sleep(250);

        int beforeRouteBack = backButtonEventCount();
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        awaitJsTrue("Number(document.querySelector('.app-shell')?.dataset.backButtonEvents) > " + beforeRouteBack);
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'settings'");
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        SystemClock.sleep(250);
        Log.i("PS2857Back", "after-nested-route-back|" + backUiState());

        int beforeHomeBack = backButtonEventCount();
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        try {
            awaitJsTrue("Number(document.querySelector('.app-shell')?.dataset.backButtonEvents) > " + beforeHomeBack);
            awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'home'"
                    + " && document.querySelector('.app-shell')?.dataset.homeSurface === 'live'"
                    + " && document.querySelector('.app-shell')?.dataset.sshPhase === 'live'"
                    + " && !!document.querySelector('[data-testid=prompt-composer-launcher]')"
                    + " && !document.querySelector('[data-testid=prompt-composer]')");
        } catch (AssertionError backFailure) {
            try {
                emitBackState("workspace-failure");
            } catch (Exception | AssertionError evidenceFailure) {
                backFailure.addSuppressed(evidenceFailure);
            }
            throw backFailure;
        }
        emitBackState("workspace-restored");
    }

    private int backButtonEventCount() throws Exception {
        return Integer.parseInt(evalString("document.querySelector('.app-shell')?.dataset.backButtonEvents ?? '0'"));
    }

    private String backUiState() throws Exception {
        String webState = evalString("(() => {const shell=document.querySelector('.app-shell');"
                + "const composer=document.querySelector('[data-testid=prompt-composer]');"
                + "const launcher=document.querySelector('[data-testid=prompt-composer-launcher]');"
                + "return JSON.stringify({route:shell?.dataset.route,homeSurface:shell?.dataset.homeSurface,"
                + "sshPhase:shell?.dataset.sshPhase,keyboardVisible:shell?.dataset.keyboardVisible,"
                + "backButtonReady:shell?.dataset.backButtonReady,backButtonEvents:shell?.dataset.backButtonEvents,"
                + "composerVisible:!!composer&&composer.getClientRects().length>0,"
                + "composerModal:composer?.getAttribute('aria-modal')==='true',"
                + "composerLauncherVisible:!!launcher&&launcher.getClientRects().length>0,"
                + "activeElementTag:document.activeElement?.tagName??'',"
                + "activeElementTestId:document.activeElement?.getAttribute('data-testid')??''});})()");
        AtomicReference<Boolean> imeVisible = new AtomicReference<>(null);
        AtomicReference<Boolean> windowFocus = new AtomicReference<>(null);
        onMainActivity(activity -> {
            WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
            imeVisible.set(insets != null && insets.isVisible(WindowInsets.Type.ime()));
            windowFocus.set(activity.getWindow().getDecorView().hasWindowFocus());
        });
        return new JSONObject(webState)
                .put("androidImeVisible", imeVisible.get())
                .put("windowFocus", windowFocus.get())
                .toString();
    }

    private void emitBackState(String state) throws Exception {
        String json = new JSONObject(backUiState())
                .put("runId", artifactRunId)
                .put("state", state)
                .toString();
        Log.i("PS2857Back", state + "|" + json);
        emitCurrentScreen(artifactRunId, "composer-back-" + state + ".png");
        emitArtifact(artifactRunId, "composer-back-" + state + ".json", json.getBytes(StandardCharsets.UTF_8));
    }

    private void createSession(String name) throws Exception {
        awaitJsTrue("!!document.querySelector('[data-testid=new-session-name]')");
        setValue("[data-testid=new-session-name]", name);
        click("[data-testid=create-session]");
        String match = "Array.from(document.querySelectorAll('[data-session-name]')).find(node => node.dataset.sessionName.endsWith("
                + JSONObject.quote(name) + "))";
        awaitJsTrue(match + " !== undefined");
    }

    private void attachSession(String suffixName) throws Exception {
        if (!"sessions".equals(evalRaw("document.querySelector('.app-shell')?.dataset.homeSurface ?? ''"))) {
            click("[data-testid=open-sessions]");
        }
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.homeSurface === 'sessions'");
        String match = "Array.from(document.querySelectorAll('[data-session-name]')).find(node => node.dataset.sessionName.endsWith("
                + JSONObject.quote(suffixName) + "))";
        awaitJsTrue(match + " !== undefined");
        String actualName = evalString(match + "?.dataset.sessionName ?? ''");
        click("[data-session-name=" + JSONObject.quote(actualName) + "]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.sshPhase === 'live'");
        awaitJsTrue("!!document.querySelector('[data-testid=prompt-composer-launcher]')"
                + " && !document.querySelector('[data-testid=prompt-composer]')");
        awaitJsTrue("document.activeElement?.classList.contains('xterm-helper-textarea')", 5_000);
    }

    private void setComposerDraft(String value) throws Exception {
        boolean openedComposer = false;
        if (!"true".equals(evalRaw("!!document.querySelector('[data-testid=prompt-composer]')"))) {
            evalString("window.__ps2857PromptDictateStable = null; 'reset physical target stability samples'");
            tapDomCenter("[data-testid=prompt-composer-launcher]");
            awaitJsTrue("document.querySelector('[data-testid=prompt-composer]')?.getAttribute('role') === 'dialog'"
                    + " && document.querySelector('[data-testid=prompt-composer]')?.getAttribute('aria-modal') === 'true'");
            openedComposer = true;
        }
        setValue("[data-testid=prompt-draft]", value);
        awaitJsTrue("document.querySelector('[data-testid=prompt-draft]')?.value === " + JSONObject.quote(value));
        if (openedComposer) awaitPromptDictateTargetSettled();
    }

    private void awaitPromptDictateTargetSettled() throws Exception {
        awaitNativeWindowFocus(true);
        evalString("window.__ps2857PromptDictateStable = null; 'reset physical Dictate target stability samples'");
        String settled = "(() => {const key='__ps2857PromptDictateStable';"
                + "const panel=document.querySelector('[data-testid=prompt-composer]');"
                + "const button=panel?.querySelector('[data-testid=composer-dictate]');"
                + "if(!panel||!button)return false;"
                + "const panelStyle=getComputedStyle(panel),buttonStyle=getComputedStyle(button);"
                + "const panelRect=panel.getBoundingClientRect(),buttonRect=button.getBoundingClientRect();"
                + "const viewport={width:window.visualViewport?.width??innerWidth,height:window.visualViewport?.height??innerHeight};"
                + "const x=buttonRect.left+buttonRect.width/2,y=buttonRect.top+buttonRect.height/2;"
                + "const hit=document.elementFromPoint(x,y);"
                + "const visible=panel.getAttribute('role')==='dialog'&&panel.getAttribute('aria-modal')==='true'"
                + "&&panel.getClientRects().length>0&&panelStyle.display!=='none'&&panelStyle.visibility==='visible'"
                + "&&Number(panelStyle.opacity)>0.95&&panelRect.top<viewport.height&&panelRect.bottom>0"
                + "&&button.getClientRects().length>0&&buttonStyle.display!=='none'&&buttonStyle.visibility==='visible'"
                + "&&Number(buttonStyle.opacity)>0.95&&!button.disabled&&buttonRect.width>=48&&buttonRect.height>=48"
                + "&&buttonRect.left>=0&&buttonRect.top>=0&&buttonRect.right<=viewport.width"
                + "&&buttonRect.bottom<=viewport.height;"
                + "const hitTested=!!hit?.closest?.('[data-testid=composer-dictate]');"
                + "const geometry=[panelRect.left,panelRect.top,panelRect.width,panelRect.height,buttonRect.left,buttonRect.top,"
                + "buttonRect.width,buttonRect.height,viewport.width,viewport.height];"
                + "const previous=window[key];const same=previous&&geometry.every((value,index)=>Math.abs(value-previous.geometry[index])<0.25);"
                + "const samples=same?previous.samples+1:1;window[key]={geometry,samples};"
                + "const moving=panel.getAnimations({subtree:true}).some(animation=>animation.playState==='running');"
                + "return visible&&hitTested&&!moving&&samples>=3;})()";
        awaitJsTrue(settled, 8_000);
    }

    private void openComposerAfterInlineWithEvidence(String runId) throws Exception {
        openComposerWithEvidence(runId, "reopen", "unicode-composer-launcher");
    }

    private void openComposerWithEvidence(String runId, String evidenceSuffix, String checkpointPrefix)
            throws Exception {
        String selector = "[data-testid=prompt-composer-launcher]";
        checkpoint("launcher-ime-geometry-wait-started-" + evidenceSuffix);
        JSONObject settledImeGeometry = awaitComposerLauncherImeGeometry(evidenceSuffix);
        Log.i("PS2857Launcher", "SETTLED_IME_GEOMETRY|" + runId + "|" + evidenceSuffix + "|" + settledImeGeometry);
        checkpoint("launcher-ime-geometry-settled-" + evidenceSuffix);
        evalString("(() => {const key='__ps2857ComposerLauncherPointerEvents';window[key]=[];"
                + "if(!window.__ps2857ComposerLauncherRecorderInstalled){"
                + "const label=node=>node?{tag:node.tagName||'',id:node.id||'',testid:node.getAttribute?.('data-testid')||'',"
                + "className:typeof node.className==='string'?node.className:''}:null;"
                + "for(const type of ['pointerdown','pointerup','click','focusin'])document.addEventListener(type,event=>{"
                + "const target=event.target;const launcher=target?.closest?.(" + JSONObject.quote(selector) + ");"
                + "if(!launcher)return;const rect=launcher.getBoundingClientRect();window[key].push({type,isTrusted:event.isTrusted,"
                + "pointerType:event.pointerType??'',clientX:event.clientX??null,clientY:event.clientY??null,"
                + "target:label(target),launcher:label(launcher),launcherBounds:{left:rect.left,top:rect.top,right:rect.right,"
                + "bottom:rect.bottom,width:rect.width,height:rect.height},timeStamp:event.timeStamp});},true);"
                + "window.__ps2857ComposerLauncherRecorderInstalled=true;}return 'installed';})()");

        JSONObject before = composerLauncherSnapshot("before-tap");
        emitArtifact(runId, "composer-launcher-before-" + evidenceSuffix + ".json",
                before.toString().getBytes(StandardCharsets.UTF_8));
        if ("reopen".equals(evidenceSuffix) || "uncertain-first-attach".equals(evidenceSuffix)) {
            emitCurrentScreen(runId, "composer-launcher-before-" + evidenceSuffix + ".png");
        }
        Log.i("PS2857Launcher", "BEFORE_REOPEN|" + evidenceSuffix + "|" + runId + "|" + before);

        JSONArray attempts = new JSONArray();
        lastPhysicalTapEvidence = null;
        String failure = null;
        String firstAttemptFailure = null;
        JSONObject afterFirstAttempt = null;
        try {
            checkpoint(checkpointPrefix + "-before-physical-tap");
            tapDomCenter(selector);
            checkpoint(checkpointPrefix + "-physical-tap-returned");
            awaitJsTrue("document.querySelector('[data-testid=prompt-composer]')?.getAttribute('role') === 'dialog'"
                    + " && document.querySelector('[data-testid=prompt-composer]')?.getAttribute('aria-modal') === 'true'",
                    12_000);
        } catch (Exception | AssertionError error) {
            failure = error.toString();
        }
        afterFirstAttempt = composerLauncherSnapshot("after-first-tap");
        attempts.put(launcherAttemptEvidence(1, failure, afterFirstAttempt));

        if (failure != null && ("uncertain-reattach".equals(evidenceSuffix)
                || "uncertain-first-attach".equals(evidenceSuffix))
                && launcherRetryAfterImeResize(before, afterFirstAttempt)) {
            firstAttemptFailure = failure;
            failure = null;
            lastPhysicalTapEvidence = null;
            try {
                checkpoint(checkpointPrefix + "-retry-after-ime-resize-before-physical-tap");
                tapDomCenter(selector);
                checkpoint(checkpointPrefix + "-retry-after-ime-resize-physical-tap-returned");
                awaitJsTrue("document.querySelector('[data-testid=prompt-composer]')?.getAttribute('role') === 'dialog'"
                        + " && document.querySelector('[data-testid=prompt-composer]')?.getAttribute('aria-modal') === 'true'",
                        12_000);
            } catch (Exception | AssertionError error) {
                failure = error.toString();
            }
            JSONObject afterRetry = composerLauncherSnapshot("after-resized-tap");
            attempts.put(launcherAttemptEvidence(2, failure, afterRetry));
        }

        JSONObject after = composerLauncherSnapshot("after-tap")
                .put("physicalTap", lastPhysicalTapEvidence == null
                        ? JSONObject.NULL : new JSONObject(lastPhysicalTapEvidence.toString()))
                .put("attempts", attempts)
                .put("firstAttemptFailure", firstAttemptFailure == null ? JSONObject.NULL : firstAttemptFailure)
                .put("openWaitFailure", failure == null ? JSONObject.NULL : failure);
        emitArtifact(runId, "composer-launcher-after-" + evidenceSuffix + ".json",
                after.toString().getBytes(StandardCharsets.UTF_8));
        if ("reopen".equals(evidenceSuffix) || "uncertain-first-attach".equals(evidenceSuffix)) {
            emitCurrentScreen(runId, "composer-launcher-after-" + evidenceSuffix + ".png");
        }
        Log.i("PS2857Launcher", "AFTER_REOPEN|" + evidenceSuffix + "|" + runId + "|" + after);
        checkpoint(checkpointPrefix + "-after-physical-tap-evidence");
        if (failure != null) {
            throw new AssertionError("physical composer launcher tap did not open the dialog; same-run evidence: "
                    + after, new AssertionError(failure));
        }
    }

    private JSONObject awaitComposerLauncherImeGeometry(String evidenceSuffix) throws Exception {
        final long timeoutMillis = 8_000;
        final long deadline = SystemClock.uptimeMillis() + timeoutMillis;
        JSONObject previousCoherent = null;
        JSONObject latest = null;
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                JSONObject sample = composerLauncherSnapshot("ime-geometry-settle", deadline);
                if (SystemClock.uptimeMillis() >= deadline) {
                    throw new TimeoutException("complete DOM/native sample arrived after the settle deadline");
                }
                latest = sample;
            } catch (TimeoutException timeout) {
                throw composerLauncherImeGeometryTimeout(evidenceSuffix, timeoutMillis, latest, timeout);
            }
            if (composerLauncherImeGeometryIsCoherent(latest)) {
                if (previousCoherent != null && composerLauncherImeGeometryIsStable(previousCoherent, latest)) {
                    if (SystemClock.uptimeMillis() >= deadline) {
                        throw composerLauncherImeGeometryTimeout(evidenceSuffix, timeoutMillis, latest,
                                new TimeoutException("second coherent sample completed after the settle deadline"));
                    }
                    return latest;
                }
                previousCoherent = latest;
            } else {
                previousCoherent = null;
            }
            long remainingMillis = deadline - SystemClock.uptimeMillis();
            if (remainingMillis <= 0) break;
            SystemClock.sleep(Math.min(120, remainingMillis));
        }
        throw composerLauncherImeGeometryTimeout(evidenceSuffix, timeoutMillis, latest, null);
    }

    private AssertionError composerLauncherImeGeometryTimeout(String evidenceSuffix, long timeoutMillis,
                                                              JSONObject latest, TimeoutException cause) {
        String message = "Android IME and WebView geometry did not settle to two consecutive coherent samples before "
                + "the " + evidenceSuffix + " composer launcher baseline within " + timeoutMillis
                + "ms; last full DOM/native snapshot=" + latest;
        return cause == null ? new AssertionError(message) : new AssertionError(message, cause);
    }

    private boolean composerLauncherImeGeometryIsCoherent(JSONObject state) throws JSONException {
        JSONObject dom = state.getJSONObject("dom");
        JSONObject nativeState = state.getJSONObject("native");
        boolean keyboardVisible = dom.optBoolean("keyboardVisible");
        if (keyboardVisible != nativeState.optBoolean("imeVisible")) return false;
        if (!keyboardVisible) return true;

        JSONObject viewport = dom.optJSONObject("visualViewport");
        if (viewport == null) return false;
        double cssWidth = viewport.optDouble("width");
        double cssHeight = viewport.optDouble("height");
        double webViewWidth = nativeState.optDouble("webViewWidthPx");
        double webViewHeight = nativeState.optDouble("webViewHeightPx");
        if (cssWidth <= 0 || cssHeight <= 0 || webViewWidth <= 0 || webViewHeight <= 0) return false;
        double scaleX = webViewWidth / cssWidth;
        double scaleY = webViewHeight / cssHeight;
        return Math.abs(scaleX - scaleY) <= Math.max(scaleX, scaleY) * 0.02;
    }

    private boolean composerLauncherImeGeometryIsStable(JSONObject previous, JSONObject current)
            throws JSONException {
        JSONObject previousDom = previous.getJSONObject("dom");
        JSONObject currentDom = current.getJSONObject("dom");
        JSONObject previousNative = previous.getJSONObject("native");
        JSONObject currentNative = current.getJSONObject("native");
        if (previousDom.optBoolean("keyboardVisible") != currentDom.optBoolean("keyboardVisible")) return false;
        return approximatelyEqual(previousDom.getJSONObject("visualViewport").optDouble("width"),
                currentDom.getJSONObject("visualViewport").optDouble("width"), 0.02)
                && approximatelyEqual(previousDom.getJSONObject("visualViewport").optDouble("height"),
                        currentDom.getJSONObject("visualViewport").optDouble("height"), 0.02)
                && approximatelyEqual(previousNative.optDouble("webViewWidthPx"),
                        currentNative.optDouble("webViewWidthPx"), 0.02)
                && approximatelyEqual(previousNative.optDouble("webViewHeightPx"),
                        currentNative.optDouble("webViewHeightPx"), 0.02);
    }

    private boolean approximatelyEqual(double left, double right, double toleranceFraction) {
        if (!Double.isFinite(left) || !Double.isFinite(right) || left <= 0 || right <= 0) return false;
        return Math.abs(left - right) <= Math.max(left, right) * toleranceFraction;
    }

    private JSONObject launcherAttemptEvidence(int attempt, String failure, JSONObject state) throws JSONException {
        return new JSONObject().put("attempt", attempt)
                .put("physicalTap", lastPhysicalTapEvidence == null
                        ? JSONObject.NULL : new JSONObject(lastPhysicalTapEvidence.toString()))
                .put("state", state)
                .put("failure", failure == null ? JSONObject.NULL : failure);
    }

    private boolean launcherRetryAfterImeResize(JSONObject before, JSONObject state) throws JSONException {
        JSONObject dom = state.getJSONObject("dom");
        JSONObject nativeState = state.getJSONObject("native");
        int initialWebViewHeight = before.getJSONObject("native").optInt("webViewHeightPx");
        return "home".equals(dom.optString("route"))
                && "live".equals(dom.optString("homeSurface"))
                && "live".equals(dom.optString("sshPhase"))
                && !dom.optBoolean("composerPresent")
                && dom.optBoolean("launcherPresent")
                && dom.optBoolean("launcherVisible")
                && !dom.optBoolean("launcherDisabled")
                && dom.optBoolean("keyboardVisible")
                && nativeState.optBoolean("imeVisible")
                && nativeState.optInt("webViewHeightPx") < initialWebViewHeight;
    }

    private JSONObject composerLauncherSnapshot(String stage) throws Exception {
        return composerLauncherSnapshot(stage, 0L);
    }

    private JSONObject composerLauncherSnapshot(String stage, long deadlineUptimeMillis) throws Exception {
        String domExpression = "(() => {const shell=document.querySelector('.app-shell');"
                + "const launcher=document.querySelector('[data-testid=prompt-composer-launcher]');"
                + "const composer=document.querySelector('[data-testid=prompt-composer]');"
                + "const rect=launcher?.getBoundingClientRect();const x=rect?rect.left+rect.width/2:0;"
                + "const y=rect?rect.top+rect.height/2:0;const hit=launcher?document.elementFromPoint(x,y):null;"
                + "const label=node=>node?{tag:node.tagName||'',id:node.id||'',testid:node.getAttribute?.('data-testid')||'',"
                + "className:typeof node.className==='string'?node.className:''}:null;"
                + "const bounds=r=>r?{left:r.left,top:r.top,right:r.right,bottom:r.bottom,width:r.width,height:r.height}:null;"
                + "const active=document.activeElement;return JSON.stringify({stage:" + JSONObject.quote(stage)
                + ",route:shell?.dataset.route??null,homeSurface:shell?.dataset.homeSurface??null,"
                + "sshPhase:shell?.dataset.sshPhase??null,keyboardVisible:shell?.dataset.keyboardVisible==='true',"
                + "composerPresent:!!composer,composerVisible:!!composer&&composer.getClientRects().length>0,"
                + "composerRole:composer?.getAttribute('role')??null,composerAriaModal:composer?.getAttribute('aria-modal')??null,"
                + "launcherPresent:!!launcher,launcherVisible:!!launcher&&launcher.getClientRects().length>0,"
                + "launcherDisabled:!launcher||!!launcher.disabled,launcherAriaDisabled:launcher?.getAttribute('aria-disabled')??null,"
                + "launcherBounds:bounds(rect),launcherCenter:{x,y},centerHit:label(hit),"
                + "centerHitMatchesLauncher:!!launcher&&!!hit?.closest?.('[data-testid=prompt-composer-launcher]'),"
                + "activeElement:label(active),documentHasFocus:document.hasFocus(),"
                + "visualViewport:{width:window.visualViewport?.width??innerWidth,height:window.visualViewport?.height??innerHeight,"
                + "offsetLeft:window.visualViewport?.offsetLeft??0,offsetTop:window.visualViewport?.offsetTop??0},"
                + "innerWidth,innerHeight,pointerEvents:(window.__ps2857ComposerLauncherPointerEvents||[]).slice(-20)});})()";
        boolean enforceDeadline = deadlineUptimeMillis > 0;
        JSONObject dom = enforceDeadline
                ? evalJsonBeforeDeadline(domExpression, deadlineUptimeMillis, "launcher " + stage)
                : evalJson(domExpression);
        AtomicReference<JSONObject> nativeState = new AtomicReference<>();
        Consumer<MainActivity> readNativeState = activity -> {
            View decor = activity.getWindow().getDecorView();
            WebView webView = packagedWebView;
            WindowInsets insets = decor.getRootWindowInsets();
            int[] location = new int[2];
            if (webView != null) webView.getLocationOnScreen(location);
            View focusedView = decor.findFocus();
            try {
                nativeState.set(new JSONObject().put("activityClass", activity.getClass().getName())
                        .put("activityFinishing", activity.isFinishing()).put("activityDestroyed", activity.isDestroyed())
                        .put("windowHasFocus", decor.hasWindowFocus())
                        .put("windowFocusedView", focusedView == null ? "" : focusedView.getClass().getName())
                        .put("webViewPresent", webView != null).put("webViewHasFocus", webView != null && webView.hasFocus())
                        .put("webViewScreenX", location[0]).put("webViewScreenY", location[1])
                        .put("webViewWidthPx", webView == null ? 0 : webView.getWidth())
                        .put("webViewHeightPx", webView == null ? 0 : webView.getHeight())
                        .put("imeVisible", Build.VERSION.SDK_INT >= 30 && insets != null
                                && insets.isVisible(WindowInsets.Type.ime()))
                        .put("imeBottomPx", Build.VERSION.SDK_INT >= 30 && insets != null
                                ? insets.getInsets(WindowInsets.Type.ime()).bottom : 0));
            } catch (JSONException error) {
                throw new RuntimeException(error);
            }
        };
        if (enforceDeadline) {
            onMainActivityBeforeDeadline(readNativeState, deadlineUptimeMillis, "launcher " + stage);
            if (SystemClock.uptimeMillis() >= deadlineUptimeMillis) {
                throw new TimeoutException("launcher " + stage + " native sample completed after the settle deadline");
            }
        } else {
            onMainActivity(readNativeState);
        }
        return new JSONObject().put("runId", artifactRunId).put("native", nativeState.get()).put("dom", dom);
    }

    private JSONObject evalJsonBeforeDeadline(String expression, long deadlineUptimeMillis, String context)
            throws Exception {
        return new JSONObject(evalStringBeforeDeadline(expression, deadlineUptimeMillis, context));
    }

    private String evalStringBeforeDeadline(String expression, long deadlineUptimeMillis, String context)
            throws Exception {
        String raw = evalRawBeforeDeadline(expression, deadlineUptimeMillis, context);
        Object decoded = new JSONTokener(raw).nextValue();
        return decoded == null ? null : decoded.toString();
    }

    private String evalRawBeforeDeadline(String expression, long deadlineUptimeMillis, String context)
            throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        AtomicLong callbackUptimeMillis = new AtomicLong(Long.MAX_VALUE);
        WebView webView = packagedWebView;
        assertNotNull("packaged Capacitor activity must contain a WebView", webView);
        assertTrue("packaged WebView rejected JavaScript evaluation", webView.post(() ->
                webView.evaluateJavascript(expression, value -> {
                    result.set(value);
                    callbackUptimeMillis.set(SystemClock.uptimeMillis());
                    latch.countDown();
                })));
        long remainingMillis = deadlineUptimeMillis - SystemClock.uptimeMillis();
        if (remainingMillis <= 0) {
            throw new TimeoutException(context + " exhausted its geometry settle deadline before awaiting WebView JavaScript");
        }
        boolean completed = latch.await(remainingMillis, TimeUnit.MILLISECONDS);
        long observedAt = SystemClock.uptimeMillis();
        if (!completed || callbackUptimeMillis.get() >= deadlineUptimeMillis || observedAt >= deadlineUptimeMillis) {
            throw new TimeoutException(context + " WebView JavaScript did not complete before the geometry settle deadline"
                    + " (callbackUptimeMillis=" + callbackUptimeMillis.get() + ", observedAt=" + observedAt + ")");
        }
        if (result.get() == null || "null".equals(result.get())) {
            throw new JSONException("JavaScript returned null during " + context + ": " + expression);
        }
        return result.get();
    }

    private void onMainActivityBeforeDeadline(Consumer<MainActivity> action, long deadlineUptimeMillis,
                                              String context) throws Exception {
        MainActivity activity = packagedActivity;
        assertNotNull("packaged MainActivity must remain available during the journey", activity);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicLong callbackUptimeMillis = new AtomicLong(Long.MAX_VALUE);
        activity.runOnUiThread(() -> {
            try {
                action.accept(activity);
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                callbackUptimeMillis.set(SystemClock.uptimeMillis());
                latch.countDown();
            }
        });
        long remainingMillis = deadlineUptimeMillis - SystemClock.uptimeMillis();
        if (remainingMillis <= 0) {
            throw new TimeoutException(context + " exhausted its geometry settle deadline before the native sample");
        }
        boolean completed = latch.await(remainingMillis, TimeUnit.MILLISECONDS);
        long observedAt = SystemClock.uptimeMillis();
        if (!completed || callbackUptimeMillis.get() >= deadlineUptimeMillis || observedAt >= deadlineUptimeMillis) {
            throw new TimeoutException(context + " Android native snapshot did not complete before the geometry settle deadline"
                    + " (callbackUptimeMillis=" + callbackUptimeMillis.get() + ", observedAt=" + observedAt + ")");
        }
        Throwable callbackFailure = failure.get();
        if (callbackFailure instanceof Exception) throw (Exception) callbackFailure;
        if (callbackFailure instanceof Error) throw (Error) callbackFailure;
        if (callbackFailure != null) throw new RuntimeException(callbackFailure);
    }

    private void setVoicePreferencesForComposerJourney() throws Exception {
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'home'");
        tapDomCenter("[aria-label=Settings]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'settings'");
        tapDomCenter("[data-testid=open-voice-settings]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'settings-voice'");
        setValue("[data-testid=setting-voice-language]", "de");
        awaitJsTrue("JSON.parse(localStorage.getItem('pocketshell.js.settings.v1') || '{}').voiceLanguage === 'de'");
        tapDomCenter("[aria-label=Back]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'settings'");
        tapDomCenter("[data-testid=open-advanced-settings]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'settings-advanced'");
        setValue("[data-testid=setting-voice-silence-seconds]", "9");
        awaitJsTrue("JSON.parse(localStorage.getItem('pocketshell.js.settings.v1') || '{}').voiceSilenceSeconds === 9");
        tapDomCenter("[aria-label=Back]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'settings'");
        tapDomCenter("[aria-label=Back]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'home'");
    }

    /** Replace only the Capacitor speech bridge for this packaged test; other native plugins stay real. */
    private void installControlledSpeechAdapter() throws Exception {
        String installed = evalString("(() => {"
                + "const cap=window.Capacitor;"
                + "if(!cap||typeof cap.nativePromise!=='function'||typeof cap.nativeCallback!=='function')return 'missing-capacitor-bridge';"
                + "const nativePromise=cap.nativePromise.bind(cap);const nativeCallback=cap.nativeCallback.bind(cap);"
                + "const state={startOptions:null,stopOptions:null,requestId:null,listener:null,"
                + "emit(type,text){if(!this.listener)throw new Error('speech listener is not registered');"
                + "this.listener({requestId:this.requestId,type,...(text===undefined?{}:{text})});}};"
                + "window.__ps2857ControlledSpeech=state;"
                + "cap.nativePromise=(plugin,method,options)=>{"
                + "if(plugin!=='SpeechRecognition')return nativePromise(plugin,method,options);"
                + "if(method==='startDictation'){state.startOptions=JSON.parse(JSON.stringify(options));state.stopOptions=null;state.requestId=options.requestId;"
                + "return Promise.resolve({requestId:state.requestId,started:true});}"
                + "if(method==='stopDictation'){state.stopOptions=JSON.parse(JSON.stringify(options));"
                + "return Promise.resolve({requestId:options.requestId,stopped:true});}"
                + "if(method==='getCapabilities')return Promise.resolve({speechRecognitionAvailable:true,microphonePermissionGranted:true});"
                + "return Promise.reject(new Error('unexpected controlled speech method '+method));};"
                + "cap.nativeCallback=(plugin,method,options,callback)=>{"
                + "if(plugin!=='SpeechRecognition')return nativeCallback(plugin,method,options,callback);"
                + "if(method==='addListener'){state.listener=callback;return Promise.resolve({callbackId:'controlled-dictation'});}"
                + "if(method==='removeListener')return Promise.resolve({removed:true});"
                + "return Promise.reject(new Error('unexpected controlled speech callback '+method));};"
                + "return 'installed';})()");
        assertEquals("test must replace only the speech bridge", "installed", installed);
    }

    private void exerciseInlineTerminalDictation(String nameBase, String sessionName, String targetChangeSession,
            String artifactRunId) throws Exception {
        awaitJsTrue("!document.querySelector('[data-testid=prompt-composer]')");
        awaitJsTrue("!!document.querySelector('[data-testid=inline-dictation-toggle]')"
                + " && document.querySelector('[data-testid=inline-dictation-toggle]')?.disabled === false");
        String inlineMarker = "PS2857_INLINE_" + nameBase;
        String inlineCommand = "printf '%s' 'café 🧪' | od -An -tx1 | tr -d '[:space:]' > /tmp/"
                + sessionName + "-inline-utf8.hex; printf '%s' '" + inlineMarker + "' > /tmp/"
                + sessionName + "-inline-submitted.marker";
        int acknowledgementsBefore = terminalInputAcknowledgements();

        checkpoint("inline-dictation-before-start-tap");
        tapDomCenter("[data-testid=inline-dictation-toggle]");
        checkpoint("inline-dictation-start-tap-returned");
        awaitJsTrue("document.querySelector('[data-testid=inline-dictation-bar]')?.dataset.phase === 'listening'"
                + " && document.querySelector('[data-testid=inline-dictation-toggle]')?.dataset.micState === 'listening'"
                + " && document.querySelector('[data-testid=inline-dictation-toggle]')?.getAttribute('aria-label')?.includes('Stop dictation and insert at terminal cursor')");
        JSONObject start = evalJson("JSON.stringify(window.__ps2857ControlledSpeech?.startOptions ?? null)");
        assertEquals("inline dictation must use the saved Voice language", "de", start.getString("languageTag"));
        assertEquals("inline dictation must use the saved Voice silence window", 9_000,
                start.getInt("silenceWindowMs"));
        String requestId = start.getString("requestId");

        evalString("window.__ps2857ControlledSpeech.emit('partial', " + JSONObject.quote(inlineCommand) + "); 'partial emitted'");
        awaitJsTrue("document.querySelector('[data-testid=inline-dictation-preview]')?.textContent.trim() === "
                + JSONObject.quote(inlineCommand));
        assertEquals("partial recognition must not write to the PTY", acknowledgementsBefore, terminalInputAcknowledgements());
        saveInlineDictationPreview(artifactRunId);

        tapDomCenter("[data-testid=inline-dictation-toggle]");
        awaitJsTrue("window.__ps2857ControlledSpeech?.stopOptions?.requestId === " + JSONObject.quote(requestId));
        assertEquals("Stop request alone must not write an unfinalized transcript", acknowledgementsBefore,
                terminalInputAcknowledgements());
        evalString("window.__ps2857ControlledSpeech.emit('result', " + JSONObject.quote(inlineCommand) + "); 'final emitted'");
        assertEquals("final text remains staged until the recognizer stops", acknowledgementsBefore,
                terminalInputAcknowledgements());
        evalString("window.__ps2857ControlledSpeech.emit('stopped'); 'stopped emitted'");
        awaitJsTrue("document.querySelector('[data-testid=inline-dictation-bar]')?.dataset.phase === 'idle'"
                + " && !document.querySelector('[data-testid=inline-dictation-status]')"
                + " && Number(document.querySelector('.app-shell')?.dataset.sshTerminalInputAcks) === "
                + (acknowledgementsBefore + 1), 15_000);
        awaitJsTrue("document.activeElement?.classList.contains('xterm-helper-textarea')", 5_000);

        Log.i("PS2857Inline", "RUN|" + artifactRunId + "|" + evalString("JSON.stringify({start:"
                + "window.__ps2857ControlledSpeech.startOptions,stop:window.__ps2857ControlledSpeech.stopOptions,"
                + "partialWriteAcks:" + acknowledgementsBefore + ",insertedWriteAcks:"
                + "Number(document.querySelector('.app-shell')?.dataset.sshTerminalInputAcks),text:"
                + JSONObject.quote(inlineCommand) + "})"));
        // The dictated command is now at the shell cursor but has not been
        // submitted. Enter is a separate, explicit user action.
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER);
        awaitJsTrue("Number(document.querySelector('.app-shell')?.dataset.sshTerminalInputAcks) === "
                + (acknowledgementsBefore + 2), 10_000);

        exerciseBackgroundDictationCancellation(nameBase, sessionName, artifactRunId);
        exerciseTargetChangeDictationCancellation(nameBase, sessionName, targetChangeSession, artifactRunId);
    }

    private void exerciseBackgroundDictationCancellation(String nameBase, String sessionName, String artifactRunId)
            throws Exception {
        String partialMarker = "PS2857_BG_PARTIAL_" + nameBase;
        String lateMarker = "PS2857_BG_LATE_" + nameBase;
        String partial = "printf '%s' '" + partialMarker + "'";
        String lateCommand = "printf '%s' '" + lateMarker + "' > /tmp/" + sessionName + "-inline-background.marker";
        int acknowledgementsBefore = terminalInputAcknowledgements();

        click("[data-testid=inline-dictation-toggle]");
        awaitJsTrue("document.querySelector('[data-testid=inline-dictation-bar]')?.dataset.phase === 'listening'");
        JSONObject start = evalJson("JSON.stringify(window.__ps2857ControlledSpeech?.startOptions ?? null)");
        String requestId = start.getString("requestId");
        evalString("window.__ps2857ControlledSpeech.emit('partial', " + JSONObject.quote(partial) + "); 'partial emitted'");
        awaitJsTrue("document.querySelector('[data-testid=inline-dictation-preview]')?.textContent.trim() === "
                + JSONObject.quote(partial));
        assertEquals("background partial must remain local", acknowledgementsBefore, terminalInputAcknowledgements());

        JSONObject backgroundTrace = backgroundAndResumeApp("inline-terminal-dictation");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.sshPhase === 'live'", 15_000);
        awaitJsTrue("window.__ps2857ControlledSpeech?.stopOptions?.requestId === "
                + JSONObject.quote(requestId), 15_000);
        checkpoint("inline-dictation-background-cancellation-observed");
        evalString("window.__ps2857ControlledSpeech.emit('result', " + JSONObject.quote(lateCommand) + "); 'late final emitted'");
        evalString("window.__ps2857ControlledSpeech.emit('stopped'); 'late stopped emitted'");
        awaitJsTrue("document.querySelector('[data-testid=inline-dictation-bar]')?.dataset.phase === 'idle'"
                + " && Number(document.querySelector('.app-shell')?.dataset.sshTerminalInputAcks) === "
                + acknowledgementsBefore, 15_000);
        assertEquals("the mounted inline bar must request speech stop on native background", requestId,
                evalString("window.__ps2857ControlledSpeech?.stopOptions?.requestId ?? ''"));
        backgroundTrace.put("dictationCancelledAfterResume", "idle".equals(
                evalString("document.querySelector('[data-testid=inline-dictation-bar]')?.dataset.phase ?? ''")));
        backgroundTrace.put("lateResultIgnored", terminalInputAcknowledgements() == acknowledgementsBefore);
        Log.i("PS2857Lifecycle", "INLINE_BACKGROUND_PROOF|" + artifactRunId + "|" + backgroundTrace);
        Log.i("PS2857Inline", "BACKGROUND_CANCELLED|" + artifactRunId + "|request=" + requestId
                + "|partial=" + partialMarker + "|late=" + lateMarker + "|acks=" + acknowledgementsBefore);

        String recoveryMarker = "PS2857_BG_RECOVERY_" + nameBase;
        String recoveryCommand = "printf '%s' '" + recoveryMarker + "' > /tmp/" + sessionName
                + "-inline-background-recovery.marker";
        startFreshInlineDictation(recoveryCommand, acknowledgementsBefore);
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER);
        awaitJsTrue("Number(document.querySelector('.app-shell')?.dataset.sshTerminalInputAcks) === "
                + (acknowledgementsBefore + 2), 10_000);
        Log.i("PS2857Inline", "BACKGROUND_RECOVERY|" + artifactRunId + "|marker=" + recoveryMarker);
    }

    private void exerciseTargetChangeDictationCancellation(String nameBase, String sessionName,
            String targetChangeSession, String artifactRunId) throws Exception {
        String partialMarker = "PS2857_TARGET_PARTIAL_" + nameBase;
        String lateMarker = "PS2857_TARGET_LATE_" + nameBase;
        String partial = "printf '%s' '" + partialMarker + "'";
        String lateCommand = "printf '%s' '" + lateMarker + "' > /tmp/" + sessionName + "-inline-target-change.marker";
        int acknowledgementsBefore = terminalInputAcknowledgements();

        click("[data-testid=inline-dictation-toggle]");
        awaitJsTrue("document.querySelector('[data-testid=inline-dictation-bar]')?.dataset.phase === 'listening'");
        JSONObject start = evalJson("JSON.stringify(window.__ps2857ControlledSpeech?.startOptions ?? null)");
        String requestId = start.getString("requestId");
        evalString("window.__ps2857ControlledSpeech.emit('partial', " + JSONObject.quote(partial) + "); 'partial emitted'");
        awaitJsTrue("document.querySelector('[data-testid=inline-dictation-preview]')?.textContent.trim() === "
                + JSONObject.quote(partial));
        assertEquals("target-change partial must remain local", acknowledgementsBefore, terminalInputAcknowledgements());

        // Switching the selected live session changes targetKey on the mounted
        // bar while its recognition callback remains capable of late delivery.
        attachSession(targetChangeSession);
        awaitJsTrue("window.__ps2857ControlledSpeech?.stopOptions?.requestId === "
                + JSONObject.quote(requestId), 15_000);
        checkpoint("inline-dictation-target-change-cancellation-observed");
        evalString("window.__ps2857ControlledSpeech.emit('result', " + JSONObject.quote(lateCommand) + "); 'late final emitted'");
        evalString("window.__ps2857ControlledSpeech.emit('stopped'); 'late stopped emitted'");
        awaitJsTrue("document.querySelector('[data-testid=inline-dictation-bar]')?.dataset.phase === 'idle'"
                + " && Number(document.querySelector('.app-shell')?.dataset.sshTerminalInputAcks) === "
                + acknowledgementsBefore, 15_000);
        assertEquals("the mounted inline bar must request speech stop when targetKey changes", requestId,
                evalString("window.__ps2857ControlledSpeech?.stopOptions?.requestId ?? ''"));
        awaitJsTrue("document.querySelector('[data-testid=inline-dictation-bar]')?.dataset.dictationTone === 'quiet'");
        Log.i("PS2857Inline", "TARGET_CANCELLED|" + artifactRunId + "|request=" + requestId
                + "|partial=" + partialMarker + "|late=" + lateMarker + "|acks=" + acknowledgementsBefore);
        attachSession(sessionName);
    }

    private void startFreshInlineDictation(String command, int acknowledgementsBefore) throws Exception {
        // Keep the original visible-control touchscreen proof in the happy path;
        // use the mounted button event here because Android may still be
        // settling its IME/window focus immediately after ActivityScenario resume.
        click("[data-testid=inline-dictation-toggle]");
        awaitJsTrue("document.querySelector('[data-testid=inline-dictation-bar]')?.dataset.phase === 'listening'");
        evalString("window.__ps2857ControlledSpeech.emit('partial', " + JSONObject.quote(command) + "); 'partial emitted'");
        awaitJsTrue("document.querySelector('[data-testid=inline-dictation-preview]')?.textContent.trim() === "
                + JSONObject.quote(command));
        evalString("window.__ps2857ControlledSpeech.emit('result', " + JSONObject.quote(command) + "); 'final emitted'");
        evalString("window.__ps2857ControlledSpeech.emit('stopped'); 'stopped emitted'");
        awaitJsTrue("document.querySelector('[data-testid=inline-dictation-bar]')?.dataset.phase === 'idle'"
                + " && !document.querySelector('[data-testid=inline-dictation-status]')"
                + " && Number(document.querySelector('.app-shell')?.dataset.sshTerminalInputAcks) === "
                + (acknowledgementsBefore + 1), 15_000);
    }

    private int terminalInputAcknowledgements() throws Exception {
        return Integer.parseInt(evalString("document.querySelector('.app-shell')?.dataset.sshTerminalInputAcks ?? '0'"));
    }

    private void saveInlineDictationPreview(String runId) throws Exception {
        awaitWebViewVisualState();
        AtomicReference<Boolean> saved = new AtomicReference<>(false);
        AtomicReference<byte[]> artifact = new AtomicReference<>();
        onMainActivity(activity -> {
            Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            if (screenshot == null) return;
            try {
                ByteArrayOutputStream encoded = new ByteArrayOutputStream();
                boolean compressed = screenshot.compress(Bitmap.CompressFormat.PNG, 100, encoded);
                byte[] png = encoded.toByteArray();
                if (compressed && png.length >= 1024) {
                    artifact.set(png);
                    saved.set(true);
                }
            } catch (Exception error) {
                throw new RuntimeException(error);
            } finally {
                screenshot.recycle();
            }
        });
        assertTrue("same-run inline dictation preview screenshot must be captured", saved.get());
        emitArtifact(runId, "inline-dictation-preview.png", artifact.get());
    }

    private JSONObject backgroundAndResumeApp(String scenarioName) throws Exception {
        checkpoint("background-before-home-" + scenarioName);
        android.app.UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        long downTime = SystemClock.uptimeMillis();
        KeyEvent homeDown = new KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME, 0);
        boolean downInjected = automation.injectInputEvent(homeDown, true);
        assertTrue("Android HOME key down must reach the system", downInjected);
        SystemClock.sleep(50);
        long upTime = SystemClock.uptimeMillis();
        KeyEvent homeUp = new KeyEvent(downTime, upTime, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HOME, 0);
        boolean upInjected = automation.injectInputEvent(homeUp, true);
        assertTrue("Android HOME key up must reach the system", upInjected);

        JSONObject background = awaitNativeWindowFocus(false);
        // HOME must background the actual activity before appStateChange
        // cancellation. Keep the IME snapshot as diagnostic evidence, but do
        // not gate on it: once the activity is stopped, its decor can report
        // cached root-window insets from the last focused frame.
        assertTrue("HOME must background the actual MainActivity before appStateChange cancellation: " + background,
                !background.getBoolean("windowFocus") && "CREATED".equals(background.getString("lifecycleState")));
        checkpoint("background-observed-" + scenarioName);
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.sshPhase === 'background'", 15_000);
        checkpoint("app-background-transition-observed-" + scenarioName);

        String targetPackage = InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName();
        String component = targetPackage + "/com.pocketshell.app.MainActivity";
        String launchOutput = new String(executeShellCommand("am start -W -n " + component), StandardCharsets.UTF_8).trim();
        assertTrue("relaunch the existing PocketShell task after HOME: " + launchOutput,
                launchOutput.contains("Status: ok"));
        JSONObject foreground = awaitNativeWindowFocus(true);
        assertTrue("component launch must return the real MainActivity to RESUMED: " + foreground,
                foreground.getBoolean("windowFocus") && "RESUMED".equals(foreground.getString("lifecycleState")));

        JSONObject trace = new JSONObject().put("runId", artifactRunId).put("scenario", scenarioName)
                .put("homeKeyDownInjected", downInjected).put("homeKeyUpInjected", upInjected)
                .put("background", background).put("foreground", foreground)
                .put("launchComponent", component).put("launchOutput", launchOutput)
                .put("homeDownUptimeMs", downTime).put("homeUpUptimeMs", upTime);
        Log.i("PS2857Lifecycle", "HOME_RESUME|" + artifactRunId + "|" + trace);
        checkpoint("foreground-observed-" + scenarioName);
        SystemClock.sleep(250);
        return trace;
    }

    private JSONObject awaitNativeWindowFocus(boolean expectedFocus) throws Exception {
        long deadline = SystemClock.uptimeMillis() + (expectedFocus ? 10_000 : 20_000);
        JSONObject last = new JSONObject();
        while (SystemClock.uptimeMillis() < deadline) {
            AtomicReference<JSONObject> snapshot = new AtomicReference<>();
            onMainActivity(activity -> {
                View decor = activity.getWindow().getDecorView();
                WindowInsets insets = decor.getRootWindowInsets();
                try {
                    snapshot.set(new JSONObject().put("windowFocus", decor.hasWindowFocus())
                            .put("lifecycleState", activity.getLifecycle().getCurrentState().name())
                            .put("imeVisible", insets != null && insets.isVisible(WindowInsets.Type.ime()))
                            .put("capturedAtUptimeMs", SystemClock.uptimeMillis()));
                } catch (JSONException error) {
                    throw new RuntimeException(error);
                }
            });
            last = snapshot.get();
            boolean matchingFocus = last != null && last.optBoolean("windowFocus") == expectedFocus;
            boolean matchingLifecycle = last != null && (expectedFocus
                    ? "RESUMED".equals(last.optString("lifecycleState"))
                    : "CREATED".equals(last.optString("lifecycleState")));
            if (matchingFocus && matchingLifecycle) return last;
            Thread.sleep(50);
        }
        throw new AssertionError("PocketShell window/lifecycle did not reach focus=" + expectedFocus
                + " after native HOME/launch: " + last);
    }

    private void exerciseComposerDictationMode(String runId, String nameBase) throws Exception {
        grantMicrophonePermissionForJourney();
        evalString("window.__ps2857DictationTestMode = true; 'debug dictation test mode enabled'");
        String original = "keep this typed draft " + nameBase;
        checkpoint("dictation-open-sheet");
        capturePromptComposerRoute(runId);
        tapDomCenter("[data-testid=prompt-composer-launcher]");
        awaitJsTrue("document.querySelector('[data-testid=prompt-composer]')?.getAttribute('role') === 'dialog'"
                + " && document.querySelector('[data-testid=prompt-composer]')?.getAttribute('aria-modal') === 'true'");
        setComposerDraft(original);
        assertGenericComposerTitleAndSessionChrome(runId);
        checkpoint("dictation-title-verified");
        checkpoint("dictation-before-editor-tap");
        tapDomCenter("[data-testid=prompt-draft]");
        awaitImeVisible(true);
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.keyboardVisible === 'true'");
        checkpoint("dictation-ime-open");
        checkpoint("dictation-before-prompt-action-tap");
        installComposerDictatePointerDownProbe();
        tapDomCenter("[data-testid=composer-dictate]");
        checkpoint("dictation-prompt-action-tapped");
        awaitJsTrue("document.querySelector('[data-testid=prompt-composer]')?.dataset.dictationState === 'recording'", 15_000);
        checkpoint("dictation-recording-visible");
        JSONObject dictatePointerDown = readComposerDictatePointerDownEvidence();
        Log.i("PS2857Checkpoint", "DICTATE_POINTERDOWN|" + runId + "|" + dictatePointerDown);
        assertTrue("keyboard-up Dictate tap must be a trusted touch on the mobile mic",
                dictatePointerDown.optBoolean("isTrusted")
                        && dictatePointerDown.optBoolean("targetMatchesButton")
                        && dictatePointerDown.optBoolean("keyboardVisibleAtPointerDown"));
        assertTrue("mobile Dictate target must prevent its cancelable pointerdown before click: " + dictatePointerDown,
                dictatePointerDown.optBoolean("cancelable")
                        && dictatePointerDown.optBoolean("defaultPreventedAtDocumentBubble"));
        checkpoint("dictation-before-ime-dismiss");
        awaitImeVisible(false);
        checkpoint("dictation-ime-dismissed");
        checkpoint("dictation-before-first-native-event");
        JSONObject nativeOptions = new JSONObject(injectDictationTestEvent("partial", "discard this dictated phrase"));
        checkpoint("dictation-first-native-event-returned");
        JSONObject savedVoiceSettings = new JSONObject(evalString("JSON.stringify(JSON.parse(localStorage.getItem('pocketshell.js.settings.v1') || '{}'))"));
        long expectedSilenceWindowMs = Math.round(savedVoiceSettings.optDouble("voiceSilenceSeconds", 4.0) * 1_000.0);
        assertEquals("the native adapter must receive the persisted silence setting on this start",
                expectedSilenceWindowMs, nativeOptions.getLong("silenceWindowMs"));
        String expectedLanguage = savedVoiceSettings.optString("voiceLanguage", "auto");
        if ("auto".equals(expectedLanguage)) {
            assertTrue("auto-detect must omit a fixed language hint", !nativeOptions.has("languageTag"));
        } else {
            assertEquals("the native adapter must receive the persisted language hint on this start",
                    expectedLanguage, nativeOptions.getString("languageTag"));
        }
        awaitJsTrue("document.querySelector('[data-testid=prompt-draft]')?.value.includes('discard this dictated phrase')");
        String recordingPredicate = "(() => {const draft=document.querySelector('[data-testid=prompt-draft]');"
                + "const style=draft&&getComputedStyle(draft);const mode=document.querySelector('[data-testid=composer-recording-mode]');"
                + "const actions=document.querySelector('[data-testid=composer-recording-actions]');"
                + "const cancel=actions?.querySelector('[data-testid=composer-recording-cancel]');"
                + "const preview=document.querySelector('[data-testid=composer-recording-preview]');"
                + "const stop=mode?.querySelector('[data-testid=composer-recording-stop]');"
                + "const header=mode?.querySelector('.recording-mode__live-row');"
                + "const stopStyle=stop&&getComputedStyle(stop);const stopRect=stop?.getBoundingClientRect();"
                + "const stopGlyph=stop?.querySelector(\"svg[aria-hidden='true'] > rect[x='6'][y='6'][width='12'][height='12'][rx='1'][fill='currentColor']\");"
                + "return draft?.classList.contains('composer-draft--dictation-anchor')===true"
                + " && style?.display!=='none' && style?.visibility!=='hidden' && style?.opacity==='0'"
                + " && draft?.getAttribute('aria-hidden')!=='true' && draft?.getAttribute('aria-readonly')==='true'"
                + " && draft?.getAttribute('aria-label')==='Prompt dictation draft, read only during capture'"
                + " && draft?.getBoundingClientRect().width<=1 && draft?.getBoundingClientRect().height<=1"
                + " && mode?.getClientRects().length>0 && preview?.textContent.includes('discard this dictated phrase')"
                + " && document.querySelector('[data-testid=composer-status]')?.textContent.includes('Your draft stays in the composer until you tap Insert or Send')"
                + " && actions?.getAttribute('role')==='group'"
                + " && Array.from(actions?.querySelectorAll('button')??[]).map(button=>button.getAttribute('data-testid')).join(',')"
                + "==='composer-recording-cancel,composer-insert,composer-dictation-send'"
                + " && cancel?.textContent.trim()==='Discard'"
                + " && cancel?.getAttribute('aria-label')==='Discard recording without transcribing'"
                + " && !!header&&header.contains(stop)"
                + " && stop?.innerText.trim()===''"
                + " && stop?.getAttribute('aria-label')==='Stop dictation and keep the recognized text in the editable draft'"
                + " && !!stop&&stop.getClientRects().length>0&&stopStyle?.display!=='none'"
                + " && stopStyle?.visibility!=='hidden'&&Number(stopStyle?.opacity??0)>0.95&&!stop.disabled"
                + " && !!stopRect&&Math.abs(stopRect.width-48.0)<0.5&&Math.abs(stopRect.height-48.0)<0.5"
                + " && !!stopGlyph;})()";
        try {
            awaitJsTrue(recordingPredicate, 10_000);
        } catch (AssertionError predicateFailure) {
            try {
                recordComposerModeState(runId, "recording", original + " discard this dictated phrase");
            } catch (Exception | AssertionError evidenceFailure) {
                predicateFailure.addSuppressed(evidenceFailure);
            }
            throw predicateFailure;
        }
        recordComposerModeState(runId, "recording", original + " discard this dictated phrase");
        assertTrue("recording surface must pair the elapsed timer and capture-state waveform like the Kotlin composer",
                "true".equals(evalRaw("(() => {const mode=document.querySelector('[data-testid=composer-recording-mode]');"
                        + "const row=mode?.querySelector('.recording-mode__live-row');"
                        + "const timer=row?.querySelector('[data-testid=composer-recording-timer]');"
                        + "const waveform=row?.querySelector('.recording-mode__waveform');"
                        + "const preview=mode?.querySelector('[data-testid=composer-recording-preview]');"
                        + "const tr=(node)=>node?.getBoundingClientRect();const timeRect=tr(timer);const waveRect=tr(waveform);"
                        + "return !!row&&!!timeRect&&!!waveRect&&!!preview&&timeRect.top<waveRect.bottom"
                        + " && timeRect.bottom>waveRect.top&&waveRect.width>0"
                        + " && waveform.getAttribute('aria-label').includes('not volume')"
                        + " && !mode.querySelector('[data-testid=composer-recording-actions]');})()")));
        String timer = evalString("document.querySelector('[data-testid=composer-recording-timer]')?.textContent.trim() ?? ''");
        assertTrue("recording mode must show a formatted elapsed timer", timer.matches("\\d{2}:\\d{2}"));

        tapDomCenter("[data-testid=composer-recording-cancel]");
        awaitJsTrue("document.querySelector('[data-testid=prompt-composer]')?.dataset.dictationState === 'idle'"
                + " && document.querySelector('[data-testid=prompt-draft]')?.value === " + JSONObject.quote(original));
        awaitImeVisible(false);
        recordComposerModeState(runId, "cancel", original);
        injectDictationTestEvent("partial", "late partial after Cancel must be ignored");
        SystemClock.sleep(250);
        assertEquals("Cancel must restore the original draft and ignore later native partials", original,
                evalString("document.querySelector('[data-testid=prompt-draft]')?.value ?? ''"));

        tapDomCenter("[data-testid=composer-dictate]");
        awaitJsTrue("document.querySelector('[data-testid=prompt-composer]')?.dataset.dictationState === 'recording'", 15_000);
        awaitImeVisible(false);
        injectDictationTestEvent("partial", "background must discard this partial");
        awaitJsTrue("document.querySelector('[data-testid=prompt-draft]')?.value.includes('background must discard this partial')");
        JSONObject backgroundTrace = backgroundAndResumeApp("prompt-dictation");
        awaitJsTrue("document.querySelector('[data-testid=prompt-composer]')?.dataset.dictationState === 'idle'"
                + " && document.querySelector('[data-testid=prompt-draft]')?.value === " + JSONObject.quote(original));
        backgroundTrace.put("dictationCancelledAfterResume", true);
        awaitImeVisible(false);
        injectDictationTestEvent("result", "late final after background must be ignored");
        SystemClock.sleep(250);
        String restoredDraft = evalString("document.querySelector('[data-testid=prompt-draft]')?.value ?? ''");
        assertEquals("background cancellation must restore the original draft and reject late final events", original,
                restoredDraft);
        backgroundTrace.put("draftRestored", original.equals(restoredDraft));
        backgroundTrace.put("lateResultIgnored", original.equals(restoredDraft));
        lastBackgroundLifecycleTrace = backgroundTrace;
        Log.i("PS2857Lifecycle", "PROMPT_BACKGROUND_PROOF|" + runId + "|" + backgroundTrace);
        recordComposerModeState(runId, "background", original);

        awaitConnectedLivePromptTarget("after-home-resume");

        String insertMarker = "PS2857_DICTATION_INSERT_" + nameBase;
        String insertCommand = "printf '%s' '" + insertMarker + "' > /tmp/" + bytesSession
                + "-dictation-insert.marker";
        String insertPreview = "Review the terminal output carefully.";
        setComposerDraft("");
        String insertWriteBaseline = evalString("document.querySelector('[data-testid=prompt-composer]')?.dataset.acknowledgedWrites ?? ''");
        checkpoint("dictation-insert-waiting-for-post-resume-target");
        awaitPromptDictateTargetSettled();
        checkpoint("dictation-insert-post-resume-target-settled");
        tapDomCenter("[data-testid=composer-dictate]");
        awaitJsTrue("document.querySelector('[data-testid=prompt-composer]')?.dataset.dictationState === 'recording'", 15_000);
        awaitImeVisible(false);
        injectDictationTestEvent("partial", insertPreview);
        awaitJsTrue("document.querySelector('[data-testid=prompt-draft]')?.value === " + JSONObject.quote(insertPreview));
        assertEquals("recording-time Insert must not write to the PTY before the explicit action", insertWriteBaseline,
                evalString("document.querySelector('[data-testid=prompt-composer]')?.dataset.acknowledgedWrites ?? ''"));
        recordComposerModeState(runId, "recording-insert", insertPreview, Integer.parseInt(insertWriteBaseline));
        injectDictationTestEvent("partial", insertCommand);
        awaitJsTrue("document.querySelector('[data-testid=prompt-draft]')?.value === " + JSONObject.quote(insertCommand));
        assertEquals("recording-time Insert must not write while its visible transcript is updated", insertWriteBaseline,
                evalString("document.querySelector('[data-testid=prompt-composer]')?.dataset.acknowledgedWrites ?? ''"));
        checkpoint("dictation-insert-before-tap");
        captureHostBeforeExplicitAction("recording-insert", insertCommand);
        tapDomCenter("[data-testid=composer-insert]");
        checkpoint("dictation-insert-tap-returned");
        awaitJsTrue("document.querySelector('[data-testid=prompt-composer]')?.dataset.dictationState === 'transcribing'"
                + " && document.querySelector('[data-testid=prompt-composer]')?.dataset.acknowledgedWrites === '0'"
                + " && document.querySelector('[data-testid=composer-status]')?.textContent.includes('Stopping dictation before Insert')");
        checkpoint("dictation-insert-transcribing");
        injectDictationTestEvent("finish", null);
        awaitInsertedAndCleared();
        tapDomCenter("[data-testid=composer-close]");
        awaitJsTrue("!document.querySelector('[data-testid=prompt-composer]')");
        tapDomCenter("[data-key-id=enter]");
        awaitJsTrue("Array.from(document.querySelectorAll('.terminal-viewport .xterm-rows > div'))"
                + ".map(row => row.textContent || '').join('').includes(" + JSONObject.quote(insertMarker) + ")", 10_000);

        awaitConnectedLivePromptTarget("before-transcribing-send-case");
        String transcribingSendMarker = "PS2857_DICTATION_TRANSCRIBING_SEND_" + nameBase;
        String transcribingSendCommand = "printf '%s' '" + transcribingSendMarker + "' > /tmp/" + bytesSession
                + "-dictation-transcribing-send.marker";
        String transcribingSendPreview = "Summarize this terminal output in two bullets.";
        setComposerDraft("");
        String transcribingSendWriteBaseline = evalString("document.querySelector('[data-testid=prompt-composer]')?.dataset.acknowledgedWrites ?? ''");
        tapDomCenter("[data-testid=composer-dictate]");
        awaitJsTrue("document.querySelector('[data-testid=prompt-composer]')?.dataset.dictationState === 'recording'", 15_000);
        awaitImeVisible(false);
        injectDictationTestEvent("partial", transcribingSendPreview);
        awaitJsTrue("document.querySelector('[data-testid=prompt-draft]')?.value === "
                + JSONObject.quote(transcribingSendPreview));
        assertEquals("transcribing-time Send must not write while recording", transcribingSendWriteBaseline,
                evalString("document.querySelector('[data-testid=prompt-composer]')?.dataset.acknowledgedWrites ?? ''"));
        tapDomCenter("[data-testid=composer-recording-stop]");
        awaitJsTrue("document.querySelector('[data-testid=prompt-composer]')?.dataset.dictationState === 'transcribing'");
        assertEquals("transcribing-time Send must not write before the explicit action", transcribingSendWriteBaseline,
                evalString("document.querySelector('[data-testid=prompt-composer]')?.dataset.acknowledgedWrites ?? ''"));
        recordComposerModeState(runId, "transcribing-send", transcribingSendPreview,
                Integer.parseInt(transcribingSendWriteBaseline));
        injectDictationTestEvent("partial", transcribingSendCommand);
        awaitJsTrue("document.querySelector('[data-testid=prompt-draft]')?.value === "
                + JSONObject.quote(transcribingSendCommand));
        assertEquals("transcribing-time Send must not write when recognition updates its visible transcript",
                transcribingSendWriteBaseline,
                evalString("document.querySelector('[data-testid=prompt-composer]')?.dataset.acknowledgedWrites ?? ''"));
        checkpoint("dictation-transcribing-send-before-tap");
        captureHostBeforeExplicitAction("transcribing-send", transcribingSendCommand);
        tapDomCenter("[data-testid=composer-dictation-send]");
        checkpoint("dictation-transcribing-send-tap-returned");
        awaitJsTrue("document.querySelector('[data-testid=prompt-composer]')?.dataset.dictationState === 'transcribing'"
                + " && document.querySelector('[data-testid=prompt-composer]')?.dataset.acknowledgedWrites === '0'"
                + " && document.querySelector('[data-testid=composer-status]')?.textContent.includes('Stopping dictation before Send')");
        checkpoint("dictation-transcribing-send-stopping");
        injectDictationTestEvent("finish", null);
        awaitDeliveredAndCleared();
        awaitJsTrue("document.querySelector('[data-testid=prompt-composer]')?.dataset.acknowledgedWrites === '2'");

        setComposerDraft("");
        String dictationWriteBaseline = evalString("document.querySelector('[data-testid=prompt-composer]')?.dataset.acknowledgedWrites ?? ''");
        // The writer count is incremented inside each acknowledgement, so it
        // can reach two before deliver() resumes and clears sendingIntent.
        // Wait for the actual idle, enabled Dictate target to settle before
        // this one-shot physical tap; do not retry or weaken the start proof.
        awaitJsTrue("(() => {const composer=document.querySelector('[data-testid=prompt-composer]');"
                + "const dictate=composer?.querySelector('[data-testid=composer-dictate]');"
                + "const status=document.querySelector('[data-testid=composer-status]');"
                + "return composer?.dataset.dictationState==='idle'&&!!dictate&&!dictate.disabled"
                + "&&status?.dataset.deliveryIntent==='';})()");
        awaitPromptDictateTargetSettled();
        tapDomCenter("[data-testid=composer-dictate]");
        awaitJsTrue("document.querySelector('[data-testid=prompt-composer]')?.dataset.dictationState === 'recording'", 15_000);
        awaitImeVisible(false);
        String dictationCommand = "Please summarize the latest terminal output in three bullet points.";
        injectDictationTestEvent("partial", dictationCommand);
        awaitJsTrue("document.querySelector('[data-testid=prompt-draft]')?.value.includes(" + JSONObject.quote(dictationCommand) + ")");
        String timerBeforeNaturalPause = evalString("document.querySelector('[data-testid=composer-recording-timer]')?.textContent.trim() ?? ''");
        injectDictationTestEvent("processing", null);
        String recordingAfterEndpoint = "(() => {const composer=document.querySelector('[data-testid=prompt-composer]');"
                + "const stop=document.querySelector('[data-testid=composer-recording-stop]');"
                + "return composer?.dataset.dictationState==='recording' && !!stop && !stop.disabled"
                + " && composer.dataset.acknowledgedWrites===" + JSONObject.quote(dictationWriteBaseline) + ";})()";
        try {
            awaitJsTrue(recordingAfterEndpoint, 5_000);
        } catch (AssertionError pauseFailure) {
            try {
                recordComposerModeState(runId, "recording", dictationCommand);
            } catch (Exception | AssertionError evidenceFailure) {
                pauseFailure.addSuppressed(evidenceFailure);
            }
            throw pauseFailure;
        }
        // A normal end-of-speech produces results and then a fresh ready/listening
        // pair. Keep the recording controls and timer through that restart.
        injectDictationTestEvent("result", null);
        injectDictationTestEvent("ready", null);
        injectDictationTestEvent("listening", null);
        SystemClock.sleep(1_200);
        String resumedRecording = "(() => {const composer=document.querySelector('[data-testid=prompt-composer]');"
                + "const stop=document.querySelector('[data-testid=composer-recording-stop]');"
                + "const timer=document.querySelector('[data-testid=composer-recording-timer]')?.textContent.trim()??'';"
                + "return composer?.dataset.dictationState==='recording' && !!stop && !stop.disabled"
                + " && timer!==" + JSONObject.quote(timerBeforeNaturalPause)
                + " && composer.dataset.acknowledgedWrites===" + JSONObject.quote(dictationWriteBaseline) + ";})()";
        try {
            awaitJsTrue(resumedRecording, 5_000);
        } catch (AssertionError restartFailure) {
            try {
                recordComposerModeState(runId, "recording", dictationCommand);
            } catch (Exception | AssertionError evidenceFailure) {
                restartFailure.addSuppressed(evidenceFailure);
            }
            throw restartFailure;
        }
        recordComposerModeState(runId, "recording-after-restart", dictationCommand);
        tapDomCenter("[data-testid=composer-recording-stop]");
        awaitJsTrue("document.querySelector('[data-testid=prompt-composer]')?.dataset.dictationState === 'transcribing'", 10_000);
        awaitImeVisible(false);
        recordComposerModeState(runId, "transcribing", dictationCommand);
        assertTrue("Stop must enter transcribing without sending the draft", "true".equals(evalRaw(
                "document.querySelector('[data-testid=composer-status]')?.textContent.includes('not be sent automatically')"
                        + " && document.querySelector('[data-testid=prompt-composer]')?.dataset.acknowledgedWrites === "
                        + JSONObject.quote(dictationWriteBaseline))));
        injectDictationTestEvent("finish", null);
        awaitJsTrue("document.querySelector('[data-testid=prompt-composer]')?.dataset.dictationState === 'review'"
                + " && document.querySelector('[data-testid=composer-dictation-review]')"
                + " && document.querySelector('[data-testid=prompt-draft]')?.readOnly === false"
                + " && document.querySelector('[data-testid=prompt-draft]')?.getAttribute('aria-readonly') === 'false'"
                + " && document.querySelector('[data-testid=composer-dictation-review]')?.textContent.includes('Transcript ready')", 10_000);
        recordComposerModeState(runId, "review", dictationCommand);
        assertEquals("Stop must keep recognized text in the editable review draft", dictationCommand,
                evalString("document.querySelector('[data-testid=prompt-draft]')?.value ?? ''"));
        checkpoint("dictation-stop-review-before-action");
        captureHostBeforeExplicitAction("stop-review", dictationCommand);

        String editedMarker = "PS2857_DICTATION_EDITED_" + nameBase;
        String editedCommand = "printf '%s' '" + editedMarker + "' > /tmp/" + bytesSession + "-dictation.marker";
        setComposerDraft(editedCommand);
        assertEquals("review draft must accept edits before delivery", editedCommand,
                evalString("document.querySelector('[data-testid=prompt-draft]')?.value ?? ''"));
        assertEquals("dictated text must not reach the terminal before explicit Send", dictationWriteBaseline,
                evalString("document.querySelector('[data-testid=prompt-composer]')?.dataset.acknowledgedWrites ?? ''"));
        awaitComposerReadyToSend(runId, editedCommand);
        tapDomCenter(".composer-shared-controls .send");
        awaitDeliveredAndCleared();
        // A completed one-line submit acknowledges the body and Enter as separate PTY writes.
        awaitJsTrue("document.querySelector('[data-testid=prompt-composer]')?.dataset.acknowledgedWrites === '2'");
        String sendEvidence = evalString("JSON.stringify({stage:'dictation-explicit-send',runId:"
                + JSONObject.quote(runId) + ",marker:" + JSONObject.quote(editedMarker)
                + ",dictationState:document.querySelector('[data-testid=prompt-composer]')?.dataset.dictationState??'',"
                + "acknowledgedWrites:Number(document.querySelector('[data-testid=prompt-composer]')?.dataset.acknowledgedWrites??0),"
                + "draft:document.querySelector('[data-testid=prompt-draft]')?.value??'',"
                + "deliveryStatus:document.querySelector('[data-testid=composer-status]')?.textContent.trim()??''})");
        byte[] sendEvidenceBytes = sendEvidence.getBytes(StandardCharsets.UTF_8);
        emitArtifact(runId, "composer-dictation-send.json", sendEvidenceBytes);
        JSONObject sent = new JSONObject(sendEvidence);
        assertTrue("only the explicit Send action may deliver the reviewed transcript",
                sent.getInt("acknowledgedWrites") == 2 && sent.getString("draft").isEmpty()
                        && sent.getString("dictationState").equals("idle")
                        && sent.getString("deliveryStatus").contains("Sent to the terminal"));
        exerciseComposerDictationFailureModes(runId);
        assertTrue("debug event injection must be reset after the packaged composer journey",
                "true".equals(evalRaw("(window.__ps2857DictationTestMode = false) === false")));
    }

    private void exerciseComposerDictationFailureModes(String runId) throws Exception {
        String errorBase = "keep typed text after recognition error";
        String errorPartial = "partial speech before the error";
        String errorDraft = errorBase + " " + errorPartial;
        setComposerDraft(errorBase);
        String errorWriteBaseline = evalString("document.querySelector('[data-testid=prompt-composer]')?.dataset.acknowledgedWrites ?? ''");
        tapDomCenter("[data-testid=composer-dictate]");
        awaitJsTrue("document.querySelector('[data-testid=prompt-composer]')?.dataset.dictationState === 'recording'", 15_000);
        awaitImeVisible(false);
        injectDictationTestEvent("partial", errorPartial);
        awaitJsTrue("document.querySelector('[data-testid=prompt-draft]')?.value === " + JSONObject.quote(errorDraft));
        injectDictationTestEvent("error", "recognizer-no-match");
        injectDictationTestEvent("finish", null);
        awaitJsTrue("document.querySelector('[data-testid=prompt-composer]')?.dataset.dictationState === 'review'"
                + " && document.querySelector('[data-testid=composer-dictation-review]')?.textContent.includes('Recognition stopped.')"
                + " && document.querySelector('[data-testid=composer-mode-status]')?.getAttribute('aria-label')"
                + " === 'Dictation ended with a recognition error'", 10_000);
        recordComposerModeState(runId, "review-error", errorDraft);
        assertDictationReviewCannotWrite(errorWriteBaseline, "recognition error");

        String emptyBase = "keep typed text after empty recognition";
        setComposerDraft(emptyBase);
        String emptyWriteBaseline = evalString("document.querySelector('[data-testid=prompt-composer]')?.dataset.acknowledgedWrites ?? ''");
        tapDomCenter("[data-testid=composer-dictate]");
        awaitJsTrue("document.querySelector('[data-testid=prompt-composer]')?.dataset.dictationState === 'recording'", 15_000);
        awaitImeVisible(false);
        tapDomCenter("[data-testid=composer-recording-stop]");
        awaitJsTrue("document.querySelector('[data-testid=prompt-composer]')?.dataset.dictationState === 'transcribing'");
        awaitImeVisible(false);
        injectDictationTestEvent("finish", null);
        awaitJsTrue("document.querySelector('[data-testid=prompt-composer]')?.dataset.dictationState === 'review'"
                + " && document.querySelector('[data-testid=composer-dictation-review]')?.textContent.includes('No speech recognized.')"
                + " && document.querySelector('[data-testid=composer-mode-status]')?.getAttribute('aria-label')"
                + " === 'No speech was recognized'", 10_000);
        recordComposerModeState(runId, "review-empty", emptyBase);
        assertDictationReviewCannotWrite(emptyWriteBaseline, "empty transcript");
    }

    private void assertDictationReviewCannotWrite(String writeBaseline, String description) throws Exception {
        String guarded = "(() => {const composer=document.querySelector('[data-testid=prompt-composer]');"
                + "const insert=document.querySelector('[data-testid=composer-insert]');"
                + "const send=document.querySelector('.composer-shared-controls .send');"
                + "return composer?.dataset.dictationState==='review' && !!insert&&insert.disabled"
                + " && !!send&&send.disabled && composer.dataset.acknowledgedWrites==="
                + JSONObject.quote(writeBaseline) + ";})()";
        assertTrue(description + " review must keep Insert and Send disabled without a deliberate text edit",
                "true".equals(evalRaw(guarded)));
        assertEquals(description + " must not write any transcript bytes to the PTY", writeBaseline,
                evalString("document.querySelector('[data-testid=prompt-composer]')?.dataset.acknowledgedWrites ?? ''"));
    }

    private void awaitComposerReadyToSend(String runId, String expectedDraft) throws Exception {
        String expression = "(() => {const shell=document.querySelector('.app-shell');"
                + "const composer=document.querySelector('[data-testid=prompt-composer]');"
                + "const draft=document.querySelector('[data-testid=prompt-draft]');"
                + "const send=document.querySelector('.composer-shared-controls .send');"
                + "return shell?.dataset.sshPhase==='live' && composer?.dataset.transportState==='connected'"
                + " && composer?.dataset.dictationState==='review' && draft?.value==="
                + JSONObject.quote(expectedDraft) + " && !!send && !send.disabled;})()";
        try {
            awaitJsTrue(expression, 30_000);
        } catch (AssertionError notReady) {
            String state = evalString("(() => {const rect=(node)=>{if(!node)return null;const r=node.getBoundingClientRect();"
                    + "return {x:r.x,y:r.y,width:r.width,height:r.height};};"
                    + "const shell=document.querySelector('.app-shell');const composer=document.querySelector('[data-testid=prompt-composer]');"
                    + "const draft=document.querySelector('[data-testid=prompt-draft]');const send=document.querySelector('.composer-shared-controls .send');"
                    + "return JSON.stringify({stage:'dictation-send-readiness-timeout',runId:" + JSONObject.quote(runId)
                    + ",sshPhase:shell?.dataset.sshPhase??'',transportState:composer?.dataset.transportState??'',"
                    + "dictationState:composer?.dataset.dictationState??'',sendDisabled:send?.disabled??null,send:rect(send),"
                    + "draft:draft?.value??'',draftFocused:document.activeElement===draft,keyboardVisible:shell?.dataset.keyboardVisible==='true',"
                    + "acknowledgedWrites:Number(composer?.dataset.acknowledgedWrites??0),status:document.querySelector('[data-testid=composer-status]')?.textContent.trim()??'',"
                    + "pageText:document.body.innerText});})() ");
            try {
                emitCurrentScreen(runId, "composer-send-readiness-timeout.png");
                emitArtifact(runId, "composer-send-readiness-timeout.json", state.getBytes(StandardCharsets.UTF_8));
            } catch (Exception evidenceFailure) {
                notReady.addSuppressed(evidenceFailure);
            }
            throw new AssertionError("composer did not reconnect and enable Send within 30 seconds: " + state, notReady);
        }
    }

    private void awaitConnectedLivePromptTarget(String stage) throws Exception {
        String ready = "(() => {const shell=document.querySelector('.app-shell');"
                + "const composer=document.querySelector('[data-testid=prompt-composer]');"
                + "const title=document.querySelector('#terminal-title')?.textContent??'';"
                + "return shell?.dataset.homeSurface==='live' && shell?.dataset.sshPhase==='live'"
                + " && (!composer || composer.dataset.transportState==='connected') && title.includes("
                + JSONObject.quote(bytesSession) + ");})()";
        checkpoint("dictation-waiting-for-connected-pty-" + stage);
        try {
            awaitJsTrue(ready, WAIT_TIMEOUT_MILLIS);
        } catch (AssertionError failure) {
            String state = evalString("(() => {const shell=document.querySelector('.app-shell');"
                    + "const composer=document.querySelector('[data-testid=prompt-composer]');"
                    + "return JSON.stringify({homeSurface:shell?.dataset.homeSurface??'',"
                    + "sshPhase:shell?.dataset.sshPhase??'',composerPresent:!!composer,"
                    + "transportState:composer?.dataset.transportState??'',"
                    + "terminalTitle:document.querySelector('#terminal-title')?.textContent??'',"
                    + "pageText:document.body.innerText});})()");
            throw new AssertionError("live PTY did not reconnect for " + stage + ": " + state, failure);
        }
        checkpoint("dictation-connected-pty-" + stage);
    }

    private void stopWebAnimationsBeforeScenarioClose() {
        WebView webView = packagedWebView;
        if (webView == null) return;
        CountDownLatch latch = new CountDownLatch(1);
        String expression = "(() => {let style=document.getElementById('js-composer-teardown-motion');"
                + "if(!style){style=document.createElement('style');style.id='js-composer-teardown-motion';"
                + "document.head.appendChild(style);}style.textContent='*,*::before,*::after{"
                + "animation:none!important;transition:none!important;scroll-behavior:auto!important}';"
                + "return 'motion disabled for teardown';})()";
        if (!webView.post(() -> webView.evaluateJavascript(expression, ignored -> latch.countDown()))) return;
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            Log.w("PS2857Teardown", "interrupted while disabling WebView motion before ActivityScenario close", error);
        }
    }

    private void capturePromptComposerRoute(String runId) throws Exception {
        awaitImeVisible(false);
        awaitWebViewVisualState();
        String report = evalString("(() => {const shell=document.querySelector('.app-shell');"
                + "const composer=document.querySelector('[data-testid=prompt-composer]');"
                + "const launcher=document.querySelector('[data-testid=prompt-composer-launcher]');"
                + "const promptIcon=launcher?.querySelector('svg');const promptIconStyle=promptIcon?getComputedStyle(promptIcon):null;"
                + "const inlineMic=document.querySelector('[data-testid=inline-dictation-toggle]');"
                + "const terminalDestination=inlineMic?.parentElement?.querySelector('[data-testid=inline-dictation-destination]');"
                + "const terminalDestinationLabels=terminalDestination?Array.from(terminalDestination.children).map(node=>node.textContent.trim()):[];"
                + "const inlineMicIcon=inlineMic?.querySelector('svg');const inlineMicIconStyle=inlineMicIcon?getComputedStyle(inlineMicIcon):null;"
                + "const visible=node=>!!node&&node.getClientRects().length>0"
                + "&&getComputedStyle(node).display!=='none'&&getComputedStyle(node).visibility!=='hidden';"
                + "const bounds=node=>{if(!node)return null;const r=node.getBoundingClientRect();return {top:r.top,bottom:r.bottom,left:r.left,right:r.right,width:r.width,height:r.height};};"
                + "const promptBounds=bounds(launcher),inlineMicBounds=bounds(inlineMic);"
                + "const hitCenter=node=>{if(!node)return false;const r=node.getBoundingClientRect(),hit=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2);return !!hit&&(hit===node||node.contains(hit));};"
                + "return JSON.stringify({route:shell?.dataset.route??'',homeSurface:shell?.dataset.homeSurface??'',"
                + "sshPhase:shell?.dataset.sshPhase??'',keyboardVisible:shell?.dataset.keyboardVisible==='true',"
                + "viewport:{width:window.visualViewport?.width??window.innerWidth,height:window.visualViewport?.height??window.innerHeight},"
                + "composerPresent:!!composer,launcherVisible:visible(launcher),launcherEnabled:!!launcher&&!launcher.disabled,"
                + "promptAccessibleName:launcher?.getAttribute('aria-label')??'',promptTitle:launcher?.getAttribute('title')??'',"
                + "promptVisibleText:launcher?.innerText.trim()??'',"
                + "promptIconVisible:!!promptIcon&&visible(promptIcon)&&Number.parseFloat(promptIconStyle?.opacity??'1')>0"
                + "&&promptIcon.getBoundingClientRect().width>0&&promptIcon.getBoundingClientRect().height>0,"
                + "promptCenterHit:hitCenter(launcher),inlineMicVisible:visible(inlineMic),"
                + "inlineMicEnabled:!!inlineMic&&!inlineMic.disabled,inlineMicLabel:inlineMic?.getAttribute('aria-label')??'',"
                + "inlineMicTitle:inlineMic?.getAttribute('title')??'',"
                + "inlineMicVisibleText:inlineMic?.innerText.trim()??'',"
                + "terminalDestinationLabels,terminalDestinationVisible:visible(terminalDestination),"
                + "inlineMicIconVisible:!!inlineMicIcon&&visible(inlineMicIcon)&&Number.parseFloat(inlineMicIconStyle?.opacity??'1')>0"
                + "&&inlineMicIcon.getBoundingClientRect().width>0&&inlineMicIcon.getBoundingClientRect().height>0,"
                + "inlineMicCenterHit:hitCenter(inlineMic),"
                + "targetsSeparated:!!promptBounds&&!!inlineMicBounds&&promptBounds.right<=inlineMicBounds.left,"
                + "terminalHeading:document.querySelector('#terminal-title')?.textContent.trim()??'',"
                + "promptBounds,inlineMicBounds});})() ");
        JSONObject state = new JSONObject(report).put("runId", runId)
                .put("expectedPromptAccessibleName", "Open prompt composer to type or dictate a prompt")
                .put("expectedPromptVisibleText", "Prompt")
                .put("expectedInlineMicLabel", "Dictate at terminal cursor")
                .put("expectedTerminalDestinationLabels", new JSONArray())
                .put("expectedSession", bytesSession);
        emitArtifact(runId, "composer-route.json", state.toString().getBytes(StandardCharsets.UTF_8));
        emitCurrentScreen(runId, "composer-route.png");
        assertTrue("idle terminal must expose an accessible Prompt composer separately from terminal dictation: " + state,
                "home".equals(state.getString("route")) && "live".equals(state.getString("homeSurface"))
                        && "live".equals(state.getString("sshPhase")) && !state.getBoolean("keyboardVisible")
                        && !state.getBoolean("composerPresent") && state.getBoolean("launcherVisible")
                        && state.getBoolean("launcherEnabled")
                        && "Open prompt composer to type or dictate a prompt".equals(state.getString("promptAccessibleName"))
                        && "Open prompt composer to type or dictate a prompt".equals(state.getString("promptTitle"))
                        && "Prompt".equals(state.getString("promptVisibleText"))
                        && state.getBoolean("promptIconVisible") && state.getBoolean("promptCenterHit")
                        && state.getBoolean("inlineMicVisible") && state.getBoolean("inlineMicEnabled")
                        && "Dictate at terminal cursor".equals(state.getString("inlineMicLabel"))
                        && "Dictate at terminal cursor".equals(state.getString("inlineMicTitle"))
                        && "Dictate".equals(state.getString("inlineMicVisibleText"))
                        && "[]".equals(state.getJSONArray("terminalDestinationLabels").toString())
                        && "[]".equals(state.getJSONArray("expectedTerminalDestinationLabels").toString())
                        && !state.getBoolean("terminalDestinationVisible")
                        && state.getBoolean("inlineMicIconVisible") && state.getBoolean("inlineMicCenterHit")
                        && state.getBoolean("targetsSeparated")
                        && state.getString("terminalHeading").contains(bytesSession)
                        && state.getJSONObject("promptBounds").getDouble("width") >= 48.0
                        && state.getJSONObject("promptBounds").getDouble("height") >= 48.0
                        && state.getJSONObject("inlineMicBounds").getDouble("width") >= 48.0
                        && state.getJSONObject("inlineMicBounds").getDouble("height") >= 48.0
                        && state.getJSONObject("promptBounds").getDouble("top") >= 0.0
                        && state.getJSONObject("promptBounds").getDouble("bottom")
                                <= state.getJSONObject("viewport").getDouble("height")
                        && state.getJSONObject("promptBounds").getDouble("left") >= 0.0
                        && state.getJSONObject("promptBounds").getDouble("right")
                                <= state.getJSONObject("viewport").getDouble("width")
                        && state.getJSONObject("inlineMicBounds").getDouble("top") >= 0.0
                        && state.getJSONObject("inlineMicBounds").getDouble("bottom")
                                <= state.getJSONObject("viewport").getDouble("height")
                        && state.getJSONObject("inlineMicBounds").getDouble("left") >= 0.0
                        && state.getJSONObject("inlineMicBounds").getDouble("right")
                                <= state.getJSONObject("viewport").getDouble("width"));
    }

    private void assertGenericComposerTitleAndSessionChrome(String runId) throws Exception {
        String visibleIdleSheet = "(() => {const composer=document.querySelector('[data-testid=prompt-composer]');"
                + "const scrim=document.querySelector('[data-testid=prompt-composer-scrim]');"
                + "const panel=composer?.getBoundingClientRect();const viewportHeight=window.visualViewport?.height??innerHeight;"
                + "const style=composer?getComputedStyle(composer):null;"
                + "return composer?.dataset.dictationState==='idle' && composer.getAttribute('role')==='dialog'"
                + " && composer.getAttribute('aria-modal')==='true' && !!scrim && scrim.getClientRects().length>0"
                + " && !!panel && panel.height>=160 && panel.top>=0 && panel.bottom<=viewportHeight+0.5"
                + " && style?.display!=='none' && style?.visibility!=='hidden' && Number(style?.opacity??0)>0.95;})()";
        awaitJsTrue(visibleIdleSheet, 15_000);
        awaitWebViewVisualState();
        JSONObject titleState = new JSONObject(evalString("(() => {const composer=document.querySelector('[data-testid=prompt-composer]');"
                + "const scrim=document.querySelector('[data-testid=prompt-composer-scrim]');"
                + "const shell=document.querySelector('.app-shell');"
                + "const rect=node=>{if(!node)return null;const r=node.getBoundingClientRect();return {top:r.top,bottom:r.bottom,left:r.left,right:r.right,width:r.width,height:r.height};};"
                + "const viewport={width:window.visualViewport?.width??innerWidth,height:window.visualViewport?.height??innerHeight};"
                + "const panelBounds=rect(composer);const scrimBounds=rect(scrim);"
                + "const heading=composer?.querySelector('.composer-heading__copy');const headingStyle=heading?getComputedStyle(heading):null;"
                + "const draftNode=composer?.querySelector('[data-testid=prompt-draft]');"
                + "const actionsNode=composer?.querySelector('[data-testid=composer-actions]');"
                + "const buttons={dictate:document.querySelector('[data-testid=composer-dictate]'),"
                + "insert:document.querySelector('[data-testid=composer-insert]'),send:document.querySelector('.composer-shared-controls .send'),"
                + "keys:composer?.querySelector('[data-testid=composer-open-keys]')};"
                + "const dictateLabel=buttons.dictate?(buttons.dictate.innerText||'').replace(/\\s+/g,' ').trim():'';"
                + "const dictateRect=buttons.dictate?.getBoundingClientRect();"
                + "const dictateStyle=buttons.dictate?getComputedStyle(buttons.dictate):null;"
                + "const dictateVisible=!!buttons.dictate&&!!dictateRect&&buttons.dictate.getClientRects().length>0"
                + "&&dictateStyle?.display!=='none'&&dictateStyle?.visibility!=='hidden'&&Number(dictateStyle?.opacity??0)>0.95;"
                + "return JSON.stringify({state:composer?.dataset.dictationState??'',"
                + "composerHeading:composer?.querySelector('#composer-title')?.textContent.trim()??'',"
                + "composerHeadingDisplay:headingStyle?.display??'',"
                + "composerHeadingVisible:!!heading&&heading.getClientRects().length>0&&headingStyle?.display!=='none'"
                + "&&headingStyle?.visibility!=='hidden'&&Number(headingStyle?.opacity??0)>0.95,"
                + "terminalHeading:document.querySelector('#terminal-title')?.textContent.trim()??'',"
                + "composerVisible:!!composer&&composer.getClientRects().length>0,"
                + "composerRole:composer?.getAttribute('role')??'',composerAriaModal:composer?.getAttribute('aria-modal')??'',"
                + "panelBounds,scrimBounds,viewport,keyboardVisible:shell?.dataset.keyboardVisible==='true',"
                + "draftBounds:rect(draftNode),actionsBounds:rect(actionsNode),"
                + "sheetFullyVisible:!!panelBounds&&panelBounds.top>=0&&panelBounds.bottom<=viewport.height+0.5,"
                + "draftText:document.querySelector('[data-testid=prompt-draft]')?.value??'',"
                + "dictatePromptText:dictateLabel,"
                + "dictatePromptGlyphPresent:!!buttons.dictate?.querySelector(\"svg[aria-hidden='true'] path[d^='M12 2a3']\"),"
                + "dictatePromptAccessibleName:buttons.dictate?.getAttribute('aria-label')??'',"
                + "dictatePromptVisible:dictateVisible,"
                + "dictatePromptBounds:dictateRect?{top:dictateRect.top,bottom:dictateRect.bottom,left:dictateRect.left,right:dictateRect.right,width:dictateRect.width,height:dictateRect.height}:null,"
                + "dictatePromptEnabled:!!buttons.dictate&&!buttons.dictate.disabled,"
                + "composerOpenKeysBounds:rect(buttons.keys),"
                + "buttons:Object.fromEntries(Object.entries(buttons).map(([name,node])=>[name,!!node&&node.getClientRects().length>0&&!node.disabled])),"
                + "screenScrollTop:document.querySelector('.screen-content')?.scrollTop??0,"
                + "documentScrollTop:document.documentElement.scrollTop??0});})()"));
        titleState.put("runId", runId);
        titleState.put("expectedComposerHeading", "Prompt Composer");
        titleState.put("expectedDictatePromptAccessibleName", "Dictate prompt draft");
        titleState.put("expectedDictatePromptVisibleLabel", "");
        titleState.put("expectedSessionChrome", bytesSession);
        emitArtifact(runId, "composer-title.json", titleState.toString().getBytes(StandardCharsets.UTF_8));
        emitCurrentScreen(runId, "composer-title.png");
        assertTrue("idle prompt composer sheet must be fully onscreen when title is captured: " + titleState,
                titleState.getBoolean("composerVisible") && titleState.getBoolean("sheetFullyVisible")
                        && titleState.getBoolean("keyboardVisible") && !titleState.getBoolean("composerHeadingVisible")
                        && "none".equals(titleState.getString("composerHeadingDisplay"))
                        && titleState.getJSONObject("buttons").getBoolean("dictate")
                        && titleState.getJSONObject("buttons").getBoolean("insert")
                        && titleState.getJSONObject("buttons").getBoolean("send")
                        && titleState.getJSONObject("buttons").getBoolean("keys")
                        && titleState.getString("dictatePromptText").isEmpty()
                        && "Dictate prompt draft".equals(titleState.getString("dictatePromptAccessibleName"))
                        && titleState.getBoolean("dictatePromptGlyphPresent")
                        && titleState.getBoolean("dictatePromptVisible")
                        && titleState.getBoolean("dictatePromptEnabled")
                        && titleState.getJSONObject("dictatePromptBounds").getDouble("width") >= 48.0
                        && titleState.getJSONObject("dictatePromptBounds").getDouble("width") < 49.0
                        && titleState.getJSONObject("dictatePromptBounds").getDouble("height") >= 48.0
                        && titleState.getJSONObject("dictatePromptBounds").getDouble("height") < 49.0
                        && titleState.getJSONObject("composerOpenKeysBounds").getDouble("width") >= 48.0
                        && titleState.getJSONObject("composerOpenKeysBounds").getDouble("width") < 49.0
                        && titleState.getJSONObject("composerOpenKeysBounds").getDouble("height") >= 48.0
                        && titleState.getJSONObject("composerOpenKeysBounds").getDouble("height") < 49.0
                        && titleState.getJSONObject("draftBounds").getDouble("left") >= 16.0
                        && titleState.getJSONObject("viewport").getDouble("width")
                                - titleState.getJSONObject("draftBounds").getDouble("right") >= 16.0
                        && titleState.getJSONObject("actionsBounds").getDouble("left") >= 16.0
                        && titleState.getJSONObject("viewport").getDouble("width")
                                - titleState.getJSONObject("actionsBounds").getDouble("right") >= 16.0
                        && titleState.getDouble("screenScrollTop") == 0
                        && titleState.getDouble("documentScrollTop") == 0);
        assertEquals("the composer title capture must show the idle composer", "idle", titleState.getString("state"));
        assertEquals("mobile sheet title must use the generic composer noun", "Prompt Composer",
                titleState.getString("composerHeading"));
        assertTrue("selected session identity must remain in terminal chrome, not the composer title: " + titleState,
                titleState.getString("terminalHeading").contains(bytesSession));
    }

    private void startJourneyWatchdog() {
        journeyThread = Thread.currentThread();
        checkpoint("test-start");
        final String runId = artifactRunId;
        journeyWatchdog = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "composer-journey-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        journeyWatchdog.scheduleAtFixedRate(() -> {
            long stuckMillis = SystemClock.elapsedRealtime() - journeyCheckpointAtMs;
            if (stuckMillis < 30_000) return;
            Thread blockedThread = journeyThread;
            if (blockedThread == null) return;
            StringBuilder stack = new StringBuilder();
            StackTraceElement[] frames = blockedThread.getStackTrace();
            for (int index = 0; index < Math.min(frames.length, 12); index++) {
                if (index > 0) stack.append(" <- ");
                stack.append(frames[index]);
            }
            Log.w("PS2857Watchdog", "runId=" + runId + " checkpoint=" + journeyCheckpoint
                    + " stuckMs=" + stuckMillis + " thread=" + blockedThread.getName()
                    + " threadState=" + blockedThread.getState() + " stack=" + stack);
        }, 30, 30, TimeUnit.SECONDS);
    }

    private void checkpoint(String name) {
        journeyCheckpoint = name;
        journeyCheckpointAtMs = SystemClock.elapsedRealtime();
        Log.i("PS2857Checkpoint", "runId=" + artifactRunId + " checkpoint=" + name);
    }

    private JSONObject captureHostBeforeExplicitAction(String stage, String forbiddenMarker) throws Exception {
        JSONObject request = new JSONObject()
                .put("runId", artifactRunId)
                .put("stage", stage)
                .put("session", bytesSession)
                .put("forbiddenMarker", forbiddenMarker);
        String responseLine;
        try (Socket socket = new Socket("127.0.0.1", hostOraclePort)) {
            socket.setSoTimeout(15_000);
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.UTF_8));
                 BufferedReader reader = new BufferedReader(new InputStreamReader(
                         socket.getInputStream(), StandardCharsets.UTF_8))) {
                writer.write(request.toString());
                writer.write('\n');
                writer.flush();
                responseLine = reader.readLine();
            }
        }
        assertNotNull("Docker host-byte oracle did not return a snapshot for " + stage, responseLine);
        JSONObject report = new JSONObject(responseLine);
        assertEquals("host-byte oracle response belongs to this run", artifactRunId, report.getString("runId"));
        assertEquals("host-byte oracle response belongs to the requested checkpoint", stage, report.getString("stage"));
        assertEquals("host-byte oracle reads the Docker host's PTY capture", "docker-host-a-capture",
                report.getString("source"));
        assertEquals("host-byte oracle checks the expected transcript bytes", forbiddenMarker,
                report.getString("forbiddenMarker"));
        byte[] hostBytes = Base64.getDecoder().decode(report.getString("hostBytesBase64"));
        assertEquals("host-byte snapshot length matches the independent capture", hostBytes.length,
                report.getInt("hostBytesLength"));
        assertEquals("host-byte snapshot hash matches the independent capture",
                hex(MessageDigest.getInstance("SHA-256").digest(hostBytes)), report.getString("hostBytesSha256"));
        assertTrue("dictated text must not reach the Docker PTY before explicit Insert or Send: " + report,
                report.getBoolean("noPtyWriteBeforeExplicitAction")
                        && !report.getBoolean("ptyWriteObserved")
                        && !new String(hostBytes, StandardCharsets.UTF_8).contains(forbiddenMarker));
        Log.i("PS2857HostOracle", "PRE_ACTION_BYTES|" + stage + "|length=" + hostBytes.length
                + "|sha256=" + report.getString("hostBytesSha256") + "|markerAbsent=true");
        return report;
    }

    private void recordComposerModeState(String runId, String state, String expectedDraft) throws Exception {
        recordComposerModeState(runId, state, expectedDraft, null);
    }

    private void recordComposerModeState(String runId, String state, String expectedDraft,
                                         Integer acknowledgedWritesBeforeAction) throws Exception {
        awaitWebViewVisualState();
        String report = evalString("(() => {const rect=(selector)=>{const node=document.querySelector(selector);"
                + "if(!node)return null;const r=node.getBoundingClientRect();return {top:r.top,bottom:r.bottom,left:r.left,right:r.right,width:r.width,height:r.height};};"
                + "const composer=document.querySelector('[data-testid=prompt-composer]');"
                + "const draft=document.querySelector('[data-testid=prompt-draft]');"
                + "const mode=document.querySelector('[data-testid=composer-recording-mode]');"
                + "const recordingHeader=mode?.querySelector('.recording-mode__live-row');"
                + "const preview=document.querySelector('[data-testid=composer-recording-preview]');"
                + "const composerStatus=document.querySelector('[data-testid=composer-status]');"
                + "const actionRow=composer?.querySelector('[data-testid=composer-recording-actions]');"
                + "const timer=document.querySelector('[data-testid=composer-recording-timer]');"
                + "const waveform=mode?.querySelector('.recording-mode__waveform');"
                + "const timerRect=timer?.getBoundingClientRect();const waveformRect=waveform?.getBoundingClientRect();"
                + "const heading=composer?.querySelector('.composer-heading__copy');const headingStyle=heading?getComputedStyle(heading):null;"
                + "const cancelButton=document.querySelector('[data-testid=composer-recording-cancel]');"
                + "const stopButton=document.querySelector('[data-testid=composer-recording-stop]');"
                + "const insertButton=document.querySelector('[data-testid=composer-insert]');"
                + "const dictationSendButton=document.querySelector('[data-testid=composer-dictation-send]');"
                + "const sendButton=document.querySelector('.composer-shared-controls .send');"
                + "const modeStatus=document.querySelector('[data-testid=composer-mode-status]');"
                + "const transcribingStatus=mode?.querySelector('[role=status][aria-live=polite]');"
                + "const cancelText=(cancelButton?.textContent??'').trim();"
                + "const cancelAriaLabel=cancelButton?.getAttribute('aria-label')??'';"
                + "const stopText=(stopButton?.innerText??'').trim();"
                + "const stopInRecordingHeader=!!recordingHeader&&recordingHeader.contains(stopButton);"
                + "const stopAccessibleName=stopButton?.getAttribute('aria-label')??'';"
                + "const stopStyle=stopButton?getComputedStyle(stopButton):null;"
                + "return JSON.stringify({runId:" + JSONObject.quote(runId) + ",state:" + JSONObject.quote(state)
                + ",composerHeading:composer?.querySelector('#composer-title')?.textContent.trim()??'',"
                + "composerHeadingDisplay:headingStyle?.display??'',"
                + "composerHeadingVisible:!!heading&&heading.getClientRects().length>0&&headingStyle?.display!=='none'"
                + "&&headingStyle?.visibility!=='hidden'&&Number(headingStyle?.opacity??0)>0.95,"
                + "terminalHeading:document.querySelector('#terminal-title')?.textContent.trim()??'',"
                + "dictationState:composer?.dataset.dictationState??'',"
                + "acknowledgedWrites:Number(composer?.dataset.acknowledgedWrites??0),"
                + "keyboardVisible:document.querySelector('.app-shell')?.dataset.keyboardVisible==='true',"
                + "composerModal:composer?.getAttribute('role')==='dialog'&&composer?.getAttribute('aria-modal')==='true',"
                + "draftFocused:document.activeElement===draft,activeElementTestId:document.activeElement?.getAttribute('data-testid')??'',"
                + "draftReadOnly:!!draft?.readOnly,draft:rect('[data-testid=prompt-draft]'),"
                + "draftPresentation:draft?.classList.contains('composer-draft--dictation-anchor')?'focus-anchor':'editor',"
                + "draftClassName:draft?.className??'',draftDisplay:draft?getComputedStyle(draft).display:null,"
                + "draftVisibility:draft?getComputedStyle(draft).visibility:null,"
                + "draftOpacity:draft?getComputedStyle(draft).opacity:null,draftAriaHidden:draft?.getAttribute('aria-hidden')==='true',"
                + "draftAriaLabel:draft?.getAttribute('aria-label')??'',"
                + "draftDescribedBy:draft?.getAttribute('aria-describedby')??'',"
                + "draftEditingLocked:draft?.getAttribute('aria-readonly')==='true',"
                + "expectedDraftMatches:draft?.value===" + JSONObject.quote(expectedDraft) + ","
                + "recordingModeVisible:!!mode&&getComputedStyle(mode).display!=='none'&&mode.getClientRects().length>0,"
                + "recordingModeLabel:mode?.getAttribute('aria-label')??'',"
                + "previewVisible:!!preview&&preview.getClientRects().length>0&&getComputedStyle(preview).display!=='none',"
                + "previewLive:preview?.getAttribute('aria-live')==='polite',"
                + "previewAccessible:!!preview&&preview.getAttribute('id')==='composer-recording-preview'"
                + "&&preview.getAttribute('aria-live')==='polite'&&preview.getClientRects().length>0"
                + "&&draft?.getAttribute('aria-describedby')?.includes('composer-recording-preview')===true,"
                + "previewText:preview?.textContent.trim()??'',"
                + "cancelText,cancelAriaLabel,cancelAccessible:!!cancelButton"
                + "&&(composer?.dataset.dictationState==='recording' ? cancelText==='Discard'"
                + "&&cancelAriaLabel==='Discard recording without transcribing' : cancelText==='Cancel'"
                + "&&cancelAriaLabel==='Cancel dictation and restore the original draft')&&cancelButton.getClientRects().length>0,"
                + "stopText,stopAccessibleName,"
                + "stopVisible:!!stopButton&&stopButton.getClientRects().length>0&&stopStyle?.display!=='none'"
                + "&&stopStyle?.visibility!=='hidden'&&Number(stopStyle?.opacity??0)>0.95,"
                + "stopInRecordingHeader:stopInRecordingHeader,"
                + "stopEnabled:!!stopButton&&!stopButton.disabled,"
                + "stopGlyphPresent:!!stopButton?.querySelector(\"svg[aria-hidden='true'] > rect[x='6'][y='6'][width='12'][height='12'][rx='1'][fill='currentColor']\"),"
                + "insertAccessible:!!insertButton&&(insertButton.textContent??'').trim()==='Insert'&&insertButton.getClientRects().length>0,"
                + "insertEnabled:!!insertButton&&!insertButton.disabled,"
                + "dictationSendAccessible:!!dictationSendButton&&(dictationSendButton.textContent??'').includes('Send')"
                + "&&dictationSendButton.getClientRects().length>0,dictationSendEnabled:!!dictationSendButton&&!dictationSendButton.disabled,"
                + "timerAccessible:timer?.getAttribute('aria-label')==='Dictation duration',"
                + "timerVisible:!!timer&&timer.getClientRects().length>0&&getComputedStyle(timer).display!=='none',"
                + "timerText:timer?.textContent.trim()??'',"
                + "timerBesideWaveform:!!timerRect&&!!waveformRect&&timerRect.bottom>waveformRect.top"
                + "&&timerRect.top<waveformRect.bottom&&timerRect.right<waveformRect.left,"
                + "recordingControlsAccessible:actionRow?.getAttribute('role')==='group'"
                + "&&actionRow?.getAttribute('aria-label')==='Dictation controls',"
                + "recordingControlsSeparate:!!actionRow&&!!mode&&!mode.contains(actionRow),"
                + "reviewEditable:!!draft&&!draft.readOnly&&draft.getAttribute('aria-readonly')==='false',"
                + "transcribingStatusAccessible:!!transcribingStatus&&transcribingStatus.getClientRects().length>0,"
                + "actionOrder:actionRow?Array.from(actionRow.children).map(node=>node.getAttribute('data-testid')).join(','):'',"
                + "composerStatusAccessible:composerStatus?.getAttribute('role')==='status'&&composerStatus?.getAttribute('aria-live')==='polite',"
                + "reviewText:document.querySelector('[data-testid=composer-dictation-review]')?.textContent.trim()??'',"
                + "reviewVisible:!!document.querySelector('[data-testid=composer-dictation-review]')"
                + "&&getComputedStyle(document.querySelector('[data-testid=composer-dictation-review]')).display!=='none',"
                + "recordingMode:rect('[data-testid=composer-recording-mode]'),timer:rect('[data-testid=composer-recording-timer]'),"
                + "waveform:rect('.recording-mode__waveform'),recordingActions:rect('[data-testid=composer-recording-actions]'),"
                + "preview:rect('[data-testid=composer-recording-preview]'),review:rect('[data-testid=composer-dictation-review]'),"
                + "status:rect('[data-testid=composer-status]'),actions:rect('[data-testid=composer-actions]'),"
                + "cancel:rect('[data-testid=composer-recording-cancel]'),stop:rect('[data-testid=composer-recording-stop]'),"
                + "insert:rect('[data-testid=composer-insert]'),dictationSend:rect('[data-testid=composer-dictation-send]'),"
                + "send:rect('.composer-shared-controls .send'),visualViewport:{height:window.visualViewport?.height??innerHeight,width:window.visualViewport?.width??innerWidth},"
                + "insertEnabled:!!insertButton&&!insertButton.disabled,sendEnabled:!!sendButton&&!sendButton.disabled,"
                + "modeStatusText:modeStatus?.textContent.trim()??'',modeStatusLabel:modeStatus?.getAttribute('aria-label')??'',"
                + "screenScrollTop:document.querySelector('.screen-content')?.scrollTop??null,documentScrollTop:document.scrollingElement?.scrollTop??null,"
                + "statusText:document.querySelector('[data-testid=composer-status]')?.textContent.trim()??'',"
                + "waveformLabel:mode?.querySelector('.recording-mode__waveform')?.getAttribute('aria-label')??null});})() ");
        JSONObject measured = new JSONObject(report).put("androidImeVisible", isImeVisible());
        if (acknowledgedWritesBeforeAction != null) {
            measured.put("acknowledgedWritesBeforeAction", acknowledgedWritesBeforeAction);
        }
        measured.put("lastDictationTestEvent", lastDictationTestEvent);
        if ("background".equals(state) && lastBackgroundLifecycleTrace != null) {
            measured.put("nativeLifecycle", lastBackgroundLifecycleTrace);
        }
        byte[] geometry = measured.toString().getBytes(StandardCharsets.UTF_8);
        // The DOM report can observe a just-committed Vue state one frame
        // before Android's screenshot surface catches up (notably Stop ->
        // review). Wait for a fresh WebView visual commit after sampling it so
        // the screenshot and its same-run geometry show the same composer mode.
        awaitWebViewVisualState();
        emitCurrentScreen(runId, "composer-" + state + ".png");
        emitArtifact(runId, "composer-" + state + "-geometry.json", geometry);
        if (measured.getBoolean("androidImeVisible") || measured.getBoolean("keyboardVisible")) {
            emitCurrentScreen(runId, "composer-mode-ime-failure.png");
            emitArtifact(runId, "composer-mode-ime-failure.json", geometry);
            throw new AssertionError("the Android keyboard must be dismissed during the " + state + " modal dictation state; state="
                    + measured);
        }
        boolean reviewState = state.startsWith("review");
        String expectedPhase = state.equals("cancel") || state.equals("background") ? "idle"
                : state.startsWith("recording") ? "recording"
                        : state.startsWith("transcribing") ? "transcribing" : reviewState ? "review" : state;
        assertEquals("composer phase must match the " + state + " screenshot", expectedPhase, measured.getString("dictationState"));
        String expectedHeading = state.startsWith("recording") || state.startsWith("transcribing")
                ? "Prompt dictation" : reviewState ? "Review dictation" : "Prompt Composer";
        assertEquals("composer heading must identify the active prompt dictation phase", expectedHeading,
                measured.getString("composerHeading"));
        assertTrue("keyboard-down composer must retain its phase heading",
                measured.getBoolean("composerHeadingVisible")
                        && !"none".equals(measured.getString("composerHeadingDisplay")));
        assertTrue("state screenshot must retain the expected draft text", measured.getBoolean("expectedDraftMatches"));
        assertTrue("dictation mode must be presented in the modal composer sheet", measured.getBoolean("composerModal"));
        assertTrue("dictation controls must dismiss the IME and keep the sheet unobstructed",
                !measured.getBoolean("androidImeVisible") && !measured.getBoolean("keyboardVisible"));
        boolean dictationBusy = state.startsWith("recording") || state.startsWith("transcribing");
        assertEquals("busy dictation must replace the visible editor with its accessible focus anchor",
                dictationBusy ? "focus-anchor" : "editor", measured.getString("draftPresentation"));
        assertTrue("the focused dictation draft anchor must stay in the accessibility tree",
                !measured.getBoolean("draftAriaHidden")
                        && (!dictationBusy || measured.getString("draftAriaLabel").equals(
                                "Prompt dictation draft, read only during capture")));
        if (dictationBusy) {
            assertTrue("busy dictation must show its recording/transcribing surface while hiding the editor visually",
                    measured.getBoolean("recordingModeVisible")
                            && measured.getJSONObject("draft").getDouble("width") <= 1.0
                            && measured.getJSONObject("draft").getDouble("height") <= 1.0);
            assertTrue("visible dictation mode and status must have accessible names and live semantics",
                    !measured.getString("recordingModeLabel").isEmpty()
                            && measured.getBoolean("composerStatusAccessible")
                            && measured.getString("draftDescribedBy").contains("composer-status")
                            && measured.getBoolean("cancelAccessible"));
            if (state.startsWith("recording")) {
                assertEquals("recording actions must follow the Kotlin composer row: Discard, Insert, Send",
                        "composer-recording-cancel,composer-insert,composer-dictation-send",
                        measured.getString("actionOrder"));
                assertEquals("recording must use the Kotlin discard label", "Discard", measured.getString("cancelText"));
                assertEquals("discard must explain that this throws away the recording",
                        "Discard recording without transcribing", measured.getString("cancelAriaLabel"));
                assertTrue("recording must keep explicit Insert and Send visible, enabled, and at least 48dp tall",
                        measured.getBoolean("insertAccessible") && measured.getBoolean("insertEnabled")
                                && measured.getJSONObject("insert").getDouble("height") >= 47.9
                                && measured.getBoolean("dictationSendAccessible") && measured.getBoolean("dictationSendEnabled")
                                && measured.getJSONObject("dictationSend").getDouble("height") >= 47.9);
                assertTrue("recording preview and header Stop control must be visible, enabled, accessible, and 48dp square",
                        measured.getBoolean("previewVisible") && measured.getBoolean("previewLive")
                                && measured.getString("stopText").isEmpty()
                                && measured.getBoolean("stopInRecordingHeader")
                                && "Stop dictation and keep the recognized text in the editable draft".equals(
                                        measured.getString("stopAccessibleName"))
                                && measured.getBoolean("stopVisible") && measured.getBoolean("stopEnabled")
                                && measured.getBoolean("stopGlyphPresent")
                                && Math.abs(measured.getJSONObject("stop").getDouble("width") - 48.0) < 0.5
                                && Math.abs(measured.getJSONObject("stop").getDouble("height") - 48.0) < 0.5
                                && measured.getString("draftDescribedBy").contains("composer-recording-preview"));
            } else {
                assertEquals("transcribing actions must match Kotlin: Cancel and Send",
                        "composer-recording-cancel,composer-dictation-send",
                        measured.getString("actionOrder"));
                assertEquals("transcribing Cancel must be distinct from recording Discard", "Cancel",
                        measured.getString("cancelText"));
                assertEquals("transcribing Cancel must explain that it restores the original draft",
                        "Cancel dictation and restore the original draft", measured.getString("cancelAriaLabel"));
                assertTrue("transcribing state must expose Cancel, timer, live preview, and Send without Insert",
                        measured.getBoolean("transcribingStatusAccessible")
                                && measured.getBoolean("timerAccessible") && measured.getBoolean("timerVisible")
                                && measured.getString("timerText").matches("\\d{2}:\\d{2}")
                                && measured.getBoolean("previewVisible") && measured.getBoolean("previewLive")
                                && measured.getBoolean("previewAccessible")
                                && !measured.getBoolean("insertAccessible") && !measured.getBoolean("insertEnabled")
                                && measured.isNull("insert")
                                && measured.getBoolean("dictationSendAccessible")
                                && measured.getBoolean("dictationSendEnabled")
                                && measured.getJSONObject("dictationSend").getDouble("height") >= 47.9);
            }
        } else {
            assertTrue("idle and review must retain the ordinary composer textarea",
                    reviewState ? measured.getBoolean("reviewVisible") : !measured.getBoolean("recordingModeVisible"));
            if (reviewState) {
                assertTrue("editable review must restore its explicit, enabled Insert action",
                        measured.getBoolean("insertAccessible") && measured.getJSONObject("insert").getDouble("height") >= 47.9);
                if (state.equals("review-error") || state.equals("review-empty")) {
                    assertTrue("error and empty review states must keep delivery disabled until a deliberate edit",
                            !measured.getBoolean("insertEnabled") && !measured.getBoolean("sendEnabled")
                                    && measured.getJSONObject("send").getDouble("height") >= 47.9);
                    assertEquals("error and empty review states must show the Review status", "REVIEW",
                            measured.getString("modeStatusText"));
                } else {
                    assertTrue("successful review must restore enabled Insert",
                            measured.getBoolean("insertEnabled"));
                }
            }
        }
    }

    private void grantMicrophonePermissionForJourney() throws Exception {
        String packageName = InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName();
        ParcelFileDescriptor command = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand("pm grant " + packageName + " android.permission.RECORD_AUDIO");
        if (command != null) command.close();
    }

    private String injectDictationTestEvent(String type, String text) throws Exception {
        String options = new JSONObject().put("type", type).put("text", text).toString();
        evalString("window.__ps2857DictationTestInjection = null; (() => {"
                + "const plugin = window.Capacitor?.Plugins?.SpeechRecognition;"
                + "if (!plugin?.injectTestDictationEvent) throw new Error('debug speech adapter injection is unavailable');"
                + "plugin.injectTestDictationEvent(JSON.parse(" + JSONObject.quote(options) + "))"
                + ".then(result => window.__ps2857DictationTestInjection = JSON.stringify(result))"
                + ".catch(error => window.__ps2857DictationTestInjection = 'ERROR: ' + String(error));"
                + "return 'queued';})()");
        awaitJsTrue("typeof window.__ps2857DictationTestInjection === 'string'");
        String result = evalString("window.__ps2857DictationTestInjection");
        JSONObject nativeResult = new JSONObject(result);
        assertTrue("debug speech adapter must inject a deterministic event: " + result,
                nativeResult.optBoolean("emitted"));
        lastDictationTestEvent = new JSONObject()
                .put("type", type)
                .put("text", text == null ? "" : text)
                .put("requestId", nativeResult.optString("requestId", ""));
        evalString("window.__ps2857DictationTestInjection = null; 'cleared'");
        return result;
    }

    private void emitCurrentScreen(String runId, String name) throws Exception {
        AtomicReference<byte[]> screenshotArtifact = new AtomicReference<>();
        onMainActivity(activity -> {
            Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            if (screenshot == null) return;
            try {
                ByteArrayOutputStream encoded = new ByteArrayOutputStream();
                if (screenshot.compress(Bitmap.CompressFormat.PNG, 100, encoded)) {
                    screenshotArtifact.set(encoded.toByteArray());
                }
            } finally {
                screenshot.recycle();
            }
        });
        if (screenshotArtifact.get() != null) emitArtifact(runId, name, screenshotArtifact.get());
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
        onMainActivity(activity -> {
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
        ensureImeVisible("post-inline-dictation-attach");
        SystemClock.sleep(350);
        assertTrue("Android IME must still be open for the keyboard-up capture", isImeVisible());
        assertTrue("capture the real keyboard-up composer before checking its visible bounds", saveKeyboardScreenshot(runId));
        String geometry = saveKeyboardGeometry(runId);
        try {
            awaitJsTrue("(() => {const shell=document.querySelector('.app-shell');"
                    + "const composer=document.querySelector('[data-testid=prompt-composer]');"
                    + "const heading=composer?.querySelector('.composer-heading__copy');"
                    + "const headingStyle=heading?getComputedStyle(heading):null;"
                    + "const gutter=node=>{if(!node)return false;const r=node.getBoundingClientRect();return r.left>=16&&innerWidth-r.right>=16;};"
                    + "const appBar=document.querySelector('.app-bar')?.getBoundingClientRect();"
                    + "const terminal=document.querySelector('.terminal-viewport')?.getBoundingClientRect();"
                    + "const height=window.visualViewport?.height ?? innerHeight;"
                    + "const safeTop=parseFloat(getComputedStyle(shell).paddingTop)||0;"
                    + "const selectors=['[data-testid=prompt-draft]','[data-testid=composer-status]','[data-testid=composer-open-keys]',"
                    + "'[data-testid=composer-discard]','[data-testid=composer-insert]','.composer-shared-controls .send'];"
                    + "const visible=selectors.every(selector=>{const node=document.querySelector(selector);"
                    + "if(!node)return false;const rect=node.getBoundingClientRect();return rect.top >= 0 && rect.bottom <= height + 0.5"
                    + " && rect.left >= 0 && rect.right <= innerWidth + 0.5;});"
                    + "const nav=Array.from(document.querySelectorAll('.workspace-navigation button'));"
                    + "const navVisible=nav.length >= 3 && nav.every(node=>{const rect=node.getBoundingClientRect();"
                    + "return rect.top >= 0 && rect.bottom <= height + 0.5 && rect.left >= 0 && rect.right <= innerWidth + 0.5;});"
                    + "const screen=document.querySelector('.screen-content');"
                    + "return shell?.dataset.keyboardVisible === 'true' && !!appBar && !!terminal && terminal.height >= 48"
                    + " && composer?.classList.contains('composer-panel--sheet')===true && headingStyle?.display==='none'"
                    + " && gutter(document.querySelector('[data-testid=prompt-draft]'))"
                    + " && gutter(document.querySelector('[data-testid=composer-actions]'))"
                    + " && appBar.top >= safeTop - 0.5 && appBar.bottom <= height + 0.5"
                    + " && terminal.top >= appBar.bottom && terminal.bottom <= height + 0.5 && terminal.right <= innerWidth + 0.5"
                    + " && navVisible && visible && screen?.scrollTop === 0 && document.scrollingElement?.scrollTop === 0;})()");
        } catch (AssertionError error) {
            throw new AssertionError(error.getMessage() + "; captured keyboard screenshot precedes geometry=" + geometry, error);
        }
        JSONObject keyboardGeometry = new JSONObject(geometry);
        JSONObject nativeInsets = keyboardGeometry.optJSONObject("nativeInsets");
        assertNotNull("keyboard capture must include Android system-bar insets", nativeInsets);
        assertTrue("workspace chrome must begin below the Android status bar",
                keyboardGeometry.getJSONObject("appBar").getDouble("top")
                        >= nativeInsets.getDouble("statusBarTopDp") - 1.0);
        assertTrue("keyboard-up Prompt Composer must hide its title copy while keeping the key route visible",
                keyboardGeometry.getBoolean("composerIsSheet")
                        && !keyboardGeometry.getBoolean("composerHeadingVisible")
                        && "none".equals(keyboardGeometry.getString("composerHeadingDisplay")));
        double viewportWidth = keyboardGeometry.getJSONObject("visualViewport").getDouble("width");
        for (String name : new String[]{"draft", "actions"}) {
            JSONObject bounds = keyboardGeometry.getJSONObject(name);
            assertTrue("keyboard-up composer " + name + " must keep a 16dp horizontal gutter",
                    bounds.getDouble("left") >= 16.0 && viewportWidth - bounds.getDouble("right") >= 16.0);
        }
        JSONObject buttons = keyboardGeometry.getJSONObject("buttons");
        for (String name : new String[]{"discard", "insert", "send", "keys"}) {
            JSONObject bounds = buttons.getJSONObject(name);
            assertTrue(name + " must keep a 48dp touch target with the IME open",
                    bounds.getDouble("bottom") - bounds.getDouble("top") >= 47.9);
        }
        assertTrue("a real Android keyboard must still be open when the composer is captured", isImeVisible());
    }

    private void ensureImeVisible() throws Exception {
        ensureImeVisible("composer-action");
    }

    private void ensureImeVisible(String stage) throws Exception {
        boolean composerFocused = isPromptDraftFocused();
        boolean composerMode = isKeyboardComposerMode();
        boolean imeVisible = isImeVisible();
        boolean requirePhysicalTap = "post-inline-dictation-attach".equals(stage)
                || "uncertain-session-after-attach".equals(stage);
        if (!imeVisible || !composerFocused || !composerMode || requirePhysicalTap) {
            installFocusTapEventRecorder();
            for (int attemptIndex = 0; attemptIndex < composerFocusMaxAttempts; attemptIndex += 1) {
                awaitWebViewVisualState();
                boolean injectMiss = forceFirstPostAttachTapMiss && requirePhysicalTap && attemptIndex == 0;
                // Keep the deliberate missed tap inside the modal. A tap through
                // its scrim closes Composer and makes the next retry target stale.
                String targetSelector = injectMiss
                        ? "[data-testid=prompt-composer] #composer-title"
                        : "[data-testid=prompt-draft]";
                clearFocusTapEvents(targetSelector);
                JSONObject before = readFocusDomState();
                boolean imeBefore = isImeVisible();
                long attemptStarted = SystemClock.uptimeMillis();
                tapDomCenter(targetSelector);
                JSONObject tap = lastPhysicalTapEvidence == null
                        ? new JSONObject() : new JSONObject(lastPhysicalTapEvidence.toString());
                boolean physicalTargetObserved = awaitTrustedPointerDown(targetSelector, 900);
                boolean focused = physicalTargetObserved && !injectMiss && awaitPromptDraftFocus(2_500);
                boolean imeSettled = focused && awaitKeyboardReadyAfterTap(4_000);
                JSONObject after = readFocusDomState();
                JSONObject record = new JSONObject()
                        .put("stage", stage)
                        .put("attempt", attemptIndex + 1)
                        .put("requestedSelector", targetSelector)
                        .put("before", before)
                        .put("nativeImeVisibleBefore", imeBefore)
                        .put("tap", tap)
                        .put("trustedPointerDownOnRequestedTarget", physicalTargetObserved)
                        .put("draftFocusedAfter", focused)
                        .put("nativeImeVisibleAfter", isImeVisible())
                        .put("keyboardReadyAfter", imeSettled)
                        .put("after", after)
                        .put("elapsedMs", SystemClock.uptimeMillis() - attemptStarted);
                boolean injectedMissPreservedRetryTarget = true;
                if (injectMiss) {
                    boolean modalStayedOpen = after.optBoolean("composerModal")
                            && after.optBoolean("composerTitlePresent");
                    boolean draftStayedMounted = after.optBoolean("draftPresent")
                            && after.optBoolean("draftConnected");
                    record.put("inertMissInsideComposer", targetSelector.equals("[data-testid=prompt-composer] #composer-title"))
                            .put("dialogStayedOpenAfterMiss", modalStayedOpen)
                            .put("draftStayedMountedAfterMiss", draftStayedMounted);
                    injectedMissPreservedRetryTarget = physicalTargetObserved && modalStayedOpen && draftStayedMounted;
                }
                focusTapAttempts.put(record);
                Log.i("PS2891Focus", "ATTEMPT|" + artifactRunId + "|" + record);
                assertTrue("the injected focus miss must hit inert Composer content and preserve its retry target: " + record,
                        injectedMissPreservedRetryTarget);
                if (imeSettled) {
                    record.put("nativeImeVisibleAfterImeWait", true)
                            .put("afterImeWait", readFocusDomState());
                    Log.i("PS2891Focus", "SETTLED|" + artifactRunId + "|" + record);
                    break;
                }
            }
        }
        boolean ready = isImeVisible() && isPromptDraftFocused()
                && "true".equals(evalRaw("document.querySelector('.app-shell')?.dataset.keyboardVisible === 'true'"))
                && isKeyboardComposerMode();
        if (!ready) {
            captureFocusFailure(stage, "composer tap attempts did not leave the real draft focused with the IME open; state="
                    + readFocusDomState());
            throw new AssertionError("Composer did not reach a focused, keyboard-open state after "
                    + composerFocusMaxAttempts + " physical tap attempt(s); same-run focus screenshot and diagnostics were captured");
        }
        if (requirePhysicalTap && !hasSuccessfulPhysicalDraftTap(stage)) {
            captureFocusFailure(stage, "post-attach composer state became ready without an observed trusted physical draft tap");
            throw new AssertionError("Post-attach composer focus was not proven by a physical draft tap");
        }
    }

    private boolean isPromptDraftFocused() throws Exception {
        return "true".equals(evalRaw("document.activeElement === document.querySelector('[data-testid=prompt-draft]')"));
    }

    private boolean isKeyboardComposerMode() throws Exception {
        return "true".equals(evalRaw("document.querySelector('.app-shell')?.dataset.keyboardComposerMode === 'true'"));
    }

    private boolean awaitKeyboardReadyAfterTap(long timeoutMillis) throws Exception {
        long deadline = SystemClock.uptimeMillis() + timeoutMillis;
        while (SystemClock.uptimeMillis() < deadline) {
            if (isImeVisible() && isPromptDraftFocused()
                    && "true".equals(evalRaw("document.querySelector('.app-shell')?.dataset.keyboardVisible === 'true'"))
                    && isKeyboardComposerMode()) {
                Thread.sleep(120);
                if (isImeVisible() && isPromptDraftFocused() && isKeyboardComposerMode()) return true;
            }
            Thread.sleep(60);
        }
        return false;
    }

    private void installComposerDictatePointerDownProbe() throws Exception {
        evalString("(() => {const key='__ps2857ComposerDictatePointerDownEvents';window[key]=[];"
                + "if(window.__ps2857ComposerDictatePointerDownProbeInstalled)return 'installed';"
                + "document.addEventListener('pointerdown',event=>{const target=event.target;"
                + "const button=target instanceof Element?target.closest('[data-testid=composer-dictate]'):null;"
                + "const sheet=document.querySelector('.composer-panel--sheet');"
                + "if(!button||!sheet?.contains(button)||!button.classList.contains('composer-dictate--mic'))return;"
                + "const record={isTrusted:event.isTrusted,targetMatchesButton:button.contains(target),"
                + "keyboardVisibleAtPointerDown:document.querySelector('.app-shell')?.dataset.keyboardVisible==='true',"
                + "cancelable:event.cancelable,defaultPreventedAtDocumentBubble:event.defaultPrevented,timeStamp:event.timeStamp};"
                + "window[key].push(record);},false);window.__ps2857ComposerDictatePointerDownProbeInstalled=true;return 'installed';})()");
    }

    private JSONObject readComposerDictatePointerDownEvidence() throws Exception {
        return evalJson("(() => {const events=window.__ps2857ComposerDictatePointerDownEvents||[];"
                + "return JSON.stringify(events[events.length-1]??{});})()");
    }

    private void installFocusTapEventRecorder() throws Exception {
        evalString("(() => {if(window.__ps2891FocusTapRecorderInstalled)return 'installed';"
                + "window.__ps2891FocusTapEvents=[];window.__ps2891ExpectedFocusTapSelector='';"
                + "const label=node=>node?{tag:node.tagName||'',id:node.id||'',testid:node.getAttribute?.('data-testid')||'',"
                + "className:typeof node.className==='string'?node.className:''}:null;"
                + "for(const type of ['pointerdown','pointerup','focusin','focusout'])document.addEventListener(type,event=>{"
                + "const target=event.target;const selector=window.__ps2891ExpectedFocusTapSelector;if(!selector)return;"
                + "const matches=selector==='[data-testid=prompt-draft]'?target===document.querySelector('[data-testid=prompt-draft]'):!!target?.closest?.(selector);"
                + "window.__ps2891FocusTapEvents.push({type,isTrusted:event.isTrusted,target:label(target),targetMatchesRequested:matches,"
                + "clientX:event.clientX??null,clientY:event.clientY??null,pointerType:event.pointerType??'',timeStamp:event.timeStamp});},true);"
                + "window.__ps2891FocusTapRecorderInstalled=true;return 'installed';})()");
    }

    private void clearFocusTapEvents(String targetSelector) throws Exception {
        evalString("(() => {window.__ps2891FocusTapEvents.length=0;window.__ps2891ExpectedFocusTapSelector="
                + JSONObject.quote(targetSelector) + ";return 'cleared';})()");
    }

    private boolean awaitTrustedPointerDown(String targetSelector, long timeoutMillis) throws Exception {
        if (targetSelector.isEmpty()) return false;
        String expression = "window.__ps2891FocusTapEvents?.some(event=>event.type==='pointerdown'"
                + "&&event.isTrusted===true&&event.targetMatchesRequested===true)===true";
        long deadline = SystemClock.uptimeMillis() + timeoutMillis;
        while (SystemClock.uptimeMillis() < deadline) {
            if ("true".equals(evalRaw(expression))) return true;
            Thread.sleep(30);
        }
        return "true".equals(evalRaw(expression));
    }

    private boolean awaitPromptDraftFocus(long timeoutMillis) throws Exception {
        long deadline = SystemClock.uptimeMillis() + timeoutMillis;
        while (SystemClock.uptimeMillis() < deadline) {
            if (isPromptDraftFocused()) return true;
            Thread.sleep(60);
        }
        return isPromptDraftFocused();
    }

    private JSONObject readFocusDomState() throws Exception {
        return evalJson("(() => {const draft=document.querySelector('[data-testid=prompt-draft]');"
                + "const composer=document.querySelector('[data-testid=prompt-composer]');"
                + "const title=composer?.querySelector('#composer-title');"
                + "const active=document.activeElement;const rect=draft?.getBoundingClientRect();"
                + "const x=rect?rect.left+rect.width/2:0,y=rect?rect.top+rect.height/2:0,hit=document.elementFromPoint(x,y);"
                + "const label=node=>node?{tag:node.tagName||'',id:node.id||'',testid:node.getAttribute?.('data-testid')||'',"
                + "className:typeof node.className==='string'?node.className:''}:null;"
                + "const bounds=node=>{const r=node?.getBoundingClientRect();return r?{top:r.top,bottom:r.bottom,left:r.left,right:r.right,width:r.width,height:r.height}:null};"
                + "const shell=document.querySelector('.app-shell');return JSON.stringify({uptimeHintMs:performance.now(),"
                + "route:shell?.dataset.route||'',homeSurface:shell?.dataset.homeSurface||'',sshPhase:shell?.dataset.sshPhase||'',"
                + "keyboardVisible:shell?.dataset.keyboardVisible==='true',keyboardComposerMode:shell?.dataset.keyboardComposerMode==='true',"
                + "composerPresent:!!composer,composerModal:composer?.getAttribute('role')==='dialog'&&composer?.getAttribute('aria-modal')==='true',"
                + "composerTitlePresent:!!title,"
                + "activeElement:label(active),draftPresent:!!draft,draftConnected:!!draft?.isConnected,draftDisabled:!!draft?.disabled,"
                + "draftFocused:active===draft,draftBounds:bounds(draft),draftCenterHit:label(hit),draftCenterHitIsDraft:hit===draft,"
                + "visualViewport:{width:window.visualViewport?.width??innerWidth,height:window.visualViewport?.height??innerHeight,"
                + "offsetLeft:window.visualViewport?.offsetLeft??0,offsetTop:window.visualViewport?.offsetTop??0,scale:window.visualViewport?.scale??1},"
                + "innerWidth,innerHeight,screenScroll:document.querySelector('.screen-content')?.scrollTop??null,"
                + "documentScroll:document.scrollingElement?.scrollTop??null,pointerEvents:(window.__ps2891FocusTapEvents||[]).slice(-20)});})()");
    }

    private boolean hasSuccessfulPhysicalDraftTap(String stage) throws JSONException {
        for (int index = 0; index < focusTapAttempts.length(); index += 1) {
            JSONObject attempt = focusTapAttempts.getJSONObject(index);
            if (stage.equals(attempt.optString("stage"))
                    && "[data-testid=prompt-draft]".equals(attempt.optString("requestedSelector"))
                    && attempt.optBoolean("trustedPointerDownOnRequestedTarget")
                    && attempt.optBoolean("draftFocusedAfter")
                    && attempt.optBoolean("nativeImeVisibleAfterImeWait")) return true;
        }
        return false;
    }

    private void emitFocusTraceIfNeeded() throws Exception {
        if (focusTraceEmitted || focusTapAttempts.length() == 0 || artifactRunId == null) return;
        JSONObject trace = new JSONObject().put("runId", artifactRunId)
                .put("androidApi", Build.VERSION.SDK_INT)
                .put("maxAttempts", composerFocusMaxAttempts)
                .put("forcedFirstPostAttachMiss", forceFirstPostAttachTapMiss)
                .put("attempts", focusTapAttempts);
        byte[] bytes = trace.toString(2).getBytes(StandardCharsets.UTF_8);
        if (scenario != null) {
            onMainActivity(activity -> {
                try (FileOutputStream output = new FileOutputStream(
                        new File(activity.getFilesDir(), "composer-focus-trace.json"))) {
                    output.write(bytes);
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            });
        }
        emitArtifact(artifactRunId, "composer-focus-trace.json", bytes);
        focusTraceEmitted = true;
    }

    private void captureFocusFailure(String stage, String reason) {
        if (focusFailureCaptured || artifactRunId == null || scenario == null) return;
        focusFailureCaptured = true;
        Log.e("PS2891Focus", "FAILURE|" + artifactRunId + "|" + stage + "|" + reason);
        try {
            byte[] screenshot = captureFocusFailureScreenshot();
            if (screenshot != null) emitArtifact(artifactRunId, "composer-focus-failure.png", screenshot);
        } catch (Exception error) {
            Log.e("PS2891Focus", "could not capture same-frame composer focus failure screenshot", error);
        }
        try {
            JSONObject report = new JSONObject().put("runId", artifactRunId).put("stage", stage).put("reason", reason)
                    .put("capturedAtAndroidUptimeMs", SystemClock.uptimeMillis())
                    .put("androidApi", Build.VERSION.SDK_INT).put("imeAndWindowState", readNativeFocusState())
                    .put("webViewState", readFocusDomState()).put("lastPhysicalTap", lastPhysicalTapEvidence)
                    .put("attempts", focusTapAttempts);
            byte[] reportBytes = report.toString(2).getBytes(StandardCharsets.UTF_8);
            onMainActivity(activity -> {
                try (FileOutputStream output = new FileOutputStream(
                        new File(activity.getFilesDir(), "composer-focus-failure.json"))) {
                    output.write(reportBytes);
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            });
            emitArtifact(artifactRunId, "composer-focus-failure.json", reportBytes);
        } catch (Exception error) {
            Log.e("PS2891Focus", "could not capture composer focus state before ActivityScenario teardown", error);
        }
        try {
            byte[] logcat = executeShellCommand("logcat -d -v threadtime -t 1200 -s ImeTracker InputMethodManager "
                    + "InputMethodManagerService ViewRootImpl PS2891Focus");
            if (logcat.length > 0) emitArtifact(artifactRunId, "composer-focus-failure-logcat.txt", logcat);
        } catch (Exception error) {
            Log.e("PS2891Focus", "could not capture filtered Android focus log before ActivityScenario teardown", error);
        }
        try {
            emitFocusTraceIfNeeded();
        } catch (Exception error) {
            Log.e("PS2891Focus", "could not emit composer focus attempts before ActivityScenario teardown", error);
        }
    }

    private byte[] captureFocusFailureScreenshot() throws Exception {
        AtomicReference<byte[]> bytes = new AtomicReference<>();
        onMainActivity(activity -> {
            Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            if (screenshot == null) return;
            try {
                ByteArrayOutputStream encoded = new ByteArrayOutputStream();
                boolean compressed = screenshot.compress(Bitmap.CompressFormat.PNG, 100, encoded);
                byte[] png = encoded.toByteArray();
                File destination = new File(activity.getFilesDir(), "composer-focus-failure.png");
                if (compressed && png.length >= 1024) {
                    try {
                        try (FileOutputStream output = new FileOutputStream(destination)) {
                            output.write(png);
                        }
                    } catch (Exception error) {
                        throw new RuntimeException(error);
                    }
                    if (destination.length() >= 1024) bytes.set(png);
                }
            } finally {
                screenshot.recycle();
            }
        });
        return bytes.get();
    }

    private JSONObject readNativeFocusState() throws Exception {
        AtomicReference<JSONObject> state = new AtomicReference<>();
        onMainActivity(activity -> {
            View decor = activity.getWindow().getDecorView();
            View focusedView = decor.findFocus();
            WebView webView = findWebView(decor);
            WindowInsets insets = decor.getRootWindowInsets();
            try {
                state.set(new JSONObject().put("windowHasFocus", decor.hasWindowFocus())
                        .put("decorHasFocus", decor.hasFocus()).put("decorShown", decor.isShown())
                        .put("imeVisible", insets != null && insets.isVisible(WindowInsets.Type.ime()))
                        .put("imeBottomPx", insets == null ? 0 : insets.getInsets(WindowInsets.Type.ime()).bottom)
                        .put("focusedViewClass", focusedView == null ? "" : focusedView.getClass().getName())
                        .put("focusedViewId", focusedView == null ? View.NO_ID : focusedView.getId())
                        .put("focusedViewHasFocus", focusedView != null && focusedView.hasFocus())
                        .put("webViewPresent", webView != null).put("webViewHasFocus", webView != null && webView.hasFocus())
                        .put("webViewWindowTokenPresent", webView != null && webView.getWindowToken() != null));
            } catch (JSONException error) {
                throw new RuntimeException(error);
            }
        });
        return state.get() == null ? new JSONObject() : state.get();
    }

    private byte[] executeShellCommand(String command) throws Exception {
        ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().executeShellCommand(command);
        try (InputStream input = new FileInputStream(descriptor.getFileDescriptor());
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int remaining = 180_000;
            int read;
            while (remaining > 0 && (read = input.read(buffer, 0, Math.min(buffer.length, remaining))) >= 0) {
                output.write(buffer, 0, read);
                remaining -= read;
            }
            return output.toByteArray();
        } finally {
            descriptor.close();
        }
    }

    private long tapComposerAction(String selector) throws Exception {
        return tapComposerAction(selector, "composer-action:" + selector);
    }

    private long tapComposerAction(String selector, String focusStage) throws Exception {
        ensureImeVisible(focusStage);
        assertTrue("Android IME must be visible immediately before tapping " + selector, isImeVisible());
        return tapDomCenter(selector);
    }

    private static String wrappedXtermMarkerSpanJs(String quotedMarker) {
        return "const findWrappedMarkerSpan=needle=>{for(let end=0;end<rows.length;end++){let joined='';"
                + "for(let start=end;start>=0;start--){joined=(rows[start].textContent||'')+joined;"
                + "const offset=joined.indexOf(needle);if(offset>=0)return {start,end,offset,joined,rows:rows.slice(start,end+1)};"
                + "}}return null;};const markerSpan=findWrappedMarkerSpan(" + quotedMarker + ");"
                + "const markerRowNodes=markerSpan?.rows??[];"
                + "const markerRowRects=markerRowNodes.map(node=>node.getBoundingClientRect());"
                + "const markerRect=markerRowRects.length?{top:markerRowRects[0].top,bottom:markerRowRects[markerRowRects.length-1].bottom,"
                + "left:Math.min(...markerRowRects.map(bounds=>bounds.left)),right:Math.max(...markerRowRects.map(bounds=>bounds.right))}:null;";
    }

    private long waitForTerminalMarkerOrCaptureWindow(String marker, long sendTouchUpUptimeMs) throws Exception {
        String quotedMarker = JSONObject.quote(marker);
        String expectedBytes = JSONObject.quote("636166c3a920f09fa7aa");
        try {
            awaitJsTrue("(() => {const status=document.querySelector('[data-testid=composer-status]');"
                + "const viewport=document.querySelector('.terminal-viewport');"
                + "const screen=viewport?.querySelector('.xterm-screen');"
                + "const composerBounds=document.querySelector('[data-testid=prompt-composer]')?.getBoundingClientRect();"
                + "const rows=Array.from(viewport?.querySelectorAll('.xterm-rows > div') ?? []);"
                + "const byteRow=rows.find(node=>(node.textContent||'').includes(" + expectedBytes + "));"
                + wrappedXtermMarkerSpanJs(quotedMarker)
                + "const byteBounds=byteRow?.getBoundingClientRect(),markerBounds=markerRect;"
                + "const byteRowIndex=rows.indexOf(byteRow);"
                + "const view=viewport?.getBoundingClientRect(),screenBounds=screen?.getBoundingClientRect();"
                + "const visible=(bounds,outer,inner)=>!!bounds&&!!outer&&!!inner&&!!composerBounds&&bounds.top>=outer.top&&bounds.bottom<=outer.bottom"
                + "&&bounds.left>=outer.left&&bounds.right<=outer.right&&bounds.top>=inner.top&&bounds.bottom<=inner.bottom"
                + "&&bounds.left>=inner.left&&bounds.right<=inner.right&&bounds.bottom<=composerBounds.top;"
                + "const markerRowsVisible=!!markerSpan&&markerRowRects.length>0&&markerRowRects.every(bounds=>visible(bounds,view,screenBounds));"
                + "return status?.dataset.deliveryState==='success'&&status.textContent.includes('Sent to the terminal')"
                + "&&document.querySelector('[data-testid=prompt-draft]')?.value===''"
                + "&&visible(byteBounds,view,screenBounds)&&markerRowsVisible"
                + "&&byteRowIndex>=0&&byteRowIndex<markerSpan.start&&byteBounds.bottom<=markerBounds.top+0.5"
                + "&&document.querySelector('.screen-content')?.scrollTop===0&&document.scrollingElement?.scrollTop===0;})()",
                5_000);
        } catch (AssertionError failure) {
            String bounds = evalString("(() => {const v=document.querySelector('.terminal-viewport');"
                + "const screen=v?.querySelector('.xterm-screen');"
                + "const composer=document.querySelector('[data-testid=prompt-composer]');"
                + "const rows=Array.from(v?.querySelectorAll('.xterm-rows > div') ?? []);"
                + "const byteRow=rows.find(node=>(node.textContent||'').includes('636166c3a920f09fa7aa'));"
                + wrappedXtermMarkerSpanJs(quotedMarker)
                + "const rect=node=>{const r=node?.getBoundingClientRect();return r?{top:r.top,bottom:r.bottom,left:r.left,right:r.right}:null};"
                + "return JSON.stringify({status:document.querySelector('[data-testid=composer-status]')?.dataset.deliveryState,"
                + "draft:document.querySelector('[data-testid=prompt-draft]')?.value,"
                + "byteRowIndex:rows.indexOf(byteRow),markerSpanStart:markerSpan?.start??null,markerSpanEnd:markerSpan?.end??null,"
                + "bytes:rect(byteRow),marker:markerRect,markerFragments:markerRowNodes.map((node,index)=>"
                + "({rowIndex:markerSpan.start+index,text:node.textContent||'',bounds:rect(node)})),"
                + "viewport:rect(v),screen:rect(screen),composer:rect(composer),screenScroll:document.querySelector('.screen-content')?.scrollTop,"
                + "documentScroll:document.scrollingElement?.scrollTop});})()");
            throw new AssertionError("Post-send rendered-row bounds: " + bounds, failure);
        }
        return SystemClock.uptimeMillis() - sendTouchUpUptimeMs;
    }

    private void savePostSendArtifacts(String runId, String expectedMarker, String submittedCommand,
            long sendToVisibleOutputLatencyMs) throws Exception {
        String report = evalString("(() => {const viewport=document.querySelector('.terminal-viewport');"
                + "const rect=viewport?.getBoundingClientRect();"
                + "const appBarRect=document.querySelector('.app-bar')?.getBoundingClientRect();"
                + "const terminalHeading=document.querySelector('#terminal-title');"
                + "const terminalHeadingRect=terminalHeading?.getBoundingClientRect();"
                + "const composerRect=document.querySelector('[data-testid=prompt-composer]')?.getBoundingClientRect();"
                + "const terminalScreenRect=viewport?.querySelector('.xterm-screen')?.getBoundingClientRect();"
                + "const terminalScroller=viewport?.querySelector('.xterm-viewport');"
                + "const rows=Array.from(viewport?.querySelectorAll('.xterm-rows > div') ?? []);"
                + "const byteOutputRow=rows.find(row=>(row.textContent||'').includes('636166c3a920f09fa7aa'));"
                + wrappedXtermMarkerSpanJs(JSONObject.quote(expectedMarker))
                + "const byteOutputRect=byteOutputRow?.getBoundingClientRect();"
                + "const byteOutputRowIndex=rows.indexOf(byteOutputRow);"
                + "const visibleText=window.__ps2857TerminalVisibleText || '';"
                + "const terminalDomRows=rows.map(row=>row.textContent||'').join('\\n').slice(-4000);"
                + "const terminalDomText=rows.map(row=>row.textContent||'').join('').slice(-4000);"
                + "const height=window.visualViewport?.height ?? innerHeight;"
                + "const markerRowsVisible=!!rect&&!!terminalScreenRect&&!!markerSpan&&markerRowRects.length>0"
                + "&&markerRowRects.every(bounds=>bounds.top>=rect.top&&bounds.bottom<=rect.bottom"
                + "&&bounds.left>=rect.left&&bounds.right<=rect.right"
                + "&&bounds.top>=terminalScreenRect.top&&bounds.bottom<=terminalScreenRect.bottom"
                + "&&bounds.left>=terminalScreenRect.left&&bounds.right<=terminalScreenRect.right"
                + "&&!!composerRect&&bounds.bottom<=composerRect.top);"
                + "const byteOutputVisible=!!rect&&!!terminalScreenRect&&!!byteOutputRect"
                + "&&byteOutputRect.top>=rect.top&&byteOutputRect.bottom<=rect.bottom&&byteOutputRect.left>=rect.left&&byteOutputRect.right<=rect.right"
                + "&&byteOutputRect.top>=terminalScreenRect.top&&byteOutputRect.bottom<=terminalScreenRect.bottom"
                + "&&byteOutputRect.left>=terminalScreenRect.left&&byteOutputRect.right<=terminalScreenRect.right"
                + "&&!!composerRect&&byteOutputRect.bottom<=composerRect.top;"
                + "const screenScrollTop=document.querySelector('.screen-content')?.scrollTop??null;"
                + "const documentScrollTop=document.scrollingElement?.scrollTop??null;"
                + "const capturedBeforeScroll=screenScrollTop===0&&documentScrollTop===0;"
                + "return JSON.stringify({stage:'after-send',capturedBeforeScroll,expectedMarker:" + JSONObject.quote(expectedMarker)
                + ",sendToVisibleOutputLatencyMs:" + sendToVisibleOutputLatencyMs
                + ",sendToVisibleOutputTiming:'Android uptime from Send touch-up to the first 60ms WebView poll with both executed rows rendered inside the visible xterm screen'"
                + ",captureEnabled:window.__ps2857CaptureTerminalEvidence===true,"
                + "terminalEvidenceSource:'xterm-active-buffer-after-render',visibleTerminalText:visibleText,terminalDomText:terminalDomText,"
                + "appTerminalDeliveryCount:window.__ps2857AppTerminalDeliveryCount??0,appTerminalMissingRefCount:window.__ps2857AppTerminalMissingRefCount??0,"
                + "appTerminalLastChunk:window.__ps2857AppTerminalLastChunk??'',terminalWriteCount:window.__ps2857TerminalWriteCount??0,"
                + "terminalLastWriteText:window.__ps2857TerminalLastWriteText??'',terminalRenderCount:window.__ps2857TerminalRenderCount??0,"
                + "sentMarkerAbsentFromSubmittedCommand:" + !submittedCommand.contains(expectedMarker) + ","
                + "terminalViewport:rect?{top:rect.top,bottom:rect.bottom,left:rect.left,right:rect.right,width:rect.width,height:rect.height}:null,"
                + "terminalHeading:terminalHeadingRect?{text:terminalHeading.textContent?.trim()??'',top:terminalHeadingRect.top,"
                + "bottom:terminalHeadingRect.bottom,left:terminalHeadingRect.left,right:terminalHeadingRect.right}:null,"
                + "terminalScreen:terminalScreenRect?{top:terminalScreenRect.top,bottom:terminalScreenRect.bottom,left:terminalScreenRect.left,right:terminalScreenRect.right}:null,"
                + "appBar:appBarRect?{top:appBarRect.top,bottom:appBarRect.bottom,left:appBarRect.left,right:appBarRect.right}:null,"
                + "composer:composerRect?{top:composerRect.top,bottom:composerRect.bottom,left:composerRect.left,right:composerRect.right}:null,"
                + "markerRow:markerRect?{top:markerRect.top,bottom:markerRect.bottom,left:markerRect.left,right:markerRect.right}:null,"
                + "markerRowSpan:{startRowIndex:markerSpan?.start??null,endRowIndex:markerSpan?.end??null,"
                + "matchedText:markerSpan?.joined??'',fragments:markerRowNodes.map((node,index)=>{const bounds=markerRowRects[index];"
                + "return {rowIndex:markerSpan.start+index,text:node.textContent||'',top:bounds.top,bottom:bounds.bottom,"
                + "left:bounds.left,right:bounds.right};})},"
                + "byteOutputRow:byteOutputRect?{top:byteOutputRect.top,bottom:byteOutputRect.bottom,left:byteOutputRect.left,right:byteOutputRect.right}:null,"
                + "byteOutputRowIndex:byteOutputRowIndex,"
                + "terminalScroller:{scrollTop:terminalScroller?.scrollTop??null,scrollHeight:terminalScroller?.scrollHeight??null,clientHeight:terminalScroller?.clientHeight??null},"
                + "terminalOutputRowVisible:markerRowsVisible&&byteOutputVisible&&byteOutputRowIndex>=0"
                + "&&byteOutputRowIndex<markerSpan.start&&byteOutputRect.bottom<=markerRect.top+0.5,"
                + "markerRowsVisible,byteOutputVisible,visualViewport:{height,width:window.visualViewport?.width ?? innerWidth},"
                + "keyboardVisible:document.querySelector('.app-shell')?.dataset.keyboardVisible==='true',"
                + "screenScrollTop,documentScrollTop,"
                + "terminalDomText,terminalDomRows,deliveryStatus:document.querySelector('[data-testid=composer-status]')?.textContent.trim() ?? ''});})() ");
        JSONObject measured = new JSONObject(report);
        byte[] reportBytes = measured.toString().getBytes(StandardCharsets.UTF_8);
        awaitWebViewVisualState();
        AtomicReference<byte[]> screenshotArtifact = new AtomicReference<>();
        AtomicReference<Boolean> saved = new AtomicReference<>(false);
        onMainActivity(activity -> {
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
        JSONObject appBar = measured.getJSONObject("appBar");
        JSONObject terminalHeading = measured.getJSONObject("terminalHeading");
        JSONObject composer = measured.getJSONObject("composer");
        JSONObject byteOutputRow = measured.getJSONObject("byteOutputRow");
        JSONObject markerRow = measured.getJSONObject("markerRow");
        double composerTop = composer.getDouble("top");
        assertTrue("post-send app bar and terminal heading must remain visible above the composer sheet",
                !measured.getBoolean("keyboardVisible")
                        && appBar.getDouble("top") >= 0
                        && appBar.getDouble("bottom") <= visualViewport.getDouble("height") + 0.5
                        && appBar.getDouble("bottom") <= terminalHeading.getDouble("top")
                        && terminalHeading.getDouble("bottom") <= viewport.getDouble("top")
                        && terminalHeading.getDouble("bottom") <= composerTop
                        && !terminalHeading.getString("text").isBlank()
                        && composer.getDouble("top") >= 0
                        && composer.getDouble("bottom") <= visualViewport.getDouble("height") + 0.5);
        assertTrue("post-send byte and marker output rows must remain visible above the composer sheet",
                        byteOutputRow.getDouble("top") >= viewport.getDouble("top")
                        && byteOutputRow.getDouble("bottom") <= viewport.getDouble("bottom")
                        && markerRow.getDouble("top") >= viewport.getDouble("top")
                        && markerRow.getDouble("bottom") <= viewport.getDouble("bottom")
                        && byteOutputRow.getDouble("bottom") <= composerTop
                        && markerRow.getDouble("bottom") <= composerTop
                        && measured.getBoolean("terminalOutputRowVisible"));
        assertTrue("post-send terminal and composer must remain visible without scrolling",
                measured.getBoolean("capturedBeforeScroll")
                        && measured.getDouble("screenScrollTop") == 0
                        && measured.getDouble("documentScrollTop") == 0);
        assertTrue("the app terminal subscription must deliver PTY bytes to its mounted Xterm component",
                measured.getInt("appTerminalDeliveryCount") > 0 && measured.getInt("appTerminalMissingRefCount") == 0
                        && measured.getInt("terminalWriteCount") > 0);
        assertTrue("the sent output row must already be rendered inside the onscreen terminal without test scrolling",
                measured.getBoolean("terminalOutputRowVisible")
                        && visibleText.contains(expectedMarker)
                        && measured.getString("terminalDomText").contains(expectedMarker));
    }

    private void awaitWebViewVisualState() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        onMainActivity(activity -> {
            WebView webView = findWebView(activity.getWindow().getDecorView());
            assertNotNull("packaged Capacitor activity must contain a WebView", webView);
            long requestId = SystemClock.uptimeMillis();
            webView.postVisualStateCallback(requestId, new WebView.VisualStateCallback() {
                @Override
                public void onComplete(long completedRequestId) {
                    latch.countDown();
                }
            });
        });
        assertTrue("WebView did not commit the measured post-send state before screenshot capture",
                latch.await(JS_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        SystemClock.sleep(250);
    }

    private String saveKeyboardGeometry(String runId) throws Exception {
        String geometry = evalString("(() => {const rect=(selector) => {const node=document.querySelector(selector);"
                + "if(!node)return null;const r=node.getBoundingClientRect();return {top:r.top,bottom:r.bottom,left:r.left,right:r.right,width:r.width,height:r.height};};"
                + "const style=(selector) => {const node=document.querySelector(selector);if(!node)return null;const s=getComputedStyle(node);return {display:s.display,position:s.position,visibility:s.visibility,overflow:s.overflow,overflowY:s.overflowY,zIndex:s.zIndex};};"
                + "const composer=document.querySelector('[data-testid=prompt-composer]');"
                + "const heading=composer?.querySelector('.composer-heading__copy');const headingStyle=heading?getComputedStyle(heading):null;"
                + "return JSON.stringify({innerHeight,innerWidth,outerHeight,outerWidth,scrollY,clientHeight:document.documentElement.clientHeight,"
                + "visualViewport:window.visualViewport?{height:visualViewport.height,width:visualViewport.width,offsetTop:visualViewport.offsetTop}:null,"
                + "screen:{height:screen.height,width:screen.width},activeElement:document.activeElement?.outerHTML?.slice(0,300)??null,"
                + "shell:rect('.app-shell'),screenContent:rect('.screen-content'),terminal:rect('.terminal-panel'),"
                + "terminalViewport:rect('.terminal-viewport'),"
                + "appBar:rect('.app-bar'),draft:rect('[data-testid=prompt-draft]'),"
                + "status:rect('[data-testid=composer-status]'),actions:rect('[data-testid=composer-actions]'),"
                + "composerIsSheet:composer?.classList.contains('composer-panel--sheet')===true,"
                + "composerHeading:rect('.composer-panel--sheet .composer-heading'),"
                + "composerHeadingVisible:!!heading&&heading.getClientRects().length>0&&headingStyle?.display!=='none'"
                + "&&headingStyle?.visibility!=='hidden'&&Number(headingStyle?.opacity??0)>0.95,"
                + "composerHeadingDisplay:headingStyle?.display??'',"
                + "buttons:{discard:rect('[data-testid=composer-discard]'),insert:rect('[data-testid=composer-insert]'),send:rect('.composer-shared-controls .send'),"
                + "keys:rect('[data-testid=composer-open-keys]')},"
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
        onMainActivity(activity -> {
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
        onMainActivity(activity -> {
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
        onMainActivity(activity -> {
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

    private long tapDomCenter(String selector) throws Exception {
        JSONObject point = evalJson("(() => {const element = document.querySelector(" + JSONObject.quote(selector)
                + "); if (!element) return JSON.stringify({missing:true}); const rect=element.getBoundingClientRect();"
                + "const height=window.visualViewport?.height ?? innerHeight;const x=rect.left+rect.width/2,y=rect.top+rect.height/2;"
                + "const hit=document.elementFromPoint(x,y);const label=node=>node?{tag:node.tagName||'',id:node.id||'',"
                + "testid:node.getAttribute?.('data-testid')||'',className:typeof node.className==='string'?node.className:''}:null;"
                + "const targetHit=selector=>selector==='[data-testid=prompt-draft]'?hit===element:!!hit?.closest?.(selector);"
                + "return JSON.stringify({selector:" + JSONObject.quote(selector)
                + ",x,y,width:innerWidth,cssHeight:innerHeight,top:rect.top,bottom:rect.bottom,left:rect.left,right:rect.right,height,"
                + "visualViewport:{width:window.visualViewport?.width??innerWidth,height:window.visualViewport?.height??innerHeight,"
                + "offsetLeft:window.visualViewport?.offsetLeft??0,offsetTop:window.visualViewport?.offsetTop??0},"
                + "centerHit:label(hit),centerHitMatchesTarget:targetHit(" + JSONObject.quote(selector) + "),"
                + "activeElementBefore:{tag:document.activeElement?.tagName||'',id:document.activeElement?.id||'',"
                + "testid:document.activeElement?.getAttribute?.('data-testid')||''},disabled:!!element.disabled});})()");
        assertTrue("WebView touch target must exist", !point.optBoolean("missing"));
        assertTrue("WebView touch target must be enabled", !point.optBoolean("disabled"));
        assertTrue("WebView touch target center must hit the requested DOM target: " + point,
                point.getBoolean("centerHitMatchesTarget"));
        assertTrue("WebView touch target must be visibly inside the Android viewport: " + point,
                point.optDouble("top", -1) >= 0 && point.optDouble("bottom", -1) <= point.optDouble("height") + 0.5
                        && point.optDouble("left", -1) >= 0 && point.optDouble("right", -1) <= point.optDouble("width") + 0.5);
        AtomicReference<float[]> screenPoint = new AtomicReference<>();
        AtomicReference<JSONObject> nativeMapping = new AtomicReference<>();
        onMainActivity(activity -> {
            WebView webView = findWebView(activity.getWindow().getDecorView());
            assertNotNull("packaged Capacitor activity must contain a WebView", webView);
            int[] location = new int[2];
            webView.getLocationOnScreen(location);
            double cssWidth = point.optDouble("width");
            double cssHeight = point.optDouble("cssHeight");
            float scaleX = webView.getWidth() / (float) cssWidth;
            float scaleY = webView.getHeight() / (float) cssHeight;
            float screenX = location[0] + (float) point.optDouble("x") * scaleX;
            float screenY = location[1] + (float) point.optDouble("y") * scaleY;
            screenPoint.set(new float[] {screenX, screenY});
            WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
            View focusedView = activity.getWindow().getDecorView().findFocus();
            try {
                nativeMapping.set(new JSONObject().put("webViewScreenX", location[0]).put("webViewScreenY", location[1])
                        .put("webViewWidthPx", webView.getWidth()).put("webViewHeightPx", webView.getHeight())
                        .put("cssWidth", cssWidth).put("cssHeight", cssHeight).put("scaleX", scaleX).put("scaleY", scaleY)
                        .put("webViewHasFocus", webView.hasFocus())
                        .put("windowHasFocus", activity.getWindow().getDecorView().hasWindowFocus())
                        .put("nativeFocusedView", focusedView == null ? "" : focusedView.getClass().getName())
                        .put("imeVisible", insets != null && insets.isVisible(WindowInsets.Type.ime()))
                        .put("imeBottomPx", insets == null ? 0 : insets.getInsets(WindowInsets.Type.ime()).bottom));
            } catch (JSONException error) {
                throw new RuntimeException(error);
            }
        });
        float[] screen = screenPoint.get();
        long downTime = SystemClock.uptimeMillis();
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, screen[0], screen[1], 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        boolean downInjected = instrumentation.getUiAutomation().injectInputEvent(down, true);
        down.recycle();
        assertTrue("Android touchscreen ACTION_DOWN must be injected", downInjected);
        SystemClock.sleep(60);
        long upTime = SystemClock.uptimeMillis();
        MotionEvent up = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, screen[0], screen[1], 0);
        up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        boolean upInjected = instrumentation.getUiAutomation().injectInputEvent(up, true);
        up.recycle();
        assertTrue("Android touchscreen ACTION_UP must be injected", upInjected);
        lastPhysicalTapEvidence = new JSONObject(point.toString()).put("nativeMapping", nativeMapping.get())
                .put("screenX", screen[0]).put("screenY", screen[1]).put("touchDownUptimeMs", downTime)
                .put("touchUpUptimeMs", upTime).put("downInjected", downInjected).put("upInjected", upInjected);
        return upTime;
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
        WebView webView = packagedWebView;
        assertNotNull("packaged Capacitor activity must contain a WebView", webView);
        assertTrue("packaged WebView rejected JavaScript evaluation", webView.post(() ->
                webView.evaluateJavascript(expression, value -> {
                    result.set(value);
                    latch.countDown();
                })));
        assertTrue("timed out evaluating packaged WebView JavaScript", latch.await(JS_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (result.get() == null || "null".equals(result.get())) throw new JSONException("JavaScript returned null: " + expression);
        return result.get();
    }

    private void onMainActivity(Consumer<MainActivity> action) {
        MainActivity activity = packagedActivity;
        assertNotNull("packaged MainActivity must remain available during the journey", activity);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> action.accept(activity));
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
