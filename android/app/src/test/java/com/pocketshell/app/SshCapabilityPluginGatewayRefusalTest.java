package com.pocketshell.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.getcapacitor.JSObject;
import java.io.IOException;
import org.junit.Before;
import org.junit.Test;

/**
 * Issue #3060: the SshCapabilityPlugin's gateway dial plan — every refusal is
 * typed and happens BEFORE any effect: before the SSH engine exists, before
 * the broker is asked, before any socket opens. A present-but-malformed
 * marker refuses; nothing is ever projected into an ordinary direct dial,
 * and every direct-shaped request resolves exactly as before (null plan).
 */
public final class SshCapabilityPluginGatewayRefusalTest {
    private static final String HANDLE = "01234567-89ab-cdef-0123-456789abcdef";
    private static final String PIN = "SHA256:" + "A".repeat(43);

    private RecordingSession session;
    private SshCapabilityPlugin plugin;

    @Before public void setUp() throws IOException {
        session = new RecordingSession();
        GatewayPairingStore store = new GatewayPairingStore(
                new GatewayPairingStoreTest.MemoryRepository(), () -> 1_000L);
        plugin = new SshCapabilityPlugin(null, () -> 123L, session, store);
    }

    // --- direct dials are untouched -------------------------------------------------

    @Test public void aRequestWithoutGatewayMarkerPlansADirectDial() throws Exception {
        assertNull(plugin.resolveGatewayDialPlan(connectOptions(null, "key-handle"), "key-handle", HANDLE, 20_000));
        assertFalse(session.subjectReads > 0);
    }

    // --- the marker's presence decides; the value must strictly validate ------------

    @Test public void aPresentNullMarkerRefuses() {
        assertRefused(connectOptions(JSObject.NULL, "key-handle"), "INVALID_ARGUMENT");
    }

    @Test public void aPresentNonObjectMarkerRefuses() {
        assertRefused(connectOptions("wss://gateway.example.io", "key-handle"), "INVALID_ARGUMENT");
    }

    @Test public void malformedTargetsRefuse() {
        assertRefused(connectOptions(target(null, "host-1"), "key-handle"), "INVALID_ARGUMENT");
        assertRefused(connectOptions(target("wss://gateway.example.io/path", "host-1"), "key-handle"), "INVALID_ARGUMENT");
        assertRefused(connectOptions(target("wss://gateway.example.io", "no"), "key-handle"), "INVALID_ARGUMENT");
        assertRefused(connectOptions(target(42, "host-1"), "key-handle"), "INVALID_ARGUMENT");
        assertRefused(connectOptions(target("wss://gateway.example.io", 42), "key-handle"), "INVALID_ARGUMENT");
    }

    // --- refusals that must happen before any sign-in or broker call -----------------

    @Test public void aPlaintextWsTargetRefusesBeforeTheSignInOrBroker() throws Exception {
        // Core accepts ws:// as a (development) target; the phone's tunnel
        // dials wss:// only. #3086 slice 3: the refusal happens at plan time,
        // not after a routing token was already minted for it.
        pairFor("sub-1", "ws://gateway.example.io", "host-1");
        assertRefused(connectOptions(target("ws://gateway.example.io", "host-1"), "key-handle"), "INVALID_ARGUMENT");
        assertRefused(connectOptions(target("ws://127.0.0.1:8080", "host-1"), "key-handle"), "INVALID_ARGUMENT");
        assertFalse("the sign-in was never even read", session.subjectReads > 0);
        assertEquals(0, session.exchanges);
    }

    @Test public void aCallerSuppliedHostKeyPinRefusesBeforeEverything() {
        JSObject options = connectOptions(target("wss://gateway.example.io", "host-1"), "key-handle");
        options.put("expectedHostKey", new JSObject().put("kind", "sha256-fingerprint").put("fingerprintSha256", PIN));
        assertRefused(options, "INVALID_ARGUMENT");
        assertFalse("the sign-in was never even read", session.subjectReads > 0);
        assertEquals(0, session.exchanges);
    }

    @Test public void aConflictingLinkMarkerRefuses() {
        JSObject options = connectOptions(target("wss://gateway.example.io", "host-1"), "key-handle");
        options.put("link", new JSObject().put("relay", "x"));
        assertRefused(options, "INVALID_ARGUMENT");
        assertFalse(session.subjectReads > 0);
    }

    @Test public void aPasswordCredentialRefuses() {
        assertRefused(connectOptions(target("wss://gateway.example.io", "host-1"), "password"),
                "INVALID_ARGUMENT");
        assertFalse(session.subjectReads > 0);
    }

    @Test public void aSignedOutPhoneRefusesBeforeAnyBrokerCall() {
        session.signedOut = true;
        assertRefused(connectOptions(target("wss://gateway.example.io", "host-1"), "key-handle"),
                "NOT_SIGNED_IN");
        assertEquals("no exchange may run for a signed-out phone", 0, session.exchanges);
    }

    // --- pairing namespace refusals, still before the broker -------------------------

    @Test public void anUnpairedTargetRefusesBeforeTheBroker() {
        assertRefused(connectOptions(target("wss://gateway.example.io", "host-1"), "key-handle"),
                "GATEWAY_UNPAIRED");
        assertEquals(0, session.exchanges);
    }

    @Test public void aDifferentNamespacedAccountDoesNotSeeThePairing() throws Exception {
        pairFor("sub-OTHER", "wss://gateway.example.io", "host-1");
        assertRefused(connectOptions(target("wss://gateway.example.io", "host-1"), "key-handle"),
                "GATEWAY_UNPAIRED");
        assertEquals(0, session.exchanges);
    }

