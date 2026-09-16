package com.pocketshell.next.terminal

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.pocketshell.next.composer.ComposerUiState
import com.pocketshell.next.composer.ComposerViewModel
import com.pocketshell.next.composer.DeliveryUncertainReview
import com.pocketshell.next.composer.MessageHistorySheet
import com.pocketshell.next.composer.PromptComposerContent
import com.pocketshell.next.composer.PromptComposerSheet
import com.pocketshell.next.composer.SentMessage
import com.pocketshell.next.composer.SessionSink
import com.pocketshell.next.composer.SlashCommandAutocomplete
import com.pocketshell.core.hostapi.AgentState
import com.pocketshell.core.hostapi.SessionRow
import com.pocketshell.next.tree.STOP_SESSION_CANCEL_TAG
import com.pocketshell.next.tree.STOP_SESSION_CONFIRM_LABEL
import com.pocketshell.next.tree.STOP_SESSION_CONFIRM_TAG
import com.pocketshell.next.tree.STOP_SESSION_ITEM_LABEL
import com.pocketshell.next.tree.STOP_SESSION_ITEM_TAG
import com.pocketshell.next.tree.STOP_SESSION_MESSAGE_TAG
import com.pocketshell.next.tree.STOP_SESSION_TITLE
import com.pocketshell.next.tree.STOP_SESSION_TITLE_TAG
import com.pocketshell.next.tree.stopSessionMessage
import com.pocketshell.next.usage.UsageGlancePillState
import com.pocketshell.next.usage.UsageGlancePill
import com.pocketshell.next.usage.UsageGlanceViewModel
import com.pocketshell.next.settings.LocalAppSettings
import com.pocketshell.next.workspaces.readableSessionName
import com.pocketshell.next.workspaces.sessionDisplayNames
import com.pocketshell.uikit.components.Banner
import com.pocketshell.uikit.components.BannerRole
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.ConfirmDialog
import com.pocketshell.uikit.components.EmptyState
import com.pocketshell.uikit.components.HeaderIconAction
import com.pocketshell.uikit.components.KebabTrigger
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.components.SessionTab
import com.pocketshell.uikit.components.SessionTabState
import com.pocketshell.uikit.components.SessionTabStrip
import com.pocketshell.uikit.components.SessionNavKey
import com.pocketshell.uikit.components.SessionTerminalBar
import com.pocketshell.uikit.components.TerminalHotkeysPaletteOverlay
import com.pocketshell.uikit.icons.PocketShellIcons
import com.pocketshell.uikit.model.ConnectionStatus
import com.pocketshell.uikit.model.KeyBinding
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType
import com.termux.terminal.TerminalSession

/** Stable test tags for the session screen's own chrome. */
const val SESSION_SCREEN_TAG: String = "session-screen"
const val SESSION_TITLE_TAG: String = "session-title"
const val SESSION_CONNECTING_TAG: String = "session-connecting"
const val SESSION_ERROR_BANNER_TAG: String = "session-error-banner"
const val SESSION_RECONNECT_BANNER_TAG: String = "session-reconnect-banner"
const val SESSION_RETRY_TAG: String = "session-retry"
const val SESSION_BACK_TAG: String = "session-back"
/** Fallback Usage control when the glance pill has no reading (issue #2532). */
const val SESSION_USAGE_TAG: String = "session-usage"
const val SESSION_HEADER_KEBAB_TAG: String = "session-header-kebab"
const val SESSION_STOP_FAILURE_TAG: String = "session-stop-failure"
const val SESSION_ACTIONS_ITEM_TAG: String = "session-actions-item"
const val SESSION_ENDED_TAG: String = "session-ended"

/** D3: header status dot — colour from [ConnectionStatus], label from the status description. */
const val SESSION_STATUS_DOT_TAG: String = "session-status-dot"
/** D3: the strip "+" label, reused by the header "+" when the strip is hidden. */
const val SESSION_TAB_NEW_DESCRIPTION: String = "New session"
/** D3: test tag for the header "+" shown while the tab strip is hidden. */
const val SESSION_HEADER_NEW_TAG: String = "session-header-new"

