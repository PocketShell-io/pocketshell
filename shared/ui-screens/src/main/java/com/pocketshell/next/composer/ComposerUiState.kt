package com.pocketshell.next.composer

/**
 * What the composer is telling the user right now.
 *
 * [Undelivered] is the entire delivery story: the send did not go out, the
 * draft is still here, and the user decides what to do about it. There is no
 * retry timer, no queue depth and no "will send when reconnected", because
 * there is no queue — sending ONE message reliably is the job, and a message
 * that did not leave simply did not leave.
 *
 * Lives in the shared presentation module (#2636 D2). The transport-facing
 * sink the send path goes through stays in app2, beside the session logic
 * that implements it.
 */
sealed interface ComposerNotice {

    data object Undelivered : ComposerNotice

    /** PTY input was attempted, but the connection dropped before delivery was knowable. */
    data object DeliveryUncertain : ComposerNotice

    /** Something failed (an upload, a connection) — [message] says what. */
    data class Problem(val message: String) : ComposerNotice

    /** Something worked and is worth a word (an attachment landed). */
    data class Info(val message: String) : ComposerNotice
}

/**
 * Attachment upload progress for the staging row: "2 of 3 · screenshot.png".
 *
 * [fileBytesWritten]/[fileBytesTotal] are the current file's byte progress,
 * reported by the transport itself (#2686) — never a timer. Both zero means
 * the current file has no byte information (not writing yet, or a channel
 * that reports no bytes), and the bar then falls back to the file-level
 * ratio #2568 shipped, so a byte-silent channel behaves exactly as before.
 *
 * Lives in the shared presentation module (#2636 D2).
 */
data class StagingProgress(
    val index: Int,
    val count: Int,
    val name: String,
    val fileBytesWritten: Long = 0,
    val fileBytesTotal: Long = 0,
) {
    /**
     * The fraction the staging bar renders: finished files plus the current
     * file's byte share, so a single large upload moves continuously instead
     * of sitting pinned at `index/count` (#2686). Always clamped to `[0, 1]`.
     */
    val barFraction: Float
        get() {
            if (count <= 0) return 0f
            val fileShare = if (fileBytesTotal > 0) {
                (fileBytesWritten.toFloat() / fileBytesTotal).coerceIn(0f, 1f)
            } else {
                1f
            }
            return ((index - 1 + fileShare) / count).coerceIn(0f, 1f)
        }
}

/**
 * One entry of the per-session sent-message log.
 *
 * Lives in the shared presentation module (#2636 D2); the Room entity behind
 * it (`SentMessageEntity`, core-storage) stops at app2's route/ViewModel
 * boundary.
 */
data class SentMessage(
    val id: Long,
    val body: String,
    val sentAtMs: Long,
    val delivered: Boolean,
) {
    /** The single line the history list shows. Tapping still restores [body] whole. */
    val label: String get() = ComposerText.historyLabel(body)
}

/**
 * Everything the composer surface renders.
 *
 * Lives in the shared presentation module (#2636 D2); the send path's
 * transport sink and the ViewModel that produces this state stay in app2.
 */
data class ComposerUiState(
    val draft: String = "",
    val attachments: List<StagedAttachment> = emptyList(),
    val recording: RecordingState = RecordingState.Idle,
    val staging: StagingProgress? = null,
    val notice: ComposerNotice? = null,
    /** The draft is shown rendered as Markdown instead of editable. */
    val previewing: Boolean = false,
    val historyOpen: Boolean = false,
    val history: List<SentMessage> = emptyList(),
    /** False when no speech recognizer is wired; the mic renders disabled. */
    val micAvailable: Boolean = false,
) {
    /**
     * A send is possible. An attachment with no text counts: "here is the
     * screenshot" is a complete message when the paths are the content.
     */
    val canSend: Boolean get() = draft.isNotBlank() || attachments.isNotEmpty()

    /** An upload is in flight; the send and the picker are held off until it lands. */
    val busy: Boolean get() = staging != null

    /** The draft survived a send that did not leave the device. */
    val undelivered: Boolean get() = notice is ComposerNotice.Undelivered

    val deliveryUncertain: Boolean get() = notice is ComposerNotice.DeliveryUncertain
}
