package com.pocketshell.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The loopback-PKCE fallback sign-in (issue #3020's missing Android OAuth
 * client): the PKCE pair, the authorization URL, the loopback callback, the
 * relay exchange — and the rule that no token ever reaches a message, a page,
 * or a test double's failure.
 */
public final class LoopbackPkceSignInTest {
    private static final String CLIENT_ID = GoogleSyncConfig.DESKTOP_CLIENT_ID;
    private static final String API = "https://sync.example.test";
    private static final long NOW_SECONDS = 1_800_000_000L;
    private static final long NOW_MILLIS = NOW_SECONDS * 1000L;

    private final ExecutorService browser = Executors.newCachedThreadPool();

    @Before public void setUp() { }

    @After public void tearDown() { browser.shutdownNow(); }

    @Test public void aPkcePairMatchesItsChallengeAndTheRelayVerifierRules() throws Exception {
        LoopbackPkceSignIn.PkcePair pair = LoopbackPkceSignIn.createPkcePair(seededRandom("pkce-1"));
        assertEquals("32 random bytes are 43 base64url characters", 43, pair.verifier.length());
        assertTrue(pair.verifier.matches("[A-Za-z0-9._~-]{43,128}"));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String expected = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(digest.digest(pair.verifier.getBytes(StandardCharsets.US_ASCII)));
        assertEquals(expected, pair.challenge);
        assertFalse("the challenge must not be the verifier", pair.challenge.equals(pair.verifier));

        LoopbackPkceSignIn.PkcePair again = LoopbackPkceSignIn.createPkcePair(seededRandom("pkce-1"));
        assertEquals("the same seed yields the same pair", pair.verifier, again.verifier);
        LoopbackPkceSignIn.PkcePair other = LoopbackPkceSignIn.createPkcePair(seededRandom("pkce-2"));
        assertFalse("a different seed yields a different verifier", pair.verifier.equals(other.verifier));
    }

    @Test public void theStateTokenIsBase64UrlAndSeedDeterministic() throws Exception {
        String state = LoopbackPkceSignIn.randomState(seededRandom("state-1"));
        assertTrue(state.matches("[A-Za-z0-9._~-]{22}"));
        assertEquals(state, LoopbackPkceSignIn.randomState(seededRandom("state-1")));
        assertFalse(state.equals(LoopbackPkceSignIn.randomState(seededRandom("state-2"))));
    }

    @Test public void theAuthorizationUrlCarriesTheDesktopFlowParameters() throws Exception {
        String url = LoopbackPkceSignIn.buildAuthorizationUrl(
                CLIENT_ID, "http://127.0.0.1:4919", "challenge-1", "state-1");
        URI uri = new URI(url);
        assertEquals("https", uri.getScheme());
        assertEquals("accounts.google.com", uri.getHost());
        assertEquals("/o/oauth2/v2/auth", uri.getPath());
        Map<String, String> params = queryOf(uri.getRawQuery());
        assertEquals("code", params.get("response_type"));
        assertEquals(CLIENT_ID, params.get("client_id"));
        assertEquals("http://127.0.0.1:4919", params.get("redirect_uri"));
        assertEquals("openid email profile", params.get("scope"));
        assertEquals("offline", params.get("access_type"));
        assertEquals("consent", params.get("prompt"));
        assertEquals("challenge-1", params.get("code_challenge"));
        assertEquals("S256", params.get("code_challenge_method"));
        assertEquals("state-1", params.get("state"));
    }

    @Test public void theCallbackQueryYieldsTheCodeAndState() {
        LoopbackPkceSignIn.Callback ok = LoopbackPkceSignIn.parseCallbackQuery(
                "state=st%2Bate&code=4%2F0AuthCode&scope=openid");
        assertNull(ok.error);
        assertEquals("4/0AuthCode", ok.code);
        assertEquals("st+ate", ok.state);
    }

    @Test public void aDeniedConsentIsACancellation() {
        LoopbackPkceSignIn.Callback denied = LoopbackPkceSignIn.parseCallbackQuery("error=access_denied");
        assertEquals("Google sign-in was not completed (access_denied).", denied.error);
        assertTrue(denied.cancelled);
    }

    @Test public void aCallbackWithoutACodeIsAPlainFailure() {
        for (String query : new String[] {null, "", "state=only", "scope=openid%20email"}) {
            LoopbackPkceSignIn.Callback callback = LoopbackPkceSignIn.parseCallbackQuery(query);
            assertEquals("Google did not return an authorization code.", callback.error);
            assertFalse(callback.cancelled);
        }
    }

