package com.pocketshell.next.composer

import android.app.Activity
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.next.MainActivity
import com.pocketshell.next.connect.AgentsFixture
import com.pocketshell.next.connect.JourneyScreenshots
import com.pocketshell.next.connect.SeedBeforeLaunchRule
import com.pocketshell.next.connect.ToxiproxyControl
import com.pocketshell.next.connect.appGraph
import com.pocketshell.next.connect.awaitIdle
import com.pocketshell.next.connect.openQuietSession
import com.pocketshell.next.settings.AppSettings
import com.pocketshell.uikit.components.SESSION_COMPOSER_LAUNCHER_TAG
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.Description
import org.junit.runner.RunWith

/**
 * Journey J20 — the composer's upload progress bar over a REAL multi-file
 * SFTP upload (#2568).
 *
 * ## Why this has to be a device journey
 *
 * `ComposerBarTest` pins the bar's VALUE against a synthetic staging state;
 * this journey proves the thing a unit test cannot see: while three real
 * files are in flight over SFTP to the fixture — composer open, keyboard up,
 * the exact state the maintainer reported — the determinate bar is on screen
 * under the "Uploading N of 3" label, and when the upload finishes it is GONE
 * with no residual track, leaving the staged tiles behind.
 *
 * ## The upload is the app's own production path
 *
 * The system document picker cannot be operated by an instrumented test
 * (J07), so the journey delivers the picker's RESULT instead: it dispatches
 * `RESULT_OK` with three `content://` MediaStore URIs (the exact shape a real
 * SAF pick produces — provider-backed, resolver-readable, grant-flagged) to
 * the launcher key the app registered for
 * [androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments].
 * Everything downstream of the picker is production: `SessionScreen`'s
 * callback, `ComposerViewModel.attach`, the real stager, the real SFTP
 * channel.
 *
 * ## Why the dispatch is staged (the review round's fix)
 *
 * A picker result cannot be dispatched COLD. Activity 1.10.1 (verified in the
 * artifact's bytecode): `ActivityResultRegistry.doDispatch` hands a result to
 * the launcher's callback only when the key is in `launchedKeys` — the state
 * `launch()` puts it in — and otherwise parks the raw `ActivityResult` in
 * `pendingResults`, where teardown's `unregister` logs
 * `Dropping pending result for request …` and throws it away. The first cut of
 * this journey dispatched cold, so `attach()` never ran: silent no-op, no
 * bar, zero bytes, twice (reviewer + fix-round reproduction). Now the journey
 * marks the picker's key launched first, dispatches on the MAIN thread (the
 * registry is `@MainThread`), and asserts the dispatch was CONSUMED — a
 * delivered key is removed from `launchedKeys`, so a silently parked result
 * fails right here instead of at teardown.
 *
 * The link is throttled with a toxiproxy `bandwidth` toxic (`upstream`,
 * rate 1024 ≈ 1 MB/s — measured: 8 MB in 8.4 s, and an unthrottled loopback
 * upload of the same payload ran 0.4 s). 3 × 4 MiB then takes ~12 s instead
 * of ~0.6 s, which a 250 ms poll can actually observe. The toxic goes on in
 * [seed], BEFORE the app's first dial through the proxy — a toxic added after
 * the connection exists left the upload unthrottled in every run so far —
 * and comes off again in a `finally`.
 *
 * The oracle is the host: the three files must exist under the attachment
 * directory with their full byte counts, over an INDEPENDENT SSH connection.
 *
 * Bring the fixture up before running:
 * `docker compose -f tests/docker/docker-compose.yml up -d --build agents network-fault-proxy`
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class J20ComposerUploadProgressJourney {

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain
        .outerRule(HiltAndroidRule(this))
        .around(SeedBeforeLaunchRule { description -> seed(description) })
        .around(compose)

    private var hostId: Long = 0

    private val proxy = ToxiproxyControl()

    private suspend fun seed(description: Description) {
        val graph = appGraph()
        graph.connectionsRegistry().closeAll()
        graph.hostDao().getAll().first().forEach { graph.hostDao().deleteById(it.id) }
        graph.sshKeyDao().getAll().first().forEach { graph.sshKeyDao().deleteById(it.id) }

        proxy.reset()
        check(proxy.state().enabled) { "the network-fault proxy did not come up enabled" }
        // Predates every app dial: the upload's connection is born throttled
        // rather than betting on a toxic reaching an already-open connection.
        addSlowUploadToxic()
        graph.settingsRepository().setAgentSubmitEnterDelayMs(AppSettings.DEFAULT_AGENT_SUBMIT_ENTER_DELAY_MS)
        val fingerprint = AgentsFixture.probeHostKeyFingerprint()
        val proxyPort = ToxiproxyControl.faultSshPortArg()
        println("J20_FIXTURE ${AgentsFixture.host}:$proxyPort direct=${AgentsFixture.port} $fingerprint")

        seedAplexerSession()

        val keyPath = AgentsFixture.installPrivateKey(fileName = "j20_fixture_key")
        val keyId = graph.sshKeyDao().insert(
            SshKeyEntity(name = "j20-${description.methodName}", privateKeyPath = keyPath),
        )
        hostId = HOST_ID
        graph.hostDao().insert(
            HostEntity(
                id = hostId,
                name = "docker-fixture",
                hostname = AgentsFixture.host,
                port = proxyPort,
                username = AgentsFixture.USER,
                keyId = keyId,
                trustedHostKeyAlgorithm = "SHA256",
                trustedHostKeySha256 = fingerprint,
            ),
        )
        graph.composerDraftStore().clear("$hostId/${AgentsFixture.stableSessionId(SESSION)}")
    }

    /** Create a real aplexer session and paint a known prompt and banner. */
    private fun seedAplexerSession() {
        AgentsFixture.exec("pocketshell sessions kill -- '$SESSION' >/dev/null 2>&1 || true")
        AgentsFixture.exec(
            "pocketshell sessions create --cwd '$WORKSPACE' --mem none --json -- '$TAG' >/dev/null",
        )
        AgentsFixture.exec(
            "a send --workspace '$WORKSPACE' --tag '$TAG' --enter " +
                "'PS1=\"$PROMPT \"; clear; echo $BANNER'",
        )
        SystemClock.sleep(500)
        val pane = AgentsFixture.exec(
            "a capture --workspace '$WORKSPACE' --tag '$TAG' --screen --plain 2>/dev/null || true",
        )
        check(pane.filterNot { it.isWhitespace() }.contains(BANNER)) {
            "the fixture aplexer session did not come up: a capture says\n$pane"
        }
    }

    /**
     * Three real files upload over SFTP through the app's own attach path:
     * the determinate bar rides under the staging label mid-flight and leaves
     * no residue when the upload completes.
     */
    @Test
    fun aThreeFileUploadShowsTheBarMidFlightAndLeavesNoResidueAfterCompletion() {
        openSession()

        openComposer()
        // The reported scenario: keyboard up while the upload runs.
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG).performTextInput(DRAFT_TEXT)
        compose.awaitIdle("after composing the draft with the keyboard up")

        val picks = pickFiles()
        try {
            // The throttling toxic is already on the proxy from seed().
            try {
                dispatchPickerResult(buildPickIntent(picks))
                awaitTagVisible(COMPOSER_STAGING_PROGRESS_TAG, "the upload bar mid-flight")
                compose.onNodeWithTag(COMPOSER_STAGING_TAG).assertIsDisplayed()
                logBarSemantics("mid-flight")
                preserveEvidence(
                    JourneyScreenshots.capture("01-upload-mid-flight", JOURNEY),
                    "01-upload-mid-flight",
                )

                awaitTagGone(COMPOSER_STAGING_PROGRESS_TAG, "the upload bar after completion")
                compose.onNodeWithTag(COMPOSER_ATTACHMENTS_TAG).assertIsDisplayed()
                preserveEvidence(
                    JourneyScreenshots.capture("02-upload-completed", JOURNEY),
                    "02-upload-completed",
                )
            } finally {
                removeSlowUploadToxic()
            }

            // The host really has the bytes: three files, full sizes, independent
            // SSH connection (not through the throttled proxy).
            val onHost = AgentsFixture.exec(
                "cat \$(find ~/.pocketshell/attachments -name '*$PAYLOAD_STEM*' 2>/dev/null) | wc -c",
            ).trim()
            assertEquals(
                "the host must hold all three uploaded payloads in full",
                (PAYLOAD_BYTES * FILE_COUNT).toString(),
                onHost,
            )
        } finally {
            deletePicks(picks)
        }
    }

    // --- helpers ----------------------------------------------------------

    private fun openSession() {
        compose.openQuietSession(hostId, SESSION, WORKSPACE, TIMEOUT_MS)
    }

    private fun openComposer() {
        awaitTagVisible(SESSION_COMPOSER_LAUNCHER_TAG, "the Prompt Composer launcher")
        compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG).performClick()
        awaitTagVisible(COMPOSER_TAG, "the Prompt Composer sheet")
    }

    /**
     * Three multi-megabyte payloads as production picker picks: `content://`
     * URIs backed by a real provider (MediaStore), the shape SAF actually
     * returns — not `file://` paths, which no production picker produces and
     * which would let the journey certify a read path production never
     * exercises.
     */
    private fun pickFiles(): List<Uri> {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        return List(FILE_COUNT) { n ->
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "$PAYLOAD_STEM-$n.bin")
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore refused the j20 payload insert")
            resolver.openOutputStream(uri)!!.use { out ->
                out.write(ByteArray(PAYLOAD_BYTES) { i -> ((i + n) % 251).toByte() })
            }
            uri
        }
    }

    /** The picks live in shared storage; give them back when the test is done. */
    private fun deletePicks(picks: List<Uri>) {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        picks.forEach { uri -> runCatching { resolver.delete(uri, null, null) } }
    }

    /** The intent shape `OpenMultipleDocuments.parseResult` turns into URIs. */
    private fun buildPickIntent(uris: List<Uri>): Intent = Intent().apply {
        clipData = ClipData.newRawUri("j20-upload", uris.first()).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /**
     * Delivers the picker RESULT the way the system would: `RESULT_OK` plus
     * the clip-data intent, through [ActivityResultRegistry.dispatchResult] —
     * the same entry point a real picker result arrives on, so the registered
     * `OpenMultipleDocuments` contract runs its own `parseResult` before the
     * app's callback sees the URIs.
     *
     * The registry is `@MainThread`, so the whole lookup-and-dispatch runs on
     * main. And per 1.10.1's `doDispatch` (see the class KDoc), a result for a
     * key outside `launchedKeys` is parked, never delivered — so the journey
     * first puts the picker's key into the state `launch()` leaves it in, then
     * asserts the dispatch CONSUMED the key: delivery is the only path that
     * removes it. A parked result therefore fails right here with this
     * message, not as a silent 60 s timeout.
     */
    private fun dispatchPickerResult(intent: Intent) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val registry = compose.activity.activityResultRegistry
            fun field(name: String) = ActivityResultRegistry::class.java
                .getDeclaredField(name)
                .apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val keyToCallback = field("keyToCallback").get(registry) as Map<String, Any>
            @Suppress("UNCHECKED_CAST")
            val keyToRc = field("keyToRc").get(registry) as Map<String, Int>
            @Suppress("UNCHECKED_CAST")
            val launchedKeys = field("launchedKeys").get(registry) as MutableList<String>
            val contractGetter = Class
                .forName("androidx.activity.result.ActivityResultRegistry\$CallbackAndContract")
                .getMethod("getContract")
            val pickerKey = keyToCallback.entries
                .firstOrNull { contractGetter.invoke(it.value) is ActivityResultContracts.OpenMultipleDocuments }
                ?.key
                ?: error(
                    "no OpenMultipleDocuments launcher is registered — the session " +
                        "screen's attach picker never composed; registered keys: " +
                        keyToCallback.keys,
                )
            val requestCode = keyToRc[pickerKey]
                ?: error("the picker registered under '$pickerKey' has no request code")
            if (pickerKey !in launchedKeys) launchedKeys.add(pickerKey)
            check(registry.dispatchResult(requestCode, Activity.RESULT_OK, intent)) {
                "the picker ('$pickerKey', rc=$requestCode) rejected the dispatched result"
            }
            check(pickerKey !in launchedKeys) {
                "the picker result was PARKED, not delivered (request key still in " +
                    "launchedKeys, callback never ran) — attach() never ran"
            }
        }
    }

    /**
     * The bar's rendered fraction, into the log the reviewer keeps: a device
     * screenshot proves pixels; this line pins WHAT the bar rendered at
     * capture time in a form that survives anywhere logcat does. Since #2686
     * the fraction moves within the current file, so mid-flight it is a
     * determinate value inside (fileIndex-1)/3..fileIndex/3 rather than the
     * whole-file 1/3 #2568 logged.
     */
    private fun logBarSemantics(phase: String) {
        val range = compose.onNodeWithTag(COMPOSER_STAGING_PROGRESS_TAG)
            .fetchSemanticsNode()
            .config[SemanticsProperties.ProgressBarRangeInfo]
        println("J20_BAR_SEMANTICS[$phase] rangeInfo=$range")
    }

    /**
     * AGP uninstalls the app after a run, and the uninstall deletes
     * `getExternalFilesDir` — [JourneyScreenshots]' home — which is how the
     * review round ended up with no journey-side frames. Every capture is
     * therefore also copied into shared MediaStore storage, which the
     * uninstall cannot touch: pull from `/sdcard/Download/i2568-*.png` on the
     * host.
     */
    private fun preserveEvidence(shot: File, name: String) {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "i2568-$name.png")
            put(MediaStore.Downloads.MIME_TYPE, "image/png")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            println("J20_EVIDENCE_PRESERVE_FAILED $name: MediaStore refused the insert")
            return
        }
        runCatching {
            resolver.openOutputStream(uri)!!.use { out ->
                shot.inputStream().use { it.copyTo(out) }
            }
        }.onSuccess { println("J20_EVIDENCE_PRESERVED $uri") }
            .onFailure { println("J20_EVIDENCE_PRESERVE_FAILED $name: $it") }
    }

    /**
     * ~1 MB/s upstream, stretching 12 MiB of upload to ~12 observable seconds
     * (toxiproxy 2.9 bandwidth `rate` is KB/s — verified by direct measurement).
     */
    private fun addSlowUploadToxic() {
        toxiproxy(
            "POST",
            "/proxies/$TOXIC_PROXY/toxics",
            """{"name":"$TOXIC_NAME","type":"bandwidth","stream":"upstream",""" +
                """"toxicity":1.0,"attributes":{"rate":1024}}""",
        )
    }

    private fun removeSlowUploadToxic() {
        toxiproxy("DELETE", "/proxies/$TOXIC_PROXY/toxics/$TOXIC_NAME", null)
    }

    /** Raw toxiproxy control call — same host/port the app's own helper uses. */
    private fun toxiproxy(method: String, path: String, body: String?): String {
        val connection = URL("http://${ToxiproxyControl.API_HOST}:${ToxiproxyControl.apiPortArg()}$path")
            .openConnection() as HttpURLConnection
        connection.requestMethod = method
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray()) }
        }
        val response = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()
        return response
    }

    private fun awaitTagVisible(tag: String, what: String) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.awaitIdle("tag poll: $what")
            if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) return
            SystemClock.sleep(POLL_MS)
        }
        val shot = JourneyScreenshots.capture("failure-${what.replace(' ', '-')}", JOURNEY)
        throw AssertionError("$what never appeared within ${TIMEOUT_MS}ms. Screenshot: ${shot.absolutePath}")
    }

    private fun awaitTagGone(tag: String, what: String) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.awaitIdle("tag poll: $what")
            if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()) return
            SystemClock.sleep(POLL_MS)
        }
        val shot = JourneyScreenshots.capture("failure-${what.replace(' ', '-')}", JOURNEY)
        throw AssertionError("$what never left within ${TIMEOUT_MS}ms. Screenshot: ${shot.absolutePath}")
    }

    private companion object {
        const val TIMEOUT_MS = 60_000L
        const val POLL_MS = 250L
        const val JOURNEY = "j20-composer-upload-progress"

        const val TAG = "j20-upload"
        const val SESSION = "testuser:j20-upload"
        const val WORKSPACE = "/home/testuser"
        const val HOST_ID = 9_705L

        const val PROMPT = "J20READY$"
        const val BANNER = "J20-FIXTURE-PANE"
        const val DRAFT_TEXT = "attaching three files"

        const val FILE_COUNT = 3
        const val PAYLOAD_BYTES = 4 * 1024 * 1024
        const val PAYLOAD_STEM = "j20-payload"

        /** Mirrors [ToxiproxyControl.PROXY_NAME] (private there). */
        const val TOXIC_PROXY = "agents_ssh"
        const val TOXIC_NAME = "i2568_slow_upload"
    }
}
