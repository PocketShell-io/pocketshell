package com.pocketshell.app.smoke;

import org.junit.Test;

/** Packaged feature journey; the inherited oracle drives the real shared app and Docker host. */
public class J15TerminalScrollJourney extends SharedAppDockerJourneyTest {
    @Test
    @Override
    public void scrollTerminalDoesNotSendInputBytes() throws Exception {
        super.scrollTerminalDoesNotSendInputBytes();
    }

}
