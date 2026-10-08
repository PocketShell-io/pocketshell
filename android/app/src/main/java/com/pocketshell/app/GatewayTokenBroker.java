package com.pocketshell.app;

import java.util.regex.Pattern;

/**
 * The strict parser for the gateway broker exchange (issue #3060): the
 * native twin of the web client's {@code gatewayToken.ts} contract. The
 * account service mints a short-lived RS256 JWT that is valid ONLY for
 * gateway routing (issuer = the sync API base, audience =
 * {@code pocketshell-gateway}, scope = pocketshell.gateway, lifetime ≤ 5
 * minutes); the Google ID token itself never reaches the gateway.
 *
 * <p>Every violation fails closed. The response must be a 2xx whose body is
 * exactly the v1 shape — a plausible-JWT {@code token} (three base64url
 * segments, values not inspected — the gateway verifies signature and
 * claims), {@code token_type} exactly {@code Bearer}, and BOTH expiry
 * spellings from one clock: bounded-integer {@code expires_in} seconds and
 * positive-integer {@code expires_at} unix seconds, agreeing within a small
 * allowance, with a bounded lifetime and no already-expired mint. An endpoint
 * echoing the Google bearer back instead of minting is refused. Error bodies
 * surface their {@code {"error": code}} code only — never body text, which
 * could carry token material or an error page. Nothing is persisted and
 * nothing token-shaped is logged.
 */
final class GatewayTokenBroker {
    /** The broker answers in one round trip; anything slower is an outage. */
    static final long MAX_LIFETIME_MS = 5 * 60_000L + 60_000L;
    /** The broker's relative-TTL ceiling in seconds (the 5-minute contract). */
    static final long MAX_LIFETIME_SECS = 5 * 60L;
    /** How far apart the two expiry spellings may fall; both are stamped from
     * one clock in one response, so anything wider is broken or hostile. */
    static final long EXPIRY_AGREEMENT_ALLOWANCE_MS = 5_000L;
    /** The broker rejects bearers over 8192 chars; a minted JWT has three
     * base64url segments and is bounded by the same figure. */
    static final int MAX_TOKEN_CHARS = 8192;

    private static final Pattern JWT_SHAPE = Pattern.compile("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");

    private final LongSupplierNow nowMs;

    /** Injectable clock; production uses wall time. */
    interface LongSupplierNow {
        long getAsLong();
    }

    /** The minted routing token: native-only, held by the WS owner, never
     * returned over the Capacitor bridge. */
    static final class RoutingToken {
        final String token;
        final long expiresAtEpochMs;

        RoutingToken(String token, long expiresAtEpochMs) {
            this.token = token;
            this.expiresAtEpochMs = expiresAtEpochMs;
        }
    }

    /** A broker failure with a stable code; messages never carry tokens or bodies. */
    static final class GatewayBrokerException extends Exception {
        static final String SIGN_IN_REJECTED = "GATEWAY_BROKER_SIGN_IN_REJECTED";
        static final String UNAVAILABLE = "GATEWAY_BROKER_UNAVAILABLE";
        static final String BAD_RESPONSE = "GATEWAY_BROKER_BAD_RESPONSE";

        final String code;

        GatewayBrokerException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    GatewayTokenBroker() {
        this(System::currentTimeMillis);
    }

    GatewayTokenBroker(LongSupplierNow nowMs) {
        this.nowMs = nowMs;
    }

    /**
     * Parse one exchange answer into a routing token. Refuses every non-2xx,
     * unreadable, mis-shaped, echoed, unbounded, or expired answer.
     */
    RoutingToken parse(GoogleSyncSession.GatewayBrokerExchange exchange) throws GatewayBrokerException {
        GoogleSyncSession.Response response = exchange.response;
        if (response.status == 401 || response.status == 403) {
            throw new GatewayBrokerException(GatewayBrokerException.SIGN_IN_REJECTED,
                    "Your sign-in was not accepted for a gateway credential — sign in again.");
        }
        if (response.status < 200 || response.status >= 300) {
            throw new GatewayBrokerException(GatewayBrokerException.UNAVAILABLE,
                    "The account service could not mint a gateway credential right now.");
        }
        Object parsed;
        try {
            parsed = new org.json.JSONTokener(response.body).nextValue();
        } catch (Exception error) {
            throw unreadable();
        }
        if (!(parsed instanceof org.json.JSONObject)) throw unreadable();
        org.json.JSONObject body = (org.json.JSONObject) parsed;
        String token = body.optString("token", "");
        if (!plausibleJwt(token)) throw malformed();
        if (exchange.bearerEchoedBy(token)) {
            // The exchange must MINT a scoped token; an endpoint echoing the
            // bearer back is not the broker contract — refuse rather than
            // forward a sync-capable Google token to the gateway.
            throw malformed();
        }
        if (!"Bearer".equals(body.optString("token_type", ""))) throw malformed();

        // Both expiry spellings, both bounded, agreeing within the allowance;
        // `now` is taken at ARRIVAL so the network trip cannot stretch the
        // credential's accepted lifetime. Numbers only — a quoted "123" is
        // malformed, no coercion, no fallback.
        long now = nowMs.getAsLong();
        long expiresAtMs;
        Object rawAt = body.opt("expires_at");
        if (rawAt instanceof Number) {
            double raw = ((Number) rawAt).doubleValue();
            if (Math.floor(raw) != raw || raw <= 0 || raw > 4_000_000_000L) throw malformedExpiry();
            expiresAtMs = (long) raw * 1000L;
        } else {
            throw malformedExpiry();
        }
        Object rawInValue = body.opt("expires_in");
        if (rawInValue instanceof Number) {
            double rawIn = ((Number) rawInValue).doubleValue();
            if (Math.floor(rawIn) != rawIn || rawIn <= 0 || rawIn > MAX_LIFETIME_SECS) {
                throw malformedExpiry();
            }
            long expiresInMillis = (long) rawIn * 1000L;
            if (Math.abs(expiresAtMs - (now + expiresInMillis)) > EXPIRY_AGREEMENT_ALLOWANCE_MS) {
                throw new GatewayBrokerException(GatewayBrokerException.BAD_RESPONSE,
                        "The gateway credential's two expiries disagree — refusing it.");
            }
        } else {
            throw malformedExpiry();
        }

        long lifetimeMs = expiresAtMs - now;
        if (lifetimeMs <= 0) {
            throw new GatewayBrokerException(GatewayBrokerException.BAD_RESPONSE,
                    "The gateway credential arrived already expired.");
        }
        if (lifetimeMs > MAX_LIFETIME_MS) {
            throw new GatewayBrokerException(GatewayBrokerException.BAD_RESPONSE,
                    "The gateway credential outlives the broker contract — refusing it.");
        }
        return new RoutingToken(token, expiresAtMs);
    }

    private static boolean plausibleJwt(String token) {
        return token != null && !token.isEmpty() && token.length() <= MAX_TOKEN_CHARS && JWT_SHAPE.matcher(token).matches();
    }

    private static GatewayBrokerException unreadable() {
        return new GatewayBrokerException(GatewayBrokerException.BAD_RESPONSE,
                "The account service answered the credential exchange with something unreadable.");
    }

    private static GatewayBrokerException malformed() {
        return new GatewayBrokerException(GatewayBrokerException.BAD_RESPONSE,
                "The account service returned a malformed gateway credential.");
    }

    private static GatewayBrokerException malformedExpiry() {
        return new GatewayBrokerException(GatewayBrokerException.BAD_RESPONSE,
                "The gateway credential arrived with a malformed expiry.");
    }
}