    @Test public void aTokenResponseBecomesAGrant() throws Exception {
        String idToken = idToken("sub-1", NOW_SECONDS + 3600);
        LoopbackPkceSignIn.TokenGrant grant = LoopbackPkceSignIn.parseTokenResponse(200,
                "{\"id_token\":\"" + idToken + "\",\"refresh_token\":\"RT-1\",\"expires_in\":3599,"
                        + "\"access_token\":\"SECRET-ACCESS\",\"token_type\":\"Bearer\"}");
        assertEquals(idToken, grant.idToken);
        assertEquals("RT-1", grant.refreshToken);
        assertEquals(3599, grant.expiresInSeconds);
        assertFalse(grant.toString().contains("SECRET-ACCESS"));
        assertFalse(grant.toString().contains("RT-1"));
    }

    @Test public void aRefreshGrantWithoutANewRefreshTokenKeepsItAbsent() throws Exception {
        LoopbackPkceSignIn.TokenGrant grant = LoopbackPkceSignIn.parseTokenResponse(200,
                "{\"id_token\":\"" + idToken("sub-1", NOW_SECONDS + 3600) + "\",\"expires_in\":3600}");
        assertNull(grant.refreshToken);
        assertEquals(3600, grant.expiresInSeconds);
    }

    @Test public void relayRefusalsBecomeBoundedHumanFailures() {
        try {
            LoopbackPkceSignIn.parseTokenResponse(400,
                    "{\"error\":\"invalid_grant\",\"error_description\":\"code was already redeemed.\"}");
            fail("a non-200 must be refused");
        } catch (SyncAuthException expected) {
            assertEquals(SyncAuthException.FAILED, expected.code);
            assertTrue(expected.getMessage().contains("invalid_grant"));
            assertTrue(expected.getMessage().contains("already redeemed"));
        }
        try {
            LoopbackPkceSignIn.parseTokenResponse(200, "{\"no_id_token\":true}");
            fail("a response without an ID token must be refused");
        } catch (SyncAuthException expected) {
            assertTrue(expected.getMessage().contains("no ID token"));
        }
        try {
            LoopbackPkceSignIn.parseTokenResponse(200, "not json at all");
            fail("an unreadable body must be refused");
        } catch (SyncAuthException expected) {
            assertTrue(expected.getMessage().contains("unreadable"));
        }
        String longDescription = "x".repeat(900);
        try {
            LoopbackPkceSignIn.parseTokenResponse(502, "{\"error\":\"bad_gateway\",\"error_description\":\""
                    + longDescription + "\"}");
            fail("a long refusal must still be refused");
        } catch (SyncAuthException expected) {
            assertTrue("the detail is bounded", expected.getMessage().length() < 500);
        }
    }

    @Test public void theRelayRequestBodiesCarryExactlyTheContractFields() {
        String exchange = LoopbackPkceSignIn.authorizationCodeRequest("4/abc", "verifier-1", "http://127.0.0.1:1");
        assertTrue(exchange.contains("\"grant_type\":\"authorization_code\""));
        assertTrue(exchange.contains("\"code\":\"4/abc\""));
        assertTrue(exchange.contains("\"code_verifier\":\"verifier-1\""));
        assertTrue(exchange.contains("\"redirect_uri\":\"http://127.0.0.1:1\""));
        String refresh = LoopbackPkceSignIn.refreshRequest("RT-1");
        assertTrue(refresh.contains("\"grant_type\":\"refresh_token\""));
        assertTrue(refresh.contains("\"refresh_token\":\"RT-1\""));
    }

    @Test public void theLoopbackListenerSettlesOnItsOwnStateAndAnswersTheBrowser() throws Exception {
        LoopbackPkceSignIn.LoopbackListener listener = new LoopbackPkceSignIn.LoopbackListener();
        int port = listener.start();
        Future<String> page = browser.submit(() -> dial(port, "/?code=4%2Fone&state=mine"));
        String code = listener.awaitCode("mine", 10_000);
        assertEquals("4/one", code);
        assertTrue(page.get(5, TimeUnit.SECONDS).contains("return to PocketShell"));
        assertFalse("the server is closed after the flow", isPortOpen(port));
    }

