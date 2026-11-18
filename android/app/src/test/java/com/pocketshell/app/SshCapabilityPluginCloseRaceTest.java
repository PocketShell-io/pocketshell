package com.pocketshell.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * #3141: connection close is no longer ordered against openPty /
 * openPortForward by a single thread. A close that runs after an open's
 * connected-check but before its registration has already scanned the
 * registries, so registration must re-check and tear the new resource down,
 * or the channel/forward permit, the server socket and the forward-service
 * entry leak.
 */
public final class SshCapabilityPluginCloseRaceTest {
    private static SshCapabilityPlugin.SshConnection connection(SshCapabilityPlugin plugin, String id) throws Exception {
        SshCapabilityPlugin.SshConnection connection = new SshCapabilityPlugin.SshConnection(id, "g", "h", "r", new net.schmizz.sshj.SSHClient());
        registry(plugin, "connections").put(id, connection);
        return connection;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> registry(SshCapabilityPlugin plugin, String name) throws Exception {
        Field field = SshCapabilityPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        return (Map<String, Object>) field.get(plugin);
    }

    private static Semaphore forwardPermits() throws Exception {
        Field field = SshCapabilityPlugin.class.getDeclaredField("FORWARD_PERMITS");
        field.setAccessible(true);
        return (Semaphore) field.get(null);
    }

    @Test
    public void aPtyRegisteredAfterCloseScannedIsTornDownAndItsPermitReturned() throws Exception {
        SshCapabilityPlugin plugin = new SshCapabilityPlugin(null, () -> 123L);
        SshCapabilityPlugin.SshConnection connection = connection(plugin, "c-pty");
        int permitsBefore = connection.channelPermits.availablePermits();
        assertTrue(connection.channelPermits.tryAcquire()); // openPty passed acquireChannel while "connected"
        plugin.closeConnection(connection, "closed", false); // ...then close scanned an empty registry
        AtomicInteger closes = new AtomicInteger();
        SshCapabilityPlugin.SshPty pty = new SshCapabilityPlugin.SshPty("pty-late", connection, null, null, closes::incrementAndGet);
        try {
            plugin.registerPty(connection, pty);
            fail("registration on a closed connection must fail");
        } catch (SshCapabilityPlugin.PluginFailure expected) {
            assertEquals("CONNECTION_LOST", expected.code);
        }
        assertEquals("channel closed exactly once", 1, closes.get());
        assertFalse("PTY must not stay registered", registry(plugin, "ptys").containsKey("pty-late"));
        assertEquals("channel permit returned", permitsBefore, connection.channelPermits.availablePermits());
        assertEquals(0, plugin.currentResourceSnapshot().ptys);
    }

    @Test
    public void aForwardRegisteredAfterCloseScannedFreesItsSocketAndBothPermits() throws Exception {
        SshCapabilityPlugin plugin = new SshCapabilityPlugin(null, () -> 123L);
        SshCapabilityPlugin.SshConnection connection = connection(plugin, "c-fwd");
        int channelBefore = connection.channelPermits.availablePermits();
        int forwardBefore = forwardPermits().availablePermits();
        assertTrue(connection.channelPermits.tryAcquire());
        assertTrue(forwardPermits().tryAcquire());
        plugin.closeConnection(connection, "closed", false);
        AtomicInteger closes = new AtomicInteger();
        SshCapabilityPlugin.SshForward forward = new SshCapabilityPlugin.SshForward("fwd-late", connection, null, null, 4242, closes::incrementAndGet);
        try {
            plugin.registerForward(connection, forward);
            fail("registration on a closed connection must fail");
        } catch (SshCapabilityPlugin.PluginFailure expected) {
            assertEquals("CONNECTION_LOST", expected.code);
        }
        assertEquals("server socket / forwarder closed exactly once", 1, closes.get());
        assertFalse(registry(plugin, "forwards").containsKey("fwd-late"));
        assertEquals(channelBefore, connection.channelPermits.availablePermits());
        assertEquals("global forward permit returned", forwardBefore, forwardPermits().availablePermits());
        assertEquals(0, plugin.currentResourceSnapshot().forwards);
    }

    @Test
    public void aCloseBetweenRegistrationAndRecheckClosesTheResourceExactlyOnce() throws Exception {
        SshCapabilityPlugin plugin = new SshCapabilityPlugin(null, () -> 123L);
        SshCapabilityPlugin.SshConnection connection = connection(plugin, "c-mid");
        assertTrue(connection.channelPermits.tryAcquire());
        int permitsAfterAcquire = connection.channelPermits.availablePermits();
        AtomicInteger closes = new AtomicInteger();
        SshCapabilityPlugin.SshPty pty = new SshCapabilityPlugin.SshPty("pty-mid", connection, null, null, closes::incrementAndGet);
        // The close lands exactly between put and re-check: closeChildren closes it, the re-check must not double-close/release.
        plugin.beforeRegisterRecheckForTesting = () -> plugin.closeConnection(connection, "closed", false);
        try {
            plugin.registerPty(connection, pty);
            fail("must fail once closed");
        } catch (SshCapabilityPlugin.PluginFailure expected) { /* ok */ }
        assertEquals(1, closes.get());
        assertEquals("exactly one permit release", permitsAfterAcquire + 1, connection.channelPermits.availablePermits());
    }

    @Test
    public void aLiveConnectionKeepsItsRegisteredResources() throws Exception {
        SshCapabilityPlugin plugin = new SshCapabilityPlugin(null, () -> 123L);
        SshCapabilityPlugin.SshConnection connection = connection(plugin, "c-live");
        SshCapabilityPlugin.SshPty pty = new SshCapabilityPlugin.SshPty("pty-live", connection, null, null, () -> { });
        plugin.registerPty(connection, pty);
        assertTrue(registry(plugin, "ptys").containsKey("pty-live"));
    }
}
