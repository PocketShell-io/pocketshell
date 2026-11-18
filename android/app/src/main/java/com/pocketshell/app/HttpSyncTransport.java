package com.pocketshell.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Production {@link GoogleSyncSession.Transport}. Reads the error stream on a
 * non-2xx status so the 404/409 bodies the sync flow branches on still arrive,
 * caps the response, and keeps the connection alive for reuse: the body is always
 * read to the end and closed, which returns the socket to the keep-alive pool;
 * only a failed exchange disconnects (issue #3137).
 *
 * <p>Issue #3189: a pooled keep-alive socket can be dead without the phone
 * knowing — the carrier's NAT forgot it while the app sat in the background,
 * or the phone moved from Wi-Fi to mobile data. Android's HTTP stack reuses an
 * idle pooled socket for a GET without probing it, so the request vanishes and
 * the exchange ends in a {@code SocketTimeoutException} only after the full
 * read timeout. Two defences:
 * <ul>
 *   <li>Connections are opened through the active {@link Network}; each
 *       Network instance has its own pool, so a network switch never reuses a
 *       socket from the network the phone just left.</li>
 *   <li>After the transport sat idle (the user was in another app), the first
 *       request takes a fresh pool at once: a socket that old is the one most
 *       likely to be dead.</li>
 *   <li>An idempotent GET gets short deadlines on its first attempt and, if
 *       that fails on the transport, exactly one retry on a fresh pool with
 *       the full deadlines. A dead socket costs at most the short deadline,
 *       never the full read timeout. Other methods are never retried: a vault
 *       verification or a write must not be sent twice.</li>
 * </ul>
 */
public final class HttpSyncTransport implements GoogleSyncSession.Transport {
    static final int CONNECT_TIMEOUT_MS = 10_000;
    static final int READ_TIMEOUT_MS = 15_000;
    /** First-attempt deadlines for an idempotent GET; a fresh retry follows a miss. */
    static final int FIRST_GET_CONNECT_TIMEOUT_MS = 4_000;
    static final int FIRST_GET_READ_TIMEOUT_MS = 4_000;
    /** A pooled socket idle longer than this is not trusted for the next request. */
    static final long IDLE_FRESH_POOL_MS = 30_000L;
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    /** Opens one connection; {@code freshPool} must not hand back any previously pooled socket. */
    public interface Opener {
        HttpURLConnection open(URL url, boolean freshPool) throws IOException;
    }

    private final Opener opener;
    private final LongSupplier clockMillis;
    /** When the last exchange completed; 0 before the first. */
    private volatile long lastCompletedAt;

    /** Default JDK pool, no network binding (the gateway directory client). */
    HttpSyncTransport() {
        this((url, freshPool) -> (HttpURLConnection) url.openConnection());
    }

    HttpSyncTransport(Opener opener) {
        this(opener, () -> System.nanoTime() / 1_000_000L);
    }

    HttpSyncTransport(Opener opener, LongSupplier clockMillis) {
        this.opener = opener;
        this.clockMillis = clockMillis;
    }

    /** The sync API transport: bound to the active network, with a fresh pool after idle and for the one retry. */
    public static HttpSyncTransport forSync(Context context) {
        return new HttpSyncTransport(new ActiveNetworkOpener(
                (ConnectivityManager) context.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE)));
    }

    @Override
    public GoogleSyncSession.Response send(String method, String url, Map<String, String> headers, String body)
            throws IOException {
        boolean get = "GET".equals(method);
        long last = lastCompletedAt;
        boolean idle = last != 0L && clockMillis.getAsLong() - last > IDLE_FRESH_POOL_MS;
        try {
            return exchange(method, url, headers, body, idle, get);
        } catch (IOException first) {
            if (!get) throw first;
            log("http " + method + " " + new URL(url).getPath() + " retry on a fresh connection after "
                    + first.getClass().getSimpleName());
            return exchange(method, url, headers, body, true, false);
        }
    }

    private GoogleSyncSession.Response exchange(String method, String url, Map<String, String> headers, String body,
            boolean freshPool, boolean shortDeadlines) throws IOException {
        HttpURLConnection connection = opener.open(new URL(url), freshPool);
        boolean completed = false;
        long started = System.nanoTime();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(shortDeadlines ? FIRST_GET_CONNECT_TIMEOUT_MS : CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(shortDeadlines ? FIRST_GET_READ_TIMEOUT_MS : READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            for (Map.Entry<String, String> header : headers.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
            GoogleSyncSession.Response response = new GoogleSyncSession.Response(status, read(stream));
            completed = true;
            lastCompletedAt = clockMillis.getAsLong();
            return response;
        } finally {
            if (!completed) connection.disconnect();
            log("http " + method + " " + new URL(url).getPath() + (freshPool ? " (fresh)" : "") + " "
                    + (completed ? "" : "failed ") + (System.nanoTime() - started) / 1_000_000L + "ms");
        }
    }

    private static void log(String line) {
        Consumer<String> sink = GoogleSyncSession.timingLog;
        if (sink != null) sink.accept(line);
    }

    private static String read(InputStream stream) throws IOException {
        if (stream == null) return "";
        try (InputStream input = stream) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_RESPONSE_BYTES) throw new IOException("sync response is too large");
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    /**
     * Opens through the active network. The Network instance is kept while the
     * network is the same (keep-alive reuse, #3137); a network change or a
     * fresh-pool request takes a new instance, whose pool holds no old socket.
     */
    public static final class ActiveNetworkOpener implements Opener {
        private final ConnectivityManager connectivity;
        private Network network;

        public ActiveNetworkOpener(ConnectivityManager connectivity) {
            this.connectivity = connectivity;
        }

        @Override
        public synchronized HttpURLConnection open(URL url, boolean freshPool) throws IOException {
            Network active;
            try {
                active = connectivity == null ? null : connectivity.getActiveNetwork();
            } catch (SecurityException noPermission) {
                active = null;
            }
            if (active == null) {
                network = null;
                // The default pool cannot promise a fresh socket: a retry with no network fails here
                // rather than reusing the socket that just failed.
                if (freshPool) throw new IOException("no active network for a fresh connection");
                return (HttpURLConnection) url.openConnection();
            }
            if (freshPool || network == null || !network.equals(active)) network = active;
            return (HttpURLConnection) network.openConnection(url);
        }
    }
}
