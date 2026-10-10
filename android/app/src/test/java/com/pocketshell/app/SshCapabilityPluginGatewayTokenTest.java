package com.pocketshell.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.getcapacitor.JSObject;
import com.getcapacitor.PluginMethod;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * #3086 slice 2: the gateway routing token (the broker's 5-minute JWT from
 * {@code POST /gateway/token}) is native-only and in memory only. It reaches
 * the gateway inside the auth frame and nowhere else — not a connect result,
 * an error, a log line, or any bridge method — and it is re-minted when the
 * cached one would not outlive the attempt, when the account changes, and
 * once (only once) when the gateway refuses a cached token with 4401.
 */
public final class SshCapabilityPluginGatewayTokenTest {
    private final AtomicLong now = new AtomicLong(1_800_000_000_000L);
    private GatewayDialFixture fixture;

    @Before public void setUp() throws Exception {
        fixture = new GatewayDialFixture(now::get);
        fixture.plugin.useGatewayRoutingTokensForTesting(new GatewayRoutingTokens(now::get));
        fixture.pairings.pair(GatewayDialFixture.SUBJECT, fixture.serverUrl(), GatewayDialFixture.DEVICE,
                fixture.hostKeyLine(), GatewayDialFixture.HANDLE);
    }

    @After public void tearDown() throws Exception {
        fixture.close();
    }

    private List<String> tokensSentToTheGateway() {
        List<String> tokens = new ArrayList<>();
        for (String frame : fixture.gateway.authFrames) tokens.add(GatewayDialFixture.FakeGateway.tokenOf(frame));
        return tokens;
    }

    @Test public void theTokenReachesOnlyTheGatewayAuthFrame() throws Exception {
        JSObject result = fixture.connect("only-auth-frame");
        String minted = fixture.broker.minted.get(0);
        assertEquals("the gateway got the minted routing token", List.of(minted), tokensSentToTheGateway());
        assertFalse("the connect result crossing the bridge has no token", result.toString().contains(minted));
        assertFalse(result.toString().contains(GatewayDialFixture.ScriptedBroker.GOOGLE_BEARER));
        assertFalse("the Google ID token is never sent to the gateway",
                fixture.gateway.authFrames.get(0).contains(GatewayDialFixture.ScriptedBroker.GOOGLE_BEARER));

        // A refusal after a mint carries no token either, and the plugin's
        // only log line is a code.
        fixture.gateway.script(GatewayDialFixture.Mode.CLOSE, 4503, null);
        SshCapabilityPlugin.PluginFailure failure = fixture.connectExpectingFailure("refused");
        for (String token : fixture.broker.minted) {
            assertFalse(failure.getMessage().contains(token));
            assertFalse(String.valueOf(failure.data).contains(token));
        }
        for (String logged : fixture.loggedFailureCodes) {
            assertTrue("logs carry codes only: " + logged, logged.matches("[A-Z_]+"));
        }
        assertTokensOnlyInAuthFrames(List.of(result.toString(), failure.getMessage(), String.valueOf(failure.data)));
    }

    @Test public void theUpgradeRequestCarriesNoTokenInItsUrlQueryOrHeaders() throws Exception {
        // #3086 review round 1, blocker 4: a token appended to the WebSocket
        // URL (`?t=<token>`) passed every other test. Every dial below —
        // fresh mint, cached reuse, the 4401 re-mint — is checked.
        fixture.connect("fresh");
        fixture.connect("reused");
        fixture.gateway.rejectedTokens.add(fixture.broker.minted.get(0));
        fixture.connect("re-minted");
        assertEquals(4, fixture.gateway.upgradeRequests.size());
        for (String upgrade : fixture.gateway.upgradeRequests) {
            assertTrue("the upgrade targets the fixed route only: " + upgrade,
                    upgrade.startsWith("/api/v1/hosts/" + GatewayDialFixture.DEVICE + "/ssh\n"));
        }
        assertTokensOnlyInAuthFrames(List.of());
    }

    /**
     * Every minted routing token and the Google bearer appear nowhere the
     * client produced except the auth frames: not the upgrade request (path,
     * query, any header), not the given bridge payloads or error texts, not
     * the logged codes.
     */
    private void assertTokensOnlyInAuthFrames(List<String> bridgePayloads) {
        List<String> secrets = new ArrayList<>(fixture.broker.minted);
        secrets.add(GatewayDialFixture.ScriptedBroker.GOOGLE_BEARER);
        List<String> outside = new ArrayList<>(fixture.gateway.upgradeRequests);
        outside.addAll(bridgePayloads);
        outside.addAll(fixture.loggedFailureCodes);
        for (String secret : secrets) {
            for (String place : outside) {
                assertFalse("a token escaped the auth frame into: " + place, place != null && place.contains(secret));
            }
            // URL-encoded or partial copies of the JWT body count too.
            String middle = secret.split("\\.")[1];
            for (String place : outside) {
                assertFalse("a token segment escaped into: " + place, place != null && place.contains(middle));
            }
        }
    }

