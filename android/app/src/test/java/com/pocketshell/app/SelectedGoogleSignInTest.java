package com.pocketshell.app;

import static org.junit.Assert.*;
import org.junit.Test;

public final class SelectedGoogleSignInTest {
    static final class Provider implements GoogleSyncSession.SignInProvider {
        int calls;
        int clears;
        boolean interactive;
        SyncAuthException failure;
        @Override public String obtainIdToken(boolean value) throws SyncAuthException {
            calls++;
            interactive = value;
            if (failure != null) throw failure;
            return "fixture-token";
        }
        @Override public void clearCredentialState() { clears++; }
    }

    @Test public void explicitBrowserChoiceBypassesBrokenDeviceProvider() throws Exception {
        Provider device = new Provider();
        device.failure = new SyncAuthException(SyncAuthException.CANCELLED, "provider masks registration failure");
        Provider browser = new Provider();
        SelectedGoogleSignIn flow = new SelectedGoogleSignIn(device, browser,
                () -> SelectedGoogleSignIn.Method.BROWSER);
        assertEquals("fixture-token", flow.obtainIdToken(true));
        assertEquals(0, device.calls);
        assertEquals(1, browser.calls);
        assertTrue(browser.interactive);
    }

    @Test public void deviceCancellationNeverOpensBrowser() throws Exception {
        Provider device = new Provider();
        device.failure = new SyncAuthException(SyncAuthException.CANCELLED, "cancelled");
        Provider browser = new Provider();
        SelectedGoogleSignIn flow = new SelectedGoogleSignIn(device, browser,
                () -> SelectedGoogleSignIn.Method.DEVICE);
        try { flow.obtainIdToken(true); fail("must cancel"); }
        catch (SyncAuthException expected) { assertEquals(SyncAuthException.CANCELLED, expected.code); }
        assertEquals(0, browser.calls);
    }

    @Test public void dismissingMethodChoiceDoesNotStartEitherProvider() throws Exception {
        Provider device = new Provider();
        Provider browser = new Provider();
        SelectedGoogleSignIn flow = new SelectedGoogleSignIn(device, browser,
                () -> SelectedGoogleSignIn.Method.CANCEL);
        try { flow.obtainIdToken(true); fail("must cancel"); }
        catch (SyncAuthException expected) { assertEquals(SyncAuthException.CANCELLED, expected.code); }
        assertEquals(0, device.calls);
        assertEquals(0, browser.calls);
    }

    @Test public void silentRenewalNeverPromptsAndKeepsCompositeOwner() throws Exception {
        Provider automatic = new Provider();
        Provider browser = new Provider();
        SelectedGoogleSignIn flow = new SelectedGoogleSignIn(automatic, browser,
                () -> { throw new AssertionError("silent renewal opened chooser"); });
        flow.obtainIdToken(false);
        assertEquals(1, automatic.calls);
        assertFalse(automatic.interactive);
        assertEquals(0, browser.calls);
        flow.clearCredentialState();
        assertEquals(1, automatic.clears);
    }
}
