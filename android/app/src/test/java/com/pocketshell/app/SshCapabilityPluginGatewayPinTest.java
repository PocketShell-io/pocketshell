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

    @Test public void vaultDescriptorAuthenticatesWithItsBoundEphemeralKeyThroughRealTunnel() throws Exception {
        String key = "A".repeat(22), vault = "B".repeat(22);
        fixture.pairings.installVault(GatewayDialFixture.SUBJECT, fixture.serverUrl(), GatewayDialFixture.DEVICE,
                fixture.hostKeyLine(), key, vault, 2, 4, "work", "alexey");
        EphemeralCredentials held = new EphemeralCredentials();
        fixture.plugin.ephemeralCredentials = held;
        byte[] pem = GatewayDialFixture.pkcs8Pem(fixture.clientKey).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String handle = held.hold(pem).handleId;
        java.util.Arrays.fill(pem, (byte) 0);
        held.bind(handle, "google:" + GatewayDialFixture.SUBJECT, vault, 2, key);
        JSObject options = fixture.connectOptions("trusted-vault");
        options.put("credential", new JSObject().put("kind", "key-handle").put("handleId", handle));
        JSObject result = fixture.plugin.connectNow(new JSObject(options.toString()));
        assertTrue(result.getBoolean("gatewayHostKeyVerified"));
        assertTrue(fixture.host.acceptedLogins.get() > 0);
        held.wipeAll();
    }

    @Test public void wrongAccountVaultEpochOrKeyNeverOpensTunnelOrAuthenticates() throws Exception {
        String key = "A".repeat(22), vault = "B".repeat(22);
        fixture.pairings.installVault(GatewayDialFixture.SUBJECT, fixture.serverUrl(), GatewayDialFixture.DEVICE,
                fixture.hostKeyLine(), key, vault, 2, 4, "work", "alexey");
        EphemeralCredentials held = new EphemeralCredentials();
        fixture.plugin.ephemeralCredentials = held;
        for (String[] identity : new String[][] {
                { "google:other", vault, "2", key }, { "google:sub-1", "C".repeat(22), "2", key },
                { "google:sub-1", vault, "1", key }, { "google:sub-1", vault, "2", "D".repeat(22) } }) {
            String handle = held.hold(EphemeralCredentialsTest.throwawayKey()).handleId;
            held.bind(handle, identity[0], identity[1], Long.parseLong(identity[2]), identity[3]);
            JSObject options = fixture.connectOptions("wrong-identity");
            options.put("credential", new JSObject().put("kind", "key-handle").put("handleId", handle));
            try { fixture.plugin.connectNow(new JSObject(options.toString())); org.junit.Assert.fail("foreign vault credential accepted"); }
            catch (SshCapabilityPlugin.PluginFailure error) { assertEquals("GATEWAY_UNPAIRED", error.code); }
            held.wipeAll();
        }
        assertEquals(0, fixture.gateway.clientConnections.get());
        assertEquals(0, fixture.host.userauthAttempts.get());
    }

    @Test public void aNewerProtectedRevisionRefusesCachedVaultPairingBeforeAnyReconnectSocket() throws Exception {
        String key = "A".repeat(22), vault = "B".repeat(22);
        fixture.pairings.installVault(GatewayDialFixture.SUBJECT, fixture.serverUrl(), GatewayDialFixture.DEVICE,
                fixture.hostKeyLine(), key, vault, 2, 4, "work", "alexey");
        EphemeralCredentials held = new EphemeralCredentials(); fixture.plugin.ephemeralCredentials = held;
        String handle = held.hold(EphemeralCredentialsTest.throwawayKey()).handleId;
        held.bind(handle, "google:sub-1", vault, 2, key);
        fixture.plugin.gatewayTrustedRevisionProbe = pairing -> 5L;
        JSObject options = fixture.connectOptions("stale-vault");
        options.put("credential", new JSObject().put("kind", "key-handle").put("handleId", handle));
        try { fixture.plugin.connectNow(new JSObject(options.toString())); org.junit.Assert.fail("stale cached pairing accepted"); }
        catch (SshCapabilityPlugin.PluginFailure error) { assertEquals("GATEWAY_UNPAIRED", error.code); }
        held.wipeAll();
        assertEquals(0, fixture.gateway.clientConnections.get());
        assertEquals(0, fixture.host.userauthAttempts.get());
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
        assertTrue("the refusal names the pairing, not a trust prompt: " + failure.getMessage(),
                failure.getMessage().startsWith("The host's SSH key does not match the key this phone paired"));
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
