package com.pocketshell.app;

/** Defensive bounds for settings forwarded to Android's system recognizer. */
final class SpeechRecognitionOptions {
    static final long DEFAULT_SILENCE_WINDOW_MS = 4_000L;
    static final long MIN_SILENCE_WINDOW_MS = 2_000L;
    static final long MAX_SILENCE_WINDOW_MS = 60_000L;
    static final long MINIMUM_SPEECH_LENGTH_MS = 2_000L;

    private SpeechRecognitionOptions() {}

    static long clampSilenceWindowMs(long requestedMs) {
        return Math.max(MIN_SILENCE_WINDOW_MS, Math.min(MAX_SILENCE_WINDOW_MS, requestedMs));
    }
}
