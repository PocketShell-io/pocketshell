package com.pocketshell.next.terminal

import com.pocketshell.next.composer.SpeechMessages
import com.pocketshell.next.composer.SpeechRecognitionListener
import com.pocketshell.next.composer.SpeechRecognitionProvider
import com.pocketshell.next.composer.SpeechRecognitionSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The key-bar inline dictation controller (#2475), over a fake recognizer and
 * `runTest`'s virtual clock.
 *
 * The load-bearing tests here pin the PTY invariant: partial transcription
 * results must NEVER reach `SessionViewModel.sendBytes`. [SessionScreen]
 * collects [InlineDictationController.finals] straight into `sendBytes`, so
 * the harness's `sent` list IS the PTY write log, and the suite proves that
 * partials, errors, timeouts, cancels, and stale finals leave it empty while
 * exactly one final lands per completed dictation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InlineDictationControllerTest {

    // ------------------------------------------------------------- the fakes

    /** Records stop/cancel so teardown behaviour is assertable. */
    private class FakeSession : SpeechRecognitionSession {
        var stopped: Int = 0
        var cancelled: Int = 0

        override fun stopListening() {
            stopped++
        }

        override fun cancel() {
            cancelled++
        }
    }

    /**
     * Emits nothing on its own — tests drive the captured
     * [SpeechRecognitionListener] directly, exactly as the real recognizer's
     * main-thread callbacks would arrive.
     */
    private class FakeProvider : SpeechRecognitionProvider {
        var available: Boolean = true
        var returnNullSession: Boolean = false
        var throwOnStart: Boolean = false
        val session: FakeSession = FakeSession()
        val listeners: MutableList<SpeechRecognitionListener> = mutableListOf()
        val languages: MutableList<String?> = mutableListOf()

        val latestListener: SpeechRecognitionListener?
            get() = listeners.lastOrNull()

        override fun isAvailable(): Boolean = available

        override fun start(
            language: String?,
            listener: SpeechRecognitionListener,
        ): SpeechRecognitionSession? {
            languages += language
            if (throwOnStart) throw IllegalStateException("recognizer exploded")
            if (returnNullSession) return null
            listeners += listener
            return session
        }
    }

    /**
     * One controller wired to the fakes. The finals collector runs
     * unconfined so a `trySend` is observed the moment it happens — no
     * dispatcher advance can hide an ordering mistake between the finals
     * channel and the state machine.
     */
    private class Harness(
        scope: CoroutineScope,
        val provider: FakeProvider = FakeProvider(),
        transcribingTimeoutMs: Long = 5_000L,
    ) {
        val controller = InlineDictationController(
            scope = scope,
            provider = provider,
            transcribingTimeoutMs = transcribingTimeoutMs,
        )

        /** The PTY write log: everything that reached `sendBytes`, in order. */
        val sent = mutableListOf<String>()
        val collector = scope.launch(UnconfinedTestDispatcher()) {
            controller.finals.collect { sent += it }
        }
    }

    /** Runs [body] against a fresh harness on `runTest`'s virtual clock. */
    private fun TestScope.harness(
        transcribingTimeoutMs: Long = 5_000L,
        body: Harness.() -> Unit,
    ) {
        val harness = Harness(
            scope = backgroundScope,
            transcribingTimeoutMs = transcribingTimeoutMs,
        )
        harness.body()
        advanceUntilIdle()
        harness.collector.cancel()
    }

    // -------------------------------------------------------- the invariant

    /**
     * THE invariant (#2475): a dictation emits partials and a final, and the
     * PTY sink sees exactly the final — never any partial prefix.
     */
    @Test
    fun `partials never reach sendBytes - the final alone does`() = runTest {
        harness {
            controller.onMicTap(language = null)
            val listener = provider.latestListener!!

            listener.onPartial("hel")
            listener.onPartial("hello wor")
            listener.onPartial("hello world")
            listener.onFinal("hello world")

            assertEquals(listOf("hello world"), sent)
            assertEquals(InlineDictationPhase.Idle, controller.state.value.phase)
            assertNull(controller.state.value.error)
        }
    }

    /**
     * The same invariant under the messy real-world shape: partials around a
     * stop, an error, a fresh dictation, and a stale final from the first
     * abandoned dictation — interleaved the way a flaky recognizer produces
     * them.
     */
    @Test
    fun `partials stay preview-only across errors restarts and stale finals`() = runTest {
        harness {
            controller.onMicTap(null)
            val first = provider.latestListener!!
            first.onPartial("partial one")
            first.onPartial("partial one is longer")
            controller.onMicTap(null) // stop → transcribing
            first.onError(SpeechMessages.FAILED)
            assertEquals(InlineDictationPhase.Idle, controller.state.value.phase)
            assertEquals(emptyList<String>(), sent)

            // Second dictation in the same screen session.
            controller.onMicTap(null)
            val second = provider.latestListener!!
            assertNotEquals(first, second)
            second.onPartial("final two half")
            second.onFinal("final two")

            // The dead first dictation's late final arrives last of all.
            first.onFinal("partial one is longer")

            assertEquals(listOf("final two"), sent)
        }
    }

    // ------------------------------------------------------------ lifecycle

    @Test
    fun `tap starts listening and partials preview in the chip state`() = runTest {
        harness {
            controller.onMicTap(null)

            assertEquals(InlineDictationPhase.Listening, controller.state.value.phase)
            assertEquals("", controller.state.value.partial)
            assertTrue(provider.listeners.isNotEmpty())
            assertEquals(0, provider.session.stopped)

            provider.latestListener!!.onPartial("hello")
            assertEquals("hello", controller.state.value.partial)
            assertEquals(InlineDictationPhase.Listening, controller.state.value.phase)
            assertEquals(emptyList<String>(), sent)
        }
    }

    @Test
    fun `language hint is passed through to the provider per tap`() = runTest {
        harness {
            controller.onMicTap("de")
            assertEquals(listOf<String?>("de"), provider.languages)
        }
    }

    @Test
    fun `stop enters transcribing and a final commits exactly the final text`() = runTest {
        harness {
            controller.onMicTap(null)
            provider.latestListener!!.onPartial("hello wor")
            controller.onMicTap(null)

            assertEquals(InlineDictationPhase.Transcribing, controller.state.value.phase)
            assertEquals(1, provider.session.stopped)
            assertEquals("hello wor", controller.state.value.partial)

            provider.latestListener!!.onFinal("hello world")

            assertEquals(listOf("hello world"), sent)
            assertEquals(InlineDictationPhase.Idle, controller.state.value.phase)
            assertNull(controller.state.value.error)
        }
    }

    @Test
    fun `tap while transcribing is ignored - the in-flight final is not raced`() = runTest {
        harness {
            controller.onMicTap(null)
            controller.onMicTap(null)
            assertEquals(InlineDictationPhase.Transcribing, controller.state.value.phase)
            assertEquals(1, provider.session.stopped)

            controller.onMicTap(null)

            assertEquals(InlineDictationPhase.Transcribing, controller.state.value.phase)
            assertEquals(1, provider.session.stopped)
            assertEquals(emptyList<String>(), sent)
        }
    }

    @Test
    fun `cancel discards everything and a late final never reaches the PTY`() = runTest {
        harness {
            controller.onMicTap(null)
            provider.latestListener!!.onPartial("hel")
            controller.cancel()

            assertEquals(InlineDictationPhase.Idle, controller.state.value.phase)
            assertNull(controller.state.value.error)
            assertTrue(provider.session.cancelled >= 1)

            provider.latestListener!!.onFinal("hel")

            assertEquals(emptyList<String>(), sent)
        }
    }

    // -------------------------------------------------------- failure paths

    @Test
    fun `a stop the recognizer never answers is failed by the virtual-clock watchdog`() =
        runTest {
            harness {
                controller.onMicTap(null)
                provider.latestListener!!.onPartial("still here")
                controller.onMicTap(null)

                advanceTimeBy(4_999)
                runCurrent()
                assertEquals(InlineDictationPhase.Transcribing, controller.state.value.phase)

                advanceTimeBy(1)
                runCurrent()

                assertEquals(InlineDictationPhase.Idle, controller.state.value.phase)
                assertEquals(
                    InlineDictationController.TRANSCRIBING_TIMEOUT_MESSAGE,
                    controller.state.value.error,
                )
                assertEquals(1, provider.session.cancelled)
                assertEquals(emptyList<String>(), sent)
            }
        }

    @Test
    fun `a late final after the watchdog gave up is discarded`() = runTest {
        harness {
            controller.onMicTap(null)
            controller.onMicTap(null)
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(InlineDictationPhase.Idle, controller.state.value.phase)

            provider.latestListener!!.onFinal("too late")

            assertEquals(emptyList<String>(), sent)
            assertEquals(InlineDictationPhase.Idle, controller.state.value.phase)
        }
    }

    @Test
    fun `an error while listening surfaces the message and sends nothing`() = runTest {
        harness {
            controller.onMicTap(null)
            provider.latestListener!!.onPartial("hel")
            provider.latestListener!!.onError("Microphone permission denied.")

            assertEquals(InlineDictationPhase.Idle, controller.state.value.phase)
            assertEquals("Microphone permission denied.", controller.state.value.error)
            assertEquals(emptyList<String>(), sent)
        }
    }

    @Test
    fun `an error instead of a final after stop surfaces and sends nothing`() = runTest {
        harness {
            controller.onMicTap(null)
            controller.onMicTap(null)
            provider.latestListener!!.onError(SpeechMessages.FAILED)

            assertEquals(InlineDictationPhase.Idle, controller.state.value.phase)
            assertEquals(SpeechMessages.FAILED, controller.state.value.error)
            assertEquals(emptyList<String>(), sent)
        }
    }

    @Test
    fun `a blank final is a no-text failure not an empty PTY write`() = runTest {
        harness {
            controller.onMicTap(null)
            provider.latestListener!!.onFinal("   ")

            assertEquals(InlineDictationPhase.Idle, controller.state.value.phase)
            assertEquals(SpeechMessages.NO_TEXT, controller.state.value.error)
            assertEquals(emptyList<String>(), sent)
        }
    }

    @Test
    fun `an unavailable recognizer surfaces the unavailable message`() = runTest {
        harness {
            provider.available = false
            controller.onMicTap(null)

            assertEquals(InlineDictationPhase.Idle, controller.state.value.phase)
            assertEquals(SpeechMessages.UNAVAILABLE, controller.state.value.error)
            assertEquals(emptyList<String?>(), provider.languages)
        }
    }

    @Test
    fun `a provider whose start throws or returns null surfaces the unavailable message`() =
        runTest {
            harness {
                provider.throwOnStart = true
                controller.onMicTap(null)
                assertEquals(SpeechMessages.UNAVAILABLE, controller.state.value.error)
                assertEquals(InlineDictationPhase.Idle, controller.state.value.phase)

                provider.throwOnStart = false
                provider.returnNullSession = true
                controller.onMicTap(null)
                assertEquals(SpeechMessages.UNAVAILABLE, controller.state.value.error)
                assertEquals(InlineDictationPhase.Idle, controller.state.value.phase)
            }
        }

    @Test
    fun `permission denied surfaces the message without ever starting a recognizer`() =
        runTest {
            harness {
                controller.onPermissionDenied()

                assertEquals(InlineDictationPhase.Idle, controller.state.value.phase)
                assertEquals(
                    InlineDictationController.PERMISSION_DENIED_MESSAGE,
                    controller.state.value.error,
                )
                assertEquals(emptyList<String?>(), provider.languages)
                assertEquals(emptyList<String>(), sent)
            }
        }

    @Test
    fun `the next successful tap clears a previous error`() = runTest {
        harness {
            provider.available = false
            controller.onMicTap(null)
            assertEquals(SpeechMessages.UNAVAILABLE, controller.state.value.error)

            provider.available = true
            controller.onMicTap(null)

            assertEquals(InlineDictationPhase.Listening, controller.state.value.phase)
            assertNull(controller.state.value.error)
        }
    }
}
