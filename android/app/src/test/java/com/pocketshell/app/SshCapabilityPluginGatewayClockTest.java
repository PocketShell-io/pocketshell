package com.pocketshell.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.getcapacitor.JSObject;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * #3086 slice 2 review follow-up: the whole-connect deadline of a gateway
 * dial is measured on a monotonic clock. A wall clock that jumps while a dial
 * is in flight (NTP sync, the user changing the time, a time-zone/carrier
 * update) must neither kill a healthy dial at once nor stretch a stalled one
 * past its budget.
 *
 * <p>Each case has a JUnit timeout: a deadline back on the wall clock must
 * FAIL fast here, not hang the unit gate (a backward jump stretches it by an
 * hour).
 *
 * <p>The plugin's gateway wall clock is replaced with one that jumps right
 * after its first read (on the old code: the dial plan's deadline), and
 * every case drives the REAL
 * {@link SshCapabilityPlugin#connectNow} through the loopback gateway.
 */
public final class SshCapabilityPluginGatewayClockTest {
    private static final long HOUR_MS = 3_600_000L;

    private GatewayDialFixture fixture;

    @Before public void setUp() throws Exception {
        fixture = new GatewayDialFixture();
        // The routing-token cache keeps real wall time (arrival + expires_in;
        // the gateway judges the real exp): only the DEADLINE is under test.
        fixture.plugin.useGatewayRoutingTokensForTesting(new GatewayRoutingTokens(System::currentTimeMillis));
        fixture.pairings.pair(GatewayDialFixture.SUBJECT, fixture.serverUrl(), GatewayDialFixture.DEVICE,
                fixture.hostKeyLine(), GatewayDialFixture.HANDLE);
    }

    @After public void tearDown() throws Exception {
        fixture.close();
    }

    /** Real wall time for the first read, then shifted by {@code jumpMs}. */
    private static java.util.function.LongSupplier jumpingWallClock(long jumpMs) {
        AtomicInteger reads = new AtomicInteger();
        AtomicLong offset = new AtomicLong();
        return () -> {
            if (reads.incrementAndGet() > 1) offset.set(jumpMs);
            return System.currentTimeMillis() + offset.get();
        };
    }

    @Test(timeout = 30_000) public void aWallClockJumpForwardDoesNotExpireAHealthyDial() throws Exception {
        fixture.plugin.useGatewayWallClockForTesting(jumpingWallClock(HOUR_MS));
        JSObject result = fixture.connect("clock-forward");
        assertTrue("the dial completes on its real budget", result.getBoolean("gatewayHostKeyVerified"));
        assertTrue("the host accepted the phone key", fixture.host.acceptedLogins.get() > 0);
    }

    @Test(timeout = 30_000) public void aWallClockJumpBackwardCannotStretchAStalledDialPastItsBudget() throws Exception {
        fixture.plugin.useGatewayWallClockForTesting(jumpingWallClock(-HOUR_MS));
        fixture.gateway.script(GatewayDialFixture.Mode.SILENT, 0, null);
        JSObject options = fixture.connectOptions("clock-backward").put("connectTimeoutMs", 5_000);
        long started = System.nanoTime();
        try {
            fixture.plugin.connectNow(options);
            throw new AssertionError("a gateway that never answers cannot connect");
        } catch (SshCapabilityPlugin.PluginFailure failure) {
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            assertEquals("the whole-connect deadline fired", "CONNECT_TIMEOUT", failure.code);
            assertTrue("the 5 s budget held under a backward jump: " + elapsedMs + " ms",
                    elapsedMs >= 4_000 && elapsedMs < 15_000);
        }
    }
}
