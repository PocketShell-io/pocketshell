package com.pocketshell.app;

import android.app.Activity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.Handler;
import android.util.Log;
import java.util.function.LongSupplier;

/**
 * Android's half of background grace (#3160), for {@link SshCapabilityPlugin}:
 * opens and closes the foreground-service hold ({@link ForwardService}) from
 * the {@link BackgroundHold} decision, and reports when the network is usable
 * again. The grace policy itself (window, scheduled closes, reconnect on
 * return) stays in the shared core's ConnectionController.
 */
final class BackgroundHoldBridge {
    private static final String TAG = "PocketShellHold";

    /** What the plugin knows: its live connections, its Android context, its events. */
    interface Host {
        int liveConnections();
        /** Null in stand-alone JVM tests (no bridge): the hold then only decides. */
        Context context();
        /** The notification's Disconnect: end the hold now, as its expiry would. */
        void disconnectHeld();
        void networkUsable(String reason);
    }

    final BackgroundHold hold = new BackgroundHold();
    private final Handler mainHandler;
    private final LongSupplier elapsedRealtime;
    private final Host host;
    private final Runnable deadlineCheck = this::update;
    private volatile ConnectivityManager.NetworkCallback networkCallback;

    BackgroundHoldBridge(Handler mainHandler, LongSupplier elapsedRealtime, Host host) {
        this.mainHandler = mainHandler;
        this.elapsedRealtime = elapsedRealtime;
        this.host = host;
    }

    /**
     * The activity left the screen. The hold starts HERE, synchronously:
     * Android lets an app start a foreground service only briefly after it
     * leaves the foreground, and the WebView's lifecycle event reaches the
     * shared core (which then schedules each connection's grace close) later.
     */
    void onStop(Activity activity) {
        if (activity != null && activity.isChangingConfigurations()) return;
        hold.onBackground(elapsedRealtime.getAsLong());
        update();
    }

    void onStart() {
        hold.onForeground();
        update();
    }

    /** Re-decide on the main thread (the service and its notification live there). */
    void post() {
        if (mainHandler != null) mainHandler.post(this::update);
    }

    /** Re-decide the hold now, and again at its deadline. */
    void update() {
        if (mainHandler == null) return; // stand-alone JVM resource tests
        int live = host.liveConnections();
        BackgroundHold.Decision decision = hold.decide(live, elapsedRealtime.getAsLong());
        Log.i(TAG, "hold " + decision + " backgrounded=" + hold.backgrounded() + " live=" + live + " graceMs=" + hold.graceMs());
        mainHandler.removeCallbacks(deadlineCheck);
        if (decision.holding()) {
            mainHandler.postDelayed(deadlineCheck, Math.max(0L, decision.deadlineElapsedMs - elapsedRealtime.getAsLong()) + 250L);
        }
        Context context = host.context();
        if (context == null) return;
        ForwardService.hold(context, this, decision, host::disconnectHeld);
    }

    /**
     * Tell the app when the network is usable again: a new default network,
     * or Android lifting this app's block (the background firewall a resumed
     * app sits behind for a moment, Doze, data saver). The shared core's
     * reconnect ladder is short; this lets a give-up recover by itself.
     */
    void registerNetworkCallback() {
        Context context = host.context();
        if (context == null || networkCallback != null) return;
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        if (connectivity == null) return;
        ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                // API 29+ follows every onAvailable with onBlockedStatusChanged,
                // which says whether THIS app may use the network yet.
                if (Build.VERSION.SDK_INT < 29) host.networkUsable("available");
            }

            @Override
            public void onBlockedStatusChanged(Network network, boolean blocked) {
                // A new default network, or Android lifting this app's block.
                // A repeat is harmless: only given-up connections retry.
                if (!blocked) host.networkUsable("usable");
            }
        };
        try {
            connectivity.registerDefaultNetworkCallback(callback, mainHandler);
            networkCallback = callback;
        } catch (RuntimeException error) {
            Log.w(TAG, "network callback registration failed", error);
        }
    }

    void unregisterNetworkCallback() {
        ConnectivityManager.NetworkCallback callback = networkCallback;
        networkCallback = null;
        Context context = host.context();
        if (callback == null || context == null) return;
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        if (connectivity == null) return;
        try {
            connectivity.unregisterNetworkCallback(callback);
        } catch (RuntimeException ignored) {
            // Never registered or already gone.
        }
    }
}
