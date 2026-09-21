package com.pocketshell.next.mockapp

import com.pocketshell.next.ports.TunnelDisplay
import com.pocketshell.next.ports.TunnelStatusDisplay
import com.pocketshell.next.hosts.HostRow
import com.pocketshell.next.usage.UsageHostSnapshot
import com.pocketshell.next.usage.UsageProviderRecordDisplay
import com.pocketshell.next.usage.UsageResetCreditDisplay
import com.pocketshell.next.usage.UsageResetCreditsDisplay
import com.pocketshell.next.usage.UsageStatusDisplay
import com.pocketshell.next.usage.UsageWindowDisplay
import java.time.Instant

/**
 * The mock app's single data source (issue #2636 phase 1).
 *
 * Every deterministic host, key, workspace, session, tunnel and usage value
 * for the standalone mock state lives here. [MockAppState] projects these
 * values into shared display types or named mock-local mirrors. The browser
 * fixtures are not wired to this data yet; README records that remaining step
 * rather than claiming the state library is already an interactive renderer.
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
        HostRow(id = 1, name = "hetzner", subtitle = "alexey@135.181.114.209"),
        HostRow(id = 2, name = "builder", subtitle = "root@10.0.0.7"),
        HostRow(
            id = 3,
            name = "relay-eu-central-1-with-a-deliberately-long-name",
            subtitle = "deploy@relay.example.io",
        ),
    )

    // ── Workspaces / sessions (host 1 = hetzner) ─────────────────────────────

    val memberships: List<MockWorkspaceMembership> = listOf(
        MockWorkspaceMembership(path = "/home/alexey/git/pocketshell", displayPath = "~/git/pocketshell"),
        MockWorkspaceMembership(path = "/home/alexey/git/aplexer", displayPath = "~/git/aplexer"),
        MockWorkspaceMembership(path = "/home/alexey/work/mobile", displayPath = "~/work/mobile"),
    )

    // Named args per the D2 review: `1L`/`2L` bind to createdAt (and through it
    // sortOrder), NOT id — id stays at its 0L default, which is behaviour-neutral
    // because projectWorkspaceRoots reads only .path.
    val registeredRoots: List<MockRegisteredWorkspaceRoot> = listOf(
        MockRegisteredWorkspaceRoot(path = "/home/alexey/git", label = "Git", createdAt = 1L),
        MockRegisteredWorkspaceRoot(path = "/home/alexey/work", label = "Work", createdAt = 2L),
    )

    /** A live root session that makes the Git root non-empty. */
    fun session(name: String, workspace: String, attached: Boolean = true): MockSessionRow = MockSessionRow(
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

    val rootSessions: List<MockSessionRow> = listOf(
        session("root-shell", "/home/alexey/git"),
    )

    /** The workspace path a mock "start" destination opens on. */
    const val START_WORKSPACE_PATH: String = "/home/alexey/git/pocketshell"

    // ── Services & tunnels ───────────────────────────────────────────────────

    val tunnels: List<TunnelDisplay> = listOf(
        TunnelDisplay(
            remotePort = 5173,
            localPort = 35173,
            process = "vite",
            status = TunnelStatusDisplay.FORWARDING,
        ),
        TunnelDisplay(
            remotePort = 8000,
            localPort = 38000,
            process = "python",
            status = TunnelStatusDisplay.AVAILABLE,
        ),
        TunnelDisplay(remotePort = 22, localPort = 0, process = "sshd", status = TunnelStatusDisplay.AVAILABLE),
    )

    // ── Usage ────────────────────────────────────────────────────────────────

    val usageHost: UsageHostSnapshot = UsageHostSnapshot(
        hostId = 1,
        hostName = "hetzner",
        records = listOf(
            record(
                provider = "claude",
                displayName = "Claude Code",
                windows = listOf(
                    window("5h", 12.0, NOW.plusSeconds(9_000)),
                    window("7d", 41.0, NOW.plusSeconds(3 * 86_400)),
                ),
            ),
            record(
                provider = "codex",
                displayName = "Codex",
                windows = listOf(
                    window("5h", 8.0, NOW.plusSeconds(16_000)),
                    window("7d", 60.0, NOW.plusSeconds((1.4 * 86_400).toLong())),
                ),
                resetCredits = UsageResetCreditsDisplay(
                    availableCount = 3,
                    credits = listOf(
                        UsageResetCreditDisplay(title = "Full reset", expiresAt = NOW.plusSeconds(4 * 86_400)),
                    ),
                    unavailable = false,
                ),
            ),
            record(
                provider = "zai",
                displayName = "Zai",
                windows = listOf(window("7d", 7.0, NOW.plusSeconds(5 * 86_400))),
            ),
        ),
        lastSyncedAt = NOW,
    )

    fun record(
        provider: String,
        displayName: String,
        windows: List<UsageWindowDisplay>,
        resetCredits: UsageResetCreditsDisplay? = null,
    ): UsageProviderRecordDisplay = UsageProviderRecordDisplay(
        provider = provider,
        status = UsageStatusDisplay.Ok,
        rawStatus = "ok",
        displayName = displayName,
        windows = windows,
        resetCredits = resetCredits,
    )

    fun window(name: String, percent: Double, resetAt: Instant?): UsageWindowDisplay =
        UsageWindowDisplay(name = name, percent = percent, resetAt = resetAt)

    // ── Composer ─────────────────────────────────────────────────────────────

    const val SESSION_NAME: String = "git-pocketshell"

    /** A long agent prompt exercising wrapping and the send affordance. */
    const val TYPED_DRAFT: String =
        "check the deploy log and tell me what failed in the last run; " +
            "if it was the migration step, draft the rollback command but do not run it"
}
