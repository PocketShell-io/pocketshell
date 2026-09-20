package com.pocketshell.next.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.next.MainActivity
import com.pocketshell.next.connect.AgentsFixture
import com.pocketshell.next.connect.JourneyScreenshots
import com.pocketshell.next.connect.SeedBeforeLaunchRule
import com.pocketshell.next.connect.appGraph
import com.pocketshell.next.connect.awaitQuietTag
import com.pocketshell.next.connect.openQuietHost
import com.pocketshell.next.connect.openQuietSession
import com.pocketshell.next.terminal.SESSION_HEADER_KEBAB_TAG
import com.pocketshell.next.terminal.SESSION_SCREEN_TAG
import com.pocketshell.next.terminal.TERMINAL_ACTIONS_SETTINGS_TAG
import com.pocketshell.next.terminal.TERMINAL_ACTIONS_SHEET_TAG
import com.pocketshell.next.workspaces.HOST_WORKSPACES_ACTIONS_TAG
import com.pocketshell.next.workspaces.HOST_WORKSPACES_HOST_TOOLS_TAG
import com.pocketshell.next.workspaces.HOST_WORKSPACES_SETTINGS_TAG
import com.pocketshell.next.workspaces.HOST_WORKSPACES_TAG
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.File
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.Description
import org.junit.runner.RunWith

