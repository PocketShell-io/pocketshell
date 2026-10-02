package com.pocketshell.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * The phone's Google sign-in and the only code that ever holds its ID token
 * (issue #3020).
 *
 * <p>The token goes from the {@link SignInProvider} into the encrypted
 * {@link TokenStore} and from there into one HTTP header. The WebView sees
 * {@link Status} (signed in? which email?) and the sync API's HTTP status and
 * body — never the token. Encryption of the settings payload happens in the
 * WebView with the user's sync passphrase; this class only moves the already
 * encrypted envelope.
 *
 * <p>Everything Android-specific (Credential Manager, encrypted preferences,
 * HttpURLConnection) sits behind the three interfaces below, so this class runs
 * on the JVM in {@code GoogleSyncSessionTest}.
 */
public final class GoogleSyncSession {
    /** Where the raw ID token is kept. Production: EncryptedSharedPreferences. */
    public interface TokenStore {
        String read() throws IOException;

        void write(String token) throws IOException;

        void clear() throws IOException;
    }

    /** Mints a Google ID token. Production: Credential Manager. */
    public interface SignInProvider {
        /**
         * @param interactive true for the user's "Sign in with Google" tap;
         *     false for a silent renewal of an expired token with the account
         *     already authorized (no chooser).
         */
        String obtainIdToken(boolean interactive) throws SyncAuthException;

        /** Forget the authorized account in Credential Manager; best effort. */
        void clearCredentialState();
    }

    /** One HTTP exchange. Throws only for transport failures, never for a status code. */
    public interface Transport {
        Response send(String method, String url, Map<String, String> headers, String body) throws IOException;
    }

    public static final class Response {
        public final int status;
        public final String body;

        public Response(int status, String body) {
            this.status = status;
            this.body = body == null ? "" : body;
        }
    }

    static final class Status {
        final boolean signedIn;
        final String email;

        Status(boolean signedIn, String email) {
            this.signedIn = signedIn;
            this.email = email;
        }
    }

    private final TokenStore store;
    private final SignInProvider provider;
    private final Transport transport;
    private final String apiBaseUrl;
    private final LongSupplier nowEpochSeconds;

    GoogleSyncSession(TokenStore store, SignInProvider provider, Transport transport, String apiBaseUrl,
            LongSupplier nowEpochSeconds) {
        this.store = store;
        this.provider = provider;
        this.transport = transport;
        this.apiBaseUrl = apiBaseUrl.endsWith("/") ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1) : apiBaseUrl;
        this.nowEpochSeconds = nowEpochSeconds;
    }

    /** Signed in means a stored token exists; an expired one is renewed on the next request. */
    synchronized Status status() throws SyncAuthException {
        GoogleIdToken token = storedToken();
        return token == null ? new Status(false, null) : new Status(true, token.email);
    }

    /** The interactive sign-in: choose an account, store its ID token. */
    synchronized Status signIn() throws SyncAuthException {
        GoogleIdToken token = GoogleIdToken.parse(provider.obtainIdToken(true));
        write(token.token);
        return new Status(true, token.email);
    }

    /** Delete the stored token and forget the authorized account. */
    synchronized void signOut() throws SyncAuthException {
        try {
            store.clear();
        } catch (IOException error) {
            throw new SyncAuthException(SyncAuthException.STORAGE, "The Google sign-in could not be removed from secure storage.");
        } finally {
            provider.clearCredentialState();
        }
    }

    /**
     * One authenticated sync API call. An expired token is renewed silently
     * first; a 401 is retried once after a silent renewal, because the token
     * can age out between the check and the server.
     */
    synchronized Response request(String method, String slot, String body) throws SyncAuthException {
        String path = pathFor(method, slot, body);
        GoogleIdToken token = usableToken();
        Response response = send(method, path, body, token);
        if (response.status == 401) {
            token = renew(token);
            response = send(method, path, body, token);
        }
        return response;
    }

    private String pathFor(String method, String slot, String body) throws SyncAuthException {
        if ("GET".equals(method) && slot == null) {
            if (body != null) throw invalid();
            return "/me";
        }
        if (slot == null || !GoogleSyncConfig.SLOT.matcher(slot).matches()) throw invalid();
        if ("GET".equals(method)) {
            if (body != null) throw invalid();
        } else if ("PUT".equals(method)) {
            if (body == null || body.getBytes(StandardCharsets.UTF_8).length > GoogleSyncConfig.MAX_REQUEST_BODY_BYTES) {
                throw invalid();
            }
        } else {
            throw invalid();
        }
        return "/settings/" + slot;
    }

    private static SyncAuthException invalid() {
        return new SyncAuthException(SyncAuthException.INVALID_REQUEST, "The sync request is not one the sync service accepts.");
    }

    private Response send(String method, String path, String body, GoogleIdToken token) throws SyncAuthException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        headers.put("Authorization", "Bearer " + token.token);
        if (body != null) headers.put("Content-Type", "application/json");
        try {
            return transport.send(method, apiBaseUrl + path, Collections.unmodifiableMap(headers), body);
        } catch (IOException error) {
            // The transport's message can name the host; it never carries the
            // header, but keep only the class so no future change can leak one.
            throw new SyncAuthException(SyncAuthException.NETWORK,
                    "The sync service could not be reached (" + error.getClass().getSimpleName() + ").");
        }
    }

    private GoogleIdToken usableToken() throws SyncAuthException {
        GoogleIdToken token = storedToken();
        if (token == null) throw new SyncAuthException(SyncAuthException.NOT_SIGNED_IN, "Sign in with Google first.");
        return token.isExpired(nowEpochSeconds.getAsLong()) ? renew(token) : token;
    }

    /** Silently renew for the same Google account, or drop the sign-in. */
    private GoogleIdToken renew(GoogleIdToken previous) throws SyncAuthException {
        GoogleIdToken next;
        try {
            next = GoogleIdToken.parse(provider.obtainIdToken(false));
        } catch (SyncAuthException error) {
            dropExpired();
            throw new SyncAuthException(SyncAuthException.NOT_SIGNED_IN, "Your Google sign-in expired. Sign in again.");
        }
        if (!next.subject.equals(previous.subject) || next.isExpired(nowEpochSeconds.getAsLong())) {
            dropExpired();
            throw new SyncAuthException(SyncAuthException.NOT_SIGNED_IN, "Your Google sign-in expired. Sign in again.");
        }
        write(next.token);
        return next;
    }

    private void dropExpired() {
        try {
            store.clear();
        } catch (IOException ignored) {
            // The next status read reports whatever the store still holds.
        }
    }

    private GoogleIdToken storedToken() throws SyncAuthException {
        String raw;
        try {
            raw = store.read();
        } catch (IOException error) {
            throw new SyncAuthException(SyncAuthException.STORAGE, "The Google sign-in could not be read from secure storage.");
        }
        if (raw == null || raw.isEmpty()) return null;
        try {
            return GoogleIdToken.parse(raw);
        } catch (SyncAuthException unreadable) {
            // A record we cannot use is a signed-out state the user can act on.
            dropExpired();
            return null;
        }
    }

    private void write(String token) throws SyncAuthException {
        try {
            store.write(token);
        } catch (IOException error) {
            throw new SyncAuthException(SyncAuthException.STORAGE, "The Google sign-in could not be saved to secure storage.");
        }
    }
}
