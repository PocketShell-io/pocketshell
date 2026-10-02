package com.pocketshell.app;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The claims PocketShell reads from a Google ID token: who (sub, email) and
 * until when (exp). Decoded without verifying the signature — the token comes
 * straight from Google Play services, and the sync API's JWT authorizer
 * verifies signature, issuer, audience and expiry on every request.
 *
 * <p>{@link #toString()} never includes the token.
 */
final class GoogleIdToken {
    final String token;
    final String subject;
    final String email;
    final long expiresAtEpochSeconds;

    private GoogleIdToken(String token, String subject, String email, long expiresAtEpochSeconds) {
        this.token = token;
        this.subject = subject;
        this.email = email;
        this.expiresAtEpochSeconds = expiresAtEpochSeconds;
    }

    /** Parse a compact JWS. Any malformed token is rejected without echoing it. */
    static GoogleIdToken parse(String token) throws SyncAuthException {
        if (token == null || token.isEmpty()) throw new SyncAuthException(SyncAuthException.FAILED, "Google returned no ID token.");
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || parts[1].isEmpty()) {
            throw new SyncAuthException(SyncAuthException.FAILED, "Google returned a malformed ID token.");
        }
        JSONObject claims;
        try {
            claims = new JSONObject(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException | JSONException error) {
            throw new SyncAuthException(SyncAuthException.FAILED, "Google returned a malformed ID token.");
        }
        String subject = claims.optString("sub", "");
        if (subject.isEmpty()) throw new SyncAuthException(SyncAuthException.FAILED, "The Google ID token names no account.");
        long expiresAt = claims.optLong("exp", 0L);
        if (expiresAt <= 0L) throw new SyncAuthException(SyncAuthException.FAILED, "The Google ID token has no expiry.");
        String email = claims.optString("email", "");
        return new GoogleIdToken(token, subject, email.isEmpty() ? null : email, expiresAt);
    }

    boolean isExpired(long nowEpochSeconds) {
        return nowEpochSeconds >= expiresAtEpochSeconds - GoogleSyncConfig.EXPIRY_SKEW_SECONDS;
    }

    @Override
    public String toString() {
        return "GoogleIdToken{subject=" + subject + ", exp=" + expiresAtEpochSeconds + "}";
    }
}
