package com.pocketshell.app;

/**
 * The gateway routing token, held natively and in memory only (#3086).
 *
 * <p>The broker ({@code POST /gateway/token}) mints a short-lived (at most
 * 5 minutes) JWT that only ADMITS a gateway route; it never authenticates
 * to the host. This class keeps the newest one for the signed-in account so
 * a reconnect ladder does not mint per attempt, and mints a fresh one when
 * the cached token would not outlive the attempt that needs it. The token
 * is never written to storage, logged, put in an error message, or returned
 * over the Capacitor bridge: the only consumer is {@link GatewayTunnel}'s
 * auth frame. {@link Issued#toString()} redacts it.
 */
final class GatewayRoutingTokens {
    /** A freshly minted token must leave at least this long to be usable
     * at all (the broker caps it by the Google token's own expiry). */
    static final long MIN_FRESH_LIFETIME_MS = 10_000L;
    /** A cached token is reused only if it outlives the attempt's whole
     * connect budget by this much: the gateway checks it at the auth frame,
     * which the attempt sends well before its deadline. */
    static final long REUSE_SLACK_MS = 5_000L;

    /** One token handed to one dial attempt. */
    static final class Issued {
        final String token;
        final long expiresAtEpochMs;
        /** True when this attempt got the cached token rather than a mint. */
        final boolean reused;

        Issued(String token, long expiresAtEpochMs, boolean reused) {
            this.token = token;
            this.expiresAtEpochMs = expiresAtEpochMs;
            this.reused = reused;
        }

        @Override
        public String toString() {
            return "GatewayRoutingToken{expiresAt=" + expiresAtEpochMs + ", reused=" + reused + ", token=<redacted>}";
        }
    }

    private final GatewayTokenBroker.LongSupplierNow clock;
    private final GatewayTokenBroker broker;
    private String cachedSubject;
    private GatewayTokenBroker.RoutingToken cached;

    GatewayRoutingTokens(GatewayTokenBroker.LongSupplierNow clock) {
        this.clock = clock;
        this.broker = new GatewayTokenBroker(clock);
    }

    /**
     * A token for {@code subject} that stays valid for the next
     * {@code requiredValidityMs} (the attempt's remaining connect budget),
     * reusing the cached one when it does, and minting (one broker exchange)
     * when it does not, when the account changed, or when {@code forceFresh}
     * is set. Expiry is judged on this class's own clock only.
     */
    Issued issue(GatewaySyncSession session, String subject, long requiredValidityMs, boolean forceFresh)
            throws SyncAuthException, GatewayTokenBroker.GatewayBrokerException {
        synchronized (this) {
            if (!forceFresh && cached != null && subject.equals(cachedSubject)
                    && cached.expiresAtEpochMs > clock.getAsLong() + Math.max(0, requiredValidityMs) + REUSE_SLACK_MS) {
                return new Issued(cached.token, cached.expiresAtEpochMs, true);
            }
            cached = null;
            cachedSubject = null;
        }
        // The exchange runs outside the lock: two concurrent dials may both
        // mint, which is harmless; a slow broker must not block the other.
        GatewayTokenBroker.RoutingToken minted = broker.parse(session.exchange());
        if (minted.expiresAtEpochMs - clock.getAsLong() < MIN_FRESH_LIFETIME_MS) {
            throw new GatewayTokenBroker.GatewayBrokerException(GatewayTokenBroker.GatewayBrokerException.BAD_RESPONSE,
                    "The gateway credential expires too soon to use.");
        }
        synchronized (this) {
            cached = minted;
            cachedSubject = subject;
        }
        return new Issued(minted.token, minted.expiresAtEpochMs, false);
    }

    /** Forget {@code issued} if it is still the cached token (a 4401 for it). */
    synchronized void invalidate(Issued issued) {
        if (cached != null && issued != null && cached.token.equals(issued.token)) {
            cached = null;
            cachedSubject = null;
        }
    }

    /** Forget any cached token. */
    synchronized void clear() {
        cached = null;
        cachedSubject = null;
    }
}