    @Test public void noBridgeMethodCanYieldAToken() {
        // GoogleSync's bridge is unchanged by the gateway work (no new
        // method, so nothing new reaches JS from the Google session) ...
        assertEquals(Set.of("status", "signIn", "signOut", "request"), pluginMethods(GoogleSyncPlugin.class));
        // ... the pairing bridge has no token method ...
        assertEquals(Set.of("list", "pair", "remove"), pluginMethods(GatewayPairingPlugin.class));
        // ... and the SSH plugin's bridge surface is pinned, so a future
        // method that could hand back a token cannot appear unnoticed.
        assertEquals(Set.of("transportCapabilities", "connect", "cancelOperation", "getConnectionState",
                "closeConnection", "scheduleClose", "cancelScheduledClose", "exec", "openPty", "readPty",
                "writePty", "resizePty", "closePty", "sftpList", "sftpRead", "sftpWrite", "sftpWriteIfUnchanged",
                "sftpMkdir", "sftpRename", "sftpDelete", "openPortForward", "closePortForward", "resourceSnapshot"),
                pluginMethods(SshCapabilityPlugin.class));
        // ... and the capability report is a flag, nothing more.
        JSObject capabilities = SshCapabilityPlugin.transportCapabilitiesReply("r-1");
        assertEquals(Set.of("requestId", "gatewayTransport", "linkTransport"), keys(capabilities));
    }

    @Test public void theSyncBridgeCannotReachTheBrokerRoute() throws Exception {
        // GoogleSync.request() is JS-callable; its route table must not let
        // JS ask for a gateway token with the stored Google token.
        GoogleSyncSession session = new GoogleSyncSession(new GoogleSyncSession.TokenStore() {
            public String read() { return null; }
            public void write(String token) { }
            public void clear() { }
        }, new GoogleSyncSession.SignInProvider() {
            public String obtainIdToken(boolean interactive) throws SyncAuthException {
                throw new SyncAuthException(SyncAuthException.FAILED, "no");
            }

            public void clearCredentialState() { }
        },
                (method, url, headers, body) -> { throw new AssertionError("no request may be sent"); },
                "https://sync.example.test", () -> 0L);
        for (String[] call : new String[][] {{"POST", null, ""}, {"POST", "gateway/token", ""},
                {"GET", "../gateway/token", null}, {"POST", "x", "{}"}}) {
            try {
                session.request(call[0], call[1], call[2]);
                fail("the sync bridge must refuse " + call[0] + " " + call[1]);
            } catch (SyncAuthException refused) {
                assertEquals(SyncAuthException.INVALID_REQUEST, refused.code);
            }
        }
    }

    @Test public void aCachedTokenIsReusedWhileItOutlivesTheAttempt() throws Exception {
        fixture.connect("first");
        now.addAndGet(60_000);
        fixture.connect("second");
        assertEquals("one mint serves both dials", 1, fixture.broker.mints.get());
        List<String> sent = tokensSentToTheGateway();
        assertEquals(sent.get(0), sent.get(1));
    }

    @Test public void anExpiringTokenIsReMinted() throws Exception {
        fixture.connect("first");
        // 290 s into a 300 s token: 10 s left cannot cover a 15 s attempt.
        now.addAndGet(290_000);
        fixture.connect("after-expiry");
        assertEquals(2, fixture.broker.mints.get());
        List<String> sent = tokensSentToTheGateway();
        assertEquals(fixture.broker.minted, sent);
        assertFalse(sent.get(0).equals(sent.get(1)));
        // Past expiry entirely: also a fresh mint.
        now.addAndGet(600_000);
        fixture.connect("long-after");
        assertEquals(3, fixture.broker.mints.get());
    }

    @Test public void anAccountChangeNeverReusesTheOtherAccountsToken() throws Exception {
        fixture.connect("first");
        fixture.broker.subject = "sub-2";
        fixture.pairings.pair("sub-2", fixture.serverUrl(), GatewayDialFixture.DEVICE,
                fixture.hostKeyLine(), GatewayDialFixture.HANDLE);
        fixture.connect("other-account");
        assertEquals(2, fixture.broker.mints.get());
    }

    @Test public void aCachedTokenRefusedWith4401IsReMintedOnceAndTheDialSucceeds() throws Exception {
        fixture.connect("first");
        fixture.gateway.rejectedTokens.add(fixture.broker.minted.get(0));
        JSObject result = fixture.connect("after-rotation");
        assertTrue(result.getBoolean("gatewayHostKeyVerified"));
        assertEquals("the stale cache entry cost exactly one re-mint", 2, fixture.broker.mints.get());
        assertEquals(List.of(fixture.broker.minted.get(0), fixture.broker.minted.get(0), fixture.broker.minted.get(1)),
                tokensSentToTheGateway());
    }

