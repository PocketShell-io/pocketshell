package com.pocketshell.app;

import android.util.Log;
import android.webkit.JavascriptInterface;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

/**
 * Android platform storage seam for the WebView's localStorage (issue #2993).
 *
 * <p>{@code open} (an ordinary, origin-restricted Capacitor call) hands the
 * app's top frame the committed entries plus a per-page write token. Writes then
 * go through the synchronous {@link Writer} JavaScript interface, which commits
 * to {@link DurableKeyValueStore} before returning, so a write is on disk before
 * the UI can acknowledge it. A JavaScript interface is visible to every frame,
 * so each write must carry the token, which only the top frame ever receives;
 * a preview iframe cannot read or forge storage through it.
 */
@CapacitorPlugin(name = "DurableStorage")
public final class DurableStoragePlugin extends Plugin {
    static final String INTERFACE_NAME = "PocketShellDurableStorage";
    private static final String TAG = "DurableStorage";
    /** Directory under {@code getFilesDir()}; public so packaged tests can read the committed state. */
    public static final String DIRECTORY_NAME = "pocketshell-durable-storage";

    private final SecureRandom random = new SecureRandom();
    private DurableKeyValueStore store;
    private volatile byte[] token;

    @Override
    public void load() {
        store = new DurableKeyValueStore(new File(getContext().getFilesDir(), DIRECTORY_NAME));
        // Plugins load before Capacitor loads the page, so the interface is
        // present from the first document on.
        getBridge().getWebView().addJavascriptInterface(new Writer(), INTERFACE_NAME);
    }

    @PluginMethod
    public void open(PluginCall call) {
        try {
            boolean initialized = store.isInitialized();
            JSObject entries = new JSObject();
            if (initialized) {
                for (Map.Entry<String, String> entry : store.readAll().entrySet()) {
                    entries.put(entry.getKey(), entry.getValue());
                }
            }
            byte[] next = new byte[32];
            random.nextBytes(next);
            // A new page (first launch or reload) gets a fresh token; the
            // previous page's token stops working.
            token = next;
            call.resolve(new JSObject()
                    .put("token", hex(next))
                    .put("initialized", initialized)
                    .put("entries", entries));
        } catch (Exception error) {
            Log.e(TAG, "durable storage could not be opened", error);
            call.reject("Saved app data could not be read from durable storage.", "DURABLE_STORAGE_OPEN_FAILED", error);
        }
    }

    private boolean authorized(String candidate) {
        byte[] current = token;
        return current != null && candidate != null
                && MessageDigest.isEqual(hex(current).getBytes(StandardCharsets.US_ASCII),
                        candidate.getBytes(StandardCharsets.US_ASCII));
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte part : bytes) {
            text.append(Character.forDigit((part >>> 4) & 0xf, 16)).append(Character.forDigit(part & 0xf, 16));
        }
        return text.toString();
    }

    /**
     * Synchronous writer. Each method returns an empty string once the change
     * is durable, or a non-empty error the JavaScript side turns into a thrown
     * storage error (the localStorage change is rolled back there).
     */
    final class Writer {
        @JavascriptInterface
        public String setItem(String writeToken, String key, String value) {
            if (!authorized(writeToken)) return "unauthorized";
            try {
                store.put(key, value);
                return "";
            } catch (Exception error) {
                return failure("write", error);
            }
        }

        @JavascriptInterface
        public String removeItem(String writeToken, String key) {
            if (!authorized(writeToken)) return "unauthorized";
            try {
                store.remove(key);
                return "";
            } catch (Exception error) {
                return failure("remove", error);
            }
        }

        @JavascriptInterface
        public String clear(String writeToken) {
            if (!authorized(writeToken)) return "unauthorized";
            try {
                store.clear();
                return "";
            } catch (Exception error) {
                return failure("clear", error);
            }
        }

        /** One-time copy of the pre-existing WebView localStorage; marks the store authoritative. */
        @JavascriptInterface
        public String importAll(String writeToken, String entriesJson) {
            if (!authorized(writeToken)) return "unauthorized";
            try {
                JSONObject parsed = new JSONObject(entriesJson);
                Map<String, String> entries = new LinkedHashMap<>();
                Iterator<String> keys = parsed.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    entries.put(key, parsed.getString(key));
                }
                store.importAll(entries);
                Log.i(TAG, "imported " + entries.size() + " existing localStorage entries");
                return "";
            } catch (Exception error) {
                return failure("import", error);
            }
        }

        private String failure(String operation, Exception error) {
            Log.e(TAG, "durable storage " + operation + " failed", error);
            String message = error.getMessage();
            return message == null || message.isEmpty() ? operation + " failed" : message;
        }
    }
}
