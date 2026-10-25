package com.pocketshell.app.smoke;

import org.junit.Test;

/** Host-provided quota states through the packaged usage panel. */
public final class J12UsagePanelJourney extends UsagePortsJourneySupport {
    @Override protected boolean usageOnly() { return true; }

    @Test
    public void realHostQuotasRenderInUsagePanel() throws Exception {
        runUsagePortsJourney();
    }
}
