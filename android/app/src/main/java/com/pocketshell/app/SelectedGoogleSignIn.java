package com.pocketshell.app;

/** Explicit interactive provider selection; silent renewal keeps its existing owner. */
final class SelectedGoogleSignIn implements GoogleSyncSession.SignInProvider {
    enum Method { DEVICE, BROWSER, CANCEL }

    interface Selector {
        Method choose() throws SyncAuthException;
    }

    private final GoogleSyncSession.SignInProvider automatic;
    private final GoogleSyncSession.SignInProvider browser;
    private final Selector selector;

    SelectedGoogleSignIn(GoogleSyncSession.SignInProvider automatic,
            GoogleSyncSession.SignInProvider browser, Selector selector) {
        this.automatic = automatic;
        this.browser = browser;
        this.selector = selector;
    }

    @Override public String obtainIdToken(boolean interactive) throws SyncAuthException {
        if (!interactive) return automatic.obtainIdToken(false);
        Method method = selector.choose();
        if (method == Method.CANCEL) {
            throw new SyncAuthException(SyncAuthException.CANCELLED, "Google sign-in was cancelled.");
        }
        return (method == Method.BROWSER ? browser : automatic).obtainIdToken(true);
    }

    @Override public void clearCredentialState() {
        // The composite owns clearing both providers, including the browser.
        automatic.clearCredentialState();
    }
}
