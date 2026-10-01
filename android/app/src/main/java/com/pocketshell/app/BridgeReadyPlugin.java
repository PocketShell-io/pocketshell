package com.pocketshell.app;

import android.util.Log;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * A no-op round trip the page sends as its FIRST native call (#3000).
 *
 * Capacitor 8's MessageHandler dispatches a WebView message to the plugin
 * thread and only then stores that page's reply channel
 * (`postMessage(...); javaScriptReplyProxy = replyProxy;`). After an in-place
 * page reload the field still holds the previous document's channel, so if the
 * plugin answers the new page's first call before the main thread stores the
 * new channel, the answer goes to the dead document and the promise never
 * settles. Startup's first real call is DurableStorage.open(), so a lost reply
 * there left the app on a blank screen. Every later message finds the new
 * channel already stored. Sacrificing this ping keeps real calls off the racy
 * first slot; the JS side retries it until answered. The log line shows
 * natively that each ping arrived.
 */
@CapacitorPlugin(name = "BridgeReady")
public final class BridgeReadyPlugin extends Plugin {
    private static final String TAG = "PocketshellBridge";

    @PluginMethod
    public void ping(PluginCall call) {
        String id = call.getString("requestId", "");
        if (id == null || !id.matches("[A-Za-z0-9-]{1,40}")) id = "invalid";
        Log.i(TAG, "ping " + id);
        call.resolve(new JSObject().put("requestId", id));
    }
}
