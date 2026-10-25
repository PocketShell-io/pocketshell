package com.pocketshell.app.smoke;

import org.junit.Test;

/** Packaged feature journey; the inherited oracle drives the real shared app and Docker host. */
public class J21HostAddConnectJourney extends SharedAppDockerJourneyTest {
    @Test
    @Override
    public void hostAddedThroughFormConnectsToFixture() throws Exception {
        super.hostAddedThroughFormConnectsToFixture();
    }

}
