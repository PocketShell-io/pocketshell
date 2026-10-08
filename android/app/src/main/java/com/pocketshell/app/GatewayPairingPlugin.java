package com.pocketshell.app;

import android.content.Context;
import android.content.SharedPreferences;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;

/**
 * The narrow native API for gateway pairing storage (issue #3060) — the seam
 * the shared UI's pairing flow will call once the resolver integration lands.
 *
 * <p>This plugin stores and lists pairings; it never derives one. The
 * fingerprint must arrive from an explicit user action over an out-of-band
 * channel (the host's own {@code ssh-keygen -lf} output), never from synced
 * metadata, never from the gateway's ready advisory — so there is no method
 * here that accepts a pairing from a host record, and no method that returns
 * or accepts any token, Google or routing.
 *
 * <p>Everything is scoped to the CURRENT signed-in account; records for other
 * accounts are invisible here and unusable at dial time. Storage is private
 * to the app, crash-durable via commit, and holds only public data (fingerprint,
 * canonical gateway origin, device id, key handle, timestamp).
 */
@CapacitorPlugin(name = "GatewayPairing")
public final class GatewayPairingPlugin extends Plugin {
    private static final String PREFERENCES_FILE = "pocketshell_gateway_pairings_v1";
    private static final String RECORDS_KEY = "records";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private GatewaySyncSession session;
    private GatewayPairingStore store;

    @Override
    public void load() {
        session = new GatewaySyncSession(getContext(), this::getActivity);
        try {
            store = new GatewayPairingStore(new PreferencesRepository(getContext()), System::currentTimeMillis);
        } catch (IOException unavailable) {
            // A store that will not parse refuses every listing and pairing
            // below; nothing is silently emptied.
            store = null;
        }
    }

    @Override
    protected void handleOnDestroy() {
        worker.shutdown();
    }

    /** The account pairings bind to, for the UI's signed-in gate. */
    @PluginMethod
    public void currentAccount(PluginCall call) {
        worker.execute(() -> {
            try {
                String subject = session.subject();
                call.resolve(new JSObject().put("requestId", call.getString("requestId", ""))
                        .put("signedIn", true)
                        .put("accountSubject", subject));
            } catch (SyncAuthException signedOut) {
                call.resolve(new JSObject().put("requestId", call.getString("requestId", ""))
                        .put("signedIn", false)
                        .put("accountSubject", ""));
            }
        });
    }

    /** The current account's pairings. Other accounts' records are invisible. */
    @PluginMethod
    public void list(PluginCall call) {
        worker.execute(() -> {
            try {
                if (store == null) {
                    call.reject("The saved gateway pairings could not be read.", "GATEWAY_PAIRING_STORE_FAILED");
                    return;
                }
                String subject = session.subject();
                JSONArray rows = new JSONArray();
                for (GatewayPairingStore.Pairing pairing : store.list(subject)) {
                    rows.put(new org.json.JSONObject()
                            .put("serverUrl", pairing.serverUrl())
                            .put("deviceId", pairing.deviceId())
                            .put("fingerprintSha256", pairing.fingerprintSha256())
                            .put("keyHandleId", pairing.keyHandleId())
                            .put("pairedAtEpochMs", pairing.pairedAtEpochMs()));
                }
                call.resolve(new JSObject().put("requestId", call.getString("requestId", ""))
                        .put("accountSubject", subject)
                        .put("pairings", rows.toString()));
            } catch (SyncAuthException signedOut) {
                reject(call, signedOut);
            } catch (IOException | org.json.JSONException storeError) {
                call.reject("The saved gateway pairings could not be read.", "GATEWAY_PAIRING_STORE_FAILED");
            }
        });
    }

    /**
     * {@code {requestId, expectedAccountSubject, serverUrl, deviceId, fingerprintSha256, keyHandleId}} —
     * an explicit user pairing action. The store validates the canonical
     * origin, device id, and SHA-256 fingerprint shape; the vault must hold
     * the named key.
     */
    @PluginMethod
    public void pair(PluginCall call) {
        String requestId = call.getString("requestId", "");
        String serverUrl = call.getString("serverUrl");
        String deviceId = call.getString("deviceId");
        String fingerprint = call.getString("fingerprintSha256");
        String keyHandleId = call.getString("keyHandleId");
        String expectedAccountSubject = call.getString("expectedAccountSubject");
        worker.execute(() -> {
            try {
                if (store == null) {
                    call.reject("The saved gateway pairings could not be read.", "GATEWAY_PAIRING_STORE_FAILED");
                    return;
                }
                GatewayPairingStore.Pairing pairing = pairForAccount(store, session::subject, this::vaultHasHandle,
                        expectedAccountSubject, serverUrl, deviceId, fingerprint, keyHandleId);
                call.resolve(pairReply(requestId, pairing));
            } catch (GatewayPairingStore.InvalidPairingException invalid) {
                call.reject(invalid.getMessage(), "GATEWAY_PAIRING_INVALID");
            } catch (SyncAuthException signedOut) {
                reject(call, signedOut);
            } catch (IOException storeError) {
                call.reject("The gateway pairing could not be saved.", "GATEWAY_PAIRING_STORE_FAILED");
            }
        });
    }

