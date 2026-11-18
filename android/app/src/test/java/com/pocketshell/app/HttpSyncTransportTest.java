package com.pocketshell.app;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Issue #3189: a dead pooled keep-alive socket (carrier NAT forgot it, or the
 * phone changed networks) made the first GET after a pause end in
 * SocketTimeoutException. An idempotent GET now retries exactly once on a
 * fresh pool; nothing else is ever sent twice.
 */
public final class HttpSyncTransportTest {
    private static final String URL_ME = "https://sync.example.test/me";

    /** Scripted connections: each open() takes the next outcome. */
    private static final class ScriptedOpener implements HttpSyncTransport.Opener {
        final Deque<Object> outcomes = new ArrayDeque<>();
        final List<Boolean> freshPools = new ArrayList<>();
        final List<FakeConnection> opened = new ArrayList<>();

        @Override
        public HttpURLConnection open(URL url, boolean freshPool) {
            freshPools.add(freshPool);
            FakeConnection connection = new FakeConnection(url, outcomes.removeFirst());
            opened.add(connection);
            return connection;
        }
    }

    private static final class FakeConnection extends HttpURLConnection {
        private final Object outcome;
        final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        boolean disconnected;

        int connectTimeoutSet;
        int readTimeoutSet;

        @Override public void setConnectTimeout(int timeout) { connectTimeoutSet = timeout; super.setConnectTimeout(timeout); }
        @Override public void setReadTimeout(int timeout) { readTimeoutSet = timeout; super.setReadTimeout(timeout); }

        FakeConnection(URL url, Object outcome) {
            super(url);
            this.outcome = outcome;
        }

        @Override public void connect() { }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public OutputStream getOutputStream() { return sent; }

        @Override
        public int getResponseCode() throws IOException {
            if (outcome instanceof IOException) throw (IOException) outcome;
            return (Integer) outcome;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream("{\"sub\":\"s\"}".getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test public void aGetThatTimesOutOnAPooledSocketIsRetriedOnceOnAFreshPool() throws Exception {
        ScriptedOpener opener = new ScriptedOpener();
        opener.outcomes.add(new SocketTimeoutException("Read timed out"));
        opener.outcomes.add(200);
        GoogleSyncSession.Response response = new HttpSyncTransport(opener, () -> 1_000L).send("GET", URL_ME, Map.of(), null);
        assertEquals(200, response.status);
        assertEquals(List.of(false, true), opener.freshPools);
        assertTrue("the dead connection is discarded", opener.opened.get(0).disconnected);
        assertFalse("the good connection returns to the pool", opener.opened.get(1).disconnected);
    }

    @Test public void aGetIsRetriedAtMostOnce() throws Exception {
        ScriptedOpener opener = new ScriptedOpener();
        opener.outcomes.add(new SocketTimeoutException("first"));
        opener.outcomes.add(new SocketTimeoutException("second"));
        try {
            new HttpSyncTransport(opener, () -> 1_000L).send("GET", URL_ME, Map.of(), null);
            fail("two transport failures must be reported");
        } catch (SocketTimeoutException expected) {
            assertEquals("second", expected.getMessage());
        }
        assertEquals(2, opener.opened.size());
    }

    @Test public void nonIdempotentRequestsAreNeverResent() throws Exception {
        for (String method : List.of("POST", "PUT")) {
            ScriptedOpener opener = new ScriptedOpener();
            opener.outcomes.add(new SocketTimeoutException("lost"));
            try {
                new HttpSyncTransport(opener, () -> 1_000L).send(method, "https://sync.example.test/vault/verify", Map.of(), "{\"auth\":\"a\"}");
                fail(method + " must not be retried");
            } catch (SocketTimeoutException expected) {
                assertEquals(1, opener.opened.size());
            }
        }
    }

    @Test public void anHttpErrorStatusIsAnAnswerNotARetry() throws Exception {
        ScriptedOpener opener = new ScriptedOpener();
        opener.outcomes.add(401);
        assertEquals(401, new HttpSyncTransport(opener, () -> 1_000L).send("GET", URL_ME, Map.of(), null).status);
        assertEquals(1, opener.opened.size());
    }

    @Test public void timeoutsStayBounded() {
        assertEquals(10_000, HttpSyncTransport.CONNECT_TIMEOUT_MS);
        assertEquals(15_000, HttpSyncTransport.READ_TIMEOUT_MS);
        assertTrue("a dead socket costs well under the full read timeout",
                HttpSyncTransport.FIRST_GET_READ_TIMEOUT_MS <= 5_000 && HttpSyncTransport.FIRST_GET_CONNECT_TIMEOUT_MS <= 5_000);
    }

    /** #3189: a GET's first attempt has short deadlines; only the fresh retry gets the full ones. */
    @Test public void aGetsFirstAttemptHasShortDeadlinesAndTheRetryTheFullOnes() throws Exception {
        ScriptedOpener opener = new ScriptedOpener();
        opener.outcomes.add(new SocketTimeoutException("dead pooled socket"));
        opener.outcomes.add(200);
        new HttpSyncTransport(opener, () -> 1_000L).send("GET", URL_ME, Map.of(), null);
        assertEquals(HttpSyncTransport.FIRST_GET_CONNECT_TIMEOUT_MS, opener.opened.get(0).connectTimeoutSet);
        assertEquals(HttpSyncTransport.FIRST_GET_READ_TIMEOUT_MS, opener.opened.get(0).readTimeoutSet);
        assertEquals(HttpSyncTransport.CONNECT_TIMEOUT_MS, opener.opened.get(1).connectTimeoutSet);
        assertEquals(HttpSyncTransport.READ_TIMEOUT_MS, opener.opened.get(1).readTimeoutSet);
    }

    /** Writes are never retried, so they keep the full deadlines on their only attempt. */
    @Test public void aWriteKeepsTheFullDeadlines() throws Exception {
        ScriptedOpener opener = new ScriptedOpener();
        opener.outcomes.add(200);
        new HttpSyncTransport(opener, () -> 1_000L).send("POST", "https://sync.example.test/vault/verify", Map.of(), "{}");
        assertEquals(HttpSyncTransport.READ_TIMEOUT_MS, opener.opened.get(0).readTimeoutSet);
    }

    /** #3189: after the app sat idle (another app in front), the first request takes a fresh pool at once. */
    @Test public void afterIdleTheFirstRequestTakesAFreshPool() throws Exception {
        long[] now = {1_000L};
        ScriptedOpener opener = new ScriptedOpener();
        HttpSyncTransport transport = new HttpSyncTransport(opener, () -> now[0]);
        opener.outcomes.add(200);
        opener.outcomes.add(200);
        opener.outcomes.add(200);
        transport.send("GET", URL_ME, Map.of(), null);
        now[0] += HttpSyncTransport.IDLE_FRESH_POOL_MS - 1;
        transport.send("GET", URL_ME, Map.of(), null);
        now[0] += HttpSyncTransport.IDLE_FRESH_POOL_MS + 1;
        transport.send("POST", "https://sync.example.test/vault/verify", Map.of(), "{}");
        assertEquals(List.of(false, false, true), opener.freshPools);
    }
}
