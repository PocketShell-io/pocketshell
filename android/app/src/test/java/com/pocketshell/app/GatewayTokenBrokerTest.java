package com.pocketshell.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * Issue #3060: the native twin of the web client's {@code gatewayToken.ts}
 * broker contract. Every non-2xx, unreadable, mis-shaped, echoed, unbounded,
 * or expired answer is refused — the caller must treat "no broker token" as
 * "no gateway dial", never fall back to the Google token.
 */
public final class GatewayTokenBrokerTest {
    private static final String GOOGLE_TOKEN = "aaa.SECRETGOOGLECLAIMS.bbb";
    private static final long NOW = 1_800_000_000_000L;

    private final GatewayTokenBroker broker = new GatewayTokenBroker(() -> NOW);

    private static GoogleSyncSession.GatewayBrokerExchange exchange(int status, String body) {
        return new GoogleSyncSession.GatewayBrokerExchange(
                new GoogleSyncSession.Response(status, body), GOOGLE_TOKEN);
    }

    /** A shape-valid mint expiring 4 minutes after NOW. */
    private static String mint(long expiresAtSeconds, long expiresIn) {
        return "{\"token\":\"header.SCOPEDROUTINGCLAIMS.signature\",\"token_type\":\"Bearer\","
                + "\"expires_at\":" + expiresAtSeconds + ",\"expires_in\":" + expiresIn + "}";
    }

    @Test public void parsesAStrictMint() throws Exception {
        // 240s TTL stamped at arrival.
        GatewayTokenBroker.RoutingToken token = broker.parse(exchange(200, mint(NOW / 1000 + 240, 240)));
        assertEquals("header.SCOPEDROUTINGCLAIMS.signature", token.token);
        assertEquals(NOW + 240_000, token.expiresAtEpochMs);
    }

    @Test public void httpFailuresSurfaceTypedCodesWithoutBodyText() {
        for (int[] attempt : new int[][] {{401, 0}, {403, 0}, {500, 0}, {502, 0}}) {
            try {
                broker.parse(exchange(attempt[0], "{\"error\":\"host_offline\",\"secret\":\"GOOGLETOKENMATERIAL\"}"));
                fail("non-2xx must refuse");
            } catch (GatewayTokenBroker.GatewayBrokerException expected) {
                if (attempt[0] == 401 || attempt[0] == 403) {
                    assertEquals(GatewayTokenBroker.GatewayBrokerException.SIGN_IN_REJECTED, expected.code);
                } else {
                    assertEquals(GatewayTokenBroker.GatewayBrokerException.UNAVAILABLE, expected.code);
                }
                assertFalse(expected.getMessage().contains("GOOGLETOKENMATERIAL"));
                assertFalse(expected.getMessage().contains("host_offline"));
            }
        }
    }

    private static void assertBadResponse(GatewayTokenBroker broker, String body) {
        try {
            broker.parse(exchange(200, body));
            fail("mis-shaped answer must refuse: " + body);
        } catch (GatewayTokenBroker.GatewayBrokerException expected) {
            assertEquals(GatewayTokenBroker.GatewayBrokerException.BAD_RESPONSE, expected.code);
        }
    }

    @Test public void misShapedAnswersRefuse() {
        assertBadResponse(broker, "not json");
        assertBadResponse(broker, "42");
        assertBadResponse(broker, "[]");
        assertBadResponse(broker, "{}");
        assertBadResponse(broker, "{\"token\":\"no-dots\",\"token_type\":\"Bearer\",\"expires_at\":1,\"expires_in\":1}");
        assertBadResponse(broker, "{\"token\":\"a.b.c\",\"token_type\":\"bearer\",\"expires_at\":1,\"expires_in\":1}");
        // token_type is compared exactly; a number never reads as "Bearer".
        assertBadResponse(broker, "{\"token\":\"a.b.c\",\"token_type\":1,\"expires_at\":1,\"expires_in\":1}");
        assertBadResponse(broker, "{\"token\":\"a.b.c\",\"token_type\":\"Bearer\"}");
        assertBadResponse(broker, "{\"token\":\"a.b.c\",\"token_type\":\"Bearer\",\"expires_at\":\"1800000240\",\"expires_in\":240}");
        assertBadResponse(broker, "{\"token\":\"a.b.c\",\"token_type\":\"Bearer\",\"expires_at\":1800000240}");
        assertBadResponse(broker, "{\"token\":\"a.b.c\",\"token_type\":\"Bearer\",\"expires_at\":1800000240,\"expires_in\":\"240\"}");
        assertBadResponse(broker, "{\"token\":\"a.b.c\",\"token_type\":\"Bearer\",\"expires_at\":-5,\"expires_in\":240}");
        assertBadResponse(broker, "{\"token\":\"a.b.c\",\"token_type\":\"Bearer\",\"expires_at\":1800000240,\"expires_in\":3601}");
    }

    @Test public void anEchoedGoogleBearerIsRefused() {
        String echo = "{\"token\":\"" + GOOGLE_TOKEN + "\",\"token_type\":\"Bearer\","
                + "\"expires_at\":" + (NOW / 1000 + 240) + ",\"expires_in\":240}";
        try {
            broker.parse(exchange(200, echo));
            fail("an endpoint echoing the Google bearer is not the broker");
        } catch (GatewayTokenBroker.GatewayBrokerException expected) {
            assertEquals(GatewayTokenBroker.GatewayBrokerException.BAD_RESPONSE, expected.code);
        }
    }

    @Test public void expiriesMustAgreeAndStayBounded() {
        // expires_in stamped from a clock 60s away from expires_at: hostile or broken.
        assertBadResponse(broker, mint(NOW / 1000 + 240, 180));
        // Already expired at arrival.
        assertBadResponse(broker, mint(NOW / 1000 - 10, 240));
        // Outlives the 5 min + skew contract.
        assertBadResponse(broker, mint(NOW / 1000 + 500, 500));
    }

    @Test public void theAgreementAllowanceToleratesSmallClockSpread() throws Exception {
        GatewayTokenBroker.RoutingToken token = broker.parse(exchange(200, mint(NOW / 1000 + 240, 242)));
        assertNotEquals(0, token.expiresAtEpochMs);
        assertTrue(token.expiresAtEpochMs > NOW);
    }
}
