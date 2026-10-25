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
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;

/** Keeps user-owned native tunnel sockets alive while the activity is backgrounded.
 * This service never dials, scans or decides which ports should be forwarded.
 */
public final class ForwardService extends Service {
    public static final int NOTIFICATION_ID = 2941;
    public static final String ACTION_STOP_ALL = "com.pocketshell.app.forward.STOP_ALL";
    private static final String CHANNEL = "ssh-port-forwards";
    private static ForwardService running;
    private boolean stopping;
    private static final Map<Object, Entry> OWNERS = new IdentityHashMap<>();
    private static final class Entry {
        final int count;
        final Runnable stop;
        final String ports;
        Entry(int count, String ports, Runnable stop) { this.count = count; this.ports = ports; this.stop = stop; }
    }

    static synchronized void update(Context context, Object owner, java.util.function.IntSupplier liveCount, java.util.function.Supplier<String> livePorts, Runnable stop) {
        int count = liveCount.getAsInt();
        if (count == 0) OWNERS.remove(owner);
        else OWNERS.put(owner, new Entry(count, livePorts.get(), stop));
        Intent intent = new Intent(context, ForwardService.class);
        if (OWNERS.isEmpty()) context.stopService(intent);
        else if (running != null) {
            ForwardService current = running;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                synchronized (ForwardService.class) {
                    if (running == current && !current.stopping) current.showNotification();
                }
            });
        } else ContextCompat.startForegroundService(context, intent);
    }

    private static synchronized int count() {
        int total = 0;
        for (Entry entry : OWNERS.values()) total += entry.count;
        return total;
    }

    private static synchronized String ports() {
        ArrayList<String> labels = new ArrayList<>();
        for (Entry entry : OWNERS.values()) labels.add(entry.ports);
        return android.text.TextUtils.join(", ", labels);
    }

    @Override public void onCreate() {
        super.onCreate();
        synchronized (ForwardService.class) { running = this; }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP_ALL.equals(intent.getAction())) {
            stopping = true;
            ArrayList<Entry> entries;
            synchronized (ForwardService.class) { entries = new ArrayList<>(OWNERS.values()); }
            for (Entry entry : entries) entry.stop.run();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        showNotification();
        return START_NOT_STICKY;
    }

    private void showNotification() {
        int tunnels = count();
        if (tunnels == 0) { stopSelf(); return; }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(new NotificationChannel(CHANNEL, "SSH port forwarding", NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 1,
            new Intent(this, ForwardService.class).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = (Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this))
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("PocketShell port forwarding")
            .setContentText(tunnels + (tunnels == 1 ? " active tunnel: " : " active tunnels: ") + ports())
            .setStyle(new Notification.BigTextStyle().bigText(ports()))
            .setOngoing(true).setContentIntent(open)
            .addAction(new Notification.Action.Builder(null, "Stop all", stop).build());
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        Notification notification = builder.build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIFICATION_ID, notification);
    }
    @Override public void onDestroy() {
        stopping = true;
        synchronized (ForwardService.class) { if (running == this) running = null; }
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
