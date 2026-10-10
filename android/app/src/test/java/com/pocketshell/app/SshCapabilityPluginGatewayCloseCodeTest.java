package com.pocketshell.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * #3086 slice 2: a gateway refusal reaches core with its close code intact.
 *
 * <p>Core's contract (core docs/SYNC.md, {@code classifyGatewayDialFailure}):
 * the platform rejects {@code connect()} with code {@code GATEWAY_CLOSED}
 * and {@code data.gatewayCloseCode} = the gateway's close code, and maps a
 * handshake {@code error} frame to its documented close code. Core's retry
 * matrix depends on it: 4400/4401/4403/4404 end the reconnect ladder, while
 * 4408/4429/4503 back off. 7b882759e routed every GatewayTunnelException
 * through KeyHandleConnectFailures.classify, which collapsed all of them into
 * CONNECTION_LOST — so a revoked sign-in retried like a dropped Wi-Fi.
 *
 * <p>Every case drives the REAL {@link SshCapabilityPlugin#connectNow}
 * through a loopback gateway, not a mapping function in isolation.
 */
public final class SshCapabilityPluginGatewayCloseCodeTest {
    private static final int[] GATEWAY_CLOSE_CODES = {4400, 4401, 4403, 4404, 4408, 4429, 4503, 4000};

    private GatewayDialFixture fixture;

    @Before public void setUp() throws Exception {
        fixture = new GatewayDialFixture();
        fixture.pairings.pair(GatewayDialFixture.SUBJECT, fixture.serverUrl(), GatewayDialFixture.DEVICE,
                fixture.hostFingerprint(), GatewayDialFixture.HANDLE);
    }

    @After public void tearDown() throws Exception {
        fixture.close();
    }

    @Test public void everyCloseCodeBeforeReadyReachesCoreAsGatewayClosed() throws Exception {
        for (int code : GATEWAY_CLOSE_CODES) {
            fixture.gateway.script(GatewayDialFixture.Mode.CLOSE, code, null);
            SshCapabilityPlugin.PluginFailure failure = fixture.connectExpectingFailure("close-" + code);
            assertGatewayClosed("close " + code + " before ready", failure, code);
        }
        assertEquals("the SSH host is never reached when the gateway refuses",
                0, fixture.host.userauthAttempts.get());
    }

    @Test public void handshakeErrorFramesMapToTheirDocumentedCloseCodes() throws Exception {
        // The gateway's own vocabulary (pocketshell-gateway internal/tunnel
        // service.go reject(...) pairs, docs/tunnel.md FH-1). The fixture
        // sends ONLY the error frame and holds the socket open, so the code
        // can come from nowhere but the frame.
        Map<String, Integer> frames = new LinkedHashMap<>();
        frames.put("protocol", 4400);
        frames.put("unauthorized", 4401);
        frames.put("forbidden", 4403);
        frames.put("revoked", 4403);
        frames.put("not_found", 4404);
        frames.put("timeout", 4408);
        frames.put("quota", 4429);
        frames.put("host_offline", 4503);
        for (Map.Entry<String, Integer> frame : frames.entrySet()) {
            fixture.gateway.script(GatewayDialFixture.Mode.ERROR_FRAME_HOLD, 0, frame.getKey());
            long start = System.currentTimeMillis();
            SshCapabilityPlugin.PluginFailure failure = fixture.connectExpectingFailure("frame-" + frame.getKey());
            assertGatewayClosed("error frame " + frame.getKey(), failure, frame.getValue());
            assertTrue("a recognized error frame refuses at once, not at the deadline",
                    System.currentTimeMillis() - start < 10_000);
        }
    }

    @Test public void anUnrecognizedErrorFrameTakesTheGatewaysCloseCode() throws Exception {
        // `internal` has no documented close code; `challenge` is the agent
        // route's stage-dependent code (4408 or 4401). Neither is guessed:
        // the close frame the gateway sends next decides.
        fixture.gateway.script(GatewayDialFixture.Mode.ERROR_FRAME_THEN_CLOSE, 4000, "internal");
        assertGatewayClosed("unknown error frame + close 4000",
                fixture.connectExpectingFailure("internal"), 4000);
        fixture.gateway.script(GatewayDialFixture.Mode.ERROR_FRAME_THEN_CLOSE, 4408, "challenge");
        assertGatewayClosed("challenge frame + close 4408",
                fixture.connectExpectingFailure("challenge"), 4408);
    }

    @Test public void aCloseAfterReadyDuringTheSshHandshakeKeepsItsCode() throws Exception {
        // ready, then the gateway drops the route (host went offline) before
        // sshj finishes its identification exchange.
        fixture.gateway.script(GatewayDialFixture.Mode.READY_THEN_CLOSE, 4503, null);
        assertGatewayClosed("close 4503 after ready", fixture.connectExpectingFailure("ready-then-close"), 4503);
    }

    @Test public void aGatewayThatCannotBeReachedIsNotAGatewayVerdict() throws Exception {
        // Negative control: no close code was ever delivered, so core must
        // keep its default (retryable) handling rather than read a verdict.
        int port = fixture.gateway.getPort();
        fixture.gateway.stop(0);
        com.getcapacitor.JSObject options = fixture.connectOptions("unreachable");
        options.put("gateway", new com.getcapacitor.JSObject()
                .put("serverUrl", "ws://127.0.0.1:" + port).put("deviceId", GatewayDialFixture.DEVICE));
        try {
            fixture.plugin.connectNow(options);
            throw new AssertionError("an unreachable gateway cannot connect");
        } catch (SshCapabilityPlugin.PluginFailure failure) {
            assertNotEquals("GATEWAY_CLOSED", failure.code);
            assertFalse(failure.data != null && failure.data.has("gatewayCloseCode"));
        }
    }

    private static void assertGatewayClosed(String label, SshCapabilityPlugin.PluginFailure failure, int code) {
        assertEquals(label + ": native code", "GATEWAY_CLOSED", failure.code);
        assertTrue(label + ": close code data present", failure.data != null && failure.data.has("gatewayCloseCode"));
        assertEquals(label + ": close code", code, failure.data.optInt("gatewayCloseCode", -1));
        assertFalse(label + ": no token material in the message",
                failure.getMessage().contains("ROUTING-TOKEN") || failure.getMessage().contains("ID-TOKEN"));
    }
}
