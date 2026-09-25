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
import androidx.lifecycle.Lifecycle;

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
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
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
    private String artifactRunId;
    private boolean forceFirstPostAttachTapMiss;
    private int composerFocusMaxAttempts = 2;
    private final JSONArray focusTapAttempts = new JSONArray();
    private JSONObject lastPhysicalTapEvidence;
    private JSONObject lastDictationTestEvent = new JSONObject();
    private boolean focusTraceEmitted;
    private boolean focusFailureCaptured;

    @Before
    public void launchPackagedShell() {
        scenario = ActivityScenario.launch(MainActivity.class);
    }

    @After
    public void closeShell() {
        try {
            emitFocusTraceIfNeeded();
        } catch (Exception error) {
            Log.e("PS2891Focus", "could not emit composer tap trace before ActivityScenario teardown", error);
        }
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
        String privateKey = new String(Base64.getDecoder().decode(encodedKey), StandardCharsets.UTF_8);
        bytesSession = nameBase + "-bytes";
        uncertainSession = nameBase + "-uncertain";

        awaitJsTrue("document.querySelector('[data-testid=build-status] > span:nth-child(2)')?.textContent.trim() === 'Build verified'");
        setVoicePreferencesForComposerJourney();
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
        verifyNestedAndroidBackKeepsLiveSession();

        exerciseComposerDictationMode(artifactRunId, nameBase);
        installControlledSpeechAdapter();
        exerciseInlineTerminalDictation(nameBase, bytesSession, uncertainSession, artifactRunId);

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

        int beforeRouteBack = backButtonEventCount();
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'settings'"
                + " && Number(document.querySelector('.app-shell')?.dataset.backButtonEvents) > " + beforeRouteBack);
        Log.i("PS2857Back", "after-nested-route-back|" + backUiState());

        int beforeHomeBack = backButtonEventCount();
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'home'"
                + " && document.querySelector('.app-shell')?.dataset.sshPhase === 'live'"
                + " && !!document.querySelector('[data-testid=prompt-composer]')"
                + " && Number(document.querySelector('.app-shell')?.dataset.backButtonEvents) > " + beforeHomeBack);
        Log.i("PS2857Back", "after-workspace-back|" + backUiState());
    }

    private int backButtonEventCount() throws Exception {
        return Integer.parseInt(evalString("document.querySelector('.app-shell')?.dataset.backButtonEvents ?? '0'"));
    }

    private String backUiState() throws Exception {
        String webState = evalString("(() => {const shell=document.querySelector('.app-shell');"
                + "return JSON.stringify({route:shell?.dataset.route,keyboardVisible:shell?.dataset.keyboardVisible,"
                + "backButtonReady:shell?.dataset.backButtonReady,backButtonEvents:shell?.dataset.backButtonEvents,"
                + "activeElement:document.activeElement?.outerHTML?.slice(0,180)});})()");
        AtomicReference<String> nativeState = new AtomicReference<>("unavailable");
        scenario.onActivity(activity -> {
            WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
            nativeState.set("imeVisible=" + (insets != null && insets.isVisible(WindowInsets.Type.ime()))
                    + ",windowFocus=" + activity.getWindow().getDecorView().hasWindowFocus());
        });
        return webState + "|" + nativeState.get();
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
        awaitJsTrue("!!document.querySelector('[data-testid=prompt-composer]')");
        awaitJsTrue("document.activeElement?.classList.contains('xterm-helper-textarea')", 5_000);
    }

    private void setComposerDraft(String value) throws Exception {
        setValue("[data-testid=prompt-draft]", value);
        awaitJsTrue("document.querySelector('[data-testid=prompt-draft]')?.value === " + JSONObject.quote(value));
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
        awaitJsTrue("!!document.querySelector('[data-testid=inline-dictation-toggle]')"
                + " && document.querySelector('[data-testid=inline-dictation-toggle]')?.disabled === false");
        String inlineMarker = "PS2857_INLINE_" + nameBase;
        String inlineCommand = "printf '%s' 'café 🧪' | od -An -tx1 | tr -d '[:space:]' > /tmp/"
                + sessionName + "-inline-utf8.hex; printf '%s' '" + inlineMarker + "' > /tmp/"
                + sessionName + "-inline-submitted.marker";
        int acknowledgementsBefore = terminalInputAcknowledgements();

        tapDomCenter("[data-testid=inline-dictation-toggle]");
        awaitJsTrue("document.querySelector('[data-testid=inline-dictation-bar]')?.dataset.phase === 'listening'"
                + " && document.querySelector('[data-testid=inline-dictation-toggle]')?.textContent.includes('Stop')");
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
                + " && document.querySelector('[data-testid=inline-dictation-status]')?.textContent.includes('Inserted at the cursor')"
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

        // ActivityScenario drives BridgeActivity.onStop/onResume, which fires the
        // real Capacitor appStateChange event consumed by the mounted app and bar.
        scenario.moveToState(Lifecycle.State.CREATED);
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.sshPhase === 'background'", 10_000);
        scenario.moveToState(Lifecycle.State.RESUMED);
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.sshPhase === 'live'", 15_000);
        evalString("window.__ps2857ControlledSpeech.emit('result', " + JSONObject.quote(lateCommand) + "); 'late final emitted'");
        evalString("window.__ps2857ControlledSpeech.emit('stopped'); 'late stopped emitted'");
        awaitJsTrue("document.querySelector('[data-testid=inline-dictation-bar]')?.dataset.phase === 'idle'"
                + " && Number(document.querySelector('.app-shell')?.dataset.sshTerminalInputAcks) === "
                + acknowledgementsBefore, 15_000);
        assertEquals("the mounted inline bar must request speech stop on native background", requestId,
                evalString("window.__ps2857ControlledSpeech?.stopOptions?.requestId ?? ''"));
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
                + " && document.querySelector('[data-testid=inline-dictation-status]')?.textContent.includes('Inserted at the cursor')"
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
        scenario.onActivity(activity -> {
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

    private void exerciseComposerDictationMode(String runId, String nameBase) throws Exception {
        grantMicrophonePermissionForJourney();
        evalString("window.__ps2857DictationTestMode = true; 'debug dictation test mode enabled'");
        String original = "keep this typed draft " + nameBase;
        setComposerDraft(original);
        tapDomCenter("[data-testid=prompt-draft]");
        awaitImeVisible(true);
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.keyboardVisible === 'true'");
        tapDomCenter("[data-testid=composer-dictate]");
        awaitJsTrue("document.querySelector('[data-testid=prompt-composer]')?.dataset.dictationState === 'recording'", 15_000);
        JSONObject nativeOptions = new JSONObject(injectDictationTestEvent("partial", "discard this dictated phrase"));
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
                + "const preview=document.querySelector('[data-testid=composer-recording-preview]');"
                + "return draft?.classList.contains('composer-draft--dictation-anchor')===true"
                + " && style?.display!=='none' && style?.visibility!=='hidden' && style?.opacity==='0'"
                + " && draft?.getAttribute('aria-hidden')!=='true' && draft?.getAttribute('aria-readonly')==='true'"
                + " && draft?.getAttribute('aria-label')==='Dictation draft, read only while dictating'"
                + " && draft?.getBoundingClientRect().width<=1 && draft?.getBoundingClientRect().height<=1"
                + " && mode?.getClientRects().length>0 && preview?.textContent.includes('discard this dictated phrase')"
                + " && mode.querySelector('[data-testid=composer-recording-cancel]')?.textContent.includes('Cancel')"
                + " && mode.querySelector('[data-testid=composer-recording-stop]')?.textContent.includes('Stop');})()";
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
        assertTrue("recording surface must identify its animation as capture state rather than microphone volume",
                "true".equals(evalRaw("document.querySelector('[data-testid=composer-recording-mode]')?.textContent.includes('Recording prompt')"
                        + " && document.querySelector('.recording-mode__waveform')?.getAttribute('aria-label').includes('not volume')")));
        String timer = evalString("document.querySelector('[data-testid=composer-recording-timer]')?.textContent.trim() ?? ''");
        assertTrue("recording mode must show a formatted elapsed timer", timer.matches("\\d{2}:\\d{2}"));

        tapDomCenter("[data-testid=composer-recording-cancel]");
        awaitJsTrue("document.querySelector('[data-testid=prompt-composer]')?.dataset.dictationState === 'idle'"
                + " && document.querySelector('[data-testid=prompt-draft]')?.value === " + JSONObject.quote(original));
        awaitImeVisible(true);
        awaitJsTrue("document.activeElement === document.querySelector('[data-testid=prompt-draft]')"
                + " && document.querySelector('.app-shell')?.dataset.keyboardVisible === 'true'");
        recordComposerModeState(runId, "cancel", original);
        injectDictationTestEvent("partial", "late partial after Cancel must be ignored");
        SystemClock.sleep(250);
        assertEquals("Cancel must restore the original draft and ignore later native partials", original,
                evalString("document.querySelector('[data-testid=prompt-draft]')?.value ?? ''"));

        tapDomCenter("[data-testid=composer-dictate]");
        awaitJsTrue("document.querySelector('[data-testid=prompt-composer]')?.dataset.dictationState === 'recording'", 15_000);
        injectDictationTestEvent("partial", "background must discard this partial");
        awaitJsTrue("document.querySelector('[data-testid=prompt-draft]')?.value.includes('background must discard this partial')");
        scenario.moveToState(Lifecycle.State.CREATED);
        SystemClock.sleep(250);
        scenario.moveToState(Lifecycle.State.RESUMED);
        awaitJsTrue("document.querySelector('[data-testid=prompt-composer]')?.dataset.dictationState === 'idle'"
                + " && document.querySelector('[data-testid=prompt-draft]')?.value === " + JSONObject.quote(original));
        injectDictationTestEvent("result", "late final after background must be ignored");
        SystemClock.sleep(250);
        assertEquals("background cancellation must restore the original draft and reject late final events", original,
                evalString("document.querySelector('[data-testid=prompt-draft]')?.value ?? ''"));
        tapDomCenter("[data-testid=prompt-draft]");
        awaitImeVisible(true);
        recordComposerModeState(runId, "background", original);

        setComposerDraft("");
        tapDomCenter("[data-testid=composer-dictate]");
        awaitJsTrue("document.querySelector('[data-testid=prompt-composer]')?.dataset.dictationState === 'recording'", 15_000);
        String dictationCommand = "printf '%s' 'PS2857_DICTATION_" + nameBase
                + "' > /tmp/" + nameBase + "-bytes-dictation.marker";
        injectDictationTestEvent("partial", dictationCommand);
        awaitJsTrue("document.querySelector('[data-testid=prompt-draft]')?.value.includes(" + JSONObject.quote(dictationCommand) + ")");
        String timerBeforeNaturalPause = evalString("document.querySelector('[data-testid=composer-recording-timer]')?.textContent.trim() ?? ''");
        injectDictationTestEvent("processing", null);
        String recordingAfterEndpoint = "(() => {const composer=document.querySelector('[data-testid=prompt-composer]');"
                + "const stop=document.querySelector('[data-testid=composer-recording-stop]');"
                + "return composer?.dataset.dictationState==='recording' && !!stop && !stop.disabled"
                + " && composer.dataset.acknowledgedWrites==='0';})()";
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
                + " && composer.dataset.acknowledgedWrites==='0';})()";
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
        awaitImeVisible(true);
        awaitJsTrue("document.activeElement === document.querySelector('[data-testid=prompt-draft]')"
                + " && document.querySelector('.app-shell')?.dataset.keyboardVisible === 'true'");
        recordComposerModeState(runId, "transcribing", dictationCommand);
        assertTrue("Stop must enter transcribing without sending the draft", "true".equals(evalRaw(
                "document.querySelector('[data-testid=composer-status]')?.textContent.includes('not be sent automatically')"
                        + " && document.querySelector('[data-testid=prompt-composer]')?.dataset.acknowledgedWrites === '0'")));
        injectDictationTestEvent("finish", null);
        awaitJsTrue("document.querySelector('[data-testid=prompt-composer]')?.dataset.dictationState === 'review'"
                + " && document.querySelector('[data-testid=composer-dictation-review]')"
                + " && document.querySelector('[data-testid=prompt-draft]')?.readOnly === false"
                + " && document.querySelector('[data-testid=prompt-draft]')?.getAttribute('aria-readonly') === 'false'", 10_000);
        recordComposerModeState(runId, "review", dictationCommand);
        assertEquals("Stop must keep recognized text in the editable review draft", dictationCommand,
                evalString("document.querySelector('[data-testid=prompt-draft]')?.value ?? ''"));

        String editedCommand = dictationCommand.replace("PS2857_DICTATION_", "PS2857_DICTATION_EDITED_");
        setComposerDraft(editedCommand);
        assertEquals("review draft must accept edits before delivery", editedCommand,
                evalString("document.querySelector('[data-testid=prompt-draft]')?.value ?? ''"));
        assertEquals("dictated text must not reach the terminal before explicit Send", "0",
                evalString("document.querySelector('[data-testid=prompt-composer]')?.dataset.acknowledgedWrites ?? ''"));
        String editedMarker = "PS2857_DICTATION_EDITED_" + nameBase;
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
        assertTrue("debug event injection must be reset after the packaged composer journey",
                "true".equals(evalRaw("(window.__ps2857DictationTestMode = false) === false")));
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

    private void recordComposerModeState(String runId, String state, String expectedDraft) throws Exception {
        awaitWebViewVisualState();
        String report = evalString("(() => {const rect=(selector)=>{const node=document.querySelector(selector);"
                + "if(!node)return null;const r=node.getBoundingClientRect();return {top:r.top,bottom:r.bottom,left:r.left,right:r.right,width:r.width,height:r.height};};"
                + "const composer=document.querySelector('[data-testid=prompt-composer]');"
                + "const draft=document.querySelector('[data-testid=prompt-draft]');"
                + "const mode=document.querySelector('[data-testid=composer-recording-mode]');"
                + "const preview=document.querySelector('[data-testid=composer-recording-preview]');"
                + "const composerStatus=document.querySelector('[data-testid=composer-status]');"
                + "const cancelButton=document.querySelector('[data-testid=composer-recording-cancel]');"
                + "const stopButton=document.querySelector('[data-testid=composer-recording-stop]');"
                + "const transcribingStatus=mode?.querySelector('[role=status][aria-live=polite]');"
                + "return JSON.stringify({runId:" + JSONObject.quote(runId) + ",state:" + JSONObject.quote(state)
                + ",dictationState:composer?.dataset.dictationState??'',"
                + "keyboardVisible:document.querySelector('.app-shell')?.dataset.keyboardVisible==='true',"
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
                + "previewText:preview?.textContent.trim()??'',"
                + "cancelAccessible:!!cancelButton&&(cancelButton.textContent??'').includes('Cancel')&&cancelButton.getClientRects().length>0,"
                + "stopAccessible:!!stopButton&&(stopButton.getAttribute('aria-label')??'').includes('Stop dictation')"
                + "&&(stopButton.textContent??'').includes('Stop')&&stopButton.getClientRects().length>0,"
                + "transcribingStatusAccessible:!!transcribingStatus&&transcribingStatus.getClientRects().length>0,"
                + "composerStatusAccessible:composerStatus?.getAttribute('role')==='status'&&composerStatus?.getAttribute('aria-live')==='polite',"
                + "reviewVisible:!!document.querySelector('[data-testid=composer-dictation-review]')"
                + "&&getComputedStyle(document.querySelector('[data-testid=composer-dictation-review]')).display!=='none',"
                + "recordingMode:rect('[data-testid=composer-recording-mode]'),timer:rect('[data-testid=composer-recording-timer]'),"
                + "preview:rect('[data-testid=composer-recording-preview]'),review:rect('[data-testid=composer-dictation-review]'),"
                + "status:rect('[data-testid=composer-status]'),actions:rect('[data-testid=composer-actions]'),"
                + "cancel:rect('[data-testid=composer-recording-cancel]'),stop:rect('[data-testid=composer-recording-stop]'),"
                + "send:rect('.composer-shared-controls .send'),visualViewport:{height:window.visualViewport?.height??innerHeight,width:window.visualViewport?.width??innerWidth},"
                + "screenScrollTop:document.querySelector('.screen-content')?.scrollTop??null,documentScrollTop:document.scrollingElement?.scrollTop??null,"
                + "statusText:document.querySelector('[data-testid=composer-status]')?.textContent.trim()??'',"
                + "waveformLabel:mode?.querySelector('.recording-mode__waveform')?.getAttribute('aria-label')??null});})() ");
        JSONObject measured = new JSONObject(report).put("androidImeVisible", isImeVisible());
        measured.put("lastDictationTestEvent", lastDictationTestEvent);
        byte[] geometry = measured.toString().getBytes(StandardCharsets.UTF_8);
        emitCurrentScreen(runId, "composer-" + state + ".png");
        emitArtifact(runId, "composer-" + state + "-geometry.json", geometry);
        if (!measured.getBoolean("androidImeVisible")) {
            emitCurrentScreen(runId, "composer-mode-ime-failure.png");
            emitArtifact(runId, "composer-mode-ime-failure.json", geometry);
            throw new AssertionError("the Android keyboard must remain visible for the " + state + " screenshot; state="
                    + measured);
        }
        String expectedPhase = state.equals("cancel") || state.equals("background") ? "idle"
                : state.startsWith("recording") ? "recording" : state;
        assertEquals("composer phase must match the " + state + " screenshot", expectedPhase, measured.getString("dictationState"));
        assertTrue("state screenshot must retain the expected draft text", measured.getBoolean("expectedDraftMatches"));
        assertTrue("keyboard-up composer screenshot must retain visible IME evidence", measured.getBoolean("androidImeVisible")
                && measured.getBoolean("keyboardVisible"));
        assertTrue("dictation screenshots must keep the composer draft as the active element",
                measured.getBoolean("draftFocused") && "prompt-draft".equals(measured.getString("activeElementTestId")));
        boolean dictationBusy = state.startsWith("recording") || state.equals("transcribing");
        assertEquals("busy dictation must replace the visible editor with its accessible focus anchor",
                dictationBusy ? "focus-anchor" : "editor", measured.getString("draftPresentation"));
        assertTrue("the focused dictation draft anchor must stay in the accessibility tree",
                !measured.getBoolean("draftAriaHidden")
                        && (!dictationBusy || measured.getString("draftAriaLabel").contains("read only while dictating")));
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
                assertTrue("recording preview and Stop control must be visible and accessible",
                        measured.getBoolean("previewVisible") && measured.getBoolean("previewLive")
                                && measured.getBoolean("stopAccessible")
                                && measured.getString("draftDescribedBy").contains("composer-recording-preview"));
            } else {
                assertTrue("transcribing state must expose an accessible live status",
                        measured.getBoolean("transcribingStatusAccessible"));
            }
        } else {
            assertTrue("idle and review must retain the ordinary composer textarea",
                    state.equals("review") ? measured.getBoolean("reviewVisible") : !measured.getBoolean("recordingModeVisible"));
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
        scenario.onActivity(activity -> {
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
        ensureImeVisible("post-inline-dictation-attach");
        SystemClock.sleep(350);
        assertTrue("Android IME must still be open for the keyboard-up capture", isImeVisible());
        assertTrue("capture the real keyboard-up composer before checking its visible bounds", saveKeyboardScreenshot(runId));
        String geometry = saveKeyboardGeometry(runId);
        try {
            awaitJsTrue("(() => {const shell=document.querySelector('.app-shell');"
                    + "const appBar=document.querySelector('.app-bar')?.getBoundingClientRect();"
                    + "const terminal=document.querySelector('.terminal-viewport')?.getBoundingClientRect();"
                    + "const height=window.visualViewport?.height ?? innerHeight;"
                    + "const safeTop=parseFloat(getComputedStyle(shell).paddingTop)||0;"
                    + "const selectors=['[data-testid=prompt-draft]','[data-testid=composer-status]',"
                    + "'[data-testid=composer-discard]','[data-testid=composer-insert]','.composer-shared-controls .send'];"
                    + "const visible=selectors.every(selector=>{const node=document.querySelector(selector);"
                    + "if(!node)return false;const rect=node.getBoundingClientRect();return rect.top >= 0 && rect.bottom <= height + 0.5"
                    + " && rect.left >= 0 && rect.right <= innerWidth + 0.5;});"
                    + "const nav=Array.from(document.querySelectorAll('.workspace-navigation button'));"
                    + "const navVisible=nav.length >= 3 && nav.every(node=>{const rect=node.getBoundingClientRect();"
                    + "return rect.top >= 0 && rect.bottom <= height + 0.5 && rect.left >= 0 && rect.right <= innerWidth + 0.5;});"
                    + "const screen=document.querySelector('.screen-content');"
                    + "return shell?.dataset.keyboardVisible === 'true' && !!appBar && !!terminal && terminal.height >= 48"
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
        JSONObject buttons = keyboardGeometry.getJSONObject("buttons");
        for (String name : new String[]{"discard", "insert", "send"}) {
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
                String targetSelector = injectMiss ? ".terminal-viewport" : "[data-testid=prompt-draft]";
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
                focusTapAttempts.put(record);
                Log.i("PS2891Focus", "ATTEMPT|" + artifactRunId + "|" + record);
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
                + "const active=document.activeElement;const rect=draft?.getBoundingClientRect();"
                + "const x=rect?rect.left+rect.width/2:0,y=rect?rect.top+rect.height/2:0,hit=document.elementFromPoint(x,y);"
                + "const label=node=>node?{tag:node.tagName||'',id:node.id||'',testid:node.getAttribute?.('data-testid')||'',"
                + "className:typeof node.className==='string'?node.className:''}:null;"
                + "const bounds=node=>{const r=node?.getBoundingClientRect();return r?{top:r.top,bottom:r.bottom,left:r.left,right:r.right,width:r.width,height:r.height}:null};"
                + "const shell=document.querySelector('.app-shell');return JSON.stringify({uptimeHintMs:performance.now(),"
                + "route:shell?.dataset.route||'',homeSurface:shell?.dataset.homeSurface||'',sshPhase:shell?.dataset.sshPhase||'',"
                + "keyboardVisible:shell?.dataset.keyboardVisible==='true',keyboardComposerMode:shell?.dataset.keyboardComposerMode==='true',"
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
            scenario.onActivity(activity -> {
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
            scenario.onActivity(activity -> {
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
        scenario.onActivity(activity -> {
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
        scenario.onActivity(activity -> {
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

    private long waitForTerminalMarkerOrCaptureWindow(String marker, long sendTouchUpUptimeMs) throws Exception {
        String quotedMarker = JSONObject.quote(marker);
        String expectedBytes = JSONObject.quote("636166c3a920f09fa7aa");
        try {
            awaitJsTrue("(() => {const status=document.querySelector('[data-testid=composer-status]');"
                + "const viewport=document.querySelector('.terminal-viewport');"
                + "const screen=viewport?.querySelector('.xterm-screen');"
                + "const rows=Array.from(viewport?.querySelectorAll('.xterm-rows > div') ?? []);"
                + "const byteRow=rows.find(node=>(node.textContent||'').includes(" + expectedBytes + "));"
                + "const markerRow=rows.find(node=>(node.textContent||'').includes(" + quotedMarker + "));"
                + "const byteBounds=byteRow?.getBoundingClientRect(),markerBounds=markerRow?.getBoundingClientRect();"
                + "const view=viewport?.getBoundingClientRect(),screenBounds=screen?.getBoundingClientRect();"
                + "const visible=(bounds,outer,inner)=>!!bounds&&!!outer&&!!inner&&bounds.top>=outer.top&&bounds.bottom<=outer.bottom"
                + "&&bounds.left>=outer.left&&bounds.right<=outer.right&&bounds.top>=inner.top&&bounds.bottom<=inner.bottom"
                + "&&bounds.left>=inner.left&&bounds.right<=inner.right;"
                + "return status?.dataset.deliveryState==='success'&&status.textContent.includes('Sent to the terminal')"
                + "&&document.querySelector('[data-testid=prompt-draft]')?.value===''"
                + "&&visible(byteBounds,view,screenBounds)&&visible(markerBounds,view,screenBounds)"
                + "&&byteRow!==markerRow&&byteBounds.bottom<=markerBounds.top+0.5"
                + "&&document.querySelector('.screen-content')?.scrollTop===0&&document.scrollingElement?.scrollTop===0;})()",
                5_000);
        } catch (AssertionError failure) {
            String bounds = evalString("(() => {const v=document.querySelector('.terminal-viewport');"
                + "const screen=v?.querySelector('.xterm-screen');"
                + "const rows=Array.from(v?.querySelectorAll('.xterm-rows > div') ?? []);"
                + "const pick=text=>rows.find(node=>(node.textContent||'').includes(text));"
                + "const rect=node=>{const r=node?.getBoundingClientRect();return r?{top:r.top,bottom:r.bottom,left:r.left,right:r.right}:null};"
                + "return JSON.stringify({status:document.querySelector('[data-testid=composer-status]')?.dataset.deliveryState,"
                + "draft:document.querySelector('[data-testid=prompt-draft]')?.value,"
                + "bytes:rect(pick('636166c3a920f09fa7aa')),marker:rect(pick(" + quotedMarker + ")),"
                + "viewport:rect(v),screen:rect(screen),screenScroll:document.querySelector('.screen-content')?.scrollTop,"
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
                + "const composerRect=document.querySelector('[data-testid=prompt-composer]')?.getBoundingClientRect();"
                + "const terminalScreenRect=viewport?.querySelector('.xterm-screen')?.getBoundingClientRect();"
                + "const terminalScroller=viewport?.querySelector('.xterm-viewport');"
                + "const markerRow=Array.from(viewport?.querySelectorAll('.xterm-rows > div') ?? [])"
                + ".find(row=>(row.textContent||'').includes(" + JSONObject.quote(expectedMarker) + "));"
                + "const byteOutputRow=Array.from(viewport?.querySelectorAll('.xterm-rows > div') ?? [])"
                + ".find(row=>(row.textContent||'').includes('636166c3a920f09fa7aa'));"
                + "const markerRect=markerRow?.getBoundingClientRect();"
                + "const byteOutputRect=byteOutputRow?.getBoundingClientRect();"
                + "const visibleText=window.__ps2857TerminalVisibleText || '';"
                + "const terminalDomText=Array.from(viewport?.querySelectorAll('.xterm-rows > div') ?? [])"
                + ".map(row=>row.textContent || '').join('\\n').slice(-4000);"
                + "const height=window.visualViewport?.height ?? innerHeight;"
                + "const markerVisible=!!rect&&!!terminalScreenRect&&!!markerRect"
                + "&&markerRect.top>=rect.top&&markerRect.bottom<=rect.bottom&&markerRect.left>=rect.left&&markerRect.right<=rect.right"
                + "&&markerRect.top>=terminalScreenRect.top&&markerRect.bottom<=terminalScreenRect.bottom"
                + "&&markerRect.left>=terminalScreenRect.left&&markerRect.right<=terminalScreenRect.right;"
                + "const byteOutputVisible=!!rect&&!!terminalScreenRect&&!!byteOutputRect"
                + "&&byteOutputRect.top>=rect.top&&byteOutputRect.bottom<=rect.bottom&&byteOutputRect.left>=rect.left&&byteOutputRect.right<=rect.right"
                + "&&byteOutputRect.top>=terminalScreenRect.top&&byteOutputRect.bottom<=terminalScreenRect.bottom"
                + "&&byteOutputRect.left>=terminalScreenRect.left&&byteOutputRect.right<=terminalScreenRect.right;"
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
                + "terminalScreen:terminalScreenRect?{top:terminalScreenRect.top,bottom:terminalScreenRect.bottom,left:terminalScreenRect.left,right:terminalScreenRect.right}:null,"
                + "appBar:appBarRect?{top:appBarRect.top,bottom:appBarRect.bottom,left:appBarRect.left,right:appBarRect.right}:null,"
                + "composer:composerRect?{top:composerRect.top,bottom:composerRect.bottom,left:composerRect.left,right:composerRect.right}:null,"
                + "markerRow:markerRect?{top:markerRect.top,bottom:markerRect.bottom,left:markerRect.left,right:markerRect.right}:null,"
                + "byteOutputRow:byteOutputRect?{top:byteOutputRect.top,bottom:byteOutputRect.bottom,left:byteOutputRect.left,right:byteOutputRect.right}:null,"
                + "terminalScroller:{scrollTop:terminalScroller?.scrollTop??null,scrollHeight:terminalScroller?.scrollHeight??null,clientHeight:terminalScroller?.clientHeight??null},"
                + "terminalOutputRowVisible:markerVisible&&byteOutputVisible&&byteOutputRow!==markerRow&&byteOutputRect.bottom<=markerRect.top+0.5,"
                + "byteOutputVisible,visualViewport:{height,width:window.visualViewport?.width ?? innerWidth},"
                + "keyboardVisible:document.querySelector('.app-shell')?.dataset.keyboardVisible==='true',"
                + "screenScrollTop,documentScrollTop,"
                + "deliveryStatus:document.querySelector('[data-testid=composer-status]')?.textContent.trim() ?? ''});})() ");
        JSONObject measured = new JSONObject(report);
        byte[] reportBytes = measured.toString().getBytes(StandardCharsets.UTF_8);
        awaitWebViewVisualState();
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
        JSONObject appBar = measured.getJSONObject("appBar");
        JSONObject composer = measured.getJSONObject("composer");
        assertTrue("immediate post-send chrome, terminal and composer must remain visible without scrolling",
                !measured.getBoolean("keyboardVisible")
                        && appBar.getDouble("top") >= 0
                        && appBar.getDouble("bottom") <= visualViewport.getDouble("height") + 0.5
                && appBar.getDouble("bottom") <= viewport.getDouble("top")
                && composer.getDouble("top") >= viewport.getDouble("bottom")
                && composer.getDouble("bottom") <= visualViewport.getDouble("height") + 0.5
                        && measured.getBoolean("capturedBeforeScroll")
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
        scenario.onActivity(activity -> {
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
        assertTrue("WebView touch target must be visibly inside the Android viewport: " + point,
                point.optDouble("top", -1) >= 0 && point.optDouble("bottom", -1) <= point.optDouble("height") + 0.5
                        && point.optDouble("left", -1) >= 0 && point.optDouble("right", -1) <= point.optDouble("width") + 0.5);
        AtomicReference<float[]> screenPoint = new AtomicReference<>();
        AtomicReference<JSONObject> nativeMapping = new AtomicReference<>();
        scenario.onActivity(activity -> {
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
