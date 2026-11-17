package com.pocketshell.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
 * Issue #3137: the server list and a host tap used to cost ~12 and ~30 serial
 * backend requests, mostly repeated {@code GET /me} identity checks. These
 * tests count requests at the transport for the call sequences the WebView
 * issues, and pin the account-switch fences that the identity cache must not
 * weaken.
 */
public final class GoogleSyncSessionRequestBudgetTest {
    private static final String API = "https://sync.example.test";
    private static final long NOW = 1_800_000_000L;

    private Store store;
    private Provider provider;
    private Counting transport;
    private long now;
    private GoogleSyncSession session;

    @Before public void setUp() {
        store = new Store();
        provider = new Provider();
        transport = new Counting();
        now = NOW;
        session = new GoogleSyncSession(store, provider, transport, API, () -> now);
        store.value = token("sub-1", NOW + 3600, "A");
    }

    /** prepareAccount + androidVault.refresh: identity is asked before/after every awaited step. */
    @Test public void serverListLoadVerifiesIdentityOnceAndStaysWithinThreeRequests() throws Exception {
        for (int i = 0; i < 6; i++) assertEquals("google:sub-1", session.verifiedAccountId());
        session.requestBound("GET", null, "vault/status", null, "google:sub-1");
        for (int i = 0; i < 4; i++) assertEquals("google:sub-1", session.verifiedAccountId());
        session.requestBound("POST", null, "vault/verify", "{}", "google:sub-1");
        assertEquals("google:sub-1", session.verifiedAccountId());
        assertTrue("requests: " + transport.urls(), transport.requests.size() <= 3);
        assertEquals(1, transport.count("/me"));
    }

    /** connect(): account fences around each step, then validateTrusted/install, then the gateway token. */
    @Test public void hostTapToDialStartStaysWithinThreeRequests() throws Exception {
        for (int i = 0; i < 12; i++) session.verifiedAccountId();
        session.requestBound("GET", null, "vault/status", null, "google:sub-1");
        for (int i = 0; i < 8; i++) session.verifiedAccountId();
        transport.responses.add(new GoogleSyncSession.Response(200, "{\"token\":\"x\"}"));
        session.gatewayTokenExchange();
        assertTrue("requests: " + transport.urls(), transport.requests.size() <= 3);
        assertEquals(1, transport.count("/me"));
    }

    @Test public void cachedIdentityIsBoundedByIdTokenExpiryAndSixtySeconds() throws Exception {
        session.verifiedAccountId();
        now = NOW + 59;
        session.verifiedAccountId();
        assertEquals(1, transport.count("/me"));
        now = NOW + 61;
        session.verifiedAccountId();
        assertEquals(2, transport.count("/me"));
    }

    /** exp = now+90 with a 60s skew leaves 30s of usable life: the cache ends there, not at 60s. */
    @Test public void cacheNeverOutlivesTheUsableLifeOfTheIdToken() throws Exception {
        store.value = token("sub-1", NOW + 90, "S");
        session.verifiedAccountId();
        now = NOW + 29;
        session.verifiedAccountId();
        assertEquals("still inside the token's usable life", 1, transport.count("/me"));
        provider.tokens.add(token("sub-1", NOW + 3600, "S2"));
        now = NOW + 31;
        session.verifiedAccountId();
        assertEquals("the cache ended with the token", 2, transport.count("/me"));
        assertEquals("the expired token was renewed once", 1, provider.calls);
    }

    /** A request in flight before sign-out must not write a renewed token back after it. */
    @Test public void aRequestInFlightAcrossSignOutNeverResurrectsTheSignIn() throws Exception {
        transport.gate("/settings/main");
        Thread requester = transport.async(() -> session.request("GET", "main", null));
        transport.awaitGated();
        session.signOut();
        provider.tokens.add(token("sub-1", NOW + 3600, "RESURRECTED"));
        transport.release(new GoogleSyncSession.Response(401, ""));
        requester.join();
        assertEquals(SyncAuthException.NOT_SIGNED_IN, transport.asyncCode());
        assertEquals("no renewal after sign-out", 0, provider.calls);
        assertEquals(null, store.value);
        assertFalse(session.status().signedIn);
    }

