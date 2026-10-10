package com.pocketshell.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.getcapacitor.JSObject;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * #3086 slice 2 review round 1, blocker 1: a gateway dial is bound to the
 * account it was planned for. The plan reads the signed-in subject once;
 * if the phone signs out or switches account after that — before the cached
 * token is reused, while the broker mints, or before the tunnel is created —
 * the dial fails closed: no routing token is sent, no gateway socket opens,
 * nothing is cached under the wrong account, and each account's next dial
 * mints its own token.
 */
public final class SshCapabilityPluginGatewayAccountTest {
    private static final String OTHER = "sub-2";
    private final AtomicLong now = new AtomicLong(1_800_000_000_000L);
    private GatewayDialFixture fixture;

    @Before public void setUp() throws Exception {
        fixture = new GatewayDialFixture(now::get);
        fixture.plugin.useGatewayRoutingTokensForTesting(new GatewayRoutingTokens(now::get));
        for (String subject : new String[] {GatewayDialFixture.SUBJECT, OTHER}) {
            fixture.pairings.pair(subject, fixture.serverUrl(), GatewayDialFixture.DEVICE,
                    fixture.hostKeyLine(), GatewayDialFixture.HANDLE);
        }
    }

    @After public void tearDown() throws Exception {
        fixture.close();
    }

    /** Arm a change that lands right after the plan's subject read. */
    private void afterPlan(Runnable change) {
        fixture.broker.subjectReads.set(0);
        fixture.broker.onSubjectRead = read -> {
            if (read == 1) change.run();
        };
    }

    private void disarm() {
        fixture.broker.onSubjectRead = null;
        fixture.broker.duringExchange = null;
    }

    private void assertNothingSentSince(int authFrames, int gatewayConnections) {
        assertEquals("no routing token was sent for the wrong account",
                authFrames, fixture.gateway.authFrames.size());
        assertEquals("no gateway socket was opened for the wrong account",
                gatewayConnections, fixture.gateway.clientConnections.get());
    }

    private String lastTokenSent() {
        return GatewayDialFixture.FakeGateway.tokenOf(
                fixture.gateway.authFrames.get(fixture.gateway.authFrames.size() - 1));
    }

    @Test public void signOutAfterThePlanWithAWarmCacheSendsNothing() throws Exception {
        fixture.connect("warm");
        afterPlan(fixture.broker::signOut);
        SshCapabilityPlugin.PluginFailure failure = fixture.connectExpectingFailure("signed-out");
        assertEquals(SyncAuthException.NOT_SIGNED_IN, failure.code);
        assertNothingSentSince(1, 1);

        disarm();
        fixture.broker.switchTo(GatewayDialFixture.SUBJECT);
        fixture.connect("signed-back-in");
        assertEquals("the cache was dropped: signing back in mints again", 2, fixture.broker.mints.get());
        assertEquals(fixture.broker.minted.get(1), lastTokenSent());
    }

    @Test public void anAccountSwitchAfterThePlanWithAWarmCacheSendsNothing() throws Exception {
        fixture.connect("warm");
        String firstAccountsToken = fixture.broker.minted.get(0);
        afterPlan(() -> fixture.broker.switchTo(OTHER));
        SshCapabilityPlugin.PluginFailure failure = fixture.connectExpectingFailure("switched");
        assertEquals(SshCapabilityPlugin.GATEWAY_ACCOUNT_CHANGED, failure.code);
        assertNothingSentSince(1, 1);

        disarm();
        fixture.connect("other-account");
        assertEquals(2, fixture.broker.mints.get());
        assertNotEquals("the other account never reuses the first account's token", firstAccountsToken, lastTokenSent());
        fixture.broker.switchTo(GatewayDialFixture.SUBJECT);
        fixture.connect("first-account-again");
        assertEquals(3, fixture.broker.mints.get());
    }

    @Test public void anAccountSwitchDuringTheExchangeNeitherSendsNorCachesTheMint() throws Exception {
        fixture.broker.duringExchange = () -> fixture.broker.switchTo(OTHER);
        SshCapabilityPlugin.PluginFailure failure = fixture.connectExpectingFailure("switch-mid-mint");
        assertEquals(SshCapabilityPlugin.GATEWAY_ACCOUNT_CHANGED, failure.code);
        assertNothingSentSince(0, 0);
        String mintedMidSwitch = fixture.broker.minted.get(0);

        disarm();
        fixture.connect("other-account");
        assertEquals("the mid-switch mint was not cached for anyone", 2, fixture.broker.mints.get());
        assertNotEquals(mintedMidSwitch, lastTokenSent());
        fixture.broker.switchTo(GatewayDialFixture.SUBJECT);
        fixture.connect("first-account");
        assertEquals(3, fixture.broker.mints.get());
        assertNotEquals(mintedMidSwitch, lastTokenSent());
    }

    @Test public void aSignOutDuringTheExchangeSendsNothing() throws Exception {
        fixture.broker.duringExchange = fixture.broker::signOut;
        SshCapabilityPlugin.PluginFailure failure = fixture.connectExpectingFailure("sign-out-mid-mint");
        assertEquals(SyncAuthException.NOT_SIGNED_IN, failure.code);
        assertNothingSentSince(0, 0);
    }

    @Test public void aSwitchAtAnyAccountCheckBeforeTheTunnelSendsNothing() throws Exception {
        // Whichever account read the switch lands after (the plan's, the one
        // before the cache/mint, or the one after the exchange), the attempt
        // still re-checks before the tunnel exists.
        for (int landing = 1; landing <= 3; landing++) {
            final int at = landing;
            fixture.plugin.useGatewayRoutingTokensForTesting(new GatewayRoutingTokens(now::get));
            fixture.broker.switchTo(GatewayDialFixture.SUBJECT);
            fixture.broker.subjectReads.set(0);
            fixture.broker.onSubjectRead = read -> {
                if (read == at) fixture.broker.switchTo(OTHER);
            };
            int frames = fixture.gateway.authFrames.size();
            int sockets = fixture.gateway.clientConnections.get();
            SshCapabilityPlugin.PluginFailure failure = fixture.connectExpectingFailure("switch-after-read-" + at);
            assertEquals("switch after account read " + at, SshCapabilityPlugin.GATEWAY_ACCOUNT_CHANGED, failure.code);
            assertNothingSentSince(frames, sockets);
        }
        disarm();
        JSObject result = fixture.connect("settled");
        assertTrue(result.getBoolean("gatewayHostKeyVerified"));
    }
}
