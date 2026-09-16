package com.pocketshell.next.render

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.takahirom.roborazzi.captureRoboImage
import com.pocketshell.next.composer.ComposerBar
import com.pocketshell.next.composer.ComposerNotice
import com.pocketshell.next.composer.ComposerUiState
import com.pocketshell.next.composer.MessageHistorySheet
import com.pocketshell.next.composer.RecordingState
import com.pocketshell.next.composer.SentMessage
import com.pocketshell.next.composer.StagedAttachment
import com.pocketshell.next.composer.StagingProgress
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import com.pocketshell.testsupport.LeakGuard
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fast design-render harness for the composer (task P-1), alongside
 * [HostScreenRenders] and for the same reason (issue #555).
 *
 * ```
 * ./gradlew :app2:testDebugUnitTest --tests '*ComposerRenders*' --rerun-tasks
 * # then open the PNGs under app2/build/renders/
 * ```
 *
 * These are renders, not assertions — they exist to be looked at while
 * iterating, and every state below is behaviourally covered by
 * `ComposerBarTest`. The emulator journey (`J07ComposerSendJourney`) remains
 * the acceptance gate: only it shows the composer against the real keyboard,
 * which is the geometry that actually goes wrong.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class ComposerRenders {

    // Issue #2724: record-mode captures compose screens a plain unit run never
    // composes, so a leak from that composition fails HERE (the class-guard
    // grace catches post-test stragglers), not whichever runTest class is next.
    @get:Rule
    val leakGuard = LeakGuard()

    /**
     * Issue #2635 C3: the idle row at the narrowest supported width — paste is
     * Send's long-press (not a fifth control), so attach/tools/Send/mic must
     * all fit unclipped. The pixel-boundary pin lives in
     * `ComposerIdleControlsNarrowScreenTest` (ui-kit); this is the visual twin.
     */
    @Test
    @Config(qualifiers = "w360dp-h915dp-night-xxhdpi")
    fun composerIdle360() = render("i2635-composer-idle-360") {
        ComposerBar(state = ComposerUiState(draft = "check the deploy log", micAvailable = true))
    }

    @Test
    fun composerEmpty() = render("p1-composer-empty") {
        ComposerBar(state = ComposerUiState(micAvailable = true))
    }

    @Test
    fun composerWithDraft() = render("p1-composer-draft") {
        ComposerBar(
            state = ComposerUiState(
                draft = "Run the full test suite and summarise what failed.",
                micAvailable = true,
            ),
        )
    }

    /** The whole delivery story, as the user sees it. */
    @Test
    fun composerUndelivered() = render("p1-composer-undelivered") {
        ComposerBar(
            state = ComposerUiState(
                draft = "Run the full test suite and summarise what failed.",
                notice = ComposerNotice.Undelivered,
                micAvailable = true,
            ),
        )
    }

    @Test
    fun composerWithAttachments() = render("p1-composer-attachments") {
        ComposerBar(
            state = ComposerUiState(
                draft = "Have a look at these two.",
                attachments = listOf(
                    StagedAttachment("~/.pocketshell/attachments/7-devbox/a.png", "screenshot.png"),
                    StagedAttachment("~/.pocketshell/attachments/7-devbox/b.log", "build-output.log"),
                ),
                micAvailable = true,
            ),
        )
    }

    @Test
    fun composerUploading() = render("p1-composer-uploading") {
        ComposerBar(
            state = ComposerUiState(
                draft = "Have a look at these.",
                staging = StagingProgress(2, 3, "20260903-114210-02-screenshot.png"),
                micAvailable = true,
            ),
        )
    }

    @Test
    fun composerRecording() = render("p1-composer-recording") {
        ComposerBar(
            state = ComposerUiState(
                draft = "run the tests and",
                recording = RecordingState.Recording,
                micAvailable = true,
            ),
        )
    }

    /**
     * #2602: the dictation rows share one Send treatment — the demoted
     * outline, so the filled accent belongs to the Stop disc alone while a
     * dictation is live. Transcribing has no Stop disc, and Send staying
     * muted there keeps the "draft is not committed yet" grammar unbroken
     * from recording through transcription.
     */
    @Test
    fun composerTranscribing() = render("p1-composer-transcribing") {
        ComposerBar(
            state = ComposerUiState(
                draft = "run the tests and",
                recording = RecordingState.Transcribing,
                micAvailable = true,
            ),
        )
    }

    @Test
    fun composerPreview() = render("p1-composer-preview") {
        ComposerBar(
            state = ComposerUiState(
                draft = "## Plan\n\n- fix the parser\n- add `--json`\n\nThen ship it.",
                previewing = true,
                micAvailable = true,
            ),
        )
    }

    @Test
    fun messageHistory() = render("p1-composer-history") {
        MessageHistorySheet(
            messages = listOf(
                SentMessage(3, "Run the full test suite and summarise what failed.", SENT_AT, true),
                SentMessage(2, "this one never left the phone", SENT_AT - 600_000, false),
                SentMessage(1, "git status", SENT_AT - 3_600_000, true),
            ),
            onPick = {},
            onDismiss = {},
        )
    }

    @Composable
    private fun ComposerBar(state: ComposerUiState) = ComposerBar(
        state = state,
        onDraftChange = {},
        onSend = {},
        onInsert = {},
        onAttach = {},
        onMicTap = {},
        onCancelRecording = {},
        onToggleHistory = {},
        onTogglePreview = {},
        onRemoveAttachment = {},
        onDismissNotice = {},
        onDiscard = {},
    )

    private fun render(name: String, content: @Composable () -> Unit) {
        captureRoboImage("build/renders/$name.png") {
            PocketShellTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = PocketShellColors.Background) {
                    Column(modifier = Modifier.fillMaxSize()) { content() }
                }
            }
        }
    }

    companion object {
        @JvmStatic
        @get:ClassRule
        val leakGuardClass = LeakGuard.classGuard()

        const val SENT_AT = 1_756_900_000_000L
    }
}
