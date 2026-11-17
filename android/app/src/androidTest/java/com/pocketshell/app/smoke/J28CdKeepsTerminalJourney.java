package com.pocketshell.app.smoke;

import org.junit.Test;

/** Packaged feature journey (#3146); the inherited oracle drives the real shared app and Docker host. */
public class J28CdKeepsTerminalJourney extends SharedAppDockerJourneyTest {
    @Test
    @Override
    public void cdInsideOpenShellKeepsTheTerminalMounted() throws Exception {
        super.cdInsideOpenShellKeepsTheTerminalMounted();
    }
}
