package com.pocketshell.next.ports

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.next.R
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The destroy half of the notification lifecycle (#2701).
 *
 * Every stop path (empty-snapshot collector stop, the notification's Stop-all
 * action, a resume that finds no work) converges on `stopSelf()`, so
 * [ForwardService.onDestroy] is the last code that will ever run for the
 * service — and the collector's final empty-snapshot `notify` can be applied by
 * NotificationManagerService AFTER the system has removed the FGS notification
 * at destroy. Without an explicit cancel there, that ongoing notification
 * outlives the service as the unswipeable "Port forwarding stopping" ghost the
 * J22 journey caught.
 *
 * This is the in-process half of that contract: whatever id-4201 notification
 * is posted when destroy runs must not survive it. The cross-process ordering
 * itself (notify vs FGS removal) is the live-journey's to prove — a JVM harness
 * has no system_server racing us — which is why the ghost is posted through the
 * same NotificationManager the service races with, in the exact shape the
 * failing runs left behind.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ForwardServiceTeardownTest {

    private lateinit var context: Context
    private lateinit var manager: NotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(NotificationManager::class.java)
        // The channel the service creates in onCreate; posting needs it to be
        // indistinguishable from a real tray entry.
        manager.createNotificationChannel(
            NotificationChannel(
                ForwardService.CHANNEL_ID,
                "Port forwarding",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    @Test
    fun `onDestroy cancels the ongoing stopping notification the collector left behind`() {
        // The ghost from the failing J22 runs: the LAST notify the collector
        // issued is the empty-snapshot one — same id, same channel, ongoing.
        postGhost()
        assertGhostPosted()

        Robolectric.buildService(ForwardService::class.java).get().onDestroy()

        assertEquals(
            "destroy must leave no notification behind",
            0,
            manager.activeNotifications.size,
        )
    }

    @Test
    fun `onDestroy is a safe no-op when no forwarding notification is posted`() {
        val service = Robolectric.buildService(ForwardService::class.java).get()

        service.onDestroy()

        assertEquals(0, manager.activeNotifications.size)
    }

    @Test
    fun `onDestroy still cancels after the scope was already cancelled`() {
        // A second destroy-shaped entry (e.g. an abrupt stop path racing the
        // first) must stay idempotent, not throw on the cancelled scope.
        val service = Robolectric.buildService(ForwardService::class.java).get()
        postGhost()

        service.onDestroy()
        service.onDestroy()

        assertEquals(0, manager.activeNotifications.size)
    }

    /** Posts exactly what the failing runs left in the tray. */
    private fun postGhost() {
        manager.notify(
            ForwardService.NOTIFICATION_ID,
            NotificationCompat.Builder(context, ForwardService.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_forwarding)
                .setContentTitle(ForwardNotificationText.title(emptyList()))
                .setContentText(ForwardNotificationText.body(emptyList()))
                .setOngoing(true)
                .build(),
        )
    }

    private fun assertGhostPosted() {
        assertEquals(
            "setup must have posted the ghost before destroy runs",
            1,
            manager.activeNotifications.size,
        )
    }
}
