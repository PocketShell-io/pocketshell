package com.pocketshell.app;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private GoogleSyncSession session;

    @Override
    public void load() {
        session = new GoogleSyncSession(
                new EncryptedSyncTokenStore(getContext()),
                GoogleSyncEnvironment.signIn(this::getActivity),
                GoogleSyncEnvironment.transport(),
                GoogleSyncConfig.SYNC_API_URL,
                () -> System.currentTimeMillis() / 1000L);
    }

    @Override
    protected void handleOnDestroy() {
        worker.shutdown();
    }

    @PluginMethod
    public void status(PluginCall call) {
        worker.execute(() -> {
            try {
                call.resolve(statusJson(session.status()));
            } catch (SyncAuthException error) {
                reject(call, error);
            }
        });
    }

    @PluginMethod
    public void signIn(PluginCall call) {
        worker.execute(() -> {
            try {
                call.resolve(statusJson(session.signIn()));
            } catch (SyncAuthException error) {
                reject(call, error);
            }
        });
    }

    @PluginMethod
    public void signOut(PluginCall call) {
        worker.execute(() -> {
            try {
                session.signOut();
                call.resolve(statusJson(new GoogleSyncSession.Status(false, null)));
            } catch (SyncAuthException error) {
                reject(call, error);
            }
        });
    }

    /** {@code {method: 'GET'|'PUT', slot?: string, body?: string}} → {@code {status, body}}. */
    @PluginMethod
    public void request(PluginCall call) {
        String method = call.getString("method");
        String slot = call.getString("slot");
        String body = call.getString("body");
        worker.execute(() -> {
            try {
                GoogleSyncSession.Response response = session.request(method, slot, body);
                call.resolve(new JSObject().put("status", response.status).put("body", response.body));
            } catch (SyncAuthException error) {
                reject(call, error);
            }
        });
    }

    private JSObject statusJson(GoogleSyncSession.Status status) {
        return new JSObject()
                .put("signedIn", status.signedIn)
                .put("email", status.email)
                .put("packageName", getContext().getPackageName());
    }

    private static void reject(PluginCall call, SyncAuthException error) {
        call.reject(error.getMessage(), error.code);
    }
}