    @Test public void aForeignStateIsRefusedInTheBrowserAndTheFlowFails() throws Exception {
        LoopbackPkceSignIn.LoopbackListener listener = new LoopbackPkceSignIn.LoopbackListener();
        int port = listener.start();
        Future<String> page = browser.submit(() -> dial(port, "/?code=4%2Ftwo&state=theirs"));
        try {
            listener.awaitCode("mine", 10_000);
            fail("a foreign state must fail the flow");
        } catch (SyncAuthException expected) {
            assertEquals(SyncAuthException.FAILED, expected.code);
            assertTrue(expected.getMessage().contains("did not match"));
        }
        assertTrue(page.get(5, TimeUnit.SECONDS).contains("Sign-in failed"));
    }

    @Test public void aDeniedCallbackCancelsTheFlowWithAPage() throws Exception {
        LoopbackPkceSignIn.LoopbackListener listener = new LoopbackPkceSignIn.LoopbackListener();
        int port = listener.start();
        Future<String> page = browser.submit(() -> dial(port, "/?error=access_denied"));
        try {
            listener.awaitCode("mine", 10_000);
            fail("a denial must cancel the flow");
        } catch (SyncAuthException expected) {
            assertEquals(SyncAuthException.CANCELLED, expected.code);
        }
        assertTrue(page.get(5, TimeUnit.SECONDS).contains("Sign-in failed"));
    }

    @Test public void uninterestingPathsGetA404AndTheListenerKeepsWaiting() throws Exception {
        LoopbackPkceSignIn.LoopbackListener listener = new LoopbackPkceSignIn.LoopbackListener();
        int port = listener.start();
        Future<String> favicon = browser.submit(() -> dial(port, "/favicon.ico"));
        Future<String> page = browser.submit(() -> dial(port, "/?code=4%2Fthree&state=mine"));
        assertEquals("the real callback settles the flow", "4/three", listener.awaitCode("mine", 10_000));
        assertTrue("the favicon poke got a 404, not the flow's answer",
                favicon.get(5, TimeUnit.SECONDS).contains("404"));
        assertTrue(page.get(5, TimeUnit.SECONDS).contains("return to PocketShell"));
    }

    @Test public void theDeadlineStopsTheWait() throws Exception {
        LoopbackPkceSignIn.LoopbackListener listener = new LoopbackPkceSignIn.LoopbackListener();
        listener.start();
        long started = System.currentTimeMillis();
        try {
            listener.awaitCode("mine", 300);
            fail("the deadline must stop the wait");
        } catch (SyncAuthException expected) {
            assertTrue(expected.getMessage().contains("timed out"));
        }
        assertTrue("the deadline is honored, not ignored", System.currentTimeMillis() - started < 10_000);
    }

    @Test public void anInteractiveSignInRunsTheWholeBrowserRoundTrip() throws Exception {
        MemoryRefreshStore store = new MemoryRefreshStore();
        RecordingTransport transport = new RecordingTransport();
        String idToken = idToken("sub-1", NOW_SECONDS + 3600);
        transport.responses.add(new GoogleSyncSession.Response(200, "{\"id_token\":\"" + idToken
                + "\",\"refresh_token\":\"RT-INTERACTIVE\",\"expires_in\":3600}"));
        List<String> openedUrls = new ArrayList<>();
        LoopbackPkceSignIn signIn = new LoopbackPkceSignIn(() -> null, CLIENT_ID, API, transport, store,
                seededRandom("e2e-1"), (activity, url) -> {
                    openedUrls.add(url);
                    browser.submit(() -> {
                        try {
                            Map<String, String> params = queryOf(new URI(url).getRawQuery());
                            int port = new URI(params.get("redirect_uri")).getPort();
                            dial(port, "/?state=" + params.get("state") + "&code=4%2Fauth-code");
                        } catch (Exception brokenBrowser) {
                            throw new RuntimeException(brokenBrowser);
                        }
                        return null;
                    });
                }, () -> NOW_MILLIS);

        String token = signIn.browserSignIn(null);

        assertEquals(idToken, token);
        assertEquals("RT-INTERACTIVE", store.value);
        assertEquals(1, openedUrls.size());
        Map<String, String> params = queryOf(new URI(openedUrls.get(0)).getRawQuery());
        assertEquals(CLIENT_ID, params.get("client_id"));
        assertEquals("S256", params.get("code_challenge_method"));
        assertEquals("consent", params.get("prompt"));

        assertEquals(1, transport.requests.size());
        Sent sent = transport.requests.get(0);
        assertEquals("POST", sent.method);
        assertEquals(API + GoogleSyncConfig.TOKEN_RELAY_PATH, sent.url);
        assertEquals("application/json", sent.headers.get("Content-Type"));
        assertTrue(sent.body.contains("\"grant_type\":\"authorization_code\""));
        assertTrue(sent.body.contains("\"code\":\"4/auth-code\""));
        assertTrue("the verifier travels only in the relay body", sent.body.contains("\"code_verifier\":\""));
        assertFalse("the verifier never travels in the browser URL", openedUrls.get(0).contains("verifier"));
        assertFalse("the URL carries no secret", openedUrls.get(0).contains("RT-INTERACTIVE"));
    }

