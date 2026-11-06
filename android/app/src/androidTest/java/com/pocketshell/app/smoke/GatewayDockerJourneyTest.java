package com.pocketshell.app.smoke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.pocketshell.app.GoogleSyncEnvironment;
import com.pocketshell.app.GoogleSyncSession;
import com.pocketshell.app.MainActivity;
import com.pocketshell.app.SharedShellLaunch;
import com.pocketshell.app.SyncAuthException;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.FixMethodOrder;
import org.junit.runners.MethodSorters;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Packaged trusted-vault journeys through the real TLS gateway, enrolled agent,
 * private sshd, CLI, aplexer and PTY. Only Google and the native sync API edge
 * are faked; account slots are authenticated core-generated encrypted records.
 * The visible Google/unlock/server controls drive every normal connection.
 * The host-side controller records independent pin, login, session and file
 * evidence. NAME_ASCENDING deliberately runs the fresh-HOME creation case first
 * against a new per-run Docker host; both before/after inventories are checked.
 */
@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public final class GatewayDockerJourneyTest {
    private static final String TAG = "PocketshellGatewayLane";
    private static final long JS_TIMEOUT_SECONDS = 15;
    private static final long WAIT_TIMEOUT_MILLIS = 45_000;
    private static final String PASSPHRASE = "gateway lane passphrase";
    private static final String AUTH_PREFS = "pocketshell-sync-auth";
    private static final String HOSTS_KEY = "pocketshell.android.hosts.v1";
    private static final String SHARED_SETTINGS_KEY = "pocketshell.settings.v1";
    private static final String DEVICE_HOST = "gw-dev";
    private static final String MISSING_HOST = "gw-missing";
    private static final String VISIBLE_TERMINAL =
            "([...document.querySelectorAll('.xterm-rows')].find((n)=>n.offsetParent!==null)?.innerText ?? '')";
    private static final String VISIBLE_TEXTAREA =
            "[...document.querySelectorAll('.xterm')].find((n)=>n.offsetParent!==null)?.querySelector('.xterm-helper-textarea')";
    private static final String PICKER_ERROR = "(document.querySelector('.picker p.error')?.textContent ?? '').trim()";

    ActivityScenario<MainActivity> scenario;
    JSONObject hello;
    private FakeGoogle google;
    FakeSyncBackend backend;
    String run;
    private String phoneFingerprint;
    private final JSONArray previousDials = new JSONArray();

    @Before
    public void installFakeEdges() throws Exception {
        assertTrue("the gateway lane runs on API 35+", Build.VERSION.SDK_INT >= 35);
        run = requiredArgument("gatewayRunId");
        assertTrue("run id must be path safe", run.matches("[A-Za-z0-9][A-Za-z0-9_-]{2,48}"));
        hello = controller(new JSONObject().put("op", "hello"));
        google = new FakeGoogle(hello.getString("sub"), hello.getString("email"));
        backend = new FakeSyncBackend();
        backend.seed(encrypt(accountHosts().toString()));
        GoogleSyncEnvironment.installForTesting(activity -> google, backend);
        for (String prefs : new String[]{AUTH_PREFS, "pocketshell-gateway-pairings", "pocketshell-trusted-host-revisions", "pocketshell_vault_device_v1"})
            targetContext().deleteSharedPreferences(prefs);
    }

    @After
    public void restore() throws Exception {
        try {
            // A failed refusal step must not leave the next test's host offline.
            if (!controller(new JSONObject().put("op", "presence")).optBoolean("online")) {
                controller(new JSONObject().put("op", "agent-start").put("phase", "restore"));
            }
        } finally {
            if (scenario != null) {
                try {
                    evalRaw("['" + HOSTS_KEY + "','" + SHARED_SETTINGS_KEY + "'].forEach((key) => localStorage.removeItem(key)); 'reset'");
                } catch (Throwable ignored) {
                    // The activity may already be gone after a failure.
                }
                scenario.close();
            }
            GoogleSyncEnvironment.resetForTesting();
            targetContext().deleteSharedPreferences(AUTH_PREFS);
        }
    }

    /**
     * Every non-happy route is refused before any SSH login: a pin that is
     * not the host's key (sshd sees the bridged connection and ZERO userauth
     * attempts), a routing token the gateway cannot verify (4401), a device
     * the gateway does not know (4404), and an enrolled device whose agent is
     * offline (4503) — reported as offline, never as a sign-in failure. No
     * dial is ever made as plain SSH.
     */
    @Test
    public void refusesPinMismatchUnverifiedTokenUnknownAndOfflineDevicesBeforeAnyLogin() throws Exception {
        selectVariant("active");
        launchSignedInAndUnlocked();
        authorizeVault("authorize");

        // 1. 4401: the gateway cannot verify the routing token. First, so this
        // activity's native token cache holds no valid token to reuse.
        backend.signWithWrongKey = true;
        int mark = controller(new JSONObject().put("op", "mark")).getInt("mark");
        String error = dialExpectingError(DEVICE_HOST);
        assertEquals("4401 tells the user to sign in again", "Your sign-in expired — sign in again and retry.", error);
        JSONObject unauthorized = sshdSince(mark, "unauthorized-4401");
        assertEquals("a refused token never reaches sshd: " + unauthorized, 0, unauthorized.getInt("connections"));
        backend.signWithWrongKey = false;

        // 2. Valid authenticated descriptor carrying a pin that is not the host's.
        restartWithVariant("wrongPin");
        mark = controller(new JSONObject().put("op", "mark")).getInt("mark");
        error = dialExpectingError(DEVICE_HOST);
        assertTrue("a pin mismatch names the authenticated trust refusal: " + error,
                error.startsWith("The host's SSH key does not match the key this phone paired for that device."));
        assertFalse("a gateway dial never offers a trust prompt", exists("[data-testid=host-key-trust-gate]"));
        JSONObject mismatch = sshdSince(mark, "pin-mismatch");
        assertTrue("the mismatched dial reached the host's sshd through the gateway: " + mismatch,
                mismatch.getInt("connections") >= 1);
        assertEquals("a pin mismatch makes ZERO userauth attempts: " + mismatch, 0, mismatch.getJSONArray("userauth").length());
        screenshot("pin-mismatch");
        assertInstalledTrustedRevision(2);

        // The next isolated descriptor contains the unknown enrolled route.

        // 3. 4404: a device the gateway has never enrolled.
        restartWithVariant("missing");
        mark = controller(new JSONObject().put("op", "mark")).getInt("mark");
        error = dialExpectingError(MISSING_HOST);
        assertEquals("an unknown device is not registered", "That host is not registered on the gateway.", error);
        JSONObject notFound = sshdSince(mark, "not-found-4404");
        assertEquals(0, notFound.getInt("connections"));

        restartWithVariant("restoredPin");

        // 4. 4503: the enrolled device's agent is offline (the gateway's own presence says so).
        assertFalse(controller(new JSONObject().put("op", "agent-stop").put("phase", "offline")).optBoolean("online", true));
        mark = controller(new JSONObject().put("op", "mark")).getInt("mark");
        error = dialExpectingError(DEVICE_HOST);
        assertEquals("an offline device is reported as offline, not as a login failure",
                "The host is not connected to the gateway right now — check that its agent is running.", error);
        assertFalse("offline is not a sign-in failure: " + error, error.toLowerCase(java.util.Locale.ROOT).contains("sign"));
        screenshot("device-offline");
        assertInstalledTrustedRevision(3);
        JSONObject offline = sshdSince(mark, "offline-4503");
        assertEquals(0, offline.getInt("connections"));
        assertTrue(controller(new JSONObject().put("op", "agent-start").put("phase", "online-again")).optBoolean("online"));

        JSONArray dials = nativeDials();
        assertTrue("every refused route was a native gateway dial: " + dials, dials.length() >= 4);
        assertEveryDialWentThroughTheGateway(dials);
        assertNoRoutingTokenEscaped();
        Log.i(TAG, "GATEWAY_REFUSALS " + new JSONObject().put("dials", dials.length())
                .put("mints", backend.mints.size()).put("mismatch", mismatch).put("offline", offline));
    }

    /**
     * The happy path through the real gateway: the host's sessions are
     * listed, one is attached, typed into (the host's own file proves it),
     * resized (the host's `stty size` follows the pane), and after the tunnel
     * drops the controller re-dials through the gateway with a fresh token
     * and the same session keeps working.
     */
    @Test
    public void dialsTheEnrolledHostThroughTheGatewayListsAttachesTypesResizesAndReconnects() throws Exception {
        launchSignedInAndUnlocked();
        authorizeVault("authorize");

        int mark = controller(new JSONObject().put("op", "mark")).getInt("mark");
        tapHost(DEVICE_HOST);
        awaitJsTrue("!!document.querySelector('button[title=\"Back to hosts\"]') || " + PICKER_ERROR + " !== ''");
        assertEquals("the gateway dial connects", "", evalString(PICKER_ERROR));
        assertFalse("no trust prompt for a pinned gateway host", exists("[data-testid=host-key-trust-gate]"));
        JSONObject login = sshdSince(mark, "connect");
        assertTrue("the host accepted the decrypted vault key over the gateway: " + login,
                acceptedFingerprints(login).contains(phoneFingerprint));

        // The host's aplexer sessions, listed through the gateway.
        if (exists("button[title=\"Show session panel\"]")) click("button[title=\"Show session panel\"]");
        String folder = hello.getString("sessionFolder");
        awaitJsTrue("[...document.querySelectorAll('.dir-header')].some((n)=>n.textContent.includes(" + JSONObject.quote(folder) + "))");
        evalString("(() => {const row=[...document.querySelectorAll('.dir-header')].find((n)=>n.textContent.includes("
                + JSONObject.quote(folder) + ")); if(!row) throw new Error('no folder row'); row.click(); return 'ok';})()");
        awaitJsTrue(VISIBLE_TERMINAL + ".includes('$')");

        // Terminal I/O: the shell runs what was typed; the host keeps its own copy.
        String io = "gwlane-io-" + run + ".txt";
        typeCommand("echo GWLANE_$((6*7))_" + run + " | tee ~/" + io);
        awaitJsTrue(VISIBLE_TERMINAL + ".includes('GWLANE_42')");
        assertEquals("GWLANE_42_" + run, hostFile(io, "io").trim());
        screenshot("terminal-io");

        // Resize: shrink the WebView; the pane refits and the host's PTY follows.
        int rowsBefore = visibleRows();
        String sizeBefore = "gwlane-size1-" + run + ".txt";
        typeCommand("stty size > ~/" + sizeBefore);
        int hostRowsBefore = hostRows(sizeBefore, "size-before");
        setWebViewHeightFraction(0.6f);
        awaitJsTrue("(() => {const r=[...document.querySelectorAll('.xterm-rows')].find((n)=>n.offsetParent!==null);"
                + " return !!r && r.children.length > 0 && r.children.length < " + rowsBefore + ";})()");
        // The shrink lands in more than one frame (a proportional frame, then the
        // refit to the final height, #3092): read the pane only once its row
        // count has held for several polls, so it is compared with the host
        // PTY's settled size, never an intermediate frame.
        int rowsAfter = settledVisibleRows();
        String sizeAfter = "gwlane-size2-" + run + ".txt";
        typeCommand("stty size > ~/" + sizeAfter);
        int hostRowsAfter = hostRows(sizeAfter, "size-after");
        if (rowsAfter != hostRowsAfter) {
            // If the pane's intermediate frame held through the initial settle window
            // on a slow runner (#3092), read the settled pane rows now that the host
            // has processed the resize.
            rowsAfter = settledVisibleRows();
        }
        setWebViewHeightFraction(1f);
        assertTrue("the host PTY shrank with the pane: " + hostRowsBefore + " -> " + hostRowsAfter
                + " (pane " + rowsBefore + " -> " + rowsAfter + ")", hostRowsAfter < hostRowsBefore);
        assertEquals("the host PTY has the pane's rows after the resize", rowsAfter, hostRowsAfter);

        // Reconnect: the tunnel drops; the controller (the one recovery owner)
        // re-dials through the gateway and the session keeps working.
        int dialsBefore = nativeDials().length();
        mark = controller(new JSONObject().put("op", "mark")).getInt("mark");
        assertTrue(controller(new JSONObject().put("op", "drop").put("phase", "reconnect")).optBoolean("dropped"));
        long deadline = SystemClock.uptimeMillis() + 90_000;
        while (nativeDials().length() <= dialsBefore && SystemClock.uptimeMillis() < deadline) Thread.sleep(250);
        assertTrue("the dropped tunnel was re-dialled", nativeDials().length() > dialsBefore);
        String after = "gwlane-reconnected-" + run + ".txt";
        long typedDeadline = SystemClock.uptimeMillis() + 90_000;
        String content = "";
        while (SystemClock.uptimeMillis() < typedDeadline) {
            if (exists(VISIBLE_TEXTAREA_SELECTOR) && "connected".equals(connectionPhase())) {
                typeCommand("echo GWLANE_BACK_$((6*7))_" + run + " > ~/" + after);
                SystemClock.sleep(1_500);
                content = hostFile(after, "reconnect-io").trim();
                if (!content.isEmpty()) break;
            }
            Thread.sleep(500);
        }
        assertEquals("typing works again after the gateway reconnect", "GWLANE_BACK_42_" + run, content);
        screenshot("after-reconnect");
        JSONObject relogin = sshdSince(mark, "reconnect");
        assertTrue("the reconnect logged in again with the vault key, through the gateway: " + relogin,
                acceptedFingerprints(relogin).contains(phoneFingerprint));

        JSONArray dials = nativeDials();
        assertEveryDialWentThroughTheGateway(dials);
        assertTrue("each gateway dial minted or reused a native token (" + backend.mints.size() + " mints)",
                backend.mints.size() >= 1);
        assertNoRoutingTokenEscaped();
        Log.i(TAG, "GATEWAY_HAPPY " + new JSONObject().put("dials", dials.length()).put("mints", backend.mints.size())
                .put("rows", new JSONArray().put(hostRowsBefore).put(hostRowsAfter)));
    }

    /** One visible vault unlock decrypts the managed credential and pin; no manual key or pairing form. */
    @Test
    public void dialsTheGatewayHostWithTheAccountVaultKeyAfterOneUnlockAndNoKeyPicker() throws Exception {
        selectVariant("active");
        launchSignedInAndUnlocked();
        String vaultFingerprint = authorizeVault("vault-authorize");
        evalRaw("window.__psSawKeyPicker = false; new MutationObserver(() => {"
                + " if (document.querySelector('[data-testid=account-host-key-gate]')) window.__psSawKeyPicker = true; })"
                + ".observe(document.body, {childList: true, subtree: true}); 'watching'");
        screenshot("vault-unlocked-gateway-hosts");

        int mark = controller(new JSONObject().put("op", "mark")).getInt("mark");
        tapHost(DEVICE_HOST);
        awaitJsTrue("!!document.querySelector('button[title=\"Back to hosts\"]') || " + PICKER_ERROR + " !== ''", 120_000);
        assertEquals("the gateway dial connects with the vault key", "", evalString(PICKER_ERROR));
        assertEquals("no key picker at any point", "false", evalRaw("window.__psSawKeyPicker === true"));
        assertFalse("no trust prompt for a pinned gateway host", exists("[data-testid=host-key-trust-gate]"));
        JSONObject login = sshdSince(mark, "vault-connect");
        assertTrue("the host accepted the VAULT key over the gateway: " + login,
                acceptedFingerprints(login).contains(vaultFingerprint));
        screenshot("vault-gateway-connected");

        JSONArray dials = nativeDials();
        assertEveryDialWentThroughTheGateway(dials);
        for (int i = 0; i < dials.length(); i++) {
            assertTrue("the dial used the vault's in-memory handle: " + dials.getJSONObject(i),
                    dials.getJSONObject(i).getString("handleId").startsWith("ephemeral-"));
        }
        assertEquals("the phone never writes the account", 0, backend.writes);
        assertNoRoutingTokenEscaped();
        String pid = shell("pidof " + targetContext().getPackageName()).trim();
        String logcat = shell("logcat -d -v brief --pid=" + pid);
        assertFalse("the vault passphrase never reaches logcat", logcat.contains("pocketshell-vault-test-passphrase"));
        assertFalse("no key material in logcat", logcat.contains("PRIVATE KEY-----"));
        Log.i(TAG, "GATEWAY_VAULT " + new JSONObject().put("dials", dials.length()).put("vaultFingerprint", vaultFingerprint));
    }

    // ---- the journey's steps -------------------------------------------------

    /** Fresh authenticated vault descriptors drive the real picker, pairing, home-shell create and terminal. */
    @Test
    public void connectsTrustedVaultServerWithoutManualPairingAndCreatesHomeTerminal() throws Exception {
        // This alphabetically first method is the fresh-HOME production case.
        assertEquals("the fixture did not pre-create a HOME shell", 0,
                controller(new JSONObject().put("op", "home-sessions").put("phase", "trusted-before")).getInt("count"));
        JSONObject fixture = new JSONObject(shell("cat " + requiredArgument("vaultFixture")));
        backend.vaultSlots = new JSONObject(fixture.getJSONObject("slots").toString());
        String trustedSlot = null;
        java.util.Iterator<String> slotNames = backend.vaultSlots.keys();
        while (slotNames.hasNext()) { String slot = slotNames.next(); if (slot.startsWith("th-")) trustedSlot = slot; }
        assertNotNull("the runner staged a real encrypted trusted descriptor", trustedSlot);
        JSONObject stagedSlots = backend.vaultSlots;
        JSONObject descriptorSlot = backend.vaultSlots.getJSONObject(trustedSlot);
        String originalDescriptor = descriptorSlot.getString("data");
        JSONObject corrupted = new JSONObject(originalDescriptor);
        String ciphertext = corrupted.getString("ct");
        corrupted.put("ct", (ciphertext.startsWith("A") ? "B" : "A") + ciphertext.substring(1));
        descriptorSlot.put("data", corrupted.toString());
        backend.seed(encrypt(new JSONObject().put("hosts", new JSONArray()).toString()));
        JSONObject authorized = controller(new JSONObject().put("op", "authorize")
                .put("publicKey", fixture.getString("publicKey")).put("phase", "trusted-authorize"));
        selectVariant("beforeRegistration");
        scenario = ActivityScenario.launch(SharedShellLaunch.intent());
        awaitJsTrue("!!document.querySelector('[data-testid=server-google-login]')", 60_000);
        evalRaw("localStorage.setItem('" + HOSTS_KEY + "','[]'); localStorage.removeItem('ps.lastFolder.gw-dev'); localStorage.removeItem('ps.lastFolder.gw-missing'); 'empty'");
        click("[data-testid=server-google-login]");
        awaitJsTrue("!!document.querySelector('[data-testid=vault-unlock-gate]')", 60_000);
        setValue("[data-testid=vault-unlock-passphrase]", "pocketshell-vault-test-passphrase gateway");
        click("[data-testid=vault-unlock-submit]");
        awaitJsTrue("!document.querySelector('[data-testid=vault-unlock-gate]') && !document.querySelector('[data-testid=trusted-server-connect]') && !!document.querySelector('.trusted-servers .btn-ghost')", 120_000);
        // A second client registers after this phone unlocked an older index.
        // Refresh must fetch the authenticated header/index before discovering it.
        backend.vaultSlots = stagedSlots;
        int beforeRegistration = backend.trustedReads;
        click(".trusted-servers .btn-ghost");
        awaitTrustedReadAfter(beforeRegistration);
        awaitJsTrue("!!document.querySelector('.trusted-servers [role=alert]') && !document.querySelector('[data-testid=trusted-server-connect]')", 120_000);
        screenshot("trusted-vault-tampered");
        JSONObject foreign = new JSONObject(originalDescriptor).put("accountId", "google:foreign-subject");
        descriptorSlot.put("data", foreign.toString());
        int beforeForeign = backend.trustedReads;
        click(".trusted-servers .btn-ghost");
        awaitTrustedReadAfter(beforeForeign);
        awaitJsTrue("!!document.querySelector('.trusted-servers [role=alert]') && !document.querySelector('[data-testid=trusted-server-connect]')", 120_000);
        screenshot("trusted-vault-foreign");
        descriptorSlot.put("data", originalDescriptor);
        int beforeValid = backend.trustedReads;
        click(".trusted-servers .btn-ghost");
        awaitTrustedReadAfter(beforeValid);
        awaitJsTrue("!!document.querySelector('[data-testid=trusted-server-connect]:not([disabled])')", 120_000);
        assertEquals("cloud descriptor discovery saves no phone host row", "[]", evalString("localStorage.getItem('" + HOSTS_KEY + "')"));
        installDialRecorder();
        installTrustedPtyDiagnostics();
        screenshot("trusted-vault-servers");
        int mark = controller(new JSONObject().put("op", "mark")).getInt("mark");
        click("[data-testid=trusted-server-connect]");
        awaitJsTrue(VISIBLE_TERMINAL + ".includes('$')", 120_000);
        assertEquals("normal server tap created exactly one HOME shell", 1,
                controller(new JSONObject().put("op", "home-sessions").put("phase", "trusted-after")).getInt("count"));
        assertFalse("no manual key chooser", exists("[data-testid=account-host-key-gate]"));
        assertFalse("no host pin prompt", exists("[data-testid=host-key-trust-gate]"));
        assertTrue("native SSH authenticated with the vault key", acceptedFingerprints(sshdSince(mark, "trusted-connect"))
                .contains(authorized.getString("fingerprint")));
        String file = "gwlane-trusted-io-" + run + ".txt";
        // Force the same narrow terminal that soft-wrapped a long CI run id in #3106.
        // The visible receipt is deliberately short; the independent host oracle
        // verifies the full run-unique payload and that the new shell's cwd is HOME.
        setWebViewWidthFraction(0.55f);
        try {
            awaitJsTrue("document.querySelector('.xterm-screen')?.getBoundingClientRect().width < 350");
            logTrustedPtyDiagnostics("before-typing");
            typeCommand("echo GWTRUST$((6*7))_" + run + " | tee ./" + file);
            awaitJsTrue(VISIBLE_TERMINAL + ".includes('GWTRUST42')");
            String content = awaitTrustedHostFile(file, "GWTRUST42_" + run);
            assertEquals("GWTRUST42_" + run, content);
            logTrustedPtyDiagnostics("completed");
            screenshot("trusted-vault-terminal");
        } catch (AssertionError | Exception failure) {
            captureTrustedFailure(file);
            throw failure;
        } finally { setWebViewWidthFraction(1f); }
        assertEveryDialWentThroughTheGateway(nativeDials());
        assertEquals("no cloud writes from the phone", 0, backend.writes);
        assertNoRoutingTokenEscaped();
    }

    /** Metadata-only PTY witness; forwards original native arguments/results unchanged. */
    private void installTrustedPtyDiagnostics() throws Exception {
        assertEquals("installed", evalString("(() => {"
                + "window.__gwPty=[];window.__gwPtyDropped=0;"
                + "const push=e=>{if(window.__gwPty.length<512)window.__gwPty.push(e);else window.__gwPtyDropped++;};"
                + "const bytes=s=>{try{return atob(s??'').length;}catch{return -1;}};"
                + "const nativePromise=window.Capacitor.nativePromise.bind(window.Capacitor);"
                + "window.Capacitor.nativePromise=(plugin,method,options)=>{"
                + "if(plugin!=='SshCapability'||!['openPty','writePty','readPty','closePty'].includes(method))return nativePromise(plugin,method,options);"
                + "const ref=o=>({connectionId:o?.connectionId??null,generationId:o?.generationId??null,channelId:o?.channelId??null,sequence:o?.sequence??null});"
                + "push({at:Date.now(),kind:'call',method,...ref(options),byteCount:method==='writePty'?bytes(options?.dataBase64):0,attachCommand:method==='openPty'&&String(options?.command??'').includes('pocketshell sessions attach')});"
                + "return nativePromise(plugin,method,options).then(result=>{push({at:Date.now(),kind:'result',method,...ref(result),byteCount:method==='readPty'?bytes(result?.dataBase64):0,eof:result?.eof===true});return result;},error=>{push({at:Date.now(),kind:'rejected',method,...ref(options)});throw error;});"
                + "};return 'installed';})()"));
    }

    private void logTrustedPtyDiagnostics(String stage) throws Exception {
        JSONObject evidence = new JSONObject(evalString("JSON.stringify((() => {"
                + "const t=[...document.querySelectorAll('.terminal-pane textarea')].find(n=>n.offsetParent!==null)??[...document.querySelectorAll('.xterm-helper-textarea')].find(n=>n.offsetParent!==null);"
                + "const r=t?.getBoundingClientRect();"
                + "return {innerWidth,innerHeight,terminalTextareaPresent:!!t,terminalFocused:!!t&&document.activeElement===t,textareaValueLength:t?.value?.length??0,selectionStart:t?.selectionStart??null,selectionEnd:t?.selectionEnd??null,textareaRect:r?{x:r.x,y:r.y,width:r.width,height:r.height}:null,pty:window.__gwPty??[],ptyDropped:window.__gwPtyDropped??0,journal:(window.__pocketshellConnectionJournal??[]).slice(-8).map(e=>({phase:e.phase,selectedId:e.selectedId,transportId:e.transportId,generationId:e.generationId}))};"
                + "})())"));
        JSONArray events = evidence.getJSONArray("pty");
        evidence.remove("pty");
        Log.i(TAG, "GATEWAY_TRUSTED_PTY " + evidence.put("stage", stage).put("ptyEvents", events.length()));
        for (int i = 0; i < events.length(); i++)
            Log.i(TAG, "GATEWAY_TRUSTED_PTY_EVENT " + events.getJSONObject(i).put("stage", stage));
    }

    private void captureTrustedFailure(String name) {
        try { screenshot("trusted-vault-terminal"); }
        catch (Throwable unavailable) { Log.w(TAG, "trusted native frame unavailable: " + unavailable.getClass().getSimpleName()); }
        try { logTrustedPtyDiagnostics("failed-before-width-restore"); }
        catch (Throwable unavailable) { Log.w(TAG, "trusted PTY evidence unavailable: " + unavailable.getClass().getSimpleName()); }
        try {
            JSONObject metadata = controller(new JSONObject().put("op", "host-file").put("name", name).put("phase", "trusted-io"));
            metadata.remove("content");
            Log.i(TAG, "GATEWAY_TRUSTED_FILE_FAILURE " + metadata);
        } catch (Throwable unavailable) { Log.w(TAG, "trusted host evidence unavailable: " + unavailable.getClass().getSimpleName()); }
    }

    /** One typed command; independently observe actual completion without retyping. */
    private String awaitTrustedHostFile(String name, String expected) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 20_000;
        JSONObject reply = null;
        String content = "";
        do {
            reply = controller(new JSONObject().put("op", "host-file").put("name", name).put("phase", "trusted-io"));
            content = reply.getString("content").trim();
            JSONObject metadata = new JSONObject(reply.toString());
            metadata.remove("content");
            Log.i(TAG, "GATEWAY_TRUSTED_FILE " + metadata);
            assertEquals("independent host exec succeeds: " + reply, 0, reply.getInt("hostExecExitCode"));
            if (expected.equals(content) && reply.optBoolean("exists") && "regular".equals(reply.optString("type")) && reply.getInt("readExitCode") == 0) break;
            Thread.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        assertNotNull("independent file status was read", reply);
        assertTrue("the command created the independent host file: " + reply, reply.getBoolean("exists"));
        assertEquals("the command wrote a regular file", "regular", reply.getString("type"));
        assertTrue("the independent file has bytes", reply.getLong("sizeBytes") > 0);
        assertEquals("independent host read succeeds: " + reply, 0, reply.getInt("readExitCode"));
        assertEquals("independent host read has no error", "", reply.getString("readStderr"));
        return content;
    }

    private void awaitTrustedReadAfter(int previous) throws Exception {
        long deadline = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS;
        while (backend.trustedReads <= previous && SystemClock.uptimeMillis() < deadline) Thread.sleep(50);
        assertTrue("the new encrypted descriptor was fetched through native /settings", backend.trustedReads > previous);
    }

    JSONObject fixture() throws Exception {
        return new JSONObject(shell("cat " + requiredArgument("vaultFixture")));
    }

    void selectVariant(String variant) throws Exception {
        backend.vaultSlots = new JSONObject(fixture().getJSONObject("variants").getJSONObject(variant).toString());
    }

    String authorizeVault(String phase) throws Exception {
        phoneFingerprint = controller(new JSONObject().put("op", "authorize")
                .put("publicKey", fixture().getString("publicKey")).put("phase", phase)).getString("fingerprint");
        return phoneFingerprint;
    }

    void launchSignedInLocked() throws Exception {
        scenario = ActivityScenario.launch(SharedShellLaunch.intent());
        awaitJsTrue("!!document.querySelector('[data-testid=server-google-login]')", 60_000);
        evalRaw("localStorage.removeItem('" + SHARED_SETTINGS_KEY + "'); localStorage.setItem('" + HOSTS_KEY + "','[]'); localStorage.removeItem('ps.lastFolder.gw-dev'); localStorage.removeItem('ps.lastFolder.gw-missing'); 'empty'");
        click("[data-testid=server-google-login]");
        awaitJsTrue("!!document.querySelector('[data-testid=vault-unlock-gate]')", 60_000);
    }

    /** Only the Google provider and sync API are fake: normal visible sign-in and vault unlock. */
    void launchSignedInAndUnlocked() throws Exception {
        if (backend.vaultSlots == null) selectVariant("active");
        launchSignedInLocked();
        setValue("[data-testid=vault-unlock-passphrase]", "pocketshell-vault-test-passphrase gateway");
        click("[data-testid=vault-unlock-submit]");
        awaitJsTrue("!!document.querySelector('[data-testid=trusted-server-connect]:not([disabled])')", 120_000);
        installDialRecorder();
    }

    /** Relaunch refusal cases with normal authentication; protected trust floors remain intact. */
    private void restartWithVariant(String variant) throws Exception {
        JSONArray recent = new JSONArray(evalString("JSON.stringify(window.__gwDials ?? [])"));
        for (int i = 0; i < recent.length(); i++) previousDials.put(recent.get(i));
        scenario.close(); scenario = null;
        for (String prefs : new String[]{AUTH_PREFS, "pocketshell_vault_device_v1"})
            targetContext().deleteSharedPreferences(prefs);
        selectVariant(variant);
        launchSignedInAndUnlocked();
    }

    /** Read only public native pairing/floor metadata; never install, remove or reset trust here. */
    private void assertInstalledTrustedRevision(int expected) throws Exception {
        String slot = null;
        java.util.Iterator<String> names = backend.vaultSlots.keys();
        while (names.hasNext()) {
            String name = names.next();
            if (name.startsWith("th-") && new JSONObject(backend.vaultSlots.getJSONObject(name).getString("data"))
                    .getString("deviceId").equals(hello.getString("deviceId"))) slot = name;
        }
        assertNotNull("the primary descriptor slot exists", slot);
        evalRaw("(() => {window.__gwTrustRead=null; const plugin=window.Capacitor.Plugins.GatewayPairing;"
                + "(async()=>{try{const rows=await plugin.list({requestId:'gwlane-read-trust'});"
                + "const row=rows.pairings.find(r=>r.deviceId===" + JSONObject.quote(hello.getString("deviceId")) + ");"
                + "const result=await plugin.trustedRevisions({accountId:" + JSONObject.quote("google:" + hello.getString("sub"))
                + ",vaultId:row.vaultId});window.__gwTrustRead={revision:row.trustedRevision,floor:result.floors["
                + JSONObject.quote(slot) + "]};}catch(e){window.__gwTrustRead={error:String(e?.message??e)};}})();return 'reading';})()");
        awaitJsTrue("window.__gwTrustRead !== null");
        JSONObject state = new JSONObject(evalString("JSON.stringify(window.__gwTrustRead)"));
        assertFalse("native trust metadata is readable: " + state, state.has("error"));
        assertEquals("the native pairing retains its authenticated revision", expected, state.getInt("revision"));
        assertEquals("the native rollback floor remains enforced", expected, state.getInt("floor"));
        Log.i(TAG, "TRUSTED_REVISION " + state);
    }

    /** Tap an account host and return the picker's error (the dial must fail). */
    private String dialExpectingError(String host) throws Exception {
        evalRaw("(() => {const e=document.querySelector('.picker p.error'); if(e) e.textContent=''; return 'ok';})()");
        tapHost(host);
        awaitJsTrue(PICKER_ERROR + " !== '' || !!document.querySelector('button[title=\"Back to hosts\"]')", 120_000);
        String error = evalString(PICKER_ERROR);
        assertFalse("the dial must be refused, not connected", exists("button[title=\"Back to hosts\"]"));
        Log.i(TAG, "refused " + host + ": " + error);
        return error;
    }

    void tapHost(String name) throws Exception {
        evalRaw("(() => {const button=[...document.querySelectorAll('[data-testid=trusted-server-connect]')].find((n)=>"
                + "n.textContent.includes(" + JSONObject.quote(name) + "));"
                + "if(!button) throw new Error('no trusted server'); button.click(); return 'ok';})()");
    }

    void typeCommand(String command) throws Exception {
        scenario.onActivity(activity -> activity.getBridge().getWebView().requestFocus());
        String result = evalString("JSON.stringify((() => {"
                + "const textarea=" + VISIBLE_TEXTAREA + ";"
                + "if(!textarea) throw new Error('no visible terminal'); textarea.focus();"
                + "const transfer=new DataTransfer();transfer.setData('text/plain'," + JSONObject.quote(command) + ");"
                + "textarea.dispatchEvent(new ClipboardEvent('paste',{clipboardData:transfer,bubbles:true,cancelable:true}));"
                + "window.__gwKeyUps=[]; textarea.addEventListener('keyup',(e)=>window.__gwKeyUps.push(e.key),{once:true});"
                + "return {focused:document.activeElement===textarea};})())");
        assertTrue("the terminal holds focus for typing: " + result, new JSONObject(result).getBoolean("focused"));
        awaitJsTrue(VISIBLE_TERMINAL + ".includes(" + JSONObject.quote(command.substring(0, Math.min(8, command.length()))) + ")");
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER);
        awaitJsTrue("(window.__gwKeyUps ?? []).includes('Enter')");
    }

    /** The visible pane's row count once it has stayed the same for 4 consecutive polls ~250 ms apart. */
    private int settledVisibleRows() throws Exception {
        long deadline = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS;
        int last = visibleRows();
        List<Integer> seen = new ArrayList<>(List.of(last));
        int steady = 0;
        while (steady < 3) {
            assertTrue("the pane's row count never settled after the resize (seen " + seen + ")",
                    SystemClock.uptimeMillis() < deadline);
            Thread.sleep(250);
            int now = visibleRows();
            if (now != last) seen.add(now);
            steady = (now == last && now > 0) ? steady + 1 : 0;
            last = now;
        }
        Log.i(TAG, "GATEWAY_RESIZE_ROWS " + seen);
        return last;
    }

    private int visibleRows() throws Exception {
        return Integer.parseInt(evalString("String([...document.querySelectorAll('.xterm-rows')].find((n)=>n.offsetParent!==null)?.children.length ?? 0)"));
    }

    private int hostRows(String file, String phase) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 20_000;
        String content = "";
        while (SystemClock.uptimeMillis() < deadline) {
            content = hostFile(file, phase).trim();
            if (content.matches("\\d+ \\d+")) return Integer.parseInt(content.split(" ")[0]);
            Thread.sleep(300);
        }
        throw new AssertionError("the host never wrote its terminal size to " + file + ": " + content);
    }

    String hostFile(String name, String phase) throws Exception {
        return controller(new JSONObject().put("op", "host-file").put("name", name).put("phase", phase)).getString("content");
    }

    private void setWebViewWidthFraction(float fraction) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            WebView webView = activity.getBridge().getWebView();
            ViewGroup.LayoutParams params = webView.getLayoutParams();
            View parent = (View) webView.getParent();
            params.width = fraction >= 1f ? ViewGroup.LayoutParams.MATCH_PARENT : Math.round(parent.getWidth() * fraction);
            webView.setLayoutParams(params);
            done.countDown();
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
    }

    private void setWebViewHeightFraction(float fraction) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            WebView webView = activity.getBridge().getWebView();
            ViewGroup.LayoutParams params = webView.getLayoutParams();
            View parent = (View) webView.getParent();
            params.height = fraction >= 1f ? ViewGroup.LayoutParams.MATCH_PARENT : Math.round(parent.getHeight() * fraction);
            webView.setLayoutParams(params);
            done.countDown();
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
    }

    private String connectionPhase() throws Exception {
        return evalString("String([...(window.__pocketshellConnectionJournal ?? [])].at(-1)?.phase === 'live' ? 'connected' : 'pending')");
    }

    private static final String VISIBLE_TEXTAREA_SELECTOR = ".xterm .xterm-helper-textarea";

    JSONObject sshdSince(int mark, String phase) throws Exception {
        return controller(new JSONObject().put("op", "sshd-since").put("mark", mark).put("phase", phase));
    }

    static List<String> acceptedFingerprints(JSONObject sshd) throws Exception {
        List<String> out = new ArrayList<>();
        JSONArray accepted = sshd.getJSONArray("accepted");
        for (int i = 0; i < accepted.length(); i++) out.add(accepted.getJSONObject(i).getString("fingerprint"));
        return out;
    }

    /** Every native connect() the app made, with what it asked for (failed dials included). */
    void installDialRecorder() throws Exception {
        assertEquals("installed", evalString("(() => {window.__gwDials=[];"
                + "const nativePromise=window.Capacitor.nativePromise.bind(window.Capacitor);"
                + "window.Capacitor.nativePromise=(plugin,method,options)=>{"
                + "if(plugin==='SshCapability'&&method==='connect') window.__gwDials.push({at:Date.now(),"
                + "gateway:options?.gateway ?? null, link:options?.link ?? null, hostname:String(options?.hostname ?? ''),"
                + "credentialKind:String(options?.credential?.kind ?? ''), handleId:String(options?.credential?.handleId ?? ''),"
                + " expectedHostKey:options?.expectedHostKey ?? null});"
                + "return nativePromise(plugin,method,options);}; return 'installed';})()"));
    }

    JSONArray nativeDials() throws Exception {
        JSONArray result = new JSONArray(previousDials.toString());
        JSONArray current = new JSONArray(evalString("JSON.stringify(window.__gwDials ?? [])"));
        for (int i = 0; i < current.length(); i++) result.put(current.get(i));
        return result;
    }

    /** D28: no gateway host is ever dialled as plain SSH. */
    void assertEveryDialWentThroughTheGateway(JSONArray dials) throws Exception {
        assertTrue("the recorder saw native dials", dials.length() > 0);
        for (int i = 0; i < dials.length(); i++) {
            JSONObject dial = dials.getJSONObject(i);
            JSONObject gateway = dial.optJSONObject("gateway");
            assertNotNull("dial " + i + " went through the gateway: " + dial, gateway);
            assertEquals(hello.getString("serverUrl"), gateway.getString("serverUrl"));
            assertTrue("no link marker: " + dial, dial.isNull("link"));
            assertEquals("a stored key, never a password", "key-handle", dial.getString("credentialKind"));
            assertTrue("the pin never comes from JS: " + dial, dial.isNull("expectedHostKey"));
        }
    }

    /** The routing tokens the fake broker minted never reach WebView state or the app's logcat. */
    private void assertNoRoutingTokenEscaped() throws Exception {
        String dump = evalString("JSON.stringify({local: Object.entries(localStorage), session: Object.entries(sessionStorage),"
                + " html: document.documentElement.outerHTML, dials: window.__gwDials})");
        String pid = shell("pidof " + targetContext().getPackageName()).trim();
        String logcat = shell("logcat -d -v brief --pid=" + pid);
        for (String token : backend.mints) {
            String signature = token.substring(token.lastIndexOf('.') + 1);
            assertFalse("a routing token reached WebView state", dump.contains(signature));
            assertFalse("a routing token reached logcat", logcat.contains(signature));
        }
        assertFalse("the Google ID token reached WebView state", dump.contains(FakeGoogle.SIGNATURE));
    }

    // ---- the account: two gateway hosts as the desktop wrote them -------------

    private JSONObject accountHosts() throws Exception {
        JSONObject device = new JSONObject().put("serverUrl", hello.getString("serverUrl")).put("deviceId", hello.getString("deviceId"));
        JSONObject missing = new JSONObject().put("serverUrl", hello.getString("serverUrl")).put("deviceId", hello.getString("missingDeviceId"));
        return new JSONObject().put("hosts", new JSONArray()
                .put(new JSONObject().put("name", DEVICE_HOST).put("hostname", "gw-dev.gateway").put("port", 22)
                        .put("user", hello.getString("user")).put("gateway", device))
                .put(new JSONObject().put("name", MISSING_HOST).put("hostname", "gw-missing.gateway").put("port", 22)
                        .put("user", hello.getString("user")).put("gateway", missing)));
    }

    private static String encrypt(String plaintext) throws Exception {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[16];
        byte[] iv = new byte[12];
        random.nextBytes(salt);
        random.nextBytes(iv);
        int iterations = 100_000;
        byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(new PBEKeySpec(PASSPHRASE.toCharArray(), salt, iterations, 256)).getEncoded();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        return new JSONObject().put("v", 1).put("kdf", "pbkdf2-sha256").put("iter", iterations)
                .put("salt", Base64.getEncoder().encodeToString(salt))
                .put("iv", Base64.getEncoder().encodeToString(iv))
                .put("ct", Base64.getEncoder().encodeToString(ct)).toString();
    }

    // ---- fake edges -----------------------------------------------------------

    /** Google's account chooser: a scripted ID token for the run's account. */
    static final class FakeGoogle implements GoogleSyncSession.SignInProvider {
        static final String SIGNATURE = "FAKEGOOGLEIDTOKEN3086gwlaneDoNotLeak";
        final String token;

        FakeGoogle(String sub, String email) {
            long exp = System.currentTimeMillis() / 1000L + 3600L;
            token = b64url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}") + "."
                    + b64url("{\"sub\":\"" + sub + "\",\"email\":\"" + email + "\",\"exp\":" + exp + "}") + "." + SIGNATURE;
        }

        @Override
        public String obtainIdToken(boolean interactiveRequest) throws SyncAuthException {
            return token;
        }

        @Override
        public void clearCredentialState() {
        }
    }

    static String b64url(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The pocketshell-sync API: the encrypted account slot, and the broker's
     * {@code POST /gateway/token}, which signs a broker-shaped routing token
     * (RS256, iss/aud/scope/sub/email, 4 minutes) with the run's broker key
     * — or, while {@link #signWithWrongKey}, with a key the gateway does not
     * know (the 4401 step).
     */
    final class FakeSyncBackend implements GoogleSyncSession.Transport {
        final List<String> mints = Collections.synchronizedList(new ArrayList<>());
        volatile boolean signWithWrongKey;
        private final PrivateKey brokerKey;
        private final PrivateKey wrongKey;
        private final String kid;
        private String data;
        /** Key-vault W4: the account's `vault` / `vk-*` slots, when the journey has a vault. */
        JSONObject vaultSlots;
        int writes;
        volatile int trustedReads;
        final List<String> reads = Collections.synchronizedList(new ArrayList<>());

        FakeSyncBackend() throws Exception {
            byte[] der = Base64.getDecoder().decode(requiredArgument("gatewayBrokerKeyPkcs8Base64"));
            brokerKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            wrongKey = generator.generateKeyPair().getPrivate();
            kid = requiredArgument("gatewayBrokerKid");
        }

        synchronized void seed(String envelope) {
            data = envelope;
        }

        @Override
        public synchronized GoogleSyncSession.Response send(String method, String url, Map<String, String> headers, String body) {
            String path = url.replaceFirst("^https://[^/]+", "");
            if ("GET".equals(method)) reads.add(path);
            if (!("Bearer " + google.token).equals(headers.get("Authorization"))) {
                return new GoogleSyncSession.Response(401, "{\"message\":\"Unauthorized\"}");
            }
            try {
                if ("GET".equals(method) && "/me".equals(path)) return new GoogleSyncSession.Response(200,
                        new JSONObject().put("sub", hello.getString("sub")).put("email", hello.getString("email")).toString());
                if ("GET".equals(method) && "/settings".equals(path)) {
                    JSONArray slots = new JSONArray();
                    if (vaultSlots != null) {
                        java.util.Iterator<String> keys = vaultSlots.keys();
                        while (keys.hasNext()) {
                            String key = keys.next();
                            JSONObject row = vaultSlots.getJSONObject(key);
                            slots.put(new JSONObject().put("slot", key).put("version", row.getInt("version"))
                                    .put("size", row.getString("data").length()).put("updatedAt", "2026-11-05T00:00:00.000Z"));
                        }
                    }
                    return new GoogleSyncSession.Response(200, new JSONObject().put("slots", slots).toString());
                }
                if ("POST".equals(method) && "/gateway/token".equals(path)) {
                    if (body != null && !body.isEmpty()) return new GoogleSyncSession.Response(400, "{\"error\":\"bad_request\"}");
                    long now = System.currentTimeMillis() / 1000L;
                    long lifetime = 240;
                    String token = sign(new JSONObject().put("iss", hello.getString("issuer")).put("aud", "pocketshell-gateway")
                            .put("scope", "pocketshell.gateway").put("sub", hello.getString("sub"))
                            .put("email", hello.getString("email")).put("email_verified", true)
                            .put("nbf", now - 1).put("iat", now).put("exp", now + lifetime));
                    mints.add(token);
                    return new GoogleSyncSession.Response(200, new JSONObject().put("token", token).put("token_type", "Bearer")
                            .put("expires_in", lifetime).put("expires_at", now + lifetime).toString());
                }
                if ("GET".equals(method) && path.startsWith("/settings/") && vaultSlots != null
                        && vaultSlots.has(path.substring("/settings/".length())) && !"/settings/main".equals(path)) {
                    String slot = path.substring("/settings/".length());
                    if (slot.startsWith("th-")) trustedReads++;
                    JSONObject stored = vaultSlots.getJSONObject(slot);
                    return new GoogleSyncSession.Response(200, new JSONObject().put("slot", slot)
                            .put("version", stored.getInt("version")).put("data", stored.getString("data")).toString());
                }
                if ("PUT".equals(method) || "DELETE".equals(method)) writes += 1;
                if ("GET".equals(method) && "/settings/main".equals(path)) {
                    return new GoogleSyncSession.Response(200, new JSONObject().put("slot", "main").put("version", 1)
                            .put("data", data).toString());
                }
                return new GoogleSyncSession.Response(404, "{\"message\":\"no route\"}");
            } catch (Exception error) {
                return new GoogleSyncSession.Response(500, "{\"message\":\"fixture error\"}");
            }
        }

        private String sign(JSONObject claims) throws Exception {
            String input = b64url(new JSONObject().put("alg", "RS256").put("typ", "JWT").put("kid", kid).toString())
                    + "." + b64url(claims.toString());
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(signWithWrongKey ? wrongKey : brokerKey);
            signer.update(input.getBytes(StandardCharsets.US_ASCII));
            return input + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
        }
    }

    // ---- the runner's host controller ------------------------------------------

    JSONObject controller(JSONObject request) throws Exception {
        int port = Integer.parseInt(requiredArgument("gatewayControllerPort"));
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("10.0.2.2", port), 10_000);
            socket.setSoTimeout(150_000);
            OutputStream out = socket.getOutputStream();
            out.write((request.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            String line = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
            assertNotNull("the gateway lane controller closed without an answer to " + request, line);
            JSONObject reply = new JSONObject(line);
            assertTrue("the gateway lane controller failed " + request + ": " + reply, reply.optBoolean("ok"));
            return reply;
        }
    }

    // ---- WebView plumbing --------------------------------------------------------

    private String requiredArgument(String name) {
        String value = InstrumentationRegistry.getArguments().getString(name);
        assertNotNull("pass instrumentation argument " + name + " (scripts/connected-js-gateway-docker.sh does)", value);
        return value;
    }

    private boolean exists(String selector) throws Exception {
        return "true".equals(evalRaw("!!document.querySelector(" + JSONObject.quote(selector) + ")"));
    }

    private void setValue(String selector, String value) throws Exception {
        evalString("(() => {const node=document.querySelector(" + JSONObject.quote(selector) + ");"
                + "if(!node)throw new Error('missing ' + " + JSONObject.quote(selector) + ");"
                + "node.value=" + JSONObject.quote(value) + ";node.dispatchEvent(new Event('input',{bubbles:true}));"
                + "node.dispatchEvent(new Event('change',{bubbles:true}));return 'set';})()");
    }

    private void click(String selector) throws Exception {
        evalString("(() => {const node = document.querySelector(" + JSONObject.quote(selector) + ");"
                + "if (!node) throw new Error('missing ' + " + JSONObject.quote(selector) + ");"
                + "node.click(); return 'clicked';})()");
    }

    void awaitJsTrue(String expression) throws Exception {
        awaitJsTrue(expression, WAIT_TIMEOUT_MILLIS);
    }

    void awaitJsTrue(String expression, long timeoutMillis) throws Exception {
        long deadline = SystemClock.uptimeMillis() + timeoutMillis;
        String last = "<not evaluated>";
        while (SystemClock.uptimeMillis() < deadline) {
            last = evalRaw(expression);
            if ("true".equals(last)) return;
            Thread.sleep(150);
        }
        String state = evalRaw("JSON.stringify({screen: (document.body?.innerText ?? '').slice(0, 800),"
                + " journal: (window.__pocketshellConnectionJournal ?? []).slice(-8), dials: window.__gwDials ?? null})");
        throw new AssertionError("WebView condition did not become true: " + expression + " (last: " + last + "; state: " + state + ")");
    }

    private String evalString(String expression) throws Exception {
        Object decoded = new JSONTokener(evalRaw(expression)).nextValue();
        return decoded == null || decoded == JSONObject.NULL ? null : decoded.toString();
    }

    String evalRaw(String expression) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        scenario.onActivity(activity -> {
            WebView webView = activity.getBridge().getWebView();
            assertNotNull("the packaged activity must contain its WebView", webView);
            webView.evaluateJavascript(expression, value -> {
                result.set(value);
                latch.countDown();
            });
        });
        assertTrue("timed out evaluating WebView JavaScript", latch.await(JS_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        return result.get();
    }

    /** Evidence only: the runner pulls /data/local/tmp/gwlane-*.png into the run's artifacts. */
    private void screenshot(String name) throws Exception {
        shell("screencap -p /data/local/tmp/gwlane-" + name + ".png");
    }

    private String shell(String command) throws Exception {
        android.os.ParcelFileDescriptor output = InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);
        try (java.io.InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(output)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Context targetContext() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }
}
