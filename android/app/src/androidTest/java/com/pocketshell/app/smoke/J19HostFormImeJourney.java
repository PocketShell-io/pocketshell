package com.pocketshell.app.smoke;

import org.junit.Test;

/** Packaged feature journey; the inherited oracle drives the real shared app and Docker host. */
public class J19HostFormImeJourney extends SharedAppDockerJourneyTest {
    @Test
    @Override
    public void hostFormSubmitRemainsReachableWithTheIme() throws Exception {
        super.hostFormSubmitRemainsReachableWithTheIme();
    }

    @Test
    @Override
    public void tunnelFormSubmitRemainsReachableWithTheIme() throws Exception {
        super.tunnelFormSubmitRemainsReachableWithTheIme();
    }
}
