package com.pocketshell.next.mockapp

import com.pocketshell.core.hostapi.SessionRow
import com.pocketshell.core.hostapi.WorkspaceMembership
import com.pocketshell.core.portfwd.TunnelInfo
import com.pocketshell.core.usage.UsageProviderRecord
import com.pocketshell.core.usage.UsageResetCredit
import com.pocketshell.core.usage.UsageResetCredits
import com.pocketshell.core.usage.UsageStatus
import com.pocketshell.core.usage.UsageWindow
import com.pocketshell.next.hosts.HostRow
import com.pocketshell.next.usage.UsageHostSnapshot
import com.pocketshell.next.workspaces.RegisteredWorkspaceRoot
import java.time.Instant

/**
 * The mock app's single data source (issue #2636 phase 1).
 *
 * Every deterministic host, key, workspace, session, tunnel and usage value
 * the mock harness renders lives HERE, not in the fixtures: a fixture picks a
 * scenario of [MockAppState], and [MockAppState] projects [MockData] values
 * into the production screens' UI states. Editing a value in this file and
 * saving re-renders every scenario that shows it through the ui-mock loop —
 * that is the "one mock data layer" the issue asks for, ahead of the module
 * extraction that will move it behind the presentation boundary.
 *
 * Values mirror the maintainer's real dev-box shape (hetzner + a builder
 * box) so long names, mono paths and multi-provider quotas read true.
 * Determinism: [NOW] pins every relative timestamp (usage windows, "read at"
 * labels); nothing here reads the clock or the network.
 */
object MockData {

    /** Pins usage windows and sync labels so renders never drift between runs. */
    val NOW: Instant = Instant.parse("2026-09-16T18:25:00Z")

    // ── Hosts ────────────────────────────────────────────────────────────────

    val hosts: List<HostRow> = listOf(
        HostRow(1, "hetzner", "alexey@135.181.114.209"),
        HostRow(2, "builder", "root@10.0.0.7"),
        HostRow(3, "relay-eu-central-1-with-a-deliberately-long-name", "deploy@relay.example.io"),
    )

    // ── Workspaces / sessions (host 1 = hetzner) ─────────────────────────────

    val memberships: List<WorkspaceMembership> = listOf(
        WorkspaceMembership("/home/alexey/git/pocketshell", "~/git/pocketshell"),
        WorkspaceMembership("/home/alexey/git/aplexer", "~/git/aplexer"),
        WorkspaceMembership("/home/alexey/work/mobile", "~/work/mobile"),
    )

    val registeredRoots: List<RegisteredWorkspaceRoot> = listOf(
        RegisteredWorkspaceRoot("/home/alexey/git", "Git", 1L),
        RegisteredWorkspaceRoot("/home/alexey/work", "Work", 2L),
    )

    /** A live root session that makes the Git root non-empty. */
    fun session(name: String, workspace: String, attached: Boolean = true): SessionRow = SessionRow(
        name = name,
        id = null,
        workspace = workspace,
        tag = null,
        engine = null,
        profile = null,
        agent = null,
        agentState = null,
        agentStateSource = null,
        attached = attached,
        createdEpoch = 1L,
        activityEpoch = null,
    )

    val rootSessions: List<SessionRow> = listOf(
        session("root-shell", "/home/alexey/git"),
    )

    /** The workspace path a mock "start" destination opens on. */
    const val START_WORKSPACE_PATH: String = "/home/alexey/git/pocketshell"

    // ── Services & tunnels ───────────────────────────────────────────────────

    val tunnels: List<TunnelInfo> = listOf(
        TunnelInfo(5173, 35173, "vite", TunnelInfo.Status.FORWARDING),
        TunnelInfo(8000, 38000, "python", TunnelInfo.Status.AVAILABLE),
        TunnelInfo(22, 0, "sshd", TunnelInfo.Status.AVAILABLE),
    )

    // ── Usage ────────────────────────────────────────────────────────────────

    val usageHost: UsageHostSnapshot = UsageHostSnapshot(
        hostId = 1,
        hostName = "hetzner",
        records = listOf(
            record(
                provider = "claude",
                windows = listOf(
                    window("5h", 12.0, NOW.plusSeconds(9_000)),
                    window("7d", 41.0, NOW.plusSeconds(3 * 86_400)),
                ),
            ),
            record(
                provider = "codex",
                windows = listOf(
                    window("5h", 8.0, NOW.plusSeconds(16_000)),
                    window("7d", 60.0, NOW.plusSeconds((1.4 * 86_400).toLong())),
                ),
                resetCredits = UsageResetCredits(
                    availableCount = 3,
                    credits = listOf(
                        UsageResetCredit("Full reset", NOW.plusSeconds(4 * 86_400)),
                    ),
                    unavailable = false,
                ),
            ),
            record(
                provider = "zai",
                windows = listOf(window("7d", 7.0, NOW.plusSeconds(5 * 86_400))),
            ),
        ),
        lastSyncedAt = NOW,
    )

    fun record(
        provider: String,
        windows: List<UsageWindow>,
        resetCredits: UsageResetCredits? = null,
    ): UsageProviderRecord = UsageProviderRecord(
        provider = provider,
        status = UsageStatus.Ok,
        windows = windows,
        rawStatus = "ok",
        resetCredits = resetCredits,
    )

    fun window(name: String, percent: Double, resetAt: Instant?): UsageWindow = UsageWindow(
        name = name,
        used = percent,
        limit = 100.0,
        unit = "percent",
        resetAt = resetAt,
    )

    // ── Composer ─────────────────────────────────────────────────────────────

    const val SESSION_NAME: String = "git-pocketshell"

    /** A long agent prompt exercising wrapping and the send affordance. */
    const val TYPED_DRAFT: String =
        "check the deploy log and tell me what failed in the last run; " +
            "if it was the migration step, draft the rollback command but do not run it"
}
