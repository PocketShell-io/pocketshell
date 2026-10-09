package com.pocketshell.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Issue #3062: SpeechRecognizer.onRmsChanged fires many times a second. The
 * plugin forwards only a coarse, rate-limited sound/silence flag so the
 * "not hearing words" warning can say "no sound" vs "sound but no words".
 */
public final class SpeechAudioPresenceTest {
    @Test
    public void firstLevelIsReportedImmediatelyAsSoundOrSilence() {
        assertEquals(Boolean.FALSE, new SpeechAudioPresence().onRms(-2.0f, 0));
        assertEquals(Boolean.TRUE, new SpeechAudioPresence().onRms(6.0f, 0));
    }

    @Test
    public void unchangedStateIsNotRepeatedMoreThanOncePerHeartbeat() {
        SpeechAudioPresence presence = new SpeechAudioPresence();
        assertEquals(Boolean.FALSE, presence.onRms(-2.0f, 0));
        for (long now = 50; now < SpeechAudioPresence.HEARTBEAT_MS; now += 50) {
            assertNull("silence must not be re-sent every RMS callback", presence.onRms(-1.5f, now));
        }
        assertEquals(Boolean.FALSE, presence.onRms(-1.5f, SpeechAudioPresence.HEARTBEAT_MS));
    }

    @Test
    public void soundIsHeldBrieflySoPausesBetweenSyllablesDoNotFlap() {
        SpeechAudioPresence presence = new SpeechAudioPresence();
        assertEquals(Boolean.TRUE, presence.onRms(7.0f, 0));
        assertNull(presence.onRms(-2.0f, 100));
        assertNull(presence.onRms(-2.0f, SpeechAudioPresence.SOUND_HOLD_MS - 1));
        assertEquals(Boolean.FALSE, presence.onRms(-2.0f, SpeechAudioPresence.SOUND_HOLD_MS + 1));
    }

    @Test
    public void transitionsAreRateLimited() {
        SpeechAudioPresence presence = new SpeechAudioPresence();
        assertEquals(Boolean.FALSE, presence.onRms(-2.0f, 0));
        assertNull("a transition inside the minimum interval waits", presence.onRms(8.0f, 10));
        assertEquals(Boolean.TRUE, presence.onRms(8.0f, SpeechAudioPresence.MIN_INTERVAL_MS));
    }

    @Test
    public void boundedEventRateOverAMinuteOfNoisyLevels() {
        SpeechAudioPresence presence = new SpeechAudioPresence();
        int events = 0;
        // 20 callbacks per second for a minute, alternating loud and quiet.
        for (long now = 0; now < 60_000; now += 50) {
            if (presence.onRms((now / 50) % 2 == 0 ? 9.0f : -2.0f, now) != null) events++;
        }
        long maxEvents = 60_000 / SpeechAudioPresence.MIN_INTERVAL_MS + 1;
        assertTrue("events " + events + " must stay under " + maxEvents, events <= maxEvents);
    }

    @Test
    public void nonFiniteLevelsAreIgnored() {
        SpeechAudioPresence presence = new SpeechAudioPresence();
        assertNull(presence.onRms(Float.NaN, 0));
        assertNull(presence.onRms(Float.POSITIVE_INFINITY, 10));
        assertEquals(Boolean.FALSE, presence.onRms(-2.0f, 20));
    }
}