/**
 * Route-level entry point for `session/{hostId}/{sessionName}` (rewrite tasks
 * U-4, U-5, U-7, P-1, and #2521).
 *
 * Two ViewModels, one screen. [SessionViewModel] owns the transport and the
 * terminal; [ComposerViewModel] owns the draft, its attachments and its
 * history. They meet at exactly one place — the [SessionSink] built here — and
 * that seam is deliberately two members wide, so the composer can never grow a
 * second opinion about whether the session is attached.
 *
 * The sink reads `uiState.value` at call time rather than closing over the
 * collected state: a sink built from a snapshot would answer "live" from
 * whenever the screen last recomposed, which is precisely when a send would
 * vanish into a dead pane and the draft would be cleared for it.
 *
 * Hotkeys bytes go STRAIGHT to [SessionViewModel.sendBytes] — from the
 * #2612 bottom bar and the floating palette alike, they are not composed
 * messages and have no business in the composer's draft/history/attachment
 * machinery. They are ordinary session input, though: when the link is down
 * they take the same held-input path as keystrokes (#2578), so a key tapped
 * at the "Reconnecting" banner is not lost.
 */
@Composable
fun SessionRoute(
    hostId: Long,
    sessionName: String,
    sessionId: String? = null,
    onBack: () -> Unit,
    onOpenUsage: () -> Unit,
    onOpenFiles: () -> Unit = {},
    onOpenPorts: () -> Unit = {},
    onOpenSession: (SessionRow) -> Unit = {},
    onOpenNewSession: () -> Unit = {},
    workspacePath: String? = null,
    modifier: Modifier = Modifier,
    viewModel: SessionViewModel = hiltViewModel(),
    composerViewModel: ComposerViewModel = hiltViewModel(),
    usageGlanceViewModel: UsageGlanceViewModel = hiltViewModel(),
    sessionSwitcherViewModel: SessionSwitcherViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val appSettings = LocalAppSettings.current
    val composerState by composerViewModel.state.collectAsState()
    val usagePillState by usageGlanceViewModel.state.collectAsState()
    val sessionSwitcherState by sessionSwitcherViewModel.state.collectAsState()
    val leaveAfterStop by viewModel.leaveAfterStop.collectAsState()
    val stopFailure by viewModel.stopFailure.collectAsState()

    // Issue #2572: the id is the attach identity when the route carries one;
    // the name is presentation. A rename re-keys nothing on this screen.
    LaunchedEffect(hostId, sessionName, sessionId) {
        viewModel.open(hostId, sessionName, sessionId)
    }
    LaunchedEffect(appSettings.reconnectWhenReturn) {
        viewModel.setAutomaticReconnectEnabled(appSettings.reconnectWhenReturn)
    }
    // Issue #2579: the pill on THIS screen is about THIS session's agent, so
    // the refresh names the session. The tree's Usage affordance keeps calling
    // the no-argument overload and keeps the cross-provider meaning.
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        usageGlanceViewModel.refresh(hostId = hostId, sessionName = sessionName, sessionId = sessionId)
        sessionSwitcherViewModel.refresh()
    }

    LaunchedEffect(leaveAfterStop) {
        if (!leaveAfterStop) return@LaunchedEffect
        viewModel.consumeLeaveAfterStop()
        onBack()
    }

    val sink = remember(viewModel) {
        object : SessionSink {
            override val isLive: Boolean get() = viewModel.uiState.value is SessionUiState.Live
            override fun sendBytes(bytes: ByteArray) = viewModel.sendBytes(bytes)
            override val sendFailures = viewModel.sendFailures
        }
    }
    LaunchedEffect(hostId, sessionName, sessionId, sink) {
        composerViewModel.bind(hostId, sessionId, sessionName, sink)
    }

    LifecycleEventEffect(Lifecycle.Event.ON_START) { composerViewModel.onForegroundResume() }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> -> composerViewModel.attach(uris) }

    SessionScreen(
        state = state,
        composerState = composerState,
        sessionName = sessionName,
        sessionId = sessionId,
        onBack = onBack,
        usagePillState = usagePillState,
        onOpenUsage = onOpenUsage,
        onOpenFiles = onOpenFiles,
        onOpenPorts = onOpenPorts,
        onOpenSession = onOpenSession,
        onOpenNewSession = onOpenNewSession,
        sessionSwitcherState = sessionSwitcherState,
        workspacePath = workspacePath,
        showCommonKeys = appSettings.showCommonKeys,
        onResized = viewModel::onResized,
        onRetry = viewModel::retryNow,
        onStopSession = viewModel::stopSession,
        stopFailure = stopFailure,
        onHotkeySend = viewModel::sendBytes,
        onDraftChange = composerViewModel::onDraftChange,
        onSend = { composerViewModel.send() },
        onInsert = composerViewModel::insert,
        onAttach = { picker.launch(arrayOf("*/*")) },
        onMicTap = composerViewModel::onMicTap,
        onCancelRecording = composerViewModel::cancelRecording,
        onToggleHistory = composerViewModel::toggleHistory,
        onTogglePreview = composerViewModel::togglePreview,
        onRemoveAttachment = composerViewModel::removeAttachment,
        onDismissNotice = composerViewModel::dismissNotice,
        onDiscardDraft = composerViewModel::discard,
        onUseHistoryEntry = composerViewModel::useHistoryEntry,
        onPermissionDenied = composerViewModel::surfacePermissionDenied,
        modifier = modifier,
    )
}

