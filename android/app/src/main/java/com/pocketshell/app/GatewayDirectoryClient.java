package com.pocketshell.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Account-bound device discovery. Routing credentials never cross the bridge. */
final class GatewayDirectoryClient {
    static final int MAX_RESPONSE_BYTES = 64 * 1024;
    static final int MAX_DEVICES = 256; // pocketshell-core gatewayDevices.ts
    private static final String DEVICES_PATH = "/identity/v1/devices";
    private final GatewaySyncSession session;
    private final GatewayRoutingTokens tokens;
    private final GoogleSyncSession.Transport transport;
    private final GatewayOriginAllowlist allowlist;
    private final String origin;

    static final class DirectoryException extends Exception {
        final String code;

        DirectoryException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    GatewayDirectoryClient(GatewaySyncSession session, GatewayRoutingTokens tokens,
            GoogleSyncSession.Transport transport, GatewayOriginAllowlist allowlist, String origin) {
        this.session = session;
        this.tokens = tokens;
        this.transport = transport;
        this.allowlist = allowlist;
        this.origin = GatewayTargetPolicy.normalizeServerUrl(origin);
    }

    /** Build configuration only; no JavaScript argument can select the credential destination. */
    static String serverUrlForBuild() {
        return BuildConfig.GATEWAY_LANE_TEST_ORIGIN.isEmpty()
                ? GatewayOriginAllowlist.PRODUCTION_ORIGIN : BuildConfig.GATEWAY_LANE_TEST_ORIGIN;
    }

    String serverUrl() throws DirectoryException {
        if (origin == null || !origin.startsWith("wss://") || !allowlist.allowsGatewayOrigin(origin)) {
            throw new DirectoryException(GatewayOriginAllowlist.NOT_ALLOWED,
                    "PocketShell can list hosts only on the configured PocketShell gateway.");
        }
        return origin;
    }

    JSONArray devices(String expectedEmail)
            throws DirectoryException, SyncAuthException, GatewayTokenBroker.GatewayBrokerException {
        String httpsOrigin = "https://" + serverUrl().substring("wss://".length());
        if (!allowlist.allowsHttpsOrigin(httpsOrigin)) {
            throw new DirectoryException(GatewayOriginAllowlist.NOT_ALLOWED,
                    "The gateway device-list address is not allowed.");
        }
        String subject = requireExpectedAccount(expectedEmail);
        GatewayRoutingTokens.Issued credential = tokens.issue(session, subject, 30_000, false);
        tokens.requireAccount(session, subject);
        GoogleSyncSession.Response response;
        try {
            // HttpSyncTransport bounds connect/read time and the body, refuses
            // redirects, and disconnects even on an error. Only this scoped
            // routing bearer reaches the fixed gateway identity endpoint.
            response = transport.send("GET", httpsOrigin + DEVICES_PATH,
                    Map.of("Authorization", "Bearer " + credential.token, "Accept", "application/json"), null);
        } catch (IOException failure) {
            throw new DirectoryException("GATEWAY_DIRECTORY_UNAVAILABLE",
                    "The PocketShell gateway could not be reached. Try again.");
        }
        tokens.requireAccount(session, subject);
        if (!subject.equals(requireExpectedAccount(expectedEmail))) {
            throw new DirectoryException("GATEWAY_ACCOUNT_CHANGED",
                    "The signed-in account changed. Refresh the host list.");
        }
        if (response.status == 401 || response.status == 403) {
            tokens.invalidate(credential);
            throw new DirectoryException("GATEWAY_DIRECTORY_FORBIDDEN",
                    "This PocketShell account cannot list gateway hosts. Sign in again and retry.");
        }
        if (response.status != 200) {
            throw new DirectoryException("GATEWAY_DIRECTORY_UNAVAILABLE",
                    "The PocketShell gateway could not list hosts. Try again.");
        }
        if (response.body == null || response.body.getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES
                || response.body.contains(credential.token)) throw malformed();
        return publicDevices(response.body);
    }

    private String requireExpectedAccount(String expectedEmail) throws SyncAuthException, DirectoryException {
        if (expectedEmail == null || expectedEmail.isEmpty() || !expectedEmail.equals(session.email())) {
            tokens.clear();
            throw new DirectoryException("GATEWAY_ACCOUNT_CHANGED",
                    "The signed-in account changed. Refresh the host list.");
        }
        return session.subject();
    }

    /** Copy only public directory fields; account ids, device keys and unknown fields stay native. */
    static JSONArray publicDevices(String body) throws DirectoryException {
        try {
            JSONTokener parser = new JSONTokener(body);
            Object value = parser.nextValue();
            if (!(value instanceof JSONObject) || parser.nextClean() != 0) throw malformed();
            Object rawDevices = ((JSONObject) value).opt("devices");
            if (!(rawDevices instanceof JSONArray)) throw malformed();
            JSONArray source = (JSONArray) rawDevices;
            if (source.length() > MAX_DEVICES) throw malformed();
            JSONArray devices = new JSONArray();
            Set<String> seen = new HashSet<>();
            for (int index = 0; index < source.length(); index++) {
                Object item = source.get(index);
                if (!(item instanceof JSONObject)) throw malformed();
                JSONObject row = (JSONObject) item;
                Object id = row.opt("id");
                if (!(id instanceof String) || !GatewayTargetPolicy.isValidDeviceId((String) id)
                        || !seen.add((String) id) || !(row.opt("revoked") instanceof Boolean)) throw malformed();
                JSONObject safe = new JSONObject().put("id", id).put("revoked", row.get("revoked"));
                if (row.has("ssh_host_key")) {
                    Object advisory = row.get("ssh_host_key");
                    if (!(advisory instanceof String) || ((String) advisory).length() > 16_384) throw malformed();
                    // Advisory metadata is never installed as a pairing/pin.
                    GatewayTargetPolicy.HostKey key = GatewayTargetPolicy.parseHostKeyLine((String) advisory);
                    if (key != null) safe.put("ssh_host_key", key.keyType + " " + key.keyB64);
                }
                if (row.has("presence")) {
                    Object rawPresence = row.get("presence");
                    if (!(rawPresence instanceof JSONObject)) throw malformed();
                    JSONObject presence = (JSONObject) rawPresence;
                    if (!(presence.opt("online") instanceof Boolean)) throw malformed();
                    JSONObject safePresence = new JSONObject().put("online", presence.get("online"))
                            .put("observed_at", timestamp(presence.opt("observed_at")));
                    if (presence.has("connected_since")) {
                        safePresence.put("connected_since", timestamp(presence.opt("connected_since")));
                    }
                    safe.put("presence", safePresence);
                }
                devices.put(safe);
            }
            return devices;
        } catch (JSONException | IllegalArgumentException invalid) {
            throw malformed();
        }
    }

    private static String timestamp(Object value) throws DirectoryException {
        if (!(value instanceof String) || !((String) value).matches(
                "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?(Z|[+-]\\d{2}:\\d{2})")) throw malformed();
        return (String) value; // The shared core parser owns calendar/timestamp interpretation.
    }

    private static DirectoryException malformed() {
        return new DirectoryException("GATEWAY_DIRECTORY_BAD_RESPONSE",
                "The gateway answered with a host list this version cannot read.");
    }
}
