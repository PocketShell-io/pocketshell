package com.pocketshell.app;

/**
 * Reduces SpeechRecognizer.onRmsChanged (many callbacks a second) to a coarse,
 * rate-limited "sound / silence" flag (#3062). JavaScript uses it only to word
 * the "not hearing words" warning as "no sound from the mic" versus "sound, but
 * no words". No audio content or level value leaves the device's recognizer
 * callback; only this boolean, at most once per {@link #MIN_INTERVAL_MS}.
 */
final class SpeechAudioPresence {
    /**
     * RMS in dB above which the mic is treated as hearing sound. The platform
     * recognizer reports roughly -2 dB for silence and up to ~10 dB for speech.
     */
    static final float SOUND_THRESHOLD_DB = 2.0f;
    /** Keep "sound" for this long after the last loud sample so pauses between syllables do not flap. */
    static final long SOUND_HOLD_MS = 1_000;
    /** Never send two flags closer together than this. */
    static final long MIN_INTERVAL_MS = 250;
    /** Re-send an unchanged state this often, so JS evidence survives a reset by recognized text. */
    static final long HEARTBEAT_MS = 1_000;

    private Boolean reported;
    private long reportedAtMs;
    private long lastSoundAtMs = Long.MIN_VALUE;

    /** Returns the flag to forward now, or null when nothing should be sent. */
    Boolean onRms(float rmsDb, long nowMs) {
        if (Float.isNaN(rmsDb) || Float.isInfinite(rmsDb)) return null;
        if (rmsDb >= SOUND_THRESHOLD_DB) lastSoundAtMs = nowMs;
        boolean sound = lastSoundAtMs != Long.MIN_VALUE && nowMs - lastSoundAtMs <= SOUND_HOLD_MS;
        if (reported == null) return report(sound, nowMs);
        long sinceReport = nowMs - reportedAtMs;
        if (sinceReport < MIN_INTERVAL_MS) return null;
        if (reported != sound || sinceReport >= HEARTBEAT_MS) return report(sound, nowMs);
        return null;
    }

    private Boolean report(boolean sound, long nowMs) {
        reported = sound;
        reportedAtMs = nowMs;
        return sound;
    }
}
