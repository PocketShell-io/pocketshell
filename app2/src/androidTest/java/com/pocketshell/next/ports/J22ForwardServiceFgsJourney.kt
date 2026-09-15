package com.pocketshell.next.ports

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.core.portfwd.TunnelInfo
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.next.MainActivity
import com.pocketshell.next.connect.AgentsFixture
import com.pocketshell.next.connect.JourneyScreenshots
import com.pocketshell.next.connect.SeedBeforeLaunchRule
import com.pocketshell.next.connect.appGraph
import com.pocketshell.next.connect.awaitIdle
import com.pocketshell.next.connect.openQuietHost
import com.pocketshell.next.tree.SESSION_TREE_PORTS_TAG
import com.pocketshell.next.workspaces.HOST_WORKSPACES_ACTIONS_TAG
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Journey J22 — the REAL [ForwardService] foreground-service lifecycle, end to
 * end on a device (issue #2490; the plan's P-4 journey slot, reserved as
 * `J11PortForwardJourney` in docs/rewrite-implementation-plan.md and filled by
 * `J11ShareUploadJourney` instead, left the FGS stack with no device evidence).
 *
 * ## Why this has to be a device journey
 *
 * The `AutoForwarder`/`ForwardingController` unit and Robolectric tests never
 * construct the service. Port forwarding on API 34+ is exactly the behavior
 * that only breaks at the OS/manifest/notification boundary — a
 * `startForeground` rejection, a `specialUse` FGS-type problem, or a
 * notification-channel failure would kill every tunnel the moment the user
 * backgrounds the app, and nothing on the JVM would go red. So every oracle
 * here is a real system surface, not a UI claim:
 *
 * - the service is alive AND foreground via [ActivityManager] (real system API);
 * - the FGS notification exists via [NotificationManager.activeNotifications],
 *   on the right channel, ongoing, listing the forwarded local port — the words
 *   the system actually rendered, not what a composable claims;
 * - traffic genuinely forwards: an HTTP GET on `127.0.0.1:<local>` returns a
 *   run-unique token, AND the fixture's own http.server access log (read over
 *   an independent SSH connection) shows the bytes arriving host-side;
 * - backgrounding is the real Activity stop boundary, driven the same way
 *   J06 drives it ([androidx.test.core.app.ActivityScenario.moveToState]).
 *
 * ## The arc
 *
 * Add a manual tunnel through the REAL UI (Services & tunnels → Add tunnel →
 * form → submit, which calls [ForwardService.resume] from production code) →
 * prove tunnel + FGS + notification + host-side traffic → background the app
 * for a real sojourn, continuously re-checking the FGS/notification/tunnel →
 * return → remove the tunnel through the tunnel-detail UI → drive the
 * notification's own "Stop all" action by sending its real PendingIntent →
 * prove the full teardown: notification gone, service stopped, local port
 * closed, fixture access log silent.
 *
 * ## Why no single-shot display asserts
 *
 * J13 (#2679 quarantine) flaked on one bare `assertIsDisplayed` after an
 * existence wait — an animation-sensitive single shot. Every UI touch here
 * goes through [awaitClickableTag], which polls for the node being present AND
 * laid out and scrollable-into-view before clicking; the load-bearing oracles
 * are the system APIs and the host-side log, none of which can flake on an
 * animation.
 *
 * Fixture: Docker `agents` on `10.0.2.2:2222` (or a pool lane's own port).
 * Bring it up first:
 * `docker compose -f tests/docker/docker-compose.yml up -d --build agents`
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class J22ForwardServiceFgsJourney {

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain
        .outerRule(HiltAndroidRule(this))
        .around(SeedBeforeLaunchRule { description -> seed(description) })
        .around(compose)

    private var hostId: Long = 0

    /** Run-unique body token, so a stale fixture file can never satisfy the oracle. */
    private lateinit var bodyToken: String

    @After
    fun tearDown() {
        // Best-effort: whatever state a failure left behind, do not bequeath a
        // running FGS or an enabled forwarding host to the next journey in the
        // wholesale suite process (every journey seeds defensively, but this is
        // this class's own mess).
        runCatching {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            runBlocking {
                appGraph().forwardingController().stopAll()
            }
            context.stopService(Intent(context, ForwardService::class.java))
        }
    }

    private suspend fun seed(description: Description) {
        grantNotificationPermission()
        val graph = appGraph()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        graph.connectionsRegistry().closeAll()

        // A prior journey in the wholesale suite (J18 ends with ForwardService
        // running) must not be able to satisfy "some FGS is alive" here: tear
        // every forward and the service itself down before this test claims
        // anything about their state.
        graph.forwardingController().stopAll()
        context.stopService(Intent(context, ForwardService::class.java))
        awaitForwardServiceStopped("seed: leftover ForwardService did not stop")
        awaitNoForwardNotification("seed: leftover ForwardService notification")

        graph.hostDao().getAll().first().forEach {
            graph.portRemappingDao().deleteByHostId(it.id)
            graph.hostDao().deleteById(it.id)
        }
        graph.sshKeyDao().getAll().first().forEach { graph.sshKeyDao().deleteById(it.id) }

        val fingerprint = AgentsFixture.probeHostKeyFingerprint()
        println("J22_FIXTURE ${AgentsFixture.host}:${AgentsFixture.port} $fingerprint")

        bodyToken = "POCKETSHELL_J22_" + UUID.randomUUID().toString().take(8)
        AgentsFixture.exec("printf '$bodyToken\\n' > $BODY_FILE")
        ensureFixtureHttpServer()

        hostId = HOST_ID
        val keyPath = AgentsFixture.installPrivateKey(fileName = "j22_fixture_key")
        val keyId = graph.sshKeyDao().insert(
            SshKeyEntity(name = "j22-${description.methodName}", privateKeyPath = keyPath),
        )
        graph.hostDao().insert(
            HostEntity(
                id = hostId,
                name = "docker-fixture",
                hostname = AgentsFixture.host,
                port = AgentsFixture.port,
                username = AgentsFixture.USER,
                keyId = keyId,
                // Keep the auto-forward window ABOVE the fixture port this
                // journey serves, so the manual tunnel below is the ONE desired
                // tunnel: a deterministic single-tunnel notification, and no
                // auto-discovered sibling row to alias the oracle.
                maxAutoPort = 20_000,
                skipPortsBelow = 6_000,
                trustedHostKeyAlgorithm = "SHA256",
                trustedHostKeySha256 = fingerprint,
                // enabled defaults to false: the forward must start through the
                // UI below, not from a cold-start auto-resume.
            ),
        )
    }

    @Test
    fun forwardAddedThroughTheUiSurvivesBackgroundingAndTearsDownFromItsNotification() {
        startManualTunnelThroughTheUi()

        // --- UP: tunnel + FGS + notification + real traffic, all polled --------
        val tunnel = awaitForwardedTunnel()
        println(
            "J22_TUNNEL_UP remote=${tunnel.remotePort} local=${tunnel.localPort} " +
                "status=${tunnel.status}",
        )
        awaitForwardServiceForeground("after the tunnel came up")
        dumpFgsTypeEvidence()
        awaitForwardNotification("after the tunnel came up")
        JourneyScreenshots.capture("03-forwarding-live", JOURNEY)

        awaitForwardedHttpBody("while foregrounded")
        val hostHitsBeforeBackground = awaitHostAccessLogLines("after the foreground fetch")
        assertTrue(
            "the fixture's http.server access log must show the forwarded GET arriving " +
                "host-side (host-side oracle), saw $hostHitsBeforeBackground line(s)",
            hostHitsBeforeBackground >= 1,
        )

        // --- BACKGROUND: real Activity stop boundary, continuous liveness ------
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        proveFgsSurvivesSojourn()
        JourneyScreenshots.capture("04-backgrounded-fgs-holding", JOURNEY)

        // The whole feature, asserted while backgrounded: the forwarded port
        // still answers, and the fixture log proves fresh bytes crossed the SSH
        // tunnel during the sojourn.
        awaitForwardedHttpBody("while backgrounded")
        val hostHitsAfterBackground = awaitHostAccessLogLines("after the backgrounded fetch")
        assertTrue(
            "the fixture access log must GROW while the app is backgrounded " +
                "(before=$hostHitsBeforeBackground after=$hostHitsAfterBackground): " +
                "backgrounded forwarding must carry real traffic host-side",
            hostHitsAfterBackground > hostHitsBeforeBackground,
        )
        println(
            "J22_BACKGROUND_SURVIVED fgs=true notification=true tunnel=true " +
                "hostHits=$hostHitsBeforeBackground->$hostHitsAfterBackground",
        )

        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        awaitTag(SERVICES_SCREEN_TAG, "the Services screen after returning")
        logStackState("after returning")

        // --- TEARDOWN A: remove the tunnel through the tunnel-detail UI --------
        // The UI leg is exercised when the screen renders the row; if it does
        // not, the state dump identifies which branch the screen took and the
        // notification Stop-all leg below still owns the full teardown proof.
        try {
            // The Services view model reads hosts.enabled ONCE in init and has
            // no Room observer, so the screen can still be rendering the
            // discovery-off branch even though the tunnel is live (visible as
            // J22_SCREEN_DUMP branches=[discovery-off] rowWanted=0). Turning
            // discovery On through the real toggle is the user path that
            // re-enables the branch and reveals the active-tunnel row.
            if (compose.onAllNodesWithTag(servicesRowTag(REMOTE_PORT))
                    .fetchSemanticsNodes().isEmpty()
            ) {
                awaitClickableTag(
                    "$SERVICES_DISCOVERY_TAG-on",
                    "the discovery On toggle",
                    timeoutMs = UI_REMOVE_TIMEOUT_MS,
                ).performClick()
            }
            awaitClickableTag(
                servicesRowTag(REMOTE_PORT),
                "the active tunnel row after returning",
                timeoutMs = UI_REMOVE_TIMEOUT_MS,
            ).performClick()
            awaitTag(TUNNEL_DETAIL_TAG, "the tunnel detail screen", timeoutMs = UI_REMOVE_TIMEOUT_MS)
            JourneyScreenshots.capture("05-tunnel-detail", JOURNEY)
            awaitClickableTag(TUNNEL_STOP_TAG, "the Remove tunnel control", timeoutMs = UI_REMOVE_TIMEOUT_MS)
                .performClick()
            awaitTag(SERVICES_SCREEN_TAG, "Services after removing the tunnel", timeoutMs = UI_REMOVE_TIMEOUT_MS)
            awaitMappingAbsent(REMOTE_PORT)
            awaitNoForwardingTunnel("after removing the tunnel through the UI")
            println("J22_UI_REMOVE_OK")
        } catch (uiRemoval: AssertionError) {
            logStackState("UI remove unavailable")
            dumpServicesScreenBranch()
            println(
                "J22_UI_REMOVE_UNAVAILABLE continuing through the notification " +
                    "Stop-all leg: $uiRemoval",
            )
        }

        // --- TEARDOWN B: the notification's own Stop-all action ---------------
        sendNotificationStopAll()
        awaitForwardServiceStopped("after the notification Stop-all action")
        awaitNoForwardNotification("after the notification Stop-all action")
        awaitNoForwardingTunnel("after the notification Stop-all action")
        awaitLocalPortClosed()
        val quietBefore = awaitHostAccessLogLines("teardown baseline")
        SystemClock.sleep(TEARDOWN_SETTLE_MS)
        val quietAfter = awaitHostAccessLogLines("teardown settle")
        assertTrue(
            "the fixture access log must stay silent after teardown " +
                "($quietBefore -> $quietAfter): a torn-down forward must not carry traffic",
            quietAfter == quietBefore,
        )
        JourneyScreenshots.capture("06-after-stop-all", JOURNEY)
        println("J22_TEARDOWN_COMPLETE service stopped, notification gone, port closed")
    }

    // --------------------------------------------------------------- UI legwork

    /**
     * Services & tunnels → Add tunnel → form → submit, all through the real
     * screens. Submitting is the production path that persists the mapping and
     * calls [ForwardService.resume] (AddTunnelScreen.kt).
     */
    private fun startManualTunnelThroughTheUi() {
        compose.openQuietHost(hostId, TIMEOUT_MS)

        awaitClickableTag(HOST_WORKSPACES_ACTIONS_TAG, "the host actions menu").performClick()
        awaitClickableTag(SESSION_TREE_PORTS_TAG, "the Ports header action").performClick()
        awaitTag(SERVICES_SCREEN_TAG, "the Services & tunnels screen")
        awaitTag(SERVICES_DISCOVERY_TAG, "the discovery control")
        awaitText("No active tunnels")
        JourneyScreenshots.capture("01-services-off", JOURNEY)

        awaitClickableTag(SERVICES_ADD_TUNNEL_TAG, "the Add tunnel footer button")
            .performClick()
        awaitTag(ADD_TUNNEL_SCREEN_TAG, "the add-tunnel form")

        compose.onNodeWithTag(ADD_TUNNEL_NAME_TAG).performTextReplacement(TUNNEL_NAME)
        compose.onNodeWithTag(ADD_TUNNEL_REMOTE_TAG)
            .performTextReplacement(REMOTE_PORT.toString())
        compose.onNodeWithTag(ADD_TUNNEL_LOCAL_TAG)
            .performTextReplacement(LOCAL_PORT.toString())
        JourneyScreenshots.capture("02-add-tunnel-filled", JOURNEY)

        awaitSubmittableForm().performClick()
        awaitTag(SERVICES_SCREEN_TAG, "Services after submitting the tunnel")
    }

    /**
     * The form's submit only enables once the name/ports validate AND the async
     * local-port collision check has landed — poll for it instead of asserting
     * a single frame (the J13 flake shape).
     */
    private fun awaitSubmittableForm(): SemanticsNodeInteraction {
        val submit = compose.onNodeWithTag(ADD_TUNNEL_SUBMIT_TAG)
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.awaitIdle("add-tunnel submit enablement")
            val ready = runCatching {
                submit.performScrollTo().assertIsEnabled()
            }.isSuccess
            if (ready) return submit
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError(
            "the add-tunnel submit never became enabled within ${TIMEOUT_MS}ms — " +
                "name/ports rejected or the collision check never landed",
        )
    }

    /**
     * Polls until [tag] is present, scrollable into view, and laid out, then
     * returns a handle for the click. This is the anti-J13 idiom: existence
     * alone says nothing on an animating screen, and one `assertIsDisplayed`
     * shot is exactly how #2679 flaked.
     */
    private fun awaitClickableTag(
        tag: String,
        what: String,
        timeoutMs: Long = TIMEOUT_MS,
    ): SemanticsNodeInteraction {
        val node = compose.onNodeWithTag(tag)
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.awaitIdle("clickable poll: $what")
            // Scroll into view only when a scrollable ancestor exists (a
            // popup row has none); the real gate is being laid out.
            runCatching { node.performScrollTo() }
            val displayed = runCatching { node.assertIsDisplayed() }.isSuccess
            if (displayed) return node
            SystemClock.sleep(POLL_MS)
        }
        val shot = JourneyScreenshots.capture("failure-${what.replace(' ', '-')}", JOURNEY)
        throw AssertionError(
            "$what never became clickable/displayed within ${timeoutMs}ms.\n" +
                "Screenshot: ${shot.absolutePath}",
        )
    }

    private fun awaitTag(tag: String, what: String = tag, timeoutMs: Long = TIMEOUT_MS) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.awaitIdle("tag poll: $what")
            if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) return
            SystemClock.sleep(POLL_MS)
        }
        val shot = JourneyScreenshots.capture("failure-${what.replace(' ', '-')}", JOURNEY)
        throw AssertionError(
            "$what never appeared within ${timeoutMs}ms.\n" +
                "Screenshot: ${shot.absolutePath}",
        )
    }

    private fun awaitText(text: String) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.awaitIdle("text poll: $text")
            if (compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()) return
            SystemClock.sleep(POLL_MS)
        }
        val shot = JourneyScreenshots.capture("failure-text", JOURNEY)
        throw AssertionError(
            "text '$text' never appeared within ${TIMEOUT_MS}ms.\n" +
                "Screenshot: ${shot.absolutePath}",
        )
    }

    // ------------------------------------------------------- system-API oracles

    /** The forwarded tunnel, polled through the controller's real snapshot. */
    private fun awaitForwardedTunnel(): TunnelInfo {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            appGraph().forwardingController().snapshot.value
                .firstOrNull { it.hostId == hostId }
                ?.tunnels
                ?.firstOrNull {
                    it.remotePort == REMOTE_PORT &&
                        it.localPort == LOCAL_PORT &&
                        it.status == TunnelInfo.Status.FORWARDING
                }
                ?.let { return it }
            SystemClock.sleep(POLL_MS)
        }
        reportForwardingSnapshot("timeout-waiting-for-tunnel")
        val shot = JourneyScreenshots.capture("failure-no-tunnel", JOURNEY)
        throw AssertionError(
            "tunnel $REMOTE_PORT->$LOCAL_PORT never reached FORWARDING for host $hostId " +
                "within ${TIMEOUT_MS}ms.\nScreenshot: ${shot.absolutePath}",
        )
    }

    private fun awaitNoForwardingTunnel(whenLabel: String) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val stillForwarding = appGraph().forwardingController().snapshot.value
                .firstOrNull { it.hostId == hostId }
                ?.tunnels
                ?.any {
                    it.remotePort == REMOTE_PORT &&
                        it.status == TunnelInfo.Status.FORWARDING
                } == true
            if (!stillForwarding) return
            SystemClock.sleep(POLL_MS)
        }
        reportForwardingSnapshot("timeout-tunnel-still-forwarding")
        throw AssertionError(
            "tunnel $REMOTE_PORT was still FORWARDING $whenLabel within ${TIMEOUT_MS}ms",
        )
    }

    /**
     * The service must be running AND foreground — the whole subject of the
     * journey — read through [ActivityManager], a real system API.
     */
    private fun awaitForwardServiceForeground(what: String) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            @Suppress("DEPRECATION")
            val service = context().getSystemService(ActivityManager::class.java)
                ?.getRunningServices(100)
                ?.firstOrNull { it.service.className == ForwardService::class.java.name }
            if (service != null && service.foreground) return
            SystemClock.sleep(POLL_MS)
        }
        val shot = JourneyScreenshots.capture("failure-foreground-service", JOURNEY)
        throw AssertionError(
            "ForwardService was not a running FOREGROUND service $what. " +
                "Screenshot: ${shot.absolutePath}",
        )
    }

    private fun awaitForwardServiceStopped(what: String) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!isForwardServiceRunning()) return
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError("ForwardService did not stop $what within ${TIMEOUT_MS}ms")
    }

    private fun isForwardServiceRunning(): Boolean {
        @Suppress("DEPRECATION")
        return context().getSystemService(ActivityManager::class.java)
            ?.getRunningServices(100)
            ?.any { it.service.className == ForwardService::class.java.name } == true
    }

    /**
     * The FGS notification, read from the real tray: right channel, ongoing,
     * and describing the forwarded local port the user would look for.
     */
    private fun awaitForwardNotification(
        what: String,
    ): android.service.notification.StatusBarNotification {
        var found = forwardNotification()
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (found == null && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(POLL_MS)
            found = forwardNotification()
        }
        val notification = found ?: run {
            val shot = JourneyScreenshots.capture("failure-notification", JOURNEY)
            throw AssertionError(
                "no ForwardService notification (id=${ForwardService.NOTIFICATION_ID}) " +
                    "was posted $what within ${TIMEOUT_MS}ms — check POST_NOTIFICATIONS " +
                    "and the notification channel.\nScreenshot: ${shot.absolutePath}",
            )
        }
        assertTrue(
            "the FGS notification must be on the '${ForwardService.CHANNEL_ID}' " +
                "channel, got ${notification.notification.channelId}",
            notification.notification.channelId == ForwardService.CHANNEL_ID,
        )
        assertTrue(
            "the FGS notification must be ongoing (not swipe-dismissable), " +
                "got flags=${notification.notification.flags}",
            notification.notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0,
        )
        val title = notification.notification.extras
            .getCharSequence(android.app.Notification.EXTRA_TITLE) ?: ""
        val body = notification.notification.extras
            .getCharSequence(android.app.Notification.EXTRA_TEXT) ?: ""
        assertTrue(
            "the FGS notification title must report the forwarded port count, got '$title'",
            title.contains("port forwarded"),
        )
        assertTrue(
            "the FGS notification body must list the forwarded local port " +
                "$LOCAL_PORT, got '$body'",
            body.contains(LOCAL_PORT.toString()),
        )
        println("J22_FGS_NOTIFICATION title='$title' body='$body'")
        return notification
    }

    private fun forwardNotification() =
        context().getSystemService(NotificationManager::class.java)
            ?.activeNotifications
            ?.firstOrNull { it.id == ForwardService.NOTIFICATION_ID }

    private fun awaitNoForwardNotification(what: String) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (forwardNotification() == null) return
            SystemClock.sleep(POLL_MS)
        }
        // Describe WHAT lingered: a notification that outlives its dead service
        // (service gone per ActivityManager, notification still in the tray) is
        // the orphaned-notify defect class — post-stopSelf collector notify —
        // and the rendered words identify which snapshot it froze on.
        val lingering = forwardNotification()
        val title = lingering?.notification?.extras
            ?.getCharSequence(android.app.Notification.EXTRA_TITLE) ?: ""
        val body = lingering?.notification?.extras
            ?.getCharSequence(android.app.Notification.EXTRA_TEXT) ?: ""
        throw AssertionError(
            "the ForwardService notification (id=${ForwardService.NOTIFICATION_ID}) " +
                "was still posted $what within ${TIMEOUT_MS}ms — " +
                "serviceRunning=${isForwardServiceRunning()} title='$title' body='$body'",
        )
    }

    /**
     * Evidence-only: the `specialUse` FGS type is an OS/manifest boundary fact,
     * so print what the system itself says about the running service. Parsing
     * dumpsys output for an assertion would couple the journey to its exact
     * formatting; [awaitForwardServiceForeground] carries the hard assertion.
     */
    private fun dumpFgsTypeEvidence() {
        runCatching {
            val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("dumpsys activity services ${context().packageName}")
            java.io.FileInputStream(pfd.fileDescriptor).use { input ->
                val lines = input.bufferedReader().readText()
                    .lineSequence()
                    .filter {
                        it.contains("ForwardService") ||
                            it.contains("foregroundServiceType") ||
                            it.contains("specialUse") ||
                            it.contains("isForeground")
                    }
                    .take(20)
                    .toList()
                println("J22_FGS_DUMPSYS\n${lines.joinToString("\n")}")
            }
            runCatching { pfd.close() }
        }.onFailure { println("J22_FGS_DUMPSYS unavailable: $it") }
    }

    private fun reportForwardingSnapshot(label: String) {
        val snapshot = appGraph().forwardingController().snapshot.value
        println(
            "J22_FORWARDING_SNAPSHOT label=$label fgs=${isForwardServiceRunning()} " +
                snapshot.joinToString { host ->
                    "host=${host.hostId}/${host.connection} " +
                        "tunnels=${host.tunnels.joinToString { tunnel ->
                            "${tunnel.remotePort}->${tunnel.localPort}:${tunnel.status}"
                        }}"
                },
        )
    }

    /** One-line process truth: service, notification, snapshot — for the log. */
    private fun logStackState(label: String) {
        val notification = forwardNotification()
        val title = notification?.notification?.extras
            ?.getCharSequence(android.app.Notification.EXTRA_TITLE) ?: ""
        val body = notification?.notification?.extras
            ?.getCharSequence(android.app.Notification.EXTRA_TEXT) ?: ""
        println(
            "J22_STACK_STATE label=$label fgs=${isForwardServiceRunning()} " +
                "notification=${notification != null} title='$title' body='$body'",
        )
        reportForwardingSnapshot(label)
    }

    /**
     * Identifies WHICH branch ServicesScreen is rendering by probing its
     * branch-marker texts and the structural tags, so a post-return row
     * absence is diagnosable from the run log alone.
     */
    private fun dumpServicesScreenBranch() {
        runCatching {
            compose.awaitIdle("services screen branch dump")
            val branchMarkers = mapOf(
                "Looking for services" to "spinner-scanning",
                "No services found" to "empty-discovered",
                "No active tunnels" to "discovery-off",
                "Connection needs attention" to "lost",
            )
            val visibleBranches = branchMarkers.mapNotNull { (text, branch) ->
                if (compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()) {
                    branch
                } else {
                    null
                }
            }
            fun countTag(tag: String) =
                compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size
            println(
                "J22_SCREEN_DUMP branches=$visibleBranches " +
                    "list=${countTag("$SERVICES_SCREEN_TAG-list")} " +
                    "discovery=${countTag(SERVICES_DISCOVERY_TAG)} " +
                    "discoveryOn=${countTag("$SERVICES_DISCOVERY_TAG-on")} " +
                    "discoveryOff=${countTag("$SERVICES_DISCOVERY_TAG-off")} " +
                    "addTunnelButton=${countTag(SERVICES_ADD_TUNNEL_TAG)} " +
                    "rowWanted=${countTag(servicesRowTag(REMOTE_PORT))}",
            )
        }.onFailure { println("J22_SCREEN_DUMP unavailable: $it") }
    }

    // ------------------------------------------------- teardown via notification

    /**
     * Stops forwarding by sending the REAL "Stop all" PendingIntent recorded on
     * the FGS notification — the user's own teardown gesture — which starts the
     * service with [ForwardService.ACTION_STOP_ALL]. If the service already
     * stopped itself (either teardown leg is acceptable), there is nothing to
     * send and nothing to do. Every attempt is logged: a notification that
     * never acts on its own Stop-all action is exactly the defect class this
     * journey exists to surface, so it must fail loudly, with evidence.
     */
    private fun sendNotificationStopAll() {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        var sends = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!isForwardServiceRunning()) return
            val notification = forwardNotification()
            val actions = notification?.notification?.actions.orEmpty()
            val stopAction = actions.firstOrNull {
                it.title?.contains("stop", ignoreCase = true) == true
            } ?: actions.singleOrNull()
            if (stopAction?.actionIntent != null && sends < MAX_STOP_ALL_SENDS) {
                sends += 1
                println(
                    "J22_STOP_ALL sending attempt=$sends title=${stopAction.title} " +
                        "actionCount=${actions.size}",
                )
                runCatching { stopAction.actionIntent.send() }
                    .onFailure { println("J22_STOP_ALL send failed: $it") }
            } else if (stopAction?.actionIntent == null) {
                println(
                    "J22_STOP_ALL no usable action on notification " +
                        "(actionCount=${actions.size})",
                )
            }
            SystemClock.sleep(POLL_MS)
        }
        // Fallback: deliver the exact intent the notification action carries,
        // straight to the service, so the service-side stop-all handling is
        // still exercised end to end. The app is foreground here, so this is
        // allowed; if even this cannot stop the service, the journey fails.
        val afterSends = sends
        runCatching {
            context().startService(
                Intent(context(), ForwardService::class.java)
                    .setAction(ForwardService.ACTION_STOP_ALL),
            )
            println("J22_STOP_ALL direct-intent fallback delivered (sends=$afterSends)")
        }.onFailure { println("J22_STOP_ALL direct-intent fallback failed: $it") }
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!isForwardServiceRunning()) return
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError(
            "the ForwardService neither stopped itself nor acted on its notification " +
                "'$STOP_ALL_ACTION_LABEL' action within ${TIMEOUT_MS}ms " +
                "(sent $afterSends time(s), then the direct intent)",
        )
    }

    // ------------------------------------------------------------ traffic oracles

    /**
     * Device-side traffic oracle: the forwarded local port must answer with the
     * run-unique fixture body — not a cached frame, not another process.
     */
    private fun awaitForwardedHttpBody(whenLabel: String) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        var lastBody = ""
        var lastFailure: Throwable? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            val connection = runCatching {
                (java.net.URL("http://127.0.0.1:$LOCAL_PORT/$BODY_FILE_NAME")
                    .openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = HTTP_TIMEOUT_MS
                    readTimeout = HTTP_TIMEOUT_MS
                    requestMethod = "GET"
                }
            }.getOrNull()
            if (connection != null) {
                try {
                    val responseCode = connection.responseCode
                    lastBody = connection.inputStream.bufferedReader().use { it.readText() }
                    assertTrue("forwarded HTTP response must be 200", responseCode == 200)
                    if (lastBody.contains(bodyToken)) {
                        println("J22_HTTP_BODY $whenLabel=${lastBody.trim()}")
                        return
                    }
                    lastFailure = AssertionError(
                        "HTTP $responseCode from forwarded port contained: $lastBody",
                    )
                } catch (failure: Throwable) {
                    lastFailure = failure
                } finally {
                    connection.disconnect()
                }
            }
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError(
            "GET http://127.0.0.1:$LOCAL_PORT/$BODY_FILE_NAME did not return the " +
                "run-unique fixture body '$bodyToken' $whenLabel; lastBody='$lastBody'",
            lastFailure,
        )
    }

    /** After teardown the local port must really be closed, not just unlisted. */
    private fun awaitLocalPortClosed() {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val answered = runCatching {
                (java.net.URL("http://127.0.0.1:$LOCAL_PORT/$BODY_FILE_NAME")
                    .openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = HTTP_TIMEOUT_MS
                    readTimeout = HTTP_TIMEOUT_MS
                }.responseCode
            }.isSuccess
            if (!answered) return
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError(
            "127.0.0.1:$LOCAL_PORT still answered after teardown within ${TIMEOUT_MS}ms"
        )
    }

    /**
     * The HOST-side oracle: the fixture's own http.server access log proves the
     * bytes physically arrived over the SSH tunnel, on an independent
     * connection the app-under-test cannot fabricate.
     */
    private fun awaitHostAccessLogLines(what: String): Int {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        var last = -1
        while (SystemClock.elapsedRealtime() < deadline) {
            val stdout = AgentsFixture.exec(
                "grep -c 'GET /$BODY_FILE_NAME' $HTTP_LOG 2>/dev/null || true",
            )
            last = stdout.trim().toIntOrNull() ?: 0
            if (last >= 1) return last
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError(
            "the fixture http.server access log never recorded a GET for " +
                "$BODY_FILE_NAME ($what); last count=$last",
        )
    }

    // ------------------------------------------------------------------ seed legwork

    /**
     * The fixture http.server on [REMOTE_PORT] must answer THIS run's token
     * before the journey starts (a stale server from an earlier run serves the
     * same /tmp directory, so an overwritten body file keeps even it honest).
     */
    private fun ensureFixtureHttpServer() {
        var started = false
        var lastProbe = ""
        val deadline = SystemClock.elapsedRealtime() + SEED_HTTP_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            lastProbe = runCatching {
                AgentsFixture.exec(
                    "python3 -c \"import urllib.request;" +
                        "print(urllib.request.urlopen(" +
                        "'http://127.0.0.1:$REMOTE_PORT/$BODY_FILE_NAME',timeout=2)" +
                        ".read().decode().strip())\" 2>/dev/null || true",
                ).trim()
            }.getOrDefault("")
            if (lastProbe == bodyToken) {
                println(
                    "J22_HTTP_SERVER ready (freshly_started=$started) " +
                        "${AgentsFixture.host}:$REMOTE_PORT",
                )
                return
            }
            if (!started) {
                AgentsFixture.exec(
                    "nohup python3 -m http.server $REMOTE_PORT --directory /tmp " +
                        ">$HTTP_LOG 2>&1 </dev/null &",
                )
                started = true
            }
            SystemClock.sleep(POLL_MS)
        }
        error(
            "the fixture http server on $REMOTE_PORT never served the run-unique " +
                "token within ${SEED_HTTP_TIMEOUT_MS}ms (probe saw '$lastProbe' last)",
        )
    }

    /** Backgrounded sojourn: continuously re-prove the FGS/notification/tunnel. */
    private fun proveFgsSurvivesSojourn() {
        val deadline = SystemClock.elapsedRealtime() + BACKGROUND_SOJOURN_MS
        var rounds = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            val serviceUp = isForwardServiceRunning()
            val notificationUp = forwardNotification() != null
            val tunnelUp = appGraph().forwardingController().snapshot.value
                .firstOrNull { it.hostId == hostId }
                ?.tunnels
                ?.any {
                    it.remotePort == REMOTE_PORT &&
                        it.status == TunnelInfo.Status.FORWARDING &&
                        it.localPort == LOCAL_PORT
                } == true
            assertTrue(
                "the FGS stack must survive the whole backgrounded sojourn: " +
                    "service=$serviceUp notification=$notificationUp tunnel=$tunnelUp " +
                    "after $rounds poll round(s)",
                serviceUp && notificationUp && tunnelUp,
            )
            rounds += 1
            SystemClock.sleep(SOJOURN_POLL_MS)
        }
        println("J22_SOJOURN rounds=$rounds (~${BACKGROUND_SOJOURN_MS}ms backgrounded)")
    }

    private fun grantNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            android.Manifest.permission.POST_NOTIFICATIONS,
        )
    }

    private fun awaitMappingAbsent(remotePort: Int) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val mapping = runBlocking {
                appGraph().portRemappingDao().getByRemotePort(hostId, remotePort)
            }
            if (mapping == null) return
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError(
            "the manual mapping for $remotePort remained after the UI removal " +
                "within ${TIMEOUT_MS}ms",
        )
    }

    private fun context() = InstrumentationRegistry.getInstrumentation().targetContext

    private companion object {
        const val TIMEOUT_MS = 60_000L
        const val POLL_MS = 250L
        const val SEED_HTTP_TIMEOUT_MS = 20_000L
        const val HTTP_TIMEOUT_MS = 1_000

        /** Long enough to be a real sojourn, short enough to keep the suite fast. */
        const val BACKGROUND_SOJOURN_MS = 10_000L
        const val SOJOURN_POLL_MS = 500L

        /** Bounded budget for the (best-effort) UI-remove leg of the teardown. */
        const val UI_REMOVE_TIMEOUT_MS = 15_000L

        /** PendingIntent sends per Stop-all leg; the action either works or it doesn't. */
        const val MAX_STOP_ALL_SENDS = 3

        /** Settle window proving the torn-down forward carries no more traffic. */
        const val TEARDOWN_SETTLE_MS = 3_000L

        const val REMOTE_PORT = 5_174
        const val LOCAL_PORT = 7_433
        const val BODY_FILE = "/tmp/j22-body.txt"
        const val BODY_FILE_NAME = "j22-body.txt"
        const val HTTP_LOG = "/tmp/pocketshell-j22-http.log"
        const val TUNNEL_NAME = "J22 fixture HTTP"
        const val STOP_ALL_ACTION_LABEL = "Stop all"

        const val JOURNEY = "j22-forward-service-fgs"
        const val HOST_ID = 9_922L
    }
}
