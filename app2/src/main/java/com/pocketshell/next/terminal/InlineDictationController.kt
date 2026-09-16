package com.pocketshell.next.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.next.composer.SpeechMessages
import com.pocketshell.next.composer.SpeechRecognitionListener
import com.pocketshell.next.composer.SpeechRecognitionProvider
import com.pocketshell.next.composer.SpeechRecognitionSession
import com.pocketshell.next.settings.AppSettings
import com.pocketshell.next.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Where the terminal key-bar dictation (#2475) is in its lifecycle. The three
 * phases map one-to-one onto the mic slot's appearance in
 * [com.pocketshell.uikit.components.SessionTerminalBar].
 */
enum class InlineDictationPhase {
    /** Nothing is happening; the mic is a plain affordance. */
    Idle,

    /** The mic is live; partials are landing in [InlineDictationUiState.partial]. */
    Listening,

    /** The user stopped; the recognizer is resolving the final transcript. */
    Transcribing,
}

/**
 * The key-bar dictation's whole UI state. [partial] is PREVIEW-ONLY text: the
 * contract of this class is that it can never leave through [InlineDictationController.finals],
 * so it can never be written to the PTY.
 */
data class InlineDictationUiState(
    val phase: InlineDictationPhase = InlineDictationPhase.Idle,
    val partial: String = "",

    /**
     * The last user-facing failure ("permission denied", "nothing was heard"),
     * shown in the bar's dictation chip until the next mic tap. Null when the
     * dictation ended cleanly.
     */
    val error: String? = null,
)

/**
 * Terminal key-bar inline dictation (#2475): dictate straight into the PTY at
 * the cursor, no composer, no review step.
 *
 * The slim port of the old app's 1,570-line `session/InlineDictation.kt`,
 * minus everything the rewrite already moved or cut: no Whisper arm (the
 * recognizer-only route #2529 locked for the composer mic), no undelivered
 * queue (`PendingTranscriptionStore`/`Delivery` shipped with P-2), and no
 * `PromptComposerViewModel` coupling — this controller talks to ONE provider
 * seam and ONE finals channel.
 *
 * ## The invariant: partials never reach the PTY
 *
 * A partial transcription is a guess; a PTY write cannot be un-written. The
 * controller therefore has exactly TWO output paths and they never meet:
 *
 *  - [state] — preview text for the bar's status chip. Partials land here.
 *  - [finals] — the ONLY channel that carries text onward (SessionScreen
 *    maps it onto `SessionViewModel.sendBytes`). Fed exclusively from the
 *    recognizer's `onFinal`. The unit suite pins this with a fake provider
 *    that interleaves partials and a final.
 *
 * ## Lifecycle
 *
 * `onMicTap` toggles Idle ↔ Listening (the same start-or-stop contract as the
 * composer mic); a tap while Transcribing is ignored — the final is already in
 * flight and the transcript the user just dictated must not be raced. A stop
 * starts a watchdog: a recognizer that never resolves the final would
 * otherwise brick the bar on "Transcribing…" forever, so after
 * [transcribingTimeoutMs] the controller gives up, returns to Idle with an
 * error, and anything arriving after that is discarded by the generation
 * guard — a late final from an abandoned dictation is exactly the kind of
 * text that must not hit the PTY out of order.
 */
class InlineDictationController(
    private val scope: CoroutineScope,
    private val provider: SpeechRecognitionProvider,

    /**
     * How long a stop waits for the recognizer's final before giving up.
     * A constructor parameter, not a constant, so tests drive it with the
     * virtual clock instead of wall time.
     */
    private val transcribingTimeoutMs: Long = DEFAULT_TRANSCRIBING_TIMEOUT_MS,
) {

    /** Where the dictation UI is. */
    private val _state = MutableStateFlow(InlineDictationUiState())
    val state: StateFlow<InlineDictationUiState> = _state.asStateFlow()

    /**
     * The finals channel — the one path from this controller to the PTY.
     *
     * A [Channel] rather than a `SharedFlow` so a final can never be dropped
     * to satisfy backpressure: `UNLIMITED` makes every `trySend` succeed, and
     * a dropped FINAL would be a dictated paragraph silently vanishing.
     */
    private val _finals = Channel<String>(Channel.UNLIMITED)
    val finals: Flow<String> = _finals.receiveAsFlow()

    /** Monotonic dictation id; callbacks from older ones are ignored. */
    private var generation: Long = 0L

    private var session: SpeechRecognitionSession? = null
    private var watchdog: Job? = null

    /**
     * The mic tap: start dictating, or stop and transcribe. A tap while
     * Transcribing does nothing. [language] is the Settings → Voice ISO hint,
     * or null for auto-detect — read per tap so a settings change takes
     * effect on the next dictation.
     */
    fun onMicTap(language: String?) {
        when (_state.value.phase) {
            InlineDictationPhase.Idle -> start(language)
            InlineDictationPhase.Listening -> stop()
            InlineDictationPhase.Transcribing -> Unit
        }
    }

    /** Abandons any dictation outright; nothing is transcribed or sent. */
    fun cancel() {
        reset()
        _state.value = InlineDictationUiState()
    }

    /** RECORD_AUDIO was denied; the recognizer was never started. */
    fun onPermissionDenied() {
        reset()
        _state.value = InlineDictationUiState(error = PERMISSION_DENIED_MESSAGE)
    }

    private fun start(language: String?) {
        reset()
        val gen = ++generation
        if (!provider.isAvailable()) {
            _state.value = InlineDictationUiState(error = SpeechMessages.UNAVAILABLE)
            return
        }
        val started = try {
            provider.start(language, listenerFor(gen))
        } catch (_: Throwable) {
            null
        }
        if (started == null) {
            _state.value = InlineDictationUiState(error = SpeechMessages.UNAVAILABLE)
            return
        }
        session = started
        _state.value = InlineDictationUiState(phase = InlineDictationPhase.Listening)
    }

    /** The stop half of the tap: resolve the transcript, then commit it. */
    private fun stop() {
        val current = _state.value
        if (current.phase != InlineDictationPhase.Listening) return
        _state.value = InlineDictationUiState(
            phase = InlineDictationPhase.Transcribing,
            partial = current.partial,
        )
        session?.stopListening()
        watchdog = scope.launch {
            delay(transcribingTimeoutMs)
            // The recognizer never resolved the final: abandon the session so
            // it cannot keep the mic, then surface the failure. A final it
            // delivers later is discarded by the generation guard in [fail].
            session?.cancel()
            fail(
                if (_state.value.partial.isBlank()) SpeechMessages.NO_TEXT
                else TRANSCRIBING_TIMEOUT_MESSAGE,
            )
        }
    }

    /** Tears down whatever is live; bumps the generation so late callbacks die. */
    private fun reset() {
        generation++
        watchdog?.cancel()
        watchdog = null
        session?.cancel()
        session = null
    }

    /** Ends a dictation with a user-facing failure; nothing is ever sent. */
    private fun fail(message: String) {
        // Invalidate the dictation wholesale: a recognizer that answers
        // AFTER its own failure/timeout (the watchdog path) must find its
        // callbacks stale, or a late "final" would sail through the guard
        // and into the PTY out of order.
        generation++
        watchdog?.cancel()
        watchdog = null
        // The session is over by definition here (final/error/timeout); do not
        // cancel() it — that would be a second teardown on a dead recognizer.
        session = null
        _state.value = InlineDictationUiState(error = message)
    }

    private fun listenerFor(gen: Long) = object : SpeechRecognitionListener {
        override fun onPartial(text: String) {
            // The generation guard is what makes stale partials structurally
            // impossible to forward — but the phase check below is the real
            // wall: partials only ever mutate the preview state.
            if (gen != generation) return
            if (_state.value.phase != InlineDictationPhase.Listening) return
            _state.value = _state.value.copy(partial = text.trim())
        }

        override fun onFinal(text: String) {
            if (gen != generation) return
            val trimmed = text.trim()
            if (trimmed.isEmpty()) {
                fail(SpeechMessages.NO_TEXT)
                return
            }
            // Order matters: the text is committed to the channel BEFORE the
            // state returns to Idle, so an observer can never see "idle" with
            // a transcript still in flight.
            _finals.trySend(trimmed)
            watchdog?.cancel()
            watchdog = null
            session = null
            _state.value = InlineDictationUiState()
        }

        override fun onError(message: String) {
            if (gen != generation) return
            fail(message)
        }
    }

    companion object {

        /**
         * How long a stop waits for the recognizer's final. The composer's
         * transcribing step resolves in low seconds on the maintainer's
         * device; this is a generous ceiling whose only job is to unbrick the
         * bar when a recognizer never answers at all.
         */
        const val DEFAULT_TRANSCRIBING_TIMEOUT_MS: Long = 15_000L

        const val PERMISSION_DENIED_MESSAGE: String =
            "Microphone permission denied. Grant it in system settings to use voice input."

        const val TRANSCRIBING_TIMEOUT_MESSAGE: String =
            "Voice input didn't finish in time — try again."
    }
}

/**
 * The Hilt entry point for the key-bar dictation. A thin shell: it exists to
 * hand the controller a [viewModelScope], the bound
 * [SpeechRecognitionProvider], and the Settings → Voice language hint, and to
 * die with the session screen. All behaviour lives in
 * [InlineDictationController], which is plain-JVM testable.
 */
@HiltViewModel
class InlineDictationViewModel @Inject constructor(
    provider: SpeechRecognitionProvider,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val controller = InlineDictationController(
        scope = viewModelScope,
        provider = provider,
    )

    /** The dictation's UI state, for the bar's mic slot and status chip. */
    val state = controller.state

    /**
     * Final transcripts only — SessionScreen collects this straight into
     * `SessionViewModel.sendBytes`. See the controller's invariant doc.
     */
    val finals: Flow<String> = controller.finals

    fun onMicTap() = controller.onMicTap(language = recognizerLanguage())

    fun cancel() = controller.cancel()

    fun onPermissionDenied() = controller.onPermissionDenied()

    /** ISO-639 hint for the system recognizer, or null for auto-detect. */
    private fun recognizerLanguage(): String? =
        settings.settings.value.voiceLanguage
            .takeUnless { it == AppSettings.VOICE_LANGUAGE_AUTO || it.isBlank() }
}
