package com.pocketshell.app.smoke;

import org.junit.Test;

/** Calls the production Android SpeechRecognizer path without debug test mode. */
public final class J08VoiceProductionProviderJourney extends ComposerJourneySupport {
    @Test
    public void realProviderStartReturnsWithoutCrashingTheProcess() throws Exception {
        runProductionSpeechProviderStart();
    }
}
