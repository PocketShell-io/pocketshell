package com.pocketshell.app.smoke;

import org.junit.Test;

/** Packaged feature journey; the inherited oracle drives the real shared app and Docker host. */
public class J03AttachAndTypeJourney extends SharedAppDockerJourneyTest {
    @Test
    @Override
    public void sharedTerminalDeliversImeEditsAsExactBytes() throws Exception {
        super.sharedTerminalDeliversImeEditsAsExactBytes();
    }

}
