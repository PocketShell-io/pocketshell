package com.pocketshell.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Google sign-in through the desktop app's installed-app flow: OAuth 2.0
 * authorization-code + PKCE against the desktop client, a one-shot loopback
 * HTTP listener on 127.0.0.1, the system browser (Custom Tab or Chrome), and
 * the sync API's token relay — which adds Google's client secret server-side
 * and answers with a real ID token (+ refresh token) that the sync
 * authorizer and the gateway token broker already accept.
 *
 * <p>This is the fallback for phones where Credential Manager refuses because
 * Google has no Android-type OAuth client registered for this package + signing
 * key (every preview/debug APK until one is registered; issue #3020's console
 * step). It needs no per-package registration: the desktop client allows
 * loopback redirects by design. The browser shows Google's own consent page;
 * the relay never sees the authorization code's verifier, Google's secret
 * never leaves the backend, and the resulting ID token enters the exact same
 * {@link GoogleSyncSession} storage as a Credential Manager token.
 *
 * <p>The refresh token (requested with {@code access_type=offline}) lives in
 * {@link PkceRefreshTokenStore} and is this class's private state: silent
 * renewal exchanges it at the relay instead of re-prompting the browser, so
 * the one-hour ID token expiry never fails silently (GoogleSyncSession's
 * renew path calls {@link #obtainIdToken(boolean)} non-interactively).
 *
 * <p>Called off the main thread; the interactive flow blocks until the browser
 * answers or the five-minute deadline passes.
 */
final class LoopbackPkceSignIn implements CompositeGoogleSignIn.Renewing {
    private static final long LOGIN_TIMEOUT_MILLIS = 5 * 60_000L;
    private static final int PKCE_VERIFIER_BYTES = 32;
    private static final int STATE_BYTES = 16;
    private static final String REDIRECT_HOST = "127.0.0.1";
    private static final String SUCCESS_PAGE =
            "<html><body><p>Sign-in response received &mdash; you can return to PocketShell.</p></body></html>";

    /** Opens the authorization URL outside the app; a seam so JVM tests play the browser. */
    interface UrlOpener {
        void open(Activity activity, String url) throws SyncAuthException;
    }

    /** Where the refresh token is kept; production is {@link PkceRefreshTokenStore}. */
    interface RefreshStore {
        String read() throws IOException;

        void write(String token) throws IOException;

        void clear() throws IOException;
    }

    /** A fresh PKCE pair: the verifier stays here, the challenge travels in the URL. */
    static final class PkcePair {
        final String verifier;
        final String challenge;

        PkcePair(String verifier, String challenge) {
            this.verifier = verifier;
            this.challenge = challenge;
        }
    }

    /** One loopback callback answer: either a failure message or a code + state. */
    static final class Callback {
        /** Human-readable failure, or null when a code arrived. */
        final String error;
        /** True when the user denied consent at Google (a cancellation, not a fault). */
        final boolean cancelled;
        final String code;
        final String state;

        private Callback(String error, boolean cancelled, String code, String state) {
            this.error = error;
            this.cancelled = cancelled;
            this.code = code;
            this.state = state;
        }

        static Callback failed(String message, boolean cancelled) {
            return new Callback(message, cancelled, null, null);
        }

        static Callback authorized(String code, String state) {
            return new Callback(null, false, code, state);
        }
    }

    /** One relay answer: an ID token, an optional refresh token, and the ID token's lifetime. */
    static final class TokenGrant {
        final String idToken;
        final String refreshToken;
        final long expiresInSeconds;

        TokenGrant(String idToken, String refreshToken, long expiresInSeconds) {
            this.idToken = idToken;
            this.refreshToken = refreshToken;
            this.expiresInSeconds = expiresInSeconds;
        }

        @Override
        public String toString() {
            return "TokenGrant{refreshToken=" + (refreshToken == null ? "absent" : "present")
                    + ", expiresInSeconds=" + expiresInSeconds + "}";
        }
    }

    private final Supplier<Activity> activity;
    private final String clientId;
    private final String apiBaseUrl;
    private final GoogleSyncSession.Transport transport;
    private final RefreshStore refreshStore;
    private final SecureRandom random;
    private final UrlOpener urlOpener;
    private final LongSupplier nowMillis;

    LoopbackPkceSignIn(Supplier<Activity> activity, String clientId, String apiBaseUrl,
            GoogleSyncSession.Transport transport, RefreshStore refreshStore) {
        this(activity, clientId, apiBaseUrl, transport, refreshStore, new SecureRandom(),
                LoopbackPkceSignIn::openInBrowser, System::currentTimeMillis);
    }

    LoopbackPkceSignIn(Supplier<Activity> activity, String clientId, String apiBaseUrl,
            GoogleSyncSession.Transport transport, RefreshStore refreshStore, SecureRandom random,
            UrlOpener urlOpener, LongSupplier nowMillis) {
        this.activity = activity;
        this.clientId = clientId;
        this.apiBaseUrl = apiBaseUrl.endsWith("/") ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1) : apiBaseUrl;
        this.transport = transport;
        this.refreshStore = refreshStore;
        this.random = random;
        this.urlOpener = urlOpener;
        this.nowMillis = nowMillis;
    }

    @Override
    public String obtainIdToken(boolean interactive) throws SyncAuthException {
        if (interactive) {
            Activity host = activity.get();
            if (host == null || host.isFinishing()) {
                throw new SyncAuthException(SyncAuthException.FAILED,
                        "Google sign-in needs PocketShell in the foreground.");
            }
            return browserSignIn(host);
        }
        return renewFromRefreshToken();
    }

    @Override
    public boolean canRenew() {
        try {
            String refreshToken = refreshStore.read();
            return refreshToken != null && !refreshToken.isEmpty();
        } catch (IOException unreadable) {
            return false;
        }
    }

    @Override
    public void forgetRenewalSecret() {
        try {
            refreshStore.clear();
        } catch (IOException ignored) {
            // A stale secret that survives costs only a failed renewal later.
        }
    }

    @Override
    public void clearCredentialState() {
        String refreshToken = null;
        try {
            refreshToken = refreshStore.read();
        } catch (IOException ignored) {
            // Whatever cannot be read cannot be revoked either.
        }
        forgetRenewalSecret();
        if (refreshToken != null && !refreshToken.isEmpty()) {
            // Kill the grant at Google too, like the desktop app. Best effort:
            // the local sign-out already holds.
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", "application/x-www-form-urlencoded");
            try {
                transport.send("POST", GoogleSyncConfig.GOOGLE_REVOKE_ENDPOINT, headers, "token=" + refreshToken);
            } catch (IOException | RuntimeException ignored) {
                // Revocation is courtesy, not correctness.
            }
        }
    }

    /**
     * The full browser round-trip: listen, open Google, receive the code,
     * exchange it. {@code host} is only handed to the URL opener; package
     * private so a JVM test can play the browser without an Activity stub.
     */
    String browserSignIn(Activity host) throws SyncAuthException {
        PkcePair pkce = createPkcePair(random);
        String state = randomState(random);
        LoopbackListener listener = new LoopbackListener();
        String redirectUri = "http://" + REDIRECT_HOST + ":" + listener.start();
        String authorizationUrl = buildAuthorizationUrl(clientId, redirectUri, pkce.challenge, state);
        try {
            urlOpener.open(host, authorizationUrl);
            String code = listener.awaitCode(state, LOGIN_TIMEOUT_MILLIS);
            TokenGrant grant = exchangeRelay(authorizationCodeRequest(code, pkce.verifier, redirectUri));
            rememberRefreshToken(grant.refreshToken);
            return grant.idToken;
        } finally {
            listener.close();
        }
    }

    /** Silent renewal: trade the stored refresh token for a fresh ID token at the relay. */
    private String renewFromRefreshToken() throws SyncAuthException {
        String refreshToken;
        try {
            refreshToken = refreshStore.read();
        } catch (IOException error) {
            throw new SyncAuthException(SyncAuthException.STORAGE,
                    "The Google sign-in could not be read from secure storage.");
        }
        if (refreshToken == null || refreshToken.isEmpty()) {
            throw new SyncAuthException(SyncAuthException.UNAVAILABLE,
                    "Google sign-in on this phone needs a browser sign-in first.");
        }
        TokenGrant grant = exchangeRelay(refreshRequest(refreshToken));
        return grant.idToken;
    }

    private void rememberRefreshToken(String refreshToken) throws SyncAuthException {
        try {
            if (refreshToken == null || refreshToken.isEmpty()) refreshStore.clear();
            else refreshStore.write(refreshToken);
        } catch (IOException error) {
            throw new SyncAuthException(SyncAuthException.STORAGE,
                    "The Google sign-in could not be saved to secure storage.");
        }
    }

    private TokenGrant exchangeRelay(String jsonBody) throws SyncAuthException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        headers.put("Content-Type", "application/json");
        headers.put("Cache-Control", "no-store");
        GoogleSyncSession.Response response;
        try {
            response = transport.send("POST", apiBaseUrl + GoogleSyncConfig.TOKEN_RELAY_PATH,
                    headers, jsonBody);
        } catch (IOException error) {
            // The transport's message can name hosts; keep only the class of failure.
            throw new SyncAuthException(SyncAuthException.NETWORK,
                    "Google sign-in could not reach the token service ("
                            + error.getClass().getSimpleName() + ").");
        }
        return parseTokenResponse(response.status, response.body);
    }

    /* --- pure pieces, unit-tested directly -------------------------------- */

    /** A verifier of 32 random bytes (43 base64url chars) and its S256 challenge. */
    static PkcePair createPkcePair(SecureRandom random) {
        byte[] bytes = new byte[PKCE_VERIFIER_BYTES];
        random.nextBytes(bytes);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new PkcePair(verifier, s256(verifier));
    }

    /** A state token: 16 random bytes, base64url. */
    static String randomState(SecureRandom random) {
        byte[] bytes = new byte[STATE_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String s256(String verifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(digest.digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** Google's authorization URL with the exact parameters the desktop app sends. */
    static String buildAuthorizationUrl(String clientId, String redirectUri, String codeChallenge, String state) {
        return GoogleSyncConfig.GOOGLE_AUTH_ENDPOINT
                + "?response_type=code"
                + "&client_id=" + queryEscape(clientId)
                + "&redirect_uri=" + queryEscape(redirectUri)
                + "&scope=" + queryEscape(GoogleSyncConfig.GOOGLE_TOKEN_SCOPE)
                + "&access_type=offline"
                + "&prompt=consent"
                + "&code_challenge=" + queryEscape(codeChallenge)
                + "&code_challenge_method=S256"
                + "&state=" + queryEscape(state);
    }

    private static String queryEscape(String value) {
        // URLEncoder is form-encoding: it writes a space as '+', while Google
        // — and every URL parser — accept '%20' in a query. Use '%20'.
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Parse the loopback request's query string into a callback answer. */
    static Callback parseCallbackQuery(String query) {
        if (query == null || query.isEmpty()) {
            return Callback.failed("Google did not return an authorization code.", false);
        }
        String error = null;
        boolean cancelled = false;
        String code = null;
        String state = null;
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            int equals = pair.indexOf('=');
            String name = decode(equals < 0 ? pair : pair.substring(0, equals));
            String value = equals < 0 ? "" : decode(pair.substring(equals + 1));
            if ("error".equals(name)) {
                error = "Google sign-in was not completed (" + value + ").";
                cancelled = true;
            } else if ("code".equals(name)) {
                code = value;
            } else if ("state".equals(name)) {
                state = value;
            }
        }
        if (error != null) return Callback.failed(error, cancelled);
        if (code == null || code.isEmpty()) {
            return Callback.failed("Google did not return an authorization code.", false);
        }
        return Callback.authorized(code, state);
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    /** The relay request body for an authorization-code exchange. */
    static String authorizationCodeRequest(String code, String codeVerifier, String redirectUri) {
        return jsonObjectBody(new String[][] {
                {"grant_type", "authorization_code"},
                {"code", code},
                {"code_verifier", codeVerifier},
                {"redirect_uri", redirectUri},
        });
    }

    /** The relay request body for a refresh-token exchange. */
    static String refreshRequest(String refreshToken) {
        return jsonObjectBody(new String[][] {
                {"grant_type", "refresh_token"},
                {"refresh_token", refreshToken},
        });
    }

    /** A JSON object body from literal string pairs; the keys are fixed above. */
    private static String jsonObjectBody(String[][] pairs) {
        JSONObject body = new JSONObject();
        for (String[] pair : pairs) {
            try {
                body.put(pair[0], pair[1]);
            } catch (JSONException impossible) {
                throw new IllegalStateException("a fixed relay body key failed to serialize", impossible);
            }
        }
        return body.toString();
    }

    /**
     * Parse the relay's pass-through of Google's token response. A non-200 or
     * unreadable answer becomes a human-readable failure; the response body is
     * bounded before it reaches a message, and tokens never appear in one.
     */
    static TokenGrant parseTokenResponse(int status, String body) throws SyncAuthException {
        if (status != 200) {
            throw new SyncAuthException(SyncAuthException.FAILED, relayRefusal(status, body));
        }
        JSONObject json;
        try {
            json = new JSONObject(body);
        } catch (JSONException | NullPointerException unreadable) {
            throw new SyncAuthException(SyncAuthException.FAILED,
                    "The sign-in token service returned an unreadable response.");
        }
        String idToken = json.optString("id_token", "");
        if (idToken.isEmpty()) {
            throw new SyncAuthException(SyncAuthException.FAILED,
                    "The sign-in token service returned no ID token.");
        }
        long expiresInSeconds = json.optLong("expires_in", 3600L);
        if (expiresInSeconds <= 0) expiresInSeconds = 3600L;
        String refreshToken = json.optString("refresh_token", "");
        return new TokenGrant(idToken, refreshToken.isEmpty() ? null : refreshToken, expiresInSeconds);
    }

    /** A bounded, human-readable relay refusal; error details are Google's words. */
    private static String relayRefusal(int status, String body) {
        String raw = body == null ? "" : body.trim();
        String detail = raw;
        try {
            JSONObject json = new JSONObject(raw);
            String error = json.optString("error", "");
            String description = json.optString("error_description", "");
            if (!error.isEmpty() && !description.isEmpty()) detail = error + ": " + description;
            else if (!error.isEmpty()) detail = error;
        } catch (JSONException | NullPointerException notJson) {
            // Keep the bounded raw text below.
        }
        if (detail.isEmpty()) detail = "no error details returned";
        return "Google sign-in was refused (" + status + "): "
                + detail.substring(0, Math.min(detail.length(), 400)) + ".";
    }

    /* --- the loopback listener --------------------------------------------- */

    /**
     * A one-shot HTTP listener on 127.0.0.1 with an OS-picked port — the
     * loopback redirect URI trick, so there is nothing to pre-register. It
     * answers every request (so the browser shows a page either way), accepts
     * only its own state, and stops at the first settled answer, the deadline,
     * or {@link #close()}.
     */
    static final class LoopbackListener {
        private ServerSocket serverSocket;
        private boolean settled;
        private String code;
        private SyncAuthException failure;

        /** Bind and return the picked port. */
        synchronized int start() throws SyncAuthException {
            try {
                serverSocket = new ServerSocket();
                serverSocket.bind(new InetSocketAddress(InetAddress.getByName(REDIRECT_HOST), 0));
                serverSocket.setSoTimeout(1000);
                return serverSocket.getLocalPort();
            } catch (IOException error) {
                throw new SyncAuthException(SyncAuthException.FAILED,
                        "PocketShell could not start the sign-in callback listener.");
            }
        }

        /**
         * Accept until a valid callback settles the flow; returns the
         * authorization code or throws. The server is closed on the way out
         * either way.
         */
        synchronized String awaitCode(String expectedState, long timeoutMillis) throws SyncAuthException {
            long deadline = now() + timeoutMillis;
            try {
                while (!settled) {
                    long remaining = deadline - now();
                    if (remaining <= 0) {
                        settled = true;
                        failure = new SyncAuthException(SyncAuthException.FAILED,
                                "Google sign-in timed out — no browser response within five minutes.");
                        break;
                    }
                    try (Socket socket = serverSocket.accept()) {
                        handle(socket, expectedState);
                    } catch (SocketTimeoutException waiting) {
                        // Loop back around and re-check the deadline.
                    }
                }
            } catch (IOException listenerClosed) {
                if (!settled) {
                    settled = true;
                    failure = new SyncAuthException(SyncAuthException.FAILED,
                            "PocketShell could not receive the sign-in response.");
                }
            } finally {
                close();
            }
            if (failure != null) throw failure;
            return code;
        }

        synchronized void close() {
            try {
                if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close();
            } catch (IOException ignored) {
                // Closing is best effort; the flow result is already settled.
            }
        }

        private long now() {
            return System.nanoTime() / 1_000_000L;
        }

        private void handle(Socket socket, String expectedState) {
            try {
                socket.setSoTimeout(5000);
                String requestLine = readRequest(socket);
                if (requestLine == null) return; // a hung-up or empty probe; keep waiting
                String[] parts = requestLine.split(" ");
                if (parts.length < 2) return;
                String target = parts[1];
                String path = target.contains("?") ? target.substring(0, target.indexOf('?')) : target;
                String query = target.contains("?") ? target.substring(target.indexOf('?') + 1) : "";
                if (!"/".equals(path)) {
                    // Browsers poke favicon.ico and friends; ignore quietly.
                    respond(socket, "404 Not Found", "<html><body><p>Not found.</p></body></html>");
                    return;
                }
                Callback callback = parseCallbackQuery(query);
                if (callback.error != null) {
                    respond(socket, "400 Bad Request", failurePage(callback.error));
                    settled = true;
                    failure = new SyncAuthException(
                            callback.cancelled ? SyncAuthException.CANCELLED : SyncAuthException.FAILED,
                            callback.error);
                    return;
                }
                if (!expectedState.equals(callback.state)) {
                    String message = "The sign-in response did not match this request. Close the browser"
                            + " page and try again.";
                    respond(socket, "400 Bad Request", failurePage(message));
                    settled = true;
                    failure = new SyncAuthException(SyncAuthException.FAILED, message);
                    return;
                }
                respond(socket, "200 OK", SUCCESS_PAGE);
                settled = true;
                code = callback.code;
            } catch (IOException brokenConnection) {
                // The browser hung up mid-request; keep waiting for the real callback.
            }
        }

        /**
         * The request line, after draining the headers so the response is not
         * lost to a reset; null when nothing usable arrived.
         */
        private static String readRequest(Socket socket) throws IOException {
            InputStream input = socket.getInputStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            while (!endsWithHeaderEnd(buffer.toByteArray())) {
                if (buffer.size() > 16 * 1024) break;
                int current = input.read();
                if (current == -1) break;
                buffer.write(current);
            }
            String request = new String(buffer.toByteArray(), StandardCharsets.US_ASCII);
            int lineEnd = request.indexOf('\n');
            if (lineEnd < 0) return null;
            String requestLine = request.substring(0, lineEnd).trim();
            return requestLine.isEmpty() ? null : requestLine;
        }

        /** Whether the accumulated bytes end with the blank line after the headers. */
        private static boolean endsWithHeaderEnd(byte[] received) {
            byte[] sentinel = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
            if (received.length < sentinel.length) return false;
            for (int i = 0; i < sentinel.length; i++) {
                if (received[received.length - sentinel.length + i] != sentinel[i]) return false;
            }
            return true;
        }

        private static void respond(Socket socket, String status, String html) {
            byte[] body = html.getBytes(StandardCharsets.UTF_8);
            String head = "HTTP/1.1 " + status + "\r\n"
                    + "Content-Type: text/html; charset=utf-8\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            try (OutputStream output = socket.getOutputStream()) {
                output.write(head.getBytes(StandardCharsets.US_ASCII));
                output.write(body);
                output.flush();
            } catch (IOException ignored) {
                // The browser left before the answer; the flow result stands.
            }
        }

        private static String failurePage(String message) {
            return "<html><body><h3>Sign-in failed</h3><p>" + escapeHtml(message) + "</p></body></html>";
        }

        private static String escapeHtml(String text) {
            return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    .replace("\"", "&quot;");
        }
    }

    /* --- production browser opener ----------------------------------------- */

    private static void openInBrowser(Activity activity, String url) throws SyncAuthException {
        try {
            // startActivity is a binder call into the system; it does not need
            // the UI thread, and the sign-in runs on the plugin's worker.
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (RuntimeException error) {
            throw new SyncAuthException(SyncAuthException.FAILED,
                    "No browser is available to open Google sign-in.");
        }
    }
}
