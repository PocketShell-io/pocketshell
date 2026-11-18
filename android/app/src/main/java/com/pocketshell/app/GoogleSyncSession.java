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

        /** All sessions using one credential record must share its lifecycle lock and invalidation. */
        default AuthenticationState authenticationState() { return AuthenticationState.forScope(this); }
    }

    /** No bearer or Activity is retained here. The monitor orders credential changes across plugin workers. */
    static final class AuthenticationState {
        private static final java.util.Map<Object, AuthenticationState> SCOPES = new java.util.WeakHashMap<>();
        boolean invalidated;
        /** Backend-verified subject (issue #3137); bounded by {@link #verifiedUntil} and every credential change. */
        String verifiedSubject;
        long verifiedUntil;
        /** Bumped on every identity invalidation so an in-flight verification cannot cache a stale answer. */
        long identityGeneration;
        /**
         * Bumped on every credential replacement or removal (sign-in, sign-out, dropped record). A request
         * started under an older epoch may not renew, cache an identity, or drop the newer credentials.
         */
        long credentialEpoch;
        void forgetIdentity() {
            verifiedSubject = null;
            verifiedUntil = 0L;
            identityGeneration++;
        }
        static synchronized AuthenticationState forScope(Object scope) {
            return SCOPES.computeIfAbsent(scope, ignored -> new AuthenticationState());
        }
    }

    /** Mints a Google ID token. Production: Credential Manager. */
    public interface SignInProvider {
        /**
         * Google's account chooser, on the user's own "Sign in" tap only. There is
         * no silent variant (#3189): every Credential Manager call can show UI.
         */
        String obtainIdToken() throws SyncAuthException;

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

    private final AuthenticationState authentication;
    private final TokenStore store;
    private final SignInProvider provider;
    private final Transport transport;
    private final String apiBaseUrl;
    private final LongSupplier nowEpochSeconds;

    /** The plain-words transport failure; the exception class rides in {@link SyncAuthException#detail}. */
    static final String NETWORK_MESSAGE = "PocketShell sync can't be reached right now.";

    /** An expired, refused or revoked sign-in (#3189): shown as signed out, never renewed silently. */
    static final String SIGNED_OUT_MESSAGE = "You're signed out. Sign in to sync your servers.";

    /** A verified identity is reused for at most this long, and never past the ID token's own expiry. */
    static final long VERIFIED_IDENTITY_TTL_SECONDS = 60L;

    /** Debug timing sink ("backend GET /me 120ms"); the plugin routes it to logcat. Never carries a token. */
    static volatile java.util.function.Consumer<String> timingLog;

    private static void timing(String line) {
        java.util.function.Consumer<String> sink = timingLog;
        if (sink != null) sink.accept(line);
    }

    GoogleSyncSession(TokenStore store, SignInProvider provider, Transport transport, String apiBaseUrl,
            LongSupplier nowEpochSeconds) {
        this.store = store;
        this.authentication = store.authenticationState();
        this.provider = provider;
        this.transport = transport;
        this.apiBaseUrl = apiBaseUrl.endsWith("/") ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1) : apiBaseUrl;
        this.nowEpochSeconds = nowEpochSeconds;
    }

    /**
     * The signed-in account's stable subject (the Google {@code sub}), the
     * first half of the gateway pairing namespace. Reads the stored record
     * only — no renewal, no network; renewal happens where a token is spent.
     * The subject is an account identifier, not a secret.
     */
    String currentAccountSubject() throws SyncAuthException {
        synchronized (authentication) {
            GoogleIdToken token = storedToken();
            if (token == null) {
                throw new SyncAuthException(SyncAuthException.NOT_SIGNED_IN, "Sign in with Google first.");
            }
            return token.subject;
        }
    }

    /**
     * The account opening a saved server uses (issue #3189): the subject of the stored ID token, the
     * credential every gateway and sync request carries. No network: the open never waits on {@code /me}.
     * An expired token ends the session here exactly as at a request ({@link #signedOut()}), and a
     * revoked one is refused by the first authenticated request (401), which ends it too.
     * {@link #verifiedAccountId()} still checks the subject with the backend, in the background.
     */
    String currentAccountId() throws SyncAuthException {
        synchronized (authentication) {
            GoogleIdToken token = usableToken();
            if (!token.subject.matches("[0-9A-Za-z_-]{1,128}")) throw invalid();
            return "google:" + token.subject;
        }
    }

    /**
     * Verify this native Google subject with the fixed backend authorizer before exposing public identity.
     * A successful verification is reused for up to {@link #VERIFIED_IDENTITY_TTL_SECONDS} (and never past the
     * stored token's expiry) while the stored subject is unchanged; sign-in, sign-out, any 401 and any dropped
     * credential forget it. The network call runs outside the authentication lock.
     */
    String verifiedAccountId() throws SyncAuthException {
        String before;
        long generation;
        long epoch;
        GoogleIdToken token;
        synchronized (authentication) {
            token = storedToken();
            if (token == null) throw new SyncAuthException(SyncAuthException.NOT_SIGNED_IN, "Sign in with Google first.");
            before = token.subject;
            long now = nowEpochSeconds.getAsLong();
            if (before.equals(authentication.verifiedSubject) && now < authentication.verifiedUntil) {
                return "google:" + before;
            }
            generation = authentication.identityGeneration;
            epoch = authentication.credentialEpoch;
            if (token.isExpired(now)) token = signedOut();
        }
        Response response = send("GET", "/me", null, token);
        if (response.status == 401) {
            synchronized (authentication) {
                token = renewAfter401(token, epoch);
            }
            response = send("GET", "/me", null, token);
        }
        if (response.status == 401) {
            synchronized (authentication) {
                // Only the credentials this verification started with may be dropped.
                if (epoch == authentication.credentialEpoch) dropExpired();
            }
            throw new SyncAuthException(SyncAuthException.NOT_SIGNED_IN, "Sign in to verify your account.");
        }
        if (response.status != 200) throw new SyncAuthException(SyncAuthException.NETWORK, "Account verification is temporarily unavailable.");
        String verifiedSubject;
        try { verifiedSubject = new org.json.JSONObject(response.body).getString("sub"); }
        catch (Exception invalid) { throw new SyncAuthException(SyncAuthException.INVALID_REQUEST, "Account verification returned an invalid response."); }
        synchronized (authentication) {
            if (epoch != authentication.credentialEpoch) {
                // The account changed while this verification was in flight: say nothing about the new one.
                throw new SyncAuthException(SyncAuthException.NOT_SIGNED_IN, "The signed-in account changed. Try again.");
            }
            GoogleIdToken after = storedToken();
            if (!before.equals(verifiedSubject) || after == null || !before.equals(after.subject)) {
                dropExpired();
                throw new SyncAuthException(SyncAuthException.NOT_SIGNED_IN, "Sign in to verify your account.");
            }
            if (!before.matches("[0-9A-Za-z_-]{1,128}")) throw invalid();
            if (generation == authentication.identityGeneration) {
                long now = nowEpochSeconds.getAsLong();
                authentication.verifiedSubject = before;
                authentication.verifiedUntil = Math.min(now + VERIFIED_IDENTITY_TTL_SECONDS,
                        after.expiresAtEpochSeconds - GoogleSyncConfig.EXPIRY_SKEW_SECONDS);
            }
            return "google:" + before;
        }
    }

    Response listSettings() throws SyncAuthException {
        return sendAuthenticated("GET", "/settings", null, true);
    }

    /**
     * The dedicated gateway broker exchange: one empty-body POST
     * {@code /gateway/token} at the configured sync API base, carrying the
     * current account's ID token in the Authorization header only. The route
     * is fixed here — the gateway target, the display hostname, or any
     * response content can never choose the URL (issue #3060). The exchange
     * rides the same no-redirect, deadline- and size-bounded transport as
     * settings sync, and the token is renewed silently first, exactly like a
     * sync request — including the one retry after a silent renewal when the
     * broker answers 401 (the ID token can age out between the check and the
     * broker's authorizer).
     *
     * <p>The bearer never leaves this class except through
     * {@link GatewayBrokerExchange#bearerEchoedBy(String)} — an equality
     * answer, so the strict response parser can refuse an endpoint that
     * echoes the Google token back instead of minting a scoped one.
     */
    GatewayBrokerExchange gatewayTokenExchange() throws SyncAuthException {
        GoogleIdToken token;
        long epoch;
        synchronized (authentication) { token = usableToken(); epoch = authentication.credentialEpoch; }
        // Empty body by contract: an explicit zero-length body, so the
        // exchange is a POST with Content-Length: 0 on every HTTP stack.
        Response response = send("POST", "/gateway/token", "", token);
        if (response.status == 401) {
            synchronized (authentication) { token = renewAfter401(token, epoch); }
            response = send("POST", "/gateway/token", "", token);
        }
        return new GatewayBrokerExchange(response, token.token);
    }

    /** One broker exchange answer plus the bearer-echo check for its parse. */
    static final class GatewayBrokerExchange {
        final Response response;
        private final String bearer;

        GatewayBrokerExchange(Response response, String bearer) {
            this.response = response;
            this.bearer = bearer;
        }

        /** Whether a token parsed from the body is this exchange's Google bearer. */
        boolean bearerEchoedBy(String candidate) {
            return bearer != null && bearer.equals(candidate);
        }
    }

    /** Signed in means a stored token exists; an expired one is renewed on the next request. */
    Status status() throws SyncAuthException {
        synchronized (authentication) {
            GoogleIdToken token = storedToken();
            return token == null ? new Status(false, null) : new Status(true, token.email);
        }
    }

    /** The interactive sign-in: choose an account, store its ID token. */
    Status signIn() throws SyncAuthException {
        synchronized (authentication) {
            GoogleIdToken token = GoogleIdToken.parse(provider.obtainIdToken());
            authentication.forgetIdentity();
            authentication.credentialEpoch++;
            write(token.token);
            return new Status(true, token.email);
        }
    }

    /** Delete the stored token and forget the authorized account. */
    void signOut() throws SyncAuthException {
        synchronized (authentication) {
            authentication.invalidated = true;
            authentication.forgetIdentity();
            authentication.credentialEpoch++;
            try {
                store.clear();
            } catch (IOException error) {
                throw new SyncAuthException(SyncAuthException.STORAGE, "The Google sign-in could not be removed from secure storage.");
            } finally {
                provider.clearCredentialState();
            }
        }
    }

    /**
     * One authenticated sync API call. An expired token is renewed silently
     * first; a 401 is retried once after a silent renewal, because the token
     * can age out between the check and the server.
     */
    Response request(String method, String slot, String body) throws SyncAuthException {
        return sendAuthenticated(method, pathFor(method, slot, body), body, true);
    }

    /**
     * Fixed vault routes: status, verify, and account-bound atomic first
     * initialization. Reset and verifier replacement are not exposed.
     */
    Response requestVault(String method, String endpoint, String body) throws SyncAuthException {
        {
            String path;
            if ("GET".equals(method) && "vault/status".equals(endpoint) && body == null) {
                path = "/vault/status";
            } else if ("POST".equals(method) && "vault/verify".equals(endpoint) && body != null
                    && body.getBytes(StandardCharsets.UTF_8).length <= 1024) {
                path = "/vault/verify";
            } else if ("POST".equals(method) && "vault/initialize".equals(endpoint) && validVaultInitialization(body)) {
                path = "/vault/initialize";
            } else {
                throw invalid();
            }
            return sendAuthenticated(method, path, body, !"/vault/verify".equals(path));
        }
    }

    /** Token selection/renewal under the lock; the network exchange itself outside it. */
    private Response sendAuthenticated(String method, String path, String body, boolean retryAfterRenewal)
            throws SyncAuthException {
        GoogleIdToken token;
        long epoch;
        synchronized (authentication) { token = usableToken(); epoch = authentication.credentialEpoch; }
        Response response = send(method, path, body, token);
        if (response.status == 401 && retryAfterRenewal) {
            synchronized (authentication) { token = renewAfter401(token, epoch); }
            response = send(method, path, body, token);
        }
        return response;
    }

    /** Another caller may already have renewed the token this one just had refused. */
    private GoogleIdToken renewAfter401(GoogleIdToken used, long epoch) throws SyncAuthException {
        if (epoch != authentication.credentialEpoch) {
            // Signed out, signed in elsewhere or dropped since this request started: never resurrect it.
            throw new SyncAuthException(SyncAuthException.NOT_SIGNED_IN, "The Google sign-in changed during the request.");
        }
        GoogleIdToken stored = storedToken();
        if (stored != null && !stored.token.equals(used.token) && stored.subject.equals(used.subject)
                && !stored.isExpired(nowEpochSeconds.getAsLong())) return stored;
        return signedOut();
    }

    /** Account-bound vault IO remains on the original subject across asynchronous KDF work. */
    Response requestBound(String method, String slot, String endpoint, String body, String accountId) throws SyncAuthException {
        {
            if (accountId != null && (!accountId.matches("google:[0-9A-Za-z_-]{1,128}")
                    || !accountId.equals("google:" + currentAccountSubject()))) throw invalid();
            // First setup must always name the verified account; it cannot be an unscoped write.
            if ("vault/initialize".equals(endpoint) && accountId == null) throw invalid();
            if ("settings".equals(endpoint) && "GET".equals(method) && slot == null && body == null) return listSettings();
            if (endpoint != null) {
                if (slot != null) throw invalid();
                return requestVault(method, endpoint, body);
            }
            return request(method, slot, body);
        }
    }

    private static boolean validInitialVerifier(String body) {
        if (body == null || body.getBytes(StandardCharsets.UTF_8).length > 1024) return false;
        try {
            org.json.JSONObject value = new org.json.JSONObject(body);
            return value.length() == 2 && value.has("auth") && value.has("epoch")
                    && value.get("auth") instanceof String && value.getString("auth").matches("[A-Za-z0-9_-]{43}")
                    && value.get("epoch") instanceof Number && value.getDouble("epoch") == value.getLong("epoch")
                    && value.getLong("epoch") >= 1 && value.getLong("epoch") <= 9007199254740991L;
        } catch (Exception invalid) { return false; }
    }

    private static boolean validVaultInitialization(String body) {
        if (body == null || body.getBytes(StandardCharsets.UTF_8).length > 10000) return false;
        try {
            org.json.JSONObject value = new org.json.JSONObject(body);
            if (value.length() != 3 || !(value.get("data") instanceof String)
                    || value.getString("data").getBytes(StandardCharsets.UTF_8).length > 8192) return false;
            value.remove("data");
            return validInitialVerifier(value.toString());
        } catch (Exception invalid) { return false; }
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
        long started = System.nanoTime();
        try {
            Response response = transport.send(method, apiBaseUrl + path, Collections.unmodifiableMap(headers), body);
            if (response.status == 401) synchronized (authentication) { authentication.forgetIdentity(); }
            timing("backend " + method + " " + path + " " + response.status + " " + (System.nanoTime() - started) / 1_000_000L + "ms");
            return response;
        } catch (IOException error) {
            timing("backend " + method + " " + path + " failed " + (System.nanoTime() - started) / 1_000_000L + "ms");
            // The transport's message can name the host; it never carries the
            // header, but keep only the class so no future change can leak one.
            // The class is a detail behind "Copy details", never the message (#3189).
            throw new SyncAuthException(SyncAuthException.NETWORK, NETWORK_MESSAGE,
                    error.getClass().getSimpleName() + " on " + method + " " + path);
        }
    }

    private GoogleIdToken usableToken() throws SyncAuthException {
        GoogleIdToken token = storedToken();
        if (token == null) throw new SyncAuthException(SyncAuthException.NOT_SIGNED_IN, "Sign in with Google first.");
        return token.isExpired(nowEpochSeconds.getAsLong()) ? signedOut() : token;
    }

    /**
     * Issue #3189: an expired or refused token is never renewed behind the
     * user's back. Every Credential Manager call can show UI (even the
     * "silent" auto-select option shows a "Signing you in" sheet), and one
     * fired from resume pauses and resumes the activity, which used to fire
     * the next one: a sign-in sheet looping over the terminal. So a request
     * that finds the token unusable ends the session visibly instead: the
     * record is dropped and the caller gets NOT_SIGNED_IN, which the app shows
     * as "Signed out" with a Sign in banner. Sign-in UI only ever comes from
     * {@link #signIn()}, on the user's own tap.
     */
    private GoogleIdToken signedOut() throws SyncAuthException {
        dropExpired();
        throw new SyncAuthException(SyncAuthException.NOT_SIGNED_IN, SIGNED_OUT_MESSAGE);
    }

    private void dropExpired() {
        authentication.invalidated = true;
        authentication.forgetIdentity();
        authentication.credentialEpoch++;
        try {
            store.clear();
        } catch (IOException ignored) {
            // Shared invalidation refuses the old record even when deletion fails.
        }
    }

    private GoogleIdToken storedToken() throws SyncAuthException {
        if (authentication.invalidated) return null;
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
            authentication.invalidated = false;
        } catch (IOException error) {
            throw new SyncAuthException(SyncAuthException.STORAGE, "The Google sign-in could not be saved to secure storage.");
        }
    }
}