    @Test public void foregroundPreRenewalFailuresNeverDropTheSignIn() throws Exception {
        store.value = token("sub-1", NOW + 100, "NEAR");
        String stored = store.value;
        provider.failure = new SyncAuthException(SyncAuthException.FAILED, "silent timeout");
        session.renewIfExpiringWithin(600);
        provider.failure = null;
        provider.runtime = new IllegalStateException("activity finishing");
        session.renewIfExpiringWithin(600);
        provider.runtime = null;
        provider.tokens.add(token("sub-2", NOW + 3600, "OTHER"));
        session.renewIfExpiringWithin(600);
        assertEquals("sign-in untouched by any failed or foreign pre-renewal", stored, store.value);
        assertTrue(session.status().signedIn);
        provider.tokens.add(token("sub-1", NOW + 3600, "FRESH"));
        session.renewIfExpiringWithin(600);
        assertEquals(token("sub-1", NOW + 3600, "FRESH"), store.value);
    }

    /** Account A's /me is in flight when B signs in: nothing of A's may be cached for or dropped from B. */
    @Test public void aStaleVerificationNeverCachesForOrDropsTheNewAccount() throws Exception {
        transport.gate("/me");
        Thread verifier = transport.async(() -> session.verifiedAccountId());
        transport.awaitGated();
        String tokenB = token("sub-B", NOW + 3600, "B");
        provider.tokens.add(tokenB);
        session.signIn();
        transport.subject = "sub-A";
        transport.release(new GoogleSyncSession.Response(200, "{\"sub\":\"sub-A\"}"));
        verifier.join();
        assertEquals(SyncAuthException.NOT_SIGNED_IN, transport.asyncCode());
        assertEquals("B's credentials survive A's stale verification", tokenB, store.value);
        transport.subject = "sub-B";
        assertEquals("google:sub-B", session.verifiedAccountId());
        assertEquals("A's answer was not cached for B", 2, transport.count("/me"));
        // The same for a stale 401.
        transport.gate("/me");
        now = NOW + 61;
        Thread stale = transport.async(() -> session.verifiedAccountId());
        transport.awaitGated();
        provider.tokens.add(token("sub-C", NOW + 3600, "C"));
        session.signIn();
        transport.release(new GoogleSyncSession.Response(401, ""));
        stale.join();
        assertEquals(token("sub-C", NOW + 3600, "C"), store.value);
    }

    @Test public void twoConcurrent401sRenewOnce() throws Exception {
        transport.barrierFor("/settings/main", 2);
        provider.tokens.add(token("sub-1", NOW + 3600, "RENEWED"));
        Thread one = transport.async(() -> session.request("GET", "main", null));
        Thread two = transport.async2(() -> session.request("GET", "main", null));
        one.join();
        two.join();
        assertEquals("both 401s share one renewal", 1, provider.calls);
        assertEquals(token("sub-1", NOW + 3600, "RENEWED"), store.value);
        assertEquals(4, transport.count("/settings/main"));
    }

    @Test public void accountChangeMidOperationIsStillRefused() throws Exception {
        session.verifiedAccountId();
        // Another plugin/process swaps the stored credential to a different account.
        store.value = token("sub-2", NOW + 3600, "B");
        transport.subject = "sub-2";
        assertEquals("the new account is verified fresh, never served from the old cache", "google:sub-2",
                session.verifiedAccountId());
        assertEquals(2, transport.count("/me"));
        // And the backend disagreeing with the stored subject is refused outright.
        store.value = token("sub-3", NOW + 3600, "C");
        try {
            session.verifiedAccountId();
            fail("backend says sub-2 for a sub-3 token");
        } catch (SyncAuthException expected) {
            assertEquals(SyncAuthException.NOT_SIGNED_IN, expected.code);
        }
    }

    @Test public void signOutSignInAnd401InvalidateTheCache() throws Exception {
        session.verifiedAccountId();
        session.signOut();
        try {
            session.verifiedAccountId();
            fail("signed out");
        } catch (SyncAuthException expected) {
            assertEquals(SyncAuthException.NOT_SIGNED_IN, expected.code);
        }
        provider.tokens.add(token("sub-1", NOW + 3600, "A3"));
        session.signIn();
        session.verifiedAccountId();
        assertEquals("sign-out then sign-in re-verifies", 2, transport.count("/me"));
        provider.tokens.add(token("sub-1", NOW + 3600, "A5"));
        session.signIn();
        session.verifiedAccountId();
        assertEquals("a second sign-in re-verifies", 3, transport.count("/me"));
        transport.responses.add(new GoogleSyncSession.Response(401, ""));
        transport.responses.add(new GoogleSyncSession.Response(200, "{}"));
        provider.tokens.add(token("sub-1", NOW + 3600, "A4"));
        session.request("GET", "main", null);
        session.verifiedAccountId();
        assertEquals(4, transport.count("/me"));
    }