    /**
     * core#49 item 3: a cached token refused with 4401 is re-minted once.
     * When THAT re-mint fails because the phone signed out, the account
     * changed, or the broker refused the sign-in, the dial reports the
     * sign-in/account code — core's terminal "sign in" / "account changed"
     * advice — never the 4401 of a stale cache entry.
     */
    @Test public void aReMintThatFailsOnSignOutReportsTheSignInCodeNotThe4401() throws Exception {
        fixture.connect("first");
        fixture.gateway.rejectedTokens.add(fixture.broker.minted.get(0));
        fixture.gateway.onRejectedToken = fixture.broker::signOut;
        SshCapabilityPlugin.PluginFailure failure = fixture.connectExpectingFailure("signed-out-during-remint");
        assertEquals(SyncAuthException.NOT_SIGNED_IN, failure.code);
        assertFalse(failure.data != null && failure.data.has("gatewayCloseCode"));
        assertEquals("nothing was minted for a signed-out phone", 1, fixture.broker.mints.get());
    }

    @Test public void aReMintThatFailsOnAnAccountSwitchReportsAccountChangedNotThe4401() throws Exception {
        fixture.connect("first");
        fixture.gateway.rejectedTokens.add(fixture.broker.minted.get(0));
        fixture.gateway.onRejectedToken = () -> fixture.broker.switchTo("sub-2");
        SshCapabilityPlugin.PluginFailure failure = fixture.connectExpectingFailure("switched-during-remint");
        assertEquals(SshCapabilityPlugin.GATEWAY_ACCOUNT_CHANGED, failure.code);
        assertFalse(failure.data != null && failure.data.has("gatewayCloseCode"));
    }

    @Test public void aReMintTheBrokerRefusesReportsTheBrokerSignInCodeNotThe4401() throws Exception {
        fixture.connect("first");
        fixture.gateway.rejectedTokens.add(fixture.broker.minted.get(0));
        fixture.gateway.onRejectedToken = () -> fixture.broker.status = 401;
        SshCapabilityPlugin.PluginFailure failure = fixture.connectExpectingFailure("broker-refused-remint");
        assertEquals(GatewayTokenBroker.GatewayBrokerException.SIGN_IN_REJECTED, failure.code);
        assertFalse(failure.data != null && failure.data.has("gatewayCloseCode"));
        assertEquals("the stale token and nothing else reached the gateway",
                List.of(fixture.broker.minted.get(0), fixture.broker.minted.get(0)), tokensSentToTheGateway());
    }

    @Test public void aFreshToken4401IsTheVerdictAndIsNotRetried() throws Exception {
        // Every token is refused: the sign-in itself is not accepted.
        fixture.gateway.script(GatewayDialFixture.Mode.CLOSE, 4401, null);
        SshCapabilityPlugin.PluginFailure failure = fixture.connectExpectingFailure("refused-sign-in");
        assertEquals("GATEWAY_CLOSED", failure.code);
        assertEquals(4401, failure.data.getInt("gatewayCloseCode"));
        assertEquals("a fresh token's 4401 is not retried natively", 1, fixture.broker.mints.get());
        // The refused token is dropped: the next dial mints again.
        fixture.gateway.script(GatewayDialFixture.Mode.READY_RELAY, 0, null);
        fixture.connect("after-sign-in");
        assertEquals(2, fixture.broker.mints.get());
    }

    @Test public void aTokenTooShortToUseIsRefusedNotSent() throws Exception {
        fixture.broker.lifetimeSeconds = 5;
        SshCapabilityPlugin.PluginFailure failure = fixture.connectExpectingFailure("short-token");
        assertEquals(GatewayTokenBroker.GatewayBrokerException.BAD_RESPONSE, failure.code);
        assertEquals("nothing was sent to the gateway", 0, fixture.gateway.authFrames.size());
    }

    @Test public void issuedTokensPrintRedacted() {
        GatewayRoutingTokens.Issued issued = new GatewayRoutingTokens.Issued("aaa.SECRET.ccc", 1L, false);
        assertFalse(issued.toString().contains("SECRET"));
    }

    private static Set<String> pluginMethods(Class<?> plugin) {
        Set<String> names = new TreeSet<>();
        for (Method method : plugin.getDeclaredMethods()) {
            if (method.isAnnotationPresent(PluginMethod.class)) names.add(method.getName());
        }
        return names;
    }

    private static Set<String> keys(JSObject json) {
        Set<String> keys = new TreeSet<>();
        json.keys().forEachRemaining(keys::add);
        return keys;
    }
}
