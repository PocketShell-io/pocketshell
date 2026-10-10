package com.pocketshell.app;

import java.net.URI;
import java.util.Base64;

/**
 * The Android mirror of pocketshell-core's {@code gatewayTransport.ts}
 * contract (pocketshell-gateway-tunnel v1) at core pin 4096fc569e: target
 * validation, the dial endpoint, the JSON handshake frames, the close-code
 * vocabulary, and the fail-closed host-key pin verdict.
 *
 * <p>This is the ONE place the native transport interprets gateway-shaped
 * data. Every check fails closed: anything that is not exactly the v1
 * contract is refused, never repaired, never projected into an ordinary
 * direct-SSH target. The display {@code hostname}/{@code port} of a gateway
 * host are labels only — nothing here ever resolves, connects to, or embeds
 * them in an endpoint.
 *
 * <p>Trust rules (see core's module header): the Google ID token never rides
 * this transport — only the broker-minted routing token, inside the one auth
 * TEXT frame; {@code ready.ssh_host_key} is the gateway's own enrollment
 * record and is advisory — it can never establish or replace trust; trust
 * comes only from an independently provisioned SHA-256 fingerprint pin
 * compared against the key the host actually presents during the SSH
 * handshake.
 */
final class GatewayTargetPolicy {
    /** The per-host client WebSocket route (server: PathClientSSH). */
    static final String SSH_PATH_PREFIX = "/api/v1/hosts/";
    /** Multiplexer/protocol version (server: ProtocolVersion). */
    static final int PROTOCOL_VERSION = 1;
    /** One inbound WebSocket message after the handshake (core
     * GATEWAY_MAX_WS_MESSAGE_BYTES): the server caps its own frames at 1 MiB
     * plus overhead; the WS draft is bounded to exactly this BEFORE any
     * payload allocation. */
    static final int MAX_WS_MESSAGE_BYTES = (1 << 20) + (64 << 10);
    /** The client gives the whole auth→ready exchange 30s (core
     * GATEWAY_HANDSHAKE_TIMEOUT_MS). */
    static final long HANDSHAKE_TIMEOUT_MS = 30_000L;

    /** Same shape the Go registry enforces (identity: deviceIDPattern). */
    private static final String DEVICE_ID_PATTERN = "[A-Za-z0-9][A-Za-z0-9._:-]{2,63}";

    private GatewayTargetPolicy() {
    }

    /** A validated gateway target: the only shape a gateway dial accepts. */
    static final class Target {
        /** Canonical {@code wss://host[:port]} base — no path, query, fragment, userinfo. */
        final String serverUrl;
        /** The enrolled host-agent id to reach through the gateway. */
        final String deviceId;

        Target(String serverUrl, String deviceId) {
            this.serverUrl = serverUrl;
            this.deviceId = deviceId;
        }

        /** The host the WebSocket actually dials (never the display hostname). */
        String tlsHost() {
            return hostOf(serverUrl);
        }
    }

