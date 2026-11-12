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
import java.util.concurrent.RejectedExecutionException;
import org.json.JSONArray;

/**
 * Native gateway discovery and account-bound SSH trust. Discovery is advisory:
 * it never supplies an accepted pin. Explicit local pairings bind a phone key;
 * the trusted-server path installs an authenticated vault descriptor with its
 * exact host key, vault identity and key reference instead. Google and gateway
 * tokens never cross this bridge.
 *
 * <p>Pairings and monotonic trust floors use Keystore-encrypted preferences,
 * excluded from backup and device transfer. Trusted writes require the current
 * native Google subject before and after mutation; explicit legacy pairing
 * also checks the email displayed by the action's UI.
 */
@CapacitorPlugin(name = "GatewayPairing")
public final class GatewayPairingPlugin extends Plugin {
    static final String PREFERENCES_FILE = "pocketshell-gateway-pairings";
    private static final String RECORDS_KEY = "records";

    private static GatewayPairingStore sharedStore;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private GatewaySyncSession session;
    private GatewayDirectoryClient directory;

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
        directory = new GatewayDirectoryClient(session, new GatewayRoutingTokens(System::currentTimeMillis),
                new HttpSyncTransport(), GatewayOriginAllowlist.forBuild(), GatewayDirectoryClient.serverUrlForBuild());
    }

    @Override
    protected void handleOnDestroy() {
        worker.shutdown();
    }

    /** This build's fixed gateway origin. JavaScript cannot choose where a token goes. */
    @PluginMethod
    public void serverUrl(PluginCall call) {
        try {
            call.resolve(new JSObject().put("requestId", call.getString("requestId", ""))
                    .put("serverUrl", directory.serverUrl()));
        } catch (GatewayDirectoryClient.DirectoryException error) {
            call.reject(error.getMessage(), error.code);
        }
    }

    /** Public account-bound device rows, fetched with a native-only scoped credential. */
    @PluginMethod
    public void devices(PluginCall call) {
        String requestId = call.getString("requestId", "");
        String expectedEmail = call.getString("expectedAccountEmail");
        submit(call, () -> {
            try {
                call.resolve(new JSObject().put("requestId", requestId)
                        .put("devices", directory.devices(expectedEmail)));
            } catch (GatewayDirectoryClient.DirectoryException error) {
                call.reject(error.getMessage(), error.code);
            } catch (GatewayTokenBroker.GatewayBrokerException error) {
                call.reject(error.getMessage(), error.code);
            } catch (SyncAuthException error) {
                reject(call, error);
            }
        });
    }

    /**
     * Runs a plugin call on the worker. A call that arrives after the plugin was
     * destroyed (a dying WebView racing the activity teardown) is rejected rather
     * than letting RejectedExecutionException crash the whole process.
     */
    private void submit(PluginCall call, Runnable task) {
        try {
            worker.execute(task);
        } catch (RejectedExecutionException destroyed) {
            call.reject("Plugin was destroyed before the call could run");
        }
    }

    /** The current account's pairings: public data only, no account subject. */
    @PluginMethod
    public void list(PluginCall call) {
        String requestId = call.getString("requestId", "");
        submit(call, () -> {
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
        submit(call, () -> {
            try {
                GatewayPairingStore.Pairing pairing = pairForAccount(sharedStore(getContext()), session,
                        this::vaultHasHandle, expectedEmail, serverUrl, deviceId, hostKey, keyHandleId);
                call.resolve(pairReply(requestId, pairing));
            } catch (OriginNotAllowedException notAllowed) {
                call.reject(notAllowed.getMessage(), GatewayOriginAllowlist.NOT_ALLOWED);
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
        String deviceId = call.getString("deviceId");
        submit(call, () -> {
            try {
                // The whole target is validated before the store is even
                // opened: a missing or malformed origin/device removes nothing.
                GatewayPairingStore.requireRemoveTarget(serverUrl, deviceId);
                boolean removed = sharedStore(getContext()).remove(session.subject(), serverUrl, deviceId);
                call.resolve(new JSObject().put("requestId", requestId).put("removed", removed));
            } catch (GatewayPairingStore.InvalidPairingException invalid) {
                call.reject(invalid.getMessage(), "GATEWAY_PAIRING_INVALID");
            } catch (SyncAuthException signedOut) {
                reject(call, signedOut);
            } catch (IOException storeError) {
                call.reject("The gateway pairing could not be removed.", "GATEWAY_PAIRING_STORE_FAILED");
            }
        });
    }

    static TrustedHostRevisions protectedRevisions(Context context) {
        return new TrustedHostRevisions(new EncryptedRepository(context, "pocketshell-trusted-host-revisions"));
    }

    private TrustedHostRevisions revisions() { return protectedRevisions(getContext()); }

    private String requireAccountId(String expected) throws SyncAuthException {
        String subject = session.subject();
        if (!("google:" + subject).equals(expected)) throw accountChanged();
        return subject;
    }

    @PluginMethod
    public void trustedRevisions(PluginCall call) {
        submit(call, () -> {
            try {
                String account = call.getString("accountId");
                requireAccountId(account);
                org.json.JSONObject floors = revisions().load(account, call.getString("vaultId"));
                requireAccountId(account);
                call.resolve(new JSObject().put("floors", floors));
            } catch (Exception error) { call.reject("Trusted host revisions could not be read.", "TRUSTED_HOST_STORE_FAILED"); }
        });
    }

    @PluginMethod
    public void saveTrustedRevision(PluginCall call) {
        submit(call, () -> {
            try {
                String account = call.getString("accountId");
                requireAccountId(account);
                revisions().save(account, call.getString("vaultId"), call.getString("slot"), TrustedHostRevisions.positive(call.getData(), "revision"));
                requireAccountId(account);
                call.resolve();
            } catch (Exception error) { call.reject("Trusted host revision was refused.", "TRUSTED_HOST_ROLLBACK"); }
        });
    }

    /** Only the authenticated, decrypted vault descriptor path calls this. Local phone keys are never substituted. */
    @PluginMethod
    public void installTrustedHost(PluginCall call) {
        submit(call, () -> {
            try {
                JSObject host = call.getObject("host");
                if (host == null || !"active".equals(host.getString("status"))) throw new IOException("Missing active trusted host.");
                String account = host.getString("accountId");
                String subject = requireAccountId(account);
                String server = host.getString("serverUrl");
                if (!GatewayOriginAllowlist.forBuild().allowsGatewayOrigin(server)) throw new OriginNotAllowedException();
                String vaultId = host.getString("vaultId");
                String slot = TrustedHostRevisions.slot(server, host.getString("deviceId"));
                long revision = TrustedHostRevisions.positive(host, "revision");
                if (revision < revisions().load(account, vaultId).optLong(slot, 0)) throw new IOException("Trusted host rollback refused.");
                requireAccountId(account);
                sharedStore(getContext()).installVault(subject, server, host.getString("deviceId"), host.getString("sshHostKey"),
                        host.getString("keyId"), vaultId, TrustedHostRevisions.positive(host, "epoch"), revision,
                        host.getString("hostAlias"), host.getString("username"));
                requireAccountId(account);
                revisions().save(account, vaultId, slot, revision);
                call.resolve();
            } catch (SyncAuthException error) { reject(call, error); }
            catch (Exception error) { call.reject("Trusted host installation was refused.", "TRUSTED_HOST_INVALID"); }
        });
    }

    interface VaultProbe { boolean hasHandle(String keyHandleId); }

    /** A pairing for a gateway origin outside the native allowlist (B1). */
    static final class OriginNotAllowedException extends IOException {
        OriginNotAllowedException() {
            super("PocketShell pairs only with the PocketShell gateway; this gateway address is not allowed.");
        }
    }

    /** Bind the mutation to the account the user observed, never a later current account.
     * The origin must be on the native allowlist first: nothing is read, probed or stored
     * for any other gateway (#3086 B1). */
    static GatewayPairingStore.Pairing pairForAccount(GatewayPairingStore store, GatewaySyncSession account,
            VaultProbe vault, String expectedEmail, String serverUrl, String deviceId, String hostKey,
            String keyHandleId) throws IOException, SyncAuthException {
        String canonical = GatewayTargetPolicy.normalizeServerUrl(serverUrl);
        if (canonical == null || !GatewayOriginAllowlist.forBuild().allowsGatewayOrigin(canonical)) {
            throw new OriginNotAllowedException();
        }
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
                .put("credentialSource", pairing.credentialSource());
        if ("vault".equals(pairing.credentialSource())) row.put("vaultId", pairing.vaultId())
                .put("vaultEpoch", pairing.vaultEpoch()).put("vaultKeyId", pairing.vaultKeyId())
                .put("trustedRevision", pairing.trustedRevision).put("hostAlias", pairing.hostAlias).put("username", pairing.username);
        row.put("pairedAtEpochMs", pairing.pairedAtEpochMs());
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
        private final String fileName;
        private SharedPreferences preferences;

        EncryptedRepository(Context context) {
            this(context, PREFERENCES_FILE);
        }

        EncryptedRepository(Context context, String fileName) {
            this.context = context;
            this.fileName = fileName;
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
            File file = new File(new File(context.getApplicationInfo().dataDir, "shared_prefs"), fileName + ".xml");
            if (!create && !file.exists()) return null;
            try {
                preferences = create();
            } catch (Exception unreadable) {
                if (!PREFERENCES_FILE.equals(fileName)) throw new IOException("Protected trust storage could not be opened.");
                context.deleteSharedPreferences(fileName);
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
            return EncryptedSharedPreferences.create(context, fileName, masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        }
    }
}
