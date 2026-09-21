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
 *
 * D18 did exactly that for the SSH-keys family: `MockSshKeysUiState` was
 * deleted when D13's shared `com.pocketshell.next.hosts.SshKeysUiState`
 * landed, and the state now projects into the shared type directly.
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

/**
 * Mock-local mirror of the persistent-workspace screen's display inputs
 * (app2's `SessionTreeUiState`, not yet shared). Loading (first read),
 * refreshing, failure (a failed refresh keeps content under an error banner)
 * and the workspace's live sessions are exactly what the screen paints.
 */
data class MockWorkspaceUiState(
    val hostId: Long = 0,
    val hostLabel: String = "",
    val workspacePath: String? = null,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    val failure: String? = null,
    val sessions: List<MockSessionRow> = emptyList(),
)

/**
 * Mock-local mirror of the file viewer/editor's display inputs (app2's
 * `ViewerUiState`, not yet shared). [content] is the loaded long-form text;
 * [draft] is the editing buffer (typing); [failure] is the load/save error.
 */
data class MockViewerUiState(
    val hostId: Long = 0,
    val hostName: String = "",
    val path: String = "",
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val failure: String? = null,
    val content: String = "",
    val editing: Boolean = false,
    val draft: String = "",
    val savedMessage: String? = null,
)

/** Mock-local mirror of one saved workspace-root row (app2's `WorkspaceRootRow`). */
data class MockWorkspaceRootRow(
    val id: Long,
    val label: String,
    val path: String,
    val workspaceCount: Int = 0,
)

/**
 * Mock-local mirror of the workspace-roots manager's display inputs (app2's
 * `WorkspaceRootsUiState`, not yet shared): the host it manages plus its
 * roots, a cold-loading flag and a load/save failure.
 */
data class MockWorkspaceRootsUiState(
    val hostId: Long = 0,
    val hostName: String = "",
    val roots: List<MockWorkspaceRootRow> = emptyList(),
    val loaded: Boolean = false,
    val failure: String? = null,
)

/** Typing state of the focused add-workspace-root form (label + path fields). */
data class MockAddWorkspaceRootForm(
    val label: String = "",
    val path: String = "",
) {
    /** Canonical absolute path, or null while the path is not absolute yet. */
    val canonicalPath: String?
        get() = path.trim().trimEnd('/').takeIf { it.startsWith("/") }

    val valid: Boolean
        get() = label.isNotBlank() && canonicalPath != null
}

/** Typing state of the manual tunnel form (name + remote/local port fields). */
data class MockAddTunnelFormState(
    val name: String = "",
    val remotePort: String = "",
    val localPort: String = "",
) {
    /** Parsed remote port, or null while the text is not a valid 1..65535 port. */
    val remotePortValue: Int?
        get() = remotePort.trim().toIntOrNull()?.takeIf { it in 1..65_535 }

    /** Parsed local port (0 = auto-assign), or null while invalid. */
    val localPortValue: Int?
        get() = localPort.trim().toIntOrNull()?.takeIf { it in 0..65_535 }

    val nameValid: Boolean
        get() = name.isNotBlank()

    fun isValid(usedLocalPorts: Set<Int>): Boolean =
        nameValid && remotePortValue != null && localPortValue != null &&
            localPortValue !in usedLocalPorts

    /** The user-facing collision line the production screen paints, or null. */
    fun localPortCollision(processByLocalPort: Map<Int, String>): String? {
        val port = localPortValue ?: return null
        val owner = processByLocalPort[port] ?: return null
        return "Local port $port is already forwarding $owner."
    }
}

/**
 * Mock-local mirror of the workspace route's folder action (app2's
 * `WorkspaceActionState`, not yet shared): the create-folder sheet opened by
 * `Destination.WorkspaceRootAction`, with its name draft as typing state.
 */
data class MockWorkspaceActionState(
    val rootPath: String? = null,
    val name: String = "",
) {
    val visible: Boolean get() = rootPath != null
}

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
