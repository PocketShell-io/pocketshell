package com.pocketshell.next.hosts

import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.next.MainActivity
import com.pocketshell.next.connect.AgentsFixture
import com.pocketshell.next.connect.JourneyScreenshots
import com.pocketshell.next.connect.SeedBeforeLaunchRule
import com.pocketshell.next.connect.appGraph
import com.pocketshell.next.connect.awaitIdle
import com.pocketshell.next.connect.openQuietHost
import com.pocketshell.next.ports.ADD_TUNNEL_NAME_TAG
import com.pocketshell.next.ports.ADD_TUNNEL_SCREEN_TAG
import com.pocketshell.next.ports.ADD_TUNNEL_SUBMIT_TAG
import com.pocketshell.next.ports.SERVICES_ADD_TUNNEL_TAG
import com.pocketshell.next.ports.SERVICES_SCREEN_TAG
import com.pocketshell.next.tree.SESSION_TREE_PORTS_TAG
import com.pocketshell.next.workspaces.HOST_WORKSPACES_ACTIONS_TAG
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.File
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.Description
import org.junit.runner.RunWith

/**
 * J19 — the non-terminal form screens' submit controls stay reachable with the
 * real keyboard up (issue #2551).
 *
 * ## Why this has to be a device journey
 *
 * MainActivity runs edge-to-edge with `SOFT_INPUT_ADJUST_NOTHING`
 * (#887/#2533): the OS never resizes or pans the window, so a form that never
 * opts into `WindowInsets.ime` keeps a full-height viewport and whatever sits
 * at its bottom is simply overlaid by the keyboard — with no scroll range to
 * reveal it. No JVM test has an IME, and the synthetic-inset placement test
 * (`AddEditHostImePlacementTest`) proves layout against a blank activity, not
 * the real navigation graph. This journey drives the real route, raises the
 * real keyboard, and asserts geometry in window coordinates — the same oracle
 * the issue's uiautomator reproduction used.
 *
 * ## Screens
 *
 * - Add host: the screen the issue reports. The Quiet redesign already moved
 *   the IME inset onto the action footer; this pins the acceptance
 *   ("submit visible or reachable by scrolling") end to end.
 * - Add tunnel: the audit hit from the issue's second scope item. Its submit
 *   button is the last child of a full-height scroll column with no IME
 *   handling — exactly the shape the issue quotes for Add host.
 *
 * The seeded host is the shared Docker `agents` fixture (10.0.2.2:2222,
 * `tests/docker/docker-compose.yml`), seeded the J13 way with the live probed
 * host-key fingerprint so the dial lands without a trust prompt: opening the
 * host's workspaces screen auto-connects, and against an unreachable host that
 * screen never reaches the state carrying [HOST_WORKSPACES_TAG] (J19's first
 * run timed out exactly there with an `example.invalid` host).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class J19HostFormImeJourney {

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain
        .outerRule(HiltAndroidRule(this))
        .around(SeedBeforeLaunchRule { description -> seed(description) })
        .around(compose)

    private suspend fun seed(description: Description) {
        val graph = appGraph()
        graph.connectionsRegistry().closeAll()
        graph.hostDao().getAll().first().forEach { graph.hostDao().deleteById(it.id) }
        graph.sshKeyDao().getAll().first().forEach { graph.sshKeyDao().deleteById(it.id) }

        val fingerprint = AgentsFixture.probeHostKeyFingerprint()
        println("J19_FIXTURE ${AgentsFixture.host}:${AgentsFixture.port} $fingerprint")
        val keyPath = AgentsFixture.installPrivateKey("j19_fixture_key")
        val keyId = graph.sshKeyDao().insert(
            SshKeyEntity(
                name = "j19-key",
                privateKeyPath = keyPath,
            ),
        )
        graph.hostDao().insert(
            HostEntity(
                id = HOST_ID,
                name = "j19-host",
                hostname = AgentsFixture.host,
                port = AgentsFixture.port,
                username = AgentsFixture.USER,
                keyId = keyId,
                trustedHostKeyAlgorithm = "SHA256",
                trustedHostKeySha256 = fingerprint,
            ),
        )

        println("J19_SEED ${description.methodName}")
    }

    /**
     * The issue's acceptance: with the IME shown on Add host, the submit
     * controls are visible above the keyboard — not merely present under it.
     */
    @Test
    @Ignore("quarantined: #2679, expires 2026-09-27 — 'host-list-add-methods' never appeared within 30000ms on the hosted AVD (run 34781691505 on 41890ffb6); first hosted exposure of the #2551 IME journeys, same hostile-environment class as #2514/#2622")
    fun addHostActionsStayReachableUnderTheRealIme() {
        awaitScrollableTag(hostRowTag(HOST_ID))

        compose.onNodeWithTag(HOST_LIST_ADD_TAG).performClick()
        awaitTag(HOST_LIST_ADD_METHODS_TAG)
        compose.onNodeWithTag(HOST_LIST_ADD_DETAILS_TAG).performClick()
        awaitTag(HOST_FORM_CONTENT_TAG)
        capture("01-add-host-form")

        compose.onNodeWithTag(HOST_FORM_USERNAME_TAG).performClick()
        compose.onNodeWithTag(HOST_FORM_USERNAME_TAG).performTextInput("u")
        awaitImeUp("failure-add-host-ime-down")
        capture("02-add-host-ime-up")

        assertTagAboveKeyboard(HOST_FORM_TEST_TAG)
        assertTagAboveKeyboard(HOST_FORM_SAVE_TAG)
        compose.onNodeWithTag(HOST_FORM_SAVE_TAG).assertIsDisplayed()
        capture("03-add-host-actions-above-ime")
    }

    /**
     * The audit finding: Add tunnel had the same defect shape the issue quotes
     * for Add host — submit buried at the bottom of a full-height scroll
     * column, nothing opting into the IME inset. Fails on the pre-fix layout.
     */
    @Test
    @Ignore("quarantined: #2679, expires 2026-09-27 — the real IME never appeared within 30000ms on the hosted AVD (run 34781691505 on 41890ffb6); first hosted exposure of the #2551 IME journeys")
    fun addTunnelSubmitStaysReachableUnderTheRealIme() {
        compose.openQuietHost(HOST_ID, TIMEOUT_MS)
        awaitTag(HOST_WORKSPACES_ACTIONS_TAG)
        compose.onNodeWithTag(HOST_WORKSPACES_ACTIONS_TAG).performClick()
        awaitTag(SESSION_TREE_PORTS_TAG)
        compose.onNodeWithTag(SESSION_TREE_PORTS_TAG).performClick()
        awaitTag(SERVICES_SCREEN_TAG)
        runCatching {
            compose.onNodeWithTag(SERVICES_ADD_TUNNEL_TAG).performScrollTo()
        }
        compose.onNodeWithTag(SERVICES_ADD_TUNNEL_TAG).performClick()
        awaitTag(ADD_TUNNEL_SCREEN_TAG)
        capture("04-add-tunnel-form")

        compose.onNodeWithTag(ADD_TUNNEL_NAME_TAG).performClick()
        compose.onNodeWithTag(ADD_TUNNEL_NAME_TAG).performTextInput("9")
        awaitImeUp("failure-add-tunnel-ime-down")
        capture("05-add-tunnel-ime-up")

        // The user's recourses while the keyboard hides the bottom of the
        // column: scrolling (only once the viewport overflows) or the form
        // already fitting above the keyboard. Either way the submit must
        // clear the keyboard's top edge to count as reachable. Pre-fix the
        // full-height viewport (ADJUST_NOTHING) makes the content fit with NO
        // scroll range at all and the buried submit fails the assertion
        // below — exactly the defect shape the issue quotes for Add host.
        runCatching {
            compose.onNodeWithTag(ADD_TUNNEL_SUBMIT_TAG).performScrollTo()
        }
        assertTagAboveKeyboard(ADD_TUNNEL_SUBMIT_TAG)
        compose.onNodeWithTag(ADD_TUNNEL_SUBMIT_TAG).assertIsDisplayed()
        capture("06-add-tunnel-submit-above-ime")
    }

    // --- helpers ----------------------------------------------------------

    /**
     * The node's bottom edge in window coordinates must clear the keyboard's
     * top. Window coordinates, like [com.pocketshell.next.tree.J04CreateSessionJourney]'s
     * Create-button oracle: the window never resizes under `ADJUST_NOTHING`,
     * so `decorView.height - imeInset` IS the keyboard's top.
     */
    private fun assertTagAboveKeyboard(tag: String) {
        val imeHeight = imeInsetBottom()
        assertTrue(
            "the IME inset read 0 while asserting '$tag'; the keyboard-up " +
                "precondition must be checked with awaitImeUp first",
            imeHeight > 0,
        )
        val keyboardTop = compose.activity.window.decorView.height - imeHeight
        val bounds = compose.onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInWindow
        assertTrue(
            "'$tag' (bottom=${bounds.bottom}) is under the keyboard " +
                "(top=$keyboardTop) — the user cannot reach it",
            bounds.bottom <= keyboardTop.toFloat(),
        )
    }

    /** The framework's own IME inset, in pixels. 0 when the keyboard is down. */
    private fun imeInsetBottom(): Int {
        compose.awaitIdle("before reading the IME inset")
        return compose.runOnUiThread {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.getInsets(WindowInsetsCompat.Type.ime())
                ?.bottom
                ?: 0
        }
    }

    /**
     * Waits for the real keyboard to come up. If it never does, the test says
     * so instead of quietly asserting a no-op — the same guard J03/J04 use.
     */
    private fun awaitImeUp(failureScreenshot: String) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (imeInsetBottom() > 0) {
                // Let the inset animation finish before anything measures.
                SystemClock.sleep(IME_SETTLE_MS)
                return
            }
            SystemClock.sleep(POLL_MS)
        }
        val shot = capture(failureScreenshot)
        throw AssertionError(
            "the real IME never appeared within ${TIMEOUT_MS}ms; without it " +
                "this journey proves nothing. Screenshot: ${shot.absolutePath}",
        )
    }

    private fun awaitTag(tag: String) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.awaitIdle("tag poll: $tag")
            if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithTag(tag).assertIsDisplayed()
                return
            }
            SystemClock.sleep(POLL_MS)
        }
        val shot = capture("failure-missing-${tag.replace('-', '_')}")
        throw AssertionError(
            "'$tag' never appeared within ${TIMEOUT_MS}ms. " +
                "Screenshot: ${shot.absolutePath}",
        )
    }

    private fun awaitScrollableTag(tag: String) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) {
                runCatching {
                    compose.onNodeWithTag(tag).performScrollTo()
                    compose.onNodeWithTag(tag).assertIsDisplayed()
                }.onSuccess { return }
            }
            SystemClock.sleep(POLL_MS)
        }
        val shot = capture("failure-missing-${tag.replace('-', '_')}")
        throw AssertionError(
            "'$tag' never became scrollable/displayed within ${TIMEOUT_MS}ms. " +
                "Screenshot: ${shot.absolutePath}",
        )
    }

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
                    println("J19_SCREENSHOT ${target.absolutePath}")
                }
        }
        return file
    }

    private companion object {
        const val JOURNEY = "j19-host-form-ime"
        const val TIMEOUT_MS = 30_000L
        const val POLL_MS = 250L
        const val IME_SETTLE_MS = 1_500L
        const val HOST_ID = 9_501L
    }
}
