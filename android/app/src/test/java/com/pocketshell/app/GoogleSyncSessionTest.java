package com.pocketshell.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.NoCredentialException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

/**
 * Issue #3020: the native half of Google sign-in and settings sync. The ID
 * token must travel only from the sign-in provider into the token store and
 * the Authorization header — never into a status, a rejection message, or a
 * request the sync API does not define.
 */
public final class GoogleSyncSessionTest {
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

    @Test public void signedOutReportsSignedOutAndSendsNothing() throws Exception {
        assertFalse(session.status().signedIn);
        try {
            session.request("GET", "main", null);
            fail("a request without a sign-in must be refused");
        } catch (SyncAuthException expected) {
            assertEquals(SyncAuthException.NOT_SIGNED_IN, expected.code);
        }
        assertTrue(transport.requests.isEmpty());
    }

    @Test public void signInStoresTheTokenNativelyAndReportsOnlyTheEmail() throws Exception {
        String token = token("sub-1", "phone@example.com", NOW + 3600, "SIGNINSECRET1");
        signIn.tokens.add(token);
        GoogleSyncSession.Status status = session.signIn();
        assertTrue(status.signedIn);
        assertEquals("phone@example.com", status.email);
        assertEquals(token, store.value);
        assertEquals(List.of(true), signIn.interactive);
        GoogleSyncSession.Status reread = session.status();
        assertTrue(reread.signedIn);
        assertEquals("phone@example.com", reread.email);
    }

    @Test public void requestsCarryTheTokenOnlyInTheAuthorizationHeader() throws Exception {
        String token = token("sub-1", "phone@example.com", NOW + 3600, "HEADERSECRET2");
        store.value = token;
        transport.responses.add(new GoogleSyncSession.Response(200, "{\"slot\":\"main\",\"version\":4,\"data\":\"e\"}"));
        GoogleSyncSession.Response response = session.request("GET", "main", null);
        assertEquals(200, response.status);
        assertEquals("{\"slot\":\"main\",\"version\":4,\"data\":\"e\"}", response.body);
        Sent sent = transport.requests.get(0);
        assertEquals("GET", sent.method);
        assertEquals(API + "/settings/main", sent.url);
        assertEquals("Bearer " + token, sent.headers.get("Authorization"));
        assertNull(sent.body);

        transport.responses.add(new GoogleSyncSession.Response(409, "{\"currentVersion\":5}"));
        GoogleSyncSession.Response conflict = session.request("PUT", "main", "{\"data\":\"x\",\"version\":4}");
        assertEquals(409, conflict.status);
        Sent put = transport.requests.get(1);
        assertEquals("PUT", put.method);
        assertEquals("application/json", put.headers.get("Content-Type"));
        assertEquals("{\"data\":\"x\",\"version\":4}", put.body);
        assertFalse("no token in a response the WebView receives", conflict.body.contains("HEADERSECRET2"));
    }

    @Test public void anExpiredTokenIsRenewedSilentlyBeforeTheRequest() throws Exception {
        store.value = token("sub-1", "phone@example.com", NOW + 30, "OLDSECRET3");
        String fresh = token("sub-1", "phone@example.com", NOW + 3600, "NEWSECRET3");
        signIn.tokens.add(fresh);
        transport.responses.add(new GoogleSyncSession.Response(404, ""));
        assertEquals(404, session.request("GET", "main", null).status);
        assertEquals(List.of(false), signIn.interactive);
        assertEquals(fresh, store.value);
        assertEquals("Bearer " + fresh, transport.requests.get(0).headers.get("Authorization"));
    }

