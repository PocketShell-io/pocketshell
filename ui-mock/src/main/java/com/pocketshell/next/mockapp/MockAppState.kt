package com.pocketshell.next.mockapp

import com.pocketshell.next.crash.CrashReportDisplay
import com.pocketshell.next.crash.CrashReportsLoadState
import com.pocketshell.next.files.FileCrumbDisplay
import com.pocketshell.next.files.FileExplorerDisplayState
import com.pocketshell.next.hosts.HostFormState
import com.pocketshell.next.hosts.HostRow
import com.pocketshell.next.hosts.SshKeyRow
import com.pocketshell.next.hosts.SshKeysUiState
import com.pocketshell.next.ports.PortForwardDisplayState
import com.pocketshell.next.ports.TunnelDisplay
import com.pocketshell.next.settings.AppSettings
import com.pocketshell.next.settings.AppBuildInfo
import com.pocketshell.next.settings.SettingsUpdateCheckState
import com.pocketshell.next.sync.AccountSyncUiState
import com.pocketshell.next.usage.UsageFailedHost
import com.pocketshell.next.usage.UsageScreenState

/**
 * One immutable snapshot of the whole mock app (issue #2636 phase 1).
 *
 * This is the state foundation for the presentation boundary: a single value
 * the future shell renders and the reducer replaces — no ViewModel, Room, SSH,
 * terminal implementation, or clock. Projections use shared display types
 * where extraction is complete and explicit mock-local mirrors where it is
 * not; README lists those seams and does not claim a runnable shell.
 *
 * Since the D18 interactive-state slice every production destination is
 * representable here, each with the loading / error / typing / long-content
 * states its real screen has. Families whose display types already live in
 * `:shared:ui-screens` (settings, update check, account sync, crash reports,
 * SSH keys, file explorer, usage, ports) are carried or projected AS those
 * shared types; families still app-side (session tree, viewer, workspace
 * roots) use named mock-local mirrors from [MockDisplayModels].
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

    // ── SSH keys (shared SshKeysUiState — D13 seam) ──────────────────────────

    val sshKeysLoaded: Boolean = false,
    val sshKeys: List<SshKeyRow> = emptyList(),
    val sshKeysGenerating: Boolean = false,
    val sshKeyMessage: String? = null,

    // ── Workspaces (host root list) ──────────────────────────────────────────

    val workspaceSearchQuery: String = "",

    // ── One persistent workspace (session-tree mirror) ───────────────────────

    val workspaceScreen: MockWorkspaceUiState = MockWorkspaceUiState(),

    /** The create-folder sheet opened by the WorkspaceRootAction route. */
    val workspaceAction: MockWorkspaceActionState = MockWorkspaceActionState(),

    // ── Session ──────────────────────────────────────────────────────────────

    val sessionPhase: MockSessionPhase = MockSessionPhase.CONNECTING,
    /** User-facing failure text for [MockSessionPhase.FAILED]. */
    val sessionMessage: String = "",
    val reconnectAttempt: Int = 0,
    val reconnectRetryInMs: Long = 0L,
    val composerDraft: String = "",
    val composerHistory: List<String> = emptyList(),

    // ── Files / file viewer ──────────────────────────────────────────────────

    val filesPath: String = MockData.FILES_ROOT_PATH,
    val filesLoading: Boolean = false,
    val filesLoaded: Boolean = false,
    val filesFailure: String? = null,
    val viewer: MockViewerUiState = MockViewerUiState(),

    // ── Services & tunnels ───────────────────────────────────────────────────

    val servicesEnabled: Boolean = false,
    val servicesLoading: Boolean = false,
    val tunnelDetailPort: Int? = null,
    /** Tunnels the mock AddTunnel form "created" — display data only. */
    val manualTunnels: List<TunnelDisplay> = emptyList(),
    val addTunnelForm: MockAddTunnelFormState = MockAddTunnelFormState(),

    // ── Usage / HostUsage ────────────────────────────────────────────────────

    val usageRefreshing: Boolean = false,
    /** The host read failure surfaced through [UsageScreenState.failedHosts]. */
    val usageFailure: String? = null,

    // ── Settings pages (shared AppSettings — D6 seam) ────────────────────────

    val settings: AppSettings = AppSettings(),

    // ── Account sync (shared AccountSyncUiState — D7 seam) ───────────────────

    val accountSync: AccountSyncUiState = AccountSyncUiState(clientConfigured = true),

    // ── About / Update (shared update-check seam — D3) ───────────────────────

    val buildInfo: AppBuildInfo = MockData.buildInfo,
    val updateCheck: SettingsUpdateCheckState = SettingsUpdateCheckState.Idle,

    // ── Diagnostics (shared crash seam — D12) ────────────────────────────────

    val diagnosticsLoad: CrashReportsLoadState = CrashReportsLoadState.Loading,
    val crashReports: List<CrashReportDisplay> = emptyList(),
    val diagnosticReportId: String? = null,

    // ── Workspace roots manager + reorder ────────────────────────────────────

    val workspaceRoots: MockWorkspaceRootsUiState = MockWorkspaceRootsUiState(),
    val addWorkspaceRootForm: MockAddWorkspaceRootForm = MockAddWorkspaceRootForm(),
    val reorderRoots: List<MockRegisteredWorkspaceRoot> = emptyList(),
) {
    /** The session screen's session label (the workspace row's entry session). */
    val sessionName: String get() = MockData.SESSION_NAME

    // ── Projections into shared or explicitly mock-local display types ───────

    fun toHostListUiState() = com.pocketshell.next.hosts.HostListUiState(
        hosts = hosts,
        loaded = hostsLoaded,
    )

    /**
     * Projects into the SHARED `SshKeysUiState` (D13) — the D18 slice deleted
     * the former `MockSshKeysUiState` mirror at exactly this seam.
     */
    fun toSshKeysUiState() = SshKeysUiState(
        keys = sshKeys,
        loaded = sshKeysLoaded,
        generating = sshKeysGenerating,
        message = sshKeyMessage,
    )

    fun toWorkspaceUiState(): MockHostWorkspacesUiState {
        val roots = projectMockWorkspaceRoots(
            sessions = MockData.rootSessions,
            memberships = MockData.memberships,
            registeredRoots = reorderRoots.ifEmpty { MockData.registeredRoots },
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

    /** Mock-local mirror: app2's `SessionTreeUiState` is not shared yet. */
    fun toWorkspaceScreenUiState(): MockWorkspaceUiState = workspaceScreen.copy(
        hostId = HOST_ID,
        hostLabel = HOST_LABEL,
        sessions = workspaceScreen.sessions.ifEmpty { MockData.rootSessions },
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

    /** Projects into the SHARED `FileExplorerDisplayState` (D14 seam). */
    fun toFileExplorerUiState(): FileExplorerDisplayState = FileExplorerDisplayState(
        subtitle = HOST_LABEL,
        path = filesPath,
        entries = MockData.fileEntries,
        crumbs = projectFileCrumbs(hostLabel = HOST_LABEL, root = MockData.FILES_ROOT_PATH, path = filesPath),
        loading = filesLoading,
        loaded = filesLoaded,
        failure = filesFailure,
    )

    /** Mock-local mirror: app2's `ViewerUiState` is not shared yet. */
    fun toViewerUiState(): MockViewerUiState = viewer.copy(
        hostId = HOST_ID,
        hostName = HOST_LABEL,
    )

    fun toServicesUiState() = PortForwardDisplayState(
        hostName = HOST_LABEL,
        hostSubtitle = HOST_ADDRESS,
        enabled = servicesEnabled,
        rows = if (servicesEnabled) allTunnels else emptyList(),
        discoveredRows = if (servicesEnabled) MockData.tunnels else emptyList(),
        loading = servicesLoading,
    )

    /** Every known tunnel: the discovered fixture rows plus mock-created manual ones. */
    val allTunnels: List<TunnelDisplay>
        get() = MockData.tunnels + manualTunnels

    /** localPort -> owning process for collision display; auto-assign (0) excluded. */
    val processByLocalPort: Map<Int, String>
        get() = allTunnels.filter { it.localPort != 0 }.associate { it.localPort to it.process }

    val usedLocalPorts: Set<Int>
        get() = processByLocalPort.keys

    val addTunnelValid: Boolean
        get() = addTunnelForm.isValid(usedLocalPorts)

    fun addTunnelCollision(): String? = addTunnelForm.localPortCollision(processByLocalPort)

    /** The TunnelDetail screen's row, or null while the port is unknown (its error state). */
    fun tunnelDetailTunnel(): TunnelDisplay? =
        tunnelDetailPort?.let { port -> allTunnels.firstOrNull { it.remotePort == port } }

    /** True when the detail row came from the mock AddTunnel form rather than discovery. */
    val tunnelDetailManual: Boolean
        get() = tunnelDetailPort != null && manualTunnels.any { it.remotePort == tunnelDetailPort }

    fun toUsageUiState() = UsageScreenState(
        selectedHostId = HOST_ID,
        selectedHostName = HOST_LABEL,
        hosts = listOf(MockData.usageHost),
        failedHosts = usageFailure
            ?.let { reason -> listOf(UsageFailedHost(hostId = HOST_ID, hostName = HOST_LABEL, reason = reason)) }
            ?: emptyList(),
        isRefreshing = usageRefreshing,
        loaded = true,
        connectedHostCount = 1,
    )

    /** Mock-local mirror: app2's `WorkspaceRootsUiState` is not shared yet. */
    fun toWorkspaceRootsUiState(): MockWorkspaceRootsUiState = workspaceRoots.copy(
        hostId = HOST_ID,
        hostName = HOST_LABEL,
    )

    /** The carried SHARED `AccountSyncUiState` (D7) — no mirror exists. */
    fun toAccountSyncUiState(): AccountSyncUiState = accountSync

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
            sshKeys = MockData.sshKeys,
            reorderRoots = MockData.registeredRoots,
        )
    }
}

/**
 * Breadcrumb projection for the shared file explorer: one host crumb at the
 * mock root, then one crumb per segment below it. A path outside the mock
 * root degrades to a single self-named crumb rather than inventing segments.
 */
internal fun projectFileCrumbs(hostLabel: String, root: String, path: String): List<FileCrumbDisplay> {
    if (path.isBlank()) return emptyList()
    val crumbs = mutableListOf(FileCrumbDisplay(label = hostLabel, path = root))
    if (path == root) return crumbs
    if (!path.startsWith("${root.trimEnd('/')}/")) {
        return listOf(FileCrumbDisplay(label = path, path = path))
    }
    var prefix = root.trimEnd('/')
    for (segment in path.removePrefix("${root.trimEnd('/')}/").split('/')) {
        if (segment.isEmpty()) continue
        prefix = "$prefix/$segment"
        crumbs.add(FileCrumbDisplay(label = segment, path = prefix))
    }
    return crumbs
}

/** How far the mock session is, mirroring [MockSessionUiState]'s four shapes. */
enum class MockSessionPhase {
    CONNECTING,
    LIVE,
    RECONNECTING,
    FAILED,
}
