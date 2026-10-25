package com.pocketshell.app.smoke;
import org.junit.Test;
public final class J20ComposerUploadProgressJourney extends AttachmentJourneySupport {
    @Test public void aThreeFileUploadShowsTheBarMidFlightAndLeavesNoResidueAfterCompletion() throws Exception {
        shareAndVerifyHostBytes(3, true);
    }
}
