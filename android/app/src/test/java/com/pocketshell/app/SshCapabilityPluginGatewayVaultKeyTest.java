package com.pocketshell.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import com.getcapacitor.JSObject;
import java.io.IOException;
import org.junit.Before;
import org.junit.Test;

/**
 * Key-vault W4 + W5: a gateway dial may authenticate with the pairing's own
 * key (`host` policy) or with a key from the account vault held in memory
 * (`managed` / `both`). The pairing's pin is required either way; a vault
 * handle that was wiped (vault locked) refuses as VAULT_LOCKED before the
 * broker; any OTHER stored key is still refused.
 */
public final class SshCapabilityPluginGatewayVaultKeyTest {
    private static final String PAIRED = "01234567-89ab-cdef-0123-456789abcdef";
    private static final String PIN = "SHA256:" + "A".repeat(43);
    private static final String ORIGIN = "wss://gateway.pocketshell.io";

    private SshCapabilityPluginGatewayRefusalTest.RecordingSession session;
    private SshCapabilityPlugin plugin;
    private EphemeralCredentials held;

    @Before public void setUp() throws IOException {
        session = new SshCapabilityPluginGatewayRefusalTest.RecordingSession();
        GatewayPairingStore store = new GatewayPairingStore(new GatewayPairingStoreTest.MemoryRepository(), () -> 1_000L);
        store.pair("sub-1", ORIGIN, "host-1", PIN, PAIRED);
        plugin = new SshCapabilityPlugin(null, () -> 123L, session, store);
        held = new EphemeralCredentials();
        plugin.ephemeralCredentials = held;
    }

    @Test public void aHeldVaultKeyDialsThroughThePairingWithItsPin() throws Exception {
        String handle = held.hold(EphemeralCredentialsTest.throwawayKey()).handleId;
        SshCapabilityPlugin.GatewayDialPlan plan = plugin.resolveGatewayDialPlan(options(handle), "key-handle", handle, 60_000);
        assertNotNull(plan);
        assertEquals("the pin still comes from the pairing", PIN, plan.pinExpectation().fingerprintSha256);
        assertEquals(0, session.exchanges);
    }

    @Test public void aWipedVaultKeyRefusesAsVaultLockedBeforeTheBroker() throws Exception {
        String handle = held.hold(EphemeralCredentialsTest.throwawayKey()).handleId;
        held.wipeAll();
        assertRefused(handle, SshCapabilityPlugin.VAULT_LOCKED);
        assertEquals(0, session.exchanges);
    }

    @Test public void anotherStoredKeyIsStillRefused() {
        assertRefused("ffffffff-ffff-ffff-ffff-ffffffffffff", "GATEWAY_UNPAIRED");
    }

    @Test public void theVaultKeyNeedsAPairingForThatDevice() throws Exception {
        String handle = held.hold(EphemeralCredentialsTest.throwawayKey()).handleId;
        JSObject other = options(handle);
        other.put("gateway", new JSObject().put("serverUrl", ORIGIN).put("deviceId", "host-2"));
        try {
            plugin.resolveGatewayDialPlan(other, "key-handle", handle, 20_000);
            fail("an unpaired device refuses");
        } catch (SshCapabilityPlugin.PluginFailure failure) {
            assertEquals("GATEWAY_UNPAIRED", failure.code);
        }
    }

    private void assertRefused(String handle, String code) {
        try {
            plugin.resolveGatewayDialPlan(options(handle), "key-handle", handle, 20_000);
            fail("expected " + code);
        } catch (SshCapabilityPlugin.PluginFailure failure) {
            assertEquals(code, failure.code);
        } catch (Exception other) {
            throw new AssertionError(other);
        }
    }

    private static JSObject options(String handle) {
        return new JSObject()
                .put("requestId", "request-1")
                .put("generationId", "generation-1")
                .put("hostId", "host-id-1")
                .put("hostname", "display-label")
                .put("port", 22)
                .put("username", "alexey")
                .put("credential", new JSObject().put("kind", "key-handle").put("handleId", handle))
                .put("gateway", new JSObject().put("serverUrl", ORIGIN).put("deviceId", "host-1"));
    }
}
