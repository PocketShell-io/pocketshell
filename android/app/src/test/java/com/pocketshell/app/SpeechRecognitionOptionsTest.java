package com.pocketshell.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class SpeechRecognitionOptionsTest {
    @Test
    public void clampsSilenceWindowAtTheRecognizerBoundary() {
        assertEquals(2_000L, SpeechRecognitionOptions.clampSilenceWindowMs(Long.MIN_VALUE));
        assertEquals(2_000L, SpeechRecognitionOptions.clampSilenceWindowMs(0L));
        assertEquals(2_000L, SpeechRecognitionOptions.clampSilenceWindowMs(2_000L));
        assertEquals(7_000L, SpeechRecognitionOptions.clampSilenceWindowMs(7_000L));
        assertEquals(60_000L, SpeechRecognitionOptions.clampSilenceWindowMs(60_000L));
        assertEquals(60_000L, SpeechRecognitionOptions.clampSilenceWindowMs(Long.MAX_VALUE));
    }
}
