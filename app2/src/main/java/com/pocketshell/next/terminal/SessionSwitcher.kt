package com.pocketshell.next.terminal

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.core.hostapi.SessionRow
import com.pocketshell.core.transport.ConnectResult
import com.pocketshell.next.connect.ConnectionsRegistry
import com.pocketshell.next.hostcli.HostCliClientFactory
import com.pocketshell.next.nav.Destination
import com.pocketshell.next.tree.STOP_SESSION_ITEM_LABEL
import com.pocketshell.next.tree.STOP_SESSION_ITEM_TAG
import com.pocketshell.next.workspaces.canonicalRemotePath
import com.pocketshell.next.workspaces.sessionDisplayNames
import com.pocketshell.next.workspaces.sessionKindLabel
import com.pocketshell.next.workspaces.sessionStatusLabel
import com.pocketshell.next.workspaces.workspaceLabel
import com.pocketshell.uikit.components.EmptyState
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.components.SheetHeader
import com.pocketshell.uikit.components.SessionTabState
import com.pocketshell.uikit.icons.PocketShellIcons
import com.pocketshell.uikit.theme.LocalPocketShellSemantic
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellSpacing
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val SESSION_SWITCHER_SHEET_TAG: String = "session-switcher-sheet"
const val SESSION_SWITCHER_LOADING_TAG: String = "session-switcher-loading"
const val SESSION_SWITCHER_EMPTY_TAG: String = "session-switcher-empty"
const val SESSION_SWITCHER_ERROR_TAG: String = "session-switcher-error"
const val SESSION_SWITCHER_NEW_TAG: String = "session-switcher-new-session"

/**
 * Issue #2721 (N2): the header of the sheet's other-workspaces section — the
 * host's remaining workspaces, so any session on the host is two taps from a
 * terminal.
 */
const val SESSION_SWITCHER_OTHER_WORKSPACES_TAG: String = "session-switcher-other-workspaces"

fun sessionSwitcherRowTag(name: String): String = "session-switcher-row-$name"

/** Per-row tag for one other workspace, keyed by its canonical path. */
fun sessionSwitcherWorkspaceTag(path: String): String = "session-switcher-workspace-$path"

/**
 * One OTHER workspace on this host, as the switcher sheet lists it
 * (issue #2721 N2): name · dot · count.
 *
 * [entry] is the session a tap opens — the same entry-session ladder the
 * workspace rows use (remembered, else most recently active, else first), so
 * tapping a workspace here lands IN a terminal rather than on a list.
 */
data class OtherWorkspaceEntry(
    val path: String,
    val label: String,
    val sessionCount: Int,
    /** The busiest agent state in the workspace — the same dot vocabulary as the tab strip. */
    val state: SessionTabState?,
    val entry: SessionRow,
)

data class SessionSwitcherUiState(
    val loading: Boolean = false,
    val sessions: List<SessionRow> = emptyList(),
    /**
     * The host's other workspaces — everything in the same listing whose
     * canonical workspace is not the one on screen (issue #2721 N2). Derived
     * from the listing this ViewModel already fetches; no second round trip.
     */
    val otherWorkspaces: List<OtherWorkspaceEntry> = emptyList(),
    val failure: String? = null,
    val hostLabel: String = "",
    val workspacePath: String? = null,
)

