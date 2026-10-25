package com.pocketshell.app.smoke;

import org.junit.Test;

/** Actual packaged Send/Insert/uncertain-delivery behavior and host byte oracles. */
public final class J07ComposerSendJourney extends ComposerJourneySupport {
    @Test
    public void composerWritesUtf8AndMultilineInsertAndRetainsAfterDrop() throws Exception {
        runComposerJourney();
    }
}
