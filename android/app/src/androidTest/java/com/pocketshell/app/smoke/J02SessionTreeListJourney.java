package com.pocketshell.app.smoke;

import org.junit.Test;

/** Packaged feature journey; the inherited oracle drives the real shared app and Docker host. */
public class J02SessionTreeListJourney extends SharedAppDockerJourneyTest {
    @Test
    @Override
    public void sharedAppListsAttachesAndTypesIntoFixtureSession() throws Exception {
        super.sharedAppListsAttachesAndTypesIntoFixtureSession();
    }

    @Test
    @Override
    public void sharedAppSwitchesSessionTabsKeepingEachTerminal() throws Exception {
        super.sharedAppSwitchesSessionTabsKeepingEachTerminal();
    }

}
