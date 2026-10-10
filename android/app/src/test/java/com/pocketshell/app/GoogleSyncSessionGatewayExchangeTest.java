package com.pocketshell.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

/**
 * Issue #3060: the dedicated gateway broker exchange on GoogleSyncSession —
 * one empty-body POST /gateway/token at the CONFIGURED sync base, carrying
 * the Google bearer in that one header only. The token is never resolved to
 * a caller; the exchange answer exposes only the bearer-echo check.
 */
public final class GoogleSyncSessionGatewayExchangeTest {
    private static final String API = "https://sync.example.test";
    private static final long NOW = 1_800_000_000L;

    private MemoryStore store;
    private ScriptedSignIn signIn;
    private RecordingTransport transport;
    private long now;
    private GoogleSyncSession session;

    @Before public void setUp() {
        store = new MemoryStore();
        signIn = new ScriptedSignIn();
        transport = new RecordingTransport();
        now = NOW;
        session = new GoogleSyncSession(store, signIn, transport, API + "/", () -> now);
    }

    @Test public void theSubjectComesFromTheStoredAccountWithoutRenewal() throws Exception {
        try {
            session.currentAccountSubject();
            fail("a signed-out phone has no pairing namespace");
        } catch (SyncAuthException expected) {
            assertEquals(SyncAuthException.NOT_SIGNED_IN, expected.code);
        }
        String token = token("sub-77", "phone@example.com", NOW + 3600, "SUBJECTSECRET1");
        store.value = token;
        assertEquals("sub-77", session.currentAccountSubject());
        assertTrue("a subject read never mints or renews", transport.requests.isEmpty());
    }

    @Test public void theExchangeIsAFixedEmptyPostAtTheConfiguredBase() throws Exception {
        String google = token("sub-77", "phone@example.com", NOW + 3600, "EXCHANGESECRET2");
        store.value = google;
        transport.responses.add(new GoogleSyncSession.Response(200,
                "{\"token\":\"hdr.SCOPED.sig\",\"token_type\":\"Bearer\",\"expires_at\":"
                        + (NOW + 240) + ",\"expires_in\":240}"));
        GoogleSyncSession.GatewayBrokerExchange exchange = session.gatewayTokenExchange();
        assertEquals(200, exchange.response.status);
        assertEquals(1, transport.requests.size());
        Sent sent = transport.requests.get(0);
        assertEquals("the route is fixed: configured base, /gateway/token, nothing else",
                API + "/gateway/token", sent.url);
        assertEquals("POST", sent.method);
        assertEquals("the body is empty — the broker mints fixed claims",
                "", sent.body);
        assertEquals("Bearer " + google, sent.headers.get("Authorization"));
    }

    @Test public void theExchangeAnswersWithTheEchoCheckOnly() throws Exception {
        String google = token("sub-77", "phone@example.com", NOW + 3600, "ECHOSECRET3");
        store.value = google;
        transport.responses.add(new GoogleSyncSession.Response(200,
                "{\"token\":\"" + google + "\",\"token_type\":\"Bearer\",\"expires_at\":"
                        + (NOW + 240) + ",\"expires_in\":240}"));
        GoogleSyncSession.GatewayBrokerExchange exchange = session.gatewayTokenExchange();
        assertTrue("an echo of the bearer is detectable for the strict parser",
                exchange.bearerEchoedBy(google));
        assertFalse(exchange.bearerEchoedBy("hdr.SCOPED.sig"));
    }

    @Test public void anExpiredStoredTokenRenewsSilentlyForTheSameAccount() throws Exception {
        String expired = token("sub-77", "phone@example.com", NOW - 10, "OLDBEARER");
        store.value = expired;
        String renewed = token("sub-77", "phone@example.com", NOW + 3600, "NEWBEARER");
        signIn.tokens.add(renewed);
        transport.responses.add(new GoogleSyncSession.Response(200, "{\"token\":\"hdr.SCOPED.sig\","
                + "\"token_type\":\"Bearer\",\"expires_at\":" + (NOW + 240) + ",\"expires_in\":240}"));
        session.gatewayTokenExchange();
        Sent sent = transport.requests.get(0);
        assertEquals("Bearer " + renewed, sent.headers.get("Authorization"));
        assertFalse("the old bearer never reaches the wire", sent.headers.get("Authorization").contains("OLDBEARER"));
    }