    private static String token(String subject, long exp, String signature) {
        return b64("{\"alg\":\"RS256\"}") + "." + b64("{\"sub\":\"" + subject + "\",\"email\":\"a@example.com\",\"exp\":" + exp
                + ",\"aud\":\"" + GoogleSyncConfig.SERVER_CLIENT_ID + "\"}") + "." + signature;
    }

    private static String b64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static final class Store implements GoogleSyncSession.TokenStore {
        String value;
        @Override public String read() { return value; }
        @Override public void write(String token) { value = token; }
        @Override public void clear() { value = null; }
    }

    private static final class Provider implements GoogleSyncSession.SignInProvider {
        final Deque<String> tokens = new ArrayDeque<>();
        volatile int calls;
        SyncAuthException failure;
        RuntimeException runtime;
        @Override public synchronized String obtainIdToken(boolean interactive) throws SyncAuthException {
            calls++;
            if (runtime != null) throw runtime;
            if (failure != null) throw failure;
            if (tokens.isEmpty()) throw new SyncAuthException(SyncAuthException.FAILED, "none");
            return tokens.removeFirst();
        }
        @Override public void clearCredentialState() {}
    }

    interface Body { void run() throws Exception; }

    private static final class Counting implements GoogleSyncSession.Transport {
        final List<String> requests = java.util.Collections.synchronizedList(new ArrayList<>());
        final Deque<GoogleSyncSession.Response> responses = new ArrayDeque<>();
        volatile String subject = "sub-1";
        private volatile String gatedPath;
        private java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        private java.util.concurrent.CountDownLatch released = new java.util.concurrent.CountDownLatch(1);
        private volatile GoogleSyncSession.Response gatedAnswer;
        private volatile String barrierPath;
        private java.util.concurrent.CyclicBarrier barrier;
        private final java.util.Set<String> barrierSeen = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
        volatile String asyncCode = "none";

        void gate(String path) { gatedPath = path; entered = new java.util.concurrent.CountDownLatch(1);
            released = new java.util.concurrent.CountDownLatch(1); }
        void awaitGated() throws InterruptedException { assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
        void release(GoogleSyncSession.Response answer) { gatedAnswer = answer; gatedPath = null; released.countDown(); }
        void barrierFor(String path, int parties) { barrierPath = path; barrier = new java.util.concurrent.CyclicBarrier(parties); }
        String asyncCode() { return asyncCode; }

        Thread async(Body body) { return start(body); }
        Thread async2(Body body) { return start(body); }

        private Thread start(Body body) {
            Thread t = new Thread(() -> {
                try { body.run(); asyncCode = "ok"; }
                catch (SyncAuthException e) { asyncCode = e.code; }
                catch (Exception e) { asyncCode = "error:" + e; }
            });
            t.start();
            return t;
        }

        @Override public GoogleSyncSession.Response send(String method, String url, Map<String, String> headers, String body)
                throws IOException {
            String path = url.substring(API.length());
            requests.add(method + " " + path);
            if (path.equals(gatedPath)) {
                entered.countDown();
                try { assertTrue(released.await(5, java.util.concurrent.TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new IOException(e); }
                return gatedAnswer;
            }
            if (path.equals(barrierPath)) {
                // The first exchange of each of two concurrent callers answers 401 together; the retries succeed.
                String who = Thread.currentThread().getName() + ":" + headers.get("Authorization");
                if (barrierSeen.add(who) && barrierSeen.size() <= 2) {
                    try { barrier.await(5, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception e) { throw new IOException(e); }
                    return new GoogleSyncSession.Response(401, "");
                }
                return new GoogleSyncSession.Response(200, "{}");
            }
            synchronized (responses) {
                if (path.equals("/me") && responses.isEmpty()) return new GoogleSyncSession.Response(200, "{\"sub\":\"" + subject + "\"}");
                if (responses.isEmpty()) return new GoogleSyncSession.Response(200, "{}");
                return responses.removeFirst();
            }
        }

        int count(String path) {
            int n = 0;
            synchronized (requests) { for (String r : requests) if (r.endsWith(" " + path)) n++; }
            return n;
        }

        String urls() { return requests.toString(); }
    }
}
