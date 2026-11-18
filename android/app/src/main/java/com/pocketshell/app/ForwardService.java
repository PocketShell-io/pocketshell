package com.pocketshell.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * PocketShell's one foreground service: keeps the process and its network
 * alive while the activity is backgrounded, for two reasons that share one
 * notification.
 *
 * <ul>
 *   <li><b>Port forwards</b> the user opened keep answering (always on while
 *       any forward is open).</li>
 *   <li><b>Background grace hold</b> (#3160): live SSH connections ride
 *       through an app switch for the user's grace window. Without it Android
 *       freezes the backgrounded app and blocks its network, the transport
 *       dies, and every return is a full redial.</li>
 * </ul>
 *
 * This service never dials, scans, or decides policy: the plugin tells it
 * what to hold and until when, and closing connections at the end of grace is
 * the shared core's scheduled close.
 */
public final class ForwardService extends Service {
    private static final String TAG = "PocketShellHold";
    public static final int NOTIFICATION_ID = 2941;
    public static final String ACTION_STOP_ALL = "com.pocketshell.app.forward.STOP_ALL";
    public static final String ACTION_DISCONNECT = "com.pocketshell.app.hold.DISCONNECT";
    private static final String CHANNEL = "ssh-port-forwards";
    private static final String WAKE_LOCK_TAG = "PocketShell:grace";
    /** Head-room so the normal release always wins; the timeout is only the backstop. */
    private static final long WAKE_LOCK_MARGIN_MS = 30_000L;
    private PowerManager.WakeLock wakeLock;
    private static final Map<Object, Entry> OWNERS = new IdentityHashMap<>();
    private static final Map<Object, Hold> HOLDS = new IdentityHashMap<>();
    /** Starts Android still waits on: a stop before they go foreground kills the app. */
    private static final ForegroundStartLedger STARTS = new ForegroundStartLedger();

    private static final class Entry {
        final int count;
        final Runnable stop;
        final String ports;
        Entry(int count, String ports, Runnable stop) { this.count = count; this.ports = ports; this.stop = stop; }
    }

    private static final class Hold {
        final int count;
        final long deadlineElapsedMs;
        final Runnable disconnect;
        Hold(int count, long deadlineElapsedMs, Runnable disconnect) {
            this.count = count;
            this.deadlineElapsedMs = deadlineElapsedMs;
            this.disconnect = disconnect;
        }
    }

    /** Port forwards of {@code owner}: always held while any is open. */
    static synchronized void update(Context context, Object owner, java.util.function.IntSupplier liveCount, java.util.function.Supplier<String> livePorts, Runnable stop) {
        int count = liveCount.getAsInt();
        if (count == 0) OWNERS.remove(owner);
        else OWNERS.put(owner, new Entry(count, livePorts.get(), stop));
        reconcile(context);
    }

    /**
     * The background grace hold of {@code owner}: {@code decision.count} live
     * connections until {@code decision.deadlineElapsedMs}, or no hold.
     * {@code disconnect} ends the hold early (the notification's action).
     */
    static synchronized void hold(Context context, Object owner, BackgroundHold.Decision decision, Runnable disconnect) {
        if (!decision.holding()) HOLDS.remove(owner);
        else HOLDS.put(owner, new Hold(decision.count, decision.deadlineElapsedMs, disconnect));
        reconcile(context);
    }

    /**
     * Start (or refresh) the service while anything needs holding, else stop
     * it. Always a fresh start command, never a post to the current instance:
     * an instance that is being stopped has not run onDestroy yet, and handing
     * the update to it would leave nothing running (the hold silently missing
     * on the next background). A start on a running service only re-posts its
     * notification.
     */
    private static void reconcile(Context context) {
        Intent intent = new Intent(context, ForwardService.class);
        if (OWNERS.isEmpty() && HOLDS.isEmpty()) {
            // A fast leave-and-return asks to stop before the start the leave
            // issued has gone foreground. Stopping then is fatal
            // (ForegroundServiceDidNotStartInTimeException, the #2483 class):
            // the pending start goes foreground first and then stops itself.
            if (STARTS.mayStop()) context.stopService(intent);
            return;
        }
        try {
            ContextCompat.startForegroundService(context, intent);
            STARTS.requested();
        } catch (RuntimeException rejected) {
            // Android 12+ refuses a start once the app has been in the
            // background too long. The hold is then simply not up: the
            // session reconnects on return, the process must not crash.
            Log.w(TAG, "foreground service start was refused", rejected);
        }
    }

    private static synchronized int tunnelCount() {
        int total = 0;
        for (Entry entry : OWNERS.values()) total += entry.count;
        return total;
    }

    private static synchronized String ports() {
        ArrayList<String> labels = new ArrayList<>();
        for (Entry entry : OWNERS.values()) labels.add(entry.ports);
        return android.text.TextUtils.join(", ", labels);
    }

    private static synchronized int heldCount() {
        int total = 0;
        for (Hold hold : HOLDS.values()) total += hold.count;
        return total;
    }

    private static synchronized long holdDeadlineElapsedMs() {
        long deadline = 0L;
        for (Hold hold : HOLDS.values()) deadline = Math.max(deadline, hold.deadlineElapsedMs);
        return deadline;
    }

    /** "Keeping 1 connection alive" / "Keeping 3 connections alive". */
    static String holdText(int connections) {
        return "Keeping " + connections + (connections == 1 ? " connection" : " connections") + " alive";
    }

    static String tunnelText(int tunnels, String ports) {
        return tunnels + (tunnels == 1 ? " active tunnel: " : " active tunnels: ") + ports;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP_ALL.equals(action)) {
            ArrayList<Entry> entries;
            synchronized (ForwardService.class) { entries = new ArrayList<>(OWNERS.values()); }
            for (Entry entry : entries) entry.stop.run();
            // Forwards close asynchronously and update() reconciles; post the
            // current state now so startForeground's contract is kept.
            showNotification();
            return START_NOT_STICKY;
        }
        if (ACTION_DISCONNECT.equals(action)) {
            ArrayList<Hold> holds;
            synchronized (ForwardService.class) { holds = new ArrayList<>(HOLDS.values()); }
            for (Hold hold : holds) hold.disconnect.run();
            showNotification();
            return START_NOT_STICKY;
        }
        showNotification(startId, true);
        return START_NOT_STICKY;
    }

    private void showNotification() {
        showNotification(0, false);
    }

    /**
     * Go foreground first, on every path (each startForegroundService() is
     * owed a startForeground()), then stop if nothing is left to hold.
     * {@code answersStart}: this call answers one of {@link #reconcile}'s starts.
     */
    private void showNotification(int startId, boolean answersStart) {
        int tunnels = tunnelCount();
        int held = heldCount();
        // startForeground first, on every path: startForegroundService() promised it.
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(new NotificationChannel(CHANNEL, "SSH connections and port forwarding", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0,
            new Intent(this, MainActivity.class).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = (Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this))
            .setOngoing(true).setContentIntent(open).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE);
        String text;
        if (held > 0 && tunnels > 0) {
            text = holdText(held) + " · " + tunnelText(tunnels, ports());
            builder.setSmallIcon(android.R.drawable.stat_sys_upload_done).setContentTitle("PocketShell");
        } else if (held > 0) {
            text = holdText(held);
            builder.setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("PocketShell");
        } else {
            text = tunnels == 0 ? "" : tunnelText(tunnels, ports());
            builder.setSmallIcon(android.R.drawable.stat_sys_upload_done).setContentTitle("PocketShell port forwarding");
        }
        builder.setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text));
        if (held > 0) {
            // A system-drawn count-down to the end of the hold: no per-second work here.
            long remaining = Math.max(0L, holdDeadlineElapsedMs() - SystemClock.elapsedRealtime());
            builder.setWhen(System.currentTimeMillis() + remaining).setShowWhen(true)
                .setUsesChronometer(true);
            if (Build.VERSION.SDK_INT >= 24) builder.setChronometerCountDown(true);
            PendingIntent disconnect = PendingIntent.getService(this, 2,
                new Intent(this, ForwardService.class).setAction(ACTION_DISCONNECT),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(null, "Disconnect", disconnect).build());
        }
        if (tunnels > 0) {
            PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, ForwardService.class).setAction(ACTION_STOP_ALL),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(null, "Stop all", stop).build());
        }
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        Notification notification = builder.build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIFICATION_ID, notification);
        boolean nothingLeft;
        synchronized (ForwardService.class) {
            if (answersStart) STARTS.answered();
            nothingLeft = OWNERS.isEmpty() && HOLDS.isEmpty() && STARTS.mayStop();
        }
        if (nothingLeft) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            // Only if no newer start arrived meanwhile: that one stops itself.
            if (answersStart) stopSelfResult(startId);
            else stopSelf();
            return;
        }
        updateWakeLock(held > 0 ? holdDeadlineElapsedMs() : 0L);
    }

    /**
     * The CPU stays awake for the grace hold (screen-off Doze would otherwise
     * stop the scheduled close and the transport alike). Bounded twice:
     * released when the hold ends and acquired with the remaining window as
     * its platform timeout.
     */
    private void updateWakeLock(long holdDeadlineElapsedMs) {
        long remaining = holdDeadlineElapsedMs - SystemClock.elapsedRealtime();
        if (holdDeadlineElapsedMs <= 0L || remaining <= 0L) {
            releaseWakeLock();
            return;
        }
        PowerManager power = getSystemService(PowerManager.class);
        if (power == null) return;
        if (wakeLock == null) {
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG);
            wakeLock.setReferenceCounted(false);
        }
        wakeLock.acquire(remaining + WAKE_LOCK_MARGIN_MS);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (RuntimeException error) {
                Log.w(TAG, "grace wake lock release failed", error);
            }
        }
    }

    @Override public void onDestroy() {
        releaseWakeLock();
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