    @Test public void aSilentRenewalExchangesTheStoredRefreshToken() throws Exception {
        MemoryRefreshStore store = new MemoryRefreshStore();
        store.write("RT-STORED");
        RecordingTransport transport = new RecordingTransport();
        String fresh = idToken("sub-1", NOW_SECONDS + 7200);
        transport.responses.add(new GoogleSyncSession.Response(200, "{\"id_token\":\"" + fresh
                + "\",\"expires_in\":3600}"));
        LoopbackPkceSignIn signIn = new LoopbackPkceSignIn(() -> null, CLIENT_ID, API, transport, store,
                seededRandom("renew-1"), (activity, url) -> fail("a renewal must not open a browser"),
                () -> NOW_MILLIS);

        assertTrue(signIn.canRenew());
        assertEquals(fresh, signIn.obtainIdToken(false));
        assertEquals("RT-STORED", store.value);
        Sent sent = transport.requests.get(0);
        assertEquals(API + GoogleSyncConfig.TOKEN_RELAY_PATH, sent.url);
        assertTrue(sent.body.contains("\"grant_type\":\"refresh_token\""));
        assertTrue(sent.body.contains("\"refresh_token\":\"RT-STORED\""));
    }

    @Test public void aSilentRenewalWithoutAStoredSecretIsUnavailable() throws Exception {
        LoopbackPkceSignIn signIn = new LoopbackPkceSignIn(() -> null, CLIENT_ID, API,
                new RecordingTransport(), new MemoryRefreshStore(), seededRandom("renew-2"),
                (activity, url) -> fail("no browser without a secret"), () -> NOW_MILLIS);
        assertFalse(signIn.canRenew());
        try {
            signIn.obtainIdToken(false);
            fail("no secret, no renewal");
        } catch (SyncAuthException expected) {
            assertEquals(SyncAuthException.UNAVAILABLE, expected.code);
        }
    }

    @Test public void aFailedRenewalNeverEchoesTheSecret() throws Exception {
        MemoryRefreshStore store = new MemoryRefreshStore();
        store.write("RT-EXPIRED");
        RecordingTransport transport = new RecordingTransport();
        transport.responses.add(new GoogleSyncSession.Response(400,
                "{\"error\":\"invalid_grant\",\"error_description\":\"Token has been expired or revoked.\"}"));
        LoopbackPkceSignIn signIn = new LoopbackPkceSignIn(() -> null, CLIENT_ID, API, transport, store,
                seededRandom("renew-3"), (activity, url) -> fail("no browser on a renewal"),
                () -> NOW_MILLIS);
        try {
            signIn.obtainIdToken(false);
            fail("a refused renewal must fail");
        } catch (SyncAuthException expected) {
            assertEquals(SyncAuthException.FAILED, expected.code);
            assertFalse(expected.getMessage().contains("RT-EXPIRED"));
        }
    }

    @Test public void aRenewalTransportFailureIsANetworkError() throws Exception {
        MemoryRefreshStore store = new MemoryRefreshStore();
        store.write("RT-NET");
        RecordingTransport transport = new RecordingTransport();
        transport.failure = new IOException("offline");
        LoopbackPkceSignIn signIn = new LoopbackPkceSignIn(() -> null, CLIENT_ID, API, transport, store,
                seededRandom("renew-4"), (activity, url) -> fail("no browser on a renewal"),
                () -> NOW_MILLIS);
        try {
            signIn.obtainIdToken(false);
            fail("a transport failure must surface");
        } catch (SyncAuthException expected) {
            assertEquals(SyncAuthException.NETWORK, expected.code);
            assertFalse(expected.getMessage().contains("RT-NET"));
            assertFalse(expected.getMessage().contains("offline"));
        }
    }

