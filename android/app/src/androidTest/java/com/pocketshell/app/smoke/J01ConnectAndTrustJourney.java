package com.pocketshell.app.smoke;

import org.junit.Test;

/** Packaged feature journey; the inherited oracle drives the real shared app and Docker host. */
public class J01ConnectAndTrustJourney extends SharedAppDockerJourneyTest {
    @Test
    @Override
    public void firstContactHostKeyAsksAndHonoursRejectOnceAndAlways() throws Exception {
        super.firstContactHostKeyAsksAndHonoursRejectOnceAndAlways();
    }

    @Test
    @Override
    public void changedHostKeyIsRefusedWithVisibleMessage() throws Exception {
        super.changedHostKeyIsRefusedWithVisibleMessage();
    }

}
