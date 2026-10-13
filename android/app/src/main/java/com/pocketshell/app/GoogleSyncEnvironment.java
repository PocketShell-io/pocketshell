package com.pocketshell.app;

import android.app.Activity;
import java.util.function.Supplier;

/**
 * Which Google sign-in and which network the sync plugin uses.
 *
 * <p>Production is Credential Manager with an automatic loopback-PKCE fallback
 * ({@link CompositeGoogleSignIn}), against the real sync API. Packaged
 * journeys run without a Google account or network, so they install a fake
 * sign-in provider and an in-process fake sync backend before the activity
 * starts. Everything between those two edges — encrypted token storage, the
 * plugin bridge, the WebView's encryption and core's sync round — is the
 * production code.
 */
public final class GoogleSyncEnvironment {
    /** Builds the sign-in provider for the activity hosting the plugin. */
    public interface SignInFactory {
        GoogleSyncSession.SignInProvider create(Supplier<Activity> activity);
    }

    private static volatile SignInFactory testSignIn;
    private static volatile GoogleSyncSession.Transport testTransport;

    private GoogleSyncEnvironment() {
    }

    /**
     * Replace Google and the sync API for a packaged journey. Both must be
     * supplied together so a journey can never reach the real backend with a
     * fake token, or real Google with a fake backend.
     */
    public static void installForTesting(SignInFactory signIn, GoogleSyncSession.Transport transport) {
        if (signIn == null || transport == null) throw new IllegalArgumentException("both test edges are required");
        testSignIn = signIn;
        testTransport = transport;
    }

    public static void resetForTesting() {
        testSignIn = null;
        testTransport = null;
    }

    static GoogleSyncSession.SignInProvider signIn(Supplier<Activity> activity) {
        SignInFactory factory = testSignIn;
        if (factory != null) return factory.create(activity);
        CredentialManagerSignIn credentialManager =
                new CredentialManagerSignIn(activity, GoogleSyncConfig.SERVER_CLIENT_ID);
        LoopbackPkceSignIn loopback = new LoopbackPkceSignIn(activity, GoogleSyncConfig.DESKTOP_CLIENT_ID,
                GoogleSyncConfig.SYNC_API_URL, transport(), new PkceRefreshTokenStore(() -> {
                    // The refresh store is provider-private state; the
                    // application context outlives any one activity.
                    Activity host = activity.get();
                    return host != null ? host.getApplicationContext() : null;
                }));
        return new CompositeGoogleSignIn(credentialManager, loopback);
    }

    static GoogleSyncSession.Transport transport() {
        GoogleSyncSession.Transport transport = testTransport;
        return transport != null ? transport : new HttpSyncTransport();
    }
}
