package com.pocketshell.app.smoke;

import org.junit.Test;

/** Packaged feature journey; the inherited oracle drives the real shared app and Docker host. */
public class J05ReconnectAfterDropJourney extends SharedAppDockerJourneyTest {
    @Test
    @Override
    public void sharedAppRecoversAbruptServerDropWithOneControllerReconnect() throws Exception {
        super.sharedAppRecoversAbruptServerDropWithOneControllerReconnect();
    }

    @Test
    @Override
    public void sharedAppSilentlyRejoinsWhenOnlyTheAttachClientDies() throws Exception {
        super.sharedAppSilentlyRejoinsWhenOnlyTheAttachClientDies();
    }

}
