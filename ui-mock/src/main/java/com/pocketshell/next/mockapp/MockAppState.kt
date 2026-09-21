package com.pocketshell.next.mockapp

import com.pocketshell.next.hosts.HostFormState
import com.pocketshell.next.hosts.HostRow
import com.pocketshell.next.ports.PortForwardDisplayState

/**
 * One immutable snapshot of the whole mock app (issue #2636 phase 1).
 *
 * This is the state foundation for the presentation boundary: a single value
 * the future shell renders and the reducer replaces — no ViewModel, Room, SSH,
 * terminal implementation, or clock. Projections use shared display types
 * where extraction is complete and explicit mock-local mirrors where it is
 * not; README lists those seams and does not claim a runnable shell.
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

    // ── Projections into shared or explicitly mock-local display types ───────

    fun toHostListUiState() = com.pocketshell.next.hosts.HostListUiState(
        hosts = hosts,
        loaded = hostsLoaded,
    )

    fun toSshKeysUiState() = MockSshKeysUiState(
        loaded = sshKeysLoaded,
        message = sshKeyMessage,
    )

    fun toWorkspaceUiState(): MockHostWorkspacesUiState {
        val roots = projectMockWorkspaceRoots(
            sessions = MockData.rootSessions,
            memberships = MockData.memberships,
            registeredRoots = MockData.registeredRoots,
        )
        return MockHostWorkspacesUiState(
            hostId = HOST_ID,
            hostLabel = HOST_LABEL,
            loaded = true,
            roots = roots,
            searchQuery = workspaceSearchQuery,
        )
    }

    fun toWorkspaceStartUiState() = MockWorkspaceStartUiState(
        hostId = HOST_ID,
        hostLabel = HOST_LABEL,
        loaded = true,
        workspacePath = MockData.START_WORKSPACE_PATH,
    )

    /**
     * Mock-local session display state. The real Termux-backed state remains an
     * app-side route concern until its pure display seam is extracted.
     */
    fun toSessionUiState(terminal: MockSessionUiState.Live): MockSessionUiState =
        when (sessionPhase) {
            MockSessionPhase.CONNECTING -> MockSessionUiState.Connecting
            MockSessionPhase.LIVE -> terminal
            MockSessionPhase.RECONNECTING ->
                MockSessionUiState.Reconnecting(reconnectAttempt, reconnectRetryInMs, terminal.terminal)
            MockSessionPhase.FAILED -> MockSessionUiState.Failed(sessionMessage)
        }

    fun toServicesUiState() = PortForwardDisplayState(
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

/** How far the mock session is, mirroring [MockSessionUiState]'s four shapes. */
enum class MockSessionPhase {
    CONNECTING,
    LIVE,
    RECONNECTING,
    FAILED,
}