    @Test public void a401IsRetriedExactlyOnceAfterASilentRenewal() throws Exception {
        store.value = token("sub-1", "phone@example.com", NOW + 3600, "FIRSTSECRET4");
        String fresh = token("sub-1", "phone@example.com", NOW + 3600, "SECONDSECRET4");
        signIn.tokens.add(fresh);
        transport.responses.add(new GoogleSyncSession.Response(401, "{\"message\":\"Unauthorized\"}"));
        transport.responses.add(new GoogleSyncSession.Response(401, "{\"message\":\"Unauthorized\"}"));
        GoogleSyncSession.Response response = session.request("GET", "main", null);
        assertEquals("a refused renewal is reported, not looped", 401, response.status);
        assertEquals(2, transport.requests.size());
        assertEquals("Bearer " + fresh, transport.requests.get(1).headers.get("Authorization"));
    }

    @Test public void aFailedRenewalSignsOutWithoutEchoingAnyToken() throws Exception {
        store.value = token("sub-1", "phone@example.com", NOW - 10, "EXPIREDSECRET5");
        signIn.failure = new SyncAuthException(SyncAuthException.UNAVAILABLE, "no authorized account");
        try {
            session.request("GET", "main", null);
            fail("an expired, unrenewable sign-in must be refused");
        } catch (SyncAuthException expected) {
            assertEquals(SyncAuthException.NOT_SIGNED_IN, expected.code);
            assertFalse(expected.getMessage().contains("EXPIREDSECRET5"));
        }
        assertNull("the unusable token is deleted", store.value);
        assertFalse(session.status().signedIn);
        assertTrue(transport.requests.isEmpty());
    }

    @Test public void aRenewalForAnotherGoogleAccountIsRefused() throws Exception {
        store.value = token("sub-1", "phone@example.com", NOW - 10, "ACCOUNTONE6");
        signIn.tokens.add(token("sub-2", "other@example.com", NOW + 3600, "ACCOUNTTWO6"));
        try {
            session.request("GET", "main", null);
            fail("a silent renewal must not switch accounts");
        } catch (SyncAuthException expected) {
            assertEquals(SyncAuthException.NOT_SIGNED_IN, expected.code);
        }
        assertNull(store.value);
        assertTrue(transport.requests.isEmpty());
    }

    @Test public void signOutDeletesTheTokenAndForgetsTheAccount() throws Exception {
        store.value = token("sub-1", "phone@example.com", NOW + 3600, "SIGNOUTSECRET7");
        session.signOut();
        assertNull(store.value);
        assertEquals(1, signIn.cleared);
        assertFalse(session.status().signedIn);
    }

    @Test public void onlyTheSyncApiRoutesCanBeRequested() throws Exception {
        store.value = token("sub-1", "phone@example.com", NOW + 3600, "ROUTESECRET8");
        String oversized = "x".repeat(GoogleSyncConfig.MAX_REQUEST_BODY_BYTES + 1);
        String[][] refused = {
                {"DELETE", "main", null},
                {"POST", "main", "{}"},
                {"GET", "../auth/google/token", null},
                {"GET", "main", "{}"},
                {"PUT", "main", null},
                {"PUT", "main", oversized},
                {"PUT", "", "{}"},
                {"PUT", null, "{}"},
        };
        for (String[] request : refused) {
            try {
                session.request(request[0], request[1], request[2]);
                fail("refuse " + request[0] + " " + request[1]);
            } catch (SyncAuthException expected) {
                assertEquals(SyncAuthException.INVALID_REQUEST, expected.code);
            }
        }
        assertTrue(transport.requests.isEmpty());
        transport.responses.add(new GoogleSyncSession.Response(200, "{\"sub\":\"sub-1\"}"));
        session.request("GET", null, null);
        assertEquals(API + "/me", transport.requests.get(0).url);
    }

    @Test public void aTransportFailureNeverEchoesTheTransportMessage() throws Exception {
        String token = token("sub-1", "phone@example.com", NOW + 3600, "TRANSPORTSECRET9");
        store.value = token;
        transport.failure = new IOException("connect failed with Authorization: Bearer " + token);
        try {
            session.request("GET", "main", null);
            fail("a transport failure must be reported");
        } catch (SyncAuthException expected) {
            assertEquals(SyncAuthException.NETWORK, expected.code);
            assertFalse(expected.getMessage().contains("TRANSPORTSECRET9"));
            assertFalse(expected.getMessage().contains("Bearer"));
        }
    }