    /**
     * Normalize a raw gateway base URL to its canonical origin, or null when
     * it is not a usable gateway origin. Accepts {@code wss://}, {@code ws://},
     * and the {@code https://}/{@code http://} spellings (mapped to wss/ws).
     * The origin only: path, query, fragment and USERINFO are all rejected, so
     * nothing can smuggle a token or a different destination into the dial.
     */
    static String normalizeServerUrl(String input) {
        if (input == null) return null;
        String trimmed = input.trim();
        if (trimmed.isEmpty()) return null;
        String candidate = trimmed;
        if (trimmed.regionMatches(true, 0, "https://", 0, 8)) {
            candidate = "wss://" + trimmed.substring(8);
        } else if (trimmed.regionMatches(true, 0, "http://", 0, 7)) {
            candidate = "ws://" + trimmed.substring(7);
        }
        URI uri;
        try {
            uri = new URI(candidate);
        } catch (Exception error) {
            return null;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!"ws".equals(scheme) && !"wss".equals(scheme)) return null;
        // Tightened past core's URL-based check: a userinfo component is never
        // silently dropped — it would smuggle credentials or a fake authority.
        if (uri.getRawUserInfo() != null) return null;
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) return null;
        String host = uri.getHost();
        if (host == null || host.isEmpty()) return null;
        if (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !"/".equals(uri.getRawPath())) return null;
        int port = uri.getPort();
        String hostPart = port == -1 ? host.toLowerCase(java.util.Locale.ROOT)
                : host.toLowerCase(java.util.Locale.ROOT) + ":" + port;
        return scheme + "://" + hostPart;
    }

    static boolean isValidDeviceId(String deviceId) {
        return deviceId != null && deviceId.matches(DEVICE_ID_PATTERN);
    }

    /**
     * Strictly parse a raw (JSON-round-tripped) gateway target. Null unless
     * BOTH fields normalize — callers treat null as "refuse the dial", never
     * as "treat as an ordinary host".
     */
    static Target normalizeTarget(Object rawServerUrl, Object rawDeviceId) {
        if (!(rawServerUrl instanceof String) || !(rawDeviceId instanceof String)) return null;
        String serverUrl = normalizeServerUrl((String) rawServerUrl);
        String deviceId = ((String) rawDeviceId).trim();
        if (serverUrl == null || !isValidDeviceId(deviceId)) return null;
        return new Target(serverUrl, deviceId);
    }

    /**
     * The exact path a client dials, with the device id percent-encoded the
     * way core's {@code encodeURIComponent} does. The base URL carries no
     * path, query or fragment by {@link #normalizeServerUrl}, so the dial URL
     * can never carry more than the fixed prefix and the device id — and no
     * secret is ever URL-carried (the routing token travels in the auth TEXT
     * frame only).
     */
    static String sshPath(String deviceId) {
        return SSH_PATH_PREFIX + percentEncode(deviceId) + "/ssh";
    }

    /** The endpoint the WebSocket opens: scheme, TLS host, port, path. */
    static final class Endpoint {
        final boolean secure;
        final String host;
        final int port;
        final String path;

        Endpoint(boolean secure, String host, int port, String path) {
            this.secure = secure;
            this.host = host;
            this.port = port;
            this.path = path;
        }
    }

    /**
     * Build the dial endpoint from a validated target. Production is WSS
     * only; {@code ws://} needs the explicit test-only flag AND a loopback
     * host — a flag the shipped plugin never sets, so it cannot arrive from
     * any synced target or stored record.
     */
    static Endpoint endpoint(Target target, boolean allowInsecureWs) {
        URI uri;
        try {
            uri = new URI(target.serverUrl);
        } catch (Exception error) {
            throw new IllegalArgumentException("not a gateway URL");
        }
        boolean secure = "wss".equals(uri.getScheme());
        if (!secure && !"ws".equals(uri.getScheme())) throw new IllegalArgumentException("not a gateway URL scheme");
        String host = uri.getHost();
        int port = uri.getPort();
        if (secure) {
            return new Endpoint(true, host, port == -1 ? 443 : port, sshPath(target.deviceId));
        }
        if (!allowInsecureWs || !isLoopbackHost(host)) {
            throw new IllegalArgumentException("refusing unencrypted ws:// — development gateways need the explicit test-only loopback flag");
        }
        return new Endpoint(false, host, port == -1 ? 80 : port, sshPath(target.deviceId));
    }

    static boolean isLoopbackHost(String host) {
        if (host == null) return false;
        String bare = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1)
                : host;
        return "localhost".equalsIgnoreCase(bare) || "127.0.0.1".equals(bare) || "::1".equals(bare) || "0:0:0:0:0:0:0:1".equals(bare);
    }

    /**
     * Build the exact auth TEXT frame: fields in the protocol's order. The
     * routing token travels here and NOWHERE else — never in a URL, header,
     * or log.
     */
    static String buildAuthFrame(String routingToken, String deviceId) {
        try {
            return new org.json.JSONObject()
                    .put("type", "auth")
                    .put("v", PROTOCOL_VERSION)
                    .put("token", routingToken)
                    .put("device_id", deviceId)
                    .toString();
        } catch (org.json.JSONException error) {
            throw new IllegalStateException("The gateway auth frame could not be serialized.", error);
        }
    }

    /** One strict parse of a handshake TEXT frame. */
    static final class Handshake {
        final boolean ready;
        final String deviceId;
        /** The gateway's enrollment record — ADVISORY only, never trusted. */
        final String advisoryHostKey;
        final String errorCode;
        final String errorMessage;

        private Handshake(boolean ready, String deviceId, String advisoryHostKey, String errorCode, String errorMessage) {
            this.ready = ready;
            this.deviceId = deviceId;
            this.advisoryHostKey = advisoryHostKey;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
        }

        static Handshake ready(String deviceId, String advisoryHostKey) {
            return new Handshake(true, deviceId, advisoryHostKey, null, null);
        }

        static Handshake error(String code, String message) {
            return new Handshake(false, null, null, code, message);
        }
    }

    /** The handshake was not exactly a v1 ready/error object. */
    static final class MalformedHandshakeException extends Exception {
        MalformedHandshakeException(String reason) {
            super(reason);
        }
    }

    /**
     * Strict parse of one handshake TEXT frame. Anything that is not exactly
     * a v1 {@code ready} or {@code error} object is malformed — a handshake
     * is never guessed into shape, and malformed input is refused without
     * echoing its contents.
     */
    static Handshake parseHandshakeFrame(String text) throws MalformedHandshakeException {
        Object parsed;
        try {
            parsed = new org.json.JSONTokener(text).nextValue();
        } catch (Exception error) {
            throw new MalformedHandshakeException("handshake frame is not JSON");
        }
        if (!(parsed instanceof org.json.JSONObject)) {
            throw new MalformedHandshakeException("handshake frame is not an object");
        }
        org.json.JSONObject frame = (org.json.JSONObject) parsed;
        if (!frame.has("v") || frame.optInt("v", Integer.MIN_VALUE) != PROTOCOL_VERSION) {
            throw new MalformedHandshakeException("handshake version is not supported");
        }
        String type = frame.optString("type", "");
        if ("ready".equals(type)) {
            String deviceId = frame.optString("device_id", null);
            String advisoryKey = frame.optString("ssh_host_key", null);
            if (deviceId == null || advisoryKey == null) {
                throw new MalformedHandshakeException("ready frame missing device_id or ssh_host_key");
            }
            return Handshake.ready(deviceId, advisoryKey);
        }
        if ("error".equals(type)) {
            String code = frame.optString("code", null);
            String message = frame.optString("message", null);
            if (code == null || message == null) {
                throw new MalformedHandshakeException("error frame missing code or message");
            }
            return Handshake.error(code, message);
        }
        throw new MalformedHandshakeException("unexpected handshake type");
    }

    /** The close-code vocabulary the Go gateway documents, plus anonymous drops. */
    static final class CloseKind {
        final String kind;
        final String userMessage;

        CloseKind(String kind, String userMessage) {
            this.kind = kind;
            this.userMessage = userMessage;
        }
    }

    static CloseKind classifyClose(int code) {
        switch (code) {
            case 4400: return new CloseKind("protocol", "The gateway rejected this connection as malformed.");
            case 4401: return new CloseKind("unauthorized", "Your sign-in expired — sign in again and retry.");
            case 4403: return new CloseKind("forbidden", "This PocketShell account cannot reach that host.");
            case 4404: return new CloseKind("not_found", "That host is not registered on the gateway.");
            case 4408: return new CloseKind("timeout", "The gateway took too long to answer.");
            case 4429: return new CloseKind("quota", "Too many open sessions — close one and retry.");
            case 4503: return new CloseKind("host_offline",
                    "The host is not connected to the gateway right now — check that its agent is running.");
            default: return new CloseKind("abnormal", "The connection to the gateway closed before it was ready.");
        }
    }

    // --- fingerprint + host-key pin verdicts -----------------------------------

    /**
     * Normalize a SHA-256 fingerprint to the OpenSSH display form
     * {@code SHA256:<43 unpadded base64 chars}>. Accepts {@code ssh-keygen -lf}
     * output, a bare {@code SHA256:…}, padding, and any prefix case. Null for
     * anything else (md5 lines, hex, prose).
     */
    static String normalizeSha256Fingerprint(String input) {
        if (input == null) return null;
        java.util.regex.Matcher match = java.util.regex.Pattern
                .compile("SHA256:\\s*([A-Za-z0-9+/=]+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(input);
        if (!match.find()) return null;
        String body = match.group(1).replaceAll("=+$", "");
        if (body.length() != 43) return null;
        try {
            String padded = body + "=".repeat((4 - (body.length() % 4)) % 4);
            if (Base64.getDecoder().decode(padded).length != 32) return null;
        } catch (IllegalArgumentException error) {
            return null;
        }
        return "SHA256:" + body;
    }

    /** The three ways a dial can relate to a pin — no "unknown → ask" arm:
     * gateway mode fails closed; an absent pin is a refusal, not a TOFU
     * prompt, because the gateway can never be trusted to introduce the key. */
    static final String PIN_TRUSTED = "trusted";
    static final String PIN_MISMATCH = "mismatch";
    static final String PIN_UNPINNED = "unpinned";

    /**
     * The verdict that runs inside {@code PinVerifier}, BEFORE userauth: the
     * independently provisioned pin (paired out of band) against the
     * fingerprint of the key the host actually presented. The ready frame's
     * key advisory never reaches this method.
     */
    static String verifyHostKeyPin(String pinnedFingerprint, String presentedFingerprint) {
        if (pinnedFingerprint == null) return PIN_UNPINNED;
        String pinned = normalizeSha256Fingerprint(pinnedFingerprint);
        if (pinned == null) return PIN_UNPINNED;
        String presented = normalizeSha256Fingerprint(presentedFingerprint);
        if (presented == null) return PIN_MISMATCH;
        return pinned.equals(presented) ? PIN_TRUSTED : PIN_MISMATCH;
    }

    /**
     * Percent-encode exactly like the web's {@code encodeURIComponent}: the
     * unreserved set stays, everything else becomes strict uppercase %XX.
     */
    static String percentEncode(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '!' || c == '~' || c == '*' || c == '\'' || c == '(' || c == ')') {
                out.append(c);
            } else {
                byte[] bytes = String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                for (byte b : bytes) {
                    out.append('%')
                            .append("0123456789ABCDEF".charAt((b >> 4) & 0xF))
                            .append("0123456789ABCDEF".charAt(b & 0xF));
                }
            }
        }
        return out.toString();
    }

    private static String hostOf(String serverUrl) {
        try {
            return new URI(serverUrl).getHost();
        } catch (Exception error) {
            throw new IllegalArgumentException("not a gateway URL");
        }
    }
}
