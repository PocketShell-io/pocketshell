package com.pocketshell.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * Fallback selection (issue #3020's missing Android OAuth client): Credential
 * Manager gets every first chance; only its "this package is not registered"
 * refusal (SIGN_IN_UNAVAILABLE) hands over to the loopback browser flow, and
 * silent renewal prefers whichever provider owns the sign-in.
 */
public final class CompositeGoogleSignInTest {
    private ScriptedSignIn credentialManager;
    private ScriptedRenewing loopback;
    private CompositeGoogleSignIn composite;

    @Before public void setUp() {
        credentialManager = new ScriptedSignIn();
        loopback = new ScriptedRenewing();
        composite = new CompositeGoogleSignIn(credentialManager, loopback);
    }

    @Test public void anInteractiveSignInTriesCredentialManagerFirst() throws Exception {
        credentialManager.tokens.add("CM-TOKEN");
        assertEquals("CM-TOKEN", composite.obtainIdToken(true));
        assertEquals(List.of(true), credentialManager.interactive);
        assertTrue(loopback.calls.isEmpty());
        assertEquals("a Credential Manager success clears the loopback renewal secret", 1, loopback.forgotten);
    }

    @Test public void anUnavailableCredentialManagerFallsBackToTheLoopbackBrowserFlow() throws Exception {
        credentialManager.failure = new SyncAuthException(SyncAuthException.UNAVAILABLE,
                "Google sign-in is not available for com.pocketshell.app.preview.");
        loopback.tokens.add("LOOPBACK-TOKEN");
        assertEquals("LOOPBACK-TOKEN", composite.obtainIdToken(true));
        assertEquals(List.of(true), loopback.interactive);
        assertEquals("no renewal secret is forgotten on the fallback path", 0, loopback.forgotten);
    }

    @Test public void aCancelledChooserNeverOpensABrowser() {
        credentialManager.failure = new SyncAuthException(SyncAuthException.CANCELLED, "Google sign-in was cancelled.");
        try {
            composite.obtainIdToken(true);
            fail("a cancellation must surface");
        } catch (SyncAuthException expected) {
            assertEquals(SyncAuthException.CANCELLED, expected.code);
        }
        assertTrue(loopback.calls.isEmpty());
    }

    @Test public void aPlainCredentialManagerFailureNeverOpensABrowser() {
        credentialManager.failure = new SyncAuthException(SyncAuthException.FAILED, "Google sign-in failed.");
        try {
            composite.obtainIdToken(true);
            fail("a plain failure must surface");
        } catch (SyncAuthException expected) {
            assertEquals(SyncAuthException.FAILED, expected.code);
        }
        assertTrue(loopback.calls.isEmpty());
    }

    @Test public void aSilentRenewalPrefersTheLoopbackSecretWhenItExists() throws Exception {
        loopback.canRenewAnswer = true;
        loopback.tokens.add("LOOPBACK-REFRESHED");
        assertEquals("LOOPBACK-REFRESHED", composite.obtainIdToken(false));
        assertEquals(List.of(false), loopback.interactive);
        assertTrue("Credential Manager is not asked while the loopback secret stands", credentialManager.calls.isEmpty());
    }

    @Test public void aSilentRenewalWithoutALoopbackSecretUsesCredentialManager() throws Exception {
        loopback.canRenewAnswer = false;
        credentialManager.tokens.add("CM-SILENT");
        assertEquals("CM-SILENT", composite.obtainIdToken(false));
        assertEquals(List.of(false), credentialManager.interactive);
        assertTrue(loopback.calls.isEmpty());
    }

    @Test public void anUnavailableSilentRenewalStillFallsBackOnce() throws Exception {
        loopback.canRenewAnswer = false;
        credentialManager.failure = new SyncAuthException(SyncAuthException.UNAVAILABLE, "not registered");
        loopback.failure = new SyncAuthException(SyncAuthException.UNAVAILABLE, "no refresh token");
        try {
            composite.obtainIdToken(false);
            fail("an unusable renewal must surface");
        } catch (SyncAuthException expected) {
            assertEquals(SyncAuthException.UNAVAILABLE, expected.code);
        }
        assertEquals(List.of(false), credentialManager.interactive);
        assertEquals(List.of(false), loopback.interactive);
    }

    @Test public void signOutClearsBothProviders() {
        composite.clearCredentialState();
        assertEquals(1, credentialManager.cleared);
        assertEquals(1, loopback.cleared);
    }

    /** Records every call; the same shape GoogleSyncSessionTest scripts. */
    private static final class ScriptedSignIn implements GoogleSyncSession.SignInProvider {
        final Deque<String> tokens = new ArrayDeque<>();
        final List<Boolean> interactive = new ArrayList<>();
        final List<Boolean> calls = new ArrayList<>();
        SyncAuthException failure;
        int cleared;

        @Override public String obtainIdToken(boolean interactiveRequest) throws SyncAuthException {
            interactive.add(interactiveRequest);
            calls.add(interactiveRequest);
            if (failure != null) throw failure;
            if (tokens.isEmpty()) throw new SyncAuthException(SyncAuthException.FAILED, "no scripted token");
            return tokens.removeFirst();
        }

        @Override public void clearCredentialState() {
            cleared += 1;
        }
    }

    private static final class ScriptedRenewing implements CompositeGoogleSignIn.Renewing {
        final Deque<String> tokens = new ArrayDeque<>();
        final List<Boolean> interactive = new ArrayList<>();
        final List<Boolean> calls = new ArrayList<>();
        SyncAuthException failure;
        boolean canRenewAnswer;
        int cleared;
        int forgotten;

        @Override public String obtainIdToken(boolean interactiveRequest) throws SyncAuthException {
            interactive.add(interactiveRequest);
            calls.add(interactiveRequest);
            if (failure != null) throw failure;
            if (tokens.isEmpty()) throw new SyncAuthException(SyncAuthException.FAILED, "no scripted token");
            return tokens.removeFirst();
        }

        @Override public boolean canRenew() {
            return canRenewAnswer;
        }

        @Override public void forgetRenewalSecret() {
            forgotten += 1;
        }

        @Override public void clearCredentialState() {
            cleared += 1;
        }
    }
}