    /** {@code {requestId, serverUrl, deviceId}} — remove the triple's pairing. */
    @PluginMethod
    public void remove(PluginCall call) {
        String requestId = call.getString("requestId", "");
        String serverUrl = call.getString("serverUrl");
        String deviceId = call.getString("deviceId", "");
        worker.execute(() -> {
            try {
                if (store == null) {
                    call.reject("The saved gateway pairings could not be read.", "GATEWAY_PAIRING_STORE_FAILED");
                    return;
                }
                String subject = session.subject();
                boolean removed = store.remove(subject, serverUrl, deviceId);
                call.resolve(new JSObject().put("requestId", requestId).put("removed", removed));
            } catch (SyncAuthException signedOut) {
                reject(call, signedOut);
            } catch (IOException storeError) {
                call.reject("The gateway pairing could not be removed.", "GATEWAY_PAIRING_STORE_FAILED");
            }
        });
    }

    interface AccountSubject { String current() throws SyncAuthException; }
    interface VaultProbe { boolean hasHandle(String keyHandleId); }

    /** Bind the mutation to the subject the user observed, never a later current account. */
    static GatewayPairingStore.Pairing pairForAccount(GatewayPairingStore store, AccountSubject account,
            VaultProbe vault, String expectedSubject, String serverUrl, String deviceId, String fingerprint,
            String keyHandleId) throws IOException, SyncAuthException {
        requireExpectedAccount(expectedSubject, account.current());
        if (!vault.hasHandle(keyHandleId)) {
            throw new SyncAuthException("GATEWAY_PAIRING_KEY_MISSING", "Choose an SSH key from the key vault.");
        }
        requireExpectedAccount(expectedSubject, account.current());
        // A subsequent switch cannot redirect this write: its namespace is the approved subject.
        return store.pair(expectedSubject, serverUrl, deviceId, fingerprint, keyHandleId);
    }

    private static void requireExpectedAccount(String expected, String actual) throws SyncAuthException {
        if (expected == null || expected.isEmpty() || !expected.equals(actual)) {
            throw new SyncAuthException("GATEWAY_PAIRING_ACCOUNT_CHANGED",
                    "The signed-in account changed. Select the host and pair again.");
        }
    }

    /** Actual bridge envelope, shared by production and native regression tests. */
    static JSObject pairReply(String requestId, GatewayPairingStore.Pairing pairing) {
        return new JSObject().put("requestId", requestId).put("accountSubject", pairing.accountSubject())
                .put("serverUrl", pairing.serverUrl()).put("deviceId", pairing.deviceId())
                .put("fingerprintSha256", pairing.fingerprintSha256()).put("keyHandleId", pairing.keyHandleId())
                .put("pairedAtEpochMs", pairing.pairedAtEpochMs());
    }

    private boolean vaultHasHandle(String keyHandleId) {
        if (keyHandleId == null) return false;
        try {
            for (CredentialHandleVault.KeyMetadata key : CredentialHandleVault.forContext(getContext()).list()) {
                if (key.handleId.equals(keyHandleId)) return true;
            }
        } catch (IOException error) {
            return false;
        }
        return false;
    }

    private static void reject(PluginCall call, SyncAuthException error) {
        call.reject(error.getMessage(), error.code);
    }

    /** SharedPreferences-backed pairing persistence: commit() returns only
     * once the bytes are on disk, matching the vault's durability bar. */
    static final class PreferencesRepository implements GatewayPairingStore.Repository {
        private final SharedPreferences preferences;

        PreferencesRepository(Context context) {
            this.preferences = context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE);
        }

        @Override
        public String read() {
            return preferences.getString(RECORDS_KEY, null);
        }

        @Override
        public void write(String json) throws IOException {
            if (!preferences.edit().putString(RECORDS_KEY, json).commit()) {
                throw new IOException("The gateway pairing storage refused the write.");
            }
        }
    }
}
