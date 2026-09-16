package com.pocketshell.next.mockapp

import com.pocketshell.next.hosts.HostFormState
import com.pocketshell.next.hosts.HostRow
import com.pocketshell.next.ports.PortForwardUiState
import com.pocketshell.next.terminal.SessionUiState
import com.pocketshell.next.workspaces.HostWorkspacesUiState

/**
 * One immutable snapshot of the whole mock app (issue #2636 phase 1).
 *
 * This is the embryonic presentation boundary the issue asks for: a single
 * state value the shell renders and the reducer replaces — no ViewModel, no
 * Room, no SSH, no clock. Screens never see [MockAppState] directly; the
 * `to...UiState()` functions here project it into the production screens'
 * existing state types, which is the same mapping shape the extracted
 * presentation module will own permanently (AGENT-HANDOFF step 2/3).
 *
 * Pure Kotlin: everything here constructs without Robolectric, so the
 * reducer and these projections unit-test on the plain JVM in milliseconds.
 */
data class MockAppState(
    val destination: MockDestination = MockDestination.Hosts,

    // ── Hosts ────────────────────────────────────────────────────────────────

    /** False until the (mock) first host read completed — the cold-launch state. */
    val hostsLoaded: Boolean = false,
    val hosts: List<HostRow> = emptyList(),

    // ── Host form ────────────────────────────────────────────────────────────

    val hostForm: HostFormState = HostFormState(),

    // ── SSH keys ─────────────────────────────────────────────────────────────

    val sshKeysLoaded: Boolean = false,
    val sshKeyMessage: String? = null,

    // ── Workspaces ───────────────────────────────────────────────────────────

    val workspaceSearchQuery: String = "",

    // ── Session ──────────────────────────────────────────────────────────────

    val sessionPhase: MockSessionPhase = MockSessionPhase.CONNECTING,
    /** User-facing failure text for [MockSessionPhase.FAILED]. */
    val sessionMessage: String = "",
    val reconnectAttempt: Int = 0,
    val reconnectRetryInMs: Long = 0L,
    val composerDraft: String = "",
    val composerHistory: List<String> = emptyList(),

    // ── Services & tunnels ───────────────────────────────────────────────────

    val servicesEnabled: Boolean = false,
    val servicesLoading: Boolean = false,

    // ── Usage ────────────────────────────────────────────────────────────────

    val usageRefreshing: Boolean = false,
) {
    /** The session screen's session label (the workspace row's entry session). */
    val sessionName: String get() = MockData.SESSION_NAME

    // ── Projections into the production screens' state types ─────────────────

    fun toHostListUiState() = com.pocketshell.next.hosts.HostListUiState(
        hosts = hosts,
        loaded = hostsLoaded,
    )

    fun toSshKeysUiState() = com.pocketshell.next.hosts.SshKeysUiState(
        loaded = sshKeysLoaded,
        message = sshKeyMessage,
    )

    fun toWorkspaceUiState(): HostWorkspacesUiState {
        val roots = com.pocketshell.next.workspaces.projectWorkspaceRoots(
            sessions = MockData.rootSessions,
            memberships = MockData.memberships,
            registeredRoots = MockData.registeredRoots,
        )
        return HostWorkspacesUiState(
            hostId = HOST_ID,
            hostLabel = HOST_LABEL,
            loaded = true,
            roots = roots,
            searchQuery = workspaceSearchQuery,
        )
    }

    fun toWorkspaceStartUiState() = com.pocketshell.next.tree.SessionTreeUiState(
        hostId = HOST_ID,
        hostLabel = HOST_LABEL,
        loaded = true,
        workspacePath = MockData.START_WORKSPACE_PATH,
    )

    /**
     * [SessionUiState] for the session screen. `Live`/`Reconnecting` need the
     * vendored `com.termux.terminal.TerminalSession`; the shell builds it at
     * the composition boundary and hands it in here, so this state class stays
     * Robolectric-free.
     */
    fun toSessionUiState(terminal: SessionUiState.Live): SessionUiState =
        when (sessionPhase) {
            MockSessionPhase.CONNECTING -> SessionUiState.Connecting
            MockSessionPhase.LIVE -> terminal
            MockSessionPhase.RECONNECTING ->
                SessionUiState.Reconnecting(reconnectAttempt, reconnectRetryInMs, terminal.terminal)
            MockSessionPhase.FAILED -> SessionUiState.Failed(sessionMessage)
        }

    fun toServicesUiState() = PortForwardUiState(
        hostId = HOST_ID,
        hostName = HOST_LABEL,
        hostSubtitle = HOST_ADDRESS,
        enabled = servicesEnabled,
        rows = if (servicesEnabled) MockData.tunnels else emptyList(),
        discoveredRows = if (servicesEnabled) MockData.tunnels else emptyList(),
        loading = servicesLoading,
    )

    fun toUsageUiState() = com.pocketshell.next.usage.UsageScreenState(
        selectedHostId = HOST_ID,
        selectedHostName = HOST_LABEL,
        hosts = listOf(MockData.usageHost),
        isRefreshing = usageRefreshing,
        loaded = true,
        connectedHostCount = 1,
    )

    companion object {
        const val HOST_ID: Long = 1L
        const val HOST_LABEL: String = "hetzner"
        const val HOST_ADDRESS: String = "alexey@135.181.114.209"

        /** Cold launch: the host list has not been (mock-)read yet. */
        fun boot(): MockAppState = MockAppState()

        /** The deterministic populated app every scenario starts from. */
        fun populated(): MockAppState = MockAppState(
            hostsLoaded = true,
            hosts = MockData.hosts,
            sshKeysLoaded = true,
        )
    }
}

/** How far the mock session is, mirroring [SessionUiState]'s four shapes. */
enum class MockSessionPhase {
    CONNECTING,
    LIVE,
    RECONNECTING,
    FAILED,
}
