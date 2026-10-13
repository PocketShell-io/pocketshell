package com.pocketshell.app;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The ONE native allowlist of gateway origins a gateway credential may be
 * used with (#3086 slice 3, review finding B1). The broker's routing token
 * is a real credential for the gateway's device list, revoke and dial, so
 * before any token is minted, cached, sent, or a pairing stored, the
 * canonical origin must be exactly one of:
 *
 * <ul>
 *   <li>{@code wss://gateway.pocketshell.io} (port 443), in every build;
 *   <li>the gateway emulator lane's TLS front, {@code wss://localhost:<port>},
 *       compiled ONLY into the lane's {@code gwlane} debug build, by the same
 *       build-time switch that trusts its test CA ({@code BuildConfig
 *       .GATEWAY_LANE_TEST_ORIGIN}, empty in every other build).
 * </ul>
 *
 * <p>Nothing here can be widened at run time: no JS, localStorage, sync data
 * or plugin argument reaches it. The comparison is exact on (scheme, host,
 * port) after canonicalization — no suffix, prefix, look-alike, IP-literal
 * or punycode matching. The HTTPS form of an allowed origin (identity API
 * calls) is the same (host, port) over https.
 */
final class GatewayOriginAllowlist {
    /** The production PocketShell gateway. */
    static final String PRODUCTION_ORIGIN = "wss://gateway.pocketshell.io";
    /** Why a gateway credential use was refused for its origin. */
    static final String NOT_ALLOWED = "GATEWAY_ORIGIN_NOT_ALLOWED";

    private final Set<String> allowed;
    private final boolean plaintextForTesting;

    private GatewayOriginAllowlist(Set<String> allowed, boolean plaintextForTesting) {
        this.allowed = Collections.unmodifiableSet(allowed);
        this.plaintextForTesting = plaintextForTesting;
    }

    /** This build's allowlist: production, plus the lane origin only in the gwlane build. */
    static GatewayOriginAllowlist forBuild() {
        return of(BuildConfig.GATEWAY_LANE_TEST_ORIGIN);
    }

    /** Production plus at most one extra origin. Package-private: the build and JVM tests only. */
    static GatewayOriginAllowlist of(String extraOrigin) {
        return build(extraOrigin, false);
    }

    /** JVM tests only: production plus the loopback fixture, which may be plaintext ws://. */
    static GatewayOriginAllowlist forTesting(String fixtureOrigin) {
        return build(fixtureOrigin, true);
    }

    private static GatewayOriginAllowlist build(String extraOrigin, boolean plaintextForTesting) {
        Set<String> allowed = new LinkedHashSet<>();
        allowed.add(key(PRODUCTION_ORIGIN, false));
        if (extraOrigin != null && !extraOrigin.isEmpty()) {
            String extra = key(extraOrigin, plaintextForTesting);
            if (extra == null) throw new IllegalArgumentException("not a gateway origin");
            allowed.add(extra);
        }
        return new GatewayOriginAllowlist(allowed, plaintextForTesting);
    }

    /** Whether a gateway (ws/wss) origin may receive a gateway credential. */
    boolean allowsGatewayOrigin(String origin) {
        String key = key(origin, plaintextForTesting);
        return key != null && allowed.contains(key);
    }

    /** Whether an HTTPS origin is an allowed gateway's (identity API calls). */
    boolean allowsHttpsOrigin(String origin) {
        if (origin == null || !origin.regionMatches(true, 0, "https://", 0, 8)) return false;
        return allowsGatewayOrigin("wss://" + origin.substring(8));
    }

    /**
     * The exact (scheme, host, port) of a gateway origin, or null. Only
     * {@code wss} counts; default ports are made explicit; anything with
     * userinfo, a path, query or fragment is not an origin.
     */
    static String key(String origin, boolean allowPlaintext) {
        if (origin == null) return null;
        URI uri;
        try {
            uri = new URI(origin.trim());
        } catch (Exception error) {
            return null;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!"wss".equals(scheme) && !(allowPlaintext && "ws".equals(scheme))) return null;
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) return null;
        String path = uri.getRawPath();
        if (path != null && !path.isEmpty() && !"/".equals(path)) return null;
        String host = uri.getHost();
        if (host == null || host.isEmpty()) return null;
        int port = uri.getPort() != -1 ? uri.getPort() : ("wss".equals(scheme) ? 443 : 80);
        return scheme + "://" + host.toLowerCase(java.util.Locale.ROOT) + ":" + port;
    }
}
