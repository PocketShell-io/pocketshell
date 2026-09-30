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
import android.view.accessibility.AccessibilityNodeInfo;
import android.webkit.WebView;
import android.view.inputmethod.InputMethodManager;

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
    private final JSONArray composerActionTapEvidence = new JSONArray();
    private JSONObject lastPhysicalTapEvidence;
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
        String phase = arguments.getString("composerPhase", "resume");
        assertTrue("composerPhase must be prepare or resume", phase.equals("prepare") || phase.equals("resume"));
        artifactRunId = phase.equals("resume") ? arguments.getString("artifactRunId", nameBase) : null;
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
        String testTag = nameBase.substring(Math.max(0, nameBase.length() - 5));
        String chipLabel = "PS2885-M-" + testTag;
        String chipOutputPath = "/tmp/" + bytesSession + "-snippet-lines.txt";
        String chipBody = "printf 'alpha\\nβeta\\n🙂\\n' > " + chipOutputPath;
        String uncertainMarker = "PS2857_UNCERTAIN_" + nameBase;
        String uncertainCommand = "printf '%s' '" + uncertainMarker + "' > /tmp/" + uncertainSession + "-uncertain.marker\n"
                + "# PS2857_MULTILINE_SUFFIX_" + nameBase;
        String uncertainChipLabel = "PS2885-R-" + testTag;
        String transientChipLabel = "PS2885-X-" + testTag;
        if (phase.equals("prepare")) {
            prepareHostSnippets(host, port, privateKey, bytesSession, uncertainSession,
                    chipLabel, chipBody, uncertainChipLabel, uncertainCommand, transientChipLabel, testTag);
            return;
        }
        chipLabel = "PS2885-ME-" + testTag;

        String oldPid = arguments.getString("oldAppPid");
        String stoppedStatus = arguments.getString("stoppedAppStatus");
        String resolvedActivity = arguments.getString("resolvedActivity");
        assertNotNull("resume phase requires the app PID captured before force-stop", oldPid);
        assertNotNull("resume phase requires the external force-stop result", stoppedStatus);
        assertNotNull("resume phase requires the activity component resolved by the host", resolvedActivity);
        assertTrue("wrapper must verify that the target process is absent between invocations",
                !oldPid.isEmpty() && stoppedStatus.equals("absent"));
        assertTrue("resolved launch activity must belong to the installed target package",
                resolvedActivity.startsWith(InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName() + "/"));
        evalString("window.__ps2857CaptureTerminalEvidence = true; 'terminal evidence enabled after relaunch'");
        setValue("[data-testid=ssh-host]", host);
        setValue("[data-testid=ssh-port]", port);
        setValue("[data-testid=ssh-username]", "testuser");
        setValue("[data-testid=ssh-private-key]", privateKey);
        click("[data-testid=ssh-connect]");
        awaitTrustOrConnected();
        awaitJsTrue("['connected','listing'].includes(document.querySelector('.app-shell')?.dataset.sshPhase)");
        attachSession(bytesSession);
        JSONObject restartEvidence = verifyRestartAndReconnect(oldPid, stoppedStatus, resolvedActivity, bytesSession,
                chipLabel, chipBody, uncertainChipLabel, uncertainCommand);
        emitArtifact(artifactRunId, "snippet-restart-evidence.json",
                restartEvidence.toString(2).getBytes(StandardCharsets.UTF_8));
        evalString("window.__ps2885CaptureSnippetEvidence = true; window.__ps2885ComposerWriteCount = 0; 'enabled'");
        awaitImeVisible(false);
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.keyboardVisible === 'false'");
        JSONObject chipsDown = readSnippetChipGeometry(chipLabel, chipBody)
                .put("imeVisible", isImeVisible()).put("expectedLabel", "Insert " + chipLabel);
        saveSnippetScreenshot(artifactRunId, "snippet-keyboard-down.png");
        emitArtifact(artifactRunId, "snippet-keyboard-down-geometry.json", chipsDown.toString().getBytes(StandardCharsets.UTF_8));
        assertSnippetChipLayout(chipsDown, false);

        JSONObject accessibility = activateSnippetThroughAccessibility("Insert " + chipLabel);
        String selectedChipExpression = "Array.from(document.querySelectorAll('[data-testid=snippet-chip]'))"
                + ".some(node => node.getAttribute('aria-label') === "
                + JSONObject.quote("Insert " + chipLabel)
                + " && node.getAttribute('aria-current') === 'true')";
        awaitJsTrue("document.querySelector('[data-testid=prompt-draft]')?.value === " + JSONObject.quote(chipBody)
                + " && " + selectedChipExpression);
        awaitJsTrue("document.querySelector('[data-testid=prompt-composer]')?.dataset.acknowledgedWrites === '0'"
                + " && (window.__ps2885ComposerWriteCount ?? 0) === 0");
        JSONObject chipSelected = readSnippetChipGeometry(chipLabel, chipBody)
                .put("imeVisible", isImeVisible()).put("expectedLabel", "Insert " + chipLabel)
                .put("accessibilityNode", accessibility)
                .put("composerWriteCountBeforeSend", Integer.parseInt(evalString("String(window.__ps2885ComposerWriteCount ?? 0)")))
                .put("acknowledgedWritesBeforeSend", Integer.parseInt(evalString("document.querySelector('[data-testid=prompt-composer]')?.dataset.acknowledgedWrites ?? '0'")));
        assertSnippetChipLayout(chipSelected, true);
        saveSnippetScreenshot(artifactRunId, "snippet-selected-chip.png");
        emitArtifact(artifactRunId, "snippet-selected-chip-geometry.json", chipSelected.toString().getBytes(StandardCharsets.UTF_8));

        // The selected here-document must stay a draft until this explicit Send.
        assertEquals("chip selection has not called the composer PTY writer", 0,
                Integer.parseInt(evalString("String(window.__ps2885ComposerWriteCount ?? 0)")));
        String preSendMarker = "/tmp/" + bytesSession + "-snippet-before-send.marker";
        String preSendGuard = "if test -e " + chipOutputPath + "; then printf EXECUTED > " + preSendMarker
                + "; else printf NOT_EXECUTED > " + preSendMarker + "; fi";
        attachSession(uncertainSession);
        setComposerDraft(preSendGuard);
        tapComposerAction(".composer-shared-controls .send", "snippet-no-execution-check");
        awaitDeliveredAndCleared();
        attachSession(bytesSession);
        awaitJsTrue("document.querySelector('[data-testid=prompt-draft]')?.value === " + JSONObject.quote(chipBody));
        evalString("window.__ps2885ComposerWriteCount = 0; 'count reset before explicit snippet Send'");
        awaitJsTrue("(window.__ps2885ComposerWriteCount ?? 0) === 0"
                + " && document.querySelector('[data-testid=prompt-composer]')?.dataset.acknowledgedWrites === '0'");
        tapComposerAction(".composer-shared-controls .send", "snippet-explicit-send");
        awaitDeliveredAndCleared();
        assertTrue("snippet send must use the composer PTY writer", Integer.parseInt(
                evalString("String(window.__ps2885ComposerWriteCount ?? 0)")) > 0);

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
        awaitJsTrue("Array.from(document.querySelectorAll('[data-testid=snippet-chip]'))"
                + ".some(node => node.getAttribute('aria-label') === " + JSONObject.quote("Insert " + uncertainChipLabel) + ")");
        awaitAppWindowActive("before hiding the IME for the reconnected chip");
        hideImeUntilStableWithoutEditableFocus();
        awaitAppWindowActive("after hiding the IME for the reconnected chip");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.keyboardVisible === 'false'"
                + " && document.querySelector('.app-shell')?.dataset.sshPhase === 'live'");
        evalString("window.__ps2885ComposerWriteCount = 0; 'counter reset before reconnect chip selection'");
        JSONObject uncertainAccessibility = activateSnippetThroughAccessibility("Insert " + uncertainChipLabel);
        assertEquals("reconnected per-host chip keeps its accessible label", "Insert " + uncertainChipLabel,
                uncertainAccessibility.getString("label"));
        awaitJsTrue("document.querySelector('[data-testid=prompt-draft]')?.value === " + JSONObject.quote(uncertainCommand)
                + " && Array.from(document.querySelectorAll('[data-testid=snippet-chip]'))"
                + ".some(node=>node.getAttribute('aria-label')===" + JSONObject.quote("Insert " + uncertainChipLabel)
                + "&&node.getAttribute('aria-current')==='true')"
                + " && (window.__ps2885ComposerWriteCount ?? 0) === 0"
                + " && document.querySelector('[data-testid=prompt-composer]')?.dataset.acknowledgedWrites === '0'");
        armDisconnectAfterFirstAcknowledgement();
        tapComposerAction(".composer-shared-controls .send", "uncertain-session-after-attach");
        assertTrue("uncertain-session attach must recover composer focus from a physical draft tap",
                hasSuccessfulPhysicalDraftTap("uncertain-session-after-attach"));
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.sshPhase === 'idle'", 30_000);
        awaitJsTrue("!document.querySelector('[data-testid=prompt-composer]')", 10_000);

        click("[data-testid=ssh-connect]");
        awaitTrustOrConnected();
        awaitJsTrue("['connected','listing'].includes(document.querySelector('.app-shell')?.dataset.sshPhase)");
        attachSession(uncertainSession);
        awaitJsTrue("document.querySelector('[data-testid=prompt-draft]')?.value === " + JSONObject.quote(uncertainCommand));
        assertEquals("uncertain delivery must keep the exact draft after reattach", uncertainCommand,
                evalString("document.querySelector('[data-testid=prompt-draft]')?.value ?? ''"));
        awaitJsTrue("Array.from(document.querySelectorAll('[data-testid=snippet-chip]'))"
                + ".some(node => node.getAttribute('aria-label') === " + JSONObject.quote("Insert " + uncertainChipLabel) + ")");
        emitFocusTraceIfNeeded();
    }

    private void prepareHostSnippets(String host, String port, String privateKey, String bytesSession,
            String uncertainSession, String chipLabel, String chipBody, String uncertainChipLabel,
            String uncertainCommand, String transientChipLabel, String testTag) throws Exception {
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
        deletePriorTestChips();
        createHostSnippet(chipLabel, chipBody);
        createHostSnippet(uncertainChipLabel, uncertainCommand);
        createHostSnippet(transientChipLabel, "This temporary test chip must be deleted.");
        exerciseSnippetEditReorderAndDelete(chipLabel, uncertainChipLabel, transientChipLabel, chipBody, testTag);
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
        int before = Integer.parseInt(evalString("document.querySelector('.app-shell')?.dataset.backButtonEvents ?? '0'"));
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'settings'"
                + " && Number(document.querySelector('.app-shell')?.dataset.backButtonEvents) > " + before);
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'home'"
                + " && document.querySelector('.app-shell')?.dataset.sshPhase === 'live'"
                + " && !!document.querySelector('[data-testid=prompt-composer]')");
    }

    private void createHostSnippet(String label, String body) throws Exception {
        click("[data-testid=manage-snippets]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'settings-snippets'");
        awaitJsTrue("document.querySelector('[data-testid=host-snippets-screen]')?.dataset.hostId === "
                + "document.querySelector('[data-testid=prompt-composer]')?.dataset.snippetHostId");
        setValue("[data-testid=snippet-label]", label);
        setValue("[data-testid=snippet-body]", body);
        click("[data-testid=save-snippet]");
        awaitJsTrue("Array.from(document.querySelectorAll('[data-testid=host-snippet-list] li'))"
                + ".some(row => row.textContent.includes(" + JSONObject.quote(label) + "))");
        click("button[aria-label='Back']");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'settings'");
        click("button[aria-label='Back']");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'home'"
                + " && document.querySelector('.app-shell')?.dataset.homeSurface === 'live'");
    }

    private void deletePriorTestChips() throws Exception {
        openSnippetManager();
        JSONArray labels = new JSONArray(evalString("JSON.stringify(Array.from(document.querySelectorAll('[data-testid=host-snippet-list] li'))"
                + ".map(row=>row.querySelector('.managed-snippet__heading strong')?.textContent.trim()||'')"
                + ".filter(label=>/^PS2885-(M|ME|R|X)-/.test(label)))"));
        for (int index = 0; index < labels.length(); index += 1) deleteManagedSnippet(labels.getString(index));
        leaveSnippetManager();
    }

    private String exerciseSnippetEditReorderAndDelete(String mainLabel, String retryLabel,
            String transientLabel, String exactBody, String testTag) throws Exception {
        openSnippetManager();
        int mainBefore = managedSnippetIndex(mainLabel);
        int retryBefore = managedSnippetIndex(retryLabel);
        assertTrue("newly created host chips begin in saved order", mainBefore >= 0 && retryBefore > mainBefore);
        click("button[aria-label=" + JSONObject.quote("Move " + retryLabel + " up") + "]");
        awaitJsTrue("(" + managedSnippetIndexExpression(retryLabel) + ") < (" + managedSnippetIndexExpression(mainLabel) + ")");

        click("button[aria-label=" + JSONObject.quote("Edit " + mainLabel) + "]");
        String editedLabel = "PS2885-ME-" + testTag;
        setValue("[data-testid=snippet-label]", editedLabel);
        assertEquals("editing a chip must keep its exact multiline body", exactBody,
                evalString("document.querySelector('[data-testid=snippet-body]')?.value ?? ''"));
        click("[data-testid=save-snippet]");
        awaitJsTrue("Array.from(document.querySelectorAll('[data-testid=host-snippet-list] li'))"
                + ".some(row=>row.querySelector('.managed-snippet__heading strong')?.textContent.trim()==="
                + JSONObject.quote(editedLabel) + ")");
        assertTrue("editing a chip persists its position", managedSnippetIndex(retryLabel) < managedSnippetIndex(editedLabel));
        deleteManagedSnippet(transientLabel);
        leaveSnippetManager();
        return editedLabel;
    }

    private int managedSnippetIndex(String label) throws Exception {
        return Integer.parseInt(evalString(managedSnippetIndexExpression(label)));
    }

    private String managedSnippetIndexExpression(String label) {
        return "Array.from(document.querySelectorAll('[data-testid=host-snippet-list] li'))"
                + ".findIndex(row=>row.querySelector('.managed-snippet__heading strong')?.textContent.trim()==="
                + JSONObject.quote(label) + ")";
    }

    private void openSnippetManager() throws Exception {
        click("[data-testid=manage-snippets]");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'settings-snippets'");
    }

    private void leaveSnippetManager() throws Exception {
        click("button[aria-label='Back']");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'settings'");
        click("button[aria-label='Back']");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.route === 'home'"
                + " && document.querySelector('.app-shell')?.dataset.homeSurface === 'live'");
    }

    private void deleteManagedSnippet(String label) throws Exception {
        String deleteSelector = "button[aria-label=" + JSONObject.quote("Delete " + label) + "]";
        String confirmSelector = "button[aria-label=" + JSONObject.quote("Confirm delete " + label) + "]";
        click(deleteSelector);
        awaitJsTrue("!!document.querySelector(" + JSONObject.quote(confirmSelector) + ")");
        click(confirmSelector);
        awaitJsTrue("!Array.from(document.querySelectorAll('[data-testid=host-snippet-list] li'))"
                + ".some(row=>row.querySelector('.managed-snippet__heading strong')?.textContent.trim()==="
                + JSONObject.quote(label) + ")");
        JSONObject storage = readHostSnippetStorage();
        Log.i("PS2885Storage", "after-delete|label=" + label + "|" + storage);
        assertTrue("confirmed deletion must be durable before restart; snapshot=" + storage,
                !containsString(storage.getJSONArray("snippetLabels"), label));
    }

    private JSONObject verifyRestartAndReconnect(String oldPid, String stoppedStatus, String resolvedActivity,
            String session, String chipLabel, String chipBody,
            String uncertainChipLabel, String uncertainCommand) throws Exception {
        String packageName = InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName();
        String processIds = new String(executeShellCommand("pidof " + packageName), StandardCharsets.UTF_8).trim();
        String restartedPid = processIds.isEmpty() ? "" : processIds.split("\\s+")[0];
        assertTrue("external wrapper force-stop must have launched a new packaged process on resume",
                !restartedPid.isEmpty() && !oldPid.equals(restartedPid));
        JSONObject storageAtResume = readHostSnippetStorage();
        Log.i("PS2885Storage", "after-resume-attach|" + storageAtResume);
        awaitJsTrue("Array.from(document.querySelectorAll('[data-testid=snippet-chip]'))"
                + ".filter(node => node.getAttribute('aria-label') === " + JSONObject.quote("Insert " + chipLabel) + ").length === 1"
                + " && Array.from(document.querySelectorAll('[data-testid=snippet-chip]'))"
                + ".filter(node => node.getAttribute('aria-label') === " + JSONObject.quote("Insert " + uncertainChipLabel) + ").length === 1");
        JSONArray restoredOrder = new JSONArray(evalString(
                "JSON.stringify(Array.from(document.querySelectorAll('[data-testid=snippet-chip]'))"
                        + ".map(node=>node.textContent.trim()))"));
        assertEquals("the reordered host chip order must persist after process restart and reconnect",
                new JSONArray().put(uncertainChipLabel).put(chipLabel).toString(), restoredOrder.toString());
        JSONObject storage = evalJson("(() => {const hostId=document.querySelector('[data-testid=prompt-composer]')?.dataset.snippetHostId||'';"
                + "const saved=JSON.parse(localStorage.getItem('pocketshell.js.host-snippets.v1')||'null');"
                + "const hostSnippets=saved?.snippets?.filter(item=>item.hostId===hostId)||[];"
                + "return JSON.stringify({hostId,storedSnippetCount:hostSnippets.length,exactMain:hostSnippets.filter(item=>item.label==="
                + JSONObject.quote(chipLabel) + "&&item.body===" + JSONObject.quote(chipBody) + ").length===1,"
                + "exactUncertain:hostSnippets.filter(item=>item.label===" + JSONObject.quote(uncertainChipLabel)
                + "&&item.body===" + JSONObject.quote(uncertainCommand) + ").length===1});})()");
        assertTrue("durable host identity must remain stable after force-stop/reconnect", !storage.getString("hostId").isEmpty());
        assertTrue("main snippet label and exact body must survive a real process restart", storage.getBoolean("exactMain"));
        assertTrue("second snippet label and exact body must survive a real process restart", storage.getBoolean("exactUncertain"));
        Log.i("PS2885Restart", "RELAUNCH|" + packageName + "|oldPid=" + oldPid
                + "|stoppedStatus=" + stoppedStatus + "|newPid=" + restartedPid
                + "|activity=" + resolvedActivity + "|hostId=" + storage.getString("hostId"));
        return new JSONObject()
                .put("appPackage", packageName)
                .put("launchableActivity", resolvedActivity)
                .put("oldPid", oldPid)
                .put("stoppedPid", "")
                .put("stoppedStatus", stoppedStatus)
                .put("newPid", restartedPid)
                .put("hostId", storage.getString("hostId"))
                .put("storedSnippetCount", storage.getInt("storedSnippetCount"))
                .put("mainSnippetExact", storage.getBoolean("exactMain"))
                .put("uncertainSnippetExact", storage.getBoolean("exactUncertain"))
                .put("mainLabel", chipLabel)
                .put("uncertainLabel", uncertainChipLabel)
                .put("storageKey", "pocketshell.js.host-snippets.v1");
    }

    private JSONObject readHostSnippetStorage() throws Exception {
        return evalJson("(() => {const hostId=document.querySelector('[data-testid=prompt-composer]')?.dataset.snippetHostId||'';"
                + "const saved=JSON.parse(localStorage.getItem('pocketshell.js.host-snippets.v1')||'null');"
                + "const snippets=saved?.snippets?.filter(item=>item.hostId===hostId)||[];"
                + "return JSON.stringify({hostId,legacyImportComplete:saved?.legacyImportComplete??false,"
                + "snippetLabels:snippets.map(item=>item.label),"
                + "snippets:snippets.map(({label,body,sortOrder})=>({label,body,sortOrder}))});})()");
    }

    private boolean containsString(JSONArray values, String expected) throws JSONException {
        for (int index = 0; index < values.length(); index += 1) {
            if (expected.equals(values.getString(index))) return true;
        }
        return false;
    }

    private JSONObject readSnippetChipGeometry(String label, String expectedBody) throws Exception {
        String labelExpression = JSONObject.quote("Insert " + label);
        String bodyExpression = JSONObject.quote(expectedBody);
        return evalJson("(() => {const shell=document.querySelector('.app-shell');"
                + "const row=document.querySelector('[data-testid=command-chips]');"
                + "const composer=document.querySelector('[data-testid=prompt-composer]');"
                + "const terminal=document.querySelector('.terminal-viewport');"
                + "const target=Array.from(document.querySelectorAll('[data-testid=snippet-chip]'))"
                + ".find(node=>node.getAttribute('aria-label')===" + labelExpression + ");"
                + "const rect=node=>{const r=node?.getBoundingClientRect();return r?{top:r.top,bottom:r.bottom,left:r.left,right:r.right,width:r.width,height:r.height}:null};"
                + "const chips=Array.from(document.querySelectorAll('[data-testid=snippet-chip]')).map(node=>({tag:node.tagName,"
                + "label:node.getAttribute('aria-label')||'',width:rect(node)?.width??0,height:rect(node)?.height??0,"
                + "top:rect(node)?.top??0,bottom:rect(node)?.bottom??0,left:rect(node)?.left??0,right:rect(node)?.right??0,"
                + "current:node.getAttribute('aria-current')||''}));"
                + "return JSON.stringify({keyboardVisible:shell?.dataset.keyboardVisible==='true',hostId:composer?.dataset.snippetHostId||'',"
                + "viewport:{width:window.visualViewport?.width??innerWidth,height:window.visualViewport?.height??innerHeight},"
                + "rowVisible:!!row&&getComputedStyle(row).display!=='none'&&rect(row)?.height>0,chipRow:rect(row),composer:rect(composer),terminal:rect(terminal),"
                + "target:{tag:target?.tagName||'',label:target?.getAttribute('aria-label')||'',current:target?.getAttribute('aria-current')||'',"
                + "top:rect(target)?.top??0,bottom:rect(target)?.bottom??0,left:rect(target)?.left??0,right:rect(target)?.right??0,"
                + "width:rect(target)?.width??0,height:rect(target)?.height??0},"
                + "chips,draftMatchesExact:document.querySelector('[data-testid=prompt-draft]')?.value===" + bodyExpression + ","
                + "composerWriteCount:window.__ps2885ComposerWriteCount??0,"
                + "acknowledgedWrites:Number(composer?.dataset.acknowledgedWrites??0),"
                + "screenScrollTop:document.querySelector('.screen-content')?.scrollTop??0,documentScrollTop:document.scrollingElement?.scrollTop??0});})()");
    }

    private void assertSnippetChipLayout(JSONObject geometry, boolean selected) throws Exception {
        assertTrue("command chip rail must be visible with the Android keyboard down", geometry.getBoolean("rowVisible")
                && !geometry.getBoolean("keyboardVisible"));
        JSONObject chipRow = geometry.getJSONObject("chipRow");
        JSONObject composer = geometry.getJSONObject("composer");
        JSONObject terminal = geometry.getJSONObject("terminal");
        JSONObject viewport = geometry.getJSONObject("viewport");
        assertTrue("keyboard-down chips, terminal, and composer must fit without overlap or page scrolling",
                chipRow.getDouble("top") >= composer.getDouble("top")
                        && chipRow.getDouble("bottom") <= composer.getDouble("bottom") + 0.5
                        && terminal.getDouble("bottom") <= composer.getDouble("top") + 0.5
                        && chipRow.getDouble("top") >= 0
                        && chipRow.getDouble("bottom") <= viewport.getDouble("height") + 0.5
                        && chipRow.getDouble("left") >= 0
                        && chipRow.getDouble("right") <= viewport.getDouble("width") + 0.5
                        && !geometry.getBoolean("imeVisible")
                        && geometry.getInt("screenScrollTop") == 0
                        && geometry.getInt("documentScrollTop") == 0);
        JSONArray chips = geometry.getJSONArray("chips");
        assertTrue("a saved snippet chip must be present", chips.length() > 0);
        for (int index = 0; index < chips.length(); index += 1) {
            JSONObject chip = chips.getJSONObject(index);
            assertEquals("command chip is exposed as a native button", "BUTTON", chip.getString("tag"));
            assertTrue("command chip has an accessible spoken label", chip.getString("label").startsWith("Insert "));
            assertTrue("command chip keeps at least a 48dp width and height",
                    chip.getDouble("width") >= 47.9 && chip.getDouble("height") >= 47.9);
            assertTrue("command chip target is inside the screen; geometry=" + chip + "; viewport=" + viewport,
                    chip.getDouble("top") >= 0
                    && chip.getDouble("bottom") <= viewport.getDouble("height") + 0.5 && chip.getDouble("left") >= 0
                    && chip.getDouble("right") <= viewport.getDouble("width") + 0.5);
        }
        JSONObject target = geometry.getJSONObject("target");
        assertTrue("the target chip uses the expected accessible name and hit area",
                geometry.getString("expectedLabel").equals(target.getString("label"))
                        && target.getDouble("width") >= 47.9 && target.getDouble("height") >= 47.9);
        if (selected) {
            assertTrue("selected chip inserts exact text and has a visible selected state without a PTY write",
                    geometry.getBoolean("draftMatchesExact")
                            && "true".equals(target.getString("current"))
                            && geometry.getInt("composerWriteCount") == 0
                            && geometry.getInt("acknowledgedWrites") == 0);
        }
    }

    private JSONObject activateSnippetThroughAccessibility(String expectedLabel) throws Exception {
        AccessibilityNodeInfo root = null;
        AccessibilityNodeInfo target = null;
        for (int attempt = 0; attempt < 20 && target == null; attempt += 1) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            root = InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();
            if (root != null) target = findAccessibilityNode(root, expectedLabel);
            if (target == null) SystemClock.sleep(250);
        }
        assertNotNull("the packaged WebView must expose an accessibility tree", root);
        if (target == null) {
            String tree = describeAccessibilityTree(root, 0);
            try {
                JSONObject failureEvidence = readSnippetAccessibilityFailureEvidence(expectedLabel, tree);
                Log.e("PS2885A11y", "accessibility miss evidence: " + failureEvidence);
                if (artifactRunId != null) {
                    byte[] evidence = failureEvidence.toString(2).getBytes(StandardCharsets.UTF_8);
                    scenario.onActivity(activity -> {
                        try (FileOutputStream output = new FileOutputStream(
                                new File(activity.getFilesDir(), "snippet-a11y-failure.json"))) {
                            output.write(evidence);
                        } catch (Exception error) {
                            throw new RuntimeException(error);
                        }
                    });
                    emitArtifact(artifactRunId, "snippet-a11y-failure.json", evidence);
                }
            } catch (Exception error) {
                Log.e("PS2885A11y", "could not capture state for missing chip " + expectedLabel, error);
            }
            Log.e("PS2885A11y", "could not find " + expectedLabel + " in Android accessibility tree: " + tree);
        }
        assertNotNull("the snippet chip's accessible label must be present in the native accessibility tree: " + expectedLabel, target);
        CharSequence description = target.getContentDescription();
        CharSequence text = target.getText();
        String observedLabel = description != null && description.length() > 0
                ? description.toString() : text == null ? "" : text.toString();
        String labelSource = description != null && description.length() > 0 ? "contentDescription" : "text";
        assertEquals("the chip's aria-label is exposed as its Android accessibility name", expectedLabel, observedLabel);
        assertTrue("snippet chip is actionable in the accessibility tree", target.isClickable());
        boolean focused = target.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
        assertTrue("TalkBack-style accessibility focus reaches the selected chip", focused);
        boolean clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        assertTrue("TalkBack-style accessibility activation selects the chip", clicked);
        return new JSONObject()
                .put("label", observedLabel)
                .put("labelSource", labelSource)
                .put("clickable", target.isClickable())
                .put("accessibilityFocusAccepted", focused)
                .put("accessibilityClickAccepted", clicked);
    }

    private JSONObject readSnippetAccessibilityFailureEvidence(String expectedLabel, String tree) throws Exception {
        JSONObject dom = evalJson("(() => {"
                + "const shell=document.querySelector('.app-shell');"
                + "const rail=document.querySelector('[data-testid=command-chips]');"
                + "const rect=(node)=>{if(!node)return null;const r=node.getBoundingClientRect();const s=getComputedStyle(node);"
                + "return {display:s.display,visibility:s.visibility,opacity:s.opacity,hidden:node.hidden,"
                + "ariaHidden:node.getAttribute('aria-hidden'),top:r.top,bottom:r.bottom,left:r.left,right:r.right,"
                + "width:r.width,height:r.height};};"
                + "return JSON.stringify({route:shell?.dataset.route||'',homeSurface:shell?.dataset.homeSurface||'',"
                + "sshPhase:shell?.dataset.sshPhase||'',keyboardVisible:shell?.dataset.keyboardVisible||'',"
                + "keyboardComposerMode:shell?.dataset.keyboardComposerMode||'',activeElement:{tag:document.activeElement?.tagName||'',"
                + "id:document.activeElement?.id||'',className:document.activeElement?.className||''},"
                + "railPresent:!!rail,rail:rect(rail),chips:Array.from(document.querySelectorAll('[data-testid=snippet-chip]'))"
                + ".map(node=>({label:node.getAttribute('aria-label')||'',current:node.getAttribute('aria-current')||'',rect:rect(node)}))});})()");
        AtomicReference<JSONObject> nativeState = new AtomicReference<>(new JSONObject());
        AtomicReference<Boolean> windowFocused = new AtomicReference<>(false);
        scenario.onActivity(activity -> {
            windowFocused.set(activity.hasWindowFocus());
            WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
            JSONObject state = new JSONObject();
            try {
                state.put("imeVisible", insets != null && Build.VERSION.SDK_INT >= 30
                        && insets.isVisible(WindowInsets.Type.ime()));
                state.put("imeBottomPx", insets == null ? 0 : insets.getInsets(WindowInsets.Type.ime()).bottom);
                state.put("windowHasFocus", activity.hasWindowFocus());
                state.put("activity", activity.getClass().getName());
            } catch (JSONException error) {
                throw new RuntimeException(error);
            }
            nativeState.set(state);
        });
        AccessibilityNodeInfo activeRoot = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .getRootInActiveWindow();
        CharSequence activePackage = activeRoot == null ? null : activeRoot.getPackageName();
        return new JSONObject()
                .put("expectedLabel", expectedLabel)
                .put("capturedAtAndroidUptimeMs", SystemClock.uptimeMillis())
                .put("appWindowFocused", windowFocused.get())
                .put("activeAccessibilityPackage", activePackage == null ? "" : activePackage.toString())
                .put("nativeInsets", nativeState.get())
                .put("dom", dom)
                .put("accessibilityTree", tree);
    }

    private AccessibilityNodeInfo findAccessibilityNode(AccessibilityNodeInfo parent, String expectedLabel) {
        CharSequence description = parent.getContentDescription();
        if (description != null && expectedLabel.contentEquals(description)) return parent;
        CharSequence text = parent.getText();
        if (text != null && expectedLabel.contentEquals(text)) return parent;
        for (int index = 0; index < parent.getChildCount(); index += 1) {
            AccessibilityNodeInfo child = parent.getChild(index);
            if (child == null) continue;
            AccessibilityNodeInfo match = findAccessibilityNode(child, expectedLabel);
            if (match != null) return match;
        }
        return null;
    }

    private String describeAccessibilityTree(AccessibilityNodeInfo node, int depth) {
        if (node == null) return "<null>";
        StringBuilder summary = new StringBuilder();
        summary.append("\n").append("  ".repeat(Math.min(depth, 12)))
                .append(node.getClassName()).append(" text=").append(node.getText())
                .append(" desc=").append(node.getContentDescription())
                .append(" clickable=").append(node.isClickable())
                .append(" children=").append(node.getChildCount());
        for (int index = 0; index < node.getChildCount(); index += 1) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child != null) summary.append(describeAccessibilityTree(child, depth + 1));
        }
        return summary.toString();
    }

    private void saveSnippetScreenshot(String runId, String name) throws Exception {
        awaitWebViewVisualState();
        AtomicReference<byte[]> artifact = new AtomicReference<>();
        AtomicReference<Boolean> saved = new AtomicReference<>(false);
        scenario.onActivity(activity -> {
            try {
                Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
                if (screenshot == null) return;
                ByteArrayOutputStream encoded = new ByteArrayOutputStream();
                boolean compressed = screenshot.compress(Bitmap.CompressFormat.PNG, 100, encoded);
                byte[] png = encoded.toByteArray();
                if (compressed && png.length >= 1024) {
                    try (FileOutputStream output = new FileOutputStream(new File(activity.getFilesDir(), name))) {
                        output.write(png);
                    }
                    artifact.set(png);
                    saved.set(true);
                }
                screenshot.recycle();
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
        assertTrue("same-run Android screenshot must be captured: " + name, saved.get());
        emitArtifact(runId, name, artifact.get());
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
        tapDomCenter("[data-testid=prompt-draft]");
        awaitImeVisible(true);
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.keyboardVisible === 'true'");
        awaitJsTrue("document.querySelector('.app-shell')?.dataset.keyboardComposerMode === 'true'");
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

    private void ensureImeVisible(String stage) throws Exception {
        boolean composerFocused = "true".equals(evalRaw(
                "document.activeElement === document.querySelector('[data-testid=prompt-draft]')"));
        boolean composerMode = "true".equals(evalRaw(
                "document.querySelector('.app-shell')?.dataset.keyboardComposerMode === 'true'"));
        boolean imeVisible = isImeVisible();
        boolean mustPhysicallyTapAfterAttach = "uncertain-session-after-attach".equals(stage);
        if (!imeVisible || !composerFocused || !composerMode || mustPhysicallyTapAfterAttach) {
            if (!composerMode && composerFocused) evalString("document.activeElement?.blur(); 'blurred'");
            installFocusTapEventRecorder();
            for (int attemptIndex = 0; attemptIndex < composerFocusMaxAttempts; attemptIndex += 1) {
                awaitWebViewVisualState();
                boolean injectMiss = forceFirstPostAttachTapMiss
                        && mustPhysicallyTapAfterAttach && attemptIndex == 0;
                String targetSelector = injectMiss ? ".terminal-viewport" : "[data-testid=prompt-draft]";
                clearFocusTapEvents(targetSelector);
                JSONObject before = readFocusDomState();
                boolean imeBefore = isImeVisible();
                long attemptStarted = SystemClock.uptimeMillis();
                tapDomCenter(targetSelector);
                JSONObject tap = lastPhysicalTapEvidence == null
                        ? new JSONObject() : new JSONObject(lastPhysicalTapEvidence.toString());
                boolean physicalTargetObserved = awaitTrustedPointerDown(targetSelector, 900);
                boolean focused = awaitPromptDraftFocus(2_500);
                boolean imeAfter = isImeVisible();
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
                        .put("nativeImeVisibleAfter", imeAfter)
                        .put("after", after)
                        .put("elapsedMs", SystemClock.uptimeMillis() - attemptStarted);
                focusTapAttempts.put(record);
                Log.i("PS2891Focus", "ATTEMPT|" + artifactRunId + "|" + record);
                if (physicalTargetObserved && focused) {
                    try {
                        awaitImeVisible(true);
                        awaitJsTrue("document.querySelector('.app-shell')?.dataset.keyboardVisible === 'true'"
                                + " && document.querySelector('.app-shell')?.dataset.keyboardComposerMode === 'true'", 5_000);
                    } catch (Exception error) {
                        captureFocusFailure(stage, "physical draft tap focused the textarea but IME/composer mode did not settle: "
                                + error.getMessage());
                        throw error;
                    }
                    if (isImeVisible() && "true".equals(evalRaw(
                            "document.activeElement === document.querySelector('[data-testid=prompt-draft]')"))) {
                        record.put("nativeImeVisibleAfterImeWait", true)
                                .put("afterImeWait", readFocusDomState());
                        Log.i("PS2891Focus", "SETTLED|" + artifactRunId + "|" + record);
                        break;
                    }
                }
            }
        }
        boolean ready = isImeVisible()
                && "true".equals(evalRaw("document.activeElement === document.querySelector('[data-testid=prompt-draft]')"))
                && "true".equals(evalRaw("document.querySelector('.app-shell')?.dataset.keyboardVisible === 'true'"))
                && "true".equals(evalRaw("document.querySelector('.app-shell')?.dataset.keyboardComposerMode === 'true'"));
        if (!ready) {
            String state = readFocusDomState().toString();
            captureFocusFailure(stage, "composer tap attempts did not leave the real draft focused with the IME open; state=" + state);
            throw new AssertionError("Composer did not reach a focused, keyboard-open state after "
                    + composerFocusMaxAttempts + " physical tap attempt(s); same-run focus screenshot and diagnostics were captured");
        }
        if (mustPhysicallyTapAfterAttach && !hasSuccessfulPhysicalDraftTap(stage)) {
            captureFocusFailure(stage, "post-attach composer state became ready without an observed trusted physical draft tap");
            throw new AssertionError("Post-attach composer focus was not proven by a physical draft tap");
        }
    }

    private long tapComposerAction(String selector) throws Exception {
        return tapComposerAction(selector, "composer-action:" + selector);
    }

    private long tapComposerAction(String selector, String focusStage) throws Exception {
        ensureImeVisible(focusStage);
        assertTrue("Android IME must be visible immediately before tapping " + selector, isImeVisible());
        awaitStableComposerActionTarget(selector);
        beginComposerActionTapObservation(selector);
        long touchUpUptimeMs = tapDomCenter(selector);
        JSONObject physicalTap = awaitComposerActionPhysicalClick(selector, touchUpUptimeMs, 1_200);
        composerActionTapEvidence.put(physicalTap);
        Log.i("PS2920Tap", "ACTION|" + artifactRunId + "|" + physicalTap);
        assertTrue("Android touchscreen tap must deliver trusted down, up, and click events to " + selector
                        + ": " + physicalTap,
                physicalTap.optBoolean("trustedPointerDownOnTarget")
                        && physicalTap.optBoolean("trustedPointerUpOnTarget")
                        && physicalTap.optBoolean("trustedClickOnTarget"));
        return touchUpUptimeMs;
    }

    private void awaitStableComposerActionTarget(String selector) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 5_000;
        JSONObject previous = null;
        JSONObject last = null;
        int stableSamples = 0;
        while (SystemClock.uptimeMillis() < deadline) {
            last = readComposerActionTapLayout(selector);
            boolean valid = !last.optBoolean("missing")
                    && !last.optBoolean("disabled")
                    && last.optBoolean("centerHitMatchesTarget")
                    && last.optDouble("top", -1) >= 0
                    && last.optDouble("bottom", Double.MAX_VALUE) <= last.optDouble("innerHeight") + 0.5
                    && last.optDouble("left", -1) >= 0
                    && last.optDouble("right", Double.MAX_VALUE) <= last.optDouble("innerWidth") + 0.5;
            if (valid && previous != null && sameComposerActionTapLayout(previous, last)) {
                stableSamples += 1;
            } else {
                stableSamples = valid ? 1 : 0;
            }
            if (stableSamples >= 3) {
                Log.i("PS2920Tap", "LAYOUT_STABLE|" + artifactRunId + "|" + selector + "|" + last);
                return;
            }
            previous = valid ? last : null;
            Thread.sleep(75);
        }
        throw new AssertionError("Composer action target did not reach three stable visible layout samples before its physical tap: "
                + selector + " (last=" + last + ")");
    }

    private JSONObject readComposerActionTapLayout(String selector) throws Exception {
        JSONObject layout = evalJson("(() => {const element=document.querySelector(" + JSONObject.quote(selector)
                + ");if(!element)return JSON.stringify({missing:true});const rect=element.getBoundingClientRect();"
                + "const height=window.visualViewport?.height??innerHeight;"
                + "const x=rect.left+rect.width/2,y=rect.top+rect.height/2,hit=document.elementFromPoint(x,y);"
                + "return JSON.stringify({selector:" + JSONObject.quote(selector)
                + ",innerWidth,innerHeight,visualViewportHeight:height,"
                + "visualViewportOffsetTop:window.visualViewport?.offsetTop??0,top:rect.top,bottom:rect.bottom,"
                + "left:rect.left,right:rect.right,width:rect.width,height:rect.height,"
                + "centerHitMatchesTarget:!!hit?.closest?.(" + JSONObject.quote(selector) + "),disabled:!!element.disabled});})()");
        AtomicReference<JSONObject> nativeMapping = new AtomicReference<>();
        scenario.onActivity(activity -> {
            WebView webView = findWebView(activity.getWindow().getDecorView());
            assertNotNull("packaged Capacitor activity must contain a WebView", webView);
            int[] location = new int[2];
            webView.getLocationOnScreen(location);
            WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
            try {
                nativeMapping.set(new JSONObject()
                        .put("webViewScreenX", location[0])
                        .put("webViewScreenY", location[1])
                        .put("webViewWidthPx", webView.getWidth())
                        .put("webViewHeightPx", webView.getHeight())
                        .put("imeVisible", insets != null && insets.isVisible(WindowInsets.Type.ime())));
            } catch (JSONException error) {
                throw new RuntimeException(error);
            }
        });
        layout.put("nativeMapping", nativeMapping.get());
        return layout;
    }

    private boolean sameComposerActionTapLayout(JSONObject left, JSONObject right) throws JSONException {
        for (String key : new String[]{"innerWidth", "innerHeight", "visualViewportHeight", "visualViewportOffsetTop",
                "top", "bottom", "left", "right", "width", "height"}) {
            if (Math.abs(left.getDouble(key) - right.getDouble(key)) > 0.5) return false;
        }
        JSONObject leftNative = left.getJSONObject("nativeMapping");
        JSONObject rightNative = right.getJSONObject("nativeMapping");
        for (String key : new String[]{"webViewScreenX", "webViewScreenY", "webViewWidthPx", "webViewHeightPx"}) {
            if (leftNative.getInt(key) != rightNative.getInt(key)) return false;
        }
        return left.getBoolean("centerHitMatchesTarget") == right.getBoolean("centerHitMatchesTarget")
                && left.getBoolean("disabled") == right.getBoolean("disabled")
                && leftNative.getBoolean("imeVisible") == rightNative.getBoolean("imeVisible");
    }

    private void beginComposerActionTapObservation(String selector) throws Exception {
        evalString("(() => {let state=window.__ps2920ComposerActionTap;"
                + "if(!state){state={selector:'',events:[]};window.__ps2920ComposerActionTap=state;"
                + "const label=node=>node?{tag:node.tagName||'',id:node.id||'',testid:node.getAttribute?.('data-testid')||'',"
                + "className:typeof node.className==='string'?node.className:''}:null;"
                + "for(const type of ['pointerdown','pointerup','click'])document.addEventListener(type,event=>{"
                + "const current=window.__ps2920ComposerActionTap;if(!current?.selector)return;const target=event.target;"
                + "const matches=!!target?.closest?.(current.selector);current.events.push({type,isTrusted:event.isTrusted,"
                + "targetMatchesRequested:matches,target:label(target),clientX:event.clientX??null,clientY:event.clientY??null,"
                + "timeStamp:event.timeStamp});},true);}state.selector=" + JSONObject.quote(selector)
                + ";state.events=[];state.startedAt=performance.now();return 'armed';})()");
    }

    private JSONObject awaitComposerActionPhysicalClick(String selector, long touchUpUptimeMs, long timeoutMillis)
            throws Exception {
        long deadline = SystemClock.uptimeMillis() + timeoutMillis;
        JSONObject observation = readComposerActionTapObservation(selector, touchUpUptimeMs);
        while (SystemClock.uptimeMillis() < deadline) {
            if (observation.optBoolean("trustedPointerDownOnTarget")
                    && observation.optBoolean("trustedPointerUpOnTarget")
                    && observation.optBoolean("trustedClickOnTarget")) return observation;
            Thread.sleep(30);
            observation = readComposerActionTapObservation(selector, touchUpUptimeMs);
        }
        return observation;
    }

    private JSONObject readComposerActionTapObservation(String selector, long touchUpUptimeMs) throws Exception {
        JSONObject observation = evalJson("(() => {const state=window.__ps2920ComposerActionTap;"
                + "const events=state?.events||[];const event=(type)=>events.some(item=>item.type===type"
                + "&&item.isTrusted===true&&item.targetMatchesRequested===true);"
                + "return JSON.stringify({selector:state?.selector||'',events,"
                + "trustedPointerDownOnTarget:event('pointerdown'),trustedPointerUpOnTarget:event('pointerup'),"
                + "trustedClickOnTarget:event('click')});})()");
        observation.put("touchUpUptimeMs", touchUpUptimeMs)
                .put("physicalTap", lastPhysicalTapEvidence == null
                        ? JSONObject.NULL : new JSONObject(lastPhysicalTapEvidence.toString()));
        return observation;
    }

    private void installFocusTapEventRecorder() throws Exception {
        evalString("(() => {if(window.__ps2891FocusTapRecorderInstalled)return 'installed';"
                + "window.__ps2891FocusTapEvents=[];window.__ps2891ExpectedFocusTapSelector='';"
                + "const label=node=>node?{tag:node.tagName||'',id:node.id||'',testid:node.getAttribute?.('data-testid')||'',"
                + "className:typeof node.className==='string'?node.className:''}:null;"
                + "for(const type of ['pointerdown','pointerup','focusin','focusout'])document.addEventListener(type,event=>{"
                + "const target=event.target;const selector=window.__ps2891ExpectedFocusTapSelector;"
                + "if(!selector)return;const matches=selector==='[data-testid=prompt-draft]'?"
                + "target===document.querySelector('[data-testid=prompt-draft]'):!!target?.closest?.(selector);"
                + "window.__ps2891FocusTapEvents.push({type,isTrusted:event.isTrusted,target:label(target),"
                + "targetMatchesRequested:matches,clientX:event.clientX??null,clientY:event.clientY??null,"
                + "pointerType:event.pointerType??'',timeStamp:event.timeStamp});},true);"
                + "window.__ps2891FocusTapRecorderInstalled=true;return 'installed';})()");
    }

    private void clearFocusTapEvents(String targetSelector) throws Exception {
        evalString("(() => {window.__ps2891FocusTapEvents.length=0;"
                + "window.__ps2891ExpectedFocusTapSelector=" + JSONObject.quote(targetSelector)
                + ";return 'cleared';})()");
    }

    private boolean awaitTrustedPointerDown(String targetSelector, long timeoutMillis) throws Exception {
        long deadline = SystemClock.uptimeMillis() + timeoutMillis;
        String expression = "window.__ps2891FocusTapEvents?.some(event=>event.type==='pointerdown'"
                + "&&event.isTrusted===true&&event.targetMatchesRequested===true)===true";
        if (targetSelector.isEmpty()) return false;
        while (SystemClock.uptimeMillis() < deadline) {
            if ("true".equals(evalRaw(expression))) return true;
            Thread.sleep(30);
        }
        return "true".equals(evalRaw(expression));
    }

    private boolean awaitPromptDraftFocus(long timeoutMillis) throws Exception {
        long deadline = SystemClock.uptimeMillis() + timeoutMillis;
        String expression = "document.activeElement === document.querySelector('[data-testid=prompt-draft]')";
        while (SystemClock.uptimeMillis() < deadline) {
            if ("true".equals(evalRaw(expression))) return true;
            Thread.sleep(60);
        }
        return "true".equals(evalRaw(expression));
    }

    private JSONObject readFocusDomState() throws Exception {
        return evalJson("(() => {const draft=document.querySelector('[data-testid=prompt-draft]');"
                + "const active=document.activeElement;const rect=draft?.getBoundingClientRect();"
                + "const x=rect?rect.left+rect.width/2:0,y=rect?rect.top+rect.height/2:0;"
                + "const hit=document.elementFromPoint(x,y);const label=node=>node?{tag:node.tagName||'',id:node.id||'',"
                + "testid:node.getAttribute?.('data-testid')||'',className:typeof node.className==='string'?node.className:''}:null;"
                + "const bounds=node=>{const r=node?.getBoundingClientRect();return r?{top:r.top,bottom:r.bottom,left:r.left,right:r.right,width:r.width,height:r.height}:null};"
                + "const shell=document.querySelector('.app-shell');"
                + "return JSON.stringify({uptimeHintMs:performance.now(),route:shell?.dataset.route||'',"
                + "homeSurface:shell?.dataset.homeSurface||'',sshPhase:shell?.dataset.sshPhase||'',"
                + "keyboardVisible:shell?.dataset.keyboardVisible==='true',keyboardComposerMode:shell?.dataset.keyboardComposerMode==='true',"
                + "activeElement:label(active),draftPresent:!!draft,draftConnected:!!draft?.isConnected,draftDisabled:!!draft?.disabled,"
                + "draftFocused:active===draft,draftBounds:bounds(draft),draftCenterHit:label(hit),draftCenterHitIsDraft:hit===draft,"
                + "visualViewport:{width:window.visualViewport?.width??innerWidth,height:window.visualViewport?.height??innerHeight,"
                + "offsetLeft:window.visualViewport?.offsetLeft??0,offsetTop:window.visualViewport?.offsetTop??0,scale:window.visualViewport?.scale??1},"
                + "innerWidth,innerHeight,screenScroll:document.querySelector('.screen-content')?.scrollTop??null,"
                + "documentScroll:document.scrollingElement?.scrollTop??null,"
                + "pointerEvents:(window.__ps2891FocusTapEvents||[]).slice(-20)});})()");
    }

    private boolean hasSuccessfulPhysicalDraftTap(String stage) throws JSONException {
        for (int i = 0; i < focusTapAttempts.length(); i += 1) {
            JSONObject attempt = focusTapAttempts.getJSONObject(i);
            if (stage.equals(attempt.optString("stage"))
                    && "[data-testid=prompt-draft]".equals(attempt.optString("requestedSelector"))
                    && attempt.optBoolean("trustedPointerDownOnRequestedTarget")
                    && attempt.optBoolean("draftFocusedAfter")
                    && attempt.optBoolean("nativeImeVisibleAfterImeWait")) return true;
        }
        return false;
    }

    private void emitFocusTraceIfNeeded() throws Exception {
        if (focusTraceEmitted || (focusTapAttempts.length() == 0 && composerActionTapEvidence.length() == 0)
                || artifactRunId == null) return;
        JSONObject trace = new JSONObject()
                .put("runId", artifactRunId)
                .put("androidApi", Build.VERSION.SDK_INT)
                .put("maxAttempts", composerFocusMaxAttempts)
                .put("forcedFirstPostAttachMiss", forceFirstPostAttachTapMiss)
                .put("attempts", focusTapAttempts)
                .put("composerActionTaps", composerActionTapEvidence);
        byte[] bytes = trace.toString(2).getBytes(StandardCharsets.UTF_8);
        scenario.onActivity(activity -> {
            try (FileOutputStream output = new FileOutputStream(
                    new File(activity.getFilesDir(), "composer-focus-trace.json"))) {
                output.write(bytes);
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
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
            JSONObject report = new JSONObject()
                    .put("runId", artifactRunId)
                    .put("stage", stage)
                    .put("reason", reason)
                    .put("capturedAtAndroidUptimeMs", SystemClock.uptimeMillis())
                    .put("androidApi", Build.VERSION.SDK_INT)
                    .put("imeAndWindowState", readNativeFocusState())
                    .put("webViewState", readFocusDomState())
                    .put("lastPhysicalTap", lastPhysicalTapEvidence)
                    .put("composerActionTaps", composerActionTapEvidence)
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
            byte[] logcat = executeShellCommand(
                    "logcat -d -v threadtime -t 1200 -s ImeTracker InputMethodManager InputMethodManagerService ViewRootImpl PS2891Focus");
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
            try {
                Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
                if (screenshot == null) return;
                ByteArrayOutputStream encoded = new ByteArrayOutputStream();
                boolean compressed = screenshot.compress(Bitmap.CompressFormat.PNG, 100, encoded);
                screenshot.recycle();
                byte[] png = encoded.toByteArray();
                File destination = new File(activity.getFilesDir(), "composer-focus-failure.png");
                if (compressed && png.length >= 1024) {
                    try (FileOutputStream output = new FileOutputStream(destination)) {
                        output.write(png);
                    }
                    if (destination.length() >= 1024) bytes.set(png);
                }
            } catch (Exception error) {
                throw new RuntimeException(error);
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
                state.set(new JSONObject()
                        .put("windowHasFocus", decor.hasWindowFocus())
                        .put("decorHasFocus", decor.hasFocus())
                        .put("decorShown", decor.isShown())
                        .put("imeVisible", insets != null && insets.isVisible(WindowInsets.Type.ime()))
                        .put("imeBottomPx", insets == null ? 0 : insets.getInsets(WindowInsets.Type.ime()).bottom)
                        .put("focusedViewClass", focusedView == null ? "" : focusedView.getClass().getName())
                        .put("focusedViewId", focusedView == null ? View.NO_ID : focusedView.getId())
                        .put("focusedViewHasFocus", focusedView != null && focusedView.hasFocus())
                        .put("webViewPresent", webView != null)
                        .put("webViewHasFocus", webView != null && webView.hasFocus())
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
            int read;
            int remaining = 180_000;
            while (remaining > 0 && (read = input.read(buffer, 0, Math.min(buffer.length, remaining))) >= 0) {
                output.write(buffer, 0, read);
                remaining -= read;
            }
            return output.toByteArray();
        } finally {
            descriptor.close();
        }
    }

    private long waitForTerminalMarkerOrCaptureWindow(String marker, long sendTouchUpUptimeMs) throws Exception {
        String quotedMarker = JSONObject.quote(marker);
        String expectedBytes = JSONObject.quote("636166c3a920f09fa7aa");
        try {
            awaitJsTrue("(() => {const viewport=document.querySelector('.terminal-viewport');"
                + "const screen=viewport?.querySelector('.xterm-screen');"
                + "const rows=Array.from(viewport?.querySelectorAll('.xterm-rows > div') ?? []);"
                + "const byteRow=rows.find(node=>(node.textContent||'').includes(" + expectedBytes + "));"
                + "const markerRow=rows.find(node=>(node.textContent||'').includes(" + quotedMarker + "));"
                + "const byteBounds=byteRow?.getBoundingClientRect(),markerBounds=markerRow?.getBoundingClientRect();"
                + "const view=viewport?.getBoundingClientRect(),screenBounds=screen?.getBoundingClientRect();"
                + "const visible=(bounds,outer,inner)=>!!bounds&&!!outer&&!!inner&&bounds.top>=outer.top&&bounds.bottom<=outer.bottom"
                + "&&bounds.left>=outer.left&&bounds.right<=outer.right&&bounds.top>=inner.top&&bounds.bottom<=inner.bottom"
                + "&&bounds.left>=inner.left&&bounds.right<=inner.right;"
                + "return visible(byteBounds,view,screenBounds)&&visible(markerBounds,view,screenBounds)"
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
                + ",sendToVisibleOutputTiming:'Android uptime from Send touch-up to the first 60ms WebView poll with both executed rows rendered inside the visible xterm screen',"
                + "captureEnabled:window.__ps2857CaptureTerminalEvidence===true,"
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

    private void requestImeHide() {
        scenario.onActivity(activity -> {
            View decor = activity.getWindow().getDecorView();
            android.view.WindowInsetsController controller = decor.getWindowInsetsController();
            if (controller != null) controller.hide(WindowInsets.Type.ime());
            InputMethodManager inputMethodManager = activity.getSystemService(InputMethodManager.class);
            if (inputMethodManager != null) {
                inputMethodManager.hideSoftInputFromWindow(decor.getWindowToken(), 0);
            }
        });
    }

    private void hideImeUntilStableWithoutEditableFocus() throws Exception {
        String editableFocus = "document.activeElement instanceof HTMLInputElement"
                + " || document.activeElement instanceof HTMLTextAreaElement"
                + " || document.activeElement instanceof HTMLSelectElement"
                + " || (document.activeElement instanceof HTMLElement && document.activeElement.isContentEditable)";
        awaitStableFocusTarget();
        long deadline = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS;
        int stableHiddenSamples = 0;
        boolean lastImeVisible = true;
        while (SystemClock.uptimeMillis() < deadline) {
            lastImeVisible = isImeVisible();
            if (lastImeVisible) {
                evalString("(() => {const active=document.activeElement;"
                        + "if(active instanceof HTMLElement) active.blur();"
                        + "return 'active editor blurred before native IME hide';})()");
                requestImeHide();
                stableHiddenSamples = 0;
            } else {
                stableHiddenSamples += 1;
                if (stableHiddenSamples >= 4) {
                    if (!("true".equals(evalString(editableFocus)))) return;
                    evalString("(() => {const active=document.activeElement;"
                            + "if(active instanceof HTMLElement) active.blur();"
                            + "return 'late editor focus cleared after native IME hide';})()");
                    requestImeHide();
                    stableHiddenSamples = 0;
                }
            }
            Thread.sleep(100);
        }
        JSONObject appState = evalJson("(() => {const shell=document.querySelector('.app-shell');"
                + "return JSON.stringify({keyboardVisible:shell?.dataset.keyboardVisible||'',"
                + "sshPhase:shell?.dataset.sshPhase||'',activeElement:{tag:document.activeElement?.tagName||'',"
                + "id:document.activeElement?.id||'',className:document.activeElement?.className||''}});})()");
        throw new AssertionError("Android IME did not remain hidden without an editable focus"
                + " (nativeImeVisible=" + lastImeVisible + "; app=" + appState + ")");
    }

    private void awaitStableFocusTarget() throws Exception {
        long deadline = SystemClock.uptimeMillis() + 5_000;
        String current = evalString("(() => {const node=document.activeElement;return JSON.stringify({"
                + "tag:node?.tagName||'',id:node?.id||'',className:node?.className||''});})()");
        long stableSince = SystemClock.uptimeMillis();
        while (SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(100);
            String latest = evalString("(() => {const node=document.activeElement;return JSON.stringify({"
                    + "tag:node?.tagName||'',id:node?.id||'',className:node?.className||''});})()");
            if (!current.equals(latest)) {
                current = latest;
                stableSince = SystemClock.uptimeMillis();
            } else if (SystemClock.uptimeMillis() - stableSince >= 500) {
                return;
            }
        }
        throw new AssertionError("focused editor did not settle after session attach: " + current);
    }

    private void awaitAppWindowActive(String stage) throws Exception {
        String expectedPackage = InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName();
        long deadline = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS;
        String observedPackage = "";
        boolean windowFocused = false;
        while (SystemClock.uptimeMillis() < deadline) {
            AtomicReference<Boolean> focused = new AtomicReference<>(false);
            scenario.onActivity(activity -> focused.set(activity.hasWindowFocus()));
            windowFocused = focused.get();
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            AccessibilityNodeInfo root = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .getRootInActiveWindow();
            observedPackage = root == null || root.getPackageName() == null
                    ? "" : root.getPackageName().toString();
            if (windowFocused && expectedPackage.equals(observedPackage)) return;
            Thread.sleep(100);
        }
        assertTrue(stage + " must leave the PocketShell Activity with window focus; package=" + observedPackage,
                windowFocused);
        assertEquals(stage + " must leave PocketShell as the active accessibility window",
                expectedPackage, observedPackage);
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
                + "const height=window.visualViewport?.height ?? innerHeight;"
                + "const x=rect.left+rect.width/2,y=rect.top+rect.height/2,hit=document.elementFromPoint(x,y);"
                + "const label=node=>node?{tag:node.tagName||'',id:node.id||'',testid:node.getAttribute?.('data-testid')||'',"
                + "className:typeof node.className==='string'?node.className:''}:null;"
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
                nativeMapping.set(new JSONObject()
                        .put("webViewScreenX", location[0])
                        .put("webViewScreenY", location[1])
                        .put("webViewWidthPx", webView.getWidth())
                        .put("webViewHeightPx", webView.getHeight())
                        .put("cssWidth", cssWidth)
                        .put("cssHeight", cssHeight)
                        .put("scaleX", scaleX)
                        .put("scaleY", scaleY)
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
        lastPhysicalTapEvidence = new JSONObject(point.toString())
                .put("nativeMapping", nativeMapping.get())
                .put("screenX", screen[0])
                .put("screenY", screen[1])
                .put("touchDownUptimeMs", downTime)
                .put("touchUpUptimeMs", upTime)
                .put("downInjected", downInjected)
                .put("upInjected", upInjected);
        return SystemClock.uptimeMillis();
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