    @Test public void aPairingForAnotherDeviceRefuses() throws Exception {
        pairFor("sub-1", "wss://gateway.example.io", "host-2");
        assertRefused(connectOptions(target("wss://gateway.example.io", "host-1"), "key-handle"),
                "GATEWAY_UNPAIRED");
        assertEquals(0, session.exchanges);
    }

    @Test public void aPairingBoundToAnotherKeyRefuses() throws Exception {
        pairFor("sub-1", "wss://gateway.example.io", "host-1");
        assertRefused(connectOptions(target("wss://gateway.example.io", "host-1"), "key-handle", "ffffffff-ffff-ffff-ffff-ffffffffffff"),
                "GATEWAY_UNPAIRED");
        assertEquals(0, session.exchanges);
    }

    // --- the happy plan ---------------------------------------------------------------

    @Test public void aValidPairedTargetPlansTheDial() throws Exception {
        pairFor("sub-1", "wss://gateway.example.io", "host-1");
        SshCapabilityPlugin.GatewayDialPlan plan = plugin.resolveGatewayDialPlan(
                connectOptions(target("wss://gateway.example.io/", "host-1"), "key-handle"), "key-handle", HANDLE, 60_000);
        assertNotNull(plan);
        assertEquals("wss://gateway.example.io", plan.target.serverUrl);
        assertEquals("host-1", plan.target.deviceId);
        assertEquals(PIN, plan.pairing.fingerprintSha256());
        assertEquals("the verifier expectation is the saved pin, never the ready advisory",
                PIN, plan.pinExpectation().fingerprintSha256);
        assertNull(plan.pinExpectation().keyB64);
        // On the monotonic clock (the JVM constructor's System.nanoTime), never wall time.
        long now = System.nanoTime() / 1_000_000L;
        assertTrue("the whole-connect deadline covers broker, dial, ready, KEX and userauth",
                plan.deadlineMonotonicMs > now && plan.deadlineMonotonicMs <= now + 60_000 + 1_000);
        assertEquals("planning never mints; the mint happens inside the owned attempt",
                0, session.exchanges);
    }

    @Test public void aTargetAsTheCapacitorBridgeDeliversItPlansTheDial() throws Exception {
        // #3086 slice 3, found by the emulator gateway lane: the bridge parses
        // the call's JSON into a JSObject whose NESTED objects are plain
        // org.json.JSONObject, never JSObject. Slice 2 refused every real
        // gateway dial as "malformed" because it required a JSObject here.
        pairFor("sub-1", "wss://gateway.example.io", "host-1");
        JSObject bridged = new JSObject(connectOptions(
                target("wss://gateway.example.io", "host-1"), "key-handle").toString());
        assertFalse("the bridge's nested target is a plain JSONObject",
                bridged.opt("gateway") instanceof JSObject);
        SshCapabilityPlugin.GatewayDialPlan plan = plugin.resolveGatewayDialPlan(
                bridged, "key-handle", HANDLE, 60_000);
        assertNotNull(plan);
        assertEquals("host-1", plan.target.deviceId);
    }

    // --- harness ------------------------------------------------------------------------

    private void pairFor(String subject, String serverUrl, String deviceId) throws IOException {
        // The pairing is stored under `subject`; the CURRENT account stays
        // sub-1, so a pairing for any other account is invisible to a dial.
        GatewayPairingStore store = new GatewayPairingStore(
                new GatewayPairingStoreTest.MemoryRepository(), () -> 1_000L);
        store.pair(subject, serverUrl, deviceId, PIN, HANDLE);
        plugin = new SshCapabilityPlugin(null, () -> 123L, session, store);
    }

    private static JSObject connectOptions(Object gateway, String credentialKind) {
        return connectOptions(gateway, credentialKind, HANDLE);
    }

    private static JSObject connectOptions(Object gateway, String credentialKind, String handleId) {
        JSObject options = new JSObject()
                .put("requestId", "request-1")
                .put("generationId", "generation-1")
                .put("hostId", "host-id-1")
                .put("hostname", "display-label")
                .put("port", 22)
                .put("username", "alexey")
                .put("credential", new JSObject().put("kind", credentialKind).put("handleId", handleId));
        if (gateway != null) options.put("gateway", gateway);
        return options;
    }

    private static JSObject target(Object serverUrl, Object deviceId) {
        JSObject target = new JSObject();
        if (serverUrl != null || deviceId != null) {
            target.put("serverUrl", serverUrl);
            target.put("deviceId", deviceId);
        }
        return target;
    }

    private void assertRefused(JSObject options, String code) {
        try {
            plugin.resolveGatewayDialPlan(options, options.getJSObject("credential").getString("kind"),
                    options.getJSObject("credential").getString("handleId"), 20_000);
            fail("expected a " + code + " refusal");
        } catch (SshCapabilityPlugin.PluginFailure failure) {
            assertEquals(code, failure.code);
        } catch (Exception failure) {
            throw new AssertionError("refused with the wrong error type", failure);
        }
    }

    /** Records every sign-in read and every broker exchange: the refusal
     * tests prove the exchange count stays at zero. */
    static final class RecordingSession extends GatewaySyncSession {
        int subjectReads;
        int exchanges;
        boolean signedOut;
        String subject = "sub-1";

        RecordingSession() {
            super((GoogleSyncSession) null);
        }

        @Override
        String subject() throws SyncAuthException {
            subjectReads++;
            if (signedOut) throw new SyncAuthException(SyncAuthException.NOT_SIGNED_IN, "Sign in with Google first.");
            return subject;
        }

        @Override
        GoogleSyncSession.GatewayBrokerExchange exchange() {
            exchanges++;
            throw new AssertionError("the plan must never mint; the mint runs inside the owned attempt");
        }
    }
}
