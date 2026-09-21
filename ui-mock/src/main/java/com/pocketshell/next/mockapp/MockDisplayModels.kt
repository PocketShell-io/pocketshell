package com.pocketshell.next.mockapp

/**
 * Mock-local mirrors for display inputs that have not crossed the shared
 * presentation boundary yet (#2636 AC3).
 *
 * These deliberately do not copy implementation behaviour from app2. They are
 * immutable values sufficient for deterministic mock scenarios. When the
 * corresponding production display type moves to `:shared:ui-screens`, the
 * mirror can be replaced at this one seam without admitting app2/core/Termux
 * to this module's dependency graph.
 */
data class MockSessionRow(
    val name: String,
    val id: String? = null,
    val workspace: String? = null,
    val tag: String? = null,
    val engine: String? = null,
    val profile: String? = null,
    val agent: String? = null,
    val agentState: String? = null,
    val agentStateSource: String? = null,
    val attached: Boolean = false,
    val createdEpoch: Long? = null,
    val activityEpoch: Long? = null,
)

data class MockWorkspaceMembership(
    val path: String,
    val displayPath: String,
)

data class MockRegisteredWorkspaceRoot(
    val path: String,
    val label: String,
    val createdAt: Long = 0L,
)

data class MockWorkspaceRootProjection(
    val path: String,
    val label: String,
    val sessionCount: Int,
)

data class MockHostWorkspacesUiState(
    val hostId: Long,
    val hostLabel: String,
    val loaded: Boolean,
    val roots: List<MockWorkspaceRootProjection>,
    val searchQuery: String,
)

data class MockWorkspaceStartUiState(
    val hostId: Long,
    val hostLabel: String,
    val loaded: Boolean,
    val workspacePath: String,
)

data class MockSshKeysUiState(
    val loaded: Boolean,
    val message: String?,
)

/** Opaque stand-in for the platform terminal object; no native emulator. */
data class MockTerminalSession(
    val columns: Int = 80,
    val rows: Int = 24,
)

sealed interface MockSessionUiState {
    data object Connecting : MockSessionUiState
    data class Live(val terminal: MockTerminalSession) : MockSessionUiState
    data class Reconnecting(
        val attempt: Int,
        val retryInMs: Long,
        val terminal: MockTerminalSession,
    ) : MockSessionUiState
    data class Failed(val message: String) : MockSessionUiState
}

internal fun projectMockWorkspaceRoots(
    sessions: List<MockSessionRow>,
    memberships: List<MockWorkspaceMembership>,
    registeredRoots: List<MockRegisteredWorkspaceRoot>,
): List<MockWorkspaceRootProjection> = registeredRoots.map { root ->
    val knownPaths = memberships.map(MockWorkspaceMembership::path)
    MockWorkspaceRootProjection(
        path = root.path,
        label = root.label,
        sessionCount = sessions.count { session ->
            val workspace = session.workspace ?: return@count false
            workspace == root.path ||
                workspace.startsWith("${root.path}/") && knownPaths.any { it == workspace }
        },
    )
}
