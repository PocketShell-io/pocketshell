package com.pocketshell.app;

import android.app.Activity;
import android.content.Context;
import java.util.function.Supplier;

/**
 * The gateway features' handle on the native Google sign-in (issue #3060):
 * the same Credential Manager sign-in provider, encrypted token store, sync
 * transport and configured API base GoogleSyncPlugin uses — through the same
 * {@link GoogleSyncEnvironment} test seams, so packaged journeys install fakes
 * exactly once and every gateway consumer follows them.
 *
 * <p>This is the ONLY place gateway code may touch the Google identity: the
 * stable account subject names the pairing namespace, and the dedicated
 * broker exchange mints the routing token. The ID token itself never crosses
 * this class's boundary — {@link #exchange()} answers with the broker's
 * response plus a bearer-echo check, and nothing else.
 */
class GatewaySyncSession {
    private final GoogleSyncSession session;

    GatewaySyncSession(Context context, Supplier<Activity> activity) {
        this(new GoogleSyncSession(
                new EncryptedSyncTokenStore(context),
                GoogleSyncEnvironment.signIn(activity),
                GoogleSyncEnvironment.transport(),
                GoogleSyncConfig.SYNC_API_URL,
                () -> System.currentTimeMillis() / 1000L));
    }

    GatewaySyncSession(GoogleSyncSession session) {
        this.session = session;
    }

    /** The signed-in account's stable subject, or NOT_SIGNED_IN. */
    String subject() throws SyncAuthException {
        return session.currentAccountSubject();
    }

    /** One dedicated POST /gateway/token broker exchange. */
    GoogleSyncSession.GatewayBrokerExchange exchange() throws SyncAuthException {
        return session.gatewayTokenExchange();
    }
}
