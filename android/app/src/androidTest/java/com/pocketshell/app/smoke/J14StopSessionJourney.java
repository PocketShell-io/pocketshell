package com.pocketshell.app.smoke;

import org.junit.Test;

/** Packaged feature journey; the inherited oracle drives the real shared app and Docker host. */
public class J14StopSessionJourney extends SharedAppDockerJourneyTest {
    @Test
    @Override
    public void sharedAppReportsARealSessionEndWithoutReconnecting() throws Exception {
        super.sharedAppReportsARealSessionEndWithoutReconnecting();
    }

    @Test
    @Override
    public void stopSessionFromTabKillsItOnTheHost() throws Exception {
        super.stopSessionFromTabKillsItOnTheHost();
    }

}