/**
 * J24 — issue #2814's two user-visible promises, on a real Android window
 * against the real Docker `agents` fixture.
 *
 * ## Why a device journey, and what it is NOT duplicating
 *
 * The JVM suites already pin the pieces: `QuietWorkspaceScreenTest` and
 * `SessionScreenTest` pin that each sheet HAS a Settings row that fires its
 * lambda, and `AppNavHostTest` pins that the graph turns that lambda into
 * `Destination.Settings` and that a resolvable resume pair lands on
 * `Destination.Session`. What none of them can see is the thing the issue is
 * actually about: that on a device, from a CONNECTED host and from a LIVE
 * attached terminal, the Settings index is genuinely on screen — the old
 * complaint was reachability, and reachability is not "in the hierarchy".
 *
 * N-4's oracle here is the host's own session id, read over an INDEPENDENT SSH
 * connection ([AgentsFixture.stableSessionId]), compared against what the
 * production `SettingsRepository` persisted. A journey that only re-read the
 * app's own state would pass just as happily against a pair the app invented.
 *
 * Bring the fixture up before running:
 * `docker compose -f tests/docker/docker-compose.yml up -d --build agents`
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class J24SettingsReachJourney {

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
        println("J24_FIXTURE ${AgentsFixture.host}:${AgentsFixture.port} $fingerprint")

        // One real aplexer session in one workspace is the whole fixture this
        // journey needs: the sheets do not care how many there are, and N-4
        // resumes exactly one.
        AgentsFixture.exec("pocketshell sessions kill -- '$SESSION' >/dev/null 2>&1 || true")
        AgentsFixture.exec(
            "pocketshell sessions create --cwd '$WORKSPACE' --mem none --json -- '$SESSION_TAG' >/dev/null",
        )

        val keyPath = AgentsFixture.installPrivateKey(fileName = "j24_fixture_key")
        val keyId = graph.sshKeyDao().insert(
            SshKeyEntity(name = "j24-${description.methodName}", privateKeyPath = keyPath),
        )
        hostId = HOST_IDS.getValue(description.methodName)
        graph.hostDao().insert(
            HostEntity(
                id = hostId,
                name = "settings-reach-host",
                hostname = AgentsFixture.host,
                port = AgentsFixture.port,
                username = AgentsFixture.USER,
                keyId = keyId,
                treeIdentity = "j24-tree-${description.methodName}",
                trustedHostKeyAlgorithm = "SHA256",
                trustedHostKeySha256 = fingerprint,
            ),
        )
        graph.projectRootDao().insert(
            ProjectRootEntity(
                hostId = hostId,
                label = "Git",
                path = WORKSPACE_ROOT,
                createdAt = 1L,
                sortOrder = 0L,
            ),
        )

        // The resume pair must be written BY this run, so clear whatever a
        // previous method left behind — otherwise the N-4 assertion could pass
        // on a stale value and prove nothing.
        graph.settingsRepository().setDefaultHostId(null)
        graph.settingsRepository().setLastSession(null, null)
        println("J24_SEED ${description.methodName}")
    }

    /** N-2: one tap to Settings from the host tools sheet and from the terminal's. */
    @Test
    fun settingsIsOneTapFromTheWorkspaceListAndFromALiveSession() {
        compose.openQuietHost(hostId, TIMEOUT_MS)

        compose.onNodeWithTag(HOST_WORKSPACES_ACTIONS_TAG).performClick()
        compose.awaitQuietTag(HOST_WORKSPACES_HOST_TOOLS_TAG, TIMEOUT_MS)
        compose.onNodeWithTag(HOST_WORKSPACES_SETTINGS_TAG).assertIsDisplayed()
        capture("01-host-tools-settings-row")

        compose.onNodeWithTag(HOST_WORKSPACES_SETTINGS_TAG).performClick()
        compose.awaitQuietTag(SETTINGS_LIST_TAG, TIMEOUT_MS)
        compose.onNodeWithTag(SETTINGS_LIST_TAG).assertIsDisplayed()
        capture("02-settings-from-workspace-list")

        compose.onNodeWithTag(SETTINGS_BACK_TAG).performClick()
        compose.awaitQuietTag(HOST_WORKSPACES_TAG, TIMEOUT_MS)

        compose.openQuietSession(hostId, SESSION, WORKSPACE, TIMEOUT_MS)
        compose.awaitQuietTag(SESSION_SCREEN_TAG, TIMEOUT_MS)
        compose.onNodeWithTag(SESSION_HEADER_KEBAB_TAG).performClick()
        compose.awaitQuietTag(TERMINAL_ACTIONS_SHEET_TAG, TIMEOUT_MS)
        compose.onNodeWithTag(TERMINAL_ACTIONS_SETTINGS_TAG).assertIsDisplayed()
        capture("03-terminal-actions-settings-row")

        compose.onNodeWithTag(TERMINAL_ACTIONS_SETTINGS_TAG).performClick()
        compose.awaitQuietTag(SETTINGS_LIST_TAG, TIMEOUT_MS)
        compose.onNodeWithTag(SETTINGS_LIST_TAG).assertIsDisplayed()
        // The reported cost was back → back → back → tap. Proving the reverse
        // trip is what shows nothing was consumed getting here: Back from
        // Settings lands on the terminal that was open, not on the host list.
        capture("04-settings-from-live-session")
        compose.onNodeWithTag(SETTINGS_BACK_TAG).performClick()
        compose.awaitQuietTag(SESSION_SCREEN_TAG, TIMEOUT_MS)
        compose.onNodeWithTag(SESSION_SCREEN_TAG).assertIsDisplayed()
    }

    /** N-4: opening a session records the pair a cold launch can resume. */
    @Test
    fun openingASessionRemembersTheWorkAndNotOnlyTheHost() {
        val settings = appGraph().settingsRepository()
        assertEquals(
            "the seed must leave nothing remembered, or this proves nothing",
            null,
            settings.settings.value.lastSessionId,
        )

        compose.openQuietSession(hostId, SESSION, WORKSPACE, TIMEOUT_MS)
        compose.awaitQuietTag(SESSION_SCREEN_TAG, TIMEOUT_MS)
        capture("05-session-open")

        // The oracle is the HOST's id for the session now on screen, read over
        // an independent connection — not the app's own re-render of it.
        val hostSessionId = AgentsFixture.stableSessionId(SESSION)

        compose.waitUntil(timeoutMillis = TIMEOUT_MS) {
            settings.settings.value.lastSessionId != null
        }
        val snapshot = settings.settings.value
        assertEquals(
            "the remembered session must be the one the host says is on screen",
            hostSessionId,
            snapshot.lastSessionId,
        )
        assertEquals(WORKSPACE, snapshot.lastWorkspacePath)
        assertEquals(hostId, snapshot.defaultHostId)
        assertTrue(
            "the remembered pair must still be live on the host, i.e. resumable",
            hostSessionNames().contains(SESSION),
        )
    }

    /** The host's own listing, over an independent SSH connection. */
    private fun hostSessionNames(): List<String> =
        com.pocketshell.core.hostapi.SessionsJson
            .parseSessionsList(AgentsFixture.exec("pocketshell sessions list --json"))
            .getOrThrow()
            .sessions
            .map { it.name }

    /** Keep real-device captures after the connected-test app is removed. */
    private fun capture(name: String): File {
        val file = JourneyScreenshots.capture(name, JOURNEY)
        val outputDir = InstrumentationRegistry.getArguments()
            .getString("additionalTestOutputDir")
            ?.takeIf { it.isNotBlank() }
            ?: return file
        runCatching {
            val targetDir = File(outputDir, JOURNEY).apply { mkdirs() }
            file.parentFile?.listFiles()
                ?.filter { it.isFile && it.name.startsWith(file.nameWithoutExtension) }
                ?.forEach { artifact ->
                    val target = File(targetDir, artifact.name)
                    artifact.copyTo(target, overwrite = true)
                    println("J24_SCREENSHOT ${target.absolutePath}")
                }
        }
        return file
    }

    private companion object {
        const val TIMEOUT_MS = 60_000L
        const val JOURNEY = "j24-settings-reach"

        const val SESSION_TAG = "settings-reach"
        const val SESSION = "pocketshell:settings-reach"
        const val WORKSPACE_ROOT = "/home/testuser/git"
        const val WORKSPACE = "/home/testuser/git/pocketshell"

        /** Per-method host ids, same reason as J02: SQLite reuses `max(id) + 1`. */
        val HOST_IDS: Map<String, Long> = mapOf(
            "settingsIsOneTapFromTheWorkspaceListAndFromALiveSession" to 9_241L,
            "openingASessionRemembersTheWorkAndNotOnlyTheHost" to 9_242L,
        )
    }
}
