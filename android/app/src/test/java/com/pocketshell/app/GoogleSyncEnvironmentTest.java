package com.pocketshell.app;

import static org.junit.Assert.*;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.NoCredentialException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** The production auth boundary must use registered native Google sign-in only. */
public final class GoogleSyncEnvironmentTest {
    @Before public void resetBefore() { GoogleSyncEnvironment.resetForTesting(); }
    @After public void resetAfter() { GoogleSyncEnvironment.resetForTesting(); }

    @Test public void productionSignInUsesCredentialManagerDirectly() {
        assertEquals(CredentialManagerSignIn.class, GoogleSyncEnvironment.signIn(() -> null).getClass());
    }

    @Test public void packagedFakeEdgesRemainExplicitAndPaired() {
        GoogleSyncSession.SignInProvider fake = new GoogleSyncSession.SignInProvider() {
            @Override public String obtainIdToken(boolean interactive) { return "fixture"; }
            @Override public void clearCredentialState() { }
        };
        GoogleSyncEnvironment.installForTesting(activity -> fake,
                (method, url, headers, body) -> new GoogleSyncSession.Response(404, "{}"));
        assertSame(fake, GoogleSyncEnvironment.signIn(() -> null));
    }

    @Test public void nativeCancellationRemainsCancellationWithoutOpeningAnotherProvider() {
        SyncAuthException described = CredentialManagerSignIn.describe(
                new GetCredentialCancellationException("cancelled"), "com.pocketshell.app");
        assertEquals(SyncAuthException.CANCELLED, described.code);
    }

    @Test public void missingRegistrationNamesTheInstalledPackage() {
        SyncAuthException described = CredentialManagerSignIn.describe(
                new NoCredentialException("not set up"), "com.pocketshell.app");
        assertEquals(SyncAuthException.UNAVAILABLE, described.code);
        assertTrue(described.getMessage().contains("com.pocketshell.app"));
    }

    @Test public void backendAudienceStaysTheApprovedWebClient() {
        assertEquals("1035162854462-kkqius5o2ni136ed6l58iig5pdpeh4u6.apps.googleusercontent.com",
                GoogleSyncConfig.SERVER_CLIENT_ID);
    }
}
