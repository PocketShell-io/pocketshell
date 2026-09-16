package com.pocketshell.next.tree

import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.next.MainActivity
import com.pocketshell.next.connect.AgentsFixture
import com.pocketshell.next.connect.JourneyScreenshots
import com.pocketshell.next.connect.SeedBeforeLaunchRule
import com.pocketshell.next.connect.appGraph
import com.pocketshell.next.connect.openQuietHost
import com.pocketshell.next.terminal.SESSION_HEADER_KEBAB_TAG
import com.pocketshell.next.terminal.SESSION_SCREEN_TAG
import com.pocketshell.next.terminal.SESSION_SWITCHER_SHEET_TAG
import com.pocketshell.next.terminal.sessionSwitcherRowTag
import com.pocketshell.next.workspaces.HOST_WORKSPACES_TAG
import com.pocketshell.next.workspaces.workspaceRowTag
import com.pocketshell.uikit.components.SESSION_TAB_OVERFLOW_TAG
import com.pocketshell.uikit.components.SESSION_TAB_STRIP_TAG
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.Description
import org.junit.runner.RunWith
import kotlinx.coroutines.flow.first

/**
 * Journey J14 — stop a throwaway session reached from its workspace and prove
 * the HOST no longer lists it (issue #2535).
 *
 * The Quiet redesign (#2569) made workspace rows navigation-only: a row has no
 * kebab, and Stop lives in the session screen's header kebab ("End session?"
 * ConfirmDialog, design-kit frame `end-session`). So every stop here opens the
 * session from its workspace row first; the third test keeps the attached-
 * session variant. Stop from the host tree kebab is covered by the tree screen.
 *
 * ## Why this has to be a device journey
 *
 * The Stop path is kebab → confirm → `pocketshell sessions kill` → refresh.
 * A ViewModel over a scripted connection cannot see a fixture CLI that does
 * not have the verb, a kill that hits `claude-main` because the name was a
 * prefix, or a session screen that stays on a dead PTY. The oracle after
 * Stop is an independent `pocketshell sessions list --json` over SSH.
 *
 * ## Why the oracle is the HOST's liveness derivation, not a fixture (#2556)
 *
 * Every host response this journey reads is derived by the real host CLI from
 * the raw aplexer listing (`a --json list`): whether a row is listed at all,
 * and whether Stop's row disappears, are decisions the CLI's liveness filter
 * makes (#2554) — host code the emulator lane once could not observe at all
 * because the journey drove canned responses (issue #2556). Two oracles close
 * that hole:
 *
 * 1. The Stop tests assert the row left the DERIVED listing *and* the raw
 *    aplexer listing — flipped on the host, not just in-app.
 * 2. The worker-death test kills a session's worker OUT OF BAND (a bare
 *    `kill -9` of the pid read from the raw listing). The aplexer record
 *    survives as a corpse and nothing the app drove can remove it, so the
 *    only mechanism that can take the row out of the derived listing and the
 *    tree is the host's liveness derivation. The discriminating pair is read
 *    at the same moment over the independent connection: the raw listing
 *    still carries the corpse (`worker_alive: false`) while the derived
 *    listing hides it. Reverting #2554's filter re-lists the corpse and this
 *    journey goes red — demonstrated on the fixture by neutralizing
 *    `aplexer_record_is_alive` in the installed wheel (red: the corpse is
 *    re-listed; restored: hidden again).
 *
 * Poll discipline here is bounded awaits with named diagnostics (#2648); no
 * bare display assertion decides an async outcome (the #2679 J13 shape).
 *
 * ## Do not kill fixture sessions you did not create
 *
 * `claude-main` is the canned session every workspace journey lands on. This
 * class creates a throwaway name, stops THAT, and asserts `claude-main` is
 * still on the host.
 *
 * Bring the fixture up before running:
 * `docker compose -f tests/docker/docker-compose.yml up -d --build agents`
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class J14StopSessionJourney {

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain
        .outerRule(HiltAndroidRule(this))
        .around(SeedBeforeLaunchRule { description -> seed(description) })
        .around(compose)

    private var hostId: Long = 0

    private suspend fun seed(description: Description) {
        val graph = appGraph()
        graph.connectionsRegistry().closeAll()
        graph.hostDao().getAll().first().forEach { graph.hostDao().deleteById(it.id) }
        graph.sshKeyDao().getAll().first().forEach { graph.sshKeyDao().deleteById(it.id) }

        val fingerprint = AgentsFixture.probeHostKeyFingerprint()
        println("J14_FIXTURE ${AgentsFixture.host}:${AgentsFixture.port} $fingerprint")

        seedHostState(description)

        val keyPath = AgentsFixture.installPrivateKey(fileName = "j14_fixture_key")
        val keyId = graph.sshKeyDao().insert(
            SshKeyEntity(name = "j14-${description.methodName}", privateKeyPath = keyPath),
        )
        hostId = HOST_IDS.getValue(description.methodName)
        graph.hostDao().insert(
            HostEntity(
                id = hostId,
                name = "docker-fixture",
                hostname = AgentsFixture.host,
                port = AgentsFixture.port,
                username = AgentsFixture.USER,
                keyId = keyId,
                trustedHostKeyAlgorithm = "SHA256",
                trustedHostKeySha256 = fingerprint,
            ),
        )
    }

    private fun seedHostState(description: Description) {
        AgentsFixture.exec("rm -f $ERRORS_FILE")
        val tag = THROWAWAY_BY_TEST.getValue(description.methodName)
        val name = displayName(tag)
        cleanupThrowaway(name)
        // A worker-killed corpse is invisible to `sessions kill` (the CLI
        // resolves names through the liveness-filtered view), so a leftover
        // from a crashed run would make the create below answer created:false
        // and the row would never appear. Forget it at the source first.
        forgetAnyDeadRecord(tag)
        AgentsFixture.exec(
            "pocketshell sessions create --cwd /home/testuser/git/pocketshell " +
                "--mem none --json -- '$tag'",
        )
        AgentsFixture.exec("pocketshell sessions kill -- '$CANNED_SESSION' >/dev/null 2>&1 || true")
        AgentsFixture.exec(
            "pocketshell sessions create --cwd /home/testuser/git/pocketshell " +
                "--mem none --json -- claude-main >/dev/null",
        )
    }

    @Test
    fun stoppingAThrowawaySessionFromTheWorkspaceRemovesItFromTheHost() {
        openWorkspace()
        assertTrue(
            "the throwaway must exist before Stop",
            SESSION_TREE in hostSessionNames(),
        )
        assertTrue(
            "claude-main is a fixture session this journey must not kill",
            CANNED_SESSION in hostSessionNames(),
        )

        // The row tap landed in the workspace's ENTRY terminal (claude-main).
        // Reach the throwaway through the strip's overflow — the switcher is
        // backed by the workspace's live listing (#2721 route).
        openSessionFromSwitcher(SESSION_TREE)
        awaitSessionScreen()
        compose.onNodeWithTag(SESSION_HEADER_KEBAB_TAG).performClick()
        compose.onNodeWithTag(STOP_SESSION_ITEM_TAG, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(STOP_SESSION_ITEM_TAG, useUnmergedTree = true).performClick()
        compose.onNodeWithText(STOP_SESSION_TITLE).assertIsDisplayed()
        JourneyScreenshots.capture("01-stop-confirm", JOURNEY)

        compose.onNodeWithTag(STOP_SESSION_CONFIRM_TAG).performClick()

        // LeaveAfterStop pops Back — to the HOST workspaces, the level the
        // terminal was entered from (#2721).
        awaitTag(HOST_WORKSPACES_TAG)
        awaitGone(SESSION_SCREEN_TAG)
        JourneyScreenshots.capture("02-tree-after-stop", JOURNEY)

        // The on-screen oracle: re-entering the workspace and opening the
        // switcher shows the fresh listing WITHOUT the stopped session.
        openWorkspace()
        openSwitcher()
        awaitGone(sessionSwitcherRowTag(SESSION_TREE))
        compose.onNodeWithTag(sessionSwitcherRowTag(CANNED_SESSION)).assertIsDisplayed()
        JourneyScreenshots.capture("02b-switcher-after-stop", JOURNEY)

        val names = hostSessionNames()
        assertFalse("the host must no longer list the stopped session, got $names", SESSION_TREE in names)
        assertTrue("stopping the throwaway must not kill $CANNED_SESSION, got $names", CANNED_SESSION in names)
        // The derived listing hiding the row is the CLI's liveness filter at
        // work; the SOURCE must have flipped too — the kill's reap removes the
        // aplexer record itself (#2554), which is host behaviour a fixture
        // could never prove (#2556).
        awaitRawRecordGone(SESSION_TREE)
    }

    @Test
    fun cancellingStopLeavesTheSessionAlive() {
        openWorkspace()
        openSessionFromSwitcher(SESSION_CANCEL)
        awaitSessionScreen()

        compose.onNodeWithTag(SESSION_HEADER_KEBAB_TAG).performClick()
        compose.onNodeWithTag(STOP_SESSION_ITEM_TAG, useUnmergedTree = true).performClick()
        compose.onNodeWithTag(STOP_SESSION_CANCEL_TAG).performClick()

        compose.onNodeWithText(STOP_SESSION_TITLE).assertDoesNotExist()
        JourneyScreenshots.capture("03-cancel-alive", JOURNEY)

        assertTrue(
            "Cancel must leave the session on the host",
            SESSION_CANCEL in hostSessionNames(),
        )
        assertTrue(CANNED_SESSION in hostSessionNames())
    }

    @Test
    fun stoppingTheAttachedSessionReturnsToTheHostWorkspaces() {
        openWorkspace()
        // The entry terminal IS the attached session's workspace landing;
        // reach the attached throwaway through the switcher like a user.
        openSessionFromSwitcher(SESSION_ATTACHED)
        awaitSessionScreen()

        compose.onNodeWithTag(SESSION_HEADER_KEBAB_TAG).performClick()
        compose.onNodeWithTag(STOP_SESSION_ITEM_TAG, useUnmergedTree = true).performClick()
        compose.onNodeWithText(STOP_SESSION_TITLE).assertIsDisplayed()
        compose.onNodeWithTag(STOP_SESSION_CONFIRM_TAG).performClick()

        awaitTag(HOST_WORKSPACES_TAG)
        awaitGone(SESSION_SCREEN_TAG)
        JourneyScreenshots.capture("04-popped-after-stop", JOURNEY)

        val names = hostSessionNames()
        assertFalse(SESSION_ATTACHED in names)
        assertTrue(CANNED_SESSION in names)
    }

    /**
     * The host-derived liveness journey (#2556): a session whose worker dies
     * OUT OF BAND on the host must leave both the derived CLI listing and the
     * tree, even though nothing reaped it.
     *
     * Every earlier test in this class kills through `pocketshell sessions
     * kill`, so a corpse cannot distinguish liveness derivation from the
     * kill's own reap — a revert of #2554's liveness filter alone would stay
     * green there because the reap removes the record anyway. This test kills
     * only the worker process (the pid read from the raw aplexer listing,
     * over the independent connection), which leaves the record behind as a
     * corpse. The tree then has exactly one way to lose the row: the host
     * CLI's liveness derivation. The red-on-revert property is demonstrated
     * in the class KDoc.
     */
    @Test
    fun aWorkerKilledOnTheHostLeavesTheDerivedListingAndTheTree() {
        try {
            openWorkspace()
            // The baseline that makes every later absence non-vacuous: while
            // the worker is alive the switcher (the workspace's live listing,
            // reached from the entry terminal's strip) really renders the row.
            openSwitcher()
            compose.onNodeWithTag(sessionSwitcherRowTag(SESSION_WORKER_DEATH)).assertIsDisplayed()
            dismissSwitcher()

            killWorkerOutOfBand(SESSION_WORKER_DEATH)

            // Re-enter through the real hierarchy so the listing re-derives
            // from the host instead of trusting the render already up.
            openWorkspace()
            openSwitcher()

            // Control: the re-entry rendered a fresh listing (claude-main is
            // still live), so the throwaway's absence below is evidence, not
            // a render that never happened.
            compose.onNodeWithTag(sessionSwitcherRowTag(CANNED_SESSION)).assertIsDisplayed()
            awaitGone(sessionSwitcherRowTag(SESSION_WORKER_DEATH))
            awaitStillGone(sessionSwitcherRowTag(SESSION_WORKER_DEATH))
            JourneyScreenshots.capture("05-host-worker-death", JOURNEY)

            // The discriminating pair, over the independent connection: the
            // SOURCE still carries the corpse...
            val corpse = rawAplexerRecord(SESSION_WORKER_DEATH)
            assertTrue(
                "the worker-killed record must survive at the aplexer source " +
                    "(nothing reaped it), got $corpse",
                corpse != null && !corpse.optBoolean(WORKER_ALIVE_KEY, true),
            )
            // ...while the CLI's DERIVED listing hides it. Reverting #2554's
            // liveness filter re-lists the corpse and fails exactly here.
            val names = hostSessionNames()
            assertFalse(
                "the derived host listing must not show the worker-dead session, got $names",
                displayName(SESSION_WORKER_DEATH) in names,
            )
            assertTrue(
                "the control session must survive the out-of-band worker kill, got $names",
                CANNED_SESSION in names,
            )
        } finally {
            // `sessions kill` resolves names through the filtered view and
            // cannot see a corpse; forget the record at the source instead.
            forgetAnyDeadRecord(SESSION_WORKER_DEATH)
        }
    }

    /**
     * Opens the seeded workspace and lands in its ENTRY terminal — the
     * recreate of claude-main in the seed is the most recent activity, so the
     * row's entry-session ladder picks it (#2721: no workspace page between
     * the row and the terminal).
     */
    private fun openWorkspace() {
        compose.openQuietHost(hostId, TIMEOUT_MS)
        awaitTag(workspaceRowTag(WORKSPACE_MAIN))
        compose.onNodeWithTag(workspaceRowTag(WORKSPACE_MAIN)).performClick()
        awaitTag(SESSION_SCREEN_TAG)
        awaitTag(SESSION_TAB_STRIP_TAG)
    }

    /** The tab strip's overflow: the switcher over the workspace's live listing. */
    private fun openSwitcher() {
        compose.onNodeWithTag(SESSION_TAB_OVERFLOW_TAG).performClick()
        awaitTag(SESSION_SWITCHER_SHEET_TAG)
    }

    private fun dismissSwitcher() {
        // The sheet's own close affordance (SheetHeader's trailing X).
        compose.onNodeWithContentDescription("Close").performClick()
        awaitGone(SESSION_SWITCHER_SHEET_TAG)
    }

    /** Opens [name] from the entry terminal's switcher sheet. */
    private fun openSessionFromSwitcher(name: String) {
        openSwitcher()
        awaitTag(sessionSwitcherRowTag(name))
        compose.onNodeWithTag(sessionSwitcherRowTag(name)).performClick()
        awaitTag(SESSION_SCREEN_TAG)
        awaitGone(SESSION_SWITCHER_SHEET_TAG)
    }

    private fun hostSessionNames(): List<String> {
        val payload = JSONObject(AgentsFixture.exec("pocketshell sessions list --json"))
        assertTrue(payload.getInt("schema") >= 3)
        val sessions = payload.getJSONArray("sessions")
        return (0 until sessions.length()).map { index ->
            sessions.getJSONObject(index).getString("name")
        }
    }

    private fun cleanupThrowaway(name: String) {
        AgentsFixture.exec("pocketshell sessions kill -- '$name' >/dev/null 2>&1 || true")
    }

    /**
     * Every record in the fixture host's RAW aplexer listing, read over the
     * independent connection. This is the SOURCE the host CLI derives its
     * session view from — reading it directly is what lets the journey tell
     * "the record is gone from aplexer" apart from "the record is dead and
     * the CLI's liveness filter hides it" (#2556).
     */
    private fun rawAplexerRecords(): List<JSONObject> {
        val payload = JSONArray(AgentsFixture.exec(RAW_APLEXER_LIST_CMD))
        return (0 until payload.length()).map { payload.getJSONObject(it) }
    }

    /**
     * The raw aplexer record for [tag], or null when the source lacks it.
     * Callers pass the derived display name ("pocketshell:<tag>"); the raw
     * aplexer tag carries no manager prefix, so strip it before matching.
     */
    private fun rawAplexerRecord(tag: String): JSONObject? {
        val plainTag = tag.substringAfter(':')
        return rawAplexerRecords().firstOrNull { it.optString(RAW_TAG_KEY) == plainTag }
    }

    /**
     * Kill the worker PROCESS behind [tag]'s session on the fixture host,
     * out of band, and wait until the raw listing itself reports the death.
     *
     * Nothing here goes through `pocketshell sessions kill`: the pid comes
     * from the raw `a --json list`, the kill is a bare `kill -9`, and the
     * wait is a bounded poll on `worker_alive` flipping false at the source.
     * What is left behind is exactly the state the host CLI's liveness
     * derivation must classify: a record that still exists but is dead.
     */
    private fun killWorkerOutOfBand(tag: String) {
        val liveDeadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        var live: JSONObject? = null
        while (SystemClock.elapsedRealtime() < liveDeadline) {
            live = rawAplexerRecord(tag)
            val pid = live?.optInt(WORKER_PID_KEY, -1) ?: -1
            if (live != null && pid > 0 && live.optBoolean(WORKER_ALIVE_KEY, false)) break
            SystemClock.sleep(RAW_POLL_MS)
        }
        assertTrue(
            "the live session must appear in the raw aplexer listing with a " +
                "live worker pid, got $live",
            live != null &&
                live!!.optInt(WORKER_PID_KEY, -1) > 0 &&
                live.optBoolean(WORKER_ALIVE_KEY, false),
        )
        val pid = live!!.getInt(WORKER_PID_KEY)
        AgentsFixture.exec("kill -9 $pid")
        val deathDeadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deathDeadline) {
            val corpse = rawAplexerRecord(tag)
            if (corpse != null && !corpse.optBoolean(WORKER_ALIVE_KEY, true)) {
                println("J14_WORKER_DEATH tag=$tag pid=$pid corpse=$corpse")
                return
            }
            SystemClock.sleep(RAW_POLL_MS)
        }
        error(
            "worker $pid was killed but the raw aplexer listing never reported " +
                "the death of '$tag' within ${TIMEOUT_MS}ms",
        )
    }

    /**
     * Best-effort `a forget --force` for a worker-killed corpse of [tag].
     * Best effort on purpose: the record is invisible to the CLI's derived
     * view (that is the behaviour under test), so a leftover corpse must be
     * cleaned at the source but a cleanup failure must not mask the journey's
     * own verdict — the per-test seed forgets leftovers the same way.
     */
    private fun forgetAnyDeadRecord(tag: String) {
        runCatching {
            val record = rawAplexerRecord(tag) ?: return
            if (record.optBoolean(WORKER_ALIVE_KEY, true)) return
            val id = record.optString("id")
            if (id.isNotBlank()) {
                AgentsFixture.exec("a forget --force '$id' >/dev/null 2>&1 || true")
                println("J14_CORPSE_FORGOT tag=$tag id=$id")
            }
        }.onFailure { println("J14_CORPSE_CLEANUP_FAILED $tag: $it") }
    }

    /**
     * Bounded wait until the raw aplexer listing no longer contains [tag] at
     * all — the source-level complement to the derived-listing assertions.
     */
    private fun awaitRawRecordGone(tag: String) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        var last: JSONObject? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            last = rawAplexerRecord(tag)
            if (last == null) return
            SystemClock.sleep(RAW_POLL_MS)
        }
        error("the raw aplexer listing still carries '$tag' after the stop: $last")
    }

    /**
     * The row must STAY absent for a settle window after the awaited absence.
     * A plain awaitGone also passes on a screen that never re-derived; the
     * window is what distinguishes "the fresh listing landed and the row is
     * out" from "the query simply has not landed yet".
     */
    private fun awaitStillGone(tag: String) {
        val deadline = SystemClock.elapsedRealtime() + STILL_GONE_WINDOW_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) {
                throw AssertionError(
                    "the worker-dead session '$tag' reappeared in the tree within " +
                        "the ${STILL_GONE_WINDOW_MS}ms settle window",
                )
            }
            SystemClock.sleep(RAW_POLL_MS)
        }
    }

    private fun displayName(tag: String): String = "pocketshell:$tag"

    private fun awaitSessionScreen() {
        awaitTag(SESSION_SCREEN_TAG)
        awaitTag(SESSION_TAB_STRIP_TAG)
        compose.onNodeWithTag(SESSION_SCREEN_TAG).assertIsDisplayed()
        // Quiet terminal chrome puts the workspace name in the large header
        // and the sibling sessions in the tab strip below it (#2632).
        compose.onNodeWithTag(SESSION_TAB_STRIP_TAG).assertIsDisplayed()
    }

    // #2648: the CI failure was a bare ComposeTimeoutException that never said
    // WHICH await expired after Stop, so every timeout now names its tag and
    // leaves a screenshot before rethrowing.
    private fun awaitTag(tag: String) = awaitTagState(tag, wantPresent = true)

    private fun awaitGone(tag: String) = awaitTagState(tag, wantPresent = false)

    private fun awaitTagState(tag: String, wantPresent: Boolean) {
        val startedAt = SystemClock.elapsedRealtime()
        try {
            compose.waitUntil(timeoutMillis = TIMEOUT_MS) {
                val present = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
                if (wantPresent) present else !present
            }
        } catch (timeout: Throwable) {
            println(
                "J14_AWAIT_TIMEOUT tag=$tag want=${if (wantPresent) "present" else "gone"} " +
                    "waitedMs=${SystemClock.elapsedRealtime() - startedAt}",
            )
            runCatching { JourneyScreenshots.capture("await-timeout", JOURNEY) }
                .onSuccess { shot -> println("J14_TIMEOUT_SCREENSHOT ${shot.absolutePath}") }
            throw timeout
        }
    }

    private companion object {
        const val TIMEOUT_MS = 60_000L
        const val JOURNEY = "j14-stop-session"

        const val CANNED_SESSION = "pocketshell:claude-main"
        const val SESSION_TREE = "pocketshell:j14-stop-tree"
        const val SESSION_CANCEL = "pocketshell:j14-stop-cancel"
        const val SESSION_ATTACHED = "pocketshell:j14-stop-attached"
        const val SESSION_WORKER_DEATH = "pocketshell:j14-stop-worker"
        const val WORKSPACE_MAIN = "/home/testuser/git/pocketshell"

        const val ERRORS_FILE = "\$HOME/.pocketshell-fixture-session-errors.json"

        // The SOURCE listing the host CLI derives its session view from, plus
        // the record keys the liveness derivation reads (#2556). `a --json
        // list` and `a list --json` both emit the same top-level array.
        const val RAW_APLEXER_LIST_CMD = "a --json list"
        const val RAW_TAG_KEY = "tag"
        const val WORKER_PID_KEY = "worker_pid"
        const val WORKER_ALIVE_KEY = "worker_alive"

        /** Cadence for raw-listing polls; each poll is one short SSH exec. */
        const val RAW_POLL_MS = 500L

        /** How long the tree must keep the dead row out after awaitGone. */
        const val STILL_GONE_WINDOW_MS = 4_000L

        val HOST_IDS: Map<String, Long> = mapOf(
            "stoppingAThrowawaySessionFromTheWorkspaceRemovesItFromTheHost" to 9_141L,
            "cancellingStopLeavesTheSessionAlive" to 9_142L,
            "stoppingTheAttachedSessionReturnsToTheWorkspace" to 9_143L,
            "aWorkerKilledOnTheHostLeavesTheDerivedListingAndTheTree" to 9_144L,
        )

        val THROWAWAY_BY_TEST: Map<String, String> = mapOf(
            "stoppingAThrowawaySessionFromTheWorkspaceRemovesItFromTheHost" to "j14-stop-tree",
            "cancellingStopLeavesTheSessionAlive" to "j14-stop-cancel",
            "stoppingTheAttachedSessionReturnsToTheWorkspace" to "j14-stop-attached",
            "aWorkerKilledOnTheHostLeavesTheDerivedListingAndTheTree" to "j14-stop-worker",
        )
    }
}
