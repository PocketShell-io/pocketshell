package com.pocketshell.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import android.speech.RecognizerIntent;
import java.util.Locale;
import java.util.Map;
import org.junit.Test;

public final class SpeechRecognitionPluginTest {
    @Test
    public void recognizerExtrasUseConfiguredLanguageAndFourSecondDefault() {
        Map<String, Object> extras = SpeechRecognitionPlugin.recognizerExtras("fr-FR", null);

        assertEquals(RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                extras.get(RecognizerIntent.EXTRA_LANGUAGE_MODEL));
        assertEquals("fr-FR", extras.get(RecognizerIntent.EXTRA_LANGUAGE));
        assertEquals(Boolean.TRUE, extras.get(RecognizerIntent.EXTRA_PARTIAL_RESULTS));
        assertEquals(1, extras.get(RecognizerIntent.EXTRA_MAX_RESULTS));
        assertEquals(4_000L,
                extras.get(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS));
        assertEquals(4_000L,
                extras.get(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS));
        assertEquals(2_000L,
                extras.get(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS));
    }

    @Test
    public void recognizerSilenceExtrasClampTheFloorAndMaximum() {
        Map<String, Object> tooShort = SpeechRecognitionPlugin.recognizerExtras("en", 1_500);
        assertEquals(2_000L,
                tooShort.get(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS));
        assertEquals(2_000L,
                tooShort.get(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS));

        Map<String, Object> tooLong = SpeechRecognitionPlugin.recognizerExtras("en", 90_000);
        assertEquals(60_000L,
                tooLong.get(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS));
        assertEquals(60_000L,
                tooLong.get(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS));
    }

    @Test
    public void malformedSilenceAndLanguageValuesUseSafeDefaults() {
        assertEquals(4_000L, SpeechRecognitionPlugin.clampSilenceWindowMs("4000"));
        assertEquals(4_000L, SpeechRecognitionPlugin.clampSilenceWindowMs(Double.NaN));

        Map<String, Object> extras = SpeechRecognitionPlugin.recognizerExtras("bad__tag", null);
        assertEquals(Locale.getDefault().toLanguageTag(), extras.get(RecognizerIntent.EXTRA_LANGUAGE));
        assertFalse(extras.containsValue("bad__tag"));
    }

    @Test
    public void autoLanguageSettingOmitsTheLocaleHintWhileMissingSettingUsesDeviceLocale() {
        assertEquals("en-CA", SpeechRecognitionPlugin.normalizeLanguageTag(null, Locale.CANADA));
        assertNull(SpeechRecognitionPlugin.normalizeLanguageTag("auto", Locale.CANADA));
        assertNull(SpeechRecognitionPlugin.normalizeLanguageTag(" AUTO ", Locale.CANADA));

        String autoLanguage = SpeechRecognitionPlugin.normalizeLanguageTag("auto", Locale.CANADA);
        Map<String, Object> autoExtras = SpeechRecognitionPlugin.recognizerExtras(autoLanguage, null);
        assertFalse(autoExtras.containsKey(RecognizerIntent.EXTRA_LANGUAGE));
    }
}
