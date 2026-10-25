package com.pocketshell.app.smoke;

import org.junit.Test;

/** Native recognizer partial/final delivery, screen-off, restart and no-speech. */
public final class J08VoiceDictationJourney extends ComposerJourneySupport {
    @Test
    public void dictatedDraftSurvivesScreenOffAndProcessRestart() throws Exception {
        runComposerJourney();
    }

    @Test
    public void noSpeechWarningsKeepTheNativeKeyboardAndDraft() throws Exception {
        runComposerJourney();
    }
}