    @Test public void clearCredentialStateRevokesTheGrantAndForgetsTheSecret() throws Exception {
        MemoryRefreshStore store = new MemoryRefreshStore();
        store.write("RT-REVOKE");
        RecordingTransport transport = new RecordingTransport();
        LoopbackPkceSignIn signIn = new LoopbackPkceSignIn(() -> null, CLIENT_ID, API, transport, store,
                seededRandom("signout-1"), (activity, url) -> fail("no browser on sign-out"),
                () -> NOW_MILLIS);

        signIn.clearCredentialState();

        assertNull(store.value);
        assertFalse(signIn.canRenew());
        assertEquals(1, transport.requests.size());
        Sent sent = transport.requests.get(0);
        assertEquals("POST", sent.method);
        assertEquals(GoogleSyncConfig.GOOGLE_REVOKE_ENDPOINT, sent.url);
        assertEquals("application/x-www-form-urlencoded", sent.headers.get("Content-Type"));
        assertEquals("token=RT-REVOKE", sent.body);
        assertTrue("revocation failures are courtesy-only", signIn.canRenew() == false);
    }

    @Test public void forgetRenewalSecretDropsTheSecretWithoutNetworkOrRevocation() throws Exception {
        MemoryRefreshStore store = new MemoryRefreshStore();
        store.write("RT-FORGET");
        RecordingTransport transport = new RecordingTransport();
        LoopbackPkceSignIn signIn = new LoopbackPkceSignIn(() -> null, CLIENT_ID, API, transport, store,
                seededRandom("forget-1"), (activity, url) -> fail("no browser"), () -> NOW_MILLIS);

        signIn.forgetRenewalSecret();

        assertNull(store.value);
        assertEquals("forgetting is local only", 0, transport.requests.size());
    }

    @Test public void anInteractiveSignInWithoutAForegroundActivityIsRefused() throws Exception {
        LoopbackPkceSignIn signIn = new LoopbackPkceSignIn(() -> null, CLIENT_ID, API,
                new RecordingTransport(), new MemoryRefreshStore(), seededRandom("fg-1"),
                (activity, url) -> fail("no browser without an activity"), () -> NOW_MILLIS);
        try {
            signIn.obtainIdToken(true);
            fail("sign-in needs the app in the foreground");
        } catch (SyncAuthException expected) {
            assertEquals(SyncAuthException.FAILED, expected.code);
            assertTrue(expected.getMessage().contains("foreground"));
        }
    }

    /* --- helpers ------------------------------------------------------------ */

    private static SecureRandom seededRandom(String seed) throws Exception {
        SecureRandom random = SecureRandom.getInstance("SHA1PRNG");
        random.setSeed(seed.getBytes(StandardCharsets.UTF_8));
        return random;
    }

    private static Map<String, String> queryOf(String rawQuery) {
        Map<String, String> params = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) return params;
        for (String pair : rawQuery.split("&")) {
            int equals = pair.indexOf('=');
            String name = decode(equals < 0 ? pair : pair.substring(0, equals));
            String value = equals < 0 ? "" : decode(pair.substring(equals + 1));
            params.put(name, value);
        }
        return params;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    /** Play the browser: open the connection, send the GET, return the whole page. */
    private static String dial(int port, String target) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            OutputStream output = socket.getOutputStream();
            output.write(("GET " + target + " HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            output.flush();
            InputStream input = socket.getInputStream();
            ByteArrayOutputStream received = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) received.write(buffer, 0, count);
            return new String(received.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static boolean isPortOpen(int port) {
        try (Socket ignored = new Socket("127.0.0.1", port)) {
            return true;
        } catch (IOException closed) {
            return false;
        }
    }

    private static String idToken(String subject, long exp) {
        String claims = "{\"sub\":\"" + subject + "\",\"email\":\"phone@example.com\",\"exp\":" + exp
                + ",\"aud\":\"" + CLIENT_ID + "\"}";
        return Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8))
                + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(claims.getBytes(StandardCharsets.UTF_8))
                + ".SIGNATURE";
    }

    private static final class MemoryRefreshStore implements LoopbackPkceSignIn.RefreshStore {
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
        final java.util.ArrayDeque<GoogleSyncSession.Response> responses = new java.util.ArrayDeque<>();
        IOException failure;

        @Override public GoogleSyncSession.Response send(String method, String url, Map<String, String> headers,
                String body) throws IOException {
            if (failure != null) throw failure;
            requests.add(new Sent(method, url, headers, body));
            return responses.removeFirst();
        }
    }
}
