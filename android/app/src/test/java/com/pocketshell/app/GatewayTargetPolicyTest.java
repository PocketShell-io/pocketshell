package com.pocketshell.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * Issue #3060: the native mirror of core's gateway transport contract. The
 * same inputs must normalize to the same targets the web and desktop accept,
 * and every malformed, conflicting, or smuggled shape must fail closed.
 */
public final class GatewayTargetPolicyTest {

    // --- server URL normalization ------------------------------------------------

    @Test public void normalizesCanonicalOrigins() {
        assertEquals("wss://gateway.pocketshell.io", GatewayTargetPolicy.normalizeServerUrl("wss://gateway.pocketshell.io"));
        assertEquals("wss://gateway.pocketshell.io:8443", GatewayTargetPolicy.normalizeServerUrl("wss://gateway.pocketshell.io:8443"));
        assertEquals("wss://gateway.pocketshell.io", GatewayTargetPolicy.normalizeServerUrl("  https://gateway.pocketshell.io/ "));
        assertEquals("ws://127.0.0.1:8080", GatewayTargetPolicy.normalizeServerUrl("http://127.0.0.1:8080"));
        assertEquals("wss://gatewaypocket.io", GatewayTargetPolicy.normalizeServerUrl("WSS://GatewayPocket.IO"));
    }

    @Test public void rejectsSmuggledOrigins() {
        assertNull("userinfo is never silently dropped",
                GatewayTargetPolicy.normalizeServerUrl("wss://evil@example.com"));
        assertNull(GatewayTargetPolicy.normalizeServerUrl("wss://host/path"));
        assertNull(GatewayTargetPolicy.normalizeServerUrl("wss://host/?x=1"));
        assertNull(GatewayTargetPolicy.normalizeServerUrl("wss://host/#frag"));
        assertNull(GatewayTargetPolicy.normalizeServerUrl("ftp://host"));
        assertNull(GatewayTargetPolicy.normalizeServerUrl("not a url"));
        assertNull(GatewayTargetPolicy.normalizeServerUrl(""));
        assertNull(GatewayTargetPolicy.normalizeServerUrl(null));
        assertNull(GatewayTargetPolicy.normalizeServerUrl("wss://user:pass@host"));
    }

    // --- targets -----------------------------------------------------------------

    @Test public void normalizesStrictTargets() {
        GatewayTargetPolicy.Target target = GatewayTargetPolicy.normalizeTarget(
                "https://gateway.example.io/", "host-1");
        assertNotNull(target);
        assertEquals("wss://gateway.example.io", target.serverUrl);
        assertEquals("host-1", target.deviceId);
    }

    @Test public void rejectsMalformedTargets() {
        assertNull(GatewayTargetPolicy.normalizeTarget(null, "host-1"));
        assertNull(GatewayTargetPolicy.normalizeTarget("wss://ok.example", null));
        assertNull(GatewayTargetPolicy.normalizeTarget("wss://ok.example", 42));
        assertNull(GatewayTargetPolicy.normalizeTarget("wss://ok.example", "ab"));
        assertNull("a leading separator is not a device id",
                GatewayTargetPolicy.normalizeTarget("wss://ok.example", "-bad"));
        assertNull(GatewayTargetPolicy.normalizeTarget("wss://ok.example", "x".repeat(65)));
        assertNotNull("the 64-char boundary is valid",
                GatewayTargetPolicy.normalizeTarget("wss://ok.example", "x".repeat(64)));
    }

    // --- dial endpoint ------------------------------------------------------------

    @Test public void buildsTheExactSshPath() {
        assertEquals("/api/v1/hosts/host-1/ssh", GatewayTargetPolicy.sshPath("host-1"));
        assertEquals("the colon is encoded like encodeURIComponent",
                "/api/v1/hosts/a%3Ab.c-d/ssh", GatewayTargetPolicy.sshPath("a:b.c-d"));
    }

