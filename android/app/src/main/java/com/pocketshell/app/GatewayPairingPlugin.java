package com.pocketshell.app;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;

/**
 * The narrow native API for gateway pairing storage (#3086) — the seam the
 * shared UI's pairing flow will call (slice 4). It stores and lists pairings;
 * it never derives one.
 *
 * <p>The pin must arrive from an explicit user action over an out-of-band
 * channel: the line {@code pocketshell gateway show --host-key} prints on the
 * host, or that key's SHA-256 fingerprint. There is no method that accepts a
 * pairing from a host record, the gateway's ready advisory, or the device
 * list's {@code ssh_host_key}, and no method that returns or accepts any
 * token, Google or routing.
 *
 * <p>Everything is scoped to the CURRENT signed-in account. The account
 * subject is the storage namespace but is never returned to JS: a pairing
 * write is bound to the account EMAIL the user saw (the same value
 * {@code GoogleSync.status} already reports), checked before and after the
 * vault lookup, so an account switch mid-action cannot redirect it.
 *
 * <p>Storage is protected: an {@link EncryptedSharedPreferences} file keyed
 * by an Android Keystore master key (AES-256-GCM values, so a tampered row
 * fails to decrypt rather than becoming a pin), private to the app, excluded
 * from backup and device transfer, and written with {@code commit()}.
 */
@CapacitorPlugin(name = "GatewayPairing")
public final class GatewayPairingPlugin extends Plugin {
    static final String PREFERENCES_FILE = "pocketshell-gateway-pairings";
    private static final String RECORDS_KEY = "records";

    private static GatewayPairingStore sharedStore;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private GatewaySyncSession session;

    /** The process-wide pairing store; the SSH plugin dials from the same one. */
    static synchronized GatewayPairingStore sharedStore(Context context) throws IOException {
        if (sharedStore == null) {
            sharedStore = new GatewayPairingStore(
                    new EncryptedRepository(context.getApplicationContext()), System::currentTimeMillis);
        }
        return sharedStore;
    }

    @Override
    public void load() {
        session = new GatewaySyncSession(getContext(), this::getActivity);
    }

    @Override
    protected void handleOnDestroy() {
        worker.shutdown();
    }

    /** The current account's pairings: public data only, no account subject. */
    @PluginMethod
    public void list(PluginCall call) {
        String requestId = call.getString("requestId", "");
        worker.execute(() -> {
            try {
                String subject = session.subject();
                JSONArray rows = new JSONArray();
                for (GatewayPairingStore.Pairing pairing : sharedStore(getContext()).list(subject)) {
                    rows.put(pairingRow(pairing));
                }
                call.resolve(new JSObject().put("requestId", requestId).put("pairings", rows));
            } catch (SyncAuthException signedOut) {
                reject(call, signedOut);
            } catch (IOException | org.json.JSONException storeError) {
                call.reject("The saved gateway pairings could not be read.", "GATEWAY_PAIRING_STORE_FAILED");
            }
        });
    }

    /**
     * {@code {requestId, expectedAccountEmail, serverUrl, deviceId, hostKey, keyHandleId}}
     * — an explicit user pairing action. {@code hostKey} is the
     * {@code gateway show --host-key} line (or its SHA256 fingerprint). The
     * store validates the canonical origin, device id and pin; the vault
     * must hold the named key.
     */
    @PluginMethod
    public void pair(PluginCall call) {
        String requestId = call.getString("requestId", "");
        String serverUrl = call.getString("serverUrl");
        String deviceId = call.getString("deviceId");
        String hostKey = call.getString("hostKey");
        String keyHandleId = call.getString("keyHandleId");
        String expectedEmail = call.getString("expectedAccountEmail");
        worker.execute(() -> {
            try {
                GatewayPairingStore.Pairing pairing = pairForAccount(sharedStore(getContext()), session,
                        this::vaultHasHandle, expectedEmail, serverUrl, deviceId, hostKey, keyHandleId);
                call.resolve(pairReply(requestId, pairing));
            } catch (GatewayPairingStore.InvalidPairingException invalid) {
                call.reject(invalid.getMessage(), "GATEWAY_PAIRING_INVALID");
            } catch (SyncAuthException signedOut) {
                reject(call, signedOut);
            } catch (IOException | org.json.JSONException storeError) {
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
                boolean removed = sharedStore(getContext()).remove(session.subject(), serverUrl, deviceId);
                call.resolve(new JSObject().put("requestId", requestId).put("removed", removed));
            } catch (SyncAuthException signedOut) {
                reject(call, signedOut);
            } catch (IOException storeError) {
                call.reject("The gateway pairing could not be removed.", "GATEWAY_PAIRING_STORE_FAILED");
            }
        });
    }

