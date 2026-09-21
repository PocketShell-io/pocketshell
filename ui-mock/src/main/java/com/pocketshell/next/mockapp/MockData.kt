package com.pocketshell.next.mockapp

import com.pocketshell.next.crash.CrashReportDisplay
import com.pocketshell.next.files.FileEntryDisplay
import com.pocketshell.next.ports.TunnelDisplay
import com.pocketshell.next.ports.TunnelStatusDisplay
import com.pocketshell.next.hosts.HostRow
import com.pocketshell.next.hosts.SshKeyRow
import com.pocketshell.next.settings.AppBuildInfo
import com.pocketshell.next.settings.ReleaseUpdateDisplay
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

    // ── SSH keys (shared SshKeysUiState rows, D13 seam) ──────────────────────

    /** One row with a deliberately long authorized-keys line (long content). */
    val sshKeys: List<SshKeyRow> = listOf(
        SshKeyRow(
            id = 1,
            name = "hetzner-deploy",
            fingerprint = "SHA256:0vAbCdEfGhIjKlMnOpQrStUvWxYz0123456789AbCdEfGhI",
            hasPassphrase = true,
            publicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIJDvAbCdEfGhIjKlMnOpQrStUvWxYz0123456789abcd " +
                "pocketshell-maintainer@RMTHZ-" + "x".repeat(96),
            algorithm = "ED25519",
            publicFingerprint = "SHA256:0vAbCdEfGhIjKlMnOpQrStUvWxYz0123456789AbCdEfGhI",
        ),
        SshKeyRow(
            id = 2,
            name = "builder-root",
            fingerprint = "SHA256:1zYxWvUtSrQpOnMlKjIhGfEdCbA9876543210zYxWvUtSrQpO",
        ),
    )

    // ── Files / file viewer ──────────────────────────────────────────────────

    /** The directory the mock Files destination opens at. */
    const val FILES_ROOT_PATH: String = "/home/alexey/git/pocketshell"

    /** The file the mock FileViewer destination opens. */
    const val VIEWER_PATH: String = "/home/alexey/git/pocketshell/docs/architecture.md"

    val fileEntries: List<FileEntryDisplay> = listOf(
        FileEntryDisplay(
            path = "/home/alexey/git/pocketshell/docs",
            name = "docs",
            isDirectory = true,
            sizeBytes = 4_096L,
            modifiedEpochMs = NOW.minusSeconds(3_600).toEpochMilli(),
        ),
        FileEntryDisplay(
            path = "/home/alexey/git/pocketshell/build.gradle.kts",
            name = "build.gradle.kts",
            isDirectory = false,
            sizeBytes = 1_842L,
            modifiedEpochMs = NOW.minusSeconds(86_400).toEpochMilli(),
        ),
        FileEntryDisplay(
            path = "/home/alexey/git/pocketshell/" +
                "a-deliberately-long-file-name-that-must-truncate-or-wrap-not-overflow-the-row.kts",
            name = "a-deliberately-long-file-name-that-must-truncate-or-wrap-not-overflow-the-row.kts",
            isDirectory = false,
            sizeBytes = 137L,
            modifiedEpochMs = NOW.minusSeconds(120).toEpochMilli(),
        ),
    )

    /** Long multi-line content the mock viewer/editor opens — long-content case. */
    val LONG_FILE_CONTENT: String = buildList {
        repeat(24) { paragraph ->
            add(
                "## Section ${paragraph + 1}\n" +
                    "The module map keeps core-transport/sshj behind the session layer so the " +
                    "terminal never blocks the UI thread, and every paragraph here is long " +
                    "enough to exercise wrapping, scrolling and editor drafts without any " +
                    "randomness — paragraph $paragraph of the pinned deterministic fixture.",
            )
        }
    }.joinToString(separator = "\n\n")

    // ── Diagnostics (shared crash display seam, D12) ─────────────────────────

    val crashReports: List<CrashReportDisplay> = listOf(
        CrashReportDisplay(
            id = "report-2026-0920-0814",
            timestamp = NOW.minusSeconds(2 * 86_400),
            summary = "SSH connect timed out after 15s on hetzner",
            contextSummary = "Foreground session attach; wifi RTT 240ms",
            appVersion = "1.2.0 (312)",
            topFrame = "at com.pocketshell.core.transport.SshjConnector.connect(SshjConnector.kt:214)",
        ),
        CrashReportDisplay(
            id = "report-2026-0918-2243",
            timestamp = NOW.minusSeconds(4 * 86_400),
            summary = "Terminal view crashed on IME commit with a 12k-char insert",
            contextSummary = "Background; composer paste long content",
            appVersion = "1.1.9 (311)",
            topFrame = "at com.pocketshell.next.terminal.TerminalHostView.commitText(TerminalHostView.kt:88)",
        ),
    )

    // ── About / update ───────────────────────────────────────────────────────

    val buildInfo: AppBuildInfo = AppBuildInfo(versionName = "1.2.0", versionCode = 312L)

    val updateRelease: ReleaseUpdateDisplay = ReleaseUpdateDisplay(
        tagName = "v1.3.0",
        htmlUrl = "https://github.com/PocketShell-io/pocketshell/releases/tag/v1.3.0",
        apkUrl = "https://github.com/PocketShell-io/pocketshell/releases/download/v1.3.0/app2-debug.apk",
        publishedDateLabel = "20 Sep 2026",
    )

    // ── Account sync ─────────────────────────────────────────────────────────

    const val ACCOUNT_EMAIL: String = "alexey@example.com"

    // ── Composer ─────────────────────────────────────────────────────────────

    const val SESSION_NAME: String = "git-pocketshell"

    /** A long agent prompt exercising wrapping and the send affordance. */
    const val TYPED_DRAFT: String =
        "check the deploy log and tell me what failed in the last run; " +
            "if it was the migration step, draft the rollback command but do not run it"
}