/** Reads the same host-owned session list as the workspace screen for the terminal switcher. */
@HiltViewModel
class SessionSwitcherViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val registry: ConnectionsRegistry,
    private val clients: HostCliClientFactory,
    private val hostDao: com.pocketshell.core.storage.dao.HostDao,
    private val lastSessionStore: LastSessionStore,
) : ViewModel() {

    private val hostId: Long = requireNotNull(savedStateHandle.get<Long>(Destination.ARG_HOST_ID))
    private val workspacePath: String? = savedStateHandle
        .get<String>(Destination.ARG_WORKSPACE_PATH)
        ?.let(::canonicalRemotePath)
    private val sessionId: String? = savedStateHandle.get<String>(Destination.ARG_SESSION_ID)
    private val sessionName: String? = savedStateHandle.get<String>(Destination.ARG_SESSION_NAME)

    private val _state = MutableStateFlow(SessionSwitcherUiState())
    val state: StateFlow<SessionSwitcherUiState> = _state.asStateFlow()
    private var job: Job? = null

    fun refresh() {
        if (job?.isActive == true) return
        _state.update { it.copy(loading = true, failure = null) }
        job = viewModelScope.launch {
            val hostLabel = hostDao.getById(hostId)?.let { host ->
                host.name.ifBlank { host.hostname }
            }.orEmpty()
            when (val result = registry.getOrConnect(hostId)) {
                is ConnectResult.Connected -> clients.create(result.connection).listSessions().fold(
                    onSuccess = { listing ->
                        val visible = workspacePath?.let { path ->
                            listing.sessions.filter { canonicalRemotePath(it.workspace) == path }
                        } ?: listing.sessions
                        _state.value = SessionSwitcherUiState(
                            sessions = visible,
                            otherWorkspaces = otherWorkspaces(listing.sessions),
                            hostLabel = hostLabel,
                            workspacePath = workspacePath,
                            failure = listing.errors.takeIf { it.isNotEmpty() }
                                ?.joinToString("; ") { it.message },
                        )
                    },
                    onFailure = { error ->
                        _state.value = SessionSwitcherUiState(
                            hostLabel = hostLabel,
                            workspacePath = workspacePath,
                            failure = error.message ?: "Could not list sessions.",
                        )
                    },
                )
                is ConnectResult.NeedsTrust -> _state.value = SessionSwitcherUiState(
                    hostLabel = hostLabel,
                    workspacePath = workspacePath,
                    failure = "Confirm this host's key from the host list first.",
                )
                is ConnectResult.Failed -> _state.value = SessionSwitcherUiState(
                    hostLabel = hostLabel,
                    workspacePath = workspacePath,
                    failure = result.message,
                )
            }
        }
    }

    /**
     * Issue #2721 (N2): groups the SAME listing the sheet's primary section
     * reads by workspace, drops the workspace on screen, and resolves each
     * remaining group's entry session with the ladder the workspace rows use
     * — so "other workspaces" rows open a terminal in one tap.
     */
    private fun otherWorkspaces(sessions: List<SessionRow>): List<OtherWorkspaceEntry> {
        val current = workspacePath
            // A route without a workspace argument (a root-level session) still
            // must not list the workspace it is actually sitting in.
            ?: sessions.firstOrNull { it.id != null && it.id == sessionId }
                ?.workspace?.let(::canonicalRemotePath)
            ?: sessions.firstOrNull { it.name == sessionName }
                ?.workspace?.let(::canonicalRemotePath)
        return sessions
            .mapNotNull { session ->
                session.workspace?.takeIf { it.isNotBlank() }?.let { workspace ->
                    (canonicalRemotePath(workspace) ?: workspace) to session
                }
            }
            .groupBy({ it.first }, { it.second })
            .filterKeys { path -> path != current }
            .map { (path, group) ->
                val entry = resolveWorkspaceEntrySession(
                    rememberedName = lastSessionStore.getForWorkspace(hostId, path),
                    sessions = group,
                ) ?: group.first()
                OtherWorkspaceEntry(
                    path = path,
                    label = workspaceLabel(path),
                    sessionCount = group.size,
                    // Busiest wins: Working beats NeedsInput beats Idle, and
                    // the enum orders them that way.
                    state = group.map(::sessionTabState).minByOrNull { it.ordinal },
                    entry = entry,
                )
            }
            .sortedBy { it.label.lowercase() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionSwitcherSheet(
    currentSessionName: String,
    state: SessionSwitcherUiState,
    currentSessionId: String? = null,
    onNewSession: () -> Unit,
    onOpenSession: (SessionRow) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = PocketShellColors.Surface,
        contentColor = PocketShellColors.Text,
        shape = PocketShellShapes.large,
    ) {
        SessionSwitcherSheetContent(
            currentSessionName = currentSessionName,
            currentSessionId = currentSessionId,
            state = state,
            onNewSession = onNewSession,
            onOpenSession = onOpenSession,
            onDismiss = onDismiss,
        )
    }
}

/**
 * The switcher body, separate from the modal container (the same seam
 * [com.pocketshell.next.workspaces.RootActionsSheetContent] uses): Robolectric
 * drops clicks on a `ModalBottomSheet`, so the JVM tests and renders compose
 * this directly.
 */
@Composable
internal fun SessionSwitcherSheetContent(
    currentSessionName: String,
    state: SessionSwitcherUiState,
    currentSessionId: String? = null,
    onNewSession: () -> Unit = {},
    onOpenSession: (SessionRow) -> Unit = {},
    onDismiss: (() -> Unit)? = null,
) {
    val displayNames = sessionDisplayNames(state.sessions)
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 560.dp)
            .padding(horizontal = PocketShellSpacing.lg)
            .padding(bottom = PocketShellSpacing.lg)
            .testTag(SESSION_SWITCHER_SHEET_TAG),
    ) {
            item {
                SheetHeader(
                    title = "Sessions",
                    // Issue #2721 (N2): the sheet now reaches the whole host,
                    // not only the workspace the terminal is in.
                    subtitle = "Switch terminals in this workspace, or anywhere else on the host.",
                    onClose = onDismiss,
                )
            }
            item {
                ListRow(
                    title = "New session",
                    subtitle = "Start another terminal here.",
                    leading = {
                        Icon(
                            imageVector = PocketShellIcons.Plus,
                            contentDescription = null,
                            tint = PocketShellColors.TextSecondary,
                        )
                    },
                    onClick = onNewSession,
                    modifier = Modifier.testTag(SESSION_SWITCHER_NEW_TAG),
                )
            }
            when {
                state.loading -> item {
                    EmptyState(
                        title = "Loading sessions…",
                        modifier = Modifier
                            .padding(vertical = PocketShellSpacing.lg)
                            .testTag(SESSION_SWITCHER_LOADING_TAG),
                    )
                }
                state.failure != null && state.sessions.isEmpty() -> item {
                    EmptyState(
                        title = "Sessions unavailable",
                        description = state.failure,
                        modifier = Modifier.testTag(SESSION_SWITCHER_ERROR_TAG),
                    )
                }
                state.sessions.isEmpty() && state.otherWorkspaces.isEmpty() -> item {
                    EmptyState(
                        title = "No other sessions",
                        description = "Start another session from this workspace.",
                        modifier = Modifier.testTag(SESSION_SWITCHER_EMPTY_TAG),
                    )
                }
                else -> {
                    items(state.sessions, key = { "${it.workspace}:${it.name}" }) { session ->
                        ListRow(
                            title = displayNames[session.name] ?: "Terminal",
                            subtitle = sessionSwitcherSubtitle(session),
                            leading = {
                                Icon(
                                    imageVector = PocketShellIcons.Terminal,
                                    contentDescription = null,
                                    tint = PocketShellColors.TextSecondary,
                                )
                            },
                            // Issue #2572: with an id the match is ID-ONLY — a new
                            // session wearing the old name is not the one on
                            // screen. The name decides only for routes without an
                            // id, the pre-#2572 shape.
                            trailing = if (
                                if (currentSessionId != null) {
                                    session.id == currentSessionId
                                } else {
                                    session.name == currentSessionName
                                }
                            ) {
                                {
                                    Text(
                                        text = "Current",
                                        color = PocketShellColors.TextSecondary,
                                        style = com.pocketshell.uikit.theme.PocketShellType.metadata,
                                    )
                                }
                            } else {
                                null
                            },
                            onClick = { onOpenSession(session) },
                            modifier = Modifier.testTag(sessionSwitcherRowTag(session.name)),
                        )
                    }
                    // Issue #2721 (N2): every OTHER workspace on the host, one
                    // tap from any terminal — name · dot · count, opening that
                    // workspace's entry session.
                    if (state.otherWorkspaces.isNotEmpty()) {
                        item(key = "other-workspaces-header") {
                            SectionHeader(
                                label = "Other workspaces",
                                count = state.otherWorkspaces.size,
                                modifier = Modifier.testTag(SESSION_SWITCHER_OTHER_WORKSPACES_TAG),
                            )
                        }
                        items(
                            items = state.otherWorkspaces,
                            key = { "other-workspace:${it.path}" },
                        ) { workspace ->
                            ListRow(
                                title = workspace.label,
                                subtitle = null,
                                leading = {
                                    workspace.state?.let { state -> OtherWorkspaceDot(state = state) }
                                },
                                trailing = {
                                    Text(
                                        text = workspace.sessionCount.toString(),
                                        color = PocketShellColors.TextSecondary,
                                        style = com.pocketshell.uikit.theme.PocketShellType.metadata,
                                    )
                                },
                                onClick = { onOpenSession(workspace.entry) },
                                modifier = Modifier.testTag(
                                    sessionSwitcherWorkspaceTag(workspace.path),
                                ),
                            )
                        }
                    }
                }
            }
}
}

