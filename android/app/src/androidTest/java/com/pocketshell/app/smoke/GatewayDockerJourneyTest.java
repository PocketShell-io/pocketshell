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
 * pocketshell#3086 slice 3: the packaged app dials a host registered on the
 * REAL PocketShell gateway, end to end, on the emulator.
 *
 * <p>Route: the shared app's host picker → core's ConnectionController → the
 * native SshCapability plugin → native broker exchange → wss:// to the
 * gateway (a TLS front with the run's test CA, through {@code adb reverse}) →
 * the real gateway → the host's enrolled pocketshell-link agent → the host's
 * own sshd on loopback. The emulator has no route to that sshd at all.
 *
 * <p>Fake edges, both installed through {@link GoogleSyncEnvironment} like the
 * account lanes: Google's account chooser, and the sync API — whose
 * {@code POST /gateway/token} signs a broker-shaped RS256 routing token with
 * the run's throwaway broker key, so the gateway verifies it exactly as it
 * verifies the real broker's. Everything between is production code.
 *
 * <p>Host-side truth comes from the runner's controller
 * (scripts/gateway-lane-fixture.py) over a socket at 10.0.2.2: it authorizes
 * the phone's key the way a user does on the host, reads the host sshd's log
 * and files, and stops/starts the host agent. scripts/connected-js-gateway-docker.sh
 * runs this class; scripts/check-js-gateway-results.py checks its JUnit report
 * and the controller's journal.
 */
@RunWith(AndroidJUnit4.class)
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

    private ActivityScenario<MainActivity> scenario;
    private JSONObject hello;
    private FakeGoogle google;
    private FakeSyncBackend backend;
    private String run;

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
        targetContext().deleteSharedPreferences(AUTH_PREFS);
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
        launchSignedInAndUnlocked();
        String handle = generateAuthorizedPhoneKey();

        // 1. 4401: the gateway cannot verify the routing token. First, so this
        // activity's native token cache holds no valid token to reuse.
        pair(hello.getString("deviceId"), hello.getString("hostKeyLine"), handle);
        backend.signWithWrongKey = true;
        int mark = controller(new JSONObject().put("op", "mark")).getInt("mark");
        String error = dialExpectingError(DEVICE_HOST);
        assertEquals("4401 tells the user to sign in again", "Your sign-in expired — sign in again and retry.", error);
        JSONObject unauthorized = sshdSince(mark, "unauthorized-4401");
        assertEquals("a refused token never reaches sshd: " + unauthorized, 0, unauthorized.getInt("connections"));
        backend.signWithWrongKey = false;

        // 2. Pin mismatch: paired with a key that is not the host's.
        pair(hello.getString("deviceId"), hello.getString("wrongHostKeyLine"), handle);
        mark = controller(new JSONObject().put("op", "mark")).getInt("mark");
        error = dialExpectingError(DEVICE_HOST);
        assertTrue("a pin mismatch names the pairing: " + error,
                error.startsWith("The host's SSH key does not match the key this phone paired for that device."));
        assertFalse("a gateway dial never offers a trust prompt", exists("[data-testid=host-key-trust-gate]"));
        JSONObject mismatch = sshdSince(mark, "pin-mismatch");
        assertTrue("the mismatched dial reached the host's sshd through the gateway: " + mismatch,
                mismatch.getInt("connections") >= 1);
        assertEquals("a pin mismatch makes ZERO userauth attempts: " + mismatch, 0, mismatch.getJSONArray("userauth").length());
        screenshot("pin-mismatch");

        // The correct pin from here on: the line the host's enrolled agent prints.
        pair(hello.getString("deviceId"), hello.getString("hostKeyLine"), handle);

        // 3. 4404: a device the gateway has never enrolled.
        pair(hello.getString("missingDeviceId"), hello.getString("hostKeyLine"), handle);
        mark = controller(new JSONObject().put("op", "mark")).getInt("mark");
        error = dialExpectingError(MISSING_HOST);
        assertEquals("an unknown device is not registered", "That host is not registered on the gateway.", error);
        JSONObject notFound = sshdSince(mark, "not-found-4404");
        assertEquals(0, notFound.getInt("connections"));

        // 4. 4503: the enrolled device's agent is offline (the gateway's own presence says so).
        assertFalse(controller(new JSONObject().put("op", "agent-stop").put("phase", "offline")).optBoolean("online", true));
        mark = controller(new JSONObject().put("op", "mark")).getInt("mark");
        error = dialExpectingError(DEVICE_HOST);
        assertEquals("an offline device is reported as offline, not as a login failure",
                "The host is not connected to the gateway right now — check that its agent is running.", error);
        assertFalse("offline is not a sign-in failure: " + error, error.toLowerCase(java.util.Locale.ROOT).contains("sign"));
        screenshot("device-offline");
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
        String handle = generateAuthorizedPhoneKey();
        pair(hello.getString("deviceId"), hello.getString("hostKeyLine"), handle);

        int mark = controller(new JSONObject().put("op", "mark")).getInt("mark");
        tapHost(DEVICE_HOST);
        awaitJsTrue("!!document.querySelector('button[title=\"Back to hosts\"]') || " + PICKER_ERROR + " !== ''");
        assertEquals("the gateway dial connects", "", evalString(PICKER_ERROR));
        assertFalse("no trust prompt for a pinned gateway host", exists("[data-testid=host-key-trust-gate]"));
        JSONObject login = sshdSince(mark, "connect");
        assertTrue("the host accepted the phone's own key over the gateway: " + login,
                acceptedFingerprints(login).contains(phoneFingerprint));

        // The host's aplexer sessions, listed through the gateway.
        String folder = hello.getString("sessionFolder");
        awaitJsTrue("[...document.querySelectorAll('.dir-header')].some((n)=>n.textContent.includes(" + JSONObject.quote(folder) + "))");
        evalString("(() => {const row=[...document.querySelectorAll('.dir-header')].find((n)=>n.textContent.includes("
                + JSONObject.quote(folder) + ")); if(!row) throw new Error('no folder row'); row.click(); return 'ok';})()");
        awaitJsTrue(VISIBLE_TERMINAL + ".includes('$')");

        // Terminal I/O: the shell runs what was typed; the host keeps its own copy.
        String io = "gwlane-io-" + run + ".txt";
        typeCommand("echo GWLANE_$((6*7))_" + run + " | tee ~/" + io);
        awaitJsTrue(VISIBLE_TERMINAL + ".includes('GWLANE_42_" + run + "')");
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
        int rowsAfter = visibleRows();
        String sizeAfter = "gwlane-size2-" + run + ".txt";
        typeCommand("stty size > ~/" + sizeAfter);
        int hostRowsAfter = hostRows(sizeAfter, "size-after");
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
        assertTrue("the reconnect logged in again with the phone's key, through the gateway: " + relogin,
                acceptedFingerprints(relogin).contains(phoneFingerprint));

        JSONArray dials = nativeDials();
        assertEveryDialWentThroughTheGateway(dials);
        assertTrue("each gateway dial minted or reused a native token (" + backend.mints.size() + " mints)",
                backend.mints.size() >= 1);
        assertNoRoutingTokenEscaped();
        Log.i(TAG, "GATEWAY_HAPPY " + new JSONObject().put("dials", dials.length()).put("mints", backend.mints.size())
                .put("rows", new JSONArray().put(hostRowsBefore).put(hostRowsAfter)));
    }

    // ---- the journey's steps -------------------------------------------------

    private String phoneFingerprint;

    /** The shared app, signed in with the fake Google account and unlocked, listing the account's gateway hosts. */
    private void launchSignedInAndUnlocked() throws Exception {
        scenario = ActivityScenario.launch(SharedShellLaunch.intent());
        awaitJsTrue("!!document.querySelector('.picker .header-actions')", 60_000);
        String staleDocument = "reload-" + SystemClock.uptimeMillis();
        evalRaw("window.__pocketshellStaleDocument = " + JSONObject.quote(staleDocument) + ";"
                + "localStorage.removeItem('" + SHARED_SETTINGS_KEY + "');"
                + "localStorage.setItem('" + HOSTS_KEY + "', '[]');"
                + "location.reload(); 'reload'");
        awaitJsTrue("window.__pocketshellStaleDocument !== " + JSONObject.quote(staleDocument)
                + " && document.readyState === 'complete' && !!document.querySelector('.picker .header-actions')", 60_000);
        installDialRecorder();

        click(".picker .header-actions .account-action");
        awaitJsTrue("!!document.querySelector('[data-testid=android-account] .account-login .account-primary:not([disabled])')");
        click("[data-testid=android-account] .account-login .account-primary");
        awaitJsTrue("document.querySelector('[data-testid=android-account] .account-identity')?.textContent.includes("
                + JSONObject.quote(hello.getString("email")) + ") === true");
        click("[data-testid=android-account-back]");
        awaitJsTrue("!!document.querySelector('.picker .locked-note')");
        click(".picker .locked-note .btn-ghost");
        awaitJsTrue("!!document.querySelector('[data-testid=android-account] .passphrase-field input:not([disabled])')");
        setValue("[data-testid=android-account] .passphrase-field input", PASSPHRASE);
        click("[data-testid=android-account] .host-heading .btn-ghost");
        awaitJsTrue("(document.querySelector('[data-testid=android-account] .account-message')?.textContent ?? '').includes('2 synced hosts')", 120_000);
        click("[data-testid=android-account-back]");
        awaitJsTrue("[...document.querySelectorAll('.picker .host-name')].map((n)=>n.textContent.trim()).includes(" + JSONObject.quote(DEVICE_HOST) + ")");
    }

    /** A key generated in the phone's vault; its public line goes on the host like a user pastes it there. */
    private String generateAuthorizedPhoneKey() throws Exception {
        evalString("(() => {window.__gwKey=null; const vault=window.Capacitor.Plugins.SshKeyVault;"
                + "(async()=>{try{const meta=await vault.generateKey({label:'Gateway lane phone key',algorithm:'Ed25519'});"
                + "const pub=await vault.publicKey({handleId:meta.handleId});"
                + "window.__gwKey={handleId:meta.handleId,fingerprint:meta.fingerprintSha256,publicKey:pub.publicKey};}"
                + "catch(e){window.__gwKey={error:String(e?.message??e)};}})(); return 'started';})()");
        awaitJsTrue("window.__gwKey !== null");
        JSONObject key = new JSONObject(evalString("JSON.stringify(window.__gwKey)"));
        assertFalse("the vault generated a key: " + key, key.has("error"));
        String publicLine = key.getString("publicKey");
        assertFalse("only the public line leaves the vault", publicLine.contains("PRIVATE"));
        JSONObject authorized = controller(new JSONObject().put("op", "authorize").put("publicKey", publicLine).put("phase", "authorize"));
        assertEquals("the host stores exactly the phone's key", key.getString("fingerprint"), authorized.getString("fingerprint"));
        phoneFingerprint = key.getString("fingerprint");
        return key.getString("handleId");
    }

    /** The user's pairing action: the host-key line the host printed, the device and the phone key. */
    private void pair(String deviceId, String hostKeyLine, String handle) throws Exception {
        evalString("(() => {window.__gwPair=null;"
                + "window.Capacitor.Plugins.GatewayPairing.pair({requestId:'gwlane-pair-'+Date.now(),"
                + "expectedAccountEmail:" + JSONObject.quote(hello.getString("email"))
                + ",serverUrl:" + JSONObject.quote(hello.getString("serverUrl"))
                + ",deviceId:" + JSONObject.quote(deviceId)
                + ",hostKey:" + JSONObject.quote(hostKeyLine)
                + ",keyHandleId:" + JSONObject.quote(handle) + "})"
                + ".then((r)=>{window.__gwPair={ok:true,reply:r};},(e)=>{window.__gwPair={ok:false,code:e?.code,message:String(e?.message??e)};});"
                + "return 'started';})()");
        awaitJsTrue("window.__gwPair !== null");
        JSONObject result = new JSONObject(evalString("JSON.stringify(window.__gwPair)"));
        assertTrue("pairing saved: " + result, result.getBoolean("ok"));
        String reply = result.getJSONObject("reply").toString();
        assertFalse("the pairing reply never carries the account subject", reply.contains(hello.getString("sub")));
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

    private void tapHost(String name) throws Exception {
        evalString("(() => {const row=[...document.querySelectorAll('.picker .host-row')].find((n)=>"
                + "n.querySelector('.host-name')?.textContent.trim()===" + JSONObject.quote(name) + ");"
                + "if(!row) throw new Error('no host row " + name + "'); row.click(); return 'ok';})()");
    }

    private void typeCommand(String command) throws Exception {
        scenario.onActivity(activity -> activity.getBridge().getWebView().requestFocus());
        String result = evalString("JSON.stringify((() => {"
                + "const textarea=" + VISIBLE_TEXTAREA + ";"
                + "if(!textarea) throw new Error('no visible terminal'); textarea.focus();"
                + "const transfer=new DataTransfer();transfer.setData('text/plain'," + JSONObject.quote(command) + ");"
                + "textarea.dispatchEvent(new ClipboardEvent('paste',{clipboardData:transfer,bubbles:true,cancelable:true}));"
                + "window.__gwKeyUps=[]; textarea.addEventListener('keyup',(e)=>window.__gwKeyUps.push(e.key),{once:true});"
                + "return {focused:document.activeElement===textarea};})())");
        assertTrue("the terminal holds focus for typing: " + result, new JSONObject(result).getBoolean("focused"));
        awaitJsTrue(VISIBLE_TERMINAL + ".replace(/\\n/g,'').includes(" + JSONObject.quote(command.substring(0, Math.min(24, command.length()))) + ")");
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER);
        awaitJsTrue("(window.__gwKeyUps ?? []).includes('Enter')");
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

    private String hostFile(String name, String phase) throws Exception {
        return controller(new JSONObject().put("op", "host-file").put("name", name).put("phase", phase)).getString("content");
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

    private JSONObject sshdSince(int mark, String phase) throws Exception {
        return controller(new JSONObject().put("op", "sshd-since").put("mark", mark).put("phase", phase));
    }

    private static List<String> acceptedFingerprints(JSONObject sshd) throws Exception {
        List<String> out = new ArrayList<>();
        JSONArray accepted = sshd.getJSONArray("accepted");
        for (int i = 0; i < accepted.length(); i++) out.add(accepted.getJSONObject(i).getString("fingerprint"));
        return out;
    }

    /** Every native connect() the app made, with what it asked for (failed dials included). */
    private void installDialRecorder() throws Exception {
        assertEquals("installed", evalString("(() => {window.__gwDials=[];"
                + "const nativePromise=window.Capacitor.nativePromise.bind(window.Capacitor);"
                + "window.Capacitor.nativePromise=(plugin,method,options)=>{"
                + "if(plugin==='SshCapability'&&method==='connect') window.__gwDials.push({at:Date.now(),"
                + "gateway:options?.gateway ?? null, link:options?.link ?? null, hostname:String(options?.hostname ?? ''),"
                + "credentialKind:String(options?.credential?.kind ?? ''), expectedHostKey:options?.expectedHostKey ?? null});"
                + "return nativePromise(plugin,method,options);}; return 'installed';})()"));
    }

    private JSONArray nativeDials() throws Exception {
        return new JSONArray(evalString("JSON.stringify(window.__gwDials ?? [])"));
    }

    /** D28: no gateway host is ever dialled as plain SSH. */
    private void assertEveryDialWentThroughTheGateway(JSONArray dials) throws Exception {
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
            if (!("Bearer " + google.token).equals(headers.get("Authorization"))) {
                return new GoogleSyncSession.Response(401, "{\"message\":\"Unauthorized\"}");
            }
            try {
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

    private JSONObject controller(JSONObject request) throws Exception {
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

    private void awaitJsTrue(String expression) throws Exception {
        awaitJsTrue(expression, WAIT_TIMEOUT_MILLIS);
    }

    private void awaitJsTrue(String expression, long timeoutMillis) throws Exception {
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

    private String evalRaw(String expression) throws Exception {
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
