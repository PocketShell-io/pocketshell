package com.pocketshell.next.composer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.pocketshell.uikit.components.SheetHeader

const val COMPOSER_TITLE_TAG: String = "composer-title"
const val COMPOSER_CLOSE_TAG: String = "composer-close"
const val COMPOSER_SHEET_TITLE: String = "Prompt Composer"

/**
 * The targeted form of [COMPOSER_SHEET_TITLE] (#2802 C-5).
 *
 * Still the noun the sheet acts on — the input — with the session it is
 * bound to appended, so the header grammar `SessionSheetTitlesTest` pins is
 * checkable on this sheet too rather than hiding inside an interpolation.
 */
const val COMPOSER_SHEET_TITLE_PREFIX: String = "Input to "

/**
 * Sheet body without the modal window, so host-JVM tests can drive Insert /
 * Send / mic without Robolectric dropping clicks on a `ModalBottomSheet`.
 *
 * The Android permission launcher and modal route stay in app2's
 * `PromptComposerSheet`; this shared body sees only display state and callbacks.
 */
@Composable
fun PromptComposerContent(
    state: ComposerUiState,
    targetLabel: String = "Terminal",
    onClose: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onInsert: () -> Unit,
    onAttach: () -> Unit,
    onMicTap: () -> Unit,
    onCancelRecording: () -> Unit,
    onToggleHistory: () -> Unit,
    onTogglePreview: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onDismissNotice: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
    imeVisible: Boolean = false,
    deliveryEnabled: Boolean = true,
    deliveryDisabledMessage: String? = null,
    availableSlashCommands: List<SlashCommand> = SlashCommandAutocomplete.CATALOG,
) {
    val title = targetLabel.trim().takeIf { it.isNotEmpty() }?.let { COMPOSER_SHEET_TITLE_PREFIX + it }
        ?: COMPOSER_SHEET_TITLE
    Column(modifier = modifier.navigationBarsPadding()) {
        SheetHeader(
            title = title,
            titleTestTag = COMPOSER_TITLE_TAG,
            onClose = onClose,
            closeContentDescription = "Close Prompt Composer",
            closeTestTag = COMPOSER_CLOSE_TAG,
        )
        ComposerBar(
            state = state,
            onDraftChange = onDraftChange,
            onSend = onSend,
            onInsert = onInsert,
            onAttach = onAttach,
            onMicTap = onMicTap,
            onCancelRecording = onCancelRecording,
            onToggleHistory = onToggleHistory,
            onTogglePreview = onTogglePreview,
            onRemoveAttachment = onRemoveAttachment,
            onDismissNotice = onDismissNotice,
            onDiscard = onDiscard,
            deliveryEnabled = deliveryEnabled,
            deliveryDisabledMessage = deliveryDisabledMessage,
            availableSlashCommands = availableSlashCommands,
        )
    }
}