    @Test public void aBroker401RenewsSilentlyAndRetriesExactlyOnce() throws Exception {
        // #3086: same rule as a sync request — the ID token can age out
        // between the local expiry check and the broker's authorizer.
        String stale = token("sub-77", "phone@example.com", NOW + 3600, "STALEBEARER");
        store.value = stale;
        String renewed = token("sub-77", "phone@example.com", NOW + 3600, "RENEWEDBEARER");
        signIn.tokens.add(renewed);
        transport.responses.add(new GoogleSyncSession.Response(401, "{}"));
        transport.responses.add(new GoogleSyncSession.Response(200, "{\"token\":\"hdr.SCOPED.sig\","
                + "\"token_type\":\"Bearer\",\"expires_at\":" + (NOW + 240) + ",\"expires_in\":240}"));
        GoogleSyncSession.GatewayBrokerExchange exchange = session.gatewayTokenExchange();
        assertEquals(200, exchange.response.status);
        assertEquals(2, transport.requests.size());
        assertEquals("Bearer " + renewed, transport.requests.get(1).headers.get("Authorization"));
        assertTrue("the echo check follows the bearer actually sent", exchange.bearerEchoedBy(renewed));

        // A second 401 is the broker's answer, not retried again.
        signIn.tokens.add(token("sub-77", "phone@example.com", NOW + 3600, "THIRDBEARER"));
        transport.requests.clear();
        transport.responses.add(new GoogleSyncSession.Response(401, "{}"));
        transport.responses.add(new GoogleSyncSession.Response(401, "{}"));
        assertEquals(401, session.gatewayTokenExchange().response.status);
        assertEquals(2, transport.requests.size());
    }

    @Test public void aDifferentSubjectOnRenewalDropsTheSignIn() throws Exception {
        store.value = token("sub-77", "phone@example.com", NOW - 10, "OLDBEARER");
        signIn.tokens.add(token("sub-other", "other@example.com", NOW + 3600, "OTHERSUB"));
        try {
            session.gatewayTokenExchange();
            fail("an account switch must not mint a credential");
        } catch (SyncAuthException expected) {
            assertEquals(SyncAuthException.NOT_SIGNED_IN, expected.code);
        }
        assertTrue(transport.requests.isEmpty());
    }

    private static String token(String subject, String email, long expiresAt, String secret) throws org.json.JSONException {
        String claims = new org.json.JSONObject()
                .put("sub", subject).put("email", email).put("exp", expiresAt).toString();
        return "hdr." + java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(claims.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "." + secret;
    }

    static final class MemoryStore implements GoogleSyncSession.TokenStore {
        String value;

        @Override public String read() { return value; }

        @Override public void write(String token) { value = token; }

        @Override public void clear() { value = null; }
    }

    static final class ScriptedSignIn implements GoogleSyncSession.SignInProvider {
        final Deque<String> tokens = new ArrayDeque<>();

        @Override public String obtainIdToken(boolean interactive) {
            return tokens.poll();
        }

        @Override public void clearCredentialState() { }
    }

    static final class Sent {
        final String method;
        final String url;
        final Map<String, String> headers;
        final String body;

        Sent(String method, String url, Map<String, String> headers, String body) {
            this.method = method;
            this.url = url;
            this.headers = headers;
            this.body = body;
        }
    }

    static final class RecordingTransport implements GoogleSyncSession.Transport {
        final List<Sent> requests = new ArrayList<>();
        final Deque<GoogleSyncSession.Response> responses = new ArrayDeque<>();

        @Override
        public GoogleSyncSession.Response send(String method, String url, Map<String, String> headers, String body) {
            requests.add(new Sent(method, url, headers, body));
            return responses.isEmpty()
                    ? new GoogleSyncSession.Response(500, "{}")
                    : responses.removeFirst();
        }
    }
}
