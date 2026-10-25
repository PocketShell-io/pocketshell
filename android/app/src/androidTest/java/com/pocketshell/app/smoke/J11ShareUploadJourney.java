package com.pocketshell.app.smoke;
import org.junit.Test;
public final class J11ShareUploadJourney extends AttachmentJourneySupport {
    @Test public void androidSingleShareReachesTheChosenSessionOnTheRealHost() throws Exception {
        shareAndVerifyHostBytes(1, false);
    }
    @Test public void androidShareStreamsReachTheChosenSessionOnTheRealHost() throws Exception {
        shareAndVerifyHostBytes(2, false);
    }
}
