package com.pocketshell.next.terminal

import android.Manifest
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.next.MainActivity
import com.pocketshell.next.connect.AgentsFixture
import com.pocketshell.next.connect.JourneyScreenshots
import com.pocketshell.next.connect.SeedBeforeLaunchRule
import com.pocketshell.next.connect.appGraph
import com.pocketshell.next.connect.awaitIdle
import com.pocketshell.next.connect.idleWedgeNote
import com.pocketshell.next.connect.openQuietSession
import com.pocketshell.next.composer.ScriptedSpeechRecognitionProvider
import com.pocketshell.uikit.components.SESSION_BAR_DICTATION_CHIP_TAG
import com.pocketshell.uikit.components.SESSION_BAR_ENTER_TAG
import com.pocketshell.uikit.components.SESSION_BAR_MIC_TAG
import com.pocketshell.uikit.components.SESSION_TERMINAL_BAR_TAG
import com.termux.view.TerminalView
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.File
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.Description
import org.junit.runner.RunWith

/**
 * Journey J23 — the terminal key-bar mic dictates straight into the live PTY
 * (#2475): mic tap → final transcript verifiably lands at the remote cursor.
 *
 * ## Why a scripted recognizer, not a real microphone
 *
 * The same seam J08 uses: an instrumented test has no microphone and CI has no
 * speech to feed one. J08's `TestVoiceModule` replaces `di/VoiceModule` for
 * EVERY `androidTest` in this module, so the `SpeechRecognitionProvider`
 * [InlineDictationViewModel] injects is already the process-wide
 * [ScriptedSpeechRecognitionProvider] — this journey drives that exact
 * instance the app is using, the way J08 drives it for the composer. Only the
 * recognizer is a double: the route's permission gate
 * (`decideMicTap` + the RECORD_AUDIO launcher), the bar's mic slot and status
 * chip, the controller's finals channel, and `SessionViewModel.sendBytes` are
 * all production code on a real device against a real sshd.
 *
 * ## The oracle: device render AND host pane, the J03 discipline
 *
 * A dictated final goes out as raw bytes with NO trailing newline, so it sits
 * at the remote cursor exactly where typed input would — the shell echoes it
 * but runs nothing. Each landing is therefore proven three ways:
 *
 *  1. the rendered transcript shows the dictated line (the screen is not
 *     black),
 *  2. `a capture --screen --plain` over an INDEPENDENT SSH connection shows
 *     the same bytes on the host's own view of the pane (they really crossed
 *     the wire — locally-echoed bytes that never left cannot satisfy this),
 *  3. the bar's Enter key submits them and the command's OUTPUT comes back
 *     (the bytes reached the shell's line editor, not a dead buffer).
 *
 * The never-send-a-partial invariant is asserted against the same host oracle:
 * a partial or an error exists only in the bar's chip, and the host pane must
 * never contain it.
 *
 * ## Fixture
 *
 * The Docker `agents` fixture (see [AgentsFixture]), seeded with a real
 * aplexer session and a pinned `PS1`, exactly as J03 does. Bring it up before
 * running:
 * `docker compose -f tests/docker/docker-compose.yml up -d --build agents`
 *
 * Per-test host ids, for the reason J01/J02 use them: SQLite reuses
 * `max(id) + 1`, and a reused id plus the registry's one-connection-per-host
 * cache would let a later test pass on an earlier test's connection.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class J23InlineDictationJourney {

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain
        .outerRule(HiltAndroidRule(this))
        .around(SeedBeforeLaunchRule { description -> seed(description) })
        .around(compose)

    private var hostId: Long = 0

    private suspend fun seed(description: Description) {
        val graph = appGraph()
        graph.connectionsRegistry().closeAll()
        graph.hostDao().getAll().first().forEach { graph.hostDao().deleteById(it.id) }
        graph.sshKeyDao().getAll().first().forEach { graph.sshKeyDao().deleteById(it.id) }

        val fingerprint = AgentsFixture.probeHostKeyFingerprint()
        println("J23_FIXTURE ${AgentsFixture.host}:${AgentsFixture.port} $fingerprint")

        seedAplexerSession()

        val keyPath = AgentsFixture.installPrivateKey(fileName = "j23_fixture_key")
        val keyId = graph.sshKeyDao().insert(
            SshKeyEntity(name = "j23-${description.methodName}", privateKeyPath = keyPath),
        )
        hostId = HOST_IDS.getValue(description.methodName)
        graph.hostDao().insert(
            HostEntity(
                id = hostId,
                name = "docker-fixture",
                hostname = AgentsFixture.host,
                port = AgentsFixture.port,
                username = AgentsFixture.USER,
                keyId = keyId,
                trustedHostKeyAlgorithm = "SHA256",
                trustedHostKeySha256 = fingerprint,
            ),
        )

        // The scripted recognizer is a process-wide Hilt singleton shared with
        // J08 — a previous journey's live listener must not survive into this
        // one's dictations.
        ScriptedSpeechRecognitionProvider.reset()
    }

    /** Creates a real aplexer shell and paints a known prompt plus marker. */
    private fun seedAplexerSession() {
        AgentsFixture.exec("pocketshell sessions kill -- '$SESSION' >/dev/null 2>&1 || true")
        AgentsFixture.exec(
            "pocketshell sessions create --cwd '$WORKSPACE' --mem none --json -- '$TAG' >/dev/null",
        )
        AgentsFixture.exec(
            "a send --workspace '$WORKSPACE' --tag '$TAG' --enter " +
                "'PS1=\"$PROMPT \"; clear; echo $BANNER'",
        )
        SystemClock.sleep(500)
        val screen = capturePane()
        check(squashed(screen).contains(BANNER)) {
            "the fixture aplexer session did not come up: a capture says\n$screen"
        }
    }

    /**
     * The headline journey (the 02:05Z review's required flip): a key-bar mic
     * tap starts a dictation, partials show ONLY in the bar's chip, and the
     * final transcript lands at the remote cursor — proven by the device
     * render, the host's own pane capture, and the dictated command's output.
     */
    @Test
    fun aKeyBarMicFinalLandsAtTheRemoteCursor() {
        openSession()
        grantRecordAudio()

        // Idle chrome: the mic is on the bar and no dictation strip exists.
        compose.onNodeWithTag(SESSION_TERMINAL_BAR_TAG).assertIsDisplayed()
        compose.onNodeWithTag(SESSION_BAR_MIC_TAG).assertIsDisplayed()
        compose.onAllNodesWithTag(SESSION_BAR_DICTATION_CHIP_TAG).assertCountEquals(0)

        tapMic()
        awaitChip("the listening chip", contains = "Listening")
        capture("01-listening")

        // A partial is preview text: it renders in the chip and must never
        // reach the PTY. The token is deliberately NOT part of the final, so
        // a pane that ever shows it is a leaked partial, not an echo.
        ScriptedSpeechRecognitionProvider.partial(PARTIAL_ONLY)
        compose.awaitIdle("after the partial transcript")
        awaitChip("the partial in the chip", contains = PARTIAL_TOKEN)
        capture("02-partial-in-chip")
        assertPaneNeverShows("$PARTIAL_TOKEN (the partial)", reads = 3)

        ScriptedSpeechRecognitionProvider.final(DICTATED_CMD)
        compose.awaitIdle("after the final transcript")
        awaitChipGone("the dictation chip after the final")
        assertDictationReachedTheCursor(DICTATED_CMD, DICTATED_MARKER, "03-dictated-landed")
    }

    /**
     * The stop half of the tap: a second mic tap while listening must put the
     * bar into Transcribing — the transcript is in flight, NOT abandoned —
     * and the recognizer's final then lands at the cursor like any other.
     */
    @Test
    fun theStopTapResolvesTheDictationToTheCursor() {
        openSession()
        grantRecordAudio()

        tapMic()
        awaitChip("the listening chip", contains = "Listening")

        // Stop before anything was recognized: the chip must switch to
        // "Transcribing…", not vanish — an idle bar here would mean the stop
        // threw the in-flight transcript away.
        tapMic()
        awaitChip("the transcribing chip", contains = "Transcribing")
        capture("04-transcribing")
        assertPaneNeverShows("the transcribing status text (Transcribing)")

        ScriptedSpeechRecognitionProvider.final(STOPPED_CMD)
        compose.awaitIdle("after the stopped dictation's final")
        awaitChipGone("the dictation chip after the stop resolved")
        assertDictationReachedTheCursor(STOPPED_CMD, STOPPED_MARKER, "05-stopped-landed")
    }

    /**
     * The error path: a recognizer failure mid-dictation surfaces in the bar's
     * chip, sends NOTHING to the PTY (the doomed partial included), and leaves
     * the bar usable — the next dictation still lands.
     */
    @Test
    fun aRecognizerErrorShowsInTheChipAndSendsNothing() {
        openSession()
        grantRecordAudio()

        tapMic()
        awaitChip("the listening chip", contains = "Listening")
        ScriptedSpeechRecognitionProvider.partial(ERRORED_PARTIAL)
        compose.awaitIdle("after the doomed partial")
        awaitChip("the doomed partial in the chip", contains = ERRORED_TOKEN)

        ScriptedSpeechRecognitionProvider.error(ERROR_MESSAGE)
        compose.awaitIdle("after the recognizer error")
        awaitChip("the error in the chip", contains = ERROR_MESSAGE)
        capture("06-error-in-chip")

        // Nothing crossed the wire — not the partial the user saw, not the
        // error itself. Read the pane a few times over ~2s so an async leak
        // cannot land after the first read.
        assertPaneNeverShows("$ERRORED_TOKEN (the errored dictation's partial)", reads = 4)
        assertPaneNeverShows(ERROR_MESSAGE, reads = 4)

        // The error must not brick the bar: the next dictation still lands.
        tapMic()
        awaitChip("the bar listening again after the error", contains = "Listening")
        ScriptedSpeechRecognitionProvider.final(RECOVERY_CMD)
        compose.awaitIdle("after the recovery dictation's final")
        awaitChipGone("the dictation chip after the recovery final")
        assertDictationReachedTheCursor(RECOVERY_CMD, RECOVERY_MARKER, "07-recovered-landed")
    }

    // --- helpers ----------------------------------------------------------

    private fun openSession() {
        compose.openQuietSession(hostId, SESSION, WORKSPACE, TIMEOUT_MS)
        awaitTag(SESSION_SCREEN_TAG, "the session screen")
        // The fixture banner is the LIVE proof: the mic slot is only enabled
        // while the session can receive bytes, so every dictation below is
        // tapped against a session that is actually attached.
        awaitSquashedTranscript("the fixture's banner line") { it.contains(BANNER) }
    }

    private fun grantRecordAudio() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            Manifest.permission.RECORD_AUDIO,
        )
    }

    private fun tapMic() {
        compose.awaitIdle("before the mic tap")
        compose.onNodeWithTag(SESSION_BAR_MIC_TAG).performClick()
        compose.awaitIdle("after the mic tap")
    }

    /** The dictation chip's current text, or null while it is not composed. */
    private fun chipText(): String? {
        val nodes = compose.onAllNodesWithTag(SESSION_BAR_DICTATION_CHIP_TAG)
            .fetchSemanticsNodes()
        if (nodes.isEmpty()) return null
        return nodes[0].config.getOrNull(SemanticsProperties.Text)?.joinToString("")
    }

    /** Polls until the chip is composed and its text contains [contains]. */
    private fun awaitChip(what: String, contains: String) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        var last: String? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.awaitIdle("chip poll: $what")
            last = chipText()
            if (last != null && last.contains(contains)) return
            SystemClock.sleep(POLL_MS)
        }
        val shot = capture("failure-chip-${what.replace(' ', '-')}")
        throw AssertionError(
            "$what never showed \"$contains\" within ${TIMEOUT_MS}ms " +
                "(chip text: ${last ?: "<absent>"}). Screenshot: ${shot.absolutePath}" +
                compose.idleWedgeNote(),
        )
    }

    /** Waits for the chip to leave the tree (a dictation that resolved). */
    private fun awaitChipGone(what: String) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.awaitIdle("chip-gone poll: $what")
            if (compose.onAllNodesWithTag(SESSION_BAR_DICTATION_CHIP_TAG)
                .fetchSemanticsNodes().isEmpty()
            ) {
                return
            }
            SystemClock.sleep(POLL_MS)
        }
        val shot = capture("failure-gone-${what.replace(' ', '-')}")
        throw AssertionError(
            "$what never went away within ${TIMEOUT_MS}ms " +
                "(chip text: ${chipText() ?: "<absent>"}). " +
                "Screenshot: ${shot.absolutePath}" + compose.idleWedgeNote(),
        )
    }

    /**
     * The three-way landing proof: device render, host pane, then the bar's
     * Enter submits the dictated line and the command's output comes back on
     * both. Split into >= 3 parts on the device transcript: the echo and the
     * command's own output are two separate occurrences, which a screen that
     * merely rendered local bytes could never produce.
     */
    private fun assertDictationReachedTheCursor(command: String, marker: String, shot: String) {
        val line = squashed(command)
        val rendered = awaitSquashedTranscript("the dictated line") { it.contains(line) }
        val pane = capturePane()
        assertTrue(
            "the host's own pane must hold the dictated bytes, rendered:\n$rendered\npane:\n$pane",
            squashed(pane).contains(line),
        )
        capture(shot)

        // The dictated bytes sit at the cursor, un-executed (no trailing
        // newline came with them): the bar's Enter is what runs them.
        compose.onNodeWithTag(SESSION_BAR_ENTER_TAG).performClick()
        compose.awaitIdle("after the bar's Enter submitted the dictation")
        awaitSquashedTranscript("the dictated command's output") {
            it.split(marker).size >= 3
        }
        assertTrue(
            "the host must show the dictated command's output",
            squashed(capturePane()).contains(marker),
        )
    }

    /**
     * The never-send side of the invariant, against the independent host
     * oracle: whatever [what] names exists only in the bar's chip, and the
     * host's own view of the pane must not contain it.
     */
    private fun assertPaneNeverShows(what: String, reads: Int = 1) {
        repeat(reads) {
            val pane = capturePane()
            val leaked = LEAK_TOKENS.firstOrNull { squashed(pane).contains(it) }
            if (leaked != null) {
                throw AssertionError(
                    "$what leaked to the PTY — the host pane contains \"$leaked\":\n$pane",
                )
            }
            if (reads > 1) SystemClock.sleep(500)
        }
    }

    /**
     * Polls the LIVE emulator's screen buffer until the squashed transcript
     * satisfies [predicate] — the J03 shape: [compose.awaitIdle] every turn so
     * the app's test-driven frame clock never starves, and the grid read on
     * the main thread, where the vendored emulator renders.
     */
    private fun awaitSquashedTranscript(what: String, predicate: (String) -> Boolean): String {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        var last = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.awaitIdle("transcript poll: $what")
            last = renderedTranscript()
            if (predicate(squashed(last))) return last
            SystemClock.sleep(POLL_MS)
        }
        val shot = capture("failure-${what.replace(' ', '-')}")
        throw AssertionError(
            "the terminal never rendered $what within ${TIMEOUT_MS}ms.\n" +
                "Rendered viewport was:\n$last\n" +
                "The host's own aplexer capture says:\n" + capturePane() + "\n" +
                "Screenshot: ${shot.absolutePath}" + compose.idleWedgeNote(),
        )
    }

    private fun awaitTag(tag: String, what: String) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.awaitIdle("tag poll: $what")
            if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) return
            SystemClock.sleep(POLL_MS)
        }
        val shot = capture("failure-${what.replace(' ', '-')}")
        throw AssertionError(
            "$what ($tag) never appeared within ${TIMEOUT_MS}ms. " +
                "Screenshot: ${shot.absolutePath}" + compose.idleWedgeNote(),
        )
    }

    /**
     * The text the terminal is actually showing, straight out of the vendored
     * `TerminalBuffer`, read on the main thread. Empty until the view has laid
     * out once and created its emulator.
     */
    private fun renderedTranscript(): String {
        var text = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            text = terminalView()?.mEmulator?.screen?.transcriptText.orEmpty()
        }
        return text
    }

    /** Finds the hosted [TerminalView] in the running Activity, or null. */
    private fun terminalView(): TerminalView? =
        findTerminalView(compose.activity.window.decorView)

    private fun findTerminalView(view: View): TerminalView? {
        if (view is TerminalView) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findTerminalView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    /**
     * The host's own view of the pane, over an INDEPENDENT SSH connection —
     * never the app's. A seed or oracle sharing the transport under test would
     * turn a broken app connection into a broken-looking fixture.
     */
    private fun capturePane(): String =
        AgentsFixture.exec(
            "a capture --workspace '$WORKSPACE' --tag '$TAG' --screen --plain " +
                "2>/dev/null || true",
        )

    /** Keep current real-device captures after the connected-test app is removed. */
    private fun capture(name: String): File {
        val file = JourneyScreenshots.capture(name, JOURNEY)
        val outputDir = InstrumentationRegistry.getArguments()
            .getString("additionalTestOutputDir")
            ?.takeIf { it.isNotBlank() }
            ?: return file
        runCatching {
            val targetDir = File(outputDir, JOURNEY).apply { mkdirs() }
            file.parentFile?.listFiles()
                ?.filter { it.isFile && it.name.startsWith(file.nameWithoutExtension) }
                ?.forEach { artifact ->
                    val target = File(targetDir, artifact.name)
                    artifact.copyTo(target, overwrite = true)
                    println("J23_SCREENSHOT ${target.absolutePath}")
                }
        }
        return file
    }

    /**
     * Whitespace-free view of terminal text, for wrap-proof matching: the
     * phone's column count is whatever the device's font metrics produced, so
     * the aplexer capture and the rendered transcript can each wrap a marker
     * at a different point. Every matched string here is whitespace-free by
     * construction.
     */
    private fun squashed(text: String): String = text.filterNot { it.isWhitespace() }

    private companion object {
        const val TIMEOUT_MS = 60_000L
        const val POLL_MS = 250L

        const val JOURNEY = "j23-inline-dictation"

        const val TAG = "j23-shell"
        const val WORKSPACE = "/home/testuser"
        const val SESSION = "testuser:j23-shell"
        const val PROMPT = "J23READY\$"
        const val BANNER = "J23-FIXTURE-PANE"

        /**
         * The dictated final for the headline journey, and the partial that
         * must never leave the chip. The partial's token is deliberately not
         * a substring of the final, so a pane containing it proves a leaked
         * partial rather than an echo of the real transcript.
         */
        const val DICTATED_CMD = "echo POCKETSHELL-J23-DICTATED"
        const val DICTATED_MARKER = "POCKETSHELL-J23-DICTATED"
        const val PARTIAL_ONLY = "echo partial-scrapped-never-sent"
        const val PARTIAL_TOKEN = "partial-scrapped-never-sent"

        /** The stop path's dictated line. */
        const val STOPPED_CMD = "echo POCKETSHELL-J23-STOPPED"
        const val STOPPED_MARKER = "POCKETSHELL-J23-STOPPED"

        /** The error path's doomed partial, failure text, and recovery final. */
        const val ERRORED_PARTIAL = "echo errored-partial-never-sent"
        const val ERRORED_TOKEN = "errored-partial-never-sent"
        const val ERROR_MESSAGE = "scripted recognizer failure"
        const val RECOVERY_CMD = "echo POCKETSHELL-J23-RECOVERED"
        const val RECOVERY_MARKER = "POCKETSHELL-J23-RECOVERED"

        /**
         * Every string the host pane must NEVER contain, in one table: the
         * partial tokens and the recognizer error text are chip-only by the
         * invariant under test, and the transcribing status belongs to the
         * bar, not the shell.
         */
        val LEAK_TOKENS = listOf(
            PARTIAL_TOKEN,
            ERRORED_TOKEN,
            ERROR_MESSAGE,
            "Transcribing",
        )

        val HOST_IDS: Map<String, Long> = mapOf(
            "aKeyBarMicFinalLandsAtTheRemoteCursor" to 9_851L,
            "theStopTapResolvesTheDictationToTheCursor" to 9_852L,
            "aRecognizerErrorShowsInTheChipAndSendsNothing" to 9_853L,
        )
    }
}
