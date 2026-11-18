package com.pocketshell.app;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Google sign-in and authenticated sync transport for the WebView
 * (issue #3020).
 *
 * <p>The bridge carries only the signed-in state, the account's email, and
 * the sync API's HTTP status and body for a fixed set of routes. The Google ID
 * token stays in {@link GoogleSyncSession}: it is never resolved to
 * JavaScript, never logged, and never part of a rejection message. The
 * settings payload is encrypted and decrypted in the WebView with the user's
 * sync passphrase, so this plugin only ever sees the encrypted envelope.
 */
@CapacitorPlugin(name = "GoogleSync")
public final class GoogleSyncPlugin extends Plugin {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    /** Read-only backend calls (identity, GET requests) never queue behind sign-in/out or a slow renewal. */
    private final ExecutorService readers = Executors.newFixedThreadPool(3);
    private GoogleSyncSession session;

    @Override
    public void load() {
        GoogleSyncSession.timingLog = line -> android.util.Log.d("PocketShellSync", line);
        session = new GoogleSyncSession(
                new EncryptedSyncTokenStore(getContext()),
                GoogleSyncEnvironment.signIn(this::getActivity),
                GoogleSyncEnvironment.transport(getContext()),
                GoogleSyncConfig.SYNC_API_URL,
                () -> System.currentTimeMillis() / 1000L);
    }

    @Override
    protected void handleOnDestroy() {
        worker.shutdown();
        readers.shutdown();
    }

    private void submitRead(PluginCall call, Runnable task) {
        try {
            readers.execute(task);
        } catch (RejectedExecutionException destroyed) {
            call.reject("Plugin was destroyed before the call could run");
        }
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

    @PluginMethod
    public void status(PluginCall call) {
        submit(call, () -> {
            try {
                call.resolve(statusJson(session.status()));
            } catch (SyncAuthException error) {
                reject(call, error);
            }
        });
    }

    /**
     * The stored credential's public subject, without a network round trip (issue #3189): opening a
     * saved server never waits on {@code /me}. The JWT never crosses the bridge.
     */
    @PluginMethod
    public void accountId(PluginCall call) {
        submitRead(call, () -> {
            try { call.resolve(new JSObject().put("accountId", session.currentAccountId())); }
            catch (SyncAuthException error) { reject(call, error); }
        });
    }

    /** Background identity refresh: the subject as the backend authorizer verifies it ({@code GET /me}). */
    @PluginMethod
    public void verifyAccount(PluginCall call) {
        submitRead(call, () -> {
            try { call.resolve(new JSObject().put("accountId", session.verifiedAccountId())); }
            catch (SyncAuthException error) { reject(call, error); }
        });
    }

    @PluginMethod
    public void signIn(PluginCall call) {
        submit(call, () -> {
            try {
                call.resolve(statusJson(session.signIn()));
            } catch (SyncAuthException error) {
                reject(call, error);
            }
        });
    }

    @PluginMethod
    public void signOut(PluginCall call) {
        submit(call, () -> {
            try {
                try {
                    session.signOut();
                } finally {
                    // Whichever shell signed out, and even if the token store
                    // failed: the account vault's held keys and this device's
                    // remembered vault keys go with the account.
                    EphemeralCredentials.INSTANCE.wipeAll();
                    VaultDeviceKeyPlugin.forContext(getContext()).forgetAll();
                }
                call.resolve(statusJson(new GoogleSyncSession.Status(false, null)));
            } catch (SyncAuthException error) {
                reject(call, error);
            }
        });
    }

    /**
     * {@code {method: 'GET'|'PUT', slot?: string, body?: string}} → {@code {status, body}};
     * or {@code {method, endpoint: 'vault/status'|'vault/verify', body?}} for the vault's server check.
     */
    @PluginMethod
    public void request(PluginCall call) {
        String method = call.getString("method");
        String slot = call.getString("slot");
        String body = call.getString("body");
        String endpoint = call.getString("endpoint");
        String accountId = call.getString("accountId");
        Runnable task = () -> {
            try {
                GoogleSyncSession.Response response = session.requestBound(method, slot, endpoint, body, accountId);
                call.resolve(new JSObject().put("status", response.status).put("body", response.body));
            } catch (SyncAuthException error) {
                reject(call, error);
            }
        };
        if ("GET".equals(method)) submitRead(call, task); else submit(call, task);
    }

    private JSObject statusJson(GoogleSyncSession.Status status) {
        return new JSObject()
                .put("signedIn", status.signedIn)
                .put("email", status.email)
                .put("packageName", getContext().getPackageName());
    }

    private static void reject(PluginCall call, SyncAuthException error) {
        if (error.detail != null) call.reject(error.getMessage(), error.code, new JSObject().put("detail", error.detail));
        else call.reject(error.getMessage(), error.code);
    }
}
