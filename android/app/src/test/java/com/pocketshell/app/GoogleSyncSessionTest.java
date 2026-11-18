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

    @Test public void accountIdRequiresBackendVerifiedMatchingSubjectAndSettingsListKeepsBearerNative() throws Exception {
        store.value = token("sub-1", "phone@example.com", NOW + 3600, "IDENTITY");
        transport.responses.add(new GoogleSyncSession.Response(200, "{\"sub\":\"sub-1\"}"));
        assertEquals("google:sub-1", session.verifiedAccountId());
        assertEquals(API + "/me", transport.requests.get(0).url);
        transport.responses.add(new GoogleSyncSession.Response(200, "{\"slots\":[]}"));
        assertEquals(200, session.listSettings().status);
        assertEquals(API + "/settings", transport.requests.get(1).url);
        assertEquals("Bearer " + store.value, transport.requests.get(1).headers.get("Authorization"));
        now = NOW + 61; // past the #3137 verified-identity cache window, so the backend is asked again
        transport.responses.add(new GoogleSyncSession.Response(200, "{\"sub\":\"other\"}"));
        try { session.verifiedAccountId(); fail("foreign subject"); } catch (SyncAuthException expected) { assertEquals(SyncAuthException.NOT_SIGNED_IN, expected.code); }
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

    /** #3189: an expired token ends the session at the request; no silent renewal, no sheet. */
    @Test public void anExpiredTokenSignsOutBeforeTheRequestWithoutAProviderCall() throws Exception {
        store.value = token("sub-1", "phone@example.com", NOW + 30, "OLDSECRET3");
        try { session.request("GET", "main", null); fail("an expired token cannot be spent"); }
        catch (SyncAuthException expected) {
            assertEquals(SyncAuthException.NOT_SIGNED_IN, expected.code);
            assertEquals(GoogleSyncSession.SIGNED_OUT_MESSAGE, expected.getMessage());
        }
        assertTrue("no Credential Manager call", signIn.interactive.isEmpty());
        assertTrue("the expired bearer never reaches the wire", transport.requests.isEmpty());
        assertFalse(session.status().signedIn);
    }

    /** #3189: the server refusing the token (revoked, deleted, expired) ends the session; one request, no renewal. */
    @Test public void a401EndsTheSessionWithoutARenewalOrRetry() throws Exception {
        store.value = token("sub-1", "phone@example.com", NOW + 3600, "FIRSTSECRET4");
        signIn.tokens.add(token("sub-1", "phone@example.com", NOW + 3600, "SECONDSECRET4"));
        transport.responses.add(new GoogleSyncSession.Response(401, "{\"message\":\"Unauthorized\"}"));
        try { session.request("GET", "main", null); fail("a refused token is a lost session"); }
        catch (SyncAuthException expected) { assertEquals(SyncAuthException.NOT_SIGNED_IN, expected.code); }
        assertEquals(1, transport.requests.size());
        assertTrue(signIn.interactive.isEmpty());
        assertFalse(session.status().signedIn);
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

    /** #3189: an expired token never reaches the provider, so nothing can loop on its failures. */
    @Test public void anExpiredTokenNeverReachesTheProviderWhateverItWouldAnswer() throws Exception {
        for (String code : List.of(SyncAuthException.CANCELLED, SyncAuthException.NETWORK)) {
            setUp();
            store.value = token("sub-1", "phone@example.com", NOW - 10, "TRANSIENT");
            signIn.failure = new SyncAuthException(code, "safe transient error");
            try { session.request("GET", "main", null); fail("expired"); }
            catch (SyncAuthException expected) { assertEquals(SyncAuthException.NOT_SIGNED_IN, expected.code); }
            assertTrue(signIn.interactive.isEmpty());
            assertTrue(transport.requests.isEmpty());
        }
    }

    @Test public void failedRenewalInvalidatesMemoryEvenIfDeletingCredentialFailsAndLoginRecovers() throws Exception {
        store.value = token("sub-1", "phone@example.com", NOW - 10, "STALE");
        store.failClear = true;
        signIn.failure = new SyncAuthException(SyncAuthException.UNAVAILABLE, "no credential");
        assertTrue(session.status().signedIn);
        try { session.request("GET", "main", null); fail("renewal"); }
        catch (SyncAuthException expected) { assertEquals(SyncAuthException.NOT_SIGNED_IN, expected.code); }
        assertFalse(session.status().signedIn);
        try { session.currentAccountSubject(); fail("invalidated account"); }
        catch (SyncAuthException expected) { assertEquals(SyncAuthException.NOT_SIGNED_IN, expected.code); }
        signIn.failure = null;
        signIn.tokens.add(token("sub-1", "phone@example.com", NOW + 3600, "FRESH"));
        assertTrue(session.signIn().signedIn);
        assertEquals("sub-1", session.currentAccountSubject());
    }

    @Test public void twoPluginSessionsObserveNormalSignInAfterGatewayRenewalLossWithoutRecreation() throws Exception {
        GoogleSyncSession gateway = new GoogleSyncSession(store, signIn, transport, API, () -> now);
        store.value = token("sub-1", "phone@example.com", NOW - 10, "OLD");
        signIn.failure = new SyncAuthException(SyncAuthException.UNAVAILABLE, "no credential");
        try { gateway.gatewayTokenExchange(); fail("renewal must fail"); }
        catch (SyncAuthException expected) { assertEquals(SyncAuthException.NOT_SIGNED_IN, expected.code); }
        assertFalse(gateway.status().signedIn);
        signIn.failure = null;
        signIn.tokens.add(token("sub-1", "phone@example.com", NOW + 3600, "FRESH"));
        assertTrue(session.signIn().signedIn);
        assertEquals("sub-1", gateway.currentAccountSubject());
        assertTrue(gateway.status().signedIn);
    }

    @Test public void failedCredentialClearInvalidatesEverySessionUntilSuccessfulNormalSignIn() throws Exception {
        GoogleSyncSession gateway = new GoogleSyncSession(store, signIn, transport, API, () -> now);
        GatewaySyncSession pairing = new GatewaySyncSession(gateway);
        GatewaySyncSession sshBroker = new GatewaySyncSession(new GoogleSyncSession(store, signIn, transport, API, () -> now));
        store.value = token("sub-1", "phone@example.com", NOW - 10, "OLD"); store.failClear = true;
        signIn.failure = new SyncAuthException(SyncAuthException.UNAVAILABLE, "no credential");
        try { gateway.gatewayTokenExchange(); fail("renewal must fail"); }
        catch (SyncAuthException expected) { assertEquals(SyncAuthException.NOT_SIGNED_IN, expected.code); }
        assertFalse("another plugin must not resurrect the undeleted credential", session.status().signedIn);
        assertFalse(gateway.status().signedIn);
        for (GatewaySyncSession consumer : List.of(pairing, sshBroker)) {
            try { consumer.subject(); fail("gateway consumers must refuse the undeleted old identity"); }
            catch (SyncAuthException expected) { assertEquals(SyncAuthException.NOT_SIGNED_IN, expected.code); }
        }
        signIn.failure = null;
        signIn.tokens.add(token("sub-2", "other@example.com", NOW + 3600, "OTHER"));
        assertTrue(session.signIn().signedIn);
        assertEquals("sub-2", gateway.currentAccountSubject());
        assertEquals("other@example.com", gateway.status().email);
        assertEquals("sub-2", pairing.subject());
        assertEquals("sub-2", sshBroker.subject());
        transport.responses.add(new GoogleSyncSession.Response(200, "{}"));
        assertEquals(200, sshBroker.exchange().response.status);
        assertFalse("the former account must not be inherited", "sub-1".equals(pairing.subject()));
    }

    /**
     * #3189: a gateway worker that finds the token expired ends the session without any provider
     * call (no "Signing you in" sheet from a background reconnect), and the user's later explicit
     * sign-in is the one credential every plugin then reads.
     */
    @Test public void anExpiredGatewayExchangeSignsOutWithoutAProviderCallAndALaterLoginWins() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        GoogleSyncSession.SignInProvider provider = new GoogleSyncSession.SignInProvider() {
            public String obtainIdToken() {
                calls.incrementAndGet();
                return token("sub-2", "other@example.com", NOW + 3600, "NEW");
            }
            public void clearCredentialState() { }
        };
        GoogleSyncSession gateway = new GoogleSyncSession(store, provider, transport, API, () -> now);
        GoogleSyncSession interactive = new GoogleSyncSession(store, provider, transport, API, () -> now);
        store.value = token("sub-1", "phone@example.com", NOW - 10, "OLD");
        try { gateway.gatewayTokenExchange(); fail("an expired token cannot reach the broker"); }
        catch (SyncAuthException expected) { assertEquals(SyncAuthException.NOT_SIGNED_IN, expected.code); }
        assertEquals("no Credential Manager call from a background exchange", 0, calls.get());
        assertTrue(transport.requests.isEmpty());
        assertFalse(interactive.status().signedIn);
        assertTrue(interactive.signIn().signedIn);
        assertEquals(1, calls.get());
        assertEquals("sub-2", gateway.currentAccountSubject());
        assertEquals("sub-2", interactive.currentAccountSubject());
    }

    @Test public void failedExplicitSignOutCannotResurrectCredentialInAnotherPlugin() throws Exception {
        GoogleSyncSession gateway = new GoogleSyncSession(store, signIn, transport, API, () -> now);
        store.value = token("sub-1", "phone@example.com", NOW + 3600, "OLD"); store.failClear = true;
        try { session.signOut(); fail("storage error"); }
        catch (SyncAuthException expected) { assertEquals(SyncAuthException.STORAGE, expected.code); }
        assertFalse(gateway.status().signedIn);
        assertFalse(session.status().signedIn);
        assertEquals(1, signIn.cleared);
    }

    @Test public void ordinaryVerificationServerErrorsDoNotInvalidateTheAccount() throws Exception {
        store.value = token("sub-1", "phone@example.com", NOW + 3600, "VALID");
        transport.responses.add(new GoogleSyncSession.Response(503, "unavailable"));
        try { session.verifiedAccountId(); fail("server failure"); }
        catch (SyncAuthException expected) { assertEquals(SyncAuthException.NETWORK, expected.code); }
        assertTrue(session.status().signedIn);
        assertTrue(signIn.interactive.isEmpty());
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

    /** First setup adds only strict, account-bound atomic initialization, never reset or replacement. */
    @Test public void onlyFixedVaultRoutesAndStrictAtomicSetupCanBeRequested() throws Exception {
        store.value = token("sub-1", "phone@example.com", NOW + 3600, "VAULTSECRET1");
        String[][] refused = {
                {"POST", "vault/status", "{}"},
                {"GET", "vault/verify", null},
                {"POST", "vault/verify", null},
                {"POST", "vault/verify", "x".repeat(1025)},
                {"POST", "vault/reset", "{\"confirm\":\"RESET\"}"},
                {"POST", "vault/verifier", "{}"},
                {"GET", "vault/../me", null},
                {"PUT", "vault/verify", "{}"},
        };
        for (String[] request : refused) {
            try {
                session.requestVault(request[0], request[1], request[2]);
                fail("refuse " + request[0] + " " + request[1]);
            } catch (SyncAuthException expected) {
                assertEquals(SyncAuthException.INVALID_REQUEST, expected.code);
            }
        }
        assertTrue(transport.requests.isEmpty());
        transport.responses.add(new GoogleSyncSession.Response(404, "Not Found"));
        transport.responses.add(new GoogleSyncSession.Response(401, "{\"error\":\"wrong_passphrase\",\"retryAfter\":1}"));
        assertEquals(404, session.requestVault("GET", "vault/status", null).status);
        GoogleSyncSession.Response wrong = session.requestVault("POST", "vault/verify", "{\"auth\":\"a\"}");
        assertEquals(API + "/vault/status", transport.requests.get(0).url);
        assertEquals(API + "/vault/verify", transport.requests.get(1).url);
        assertEquals("POST", transport.requests.get(1).method);
        // A 401 from verify is a wrong passphrase, not an expired sign-in: no renewal, no second guess spent.
        assertEquals(401, wrong.status);
        assertEquals(2, transport.requests.size());
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
            assertFalse(expected.detail.contains("TRANSPORTSECRET9"));
            assertFalse(expected.detail.contains("Bearer"));
        }
    }

    /** #3189: opening a saved server reads the account from the stored credential, never GET /me. */
    @Test public void theOpenAccountComesFromTheStoredCredentialWithoutANetworkCall() throws Exception {
        store.value = token("sub-1", "phone@example.com", NOW + 3600, "CACHEDSECRET");
        assertEquals("google:sub-1", session.currentAccountId());
        assertTrue("no /me on the open path", transport.requests.isEmpty());
        store.value = token("sub-1", "phone@example.com", NOW - 10, "EXPIREDSECRET");
        try { session.currentAccountId(); fail("an expired credential cannot open anything"); }
        catch (SyncAuthException expected) { assertEquals(SyncAuthException.NOT_SIGNED_IN, expected.code); }
        assertTrue(transport.requests.isEmpty());
        assertFalse("an expired credential ends the session", session.status().signedIn);
    }

    /** #3189: the user-facing message is plain words; the exception class is only a "Copy details" detail. */
    @Test public void aTransportTimeoutIsPlainWordsWithTheClassOnlyInTheDetail() throws Exception {
        store.value = token("sub-1", "phone@example.com", NOW + 3600, "TIMEOUTSECRET");
        transport.failure = new java.net.SocketTimeoutException("timeout");
        try {
            session.verifiedAccountId();
            fail("a transport failure must be reported");
        } catch (SyncAuthException expected) {
            assertEquals(SyncAuthException.NETWORK, expected.code);
            assertEquals(GoogleSyncSession.NETWORK_MESSAGE, expected.getMessage());
            assertFalse(expected.getMessage().matches(".*[A-Z]\\w*(Exception|Error).*"));
            assertEquals("SocketTimeoutException on GET /me", expected.detail);
        }
        assertEquals("a timeout is not an auth loss: the sign-in stays", "phone@example.com", session.status().email);
    }

    @Test public void boundVaultCreationRefusesAccountSwitchBeforeAnyHttpEffect() throws Exception {
        store.value=token("sub-2","new@example.test",NOW+3600,"BOUNDSECRET");
        try {
            session.requestBound("PUT","vault",null,"{\"data\":\"ciphertext\",\"version\":0}","google:sub-1");
            fail("a different account must never receive the old creation");
        } catch (SyncAuthException expected) {assertEquals(SyncAuthException.INVALID_REQUEST,expected.code);}
        assertTrue(transport.requests.isEmpty());
        transport.responses.add(new GoogleSyncSession.Response(200,"{\"version\":1}"));
        session.requestBound("PUT","vault",null,"{\"data\":\"ciphertext\",\"version\":0}","google:sub-2");
        assertEquals(API+"/settings/vault",transport.requests.get(0).url);
    }

    @Test public void atomicVaultSetupIsAccountBoundAndCannotResetOrChangeAnExistingVerifier() throws Exception {
        store.value=token("sub-1","phone@example.test",NOW+3600,"SETUPSECRET");
        String valid="{\"data\":\"encrypted header\",\"auth\":\""+"A".repeat(43)+"\",\"epoch\":1}";
        for(String body:new String[]{"{}",valid.replace("1}","1.5}"),valid.replace("1}","0}"),valid.replace("1}","1,\"currentAuth\":\"old\"}")}) {
            try {session.requestBound("POST",null,"vault/initialize",body,"google:sub-1");fail("invalid setup");}
            catch(SyncAuthException expected){assertEquals(SyncAuthException.INVALID_REQUEST,expected.code);}
        }
        try {session.requestBound("POST",null,"vault/initialize",valid,null);fail("unscoped setup");}
        catch(SyncAuthException expected){assertEquals(SyncAuthException.INVALID_REQUEST,expected.code);}
        assertTrue(transport.requests.isEmpty());
        transport.responses.add(new GoogleSyncSession.Response(201,"{\"ok\":true,\"version\":1,\"epoch\":1}"));
        assertEquals(201,session.requestBound("POST",null,"vault/initialize",valid,"google:sub-1").status);
        assertEquals(API+"/vault/initialize",transport.requests.get(0).url);
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
        boolean failClear;

        @Override public String read() {
            return value;
        }

        @Override public void write(String token) {
            value = token;
        }

        @Override public void clear() throws IOException {
            if (failClear) throw new IOException("fixture clear failure");
            value = null;
        }
    }

    private static final class ScriptedSignIn implements GoogleSyncSession.SignInProvider {
        final Deque<String> tokens = new ArrayDeque<>();
        final List<Boolean> interactive = new ArrayList<>();
        SyncAuthException failure;
        int cleared;

        @Override public String obtainIdToken() throws SyncAuthException {
            interactive.add(true);
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