    @Test public void anUnreadableStoredRecordIsASignedOutState() throws Exception {
        store.value = "not-a-jwt";
        assertFalse(session.status().signedIn);
        assertNull(store.value);
    }

    @Test public void malformedTokensAreRejectedWithoutEchoingThem() {
        for (String bad : new String[] {"", "a.b", "header.!!!.sig", "h." + b64("{\"email\":\"x@y\"}") + ".SECRETSIG",
                "h." + b64("{\"sub\":\"s\"}") + ".NOEXPSECRET"}) {
            try {
                GoogleIdToken.parse(bad);
                fail("malformed token accepted: " + bad.length());
            } catch (SyncAuthException expected) {
                assertEquals(SyncAuthException.FAILED, expected.code);
                assertFalse(expected.getMessage().contains("SECRETSIG"));
                assertFalse(expected.getMessage().contains("NOEXPSECRET"));
            }
        }
    }

    @Test public void tokenToStringNamesTheAccountNotTheToken() throws Exception {
        GoogleIdToken parsed = GoogleIdToken.parse(token("sub-1", "phone@example.com", NOW, "TOSTRINGSECRET"));
        assertFalse(parsed.toString().contains("TOSTRINGSECRET"));
        assertTrue(parsed.toString().contains("sub-1"));
    }

    @Test public void credentialManagerFailuresBecomeActionableCodes() {
        SyncAuthException cancelled = CredentialManagerSignIn.describe(new GetCredentialCancellationException("x"),
                "com.pocketshell.app.preview");
        assertEquals(SyncAuthException.CANCELLED, cancelled.code);
        SyncAuthException unavailable = CredentialManagerSignIn.describe(new NoCredentialException("none"),
                "com.pocketshell.app.preview");
        assertEquals(SyncAuthException.UNAVAILABLE, unavailable.code);
        assertTrue("the message names the package to register", unavailable.getMessage().contains("com.pocketshell.app.preview"));
    }

    private static String token(String subject, String email, long exp, String signature) {
        return b64("{\"alg\":\"RS256\"}") + "." + b64("{\"sub\":\"" + subject + "\",\"email\":\"" + email
                + "\",\"exp\":" + exp + ",\"aud\":\"" + GoogleSyncConfig.SERVER_CLIENT_ID + "\"}") + "." + signature;
    }

    private static String b64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static final class MemoryStore implements GoogleSyncSession.TokenStore {
        String value;

        @Override public String read() {
            return value;
        }

        @Override public void write(String token) {
            value = token;
        }

        @Override public void clear() {
            value = null;
        }
    }

    private static final class ScriptedSignIn implements GoogleSyncSession.SignInProvider {
        final Deque<String> tokens = new ArrayDeque<>();
        final List<Boolean> interactive = new ArrayList<>();
        SyncAuthException failure;
        int cleared;

        @Override public String obtainIdToken(boolean interactiveRequest) throws SyncAuthException {
            interactive.add(interactiveRequest);
            if (failure != null) throw failure;
            if (tokens.isEmpty()) throw new SyncAuthException(SyncAuthException.FAILED, "no scripted token");
            return tokens.removeFirst();
        }

        @Override public void clearCredentialState() {
            cleared += 1;
        }
    }

    private static final class Sent {
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

    private static final class RecordingTransport implements GoogleSyncSession.Transport {
        final List<Sent> requests = new ArrayList<>();
        final Deque<GoogleSyncSession.Response> responses = new ArrayDeque<>();
        IOException failure;

        @Override public GoogleSyncSession.Response send(String method, String url, Map<String, String> headers, String body)
                throws IOException {
            if (failure != null) throw failure;
            requests.add(new Sent(method, url, headers, body));
            return responses.removeFirst();
        }
    }
}