    @Test public void productionDialsAreWssOnly() {
        GatewayTargetPolicy.Target secure = GatewayTargetPolicy.normalizeTarget("wss://gw.example", "host-1");
        GatewayTargetPolicy.Endpoint endpoint = GatewayTargetPolicy.endpoint(secure, false);
        assertTrue(endpoint.secure);
        assertEquals(443, endpoint.port);
        assertEquals("gw.example", endpoint.host);
        assertEquals("/api/v1/hosts/host-1/ssh", endpoint.path);

        GatewayTargetPolicy.Target insecure = GatewayTargetPolicy.normalizeTarget("ws://gw.example", "host-1");
        try {
            GatewayTargetPolicy.endpoint(insecure, false);
            fail("plain ws:// must never dial without the test-only flag");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("test-only"));
        }
        try {
            GatewayTargetPolicy.endpoint(insecure, true);
            fail("even with the flag, a non-loopback ws:// host is refused");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("loopback"));
        }
    }

    @Test public void theTestOnlyInsecureFlagDialsLoopbackOnly() {
        GatewayTargetPolicy.Target loopback = GatewayTargetPolicy.normalizeTarget("http://127.0.0.1:8080", "host-1");
        GatewayTargetPolicy.Endpoint endpoint = GatewayTargetPolicy.endpoint(loopback, true);
        assertFalse(endpoint.secure);
        assertEquals(8080, endpoint.port);
        assertTrue(GatewayTargetPolicy.isLoopbackHost("localhost"));
        assertTrue(GatewayTargetPolicy.isLoopbackHost("127.0.0.1"));
        assertTrue(GatewayTargetPolicy.isLoopbackHost("[::1]"));
        assertFalse(GatewayTargetPolicy.isLoopbackHost("gateway.example.io"));
    }

    // --- handshake frames -----------------------------------------------------------

    @Test public void buildsTheAuthFrame() throws Exception {
        String frame = GatewayTargetPolicy.buildAuthFrame("routing-token", "host-1");
        org.json.JSONObject parsed = new org.json.JSONObject(frame);
        assertEquals("auth", parsed.getString("type"));
        assertEquals(1, parsed.getInt("v"));
        assertEquals("routing-token", parsed.getString("token"));
        assertEquals("host-1", parsed.getString("device_id"));
    }

    @Test public void parsesAReadyFrame() throws Exception {
        GatewayTargetPolicy.Handshake handshake = GatewayTargetPolicy.parseHandshakeFrame(
                "{\"type\":\"ready\",\"v\":1,\"device_id\":\"host-1\",\"ssh_host_key\":\"ssh-ed25519 AAAA\"}");
        assertTrue(handshake.ready);
        assertEquals("host-1", handshake.deviceId);
        assertEquals("ssh-ed25519 AAAA", handshake.advisoryHostKey);
    }

    @Test public void parsesAnErrorFrame() throws Exception {
        GatewayTargetPolicy.Handshake handshake = GatewayTargetPolicy.parseHandshakeFrame(
                "{\"type\":\"error\",\"v\":1,\"code\":\"host_offline\",\"message\":\"later\"}");
        assertFalse(handshake.ready);
        assertEquals("host_offline", handshake.errorCode);
    }

    @Test public void malformedHandshakesRefuseWithoutEcho() {
        String[] malformed = {
                "not json",
                "42",
                "\"a string\"",
                "[]",
                "{\"type\":\"ready\",\"v\":2,\"device_id\":\"h\",\"ssh_host_key\":\"k\"}",
                "{\"type\":\"ready\",\"v\":1,\"ssh_host_key\":\"k\"}",
                "{\"type\":\"ready\",\"v\":1,\"device_id\":\"h\"}",
                "{\"type\":\"error\",\"v\":1}",
                "{\"type\":\"mystery\",\"v\":1}",
        };
        for (String frame : malformed) {
            try {
                GatewayTargetPolicy.parseHandshakeFrame(frame);
                fail("malformed handshake must refuse: " + frame);
            } catch (GatewayTargetPolicy.MalformedHandshakeException expected) {
                // The reason is one fixed sentence; no frame content is echoed.
                assertFalse(expected.getMessage().contains("host_offline"));
                assertFalse(expected.getMessage().contains("AAAA"));
            }
        }
    }

    // --- close codes -----------------------------------------------------------------

    @Test public void classifiesTheProtocolCloseVocabulary() {
        assertEquals("unauthorized", GatewayTargetPolicy.classifyClose(4401).kind);
        assertEquals("host_offline", GatewayTargetPolicy.classifyClose(4503).kind);
        assertEquals("quota", GatewayTargetPolicy.classifyClose(4429).kind);
        assertEquals("abnormal", GatewayTargetPolicy.classifyClose(1006).kind);
    }

    // --- fingerprints ----------------------------------------------------------------

    @Test public void normalizesSha256Fingerprints() {
        String body = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopq";
        assertEquals("base64 fingerprints are case-preserved, not lowercased",
                "SHA256:" + body,
                GatewayTargetPolicy.normalizeSha256Fingerprint("256 SHA256:" + body + " host (ED25519)"));
        assertEquals("SHA256:" + body,
                GatewayTargetPolicy.normalizeSha256Fingerprint("sha256:" + body + "="));
        assertNull(GatewayTargetPolicy.normalizeSha256Fingerprint("SHA256:tooshort"));
        assertNull(GatewayTargetPolicy.normalizeSha256Fingerprint("md5:aa:bb"));
        assertNull(GatewayTargetPolicy.normalizeSha256Fingerprint(null));
        assertNull("an internal '=' is not decodable base64",
                GatewayTargetPolicy.normalizeSha256Fingerprint("SHA256:A=b" + "0".repeat(40)));
    }

    // --- #3086: gateway verdicts that reach core as GATEWAY_CLOSED ---------------------

    @Test public void onlyApplicationCloseCodesAreGatewayVerdicts() {
        // core GATEWAY_VERDICT_CLOSE_CODE_MIN..MAX (core #48): 4000–4999 only.
        for (int code : new int[] {4000, 4400, 4401, 4403, 4404, 4408, 4429, 4503, 4999}) {
            org.junit.Assert.assertTrue(String.valueOf(code), GatewayTargetPolicy.isGatewayCloseCode(code));
        }
        // Protocol-range closes a peer can send (1000 normal, 1001 going away,
        // 1011 server error, 3xxx registered), local-only (RFC 6455 §7.4.1)
        // and Java-WebSocket's never-opened pseudo-codes are not verdicts.
        for (int code : new int[] {-3, -2, -1, 0, 999, 1000, 1001, 1005, 1006, 1011, 1015, 3000, 3999, 5000}) {
            org.junit.Assert.assertFalse(String.valueOf(code), GatewayTargetPolicy.isGatewayCloseCode(code));
        }
    }

    @Test public void clientRouteErrorFramesMapToTheGatewaysCloseCodes() {
        org.junit.Assert.assertEquals(4400, GatewayTargetPolicy.closeCodeForErrorFrame("protocol"));
        org.junit.Assert.assertEquals(4401, GatewayTargetPolicy.closeCodeForErrorFrame("unauthorized"));
        org.junit.Assert.assertEquals(4403, GatewayTargetPolicy.closeCodeForErrorFrame("forbidden"));
        org.junit.Assert.assertEquals(4403, GatewayTargetPolicy.closeCodeForErrorFrame("revoked"));
        org.junit.Assert.assertEquals(4404, GatewayTargetPolicy.closeCodeForErrorFrame("not_found"));
        org.junit.Assert.assertEquals(4408, GatewayTargetPolicy.closeCodeForErrorFrame("timeout"));
        org.junit.Assert.assertEquals(4429, GatewayTargetPolicy.closeCodeForErrorFrame("quota"));
        org.junit.Assert.assertEquals(4503, GatewayTargetPolicy.closeCodeForErrorFrame("host_offline"));
        for (String unknown : new String[] {null, "", "internal", "challenge", "signature", "HOST_OFFLINE"}) {
            org.junit.Assert.assertEquals(String.valueOf(unknown), GatewayTunnel.GatewayTunnelException.NO_CLOSE_CODE,
                    GatewayTargetPolicy.closeCodeForErrorFrame(unknown));
        }
    }

    @Test public void the4400AdviceMatchesCore() {
        org.junit.Assert.assertEquals("The gateway rejected the request — update PocketShell.",
                GatewayTargetPolicy.classifyClose(4400).userMessage);
    }
}
