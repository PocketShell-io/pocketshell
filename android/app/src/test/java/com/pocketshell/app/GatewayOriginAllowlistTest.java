package com.pocketshell.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * #3086 review B1: the native gateway-origin allowlist. Production is the
 * one PocketShell gateway; the lane origin exists only in the gwlane build
 * (BuildConfig.GATEWAY_LANE_TEST_ORIGIN, empty in this unit-test build like
 * every non-lane build). Matching is exact on (scheme, host, port).
 */
public final class GatewayOriginAllowlistTest {
    @Test public void thisBuildHasNoLaneOriginAndAllowsOnlyProduction() {
        assertEquals("only the gwlane debug build compiles a lane origin", "", BuildConfig.GATEWAY_LANE_TEST_ORIGIN);
        GatewayOriginAllowlist build = GatewayOriginAllowlist.forBuild();
        for (String allowed : new String[] {"wss://gateway.pocketshell.io", "wss://gateway.pocketshell.io:443",
                "wss://gateway.pocketshell.io/", "wss://Gateway.PocketShell.io"}) {
            assertTrue(allowed, build.allowsGatewayOrigin(allowed));
        }
        assertTrue(build.allowsHttpsOrigin("https://gateway.pocketshell.io"));
        assertFalse("the lane origin is not allowed outside the lane build", build.allowsGatewayOrigin("wss://localhost:3287"));
        for (String foreign : SshCapabilityPluginGatewayRefusalTest.FOREIGN_ORIGINS) {
            assertFalse(foreign, build.allowsGatewayOrigin(foreign));
            assertFalse(foreign, build.allowsHttpsOrigin(foreign.replaceFirst("^wss://", "https://")));
        }
        for (String malformed : new String[] {null, "", "ws://gateway.pocketshell.io", "http://gateway.pocketshell.io",
                "https://gateway.pocketshell.io", "wss://gateway.pocketshell.io/x", "wss://u@gateway.pocketshell.io",
                "wss://gateway.pocketshell.io?x", "wss://gateway.pocketshell.io.", "wss://gateway.pocketshell.io:80"}) {
            assertFalse(String.valueOf(malformed), build.allowsGatewayOrigin(malformed));
        }
    }

    @Test public void theLaneOriginIsAllowedExactlyWhenTheBuildCompilesIt() {
        GatewayOriginAllowlist lane = GatewayOriginAllowlist.of("wss://localhost:3287");
        assertTrue(lane.allowsGatewayOrigin("wss://localhost:3287"));
        assertTrue(lane.allowsGatewayOrigin("wss://gateway.pocketshell.io"));
        for (String near : new String[] {"wss://localhost:3288", "wss://localhost", "wss://127.0.0.1:3287",
                "ws://localhost:3287", "wss://localhost.evil.test:3287"}) {
            assertFalse(near, lane.allowsGatewayOrigin(near));
        }
    }
}
