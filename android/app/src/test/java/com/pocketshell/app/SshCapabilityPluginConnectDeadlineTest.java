package com.pocketshell.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.getcapacitor.JSObject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * #3086 slice 2 review round 1, blocker 2: the whole-connect deadline and a
 * successful registration can race, and exactly one of them may win.
 *
 * <p>4e93b68c7's watchdog checked "still pending" under {@code connectLock},
 * released the lock, and only then claimed the deadline and closed the
 * client; registration checked cancellation but not the deadline. A
 * registration landing in that gap returned an accepted connection to core
 * which the watchdog then killed. These interleavings are forced
 * deterministically with latches at the two production seams.
 */
public final class SshCapabilityPluginConnectDeadlineTest {
    private GatewayDialFixture fixture;

    @Before public void setUp() throws Exception {
        fixture = new GatewayDialFixture();
        fixture.pairings.pair(GatewayDialFixture.SUBJECT, fixture.serverUrl(), GatewayDialFixture.DEVICE,
                fixture.hostKeyLine(), GatewayDialFixture.HANDLE);
    }

    @After public void tearDown() throws Exception {
        fixture.plugin.beforeConnectRegistrationForTesting = null;
        fixture.plugin.afterConnectDeadlineDecisionForTesting = null;
        fixture.close();
    }

    @Test public void theDeadlineWinsWhenItFiresBeforeRegistration() throws Exception {
        CountDownLatch watchdogDecided = new CountDownLatch(1);
        CountDownLatch releaseWatchdog = new CountDownLatch(1);
        AtomicReference<SshCapabilityPlugin.ConnectAttempt> captured = new AtomicReference<>();
        AtomicReference<Thread> watchdog = new AtomicReference<>();
        fixture.plugin.afterConnectDeadlineDecisionForTesting = () -> {
            watchdogDecided.countDown();
            awaitQuietly(releaseWatchdog);
        };
        // The connect thread reaches registration; the watchdog fires first
        // and is held right after it decided — before it closes anything.
        fixture.plugin.beforeConnectRegistrationForTesting = attempt -> {
            captured.set(attempt);
            Thread thread = new Thread(() -> fixture.plugin.runConnectDeadline(attempt), "test-connect-deadline");
            watchdog.set(thread);
            thread.start();
            awaitQuietly(watchdogDecided);
        };

        SshCapabilityPlugin.PluginFailure failure = null;
        JSObject accepted = null;
        try {
            accepted = fixture.connect("deadline-first");
        } catch (SshCapabilityPlugin.PluginFailure refused) {
            failure = refused;
        } finally {
            releaseWatchdog.countDown();
            if (watchdog.get() != null) watchdog.get().join(5_000);
        }
        assertEquals("a claimed deadline must refuse registration, never hand core a doomed connection: " + accepted,
                null, accepted);
        assertNotNull(failure);
        assertEquals("CONNECT_TIMEOUT", failure.code);
        assertEquals("nothing stays registered", 0, fixture.plugin.currentResourceSnapshot().connections);
        assertFalse("the attempt's SSH client is closed", captured.get().client.isConnected());
        assertTrue("the attempt's tunnel is closed", captured.get().tunnel.socket().isClosed());
    }

    @Test public void registrationWinsAndTheLateWatchdogIsANoOp() throws Exception {
        AtomicReference<SshCapabilityPlugin.ConnectAttempt> captured = new AtomicReference<>();
        AtomicInteger watchdogDecisions = new AtomicInteger();
        fixture.plugin.beforeConnectRegistrationForTesting = captured::set;
        fixture.plugin.afterConnectDeadlineDecisionForTesting = watchdogDecisions::incrementAndGet;

        JSObject result = fixture.connect("registration-first");
        assertTrue(result.getBoolean("gatewayHostKeyVerified"));
        // The deadline fires after the connection was accepted.
        fixture.plugin.runConnectDeadline(captured.get());

        assertEquals("a late watchdog never claims an accepted connection", 0, watchdogDecisions.get());
        assertFalse(captured.get().deadlineExceeded.get());
        assertEquals("the live connection is preserved", 1, fixture.plugin.currentResourceSnapshot().connections);
        assertTrue("and still usable", captured.get().client.isConnected());
        assertFalse(captured.get().tunnel.socket().isClosed());
    }

    @Test public void cancellationStillWinsBeforeRegistration() throws Exception {
        fixture.plugin.beforeConnectRegistrationForTesting = attempt -> attempt.cancelled.set(true);
        try {
            fixture.connect("cancelled");
            fail("a cancelled attempt must not register");
        } catch (SshCapabilityPlugin.PluginFailure failure) {
            assertEquals("CANCELLED", failure.code);
        }
        assertEquals(0, fixture.plugin.currentResourceSnapshot().connections);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("interleaving latch timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
