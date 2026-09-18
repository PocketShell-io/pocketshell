package com.pocketshell.next.composer

/**
 * Where the composer is in a dictation.
 *
 * Lives in the shared presentation module (#2636 D2) — the composer bar
 * switches its recording/transcribing surfaces on this enum. The recognizer
 * seam that moves the state (`SpeechRecognitionProvider` and friends) stays
 * in app2 with the voice stack.
 */
enum class RecordingState {
    Idle,

    /** The mic is live and partials are landing in the draft. */
    Recording,

    /** The user stopped; the recognizer is resolving the final transcript. */
    Transcribing,
}
