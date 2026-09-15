package com.pocketshell.next.hosts

import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.next.MainActivity
import com.pocketshell.next.connect.AgentsFixture
import com.pocketshell.next.connect.JourneyScreenshots
import com.pocketshell.next.connect.SeedBeforeLaunchRule
import com.pocketshell.next.connect.TRUST_SHEET_FINGERPRINT_TAG
import com.pocketshell.next.connect.TRUST_SHEET_PREVIOUS_FINGERPRINT_TAG
import com.pocketshell.next.connect.TRUST_SHEET_TRUST_TAG
import com.pocketshell.next.connect.appGraph
import com.pocketshell.next.connect.awaitIdle
import com.pocketshell.next.workspaces.HOST_WORKSPACES_EMPTY_TAG
import com.pocketshell.next.workspaces.HOST_WORKSPACES_ERROR_TAG
import com.pocketshell.next.workspaces.HOST_WORKSPACES_LIST_TAG
import com.pocketshell.next.workspaces.HOST_WORKSPACES_LOADING_TAG
import com.pocketshell.next.workspaces.HOST_WORKSPACES_TAG
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.Description
import org.junit.runner.RunWith

/**
 * Journey J21 — add a host through the real [AddEditHostScreen] — name,
 * address, username, port, key picked in the real dropdown — save it, and
 * connect for the FIRST time, raising and answering the host-key prompt
 * (issue #2492).
 *
 * ## Why this has to be a device journey
 *
 * Every other journey seeds its host row straight into Room (J01/J13/J19
 * style), so the app's only hand-entry path for a host — the screen a fresh
 * install cannot start without (rewrite task P-6) — was covered only by
 * Robolectric tests. The "type a hostname, save, connect" path is exactly the
 * stack those cannot see: form validation painted on a rejected submit, the
 * collapsed Connection-options disclosure a non-22 port must open, the key
 * dropdown, the save → pop-back → list rendering of an AUTO-ASSIGNED row id,
 * and that row's first dial through the real sshj trust prompt.
 *
 * ## Oracles
 *
 * Rendered screens and the persisted Room row, never ViewModel state (D29):
 * the rejected submit must write nothing, the saved row must carry the typed
 * endpoint/key and no trusted key, the first-contact prompt must show the
 * fingerprint the fixture sshd ACTUALLY presents (independently probed in the
 * seed step, same oracle as J01), and trusting it must persist exactly that
 * key and land on workspaces.
 *
 * ## AC-2: seeding shortcut vs. this journey — they coexist, deliberately
 *
 * The DAO-seeded hosts of J01/J13/J19 stay. Replacing them with
 * this UI flow would (a) lose the per-test EXPLICIT host ids J01 documents as
 * load-bearing against the connection-cache id-reuse trap — a UI-driven add
 * gets an autoincrement id — and (b) put IME typing latency and flake into
 * every journey whose subject is not the form. This journey is the dedicated
 * coverage for the form; the seed shortcut remains the fast fixture for
 * journeys about everything else.
 *
 * ## Fixture
 *
 * Needs the Docker SSH fixture reachable at `10.0.2.2:2222` — see
 * [AgentsFixture] for the bring-up command; the probe in [seed] doubles as the
 * fixture-readiness gate.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class J21HostAddConnectJourney {

    private val compose = createAndroidComposeRule<MainActivity>()

    /**
     * Order is load-bearing (J01): Hilt first, then the seed — MainActivity
     * must launch into a wiped hosts table so the empty state carries the add
     * action and the first dial is genuinely a first contact — then the
     * activity.
     */
    @get:Rule
    val chain: RuleChain = RuleChain
        .outerRule(HiltAndroidRule(this))
        .around(SeedBeforeLaunchRule { description -> seed(description) })
        .around(compose)

    /** The fingerprint the fixture sshd actually presents, captured in [seed]. */
    private lateinit var presentedFingerprint: String

    private var keyId: Long = 0

    /**
     * Seeds the SSH KEY row only. The host row is deliberately NOT seeded:
     * creating it through the real form is the journey.
     */
    private suspend fun seed(description: Description) {
        val graph = appGraph()
        graph.connectionsRegistry().closeAll()
        graph.hostDao().getAll().first().forEach { graph.hostDao().deleteById(it.id) }
        graph.sshKeyDao().getAll().first().forEach { graph.sshKeyDao().deleteById(it.id) }

        presentedFingerprint = AgentsFixture.probeHostKeyFingerprint()
        println("J21_FIXTURE ${AgentsFixture.host}:${AgentsFixture.port} $presentedFingerprint")

        val keyPath = AgentsFixture.installPrivateKey("j21_fixture_key")
        keyId = graph.sshKeyDao().insert(
            SshKeyEntity(name = KEY_NAME, privateKeyPath = keyPath),
        )

        println("J21_SEED ${description.methodName}")
    }

    /**
     * The acceptance journey: empty list → Add host → Enter connection
     * details → rejected empty submit → valid fill → Save → first connect →
     * trust prompt with the real fingerprint → workspaces.
     */
    @Test
    fun addingAHostThroughTheRealFormSavesAndConnectsForTheFirstTime() {
        awaitTag(HOST_LIST_ADD_TAG)
        capture("01-empty-host-list")

        compose.onNodeWithTag(HOST_LIST_ADD_TAG).performClick()
        awaitTag(HOST_LIST_ADD_METHODS_TAG)
        compose.onNodeWithTag(HOST_LIST_ADD_DETAILS_TAG).performClick()
        awaitTag(HOST_FORM_CONTENT_TAG)
        capture("02-add-host-form")

        // Submit stays enabled on an invalid form by design; the rejection is
        // the per-field errors it paints. Nothing may be written and the user
        // must still be on the form.
        compose.onNodeWithTag(HOST_FORM_SAVE_TAG).performClick()
        awaitAnyText("Required")
        compose.onNodeWithText(CHOOSE_KEY_ERROR).assertIsDisplayed()
        compose.onNodeWithTag(HOST_FORM_CONTENT_TAG).assertIsDisplayed()
        assertEquals("a rejected submit must not write a host row", 0, storedHostCount())
        capture("03-submit-rejected-with-errors")

        // The fixture is not on port 22, and the disclosure starts collapsed
        // for exactly that default — a journey that never opens it cannot save
        // the endpoint it means to dial.
        compose.onNodeWithTag(HOST_FORM_OPTIONS_TAG).performClick()
        awaitTag(HOST_FORM_PORT_TAG)

        typeInto(HOST_FORM_NAME_TAG, HOST_NAME)
        typeInto(HOST_FORM_HOSTNAME_TAG, AgentsFixture.host)
        typeInto(HOST_FORM_USERNAME_TAG, AgentsFixture.USER)
        Espresso.closeSoftKeyboard()
        typeInto(HOST_FORM_PORT_TAG, AgentsFixture.port.toString(), replace = true)
        Espresso.closeSoftKeyboard()

        // Pick the seeded fixture key through the real dropdown.
        compose.onNodeWithText(CHOOSE_LABEL).performClick()
        compose.onNodeWithText(KEY_NAME).performClick()
        capture("04-form-filled")

        compose.onNodeWithTag(HOST_FORM_SAVE_TAG).performClick()

        // Save pops back to the hosts list. The row id is auto-assigned, so
        // the oracle is the persisted Room row — and the rendered row must
        // match it field for field.
        val row = awaitStoredHost()
        assertEquals(HOST_NAME, row.name)
        assertEquals(AgentsFixture.host, row.hostname)
        assertEquals(AgentsFixture.port, row.port)
        assertEquals(AgentsFixture.USER, row.username)
        assertEquals(
            "the form must save with the key chosen in the picker",
            keyId,
            row.keyId,
        )
        assertNull(
            "a freshly added host must not trust a host key yet",
            row.trustedHostKeySha256,
        )

        awaitTag(hostRowTag(row.id))
        capture("05-host-list-with-saved-row")

        // First contact: the dial reaches the real server and comes back with
        // the unknown-key prompt carrying the key it ACTUALLY presents — no
        // previous-fingerprint section, because nothing was trusted before.
        compose.onNodeWithTag(hostRowTag(row.id)).performClick()
        awaitTag(TRUST_SHEET_FINGERPRINT_TAG)
        capture("06-first-contact-trust-prompt")
        pollUntil("displayed fingerprint text") {
            compose.onAllNodesWithText(presentedFingerprint).fetchSemanticsNodes().isNotEmpty() &&
                runCatching { compose.onNodeWithText(presentedFingerprint).assertIsDisplayed() }.isSuccess
        }
        compose.onNodeWithTag(TRUST_SHEET_PREVIOUS_FINGERPRINT_TAG).assertDoesNotExist()

        compose.onNodeWithTag(TRUST_SHEET_TRUST_TAG).performClick()

        // Trust → record → re-dial → authenticated → workspaces render.
        awaitWorkspacesSettled()
        capture("07-workspaces-after-first-connect")
        pollUntil("displayed workspaces tag") {
            compose.onAllNodesWithTag(HOST_WORKSPACES_TAG).fetchSemanticsNodes().isNotEmpty() &&
                runCatching { compose.onNodeWithTag(HOST_WORKSPACES_TAG).assertIsDisplayed() }.isSuccess
        }
        compose.onNodeWithTag(HOST_WORKSPACES_ERROR_TAG).assertDoesNotExist()
        assertEquals(
            "trusting the first-contact prompt must persist the presented key",
            presentedFingerprint,
            storedFingerprint(row.id),
        )
    }

    // --- helpers ----------------------------------------------------------

    /**
     * Click-to-focus, then type (J19's proven idiom). [replace] is for the
     * port, which arrives prefilled with "22" and must be set, not appended to.
     */
    private fun typeInto(tag: String, text: String, replace: Boolean = false) {
        val node = compose.onNodeWithTag(tag)
        runCatching { node.performScrollTo() }
        node.performClick()
        if (replace) node.performTextReplacement(text) else node.performTextInput(text)
    }

    private fun storedHostCount(): Int =
        runBlocking { appGraph().hostDao().getAll().first().size }

    private fun storedFingerprint(hostId: Long): String? =
        runBlocking { appGraph().hostDao().getById(hostId)?.trustedHostKeySha256 }

    /** Polls Room for the row the form was expected to write. */
    private fun awaitStoredHost() =
        pollUntil("the saved host row") {
            runBlocking {
                appGraph().hostDao().getAll().first().firstOrNull { it.name == HOST_NAME }
            }
        }

    private fun awaitWorkspacesSettled() {
        compose.waitUntil(timeoutMillis = TIMEOUT_MS) {
            val hasWorkspaceScreen = compose.onAllNodesWithTag(HOST_WORKSPACES_TAG)
                .fetchSemanticsNodes().isNotEmpty()
            val stillLoading = compose.onAllNodesWithTag(HOST_WORKSPACES_LOADING_TAG)
                .fetchSemanticsNodes().isNotEmpty()
            val hasContent = compose.onAllNodesWithTag(HOST_WORKSPACES_LIST_TAG)
                .fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag(HOST_WORKSPACES_EMPTY_TAG)
                    .fetchSemanticsNodes().isNotEmpty()
            hasWorkspaceScreen && !stillLoading && hasContent
        }
    }

    /**
     * Existence alone is not enough: sheets and nav transitions expose their
     * nodes in the semantics tree a few frames before they are on-screen, so
     * poll the displayed assert instead of asserting once after an
     * existence poll (the J21 run-1 failure: trust sheet mid-animation).
     */
    private fun awaitTag(tag: String) {
        pollUntil("displayed tag '$tag'") {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() &&
                runCatching { compose.onNodeWithTag(tag).assertIsDisplayed() }.isSuccess
        }
    }

    /** For copy that renders multiple times (one "Required" per empty field). */
    private fun awaitAnyText(text: String) {
        pollUntil("text '$text'") {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun <T> pollUntil(what: String, condition: () -> T?): T {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.awaitIdle("j21 poll: $what")
            condition()?.let { return it }
            SystemClock.sleep(POLL_MS)
        }
        val shot = capture("failure-missing-$what".replace('\'', '_').replace(' ', '_'))
        throw AssertionError("$what never appeared within ${TIMEOUT_MS}ms. " +
            "Screenshot: ${shot.absolutePath}")
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
                    println("J21_SCREENSHOT ${target.absolutePath}")
                }
        }
        return file
    }

    private companion object {
        const val JOURNEY = "j21-host-add-connect"
        const val TIMEOUT_MS = 60_000L
        const val POLL_MS = 250L
        const val HOST_NAME = "j21-ui-host"
        const val KEY_NAME = "j21-fixture-key"
        const val CHOOSE_LABEL = "Choose"
        const val CHOOSE_KEY_ERROR = "Choose an SSH key"
    }
}
