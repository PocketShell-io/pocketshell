package com.pocketshell.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.getcapacitor.JSObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * #3086 slice 2: the gateway host-key pin. The ONLY trust source for a
 * gateway dial is the pin saved in the native pairing store; it is checked
 * against the key the host actually presents during KEX, BEFORE userauth.
 * The gateway's {@code ready.ssh_host_key} advisory (the fixture sends a
 * wrong one on purpose) is never consulted, there is no TOFU, and a
 * mismatch never connects. Every case runs the real connect path through a
 * loopback gateway into a real sshd.
 */
public final class SshCapabilityPluginGatewayPinTest {
    private GatewayDialFixture fixture;

    @Before public void setUp() throws Exception {
        fixture = new GatewayDialFixture();
    }

    @After public void tearDown() throws Exception {
        fixture.close();
    }

    @Test public void aMatchingPinConnectsAndReportsTheVerifiedReceipt() throws Exception {
        fixture.pairings.pair(GatewayDialFixture.SUBJECT, fixture.serverUrl(), GatewayDialFixture.DEVICE,
                fixture.hostFingerprint(), GatewayDialFixture.HANDLE);
        JSObject result = fixture.connect("pin-match");
        assertTrue("core's receipt: the native pin was verified before userauth",
                result.getBoolean("gatewayHostKeyVerified"));
        assertEquals(fixture.hostFingerprint(), result.getJSObject("hostKey").getString("fingerprintSha256"));
        assertTrue("userauth ran, after the pin matched", fixture.host.acceptedLogins.get() > 0);
        assertEquals(1, fixture.plugin.currentResourceSnapshot().connections);
        assertEquals("the only route to the host was the gateway relay", 1, fixture.gateway.clientConnections.get());
    }

    @Test public void aMismatchedPinNeverReachesUserauthAndNeverConnects() throws Exception {
        String otherHostsPin = GatewayDialFixture.fingerprint(GatewayDialFixture.ecKeyPair().getPublic());
        fixture.pairings.pair(GatewayDialFixture.SUBJECT, fixture.serverUrl(), GatewayDialFixture.DEVICE,
                otherHostsPin, GatewayDialFixture.HANDLE);
        SshCapabilityPlugin.PluginFailure failure = fixture.connectExpectingFailure("pin-mismatch");
        assertEquals("HOST_KEY_REJECTED", failure.code);
        assertEquals("the presented key is reported for diagnosis, never trusted",
                fixture.hostFingerprint(), failure.data.getString("fingerprintSha256"));
        assertEquals("a mismatch stops the dial BEFORE any userauth attempt",
                0, fixture.host.userauthAttempts.get());
        assertEquals("no connection is registered", 0, fixture.plugin.currentResourceSnapshot().connections);
        assertFalse(failure.data.has("gatewayHostKeyVerified"));
    }

    @Test public void thePastedHostKeyLinePinsTheExactKey() throws Exception {
        // The `pocketshell gateway show --host-key` line, as pasted (with its
        // trailing newline).
        fixture.pairings.pair(GatewayDialFixture.SUBJECT, fixture.serverUrl(), GatewayDialFixture.DEVICE,
                fixture.hostKeyLine() + "\n", GatewayDialFixture.HANDLE);
        JSObject result = fixture.connect("line-match");
        assertTrue(result.getBoolean("gatewayHostKeyVerified"));
        assertTrue(fixture.host.acceptedLogins.get() > 0);
    }

    @Test public void aHostKeyLineForAnotherKeyIsRejectedBeforeUserauth() throws Exception {
        String otherHostsLine = GatewayDialFixture.keyLine(GatewayDialFixture.ecKeyPair().getPublic());
        fixture.pairings.pair(GatewayDialFixture.SUBJECT, fixture.serverUrl(), GatewayDialFixture.DEVICE,
                otherHostsLine, GatewayDialFixture.HANDLE);
        SshCapabilityPlugin.PluginFailure failure = fixture.connectExpectingFailure("line-mismatch");
        assertEquals("HOST_KEY_REJECTED", failure.code);
        assertEquals(0, fixture.host.userauthAttempts.get());
        assertEquals(0, fixture.plugin.currentResourceSnapshot().connections);
    }

    @Test public void aMissingPinRefusesBeforeTheBrokerOrAnySocket() throws Exception {
        SshCapabilityPlugin.PluginFailure failure = fixture.connectExpectingFailure("pin-missing");
        assertEquals("no pairing, no dial: never TOFU", "GATEWAY_UNPAIRED", failure.code);
        assertEquals("no broker token is minted for an unpaired host", 0, fixture.broker.mints.get());
        assertEquals("no gateway socket is opened", 0, fixture.gateway.clientConnections.get());
        assertEquals(0, fixture.host.userauthAttempts.get());
    }

    @Test public void aCallerSuppliedPinIsRefusedEvenWhenItWouldMatch() throws Exception {
        fixture.pairings.pair(GatewayDialFixture.SUBJECT, fixture.serverUrl(), GatewayDialFixture.DEVICE,
                fixture.hostFingerprint(), GatewayDialFixture.HANDLE);
        JSObject options = fixture.connectOptions("js-pin");
        options.put("expectedHostKey", new JSObject().put("kind", "sha256-fingerprint")
                .put("fingerprintSha256", fixture.hostFingerprint()));
        try {
            fixture.plugin.connectNow(options);
            throw new AssertionError("the WebView must never set gateway trust");
        } catch (SshCapabilityPlugin.PluginFailure failure) {
            assertEquals("INVALID_ARGUMENT", failure.code);
        }
        assertEquals(0, fixture.gateway.clientConnections.get());
    }
}