private fun sessionSwitcherSubtitle(session: SessionRow): String = listOfNotNull(
    sessionProgramLabel(session),
    sessionStatusLabel(session),
).joinToString(" · ")

private fun sessionProgramLabel(session: SessionRow): String = when {
    session.agent.equals("claude", ignoreCase = true) -> "Claude Code"
    session.agent.equals("codex", ignoreCase = true) -> "Codex"
    session.agent.equals("opencode", ignoreCase = true) -> "OpenCode"
    session.agent.equals("grok", ignoreCase = true) -> "Grok"
    session.engine.equals("shell", ignoreCase = true) -> "Shell"
    !session.profile.isNullOrBlank() -> session.profile.orEmpty()
    else -> sessionKindLabel(session)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalActionsSheet(
    onSessions: () -> Unit,
    onBrowseFiles: () -> Unit,
    onOpenUsage: () -> Unit,
    onCopySelection: () -> Unit,
    onDetach: () -> Unit,
    onEndSession: () -> Unit,
    onDismiss: () -> Unit,
    onPorts: () -> Unit = {},
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = PocketShellColors.Surface,
        contentColor = PocketShellColors.Text,
        shape = PocketShellShapes.large,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PocketShellSpacing.lg)
                .padding(bottom = PocketShellSpacing.lg)
                .testTag(TERMINAL_ACTIONS_SHEET_TAG),
        ) {
            SheetHeader(title = "Terminal", onClose = onDismiss)
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
            ) {
                item { TerminalActionRow("Sessions in workspace", onSessions, TERMINAL_ACTIONS_SESSIONS_TAG) }
                item { TerminalActionRow("Browse workspace files", onBrowseFiles, TERMINAL_ACTIONS_FILES_TAG) }
                // Issue #2721 (N1): "Services & tunnels" had no entry point
                // left that a terminal could reach — the workspace screen that
                // used to link it is gone, so the terminal's own sheet is its
                // home now.
                item { TerminalActionRow("Services & tunnels", onPorts, TERMINAL_ACTIONS_PORTS_TAG) }
                item { TerminalActionRow("Copy selection", onCopySelection, TERMINAL_ACTIONS_COPY_TAG) }
                item { TerminalActionRow("Detach and keep running", onDetach, TERMINAL_ACTIONS_DETACH_TAG) }
                item { TerminalActionRow(STOP_SESSION_ITEM_LABEL, onEndSession, STOP_SESSION_ITEM_TAG) }
            }
        }
    }
}

