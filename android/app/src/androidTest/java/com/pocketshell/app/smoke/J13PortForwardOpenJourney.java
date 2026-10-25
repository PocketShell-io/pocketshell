package com.pocketshell.app.smoke;

import org.junit.Test;

/** Ports route and real loopback forwards, reconnect and cleanup. */
public final class J13PortForwardOpenJourney extends UsagePortsJourneySupport {
    @Test
    public void usageAndPortForwardingPoliciesUseDockerAndNativePlugin() throws Exception {
        runUsagePortsJourney();
    }
}