/**
 * One attached session: a title bar, a full-bleed terminal, the #2612 bottom
 * terminal bar, and floating overlays. The Prompt Composer opens as a floating
 * sheet; the extended hotkeys live in a draggable floating palette over the
 * terminal (#2612, replacing #2521's modal sheet); neither sits in this
 * column.
 *
 * ## The keyboard overlays the terminal; it must not resize it (#887/#2533)
 *
 * The session column is a plain [Modifier.fillMaxSize] — no `imePadding`, no
 * pan. The window is `SOFT_INPUT_ADJUST_NOTHING` (see [com.pocketshell.next.MainActivity]),
 * so the OS neither resizes nor pans when the keyboard shows. The grid stays
 * put; [onResized] does not fire; aplexer does not reflow. The composer is a
 * [androidx.compose.material3.ModalBottomSheet] with its own IME policy, so
 * Send/mic stay above the keyboard independently of this column. The hotkeys
 * palette floats inside the terminal slot and re-clamps to whatever viewport
 * it is given, so it can never strand off-screen.
 *
 * @param onResized the terminal's size in character cells.
 * @param onHotkeySend raw bytes for the remote from the bottom bar and the
 *   floating palette.
 * @param initiallyShowComposer test seam: start with the composer sheet open.
 * @param initiallyShowHotkeys test seam: start with the floating palette open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(
    state: SessionUiState,
    composerState: ComposerUiState,
    sessionName: String,
    sessionId: String? = null,
    onBack: () -> Unit,
    onOpenSession: (SessionRow) -> Unit = {},
    onOpenNewSession: () -> Unit = {},
    onOpenFiles: () -> Unit = {},
    onOpenPorts: () -> Unit = {},
    onResized: (cols: Int, rows: Int) -> Unit,
    usagePillState: UsageGlancePillState? = null,
    onOpenUsage: () -> Unit = {},
    onRetry: () -> Unit,
    onStopSession: () -> Unit = {},
    stopFailure: String? = null,
    onHotkeySend: (ByteArray) -> Unit,
    onDraftChange: (String) -> Unit,
    /**
     * Production Send. Returns true when the message left (close the sheet);
     * false when the draft was kept (undelivered — leave the sheet open).
     */
    onSend: () -> Boolean,
    onInsert: () -> Unit,
    onAttach: () -> Unit,
    onMicTap: () -> Unit,
    onCancelRecording: () -> Unit,
    onToggleHistory: () -> Unit,
    onTogglePreview: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onDismissNotice: () -> Unit,
    onDiscardDraft: () -> Unit,
    onUseHistoryEntry: (SentMessage) -> Unit,
    onPermissionDenied: () -> Unit = {},
    sessionSwitcherState: SessionSwitcherUiState = SessionSwitcherUiState(),
    sessionLabel: String = readableSessionName(sessionName),
    workspacePath: String? = null,
    showCommonKeys: Boolean = true,
    modifier: Modifier = Modifier,
    cellMetrics: TerminalCellMetrics = rememberTerminalCellMetrics(),
    initiallyShowComposer: Boolean = false,
    initiallyShowHotkeys: Boolean = false,
    /**
     * Test seam: Robolectric drops clicks on a `ModalBottomSheet`. Host-JVM
     * tests that drive Insert/Send pass false so [PromptComposerContent] is
     * composed in-place; production always uses the floating sheet.
     */
    embedComposerInWindow: Boolean = true,
) {
    var composerOpen by remember { mutableStateOf(initiallyShowComposer) }
    var hotkeysOpen by remember { mutableStateOf(initiallyShowHotkeys) }
    var terminalActionsOpen by remember { mutableStateOf(false) }
    var sessionSwitcherOpen by remember { mutableStateOf(false) }
    var pendingStop by remember { mutableStateOf(false) }
    var copyTerminalSelection by remember { mutableStateOf<(() -> Boolean)?>(null) }
    val sessionEnded = (state as? SessionUiState.Failed)?.message?.looksLikeEndedSession() == true
    val deliveryReviewVisible = composerState.deliveryUncertain
    var deliveryReviewDraft by remember { mutableStateOf(composerState.draft) }
    LaunchedEffect(deliveryReviewVisible, composerState.draft) {
        if (deliveryReviewVisible) deliveryReviewDraft = composerState.draft
    }
    val handleBack: () -> Unit = {
        if (deliveryReviewVisible) {
            onDraftChange(deliveryReviewDraft)
            onDismissNotice()
        }
        onBack()
    }
    // Issue #2572: this screen's row is resolved by the stable id when the
    // route carries one — ID-ONLY, no name fallback, because a listing that
    // lacks the id means the session is gone, and a NEW session that took the
    // old name is not this one. The name path is only for routes without an
    // id, the pre-#2572 shape.
    val currentSession = when {
        sessionId != null -> sessionSwitcherState.sessions.firstOrNull { it.id == sessionId }
        else -> sessionSwitcherState.sessions.firstOrNull { it.name == sessionName }
    }
    val selectedSessionAgent = currentSession?.agent
    val availableSlashCommands = remember(selectedSessionAgent) {
        SlashCommandAutocomplete.commandsFor(selectedSessionAgent)
    }
    val visibleWorkspacePath = workspacePath ?: currentSession?.workspace
    val terminalTitle = workspaceLabelForTerminal(visibleWorkspacePath).ifBlank { sessionLabel }
    // #2717 T2: the status dot carries the transport state; the subtitle line is
    // reserved for transitional words ("Connecting…", "Reconnecting…", "Offline").
    // In the steady (Live) state no words show — TalkBack still hears the full
    // sentence ("devbox · Connected") through the dot's contentDescription.
    val transport = terminalHeaderTransport(state)
    val transportSentence = headerSentence(sessionSwitcherState.hostLabel, transport.words ?: "Connected")
    // Issue #2635 D3 / `ux-rules.md` rule 6: a strip with one item is vertical
    // chrome without a choice. With exactly one live session the strip is not
    // rendered and its "+" moves into the header, so the affordance stays one
    // tap away and the terminal gets the row back. The non-live states keep
    // the strip: "attach or start something else" is the whole point of the
    // screen when there is nothing to watch.
    val showTabStrip = !sessionEnded &&
        !deliveryReviewVisible &&
        (sessionSwitcherState.sessions.size > 1 || state !is SessionUiState.Live)

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(SESSION_SCREEN_TAG),
    ) {
        ScreenHeader(
            title = when {
                deliveryReviewVisible -> "Review before resending"
                sessionEnded -> "Session ended"
                else -> terminalTitle
            },
            status = if (deliveryReviewVisible || sessionEnded) null else transport.status,
            // Transitional words are already visible on the subtitle line, so the
            // dot stays decorative there (no double announcement); the steady
            // state has no visible words and rides on the dot's description.
            statusDescription = if (deliveryReviewVisible || sessionEnded || transport.words != null) {
                null
            } else {
                transportSentence
            },
            subtitle = when {
                deliveryReviewVisible -> null
                sessionEnded -> sessionLabel
                else -> transport.words?.let { words ->
                    headerSentence(sessionSwitcherState.hostLabel, words)
                }
            },
            // Issue #2635 D3: one line, ellipsised. The two-line allowance
            // bought a wrapped header on long workspace names and cost every
            // session a row of terminal; the full name stays in the switcher
            // sheet.
            titleMaxLines = 1,
            subtitleMaxLines = 2,
            titleTestTag = SESSION_TITLE_TAG,
            // Issue #2635 D3: the dot carries a stable test tag for the chrome
            // tests; colour and words come from the #2717 T2 transport above.
            statusTestTag = SESSION_STATUS_DOT_TAG,
            onBack = handleBack,
            backTestTag = SESSION_BACK_TAG,
            trailing = if (deliveryReviewVisible) {
                null
            } else {
                {
                    // Issue #2635 D3: with exactly one live session the strip
                    // is not rendered, so its "+" moves here — the create
                    // affordance never disappears, it just stops costing a
                    // whole row. Same label as the strip's "+".
                    if (!showTabStrip && !sessionEnded) {
                        HeaderIconAction(
                            icon = PocketShellIcons.Plus,
                            contentDescription = SESSION_TAB_NEW_DESCRIPTION,
                            onClick = onOpenNewSession,
                            testTag = SESSION_HEADER_NEW_TAG,
                        )
                    }
                    if (usagePillState != null) {
                        // Issue #2635: the glance pill answers to the same tag
                        // as the fallback "Usage" button below — one control,
                        // one tag, whichever skin is up.
                        UsageGlancePill(
                            state = usagePillState,
                            onClick = onOpenUsage,
                            testTag = SESSION_USAGE_TAG,
                        )
                    } else {
                        PocketShellButton(
                            text = "Usage",
                            onClick = onOpenUsage,
                            variant = ButtonVariant.Text,
                            compact = true,
                            modifier = Modifier.testTag(SESSION_USAGE_TAG),
                        )
                    }
                    KebabTrigger(
                        onClick = { terminalActionsOpen = true },
                        contentDescription = "Terminal actions",
                        triggerTestTag = SESSION_HEADER_KEBAB_TAG,
                    )
                }
            },
        )

        if (showTabStrip) {
            // Issue #2632: the sibling sessions in this workspace are ON
            // screen as tabs, so switching is one tap. The sheet is still
            // reachable through the overflow for the things a tab cannot
            // carry (status text, stop).
            SessionTabStrip(
                tabs = sessionTabs(
                    sessions = sessionSwitcherState.sessions,
                    currentSessionName = sessionName,
                    currentSessionLabel = sessionLabel,
                ),
                selectedId = sessionName,
                onSelect = { id ->
                    if (id != sessionName) {
                        sessionSwitcherState.sessions
                            .firstOrNull { it.name == id }
                            ?.let(onOpenSession)
                    }
                },
                onNewTab = onOpenNewSession,
                onOverflow = { sessionSwitcherOpen = true },
            )
        }

        if (deliveryReviewVisible) {
            DeliveryUncertainReview(
                draft = deliveryReviewDraft,
                onDraftChange = { deliveryReviewDraft = it },
                onReconnectAndInspect = {
                    onDraftChange(deliveryReviewDraft)
                    onDismissNotice()
                    onRetry()
                },
                modifier = Modifier.weight(1f),
            )
        } else {
        stopFailure?.let { message ->
            Banner(
                text = message,
                role = BannerRole.Error,
                maxLines = 3,
                modifier = Modifier
                    .padding(horizontal = PocketShellSpacing.md)
                    .padding(bottom = PocketShellSpacing.sm)
                    .testTag(SESSION_STOP_FAILURE_TAG),
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(PocketShellColors.Background)
                .onSizeChanged { size ->
                    // Only while no terminal view exists to own the number.
                    // From the first view frame on, the view owns it — it has
                    // the renderer, so it has the authoritative metrics — and
                    // a second reporter would fight it on every layout pass.
                    // Reconnecting hosts that same view (#2496), so the
                    // estimate stays off there too: a banner or keyboard
                    // layout change mid-reconnect would otherwise publish a
                    // stale local guess, and the reattached PTY would open a
                    // few rows off until the first Live frame corrected it.
                    if (state !is SessionUiState.Live && state !is SessionUiState.Reconnecting) {
                        terminalCells(size.width, size.height, cellMetrics)?.let { cells ->
                            onResized(cells.cols, cells.rows)
                        }
                    }
                },
        ) {
            when (state) {
                SessionUiState.Connecting -> EmptyState(
                    title = "Attaching…",
                    description = "Opening a terminal on \"$sessionLabel\".",
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(SESSION_CONNECTING_TAG),
                )

                is SessionUiState.Live -> TerminalHostView(
                    session = state.terminal,
                    onResized = onResized,
                    onViewReady = { view ->
                        copyTerminalSelection = view?.let { terminal ->
                            { terminal.copySelectionToClipboard() }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                is SessionUiState.Reconnecting -> Column(modifier = Modifier.fillMaxSize()) {
                    Banner(
                        text = reconnectingMessage(state),
                        role = BannerRole.Warning,
                        maxLines = 3,
                        trailingContent = if (sessionEnded) {
                            null
                        } else {
                            {
                                PocketShellButton(
                                    text = "Retry",
                                    onClick = onRetry,
                                    variant = ButtonVariant.Text,
                                    compact = true,
                                    modifier = Modifier.testTag(SESSION_RETRY_TAG),
                                )
                            }
                        },
                        modifier = Modifier
                            .padding(horizontal = PocketShellSpacing.md)
                            .padding(bottom = PocketShellSpacing.sm)
                            .testTag(SESSION_RECONNECT_BANNER_TAG),
                    )
                    Terminal(
                        session = state.terminal,
                        onResized = onResized,
                        onViewReady = { view ->
                            copyTerminalSelection = view?.let { terminal ->
                                { terminal.copySelectionToClipboard() }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }

                is SessionUiState.Failed -> if (sessionEnded) {
                    SessionEndedBody(
                        sessionName = sessionName,
                        sessionLabel = sessionLabel,
                        exitCode = endedExitCode(state.message),
                        state = sessionSwitcherState,
                        onOpenSession = onOpenSession,
                        onOpenNewSession = onOpenNewSession,
                        onBack = onBack,
                        modifier = Modifier.testTag(SESSION_ENDED_TAG),
                    )
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Banner(
                            text = state.message,
                            role = BannerRole.Error,
                            maxLines = 4,
                            trailingContent = {
                                PocketShellButton(
                                    text = "Retry",
                                    onClick = onRetry,
                                    variant = ButtonVariant.Text,
                                    compact = true,
                                    modifier = Modifier.testTag(SESSION_RETRY_TAG),
                                )
                            },
                            modifier = Modifier
                                .padding(horizontal = PocketShellSpacing.md)
                                .padding(bottom = PocketShellSpacing.sm)
                                .testTag(SESSION_ERROR_BANNER_TAG),
                        )
                        EmptyState(
                            title = "Not attached",
                            description = "Tap Retry, or go back to the session list to pick another session.",
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // #2612: the hotkeys palette floats INSIDE the terminal slot, so
            // opening it never resizes the cell grid, never dims the
            // terminal, and never blocks touches that miss the card. It is
            // driven by the bottom bar's More keys affordance (and by the
            // composer sheet's hotkeys entry point).
            if (hotkeysOpen) {
                TerminalHotkeysPaletteOverlay(
                    mainSections = HOTKEY_PALETTE_MAIN_SECTIONS,
                    ctrlSections = HOTKEY_CTRL_SECTIONS,
                    onKey = { binding: KeyBinding ->
                        keyBarBytes(binding.label)?.let(onHotkeySend)
                    },
                    onLongKey = { binding: KeyBinding ->
                        when (binding.label) {
                            "^C" -> keyBarBytes(KEY_LABEL_INTERRUPT_X2)?.let(onHotkeySend)
                            "^D" -> keyBarBytes(KEY_LABEL_EOF_X2)?.let(onHotkeySend)
                        }
                    },
                    onClose = { hotkeysOpen = false },
                    enabled = state is SessionUiState.Live,
                    longPressActions = HOTKEY_LONG_PRESS_ACTIONS,
                )
            }
        }

        // #2612: the persistent bottom terminal bar. ↑ / ↓ / Enter reach the
        // PTY in one tap with no panel, the launcher opens the composer, and
        // More keys toggles the floating palette above. The bar is docked
        // (stable, never draggable); only the palette floats.
        if (!sessionEnded) {
            SessionTerminalBar(
                onKey = { navKey: SessionNavKey -> onHotkeySend(navKeyBytes(navKey)) },
                onOpenComposer = {
                    hotkeysOpen = false
                    composerOpen = true
                },
                onMoreKeys = {
                    composerOpen = false
                    hotkeysOpen = !hotkeysOpen
                },
                keysEnabled = state is SessionUiState.Live,
                showKeys = showCommonKeys && state !is SessionUiState.Failed,
            )
        }
        }
    }

    if (composerOpen && !sessionEnded && !deliveryReviewVisible) {
        val sendAndMaybeDismiss: () -> Unit = {
            if (!sessionEnded && onSend()) composerOpen = false
        }
        val dismiss = {
            onCancelRecording()
            composerOpen = false
        }
        if (embedComposerInWindow) {
            PromptComposerSheet(
                state = composerState,
                targetLabel = sessionLabel,
                onDismiss = dismiss,
                onDraftChange = onDraftChange,
                onSend = sendAndMaybeDismiss,
                onInsert = onInsert,
                onAttach = onAttach,
                onMicTap = onMicTap,
                onCancelRecording = onCancelRecording,
                onToggleHistory = {
                    composerOpen = false
                    onToggleHistory()
                },
                onTogglePreview = onTogglePreview,
                onRemoveAttachment = onRemoveAttachment,
                onDismissNotice = onDismissNotice,
                onDiscard = onDiscardDraft,
                onPermissionDenied = onPermissionDenied,
                deliveryEnabled = state is SessionUiState.Live,
                deliveryDisabledMessage = reconnectingComposerMessage(state),
                availableSlashCommands = availableSlashCommands,
            )
        } else {
            PromptComposerContent(
                state = composerState,
                targetLabel = sessionLabel,
                onClose = dismiss,
                onDraftChange = onDraftChange,
                onSend = sendAndMaybeDismiss,
                onInsert = onInsert,
                onAttach = onAttach,
                onMicTap = onMicTap,
                onCancelRecording = onCancelRecording,
                onToggleHistory = {
                    composerOpen = false
                    onToggleHistory()
                },
                onTogglePreview = onTogglePreview,
                onRemoveAttachment = onRemoveAttachment,
                onDismissNotice = onDismissNotice,
                onDiscard = onDiscardDraft,
                deliveryEnabled = state is SessionUiState.Live,
                deliveryDisabledMessage = reconnectingComposerMessage(state),
                availableSlashCommands = availableSlashCommands,
            )
        }
    }

    if (terminalActionsOpen) {
        TerminalActionsSheet(
            onSessions = {
                terminalActionsOpen = false
                sessionSwitcherOpen = true
            },
            onBrowseFiles = {
                terminalActionsOpen = false
                onOpenFiles()
            },
            onPorts = {
                terminalActionsOpen = false
                onOpenPorts()
            },
            onOpenUsage = {
                terminalActionsOpen = false
                onOpenUsage()
            },
            onCopySelection = {
                copyTerminalSelection?.invoke()
                terminalActionsOpen = false
            },
            onDetach = {
                terminalActionsOpen = false
                onBack()
            },
            onEndSession = {
                terminalActionsOpen = false
                pendingStop = true
            },
            onDismiss = { terminalActionsOpen = false },
        )
    }

    if (sessionSwitcherOpen) {
        SessionSwitcherSheet(
            currentSessionName = sessionName,
            currentSessionId = sessionId,
            state = sessionSwitcherState,
            onNewSession = {
                sessionSwitcherOpen = false
                onOpenNewSession()
            },
            onOpenSession = { session ->
                sessionSwitcherOpen = false
                onOpenSession(session)
            },
            onDismiss = { sessionSwitcherOpen = false },
        )
    }

    if (composerState.historyOpen) {
        MessageHistorySheet(
            messages = composerState.history,
            onPick = { message ->
                onUseHistoryEntry(message)
                composerOpen = true
            },
            onDismiss = onToggleHistory,
        )
    }

    if (pendingStop) {
        ConfirmDialog(
            title = STOP_SESSION_TITLE,
            message = stopSessionMessage(
                name = sessionLabel,
                workspace = currentSession?.workspace,
                host = "this host",
            ),
            confirmLabel = STOP_SESSION_CONFIRM_LABEL,
            dismissLabel = "Keep running",
            destructive = true,
            onConfirm = {
                pendingStop = false
                onStopSession()
            },
            onDismiss = { pendingStop = false },
            confirmTestTag = STOP_SESSION_CONFIRM_TAG,
            dismissTestTag = STOP_SESSION_CANCEL_TAG,
            titleTestTag = STOP_SESSION_TITLE_TAG,
            messageTestTag = STOP_SESSION_MESSAGE_TAG,
        )
    }
}

@Composable
private fun SessionEndedBody(
    sessionName: String,
    sessionLabel: String = readableSessionName(sessionName),
    exitCode: Int?,
    state: SessionSwitcherUiState,
    onOpenSession: (SessionRow) -> Unit,
    onOpenNewSession: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val otherSessions = state.sessions.filter { it.name != sessionName }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = PocketShellSpacing.lg, vertical = PocketShellSpacing.md),
    ) {
        Text(
            text = "Terminal in $sessionLabel has ended.",
            color = PocketShellColors.Text,
            style = PocketShellType.body,
        )
        exitCode?.let { code ->
            Text(
                text = "Exit code: $code",
                color = PocketShellColors.TextSecondary,
                style = PocketShellType.metadata,
                modifier = Modifier.padding(top = PocketShellSpacing.xs),
            )
        }
        Text(
            text = "The workspace is still here. Open another session or start a new one.",
            color = PocketShellColors.TextSecondary,
            style = PocketShellType.body,
            modifier = Modifier.padding(top = PocketShellSpacing.xs),
        )
        SectionHeader(
            label = "Other sessions",
            count = otherSessions.size.takeIf { it > 0 },
            modifier = Modifier.padding(top = PocketShellSpacing.lg),
        )
        when {
            state.loading -> Text(
                text = "Reading sessions…",
                color = PocketShellColors.TextSecondary,
                style = PocketShellType.body,
                modifier = Modifier.padding(vertical = PocketShellSpacing.md),
            )
            state.failure != null && otherSessions.isEmpty() -> Text(
                text = "Sessions unavailable: ${state.failure}",
                color = PocketShellColors.TextSecondary,
                style = PocketShellType.body,
                modifier = Modifier.padding(vertical = PocketShellSpacing.md),
            )
            otherSessions.isEmpty() -> Text(
                text = "No other sessions are running.",
                color = PocketShellColors.TextSecondary,
                style = PocketShellType.body,
                modifier = Modifier.padding(vertical = PocketShellSpacing.md),
            )
            else -> LazyColumn(modifier = Modifier.weight(1f)) {
                items(otherSessions, key = { "${it.workspace}:${it.name}" }) { session ->
                    ListRow(
                        title = session.name,
                        subtitle = endedSessionSubtitle(session),
                        onClick = { onOpenSession(session) },
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = PocketShellSpacing.md),
            horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
        ) {
            PocketShellButton(
                text = "New session",
                onClick = onOpenNewSession,
                variant = ButtonVariant.Primary,
                modifier = Modifier.weight(1f),
            )
            PocketShellButton(
                text = "Back to workspace",
                onClick = onBack,
                variant = ButtonVariant.Text,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun endedSessionSubtitle(session: SessionRow): String = listOfNotNull(
    session.agent?.replaceFirstChar { it.uppercase() },
    session.profile,
).ifEmpty { listOf("Terminal · Running") }.joinToString(" · ")

@Composable
private fun Terminal(
    session: TerminalSession,
    onResized: (cols: Int, rows: Int) -> Unit,
    onViewReady: (com.termux.view.TerminalView?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background),
    ) {
        TerminalHostView(
            session = session,
            onResized = onResized,
            onViewReady = onViewReady,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun reconnectingMessage(state: SessionUiState.Reconnecting): String {
    val attempt = state.attempt + 1
    val countdown = if (state.retryInMs <= 0) {
        "retrying now"
    } else {
        val seconds = (state.retryInMs + 999) / 1000
        "retrying in ${seconds}s"
    }
    return "Connection lost\nLast output is shown. Reconnecting… attempt $attempt · $countdown"
}

private fun reconnectingComposerMessage(state: SessionUiState): String? =
    if (state is SessionUiState.Reconnecting) {
        "Connection lost. Your draft stays local while PocketShell reconnects."
    } else {
        null
    }

private fun workspaceLabelForTerminal(path: String?): String =
    path?.trimEnd('/')?.substringAfterLast('/')?.ifBlank { path }.orEmpty()

/**
 * The session's transport state for the header, per the #2717 T2 vocabulary:
 * the status dot carries the steady state with no words; the subtitle line is
 * reserved for the transitional words ("Connecting…", "Reconnecting…",
 * "Offline"). In the steady state [HeaderTransport.words] is null and the
 * caller passes the full sentence as the dot's contentDescription instead.
 */
private data class HeaderTransport(val status: ConnectionStatus, val words: String?)

private fun terminalHeaderTransport(state: SessionUiState): HeaderTransport = when (state) {
    SessionUiState.Connecting -> HeaderTransport(ConnectionStatus.Connecting, "Connecting…")
    is SessionUiState.Live -> HeaderTransport(ConnectionStatus.Connected, null)
    is SessionUiState.Reconnecting -> HeaderTransport(ConnectionStatus.Connecting, "Reconnecting…")
    is SessionUiState.Failed -> HeaderTransport(ConnectionStatus.Error, "Offline")
}

/** `devbox · Reconnecting…` — the host label joined to the state words. */
private fun headerSentence(hostLabel: String, words: String): String =
    listOfNotNull(
        hostLabel.takeIf { it.isNotBlank() },
        words,
    ).joinToString(" · ").ifBlank { words }


/**
 * The tabs for the session strip (issue #2632).
 *
 * The open session is ALWAYS a tab, even while the listing is still loading or
 * has failed: a strip that appears a second after the terminal, or vanishes
 * when `sessions list` errors, is worse chrome than no strip at all. When the
 * listing has not (yet) produced the open session, it is synthesised from the
 * route's own name so the tab bar is stable from the first frame.
 *
 * Labels come from [sessionDisplayNames] — the same de-duplicating projection
 * the switcher sheet and the workspace rows use — so a tab and its sheet row
 * cannot end up calling the same session two different things.
 */
internal fun sessionTabs(
    sessions: List<SessionRow>,
    currentSessionName: String,
    currentSessionLabel: String,
): List<SessionTab> {
    val displayNames = sessionDisplayNames(sessions)
    val listed = sessions.map { session ->
        SessionTab(
            id = session.name,
            label = displayNames[session.name] ?: readableSessionName(session.name),
            state = sessionTabState(session),
        )
    }
    if (listed.any { it.id == currentSessionName }) return listed
    return listOf(
        SessionTab(id = currentSessionName, label = currentSessionLabel),
    ) + listed
}

/**
 * The tab's dot, straight off the host-reported agent state.
 *
 * A host that reports no state at all is [SessionTabState.Idle], not a missing
 * dot: every tab keeps the same width vocabulary, so the strip does not reflow
 * when one session's state arrives before another's.
 */
internal fun sessionTabState(session: SessionRow): SessionTabState =
    when (session.agentState) {
        AgentState.WORKING -> SessionTabState.Working
        AgentState.WAITING -> SessionTabState.NeedsInput
        AgentState.IDLE, null -> SessionTabState.Idle
    }

private fun String.looksLikeEndedSession(): Boolean =
    contains(" ended.") || contains(" ended (exit ") || contains(" ended:")

private fun endedExitCode(message: String): Int? =
    Regex("ended \\(exit (-?\\d+)\\)").find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