/**
 * The 8dp workload dot, in the tab strip's exact vocabulary — the switcher's
 * other-workspace rows summarise a workspace's sessions with the same dot a
 * tab carries for one session (issue #2721 N2). Static on purpose; see
 * [SessionTabState].
 */
@Composable
private fun OtherWorkspaceDot(state: SessionTabState) {
    val semantic = LocalPocketShellSemantic.current
    val color = when (state) {
        SessionTabState.Working -> semantic.statusActive
        SessionTabState.NeedsInput -> semantic.statusAttention
        SessionTabState.Idle -> semantic.statusIdle
    }
    Box(
        modifier = Modifier
            .size(PocketShellSpacing.sm)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun TerminalActionRow(title: String, onClick: () -> Unit, testTag: String) {
    ListRow(title = title, onClick = onClick, modifier = Modifier.testTag(testTag))
}

const val TERMINAL_ACTIONS_SHEET_TAG: String = "terminal-actions-sheet"
const val TERMINAL_ACTIONS_SESSIONS_TAG: String = "terminal-actions-sessions"
const val TERMINAL_ACTIONS_FILES_TAG: String = "terminal-actions-files"
const val TERMINAL_ACTIONS_PORTS_TAG: String = "terminal-actions-ports"
const val TERMINAL_ACTIONS_USAGE_TAG: String = "terminal-actions-usage"
const val TERMINAL_ACTIONS_COPY_TAG: String = "terminal-actions-copy"
const val TERMINAL_ACTIONS_DETACH_TAG: String = "terminal-actions-detach"
