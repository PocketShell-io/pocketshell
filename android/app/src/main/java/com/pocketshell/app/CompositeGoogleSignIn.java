package com.pocketshell.app;

/**
 * Google sign-in that works before (and after) an Android-type OAuth client is
 * registered for this package: Credential Manager first — the proper "Sign in
 * with Google" once registration exists — and the loopback-PKCE browser flow
 * as the automatic fallback for exactly the refusal registration is missing
 * ({@code SIGN_IN_UNAVAILABLE}: no registered Android client, no usable
 * account, the 28444 class). Other failures pass through untouched: a cancelled
 * chooser must not open a browser, and a genuine fault must stay visible.
 *
 * <p>Which provider signed in decides silent renewals: while the loopback
 * sign-in holds a refresh token it renews through the relay; otherwise renewal
 * is Credential Manager's silent path. An interactive Credential Manager
 * success clears any stale loopback secret so the two owners never interleave.
 */
final class CompositeGoogleSignIn implements GoogleSyncSession.SignInProvider {

    /** A sign-in provider that can also say whether a silent renewal is possible. */
    interface Renewing extends GoogleSyncSession.SignInProvider {
        /** Whether {@link #obtainIdToken(boolean)} non-interactive can work right now. */
        boolean canRenew();

        /** Drop the renewal secret without revoking it: another provider owns sign-in now. */
        void forgetRenewalSecret();
    }

    private final GoogleSyncSession.SignInProvider credentialManager;
    private final Renewing loopback;

    CompositeGoogleSignIn(GoogleSyncSession.SignInProvider credentialManager, Renewing loopback) {
        this.credentialManager = credentialManager;
        this.loopback = loopback;
    }

    @Override
    public String obtainIdToken(boolean interactive) throws SyncAuthException {
        if (!interactive && loopback.canRenew()) {
            return loopback.obtainIdToken(false);
        }
        try {
            String token = credentialManager.obtainIdToken(interactive);
            if (interactive) loopback.forgetRenewalSecret();
            return token;
        } catch (SyncAuthException error) {
            if (!SyncAuthException.UNAVAILABLE.equals(error.code)) throw error;
            return loopback.obtainIdToken(interactive);
        }
    }

    @Override
    public void clearCredentialState() {
        credentialManager.clearCredentialState();
        loopback.clearCredentialState();
    }
}