    interface VaultProbe { boolean hasHandle(String keyHandleId); }

    /** Bind the mutation to the account the user observed, never a later current account. */
    static GatewayPairingStore.Pairing pairForAccount(GatewayPairingStore store, GatewaySyncSession account,
            VaultProbe vault, String expectedEmail, String serverUrl, String deviceId, String hostKey,
            String keyHandleId) throws IOException, SyncAuthException {
        String subject = requireExpectedAccount(expectedEmail, account);
        if (!vault.hasHandle(keyHandleId)) {
            throw new SyncAuthException("GATEWAY_PAIRING_KEY_MISSING", "Choose an SSH key from the key vault.");
        }
        String after = requireExpectedAccount(expectedEmail, account);
        if (!after.equals(subject)) throw accountChanged();
        // A switch after this point cannot redirect the write: its namespace
        // is the subject that was current when the user's email matched.
        return store.pair(subject, serverUrl, deviceId, hostKey, keyHandleId);
    }

    private static String requireExpectedAccount(String expectedEmail, GatewaySyncSession account) throws SyncAuthException {
        String email = account.email();
        if (expectedEmail == null || expectedEmail.isEmpty() || email == null || !expectedEmail.equals(email)) {
            throw accountChanged();
        }
        return account.subject();
    }

    private static SyncAuthException accountChanged() {
        return new SyncAuthException("GATEWAY_PAIRING_ACCOUNT_CHANGED",
                "The signed-in account changed. Select the host and pair again.");
    }

    /** One public row: never the account subject, never a token. */
    static org.json.JSONObject pairingRow(GatewayPairingStore.Pairing pairing) throws org.json.JSONException {
        org.json.JSONObject row = new org.json.JSONObject()
                .put("serverUrl", pairing.serverUrl())
                .put("deviceId", pairing.deviceId())
                .put("fingerprintSha256", pairing.fingerprintSha256())
                .put("pinKind", pairing.hostKeyType() != null ? "host-key" : "fingerprint")
                .put("keyHandleId", pairing.keyHandleId())
                .put("pairedAtEpochMs", pairing.pairedAtEpochMs());
        if (pairing.hostKeyType() != null) row.put("hostKeyType", pairing.hostKeyType());
        return row;
    }

    /** Actual bridge envelope, shared by production and native regression tests. */
    static JSObject pairReply(String requestId, GatewayPairingStore.Pairing pairing) throws org.json.JSONException {
        return new JSObject().put("requestId", requestId).put("pairing", pairingRow(pairing));
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

    /**
     * Keystore-encrypted pairing persistence. Reads never create the file
     * (app start must not leave one behind, cf. #3047). A keyset the
     * Keystore can no longer open is deleted: losing pins only makes every
     * gateway host UNPAIRED, which refuses the dial — it can never grant
     * trust — and the user recovers by pairing again.
     */
    @SuppressWarnings("deprecation") // security-crypto 1.1.0 deprecates the helper; it is the app's at-rest store.
    static final class EncryptedRepository implements GatewayPairingStore.Repository {
        private final Context context;
        private SharedPreferences preferences;

        EncryptedRepository(Context context) {
            this.context = context;
        }

        @Override
        public synchronized String read() throws IOException {
            SharedPreferences existing = open(false);
            return existing == null ? null : existing.getString(RECORDS_KEY, null);
        }

        @Override
        public synchronized void write(String json) throws IOException {
            if (!open(true).edit().putString(RECORDS_KEY, json).commit()) {
                throw new IOException("The gateway pairing storage refused the write.");
            }
        }

        private SharedPreferences open(boolean create) throws IOException {
            if (preferences != null) return preferences;
            File file = new File(new File(context.getApplicationInfo().dataDir, "shared_prefs"), PREFERENCES_FILE + ".xml");
            if (!create && !file.exists()) return null;
            try {
                preferences = create();
            } catch (Exception unreadable) {
                context.deleteSharedPreferences(PREFERENCES_FILE);
                if (!create) return null;
                try {
                    preferences = create();
                } catch (Exception still) {
                    throw new IOException("The gateway pairing storage could not be opened.");
                }
            }
            return preferences;
        }

        private SharedPreferences create() throws Exception {
            MasterKey masterKey = new MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build();
            return EncryptedSharedPreferences.create(context, PREFERENCES_FILE, masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        }
    }
}
