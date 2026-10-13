package com.pocketshell.app;

import java.net.URI;
import java.util.Base64;

/**
 * The Android mirror of pocketshell-core's {@code gatewayTransport.ts}
 * contract (pocketshell-gateway-tunnel v1) at core pin 4096fc569e: target
 * validation, the dial endpoint, the JSON handshake frames, the close-code
 * vocabulary, and the host-key pin grammar (the verdict itself runs in
 * SshCapabilityPlugin.PinVerifier against the key the host presents).
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
            case 4400: return new CloseKind("protocol", "The gateway rejected the request — update PocketShell.");
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

    /**
     * Whether a remote WebSocket close code is a gateway verdict: the
     * application range 4000–4999 only (core
     * {@code GATEWAY_VERDICT_CLOSE_CODE_MIN..MAX}, core #48). A remote 1000,
     * 1001, 1011 or 3xxx close is an ordinary drop, the codes RFC 6455
     * reserves for local reporting (1005, 1006, 1015) never travel, and
     * Java-WebSocket's negative pseudo-codes mean the socket never opened;
     * none of those is reported as {@code GATEWAY_CLOSED}.
     */
    static boolean isGatewayCloseCode(int code) {
        return code >= 4000 && code <= 4999;
    }

    /**
     * The documented close code for a client-route handshake {@code error}
     * frame code — the exact pairs the gateway's {@code reject(...)} sends
     * (pocketshell-gateway internal/tunnel/service.go; docs/tunnel.md FH-1).
     * {@code revoked} (the agent route's 4403 code) is included so a
     * gateway that reuses it on the client route still classifies the same
     * way. The agent-only {@code challenge} code is stage-dependent (4408
     * before a challenge exists, 4401 after) and {@code signature} never
     * appears on the client route, so neither is guessed here. Unknown codes
     * (e.g. {@code internal}) return
     * {@link GatewayTunnel.GatewayTunnelException#NO_CLOSE_CODE}: the close
     * frame that follows decides.
     */
    static int closeCodeForErrorFrame(String code) {
        if (code == null) return GatewayTunnel.GatewayTunnelException.NO_CLOSE_CODE;
        switch (code) {
            case "protocol": return 4400;
            case "unauthorized": return 4401;
            case "forbidden":
            case "revoked": return 4403;
            case "not_found": return 4404;
            case "timeout": return 4408;
            case "quota": return 4429;
            case "host_offline": return 4503;
            default: return GatewayTunnel.GatewayTunnelException.NO_CLOSE_CODE;
        }
    }

    // --- fingerprint + host-key pin grammar ------------------------------------

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

    /** Host-key types a pin may name (pocketshell-cli gateway/pins.py KEY_TYPES). */
    static final java.util.List<String> HOST_KEY_TYPES = java.util.List.of(
            "ssh-ed25519", "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521", "ssh-rsa");
    /** Largest accepted public-key blob (pocketshell-cli MAX_KEY_BLOB_BYTES). */
    static final int MAX_HOST_KEY_BLOB_BYTES = 8192;

    /** A pinned public host key, as {@code gateway show --host-key} prints it. */
    static final class HostKey {
        final String keyType;
        final String keyB64;
        final String fingerprintSha256;

        HostKey(String keyType, String keyB64, String fingerprintSha256) {
            this.keyType = keyType;
            this.keyB64 = keyB64;
            this.fingerprintSha256 = fingerprintSha256;
        }
    }

    /**
     * Strictly parse the one {@code <keytype> <base64>} line that
     * {@code pocketshell gateway show --host-key} prints on the host — the
     * same grammar the CLI's own pin parser enforces: exactly two fields
     * separated by one space, printable ASCII only (no second line, no
     * {@code @cert-authority}/{@code @revoked} marker, no comment), a
     * supported key type, canonical padded base64, and a blob no larger than
     * 8 KiB that is a well-formed public key OF the stated type with nothing
     * trailing (the CLI's {@code _check_blob}: a 32-byte ed25519 key; the
     * matching curve and an uncompressed point for ECDSA; positive minimal
     * mpints, an odd exponent of at least 3 and a modulus of at least 2048
     * bits for RSA). Surrounding
     * whitespace from a paste (a trailing newline) is trimmed; anything else
     * is refused, never repaired. Null when the input is not such a line.
     */
    static HostKey parseHostKeyLine(String input) {
        if (input == null) return null;
        String line = input.strip();
        if (line.isEmpty()) return null;
        for (int index = 0; index < line.length(); index++) {
            char c = line.charAt(index);
            if (c < 0x20 || c > 0x7E) return null;
        }
        String[] fields = line.split(" ", -1);
        if (fields.length != 2 || fields[0].isEmpty() || fields[1].isEmpty()) return null;
        String keyType = fields[0];
        String keyB64 = fields[1];
        if (!HOST_KEY_TYPES.contains(keyType)) return null;
        if (!keyB64.matches("[A-Za-z0-9+/]+={0,2}") || keyB64.length() % 4 != 0) return null;
        byte[] blob;
        try {
            blob = Base64.getDecoder().decode(keyB64);
        } catch (IllegalArgumentException error) {
            return null;
        }
        if (!Base64.getEncoder().encodeToString(blob).equals(keyB64)) return null;
        if (blob.length > MAX_HOST_KEY_BLOB_BYTES || !wellFormedKeyBlob(keyType, blob)) return null;
        String fingerprint;
        try {
            fingerprint = "SHA256:" + Base64.getEncoder()
                    .encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(blob))
                    .replaceAll("=+$", "");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            return null;
        }
        return new HostKey(keyType, keyB64, fingerprint);
    }

    /** The structure check for {@link #parseHostKeyLine}; false on anything off. */
    private static boolean wellFormedKeyBlob(String keyType, byte[] blob) {
        WireReader reader = new WireReader(blob);
        byte[] inner = reader.string();
        if (inner == null || !keyType.equals(new String(inner, java.nio.charset.StandardCharsets.US_ASCII))) return false;
        switch (keyType) {
            case "ssh-ed25519": {
                byte[] key = reader.string();
                if (key == null || key.length != 32) return false;
                break;
            }
            case "ecdsa-sha2-nistp256":
            case "ecdsa-sha2-nistp384":
            case "ecdsa-sha2-nistp521": {
                String curve = keyType.substring("ecdsa-sha2-".length());
                int pointLength = "nistp256".equals(curve) ? 65 : "nistp384".equals(curve) ? 97 : 133;
                byte[] named = reader.string();
                if (named == null || !curve.equals(new String(named, java.nio.charset.StandardCharsets.US_ASCII))) return false;
                byte[] point = reader.string();
                if (point == null || point.length != pointLength || point[0] != 0x04) return false;
                break;
            }
            default: { // ssh-rsa
                java.math.BigInteger e = reader.positiveMpint();
                java.math.BigInteger n = reader.positiveMpint();
                if (e == null || n == null) return false;
                if (e.compareTo(java.math.BigInteger.valueOf(3)) < 0 || !e.testBit(0)) return false;
                if (n.bitLength() < 2048) return false;
                break;
            }
        }
        return reader.atEnd();
    }

    /** A bounds-checked SSH wire-format reader; every read returns null on truncation. */
    private static final class WireReader {
        private final byte[] data;
        private int position;

        WireReader(byte[] data) {
            this.data = data;
        }

        byte[] string() {
            if (data.length - position < 4) return null;
            long length = ((data[position] & 0xFFL) << 24) | ((data[position + 1] & 0xFFL) << 16)
                    | ((data[position + 2] & 0xFFL) << 8) | (data[position + 3] & 0xFFL);
            position += 4;
            if (length > data.length - position) return null;
            byte[] out = java.util.Arrays.copyOfRange(data, position, position + (int) length);
            position += (int) length;
            return out;
        }

        java.math.BigInteger positiveMpint() {
            byte[] raw = string();
            if (raw == null || raw.length == 0 || (raw[0] & 0x80) != 0) return null;
            if (raw.length > 1 && raw[0] == 0 && (raw[1] & 0x80) == 0) return null;
            return new java.math.BigInteger(1, raw);
        }

        boolean atEnd() {
            return position == data.length;
        }
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
}
